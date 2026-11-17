package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.SharedPreferences
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionFailure
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Incoming work scheduling and bounded calendar passes under the coordinator's existing mutex. */
internal class ImportCoordinatorIncoming(
    private val store: ImportStore,
    private val remote: ReadOnlyCalendarRemote,
    private val projection: CalendarProjection,
    private val scope: CoroutineScope,
    private val mutex: Mutex,
    private val scheduler: ImportScheduler,
    private val serial: AccountSerialExecutor,
    private val engine: SyncEngine,
    private val stagedEngine: StagedImportEngine?,
    private val deltaEngine: DeltaImportEngine?,
    private val providerIds: SharedPreferences,
    private val state: StateFlow<ImportUiState>,
    private val mutableState: MutableStateFlow<ImportUiState>,
    private val projectionWork: ImportCoordinatorProjection,
    private val generation: () -> Long,
    private val authenticated: () -> Boolean,
    private val policyRevision: () -> Long,
    private val systemCreatesEnabled: (CalendarScope) -> Boolean,
    private val hasCalendarPermissions: () -> Boolean,
    private val currentObservedAccount: suspend () -> AccountId?,
    private val isCurrent: suspend (Long, AccountId) -> Boolean,
    private val rememberAccessibleCatalog: suspend (AccountId, List<CalendarSummary>) -> Unit,
    private val refreshState: suspend () -> Unit,
    private val retainCreatePermissionLoss: () -> Unit,
    private val beforeNativeCreation: suspend () -> Boolean,
) {
    fun requestSync() {
        if (!authenticated()) return
        scope.launch {
            val requestGeneration = generation()
            try {
            mutex.withLock {
                val expected = generation()
                val account = currentObservedAccount() ?: return@launch
                if (!isCurrent(expected, account)) return@launch
                store.admitTransaction = { it.account == account && authenticated() && generation() == expected }
                // Arm local reprocessing before scheduling a network-constrained worker.
                // A supported mapper upgrade can recover old receipts even while offline.
                for (selection in store.selections(account).filter { it.enabled }) {
                    store.prepareDeferredRetry(selection.scope)
                }
            }
            if (authenticated()) {
                enqueueLocal()
                scheduler.requestNetwork()
            }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A transient store failure needs a durable trigger even when selections
                // cannot be read. Other failures keep the previous data and show an error.
                if (recoverTransientStorage(failure, requestGeneration)) return@launch
                if (generation() == requestGeneration) mutableState.value = state.value.copy(
                    selections = state.value.selections.map { if (it.enabled) it.copy(status = "ERROR") else it })
            }
        }
    }

    fun enqueueLocal() {
        scheduler.requestLocal()
    }

    /** A failed SQLite read cannot tell us which selections need a refresh. Retry durably. */
    fun recoverTransientStorage(failure: Exception, expected: Long): Boolean {
        if (!retryableImportFailure(failure)) return false
        if (authenticated() && generation() == expected) {
            enqueueLocal()
            scheduler.requestNetwork()
        }
        return true
    }

    fun enqueueContinuation(network: Boolean) {
        if (!authenticated()) return
        scheduler.continueWork(network)
    }

    /** Successful continuation and transient retry are distinct; the store owns pending work. */
    suspend fun run(network: Boolean, fresh: Boolean, expectedAccount: AccountId?, pollOnRetry: Boolean = false): CalendarPassResult =
        mutex.withLock { runLocked(network, fresh, expectedAccount, pollOnRetry) }

    private suspend fun refreshAccess(account: AccountId, expected: Long): ImportRunResult? =
        refreshImportAccess(remote, store, account, { isCurrent(expected, account) }, rememberAccessibleCatalog)

    private suspend fun bootstrapInitialPage(target: CalendarScope, allowNetwork: Boolean,
        deltaReady: Boolean): ImportProgress {
        val engine = stagedEngine ?: return ImportProgress(0, false)
        if (!deltaReady || store.transaction(target) { checkpoint != null }) return ImportProgress(0, false)
        return engine.run(target, allowNetwork = allowNetwork, maxPages = 1)
    }

    private suspend fun runLocked(network: Boolean, fresh: Boolean, expectedAccount: AccountId?, pollOnRetry: Boolean): CalendarPassResult {
        val expected = generation()
        val skipped = CalendarPassResult(ImportRunResult.COMPLETE)
        val account = currentObservedAccount() ?: return skipped
        if (expectedAccount != null && account != expectedAccount) return skipped
        if (!isCurrent(expected, account)) return skipped
        store.admitTransaction = { it.account == account && authenticated() && generation() == expected }
        projection.admitOperation = { authenticated() && generation() == expected }
        mutableState.value = state.value.copy(busy = true)
        var retry = false
        var continuation = false
        var networkSynchronized: Boolean
        var runFailure: Exception? = null
        try {
            // Re-establish access on retries too; WorkManager retries are not fresh runs.
            if (network) refreshAccess(account, expected)?.let { return CalendarPassResult(it) }
            val selected = store.selections(account).filter { it.enabled }
            networkSynchronized = network && selected.isNotEmpty() && selected.all { it.available }
            for (selection in selected.filter { it.available }) {
                if (!isCurrent(expected, account)) return skipped
                val calendarScope = selection.scope
                if (!hasCalendarPermissions()) {
                    store.status(calendarScope, "PERMISSION")
                    networkSynchronized = false
                    continue
                }
                val result = runCalendar(selection, calendarScope, account, expected, network, fresh, pollOnRetry) ?: return skipped
                networkSynchronized = networkSynchronized && result.networkSynchronized
                when (result.work) {
                    ImportRunResult.RETRY -> retry = true
                    ImportRunResult.CONTINUE -> continuation = true
                    ImportRunResult.COMPLETE -> Unit
                }
            }
        } catch (failure: Exception) {
            runFailure = failure
            throw failure
        } finally {
            finishImportRun(network, runFailure)
        }
        val work = when { retry -> ImportRunResult.RETRY; continuation -> ImportRunResult.CONTINUE; else -> ImportRunResult.COMPLETE }
        return CalendarPassResult(work, networkSynchronized && work == ImportRunResult.COMPLETE && isCurrent(expected, account))
    }

    /** One calendar pass; null preserves an inactive epoch's early exit from the entire run.
     * Keep its suspensions and status handlers outside the run's diagnostic finally. */
    private suspend fun runCalendar(selection: ImportStore.Selection, calendarScope: CalendarScope,
        account: AccountId, expected: Long, network: Boolean, fresh: Boolean, pollOnRetry: Boolean): CalendarPassResult? {
        var retry = false
        var continuation = false
        var networkSynchronized = false
        try {
            store.status(calendarScope, "RUNNING")
            if (!isCurrent(expected, account)) return null
            if (!beforeNativeCreation()) return if (isCurrent(expected, account)) CalendarPassResult(ImportRunResult.RETRY) else null
            if (!isCurrent(expected, account)) return null
            val createsMode = systemCreatesEnabled(calendarScope)
            val accessPolicy = policyRevision()
            projection.admitOperation = { authenticated() && generation() == expected &&
                policyRevision() == accessPolicy && systemCreatesEnabled(calendarScope) == createsMode }
            val ensuredCalendar = try {
                if (createsMode) projection.ensureCalendarState(calendarScope, selection.summary, true)
                else projection.ensureCalendarState(calendarScope, selection.summary)
            } finally { projection.admitOperation = { authenticated() && generation() == expected } }
            val providerId = ensuredCalendar.id
            val providerKey = projection.accountFor(account).name + ":" + selection.scope.calendar.value
            if (ensuredCalendar.created || providerIds.getLong(providerKey, -1) != providerId) {
                store.requeueAll(calendarScope)
                check(providerIds.edit().putLong(providerKey, providerId).commit())
            }
            val allowNetwork = network && selection.nextAttempt <= System.currentTimeMillis()
            if (remote is ContentMappingRemote)
                store.ensureMappingProfile(calendarScope, remote.contentMapping)
            val mappingPending = store.mappingRescanPending(calendarScope)
            // Evaluate before rearming blocked reads: a missing ID must not permanently
            // prevent unchanged events from entering the rolling import window.
            val canRefresh = deltaEngine == null || store.transaction(calendarScope) {
                val delta = this as DeltaSyncTransaction
                delta.deltaReceived == null && delta.deltaJobs(1).isEmpty()
            }
            // Admit an extension before prepare rearms deltaMore. Both the planned
            // checkpoint and the post-scan feed check survive a crash at this boundary.
            val rolling = allowNetwork && fresh && !mappingPending && tryRollingRefresh(selection)
            val deltaReady = deltaEngine?.prepare(calendarScope, allowNetwork, fresh || mappingPending, pollOnRetry) ?: true
            if (allowNetwork && remote is CalendarDeltaRemote) remote.refreshContext(calendarScope)
            if (allowNetwork && (fresh || mappingPending)) {
                serial.run(account) {
                    // Finish existing obligations before beginning another moving-window scan.
                    if (canRefresh && !rolling) store.beginRefresh(calendarScope)
                    store.prepareDeferredRetry(calendarScope)
                }
            }
            // A full refresh clears the old checkpoint before requeuing native work.
            // Commit its first staged page before draining: a DIRTY row may reject
            // projection, and capture needs a confirmed observation format to proceed.
            // Offline this only decodes an already retained page; it never invents one.
            val retainedPage = allowNetwork && stagedEngine != null && deltaEngine == null &&
                store.transaction(calendarScope) { (this as StagedSyncTransaction).received != null }
            val bootstrap = bootstrapInitialPage(calendarScope, allowNetwork, deltaReady)
            if (!isCurrent(expected, account)) return null
            suspend fun drain(): Boolean {
                for (intent in projectionWork.runnableProjections(calendarScope)) {
                    if (!isCurrent(expected, account)) throw CancellationException()
                    try { projectionWork.projectPending(intent, expected) }
                    catch (failure: CalendarProjectionException) {
                        val racedLocalChange = failure.category in setOf(CalendarProjectionFailure.PROVIDER_REJECTED,
                            CalendarProjectionFailure.IDENTITY_CONFLICT) && projectionWork.nativeChangesBlock(intent)
                        if (failure.category != CalendarProjectionFailure.LOCAL_CHANGE_PENDING && !racedLocalChange) throw failure
                        // Retain the exact intent and receipt; a fresh observation may
                        // rearm it, but this continuation must let other events progress.
                        store.holdLocalProjection(intent)
                    }
                }
                return projectionWork.runnableProjections(calendarScope, 1).isNotEmpty()
            }
            var remaining = drain()
            if (stagedEngine != null && !remaining) {
                var recoveredLocally = false
                for (event in store.pendingDeferredRetries(calendarScope)) {
                    if (!isCurrent(expected, account)) throw CancellationException()
                    recoveredLocally = stagedEngine.retryDeferred(calendarScope, event) || recoveredLocally
                    store.acknowledgeDeferredRetry(calendarScope, event)
                }
                val deferredNetwork = network && selection.nextAttempt > System.currentTimeMillis()
                val progress = if (deltaReady) stagedEngine.run(calendarScope, allowNetwork = allowNetwork,
                    maxPages = 4 - bootstrap.pagesCommitted)
                    else ImportProgress(0, false)
                val deltaProgress = if (deltaReady && progress.traversalComplete)
                    deltaEngine?.run(calendarScope, allowNetwork) else null
                val receivedPending = store.transaction(calendarScope) { (this as StagedSyncTransaction).received != null }
                remaining = drain() || receivedPending || store.pendingDeferredRetries(calendarScope, 1).isNotEmpty() ||
                    (deltaProgress?.workRemaining == true && (network || !deltaProgress.needsNetwork))
                val incompleteNetwork = network && !deferredNetwork && !progress.traversalComplete
                val localProgress = bootstrap.pagesCommitted + progress.pagesCommitted > 0 ||
                    recoveredLocally || (deltaProgress?.processed ?: 0) > 0
                val blocked = !network && (selection.status in setOf("NETWORK", "ERROR", "AUTH") ||
                    !localProgress && selection.status in setOf("UNSUPPORTED", "CAPACITY", "LOCAL_KEYS"))
                val historyGap = selection.historyGap || deltaProgress?.historyGap == true
                val missingSeries = store.blockedSeriesCount(calendarScope) > 0
                val mappingRemaining = store.mappingRescanPending(calendarScope)
                val status = when {
                    blocked -> selection.status
                    mappingRemaining || deferredNetwork || remaining || !progress.traversalComplete || deltaProgress?.workRemaining == true -> "PENDING"
                    deltaProgress?.blocked == true -> "INCOMPLETE"
                    missingSeries -> "SERIES_PENDING"
                    store.deferredCount(calendarScope) > 0 -> "PARTIAL"
                    historyGap -> "HISTORY_GAP"
                    else -> "IMPORTED"
                }
                if (!isCurrent(expected, account)) return null
                val importComplete = projectionWork.publishImportProgress(calendarScope, status,
                    if (!blocked && progress.traversalComplete && !remaining && deltaProgress?.blocked != true &&
                        deltaProgress?.workRemaining != true &&
                        !historyGap && !missingSeries && !mappingRemaining &&
                        (localProgress || selection.completedAt == null || selection.status !in setOf("IMPORTED", "PARTIAL")))
                        System.currentTimeMillis() else null)
                val readCalendarData = deltaEngine != null || bootstrap.pagesCommitted + progress.pagesCommitted > (if (retainedPage) 1 else 0)
                networkSynchronized = allowNetwork && importComplete && readCalendarData
                retry = retry || deferredNetwork
                remaining = remaining || incompleteNetwork || (allowNetwork && mappingRemaining)
            } else if (network && selection.nextAttempt > System.currentTimeMillis()) {
                retry = true
                projectionWork.publishImportProgress(calendarScope, "PENDING")
            } else if (network && !remaining) {
                val progress = engine.importPages(calendarScope)
                remaining = drain() || !progress.traversalComplete || store.mappingRescanPending(calendarScope)
                val missingSeries = store.blockedSeriesCount(calendarScope) > 0
                if (!isCurrent(expected, account)) return null
                val importComplete = projectionWork.publishImportProgress(calendarScope, if (remaining) "PENDING" else if (missingSeries) "SERIES_PENDING"
                    else if (selection.historyGap) "HISTORY_GAP" else "IMPORTED",
                    if (remaining || selection.historyGap || missingSeries)
                        null else System.currentTimeMillis())
                networkSynchronized = importComplete && progress.pagesCommitted > 0
            } else {
                // Local work must not overwrite the reason the last network run was blocked.
                val blocked = selection.status in setOf("NETWORK", "UNSUPPORTED", "ERROR", "AUTH", "CAPACITY", "LOCAL_KEYS")
                projectionWork.publishImportProgress(calendarScope, if (blocked) selection.status else if (remaining || store.mappingRescanPending(calendarScope)) "PENDING" else
                    if (store.complete(calendarScope)) {
                        if (store.blockedSeriesCount(calendarScope) > 0) "SERIES_PENDING"
                        else if (store.deferredCount(calendarScope) > 0) "PARTIAL"
                        else if (selection.historyGap) "HISTORY_GAP" else "IMPORTED"
                    } else "PENDING")
            }
            continuation = continuation || remaining
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ImportCapacityException) {
            store.status(calendarScope, "CAPACITY")
        } catch (_: SecurityException) {
            retainCreatePermissionLoss()
            store.status(calendarScope, "PERMISSION")
        } catch (failure: CalendarProjectionException) {
            if (failure.category == CalendarProjectionFailure.PERMISSION_REQUIRED) retainCreatePermissionLoss()
            val recoverable = retryableImportFailure(failure)
            retry = retry || recoverable
            // A local retry must clear itself after a successful offline drain. ERROR
            // remains reserved for blocked failures which local work cannot resolve.
            store.status(calendarScope, if (recoverable) {
                if (selection.status in setOf("NETWORK", "ERROR", "AUTH", "UNSUPPORTED", "CAPACITY", "LOCAL_KEYS"))
                    selection.status else "PENDING"
            } else
                if (failure.category == CalendarProjectionFailure.PERMISSION_REQUIRED) "PERMISSION" else "ERROR")
        } catch (failure: RemoteImportException) {
            val status = when (failure.reason) {
                RemoteImportFailure.AUTHENTICATION -> "AUTH"
                RemoteImportFailure.NETWORK -> "NETWORK"
                RemoteImportFailure.UNSUPPORTED -> "UNSUPPORTED"
                RemoteImportFailure.LOCAL_KEYS -> "LOCAL_KEYS"
                RemoteImportFailure.CAPACITY -> "CAPACITY"
                RemoteImportFailure.NOT_FOUND -> "ERROR"
                RemoteImportFailure.RETRY -> "ERROR"
            }
            store.status(calendarScope, status)
            failure.retryAfterMillis?.let { store.deferNetwork(calendarScope, it) }
            retry = retry || failure.reason == RemoteImportFailure.NETWORK || failure.reason == RemoteImportFailure.RETRY
        } catch (failure: Exception) {
            // Storage errors can also happen while publishing status or refreshing UI.
            // Let the worker boundary back off without depending on another DB write.
            if (retryableImportFailure(failure)) throw failure
            store.status(calendarScope, if (failure is ImportCoordinatorProjection.Pending) "CREATE_PENDING" else "ERROR")
        }
        val work = when { retry -> ImportRunResult.RETRY; continuation -> ImportRunResult.CONTINUE; else -> ImportRunResult.COMPLETE }
        return CalendarPassResult(work, networkSynchronized)
    }

    // Keep suspending diagnostic cleanup outside the main import coroutine's state machine.
    private suspend fun finishImportRun(network: Boolean, failure: Exception?) {
        var runFailure = failure
        try {
            refreshState()
        } catch (refreshFailure: Exception) {
            // Preserve the original cancellation/failure if storage is also unavailable
            // while refreshing diagnostics. Cleanup must never keep the UI busy.
            if (runFailure == null) {
                runFailure = refreshFailure
                throw refreshFailure
            }
        } finally {
            mutableState.value = state.value.copy(busy = false)
            if (network && authenticated() && runFailure == null) enqueueLocal()
        }
    }

    private suspend fun tryRollingRefresh(selection: ImportStore.Selection): Boolean {
        val planner = remote as? RollingCalendarRemote ?: return false
        val deltaRemote = remote as? CalendarDeltaRemote ?: return false
        if (selection.historyGap || selection.deferredCount != 0) return false
        val target = selection.scope
        if (store.blockedSeriesCount(target) > 0) return false
        return serial.run(target.account) {
            val known = store.transaction(target) {
                val delta = this as DeltaSyncTransaction
                val run = delta.coverage.active
                if (checkpoint?.traversalComplete != true || checkpoint?.format != remote.format ||
                    delta.received != null || delta.deltaCursor?.format != deltaRemote.deltaFormat ||
                    delta.deltaMore || delta.deltaReceived != null || delta.deltaJobs(1).isNotEmpty() ||
                    delta.deltaJobs(1, true).isNotEmpty() || run?.finished != true ||
                    run.appliedWindows != run.windows.size) null else delta.coverage
            } ?: return@run false
            val windows = RollingImportPlanner.missing(known.observed, remote.format, planner.queryHorizon(target))
                ?: return@run false
            if (windows.isEmpty()) {
                // Retain repair of unsupported edits made directly to the read-only mirror.
                store.beginDeltaRefresh(target, known)
            } else {
                store.beginRefresh(target, planner.planWindows(target, windows), known)
            }
            true
        }
    }

}
