// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import android.webkit.WebView
import java.util.concurrent.atomic.AtomicBoolean
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
    fun localTextPreparations(context: Context): CalendarLocalTextPreparationPort = shared(context)
    fun personalPreparations(context: Context): CalendarPersonalPreparationPort = shared(context)
    fun personalUpdates(context: Context): CalendarPersonalUpdatePort = shared(context)
    fun localTimePreparations(context: Context): CalendarLocalTimePreparationPort = shared(context)
    fun titleUpdates(context: Context): CalendarTitleUpdateSubmissionPort = shared(context)
    fun textUpdates(context: Context): CalendarTextUpdateSubmissionPort = shared(context)
    fun timeUpdates(context: Context): CalendarTimeUpdateSubmissionPort = shared(context)
    /** Experimental typed deletion; no system worker or user-facing capability is implied. */
    fun deletions(context: Context): CalendarDeleteSubmissionPort = shared(context)
    /** Local display metadata only; unavailable or signed-out accounts return null. */
    suspend fun connectedAccountLabel(context: Context): String? = shared(context).connectedAccountLabel()
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
) : AuthenticationFlowPort, CalendarCatalogPort, CalendarDeltaRemote, RollingCalendarRemote, ContentMappingRemote, CalendarCreatePreparationPort, CalendarCreateSubmissionPort, CalendarLocalEditPreparationPort, CalendarLocalTextPreparationPort, CalendarLocalTimePreparationPort, CalendarTitleUpdateSubmissionPort, CalendarTextUpdateSubmissionPort, CalendarTimeUpdateSubmissionPort, CalendarPersonalPreparationPort, CalendarPersonalUpdatePort, CalendarDeleteSubmissionPort {
    override val contentMapping = EVENT_CONTENT_MAPPING
    override val localCreateFormat = LOCAL_CREATE_PREPARATION_FORMAT
    override val knownEmptySubmissionFormat = KNOWN_EMPTY_SUBMISSION_FORMAT
    override val personalSubmissionFormat = PERSONAL_CREATE_SUBMISSION_FORMAT
    override val personalLocalCreateFormat = PERSONAL_LOCAL_CREATE_PREPARATION_FORMAT
    override val seriesLocalCreateFormat = SERIES_LOCAL_CREATE_PREPARATION_FORMAT
    override val seriesSubmissionFormat = SERIES_CREATE_SUBMISSION_FORMAT
    override val localTitleFormat = LOCAL_TITLE_PREPARATION_FORMAT
    override val localTextFormat = LOCAL_TEXT_PREPARATION_FORMAT
    override val personalFormat = PERSONAL_PREPARATION_FORMAT
    override val seriesFormat = PERSONAL_SERIES_PREPARATION_FORMAT
    override val personalUpdateFormat = PERSONAL_UPDATE_REQUEST_FORMAT
    override val seriesUpdateFormat = PERSONAL_SERIES_UPDATE_REQUEST_FORMAT
    override val localTimeFormat = LOCAL_TIME_PREPARATION_FORMAT
    override val titleUpdateFormat = TITLE_UPDATE_SUBMISSION_FORMAT
    override val textUpdateFormat = TEXT_UPDATE_REQUEST_FORMAT
    override val timeUpdateFormat = TIME_UPDATE_REQUEST_FORMAT
    override val deleteFormat = DELETE_SUBMISSION_FORMAT
    constructor(context: Context, verification: HumanVerificationHost) : this(
        { ProtonAuthenticationRuntime.build(context, verification.delegate.hooks) },
        verification::cancel,
    )

    private val lock = Mutex()
    private val localPreparations = SerializedLocalPreparations(dispatcher, lock, { authenticated }, { graph })
    override suspend fun prepareLocalTime(intent: LocalEditIntent, timestamp: java.time.Instant): LocalTimeEditPreparation =
        localPreparations.prepareLocalTime(intent, timestamp)
    override suspend fun prepareLocalTime(intent: LocalEditIntent, timestamp: java.time.Instant,
        admit: (CalendarScope) -> Boolean): LocalTimeEditPreparation? = localPreparations.prepareLocalTime(intent, timestamp, admit)

    override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: java.time.Instant): PersonalEditPreparation =
        localPreparations.preparePersonal(intent, timestamp)
    override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: java.time.Instant,
        admit: (CalendarScope) -> Boolean): PersonalEditPreparation? = localPreparations.preparePersonal(intent, timestamp, admit)
    override suspend fun prepareSeries(intent: LocalEditIntent, timestamp: java.time.Instant,
        admit: (CalendarScope) -> Boolean): PersonalEditPreparation? = localPreparations.prepareSeries(intent, timestamp, admit)

    private var graph: ProtonAuthenticationGraph? = null
    private var authenticated = false
    override suspend fun prepareSubmission(id: MutationId, preparation: LocalTimeEditPreparation,
        admit: (CalendarScope) -> Boolean): TimeUpdateAttemptRecord? {
        currentCoroutineContext().ensureActive()
        val frozen = LocalTimeEditPreparation(freezeLocalTimeRequest(preparation.intent, preparation.timestamp),
            preparation.timestamp, preparation.format, preparation.protectedPayload)
        if (frozen.format != LOCAL_TIME_PREPARATION_FORMAT) return null
        return timeOperation(frozen.intent.scope, admit, { false }, null, preparation = true) { runtime, lease, _ ->
            val result = runtime.prepareSubmission(id, frozen, lease::allows)
            currentCoroutineContext().ensureActive()
            result?.takeIf { it.id == id && it.format == timeUpdateFormat && it.state == TimeUpdateAttemptState.PREPARED &&
                it.protectedEvidence == null && it.preparation.intent == frozen.intent && it.preparation.timestamp == frozen.timestamp &&
                it.preparation.format == frozen.format && it.preparation.protectedPayload == frozen.protectedPayload }?.frozen()
        }
    }

    override suspend fun submit(record: TimeUpdateAttemptRecord, journal: TimeUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean): TimeUpdateSubmissionObservation {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        if (frozen.format != timeUpdateFormat || frozen.state != TimeUpdateAttemptState.PREPARED || frozen.protectedEvidence != null)
            return TimeUpdateSubmissionObservation.Unknown
        return timeOperation(frozen.scope, admit, admitEvidence, TimeUpdateSubmissionObservation.Unknown as TimeUpdateSubmissionObservation) {
                runtime, lease, evidence ->
            val source: suspend (CalendarScope) -> Boolean = { requested ->
                if (!lease.allows(requested)) false else {
                    val qualified = try { qualifySource(requested) }
                    catch (failure: Exception) { lease.close(); currentCoroutineContext().ensureActive(); throw failure }
                    if (!qualified) lease.close()
                    currentCoroutineContext().ensureActive()
                    qualified && lease.allows(requested)
                }
            }
            val observed = runtime.submit(frozen, journal, lease::allows, source, evidence::allows)
            currentCoroutineContext().ensureActive()
            if (observed is TimeUpdateSubmissionObservation.Verified &&
                observed.record.copy(state = TimeUpdateAttemptState.PREPARED, protectedEvidence = null) == frozen)
                TimeUpdateSubmissionObservation.Verified(observed.record, observed.event)
            else TimeUpdateSubmissionObservation.Unknown
        }
    }

    override suspend fun inspect(record: TimeUpdateAttemptRecord, journal: TimeUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean): TimeUpdateSubmissionObservation {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        if (frozen.format != timeUpdateFormat || frozen.state != TimeUpdateAttemptState.OUTCOME_UNKNOWN)
            return TimeUpdateSubmissionObservation.Unknown
        return timeOperation(frozen.scope, admit, { false }, TimeUpdateSubmissionObservation.Unknown as TimeUpdateSubmissionObservation) {
                runtime, lease, _ ->
            val observed = runtime.inspect(frozen, journal, lease::allows)
            currentCoroutineContext().ensureActive()
            if (observed is TimeUpdateSubmissionObservation.Verified && observed.record == frozen)
                TimeUpdateSubmissionObservation.Verified(observed.record, observed.event)
            else TimeUpdateSubmissionObservation.Unknown
        }
    }

    override suspend fun prepareSubmission(id: MutationId, preparation: PersonalEditPreparation,
        admit: (CalendarScope) -> Boolean): PersonalUpdateAttemptRecord? {
        currentCoroutineContext().ensureActive()
        val frozen = preparation.frozen()
        if (frozen.isSeries) freezeSeriesRequest(frozen.intent, frozen.timestamp) else freezePersonalRequest(frozen.intent, frozen.timestamp)
        if (frozen.format != if (frozen.isSeries) PERSONAL_SERIES_PREPARATION_FORMAT else PERSONAL_PREPARATION_FORMAT) return null
        val expectedFormat = if (frozen.isSeries) seriesUpdateFormat else personalUpdateFormat
        return personalOperation(frozen.intent.scope, admit, { false }, null, preparation = true) { runtime, lease, _ ->
            if (frozen.isSeries && runtime.seriesUpdateFormat != expectedFormat) return@personalOperation null
            val result = runtime.prepareSubmission(id, frozen, lease::allows)
            currentCoroutineContext().ensureActive()
            result?.takeIf { it.id == id && it.format == expectedFormat && it.preparation.isSeries == frozen.isSeries && it.state == PersonalUpdateAttemptState.PREPARED &&
                it.protectedEvidence == null && it.preparation.intent == frozen.intent && it.preparation.timestamp == frozen.timestamp &&
                it.preparation.format == frozen.format && it.preparation.protectedPayload == frozen.protectedPayload }?.frozen()
        }
    }

    override suspend fun submit(record: PersonalUpdateAttemptRecord, journal: PersonalUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean): PersonalUpdateSubmissionObservation {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        val expectedFormat = if (frozen.preparation.isSeries) seriesUpdateFormat else personalUpdateFormat
        if (frozen.format != expectedFormat || frozen.state != PersonalUpdateAttemptState.PREPARED || frozen.protectedEvidence != null)
            return PersonalUpdateSubmissionObservation.Unknown
        return personalOperation(frozen.scope, admit, admitEvidence, PersonalUpdateSubmissionObservation.Unknown as PersonalUpdateSubmissionObservation) {
                runtime, lease, evidence ->
            if (frozen.preparation.isSeries && runtime.seriesUpdateFormat != expectedFormat)
                return@personalOperation PersonalUpdateSubmissionObservation.Unknown
            val source: suspend (CalendarScope) -> Boolean = { requested ->
                if (!lease.allows(requested)) false else {
                    val qualified = try { qualifySource(requested) }
                    catch (failure: Exception) { lease.close(); currentCoroutineContext().ensureActive(); throw failure }
                    if (!qualified) lease.close()
                    currentCoroutineContext().ensureActive()
                    qualified && lease.allows(requested)
                }
            }
            val observed = runtime.submit(frozen, journal, lease::allows, source, evidence::allows)
            currentCoroutineContext().ensureActive()
            if (observed is PersonalUpdateSubmissionObservation.Verified &&
                observed.record.copy(state = PersonalUpdateAttemptState.PREPARED, protectedEvidence = null) == frozen)
                PersonalUpdateSubmissionObservation.Verified(observed.record, observed.event)
            else PersonalUpdateSubmissionObservation.Unknown
        }
    }

    override suspend fun inspect(record: PersonalUpdateAttemptRecord, journal: PersonalUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean): PersonalUpdateSubmissionObservation {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        val retainedFormat = if (frozen.preparation.isSeries) PERSONAL_SERIES_UPDATE_REQUEST_FORMAT else personalUpdateFormat
        if (frozen.format != retainedFormat || frozen.state != PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
            return PersonalUpdateSubmissionObservation.Unknown
        return personalOperation(frozen.scope, admit, { false }, PersonalUpdateSubmissionObservation.Unknown as PersonalUpdateSubmissionObservation) {
                runtime, lease, _ ->
            val observed = runtime.inspect(frozen, journal, lease::allows)
            currentCoroutineContext().ensureActive()
            if (observed is PersonalUpdateSubmissionObservation.Verified && observed.record == frozen)
                PersonalUpdateSubmissionObservation.Verified(observed.record, observed.event)
            else PersonalUpdateSubmissionObservation.Unknown
        }
    }

    override suspend fun prepareSubmission(id: MutationId, intent: LocalDeleteIntent,
        admit: (CalendarScope) -> Boolean): DeleteAttemptRecord? {
        currentCoroutineContext().ensureActive()
        val frozen = DeleteSubmissionPolicy.freezeIntent(intent)
        return deleteOperation(frozen.scope, admit, { false }, null) { runtime, lease, _ ->
            val result = runtime.prepareSubmission(id, frozen, lease::allows)
            currentCoroutineContext().ensureActive()
            result?.takeIf { it.id == id && it.intent == frozen && it.format == deleteFormat &&
                it.state == DeleteAttemptState.PREPARED && it.protectedEvidence == null }?.frozen()
        }
    }

    override suspend fun submit(record: DeleteAttemptRecord, journal: DeleteAttemptJournal,
        admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean): DeleteSubmissionObservation {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        if (frozen.format != deleteFormat || frozen.state != DeleteAttemptState.PREPARED || frozen.protectedEvidence != null)
            return DeleteSubmissionObservation.Unknown
        return deleteOperation(frozen.scope, admit, admitEvidence, DeleteSubmissionObservation.Unknown as DeleteSubmissionObservation) {
                runtime, lease, evidence ->
            val source: suspend (CalendarScope) -> Boolean = { requested ->
                if (!lease.allows(requested)) false else {
                    val qualified = try { qualifySource(requested) }
                    catch (failure: Exception) { lease.close(); currentCoroutineContext().ensureActive(); throw failure }
                    if (!qualified) lease.close()
                    currentCoroutineContext().ensureActive()
                    qualified && lease.allows(requested)
                }
            }
            val observed = runtime.submit(frozen, journal, lease::allows, source, evidence::allows)
            currentCoroutineContext().ensureActive()
            when (observed) {
                DeleteSubmissionObservation.Unknown -> observed
                is DeleteSubmissionObservation.Verified ->
                    if (observed.record.copy(state = DeleteAttemptState.PREPARED, protectedEvidence = null) == frozen)
                        DeleteSubmissionObservation.Verified(observed.record) else DeleteSubmissionObservation.Unknown
                is DeleteSubmissionObservation.Conflict ->
                    if (observed.record.copy(state = DeleteAttemptState.PREPARED, protectedEvidence = null) == frozen)
                        DeleteSubmissionObservation.Conflict(observed.record, observed.event) else DeleteSubmissionObservation.Unknown
            }
        }
    }

    override suspend fun inspect(record: DeleteAttemptRecord, journal: DeleteAttemptJournal,
        admit: (CalendarScope) -> Boolean): DeleteSubmissionObservation {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        if (frozen.format != deleteFormat || frozen.state != DeleteAttemptState.OUTCOME_UNKNOWN)
            return DeleteSubmissionObservation.Unknown
        return deleteOperation(frozen.scope, admit, { false }, DeleteSubmissionObservation.Unknown as DeleteSubmissionObservation) {
                runtime, lease, _ ->
            val observed = runtime.inspect(frozen, journal, lease::allows)
            currentCoroutineContext().ensureActive()
            when (observed) {
                DeleteSubmissionObservation.Unknown -> observed
                is DeleteSubmissionObservation.Verified -> if (observed.record == frozen)
                    DeleteSubmissionObservation.Verified(observed.record) else DeleteSubmissionObservation.Unknown
                is DeleteSubmissionObservation.Conflict -> if (observed.record == frozen)
                    DeleteSubmissionObservation.Conflict(observed.record, observed.event) else DeleteSubmissionObservation.Unknown
            }
        }
    }

    private suspend fun <T> deleteOperation(scope: CalendarScope, admit: (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean, denied: T,
        action: suspend (CalendarDeleteSubmissionPort, UpdateLease, UpdateLease) -> T): T =
        updateOperation(scope, admit, admitEvidence, denied, false,
            { it.deletions }, { it.deleteFormat == deleteFormat }, action)

    internal suspend fun connectedAccountLabel(): String? {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        return try {
            val label = withContext(dispatcher) {
                lock.withLock {
                    currentCoroutineContext().ensureActive()
                    if (!authenticated) null else {
                        val value = try {
                            graph?.connectedAccountLabel?.invoke()
                        } catch (failure: Exception) {
                            currentCoroutineContext().ensureActive()
                            throw failure
                        }
                        currentCoroutineContext().ensureActive()
                        safeConnectedAccountLabel(value)
                    }
                }
            }
            caller.ensureActive()
            label
        } catch (failure: Exception) {
            caller.ensureActive()
            if (failure is CancellationException) throw failure
            null
        }
    }
    override val format: AdapterFormat = EVENT_FORMAT
    override val deltaFormat: AdapterFormat = CALENDAR_DELTA_FORMAT

    override suspend fun currentAccount(): AccountId? = withContext(dispatcher) {
        lock.withLock { if (authenticated) graph?.events?.currentAccount() else null }
    }

    override suspend fun prepareSubmission(id: MutationId, preparation: LocalTextEditPreparation,
        admit: (CalendarScope) -> Boolean): TextUpdateAttemptRecord? {
        currentCoroutineContext().ensureActive()
        val frozen = LocalTextEditPreparation(freezeLocalTextRequest(preparation.intent, preparation.timestamp),
            preparation.timestamp, preparation.format, preparation.protectedPayload)
        if (frozen.format != LOCAL_TEXT_PREPARATION_FORMAT) return null
        return textOperation(frozen.intent.scope, admit, { false }, null, preparation = true) { runtime, lease, _ ->
            val result = runtime.prepareSubmission(id, frozen, lease::allows)
            currentCoroutineContext().ensureActive()
            result?.takeIf { it.id == id && it.format == textUpdateFormat && it.state == TextUpdateAttemptState.PREPARED &&
                it.protectedEvidence == null && it.preparation.intent == frozen.intent && it.preparation.timestamp == frozen.timestamp &&
                it.preparation.format == frozen.format && it.preparation.protectedPayload == frozen.protectedPayload }?.frozen()
        }
    }

    override suspend fun submit(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        if (frozen.format != textUpdateFormat || frozen.state != TextUpdateAttemptState.PREPARED || frozen.protectedEvidence != null)
            return TextUpdateSubmissionObservation.Unknown
        return textOperation(frozen.scope, admit, admitEvidence, TextUpdateSubmissionObservation.Unknown as TextUpdateSubmissionObservation) {
                runtime, lease, evidence ->
            val source: suspend (CalendarScope) -> Boolean = { requested ->
                if (!lease.allows(requested)) false else {
                    val qualified = try { qualifySource(requested) }
                    catch (failure: Exception) { lease.close(); currentCoroutineContext().ensureActive(); throw failure }
                    if (!qualified) lease.close()
                    currentCoroutineContext().ensureActive()
                    qualified && lease.allows(requested)
                }
            }
            val observed = runtime.submit(frozen, journal, lease::allows, source, evidence::allows)
            currentCoroutineContext().ensureActive()
            if (observed is TextUpdateSubmissionObservation.Verified &&
                observed.record.copy(state = TextUpdateAttemptState.PREPARED, protectedEvidence = null) == frozen)
                TextUpdateSubmissionObservation.Verified(observed.record, observed.event)
            else TextUpdateSubmissionObservation.Unknown
        }
    }

    override suspend fun inspect(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        if (frozen.format != textUpdateFormat || frozen.state != TextUpdateAttemptState.OUTCOME_UNKNOWN)
            return TextUpdateSubmissionObservation.Unknown
        return textOperation(frozen.scope, admit, { false }, TextUpdateSubmissionObservation.Unknown as TextUpdateSubmissionObservation) {
                runtime, lease, _ ->
            val observed = runtime.inspect(frozen, journal, lease::allows)
            currentCoroutineContext().ensureActive()
            if (observed is TextUpdateSubmissionObservation.Verified && observed.record == frozen)
                TextUpdateSubmissionObservation.Verified(observed.record, observed.event)
            else TextUpdateSubmissionObservation.Unknown
        }
    }

    /** Shared logout serialization, but separate expiring edit and response-retention permissions. */
    private suspend fun <T> textOperation(scope: CalendarScope, admit: (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean, denied: T, preparation: Boolean = false,
        action: suspend (CalendarTextUpdateSubmissionPort, UpdateLease, UpdateLease) -> T): T =
        updateOperation(scope, admit, admitEvidence, denied, preparation,
            { it.textUpdates }, { it.textUpdateFormat == textUpdateFormat }, action)

    private suspend fun <T> timeOperation(scope: CalendarScope, admit: (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean, denied: T, preparation: Boolean = false,
        action: suspend (CalendarTimeUpdateSubmissionPort, UpdateLease, UpdateLease) -> T): T =
        updateOperation(scope, admit, admitEvidence, denied, preparation,
            { it.timeUpdates }, { it.timeUpdateFormat == timeUpdateFormat }, action)

    private suspend fun <T> personalOperation(scope: CalendarScope, admit: (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean, denied: T, preparation: Boolean = false,
        action: suspend (CalendarPersonalUpdatePort, UpdateLease, UpdateLease) -> T): T =
        updateOperation(scope, admit, admitEvidence, denied, preparation,
            { it.personalUpdates }, { it.personalUpdateFormat == personalUpdateFormat }, action)

    /** Common mutex/lease mechanics only; family-specific ports and records stay distinct. */
    private suspend fun <T, P> updateOperation(scope: CalendarScope, admit: (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean, denied: T, preparation: Boolean,
        select: (ProtonAuthenticationGraph) -> P?, compatible: (P) -> Boolean,
        action: suspend (P, UpdateLease, UpdateLease) -> T): T {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val lease = UpdateLease(scope, admit, caller)
        val evidence = UpdateLease(scope, admitEvidence, caller)
        try {
            if (!lease.allows(scope)) return denied
            return withContext(dispatcher) {
                val operation = currentCoroutineContext()
                lease.bind(operation); evidence.bind(operation)
                try {
                    lock.withLock {
                        operation.ensureActive()
                        if (!lease.allows(scope) || !authenticated) return@withLock denied
                        val runtime = graph?.let(select) ?: return@withLock denied
                        if (!compatible(runtime)) return@withLock denied
                        lease.qualify { authenticated && compatible(runtime) }
                        evidence.qualify { authenticated && compatible(runtime) }
                        val result = action(runtime, lease, evidence)
                        operation.ensureActive()
                        if (!lease.allows(scope) || !authenticated || !compatible(runtime)) denied else result
                    }
                } catch (failure: Exception) {
                    // A cancelled dispatcher child can throw an ordinary error while the
                    // caller is still active. Check that child before leaving its context.
                    operation.ensureActive()
                    throw failure
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) {
              caller.ensureActive()
              if (failure is OutgoingReadUnavailableException) throw failure
              if (preparation && failure is LocalEditPreparationException) throw failure
              return denied
          } finally { lease.close(); evidence.close() }
    }

    private class UpdateLease(private val scope: CalendarScope, private val admit: (CalendarScope) -> Boolean,
        private val caller: kotlin.coroutines.CoroutineContext) {
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        private val operation = java.util.concurrent.atomic.AtomicReference(caller)
        private var qualification: () -> Boolean = { true }
        fun bind(context: kotlin.coroutines.CoroutineContext) { operation.set(context) }
        fun qualify(check: () -> Boolean) { qualification = check }
        fun allows(requested: CalendarScope): Boolean {
            if (closed.get() || requested != scope) return false
            caller.ensureActive(); operation.get().ensureActive()
            val allowed = try { qualification() && admit(requested) }
            catch (failure: Exception) { closed.set(true); caller.ensureActive(); operation.get().ensureActive(); throw failure }
            if (!allowed) closed.set(true)
            caller.ensureActive(); operation.get().ensureActive()
            return allowed && !closed.get()
        }
        fun close() { closed.set(true) }
    }

    override suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: java.time.Instant): LocalTextEditPreparation =
        localPreparations.prepareLocalText(intent, timestamp)
    override suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: java.time.Instant,
        admit: (CalendarScope) -> Boolean): LocalTextEditPreparation? = localPreparations.prepareLocalText(intent, timestamp, admit)

    override suspend fun prepareCreate(draft: CalendarCreateDraft): PreparedCalendarCreate {
        val frozen = draft.frozen()
        return withContext(dispatcher) { lock.withLock {
            if (!authenticated) createUnavailable(CreatePreparationFailure.AUTHENTICATION)
            checkNotNull(graph).preparations.prepareCreate(frozen)
        } }
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
    override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: java.time.Instant,
        admit: (CalendarScope) -> Boolean): LocalEditPreparation? {
        currentCoroutineContext().ensureActive()
        // Capture the complete supported intent before dispatcher/lock suspension. This
        // overload never borrows the unrestricted runtime preparation capability.
        val frozen = freezeLocalTitleRequest(intent, timestamp)
        val expectedFormat = localTitleFormat
        var closed = false
        val lease = { scope: CalendarScope ->
            if (scope != frozen.scope || closed) false else {
                val allowed = localTitleFormat == expectedFormat && admit(scope)
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
            if (!active()) return null
            withContext(dispatcher) {
                if (!active()) return@withContext null
                lock.withLock {
                    currentCoroutineContext().ensureActive()
                    if (!authenticated || !active()) return@withLock null
                    val runtime = graph?.localEdits ?: return@withLock null
                    if (runtime.localTitleFormat != expectedFormat) return@withLock null
                    val runtimeLease = { scope: CalendarScope ->
                        if (scope != frozen.scope || closed) false else {
                            val allowed = authenticated && runtime.localTitleFormat == expectedFormat && lease(scope)
                            if (!allowed) closed = true
                            allowed
                        }
                    }
                    val result = runtime.prepareLocalTitle(frozen, timestamp, runtimeLease)
                    currentCoroutineContext().ensureActive()
                    if (!active() || !authenticated || runtime.localTitleFormat != expectedFormat) return@withLock null
                    result?.takeIf { it.intent == frozen && it.timestamp == timestamp && it.format == expectedFormat }
                        ?.copy(intent = frozen)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { currentCoroutineContext().ensureActive(); throw failure }
          finally { closed = true }
    }
    override suspend fun prepareSubmission(draft: CalendarCreateDraft): PreparedCalendarCreate {
        val frozen = draft.frozen()
        return submissionOperation { it.prepareSubmission(frozen) }
    }
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
        journal: CreateAttemptJournal): CreateSubmissionObservation {
        val frozen = expected.frozen()
        return submissionOperation { it.submitCaptured(record, frozen, journal) }
    }
    override suspend fun inspectCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft): CreateSubmissionObservation {
        val frozen = expected.frozen()
        return submissionOperation { it.inspectCaptured(record, frozen) }
    }
    override suspend fun readCapturedExpectation(record: CreateAttemptRecord, expected: CalendarCreateDraft): EventContent? {
        val frozen = expected.frozen()
        return submissionOperation { it.readCapturedExpectation(record, frozen) }
    }
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
