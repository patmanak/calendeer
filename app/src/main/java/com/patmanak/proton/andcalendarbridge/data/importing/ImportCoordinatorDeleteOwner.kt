package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemDeleteLocalOwner
import java.util.concurrent.atomic.AtomicBoolean

/** Local deletion gateway and exact retained acknowledgement reconciliation. */
internal class ImportCoordinatorDeleteOwner(
    private val store: ImportStore,
    private val remote: ReadOnlyCalendarRemote,
    private val projection: CalendarProjection,
    private val editAccess: ImportCoordinatorEditAccess,
    private val systemDeletesEnabled: (CalendarScope) -> Boolean,
    private val systemDeleteEvidenceAdmission: (CalendarScope) -> ((CalendarScope) -> Boolean),
    private val systemDeleteProofAdmission: (CalendarScope) -> ((CalendarScope) -> Boolean),
    private val capturedRawHold: suspend (CalendarScope) -> CapturedProjectionHold,
    private val refreshState: suspend () -> Unit,
) {
    private suspend fun <T> withScopedEditOwner(target: CalendarScope, admit: (CalendarScope) -> Boolean,
        consent: Boolean, denied: T, finalAccessRequired: Boolean = true,
        sendingEnabled: (CalendarScope) -> Boolean,
        action: suspend (ImportCoordinatorEditAccess.Access) -> T): T =
        editAccess.withScopedEditOwner(target, admit, consent, denied, finalAccessRequired, sendingEnabled, action)

    val owner: SystemDeleteLocalOwner = object : SystemDeleteLocalOwner {
        override suspend fun observe(scope: CalendarScope, event: EventId, owner: MutationId,
            admit: (CalendarScope) -> Boolean): LocalDeleteIntent? =
            withScopedEditOwner(scope, admit, true, null, sendingEnabled = systemDeletesEnabled) { access ->
                val intent = access.read { store.localDelete(scope, event) } ?: return@withScopedEditOwner null
                if (intent.id != owner || !deleteSource(intent, access)) return@withScopedEditOwner null
                DeleteSubmissionPolicy.freezeIntent(intent)
            }

        override suspend fun reserve(record: DeleteAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean =
            withScopedEditOwner(record.scope, admit, true, false, sendingEnabled = systemDeletesEnabled) { access ->
                if (record.state != DeleteAttemptState.PREPARED || record.protectedEvidence != null ||
                    record.id != DeleteAttemptIdentity.requestId(record.intent, record.format) ||
                    !deleteSource(record.intent, access)) return@withScopedEditOwner false
                val reservation = access.read { store.prepareDeleteAcknowledgement(record) } ?: return@withScopedEditOwner false
                reservation.record.receipt.attempt == DeleteAttemptIdentity.of(record) && deleteSource(record.intent, access)
            }

        override suspend fun submit(record: DeleteAttemptRecord, journal: DeleteRecoveryJournal,
            port: CalendarDeleteSubmissionPort, admit: (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): DeleteSubmissionObservation {
            val frozen = record.frozen()
            val operation = currentCoroutineContext()
            val factual = systemDeleteEvidenceAdmission(frozen.scope)
            val open = AtomicBoolean(true)
            val sourceOpen = AtomicBoolean(true)
            val evidenceOpen = AtomicBoolean(true)
            val evidence: (CalendarScope) -> Boolean = { requested ->
                operation.ensureActive()
                val allowed = open.get() && evidenceOpen.get() && requested == frozen.scope && factual(requested) && admitEvidence(requested)
                if (!allowed) evidenceOpen.set(false)
                allowed && open.get() && evidenceOpen.get()
            }
            try {
                if (frozen.state != DeleteAttemptState.PREPARED || frozen.protectedEvidence != null ||
                    port.deleteFormat != frozen.format || frozen.id != DeleteAttemptIdentity.requestId(frozen.intent, frozen.format))
                    return DeleteSubmissionObservation.Unknown
                return withScopedEditOwner(frozen.scope, admit, true, DeleteSubmissionObservation.Unknown,
                    finalAccessRequired = false, sendingEnabled = systemDeletesEnabled) { access ->
                    val sending: (CalendarScope) -> Boolean = { requested ->
                        operation.ensureActive()
                        val valid = open.get() && sourceOpen.get() && requested == frozen.scope && access.active() && port.deleteFormat == frozen.format
                        if (!valid) sourceOpen.set(false)
                        valid && open.get() && sourceOpen.get()
                    }
                    suspend fun source(requested: CalendarScope): Boolean = try {
                        val valid = sending(requested) && deleteSource(frozen.intent, access) && sending(requested)
                        if (!valid) sourceOpen.set(false)
                        valid
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { operation.ensureActive(); sourceOpen.set(false); false }
                    val reservation = access.read { store.deleteAcknowledgement(frozen.scope, frozen.eventId, frozen.id) }
                    if (reservation?.receipt?.attempt != DeleteAttemptIdentity.of(frozen) ||
                        access.read { journal.read(frozen.scope, frozen.id, sending) } != frozen || !source(frozen.scope))
                        return@withScopedEditOwner DeleteSubmissionObservation.Unknown
                    // Import -> auth lock order. Source callbacks never retake either lock.
                    val observation = port.submit(frozen, journal, sending, ::source, evidence)
                    operation.ensureActive()
                    when (observation) {
                        DeleteSubmissionObservation.Unknown -> observation
                        is DeleteSubmissionObservation.Conflict -> if (sending(frozen.scope) &&
                            observation.record.copy(state = DeleteAttemptState.PREPARED, protectedEvidence = null) == frozen)
                            observation else DeleteSubmissionObservation.Unknown
                        is DeleteSubmissionObservation.Verified -> {
                            if (!evidence(frozen.scope) ||
                                observation.record.copy(state = DeleteAttemptState.PREPARED, protectedEvidence = null) != frozen)
                                return@withScopedEditOwner DeleteSubmissionObservation.Unknown
                            val durable = journal.read(frozen.scope, frozen.id, evidence)
                            operation.ensureActive()
                            if (durable == null || durable != observation.record || !evidence(frozen.scope))
                                DeleteSubmissionObservation.Unknown else DeleteSubmissionObservation.Verified(durable)
                        }
                    }
                }
            } finally { open.set(false); sourceOpen.set(false); evidenceOpen.set(false) }
        }

        override suspend fun conflict(intent: LocalDeleteIntent, observed: RemoteChange.Upsert,
            admit: (CalendarScope) -> Boolean): Boolean =
            withScopedEditOwner(intent.scope, admit, false, false, sendingEnabled = systemDeletesEnabled) { access ->
                access.read { store.conflictLocalDelete(intent, observed) }.also { refreshState() }
            }

        override suspend fun acknowledge(result: DeleteVerifiedResult, journal: DeleteRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean =
            withScopedEditOwner(result.record.scope, admit, false, false, sendingEnabled = systemDeletesEnabled) { access ->
                acknowledgeDelete(result.snapshot(), journal, access)
            }

        override suspend fun complete(result: DeleteVerifiedResult, journal: DeleteRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean =
            withScopedEditOwner(result.record.scope, admit, false, false, sendingEnabled = systemDeletesEnabled) { access ->
                if (journal.completionProtocol != 1 || !currentDeleteResult(result, journal, access)) return@withScopedEditOwner false
                val ack = access.read { store.deleteAcknowledgement(result.record.scope, result.record.eventId, result.record.id) }
                    ?: return@withScopedEditOwner false
                if (ack.state != DeleteAcknowledgementState.RESTORED || !ack.receipt.matches(DeleteSubmissionObservation.Verified(result.record)))
                    return@withScopedEditOwner false
                val completion = DeleteCompletion.of(result, ack)
                val prior = access.read { journal.readCompletion(result.record.scope, result.record.id, access.admit) }
                if (prior != null && prior != completion) return@withScopedEditOwner false
                if (prior == null && !access.read { journal.retainCompletion(completion, access.admit) }) return@withScopedEditOwner false
                access.read { journal.readCompletion(result.record.scope, result.record.id, access.admit) } == completion &&
                    currentDeleteResult(result, journal, access)
            }
    }

    private suspend fun deleteSource(intent: LocalDeleteIntent, access: ImportCoordinatorEditAccess.Access): Boolean {
        if (intent.scope != access.target || intent.format != remote.format || intent.state != LocalEditState.PAUSED) return false
        DeleteSubmissionPolicy.freezeIntent(intent)
        suspend fun sql(): Boolean = remote.format == intent.format && access.read { store.qualifyLocalDelete(intent) } &&
            access.read { store.capturedHandoff(intent.scope, intent.base.eventId) } == null
        if (!sql()) return false
        val hold = access.read { capturedRawHold(intent.scope) }
        if (hold.scopeBlocked || hold.holds(intent.base.eventId)) return false
        val native = access.read { projection.captureOwnedSimpleDelete(intent.scope, intent.base) }
        if (native?.content != intent.content) return false
        val after = access.read { capturedRawHold(intent.scope) }
        return !after.scopeBlocked && !after.holds(intent.base.eventId) && sql()
    }

    private suspend fun currentDeleteResult(result: DeleteVerifiedResult, journal: DeleteRecoveryJournal,
        access: ImportCoordinatorEditAccess.Access): Boolean {
        val record = result.record
        if (record.scope != access.target || record.state != DeleteAttemptState.OUTCOME_UNKNOWN ||
            record.intent.format != remote.format ||
            access.read { store.transaction(record.scope) { checkpoint?.format } } != record.intent.format) return false
        val durable = access.read { journal.read(record.scope, record.id, access.admit) } ?: return false
        return durable.copy(protectedEvidence = null) == record &&
            access.read { journal.readVerified(record.scope, record.id, access.admit) } == result &&
            remote.format == record.intent.format
    }

    private suspend fun acknowledgeDelete(result: DeleteVerifiedResult, journal: DeleteRecoveryJournal,
        access: ImportCoordinatorEditAccess.Access): Boolean {
        if (projection.deleteAcknowledgementProtocol != 1 || !currentDeleteResult(result, journal, access)) return false
        val record = result.record
        val ack = access.read { store.deleteAcknowledgement(record.scope, record.eventId, record.id) } ?: return false
        if (!ack.receipt.matches(DeleteSubmissionObservation.Verified(record))) return false
        if (ack.state == DeleteAcknowledgementState.RESTORED) return currentDeleteResult(result, journal, access)
        if (!access.read { store.qualifyLocalDelete(record.intent) }) return false
        val witness = access.read { projection.inspectDeleteAcknowledgement(ack.receipt) } ?: run {
            if (!deleteSource(record.intent, access)) return false
            access.read { projection.acknowledgeDelete(ack.receipt) }
        } ?: return false
        if (witness.receipt != ack.receipt || !currentDeleteResult(result, journal, access) ||
            access.read { store.deleteAcknowledgement(record.scope, record.eventId, record.id) } != ack) return false
        return access.read { store.restoreDeleteAcknowledgement(witness) }.also { if (it) refreshState() }
    }

    /** A retained ACK explains only its exact marker. Native restoration/new edits become visible conflicts. */
    suspend fun reconcileReservedDeleteChange(target: CalendarScope, event: EventId, deleted: Boolean, dirty: Boolean): Boolean =
        withScopedEditOwner(target, systemDeleteProofAdmission(target), false, true,
            sendingEnabled = systemDeletesEnabled) { access ->
            val ack = access.read { store.deleteAcknowledgement(target, event) } ?: return@withScopedEditOwner false
            suspend fun conflict(): Boolean {
                access.read { store.conflictAcknowledgedDelete(ack.receipt.intent) }
                refreshState()
                return true
            }
            if (!deleted || (ack.state == DeleteAcknowledgementState.RESTORED && dirty)) return@withScopedEditOwner conflict()
            if (!dirty) {
                if (access.read { projection.inspectDeleteAcknowledgement(ack.receipt) }?.receipt != ack.receipt)
                    conflict()
                return@withScopedEditOwner true
            }
            val current = access.read { projection.captureOwnedSimpleDelete(target, ack.receipt.intent.base) }
            if (current?.content != ack.receipt.intent.content) conflict() else false
        }

}
