// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
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
    val pending: Map<CalendarScope, Int> = emptyMap(),
    val busy: Boolean = false,
    val failed: Boolean = false,
) {
    override fun toString() = "SystemCreateUiState(REDACTED, busy=$busy, failed=$failed)"
}

/**
 * Explicit opt-in system CREATE trigger. Native edits/deletes are retained without uploading.
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
) {
    private val context = context.applicationContext
    private val mutableState = MutableStateFlow(SystemCreateUiState())
    val state: StateFlow<SystemCreateUiState> = mutableState.asStateFlow()
    private val mutex = Mutex()
    private val started = AtomicBoolean()
    private val epochs = ConcurrentHashMap<CalendarScope, AtomicLong>()
    private val cursors = this.context.getSharedPreferences("system-create-cursors", Context.MODE_PRIVATE)
    private var observing = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { request() }
    }

    private data class ScopeSignal(val scope: CalendarScope, val selected: Boolean,
        val available: Boolean, val mode: Boolean)
    private data class Signal(val authenticated: Boolean, val permission: Boolean,
        val selections: List<ScopeSignal>)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            imports.state.map { current -> Signal(current.authenticated, current.hasPermission,
                current.selections.map { ScopeSignal(it.scope, it.enabled, it.available, work.modeEnabled(it.scope)) }) }
                .distinctUntilChanged().collect {
                    try {
                        updateObserver()
                        imports.reconcileSystemCalendarAccess()
                        publish()
                        request()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { mutableState.update { state -> state.copy(failed = true) } }
                }
        }
    }

    /** Disable and epoch invalidation happen synchronously, even while network work is suspended. */
    fun setEnabled(selection: ImportStore.Selection, enabled: Boolean) {
        val target = selection.scope
        val current = imports.state.value
        if (enabled && (!current.authenticated || !current.hasPermission ||
            current.selections.none { it.scope == target && it.enabled && it.available })) return
        work.setMode(target, enabled)
        epochs.computeIfAbsent(target) { AtomicLong() }.incrementAndGet()
        imports.systemCreatePolicyChanged(target, enabled)
        scope.launch {
            try {
                updateObserver()
                imports.configureSystemCalendar(target)
                publish()
                request()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(failed = true) } }
        }
    }

    fun request() {
        if (activeScopes().isEmpty()) return
        scheduler.requestLocal()
        scheduler.requestNetwork()
    }

    /** One bounded page per selected scope; unknown outcomes never cause immediate retry loops. */
    suspend fun run(network: Boolean): ImportRunResult = mutex.withLock {
        mutableState.update { it.copy(busy = true, failed = false) }
        var more = false
        var retry = false
        try {
            for (target in activeScopes()) {
                val epoch = epochs.computeIfAbsent(target) { AtomicLong() }.get()
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
                            mutableState.update { it.copy(failed = true) }
                        }
                        SystemCalendarChangeKind.IMPORTED -> {
                            val event = change.event
                            if (event == null) mutableState.update { it.copy(failed = true) }
                            else {
                                val mutation = mutation(target, "imported-local-change", event.value)
                                val result = if (change.deleted) imports.captureLocalDelete(target, event, mutation)
                                    else imports.captureLocalEdit(target, event, mutation)
                                if (result == LocalEditCaptureResult.UNAVAILABLE) retry = true
                                if (result == LocalEditCaptureResult.UNQUALIFIED)
                                    mutableState.update { it.copy(failed = true) }
                            }
                        }
                        SystemCalendarChangeKind.CAPTURED -> {
                            // A stamped row without the identity journal is never adopted by row ID.
                            val retained = work.read(target, change.row)
                            if (retained == null) mutableState.update { it.copy(failed = true) }
                            else if (retained.status == SystemCreateWorkStatus.COMPLETE) {
                                val result = imports.reconcileHandoffLocalCreate(target, retained.localId,
                                    retained.captureId, retained.attempt, mutation(target, "captured-local-change", retained.captureId.value))
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
                    if (process(retained, network, epoch)) retry = true
                }
                if (!active(target, epoch)) continue
                val after = pending.lastOrNull()?.row ?: 0
                val further = after > 0 && work.page(target, after, 1).isNotEmpty()
                saveCursor(target, "jobs", if (further) after else 0)
                more = more || further
            }
            publish()
            when { retry -> ImportRunResult.RETRY; more -> ImportRunResult.CONTINUE; else -> ImportRunResult.COMPLETE }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.update { it.copy(failed = true) }
            ImportRunResult.RETRY
        } finally { mutableState.update { it.copy(busy = false) } }
    }

    /** True requests backoff only for a known transient failure, never for UNKNOWN alone. */
    private suspend fun process(retained: SystemCreateWork, network: Boolean, epoch: Long): Boolean {
        val target = retained.scope
        if (!active(target, epoch)) return false
        val priorAttempt = attempts.read(target, retained.attempt)
        val mutation = mutation(target, "captured-local-change", retained.captureId.value)
        if (priorAttempt?.state == CreateAttemptState.OUTCOME_UNKNOWN) {
            // Recover a completed handoff whose final queue status was lost, or preserve later edits.
            when (imports.reconcileHandoffLocalCreate(target, retained.localId, retained.captureId, retained.attempt, mutation)) {
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
        when (imports.captureLocalCreate(target, retained.row, retained.localId, retained.captureId, retained.timestamp)) {
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
            when (imports.resumePreparedLocalCreate(target, retained.localId, retained.captureId, retained.attempt, guarded)) {
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
        mutableState.update { it.copy(enabled = enabled, pending = pending) }
    }

    @Synchronized private fun updateObserver() {
        val wanted = activeScopes().isNotEmpty()
        if (wanted && !observing) {
            context.contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, observer)
            observing = true
        } else if (!wanted && observing) {
            context.contentResolver.unregisterContentObserver(observer)
            observing = false
        }
    }

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
