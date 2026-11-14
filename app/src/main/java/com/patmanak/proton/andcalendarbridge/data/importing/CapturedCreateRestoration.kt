package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Restoration of one captured CREATE under the coordinator's existing import mutex and epoch.
 * Each call keeps its own admission cleanup. Binding may inspect an UNKNOWN outcome; no method
 * submits a request or releases the incoming projection hold. Only a fresh reservation permits
 * a native ACK write; retained receipts are inspected. */
internal class CapturedCreateRestoration(
    private val target: CalendarScope,
    private val store: ImportStore,
    private val projection: CalendarProjection,
    private val captures: LocalCreateStore,
    private val journal: CreateAttemptStore,
    private val currentFormat: () -> AdapterFormat,
    private val active: () -> Boolean,
    private val currentAccount: suspend () -> Boolean,
    private val expectations: CalendarCreateSubmissionPort? = null,
) {
    private suspend fun available(): Boolean {
        if (!currentAccount() || !active()) return false
        val selected = store.selections(target.account).singleOrNull { it.scope == target }
        return selected?.enabled == true && selected.available && active()
    }

    suspend fun bind(localId: LocalEventId, id: MutationId, attempt: MutationId,
        port: CalendarCreateSubmissionPort?): CapturedCreateBindingResult {
        return try {
            if (!available()) return CapturedCreateBindingResult.INACTIVE
            store.admitTransaction = { it == target && active() }
            captures.admitCapture = { it == target && active() }
            journal.admitAssociation = { it == target && active() }
            val submission = captures.readSubmission(target, localId) ?: return CapturedCreateBindingResult.UNRESOLVED
            val reserved = submission.reservation
            val captured = reserved.intent.reservation
            if (captured.scope != target || captured.localId != localId || captured.id != id || reserved.attempt != attempt ||
                captured.format != currentFormat() || store.transaction(target) { checkpoint?.format } != captured.format)
                return CapturedCreateBindingResult.UNRESOLVED
            val identity = CapturedCreateDispatchIdentity.of(submission)
            val unknown = journal.read(target, attempt) ?: return CapturedCreateBindingResult.UNRESOLVED
            if (unknown.state != CreateAttemptState.OUTCOME_UNKNOWN || journal.capturedIdentity(target, attempt) != identity)
                return CapturedCreateBindingResult.UNRESOLVED
            val cached = journal.capturedBinding(identity)
            if (cached == null) {
                val inspections = port ?: return CapturedCreateBindingResult.UNRESOLVED
                if (!inspections.supportsCapturedSubmission(reserved.format)) return CapturedCreateBindingResult.UNRESOLVED
                val observed = inspections.inspectCaptured(unknown, reserved.draft) as? CreateSubmissionObservation.Verified
                    ?: return CapturedCreateBindingResult.UNRESOLVED
                val canonical = capturedCreateExpectation(submission, inspections) ?: return CapturedCreateBindingResult.UNRESOLVED
                if (!available()) return CapturedCreateBindingResult.INACTIVE
                if (observed.attempt != identity.attempt || observed.event.content != canonical ||
                    !inspections.supportsCapturedSubmission(reserved.format) || currentFormat() != captured.format ||
                    store.transaction(target) { checkpoint?.format } != captured.format ||
                    captures.readSubmission(target, localId) != submission || journal.capturedIdentity(target, attempt) != identity)
                    return CapturedCreateBindingResult.UNRESOLVED
                journal.bindCaptured(unknown, identity, observed)
            }
            if (!available()) return CapturedCreateBindingResult.INACTIVE
            if (currentFormat() != captured.format || store.transaction(target) { checkpoint?.format } != captured.format ||
                captures.readSubmission(target, localId) != submission || journal.capturedBinding(identity) == null)
                return CapturedCreateBindingResult.UNRESOLVED
            CapturedCreateBindingResult.RETAINED
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { CapturedCreateBindingResult.UNRESOLVED }
          finally {
              journal.admitAssociation = { false }
              captures.admitCapture = { false }
              store.admitTransaction = { false }
          }
    }

    suspend fun attach(localId: LocalEventId, id: MutationId, attempt: MutationId): CapturedCreateAttachmentResult {
        return try {
            if (!available()) return CapturedCreateAttachmentResult.INACTIVE
            store.admitTransaction = { it == target && active() }
            captures.admitCapture = { it == target && active() }
            journal.admitAssociation = { it == target && active() }
            projection.admitOperation = { active() }
            val submission = captures.readSubmission(target, localId) ?: return CapturedCreateAttachmentResult.UNRESOLVED
            val reserved = submission.reservation
            val capture = reserved.intent.reservation
            if (capture.scope != target || capture.localId != localId || capture.id != id || reserved.attempt != attempt ||
                capture.format != currentFormat() || store.transaction(target) { checkpoint?.format } != capture.format ||
                projection.capturedAttachmentProtocol != 1) return CapturedCreateAttachmentResult.UNRESOLVED
            val identity = CapturedCreateDispatchIdentity.of(submission)
            val binding = journal.capturedBinding(identity) ?: return CapturedCreateAttachmentResult.UNRESOLVED
            // The durable nonce precedes any Provider change. No network or submission port is used.
            val receipt = journal.prepareCapturedAttachment(binding)
            suspend fun exact(): Boolean = captures.readSubmission(target, localId) == submission &&
                currentFormat() == capture.format && projection.capturedAttachmentProtocol == receipt.protocol &&
                store.transaction(target) { checkpoint?.format } == capture.format &&
                journal.capturedBinding(identity) == binding && journal.capturedAttachment(binding) == receipt
            if (!available()) return CapturedCreateAttachmentResult.INACTIVE
            if (!exact()) return CapturedCreateAttachmentResult.UNRESOLVED
            var witness = projection.inspectCapturedAttachment(receipt)
            if (witness == null) {
                if (!available()) return CapturedCreateAttachmentResult.INACTIVE
                if (!exact()) return CapturedCreateAttachmentResult.UNRESOLVED
                witness = projection.attachCapturedCreate(receipt)
            }
            if (!available()) return CapturedCreateAttachmentResult.INACTIVE
            if (witness?.receipt != receipt || !exact()) return CapturedCreateAttachmentResult.UNRESOLVED
            // Historical metadata proof is deliberately insufficient to ACK a later local edit.
            CapturedCreateAttachmentResult.ATTACHED
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!active()) CapturedCreateAttachmentResult.INACTIVE else throw cancelled
        } catch (_: Exception) {
            if (!active()) CapturedCreateAttachmentResult.INACTIVE else CapturedCreateAttachmentResult.UNRESOLVED
        } finally {
            journal.admitAssociation = { false }
            captures.admitCapture = { false }
            store.admitTransaction = { false }
            projection.admitOperation = { false }
        }
    }

    suspend fun retainBaseline(localId: LocalEventId, id: MutationId, attempt: MutationId): CapturedCreateBaselineResult {
        return try {
            if (!available()) return CapturedCreateBaselineResult.INACTIVE
            store.admitTransaction = { it == target && active() }
            captures.admitCapture = { it == target && active() }
            projection.admitOperation = { active() }
            val submission = captures.readSubmission(target, localId) ?: return CapturedCreateBaselineResult.UNRESOLVED
            val reserved = submission.reservation
            val capture = reserved.intent.reservation
            if (capture.scope != target || capture.localId != localId || capture.id != id || reserved.attempt != attempt ||
                capture.format != currentFormat() || store.transaction(target) { checkpoint?.format } != capture.format ||
                projection.capturedAttachmentProtocol != 1) return CapturedCreateBaselineResult.UNRESOLVED
            val identity = CapturedCreateDispatchIdentity.of(submission)
            val binding = journal.capturedBinding(identity) ?: return CapturedCreateBaselineResult.UNRESOLVED
            // This path only reads an existing durable attachment plan and its current Provider proof.
            val receipt = journal.capturedAttachment(binding) ?: return CapturedCreateBaselineResult.UNRESOLVED
            val canonical = capturedCreateExpectation(submission, expectations) ?: return CapturedCreateBaselineResult.UNRESOLVED
            val baseline = CapturedCreateBaseline(receipt,
                RemoteChange.Upsert(binding.eventId, binding.verifiedRevision, canonical))
            suspend fun exact(): Boolean = captures.readSubmission(target, localId) == submission &&
                currentFormat() == capture.format && projection.capturedAttachmentProtocol == receipt.protocol &&
                store.transaction(target) { checkpoint?.format } == capture.format &&
                journal.capturedBinding(identity) == binding && journal.capturedAttachment(binding) == receipt
            if (!available()) return CapturedCreateBaselineResult.INACTIVE
            if (!exact()) return CapturedCreateBaselineResult.UNRESOLVED
            val witness = projection.inspectCapturedAttachment(receipt)
            if (!available()) return CapturedCreateBaselineResult.INACTIVE
            if (witness?.receipt != receipt || !exact()) return CapturedCreateBaselineResult.UNRESOLVED
            if (!store.retainCapturedBaseline(baseline, witness)) return CapturedCreateBaselineResult.UNRESOLVED
            if (!available()) return CapturedCreateBaselineResult.INACTIVE
            if (!exact() || store.capturedBaseline(target, binding.eventId) != baseline)
                return CapturedCreateBaselineResult.UNRESOLVED
            CapturedCreateBaselineResult.RETAINED
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!active()) CapturedCreateBaselineResult.INACTIVE else throw cancelled
        } catch (_: Exception) {
            if (!active()) CapturedCreateBaselineResult.INACTIVE else CapturedCreateBaselineResult.UNRESOLVED
        } finally {
            captures.admitCapture = { false }
            store.admitTransaction = { false }
            projection.admitOperation = { false }
        }
    }

    suspend fun acknowledge(localId: LocalEventId, id: MutationId, attempt: MutationId): CapturedCreateAcknowledgementResult {
        return try {
            if (!available()) return CapturedCreateAcknowledgementResult.INACTIVE
            store.admitTransaction = { it == target && active() }
            captures.admitCapture = { it == target && active() }
            projection.admitOperation = { active() }
            val submission = captures.readSubmission(target, localId) ?: return CapturedCreateAcknowledgementResult.UNRESOLVED
            val reserved = submission.reservation
            val capture = reserved.intent.reservation
            if (capture.scope != target || capture.localId != localId || capture.id != id || reserved.attempt != attempt ||
                capture.format != currentFormat() || store.transaction(target) { checkpoint?.format } != capture.format ||
                projection.capturedAttachmentProtocol != 1 || projection.capturedAcknowledgementProtocol != 1)
                return CapturedCreateAcknowledgementResult.UNRESOLVED
            val identity = CapturedCreateDispatchIdentity.of(submission)
            val binding = journal.capturedBinding(identity) ?: return CapturedCreateAcknowledgementResult.UNRESOLVED
            val attachment = journal.capturedAttachment(binding) ?: return CapturedCreateAcknowledgementResult.UNRESOLVED
            val canonical = capturedCreateExpectation(submission, expectations) ?: return CapturedCreateAcknowledgementResult.UNRESOLVED
            val baseline = CapturedCreateBaseline(attachment,
                RemoteChange.Upsert(binding.eventId, binding.verifiedRevision, canonical))
            suspend fun exactSource(): Boolean = captures.readSubmission(target, localId) == submission &&
                currentFormat() == capture.format && projection.capturedAttachmentProtocol == attachment.protocol &&
                projection.capturedAcknowledgementProtocol == 1 &&
                store.transaction(target) { checkpoint?.format } == capture.format &&
                journal.capturedBinding(identity) == binding && journal.capturedAttachment(binding) == attachment &&
                store.capturedBaseline(target, binding.eventId) == baseline
            if (!available()) return CapturedCreateAcknowledgementResult.INACTIVE
            if (!exactSource()) return CapturedCreateAcknowledgementResult.UNRESOLVED
            var record = store.capturedAcknowledgement(target, binding.eventId)
            if (record != null && record.receipt.baseline != baseline) return CapturedCreateAcknowledgementResult.UNRESOLVED
            var fresh = false
            if (record == null) {
                val observed = projection.captureCapturedCreateCurrent(baseline)
                if (!available()) return CapturedCreateAcknowledgementResult.INACTIVE
                if (!exactSource()) return CapturedCreateAcknowledgementResult.UNRESOLVED
                if (observed == CapturedCreateCurrentObservation.Unsupported) return CapturedCreateAcknowledgementResult.UNSUPPORTED
                val snapshot = (observed as? CapturedCreateCurrentObservation.Supported)?.snapshot
                    ?: return CapturedCreateAcknowledgementResult.UNRESOLVED
                if (snapshot.baseline != baseline || snapshot.acknowledgement != null || !snapshot.unchanged) return CapturedCreateAcknowledgementResult.UNRESOLVED
                val reservation = store.reserveCapturedAcknowledgement(CapturedCreateAcknowledgementReceipt(baseline, java.util.UUID.randomUUID().toString()))
                record = reservation.record
                fresh = reservation.fresh
            }
            val admitted = checkNotNull(record)
            if (!available()) return CapturedCreateAcknowledgementResult.INACTIVE
            if (!exactSource() || store.capturedAcknowledgement(target, binding.eventId) != admitted)
                return CapturedCreateAcknowledgementResult.UNRESOLVED
            // Only this invocation's fresh reservation may call the write port. All gaps and
            // reopened outcomes inspect the original immutable nonce; none can rearm the batch.
            val proof = if (fresh) projection.acknowledgeCapturedCreate(admitted.receipt)
                else projection.inspectCapturedAcknowledgement(admitted.receipt)
            if (!available()) return CapturedCreateAcknowledgementResult.INACTIVE
            if (proof?.receipt != admitted.receipt || !exactSource() ||
                store.capturedAcknowledgement(target, binding.eventId) != admitted)
                return CapturedCreateAcknowledgementResult.UNRESOLVED
            val proven = if (admitted.state == CapturedCreateAcknowledgementState.PROVEN) admitted
                else store.retainCapturedAcknowledgement(proof)
            if (!available()) return CapturedCreateAcknowledgementResult.INACTIVE
            if (!exactSource() || store.capturedAcknowledgement(target, binding.eventId) != proven)
                return CapturedCreateAcknowledgementResult.UNRESOLVED
            CapturedCreateAcknowledgementResult.ACKNOWLEDGED
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!active()) CapturedCreateAcknowledgementResult.INACTIVE else throw cancelled
        } catch (_: Exception) {
            if (!active()) CapturedCreateAcknowledgementResult.INACTIVE else CapturedCreateAcknowledgementResult.UNRESOLVED
        } finally {
            captures.admitCapture = { false }
            store.admitTransaction = { false }
            projection.admitOperation = { false }
        }
    }

    suspend fun qualifyClean(localId: LocalEventId, id: MutationId, attempt: MutationId): CapturedCreateCleanResult {
        return try {
            if (!available()) return CapturedCreateCleanResult.INACTIVE
            store.admitTransaction = { it == target && active() }
            captures.admitCapture = { it == target && active() }
            projection.admitOperation = { active() }
            val submission = captures.readSubmission(target, localId) ?: return CapturedCreateCleanResult.UNRESOLVED
            val reserved = submission.reservation
            val capture = reserved.intent.reservation
            if (capture.scope != target || capture.localId != localId || capture.id != id || reserved.attempt != attempt ||
                capture.format != currentFormat() || store.transaction(target) { checkpoint?.format } != capture.format ||
                projection.capturedAttachmentProtocol != 1 || projection.capturedAcknowledgementProtocol != 1)
                return CapturedCreateCleanResult.UNRESOLVED
            val identity = CapturedCreateDispatchIdentity.of(submission)
            val binding = journal.capturedBinding(identity) ?: return CapturedCreateCleanResult.UNRESOLVED
            val attachment = journal.capturedAttachment(binding) ?: return CapturedCreateCleanResult.UNRESOLVED
            val canonical = capturedCreateExpectation(submission, expectations) ?: return CapturedCreateCleanResult.UNRESOLVED
            val baseline = CapturedCreateBaseline(attachment,
                RemoteChange.Upsert(binding.eventId, binding.verifiedRevision, canonical))
            val record = store.capturedAcknowledgement(target, binding.eventId)
                ?: return CapturedCreateCleanResult.UNRESOLVED
            if (record.state != CapturedCreateAcknowledgementState.PROVEN || record.receipt.baseline != baseline)
                return CapturedCreateCleanResult.UNRESOLVED
            suspend fun exactSource(): Boolean = captures.readSubmission(target, localId) == submission &&
                currentFormat() == capture.format && projection.capturedAttachmentProtocol == attachment.protocol &&
                projection.capturedAcknowledgementProtocol == record.receipt.protocol &&
                store.transaction(target) { checkpoint?.format } == capture.format &&
                journal.capturedBinding(identity) == binding && journal.capturedAttachment(binding) == attachment &&
                store.capturedBaseline(target, binding.eventId) == baseline &&
                store.capturedCleanSource(record.receipt)
            if (!available()) return CapturedCreateCleanResult.INACTIVE
            if (!exactSource()) return CapturedCreateCleanResult.UNRESOLVED
            val proof = projection.inspectAcknowledgedCreateClean(record.receipt)
            if (!available()) return CapturedCreateCleanResult.INACTIVE
            if (proof?.receipt != record.receipt || !exactSource()) return CapturedCreateCleanResult.UNRESOLVED
            if (!available()) return CapturedCreateCleanResult.INACTIVE
            // The Provider observation and SQL checks are separate. No state is promoted,
            // queued work consumed or lease handed to a future projection on this result.
            CapturedCreateCleanResult.CLEAN
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!active()) CapturedCreateCleanResult.INACTIVE else throw cancelled
        } catch (_: Exception) {
            if (!active()) CapturedCreateCleanResult.INACTIVE else CapturedCreateCleanResult.UNRESOLVED
        } finally {
            captures.admitCapture = { false }
            store.admitTransaction = { false }
            projection.admitOperation = { false }
        }
    }

    suspend fun reconcile(localId: LocalEventId, id: MutationId, attempt: MutationId,
        mutation: MutationId): CapturedCreateReconciliationResult {
        return try {
            if (!available()) return CapturedCreateReconciliationResult.INACTIVE
            store.admitTransaction = { it == target && active() }
            captures.admitCapture = { it == target && active() }
            projection.admitOperation = { active() }
            val submission = captures.readSubmission(target, localId) ?: return CapturedCreateReconciliationResult.UNRESOLVED
            val reserved = submission.reservation
            val capture = reserved.intent.reservation
            if (capture.scope != target || capture.localId != localId || capture.id != id || reserved.attempt != attempt ||
                capture.format != currentFormat() || store.transaction(target) { checkpoint?.format } != capture.format ||
                projection.capturedAttachmentProtocol != 1 || mutation == id || mutation == attempt || mutation.value == localId.value)
                return CapturedCreateReconciliationResult.UNRESOLVED
            val identity = CapturedCreateDispatchIdentity.of(submission)
            val binding = journal.capturedBinding(identity) ?: return CapturedCreateReconciliationResult.UNRESOLVED
            val receipt = journal.capturedAttachment(binding) ?: return CapturedCreateReconciliationResult.UNRESOLVED
            val canonical = capturedCreateExpectation(submission, expectations) ?: return CapturedCreateReconciliationResult.UNRESOLVED
            val baseline = CapturedCreateBaseline(receipt,
                RemoteChange.Upsert(binding.eventId, binding.verifiedRevision, canonical))
            val acknowledgement = store.capturedAcknowledgement(target, binding.eventId)
            if (acknowledgement != null && (acknowledgement.state != CapturedCreateAcknowledgementState.PROVEN ||
                acknowledgement.receipt.baseline != baseline || projection.capturedAcknowledgementProtocol != acknowledgement.receipt.protocol))
                return CapturedCreateReconciliationResult.UNRESOLVED
            suspend fun currentSnapshot(): CapturedCreateCurrentObservation = if (acknowledgement == null)
                projection.captureCapturedCreateCurrent(baseline)
                else projection.captureAcknowledgedCreateCurrent(acknowledgement.receipt)
            suspend fun exact(): Boolean = store.capturedAcknowledgement(target, binding.eventId) == acknowledgement &&
                (acknowledgement == null || projection.capturedAcknowledgementProtocol == acknowledgement.receipt.protocol) &&
                captures.readSubmission(target, localId) == submission &&
                currentFormat() == capture.format && projection.capturedAttachmentProtocol == receipt.protocol &&
                store.transaction(target) { checkpoint?.format } == capture.format &&
                journal.capturedBinding(identity) == binding && journal.capturedAttachment(binding) == receipt &&
                store.capturedBaseline(target, binding.eventId) == baseline
            if (!available()) return CapturedCreateReconciliationResult.INACTIVE
            if (!exact()) return CapturedCreateReconciliationResult.UNRESOLVED
            // Cached intentions cannot qualify the original row. Every replay takes a fresh,
            // assertion-only snapshot of the complete attachment, content and child absence.
            val observed = currentSnapshot()
            if (!available()) return CapturedCreateReconciliationResult.INACTIVE
            if (!exact()) return CapturedCreateReconciliationResult.UNRESOLVED
            if (observed == CapturedCreateCurrentObservation.Unsupported)
                return CapturedCreateReconciliationResult.UNSUPPORTED
            val snapshot = (observed as? CapturedCreateCurrentObservation.Supported)?.snapshot
                ?: return CapturedCreateReconciliationResult.UNRESOLVED
            if (snapshot.baseline != baseline || snapshot.acknowledgement != acknowledgement?.receipt) return CapturedCreateReconciliationResult.UNRESOLVED
            val retained = store.retainCapturedLocalChange(snapshot, mutation)
            if (!available()) return CapturedCreateReconciliationResult.INACTIVE
            if (!exact()) return CapturedCreateReconciliationResult.UNRESOLVED
            val current = currentSnapshot() as? CapturedCreateCurrentObservation.Supported
            if (!available()) return CapturedCreateReconciliationResult.INACTIVE
            if (current?.snapshot != snapshot || !exact()) return CapturedCreateReconciliationResult.UNRESOLVED
            // Even this second observation is point-in-time evidence, never a future ACK lease.
            when (retained) {
                null -> CapturedCreateReconciliationResult.UNCHANGED
                LocalEditState.PAUSED -> CapturedCreateReconciliationResult.RETAINED
                LocalEditState.CONFLICT -> CapturedCreateReconciliationResult.CONFLICT
            }
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!active()) CapturedCreateReconciliationResult.INACTIVE else throw cancelled
        } catch (_: Exception) {
            if (!active()) CapturedCreateReconciliationResult.INACTIVE else CapturedCreateReconciliationResult.UNRESOLVED
        } finally {
            captures.admitCapture = { false }
            store.admitTransaction = { false }
            projection.admitOperation = { false }
        }
    }
}
