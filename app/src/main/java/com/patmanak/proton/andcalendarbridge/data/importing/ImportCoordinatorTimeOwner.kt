package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemTimeLocalOwner
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemTimeEditProfile
import com.patmanak.proton.andcalendarbridge.data.outgoing.TimeLocalOwnerSnapshot

/** Local time gateway under the coordinator's shared owner guard. */
internal class ImportCoordinatorTimeOwner(
    private val store: ImportStore,
    private val remote: ReadOnlyCalendarRemote,
    private val projection: CalendarProjection,
    private val editAccess: ImportCoordinatorEditAccess,
    private val systemTimesEnabled: (CalendarScope) -> Boolean,
    private val systemTimeEvidenceAdmission: (CalendarScope) -> ((CalendarScope) -> Boolean),
    private val handoffSource: suspend (CapturedCreateHandoffFrame, () -> Boolean) -> Boolean,
) {
    private suspend fun <T> withScopedEditOwner(target: CalendarScope, admit: (CalendarScope) -> Boolean,
        consent: Boolean, denied: T, finalAccessRequired: Boolean = true,
        action: suspend (ImportCoordinatorEditAccess.Access) -> T): T =
        editAccess.withScopedEditOwner(target, admit, consent, denied, finalAccessRequired, systemTimesEnabled, action)

    /** Local-only gateway: callbacks can run while the Proton session mutex is already held. */
    val owner: SystemTimeLocalOwner = object : SystemTimeLocalOwner {
        override suspend fun observe(scope: CalendarScope, event: EventId, owner: MutationId,
            admit: (CalendarScope) -> Boolean): TimeLocalOwnerSnapshot? =
            withScopedEditOwner(scope, admit, consent = true, denied = null) { access ->
                val intent = access.read { store.localEdit(scope, event) } ?: return@withScopedEditOwner null
                if (intent.id != owner || !SystemTimeEditProfile.accepts(intent)) return@withScopedEditOwner null
                val frozen = LocalTimeEditCandidate(intent).intent
                val preparation = access.read { store.localTimeEditPreparation(scope, event) }
                if (!timeSource(frozen, preparation, access)) return@withScopedEditOwner null
                TimeLocalOwnerSnapshot(frozen, preparation)
            }

        override suspend fun retain(preparation: LocalTimeEditPreparation,
            admit: (CalendarScope) -> Boolean): LocalTimeEditPreparation? {
            currentCoroutineContext().ensureActive()
            val frozen = freezeTimePreparation(preparation)
            return withScopedEditOwner(frozen.intent.scope, admit, consent = true, denied = null) { access ->
                val retained = access.read { store.localTimeEditPreparation(frozen.intent.scope, frozen.intent.base.eventId) }
                if (retained != null && !sameTimePreparation(retained, frozen)) return@withScopedEditOwner null
                if (!timeSource(frozen.intent, retained, access)) return@withScopedEditOwner null
                val outcome = access.read { store.retainCurrentLocalTimePreparation(frozen) }
                if (outcome !in listOf(LocalTimePreparationAdmission.STORED, LocalTimePreparationAdmission.HISTORICAL))
                    return@withScopedEditOwner null
                val durable = access.read { store.localTimeEditPreparation(frozen.intent.scope, frozen.intent.base.eventId) }
                if (!sameTimePreparation(durable, frozen) || !timeSource(frozen.intent, frozen, access))
                    return@withScopedEditOwner null
                freezeTimePreparation(requireNotNull(durable))
            }
        }

        override suspend fun qualify(preparation: LocalTimeEditPreparation, admit: (CalendarScope) -> Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            val frozen = freezeTimePreparation(preparation)
            return withScopedEditOwner(frozen.intent.scope, admit, consent = true, denied = false) { access ->
                timeSource(frozen.intent, frozen, access)
            }
        }

        override suspend fun submit(record: TimeUpdateAttemptRecord, journal: TimeUpdateRecoveryJournal,
            port: CalendarTimeUpdateSubmissionPort, admit: (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): TimeUpdateSubmissionObservation {
            val operation = currentCoroutineContext()
            operation.ensureActive()
            val frozen = record.frozen()
            val target = frozen.scope
            val accountEvidence = systemTimeEvidenceAdmission(target)
            val lease = EditSubmissionLease(target, operation, accountEvidence, admitEvidence)
            val evidence = lease.evidence
            try {
                if (frozen.state != TimeUpdateAttemptState.PREPARED || frozen.protectedEvidence != null ||
                    port.timeUpdateFormat != frozen.format ||
                    frozen.id != TimeUpdateAttemptIdentity.requestId(frozen.preparation, frozen.format))
                    return TimeUpdateSubmissionObservation.Unknown
                return withScopedEditOwner(target, admit, consent = true, denied = TimeUpdateSubmissionObservation.Unknown,
                    finalAccessRequired = false) { access ->
                    val sending = lease.sending(access::active) { port.timeUpdateFormat == frozen.format }
                    suspend fun source(requested: CalendarScope): Boolean = lease.source(requested, sending) {
                        timeSource(frozen.preparation.intent, frozen.preparation, access)
                    }
                    if (!sending(target) || access.read { journal.read(target, frozen.id, sending) } != frozen ||
                        !source(target)) return@withScopedEditOwner TimeUpdateSubmissionObservation.Unknown
                    // Lock order is import then authenticated adapter. The callback never retakes the import lock.
                    val observation = port.submit(frozen, journal, sending, ::source, evidence)
                    operation.ensureActive()
                    if (observation !is TimeUpdateSubmissionObservation.Verified || !evidence(target) ||
                        observation.record.copy(state = TimeUpdateAttemptState.PREPARED, protectedEvidence = null) != frozen ||
                        observation.record.protectedEvidence == null) return@withScopedEditOwner TimeUpdateSubmissionObservation.Unknown
                    // Response facts survive editing-policy revocation. No ACK authority is derived here.
                    val durable = journal.read(target, frozen.id, evidence)
                    operation.ensureActive()
                    if (!evidence(target) || durable == null || durable != observation.record)
                        return@withScopedEditOwner TimeUpdateSubmissionObservation.Unknown
                    TimeUpdateSubmissionObservation.Verified(durable, observation.event)
                }
            } finally {
                lease.close()
            }
        }

        override suspend fun acknowledge(result: TimeUpdateVerifiedResult, journal: TimeUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            val frozen = result.snapshot()
            return withScopedEditOwner(frozen.record.scope, admit, consent = false, denied = false) { access ->
                acknowledgeTime(frozen, journal, access)
            }
        }

        override suspend fun complete(result: TimeUpdateVerifiedResult, journal: TimeUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            val frozen = result.snapshot()
            return withScopedEditOwner(frozen.record.scope, admit, consent = false, denied = false) { access ->
                completeTime(frozen, journal, access)
            }
        }
    }

    private fun freezeTimePreparation(value: LocalTimeEditPreparation) =
        LocalTimeEditPreparation(value.intent, value.timestamp, value.format, value.protectedPayload)

    private fun sameTimePreparation(left: LocalTimeEditPreparation?, right: LocalTimeEditPreparation?): Boolean =
        if (left == null || right == null) left == null && right == null else
            left.intent == right.intent && left.timestamp == right.timestamp && left.format == right.format &&
                left.protectedPayload == right.protectedPayload

    /** Exact SQL and native source, including complete immutable origin for a handed-off CREATE. */
    private suspend fun timeSource(intent: LocalEditIntent, preparation: LocalTimeEditPreparation?,
        access: ImportCoordinatorEditAccess.Access): Boolean {
        val target = access.target
        val event = intent.base.eventId
        if (intent.scope != target || !SystemTimeEditProfile.accepts(intent) || intent.format != remote.format) return false
        suspend fun sql(): Boolean {
            if (remote.format != intent.format) return false
            if (access.read { store.localEdit(target, event) } != intent) return false
            val eligibility = access.read { store.assessLocalTimePreparation(intent) }
            if (eligibility != if (preparation == null) LocalTimePreparationAdmission.ELIGIBLE
                else LocalTimePreparationAdmission.HISTORICAL) return false
            return sameTimePreparation(access.read { store.localTimeEditPreparation(target, event) }, preparation) &&
                remote.format == intent.format
        }
        return editAccess.currentEditSource(intent, access, ::sql)
    }

    private suspend fun currentTimeResult(result: TimeUpdateVerifiedResult, journal: TimeUpdateRecoveryJournal,
        access: ImportCoordinatorEditAccess.Access): Boolean {
        val record = result.record
        if (record.scope != access.target || record.state != TimeUpdateAttemptState.OUTCOME_UNKNOWN ||
            record.preparation.intent.format != remote.format ||
            access.read { store.transaction(access.target) { checkpoint?.format } } != record.preparation.intent.format)
            return false
        val durable = access.read { journal.read(access.target, record.id, access.admit) } ?: return false
        return durable.copy(protectedEvidence = null) == record &&
            access.read { journal.readVerified(access.target, record.id, access.admit) } == result &&
            access.read { store.transaction(access.target) { checkpoint?.format } } == record.preparation.intent.format &&
            remote.format == record.preparation.intent.format
    }

    private suspend fun acknowledgeTime(result: TimeUpdateVerifiedResult, journal: TimeUpdateRecoveryJournal,
        access: ImportCoordinatorEditAccess.Access): Boolean {
        if (projection.timeUpdateAcknowledgementProtocol != 1 || !currentTimeResult(result, journal, access)) return false
        val target = access.target
        val attempt = result.record.id
        val intent = result.record.preparation.intent
        val event = result.event.eventId
        val existing = access.read { store.timeUpdateAcknowledgement(target, event, attempt) }
        if (existing != null && !existing.receipt.matches(result)) return false
        if (existing?.state == TimeUpdateAcknowledgementState.RESTORED)
            return currentTimeResult(result, journal, access)
        val reservation = if (existing != null) TimeUpdateAcknowledgementReservation(existing, false) else {
            val role = access.read { store.capturedHandoff(target, event) }
            if (!editAccess.currentEditNativeSource(intent, role, access) || !currentTimeResult(result, journal, access)) return false
            access.read { store.prepareTimeUpdateAcknowledgement(result, role?.frame) } ?: return false
        }
        val retained = reservation.record
        if (!retained.receipt.matches(result) ||
            access.read { store.timeUpdateAcknowledgement(target, event, attempt) } != retained ||
            !currentTimeResult(result, journal, access)) return false
        if (reservation.fresh) {
            val owner = access.read { store.localEdit(target, event) }
            val echoedOwner = owner?.state == LocalEditState.CONFLICT && owner.copy(state = LocalEditState.PAUSED) == intent
            if ((owner != intent && !echoedOwner) || !sameTimePreparation(
                    access.read { store.localTimeEditPreparation(target, event) }, result.record.preparation)) return false
            if (!editAccess.currentEditAcknowledgementRole(intent, retained.receipt.handoff, access)) return false
        }
        val historicalFrame = retained.receipt.handoff
        if (historicalFrame != null && !access.read { handoffSource(historicalFrame, access::active) }) return false
        // A retained pending receipt only inspects. An interrupted batch is never replayed.
        val witness = access.read {
            if (reservation.fresh) projection.acknowledgeTimeUpdate(retained.receipt)
            else projection.inspectTimeUpdateAcknowledgement(retained.receipt)
        } ?: return false
        if (witness.receipt.snapshot() != retained.receipt || !currentTimeResult(result, journal, access) ||
            access.read { store.timeUpdateAcknowledgement(target, event, attempt) } != retained) return false
        if (historicalFrame != null && !access.read { handoffSource(historicalFrame, access::active) }) return false
        if (!access.read { store.restoreTimeUpdateAcknowledgement(witness) }) return false
        return access.read { store.timeUpdateAcknowledgement(target, event, attempt) } ==
            TimeUpdateAcknowledgementRecord(retained.receipt, TimeUpdateAcknowledgementState.RESTORED) &&
            currentTimeResult(result, journal, access)
    }

    private suspend fun completeTime(result: TimeUpdateVerifiedResult, journal: TimeUpdateRecoveryJournal,
        access: ImportCoordinatorEditAccess.Access): Boolean {
        if (journal.completionProtocol != 1 || !currentTimeResult(result, journal, access)) return false
        val target = access.target
        val event = result.event.eventId
        val attempt = result.record.id
        val acknowledgement = access.read { store.timeUpdateAcknowledgement(target, event, attempt) } ?: return false
        if (acknowledgement.state != TimeUpdateAcknowledgementState.RESTORED || !acknowledgement.receipt.matches(result)) return false
        val completion = TimeUpdateCompletion.of(result, acknowledgement)
        val retained = access.read { journal.readCompletion(target, attempt, access.admit) }
        if (retained != null && retained != completion) return false
        if (!currentTimeResult(result, journal, access) ||
            access.read { store.timeUpdateAcknowledgement(target, event, attempt) } != acknowledgement) return false
        if (retained == null && !access.read { journal.retainCompletion(completion, access.admit) }) return false
        return access.read { journal.readCompletion(target, attempt, access.admit) } == completion &&
            access.read { store.timeUpdateAcknowledgement(target, event, attempt) } == acknowledgement &&
            currentTimeResult(result, journal, access)
    }

}
