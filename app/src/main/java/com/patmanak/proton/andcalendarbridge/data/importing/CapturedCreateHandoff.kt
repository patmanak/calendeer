package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Transfer and follow-up reconciliation under the coordinator's existing mutex and epoch.
 * Historical projection recovery only inspects; source qualification remains shared with
 * other owner paths. Neither method submits CREATE or grants a fresh sending lease. */
internal class CapturedCreateHandoff(
    private val target: CalendarScope,
    private val store: ImportStore,
    private val projection: CalendarProjection,
    private val captures: LocalCreateStore,
    private val journal: CreateAttemptStore,
    private val scheduler: ImportScheduler,
    private val currentFormat: () -> AdapterFormat,
    private val active: () -> Boolean,
    private val currentAccount: suspend (AccountId) -> Boolean,
    private val source: suspend (CapturedCreateHandoffFrame) -> Boolean,
    private val expectations: CalendarCreateSubmissionPort? = null,
) {
    private suspend fun available(): Boolean = currentAccount(target.account) && active() &&
        store.selections(target.account).singleOrNull { it.scope == target }?.let { it.enabled && it.available } == true && active()

    private fun installAdmission() {
        store.admitTransaction = { it == target && active() }
        captures.admitCapture = { it == target && active() }
        projection.admitOperation = active
    }

    private fun clearAdmission() {
        captures.admitCapture = { false }
        store.admitTransaction = { false }
        projection.admitOperation = { false }
    }

    suspend fun transfer(localId: LocalEventId, id: MutationId, attempt: MutationId): CapturedCreateHandoffResult {
        try {
            if (!available()) return CapturedCreateHandoffResult.INACTIVE
            installAdmission()
            val submission = captures.readSubmission(target, localId) ?: return CapturedCreateHandoffResult.UNRESOLVED
            val capture = submission.reservation.intent.reservation
            if (capture.scope != target || capture.localId != localId || capture.id != id ||
                submission.reservation.attempt != attempt || capture.format != currentFormat())
                return CapturedCreateHandoffResult.UNRESOLVED
            val identity = CapturedCreateDispatchIdentity.of(submission)
            val binding = journal.capturedBinding(identity) ?: return CapturedCreateHandoffResult.UNRESOLVED
            val attachment = journal.capturedAttachment(binding) ?: return CapturedCreateHandoffResult.UNRESOLVED
            val canonical = capturedCreateExpectation(submission, expectations) ?: return CapturedCreateHandoffResult.UNRESOLVED
            val original = CapturedCreateBaseline(attachment, RemoteChange.Upsert(binding.eventId, binding.verifiedRevision, canonical))
            if (!available()) return CapturedCreateHandoffResult.INACTIVE
            if (captures.readSubmission(target, localId) != submission || journal.capturedBinding(identity) != binding ||
                journal.capturedAttachment(binding) != attachment) return CapturedCreateHandoffResult.UNRESOLVED
            var role = store.capturedHandoff(target, binding.eventId)
            if (role == null) {
                val ack = store.capturedAcknowledgement(target, binding.eventId) ?: return CapturedCreateHandoffResult.UNRESOLVED
                if (ack.state != CapturedCreateAcknowledgementState.PROVEN || ack.receipt.baseline != original)
                    return CapturedCreateHandoffResult.UNRESOLVED
                val frame = CapturedCreateHandoffFrame(CapturedCreateHandoffReceipt(ack.receipt, java.util.UUID.randomUUID().toString()), original.change)
                if (!available()) return CapturedCreateHandoffResult.INACTIVE
                if (!source(frame)) return CapturedCreateHandoffResult.UNRESOLVED
                role = store.reserveCapturedHandoff(frame.receipt)
            }
            if (role.frame.receipt.acknowledgement.baseline != original || !source(role.frame))
                return CapturedCreateHandoffResult.UNRESOLVED
            if (!available()) return CapturedCreateHandoffResult.INACTIVE
            if (role.state == CapturedCreateHandoffState.PENDING) {
                val clean = projection.inspectAcknowledgedCreateClean(role.frame.receipt.acknowledgement)
                if (!available()) return CapturedCreateHandoffResult.INACTIVE
                if (clean?.receipt != role.frame.receipt.acknowledgement || !source(role.frame) ||
                    store.capturedHandoff(target, binding.eventId) != role) return CapturedCreateHandoffResult.UNRESOLVED
                role = store.retainCapturedHandoff(role.frame.receipt, clean)
            } else {
                if (!recoverProjection(role.frame)) {
                    store.waitCapturedHandoff(role.frame)
                    return CapturedCreateHandoffResult.WAITING
                }
                role = store.capturedHandoff(target, binding.eventId) ?: return CapturedCreateHandoffResult.UNRESOLVED
                val clean = projection.inspectCapturedHandoffBase(role.frame)
                if (!available()) return CapturedCreateHandoffResult.INACTIVE
                if (clean?.frame != role.frame || !source(role.frame) || !store.resumeCapturedHandoff(clean)) {
                    store.waitCapturedHandoff(role.frame)
                    return CapturedCreateHandoffResult.WAITING
                }
            }
            if (!available()) return CapturedCreateHandoffResult.INACTIVE
            return if (store.capturedHandoff(target, binding.eventId)?.let {
                it.frame == role.frame && it.state == CapturedCreateHandoffState.RELEASED && !it.waiting
            } == true && source(role.frame)) CapturedCreateHandoffResult.HANDED_OFF else CapturedCreateHandoffResult.UNRESOLVED
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!active()) return CapturedCreateHandoffResult.INACTIVE else throw cancelled
        } catch (_: Exception) {
            return if (!active()) CapturedCreateHandoffResult.INACTIVE else CapturedCreateHandoffResult.UNRESOLVED
        } finally {
            clearAdmission()
        }
    }

    suspend fun reconcile(localId: LocalEventId, id: MutationId, attempt: MutationId,
        mutation: MutationId?, beforeNewOwner: suspend () -> Unit): CapturedCreateReconciliationResult {
        return try {
            if (!available()) return CapturedCreateReconciliationResult.INACTIVE
            installAdmission()
            val submission = captures.readSubmission(target, localId) ?: return CapturedCreateReconciliationResult.UNRESOLVED
            val capture = submission.reservation.intent.reservation
            if (capture.id != id || capture.localId != localId || capture.scope != target || submission.reservation.attempt != attempt ||
                mutation == id || mutation == attempt || mutation?.value == localId.value) return CapturedCreateReconciliationResult.UNRESOLVED
            val binding = journal.capturedBinding(CapturedCreateDispatchIdentity.of(submission)) ?: return CapturedCreateReconciliationResult.UNRESOLVED
            var role = store.capturedHandoff(target, binding.eventId) ?: return CapturedCreateReconciliationResult.UNRESOLVED
            if (role.state != CapturedCreateHandoffState.RELEASED || !source(role.frame) ||
                !recoverProjection(role.frame)) return CapturedCreateReconciliationResult.UNRESOLVED
            role = store.capturedHandoff(target, binding.eventId) ?: return CapturedCreateReconciliationResult.UNRESOLVED
            // A title acknowledgement or incoming projection can leave this CREATE
            // follow-up queued after its native row is already clean. Qualify the
            // current frame; inspecting the original CREATE revision cannot close it.
            val clean = projection.inspectCapturedHandoffBase(role.frame)
            currentCoroutineContext().ensureActive()
            if (!available()) return CapturedCreateReconciliationResult.INACTIVE
            if (clean != null) {
                if (clean.frame != role.frame) return CapturedCreateReconciliationResult.UNRESOLVED
                val qualified = source(role.frame)
                currentCoroutineContext().ensureActive()
                if (!available()) return CapturedCreateReconciliationResult.INACTIVE
                val sameRole = store.capturedHandoff(target, binding.eventId) == role
                currentCoroutineContext().ensureActive()
                if (!available()) return CapturedCreateReconciliationResult.INACTIVE
                if (!qualified || !sameRole) return CapturedCreateReconciliationResult.UNRESOLVED
                val resumed = store.resumeCapturedHandoff(clean)
                currentCoroutineContext().ensureActive()
                if (!available()) return CapturedCreateReconciliationResult.INACTIVE
                if (!resumed)
                    return CapturedCreateReconciliationResult.UNRESOLVED
                val after = projection.inspectCapturedHandoffBase(role.frame)
                currentCoroutineContext().ensureActive()
                if (!available()) return CapturedCreateReconciliationResult.INACTIVE
                val sourceCurrent = source(role.frame)
                currentCoroutineContext().ensureActive()
                if (!available()) return CapturedCreateReconciliationResult.INACTIVE
                val current = store.capturedHandoff(target, binding.eventId)
                currentCoroutineContext().ensureActive()
                if (!available()) return CapturedCreateReconciliationResult.INACTIVE
                if (after?.frame != role.frame || !sourceCurrent ||
                    current == null || current.frame != role.frame || current.state != CapturedCreateHandoffState.RELEASED || current.waiting) {
                    if (active() && current?.frame == role.frame) store.waitCapturedHandoff(role.frame)
                    currentCoroutineContext().ensureActive()
                    if (!available()) return CapturedCreateReconciliationResult.INACTIVE
                    return CapturedCreateReconciliationResult.UNRESOLVED
                }
                if (role.waiting) {
                    val pending = store.capturedHandoffPending(current.frame)
                    currentCoroutineContext().ensureActive()
                    if (!available()) return CapturedCreateReconciliationResult.INACTIVE
                    if (pending == ProjectionIntent(target, current.frame.base)) {
                        val sourceStillCurrent = source(current.frame)
                        currentCoroutineContext().ensureActive()
                        if (!available()) return CapturedCreateReconciliationResult.INACTIVE
                        val settledRole = store.capturedHandoff(target, binding.eventId)
                        currentCoroutineContext().ensureActive()
                        if (!available()) return CapturedCreateReconciliationResult.INACTIVE
                        if (!sourceStillCurrent || settledRole != current)
                            return CapturedCreateReconciliationResult.UNRESOLVED
                        // Import precedes this metadata-only release in the worker.
                        // Append a local pass: KEEP could lose this wake while it runs.
                        scheduler.continueWork(network = false)
                    }
                }
                return CapturedCreateReconciliationResult.UNCHANGED
            }
            val observed = projection.captureCapturedHandoffCurrent(role.frame)
            if (!available()) return CapturedCreateReconciliationResult.INACTIVE
            if (!source(role.frame) || store.capturedHandoff(target, binding.eventId) != role)
                return CapturedCreateReconciliationResult.UNRESOLVED
            val snapshot = (observed as? CapturedHandoffCurrentObservation.Supported)?.snapshot
            if (snapshot?.frame != role.frame) {
                store.waitCapturedHandoff(role.frame)
                return if (observed == CapturedHandoffCurrentObservation.Unsupported)
                    CapturedCreateReconciliationResult.UNSUPPORTED else CapturedCreateReconciliationResult.UNRESOLVED
            }
            if (role.frame.receipt.acknowledgement.baseline.receipt.binding.identity.fingerprintVersion == 2 && !snapshot.unchanged) {
                store.waitCapturedHandoff(role.frame)
                return CapturedCreateReconciliationResult.UNSUPPORTED
            }
            val owner = mutation ?: store.localEdit(target, binding.eventId)?.id ?: store.localDelete(target, binding.eventId)?.id
                ?: MutationId(java.util.UUID.randomUUID().toString())
            if (owner == id || owner == attempt || owner.value == localId.value)
                return CapturedCreateReconciliationResult.UNRESOLVED
            if (!snapshot.unchanged && store.localEdit(target, binding.eventId) == null &&
                store.localDelete(target, binding.eventId) == null) {
                beforeNewOwner()
                if (!available()) return CapturedCreateReconciliationResult.INACTIVE
            }
            val retained = store.retainCapturedHandoffLocalChange(snapshot, owner)
            if (!available()) return CapturedCreateReconciliationResult.INACTIVE
            val second = projection.captureCapturedHandoffCurrent(role.frame) as? CapturedHandoffCurrentObservation.Supported
            if (!available()) return CapturedCreateReconciliationResult.INACTIVE
            if (second?.snapshot != snapshot || !source(role.frame) ||
                store.capturedHandoff(target, binding.eventId)?.frame != role.frame) return CapturedCreateReconciliationResult.UNRESOLVED
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
            clearAdmission()
        }
    }

    /** Explicit retention against the current transferred frame. No recovery, ACK or dispatch. */
    suspend fun captureSeriesEdit(localId: LocalEventId, id: MutationId, attempt: MutationId,
        mutation: MutationId?, beforeNewOwner: suspend () -> Unit = {}): CapturedCreateReconciliationResult {
        return try {
            if (!available()) return CapturedCreateReconciliationResult.INACTIVE
            installAdmission()
            val submission = captures.readSubmission(target, localId) ?: return CapturedCreateReconciliationResult.UNRESOLVED
            val capture = submission.reservation.intent.reservation
            if (capture.scope != target || capture.localId != localId || capture.id != id ||
                capture.format != currentFormat() || submission.reservation.attempt != attempt ||
                mutation == id || mutation == attempt || mutation?.value == localId.value)
                return CapturedCreateReconciliationResult.UNRESOLVED
            val identity = CapturedCreateDispatchIdentity.of(submission)
            if (identity.fingerprintVersion != 2) return CapturedCreateReconciliationResult.UNSUPPORTED
            val binding = journal.capturedBinding(identity) ?: return CapturedCreateReconciliationResult.UNRESOLVED
            val role = store.capturedHandoff(target, binding.eventId) ?: return CapturedCreateReconciliationResult.UNRESOLVED
            val frame = role.frame
            // The handoff reader validates the immutable PROVEN ACK and its fingerprint.
            // The pre-transfer ACK reader intentionally no longer admits this authority.
            if (frame.receipt.acknowledgement.baseline.receipt.binding != binding ||
                role.state != CapturedCreateHandoffState.RELEASED || !source(frame) ||
                store.capturedHandoffProjection(frame) != null) return CapturedCreateReconciliationResult.UNRESOLVED
            if (!available()) return CapturedCreateReconciliationResult.INACTIVE
            if (store.localDelete(target, binding.eventId) != null) return CapturedCreateReconciliationResult.UNRESOLVED
            val retained = store.localEdit(target, binding.eventId)
            val owner = mutation ?: retained?.id ?: MutationId(java.util.UUID.randomUUID().toString())
            if (owner == id || owner == attempt || owner.value == localId.value)
                return CapturedCreateReconciliationResult.UNRESOLVED
            if (retained != null) {
                if (retained.id != owner || retained.base != frame.base || retained.format != frame.format ||
                    retained.seriesProfile == null) return CapturedCreateReconciliationResult.UNRESOLVED
                if (!source(frame) || store.capturedHandoff(target, binding.eventId) != role)
                    return CapturedCreateReconciliationResult.UNRESOLVED
                if (!available()) return CapturedCreateReconciliationResult.INACTIVE
                return if (retained.state == LocalEditState.CONFLICT) CapturedCreateReconciliationResult.CONFLICT
                    else CapturedCreateReconciliationResult.RETAINED
            }
            val observed = projection.captureCapturedHandoffCurrent(frame)
            if (!available()) return CapturedCreateReconciliationResult.INACTIVE
            if (!source(frame) || store.capturedHandoff(target, binding.eventId) != role)
                return CapturedCreateReconciliationResult.UNRESOLVED
            if (!available()) return CapturedCreateReconciliationResult.INACTIVE
            val snapshot = (observed as? CapturedHandoffCurrentObservation.Supported)?.snapshot
                ?: return if (observed == CapturedHandoffCurrentObservation.Unsupported)
                    CapturedCreateReconciliationResult.UNSUPPORTED else CapturedCreateReconciliationResult.UNRESOLVED
            if (snapshot.frame != frame) return CapturedCreateReconciliationResult.UNRESOLVED
            if (snapshot.deleted) return CapturedCreateReconciliationResult.UNSUPPORTED
            if (snapshot.unchanged) {
                if (mutation != null) return CapturedCreateReconciliationResult.UNCHANGED
                val clean = projection.inspectCapturedHandoffBase(frame)
                if (!available()) return CapturedCreateReconciliationResult.INACTIVE
                if (clean?.frame != frame || !source(frame) || store.capturedHandoff(target, binding.eventId) != role ||
                    store.capturedHandoffProjection(frame) != null || !store.resumeCapturedHandoff(clean))
                    return CapturedCreateReconciliationResult.UNRESOLVED
                val after = projection.inspectCapturedHandoffBase(frame)
                if (!available()) return CapturedCreateReconciliationResult.INACTIVE
                val qualified = source(frame)
                val current = store.capturedHandoff(target, binding.eventId)
                if (!available()) return CapturedCreateReconciliationResult.INACTIVE
                return if (after?.frame == frame && qualified && current?.frame == frame &&
                    current.state == CapturedCreateHandoffState.RELEASED && !current.waiting &&
                    store.capturedHandoffProjection(frame) == null) CapturedCreateReconciliationResult.UNCHANGED
                    else CapturedCreateReconciliationResult.UNRESOLVED
            }
            if (mutation == null) {
                beforeNewOwner()
                if (!available()) return CapturedCreateReconciliationResult.INACTIVE
                if (!source(frame) || store.capturedHandoff(target, binding.eventId) != role ||
                    store.capturedHandoffProjection(frame) != null || store.localEdit(target, binding.eventId) != null ||
                    store.localDelete(target, binding.eventId) != null) return CapturedCreateReconciliationResult.UNRESOLVED
                if (!available()) return CapturedCreateReconciliationResult.INACTIVE
                val second = projection.captureCapturedHandoffCurrent(frame) as? CapturedHandoffCurrentObservation.Supported
                if (!available()) return CapturedCreateReconciliationResult.INACTIVE
                if (second?.snapshot != snapshot || !source(frame) || store.capturedHandoff(target, binding.eventId) != role)
                    return CapturedCreateReconciliationResult.UNRESOLVED
                if (!available()) return CapturedCreateReconciliationResult.INACTIVE
            }
            val state = store.retainCapturedHandoffSeriesEdit(snapshot, owner)
            if (!available()) return CapturedCreateReconciliationResult.INACTIVE
            if (mutation == null) {
                val after = projection.captureCapturedHandoffCurrent(frame) as? CapturedHandoffCurrentObservation.Supported
                if (!available()) return CapturedCreateReconciliationResult.INACTIVE
                if (after?.snapshot != snapshot || !source(frame) || store.capturedHandoff(target, binding.eventId) != role.copy(waiting = true) ||
                    store.capturedHandoffProjection(frame) != null)
                    return CapturedCreateReconciliationResult.UNRESOLVED
                if (!available()) return CapturedCreateReconciliationResult.INACTIVE
            }
            if (state == LocalEditState.CONFLICT) CapturedCreateReconciliationResult.CONFLICT
                else CapturedCreateReconciliationResult.RETAINED
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!active()) CapturedCreateReconciliationResult.INACTIVE else throw cancelled
        } catch (_: Exception) {
            if (!active()) CapturedCreateReconciliationResult.INACTIVE else CapturedCreateReconciliationResult.UNRESOLVED
        } finally { clearAdmission() }
    }

    /** Recover only historical proof. No Provider write, even after a later edit or unsupported import. */
    private suspend fun recoverProjection(frame: CapturedCreateHandoffFrame): Boolean {
        if (!source(frame)) return false
        val prepared = store.capturedHandoffProjection(frame) ?: return true
        val witness = projection.inspectCapturedHandoffProjection(prepared) ?: return false
        if (witness.plan != prepared || !currentAccount(frame.scope.account) || !active() || !source(frame)) return false
        return store.restoreCapturedHandoffProjection(witness)
    }
}
