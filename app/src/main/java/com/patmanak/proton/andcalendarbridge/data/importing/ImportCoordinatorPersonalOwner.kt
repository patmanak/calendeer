package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemPersonalLocalOwner
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemPersonalEditProfile
import com.patmanak.proton.andcalendarbridge.data.outgoing.PersonalLocalOwnerSnapshot
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.*

/** Personal-edit gateway with exact current-owner and historical acknowledgement checks. */
internal class ImportCoordinatorPersonalOwner(
    private val store: ImportStore,
    private val remote: ReadOnlyCalendarRemote,
    private val projection: CalendarProjection,
    private val editAccess: ImportCoordinatorEditAccess,
    private val personalSendingEnabled: (CalendarScope) -> Boolean,
    private val personalConsent: (LocalEditIntent) -> Boolean,
    private val systemPersonalEvidenceAdmission: (CalendarScope) -> ((CalendarScope) -> Boolean),
    private val handoffSource: suspend (CapturedCreateHandoffFrame, () -> Boolean) -> Boolean,
) {
    /** Local-only gateway: callbacks can run while the Proton session mutex is already held. */
    val owner: SystemPersonalLocalOwner = object : SystemPersonalLocalOwner {
        override suspend fun observe(scope: CalendarScope, event: EventId, owner: MutationId,
            admit: (CalendarScope) -> Boolean): PersonalLocalOwnerSnapshot? =
            withScopedPersonalOwner(scope, admit, consent = true, denied = null) { access ->
                val intent = access.read { store.localEdit(scope, event) } ?: return@withScopedPersonalOwner null
                if (intent.id != owner || !SystemPersonalEditProfile.accepts(intent)) return@withScopedPersonalOwner null
                val frozen = SystemPersonalEditProfile.freeze(intent)
                val preparation = access.read { store.personalEditPreparation(scope, event) }
                if (!personalSource(frozen, preparation, access)) return@withScopedPersonalOwner null
                PersonalLocalOwnerSnapshot(frozen, preparation)
            }

        override suspend fun retain(preparation: PersonalEditPreparation,
            admit: (CalendarScope) -> Boolean): PersonalEditPreparation? {
            currentCoroutineContext().ensureActive()
            val frozen = freezePersonalPreparation(preparation)
            return withScopedPersonalOwner(frozen.intent.scope, admit, consent = true, denied = null) { access ->
                val retained = access.read { store.personalEditPreparation(frozen.intent.scope, frozen.intent.base.eventId) }
                if (retained != null && !samePersonalPreparation(retained, frozen)) return@withScopedPersonalOwner null
                if (!personalSource(frozen.intent, retained, access)) return@withScopedPersonalOwner null
                val outcome = access.read { store.retainCurrentPersonalPreparation(frozen) }
                if (outcome !in listOf(PersonalPreparationAdmission.STORED, PersonalPreparationAdmission.HISTORICAL))
                    return@withScopedPersonalOwner null
                val durable = access.read { store.personalEditPreparation(frozen.intent.scope, frozen.intent.base.eventId) }
                if (!samePersonalPreparation(durable, frozen) || !personalSource(frozen.intent, frozen, access))
                    return@withScopedPersonalOwner null
                freezePersonalPreparation(requireNotNull(durable))
            }
        }

        override suspend fun qualify(preparation: PersonalEditPreparation, admit: (CalendarScope) -> Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            val frozen = freezePersonalPreparation(preparation)
            return withScopedPersonalOwner(frozen.intent.scope, admit, consent = true, denied = false) { access ->
                personalSource(frozen.intent, frozen, access)
            }
        }

        override suspend fun submit(record: PersonalUpdateAttemptRecord, journal: PersonalUpdateRecoveryJournal,
            port: CalendarPersonalUpdatePort, admit: (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): PersonalUpdateSubmissionObservation {
            val operation = currentCoroutineContext()
            operation.ensureActive()
            val frozen = record.frozen()
            val target = frozen.scope
            val accountEvidence = systemPersonalEvidenceAdmission(target)
            val lease = EditSubmissionLease(target, operation, accountEvidence, admitEvidence)
            val evidence = lease.evidence
            fun currentFormat() = if (frozen.preparation.isSeries) port.seriesUpdateFormat else port.personalUpdateFormat
            try {
                if (frozen.state != PersonalUpdateAttemptState.PREPARED || frozen.protectedEvidence != null ||
                    currentFormat() != frozen.format ||
                    frozen.id != PersonalUpdateAttemptIdentity.requestId(frozen.preparation, frozen.format))
                    return PersonalUpdateSubmissionObservation.Unknown
                return withScopedPersonalOwner(target, admit, consent = true, denied = PersonalUpdateSubmissionObservation.Unknown,
                    finalAccessRequired = false) { access ->
                    val sending = lease.sending(access::active) { currentFormat() == frozen.format }
                    suspend fun source(requested: CalendarScope): Boolean = lease.source(requested, sending) {
                        personalSource(frozen.preparation.intent, frozen.preparation, access)
                    }
                    if (!sending(target) || access.read { journal.read(target, frozen.id, sending) } != frozen ||
                        !source(target)) return@withScopedPersonalOwner PersonalUpdateSubmissionObservation.Unknown
                    // Lock order is import then authenticated adapter. The callback never retakes the import lock.
                    val observation = port.submit(frozen, journal, sending, ::source, evidence)
                    operation.ensureActive()
                    if (observation !is PersonalUpdateSubmissionObservation.Verified || !evidence(target) ||
                        observation.record.copy(state = PersonalUpdateAttemptState.PREPARED, protectedEvidence = null) != frozen ||
                        observation.record.protectedEvidence == null) return@withScopedPersonalOwner PersonalUpdateSubmissionObservation.Unknown
                    // Response facts survive editing-policy revocation. No ACK authority is derived here.
                    val durable = journal.read(target, frozen.id, evidence)
                    operation.ensureActive()
                    if (!evidence(target) || durable == null || durable != observation.record)
                        return@withScopedPersonalOwner PersonalUpdateSubmissionObservation.Unknown
                    PersonalUpdateSubmissionObservation.Verified(durable, observation.event)
                }
            } finally {
                lease.close()
            }
        }

        override suspend fun acknowledge(result: PersonalUpdateVerifiedResult, journal: PersonalUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            val frozen = result.snapshot()
            return withScopedPersonalOwner(frozen.record.scope, admit, consent = false, denied = false) { access ->
                acknowledgePersonal(frozen, journal, access)
            }
        }

        override suspend fun complete(result: PersonalUpdateVerifiedResult, journal: PersonalUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            val frozen = result.snapshot()
            return withScopedPersonalOwner(frozen.record.scope, admit, consent = false, denied = false) { access ->
                completePersonal(frozen, journal, access)
            }
        }
    }

    private suspend fun <T> withScopedPersonalOwner(target: CalendarScope, admit: (CalendarScope) -> Boolean,
        consent: Boolean, denied: T, finalAccessRequired: Boolean = true,
        action: suspend (ImportCoordinatorEditAccess.Access) -> T): T =
        editAccess.withScopedEditOwner(target, admit, consent, denied, finalAccessRequired, personalSendingEnabled, action)

    private fun freezePersonalPreparation(value: PersonalEditPreparation) = value.frozen()

    private fun samePersonalPreparation(left: PersonalEditPreparation?, right: PersonalEditPreparation?): Boolean =
        if (left == null || right == null) left == null && right == null else
            left.intent == right.intent && left.timestamp == right.timestamp && left.format == right.format && left.isSeries == right.isSeries &&
                left.protectedPayload == right.protectedPayload

    /** Exact SQL and native source, including complete immutable origin for a handed-off CREATE. */
    private suspend fun personalSource(intent: LocalEditIntent, preparation: PersonalEditPreparation?,
        access: ImportCoordinatorEditAccess.Access): Boolean {
        val target = access.target
        val event = intent.base.eventId
        if (intent.scope != target || !SystemPersonalEditProfile.accepts(intent) || !personalConsent(intent) || intent.format != remote.format) return false
        suspend fun sql(): Boolean {
            if (remote.format != intent.format) return false
            if (access.read { store.localEdit(target, event) } != intent) return false
            val eligibility = access.read { store.assessPersonalPreparation(intent) }
            if (eligibility != if (preparation == null) PersonalPreparationAdmission.ELIGIBLE
                else PersonalPreparationAdmission.HISTORICAL) return false
            return samePersonalPreparation(access.read { store.personalEditPreparation(target, event) }, preparation) &&
                remote.format == intent.format
        }
        return editAccess.currentEditSource(intent, access, ::sql, personal = true)
    }

    private suspend fun currentPersonalResult(result: PersonalUpdateVerifiedResult, journal: PersonalUpdateRecoveryJournal,
        access: ImportCoordinatorEditAccess.Access): Boolean {
        val record = result.record
        if (record.scope != access.target || record.state != PersonalUpdateAttemptState.OUTCOME_UNKNOWN ||
            record.preparation.intent.format != remote.format ||
            access.read { store.transaction(access.target) { checkpoint?.format } } != record.preparation.intent.format)
            return false
        val durable = access.read { journal.read(access.target, record.id, access.admit) } ?: return false
        return durable.copy(protectedEvidence = null) == record &&
            access.read { journal.readVerified(access.target, record.id, access.admit) } == result &&
            access.read { store.transaction(access.target) { checkpoint?.format } } == record.preparation.intent.format &&
            remote.format == record.preparation.intent.format
    }

    private suspend fun acknowledgePersonal(result: PersonalUpdateVerifiedResult, journal: PersonalUpdateRecoveryJournal,
        access: ImportCoordinatorEditAccess.Access): Boolean {
        if (projection.personalUpdateAcknowledgementProtocol != 1 || !currentPersonalResult(result, journal, access)) return false
        val target = access.target
        val attempt = result.record.id
        val intent = result.record.preparation.intent
        val event = result.event.eventId
        val existing = access.read { store.personalUpdateAcknowledgement(target, event, attempt) }
        if (existing != null && !existing.receipt.matches(result)) return false
        if (existing?.state == PersonalUpdateAcknowledgementState.RESTORED)
            return currentPersonalResult(result, journal, access)
        val reservation = if (existing != null) PersonalUpdateAcknowledgementReservation(existing, false) else {
            val role = access.read { store.capturedHandoff(target, event) }
            if (!editAccess.currentEditNativeSource(intent, role, access, personal = true) || !currentPersonalResult(result, journal, access)) return false
            access.read { store.preparePersonalUpdateAcknowledgement(result, role?.frame) } ?: return false
        }
        val retained = reservation.record
        if (!retained.receipt.matches(result) ||
            access.read { store.personalUpdateAcknowledgement(target, event, attempt) } != retained ||
            !currentPersonalResult(result, journal, access)) return false
        if (reservation.fresh) {
            val owner = access.read { store.localEdit(target, event) }
            val echoedOwner = owner?.state == LocalEditState.CONFLICT && owner.copy(state = LocalEditState.PAUSED) == intent
            if ((owner != intent && !echoedOwner) || !samePersonalPreparation(
                    access.read { store.personalEditPreparation(target, event) }, result.record.preparation)) return false
            if (!editAccess.currentEditAcknowledgementRole(intent, retained.receipt.handoff, access)) return false
        }
        val historicalFrame = retained.receipt.handoff
        if (historicalFrame != null && !access.read { handoffSource(historicalFrame, access::active) }) return false
        val witness = recoverPersonalAcknowledgement(reservation.fresh,
            projection.personalUpdateAcknowledgementRecoveryProtocol,
            inspect = { access.read { projection.inspectPersonalUpdateAcknowledgement(retained.receipt) } },
            qualifyCurrentOwner = {
                val owner = access.read { store.localEdit(target, event) }
                val echoedOwner = owner?.state == LocalEditState.CONFLICT && owner.copy(state = LocalEditState.PAUSED) == intent
                val role = access.read { store.capturedHandoff(target, event) }
                (owner == intent || echoedOwner) &&
                    samePersonalPreparation(access.read { store.personalEditPreparation(target, event) }, result.record.preparation) &&
                    editAccess.currentEditAcknowledgementRole(intent, retained.receipt.handoff, access) &&
                    role?.frame == retained.receipt.handoff && editAccess.currentEditNativeSource(intent, role, access, personal = true) &&
                    currentPersonalResult(result, journal, access) &&
                    access.read { store.personalUpdateAcknowledgement(target, event, attempt) } == retained
            },
            applyFresh = { access.read { projection.acknowledgePersonalUpdate(retained.receipt) } },
            resume = { access.read { projection.resumePersonalUpdateAcknowledgement(retained.receipt) } },
        ) ?: return false
        if (witness.receipt.snapshot() != retained.receipt || !currentPersonalResult(result, journal, access) ||
            access.read { store.personalUpdateAcknowledgement(target, event, attempt) } != retained) return false
        if (historicalFrame != null && !access.read { handoffSource(historicalFrame, access::active) }) return false
        if (!access.read { store.restorePersonalUpdateAcknowledgement(witness) }) return false
        return access.read { store.personalUpdateAcknowledgement(target, event, attempt) } ==
            PersonalUpdateAcknowledgementRecord(retained.receipt, PersonalUpdateAcknowledgementState.RESTORED) &&
            currentPersonalResult(result, journal, access)
    }

    private suspend fun completePersonal(result: PersonalUpdateVerifiedResult, journal: PersonalUpdateRecoveryJournal,
        access: ImportCoordinatorEditAccess.Access): Boolean {
        if (journal.completionProtocol != 1 || !currentPersonalResult(result, journal, access)) return false
        val target = access.target
        val event = result.event.eventId
        val attempt = result.record.id
        val acknowledgement = access.read { store.personalUpdateAcknowledgement(target, event, attempt) } ?: return false
        if (acknowledgement.state != PersonalUpdateAcknowledgementState.RESTORED || !acknowledgement.receipt.matches(result)) return false
        val completion = PersonalUpdateCompletion.of(result, acknowledgement)
        val retained = access.read { journal.readCompletion(target, attempt, access.admit) }
        if (retained != null && retained != completion) return false
        if (!currentPersonalResult(result, journal, access) ||
            access.read { store.personalUpdateAcknowledgement(target, event, attempt) } != acknowledgement) return false
        if (retained == null && !access.read { journal.retainCompletion(completion, access.admit) }) return false
        return access.read { journal.readCompletion(target, attempt, access.admit) } == completion &&
            access.read { store.personalUpdateAcknowledgement(target, event, attempt) } == acknowledgement &&
            currentPersonalResult(result, journal, access)
    }

}
