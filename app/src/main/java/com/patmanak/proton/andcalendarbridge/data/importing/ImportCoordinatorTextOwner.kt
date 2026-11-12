package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemTextLocalOwner
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemTextEditProfile
import com.patmanak.proton.andcalendarbridge.data.outgoing.TextLocalOwnerSnapshot
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.*

/** Local text gateway; all operations retain the shared, revocable owner guard. */
internal class ImportCoordinatorTextOwner(
    private val store: ImportStore,
    private val remote: ReadOnlyCalendarRemote,
    private val projection: CalendarProjection,
    private val editAccess: ImportCoordinatorEditAccess,
    private val systemTextsEnabled: (CalendarScope) -> Boolean,
    private val systemTextEvidenceAdmission: (CalendarScope) -> ((CalendarScope) -> Boolean),
    private val handoffSource: suspend (CapturedCreateHandoffFrame, () -> Boolean) -> Boolean,
) {
    private suspend fun <T> withScopedEditOwner(target: CalendarScope, admit: (CalendarScope) -> Boolean,
        consent: Boolean, denied: T, finalAccessRequired: Boolean = true,
        sendingEnabled: (CalendarScope) -> Boolean,
        action: suspend (ImportCoordinatorEditAccess.Access) -> T): T =
        editAccess.withScopedEditOwner(target, admit, consent, denied, finalAccessRequired, sendingEnabled, action)

    /** Local-only gateway: callbacks can run while the Proton session mutex is already held. */
    val owner: SystemTextLocalOwner = object : SystemTextLocalOwner {
        override suspend fun observe(scope: CalendarScope, event: EventId, owner: MutationId,
            admit: (CalendarScope) -> Boolean): TextLocalOwnerSnapshot? =
            withScopedEditOwner(scope, admit, consent = true, sendingEnabled = systemTextsEnabled, denied = null) { access ->
                val intent = access.read { store.localEdit(scope, event) } ?: return@withScopedEditOwner null
                if (intent.id != owner || !SystemTextEditProfile.accepts(intent)) return@withScopedEditOwner null
                val frozen = LocalTextEditPreparation.freezeIntent(intent)
                val preparation = access.read { store.localTextEditPreparation(scope, event) }
                if (!textSource(frozen, preparation, access)) return@withScopedEditOwner null
                TextLocalOwnerSnapshot(frozen, preparation)
            }

        override suspend fun retain(preparation: LocalTextEditPreparation,
            admit: (CalendarScope) -> Boolean): LocalTextEditPreparation? {
            currentCoroutineContext().ensureActive()
            val frozen = freezeTextPreparation(preparation)
            return withScopedEditOwner(frozen.intent.scope, admit, consent = true, sendingEnabled = systemTextsEnabled, denied = null) { access ->
                val retained = access.read { store.localTextEditPreparation(frozen.intent.scope, frozen.intent.base.eventId) }
                if (retained != null && !sameTextPreparation(retained, frozen)) return@withScopedEditOwner null
                if (!textSource(frozen.intent, retained, access)) return@withScopedEditOwner null
                val outcome = access.read { store.retainCurrentLocalTextPreparation(frozen) }
                if (outcome !in listOf(LocalTextPreparationAdmission.STORED, LocalTextPreparationAdmission.HISTORICAL))
                    return@withScopedEditOwner null
                val durable = access.read { store.localTextEditPreparation(frozen.intent.scope, frozen.intent.base.eventId) }
                if (!sameTextPreparation(durable, frozen) || !textSource(frozen.intent, frozen, access))
                    return@withScopedEditOwner null
                freezeTextPreparation(requireNotNull(durable))
            }
        }

        override suspend fun qualify(preparation: LocalTextEditPreparation, admit: (CalendarScope) -> Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            val frozen = freezeTextPreparation(preparation)
            return withScopedEditOwner(frozen.intent.scope, admit, consent = true, sendingEnabled = systemTextsEnabled, denied = false) { access ->
                textSource(frozen.intent, frozen, access)
            }
        }

        override suspend fun submit(record: TextUpdateAttemptRecord, journal: TextUpdateRecoveryJournal,
            port: CalendarTextUpdateSubmissionPort, admit: (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation {
            val operation = currentCoroutineContext()
            operation.ensureActive()
            val frozen = record.frozen()
            val target = frozen.scope
            val accountEvidence = systemTextEvidenceAdmission(target)
            val lease = EditSubmissionLease(target, operation, accountEvidence, admitEvidence)
            val evidence = lease.evidence
            try {
                if (frozen.state != TextUpdateAttemptState.PREPARED || frozen.protectedEvidence != null ||
                    port.textUpdateFormat != frozen.format ||
                    frozen.id != TextUpdateAttemptIdentity.requestId(frozen.preparation, frozen.format))
                    return TextUpdateSubmissionObservation.Unknown
                return withScopedEditOwner(target, admit, consent = true, sendingEnabled = systemTextsEnabled, denied = TextUpdateSubmissionObservation.Unknown,
                    finalAccessRequired = false) { access ->
                    val sending = lease.sending(access::active) { port.textUpdateFormat == frozen.format }
                    suspend fun source(requested: CalendarScope): Boolean = lease.source(requested, sending) {
                        textSource(frozen.preparation.intent, frozen.preparation, access)
                    }
                    if (!sending(target) || access.read { journal.read(target, frozen.id, sending) } != frozen ||
                        !source(target)) return@withScopedEditOwner TextUpdateSubmissionObservation.Unknown
                    // Lock order is import then authenticated adapter. The callback never retakes the import lock.
                    val observation = port.submit(frozen, journal, sending, ::source, evidence)
                    operation.ensureActive()
                    if (observation !is TextUpdateSubmissionObservation.Verified || !evidence(target) ||
                        observation.record.copy(state = TextUpdateAttemptState.PREPARED, protectedEvidence = null) != frozen ||
                        observation.record.protectedEvidence == null) return@withScopedEditOwner TextUpdateSubmissionObservation.Unknown
                    // Response facts survive editing-policy revocation. No ACK authority is derived here.
                    val durable = journal.read(target, frozen.id, evidence)
                    operation.ensureActive()
                    if (!evidence(target) || durable == null || durable != observation.record)
                        return@withScopedEditOwner TextUpdateSubmissionObservation.Unknown
                    TextUpdateSubmissionObservation.Verified(durable, observation.event)
                }
            } finally {
                lease.close()
            }
        }

        override suspend fun acknowledge(result: TextUpdateVerifiedResult, journal: TextUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            val frozen = result.snapshot()
            return withScopedEditOwner(frozen.record.scope, admit, consent = false, sendingEnabled = systemTextsEnabled, denied = false) { access ->
                acknowledgeText(frozen, journal, access)
            }
        }

        override suspend fun complete(result: TextUpdateVerifiedResult, journal: TextUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            val frozen = result.snapshot()
            return withScopedEditOwner(frozen.record.scope, admit, consent = false, sendingEnabled = systemTextsEnabled, denied = false) { access ->
                completeText(frozen, journal, access)
            }
        }
    }

    private fun freezeTextPreparation(value: LocalTextEditPreparation) =
        LocalTextEditPreparation(value.intent, value.timestamp, value.format, value.protectedPayload)

    private fun sameTextPreparation(left: LocalTextEditPreparation?, right: LocalTextEditPreparation?): Boolean =
        if (left == null || right == null) left == null && right == null else
            left.intent == right.intent && left.timestamp == right.timestamp && left.format == right.format &&
                left.protectedPayload == right.protectedPayload

    /** Exact SQL and native source, including complete immutable origin for a handed-off CREATE. */
    private suspend fun textSource(intent: LocalEditIntent, preparation: LocalTextEditPreparation?,
        access: ImportCoordinatorEditAccess.Access): Boolean {
        val target = access.target
        val event = intent.base.eventId
        if (intent.scope != target || !SystemTextEditProfile.accepts(intent) || intent.format != remote.format) return false
        suspend fun sql(): Boolean {
            if (remote.format != intent.format) return false
            if (access.read { store.localEdit(target, event) } != intent) return false
            val eligibility = access.read { store.assessLocalTextPreparation(intent) }
            if (eligibility != if (preparation == null) LocalTextPreparationAdmission.ELIGIBLE
                else LocalTextPreparationAdmission.HISTORICAL) return false
            return sameTextPreparation(access.read { store.localTextEditPreparation(target, event) }, preparation) &&
                remote.format == intent.format
        }
        return editAccess.currentEditSource(intent, access, ::sql)
    }

    private suspend fun currentTextResult(result: TextUpdateVerifiedResult, journal: TextUpdateRecoveryJournal,
        access: ImportCoordinatorEditAccess.Access): Boolean {
        val record = result.record
        if (record.scope != access.target || record.state != TextUpdateAttemptState.OUTCOME_UNKNOWN ||
            record.preparation.intent.format != remote.format ||
            access.read { store.transaction(access.target) { checkpoint?.format } } != record.preparation.intent.format)
            return false
        val durable = access.read { journal.read(access.target, record.id, access.admit) } ?: return false
        return durable.copy(protectedEvidence = null) == record &&
            access.read { journal.readVerified(access.target, record.id, access.admit) } == result &&
            access.read { store.transaction(access.target) { checkpoint?.format } } == record.preparation.intent.format &&
            remote.format == record.preparation.intent.format
    }

    private suspend fun acknowledgeText(result: TextUpdateVerifiedResult, journal: TextUpdateRecoveryJournal,
        access: ImportCoordinatorEditAccess.Access): Boolean {
        if (projection.textUpdateAcknowledgementProtocol != 1 || !currentTextResult(result, journal, access)) return false
        val target = access.target
        val attempt = result.record.id
        val intent = result.record.preparation.intent
        val event = result.event.eventId
        val existing = access.read { store.textUpdateAcknowledgement(target, event, attempt) }
        if (existing != null && !existing.receipt.matches(result)) return false
        if (existing?.state == TextUpdateAcknowledgementState.RESTORED)
            return currentTextResult(result, journal, access)
        val reservation = if (existing != null) TextUpdateAcknowledgementReservation(existing, false) else {
            val role = access.read { store.capturedHandoff(target, event) }
            if (!editAccess.currentEditNativeSource(intent, role, access) || !currentTextResult(result, journal, access)) return false
            access.read { store.prepareTextUpdateAcknowledgement(result, role?.frame) } ?: return false
        }
        val retained = reservation.record
        if (!retained.receipt.matches(result) ||
            access.read { store.textUpdateAcknowledgement(target, event, attempt) } != retained ||
            !currentTextResult(result, journal, access)) return false
        if (reservation.fresh) {
            val owner = access.read { store.localEdit(target, event) }
            val echoedOwner = owner?.state == LocalEditState.CONFLICT && owner.copy(state = LocalEditState.PAUSED) == intent
            if ((owner != intent && !echoedOwner) || !sameTextPreparation(
                    access.read { store.localTextEditPreparation(target, event) }, result.record.preparation)) return false
            if (!editAccess.currentEditAcknowledgementRole(intent, retained.receipt.handoff, access)) return false
        }
        val historicalFrame = retained.receipt.handoff
        if (historicalFrame != null && !access.read { handoffSource(historicalFrame, access::active) }) return false
        // A retained pending receipt only inspects. An interrupted batch is never replayed.
        val witness = access.read {
            if (reservation.fresh) projection.acknowledgeTextUpdate(retained.receipt)
            else projection.inspectTextUpdateAcknowledgement(retained.receipt)
        } ?: return false
        if (witness.receipt.snapshot() != retained.receipt || !currentTextResult(result, journal, access) ||
            access.read { store.textUpdateAcknowledgement(target, event, attempt) } != retained) return false
        if (historicalFrame != null && !access.read { handoffSource(historicalFrame, access::active) }) return false
        if (!access.read { store.restoreTextUpdateAcknowledgement(witness) }) return false
        return access.read { store.textUpdateAcknowledgement(target, event, attempt) } ==
            TextUpdateAcknowledgementRecord(retained.receipt, TextUpdateAcknowledgementState.RESTORED) &&
            currentTextResult(result, journal, access)
    }

    private suspend fun completeText(result: TextUpdateVerifiedResult, journal: TextUpdateRecoveryJournal,
        access: ImportCoordinatorEditAccess.Access): Boolean {
        if (journal.completionProtocol != 1 || !currentTextResult(result, journal, access)) return false
        val target = access.target
        val event = result.event.eventId
        val attempt = result.record.id
        val acknowledgement = access.read { store.textUpdateAcknowledgement(target, event, attempt) } ?: return false
        if (acknowledgement.state != TextUpdateAcknowledgementState.RESTORED || !acknowledgement.receipt.matches(result)) return false
        val completion = TextUpdateCompletion.of(result, acknowledgement)
        val retained = access.read { journal.readCompletion(target, attempt, access.admit) }
        if (retained != null && retained != completion) return false
        if (!currentTextResult(result, journal, access) ||
            access.read { store.textUpdateAcknowledgement(target, event, attempt) } != acknowledgement) return false
        if (retained == null && !access.read { journal.retainCompletion(completion, access.admit) }) return false
        return access.read { journal.readCompletion(target, attempt, access.admit) } == completion &&
            access.read { store.textUpdateAcknowledgement(target, event, attempt) } == acknowledgement &&
            currentTextResult(result, journal, access)
    }

}
