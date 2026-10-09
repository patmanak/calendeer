// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import android.webkit.WebView
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationFlowPort
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationSecret
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationStep
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogFailure
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogPort
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The only public entry point: construction is cheap; storage/crypto work runs on IO. */
object ProtonAuthentication {
    val verification = HumanVerificationHost()
    @Volatile private var instance: SerializedAuthenticationPort? = null

    fun create(context: Context): AuthenticationFlowPort = shared(context)
    fun catalog(context: Context): CalendarCatalogPort = shared(context)
    fun events(context: Context): CalendarDeltaRemote = shared(context)
    fun createPreparations(context: Context): CalendarCreatePreparationPort = shared(context)
    fun createSubmissions(context: Context): CalendarCreateSubmissionPort = shared(context)
    fun localEditPreparations(context: Context): CalendarLocalEditPreparationPort = shared(context)
    fun titleUpdates(context: Context): CalendarTitleUpdateSubmissionPort = shared(context)
    internal suspend fun matchesIdentityHash(context: Context, account: AccountId, expectedHash: String): Boolean =
        shared(context).matchesIdentityHash(account, expectedHash)

    private fun shared(context: Context): SerializedAuthenticationPort = instance ?: synchronized(this) {
        instance ?: SerializedAuthenticationPort(context.applicationContext, verification)
            .also { instance = it }
    }
}

/** UI metadata only. Hosted challenge URLs and solution tokens never cross this boundary. */
data class HumanVerificationState(val isRequired: Boolean = false, val generation: Long = 0)

class HumanVerificationHost internal constructor() {
    internal val delegate = GateCInteractiveHumanVerification()
    val state: StateFlow<HumanVerificationState> get() = delegate.uiState

    /** Invoke on the UI thread. False means the installed WebView cannot safely host verification. */
    fun load(webView: WebView, generation: Long, darkTheme: Boolean): Boolean =
        delegate.load(webView, generation, darkTheme)

    /** Releases this view, while allowing the pending challenge to attach to a replacement view. */
    fun release(webView: WebView) = delegate.release(webView)
    fun cancel() = delegate.cancel()
}

