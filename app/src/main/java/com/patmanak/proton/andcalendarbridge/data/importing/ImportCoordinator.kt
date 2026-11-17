package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.Context
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionFailure
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreatePauseReason
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemTextLocalOwner
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemDeleteLocalOwner
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemTimeLocalOwner
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemPersonalLocalOwner
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogState
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogStatus
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One process owner for foreground/workers and explicitly requested, offline conflict history. */
class ImportCoordinator(
    private val context: Context,
    val store: ImportStore,
    private val remote: ReadOnlyCalendarRemote,
    private val projection: CalendarProjection,
    private val scope: CoroutineScope,
    private val scheduler: ImportScheduler = WorkManagerImportScheduler(context),
    private val creates: CreateAttemptStore? = null,
    private val localCreates: LocalCreateStore? = null,
    private val systemCreatesEnabled: (CalendarScope) -> Boolean = { false },
    private val systemTitlesEnabled: (CalendarScope) -> Boolean = { false },
    private val systemTextsEnabled: (CalendarScope) -> Boolean = { false },
    private val systemTimesEnabled: (CalendarScope) -> Boolean = { false },
    private val systemDeletesEnabled: (CalendarScope) -> Boolean = { false },
) {
    private val mutex = Mutex()
    private val serial = AccountSerialExecutor()
    private val engine = SyncEngine(store, remote, serial)
    private val stagedEngine = (remote as? StagedCalendarRemote)?.let { StagedImportEngine(store, it, serial) }
    private val deltaEngine = (remote as? CalendarDeltaRemote)?.let { DeltaImportEngine(store, it, serial) }
    private val mutableState = MutableStateFlow(ImportUiState())
    val state = mutableState.asStateFlow()
    private val mutableConflictArchiveEpoch = MutableStateFlow(0L)
    /** Invalidates presentation memory even when a brief session/permission loss is conflated. */
    val conflictArchiveEpoch = mutableConflictArchiveEpoch.asStateFlow()
    @Volatile private var authenticated = false
    @Volatile private var generation = 0L
    private val createPolicyRevision = AtomicLong()
    private var createInitialized = false
    private var createGeneration = -1L
    private val createAccount = AtomicReference<AccountId?>()
    private val pendingCreatePauses = ConcurrentHashMap<CalendarScope, CreatePauseReason>()
    private val pendingAccountPauses = ConcurrentHashMap.newKeySet<AccountId>()
    @Volatile private var pendingPermissionPause = false
    private val providerIds = context.getSharedPreferences("calendar-provider-mappings", Context.MODE_PRIVATE)

    private val editAdmission = SystemEditAdmission(
        generation = { generation }, policyRevision = { createPolicyRevision.get() },
        currentAccount = { createAccount.get() }, authenticated = { authenticated },
        hasPermissions = ::hasCalendarPermissions, createsEnabled = systemCreatesEnabled,
        selected = { target -> state.value.selections.any { it.scope == target && it.enabled && it.available } },
    )

    private val capturedCreates = ImportCoordinatorCapturedCreates(
        store, remote, projection, mutex, scheduler, creates, localCreates,
        generation = { generation }, policyRevision = { createPolicyRevision.get() },
        authenticated = { authenticated }, systemCreatesEnabled = systemCreatesEnabled,
        hasCalendarPermissions = ::hasCalendarPermissions, isCurrent = ::isCurrent,
        ensureCreateLifecycle = ::ensureCreateLifecycle, qualifyCreate = ::qualifyCreate,
    )

    private val titleWrites = ImportCoordinatorTitleWrites(
        store, remote, projection, mutex,
        generation = { generation }, policyRevision = { createPolicyRevision.get() },
        authenticated = { authenticated }, currentAccount = { createAccount.get() },
        systemCreatesEnabled = systemCreatesEnabled, systemTitlesEnabled = systemTitlesEnabled,
        hasCalendarPermissions = ::hasCalendarPermissions, isCurrent = ::isCurrent,
        capturedRawHold = ::capturedRawHold, handoffSource = ::handoffSource,
    )

    private val projectionWork = ImportCoordinatorProjection(
        store, remote, projection, creates,
        generation = { generation }, policyRevision = { createPolicyRevision.get() },
        authenticated = { authenticated }, hasCalendarPermissions = ::hasCalendarPermissions,
        isCurrent = ::isCurrent, handoffSource = ::handoffSource,
    )

    private val titleRecovery = ImportCoordinatorTitleRecovery(
        store, remote, projection, mutex,
        generation = { generation }, policyRevision = { createPolicyRevision.get() },
        authenticated = { authenticated }, systemCreatesEnabled = systemCreatesEnabled,
        systemTitlesEnabled = systemTitlesEnabled, hasCalendarPermissions = ::hasCalendarPermissions,
        isCurrent = ::isCurrent, capturedRawHold = ::capturedRawHold, handoffSource = ::handoffSource,
    )

    private val localChanges = ImportCoordinatorLocalChanges(
        store, remote, projection, mutex, projectionWork, titleWrites,
        generation = { generation }, policyRevision = { createPolicyRevision.get() },
        authenticated = { authenticated }, systemCreatesEnabled = systemCreatesEnabled,
        hasCalendarPermissions = ::hasCalendarPermissions, isCurrent = ::isCurrent,
        capturedRawHold = ::capturedRawHold,
    )

    /** Inspection never renews server rights or grants mutation/acknowledgement authority. */
    val conflictArchiveReader: ConflictArchiveReader = localChanges.conflictArchiveReader

    private val editAccess = ImportCoordinatorEditAccess(
        store, remote, projection, mutex,
        generation = { generation }, policyRevision = { createPolicyRevision.get() },
        currentAccount = { createAccount.get() }, authenticated = { authenticated },
        hasCalendarPermissions = ::hasCalendarPermissions, systemCreatesEnabled = systemCreatesEnabled,
        selected = { target -> state.value.selections.any { it.scope == target && it.enabled && it.available } },
        capturedRawHold = ::capturedRawHold, currentSystemTitleHandoff = ::currentSystemTitleHandoff,
    )

    private val nativeCalendars by lazy { ImportCoordinatorNativeCalendars(store, projection, mutex,
        { generation }, { createPolicyRevision.get() }, { authenticated }, ::hasCalendarPermissions,
        systemCreatesEnabled, ::isCurrent, { authenticationChanged(false) },
        { pending -> mutableState.update { it.copy(nativeRemovalPending = pending) } }) }

    private val incoming = ImportCoordinatorIncoming(
        store, remote, projection, scope, mutex, scheduler, serial, engine, stagedEngine, deltaEngine,
        providerIds, state, mutableState, projectionWork,
        generation = { generation }, authenticated = { authenticated },
        policyRevision = { createPolicyRevision.get() }, systemCreatesEnabled = systemCreatesEnabled,
        hasCalendarPermissions = ::hasCalendarPermissions, currentObservedAccount = ::currentObservedAccount,
        isCurrent = ::isCurrent, rememberAccessibleCatalog = ::rememberAccessibleCatalog,
        refreshState = ::refreshState, retainCreatePermissionLoss = ::retainCreatePermissionLoss,
        beforeNativeCreation = { nativeCalendars.drainLocked() },
    )

    init {
        creates?.admitDispatch = { false }
        localCreates?.admitCapture = { false }
    }

    /** Production startup is conservative: legacy preparations require explicit future resolution. */
    suspend fun initializeCreates() = mutex.withLock { ensureCreateLifecycle() }

    /** Synchronous invalidation also covers a disable/re-enable during adapter IO. */
    fun systemCreatePolicyChanged(target: CalendarScope, enabled: Boolean) {
        advanceCreatePolicy()
        creates?.admitDispatch = { false }
        capturedCreates.invalidate(target)
        if (!enabled) pendingCreatePauses[target] = CreatePauseReason.CALENDAR_DISABLED
    }

    /** Capture once before the worker's first suspension. Fresh API calls cannot renew
     * a run after logout, selection changes or policy revocation/restoration.
     */
    fun systemTitleRunAdmission(target: CalendarScope): (CalendarScope) -> Boolean {
        val expected = generation
        val policy = createPolicyRevision.get()
        return { scope -> scope == target && authenticated && generation == expected &&
            createPolicyRevision.get() == policy && hasCalendarPermissions() &&
            systemCreatesEnabled(target) && systemTitlesEnabled(target) &&
            state.value.selections.any { it.scope == target && it.enabled && it.available } }
    }

    /** Capture the original worker epoch; the authenticated facade is never called by this lease. */
    fun systemTextRunAdmission(target: CalendarScope): (CalendarScope) -> Boolean = editAdmission.captureRun(target, true, systemTextsEnabled)

    /** Historical proof recovery needs CREATE access, but never borrows text sending consent. */
    fun systemTextProofAdmission(target: CalendarScope): (CalendarScope) -> Boolean = editAdmission.captureRun(target, false, systemTextsEnabled)

    /** Factual response retention survives editing-policy loss, never logout or account replacement.
     * The caller additionally bounds this lease to its exact invocation and UNKNOWN record.
     */
    fun systemTextEvidenceAdmission(target: CalendarScope): (CalendarScope) -> Boolean = editAdmission.captureEvidence(target)

    internal val localTextOwners: SystemTextLocalOwner = ImportCoordinatorTextOwner(
        store, remote, projection, editAccess, systemTextsEnabled, ::systemTextEvidenceAdmission, ::handoffSource,
    ).owner

    /** Capture the original worker epoch; the authenticated facade is never called by this lease. */
    fun systemTimeRunAdmission(target: CalendarScope): (CalendarScope) -> Boolean = editAdmission.captureRun(target, true, systemTimesEnabled)

    /** Historical proof recovery needs CREATE access, but never borrows time sending consent. */
    fun systemTimeProofAdmission(target: CalendarScope): (CalendarScope) -> Boolean = editAdmission.captureRun(target, false, systemTimesEnabled)

    /** Factual response retention survives editing-policy loss, never logout or account replacement.
     * The caller additionally bounds this lease to its exact invocation and UNKNOWN record.
     */
    fun systemTimeEvidenceAdmission(target: CalendarScope): (CalendarScope) -> Boolean = editAdmission.captureEvidence(target)

    internal val localTimeOwners: SystemTimeLocalOwner = ImportCoordinatorTimeOwner(
        store, remote, projection, editAccess, systemTimesEnabled, ::systemTimeEvidenceAdmission, ::handoffSource,
    ).owner

    fun systemPersonalRunAdmission(target: CalendarScope): (CalendarScope) -> Boolean = editAdmission.captureRun(target, true, ::personalSendingEnabled)

    /** Freeze the policy epoch once, then require every field changed by this exact intent. */
    fun systemPersonalConsentAdmission(target: CalendarScope): (LocalEditIntent) -> Boolean {
        val active = systemPersonalRunAdmission(target)
        return { intent -> intent.scope == target && active(target) && personalConsent(intent) && active(target) }
    }

    /** Historical proof recovery needs CREATE access, without field sending consent. */
    fun systemPersonalProofAdmission(target: CalendarScope): (CalendarScope) -> Boolean = editAdmission.captureRun(target, false, ::personalSendingEnabled)

    /** Factual response retention survives editing-policy loss, never logout or account replacement.
     * The caller additionally bounds this lease to its exact invocation and UNKNOWN record.
     */
    fun systemPersonalEvidenceAdmission(target: CalendarScope): (CalendarScope) -> Boolean = editAdmission.captureEvidence(target)

    internal val personalOwners: SystemPersonalLocalOwner = ImportCoordinatorPersonalOwner(
        store, remote, projection, editAccess, ::personalSendingEnabled, ::personalConsent,
        ::systemPersonalEvidenceAdmission, ::handoffSource,
    ).owner

    suspend fun retainUnsupportedBarePersonalUpdate(target: CalendarScope, reference: RetainedLocalChangeRef,
        admit: (CalendarScope) -> Boolean, consent: (LocalEditIntent) -> Boolean): Boolean {
        if (reference.kind != MutationKind.UPDATE || reference.state != LocalEditState.PAUSED) return false
        var intent: LocalEditIntent? = null
        val lease = { requested: CalendarScope -> admit(requested) && (intent?.let(consent) != false) }
        return editAccess.withScopedEditOwner(target, lease, true, false, sendingEnabled = ::personalSendingEnabled) { access ->
            val frozen = access.read { store.localEdit(target, reference.eventId) } ?: return@withScopedEditOwner true
            intent = frozen
            if (frozen.id != reference.mutationId) return@withScopedEditOwner true
            if (!consent(frozen)) return@withScopedEditOwner false
            if (access.read { store.hasUnsupportedLegacyUpdates(target) }) return@withScopedEditOwner true
            val role = access.read { store.capturedHandoff(target, reference.eventId) }
            if (role != null && !access.read { currentSystemTitleHandoff(role, frozen, access::active) }) return@withScopedEditOwner false
            val native = access.read { if (role == null) projection.captureOwnedPersonalEdit(target, frozen.base)?.content else
                (projection.captureCapturedHandoffCurrent(role.frame) as? CapturedHandoffCurrentObservation.Supported)
                    ?.snapshot?.takeIf { it.frame == role.frame && !it.deleted }?.content }
            if (native == frozen.content) return@withScopedEditOwner false
            val retained = access.read { store.retainUnsupportedBareUpdate(frozen) }
            if (retained) refreshState()
            retained
        }
    }

    fun systemDeleteRunAdmission(target: CalendarScope): (CalendarScope) -> Boolean = deleteAdmission(target, true)
    fun systemDeleteProofAdmission(target: CalendarScope): (CalendarScope) -> Boolean = deleteAdmission(target, false)
    fun systemDeleteEvidenceAdmission(target: CalendarScope): (CalendarScope) -> Boolean = systemTimeEvidenceAdmission(target)

    private fun deleteAdmission(target: CalendarScope, consent: Boolean): (CalendarScope) -> Boolean {
        val base = editAdmission.captureRun(target, false, systemTimesEnabled)
        val closed = AtomicBoolean(false)
        return { requested ->
            val allowed = !closed.get() && base(requested) && (!consent || systemDeletesEnabled(target))
            if (!allowed) closed.set(true)
            allowed && !closed.get()
        }
    }

    private val deleteOwner = ImportCoordinatorDeleteOwner(
        store, remote, projection, editAccess, systemDeletesEnabled,
        ::systemDeleteEvidenceAdmission, ::systemDeleteProofAdmission, ::capturedRawHold, ::refreshState,
    )
    internal val localDeleteOwners: SystemDeleteLocalOwner = deleteOwner.owner

    /** A retained ACK explains only its exact marker. Native restoration/new edits become visible conflicts. */
    suspend fun reconcileReservedDeleteChange(target: CalendarScope, event: EventId, deleted: Boolean, dirty: Boolean): Boolean =
        deleteOwner.reconcileReservedDeleteChange(target, event, deleted, dirty)

    private fun personalSendingEnabled(scope: CalendarScope): Boolean =
        systemTitlesEnabled(scope) || systemTextsEnabled(scope) || systemTimesEnabled(scope)

    private fun personalConsent(intent: LocalEditIntent): Boolean = PersonalEditConsent.allowed(intent,
        systemTitlesEnabled, systemTextsEnabled, systemTimesEnabled, systemCreatesEnabled)

    suspend fun configureSystemCalendar(target: CalendarScope): Boolean = nativeCalendars.configure(target)

    /** Also closes retained mirrors after sign-out/account changes and a cold process restart. */
    suspend fun reconcileSystemCalendarAccess() = nativeCalendars.reconcile()
    suspend fun reprojectEventColors(): Boolean = nativeCalendars.reprojectColors()
    suspend fun disconnectCalendars() = nativeCalendars.disconnect()

    suspend fun discoverSystemChanges(target: CalendarScope, afterRow: Long = 0, limit: Int = 25):
        com.patmanak.proton.andcalendarbridge.data.calendar.SystemCalendarChangePage? =
        localChanges.discoverSystemChanges(target, afterRow, limit)

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

    private fun advanceCreatePolicy() {
        createPolicyRevision.incrementAndGet()
        mutableConflictArchiveEpoch.update { it + 1 }
    }

    private fun retainObservedCreateAccount(account: AccountId?) {
        val previous = createAccount.getAndSet(account)
        if (previous != null && previous != account) {
            advanceCreatePolicy()
            creates?.admitDispatch = { false }
            if (creates != null) pendingAccountPauses.add(previous)
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
        advanceCreatePolicy()
        if (creates == null) return
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
        mutableConflictArchiveEpoch.update { it + 1 }
        capturedCreates.clearLeases()
        creates?.admitDispatch = { false }
        scope.launch {
            try { initializeCreates() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Writes remain closed; the next explicit command retries initialization. */ }
        }
        mutableState.value = ImportUiState(hasPermission = hasCalendarPermissions(), authenticated = value, selectionsLoaded = !value,
            nativeRemovalPending = state.value.nativeRemovalPending)
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
            advanceCreatePolicy()
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

    suspend fun captureAndPrepareLocalTitle(target: CalendarScope, eventId: EventId, id: MutationId,
        timestamp: java.time.Instant, port: CalendarLocalEditPreparationPort): LocalTitlePreparationResult =
        localChanges.captureAndPrepareLocalTitle(target, eventId, id, timestamp, port)

    suspend fun inspectAndRecoverLocalTitles(query: TitleUpdateWorkQuery, journal: TitleUpdateRecoveryJournal,
        port: CalendarTitleUpdateSubmissionPort, allowNetwork: Boolean = false): TitleUpdateRecoveryProgress =
        titleRecovery.inspectAndRecoverLocalTitles(query, journal, port, allowNetwork)

    suspend fun recoverLocalTitles(query: TitleUpdateWorkQuery,
        journal: TitleUpdateOfflineRecoveryJournal): TitleUpdateOfflineRecoveryProgress =
        titleRecovery.recoverLocalTitles(query, journal)

    suspend fun inspectSystemTitles(query: TitleUpdateWorkQuery, journal: TitleUpdateRecoveryJournal,
        port: CalendarTitleUpdateSubmissionPort, admission: (CalendarScope) -> Boolean,
        proofRetained: () -> Unit): TitleUpdateRecoveryProgress =
        titleRecovery.inspectSystemTitles(query, journal, port, admission, proofRetained)

    suspend fun acknowledgeLocalTitle(target: CalendarScope, eventId: EventId, attempt: MutationId,
        journal: TitleUpdateResultJournal): TitleUpdateAcknowledgementResult =
        titleRecovery.acknowledgeLocalTitle(target, eventId, attempt, journal)

    suspend fun completeLocalTitle(target: CalendarScope, eventId: EventId, attempt: MutationId,
        journal: TitleUpdateCompletionJournal): TitleUpdateCompletionResult =
        titleRecovery.completeLocalTitle(target, eventId, attempt, journal)

    suspend fun captureLocalEdit(target: CalendarScope, eventId: EventId, id: MutationId): LocalEditCaptureResult =
        localChanges.captureLocalEdit(target, eventId, id)
    suspend fun captureLocalSeriesEdit(target: CalendarScope, eventId: EventId, id: MutationId): LocalEditCaptureResult =
        localChanges.captureLocalSeriesEdit(target, eventId, id)

    suspend fun captureSystemLocalEdit(target: CalendarScope, eventId: EventId,
        beforeNewOwner: suspend () -> Unit = {}): LocalEditCaptureResult =
        localChanges.captureSystemLocalEdit(target, eventId, beforeNewOwner)

    suspend fun retainedLocalChanges(query: RetainedLocalChangeQuery): RetainedLocalChangeListing =
        localChanges.retainedLocalChanges(query)

    suspend fun inspectLocalEdit(target: CalendarScope, eventId: EventId, id: MutationId): LocalEditInspection =
        localChanges.inspectLocalEdit(target, eventId, id)

    suspend fun inspectLocalDelete(target: CalendarScope, eventId: EventId, id: MutationId): LocalDeleteInspection =
        localChanges.inspectLocalDelete(target, eventId, id)

    suspend fun submitAndRetainLocalTitleAttempt(record: TitleUpdateAttemptRecord,
        attempts: TitleUpdateAttemptJournal, results: TitleUpdateResultJournal,
        port: CalendarTitleUpdateSubmissionPort, allowNetwork: Boolean = false): TitleUpdateVerifiedResult? =
        titleWrites.submitAndRetainLocalTitleAttempt(record, attempts, results, port, allowNetwork)

    suspend fun submitLocalTitleAttempt(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
        port: CalendarTitleUpdateSubmissionPort, allowNetwork: Boolean = false): TitleUpdateSubmissionObservation =
        titleWrites.submitLocalTitleAttempt(record, journal, port, allowNetwork)

    suspend fun prepareSystemTitle(target: CalendarScope, event: EventId, owner: MutationId,
        attempt: MutationId, timestamp: java.time.Instant, journal: TitleUpdatePreparationJournal,
        localPort: CalendarLocalEditPreparationPort, titlePort: CalendarTitleUpdateSubmissionPort,
        admission: (CalendarScope) -> Boolean, existingOnly: Boolean = false): LocalTitleAttemptPreparation =
        titleWrites.prepareSystemTitle(target, event, owner, attempt, timestamp, journal, localPort, titlePort, admission, existingOnly)

    suspend fun submitSystemTitle(record: TitleUpdateAttemptRecord, journal: TitleUpdateRecoveryJournal,
        port: CalendarTitleUpdateSubmissionPort, admission: (CalendarScope) -> Boolean): TitleUpdateVerifiedResult? =
        titleWrites.submitSystemTitle(record, journal, port, admission)

    suspend fun prepareLocalTitleAttempt(candidate: TitleUpdatePreparationReservation,
        journal: TitleUpdatePreparationJournal, port: CalendarTitleUpdateSubmissionPort,
        allowNetwork: Boolean = false, systemTitle: Boolean = false,
        admission: (CalendarScope) -> Boolean = { true }): LocalTitleAttemptPreparation =
        titleWrites.prepareLocalTitleAttempt(candidate, journal, port, allowNetwork, systemTitle, admission)

    suspend fun retainLocalTitleAttempt(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
        port: CalendarTitleUpdateSubmissionPort): LocalTitleAttemptRetentionResult =
        titleWrites.retainLocalTitleAttempt(record, journal, port)

    suspend fun prepareLocalTitle(target: CalendarScope, eventId: EventId, id: MutationId,
        timestamp: java.time.Instant, port: CalendarLocalEditPreparationPort): LocalTitlePreparationResult =
        titleWrites.prepareLocalTitle(target, eventId, id, timestamp, port)

    private suspend fun currentSystemTitleHandoff(role: CapturedCreateHandoffRecord, intent: LocalEditIntent,
        active: () -> Boolean): Boolean = titleWrites.currentSystemTitleHandoff(role, intent, active)

    suspend fun captureLocalDelete(target: CalendarScope, eventId: EventId, id: MutationId,
        beforeNewOwner: suspend () -> Unit = {}): LocalEditCaptureResult =
        localChanges.captureLocalDelete(target, eventId, id, beforeNewOwner)

    suspend fun captureLocalCreate(target: CalendarScope, eventRow: Long, localId: LocalEventId,
        id: MutationId, timestamp: java.time.Instant, series: Boolean = false, autoProfile: Boolean = false): LocalEditCaptureResult =
        capturedCreates.captureLocalCreate(target, eventRow, localId, id, timestamp, series, autoProfile)

    suspend fun prepareLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        port: CalendarCreatePreparationPort): LocalCreatePreparationResult =
        capturedCreates.prepareLocalCreate(target, localId, id, port)
    suspend fun captureLocalOccurrence(target: CalendarScope, master: RemoteChange.Upsert, eventRow: Long,
        localId: LocalEventId, id: MutationId, timestamp: java.time.Instant): LocalEditCaptureResult =
        capturedCreates.captureLocalOccurrence(target, master, eventRow, localId, id, timestamp)

    suspend fun stageLocalCreateSubmission(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, port: CalendarCreateSubmissionPort): LocalCreateSubmissionResult =
        capturedCreates.stageLocalCreateSubmission(target, localId, id, attempt, port)

    suspend fun resumePreparedLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, port: CalendarCreateSubmissionPort,
        allowPaused: Boolean = true): LocalCreateSubmissionResult =
        capturedCreates.resumePreparedLocalCreate(target, localId, id, attempt, port, allowPaused)

    suspend fun submitLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, port: CalendarCreateSubmissionPort): CreateSubmissionObservation =
        capturedCreates.submitLocalCreate(target, localId, id, attempt, port)

    suspend fun recoverHistoricalSystemCreate(target: CalendarScope, row: Long, marker: String?, after: String?):
        com.patmanak.proton.andcalendarbridge.data.outgoing.HistoricalCreateRecovery =
        capturedCreates.recoverHistoricalSystemCreate(target, row, marker, after)

    suspend fun bindLocalCreateObservation(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, port: CalendarCreateSubmissionPort? = null): CapturedCreateBindingResult =
        capturedCreates.bindLocalCreateObservation(target, localId, id, attempt, port)

    suspend fun attachLocalCreateBinding(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateAttachmentResult =
        capturedCreates.attachLocalCreateBinding(target, localId, id, attempt)

    suspend fun retainLocalCreateBaseline(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateBaselineResult =
        capturedCreates.retainLocalCreateBaseline(target, localId, id, attempt)

    suspend fun acknowledgeLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateAcknowledgementResult =
        capturedCreates.acknowledgeLocalCreate(target, localId, id, attempt)

    suspend fun qualifyAcknowledgedLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateCleanResult =
        capturedCreates.qualifyAcknowledgedLocalCreate(target, localId, id, attempt)

    suspend fun reconcileLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, mutation: MutationId): CapturedCreateReconciliationResult =
        capturedCreates.reconcileLocalCreate(target, localId, id, attempt, mutation)

    suspend fun handoffLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateHandoffResult =
        capturedCreates.handoffLocalCreate(target, localId, id, attempt)

    suspend fun reconcileHandoffLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, mutation: MutationId? = null,
        beforeNewOwner: suspend () -> Unit = {}): CapturedCreateReconciliationResult =
        capturedCreates.reconcileHandoffLocalCreate(target, localId, id, attempt, mutation, beforeNewOwner)

    suspend fun captureHandoffSeriesEdit(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, mutation: MutationId? = null, beforeNewOwner: suspend () -> Unit = {}): CapturedCreateReconciliationResult =
        capturedCreates.captureHandoffSeriesEdit(target, localId, id, attempt, mutation, beforeNewOwner)

    private suspend fun handoffSource(frame: CapturedCreateHandoffFrame, active: () -> Boolean): Boolean =
        capturedCreates.handoffSource(frame, active)

    private suspend fun capturedRawHold(target: CalendarScope): CapturedProjectionHold =
        projectionWork.capturedRawHold(target)

    fun permissionsChanged() {
        val previouslyGranted = state.value.hasPermission
        mutableState.value = state.value.copy(hasPermission = hasCalendarPermissions())
        scope.launch {
            try { nativeCalendars.reconcile() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Durable removal stays pending; creation has its own barrier. */ }
        }
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
            advanceCreatePolicy()
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

    fun requestSync() = incoming.requestSync()

    private fun enqueueLocal() = incoming.enqueueLocal()

    private fun recoverTransientStorage(failure: Exception, expected: Long): Boolean =
        incoming.recoverTransientStorage(failure, expected)

    fun enqueueContinuation(network: Boolean) = incoming.enqueueContinuation(network)

    /** Successful continuation and transient retry are distinct; the store owns pending work. */
    suspend fun run(network: Boolean, fresh: Boolean = true, expectedAccount: AccountId? = null): ImportRunResult =
        incoming.run(network, fresh, expectedAccount)

    private suspend fun isCurrent(expected: Long, account: AccountId) =
        authenticated && generation == expected && currentObservedAccount() == account

    /** Session restoration is separate from resolved empty selections. No network is performed. */
    internal suspend fun refreshProviderWakeState() = mutex.withLock { refreshState() }

    private suspend fun refreshState() {
        val expected = generation
        val account = if (authenticated) currentObservedAccount() else null
        val selections = if (account == null) emptyList() else store.selections(account)
        if (generation == expected) mutableState.value = state.value.copy(selections = selections,
            authenticated = authenticated, hasPermission = hasCalendarPermissions(), selectionsLoaded = true)
    }
}
