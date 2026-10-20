package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.Context
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionFailure
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreatePauseReason
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemTextLocalOwner
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemTextEditProfile
import com.patmanak.proton.andcalendarbridge.data.outgoing.TextLocalOwnerSnapshot
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemDeleteLocalOwner
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemTimeLocalOwner
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemTimeEditProfile
import com.patmanak.proton.andcalendarbridge.data.outgoing.TimeLocalOwnerSnapshot
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ImportUiState(val selections: List<ImportStore.Selection> = emptyList(),
    val hasPermission: Boolean = false, val authenticated: Boolean = false, val busy: Boolean = false)
enum class CreateAssociationResult { INACTIVE, UNRESOLVED, WAITING_IMPORT, WAITING_PROVIDER, ASSOCIATED }
enum class LocalEditCaptureResult { INACTIVE, UNQUALIFIED, RETAINED, CONFLICT, UNAVAILABLE }
sealed interface RetainedLocalChangeListing {
    data class Available(val page: RetainedLocalChangePage) : RetainedLocalChangeListing {
        override fun toString() = "RetainedLocalChangeListing.Available(REDACTED)"
    }
    data object Inactive : RetainedLocalChangeListing
    data object Unqualified : RetainedLocalChangeListing
    data object Unavailable : RetainedLocalChangeListing
}
sealed interface LocalEditInspection {
    data class Available(val review: LocalEditReview) : LocalEditInspection {
        override fun toString() = "LocalEditInspection.Available(REDACTED)"
    }
    data object Inactive : LocalEditInspection
    data object Unqualified : LocalEditInspection
    data object Unavailable : LocalEditInspection
}
enum class LocalTitlePreparationResult { INACTIVE, UNQUALIFIED, RETAINED, HISTORICAL, CONFLICT, UNSUPPORTED, UNAVAILABLE }
sealed interface LocalDeleteInspection {
    data class Available(val review: LocalDeleteReview) : LocalDeleteInspection {
        override fun toString() = "LocalDeleteInspection.Available(REDACTED)"
    }
    data object Inactive : LocalDeleteInspection
    data object Unqualified : LocalDeleteInspection
    data object Unavailable : LocalDeleteInspection
}
enum class LocalCreatePreparationResult { INACTIVE, UNQUALIFIED, RETAINED, UNSUPPORTED, UNAVAILABLE }
enum class LocalCreateSubmissionResult { INACTIVE, UNQUALIFIED, RETAINED, UNRESOLVED, UNSUPPORTED, UNAVAILABLE }
enum class CapturedCreateBindingResult { INACTIVE, UNRESOLVED, RETAINED }
enum class CapturedCreateAttachmentResult { INACTIVE, UNRESOLVED, ATTACHED }
enum class CapturedCreateBaselineResult { INACTIVE, UNRESOLVED, RETAINED }
enum class CapturedCreateReconciliationResult { INACTIVE, UNRESOLVED, UNSUPPORTED, UNCHANGED, RETAINED, CONFLICT }
enum class CapturedCreateAcknowledgementResult { INACTIVE, UNRESOLVED, UNSUPPORTED, ACKNOWLEDGED }
enum class CapturedCreateCleanResult { INACTIVE, UNRESOLVED, CLEAN }
enum class CapturedCreateHandoffResult { INACTIVE, UNRESOLVED, WAITING, HANDED_OFF }
enum class TitleUpdateAcknowledgementResult { INACTIVE, UNRESOLVED, ACKNOWLEDGED }
enum class TitleUpdateCompletionResult { INACTIVE, UNRESOLVED, COMPLETED }
enum class LocalTitleAttemptRetentionResult { INACTIVE, UNQUALIFIED, UNSUPPORTED, UNRESOLVED, RETAINED, HISTORICAL, CONFLICT, UNAVAILABLE }
sealed interface LocalTitleAttemptPreparation {
    data class Prepared(val record: TitleUpdateAttemptRecord) : LocalTitleAttemptPreparation {
        override fun toString() = "LocalTitleAttemptPreparation.Prepared(REDACTED)"
    }
    data object Inactive : LocalTitleAttemptPreparation
    data object Unqualified : LocalTitleAttemptPreparation
    data object Unsupported : LocalTitleAttemptPreparation
    data object Unresolved : LocalTitleAttemptPreparation
    data object Conflict : LocalTitleAttemptPreparation
    data object Unavailable : LocalTitleAttemptPreparation
}
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
    /** Inspection never renews server rights or grants mutation/acknowledgement authority. */
    val conflictArchiveReader: ConflictArchiveReader = object : ConflictArchiveReader {
        override suspend fun list(query: ConflictArchiveQuery): ConflictArchiveRead<ConflictArchivePage> =
            readConflictArchive(query.scope) { store.conflictArchives(query) }
        override suspend fun detail(ref: ConflictArchiveRef): ConflictArchiveRead<ConflictArchiveDetail> =
            readConflictArchive(ref.scope) { store.conflictArchive(ref) }
        override suspend fun observations(query: ConflictObservationQuery): ConflictArchiveRead<ConflictObservationPage> =
            readConflictArchive(query.ref.scope) {
                if (store.conflictArchive(query.ref) == null) null else store.conflictObservations(query)
            }
    }
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

    private val editAdmission = SystemEditAdmission(
        generation = { generation }, policyRevision = { createPolicyRevision.get() },
        currentAccount = { createAccount.get() }, authenticated = { authenticated },
        hasPermissions = ::hasCalendarPermissions, createsEnabled = systemCreatesEnabled,
        selected = { target -> state.value.selections.any { it.scope == target && it.enabled && it.available } },
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
        freshCapturedSubmissions.entries.removeIf { it.key.first == target }
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

    /** Local-only gateway: callbacks can run while the Proton session mutex is already held. */
    internal val localTextOwners: SystemTextLocalOwner = object : SystemTextLocalOwner {
        override suspend fun observe(scope: CalendarScope, event: EventId, owner: MutationId,
            admit: (CalendarScope) -> Boolean): TextLocalOwnerSnapshot? =
            withScopedEditOwner(scope, admit, consent = true, sendingEnabled = systemTextsEnabled, denied = null) { access ->
                val intent = access.read { store.localEdit(scope, event) } ?: return@withScopedEditOwner null
                if (intent.id != owner || !SystemTextEditProfile.accepts(intent)) return@withScopedEditOwner null
                val frozen = LocalTextEditPreparation.freezeIntent(intent)
                val preparation = access.read { store.localTextEditPreparation(scope, event) }
                if (!textSource(frozen, preparation, access)) return@withScopedEditOwner null
                TextLocalOwnerSnapshot(frozen, preparation)
            }

        override suspend fun retain(preparation: LocalTextEditPreparation,
            admit: (CalendarScope) -> Boolean): LocalTextEditPreparation? {
            currentCoroutineContext().ensureActive()
            val frozen = freezeTextPreparation(preparation)
            return withScopedEditOwner(frozen.intent.scope, admit, consent = true, sendingEnabled = systemTextsEnabled, denied = null) { access ->
                val retained = access.read { store.localTextEditPreparation(frozen.intent.scope, frozen.intent.base.eventId) }
                if (retained != null && !sameTextPreparation(retained, frozen)) return@withScopedEditOwner null
                if (!textSource(frozen.intent, retained, access)) return@withScopedEditOwner null
                val outcome = access.read { store.retainCurrentLocalTextPreparation(frozen) }
                if (outcome !in listOf(LocalTextPreparationAdmission.STORED, LocalTextPreparationAdmission.HISTORICAL))
                    return@withScopedEditOwner null
                val durable = access.read { store.localTextEditPreparation(frozen.intent.scope, frozen.intent.base.eventId) }
                if (!sameTextPreparation(durable, frozen) || !textSource(frozen.intent, frozen, access))
                    return@withScopedEditOwner null
                freezeTextPreparation(requireNotNull(durable))
            }
        }

        override suspend fun qualify(preparation: LocalTextEditPreparation, admit: (CalendarScope) -> Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            val frozen = freezeTextPreparation(preparation)
            return withScopedEditOwner(frozen.intent.scope, admit, consent = true, sendingEnabled = systemTextsEnabled, denied = false) { access ->
                textSource(frozen.intent, frozen, access)
            }
        }

        override suspend fun submit(record: TextUpdateAttemptRecord, journal: TextUpdateRecoveryJournal,
            port: CalendarTextUpdateSubmissionPort, admit: (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation {
            val operation = currentCoroutineContext()
            operation.ensureActive()
            val frozen = record.frozen()
            val target = frozen.scope
            val accountEvidence = systemTextEvidenceAdmission(target)
            val open = AtomicBoolean(true)
            val sourceClosed = AtomicBoolean(false)
            val evidenceClosed = AtomicBoolean(false)
            val evidence: (CalendarScope) -> Boolean = { requested ->
                operation.ensureActive()
                val allowed = open.get() && !evidenceClosed.get() && requested == target &&
                    accountEvidence(requested) && admitEvidence(requested)
                operation.ensureActive()
                if (!allowed) evidenceClosed.set(true)
                allowed && !evidenceClosed.get() && open.get()
            }
            try {
                if (frozen.state != TextUpdateAttemptState.PREPARED || frozen.protectedEvidence != null ||
                    port.textUpdateFormat != frozen.format ||
                    frozen.id != TextUpdateAttemptIdentity.requestId(frozen.preparation, frozen.format))
                    return TextUpdateSubmissionObservation.Unknown
                return withScopedEditOwner(target, admit, consent = true, sendingEnabled = systemTextsEnabled, denied = TextUpdateSubmissionObservation.Unknown,
                    finalAccessRequired = false) { access ->
                    val sending: (CalendarScope) -> Boolean = { requested ->
                        operation.ensureActive()
                        val allowed = open.get() && !sourceClosed.get() && requested == target && access.active() &&
                            port.textUpdateFormat == frozen.format
                        operation.ensureActive()
                        if (!allowed) sourceClosed.set(true)
                        allowed && !sourceClosed.get() && open.get()
                    }
                    suspend fun source(requested: CalendarScope): Boolean {
                        operation.ensureActive()
                        return try {
                            val valid = sending(requested) && textSource(frozen.preparation.intent, frozen.preparation, access) &&
                                sending(requested)
                            if (!valid) sourceClosed.set(true)
                            valid
                        } catch (cancelled: CancellationException) { throw cancelled }
                          catch (_: Exception) { operation.ensureActive(); sourceClosed.set(true); false }
                    }
                    if (!sending(target) || access.read { journal.read(target, frozen.id, sending) } != frozen ||
                        !source(target)) return@withScopedEditOwner TextUpdateSubmissionObservation.Unknown
                    // Lock order is import then authenticated adapter. The callback never retakes the import lock.
                    val observation = port.submit(frozen, journal, sending, ::source, evidence)
                    operation.ensureActive()
                    if (observation !is TextUpdateSubmissionObservation.Verified || !evidence(target) ||
                        observation.record.copy(state = TextUpdateAttemptState.PREPARED, protectedEvidence = null) != frozen ||
                        observation.record.protectedEvidence == null) return@withScopedEditOwner TextUpdateSubmissionObservation.Unknown
                    // Response facts survive editing-policy revocation. No ACK authority is derived here.
                    val durable = journal.read(target, frozen.id, evidence)
                    operation.ensureActive()
                    if (!evidence(target) || durable == null || durable != observation.record)
                        return@withScopedEditOwner TextUpdateSubmissionObservation.Unknown
                    TextUpdateSubmissionObservation.Verified(durable, observation.event)
                }
            } finally {
                open.set(false); sourceClosed.set(true); evidenceClosed.set(true)
            }
        }

        override suspend fun acknowledge(result: TextUpdateVerifiedResult, journal: TextUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            val frozen = result.snapshot()
            return withScopedEditOwner(frozen.record.scope, admit, consent = false, sendingEnabled = systemTextsEnabled, denied = false) { access ->
                acknowledgeText(frozen, journal, access)
            }
        }

        override suspend fun complete(result: TextUpdateVerifiedResult, journal: TextUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            val frozen = result.snapshot()
            return withScopedEditOwner(frozen.record.scope, admit, consent = false, sendingEnabled = systemTextsEnabled, denied = false) { access ->
                completeText(frozen, journal, access)
            }
        }
    }

    private fun freezeTextPreparation(value: LocalTextEditPreparation) =
        LocalTextEditPreparation(value.intent, value.timestamp, value.format, value.protectedPayload)

    private fun sameTextPreparation(left: LocalTextEditPreparation?, right: LocalTextEditPreparation?): Boolean =
        if (left == null || right == null) left == null && right == null else
            left.intent == right.intent && left.timestamp == right.timestamp && left.format == right.format &&
                left.protectedPayload == right.protectedPayload

    /** Exact SQL and native source, including complete immutable origin for a handed-off CREATE. */
    private suspend fun textSource(intent: LocalEditIntent, preparation: LocalTextEditPreparation?,
        access: ScopedEditOwnerAccess): Boolean {
        val target = access.target
        val event = intent.base.eventId
        if (intent.scope != target || !SystemTextEditProfile.accepts(intent) || intent.format != remote.format) return false
        suspend fun sql(): Boolean {
            if (remote.format != intent.format) return false
            if (access.read { store.localEdit(target, event) } != intent) return false
            val eligibility = access.read { store.assessLocalTextPreparation(intent) }
            if (eligibility != if (preparation == null) LocalTextPreparationAdmission.ELIGIBLE
                else LocalTextPreparationAdmission.HISTORICAL) return false
            return sameTextPreparation(access.read { store.localTextEditPreparation(target, event) }, preparation) &&
                remote.format == intent.format
        }
        return currentEditSource(intent, access, ::sql)
    }

    private suspend fun currentTextResult(result: TextUpdateVerifiedResult, journal: TextUpdateRecoveryJournal,
        access: ScopedEditOwnerAccess): Boolean {
        val record = result.record
        if (record.scope != access.target || record.state != TextUpdateAttemptState.OUTCOME_UNKNOWN ||
            record.preparation.intent.format != remote.format ||
            access.read { store.transaction(access.target) { checkpoint?.format } } != record.preparation.intent.format)
            return false
        val durable = access.read { journal.read(access.target, record.id, access.admit) } ?: return false
        return durable.copy(protectedEvidence = null) == record &&
            access.read { journal.readVerified(access.target, record.id, access.admit) } == result &&
            access.read { store.transaction(access.target) { checkpoint?.format } } == record.preparation.intent.format &&
            remote.format == record.preparation.intent.format
    }

    private suspend fun acknowledgeText(result: TextUpdateVerifiedResult, journal: TextUpdateRecoveryJournal,
        access: ScopedEditOwnerAccess): Boolean {
        if (projection.textUpdateAcknowledgementProtocol != 1 || !currentTextResult(result, journal, access)) return false
        val target = access.target
        val attempt = result.record.id
        val intent = result.record.preparation.intent
        val event = result.event.eventId
        val existing = access.read { store.textUpdateAcknowledgement(target, event, attempt) }
        if (existing != null && !existing.receipt.matches(result)) return false
        if (existing?.state == TextUpdateAcknowledgementState.RESTORED)
            return currentTextResult(result, journal, access)
        val reservation = if (existing != null) TextUpdateAcknowledgementReservation(existing, false) else {
            val role = access.read { store.capturedHandoff(target, event) }
            if (!currentEditNativeSource(intent, role, access) || !currentTextResult(result, journal, access)) return false
            access.read { store.prepareTextUpdateAcknowledgement(result, role?.frame) } ?: return false
        }
        val retained = reservation.record
        if (!retained.receipt.matches(result) ||
            access.read { store.textUpdateAcknowledgement(target, event, attempt) } != retained ||
            !currentTextResult(result, journal, access)) return false
        if (reservation.fresh) {
            val owner = access.read { store.localEdit(target, event) }
            val echoedOwner = owner?.state == LocalEditState.CONFLICT && owner.copy(state = LocalEditState.PAUSED) == intent
            if ((owner != intent && !echoedOwner) || !sameTextPreparation(
                    access.read { store.localTextEditPreparation(target, event) }, result.record.preparation)) return false
            if (!currentEditAcknowledgementRole(intent, retained.receipt.handoff, access)) return false
        }
        val historicalFrame = retained.receipt.handoff
        if (historicalFrame != null && !access.read { handoffSource(historicalFrame, access::active) }) return false
        // A retained pending receipt only inspects. An interrupted batch is never replayed.
        val witness = access.read {
            if (reservation.fresh) projection.acknowledgeTextUpdate(retained.receipt)
            else projection.inspectTextUpdateAcknowledgement(retained.receipt)
        } ?: return false
        if (witness.receipt.snapshot() != retained.receipt || !currentTextResult(result, journal, access) ||
            access.read { store.textUpdateAcknowledgement(target, event, attempt) } != retained) return false
        if (historicalFrame != null && !access.read { handoffSource(historicalFrame, access::active) }) return false
        if (!access.read { store.restoreTextUpdateAcknowledgement(witness) }) return false
        return access.read { store.textUpdateAcknowledgement(target, event, attempt) } ==
            TextUpdateAcknowledgementRecord(retained.receipt, TextUpdateAcknowledgementState.RESTORED) &&
            currentTextResult(result, journal, access)
    }

    private suspend fun completeText(result: TextUpdateVerifiedResult, journal: TextUpdateRecoveryJournal,
        access: ScopedEditOwnerAccess): Boolean {
        if (journal.completionProtocol != 1 || !currentTextResult(result, journal, access)) return false
        val target = access.target
        val event = result.event.eventId
        val attempt = result.record.id
        val acknowledgement = access.read { store.textUpdateAcknowledgement(target, event, attempt) } ?: return false
        if (acknowledgement.state != TextUpdateAcknowledgementState.RESTORED || !acknowledgement.receipt.matches(result)) return false
        val completion = TextUpdateCompletion.of(result, acknowledgement)
        val retained = access.read { journal.readCompletion(target, attempt, access.admit) }
        if (retained != null && retained != completion) return false
        if (!currentTextResult(result, journal, access) ||
            access.read { store.textUpdateAcknowledgement(target, event, attempt) } != acknowledgement) return false
        if (retained == null && !access.read { journal.retainCompletion(completion, access.admit) }) return false
        return access.read { journal.readCompletion(target, attempt, access.admit) } == completion &&
            access.read { store.textUpdateAcknowledgement(target, event, attempt) } == acknowledgement &&
            currentTextResult(result, journal, access)
    }

    /** Capture the original worker epoch; the authenticated facade is never called by this lease. */
    fun systemTimeRunAdmission(target: CalendarScope): (CalendarScope) -> Boolean = editAdmission.captureRun(target, true, systemTimesEnabled)

    /** Historical proof recovery needs CREATE access, but never borrows time sending consent. */
    fun systemTimeProofAdmission(target: CalendarScope): (CalendarScope) -> Boolean = editAdmission.captureRun(target, false, systemTimesEnabled)

    /** Factual response retention survives editing-policy loss, never logout or account replacement.
     * The caller additionally bounds this lease to its exact invocation and UNKNOWN record.
     */
    fun systemTimeEvidenceAdmission(target: CalendarScope): (CalendarScope) -> Boolean = editAdmission.captureEvidence(target)

    /** Local-only gateway: callbacks can run while the Proton session mutex is already held. */
    internal val localTimeOwners: SystemTimeLocalOwner = object : SystemTimeLocalOwner {
        override suspend fun observe(scope: CalendarScope, event: EventId, owner: MutationId,
            admit: (CalendarScope) -> Boolean): TimeLocalOwnerSnapshot? =
            withScopedEditOwner(scope, admit, consent = true, denied = null) { access ->
                val intent = access.read { store.localEdit(scope, event) } ?: return@withScopedEditOwner null
                if (intent.id != owner || !SystemTimeEditProfile.accepts(intent)) return@withScopedEditOwner null
                val frozen = LocalTimeEditCandidate(intent).intent
                val preparation = access.read { store.localTimeEditPreparation(scope, event) }
                if (!timeSource(frozen, preparation, access)) return@withScopedEditOwner null
                TimeLocalOwnerSnapshot(frozen, preparation)
            }

        override suspend fun retain(preparation: LocalTimeEditPreparation,
            admit: (CalendarScope) -> Boolean): LocalTimeEditPreparation? {
            currentCoroutineContext().ensureActive()
            val frozen = freezeTimePreparation(preparation)
            return withScopedEditOwner(frozen.intent.scope, admit, consent = true, denied = null) { access ->
                val retained = access.read { store.localTimeEditPreparation(frozen.intent.scope, frozen.intent.base.eventId) }
                if (retained != null && !sameTimePreparation(retained, frozen)) return@withScopedEditOwner null
                if (!timeSource(frozen.intent, retained, access)) return@withScopedEditOwner null
                val outcome = access.read { store.retainCurrentLocalTimePreparation(frozen) }
                if (outcome !in listOf(LocalTimePreparationAdmission.STORED, LocalTimePreparationAdmission.HISTORICAL))
                    return@withScopedEditOwner null
                val durable = access.read { store.localTimeEditPreparation(frozen.intent.scope, frozen.intent.base.eventId) }
                if (!sameTimePreparation(durable, frozen) || !timeSource(frozen.intent, frozen, access))
                    return@withScopedEditOwner null
                freezeTimePreparation(requireNotNull(durable))
            }
        }

        override suspend fun qualify(preparation: LocalTimeEditPreparation, admit: (CalendarScope) -> Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            val frozen = freezeTimePreparation(preparation)
            return withScopedEditOwner(frozen.intent.scope, admit, consent = true, denied = false) { access ->
                timeSource(frozen.intent, frozen, access)
            }
        }

        override suspend fun submit(record: TimeUpdateAttemptRecord, journal: TimeUpdateRecoveryJournal,
            port: CalendarTimeUpdateSubmissionPort, admit: (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): TimeUpdateSubmissionObservation {
            val operation = currentCoroutineContext()
            operation.ensureActive()
            val frozen = record.frozen()
            val target = frozen.scope
            val accountEvidence = systemTimeEvidenceAdmission(target)
            val open = AtomicBoolean(true)
            val sourceClosed = AtomicBoolean(false)
            val evidenceClosed = AtomicBoolean(false)
            val evidence: (CalendarScope) -> Boolean = { requested ->
                operation.ensureActive()
                val allowed = open.get() && !evidenceClosed.get() && requested == target &&
                    accountEvidence(requested) && admitEvidence(requested)
                operation.ensureActive()
                if (!allowed) evidenceClosed.set(true)
                allowed && !evidenceClosed.get() && open.get()
            }
            try {
                if (frozen.state != TimeUpdateAttemptState.PREPARED || frozen.protectedEvidence != null ||
                    port.timeUpdateFormat != frozen.format ||
                    frozen.id != TimeUpdateAttemptIdentity.requestId(frozen.preparation, frozen.format))
                    return TimeUpdateSubmissionObservation.Unknown
                return withScopedEditOwner(target, admit, consent = true, denied = TimeUpdateSubmissionObservation.Unknown,
                    finalAccessRequired = false) { access ->
                    val sending: (CalendarScope) -> Boolean = { requested ->
                        operation.ensureActive()
                        val allowed = open.get() && !sourceClosed.get() && requested == target && access.active() &&
                            port.timeUpdateFormat == frozen.format
                        operation.ensureActive()
                        if (!allowed) sourceClosed.set(true)
                        allowed && !sourceClosed.get() && open.get()
                    }
                    suspend fun source(requested: CalendarScope): Boolean {
                        operation.ensureActive()
                        return try {
                            val valid = sending(requested) && timeSource(frozen.preparation.intent, frozen.preparation, access) &&
                                sending(requested)
                            if (!valid) sourceClosed.set(true)
                            valid
                        } catch (cancelled: CancellationException) { throw cancelled }
                          catch (_: Exception) { operation.ensureActive(); sourceClosed.set(true); false }
                    }
                    if (!sending(target) || access.read { journal.read(target, frozen.id, sending) } != frozen ||
                        !source(target)) return@withScopedEditOwner TimeUpdateSubmissionObservation.Unknown
                    // Lock order is import then authenticated adapter. The callback never retakes the import lock.
                    val observation = port.submit(frozen, journal, sending, ::source, evidence)
                    operation.ensureActive()
                    if (observation !is TimeUpdateSubmissionObservation.Verified || !evidence(target) ||
                        observation.record.copy(state = TimeUpdateAttemptState.PREPARED, protectedEvidence = null) != frozen ||
                        observation.record.protectedEvidence == null) return@withScopedEditOwner TimeUpdateSubmissionObservation.Unknown
                    // Response facts survive editing-policy revocation. No ACK authority is derived here.
                    val durable = journal.read(target, frozen.id, evidence)
                    operation.ensureActive()
                    if (!evidence(target) || durable == null || durable != observation.record)
                        return@withScopedEditOwner TimeUpdateSubmissionObservation.Unknown
                    TimeUpdateSubmissionObservation.Verified(durable, observation.event)
                }
            } finally {
                open.set(false); sourceClosed.set(true); evidenceClosed.set(true)
            }
        }

        override suspend fun acknowledge(result: TimeUpdateVerifiedResult, journal: TimeUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            val frozen = result.snapshot()
            return withScopedEditOwner(frozen.record.scope, admit, consent = false, denied = false) { access ->
                acknowledgeTime(frozen, journal, access)
            }
        }

        override suspend fun complete(result: TimeUpdateVerifiedResult, journal: TimeUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            currentCoroutineContext().ensureActive()
            val frozen = result.snapshot()
            return withScopedEditOwner(frozen.record.scope, admit, consent = false, denied = false) { access ->
                completeTime(frozen, journal, access)
            }
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

    internal val localDeleteOwners: SystemDeleteLocalOwner = object : SystemDeleteLocalOwner {
        override suspend fun observe(scope: CalendarScope, event: EventId, owner: MutationId,
            admit: (CalendarScope) -> Boolean): LocalDeleteIntent? =
            withScopedEditOwner(scope, admit, true, null, sendingEnabled = systemDeletesEnabled) { access ->
                val intent = access.read { store.localDelete(scope, event) } ?: return@withScopedEditOwner null
                if (intent.id != owner || !deleteSource(intent, access)) return@withScopedEditOwner null
                DeleteSubmissionPolicy.freezeIntent(intent)
            }

        override suspend fun reserve(record: DeleteAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean =
            withScopedEditOwner(record.scope, admit, true, false, sendingEnabled = systemDeletesEnabled) { access ->
                if (record.state != DeleteAttemptState.PREPARED || record.protectedEvidence != null ||
                    record.id != DeleteAttemptIdentity.requestId(record.intent, record.format) ||
                    !deleteSource(record.intent, access)) return@withScopedEditOwner false
                val reservation = access.read { store.prepareDeleteAcknowledgement(record) } ?: return@withScopedEditOwner false
                reservation.record.receipt.attempt == DeleteAttemptIdentity.of(record) && deleteSource(record.intent, access)
            }

        override suspend fun submit(record: DeleteAttemptRecord, journal: DeleteRecoveryJournal,
            port: CalendarDeleteSubmissionPort, admit: (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): DeleteSubmissionObservation {
            val frozen = record.frozen()
            val operation = currentCoroutineContext()
            val factual = systemDeleteEvidenceAdmission(frozen.scope)
            val open = AtomicBoolean(true)
            val sourceOpen = AtomicBoolean(true)
            val evidenceOpen = AtomicBoolean(true)
            val evidence: (CalendarScope) -> Boolean = { requested ->
                operation.ensureActive()
                val allowed = open.get() && evidenceOpen.get() && requested == frozen.scope && factual(requested) && admitEvidence(requested)
                if (!allowed) evidenceOpen.set(false)
                allowed && open.get() && evidenceOpen.get()
            }
            try {
                if (frozen.state != DeleteAttemptState.PREPARED || frozen.protectedEvidence != null ||
                    port.deleteFormat != frozen.format || frozen.id != DeleteAttemptIdentity.requestId(frozen.intent, frozen.format))
                    return DeleteSubmissionObservation.Unknown
                return withScopedEditOwner(frozen.scope, admit, true, DeleteSubmissionObservation.Unknown,
                    finalAccessRequired = false, sendingEnabled = systemDeletesEnabled) { access ->
                    val sending: (CalendarScope) -> Boolean = { requested ->
                        operation.ensureActive()
                        val valid = open.get() && sourceOpen.get() && requested == frozen.scope && access.active() && port.deleteFormat == frozen.format
                        if (!valid) sourceOpen.set(false)
                        valid && open.get() && sourceOpen.get()
                    }
                    suspend fun source(requested: CalendarScope): Boolean = try {
                        val valid = sending(requested) && deleteSource(frozen.intent, access) && sending(requested)
                        if (!valid) sourceOpen.set(false)
                        valid
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { operation.ensureActive(); sourceOpen.set(false); false }
                    val reservation = access.read { store.deleteAcknowledgement(frozen.scope, frozen.eventId, frozen.id) }
                    if (reservation?.receipt?.attempt != DeleteAttemptIdentity.of(frozen) ||
                        access.read { journal.read(frozen.scope, frozen.id, sending) } != frozen || !source(frozen.scope))
                        return@withScopedEditOwner DeleteSubmissionObservation.Unknown
                    // Import -> auth lock order. Source callbacks never retake either lock.
                    val observation = port.submit(frozen, journal, sending, ::source, evidence)
                    operation.ensureActive()
                    when (observation) {
                        DeleteSubmissionObservation.Unknown -> observation
                        is DeleteSubmissionObservation.Conflict -> if (sending(frozen.scope) &&
                            observation.record.copy(state = DeleteAttemptState.PREPARED, protectedEvidence = null) == frozen)
                            observation else DeleteSubmissionObservation.Unknown
                        is DeleteSubmissionObservation.Verified -> {
                            if (!evidence(frozen.scope) ||
                                observation.record.copy(state = DeleteAttemptState.PREPARED, protectedEvidence = null) != frozen)
                                return@withScopedEditOwner DeleteSubmissionObservation.Unknown
                            val durable = journal.read(frozen.scope, frozen.id, evidence)
                            operation.ensureActive()
                            if (durable == null || durable != observation.record || !evidence(frozen.scope))
                                DeleteSubmissionObservation.Unknown else DeleteSubmissionObservation.Verified(durable)
                        }
                    }
                }
            } finally { open.set(false); sourceOpen.set(false); evidenceOpen.set(false) }
        }

        override suspend fun conflict(intent: LocalDeleteIntent, observed: RemoteChange.Upsert,
            admit: (CalendarScope) -> Boolean): Boolean =
            withScopedEditOwner(intent.scope, admit, false, false, sendingEnabled = systemDeletesEnabled) { access ->
                access.read { store.conflictLocalDelete(intent, observed) }.also { refreshState() }
            }

        override suspend fun acknowledge(result: DeleteVerifiedResult, journal: DeleteRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean =
            withScopedEditOwner(result.record.scope, admit, false, false, sendingEnabled = systemDeletesEnabled) { access ->
                acknowledgeDelete(result.snapshot(), journal, access)
            }

        override suspend fun complete(result: DeleteVerifiedResult, journal: DeleteRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean =
            withScopedEditOwner(result.record.scope, admit, false, false, sendingEnabled = systemDeletesEnabled) { access ->
                if (journal.completionProtocol != 1 || !currentDeleteResult(result, journal, access)) return@withScopedEditOwner false
                val ack = access.read { store.deleteAcknowledgement(result.record.scope, result.record.eventId, result.record.id) }
                    ?: return@withScopedEditOwner false
                if (ack.state != DeleteAcknowledgementState.RESTORED || !ack.receipt.matches(DeleteSubmissionObservation.Verified(result.record)))
                    return@withScopedEditOwner false
                val completion = DeleteCompletion.of(result, ack)
                val prior = access.read { journal.readCompletion(result.record.scope, result.record.id, access.admit) }
                if (prior != null && prior != completion) return@withScopedEditOwner false
                if (prior == null && !access.read { journal.retainCompletion(completion, access.admit) }) return@withScopedEditOwner false
                access.read { journal.readCompletion(result.record.scope, result.record.id, access.admit) } == completion &&
                    currentDeleteResult(result, journal, access)
            }
    }

    private suspend fun deleteSource(intent: LocalDeleteIntent, access: ScopedEditOwnerAccess): Boolean {
        if (intent.scope != access.target || intent.format != remote.format || intent.state != LocalEditState.PAUSED) return false
        DeleteSubmissionPolicy.freezeIntent(intent)
        suspend fun sql(): Boolean = remote.format == intent.format && access.read { store.qualifyLocalDelete(intent) } &&
            access.read { store.capturedHandoff(intent.scope, intent.base.eventId) } == null
        if (!sql()) return false
        val hold = access.read { capturedRawHold(intent.scope) }
        if (hold.scopeBlocked || hold.holds(intent.base.eventId)) return false
        val native = access.read { projection.captureOwnedSimpleDelete(intent.scope, intent.base) }
        if (native?.content != intent.content) return false
        val after = access.read { capturedRawHold(intent.scope) }
        return !after.scopeBlocked && !after.holds(intent.base.eventId) && sql()
    }

    private suspend fun currentDeleteResult(result: DeleteVerifiedResult, journal: DeleteRecoveryJournal,
        access: ScopedEditOwnerAccess): Boolean {
        val record = result.record
        if (record.scope != access.target || record.state != DeleteAttemptState.OUTCOME_UNKNOWN ||
            record.intent.format != remote.format ||
            access.read { store.transaction(record.scope) { checkpoint?.format } } != record.intent.format) return false
        val durable = access.read { journal.read(record.scope, record.id, access.admit) } ?: return false
        return durable.copy(protectedEvidence = null) == record &&
            access.read { journal.readVerified(record.scope, record.id, access.admit) } == result &&
            remote.format == record.intent.format
    }

    private suspend fun acknowledgeDelete(result: DeleteVerifiedResult, journal: DeleteRecoveryJournal,
        access: ScopedEditOwnerAccess): Boolean {
        if (projection.deleteAcknowledgementProtocol != 1 || !currentDeleteResult(result, journal, access)) return false
        val record = result.record
        val ack = access.read { store.deleteAcknowledgement(record.scope, record.eventId, record.id) } ?: return false
        if (!ack.receipt.matches(DeleteSubmissionObservation.Verified(record))) return false
        if (ack.state == DeleteAcknowledgementState.RESTORED) return currentDeleteResult(result, journal, access)
        if (!access.read { store.qualifyLocalDelete(record.intent) }) return false
        val witness = access.read { projection.inspectDeleteAcknowledgement(ack.receipt) } ?: run {
            if (!deleteSource(record.intent, access)) return false
            access.read { projection.acknowledgeDelete(ack.receipt) }
        } ?: return false
        if (witness.receipt != ack.receipt || !currentDeleteResult(result, journal, access) ||
            access.read { store.deleteAcknowledgement(record.scope, record.eventId, record.id) } != ack) return false
        return access.read { store.restoreDeleteAcknowledgement(witness) }.also { if (it) refreshState() }
    }

    /** A retained ACK explains only its exact marker. Native restoration/new edits become visible conflicts. */
    suspend fun reconcileReservedDeleteChange(target: CalendarScope, event: EventId, deleted: Boolean, dirty: Boolean): Boolean =
        withScopedEditOwner(target, systemDeleteProofAdmission(target), false, true,
            sendingEnabled = systemDeletesEnabled) { access ->
            val ack = access.read { store.deleteAcknowledgement(target, event) } ?: return@withScopedEditOwner false
            suspend fun conflict(): Boolean {
                access.read { store.conflictAcknowledgedDelete(ack.receipt.intent) }
                refreshState()
                return true
            }
            if (!deleted || (ack.state == DeleteAcknowledgementState.RESTORED && dirty)) return@withScopedEditOwner conflict()
            if (!dirty) {
                if (access.read { projection.inspectDeleteAcknowledgement(ack.receipt) }?.receipt != ack.receipt)
                    conflict()
                return@withScopedEditOwner true
            }
            val current = access.read { projection.captureOwnedSimpleDelete(target, ack.receipt.intent.base) }
            if (current?.content != ack.receipt.intent.content) conflict() else false
        }

    /** Current native contents only. The caller retains the observed role and typed SQL proof. */
    private suspend fun currentEditNativeSource(intent: LocalEditIntent, role: CapturedCreateHandoffRecord?,
        access: ScopedEditOwnerAccess): Boolean {
        val target = access.target
        val event = intent.base.eventId
        val hold = access.read { capturedRawHold(target) }
        if (hold.scopeBlocked || (role == null && hold.holds(event))) return false
        val content = if (role == null) access.read { projection.captureOwnedSimpleEdit(target, intent.base) }?.content else {
            if (!access.read { currentSystemTitleHandoff(role, intent, access::active) }) return false
            val observed = access.read { projection.captureCapturedHandoffCurrent(role.frame) }
                as? CapturedHandoffCurrentObservation.Supported
            observed?.snapshot?.takeIf { it.frame == role.frame && !it.deleted }?.content
        }
        return content == intent.content && access.read { store.capturedHandoff(target, event) } == role
    }

    /** Recheck the same typed SQL owner/capsule before and after the shared native observations. */
    private suspend fun currentEditSource(intent: LocalEditIntent, access: ScopedEditOwnerAccess,
        sql: suspend () -> Boolean): Boolean {
        val target = access.target
        val event = intent.base.eventId
        if (!sql()) return false
        val role = access.read { store.capturedHandoff(target, event) }
        if (!currentEditNativeSource(intent, role, access)) return false
        if (role != null && !access.read { currentSystemTitleHandoff(role, intent, access::active) }) return false
        val after = access.read { capturedRawHold(target) }
        if (after.scopeBlocked || (role == null && after.holds(event)) ||
            access.read { store.capturedHandoff(target, event) } != role) return false
        return sql()
    }

    /** A fresh native ACK still requires the exact current ordinary or transferred owner role. */
    private suspend fun currentEditAcknowledgementRole(intent: LocalEditIntent, frame: CapturedCreateHandoffFrame?,
        access: ScopedEditOwnerAccess): Boolean {
        val target = access.target
        val event = intent.base.eventId
        val role = access.read { store.capturedHandoff(target, event) }
        if (frame == null) {
            val hold = access.read { capturedRawHold(target) }
            if (role != null || hold.scopeBlocked || hold.holds(event)) return false
        } else if (role == null || role != CapturedCreateHandoffRecord(frame, CapturedCreateHandoffState.RELEASED, waiting = true) ||
            !access.read { currentSystemTitleHandoff(role, intent, access::active) }) return false
        return true
    }

    private class ScopedEditOwnerDenied : IllegalStateException("Edit owner access is inactive")

    private inner class ScopedEditOwnerAccess(val target: CalendarScope, private val expected: Long,
        private val policy: Long, private val consent: Boolean, private val admission: (CalendarScope) -> Boolean,
        private val operation: kotlin.coroutines.CoroutineContext,
        private val sendingEnabled: (CalendarScope) -> Boolean) {
        private val closed = AtomicBoolean(false)
        fun close() { closed.set(true) }
        fun active(): Boolean {
            operation.ensureActive()
            if (closed.get()) return false
            val allowed = authenticated && generation == expected && createPolicyRevision.get() == policy &&
                createAccount.get() == target.account && hasCalendarPermissions() && systemCreatesEnabled(target) &&
                (!consent || sendingEnabled(target)) &&
                state.value.selections.any { it.scope == target && it.enabled && it.available } && admission(target)
            operation.ensureActive()
            if (!allowed || generation != expected || createPolicyRevision.get() != policy ||
                createAccount.get() != target.account) closed.set(true)
            return !closed.get()
        }
        val admit: (CalendarScope) -> Boolean = { requested -> requested == target && active() }
        suspend fun requireAvailable() {
            operation.ensureActive()
            if (!active()) throw ScopedEditOwnerDenied()
            val selected = store.selections(target.account).singleOrNull { it.scope == target }
            operation.ensureActive()
            if (!active() || selected == null || !selected.enabled || !selected.available) {
                close(); throw ScopedEditOwnerDenied()
            }
        }
        suspend fun <T> read(action: suspend () -> T): T {
            requireAvailable()
            val value = action()
            operation.ensureActive()
            requireAvailable()
            return value
        }
    }

    private suspend fun <T> withScopedEditOwner(target: CalendarScope, admit: (CalendarScope) -> Boolean,
        consent: Boolean, denied: T, finalAccessRequired: Boolean = true,
        sendingEnabled: (CalendarScope) -> Boolean = systemTimesEnabled,
        action: suspend (ScopedEditOwnerAccess) -> T): T {
        currentCoroutineContext().ensureActive()
        val expected = generation
        val policy = createPolicyRevision.get()
        return mutex.withLock {
            val access = ScopedEditOwnerAccess(target, expected, policy, consent, admit, currentCoroutineContext(), sendingEnabled)
            try {
                access.requireAvailable()
                store.admitTransaction = access.admit
                projection.admitOperation = access::active
                val result = action(access)
                currentCoroutineContext().ensureActive()
                if (finalAccessRequired) access.requireAvailable()
                result
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: ScopedEditOwnerDenied) { currentCoroutineContext().ensureActive(); denied }
              catch (_: Exception) { currentCoroutineContext().ensureActive(); denied }
              finally {
                  access.close()
                  store.admitTransaction = { false }
                  projection.admitOperation = { false }
              }
        }
    }

    private fun freezeTimePreparation(value: LocalTimeEditPreparation) =
        LocalTimeEditPreparation(value.intent, value.timestamp, value.format, value.protectedPayload)

    private fun sameTimePreparation(left: LocalTimeEditPreparation?, right: LocalTimeEditPreparation?): Boolean =
        if (left == null || right == null) left == null && right == null else
            left.intent == right.intent && left.timestamp == right.timestamp && left.format == right.format &&
                left.protectedPayload == right.protectedPayload

    /** Exact SQL and native source, including complete immutable origin for a handed-off CREATE. */
    private suspend fun timeSource(intent: LocalEditIntent, preparation: LocalTimeEditPreparation?,
        access: ScopedEditOwnerAccess): Boolean {
        val target = access.target
        val event = intent.base.eventId
        if (intent.scope != target || !SystemTimeEditProfile.accepts(intent) || intent.format != remote.format) return false
        suspend fun sql(): Boolean {
            if (remote.format != intent.format) return false
            if (access.read { store.localEdit(target, event) } != intent) return false
            val eligibility = access.read { store.assessLocalTimePreparation(intent) }
            if (eligibility != if (preparation == null) LocalTimePreparationAdmission.ELIGIBLE
                else LocalTimePreparationAdmission.HISTORICAL) return false
            return sameTimePreparation(access.read { store.localTimeEditPreparation(target, event) }, preparation) &&
                remote.format == intent.format
        }
        return currentEditSource(intent, access, ::sql)
    }

    private suspend fun currentTimeResult(result: TimeUpdateVerifiedResult, journal: TimeUpdateRecoveryJournal,
        access: ScopedEditOwnerAccess): Boolean {
        val record = result.record
        if (record.scope != access.target || record.state != TimeUpdateAttemptState.OUTCOME_UNKNOWN ||
            record.preparation.intent.format != remote.format ||
            access.read { store.transaction(access.target) { checkpoint?.format } } != record.preparation.intent.format)
            return false
        val durable = access.read { journal.read(access.target, record.id, access.admit) } ?: return false
        return durable.copy(protectedEvidence = null) == record &&
            access.read { journal.readVerified(access.target, record.id, access.admit) } == result &&
            access.read { store.transaction(access.target) { checkpoint?.format } } == record.preparation.intent.format &&
            remote.format == record.preparation.intent.format
    }

    private suspend fun acknowledgeTime(result: TimeUpdateVerifiedResult, journal: TimeUpdateRecoveryJournal,
        access: ScopedEditOwnerAccess): Boolean {
        if (projection.timeUpdateAcknowledgementProtocol != 1 || !currentTimeResult(result, journal, access)) return false
        val target = access.target
        val attempt = result.record.id
        val intent = result.record.preparation.intent
        val event = result.event.eventId
        val existing = access.read { store.timeUpdateAcknowledgement(target, event, attempt) }
        if (existing != null && !existing.receipt.matches(result)) return false
        if (existing?.state == TimeUpdateAcknowledgementState.RESTORED)
            return currentTimeResult(result, journal, access)
        val reservation = if (existing != null) TimeUpdateAcknowledgementReservation(existing, false) else {
            val role = access.read { store.capturedHandoff(target, event) }
            if (!currentEditNativeSource(intent, role, access) || !currentTimeResult(result, journal, access)) return false
            access.read { store.prepareTimeUpdateAcknowledgement(result, role?.frame) } ?: return false
        }
        val retained = reservation.record
        if (!retained.receipt.matches(result) ||
            access.read { store.timeUpdateAcknowledgement(target, event, attempt) } != retained ||
            !currentTimeResult(result, journal, access)) return false
        if (reservation.fresh) {
            val owner = access.read { store.localEdit(target, event) }
            val echoedOwner = owner?.state == LocalEditState.CONFLICT && owner.copy(state = LocalEditState.PAUSED) == intent
            if ((owner != intent && !echoedOwner) || !sameTimePreparation(
                    access.read { store.localTimeEditPreparation(target, event) }, result.record.preparation)) return false
            if (!currentEditAcknowledgementRole(intent, retained.receipt.handoff, access)) return false
        }
        val historicalFrame = retained.receipt.handoff
        if (historicalFrame != null && !access.read { handoffSource(historicalFrame, access::active) }) return false
        // A retained pending receipt only inspects. An interrupted batch is never replayed.
        val witness = access.read {
            if (reservation.fresh) projection.acknowledgeTimeUpdate(retained.receipt)
            else projection.inspectTimeUpdateAcknowledgement(retained.receipt)
        } ?: return false
        if (witness.receipt.snapshot() != retained.receipt || !currentTimeResult(result, journal, access) ||
            access.read { store.timeUpdateAcknowledgement(target, event, attempt) } != retained) return false
        if (historicalFrame != null && !access.read { handoffSource(historicalFrame, access::active) }) return false
        if (!access.read { store.restoreTimeUpdateAcknowledgement(witness) }) return false
        return access.read { store.timeUpdateAcknowledgement(target, event, attempt) } ==
            TimeUpdateAcknowledgementRecord(retained.receipt, TimeUpdateAcknowledgementState.RESTORED) &&
            currentTimeResult(result, journal, access)
    }

    private suspend fun completeTime(result: TimeUpdateVerifiedResult, journal: TimeUpdateRecoveryJournal,
        access: ScopedEditOwnerAccess): Boolean {
        if (journal.completionProtocol != 1 || !currentTimeResult(result, journal, access)) return false
        val target = access.target
        val event = result.event.eventId
        val attempt = result.record.id
        val acknowledgement = access.read { store.timeUpdateAcknowledgement(target, event, attempt) } ?: return false
        if (acknowledgement.state != TimeUpdateAcknowledgementState.RESTORED || !acknowledgement.receipt.matches(result)) return false
        val completion = TimeUpdateCompletion.of(result, acknowledgement)
        val retained = access.read { journal.readCompletion(target, attempt, access.admit) }
        if (retained != null && retained != completion) return false
        if (!currentTimeResult(result, journal, access) ||
            access.read { store.timeUpdateAcknowledgement(target, event, attempt) } != acknowledgement) return false
        if (retained == null && !access.read { journal.retainCompletion(completion, access.admit) }) return false
        return access.read { journal.readCompletion(target, attempt, access.admit) } == completion &&
            access.read { store.timeUpdateAcknowledgement(target, event, attempt) } == acknowledgement &&
            currentTimeResult(result, journal, access)
    }

    suspend fun configureSystemCalendar(target: CalendarScope): Boolean = mutex.withLock {
        val expected = generation
        if (!hasCalendarPermissions()) return@withLock false
        val selected = store.selections(target.account).singleOrNull { it.scope == target }
        val mode = systemCreatesEnabled(target) && selected?.enabled == true && selected.available &&
            isCurrent(expected, target.account)
        val policy = createPolicyRevision.get()
        projection.admitOperation = { generation == expected && hasCalendarPermissions() &&
            createPolicyRevision.get() == policy && (!mode || (authenticated && systemCreatesEnabled(target))) }
        try {
            if (mode) projection.ensureCalendarState(target, selected!!.summary, true)
            else projection.makeCalendarReadOnly(target)
            true
        }
        finally { projection.admitOperation = { false } }
    }

    /** Also closes retained mirrors after sign-out/account changes and a cold process restart. */
    suspend fun reconcileSystemCalendarAccess() {
        for (target in store.calendarScopes()) configureSystemCalendar(target)
    }

    suspend fun discoverSystemChanges(target: CalendarScope, afterRow: Long = 0, limit: Int = 25):
        com.patmanak.proton.andcalendarbridge.data.calendar.SystemCalendarChangePage? = mutex.withLock {
        val expected = generation
        val policy = createPolicyRevision.get()
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy &&
            systemCreatesEnabled(target) && hasCalendarPermissions() }
        if (!active() || !isCurrent(expected, target.account)) return@withLock null
        val selected = store.selections(target.account).singleOrNull { it.scope == target }
        if (selected?.enabled != true || !selected.available) return@withLock null
        projection.admitOperation = active
        store.admitTransaction = { it == target && active() }
        try {
            val page = projection.discoverSystemChanges(target, afterRow, limit)
                ?: return@withLock null
            val keys = page.changes.filter { it.kind == com.patmanak.proton.andcalendarbridge.data.calendar.SystemCalendarChangeKind.IMPORTED }
                .mapNotNull { it.nativeKey }.toSet()
            val resolved = mutableMapOf<String, EventId>()
            val ambiguous = mutableSetOf<String>()
            var afterEvent: EventId? = null
            // Metadata traversal can be linear in acknowledged event count. Memory stays bounded
            // to one ID page and the native change page; a persisted native-key index is future work.
            if (keys.isNotEmpty()) while (true) {
                currentCoroutineContext().ensureActive()
                if (!active() || !isCurrent(expected, target.account)) return@withLock null
                val ids = store.projectedEventIds(target, afterEvent, 100)
                for (event in ids) {
                    val key = projection.eventIdentityKey(target, event) ?: continue
                    if (key !in keys || key in ambiguous) continue
                    val previous = resolved.put(key, event)
                    if (previous != null && previous != event) {
                        resolved.remove(key); ambiguous += key
                    }
                }
                if (ids.size < 100) break
                afterEvent = ids.last()
            }
            val canonical = page.copy(changes = page.changes.map { change ->
                if (change.kind == com.patmanak.proton.andcalendarbridge.data.calendar.SystemCalendarChangeKind.IMPORTED)
                    change.copy(event = change.nativeKey?.let(resolved::get)) else change
            })
            val current = store.selections(target.account).singleOrNull { it.scope == target }
            if (active() && current?.enabled == true && current.available) canonical else null
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
          finally { projection.admitOperation = { false }; store.admitTransaction = { false } }
    }

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

    /** Explicit capture followed by title-only preparation under one session/policy observation.
     * Capture is durable before preparation. Failure or cancellation keeps that intent; a retry
     * never substitutes later Provider content. No acknowledgement, dispatch or observer is enabled.
     */
    suspend fun captureAndPrepareLocalTitle(target: CalendarScope, eventId: EventId, id: MutationId,
        timestamp: java.time.Instant, port: CalendarLocalEditPreparationPort): LocalTitlePreparationResult =
        mutex.withLock {
            val expected = generation
            val policy = createPolicyRevision.get()
            try {
                currentCoroutineContext().ensureActive()
                when (captureLocalEditLocked(target, eventId, id, expected, policy)) {
                    LocalEditCaptureResult.RETAINED -> {
                        currentCoroutineContext().ensureActive()
                        prepareLocalTitleLocked(target, eventId, id, timestamp, port, expected, policy)
                    }
                    LocalEditCaptureResult.INACTIVE -> LocalTitlePreparationResult.INACTIVE
                    LocalEditCaptureResult.UNQUALIFIED -> LocalTitlePreparationResult.UNQUALIFIED
                    LocalEditCaptureResult.CONFLICT -> LocalTitlePreparationResult.CONFLICT
                    LocalEditCaptureResult.UNAVAILABLE -> LocalTitlePreparationResult.UNAVAILABLE
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { LocalTitlePreparationResult.UNAVAILABLE }
              finally {
                store.admitTransaction = { false }
                projection.admitOperation = { false }
            }
        }

    /** Internal inspection/recovery only. Network eligibility never grants submission admission. */
    suspend fun inspectAndRecoverLocalTitles(query: TitleUpdateWorkQuery, journal: TitleUpdateRecoveryJournal,
        port: CalendarTitleUpdateSubmissionPort, allowNetwork: Boolean = false): TitleUpdateRecoveryProgress = mutex.withLock {
        recoverLocalTitlesLocked(query, journal, port, allowNetwork)
    }

    /** One internal offline page. No inspection, preparation or submission capability. */
    suspend fun recoverLocalTitles(query: TitleUpdateWorkQuery,
        journal: TitleUpdateOfflineRecoveryJournal): TitleUpdateOfflineRecoveryProgress = mutex.withLock {
        recoverLocalTitlesLocked(query, journal)
    }

    /** Opt-in network inspection only; durable historical proofs keep their offline admission. */
    suspend fun inspectSystemTitles(query: TitleUpdateWorkQuery, journal: TitleUpdateRecoveryJournal,
        port: CalendarTitleUpdateSubmissionPort, admission: (CalendarScope) -> Boolean,
        proofRetained: () -> Unit): TitleUpdateRecoveryProgress = mutex.withLock {
        recoverLocalTitlesLocked(query, journal, port, allowNetwork = true,
            networkAdmission = admission, proofRetained = proofRetained)
    }

    private suspend fun recoverLocalTitlesLocked(query: TitleUpdateWorkQuery,
        journal: TitleUpdateOfflineRecoveryJournal, inspection: CalendarTitleUpdateSubmissionPort? = null,
        allowNetwork: Boolean = false, networkAdmission: ((CalendarScope) -> Boolean)? = null,
        proofRetained: () -> Unit = {}): TitleUpdateRecoveryProgress {
        val target = query.scope
        val expected = generation
        val policy = createPolicyRevision.get()
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy &&
            systemCreatesEnabled(target) && hasCalendarPermissions() }
        var completed = 0
        var held = 0
        var failed = 0
        fun progress(next: TitleUpdateWorkCursor? = null, inactive: Boolean = false) =
            TitleUpdateOfflineRecoveryProgress(completed, held, failed, next, inactive)
        suspend fun available(): Boolean {
            currentCoroutineContext().ensureActive()
            if (!active() || !isCurrent(expected, target.account)) return false
            val selected = store.selections(target.account).singleOrNull { it.scope == target }
            return selected?.enabled == true && selected.available && active()
        }
        return try {
            if (!available()) return progress(inactive = true)
            check(journal.completionProtocol == 1) { "Incompatible title recovery journal" }
            val recovery = if (inspection != null) {
                check(journal is TitleUpdateRecoveryJournal) { "Incompatible title inspection journal" }
                TitleUpdateGuardedResultRecovery(journal, journal, inspection)
            } else null
            val page = journal.page(query)
            if (!available()) return progress(inactive = true)
            val next = page.next
            check(page.scope == target && page.entries.size <= query.limit &&
                (next == null || (next.scope == target && next.protocol == 1 &&
                    next.afterEvent == page.entries.lastOrNull()?.eventId))) { "Invalid title recovery page" }
            for (reference in page.entries) {
                if (!available()) return progress(inactive = true)
                // The helpers close their leases; install this invocation's same epoch again.
                store.admitTransaction = { it == target && active() }
                try {
                    val record = journal.read(target, reference.id)?.frozen()
                    if (!available()) return progress(inactive = true)
                    if (record == null) { held++; continue }
                    check(record.scope == target && record.id == reference.id &&
                        record.preparation.intent.base.eventId == reference.eventId) { "Invalid title recovery binding" }
                    // Metadata may have changed state since discovery. Only the complete record counts.
                    if (record.state != TitleUpdateAttemptState.OUTCOME_UNKNOWN ||
                        record.preparation.intent.format != remote.format ||
                        store.transaction(target) { checkpoint?.format } != record.preparation.intent.format) {
                        if (!available()) return progress(inactive = true)
                        held++; continue
                    }
                    if (!available()) return progress(inactive = true)
                    val historical = journal.readVerified(target, reference.id)
                    var inspectionSourceFrozen = false
                    var inspectionRole: CapturedCreateHandoffRecord? = null
                    suspend fun networkAvailable(): Boolean {
                        if (!available()) return false
                        if (networkAdmission == null) return true
                        if (!systemTitlesEnabled(target) || !networkAdmission(target) ||
                            !LocalTitleOwnerQualification.systemTitleProfile(record.preparation.intent)) return false
                        val role = store.capturedHandoff(target, reference.eventId)
                        if (!available()) return false
                        val hold = capturedRawHold(target)
                        currentCoroutineContext().ensureActive()
                        if (!available() || hold.scopeBlocked || (inspectionSourceFrozen && role != inspectionRole)) return false
                        if (role == null) {
                            if (hold.holds(reference.eventId)) return false
                        } else {
                            // Historical inspection must survive a later native edit. Sending and
                            // acknowledgement separately require their exact current owner/frame.
                            if (role.state != CapturedCreateHandoffState.RELEASED ||
                                role.frame.format != record.preparation.intent.format ||
                                !handoffSource(role.frame, active)) return false
                            if (!available()) return false
                        }
                        val currentRole = store.capturedHandoff(target, reference.eventId)
                        if (!available() || currentRole != role) return false
                        val currentHold = capturedRawHold(target)
                        currentCoroutineContext().ensureActive()
                        if (!available() || currentHold.scopeBlocked ||
                            (role == null && currentHold.holds(reference.eventId)) ||
                            !systemTitlesEnabled(target) || !networkAdmission(target)) return false
                        inspectionRole = role
                        inspectionSourceFrozen = true
                        return true
                    }
                    val result = historical ?: if (recovery != null && networkAvailable()) recovery.recover(
                        target, reference.id, allowNetwork, admit = { it == target && active() &&
                            (networkAdmission == null || (systemTitlesEnabled(target) && networkAdmission(target))) },
                        checkAccess = { networkAvailable() }) else null
                    if (historical == null && result != null) proofRetained()
                    if (!available()) return progress(inactive = true)
                    if (result == null) { held++; continue }
                    check(result.record == record.copy(protectedEvidence = null)) { "Invalid title recovery result" }
                    when (acknowledgeLocalTitleLocked(target, reference.eventId, reference.id, journal, expected, policy)) {
                        TitleUpdateAcknowledgementResult.INACTIVE -> return progress(inactive = true)
                        TitleUpdateAcknowledgementResult.UNRESOLVED -> { held++; continue }
                        TitleUpdateAcknowledgementResult.ACKNOWLEDGED -> Unit
                    }
                    if (!available()) return progress(inactive = true)
                    val completion = completeLocalTitleLocked(target, reference.eventId, reference.id, journal, expected, policy)
                    if (!available()) return progress(inactive = true)
                    when (completion) {
                        TitleUpdateCompletionResult.INACTIVE -> return progress(inactive = true)
                        TitleUpdateCompletionResult.UNRESOLVED -> held++
                        TitleUpdateCompletionResult.COMPLETED -> completed++
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) {
                      if (!available()) return progress(inactive = true)
                      failed++
                  }
            }
            progress(next = next)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
              if (!active()) progress(inactive = true)
              else {
                  // A failed page/access read gives no safe continuation. Restart at the first page.
                  if (completed + held + failed < TitleUpdateWorkQuery.MAX_PAGE_SIZE) failed++
                  progress()
              }
          } finally {
              store.admitTransaction = { false }
              projection.admitOperation = { false }
          }
    }

    /** Recovery passes its original epoch; constructing a helper must never renew admission. */
    private fun localTitleCompletion(target: CalendarScope, expected: Long, policy: Long) =
        LocalTitleCompletion(target, store, projection,
            currentFormat = { remote.format },
            active = { authenticated && generation == expected && createPolicyRevision.get() == policy &&
                systemCreatesEnabled(target) && hasCalendarPermissions() },
            currentAccount = { isCurrent(expected, target.account) })

    /** Internal simple title acknowledgement only. Reads an already durable verified result;
     * never prepares, inspects the service or dispatches UPDATE. A fresh reservation permits one
     * conditional native metadata write. Reopening only inspects the retained nonce.
     */
    suspend fun acknowledgeLocalTitle(target: CalendarScope, eventId: EventId, attempt: MutationId,
        journal: TitleUpdateResultJournal): TitleUpdateAcknowledgementResult = mutex.withLock {
        acknowledgeLocalTitleLocked(target, eventId, attempt, journal, generation, createPolicyRevision.get())
    }

    private suspend fun acknowledgeLocalTitleLocked(target: CalendarScope, eventId: EventId, attempt: MutationId,
        journal: TitleUpdateResultJournal, expected: Long, policy: Long): TitleUpdateAcknowledgementResult {
        return localTitleCompletion(target, expected, policy).acknowledge(eventId, attempt, journal)
    }

    /** Internal durable closure only; no Provider write or service request. The two journals
     * commit independently: a restored ACK without completion keeps the attempt's hold and
     * can be resumed by reading its exact historical proof, including after a later edit.
     */
    suspend fun completeLocalTitle(target: CalendarScope, eventId: EventId, attempt: MutationId,
        journal: TitleUpdateCompletionJournal): TitleUpdateCompletionResult = mutex.withLock {
        completeLocalTitleLocked(target, eventId, attempt, journal, generation, createPolicyRevision.get())
    }

    private suspend fun completeLocalTitleLocked(target: CalendarScope, eventId: EventId, attempt: MutationId,
        journal: TitleUpdateCompletionJournal, expected: Long, policy: Long): TitleUpdateCompletionResult {
        return localTitleCompletion(target, expected, policy).complete(eventId, attempt, journal)
    }

    private fun localChangeCapture(target: CalendarScope, expected: Long, active: () -> Boolean) =
        LocalChangeCapture(target, store, projection,
            currentFormat = { remote.format }, active = active,
            currentAccount = { isCurrent(expected, target.account) },
            requireUnheld = { event -> if (capturedRawHold(target).holds(event)) throw CapturedProjectionPending() },
            baseline = { event -> captureBaseline(expected, target, event) })

    /** Explicit internal UPDATE snapshot only. The dirty Provider row is never acknowledged. */
    suspend fun captureLocalEdit(target: CalendarScope, eventId: EventId, id: MutationId): LocalEditCaptureResult = mutex.withLock {
        captureLocalEditLocked(target, eventId, id, generation, createPolicyRevision.get())
    }

    /** Offline observer capture. Reuse the durable owner until it is closed; a later cycle
     * receives a new identity, never the event's fixed identity from a previous upload.
     */
    suspend fun captureSystemLocalEdit(target: CalendarScope, eventId: EventId,
        beforeNewOwner: suspend () -> Unit = {}): LocalEditCaptureResult = mutex.withLock {
        captureLocalEditLocked(target, eventId, null, generation, createPolicyRevision.get(), requireSystemMode = true,
            beforeNewOwner = beforeNewOwner)
    }

    private suspend fun captureLocalEditLocked(target: CalendarScope, eventId: EventId, id: MutationId?,
        expected: Long, policy: Long, requireSystemMode: Boolean = false,
        beforeNewOwner: suspend () -> Unit = {}): LocalEditCaptureResult {
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() &&
            (!requireSystemMode || systemCreatesEnabled(target)) }
        if (!isCurrent(expected, target.account) || !active()) return LocalEditCaptureResult.INACTIVE
        return localChangeCapture(target, expected, active).edit(eventId, id, beforeNewOwner)
    }

    private fun localChangeHistory(target: CalendarScope, expected: Long, policy: Long) =
        LocalChangeHistory(target, store, projection,
            active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() },
            currentAccount = { isCurrent(expected, target.account) })

    /** Local SQL history only. A missing dossier still passes the final session/cancellation gate. */
    private suspend fun <T : Any> readConflictArchive(target: CalendarScope,
        read: suspend () -> T?): ConflictArchiveRead<T> = mutex.withLock {
        val expected = generation
        val policy = createPolicyRevision.get()
        localChangeHistory(target, expected, policy).readArchive(read)
    }

    /** Explicit metadata discovery. Each page requires a fresh historical account/permission gate. */
    suspend fun retainedLocalChanges(query: RetainedLocalChangeQuery): RetainedLocalChangeListing = mutex.withLock {
        val target = query.scope
        val expected = generation
        val policy = createPolicyRevision.get()
        localChangeHistory(target, expected, policy).list(query)
    }

    /** Explicit local history inspection. Never reads Provider contents or renews remote rights. */
    suspend fun inspectLocalEdit(target: CalendarScope, eventId: EventId, id: MutationId): LocalEditInspection = mutex.withLock {
        val expected = generation
        val policy = createPolicyRevision.get()
        localChangeHistory(target, expected, policy).inspectEdit(eventId, id)
    }

    /** Explicit DELETE history only; never acknowledges a deletion or supplies an undo image. */
    suspend fun inspectLocalDelete(target: CalendarScope, eventId: EventId, id: MutationId): LocalDeleteInspection = mutex.withLock {
        val expected = generation
        val policy = createPolicyRevision.get()
        localChangeHistory(target, expected, policy).inspectDelete(eventId, id)
    }

    /** Internal submission followed by guarded result retention under the same original epoch. */
    suspend fun submitAndRetainLocalTitleAttempt(record: TitleUpdateAttemptRecord,
        attempts: TitleUpdateAttemptJournal, results: TitleUpdateResultJournal,
        port: CalendarTitleUpdateSubmissionPort, allowNetwork: Boolean = false): TitleUpdateVerifiedResult? =
        submitLocalTitleAttemptGuarded(record, attempts, port, allowNetwork, results).verifiedResult

    /** Internal submission of already durable wire; offline by default. The local callback
     * observes ownership without re-entering authentication while its submission lock is held.
     * A verified observation does not acknowledge Android or durably store a verified result.
     */
    suspend fun submitLocalTitleAttempt(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
        port: CalendarTitleUpdateSubmissionPort, allowNetwork: Boolean = false): TitleUpdateSubmissionObservation =
        submitLocalTitleAttemptGuarded(record, journal, port, allowNetwork, null).observation

    private class TitleSubmissionOutcome(
        val observation: TitleUpdateSubmissionObservation = TitleUpdateSubmissionObservation.Unknown,
        val verifiedResult: TitleUpdateVerifiedResult? = null,
    )

    private suspend fun submitLocalTitleAttemptGuarded(record: TitleUpdateAttemptRecord,
        journal: TitleUpdateAttemptJournal, port: CalendarTitleUpdateSubmissionPort,
        allowNetwork: Boolean, results: TitleUpdateResultJournal?, systemTitle: Boolean = false,
        admission: (CalendarScope) -> Boolean = { true }): TitleSubmissionOutcome {
        val unresolved = TitleSubmissionOutcome()
        val frozen = record.frozen()
        currentCoroutineContext().ensureActive()
        if (!allowNetwork || frozen.state != TitleUpdateAttemptState.PREPARED || frozen.protectedEvidence != null)
            return unresolved
        return mutex.withLock {
            val target = frozen.scope
            val qualification = localTitleOwnerQualification(frozen.preparation, frozen.format, port,
                generation, createPolicyRevision.get(), systemTitle, admission)
            var closed = false
            val lease = { scope: CalendarScope ->
                if (scope != target || closed) false else qualification.active().also { if (!it) closed = true }
            }
            suspend fun active(): Boolean {
                currentCoroutineContext().ensureActive()
                val allowed = lease(target)
                currentCoroutineContext().ensureActive()
                return allowed
            }
            val source: suspend (CalendarScope) -> Boolean = { scope ->
                currentCoroutineContext().ensureActive()
                if (scope != target || !active()) false else {
                    val qualified = try { qualification.localSourceQualification() == null }
                    catch (failure: Exception) { closed = true; currentCoroutineContext().ensureActive(); throw failure }
                    if (!qualified) closed = true
                    currentCoroutineContext().ensureActive()
                    val stillActive = active()
                    qualified && stillActive
                }
            }
            suspend fun currentSource(): Boolean {
                if (!active()) return false
                // port.submit has returned and released its authentication mutex. Fresh account
                // observations are safe here and cannot replace this invocation's original epoch.
                val qualified = qualification.available() && source(target)
                if (!qualified) closed = true
                val stillActive = active()
                return qualified && stillActive
            }
            try {
                // This account lookup is deliberately outside port.submit's authentication lock.
                if (!qualification.available() || !active()) return@withLock unresolved
                store.admitTransaction = lease
                projection.admitOperation = { lease(target) }
                val stored = journal.read(target, frozen.id)?.frozen()
                if (!active() || stored != frozen || !source(target)) return@withLock unresolved
                val result = port.submit(frozen, journal, lease, source)
                currentCoroutineContext().ensureActive()
                if (!active() || result !is TitleUpdateSubmissionObservation.Verified ||
                    result.attempt != TitleUpdateAttemptIdentity.of(frozen) ||
                    result.event.eventId != frozen.preparation.intent.base.eventId ||
                    result.event.content != frozen.preparation.intent.content)
                    return@withLock unresolved
                val durable = journal.read(target, frozen.id)?.frozen()
                if (!active() || durable == null || durable.state != TitleUpdateAttemptState.OUTCOME_UNKNOWN ||
                    durable.protectedEvidence == null ||
                    durable.copy(state = TitleUpdateAttemptState.PREPARED, protectedEvidence = null) != frozen || !source(target))
                    return@withLock unresolved
                if (results == null) return@withLock TitleSubmissionOutcome(result)
                if (!currentSource()) return@withLock unresolved
                val verified = TitleUpdateVerifiedResult.of(durable, result)
                val committed = results.retainVerified(verified, lease)
                // Recheck even false/null results: a dependency may have swallowed cancellation.
                if (!currentSource() || !committed) return@withLock unresolved
                val retained = results.readVerified(target, frozen.id)
                if (!currentSource() || retained != verified) return@withLock unresolved
                TitleSubmissionOutcome(result, retained)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { currentCoroutineContext().ensureActive(); unresolved }
              finally {
                  closed = true
                  store.admitTransaction = { false }
                  projection.admitOperation = { false }
              }
        }
    }

    /** Current source, with complete provenance required for a transferred CREATE. */
    private suspend fun systemTitleSource(intent: LocalEditIntent, active: () -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        if (!active() || !LocalTitleOwnerQualification.systemTitleProfile(intent)) return false
        val target = intent.scope
        val event = intent.base.eventId
        if (store.localEdit(target, event) != intent || !active()) return false
        val role = store.capturedHandoff(target, event)
        if (!active()) return false
        val hold = capturedRawHold(target)
        if (!active() || hold.scopeBlocked || (role == null && hold.holds(event))) return false
        val content = if (role == null) projection.captureOwnedSimpleEdit(target, intent.base)?.content else {
            if (!currentSystemTitleHandoff(role, intent, active)) return false
            val observed = projection.captureCapturedHandoffCurrent(role.frame) as? CapturedHandoffCurrentObservation.Supported
            observed?.snapshot?.takeIf { it.frame == role.frame && !it.deleted }?.content
        }
        currentCoroutineContext().ensureActive()
        if (!active() || content != intent.content) return false
        if (store.capturedHandoff(target, event) != role || !active()) return false
        if (role != null && !currentSystemTitleHandoff(role, intent, active)) return false
        val after = capturedRawHold(target)
        currentCoroutineContext().ensureActive()
        return active() && !after.scopeBlocked && (role != null || !after.holds(event)) &&
            store.capturedHandoff(target, event) == role && store.localEdit(target, event) == intent && active()
    }

    private suspend fun currentSystemTitleHandoff(role: CapturedCreateHandoffRecord, intent: LocalEditIntent,
        active: () -> Boolean): Boolean {
        if (!active() || !LocalTitleOwnerQualification.matchesReleasedHandoff(role, intent)) return false
        val pending = store.capturedHandoffProjection(role.frame)
        currentCoroutineContext().ensureActive()
        if (!active() || pending != null) return false
        val qualified = handoffSource(role.frame, active)
        currentCoroutineContext().ensureActive()
        return qualified && active()
    }

    /** Production-only opt-in preparation. Capsule and wire keep the original epoch and timestamp. */
    suspend fun prepareSystemTitle(target: CalendarScope, event: EventId, owner: MutationId,
        attempt: MutationId, timestamp: java.time.Instant, journal: TitleUpdatePreparationJournal,
        localPort: CalendarLocalEditPreparationPort, titlePort: CalendarTitleUpdateSubmissionPort,
        admission: (CalendarScope) -> Boolean): LocalTitleAttemptPreparation {
        var expected = -1L
        var policy = -1L
        var capsule: LocalEditPreparation? = null
        val refusal = mutex.withLock {
            expected = generation; policy = createPolicyRevision.get()
            val active = { authenticated && generation == expected && createPolicyRevision.get() == policy &&
                systemCreatesEnabled(target) && systemTitlesEnabled(target) && hasCalendarPermissions() && admission(target) }
            try {
                if (!active() || !isCurrent(expected, target.account)) return@withLock LocalTitleAttemptPreparation.Inactive
                store.admitTransaction = { it == target && active() }
                projection.admitOperation = active
                val intent = store.localEdit(target, event) ?: return@withLock LocalTitleAttemptPreparation.Unqualified
                if (intent.id != owner || !systemTitleSource(intent, active)) return@withLock LocalTitleAttemptPreparation.Unsupported
                val retained = store.localEditPreparation(target, event)
                if (!active()) return@withLock LocalTitleAttemptPreparation.Inactive
                val actualTime = retained?.timestamp ?: timestamp
                val result = prepareLocalTitleLocked(target, event, owner, actualTime, localPort, expected, policy,
                    systemTitle = true, admission = admission)
                if (!active()) return@withLock LocalTitleAttemptPreparation.Inactive
                when (result) {
                    LocalTitlePreparationResult.RETAINED, LocalTitlePreparationResult.HISTORICAL -> Unit
                    LocalTitlePreparationResult.INACTIVE -> return@withLock LocalTitleAttemptPreparation.Inactive
                    LocalTitlePreparationResult.CONFLICT -> return@withLock LocalTitleAttemptPreparation.Conflict
                    LocalTitlePreparationResult.UNSUPPORTED -> return@withLock LocalTitleAttemptPreparation.Unsupported
                    LocalTitlePreparationResult.UNAVAILABLE -> return@withLock LocalTitleAttemptPreparation.Unavailable
                    else -> return@withLock LocalTitleAttemptPreparation.Unqualified
                }
                store.admitTransaction = { it == target && active() }
                capsule = store.localEditPreparation(target, event)
                if (!active() || capsule?.intent != intent || capsule?.timestamp != actualTime)
                    return@withLock LocalTitleAttemptPreparation.Unqualified
                null
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { currentCoroutineContext().ensureActive(); LocalTitleAttemptPreparation.Unavailable }
              finally { store.admitTransaction = { false }; projection.admitOperation = { false } }
        }
        if (refusal != null) return refusal
        val prepared = capsule ?: return LocalTitleAttemptPreparation.Unresolved
        val format = titlePort.titleUpdateFormat ?: return LocalTitleAttemptPreparation.Unsupported
        val lease = { scope: CalendarScope -> scope == target && generation == expected &&
            createPolicyRevision.get() == policy && admission(scope) }
        return prepareLocalTitleAttempt(TitleUpdatePreparationReservation(attempt, prepared, format),
            journal, titlePort, allowNetwork = true, systemTitle = true, admission = lease)
    }

    /** Production title submission; the original worker admission participates through commit. */
    suspend fun submitSystemTitle(record: TitleUpdateAttemptRecord, journal: TitleUpdateRecoveryJournal,
        port: CalendarTitleUpdateSubmissionPort, admission: (CalendarScope) -> Boolean): TitleUpdateVerifiedResult? =
        submitLocalTitleAttemptGuarded(record, journal, port, true, journal, systemTitle = true,
            admission = admission).verifiedResult

    private fun localTitleOwnerQualification(preparation: LocalEditPreparation, format: AdapterFormat,
        port: CalendarTitleUpdateSubmissionPort, expected: Long, policy: Long,
        systemTitle: Boolean = false, admission: (CalendarScope) -> Boolean = { true }): LocalTitleOwnerQualification {
        val target = preparation.intent.scope
        return LocalTitleOwnerQualification(preparation, format, port, store, projection,
            currentFormat = { remote.format },
            baseActive = { authenticated && generation == expected && createPolicyRevision.get() == policy &&
                systemCreatesEnabled(target) && hasCalendarPermissions() && admission(target) &&
                (!systemTitle || systemTitlesEnabled(target)) },
            currentAccount = { isCurrent(expected, target.account) },
            cachedAccount = { createAccount.get() == target.account },
            systemTitle = systemTitle,
            capturedHold = { capturedRawHold(target) },
            handoffSource = { frame, active -> handoffSource(frame, active) })
    }

    /** Prepare or recover reserved wire for the exact current native edit. Offline by default;
     * read-only preparation eligibility never grants claim, submission or acknowledgement.
     */
    suspend fun prepareLocalTitleAttempt(candidate: TitleUpdatePreparationReservation,
        journal: TitleUpdatePreparationJournal, port: CalendarTitleUpdateSubmissionPort,
        allowNetwork: Boolean = false, systemTitle: Boolean = false,
        admission: (CalendarScope) -> Boolean = { true }): LocalTitleAttemptPreparation {
        val frozen = candidate.frozen()
        return mutex.withLock {
            val target = frozen.scope
            val qualification = localTitleOwnerQualification(frozen.preparation, frozen.format, port,
                generation, createPolicyRevision.get(), systemTitle, admission)
            var refusal: LocalTitleAttemptRetentionResult? = null
            val lease = { scope: CalendarScope -> scope == target && refusal == null && qualification.active() }
            try {
                if (!qualification.available(formats = false)) return@withLock LocalTitleAttemptPreparation.Inactive
                val format = port.titleUpdateFormat ?: return@withLock LocalTitleAttemptPreparation.Unsupported
                if (format != frozen.format || remote.format != frozen.preparation.intent.format)
                    return@withLock LocalTitleAttemptPreparation.Unqualified
                store.admitTransaction = lease
                projection.admitOperation = { lease(target) }
                val durable = TitleUpdatePreparationRecovery(journal, port).prepare(frozen, allowNetwork, lease) {
                    // Once denied, this invocation cannot borrow a restored source or a new epoch.
                    if (refusal == null) refusal = qualification.sourceQualification()
                    refusal == null
                }
                currentCoroutineContext().ensureActive()
                refusal?.let { return@withLock titleAttemptPreparationRefusal(it) }
                if (!qualification.active()) return@withLock LocalTitleAttemptPreparation.Inactive
                // The helper has just requalified the source and read the canonical durable wire.
                // No new asynchronous observation may substitute an owner after that boundary.
                if (durable == null) LocalTitleAttemptPreparation.Unresolved
                else LocalTitleAttemptPreparation.Prepared(durable)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: LocalEditPreparationException) {
                  currentCoroutineContext().ensureActive()
                  if (!qualification.active()) LocalTitleAttemptPreparation.Inactive else when (failure.reason) {
                      LocalEditPreparationFailure.AUTHENTICATION, LocalEditPreparationFailure.PERMISSION -> LocalTitleAttemptPreparation.Inactive
                      LocalEditPreparationFailure.CONFLICT -> LocalTitleAttemptPreparation.Conflict
                      LocalEditPreparationFailure.UNSUPPORTED -> LocalTitleAttemptPreparation.Unsupported
                      else -> LocalTitleAttemptPreparation.Unavailable
                  }
              } catch (_: Exception) {
                  currentCoroutineContext().ensureActive()
                  if (!qualification.active()) LocalTitleAttemptPreparation.Inactive else LocalTitleAttemptPreparation.Unavailable
              } finally {
                  store.admitTransaction = { false }
                  projection.admitOperation = { false }
              }
        }
    }

    private fun titleAttemptPreparationRefusal(reason: LocalTitleAttemptRetentionResult) = when (reason) {
        LocalTitleAttemptRetentionResult.INACTIVE -> LocalTitleAttemptPreparation.Inactive
        LocalTitleAttemptRetentionResult.UNQUALIFIED -> LocalTitleAttemptPreparation.Unqualified
        LocalTitleAttemptRetentionResult.UNSUPPORTED -> LocalTitleAttemptPreparation.Unsupported
        LocalTitleAttemptRetentionResult.UNRESOLVED -> LocalTitleAttemptPreparation.Unresolved
        LocalTitleAttemptRetentionResult.CONFLICT -> LocalTitleAttemptPreparation.Conflict
        else -> LocalTitleAttemptPreparation.Unavailable
    }

    /** Bind already prepared wire to the exact current owner under one access epoch.
     * This local composition never prepares, claims, submits, inspects the service or writes
     * the Provider. Durable PREPARED work is not authority for a subsequent dispatch.
     */
    suspend fun retainLocalTitleAttempt(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
        port: CalendarTitleUpdateSubmissionPort): LocalTitleAttemptRetentionResult {
        val frozen = record.frozen()
        return mutex.withLock {
            val target = frozen.scope
            val intent = frozen.preparation.intent
            val qualification = localTitleOwnerQualification(frozen.preparation, frozen.format, port,
                generation, createPolicyRevision.get())
            val active = qualification.active
            suspend fun available(formats: Boolean = true) = qualification.available(formats)
            suspend fun sourceQualification() = qualification.sourceQualification()
            try {
                if (!available(formats = false)) return@withLock LocalTitleAttemptRetentionResult.INACTIVE
                if (frozen.state != TitleUpdateAttemptState.PREPARED || frozen.protectedEvidence != null)
                    return@withLock LocalTitleAttemptRetentionResult.UNQUALIFIED
                val format = port.titleUpdateFormat ?: return@withLock LocalTitleAttemptRetentionResult.UNSUPPORTED
                if (format != frozen.format || remote.format != intent.format)
                    return@withLock LocalTitleAttemptRetentionResult.UNQUALIFIED
                if (!available()) return@withLock LocalTitleAttemptRetentionResult.INACTIVE
                store.admitTransaction = { it == target && active() }
                projection.admitOperation = active
                val existing = journal.read(target, frozen.id)
                if (!available()) return@withLock LocalTitleAttemptRetentionResult.INACTIVE
                if (existing != null && existing.state != TitleUpdateAttemptState.PREPARED)
                    return@withLock LocalTitleAttemptRetentionResult.UNRESOLVED
                if (existing != null && existing != frozen) return@withLock LocalTitleAttemptRetentionResult.UNQUALIFIED
                sourceQualification()?.let { return@withLock it }
                // Exact repetitions still validate complete journal history inside this
                // guarded transaction; read()==record alone never qualifies HISTORICAL.
                val retained = journal.remember(frozen) { it == target && active() }
                if (!available()) return@withLock LocalTitleAttemptRetentionResult.INACTIVE
                if (!retained) return@withLock LocalTitleAttemptRetentionResult.UNRESOLVED
                val durable = journal.read(target, frozen.id)
                if (!available()) return@withLock LocalTitleAttemptRetentionResult.INACTIVE
                if (durable != frozen) return@withLock LocalTitleAttemptRetentionResult.UNRESOLVED
                // Independent databases and Provider operations have no distributed commit.
                // Preserve a committed wire but do not report current qualification if its
                // owner or native contents changed during retention/readback.
                sourceQualification()?.let { return@withLock it }
                if (!available()) return@withLock LocalTitleAttemptRetentionResult.INACTIVE
                if (existing == null) LocalTitleAttemptRetentionResult.RETAINED else LocalTitleAttemptRetentionResult.HISTORICAL
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) {
                  currentCoroutineContext().ensureActive()
                  if (!active()) LocalTitleAttemptRetentionResult.INACTIVE else LocalTitleAttemptRetentionResult.UNAVAILABLE
              } finally {
                  store.admitTransaction = { false }
                  projection.admitOperation = { false }
              }
        }
    }

    /** Explicit preparation of a captured title edit; never dispatches or acknowledges Android. */
    suspend fun prepareLocalTitle(target: CalendarScope, eventId: EventId, id: MutationId,
        timestamp: java.time.Instant, port: CalendarLocalEditPreparationPort): LocalTitlePreparationResult =
        mutex.withLock { prepareLocalTitleLocked(target, eventId, id, timestamp, port, generation, createPolicyRevision.get()) }

    private suspend fun prepareLocalTitleLocked(target: CalendarScope, eventId: EventId, id: MutationId,
        timestamp: java.time.Instant, port: CalendarLocalEditPreparationPort,
        expected: Long, policy: Long, systemTitle: Boolean = false,
        admission: (CalendarScope) -> Boolean = { true }): LocalTitlePreparationResult {
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() &&
            admission(target) && (!systemTitle || (systemCreatesEnabled(target) && systemTitlesEnabled(target))) }
        if (!isCurrent(expected, target.account) || !active()) return LocalTitlePreparationResult.INACTIVE
        store.admitTransaction = { it == target && active() }
        var captured: LocalEditIntent? = null
        return try {
            val selected = store.selections(target.account).singleOrNull { it.scope == target }
            if (selected?.enabled != true || !selected.available) return LocalTitlePreparationResult.INACTIVE
            val format = store.transaction(target) { checkpoint?.format }
            if (format != remote.format) return LocalTitlePreparationResult.UNQUALIFIED
            val owner = store.localEdit(target, eventId) ?: return LocalTitlePreparationResult.UNQUALIFIED
            if (owner.id != id || owner.format != format) return LocalTitlePreparationResult.UNQUALIFIED
            if (owner.state == LocalEditState.CONFLICT) return LocalTitlePreparationResult.CONFLICT
            val frozen = try {
                require(timestamp.nano == 0 && timestamp.atOffset(java.time.ZoneOffset.UTC).year in 1970..9999)
                LocalEditPreparation.freezeIntent(owner)
            } catch (_: Exception) { return LocalTitlePreparationResult.UNQUALIFIED }
            captured = frozen
            if (systemTitle) {
                projection.admitOperation = active
                if (!systemTitleSource(frozen, active)) return LocalTitlePreparationResult.UNSUPPORTED
            }
            val preparationFormat = port.localTitleFormat ?: return LocalTitlePreparationResult.UNSUPPORTED
            val admission = store.assessLocalTitlePreparation(frozen)
            if (admission != LocalTitlePreparationAdmission.ELIGIBLE) {
                if (!isCurrent(expected, target.account) || !active()) return LocalTitlePreparationResult.INACTIVE
                return titlePreparationResult(admission)
            }
            // No store/auth mutex is held while the adapter runs its serialized authenticated reads.
            val prepared = if (systemTitle) port.prepareLocalTitle(frozen, timestamp, { it == target && active() })
                else port.prepareLocalTitle(frozen, timestamp)
            currentCoroutineContext().ensureActive()
            if (prepared == null) return if (active()) LocalTitlePreparationResult.UNAVAILABLE else LocalTitlePreparationResult.INACTIVE
            if (prepared.intent != frozen || prepared.timestamp != timestamp || prepared.format != preparationFormat ||
                port.localTitleFormat != preparationFormat || remote.format != format)
                return LocalTitlePreparationResult.UNQUALIFIED
            if (!isCurrent(expected, target.account) || !active()) return LocalTitlePreparationResult.INACTIVE
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
    suspend fun captureLocalDelete(target: CalendarScope, eventId: EventId, id: MutationId,
        beforeNewOwner: suspend () -> Unit = {}): LocalEditCaptureResult = mutex.withLock {
        val expected = generation
        val policy = createPolicyRevision.get()
        if (!isCurrent(expected, target.account) || !hasCalendarPermissions())
            return@withLock LocalEditCaptureResult.INACTIVE
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() }
        localChangeCapture(target, expected, active).delete(eventId, id, beforeNewOwner)
    }

    /** Preparation never renews its original epoch; only fresh durable staging records a send lease. */
    private fun localCreateStaging(target: CalendarScope, journal: LocalCreateStore, expected: Long, policy: Long) =
        LocalCreateStaging(target, store, projection, journal,
            currentFormat = { remote.format },
            active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() },
            currentAccount = { isCurrent(expected, target.account) },
            recordFreshSubmission = { attempt, retained ->
                if (creates != null) freshCapturedSubmissions[target to attempt] =
                    CapturedSubmissionLease(CapturedCreateDispatchIdentity.of(retained), expected, policy)
            })

    /** Explicit internal capture only; no preparation, submission or Android acknowledgement. */
    suspend fun captureLocalCreate(target: CalendarScope, eventRow: Long, localId: LocalEventId,
        id: MutationId, timestamp: java.time.Instant): LocalEditCaptureResult = mutex.withLock {
        val journal = localCreates ?: return@withLock LocalEditCaptureResult.UNAVAILABLE
        val expected = generation
        val policy = createPolicyRevision.get()
        if (!isCurrent(expected, target.account) || !hasCalendarPermissions())
            return@withLock LocalEditCaptureResult.INACTIVE
        localCreateStaging(target, journal, expected, policy).capture(eventRow, localId, id, timestamp)
    }

    /** Explicit read-only preparation; no outgoing journal, dispatch, or Provider acknowledgement. */
    suspend fun prepareLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        port: CalendarCreatePreparationPort): LocalCreatePreparationResult = mutex.withLock {
        val journal = localCreates ?: return@withLock LocalCreatePreparationResult.UNAVAILABLE
        val expected = generation
        val policy = createPolicyRevision.get()
        if (!isCurrent(expected, target.account) || !hasCalendarPermissions())
            return@withLock LocalCreatePreparationResult.INACTIVE
        localCreateStaging(target, journal, expected, policy).prepare(localId, id, port)
    }

    /** Explicit read-only staging only; no dispatch journal, upload or Provider acknowledgement. */
    suspend fun stageLocalCreateSubmission(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, port: CalendarCreateSubmissionPort): LocalCreateSubmissionResult = mutex.withLock {
        val journal = localCreates ?: return@withLock LocalCreateSubmissionResult.UNAVAILABLE
        ensureCreateLifecycle()
        val expected = generation
        val policy = createPolicyRevision.get()
        if (!isCurrent(expected, target.account) || !hasCalendarPermissions()) return@withLock LocalCreateSubmissionResult.INACTIVE
        localCreateStaging(target, journal, expected, policy).stageSubmission(localId, id, attempt, port)
    }

    /** Opt-in automatic path requalifies the same unsent payload; UNKNOWN remains inspect-only. */
    suspend fun resumePreparedLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, port: CalendarCreateSubmissionPort): LocalCreateSubmissionResult = mutex.withLock {
        val captures = localCreates ?: return@withLock LocalCreateSubmissionResult.UNRESOLVED
        val journal = creates ?: return@withLock LocalCreateSubmissionResult.UNRESOLVED
        ensureCreateLifecycle()
        val expected = generation
        val policy = createPolicyRevision.get()
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy &&
            systemCreatesEnabled(target) && hasCalendarPermissions() }
        if (!active()) return@withLock LocalCreateSubmissionResult.INACTIVE
        val key = target to attempt
        var retainedLease: CapturedSubmissionLease? = null
        try {
            qualifyCreate(target, expected, policy)
            captures.admitCapture = { it == target && active() }
            store.admitTransaction = { it == target && active() }
            projection.admitOperation = active
            val submission = captures.readSubmission(target, localId) ?: return@withLock LocalCreateSubmissionResult.UNRESOLVED
            val captured = submission.reservation.intent.reservation
            if (captured.scope != target || captured.localId != localId || captured.id != id ||
                submission.reservation.attempt != attempt || captured.format != remote.format ||
                port.knownEmptySubmissionFormat != submission.reservation.format)
                return@withLock LocalCreateSubmissionResult.UNQUALIFIED
            val wanted = CapturedCreateDispatchIdentity.of(submission)
            val record = journal.read(target, attempt)
            if (record?.state == CreateAttemptState.OUTCOME_UNKNOWN) return@withLock LocalCreateSubmissionResult.UNRESOLVED
            suspend fun qualifies(): Boolean {
                if (!active() || !isCurrent(expected, target.account)) return false
                val selected = store.selections(target.account).singleOrNull { it.scope == target }
                if (selected?.enabled != true || !selected.available ||
                    store.transaction(target) { checkpoint?.format } != captured.format ||
                    captures.readSubmission(target, localId) != submission ||
                    port.knownEmptySubmissionFormat != submission.reservation.format) return false
                val row = projection.inspectStampedSimpleCreate(target, localId)
                return row?.content == submission.reservation.intent.content && active() &&
                    captures.readSubmission(target, localId) == submission &&
                    store.transaction(target) { checkpoint?.format } == captured.format
            }
            if (!qualifies()) return@withLock if (active()) LocalCreateSubmissionResult.UNQUALIFIED else LocalCreateSubmissionResult.INACTIVE
            val lease = CapturedSubmissionLease(wanted, expected, policy)
            retainedLease = lease
            freshCapturedSubmissions[key] = lease
            journal.admitDispatch = { it == target && active() && freshCapturedSubmissions[key] == lease }
            if (!journal.resumeCapturedPrepared(submission)) {
                freshCapturedSubmissions.remove(key, lease)
                return@withLock LocalCreateSubmissionResult.UNQUALIFIED
            }
            if (!qualifies()) {
                freshCapturedSubmissions.remove(key, lease)
                return@withLock if (active()) LocalCreateSubmissionResult.UNQUALIFIED else LocalCreateSubmissionResult.INACTIVE
            }
            LocalCreateSubmissionResult.RETAINED
        } catch (cancelled: CancellationException) {
            retainedLease?.let { freshCapturedSubmissions.remove(key, it) }
            throw cancelled
        } catch (_: Exception) {
            retainedLease?.let { freshCapturedSubmissions.remove(key, it) }
            if (active()) LocalCreateSubmissionResult.UNAVAILABLE else LocalCreateSubmissionResult.INACTIVE
        } finally {
            captures.admitCapture = { false }; journal.admitDispatch = { false }
            store.admitTransaction = { false }; projection.admitOperation = { false }
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

    /** Created under the import mutex after lifecycle admission; never renews an operation's epoch. */
    private fun capturedCreateRestoration(target: CalendarScope, captures: LocalCreateStore,
        journal: CreateAttemptStore): CapturedCreateRestoration {
        val expected = generation
        val policy = createPolicyRevision.get()
        return CapturedCreateRestoration(target, store, projection, captures, journal,
            currentFormat = { remote.format },
            active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() },
            currentAccount = { isCurrent(expected, target.account) })
    }

    /** Retain only the first authenticated captured identity. No original-row ACK or projection. */
    suspend fun bindLocalCreateObservation(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, port: CalendarCreateSubmissionPort? = null): CapturedCreateBindingResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateBindingResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateBindingResult.UNRESOLVED
        ensureCreateLifecycle()
        capturedCreateRestoration(target, captures, journal).bind(localId, id, attempt, port)
    }

    /** Original-row metadata only; the incoming hold is not released by this operation. */
    suspend fun attachLocalCreateBinding(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateAttachmentResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateAttachmentResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateAttachmentResult.UNRESOLVED
        ensureCreateLifecycle()
        capturedCreateRestoration(target, captures, journal).attach(localId, id, attempt)
    }

    /** Retain the verified sent baseline only. Never clean the original row or release its hold. */
    suspend fun retainLocalCreateBaseline(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateBaselineResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateBaselineResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateBaselineResult.UNRESOLVED
        ensureCreateLifecycle()
        capturedCreateRestoration(target, captures, journal).retainBaseline(localId, id, attempt)
    }

    /** Explicit one-shot clean acknowledgement of an unchanged captured original.
     * ACKNOWLEDGED proves a historical batch only; incoming holds and remote evidence remain.
     */
    suspend fun acknowledgeLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateAcknowledgementResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateAcknowledgementResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateAcknowledgementResult.UNRESOLVED
        ensureCreateLifecycle()
        capturedCreateRestoration(target, captures, journal).acknowledge(localId, id, attempt)
    }

    /** Explicit offline point-in-time qualification; no ACK, durable clean state or hold release. */
    suspend fun qualifyAcknowledgedLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateCleanResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateCleanResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateCleanResult.UNRESOLVED
        ensureCreateLifecycle()
        capturedCreateRestoration(target, captures, journal).qualifyClean(localId, id, attempt)
    }

    /** Snapshot and retain the current local intention only; no Provider ACK or hold release. */
    suspend fun reconcileLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, mutation: MutationId): CapturedCreateReconciliationResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
        ensureCreateLifecycle()
        capturedCreateRestoration(target, captures, journal).reconcile(localId, id, attempt, mutation)
    }

    /** The caller supplies its original epoch and phase-specific consent predicate. */
    private fun capturedCreateHandoff(target: CalendarScope, captures: LocalCreateStore,
        journal: CreateAttemptStore, expected: Long, active: () -> Boolean) =
        CapturedCreateHandoff(target, store, projection, captures, journal, scheduler,
            currentFormat = { remote.format }, active = active,
            currentAccount = { account -> isCurrent(expected, account) },
            source = { frame -> handoffSource(frame, active) })

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
        return capturedCreateHandoff(target, captures, journal, expected, active).transfer(localId, id, attempt)
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


    /** Retain a later dirty intention against the current reference, never the original sent base.
     * Automatic observations reuse an existing durable owner, or allocate a distinct new UUID.
     * Explicit caller identities retain their original strict matching behavior.
     */
    suspend fun reconcileHandoffLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, mutation: MutationId? = null,
        beforeNewOwner: suspend () -> Unit = {}): CapturedCreateReconciliationResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
        ensureCreateLifecycle()
        val expected = generation
        val policy = createPolicyRevision.get()
        val active = { authenticated && generation == expected && createPolicyRevision.get() == policy && hasCalendarPermissions() &&
            (mutation != null || systemCreatesEnabled(target)) }
        capturedCreateHandoff(target, captures, journal, expected, active).reconcile(localId, id, attempt, mutation, beforeNewOwner)
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
        val locallyHeld = store.heldProjectionCount(target) > 0
        val visible = when {
            held -> "CREATE_PENDING"
            locallyHeld && status in setOf("IMPORTED", "PARTIAL", "SERIES_PENDING", "HISTORY_GAP") -> "INCOMPLETE"
            else -> status
        }
        store.status(target, visible, if (held || locallyHeld) null else completedAt)
    }
    private suspend fun requireProjectionAvailable(target: CalendarScope, eventId: EventId) {
        if (store.transaction(target) { hasPendingMutation(eventId) }) throw CapturedProjectionPending()
        if (capturedProjectionHold(target).holds(eventId)) throw CapturedProjectionPending()
    }
    private suspend fun runnableProjections(target: CalendarScope, limit: Int = 100): List<ProjectionIntent> {
        val hold = capturedProjectionHold(target)
        return if (hold.scopeBlocked) emptyList() else store.pending(target, limit, hold.events)
    }
    private suspend fun nativeChangesBlock(intent: ProjectionIntent): Boolean = projection.hasOwnedLocalChanges(intent,
        if (intent.change is RemoteChange.Deleted) store.deletedSeriesUid(intent.scope, intent.change.eventId) else null)

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
        store.requireDirectProjection(receipt.scope, receipt.change.eventId)
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
        store.requireDirectProjection(intent.scope, intent.change.eventId)
        val outstanding = store.projectionReceipt(intent.scope, intent.change.eventId)
        var representation: ProjectedEventReference? = null
        val recovered = outstanding?.let { recoverProjection(expected, it) } ?: false
        // A historical receipt can be recovered without consuming newer native edits. Once
        // inspected, an unsupported local change holds only this event, including old receipts.
        if (nativeChangesBlock(intent))
            throw CalendarProjectionException(CalendarProjectionFailure.LOCAL_CHANGE_PENDING)
        if (recovered && outstanding?.change == intent.change) return
        if (outstanding != null) {
            if (!recovered && (outstanding.change != intent.change || outstanding.format != remote.format ||
                outstanding.protocol != projection.projectionReceiptProtocol)) {
                throw CalendarProjectionException(CalendarProjectionFailure.PROVIDER_REJECTED)
            }
            if (!recovered) representation = outstanding.representation
        }
        requireProjectionAvailable(intent.scope, intent.change.eventId)
        if (projection.projectionReceiptProtocol == 1 && ProjectionReceipt.supports(intent.change)) {
            // Preserve an existing explicit direct preparation rather than substituting NULL.
            val prepared = store.prepareProjection(intent, remote.format, representation)
            store.requireDirectProjection(intent.scope, intent.change.eventId)
            val witness = projection.applyTracked(prepared)
            check(witness.receipt == prepared) { "Projection witness differs" }
            if (!isCurrent(expected, intent.scope.account)) throw CancellationException("Inactive projection acknowledgement")
            requireProjectionAvailable(intent.scope, intent.change.eventId)
            check(store.restoreProjection(witness)) { "Projection acknowledgement remains unresolved" }
        } else {
            store.requireDirectProjection(intent.scope, intent.change.eventId)
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

    private suspend fun bootstrapInitialPage(target: CalendarScope, allowNetwork: Boolean,
        deltaReady: Boolean): ImportProgress {
        val engine = stagedEngine ?: return ImportProgress(0, false)
        if (!deltaReady || store.transaction(target) { checkpoint != null }) return ImportProgress(0, false)
        return engine.run(target, allowNetwork = allowNetwork, maxPages = 1)
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
                when (runCalendar(selection, calendarScope, account, expected, network, fresh)) {
                    null -> return ImportRunResult.COMPLETE
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
        return when { retry -> ImportRunResult.RETRY; continuation -> ImportRunResult.CONTINUE; else -> ImportRunResult.COMPLETE }
    }

    /** One calendar pass; null preserves an inactive epoch's early exit from the entire run.
     * Keep its suspensions and status handlers outside the run's diagnostic finally. */
    private suspend fun runCalendar(selection: ImportStore.Selection, calendarScope: CalendarScope,
        account: AccountId, expected: Long, network: Boolean, fresh: Boolean): ImportRunResult? {
        var retry = false
        var continuation = false
        try {
            store.status(calendarScope, "RUNNING")
            if (!isCurrent(expected, account)) return null
            val createsMode = systemCreatesEnabled(calendarScope)
            val accessPolicy = createPolicyRevision.get()
            projection.admitOperation = { authenticated && generation == expected &&
                createPolicyRevision.get() == accessPolicy && systemCreatesEnabled(calendarScope) == createsMode }
            val ensuredCalendar = try {
                if (createsMode) projection.ensureCalendarState(calendarScope, selection.summary, true)
                else projection.ensureCalendarState(calendarScope, selection.summary)
            } finally { projection.admitOperation = { authenticated && generation == expected } }
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
            // A full refresh clears the old checkpoint before requeuing native work.
            // Commit its first staged page before draining: a DIRTY row may reject
            // projection, and capture needs a confirmed observation format to proceed.
            // Offline this only decodes an already retained page; it never invents one.
            val bootstrap = bootstrapInitialPage(calendarScope, allowNetwork, deltaReady)
            if (!isCurrent(expected, account)) return null
            suspend fun drain(): Boolean {
                for (intent in runnableProjections(calendarScope)) {
                    if (!isCurrent(expected, account)) throw CancellationException()
                    try { projectPending(intent, expected) }
                    catch (failure: CalendarProjectionException) {
                        val racedLocalChange = failure.category in setOf(CalendarProjectionFailure.PROVIDER_REJECTED,
                            CalendarProjectionFailure.IDENTITY_CONFLICT) && nativeChangesBlock(intent)
                        if (failure.category != CalendarProjectionFailure.LOCAL_CHANGE_PENDING && !racedLocalChange) throw failure
                        // Retain the exact intent and receipt; a fresh observation may
                        // rearm it, but this continuation must let other events progress.
                        store.holdLocalProjection(intent)
                    }
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
                if (!isCurrent(expected, account)) return null
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
        return when { retry -> ImportRunResult.RETRY; continuation -> ImportRunResult.CONTINUE; else -> ImportRunResult.COMPLETE }
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
            if (network && authenticated && runFailure == null) enqueueLocal()
        }
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