/** Serializes cleanup with even a slow, cancelling network call, preventing late session writes. */
internal class SerializedAuthenticationPort(
    private val createGraph: () -> ProtonAuthenticationGraph,
    private val cancelVerification: () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AuthenticationFlowPort, CalendarCatalogPort, CalendarDeltaRemote, RollingCalendarRemote, ContentMappingRemote, CalendarCreatePreparationPort, CalendarCreateSubmissionPort, CalendarLocalEditPreparationPort, CalendarTitleUpdateSubmissionPort {
    override val contentMapping = EVENT_CONTENT_MAPPING
    override val localCreateFormat = LOCAL_CREATE_PREPARATION_FORMAT
    override val knownEmptySubmissionFormat = KNOWN_EMPTY_SUBMISSION_FORMAT
    override val localTitleFormat = LOCAL_TITLE_PREPARATION_FORMAT
    override val titleUpdateFormat = TITLE_UPDATE_SUBMISSION_FORMAT
    constructor(context: Context, verification: HumanVerificationHost) : this(
        { ProtonAuthenticationRuntime.build(context, verification.delegate.hooks) },
        verification::cancel,
    )

    private val lock = Mutex()
    private var graph: ProtonAuthenticationGraph? = null
    private var authenticated = false
    override val format: AdapterFormat = EVENT_FORMAT
    override val deltaFormat: AdapterFormat = CALENDAR_DELTA_FORMAT

    override suspend fun currentAccount(): AccountId? = withContext(dispatcher) {
        lock.withLock { if (authenticated) graph?.events?.currentAccount() else null }
    }

    override suspend fun prepareCreate(draft: CalendarCreateDraft): PreparedCalendarCreate = withContext(dispatcher) {
        lock.withLock {
            if (!authenticated) createUnavailable(CreatePreparationFailure.AUTHENTICATION)
            checkNotNull(graph).preparations.prepareCreate(draft)
        }
    }
    override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: java.time.Instant): LocalEditPreparation {
        // Freeze before dispatcher switching or waiting for the logout mutex.
        val frozen = freezeLocalTitleRequest(intent, timestamp)
        return withContext(dispatcher) {
            lock.withLock {
                if (!authenticated) localEditUnavailable(LocalEditPreparationFailure.AUTHENTICATION)
                checkNotNull(graph).localEdits.prepareLocalTitle(frozen, timestamp)
            }
        }
    }
    override suspend fun prepareSubmission(draft: CalendarCreateDraft): PreparedCalendarCreate = submissionOperation { it.prepareSubmission(draft) }
    override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord {
        val frozen = preparation.copy(intent = LocalEditPreparation.freezeIntent(preparation.intent))
        return titleUpdateOperation { it.prepareSubmission(id, frozen) }
    }
    override suspend fun prepareSubmission(reservation: TitleUpdatePreparationReservation,
        admit: (CalendarScope) -> Boolean): TitleUpdateAttemptRecord? {
        val frozen = reservation.frozen()
        suspend fun active(): Boolean {
            currentCoroutineContext().ensureActive()
            val allowed = frozen.format == titleUpdateFormat && admit(frozen.scope)
            currentCoroutineContext().ensureActive()
            return allowed
        }
        try {
            if (!active()) return null
            return withContext(dispatcher) {
                if (!active()) return@withContext null
                lock.withLock {
                    currentCoroutineContext().ensureActive()
                    if (!authenticated || !active()) return@withLock null
                    val runtime = graph?.titleUpdates ?: return@withLock null
                    if (runtime.titleUpdateFormat != frozen.format) return@withLock null
                    val lease = { scope: CalendarScope -> scope == frozen.scope &&
                        runtime.titleUpdateFormat == frozen.format && admit(scope) }
                    val result = runtime.prepareSubmission(frozen, lease)
                    if (!active() || !authenticated || runtime.titleUpdateFormat != frozen.format) return@withLock null
                    result?.frozen()?.takeIf { frozen.matches(it) && it.state == TitleUpdateAttemptState.PREPARED &&
                        it.protectedEvidence == null }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { currentCoroutineContext().ensureActive(); throw failure }
    }
    override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation =
        submitGuarded(record, journal, admit, null)

    override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean,
        qualifySource: suspend (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation =
        submitGuarded(record, journal, admit, qualifySource)

    private suspend fun submitGuarded(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean,
        qualifySource: (suspend (CalendarScope) -> Boolean)?): TitleUpdateSubmissionObservation {
        val frozen = record.frozen()
        var closed = false
        val lease = { scope: CalendarScope ->
            if (scope != frozen.scope || closed) false else {
                val allowed = titleUpdateFormat == frozen.format && admit(scope)
                if (!allowed) closed = true
                allowed
            }
        }
        suspend fun active(): Boolean {
            currentCoroutineContext().ensureActive()
            val allowed = lease(frozen.scope)
            currentCoroutineContext().ensureActive()
            return allowed
        }
        return try {
            currentCoroutineContext().ensureActive()
            if (frozen.state != TitleUpdateAttemptState.PREPARED || frozen.protectedEvidence != null || !active())
                return TitleUpdateSubmissionObservation.Unknown
            withContext(dispatcher) {
                if (!active()) return@withContext TitleUpdateSubmissionObservation.Unknown
                lock.withLock {
                    currentCoroutineContext().ensureActive()
                    if (!authenticated || !active()) return@withLock TitleUpdateSubmissionObservation.Unknown
                    val runtime = graph?.titleUpdates ?: return@withLock TitleUpdateSubmissionObservation.Unknown
                    if (runtime.titleUpdateFormat != frozen.format) return@withLock TitleUpdateSubmissionObservation.Unknown
                    val runtimeLease = { scope: CalendarScope ->
                        if (scope != frozen.scope || closed) false else {
                            val allowed = runtime.titleUpdateFormat == frozen.format && lease(scope)
                            if (!allowed) closed = true
                            allowed
                        }
                    }
                    val runtimeSource: suspend (CalendarScope) -> Boolean = { scope ->
                        currentCoroutineContext().ensureActive()
                        val allowed = runtimeLease(scope)
                        currentCoroutineContext().ensureActive()
                        if (!allowed) false else {
                            val qualified = try { checkNotNull(qualifySource)(scope) }
                            catch (failure: Exception) { closed = true; currentCoroutineContext().ensureActive(); throw failure }
                            if (!qualified) closed = true
                            currentCoroutineContext().ensureActive()
                            val stillAllowed = runtimeLease(scope)
                            currentCoroutineContext().ensureActive()
                            qualified && stillAllowed
                        }
                    }
                    if (qualifySource != null && !runtimeSource(frozen.scope))
                        return@withLock TitleUpdateSubmissionObservation.Unknown
                    val result = if (qualifySource == null) runtime.submit(frozen, journal, runtimeLease)
                        else runtime.submit(frozen, journal, runtimeLease, runtimeSource)
                    currentCoroutineContext().ensureActive()
                    if (qualifySource != null && !runtimeSource(frozen.scope))
                        return@withLock TitleUpdateSubmissionObservation.Unknown
                    if (!active() || !authenticated || runtime.titleUpdateFormat != frozen.format)
                        return@withLock TitleUpdateSubmissionObservation.Unknown
                    if (result is TitleUpdateSubmissionObservation.Verified &&
                        result.attempt == TitleUpdateAttemptIdentity.of(frozen) &&
                        result.event.eventId == frozen.preparation.intent.base.eventId &&
                        result.event.content == frozen.preparation.intent.content) result
                    else TitleUpdateSubmissionObservation.Unknown
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { currentCoroutineContext().ensureActive(); TitleUpdateSubmissionObservation.Unknown }
    }

    override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation {
        val frozen = record.frozen()
        return titleUpdateOperation { it.submit(frozen, journal) }
    }
    override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation {
        val frozen = record.frozen()
        return titleUpdateOperation { it.inspect(frozen) }
    }
    override suspend fun inspect(record: TitleUpdateAttemptRecord,
        admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
        // Capture immutable input before dispatching or waiting for authentication cleanup.
        val frozen = record.frozen()
        return try {
            currentCoroutineContext().ensureActive()
            if (!admit(frozen.scope)) return TitleUpdateSubmissionObservation.Unknown
            withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                if (!admit(frozen.scope)) return@withContext TitleUpdateSubmissionObservation.Unknown
                lock.withLock {
                    currentCoroutineContext().ensureActive()
                    if (!authenticated || !admit(frozen.scope)) return@withLock TitleUpdateSubmissionObservation.Unknown
                    val runtime = graph?.titleUpdates ?: return@withLock TitleUpdateSubmissionObservation.Unknown
                    val result = runtime.inspect(frozen, admit)
                    currentCoroutineContext().ensureActive()
                    if (!authenticated || !admit(frozen.scope)) TitleUpdateSubmissionObservation.Unknown else result
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { currentCoroutineContext().ensureActive(); TitleUpdateSubmissionObservation.Unknown }
    }

    private suspend fun <T> titleUpdateOperation(block: suspend (CalendarTitleUpdateSubmissionPort) -> T): T = withContext(dispatcher) {
        lock.withLock {
            if (!authenticated) localEditUnavailable(LocalEditPreparationFailure.AUTHENTICATION)
            block(checkNotNull(graph).titleUpdates ?: localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED))
        }
    }
    internal suspend fun matchesIdentityHash(account: AccountId, expectedHash: String): Boolean = withContext(dispatcher) {
        lock.withLock { authenticated && graph?.matchesIdentityHash?.invoke(account, expectedHash) == true }
    }
    override suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal): CreateSubmissionObservation =
        submissionOperation { it.submit(record, journal) }
    override suspend fun inspect(record: CreateAttemptRecord): CreateSubmissionObservation = submissionOperation { it.inspect(record) }
    override suspend fun submitCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft,
        journal: CreateAttemptJournal): CreateSubmissionObservation = submissionOperation { it.submitCaptured(record, expected, journal) }
    override suspend fun inspectCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft): CreateSubmissionObservation =
        submissionOperation { it.inspectCaptured(record, expected) }
    private suspend fun <T> submissionOperation(block: suspend (CalendarCreateSubmissionPort) -> T): T = withContext(dispatcher) {
        lock.withLock {
            if (!authenticated) createUnavailable(CreatePreparationFailure.AUTHENTICATION)
            block(checkNotNull(graph).submissions ?: createUnavailable(CreatePreparationFailure.UNSUPPORTED))
        }
    }
    override suspend fun fetch(request: PageRequest): RemotePage = withContext(dispatcher) {
        lock.withLock {
            if (!authenticated) throw RemoteImportException(RemoteImportFailure.AUTHENTICATION)
            checkNotNull(graph).events.fetch(request)
        }
    }
    override suspend fun receive(request: PageRequest): ReceivedCalendarPage = eventOperation { it.receive(request) }
    override suspend fun queryHorizon(scope: CalendarScope): CoverageInterval =
        eventOperation { (it as? RollingCalendarRemote ?: unsupportedEvents()).queryHorizon(scope) }
    override suspend fun planWindows(scope: CalendarScope, windows: List<CoverageInterval>, limit: Int): PageCheckpoint =
        eventOperation { (it as? RollingCalendarRemote ?: unsupportedEvents()).planWindows(scope, windows, limit) }
    override suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage = eventOperation { it.decode(received) }
    override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? =
        eventOperation { it.retryDeferred(scope, event) }
    override suspend fun latestChanges(scope: CalendarScope): OpaqueCursor = eventOperation { it.latestChanges(scope) }
    override suspend fun receiveChanges(scope: CalendarScope, after: OpaqueCursor): ReceivedChangePage =
        eventOperation { it.receiveChanges(scope, after) }
    override suspend fun decodeChanges(received: ReceivedChangePage): DecodedChangePage = eventOperation { it.decodeChanges(received) }
    override suspend fun receiveEvent(scope: CalendarScope, eventId: EventId): ReceivedCalendarEvent =
        eventOperation { it.receiveEvent(scope, eventId) }
    override suspend fun decodeEvent(received: ReceivedCalendarEvent): DecodedCalendarEvent = eventOperation { it.decodeEvent(received) }
    override suspend fun refreshContext(scope: CalendarScope) = eventOperation { it.refreshContext(scope) }

    private suspend fun <T> eventOperation(block: suspend (CalendarDeltaRemote) -> T): T = withContext(dispatcher) {
        lock.withLock {
            if (!authenticated) throw RemoteImportException(RemoteImportFailure.AUTHENTICATION)
            block(checkNotNull(graph).events)
        }
    }
    override suspend fun dispatch(mutation: Mutation): DispatchResult = DispatchResult.Rejected

    private suspend fun operate(block: suspend (AuthenticationFlowPort) -> AuthenticationStep): AuthenticationStep =
        withContext(dispatcher) {
            lock.withLock {
                val runtime = graph ?: createGraph().also { graph = it }
                block(runtime.authentication).also { step ->
                    // A transient failure cannot revoke an already established session by itself.
                    if (step !is AuthenticationStep.Failure) {
                        authenticated = step == AuthenticationStep.Authenticated
                    }
                }
            }
        }

    override suspend fun restore(): AuthenticationStep = operate { it.restore() }
    override suspend fun signIn(username: AuthenticationSecret, password: AuthenticationSecret): AuthenticationStep =
        try { operate { it.signIn(username, password) } } finally { username.close(); password.close() }
    override suspend fun submitCode(code: AuthenticationSecret): AuthenticationStep =
        try { operate { it.submitCode(code) } } finally { code.close() }
    override suspend fun unlock(password: AuthenticationSecret): AuthenticationStep =
        try { operate { it.unlock(password) } } finally { password.close() }

    override suspend fun cancel() {
        cancelVerification()
        withContext(dispatcher) {
            lock.withLock {
                authenticated = false
                graph?.authentication?.cancel()
            }
        }
    }

    override suspend fun load(): CalendarCatalogResult = withContext(dispatcher) {
        lock.withLock {
            if (!authenticated) return@withLock CalendarCatalogResult.Failure(
                CalendarCatalogFailure.AUTHENTICATION_REQUIRED,
            )
            checkNotNull(graph).catalog.load()
        }
    }
}
