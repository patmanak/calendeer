// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.SystemCalendarChangeKind
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Metadata only: event contents stay in the existing capture and submission boundaries. */
data class SystemCreateUiState(
    val enabled: Set<CalendarScope> = emptySet(),
    val titlesEnabled: Set<CalendarScope> = emptySet(),
    val pending: Map<CalendarScope, Int> = emptyMap(),
    val busy: Boolean = false,
    val failed: Boolean = false,
    val textsEnabled: Set<CalendarScope> = emptySet(),
    val textSupportAvailable: Boolean = false,
    val timesEnabled: Set<CalendarScope> = emptySet(),
    val timeSupportAvailable: Boolean = false,
    val deletesEnabled: Set<CalendarScope> = emptySet(),
    val deleteSupportAvailable: Boolean = false,
    val modeReview: Set<CalendarScope> = emptySet(),
    val legacyUnsupported: Set<CalendarScope> = emptySet(),
) {
    override fun toString() = "SystemCreateUiState(REDACTED, busy=$busy, failed=$failed)"
}

/**
 * Separate opt-in CREATE, title, text, time and deletion triggers. Unsupported changes stay retained.
 * All Provider admission stays inside ImportCoordinator's mutex; this owner never borrows or
 * modifies the mutable admission guards of its shared projection/capture dependencies.
 */
