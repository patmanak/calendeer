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
enum class LocalTitlePreparationResult { INACTIVE, UNQUALIFIED, RETAINED, HISTORICAL, CONFLICT, UNSUPPORTED, UNAVAILABLE }
enum class LocalCreatePreparationResult { INACTIVE, UNQUALIFIED, RETAINED, UNSUPPORTED, UNAVAILABLE }
enum class LocalCreateSubmissionResult { INACTIVE, UNQUALIFIED, RETAINED, UNRESOLVED, UNSUPPORTED, UNAVAILABLE }
enum class CapturedCreateBindingResult { INACTIVE, UNRESOLVED, RETAINED }
enum class CapturedCreateAttachmentResult { INACTIVE, UNRESOLVED, ATTACHED }
enum class CapturedCreateBaselineResult { INACTIVE, UNRESOLVED, RETAINED }
enum class CapturedCreateReconciliationResult { INACTIVE, UNRESOLVED, UNSUPPORTED, UNCHANGED, RETAINED, CONFLICT }
enum class CapturedCreateAcknowledgementResult { INACTIVE, UNRESOLVED, UNSUPPORTED, ACKNOWLEDGED }
enum class CapturedCreateCleanResult { INACTIVE, UNRESOLVED, CLEAN }
enum class CapturedCreateHandoffResult { INACTIVE, UNRESOLVED, WAITING, HANDED_OFF }
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
            if (capturedRawHold(target).holds(eventId)) throw CapturedProjectionPending()
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

    /** Explicit preparation of a captured title edit; never dispatches or acknowledges Android. */
    suspend fun prepareLocalTitle(target: CalendarScope, eventId: EventId, id: MutationId,
        timestamp: java.time.Instant, port: CalendarLocalEditPreparationPort): LocalTitlePreparationResult =
        mutex.withLock {
            val expected = generation
            val policy = createPolicyRevision.get()
            val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
            if (!isCurrent(expected, target.account) || !active()) return@withLock LocalTitlePreparationResult.INACTIVE
            store.admitTransaction = { it == target && active() }
            var captured: LocalEditIntent? = null
            try {
                val selected = store.selections(target.account).singleOrNull { it.scope == target }
                if (selected?.enabled != true || !selected.available) return@withLock LocalTitlePreparationResult.INACTIVE
                val format = store.transaction(target) { checkpoint?.format }
                if (format != remote.format) return@withLock LocalTitlePreparationResult.UNQUALIFIED
                val owner = store.localEdit(target, eventId) ?: return@withLock LocalTitlePreparationResult.UNQUALIFIED
                if (owner.id != id || owner.format != format) return@withLock LocalTitlePreparationResult.UNQUALIFIED
                if (owner.state == LocalEditState.CONFLICT) return@withLock LocalTitlePreparationResult.CONFLICT
                val frozen = try {
                    require(timestamp.nano == 0 && timestamp.atOffset(java.time.ZoneOffset.UTC).year in 1970..9999)
                    LocalEditPreparation.freezeIntent(owner)
                } catch (_: Exception) { return@withLock LocalTitlePreparationResult.UNQUALIFIED }
                captured = frozen
                val preparationFormat = port.localTitleFormat ?: return@withLock LocalTitlePreparationResult.UNSUPPORTED
                val admission = store.assessLocalTitlePreparation(frozen)
                if (admission != LocalTitlePreparationAdmission.ELIGIBLE) {
                    if (!isCurrent(expected, target.account) || !active()) return@withLock LocalTitlePreparationResult.INACTIVE
                    return@withLock titlePreparationResult(admission)
                }
                // No store/auth mutex is held while the adapter runs its serialized authenticated reads.
                val prepared = port.prepareLocalTitle(frozen, timestamp)
                currentCoroutineContext().ensureActive()
                if (prepared.intent != frozen || prepared.timestamp != timestamp || prepared.format != preparationFormat ||
                    port.localTitleFormat != preparationFormat || remote.format != format)
                    return@withLock LocalTitlePreparationResult.UNQUALIFIED
                if (!isCurrent(expected, target.account) || !active()) return@withLock LocalTitlePreparationResult.INACTIVE
                // SQL and lease checks run again in the same transaction as first insertion.
                store.admitTransaction = { it == target && active() && port.localTitleFormat == preparationFormat && remote.format == format }
                titlePreparationResult(store.retainCurrentLocalTitlePreparation(prepared))
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: LocalEditPreparationException) {
                  when (failure.reason) {
                      LocalEditPreparationFailure.CONFLICT -> {
                          try {
                              val owner = captured
                              if (owner == null || !isCurrent(expected, target.account) || !active()) LocalTitlePreparationResult.INACTIVE
                              else titlePreparationResult(store.conflictLocalTitlePreparation(owner))
                          } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { LocalTitlePreparationResult.UNAVAILABLE }
                      }
                      LocalEditPreparationFailure.AUTHENTICATION, LocalEditPreparationFailure.PERMISSION -> LocalTitlePreparationResult.INACTIVE
                      LocalEditPreparationFailure.UNSUPPORTED -> LocalTitlePreparationResult.UNSUPPORTED
                      else -> LocalTitlePreparationResult.UNAVAILABLE
                  }
              } catch (_: Exception) { LocalTitlePreparationResult.UNAVAILABLE }
              finally { store.admitTransaction = { false } }
        }

    private fun titlePreparationResult(admission: LocalTitlePreparationAdmission) = when (admission) {
        LocalTitlePreparationAdmission.STORED -> LocalTitlePreparationResult.RETAINED
        LocalTitlePreparationAdmission.HISTORICAL -> LocalTitlePreparationResult.HISTORICAL
        LocalTitlePreparationAdmission.CONFLICT -> LocalTitlePreparationResult.CONFLICT
        LocalTitlePreparationAdmission.INACTIVE -> LocalTitlePreparationResult.INACTIVE
        else -> LocalTitlePreparationResult.UNQUALIFIED
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
            if (capturedRawHold(target).holds(eventId)) throw CapturedProjectionPending()
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

    /** Retain only the first authenticated captured identity. No original-row ACK or projection. */
    suspend fun bindLocalCreateObservation(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, port: CalendarCreateSubmissionPort? = null): CapturedCreateBindingResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateBindingResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateBindingResult.UNRESOLVED
        ensureCreateLifecycle()
        val expected = generation
        val policy = createPolicyRevision.get()
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
        suspend fun available(): Boolean {
            if (!isCurrent(expected, target.account) || !active()) return false
            val selected = store.selections(target.account).singleOrNull { it.scope == target }
            return selected?.enabled == true && selected.available && active()
        }
        try {
            if (!available()) return@withLock CapturedCreateBindingResult.INACTIVE
            store.admitTransaction = { it == target && active() }
            captures.admitCapture = { it == target && active() }
            journal.admitAssociation = { it == target && active() }
            val submission = captures.readSubmission(target, localId) ?: return@withLock CapturedCreateBindingResult.UNRESOLVED
            val reserved = submission.reservation
            val captured = reserved.intent.reservation
            if (captured.scope != target || captured.localId != localId || captured.id != id || reserved.attempt != attempt ||
                captured.format != remote.format || store.transaction(target) { checkpoint?.format } != captured.format)
                return@withLock CapturedCreateBindingResult.UNRESOLVED
            val identity = CapturedCreateDispatchIdentity.of(submission)
            val unknown = journal.read(target, attempt) ?: return@withLock CapturedCreateBindingResult.UNRESOLVED
            if (unknown.state != CreateAttemptState.OUTCOME_UNKNOWN || journal.capturedIdentity(target, attempt) != identity)
                return@withLock CapturedCreateBindingResult.UNRESOLVED
            val cached = journal.capturedBinding(identity)
            if (cached == null) {
                val inspections = port ?: return@withLock CapturedCreateBindingResult.UNRESOLVED
                if (inspections.knownEmptySubmissionFormat != reserved.format) return@withLock CapturedCreateBindingResult.UNRESOLVED
                val observed = inspections.inspectCaptured(unknown, reserved.draft) as? CreateSubmissionObservation.Verified
                    ?: return@withLock CapturedCreateBindingResult.UNRESOLVED
                if (!available()) return@withLock CapturedCreateBindingResult.INACTIVE
                if (observed.attempt != identity.attempt || observed.event.content != reserved.draft.content ||
                    inspections.knownEmptySubmissionFormat != reserved.format || remote.format != captured.format ||
                    store.transaction(target) { checkpoint?.format } != captured.format ||
                    captures.readSubmission(target, localId) != submission || journal.capturedIdentity(target, attempt) != identity)
                    return@withLock CapturedCreateBindingResult.UNRESOLVED
                journal.bindCaptured(unknown, identity, observed)
            }
            if (!available()) return@withLock CapturedCreateBindingResult.INACTIVE
            if (remote.format != captured.format || store.transaction(target) { checkpoint?.format } != captured.format ||
                captures.readSubmission(target, localId) != submission || journal.capturedBinding(identity) == null)
                return@withLock CapturedCreateBindingResult.UNRESOLVED
            CapturedCreateBindingResult.RETAINED
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { CapturedCreateBindingResult.UNRESOLVED }
          finally {
              journal.admitAssociation = { false }
              captures.admitCapture = { false }
              store.admitTransaction = { false }
          }
    }

    /** Original-row metadata only; the incoming hold is not released by this operation. */
    suspend fun attachLocalCreateBinding(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateAttachmentResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateAttachmentResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateAttachmentResult.UNRESOLVED
        ensureCreateLifecycle()
        val expected = generation
        val policy = createPolicyRevision.get()
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
        suspend fun available(): Boolean {
            if (!isCurrent(expected, target.account) || !active()) return false
            val selected = store.selections(target.account).singleOrNull { it.scope == target }
            return selected?.enabled == true && selected.available && active()
        }
        try {
            if (!available()) return@withLock CapturedCreateAttachmentResult.INACTIVE
            store.admitTransaction = { it == target && active() }
            captures.admitCapture = { it == target && active() }
            journal.admitAssociation = { it == target && active() }
            projection.admitOperation = { active() }
            val submission = captures.readSubmission(target, localId) ?: return@withLock CapturedCreateAttachmentResult.UNRESOLVED
            val reserved = submission.reservation
            val capture = reserved.intent.reservation
            if (capture.scope != target || capture.localId != localId || capture.id != id || reserved.attempt != attempt ||
                capture.format != remote.format || store.transaction(target) { checkpoint?.format } != capture.format ||
                projection.capturedAttachmentProtocol != 1) return@withLock CapturedCreateAttachmentResult.UNRESOLVED
            val identity = CapturedCreateDispatchIdentity.of(submission)
            val binding = journal.capturedBinding(identity) ?: return@withLock CapturedCreateAttachmentResult.UNRESOLVED
            // The durable nonce precedes any Provider change. No network or submission port is used.
            val receipt = journal.prepareCapturedAttachment(binding)
            suspend fun exact(): Boolean = captures.readSubmission(target, localId) == submission &&
                remote.format == capture.format && projection.capturedAttachmentProtocol == receipt.protocol &&
                store.transaction(target) { checkpoint?.format } == capture.format &&
                journal.capturedBinding(identity) == binding && journal.capturedAttachment(binding) == receipt
            if (!available()) return@withLock CapturedCreateAttachmentResult.INACTIVE
            if (!exact()) return@withLock CapturedCreateAttachmentResult.UNRESOLVED
            var witness = projection.inspectCapturedAttachment(receipt)
            if (witness == null) {
                if (!available()) return@withLock CapturedCreateAttachmentResult.INACTIVE
                if (!exact()) return@withLock CapturedCreateAttachmentResult.UNRESOLVED
                witness = projection.attachCapturedCreate(receipt)
            }
            if (!available()) return@withLock CapturedCreateAttachmentResult.INACTIVE
            if (witness?.receipt != receipt || !exact()) return@withLock CapturedCreateAttachmentResult.UNRESOLVED
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

    /** Retain the verified sent baseline only. Never clean the original row or release its hold. */
    suspend fun retainLocalCreateBaseline(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateBaselineResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateBaselineResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateBaselineResult.UNRESOLVED
        ensureCreateLifecycle()
        val expected = generation
        val policy = createPolicyRevision.get()
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
        suspend fun available(): Boolean {
            if (!isCurrent(expected, target.account) || !active()) return false
            val selected = store.selections(target.account).singleOrNull { it.scope == target }
            return selected?.enabled == true && selected.available && active()
        }
        try {
            if (!available()) return@withLock CapturedCreateBaselineResult.INACTIVE
            store.admitTransaction = { it == target && active() }
            captures.admitCapture = { it == target && active() }
            projection.admitOperation = { active() }
            val submission = captures.readSubmission(target, localId) ?: return@withLock CapturedCreateBaselineResult.UNRESOLVED
            val reserved = submission.reservation
            val capture = reserved.intent.reservation
            if (capture.scope != target || capture.localId != localId || capture.id != id || reserved.attempt != attempt ||
                capture.format != remote.format || store.transaction(target) { checkpoint?.format } != capture.format ||
                projection.capturedAttachmentProtocol != 1) return@withLock CapturedCreateBaselineResult.UNRESOLVED
            val identity = CapturedCreateDispatchIdentity.of(submission)
            val binding = journal.capturedBinding(identity) ?: return@withLock CapturedCreateBaselineResult.UNRESOLVED
            // This path only reads an existing durable attachment plan and its current Provider proof.
            val receipt = journal.capturedAttachment(binding) ?: return@withLock CapturedCreateBaselineResult.UNRESOLVED
            val baseline = CapturedCreateBaseline(receipt,
                RemoteChange.Upsert(binding.eventId, binding.verifiedRevision, reserved.intent.content))
            suspend fun exact(): Boolean = captures.readSubmission(target, localId) == submission &&
                remote.format == capture.format && projection.capturedAttachmentProtocol == receipt.protocol &&
                store.transaction(target) { checkpoint?.format } == capture.format &&
                journal.capturedBinding(identity) == binding && journal.capturedAttachment(binding) == receipt
            if (!available()) return@withLock CapturedCreateBaselineResult.INACTIVE
            if (!exact()) return@withLock CapturedCreateBaselineResult.UNRESOLVED
            val witness = projection.inspectCapturedAttachment(receipt)
            if (!available()) return@withLock CapturedCreateBaselineResult.INACTIVE
            if (witness?.receipt != receipt || !exact()) return@withLock CapturedCreateBaselineResult.UNRESOLVED
            if (!store.retainCapturedBaseline(baseline, witness)) return@withLock CapturedCreateBaselineResult.UNRESOLVED
            if (!available()) return@withLock CapturedCreateBaselineResult.INACTIVE
            if (!exact() || store.capturedBaseline(target, binding.eventId) != baseline)
                return@withLock CapturedCreateBaselineResult.UNRESOLVED
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

    /** Explicit one-shot clean acknowledgement of an unchanged captured original.
     * ACKNOWLEDGED proves a historical batch only; incoming holds and remote evidence remain.
     */
    suspend fun acknowledgeLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateAcknowledgementResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateAcknowledgementResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateAcknowledgementResult.UNRESOLVED
        ensureCreateLifecycle()
        val expected = generation
        val policy = createPolicyRevision.get()
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
        suspend fun available(): Boolean {
            if (!isCurrent(expected, target.account) || !active()) return false
            val selected = store.selections(target.account).singleOrNull { it.scope == target }
            return selected?.enabled == true && selected.available && active()
        }
        try {
            if (!available()) return@withLock CapturedCreateAcknowledgementResult.INACTIVE
            store.admitTransaction = { it == target && active() }
            captures.admitCapture = { it == target && active() }
            projection.admitOperation = { active() }
            val submission = captures.readSubmission(target, localId) ?: return@withLock CapturedCreateAcknowledgementResult.UNRESOLVED
            val reserved = submission.reservation
            val capture = reserved.intent.reservation
            if (capture.scope != target || capture.localId != localId || capture.id != id || reserved.attempt != attempt ||
                capture.format != remote.format || store.transaction(target) { checkpoint?.format } != capture.format ||
                projection.capturedAttachmentProtocol != 1 || projection.capturedAcknowledgementProtocol != 1)
                return@withLock CapturedCreateAcknowledgementResult.UNRESOLVED
            val identity = CapturedCreateDispatchIdentity.of(submission)
            val binding = journal.capturedBinding(identity) ?: return@withLock CapturedCreateAcknowledgementResult.UNRESOLVED
            val attachment = journal.capturedAttachment(binding) ?: return@withLock CapturedCreateAcknowledgementResult.UNRESOLVED
            val baseline = CapturedCreateBaseline(attachment,
                RemoteChange.Upsert(binding.eventId, binding.verifiedRevision, reserved.intent.content))
            suspend fun exactSource(): Boolean = captures.readSubmission(target, localId) == submission &&
                remote.format == capture.format && projection.capturedAttachmentProtocol == attachment.protocol &&
                projection.capturedAcknowledgementProtocol == 1 &&
                store.transaction(target) { checkpoint?.format } == capture.format &&
                journal.capturedBinding(identity) == binding && journal.capturedAttachment(binding) == attachment &&
                store.capturedBaseline(target, binding.eventId) == baseline
            if (!available()) return@withLock CapturedCreateAcknowledgementResult.INACTIVE
            if (!exactSource()) return@withLock CapturedCreateAcknowledgementResult.UNRESOLVED
            var record = store.capturedAcknowledgement(target, binding.eventId)
            if (record != null && record.receipt.baseline != baseline) return@withLock CapturedCreateAcknowledgementResult.UNRESOLVED
            var fresh = false
            if (record == null) {
                val observed = projection.captureCapturedCreateCurrent(baseline)
                if (!available()) return@withLock CapturedCreateAcknowledgementResult.INACTIVE
                if (!exactSource()) return@withLock CapturedCreateAcknowledgementResult.UNRESOLVED
                if (observed == CapturedCreateCurrentObservation.Unsupported) return@withLock CapturedCreateAcknowledgementResult.UNSUPPORTED
                val snapshot = (observed as? CapturedCreateCurrentObservation.Supported)?.snapshot
                    ?: return@withLock CapturedCreateAcknowledgementResult.UNRESOLVED
                if (snapshot.baseline != baseline || snapshot.acknowledgement != null || !snapshot.unchanged) return@withLock CapturedCreateAcknowledgementResult.UNRESOLVED
                val reservation = store.reserveCapturedAcknowledgement(CapturedCreateAcknowledgementReceipt(baseline, java.util.UUID.randomUUID().toString()))
                record = reservation.record
                fresh = reservation.fresh
            }
            val admitted = checkNotNull(record)
            if (!available()) return@withLock CapturedCreateAcknowledgementResult.INACTIVE
            if (!exactSource() || store.capturedAcknowledgement(target, binding.eventId) != admitted)
                return@withLock CapturedCreateAcknowledgementResult.UNRESOLVED
            // Only this invocation's fresh reservation may call the write port. All gaps and
            // reopened outcomes inspect the original immutable nonce; none can rearm the batch.
            val proof = if (fresh) projection.acknowledgeCapturedCreate(admitted.receipt)
                else projection.inspectCapturedAcknowledgement(admitted.receipt)
            if (!available()) return@withLock CapturedCreateAcknowledgementResult.INACTIVE
            if (proof?.receipt != admitted.receipt || !exactSource() ||
                store.capturedAcknowledgement(target, binding.eventId) != admitted)
                return@withLock CapturedCreateAcknowledgementResult.UNRESOLVED
            val proven = if (admitted.state == CapturedCreateAcknowledgementState.PROVEN) admitted
                else store.retainCapturedAcknowledgement(proof)
            if (!available()) return@withLock CapturedCreateAcknowledgementResult.INACTIVE
            if (!exactSource() || store.capturedAcknowledgement(target, binding.eventId) != proven)
                return@withLock CapturedCreateAcknowledgementResult.UNRESOLVED
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

    /** Explicit offline point-in-time qualification; no ACK, durable clean state or hold release. */
    suspend fun qualifyAcknowledgedLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateCleanResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateCleanResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateCleanResult.UNRESOLVED
        ensureCreateLifecycle()
        val expected = generation
        val policy = createPolicyRevision.get()
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
        suspend fun available(): Boolean {
            if (!isCurrent(expected, target.account) || !active()) return false
            val selected = store.selections(target.account).singleOrNull { it.scope == target }
            return selected?.enabled == true && selected.available && active()
        }
        try {
            if (!available()) return@withLock CapturedCreateCleanResult.INACTIVE
            store.admitTransaction = { it == target && active() }
            captures.admitCapture = { it == target && active() }
            projection.admitOperation = { active() }
            val submission = captures.readSubmission(target, localId) ?: return@withLock CapturedCreateCleanResult.UNRESOLVED
            val reserved = submission.reservation
            val capture = reserved.intent.reservation
            if (capture.scope != target || capture.localId != localId || capture.id != id || reserved.attempt != attempt ||
                capture.format != remote.format || store.transaction(target) { checkpoint?.format } != capture.format ||
                projection.capturedAttachmentProtocol != 1 || projection.capturedAcknowledgementProtocol != 1)
                return@withLock CapturedCreateCleanResult.UNRESOLVED
            val identity = CapturedCreateDispatchIdentity.of(submission)
            val binding = journal.capturedBinding(identity) ?: return@withLock CapturedCreateCleanResult.UNRESOLVED
            val attachment = journal.capturedAttachment(binding) ?: return@withLock CapturedCreateCleanResult.UNRESOLVED
            val baseline = CapturedCreateBaseline(attachment,
                RemoteChange.Upsert(binding.eventId, binding.verifiedRevision, reserved.intent.content))
            val record = store.capturedAcknowledgement(target, binding.eventId)
                ?: return@withLock CapturedCreateCleanResult.UNRESOLVED
            if (record.state != CapturedCreateAcknowledgementState.PROVEN || record.receipt.baseline != baseline)
                return@withLock CapturedCreateCleanResult.UNRESOLVED
            suspend fun exactSource(): Boolean = captures.readSubmission(target, localId) == submission &&
                remote.format == capture.format && projection.capturedAttachmentProtocol == attachment.protocol &&
                projection.capturedAcknowledgementProtocol == record.receipt.protocol &&
                store.transaction(target) { checkpoint?.format } == capture.format &&
                journal.capturedBinding(identity) == binding && journal.capturedAttachment(binding) == attachment &&
                store.capturedBaseline(target, binding.eventId) == baseline &&
                store.capturedCleanSource(record.receipt)
            if (!available()) return@withLock CapturedCreateCleanResult.INACTIVE
            if (!exactSource()) return@withLock CapturedCreateCleanResult.UNRESOLVED
            val proof = projection.inspectAcknowledgedCreateClean(record.receipt)
            if (!available()) return@withLock CapturedCreateCleanResult.INACTIVE
            if (proof?.receipt != record.receipt || !exactSource()) return@withLock CapturedCreateCleanResult.UNRESOLVED
            if (!available()) return@withLock CapturedCreateCleanResult.INACTIVE
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

    /** Snapshot and retain the current local intention only; no Provider ACK or hold release. */
    suspend fun reconcileLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, mutation: MutationId): CapturedCreateReconciliationResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
        ensureCreateLifecycle()
        val expected = generation
        val policy = createPolicyRevision.get()
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
        suspend fun available(): Boolean {
            if (!isCurrent(expected, target.account) || !active()) return false
            val selected = store.selections(target.account).singleOrNull { it.scope == target }
            return selected?.enabled == true && selected.available && active()
        }
        try {
            if (!available()) return@withLock CapturedCreateReconciliationResult.INACTIVE
            store.admitTransaction = { it == target && active() }
            captures.admitCapture = { it == target && active() }
            projection.admitOperation = { active() }
            val submission = captures.readSubmission(target, localId) ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
            val reserved = submission.reservation
            val capture = reserved.intent.reservation
            if (capture.scope != target || capture.localId != localId || capture.id != id || reserved.attempt != attempt ||
                capture.format != remote.format || store.transaction(target) { checkpoint?.format } != capture.format ||
                projection.capturedAttachmentProtocol != 1 || mutation == id || mutation == attempt || mutation.value == localId.value)
                return@withLock CapturedCreateReconciliationResult.UNRESOLVED
            val identity = CapturedCreateDispatchIdentity.of(submission)
            val binding = journal.capturedBinding(identity) ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
            val receipt = journal.capturedAttachment(binding) ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
            val baseline = CapturedCreateBaseline(receipt,
                RemoteChange.Upsert(binding.eventId, binding.verifiedRevision, reserved.intent.content))
            val acknowledgement = store.capturedAcknowledgement(target, binding.eventId)
            if (acknowledgement != null && (acknowledgement.state != CapturedCreateAcknowledgementState.PROVEN ||
                acknowledgement.receipt.baseline != baseline || projection.capturedAcknowledgementProtocol != acknowledgement.receipt.protocol))
                return@withLock CapturedCreateReconciliationResult.UNRESOLVED
            suspend fun currentSnapshot(): CapturedCreateCurrentObservation = if (acknowledgement == null)
                projection.captureCapturedCreateCurrent(baseline)
                else projection.captureAcknowledgedCreateCurrent(acknowledgement.receipt)
            suspend fun exact(): Boolean = store.capturedAcknowledgement(target, binding.eventId) == acknowledgement &&
                (acknowledgement == null || projection.capturedAcknowledgementProtocol == acknowledgement.receipt.protocol) &&
                captures.readSubmission(target, localId) == submission &&
                remote.format == capture.format && projection.capturedAttachmentProtocol == receipt.protocol &&
                store.transaction(target) { checkpoint?.format } == capture.format &&
                journal.capturedBinding(identity) == binding && journal.capturedAttachment(binding) == receipt &&
                store.capturedBaseline(target, binding.eventId) == baseline
            if (!available()) return@withLock CapturedCreateReconciliationResult.INACTIVE
            if (!exact()) return@withLock CapturedCreateReconciliationResult.UNRESOLVED
            // Cached intentions cannot qualify the original row. Every replay takes a fresh,
            // assertion-only snapshot of the complete attachment, content and child absence.
            val observed = currentSnapshot()
            if (!available()) return@withLock CapturedCreateReconciliationResult.INACTIVE
            if (!exact()) return@withLock CapturedCreateReconciliationResult.UNRESOLVED
            if (observed == CapturedCreateCurrentObservation.Unsupported)
                return@withLock CapturedCreateReconciliationResult.UNSUPPORTED
            val snapshot = (observed as? CapturedCreateCurrentObservation.Supported)?.snapshot
                ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
            if (snapshot.baseline != baseline || snapshot.acknowledgement != acknowledgement?.receipt) return@withLock CapturedCreateReconciliationResult.UNRESOLVED
            val retained = store.retainCapturedLocalChange(snapshot, mutation)
            if (!available()) return@withLock CapturedCreateReconciliationResult.INACTIVE
            if (!exact()) return@withLock CapturedCreateReconciliationResult.UNRESOLVED
            val current = currentSnapshot() as? CapturedCreateCurrentObservation.Supported
            if (!available()) return@withLock CapturedCreateReconciliationResult.INACTIVE
            if (current?.snapshot != snapshot || !exact()) return@withLock CapturedCreateReconciliationResult.UNRESOLVED
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

    /** Explicit transfer; never submit again or adopt an original through ordinary projection. */
    suspend fun handoffLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateHandoffResult = mutex.withLock {
        handoffLocalCreateLocked(target, localId, id, attempt)
    }

    private suspend fun handoffLocalCreateLocked(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateHandoffResult {
        val captures = localCreates ?: return CapturedCreateHandoffResult.UNRESOLVED
        val journal = creates ?: return CapturedCreateHandoffResult.UNRESOLVED
        ensureCreateLifecycle()
        val expected = generation
        val policy = createPolicyRevision.get()
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
        suspend fun available(): Boolean = isCurrent(expected, target.account) && active() &&
            store.selections(target.account).singleOrNull { it.scope == target }?.let { it.enabled && it.available } == true && active()
        try {
            if (!available()) return CapturedCreateHandoffResult.INACTIVE
            store.admitTransaction = { it == target && active() }
            captures.admitCapture = { it == target && active() }
            projection.admitOperation = active
            val submission = captures.readSubmission(target, localId) ?: return CapturedCreateHandoffResult.UNRESOLVED
            val capture = submission.reservation.intent.reservation
            if (capture.scope != target || capture.localId != localId || capture.id != id ||
                submission.reservation.attempt != attempt || capture.format != remote.format)
                return CapturedCreateHandoffResult.UNRESOLVED
            val identity = CapturedCreateDispatchIdentity.of(submission)
            val binding = journal.capturedBinding(identity) ?: return CapturedCreateHandoffResult.UNRESOLVED
            val attachment = journal.capturedAttachment(binding) ?: return CapturedCreateHandoffResult.UNRESOLVED
            val original = CapturedCreateBaseline(attachment, RemoteChange.Upsert(binding.eventId,
                binding.verifiedRevision, submission.reservation.intent.content))
            var role = store.capturedHandoff(target, binding.eventId)
            if (role == null) {
                val ack = store.capturedAcknowledgement(target, binding.eventId) ?: return CapturedCreateHandoffResult.UNRESOLVED
                if (ack.state != CapturedCreateAcknowledgementState.PROVEN || ack.receipt.baseline != original)
                    return CapturedCreateHandoffResult.UNRESOLVED
                val frame = CapturedCreateHandoffFrame(CapturedCreateHandoffReceipt(ack.receipt, java.util.UUID.randomUUID().toString()), original.change)
                if (!available()) return CapturedCreateHandoffResult.INACTIVE
                if (!handoffSource(frame, active)) return CapturedCreateHandoffResult.UNRESOLVED
                role = store.reserveCapturedHandoff(frame.receipt)
            }
            if (role.frame.receipt.acknowledgement.baseline != original || !handoffSource(role.frame, active))
                return CapturedCreateHandoffResult.UNRESOLVED
            if (!available()) return CapturedCreateHandoffResult.INACTIVE
            if (role.state == CapturedCreateHandoffState.PENDING) {
                val clean = projection.inspectAcknowledgedCreateClean(role.frame.receipt.acknowledgement)
                if (!available()) return CapturedCreateHandoffResult.INACTIVE
                if (clean?.receipt != role.frame.receipt.acknowledgement || !handoffSource(role.frame, active) ||
                    store.capturedHandoff(target, binding.eventId) != role) return CapturedCreateHandoffResult.UNRESOLVED
                role = store.retainCapturedHandoff(role.frame.receipt, clean)
            } else {
                if (!recoverHandoffProjection(role.frame, expected, active)) {
                    store.waitCapturedHandoff(role.frame)
                    return CapturedCreateHandoffResult.WAITING
                }
                role = store.capturedHandoff(target, binding.eventId) ?: return CapturedCreateHandoffResult.UNRESOLVED
                val clean = projection.inspectCapturedHandoffBase(role.frame)
                if (!available()) return CapturedCreateHandoffResult.INACTIVE
                if (clean?.frame != role.frame || !handoffSource(role.frame, active) || !store.resumeCapturedHandoff(clean)) {
                    store.waitCapturedHandoff(role.frame)
                    return CapturedCreateHandoffResult.WAITING
                }
            }
            if (!available()) return CapturedCreateHandoffResult.INACTIVE
            return if (store.capturedHandoff(target, binding.eventId)?.let {
                it.frame == role.frame && it.state == CapturedCreateHandoffState.RELEASED && !it.waiting
            } == true && handoffSource(role.frame, active)) CapturedCreateHandoffResult.HANDED_OFF else CapturedCreateHandoffResult.UNRESOLVED
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            if (!active()) return CapturedCreateHandoffResult.INACTIVE else throw cancelled
        } catch (_: Exception) {
            return if (!active()) CapturedCreateHandoffResult.INACTIVE else CapturedCreateHandoffResult.UNRESOLVED
        } finally {
            captures.admitCapture = { false }; store.admitTransaction = { false }; projection.admitOperation = { false }
        }
    }

    /** Immutable local/dispatch provenance is checked separately from the mutable import reference. */
    private suspend fun handoffSource(frame: CapturedCreateHandoffFrame, active: () -> Boolean): Boolean {
        val captures = localCreates ?: return false
        val journal = creates ?: return false
        val original = frame.receipt.acknowledgement.baseline
        val attachment = original.receipt
        val identity = attachment.binding.identity
        if (!active() || remote.format != frame.format || projection.capturedHandoffProtocol != frame.receipt.protocol ||
            projection.capturedAcknowledgementProtocol != frame.receipt.acknowledgement.protocol ||
            projection.capturedAttachmentProtocol != attachment.protocol ||
            store.transaction(frame.scope) { checkpoint?.format } != frame.format) return false
        val previous = captures.admitCapture
        captures.admitCapture = { it == frame.scope && active() }
        try {
            val submission = captures.readSubmission(frame.scope, identity.localId) ?: return false
            return CapturedCreateDispatchIdentity.of(submission) == identity &&
                submission.reservation.intent.content == original.change.content &&
                journal.capturedBinding(identity) == attachment.binding &&
                journal.capturedAttachment(attachment.binding) == attachment && active()
        } finally { captures.admitCapture = previous }
    }

    /** Recover only historical proof. No Provider write, even after a later edit or unsupported import. */
    private suspend fun recoverHandoffProjection(frame: CapturedCreateHandoffFrame, expected: Long,
        active: () -> Boolean): Boolean {
        if (!handoffSource(frame, active)) return false
        val prepared = store.capturedHandoffProjection(frame) ?: return true
        val witness = projection.inspectCapturedHandoffProjection(prepared) ?: return false
        if (witness.plan != prepared || !isCurrent(expected, frame.scope.account) || !active() || !handoffSource(frame, active)) return false
        return store.restoreCapturedHandoffProjection(witness)
    }

    /** Retain a later dirty intention against the current reference, never the original sent base. */
    suspend fun reconcileHandoffLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, mutation: MutationId): CapturedCreateReconciliationResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
        ensureCreateLifecycle()
        val expected = generation
        val policy = createPolicyRevision.get()
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
        suspend fun available(): Boolean = isCurrent(expected, target.account) && active() &&
            store.selections(target.account).singleOrNull { it.scope == target }?.let { it.enabled && it.available } == true && active()
        try {
            if (!available()) return@withLock CapturedCreateReconciliationResult.INACTIVE
            store.admitTransaction = { it == target && active() }; captures.admitCapture = { it == target && active() }; projection.admitOperation = active
            val submission = captures.readSubmission(target, localId) ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
            val capture = submission.reservation.intent.reservation
            if (capture.id != id || capture.localId != localId || capture.scope != target || submission.reservation.attempt != attempt ||
                mutation == id || mutation == attempt || mutation.value == localId.value) return@withLock CapturedCreateReconciliationResult.UNRESOLVED
            val binding = journal.capturedBinding(CapturedCreateDispatchIdentity.of(submission)) ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
            var role = store.capturedHandoff(target, binding.eventId) ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
            if (role.state != CapturedCreateHandoffState.RELEASED || !handoffSource(role.frame, active) ||
                !recoverHandoffProjection(role.frame, expected, active)) return@withLock CapturedCreateReconciliationResult.UNRESOLVED
            role = store.capturedHandoff(target, binding.eventId) ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
            val observed = projection.captureCapturedHandoffCurrent(role.frame)
            if (!available()) return@withLock CapturedCreateReconciliationResult.INACTIVE
            if (!handoffSource(role.frame, active) || store.capturedHandoff(target, binding.eventId) != role)
                return@withLock CapturedCreateReconciliationResult.UNRESOLVED
            val snapshot = (observed as? CapturedHandoffCurrentObservation.Supported)?.snapshot
            if (snapshot?.frame != role.frame) {
                store.waitCapturedHandoff(role.frame)
                return@withLock if (observed == CapturedHandoffCurrentObservation.Unsupported)
                    CapturedCreateReconciliationResult.UNSUPPORTED else CapturedCreateReconciliationResult.UNRESOLVED
            }
            val retained = store.retainCapturedHandoffLocalChange(snapshot, mutation)
            if (!available()) return@withLock CapturedCreateReconciliationResult.INACTIVE
            val second = projection.captureCapturedHandoffCurrent(role.frame) as? CapturedHandoffCurrentObservation.Supported
            if (!available()) return@withLock CapturedCreateReconciliationResult.INACTIVE
            if (second?.snapshot != snapshot || !handoffSource(role.frame, active) ||
                store.capturedHandoff(target, binding.eventId)?.frame != role.frame) return@withLock CapturedCreateReconciliationResult.UNRESOLVED
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
            captures.admitCapture = { false }; store.admitTransaction = { false }; projection.admitOperation = { false }
        }
    }

    private class CapturedProjectionPending : IllegalStateException("Captured creation reconciliation pending")
    private suspend fun capturedRawHold(target: CalendarScope): CapturedProjectionHold {
        val dispatch = creates?.capturedProjectionHold(target, remote.format) ?: CapturedProjectionHold()
        val provenance = try { store.capturedProvenanceEvents(target) }
        catch (_: IllegalArgumentException) { return dispatch.copy(scopeBlocked = true) }
        return dispatch.copy(events = dispatch.events + provenance)
    }
    private suspend fun capturedProjectionHold(target: CalendarScope): CapturedProjectionHold {
        val raw = capturedRawHold(target)
        if (raw.scopeBlocked || raw.events.isEmpty()) return raw
        val expected = generation
        val policy = createPolicyRevision.get()
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
        val released = mutableSetOf<EventId>()
        for (event in raw.events) {
            try {
                val role = store.capturedHandoff(target, event) ?: continue
                if (role.state == CapturedCreateHandoffState.RELEASED && !role.waiting &&
                    handoffSource(role.frame, active) && store.capturedHandoffCanProject(role.frame) && active()) released += event
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Malformed or lost provenance remains held; other events can drain. */ }
        }
        return raw.copy(events = raw.events - released)
    }

    // Keep the hold read and status publication out of the larger import coroutine.
    private suspend fun publishImportProgress(target: CalendarScope, status: String, completedAt: Long? = null) {
        val held = capturedProjectionHold(target).blocksProjection
        store.status(target, if (held) "CREATE_PENDING" else status, if (held) null else completedAt)
    }
    private suspend fun requireProjectionAvailable(target: CalendarScope, eventId: EventId) {
        if (capturedProjectionHold(target).holds(eventId)) throw CapturedProjectionPending()
    }
    private suspend fun runnableProjections(target: CalendarScope, limit: Int = 100): List<ProjectionIntent> {
        val hold = capturedProjectionHold(target)
        return if (hold.scopeBlocked) emptyList() else store.pending(target, limit, hold.events)
    }

    private suspend fun captureBaseline(expected: Long, target: CalendarScope, eventId: EventId): RemoteChange.Upsert? {
        if (capturedRawHold(target).holds(eventId)) throw CapturedProjectionPending()
        requireProjectionAvailable(target, eventId)
        val receipt = store.projectionReceipt(target, eventId)
        val recovered = receipt?.let { recoverProjection(expected, it) } ?: false
        val base = store.projectedBaseline(target, eventId) ?: return null
        // An opaque revision alone cannot choose between different prepared contents.
        if (receipt != null && !recovered && receipt.change != base && receipt.change.revision == base.revision) return null
        return base
    }

    /** A marker proves the historical write, not that the current Provider content is unchanged. */
    private suspend fun recoverProjection(expected: Long, receipt: ProjectionReceipt): Boolean {
        requireProjectionAvailable(receipt.scope, receipt.change.eventId)
        if (receipt.format != remote.format || receipt.protocol != projection.projectionReceiptProtocol) return false
        val witness = projection.inspectProjectionReceipt(receipt) ?: return false
        check(witness.receipt == receipt) { "Projection witness differs" }
        if (!isCurrent(expected, receipt.scope.account)) throw CancellationException("Inactive projection recovery")
        requireProjectionAvailable(receipt.scope, receipt.change.eventId)
        return store.restoreProjection(witness)
    }

    /** Kept out of the larger import coroutine; preparation precedes every tracked Provider write. */
    private suspend fun projectPending(intent: ProjectionIntent, expected: Long) {
        if (capturedRawHold(intent.scope).holds(intent.change.eventId)) {
            projectCapturedHandoff(intent, expected)
            return
        }
        requireProjectionAvailable(intent.scope, intent.change.eventId)
        val outstanding = store.projectionReceipt(intent.scope, intent.change.eventId)
        if (outstanding != null) {
            val recovered = recoverProjection(expected, outstanding)
            if (recovered && outstanding.change == intent.change) return
            if (!recovered && (outstanding.change != intent.change || outstanding.format != remote.format ||
                outstanding.protocol != projection.projectionReceiptProtocol)) {
                throw CalendarProjectionException(CalendarProjectionFailure.PROVIDER_REJECTED)
            }
        }
        requireProjectionAvailable(intent.scope, intent.change.eventId)
        if (projection.projectionReceiptProtocol == 1 && ProjectionReceipt.supports(intent.change)) {
            val prepared = store.prepareProjection(intent, remote.format)
            val witness = projection.applyTracked(prepared)
            check(witness.receipt == prepared) { "Projection witness differs" }
            if (!isCurrent(expected, intent.scope.account)) throw CancellationException("Inactive projection acknowledgement")
            requireProjectionAvailable(intent.scope, intent.change.eventId)
            check(store.restoreProjection(witness)) { "Projection acknowledgement remains unresolved" }
        } else {
            projection.apply(intent, if (intent.change is RemoteChange.Deleted)
                store.deletedSeriesUid(intent.scope, intent.change.eventId) else null)
            requireProjectionAvailable(intent.scope, intent.change.eventId)
            store.acknowledge(intent)
        }
    }

    /** The durable transfer authorizes only conditional specialized updates, never generic ACK. */
    private suspend fun projectCapturedHandoff(intent: ProjectionIntent, expected: Long) {
        val target = intent.scope
        val event = intent.change.eventId
        val policy = createPolicyRevision.get()
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
        val previousStore = store.admitTransaction
        val previousProjection = projection.admitOperation
        store.admitTransaction = { it == target && active() }
        projection.admitOperation = active
        var frame: CapturedCreateHandoffFrame? = null
        suspend fun available(): Boolean = isCurrent(expected, target.account) && active() &&
            store.selections(target.account).singleOrNull { it.scope == target }?.let { it.enabled && it.available } == true && active()
        suspend fun waitForResolution() {
            if (active()) frame?.let { store.waitCapturedHandoff(it) }
        }
        try {
            if (!available()) throw CancellationException("Inactive captured projection")
            var role = store.capturedHandoff(target, event) ?: return
            frame = role.frame
            if (role.state != CapturedCreateHandoffState.RELEASED || role.waiting || !handoffSource(role.frame, active) ||
                !store.capturedHandoffCanProject(role.frame)) return
            var prepared = store.capturedHandoffProjection(role.frame)
            if (prepared != null) {
                val proof = projection.inspectCapturedHandoffProjection(prepared)
                if (!available()) throw CancellationException("Inactive captured recovery")
                if (!handoffSource(role.frame, active)) { waitForResolution(); return }
                if (proof != null) {
                    check(proof.plan == prepared) { "Captured projection proof differs" }
                    check(store.restoreCapturedHandoffProjection(proof)) { "Captured projection recovery unresolved" }
                    role = store.capturedHandoff(target, event) ?: return
                    frame = role.frame
                    prepared = null
                }
            }
            // Recovery can advance the reference and leave a different/newer queue entry.
            val current = store.capturedHandoffPending(role.frame) ?: return
            if (!store.capturedHandoffCanProject(role.frame) || !supportsCapturedHandoff(current.change)) {
                waitForResolution(); return
            }
            if (prepared != null && prepared.receipt.change != current.change) { waitForResolution(); return }
            val clean = projection.inspectCapturedHandoffBase(role.frame)
            if (!available()) throw CancellationException("Inactive captured qualification")
            if (clean?.frame != role.frame || !handoffSource(role.frame, active)) { waitForResolution(); return }
            val plan = prepared ?: store.prepareCapturedHandoffProjection(role.frame, current)
            if (!available()) throw CancellationException("Inactive captured write")
            if (!handoffSource(role.frame, active)) { waitForResolution(); return }
            val proof = projection.applyCapturedHandoffProjection(plan)
            if (!available()) throw CancellationException("Inactive captured acknowledgement")
            if (proof?.plan != plan || !handoffSource(role.frame, active)) { waitForResolution(); return }
            check(store.restoreCapturedHandoffProjection(proof)) { "Captured projection acknowledgement unresolved" }
        } catch (failure: CalendarProjectionException) {
            if (failure.category in setOf(CalendarProjectionFailure.PROVIDER_REJECTED,
                    CalendarProjectionFailure.IDENTITY_CONFLICT, CalendarProjectionFailure.CALENDAR_MISSING)) waitForResolution() else throw failure
        } finally {
            store.admitTransaction = previousStore
            projection.admitOperation = previousProjection
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
                        for (intent in runnableProjections(calendarScope)) {
                            if (!isCurrent(expected, account)) throw CancellationException()
                            projectPending(intent, expected)
                        }
                        return runnableProjections(calendarScope, 1).isNotEmpty()
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
                        publishImportProgress(calendarScope, status,
                            if (!blocked && progress.traversalComplete && !remaining && deltaProgress?.blocked != true &&
                                deltaProgress?.workRemaining != true &&
                                !historyGap && !missingSeries && !mappingRemaining &&
                                (localProgress || selection.completedAt == null || selection.status !in setOf("IMPORTED", "PARTIAL")))
                                System.currentTimeMillis() else null)
                        retry = retry || deferredNetwork
                        remaining = remaining || incompleteNetwork || (allowNetwork && mappingRemaining)
                    } else if (network && selection.nextAttempt > System.currentTimeMillis()) {
                        retry = true
                        publishImportProgress(calendarScope, "PENDING")
                    } else if (network && !remaining) {
                        val progress = engine.importPages(calendarScope)
                        remaining = drain() || !progress.traversalComplete || store.mappingRescanPending(calendarScope)
                        val missingSeries = store.blockedSeriesCount(calendarScope) > 0
                        if (!isCurrent(expected, account)) return ImportRunResult.COMPLETE
                        publishImportProgress(calendarScope, if (remaining) "PENDING" else if (missingSeries) "SERIES_PENDING"
                            else if (selection.historyGap) "HISTORY_GAP" else "IMPORTED",
                            if (remaining || selection.historyGap || missingSeries)
                                null else System.currentTimeMillis())
                    } else {
                        // Local work must not overwrite the reason the last network run was blocked.
                        val blocked = selection.status in setOf("NETWORK", "UNSUPPORTED", "ERROR", "AUTH", "CAPACITY", "LOCAL_KEYS")
                        publishImportProgress(calendarScope, if (blocked) selection.status else if (remaining || store.mappingRescanPending(calendarScope)) "PENDING" else
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
                    store.status(calendarScope, if (failure is CapturedProjectionPending) "CREATE_PENDING" else "ERROR")
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
