package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.Context
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionFailure
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreatePauseReason
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogState
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogStatus
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogPort
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogResult
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogFailure
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ImportUiState(val selections: List<ImportStore.Selection> = emptyList(),
    val hasPermission: Boolean = false, val authenticated: Boolean = false, val busy: Boolean = false)
enum class CreateAssociationResult { INACTIVE, UNRESOLVED, WAITING_IMPORT, WAITING_PROVIDER, ASSOCIATED }
enum class LocalEditCaptureResult { INACTIVE, UNQUALIFIED, RETAINED, CONFLICT, UNAVAILABLE }
enum class LocalCreatePreparationResult { INACTIVE, UNQUALIFIED, RETAINED, UNSUPPORTED, UNAVAILABLE }
enum class LocalCreateSubmissionResult { INACTIVE, UNQUALIFIED, RETAINED, UNRESOLVED, UNSUPPORTED, UNAVAILABLE }
/** One process owner for foreground and durable worker triggers; no event content reaches UI. */
class ImportCoordinator(
    private val context: Context,
    val store: ImportStore,
    private val remote: ReadOnlyCalendarRemote,
    private val projection: CalendarProjection,
    private val scope: CoroutineScope,
    private val scheduler: ImportScheduler = WorkManagerImportScheduler(context),
    private val creates: CreateAttemptStore? = null,
    private val localCreates: LocalCreateStore? = null,
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
    private val createPolicyRevision = AtomicLong()
    private var createInitialized = false
    private var createGeneration = -1L
    private val createAccount = AtomicReference<AccountId?>()
    private val pendingCreatePauses = ConcurrentHashMap<CalendarScope, CreatePauseReason>()
    private val pendingAccountPauses = ConcurrentHashMap.newKeySet<AccountId>()
    private data class CapturedSubmissionLease(val identity: CapturedCreateDispatchIdentity, val generation: Long, val policy: Long)
    // Only a newly retained preparation in this owner can populate this map. Cache reads never rearm it.
    private val freshCapturedSubmissions = ConcurrentHashMap<Pair<CalendarScope, MutationId>, CapturedSubmissionLease>()
    @Volatile private var pendingPermissionPause = false
    private val providerIds = context.getSharedPreferences("calendar-provider-mappings", Context.MODE_PRIVATE)

    init {
        creates?.admitDispatch = { false }
        localCreates?.admitCapture = { false }
    }

    /** Production startup is conservative: legacy preparations require explicit future resolution. */
    suspend fun initializeCreates() = mutex.withLock { ensureCreateLifecycle() }

    private suspend fun ensureCreateLifecycle() {
        val journal = creates ?: return
        journal.admitDispatch = { false }
        if (!createInitialized) {
            journal.pausePrepared(CreatePauseReason.PROCESS_RESTART)
            createInitialized = true
        }
        val expected = generation
        if (createGeneration != expected) {
            journal.pausePrepared(CreatePauseReason.SESSION_CHANGED)
            createGeneration = expected
        }
        if (pendingPermissionPause) {
            val policy = createPolicyRevision.get()
            journal.pausePrepared(CreatePauseReason.PERMISSION_LOST)
            if (createPolicyRevision.get() == policy) pendingPermissionPause = false
        }
        for ((target, reason) in pendingCreatePauses) {
            journal.pausePrepared(reason, target = target)
            pendingCreatePauses.remove(target, reason)
        }
        for (account in pendingAccountPauses) {
            journal.pausePrepared(CreatePauseReason.SESSION_CHANGED, account = account)
            pendingAccountPauses.remove(account)
        }
        check(generation == expected) { "CREATE session changed" }
    }

    private fun retainObservedCreateAccount(account: AccountId?) {
        if (creates == null) return
        val previous = createAccount.getAndSet(account)
        if (previous != null && previous != account) {
            createPolicyRevision.incrementAndGet()
            creates?.admitDispatch = { false }
            pendingAccountPauses.add(previous)
        }
    }

    private suspend fun currentObservedAccount(): AccountId? {
        val account = remote.currentAccount()
        retainObservedCreateAccount(account)
        return account
    }

    private suspend fun observeCreateAccount(account: AccountId?) {
        retainObservedCreateAccount(account)
        ensureCreateLifecycle()
    }

    /** Retain observed permission loss even if grants return before the next explicit write. */
    private fun retainCreatePermissionLoss() {
        if (creates == null) return
        createPolicyRevision.incrementAndGet()
        pendingPermissionPause = true
        creates.admitDispatch = { false }
    }

    private fun hasCalendarPermissions(): Boolean {
        val available = projection.hasPermissions()
        if (!available) retainCreatePermissionLoss()
        return available
    }

    private suspend fun qualifyCreate(target: CalendarScope, expected: Long, policy: Long) {
        val account = currentObservedAccount()
        observeCreateAccount(account)
        check(authenticated && generation == expected && createPolicyRevision.get() == policy && account == target.account) {
            "CREATE session is not current"
        }
        val selected = store.selections(target.account).singleOrNull { it.scope == target }
        check(selected?.enabled == true && selected.available) { "CREATE calendar is inactive" }
        if (!hasCalendarPermissions()) {
            creates?.pausePrepared(CreatePauseReason.PERMISSION_LOST, account = target.account)
            error("CREATE calendar permissions are unavailable")
        }
    }

    /** Explicit experimental entry point only; no UI capture or background upload is enabled. */
    suspend fun stageCreate(draft: CalendarCreateDraft, port: CalendarCreateSubmissionPort): CreateAttemptRecord = mutex.withLock {
        val journal = checkNotNull(creates) { "CREATE journal is unavailable" }
        ensureCreateLifecycle()
        val expected = generation
        val policy = createPolicyRevision.get()
        try {
            qualifyCreate(draft.scope, expected, policy)
            check(!journal.isCaptured(draft.scope, draft.correlation)) { "Captured CREATE requires its qualified dispatch path" }
            check(journal.pauseReason(draft.scope, draft.correlation) == null) { "CREATE work is paused" }
            val prepared = port.prepareSubmission(draft)
            qualifyCreate(draft.scope, expected, policy)
            check(prepared.scope == draft.scope) { "CREATE preparation scope differs" }
            val record = CreateAttemptRecord(draft.correlation, draft.scope, prepared.format, prepared.protectedPayload)
            check(journal.pauseReason(record.scope, record.id) == null) { "CREATE work is paused" }
            journal.admitDispatch = { it == draft.scope && authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
            journal.remember(record)
            record
        } finally { journal.admitDispatch = { false } }
    }

    /** UNKNOWN work can only be inspected; pause and session changes never authorize resending. */
    suspend fun submitCreate(record: CreateAttemptRecord, port: CalendarCreateSubmissionPort): CreateSubmissionObservation = mutex.withLock {
        val journal = checkNotNull(creates) { "CREATE journal is unavailable" }
        ensureCreateLifecycle()
        val expected = generation
        val policy = createPolicyRevision.get()
        try {
            qualifyCreate(record.scope, expected, policy)
            val actual = checkNotNull(journal.read(record.scope, record.id)) { "CREATE work is unavailable" }
            check(CreateAttemptIdentity.of(actual) == CreateAttemptIdentity.of(record)) { "CREATE work differs" }
            check(!journal.isCaptured(record.scope, record.id)) { "Captured CREATE requires its qualified dispatch path" }
            if (actual.state == CreateAttemptState.OUTCOME_UNKNOWN) {
                val observation = port.inspect(actual)
                qualifyCreate(actual.scope, expected, policy)
                return@withLock observation
            }
            check(journal.pauseReason(actual.scope, actual.id) == null) { "CREATE work is paused" }
            journal.admitDispatch = { it == actual.scope && authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
            // The adapter requalifies its own session/rights/keys; this wrapper rechecks app admission.
            val guarded = object : CreateAttemptJournal by journal {
                override suspend fun claim(expectedWork: CreateAttemptRecord): Boolean {
                    // submit holds Proton's non-reentrant authentication mutex. Do not call
                    // currentAccount here: the adapter owns that session check; app leases are local.
                    check(authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions()) {
                        "CREATE admission is not current"
                    }
                    check(expectedWork == actual) { "CREATE work differs" }
                    return journal.claim(expectedWork)
                }
            }
            port.submit(actual, guarded)
        } finally { journal.admitDispatch = { false } }
    }

    private suspend fun rememberAccessibleCatalog(account: AccountId, calendars: List<CalendarSummary>) {
        ensureCreateLifecycle()
        observeCreateAccount(account)
        val present = calendars.map { it.id }.toSet()
        for (selected in store.selections(account).filter { it.scope.calendar !in present }) {
            pendingCreatePauses.putIfAbsent(selected.scope, CreatePauseReason.CALENDAR_UNAVAILABLE)
        }
        ensureCreateLifecycle()
        store.remember(account, calendars)
    }

    fun authenticationChanged(value: Boolean) {
        if (authenticated == value) return
        authenticated = value
        generation++
        freshCapturedSubmissions.clear()
        creates?.admitDispatch = { false }
        scope.launch {
            try { initializeCreates() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Writes remain closed; the next explicit command retries initialization. */ }
        }
        mutableState.value = ImportUiState(hasPermission = hasCalendarPermissions(), authenticated = value)
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
        val present = catalog.calendars.map { it.id }.toSet()
        val missing = state.value.selections.filter { it.scope.account == account && it.scope.calendar !in present }
        if (missing.isNotEmpty()) {
            createPolicyRevision.incrementAndGet()
            for (selected in missing) pendingCreatePauses.putIfAbsent(selected.scope, CreatePauseReason.CALENDAR_UNAVAILABLE)
        }
        try {
            mutex.withLock {
                if (!isCurrent(expected, account)) return
                rememberAccessibleCatalog(account, catalog.calendars)
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
            if (journal.isCaptured(target, record.id)) return@withLock CreateAssociationResult.UNRESOLVED
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
            if (!hasCalendarPermissions()) return@withLock CreateAssociationResult.WAITING_PROVIDER
            val witness = projection.inspectOwnedEvent(target, binding.eventId, imported.revision)
                ?: return@withLock CreateAssociationResult.WAITING_PROVIDER
            if (witness.revision != imported.revision) return@withLock CreateAssociationResult.WAITING_PROVIDER
            if (!isCurrent(expected, target.account)) return@withLock CreateAssociationResult.INACTIVE
            if (store.stabilizedProjection(target, binding.eventId) != imported)
                return@withLock CreateAssociationResult.WAITING_IMPORT
            journal.retainProjection(binding, witness)
            CreateAssociationResult.ASSOCIATED
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: CalendarProjectionException) {
              if (failure.category == CalendarProjectionFailure.PERMISSION_REQUIRED) retainCreatePermissionLoss()
              CreateAssociationResult.WAITING_PROVIDER
          }
          catch (_: Exception) { CreateAssociationResult.UNRESOLVED }
    }

    /** Explicit internal UPDATE snapshot only. No observer, editable calendar, or upload is enabled.
     * The Provider row remains dirty; retaining an intent does not acknowledge that edit.
     */
    suspend fun captureLocalEdit(target: CalendarScope, eventId: EventId, id: MutationId): LocalEditCaptureResult = mutex.withLock {
        val expected = generation
        val policy = createPolicyRevision.get()
        if (!isCurrent(expected, target.account) || !hasCalendarPermissions())
            return@withLock LocalEditCaptureResult.INACTIVE
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
        store.admitTransaction = { it == target && active() }
        projection.admitOperation = active
        try {
            val selected = store.selections(target.account).singleOrNull { it.scope == target }
            if (selected?.enabled != true || !selected.available) return@withLock LocalEditCaptureResult.INACTIVE
            val format = store.transaction(target) { checkpoint?.format }
            if (format != remote.format) return@withLock LocalEditCaptureResult.UNQUALIFIED
            if (store.localDelete(target, eventId) != null) return@withLock LocalEditCaptureResult.UNQUALIFIED
            val retained = store.localEdit(target, eventId)
            if (retained != null) {
                if (retained.id != id || retained.format != format) return@withLock LocalEditCaptureResult.UNQUALIFIED
                return@withLock if (retained.state == LocalEditState.CONFLICT) LocalEditCaptureResult.CONFLICT else LocalEditCaptureResult.RETAINED
            }
            val base = captureBaseline(expected, target, eventId) ?: return@withLock LocalEditCaptureResult.UNQUALIFIED
            val snapshot = projection.captureOwnedSimpleEdit(target, base) ?: return@withLock LocalEditCaptureResult.UNQUALIFIED
            if (!isCurrent(expected, target.account) || !active()) return@withLock LocalEditCaptureResult.INACTIVE
            val captured = store.captureLocalEdit(LocalEditIntent(id, target, format, base, snapshot.content))
            if (captured.state == LocalEditState.CONFLICT) LocalEditCaptureResult.CONFLICT else LocalEditCaptureResult.RETAINED
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { LocalEditCaptureResult.UNAVAILABLE }
          finally {
              store.admitTransaction = { false }
              projection.admitOperation = { false }
          }
    }

    /** Explicit internal DELETE snapshot. No observation, upload, or Android acknowledgement. */
    suspend fun captureLocalDelete(target: CalendarScope, eventId: EventId, id: MutationId): LocalEditCaptureResult = mutex.withLock {
        val expected = generation
        val policy = createPolicyRevision.get()
        if (!isCurrent(expected, target.account) || !hasCalendarPermissions())
            return@withLock LocalEditCaptureResult.INACTIVE
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
        store.admitTransaction = { it == target && active() }
        projection.admitOperation = active
        try {
            val selected = store.selections(target.account).singleOrNull { it.scope == target }
            if (selected?.enabled != true || !selected.available) return@withLock LocalEditCaptureResult.INACTIVE
            val format = store.transaction(target) { checkpoint?.format }
            if (format != remote.format) return@withLock LocalEditCaptureResult.UNQUALIFIED
            if (store.localEdit(target, eventId) != null) return@withLock LocalEditCaptureResult.UNQUALIFIED
            val retained = store.localDelete(target, eventId)
            if (retained != null) {
                if (retained.id != id || retained.format != format) return@withLock LocalEditCaptureResult.UNQUALIFIED
                return@withLock if (retained.state == LocalEditState.CONFLICT) LocalEditCaptureResult.CONFLICT else LocalEditCaptureResult.RETAINED
            }
            val base = captureBaseline(expected, target, eventId) ?: return@withLock LocalEditCaptureResult.UNQUALIFIED
            val snapshot = projection.captureOwnedSimpleDelete(target, base) ?: return@withLock LocalEditCaptureResult.UNQUALIFIED
            if (!isCurrent(expected, target.account) || !active()) return@withLock LocalEditCaptureResult.INACTIVE
            val captured = store.captureLocalDelete(LocalDeleteIntent(id, target, format, base, snapshot.content))
            if (captured.state == LocalEditState.CONFLICT) LocalEditCaptureResult.CONFLICT else LocalEditCaptureResult.RETAINED
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { LocalEditCaptureResult.UNAVAILABLE }
          finally {
              store.admitTransaction = { false }
              projection.admitOperation = { false }
          }
    }

    /** Explicit internal capture only; no preparation, submission or Android acknowledgement. */
    suspend fun captureLocalCreate(target: CalendarScope, eventRow: Long, localId: LocalEventId,
        id: MutationId, timestamp: java.time.Instant): LocalEditCaptureResult = mutex.withLock {
        val journal = localCreates ?: return@withLock LocalEditCaptureResult.UNAVAILABLE
        val expected = generation
        val policy = createPolicyRevision.get()
        if (!isCurrent(expected, target.account) || !hasCalendarPermissions())
            return@withLock LocalEditCaptureResult.INACTIVE
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
        store.admitTransaction = { it == target && active() }
        projection.admitOperation = active
        journal.admitCapture = { it == target && active() }
        try {
            val selected = store.selections(target.account).singleOrNull { it.scope == target }
            if (selected?.enabled != true || !selected.available) return@withLock LocalEditCaptureResult.INACTIVE
            val format = store.transaction(target) { checkpoint?.format }
            if (format != remote.format) return@withLock LocalEditCaptureResult.UNQUALIFIED
            val previous = journal.readReservation(target, localId)
            if (previous != null && (previous.id != id || previous.format != format || previous.markerProtocol != 1))
                return@withLock LocalEditCaptureResult.UNQUALIFIED
            // A retained intention is historical work, not a witness of the current Android row.
            val retained = journal.readIntent(target, localId)
            if (retained != null) {
                val identity = retained.reservation
                if (identity.id != id || identity.localId != localId || identity.scope != target ||
                    identity.format != format || identity.markerProtocol != 1 ||
                    (previous != null && identity != previous)) return@withLock LocalEditCaptureResult.UNQUALIFIED
                return@withLock LocalEditCaptureResult.RETAINED
            }
            if (previous == null && (eventRow <= 0 || timestamp.nano != 0)) return@withLock LocalEditCaptureResult.UNQUALIFIED
            val reservation = previous ?: LocalCreateReservation(id, localId, target, format, timestamp)
            val firstClaim = journal.reserve(reservation)
            val snapshot = if (firstClaim) projection.captureAndStampSimpleCreate(target, eventRow, localId)
                else projection.inspectStampedSimpleCreate(target, localId)
            if (snapshot == null) return@withLock LocalEditCaptureResult.UNQUALIFIED
            if (!isCurrent(expected, target.account) || !active()) return@withLock LocalEditCaptureResult.INACTIVE
            val currentSelection = store.selections(target.account).singleOrNull { it.scope == target }
            if (currentSelection?.enabled != true || !currentSelection.available) return@withLock LocalEditCaptureResult.INACTIVE
            if (store.transaction(target) { checkpoint?.format } != format) return@withLock LocalEditCaptureResult.UNQUALIFIED
            journal.retain(LocalCreateIntent(reservation, snapshot.content))
            LocalEditCaptureResult.RETAINED
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { LocalEditCaptureResult.UNAVAILABLE }
          finally {
              store.admitTransaction = { false }
              projection.admitOperation = { false }
              journal.admitCapture = { false }
          }
    }

    /** Explicit read-only preparation; no outgoing journal, dispatch, or Provider acknowledgement. */
    suspend fun prepareLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        port: CalendarCreatePreparationPort): LocalCreatePreparationResult = mutex.withLock {
        val journal = localCreates ?: return@withLock LocalCreatePreparationResult.UNAVAILABLE
        val expected = generation
        val policy = createPolicyRevision.get()
        if (!isCurrent(expected, target.account) || !hasCalendarPermissions())
            return@withLock LocalCreatePreparationResult.INACTIVE
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
        store.admitTransaction = { it == target && active() }
        projection.admitOperation = active
        journal.admitCapture = { it == target && active() }
        try {
            val selected = store.selections(target.account).singleOrNull { it.scope == target }
            if (selected?.enabled != true || !selected.available) return@withLock LocalCreatePreparationResult.INACTIVE
            val format = store.transaction(target) { checkpoint?.format }
            if (format != remote.format) return@withLock LocalCreatePreparationResult.UNQUALIFIED
            val intent = journal.readIntent(target, localId) ?: return@withLock LocalCreatePreparationResult.UNQUALIFIED
            val reservation = intent.reservation
            if (reservation.scope != target || reservation.localId != localId || reservation.id != id ||
                reservation.format != format || reservation.markerProtocol != 1) return@withLock LocalCreatePreparationResult.UNQUALIFIED
            val preparationFormat = port.localCreateFormat ?: return@withLock LocalCreatePreparationResult.UNSUPPORTED
            val observed = projection.inspectStampedSimpleCreate(target, localId)
                ?: return@withLock LocalCreatePreparationResult.UNQUALIFIED
            if (observed.content != intent.content) return@withLock LocalCreatePreparationResult.UNQUALIFIED
            val retained = journal.readPreparation(target, localId)
            if (retained != null) {
                if (retained.intent != intent || retained.format != preparationFormat)
                    return@withLock LocalCreatePreparationResult.UNQUALIFIED
                if (!isCurrent(expected, target.account) || !active()) return@withLock LocalCreatePreparationResult.INACTIVE
                return@withLock LocalCreatePreparationResult.RETAINED
            }
            val prepared = port.prepareCreate(CalendarCreateDraft(target, reservation.id, reservation.timestamp, intent.content))
            if (prepared.scope != target || prepared.format != preparationFormat || port.localCreateFormat != preparationFormat ||
                prepared.protectedPayload.isBlank()) return@withLock LocalCreatePreparationResult.UNQUALIFIED
            if (!isCurrent(expected, target.account) || !active()) return@withLock LocalCreatePreparationResult.INACTIVE
            val currentSelection = store.selections(target.account).singleOrNull { it.scope == target }
            if (currentSelection?.enabled != true || !currentSelection.available) return@withLock LocalCreatePreparationResult.INACTIVE
            if (store.transaction(target) { checkpoint?.format } != format) return@withLock LocalCreatePreparationResult.UNQUALIFIED
            val current = projection.inspectStampedSimpleCreate(target, localId)
                ?: return@withLock LocalCreatePreparationResult.UNQUALIFIED
            if (current.content != intent.content) return@withLock LocalCreatePreparationResult.UNQUALIFIED
            journal.retainPreparation(LocalCreatePreparation(intent, preparationFormat, prepared.protectedPayload))
            LocalCreatePreparationResult.RETAINED
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: CreatePreparationException) {
              when (failure.reason) {
                  CreatePreparationFailure.UNSUPPORTED -> LocalCreatePreparationResult.UNSUPPORTED
                  CreatePreparationFailure.AUTHENTICATION, CreatePreparationFailure.PERMISSION -> LocalCreatePreparationResult.INACTIVE
                  else -> LocalCreatePreparationResult.UNAVAILABLE
              }
          } catch (_: Exception) { LocalCreatePreparationResult.UNAVAILABLE }
          finally {
              store.admitTransaction = { false }
              projection.admitOperation = { false }
              journal.admitCapture = { false }
          }
    }

    /** Explicit read-only staging only; no dispatch journal, upload or Provider acknowledgement. */
    suspend fun stageLocalCreateSubmission(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, port: CalendarCreateSubmissionPort): LocalCreateSubmissionResult = mutex.withLock {
        val journal = localCreates ?: return@withLock LocalCreateSubmissionResult.UNAVAILABLE
        ensureCreateLifecycle()
        val expected = generation
        val policy = createPolicyRevision.get()
        if (!isCurrent(expected, target.account) || !hasCalendarPermissions()) return@withLock LocalCreateSubmissionResult.INACTIVE
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
        store.admitTransaction = { it == target && active() }
        projection.admitOperation = active
        journal.admitCapture = { it == target && active() }
        try {
            suspend fun admission(): LocalCreateSubmissionResult? {
                if (!isCurrent(expected, target.account) || !active()) return LocalCreateSubmissionResult.INACTIVE
                val selection = store.selections(target.account).singleOrNull { it.scope == target }
                if (selection?.enabled != true || !selection.available) return LocalCreateSubmissionResult.INACTIVE
                return null
            }
            admission()?.let { return@withLock it }
            val importFormat = store.transaction(target) { checkpoint?.format }
            if (importFormat != remote.format) return@withLock LocalCreateSubmissionResult.UNQUALIFIED
            val intent = journal.readIntent(target, localId) ?: return@withLock LocalCreateSubmissionResult.UNQUALIFIED
            val captured = intent.reservation
            if (captured.scope != target || captured.localId != localId || captured.id != id ||
                captured.format != importFormat || captured.markerProtocol != 1) return@withLock LocalCreateSubmissionResult.UNQUALIFIED
            val submissionFormat = port.knownEmptySubmissionFormat ?: return@withLock LocalCreateSubmissionResult.UNSUPPORTED
            val reservation = try { LocalCreateSubmissionReservation(intent, attempt, submissionFormat) }
                catch (_: IllegalArgumentException) { return@withLock LocalCreateSubmissionResult.UNQUALIFIED }

            suspend fun qualify(): LocalCreateSubmissionResult? {
                admission()?.let { return it }
                if (port.knownEmptySubmissionFormat != submissionFormat || remote.format != importFormat ||
                    store.transaction(target) { checkpoint?.format } != importFormat) return LocalCreateSubmissionResult.UNQUALIFIED
                val observed = projection.inspectStampedSimpleCreate(target, localId)
                if (observed?.content != intent.content) return LocalCreateSubmissionResult.UNQUALIFIED
                // Provider inspection and SQLite are separate. Recheck app state after every external await.
                admission()?.let { return it }
                if (port.knownEmptySubmissionFormat != submissionFormat || remote.format != importFormat ||
                    store.transaction(target) { checkpoint?.format } != importFormat) return LocalCreateSubmissionResult.UNQUALIFIED
                if (!active()) return LocalCreateSubmissionResult.INACTIVE
                return null
            }
            qualify()?.let { return@withLock it }
            val existing = journal.readSubmissionReservation(target, localId)
            if (existing != null && existing != reservation) return@withLock LocalCreateSubmissionResult.UNQUALIFIED
            if (existing != null) {
                val retained = journal.readSubmission(target, localId)
                qualify()?.let { return@withLock it }
                return@withLock if (retained != null) LocalCreateSubmissionResult.RETAINED else LocalCreateSubmissionResult.UNRESOLVED
            }
            val permit = journal.reserveSubmission(reservation)
            if (permit == null) {
                // Another helper won. Never acquire its preparation right or regenerate a blob.
                val retained = journal.readSubmission(target, localId)
                qualify()?.let { return@withLock it }
                return@withLock if (retained != null) LocalCreateSubmissionResult.RETAINED else LocalCreateSubmissionResult.UNRESOLVED
            }
            qualify()?.let { return@withLock it }
            val prepared = port.prepareSubmission(reservation.draft)
            if (prepared.scope != target || prepared.format != submissionFormat || prepared.protectedPayload.isBlank())
                return@withLock LocalCreateSubmissionResult.UNQUALIFIED
            qualify()?.let { return@withLock it }
            val retained = journal.retainSubmission(permit, LocalCreateSubmission(reservation, prepared.protectedPayload))
            qualify()?.let { return@withLock it }
            currentCoroutineContext().ensureActive()
            if (creates != null) freshCapturedSubmissions[target to attempt] =
                CapturedSubmissionLease(CapturedCreateDispatchIdentity.of(retained), expected, policy)
            LocalCreateSubmissionResult.RETAINED
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: CreatePreparationException) {
              when (failure.reason) {
                  CreatePreparationFailure.UNSUPPORTED -> LocalCreateSubmissionResult.UNSUPPORTED
                  CreatePreparationFailure.AUTHENTICATION, CreatePreparationFailure.PERMISSION -> LocalCreateSubmissionResult.INACTIVE
                  else -> LocalCreateSubmissionResult.UNAVAILABLE
              }
          } catch (_: Exception) { LocalCreateSubmissionResult.UNAVAILABLE }
          finally {
              store.admitTransaction = { false }
              projection.admitOperation = { false }
              journal.admitCapture = { false }
          }
    }

    /** Explicit internal dispatch; original Provider acknowledgement and association remain separate. */
    suspend fun submitLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, port: CalendarCreateSubmissionPort): CreateSubmissionObservation = mutex.withLock {
        val captures = localCreates ?: return@withLock CreateSubmissionObservation.Unknown
        val journal = creates ?: return@withLock CreateSubmissionObservation.Unknown
        ensureCreateLifecycle()
        val expected = generation
        val policy = createPolicyRevision.get()
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
        try {
            qualifyCreate(target, expected, policy)
            captures.admitCapture = { it == target && active() }
            store.admitTransaction = { it == target && active() }
            projection.admitOperation = active
            val submission = captures.readSubmission(target, localId) ?: return@withLock CreateSubmissionObservation.Unknown
            val reserved = submission.reservation
            val captured = reserved.intent.reservation
            if (captured.scope != target || captured.localId != localId || captured.id != id || reserved.attempt != attempt ||
                port.knownEmptySubmissionFormat != reserved.format) return@withLock CreateSubmissionObservation.Unknown
            val wanted = CapturedCreateDispatchIdentity.of(submission)
            val draft = reserved.draft
            val prepared = CreateAttemptRecord(attempt, target, reserved.format, submission.protectedPayload)
            val existing = journal.read(target, attempt)
            if (existing != null && journal.capturedIdentity(target, attempt) != wanted) return@withLock CreateSubmissionObservation.Unknown

            fun qualifiedObservation(observed: CreateSubmissionObservation): CreateSubmissionObservation {
                return if (observed is CreateSubmissionObservation.Verified &&
                    (observed.attempt != wanted.attempt || observed.event.content != draft.content)) CreateSubmissionObservation.Unknown
                else observed
            }
            if (existing?.state == CreateAttemptState.OUTCOME_UNKNOWN) {
                val observed = port.inspectCaptured(existing, draft)
                qualifyCreate(target, expected, policy)
                if (captures.readSubmission(target, localId) != submission || journal.capturedIdentity(target, attempt) != wanted)
                    return@withLock CreateSubmissionObservation.Unknown
                return@withLock qualifiedObservation(observed)
            }
            val key = target to attempt
            val lease = freshCapturedSubmissions[key] ?: return@withLock CreateSubmissionObservation.Unknown
            fun hasLease() = active() && lease.identity == wanted && lease.generation == expected && lease.policy == policy &&
                freshCapturedSubmissions[key] == lease
            // Called from inside Proton's authentication mutex at final claim. Never call
            // currentAccount/isCurrent/qualifyCreate here; the adapter owns fresh session checks.
            suspend fun qualifyLocal(): Boolean {
                if (!hasLease() || port.knownEmptySubmissionFormat != reserved.format || remote.format != captured.format) return false
                suspend fun localStateMatches(): Boolean {
                    val selected = store.selections(target.account).singleOrNull { it.scope == target }
                    return selected?.enabled == true && selected.available &&
                        store.transaction(target) { checkpoint?.format } == captured.format &&
                        captures.readSubmission(target, localId) == submission
                }
                if (!localStateMatches()) return false
                val row = projection.inspectStampedSimpleCreate(target, localId)
                return row?.content == draft.content && localStateMatches() && hasLease() &&
                    port.knownEmptySubmissionFormat == reserved.format && remote.format == captured.format
            }
            if (!qualifyLocal() || journal.pauseReason(target, attempt) != null) return@withLock CreateSubmissionObservation.Unknown
            journal.admitDispatch = { it == target && hasLease() }
            check(journal.rememberCaptured(submission) == wanted) { "Captured dispatch link differs" }
            val actual = checkNotNull(journal.read(target, attempt))
            if (actual != prepared || !qualifyLocal()) return@withLock CreateSubmissionObservation.Unknown
            val guarded = object : CreateAttemptJournal by journal {
                override suspend fun claim(expectedWork: CreateAttemptRecord): Boolean {
                    if (expectedWork != actual || journal.capturedIdentity(target, attempt) != wanted || !qualifyLocal()) return false
                    // Consume after the last qualification but before the independent SQLite commit.
                    // A failed/cancelled claim cannot recover this right from a cache or a new owner.
                    if (!freshCapturedSubmissions.remove(key, lease)) return false
                    journal.admitDispatch = { it == target && active() }
                    return journal.claimCaptured(expectedWork, wanted)
                }
            }
            val observed = port.submitCaptured(actual, draft, guarded)
            qualifyCreate(target, expected, policy)
            if (journal.read(target, attempt)?.state != CreateAttemptState.OUTCOME_UNKNOWN ||
                journal.capturedIdentity(target, attempt) != wanted) return@withLock CreateSubmissionObservation.Unknown
            qualifiedObservation(observed)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { CreateSubmissionObservation.Unknown }
          finally {
              captures.admitCapture = { false }
              journal.admitDispatch = { false }
              store.admitTransaction = { false }
              projection.admitOperation = { false }
          }
    }

    private suspend fun captureBaseline(expected: Long, target: CalendarScope, eventId: EventId): RemoteChange.Upsert? {
        val receipt = store.projectionReceipt(target, eventId)
        val recovered = receipt?.let { recoverProjection(expected, it) } ?: false
        val base = store.projectedBaseline(target, eventId) ?: return null
        // An opaque revision alone cannot choose between different prepared contents.
        if (receipt != null && !recovered && receipt.change != base && receipt.change.revision == base.revision) return null
        return base
    }

    /** A marker proves the historical write, not that the current Provider content is unchanged. */
    private suspend fun recoverProjection(expected: Long, receipt: ProjectionReceipt): Boolean {
        if (receipt.format != remote.format || receipt.protocol != projection.projectionReceiptProtocol) return false
        val witness = projection.inspectProjectionReceipt(receipt) ?: return false
        check(witness.receipt == receipt) { "Projection witness differs" }
        if (!isCurrent(expected, receipt.scope.account)) throw CancellationException("Inactive projection recovery")
        return store.restoreProjection(witness)
    }

    /** Kept out of the larger import coroutine; preparation precedes every tracked Provider write. */
    private suspend fun projectPending(intent: ProjectionIntent, expected: Long) {
        val outstanding = store.projectionReceipt(intent.scope, intent.change.eventId)
        if (outstanding != null) {
            val recovered = recoverProjection(expected, outstanding)
            if (recovered && outstanding.change == intent.change) return
            if (!recovered && (outstanding.change != intent.change || outstanding.format != remote.format ||
                outstanding.protocol != projection.projectionReceiptProtocol)) {
                throw CalendarProjectionException(CalendarProjectionFailure.PROVIDER_REJECTED)
            }
        }
        if (projection.projectionReceiptProtocol == 1 && ProjectionReceipt.supports(intent.change)) {
            val prepared = store.prepareProjection(intent, remote.format)
            val witness = projection.applyTracked(prepared)
            check(witness.receipt == prepared) { "Projection witness differs" }
            if (!isCurrent(expected, intent.scope.account)) throw CancellationException("Inactive projection acknowledgement")
            check(store.restoreProjection(witness)) { "Projection acknowledgement remains unresolved" }
        } else {
            projection.apply(intent, if (intent.change is RemoteChange.Deleted)
                store.deletedSeriesUid(intent.scope, intent.change.eventId) else null)
            store.acknowledge(intent)
        }
    }

    fun permissionsChanged() {
        val previouslyGranted = state.value.hasPermission
        mutableState.value = state.value.copy(hasPermission = hasCalendarPermissions())
        if (!state.value.hasPermission) {
            scope.launch {
                try { initializeCreates() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Retain the pending pause; explicit writes retry it first. */ }
            }
        }
        if (authenticated && hasCalendarPermissions()) enqueueLocal()
        if (!previouslyGranted && state.value.hasPermission && state.value.selections.any { it.enabled }) requestSync()
    }

    fun setEnabled(selection: ImportStore.Selection, enabled: Boolean) {
        if (!enabled) {
            createPolicyRevision.incrementAndGet()
            pendingCreatePauses.putIfAbsent(selection.scope, CreatePauseReason.CALENDAR_DISABLED)
            creates?.admitDispatch = { false }
        }
        scope.launch {
            mutex.withLock {
                if (!isCurrent(generation, selection.scope.account)) return@withLock
                ensureCreateLifecycle()
                if (!enabled) creates?.pausePrepared(CreatePauseReason.CALENDAR_DISABLED, target = selection.scope)
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
                val account = currentObservedAccount() ?: return@launch
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
    suspend fun run(network: Boolean, fresh: Boolean = true): ImportRunResult = mutex.withLock { runLocked(network, fresh) }

    private suspend fun refreshAccess(account: AccountId, expected: Long): ImportRunResult? {
        val catalogPort = remote as? CalendarCatalogPort ?: return null
        val enabled = store.selections(account).filter { it.enabled && it.available }
        if (enabled.isNotEmpty() && enabled.all { it.nextAttempt > System.currentTimeMillis() })
            return ImportRunResult.RETRY
        when (val catalog = catalogPort.load()) {
            is CalendarCatalogResult.Success -> {
                if (catalog.account != account || !isCurrent(expected, account)) return ImportRunResult.COMPLETE
                rememberAccessibleCatalog(account, catalog.calendars)
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
        return null
    }

    private suspend fun runLocked(network: Boolean, fresh: Boolean): ImportRunResult {
        val expected = generation
        val account = currentObservedAccount() ?: return ImportRunResult.COMPLETE
        if (!isCurrent(expected, account)) return ImportRunResult.COMPLETE
        store.admitTransaction = { it.account == account && authenticated && generation == expected }
        projection.admitOperation = { authenticated && generation == expected }
        mutableState.value = state.value.copy(busy = true)
        var retry = false
        var continuation = false
        var runFailure: Exception? = null
        try {
            // Re-establish access on retries too; WorkManager retries are not fresh runs.
            if (network) refreshAccess(account, expected)?.let { return it }
            for (selection in store.selections(account).filter { it.enabled && it.available }) {
                if (!isCurrent(expected, account)) return ImportRunResult.COMPLETE
                val calendarScope = selection.scope
                if (!hasCalendarPermissions()) {
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
                            projectPending(intent, expected)
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
        return when { retry -> ImportRunResult.RETRY; continuation -> ImportRunResult.CONTINUE; else -> ImportRunResult.COMPLETE }
    }

    private suspend fun isCurrent(expected: Long, account: AccountId) =
        authenticated && generation == expected && currentObservedAccount() == account

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
        val account = if (authenticated) currentObservedAccount() else null
        val selections = if (account == null) emptyList() else store.selections(account)
        if (generation == expected) mutableState.value = state.value.copy(selections = selections,
            authenticated = authenticated, hasPermission = hasCalendarPermissions())
    }
}