class SystemCreateController(
    context: Context,
    private val imports: ImportCoordinator,
    @Suppress("unused") private val projection: AndroidCalendarProjection,
    private val work: SystemCreateWorkStore,
    @Suppress("unused") private val captures: LocalCreateStore,
    private val attempts: CreateAttemptStore,
    private val port: CalendarCreateSubmissionPort,
    private val scope: CoroutineScope,
    private val scheduler: ImportScheduler = WorkManagerImportScheduler(context),
    private val titleRecovery: SystemTitleRecoveryController? = null,
    private val titleUpdates: SystemUpdateController? = null,
    private val textRecovery: SystemTitleRecoveryController? = null,
    private val textUpdates: SystemUpdateController? = null,
    private val timeRecovery: SystemTitleRecoveryController? = null,
    private val timeUpdates: SystemUpdateController? = null,
    private val personalRecovery: SystemTitleRecoveryController? = null,
    private val personalUpdates: SystemUpdateController? = null,
    private val deleteRecovery: SystemTitleRecoveryController? = null,
    private val deleteUpdates: SystemUpdateController? = null,
    private val legacyUnsupported: suspend (CalendarScope) -> Boolean = { false },
) {
    private val context = context.applicationContext
    private val textSupportAvailable = textRecovery != null && textUpdates != null
    private val timeSupportAvailable = timeRecovery != null && timeUpdates != null
    private val personalSupportAvailable = personalRecovery != null && personalUpdates != null
    private val deleteSupportAvailable = deleteRecovery != null && deleteUpdates != null
    private val mutableState = MutableStateFlow(SystemCreateUiState(textSupportAvailable = textSupportAvailable,
        timeSupportAvailable = timeSupportAvailable, deleteSupportAvailable = deleteSupportAvailable))
    val state: StateFlow<SystemCreateUiState> = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val started = AtomicBoolean()
    private val epochs = ConcurrentHashMap<CalendarScope, AtomicLong>()
    private val cursors = this.context.getSharedPreferences("system-create-cursors", Context.MODE_PRIVATE)
    private var observing = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            // The durable URI watcher coalesces writes, including our own ACK/imports.
            // This observer repairs its registration without scheduling a normal network run.
            scope.launch {
                try { updateObserver() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { reportAttention(AttentionStage.OBSERVATION_SETUP) }
            }
        }
    }

    private data class ScopeSignal(val scope: CalendarScope, val selected: Boolean,
        val available: Boolean, val mode: Boolean)
    private data class Signal(val authenticated: Boolean, val permission: Boolean,
        val selections: List<ScopeSignal>, val loaded: Boolean)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var previous: Signal? = null
            imports.state.map { current -> Signal(current.authenticated, current.hasPermission,
                current.selections.map { ScopeSignal(it.scope, it.enabled, it.available, work.modeEnabled(it.scope)) },
                current.selectionsLoaded) }
                .distinctUntilChanged().collect { signal ->
                    try {
                        observeCurrentCalendarSignal {
                            // Initial session/catalog hydration must preserve a restarted traversal.
                            // A subsequently revoked selection abandons its old page position only.
                            fun Signal.active() = if (authenticated && permission) selections
                                .filter { it.selected && it.available && it.mode }.map { it.scope }.toSet()
                                else emptySet()
                            val revoked = previous?.active().orEmpty() - signal.active()
                            previous = signal
                            for (target in revoked) {
                                resetEditingTraversal(target)
                            }
                            updateObserver()
                            imports.reconcileSystemCalendarAccess()
                            publish()
                            request()
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { reportAttention(AttentionStage.OBSERVATION_SETUP) }
                }
        }
    }

    /** Disable and epoch invalidation happen synchronously, even while network work is suspended. */
    fun setEnabled(selection: ImportStore.Selection, enabled: Boolean, unified: Boolean = false) {
        val target = selection.scope
        val current = imports.state.value
        if (enabled && (!current.authenticated || !current.hasPermission ||
            current.selections.none { it.scope == target && it.enabled && it.available })) return
        if (unified) work.setEditableMode(target, enabled) else work.setMode(target, enabled)
        epochs.computeIfAbsent(target) { AtomicLong() }.incrementAndGet()
        imports.systemCreatePolicyChanged(target, enabled)
        scope.launch {
            try {
                resetEditingTraversal(target)
                updateObserver()
                imports.configureSystemCalendar(target)
                publish()
                request()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reportAttention(AttentionStage.CREATE_POLICY) }
        }
    }

    fun setTextsEnabled(selection: ImportStore.Selection, enabled: Boolean) {
        val target = selection.scope
        val current = imports.state.value
        if (enabled && (!textSupportAvailable || !current.authenticated || !current.hasPermission || !work.modeEnabled(target) ||
            current.selections.none { it.scope == target && it.enabled && it.available })) return
        work.setTextsMode(target, enabled)
        epochs.computeIfAbsent(target) { AtomicLong() }.incrementAndGet()
        // Invalidate coordinator/adapter leases synchronously, before any cursor reset suspends.
        imports.systemCreatePolicyChanged(target, work.modeEnabled(target))
        scope.launch {
            try {
                resetEditingTraversal(target)
                publish()
                request()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reportAttention(AttentionStage.TEXT_POLICY) }
        }
    }

    fun setTimesEnabled(selection: ImportStore.Selection, enabled: Boolean) {
        val target = selection.scope
        val current = imports.state.value
        if (enabled && (!timeSupportAvailable || !current.authenticated || !current.hasPermission || !work.modeEnabled(target) ||
            current.selections.none { it.scope == target && it.enabled && it.available })) return
        work.setTimesMode(target, enabled)
        epochs.computeIfAbsent(target) { AtomicLong() }.incrementAndGet()
        // Invalidate coordinator/adapter leases synchronously, before any cursor reset suspends.
        imports.systemCreatePolicyChanged(target, work.modeEnabled(target))
        scope.launch {
            try {
                resetEditingTraversal(target)
                publish()
                request()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reportAttention(AttentionStage.TIME_POLICY) }
        }
    }

    fun setDeletesEnabled(selection: ImportStore.Selection, enabled: Boolean) {
        val target = selection.scope
        val current = imports.state.value
        if (enabled && (!deleteSupportAvailable || !current.authenticated || !current.hasPermission || !work.modeEnabled(target) ||
            current.selections.none { it.scope == target && it.enabled && it.available })) return
        work.setDeletesMode(target, enabled)
        epochs.computeIfAbsent(target) { AtomicLong() }.incrementAndGet()
        // Invalidate coordinator/adapter leases synchronously, before any cursor reset suspends.
        imports.systemCreatePolicyChanged(target, work.modeEnabled(target))
        scope.launch {
            try {
                resetEditingTraversal(target)
                publish()
                request()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reportAttention(AttentionStage.DELETE_POLICY) }
        }
    }

    fun request() {
        if (activeScopes().isEmpty()) return
        scheduler.requestLocal()
        scheduler.requestNetwork()
    }

    fun setTitlesEnabled(selection: ImportStore.Selection, enabled: Boolean) {
        val target = selection.scope
        val current = imports.state.value
        if (enabled && (!current.authenticated || !current.hasPermission || !work.modeEnabled(target) ||
            current.selections.none { it.scope == target && it.enabled && it.available })) return
        work.setTitlesMode(target, enabled)
        epochs.computeIfAbsent(target) { AtomicLong() }.incrementAndGet()
        // Synchronous policy revocation also reaches suspended coordinator/adapter leases.
        imports.systemCreatePolicyChanged(target, work.modeEnabled(target))
        scope.launch {
            try {
                resetEditingTraversal(target)
                publish()
                request()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reportAttention(AttentionStage.TITLE_POLICY) }
        }
    }

    private suspend fun resetEditingTraversal(target: CalendarScope) {
        titleRecovery?.reset(target)
        titleUpdates?.reset(target)
        textRecovery?.reset(target)
        textUpdates?.reset(target)
        timeRecovery?.reset(target)
        timeUpdates?.reset(target)
        personalRecovery?.reset(target)
        personalUpdates?.reset(target)
        deleteRecovery?.reset(target)
        deleteUpdates?.reset(target)
    }

    /** One bounded page per selected scope; unknown outcomes never cause immediate retry loops. */
    suspend fun run(network: Boolean, fresh: Boolean = true, providerWake: Boolean = false,
        expectedAccount: AccountId? = null): ImportRunResult = mutex.withLock {
        mutableState.update { it.copy(busy = true, failed = false) }
        var more = false
        var retry = false
        try {
            for (target in activeScopes()) {
                if (expectedAccount != null && target.account != expectedAccount) continue
                val epoch = epochs.computeIfAbsent(target) { AtomicLong() }.get()
                val titleAdmission = imports.systemTitleRunAdmission(target)
                // Capture all policy leases before the first suspension. Re-enabling cannot
                // lend fresh authority to a run already paused in title/CREATE work.
                val textAdmission = if (textSupportAvailable) imports.systemTextRunAdmission(target) else null
                val textProofAdmission = if (textSupportAvailable) imports.systemTextProofAdmission(target) else null
                val timeAdmission = if (timeSupportAvailable) imports.systemTimeRunAdmission(target) else null
                val timeProofAdmission = if (timeSupportAvailable) imports.systemTimeProofAdmission(target) else null
                val personalAdmission = if (personalSupportAvailable) imports.systemPersonalRunAdmission(target) else null
                val personalProofAdmission = if (personalSupportAvailable) imports.systemPersonalProofAdmission(target) else null
                val deleteAdmission = if (deleteSupportAvailable) imports.systemDeleteRunAdmission(target) else null
                val deleteProofAdmission = if (deleteSupportAvailable) imports.systemDeleteProofAdmission(target) else null
                if (!active(target, epoch)) continue
                // A failed native scan/CREATE must not block already durable title proof.
                // This is the same captured epoch; no new authority is acquired after a pause.
                val recovered = titleRecovery?.run(target, fresh) { active(target, epoch) }
                if (recovered?.failed == true) reportAttention(AttentionStage.TITLE_RECOVERY)
                more = more || recovered?.more == true
                if (!active(target, epoch)) continue
                // Existing local text proof remains recoverable with text sending disabled.
                val recoveredText = if (textSupportAvailable) textRecovery?.run(target, fresh) {
                    active(target, epoch) && textProofAdmission?.invoke(target) == true
                } else null
                if (recoveredText?.failed == true) reportAttention(AttentionStage.TEXT_RECOVERY)
                more = more || recoveredText?.more == true
                if (!active(target, epoch)) continue
                // Existing local time proof remains recoverable with time sending disabled.
                val recoveredTime = if (timeSupportAvailable) timeRecovery?.run(target, fresh) {
                    active(target, epoch) && timeProofAdmission?.invoke(target) == true
                } else null
                if (recoveredTime?.failed == true) reportAttention(AttentionStage.TIME_RECOVERY)
                more = more || recoveredTime?.more == true
                if (!active(target, epoch)) continue
                val recoveredPersonal = if (personalSupportAvailable) personalRecovery?.run(target, fresh) {
                    active(target, epoch) && personalProofAdmission?.invoke(target) == true
                } else null
                if (recoveredPersonal?.failed == true) reportAttention(AttentionStage.PERSONAL_RECOVERY)
                more = more || recoveredPersonal?.more == true
                if (!active(target, epoch)) continue
                // Retained deletion proof can finish locally after deletion sending is disabled.
                val recoveredDelete = if (deleteSupportAvailable) deleteRecovery?.run(target, fresh) {
                    active(target, epoch) && deleteProofAdmission?.invoke(target) == true
                } else null
                if (recoveredDelete?.failed == true) reportAttention(AttentionStage.DELETE_RECOVERY)
                more = more || recoveredDelete?.more == true
                if (!active(target, epoch)) continue
                if (!imports.configureSystemCalendar(target)) { retry = true; continue }
                val discovered = imports.discoverSystemChanges(target, cursor(target, "scan"), PAGE_SIZE)
                if (discovered == null) { if (active(target, epoch)) retry = true; continue }
                for (change in discovered.changes) {
                    if (!active(target, epoch)) break
                    when (change.kind) {
                        SystemCalendarChangeKind.NEW -> work.reserve(target, change.row)
                        SystemCalendarChangeKind.UNSUPPORTED -> {
                            val retained = work.read(target, change.row)
                            if (retained != null && retained.status != SystemCreateWorkStatus.COMPLETE)
                                work.status(retained, SystemCreateWorkStatus.UNSUPPORTED)
                            reportAttention(AttentionStage.NATIVE_IDENTITY_UNSUPPORTED)
                        }
                        SystemCalendarChangeKind.IMPORTED -> {
                            val event = change.event
                            if (event == null) reportAttention(AttentionStage.IMPORTED_IDENTITY_UNRESOLVED)
                            else {
                                if (imports.reconcileReservedDeleteChange(target, event, change.deleted, change.dirty)) continue
                                val result = if (change.deleted) imports.captureLocalDelete(target, event,
                                    mutation(target, "imported-local-change", event.value),
                                    beforeNewOwner = { rearmLocalOwnerDiscovery(target, deleted = true) })
                                    else imports.captureSystemLocalEdit(target, event,
                                        beforeNewOwner = { rearmLocalOwnerDiscovery(target, deleted = false) })
                                if (result == LocalEditCaptureResult.UNAVAILABLE) retry = true
                                if (result == LocalEditCaptureResult.UNQUALIFIED)
                                    reportAttention(AttentionStage.IMPORTED_CAPTURE_UNQUALIFIED)
                            }
                        }
                        SystemCalendarChangeKind.CAPTURED -> {
                            // A stamped row without the identity journal is never adopted by row ID.
                            var retained = work.read(target, change.row)
                            if (retained == null) {
                                val issue = work.historyIssue(target, change.row, change.capturedKey)
                                val recovered = imports.recoverHistoricalSystemCreate(target, change.row,
                                    change.capturedKey, issue?.after)
                                if (!active(target, epoch)) break
                                work.retainHistoricalRecovery(target, change.row, change.capturedKey, recovered)
                                retained = work.read(target, change.row)
                                if (retained == null && recovered.work != null)
                                    work.retainHistoricalRecovery(target, change.row, change.capturedKey,
                                        HistoricalCreateRecovery(reason = HistoricalCreateIssueReason.INCOMPLETE_PROOF))
                                // A SEARCHING cursor resumes on the next bounded discovery pass.
                                if (recovered.after != null) more = true
                                if (retained == null) reportAttention(AttentionStage.CAPTURED_IDENTITY_UNRESOLVED)
                            }
                            if (retained?.status == SystemCreateWorkStatus.COMPLETE) {
                                val result = imports.reconcileHandoffLocalCreate(target, retained.localId,
                                    retained.captureId, retained.attempt,
                                    beforeNewOwner = { rearmLocalOwnerDiscovery(target) })
                                if (result != CapturedCreateReconciliationResult.UNCHANGED)
                                    work.status(retained, if (result == CapturedCreateReconciliationResult.UNSUPPORTED)
                                        SystemCreateWorkStatus.UNSUPPORTED else SystemCreateWorkStatus.WAITING)
                            }
                        }
                    }
                }
                if (!active(target, epoch)) continue
                saveCursor(target, "scan", if (discovered.more) discovered.afterRow else 0)
                more = more || discovered.more
                val pending = work.page(target, cursor(target, "jobs"), PAGE_SIZE)
                for (retained in pending) {
                    if (!active(target, epoch)) break
                    if (retained.status == SystemCreateWorkStatus.UNSUPPORTED) continue
                    if (process(retained, network, epoch, providerWake)) retry = true
                }
                if (!active(target, epoch)) continue
                val after = pending.lastOrNull()?.row ?: 0
                val further = after > 0 && work.page(target, after, 1).isNotEmpty()
                saveCursor(target, "jobs", if (further) after else 0)
                more = more || further
                val titles = titleUpdates?.run(target, network, fresh) {
                    active(target, epoch) && titleAdmission(target)
                }
                if (titles?.failed == true) reportAttention(AttentionStage.TITLE_UPDATE)
                more = more || titles?.more == true
                if (!active(target, epoch)) continue
                val texts = if (textSupportAvailable) textUpdates?.run(target, network, fresh) {
                    active(target, epoch) && work.textsEnabled(target) && textAdmission?.invoke(target) == true
                } else null
                if (texts?.failed == true) reportAttention(AttentionStage.TEXT_UPDATE)
                more = more || texts?.more == true
                if (!active(target, epoch)) continue
                val times = if (timeSupportAvailable) timeUpdates?.run(target, network, fresh) {
                    active(target, epoch) && work.timesEnabled(target) && timeAdmission?.invoke(target) == true
                } else null
                if (times?.failed == true) reportAttention(AttentionStage.TIME_UPDATE)
                more = more || times?.more == true
                if (!active(target, epoch)) continue
                val personal = if (personalSupportAvailable) personalUpdates?.run(target, network, fresh) {
                    active(target, epoch) && personalAdmission?.invoke(target) == true
                } else null
                if (personal?.failed == true) reportAttention(AttentionStage.PERSONAL_UPDATE)
                more = more || personal?.more == true
                if (!active(target, epoch)) continue
                val deletes = if (deleteSupportAvailable) deleteUpdates?.run(target, network, fresh) {
                    active(target, epoch) && work.deletesEnabled(target) && deleteAdmission?.invoke(target) == true
                } else null
                if (deletes?.failed == true) reportAttention(AttentionStage.DELETE_UPDATE)
                more = more || deletes?.more == true
            }
            publish()
            when { retry -> ImportRunResult.RETRY; more -> ImportRunResult.CONTINUE; else -> ImportRunResult.COMPLETE }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            reportAttention(AttentionStage.WORKER_FAILURE)
            ImportRunResult.RETRY
        } finally { mutableState.update { it.copy(busy = false) } }
    }

    // Closed stage codes only: no scope, row, event, payload, exception or account values.
    private enum class AttentionStage {
        OBSERVATION_SETUP, CREATE_POLICY, TEXT_POLICY, TIME_POLICY, DELETE_POLICY, TITLE_POLICY, TITLE_RECOVERY, TEXT_RECOVERY, TIME_RECOVERY, PERSONAL_RECOVERY, DELETE_RECOVERY,
        NATIVE_IDENTITY_UNSUPPORTED, IMPORTED_IDENTITY_UNRESOLVED, IMPORTED_CAPTURE_UNQUALIFIED,
        CAPTURED_IDENTITY_UNRESOLVED, TITLE_UPDATE, TEXT_UPDATE, TIME_UPDATE, PERSONAL_UPDATE, DELETE_UPDATE, WORKER_FAILURE,
    }

    private fun reportAttention(stage: AttentionStage) {
        Log.w("CalendeerSync", stage.name)
        mutableState.update { it.copy(failed = true) }
    }

    /** The coordinator invokes this only for a qualified new owner, before its durable insert.
     * A crash can leave an extra discovery pass, never an inserted owner behind exhaustion.
     */
    private suspend fun rearmLocalOwnerDiscovery(target: CalendarScope, deleted: Boolean? = null) {
        if (deleted != true) {
            titleUpdates?.localOwnerWillBeCaptured(target)
            textUpdates?.localOwnerWillBeCaptured(target)
            timeUpdates?.localOwnerWillBeCaptured(target)
            personalUpdates?.localOwnerWillBeCaptured(target)
        }
        if (deleted != false) deleteUpdates?.localOwnerWillBeCaptured(target)
    }

    /** True requests backoff only for a known transient failure, never for UNKNOWN alone. */
    private suspend fun process(retained: SystemCreateWork, network: Boolean, epoch: Long, providerWake: Boolean): Boolean {
        val target = retained.scope
        if (!active(target, epoch)) return false
        val priorAttempt = attempts.read(target, retained.attempt)
        val mutation = mutation(target, "captured-local-change", retained.captureId.value)
        if (priorAttempt?.state == CreateAttemptState.OUTCOME_UNKNOWN) {
            // Recover a completed handoff whose final queue status was lost, or preserve later edits.
            when (imports.reconcileHandoffLocalCreate(target, retained.localId, retained.captureId, retained.attempt,
                beforeNewOwner = { rearmLocalOwnerDiscovery(target) })) {
                CapturedCreateReconciliationResult.UNCHANGED -> {
                    work.status(retained, SystemCreateWorkStatus.COMPLETE); return false
                }
                CapturedCreateReconciliationResult.RETAINED, CapturedCreateReconciliationResult.CONFLICT -> {
                    work.status(retained, SystemCreateWorkStatus.WAITING); return false
                }
                CapturedCreateReconciliationResult.UNSUPPORTED -> {
                    work.status(retained, SystemCreateWorkStatus.UNSUPPORTED); return false
                }
                else -> Unit
            }
        }
        if (!active(target, epoch)) return false
        when (imports.captureLocalCreate(target, retained.row, retained.localId, retained.captureId, retained.timestamp,
            autoProfile = port.seriesSubmissionFormat != null)) {
            LocalEditCaptureResult.RETAINED -> Unit
            LocalEditCaptureResult.UNAVAILABLE -> { work.status(retained, SystemCreateWorkStatus.WAITING); return true }
            else -> { work.status(retained, SystemCreateWorkStatus.WAITING); return false }
        }
        if (!active(target, epoch)) return false
        if (!network) {
            work.status(retained, if (priorAttempt?.state == CreateAttemptState.OUTCOME_UNKNOWN)
                SystemCreateWorkStatus.UNKNOWN else SystemCreateWorkStatus.CAPTURED)
            return false
        }
        val guarded = guardedPort(target, epoch)
        if (priorAttempt?.state != CreateAttemptState.OUTCOME_UNKNOWN) {
            when (imports.stageLocalCreateSubmission(target, retained.localId, retained.captureId, retained.attempt, guarded)) {
                LocalCreateSubmissionResult.RETAINED -> Unit
                LocalCreateSubmissionResult.UNSUPPORTED -> { work.status(retained, SystemCreateWorkStatus.UNSUPPORTED); return false }
                LocalCreateSubmissionResult.UNAVAILABLE -> { work.status(retained, SystemCreateWorkStatus.WAITING); return true }
                else -> { work.status(retained, SystemCreateWorkStatus.WAITING); return false }
            }
            if (!active(target, epoch)) return false
            when (imports.resumePreparedLocalCreate(target, retained.localId, retained.captureId, retained.attempt, guarded, allowPaused = !providerWake)) {
                LocalCreateSubmissionResult.RETAINED -> Unit
                LocalCreateSubmissionResult.UNAVAILABLE -> { work.status(retained, SystemCreateWorkStatus.WAITING); return true }
                else -> { work.status(retained, SystemCreateWorkStatus.WAITING); return false }
            }
        }
        if (!active(target, epoch)) return false
        val submitted = imports.submitLocalCreate(target, retained.localId, retained.captureId, retained.attempt, guarded)
        if (!active(target, epoch)) return false
        if (submitted !is CreateSubmissionObservation.Verified) {
            work.status(retained, if (attempts.read(target, retained.attempt)?.state == CreateAttemptState.OUTCOME_UNKNOWN)
                SystemCreateWorkStatus.UNKNOWN else SystemCreateWorkStatus.WAITING)
            return false
        }
        if (imports.bindLocalCreateObservation(target, retained.localId, retained.captureId, retained.attempt, guarded)
            != CapturedCreateBindingResult.RETAINED) return waiting(retained)
        if (!active(target, epoch)) return false
        if (imports.attachLocalCreateBinding(target, retained.localId, retained.captureId, retained.attempt)
            != CapturedCreateAttachmentResult.ATTACHED) return waiting(retained)
        if (!active(target, epoch)) return false
        if (imports.retainLocalCreateBaseline(target, retained.localId, retained.captureId, retained.attempt)
            != CapturedCreateBaselineResult.RETAINED) return waiting(retained)
        if (!active(target, epoch)) return false
        val reconciliation = imports.reconcileLocalCreate(target, retained.localId, retained.captureId, retained.attempt, mutation)
        if (reconciliation != CapturedCreateReconciliationResult.UNCHANGED) {
            work.status(retained, if (reconciliation == CapturedCreateReconciliationResult.UNSUPPORTED)
                SystemCreateWorkStatus.UNSUPPORTED else SystemCreateWorkStatus.WAITING)
            return false
        }
        if (!active(target, epoch)) return false
        if (imports.acknowledgeLocalCreate(target, retained.localId, retained.captureId, retained.attempt)
            != CapturedCreateAcknowledgementResult.ACKNOWLEDGED) return waiting(retained)
        if (!active(target, epoch)) return false
        if (imports.qualifyAcknowledgedLocalCreate(target, retained.localId, retained.captureId, retained.attempt)
            != CapturedCreateCleanResult.CLEAN) return waiting(retained)
        if (!active(target, epoch)) return false
        if (imports.handoffLocalCreate(target, retained.localId, retained.captureId, retained.attempt)
            != CapturedCreateHandoffResult.HANDED_OFF) return waiting(retained)
        work.status(retained, SystemCreateWorkStatus.COMPLETE)
        return false
    }

    private suspend fun waiting(retained: SystemCreateWork): Boolean {
        work.status(retained, SystemCreateWorkStatus.WAITING)
        return false
    }

    /** Final claim reads only preferences/current metadata; ImportCoordinator owns deeper checks. */
    private fun guardedPort(target: CalendarScope, epoch: Long): CalendarCreateSubmissionPort =
        object : CalendarCreateSubmissionPort by port {
            override suspend fun submitCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft,
                journal: CreateAttemptJournal): CreateSubmissionObservation {
                if (!active(target, epoch)) return CreateSubmissionObservation.Unknown
                val guardedJournal = object : CreateAttemptJournal by journal {
                    override suspend fun claim(expected: CreateAttemptRecord): Boolean =
                        expected.scope == target && active(target, epoch) && journal.claim(expected)
                }
                return port.submitCaptured(record, expected, guardedJournal)
            }
        }

    private fun activeScopes(): List<CalendarScope> {
        val current = imports.state.value
        if (!current.authenticated || !current.hasPermission) return emptyList()
        return current.selections.filter { it.enabled && it.available && work.modeEnabled(it.scope) }.map { it.scope }
    }

    private fun active(target: CalendarScope, epoch: Long): Boolean =
        epochs[target]?.get() == epoch && target in activeScopes()

    private suspend fun publish() {
        val selections = imports.state.value.selections
        val enabled = selections.map { it.scope }.filter { work.modeEnabled(it) }.toSet()
        val pending = selections.associate { it.scope to work.count(it.scope) }
        val titles = selections.map { it.scope }.filter { work.titlesEnabled(it) }.toSet()
        val texts = selections.map { it.scope }.filter { work.textsEnabled(it) }.toSet()
        val times = selections.map { it.scope }.filter { work.timesEnabled(it) }.toSet()
        val deletes = selections.map { it.scope }.filter { work.deletesEnabled(it) }.toSet()
        val unsupported = selections.map { it.scope }.filter { legacyUnsupported(it) }.toSet()
        mutableState.update { it.copy(enabled = enabled, titlesEnabled = titles, textsEnabled = texts,
            modeReview = selections.map { it.scope }.filter { work.modeReviewRequired(it) }.toSet(),
            legacyUnsupported = unsupported,
            textSupportAvailable = textSupportAvailable, timesEnabled = times,
            timeSupportAvailable = timeSupportAvailable, deletesEnabled = deletes, deleteSupportAvailable = deleteSupportAvailable, pending = pending) }
    }

    private val observationMutex = Mutex()
    private suspend fun updateObserver(): Unit = observationMutex.withLock {
        val wanted = activeScopes().isNotEmpty()
        if (wanted && !observing) {
            context.contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, observer)
            observing = true
        } else if (!wanted && observing) {
            context.contentResolver.unregisterContentObserver(observer)
            observing = false
        }
        val app = context as? com.patmanak.proton.andcalendarbridge.BridgeApplication
        if (app != null && imports.state.value.selectionsLoaded) app.providerWake.setEnabled(wanted)
    }

    internal fun providerWakeScopes(): List<CalendarScope> = activeScopes()

    private fun cursor(target: CalendarScope, kind: String): Long = cursors.getLong(key(target, kind), 0)
    private fun saveCursor(target: CalendarScope, kind: String, row: Long) {
        check(row >= 0 && cursors.edit().putLong(key(target, kind), row).commit()) { "System create cursor was not persisted" }
    }
    private fun key(target: CalendarScope, kind: String) = "v1-$kind-" + framedDigest(target, kind, "")
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun mutation(target: CalendarScope, kind: String, value: String) =
        MutationId(UUID.nameUUIDFromBytes(framedDigest(target, kind, value)).toString())
    private fun framedDigest(target: CalendarScope, kind: String, value: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf("system-create-controller-v1", target.account.value, target.calendar.value,
            target.generation.toString(), kind, value).forEach {
            val bytes = it.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array()); digest.update(bytes)
        }
        return digest.digest()
    }

    private companion object { const val PAGE_SIZE = 25 }
}
