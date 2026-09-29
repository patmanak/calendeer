package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.Context
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionFailure
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogState
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogStatus
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogPort
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogResult
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ImportUiState(val selections: List<ImportStore.Selection> = emptyList(),
    val hasPermission: Boolean = false, val authenticated: Boolean = false, val busy: Boolean = false)
enum class CreateAssociationResult { INACTIVE, UNRESOLVED, WAITING_IMPORT, WAITING_PROVIDER, ASSOCIATED }
/** One process owner for foreground and durable worker triggers; no event content reaches UI. */
class ImportCoordinator(
    private val context: Context,
    val store: ImportStore,
    private val remote: ReadOnlyCalendarRemote,
    private val projection: CalendarProjection,
    private val scope: CoroutineScope,
    private val scheduler: ImportScheduler = WorkManagerImportScheduler(context),
) {
    private val mutex = Mutex()
    private val serial = AccountSerialExecutor()
    private val engine = SyncEngine(store, remote, serial)
    private val stagedEngine = (remote as? StagedCalendarRemote)?.let { StagedImportEngine(store, it, serial) }
    private val deltaEngine = (remote as? CalendarDeltaRemote)?.let { DeltaImportEngine(store, it, serial) }
    private val mutableState = MutableStateFlow(ImportUiState())
    val state = mutableState.asStateFlow()
    @Volatile private var authenticated = false
    @Volatile private var generation = 0L
    private val providerIds = context.getSharedPreferences("calendar-provider-mappings", Context.MODE_PRIVATE)

    fun authenticationChanged(value: Boolean) {
        if (authenticated == value) return
        authenticated = value
        generation++
        mutableState.value = ImportUiState(hasPermission = projection.hasPermissions(), authenticated = value)
        if (!value) {
            scheduler.cancel()
        } else {
            val expected = generation
            scope.launch {
                try {
                    refreshState()
                    if (state.value.selections.any { it.enabled }) requestSync()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (!recoverTransientStorage(failure, expected)) throw failure
                }
            }
            enqueueLocal()
        }
    }

    suspend fun rememberCatalog(catalog: CalendarCatalogState) {
        val account = catalog.account ?: return
        if (!authenticated || catalog.status != CalendarCatalogStatus.READY) return
        val expected = generation
        try {
            mutex.withLock {
                if (!isCurrent(expected, account)) return
                store.remember(account, catalog.calendars)
                refreshState()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (!recoverTransientStorage(failure, expected)) throw failure
        }
    }

    /** Experimental identity reconciliation only. Never submits, overwrites import data, or consumes
     * a Provider edit. The existing import owner excludes projection drains during this journey.
     * Persisted bindings can resume offline; every Android witness is freshly resolved.
     */
    suspend fun associateCreate(record: CreateAttemptRecord, journal: CreateAttemptStore,
        submissions: CalendarCreateSubmissionPort): CreateAssociationResult = mutex.withLock {
        val expected = generation
        val target = record.scope
        if (!isCurrent(expected, target.account)) return@withLock CreateAssociationResult.INACTIVE
        store.admitTransaction = { it.account == target.account && authenticated && generation == expected }
        journal.admitAssociation = { it == target && authenticated && generation == expected }
        projection.admitOperation = { authenticated && generation == expected }
        try {
            val selection = store.selections(target.account).singleOrNull { it.scope == target }
            if (selection?.enabled != true || !selection.available) return@withLock CreateAssociationResult.INACTIVE
            val unknown = journal.read(target, record.id) ?: return@withLock CreateAssociationResult.UNRESOLVED
            val identity = CreateAttemptIdentity.of(record)
            if (unknown.state != CreateAttemptState.OUTCOME_UNKNOWN || CreateAttemptIdentity.of(unknown) != identity)
                return@withLock CreateAssociationResult.UNRESOLVED
            val binding = journal.binding(identity) ?: run {
                val observed = submissions.inspect(unknown) as? CreateSubmissionObservation.Verified
                    ?: return@withLock CreateAssociationResult.UNRESOLVED
                if (observed.attempt != identity) return@withLock CreateAssociationResult.UNRESOLVED
                if (!isCurrent(expected, target.account)) return@withLock CreateAssociationResult.INACTIVE
                journal.bind(unknown, observed)
            }
            // Historical row numbers are not evidence of the current mirror's ownership or contents.
            journal.clearProjection(identity)
            val imported = store.stabilizedProjection(target, binding.eventId)
            if (imported == null) {
                enqueueLocal(); scheduler.requestNetwork()
                return@withLock CreateAssociationResult.WAITING_IMPORT
            }
            if (!projection.hasPermissions()) return@withLock CreateAssociationResult.WAITING_PROVIDER
            val witness = projection.inspectOwnedEvent(target, binding.eventId, imported.revision)
                ?: return@withLock CreateAssociationResult.WAITING_PROVIDER
            if (witness.revision != imported.revision) return@withLock CreateAssociationResult.WAITING_PROVIDER
            if (!isCurrent(expected, target.account)) return@withLock CreateAssociationResult.INACTIVE
            if (store.stabilizedProjection(target, binding.eventId) != imported)
                return@withLock CreateAssociationResult.WAITING_IMPORT
            journal.retainProjection(binding, witness)
            CreateAssociationResult.ASSOCIATED
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: CalendarProjectionException) { CreateAssociationResult.WAITING_PROVIDER }
          catch (_: Exception) { CreateAssociationResult.UNRESOLVED }
    }

    fun permissionsChanged() {
        val previouslyGranted = state.value.hasPermission
        mutableState.value = state.value.copy(hasPermission = projection.hasPermissions())
        if (authenticated && projection.hasPermissions()) enqueueLocal()
        if (!previouslyGranted && state.value.hasPermission && state.value.selections.any { it.enabled }) requestSync()
    }

    fun setEnabled(selection: ImportStore.Selection, enabled: Boolean) {
        scope.launch {
            mutex.withLock {
                if (!isCurrent(generation, selection.scope.account)) return@withLock
                store.setEnabled(selection.scope, enabled)
                refreshState()
            }
            if (enabled) requestSync()
        }
    }

    fun requestSync() {
        if (!authenticated) return
        scope.launch {
            val requestGeneration = generation
            try {
            mutex.withLock {
                val expected = generation
                val account = remote.currentAccount() ?: return@launch
                if (!isCurrent(expected, account)) return@launch
                store.admitTransaction = { it.account == account && authenticated && generation == expected }
                // Arm local reprocessing before scheduling a network-constrained worker.
                // A supported mapper upgrade can recover old receipts even while offline.
                for (selection in store.selections(account).filter { it.enabled }) {
                    store.prepareDeferredRetry(selection.scope)
                }
            }
            if (authenticated) {
                enqueueLocal()
                scheduler.requestNetwork()
            }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                // A transient store failure needs a durable trigger even when selections
                // cannot be read. Other failures keep the previous data and show an error.
                if (recoverTransientStorage(failure, requestGeneration)) return@launch
                if (generation == requestGeneration) mutableState.value = state.value.copy(
                    selections = state.value.selections.map { if (it.enabled) it.copy(status = "ERROR") else it })
            }
        }
    }

    private fun enqueueLocal() {
        scheduler.requestLocal()
    }

    /** A failed SQLite read cannot tell us which selections need a refresh. Retry durably. */
    private fun recoverTransientStorage(failure: Exception, expected: Long): Boolean {
        if (!retryableImportFailure(failure)) return false
        if (authenticated && generation == expected) {
            enqueueLocal()
            scheduler.requestNetwork()
        }
        return true
    }

    fun enqueueContinuation(network: Boolean) {
        if (!authenticated) return
        scheduler.continueWork(network)
    }

    /** Successful continuation and transient retry are distinct; the store owns pending work. */
    suspend fun run(network: Boolean, fresh: Boolean = true): ImportRunResult = mutex.withLock {
        val expected = generation
        val account = remote.currentAccount() ?: return ImportRunResult.COMPLETE
        if (!isCurrent(expected, account)) return ImportRunResult.COMPLETE
        store.admitTransaction = { it.account == account && authenticated && generation == expected }
        projection.admitOperation = { authenticated && generation == expected }
        mutableState.value = state.value.copy(busy = true)
        var retry = false
        var continuation = false
        var runFailure: Exception? = null
        try {
            // Re-establish access on retries too; WorkManager retries are not fresh runs.
            if (network && remote is CalendarCatalogPort) {
                val enabled = store.selections(account).filter { it.enabled && it.available }
                if (enabled.isNotEmpty() && enabled.all { it.nextAttempt > System.currentTimeMillis() })
                    return ImportRunResult.RETRY
                when (val catalog = remote.load()) {
                    is CalendarCatalogResult.Success -> {
                        if (catalog.account != account || !isCurrent(expected, account)) return ImportRunResult.COMPLETE
                        store.remember(account, catalog.calendars)
                    }
                    is CalendarCatalogResult.Failure -> {
                        val status = when (catalog.category) {
                            CalendarCatalogFailure.AUTHENTICATION_REQUIRED -> "AUTH"
                            CalendarCatalogFailure.UNSUPPORTED_RESPONSE, CalendarCatalogFailure.FORBIDDEN -> "UNSUPPORTED"
                            else -> "NETWORK"
                        }
                        for (item in store.selections(account).filter { it.enabled && it.available }) {
                            store.status(item.scope, status)
                            catalog.retryAfterMillis?.let { store.deferNetwork(item.scope, it) }
                        }
                        return ImportRunResult.RETRY
                    }
                }
            }
            for (selection in store.selections(account).filter { it.enabled && it.available }) {
                if (!isCurrent(expected, account)) return ImportRunResult.COMPLETE
                val calendarScope = selection.scope
                if (!projection.hasPermissions()) {
                    store.status(calendarScope, "PERMISSION")
                    continue
                }
                try {
                    store.status(calendarScope, "RUNNING")
                    if (!isCurrent(expected, account)) return ImportRunResult.COMPLETE
                    val ensuredCalendar = projection.ensureCalendarState(calendarScope, selection.summary)
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
                    val deltaReady = deltaEngine?.prepare(calendarScope, allowNetwork, fresh || mappingPending) ?: true
                    if (allowNetwork && remote is CalendarDeltaRemote) remote.refreshContext(calendarScope)
                    if (allowNetwork && (fresh || mappingPending)) {
                        serial.run(account) {
                            // Finish existing obligations before beginning another moving-window scan.
                            if (canRefresh && !rolling) store.beginRefresh(calendarScope)
                            store.prepareDeferredRetry(calendarScope)
                        }
                    }
                    suspend fun drain(): Boolean {
                        for (intent in store.pending(calendarScope)) {
                            if (!isCurrent(expected, account)) throw CancellationException()
                            projection.apply(intent, if (intent.change is RemoteChange.Deleted)
                                store.deletedSeriesUid(calendarScope, intent.change.eventId) else null)
                            store.acknowledge(intent)
                        }
                        return store.pending(calendarScope, 1).isNotEmpty()
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
                        val progress = if (deltaReady) stagedEngine.run(calendarScope, allowNetwork = allowNetwork)
                            else ImportProgress(0, false)
                        val deltaProgress = if (deltaReady && progress.traversalComplete)
                            deltaEngine?.run(calendarScope, allowNetwork) else null
                        val receivedPending = store.transaction(calendarScope) { (this as StagedSyncTransaction).received != null }
                        remaining = drain() || receivedPending || store.pendingDeferredRetries(calendarScope, 1).isNotEmpty() ||
                            (deltaProgress?.workRemaining == true && (network || !deltaProgress.needsNetwork))
                        val incompleteNetwork = network && !deferredNetwork && !progress.traversalComplete
                        val localProgress = progress.pagesCommitted > 0 || recoveredLocally || (deltaProgress?.processed ?: 0) > 0
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
                        if (!isCurrent(expected, account)) return ImportRunResult.COMPLETE
                        store.status(calendarScope, status,
                            if (!blocked && progress.traversalComplete && !remaining && deltaProgress?.blocked != true &&
                                deltaProgress?.workRemaining != true &&
                                !historyGap && !missingSeries && !mappingRemaining &&
                                (localProgress || selection.completedAt == null || selection.status !in setOf("IMPORTED", "PARTIAL")))
                                System.currentTimeMillis() else null)
                        retry = retry || deferredNetwork
                        remaining = remaining || incompleteNetwork || (allowNetwork && mappingRemaining)
                    } else if (network && selection.nextAttempt > System.currentTimeMillis()) {
                        retry = true
                        store.status(calendarScope, "PENDING")
                    } else if (network && !remaining) {
                        val progress = engine.importPages(calendarScope)
                        remaining = drain() || !progress.traversalComplete || store.mappingRescanPending(calendarScope)
                        val missingSeries = store.blockedSeriesCount(calendarScope) > 0
                        if (!isCurrent(expected, account)) return ImportRunResult.COMPLETE
                        store.status(calendarScope, if (remaining) "PENDING" else if (missingSeries) "SERIES_PENDING"
                            else if (selection.historyGap) "HISTORY_GAP" else "IMPORTED",
                            if (remaining || selection.historyGap || missingSeries) null else System.currentTimeMillis())
                    } else {
                        // Local work must not overwrite the reason the last network run was blocked.
                        val blocked = selection.status in setOf("NETWORK", "UNSUPPORTED", "ERROR", "AUTH", "CAPACITY", "LOCAL_KEYS")
                        store.status(calendarScope, if (blocked) selection.status else if (remaining || store.mappingRescanPending(calendarScope)) "PENDING" else
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
                    store.status(calendarScope, "PERMISSION")
                } catch (failure: CalendarProjectionException) {
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
                    store.status(calendarScope, "ERROR")
                }
            }
        } catch (failure: Exception) {
            runFailure = failure
            throw failure
        } finally {
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
                if (network && authenticated && runFailure == null) enqueueLocal()
            }
        }
        when { retry -> ImportRunResult.RETRY; continuation -> ImportRunResult.CONTINUE; else -> ImportRunResult.COMPLETE }
    }

    private suspend fun isCurrent(expected: Long, account: AccountId) =
        authenticated && generation == expected && remote.currentAccount() == account

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

    private suspend fun refreshState() {
        val expected = generation
        val account = if (authenticated) remote.currentAccount() else null
        val selections = if (account == null) emptyList() else store.selections(account)
        if (generation == expected) mutableState.value = state.value.copy(selections = selections,
            authenticated = authenticated, hasPermission = projection.hasPermissions())
    }
}
