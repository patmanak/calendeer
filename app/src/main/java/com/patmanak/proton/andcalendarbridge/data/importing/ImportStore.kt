package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.requeueSupported
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.checkStagingCapacity
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.requireKnown
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.supportedLocalTextIntent
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readLocalTextEditPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.supportedLocalTimeIntent
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readLocalTimeEditPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.supportedPersonalIntent
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readPersonalEditPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readLocalEditPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readTextUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.encodeTextUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readTimeUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.encodeTimeUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readPersonalUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.encodePersonalUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readTitleUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.encodeTitleUpdateAcknowledgement
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.encodeChange
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.key
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.requirePreparationSnapshotBudget
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** App-private, backup-excluded durable import queue. Every page and checkpoint commits together. */
enum class LocalTitlePreparationAdmission { ELIGIBLE, STORED, HISTORICAL, INACTIVE, UNQUALIFIED, CONFLICT }
enum class LocalTextPreparationAdmission { ELIGIBLE, STORED, HISTORICAL, INACTIVE, UNQUALIFIED, CONFLICT }
enum class LocalTimePreparationAdmission { ELIGIBLE, STORED, HISTORICAL, INACTIVE, UNQUALIFIED, CONFLICT }
enum class PersonalPreparationAdmission { ELIGIBLE, STORED, HISTORICAL, INACTIVE, UNQUALIFIED, CONFLICT }

class ImportStore(context: Context, name: String = "calendar-import-v1.db") :
    SQLiteOpenHelper(context, name, null, 26), SyncStore {
    private val lock = Mutex()
    /** Derived read-only status; never changes an intent, capsule or acknowledgement. */
    suspend fun hasUnsupportedLegacyUpdates(scope: CalendarScope): Boolean {
        val operation = currentCoroutineContext()
        return access { db ->
            val legacy = com.patmanak.proton.andcalendarbridge.data.outgoing.LegacyUpdateCompatibility
            legacy.hasRetainedCapsules(db, scope, operation::ensureActive) || legacy.hasUnsupportedIntent(db, key(scope),
                { event -> ImportStoreLocalPreparations.readLocalPreparationOwner(db, scope, event) }, operation::ensureActive)
        }
    }
    private val localMutations = ImportStoreLocalMutationSql(::conflicts)
    private val calendar = ImportStoreCalendarSql(localMutations)
    private val projection = ImportStoreProjectionSql(localMutations) { target -> admitTransaction(target) }
    private val preparation = ImportStorePreparationSql(::conflicts, ::localTextPreparationAdmission,
        ::localTimePreparationAdmission, ::personalPreparationAdmission, ::localTitlePreparationAdmission)
    private val updates = ImportStoreUpdateSql(localMutations, ::localTextPreparationAdmission,
        ::localTimePreparationAdmission, ::personalPreparationAdmission, ::localTitlePreparationAdmission)
    /** Check the run lease both before its transaction and before durable commit. */
    @Volatile var admitTransaction: (CalendarScope) -> Boolean = { true }
    override fun onConfigure(db: SQLiteDatabase) {
        db.execSQL("PRAGMA synchronous=FULL")
    }
    override fun onCreate(db: SQLiteDatabase) = ImportSchema.create(db)
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        ImportSchema.upgrade(db, oldVersion, newVersion, ::checkStagingCapacity)
    private fun conflicts(db: SQLiteDatabase) = ConflictArchiveSql(db) { checkStagingCapacity(db) }

    /** The store retains admission/transaction ownership for historical reads. */
    suspend fun conflictArchives(query: ConflictArchiveQuery): ConflictArchivePage = stagingMutation(query.scope) { db ->
        conflicts(db).archives(query)
    }
    suspend fun conflictArchive(ref: ConflictArchiveRef): ConflictArchiveDetail? = stagingMutation(ref.scope) { db ->
        conflicts(db).archive(ref)
    }
    suspend fun conflictObservations(query: ConflictObservationQuery): ConflictObservationPage = stagingMutation(query.ref.scope) { db ->
        conflicts(db).observations(query)
    }

    data class Selection(val scope: CalendarScope, val summary: CalendarSummary, val enabled: Boolean,
        val status: String, val completedAt: Long?, val nextAttempt: Long = 0, val deferredCount: Int = 0,
        val unverifiedCount: Int = 0, val available: Boolean = true, val historyGap: Boolean = false,
        val coverage: CoverageRun? = null, val pendingProjectionCount: Int = 0,
        val retainedLocalChangeCount: Int = 0, val localConflictCount: Int = 0) {
        init { require(retainedLocalChangeCount >= 0 && localConflictCount in 0..retainedLocalChangeCount) }
        override fun toString() = "ImportSelection(REDACTED)"
    }

    suspend fun remember(account: AccountId, calendars: List<CalendarSummary>) = access { db ->
        db.beginTransaction()
        try {
            calendar.remember(db, account, calendars)
            checkStagingCapacity(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Local metadata only, including inactive accounts, for closing retained mirror access. */
    suspend fun calendarScopes(): List<CalendarScope> = access { db ->
        calendar.calendarScopes(db)
    }

    suspend fun queueNativeRemovals(): Unit = access { calendar.queueNativeRemovals(it) }
    suspend fun pendingNativeRemovals(): List<CalendarScope> = access { calendar.pendingNativeRemovals(it) }
    suspend fun completeNativeRemoval(scope: CalendarScope): Unit = access { calendar.completeNativeRemoval(it, scope) }

    suspend fun selections(account: AccountId): List<Selection> = access { db ->
        calendar.selections(db, account)
    }
    suspend fun deferNetwork(scope: CalendarScope, milliseconds: Long) = access { db ->
        calendar.deferNetwork(db, scope, milliseconds)
    }

    suspend fun setEnabled(scope: CalendarScope, enabled: Boolean) = access { db ->
        calendar.setEnabled(db, scope, enabled)
    }
    suspend fun status(scope: CalendarScope, status: String, completedAt: Long? = null) = access { db ->
        calendar.status(db, scope, status, completedAt)
    }

    /** Admit local mapping semantics without discarding any received or unresolved remote evidence.
     * A changed mapper must observe the old horizon again before rolling coverage can be reused.
     */
    suspend fun ensureMappingProfile(scope: CalendarScope, format: AdapterFormat): Unit = access { db ->
        check(admitTransaction(scope)) { "Inactive import lease" }
        db.beginTransaction()
        try {
            calendar.ensureMappingProfile(db, scope, format)
            checkStagingCapacity(db)
            check(admitTransaction(scope)) { "Inactive import lease" }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    /** Remains armed while an older traversal/receipt finishes, even after process restart. */
    suspend fun mappingRescanPending(scope: CalendarScope): Boolean = access { db ->
        ImportStorePageState.mappingRescanPending(db, scope)
    }

    /** Call only under the shared account executor. Restart a completed traversal, never a pending one. */
    suspend fun beginRefresh(scope: CalendarScope, planned: PageCheckpoint? = null,
        expectedCoverage: CoverageLedger? = null): Unit = access { db ->
        check(admitTransaction(scope)) { "Inactive import lease" }
        db.beginTransaction()
        try {
            calendar.beginRefresh(db, scope, planned, expectedCoverage)
            checkStagingCapacity(db)
            check(admitTransaction(scope)) { "Inactive import lease" }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    suspend fun requeueAll(scope: CalendarScope) = access { db ->
        requeueSupported(db, scope)
    }
    /** Admit a same-horizon poll durably without starting a new baseline traversal. */
    suspend fun beginDeltaRefresh(scope: CalendarScope, expectedCoverage: CoverageLedger): Unit = access { db ->
        check(admitTransaction(scope)) { "Inactive import lease" }
        db.beginTransaction()
        try {
            calendar.beginDeltaRefresh(db, scope, expectedCoverage)
            checkStagingCapacity(db)
            check(admitTransaction(scope)) { "Inactive import lease" }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    suspend fun complete(scope: CalendarScope) = access { db ->
        ImportStorePageState.complete(db, scope)
    }
    /** Preserve an exact pending intent while a local Provider edit prevents application.
     * State 2 is still durable pending work, not an acknowledgement. Fresh refresh rearms it;
     * continuations skip it so an unsupported edit cannot starve other events or busy-loop. */
    suspend fun holdLocalProjection(intent: ProjectionIntent): Boolean = stagingMutation(intent.scope) { db ->
        ImportStorePageState.holdLocalProjection(db, intent)
    }
    suspend fun heldProjectionCount(scope: CalendarScope): Int = access { db ->
        ImportStorePageState.heldProjectionCount(db, scope)
    }
    /** Excluded identities remain pending. Filter before the batch limit to avoid starvation. */
    suspend fun pending(scope: CalendarScope, limit: Int = 100, excludedEvents: Set<EventId> = emptySet()): List<ProjectionIntent> = access { db ->
        ImportStorePageState.pending(db, scope, limit, excludedEvents)
    }
    /** Missing or incompatible parents remain visible work without blocking their later pages. */
    suspend fun blockedSeriesCount(scope: CalendarScope): Int = access { db ->
        ImportStorePageState.blockedSeriesCount(db, scope)
    }
    suspend fun deletedSeriesUid(scope: CalendarScope, eventId: EventId): String? = access { db ->
        ImportStorePageState.deletedSeriesUid(db, scope, eventId)
    }
    /** Latest imported stable snapshot for identity reconciliation, never the earlier CREATE content.
     * Deferred/pending/deleted work cannot prove a settled Android projection. No writes occur here.
     */
    suspend fun stabilizedProjection(scope: CalendarScope, eventId: EventId): RemoteChange.Upsert? = access { db ->
        projection.stabilizedProjection(db, scope, eventId)
    }

    /** CAS acknowledgement: a newer pending value cannot be consumed by an old provider write. */
    suspend fun acknowledge(intent: ProjectionIntent): Int = stagingMutation(intent.scope) { db ->
        localMutations.acknowledge(db, intent)
    }

    /** Bounded canonical ID metadata only; native keys must never be treated as remote IDs.
     * Uses the existing (scope,event) primary key, without reading or decoding event payloads.
     */
    suspend fun projectedEventIds(scope: CalendarScope, afterEvent: EventId? = null, limit: Int = 100): List<EventId> = access { db ->
        ImportStorePageState.projectedEventIds(db, scope, afterEvent, limit) { target -> admitTransaction(target) }
    }

    /** Last successful projection acknowledgement, independent of newer imported remote evidence. */
    suspend fun projectedBaseline(scope: CalendarScope, eventId: EventId): RemoteChange.Upsert? = access { db ->
        projection.projectedBaseline(db, scope, eventId)
    }

    /** Saved applied reference, retained after receipt removal; never recalculates finite dates. */
    suspend fun projectedReference(scope: CalendarScope, eventId: EventId): ProjectedEventReference? = access { db ->
        projection.projectedReference(db, scope, eventId)
    }

    /** Admission observation only, never a lease across SQL and Provider transactions.
     * Ordinary projection cannot inspect or replace a saved finite representation.
     * Decode every current/previous tag before granting the observation; unknown
     * metadata remains untouched. Legacy NULL and explicit direct are both known.
     */
    suspend fun requireDirectProjection(scope: CalendarScope, eventId: EventId): Unit = access { db ->
        projection.requireDirectProjection(db, scope, eventId)
    }

    /** Seed the first sent reference behind exact original-row metadata proof, never a Provider ACK.
     * Existing generic bases cannot be adopted. Exact replay preserves later local intentions and
     * all different/deferred incoming evidence; neither absent records nor new remote data are invented.
     */
    suspend fun retainCapturedBaseline(baseline: CapturedCreateBaseline,
        witness: AppliedCapturedCreateAttachment): Boolean {
        if (witness.receipt != baseline.receipt) return false
        return stagingMutation(baseline.scope) { db ->
            projection.retainCapturedBaseline(db, baseline)
        }
    }

    /** Historical provenance with a matching current base, never ACK/release permission.
     * Callers must compare the full result with their current source journals and Provider witness;
     * structurally valid storage is not authentication. An advanced base is never rewound here.
     */
    suspend fun capturedBaseline(scope: CalendarScope, event: EventId): CapturedCreateBaseline? = access { db ->
        projection.capturedBaseline(db, scope, event)
    }

    /** Reserve the only volatile admission for a conditional original-row acknowledgement.
     * Existing reservations are inspection-only, including after a crash or a different proposed nonce.
     * No incoming data, sent reference, local intent or Provider row is acknowledged here.
     */
    suspend fun reserveCapturedAcknowledgement(proposed: CapturedCreateAcknowledgementReceipt):
        CapturedCreateAcknowledgementReservation = stagingMutation(proposed.baseline.scope) { db ->
        projection.reserveCapturedAcknowledgement(db, proposed)
    }

    /** Historical proof under the current exact source, never a clean-row or write permission.
     * Raw malformed/orphaned reservations must throw: they cannot reopen an admission as absence.
     */
    suspend fun capturedAcknowledgement(scope: CalendarScope, event: EventId): CapturedCreateAcknowledgementRecord? = access { db ->
        projection.capturedAcknowledgement(db, scope, event)
    }

    /** Point-in-time SQL source qualification only; never current Provider cleanliness or hold release.
     * Full sent reference and PROVEN receipt must agree, with no raw outstanding local work.
     */
    suspend fun capturedCleanSource(receipt: CapturedCreateAcknowledgementReceipt): Boolean =
        stagingMutation(receipt.baseline.scope) { db ->
        projection.capturedCleanSource(db, receipt)
    }

    /** Retain the exact historical Provider batch proof without consuming any import or local work. */
    suspend fun retainCapturedAcknowledgement(witness: AppliedCapturedCreateAcknowledgement): CapturedCreateAcknowledgementRecord =
        stagingMutation(witness.receipt.baseline.scope) { db ->
        projection.retainCapturedAcknowledgement(db, witness)
    }

    /** Generic snapshots carry no captured provenance and cannot adopt captured work, even on replay.
     * Raw presence fences malformed/orphaned rows and historical PROVEN acknowledgements alike.
     */
    /** Retain the first supported local edit/tombstone behind its exact captured sent reference.
     * Only local_edits is changed. Incoming records, raw pending flags, bases and receipts survive.
     * Null means unchanged with no retained local intent; it grants no future clean acknowledgement.
     */
    suspend fun retainCapturedLocalChange(snapshot: CapturedCreateCurrent, mutation: MutationId): LocalEditState? =
        stagingMutation(snapshot.baseline.scope) { db ->
        ImportStoreCapturedSql.retainCapturedLocalChange(db, snapshot, mutation, ::conflicts)
    }

    /** Raw provenance is a fence even if a journal row is orphaned or unreadable. */
    suspend fun capturedProvenanceEvents(scope: CalendarScope): Set<EventId> = access { db ->
        projection.capturedProvenanceEvents(db, scope)
    }

    suspend fun capturedHandoff(scope: CalendarScope, event: EventId): CapturedCreateHandoffRecord? = access { db ->
        projection.capturedHandoff(db, scope, event)
    }

    /** Reservation transfers no data and grants no future permission to overwrite Android. */
    suspend fun reserveCapturedHandoff(receipt: CapturedCreateHandoffReceipt): CapturedCreateHandoffRecord =
        stagingMutation(receipt.scope) { db ->
        ImportStoreCapturedSql.reserveCapturedHandoff(db, receipt)
    }

    suspend fun retainCapturedHandoff(receipt: CapturedCreateHandoffReceipt,
        witness: CapturedCreateCleanWitness): CapturedCreateHandoffRecord = stagingMutation(receipt.scope) { db ->
        ImportStoreCapturedSql.retainCapturedHandoff(db, receipt, witness)
    }

    /** Current pending content is read afresh after receipt recovery; stale intents cannot prepare. */
    suspend fun capturedHandoffPending(frame: CapturedCreateHandoffFrame): ProjectionIntent? = access { db ->
        projection.capturedHandoffPending(db, frame)
    }

    suspend fun capturedHandoffCanProject(frame: CapturedCreateHandoffFrame): Boolean = stagingMutation(frame.scope) { db ->
        ImportStoreCapturedSql.capturedHandoffCanProject(db, frame)
    }

    /** Historical prepared proof is recoverable even when the newest pending value differs. */
    suspend fun capturedHandoffProjection(frame: CapturedCreateHandoffFrame): CapturedHandoffProjection? = access { db ->
        projection.capturedHandoffProjection(db, frame)
    }

    suspend fun prepareCapturedHandoffProjection(frame: CapturedCreateHandoffFrame,
        intent: ProjectionIntent): CapturedHandoffProjection = stagingMutation(frame.scope) { db ->
        ImportStoreCapturedSql.prepareCapturedHandoffProjection(db, frame, intent)
    }

    /** Restore only the exact historical write. A committed replay never consumes requeued work. */
    suspend fun restoreCapturedHandoffProjection(witness: AppliedCapturedHandoffProjection): Boolean =
        stagingMutation(witness.plan.frame.scope) { db ->
        projection.restoreCapturedHandoffProjection(db, witness)
    }

    suspend fun waitCapturedHandoff(frame: CapturedCreateHandoffFrame): Boolean = stagingMutation(frame.scope) { db ->
        ImportStoreCapturedSql.waitCapturedHandoff(db, frame)
    }

    suspend fun resumeCapturedHandoff(witness: CapturedHandoffCleanWitness): Boolean = stagingMutation(witness.frame.scope) { db ->
        ImportStoreCapturedSql.resumeCapturedHandoff(db, witness)
    }

    /** Captures only a current full frame. Neither the origin nor any incoming queue is rewritten. */
    suspend fun retainCapturedHandoffLocalChange(snapshot: CapturedHandoffCurrent, mutation: MutationId): LocalEditState? =
        stagingMutation(snapshot.frame.scope) { db ->
        ImportStoreCapturedSql.retainCapturedHandoffLocalChange(db, snapshot, mutation, ::conflicts)
    }

    /** Explicit series retention in the existing handoff transaction and local-edit profile. */
    suspend fun retainCapturedHandoffSeriesEdit(snapshot: CapturedHandoffCurrent, mutation: MutationId): LocalEditState? =
        stagingMutation(snapshot.frame.scope) { db ->
        ImportStoreCapturedSql.retainCapturedHandoffLocalChange(db, snapshot, mutation, ::conflicts, series = true)
    }

    /** Origin decoding deliberately does not compare projection_bases, which advances after transfer. */
    /** Persist before Provider apply. Preparation alone is never evidence that the row was written. */
    suspend fun prepareProjection(intent: ProjectionIntent, format: AdapterFormat,
        representation: ProjectedEventReference? = null): ProjectionReceipt {
        require(ProjectionReceipt.supports(intent.change)) { "Unsupported projection receipt content" }
        val candidate = ProjectionReceipt(intent.scope, format, intent.change as RemoteChange.Upsert,
            UUID.randomUUID().toString(), null, representation = representation).snapshot()
        return stagingMutation(candidate.scope) { db ->
            projection.prepareProjection(db, candidate)
        }
    }

    suspend fun projectionReceipt(scope: CalendarScope, eventId: EventId): ProjectionReceipt? = access { db ->
        projection.projectionReceipt(db, scope, eventId)
    }

    /** A qualified atomic Provider write can restore its baseline after a lost local acknowledgement.
     * Newer import evidence is retained. Neither this method nor preparation touches Provider flags.
     */
    suspend fun restoreProjection(witness: AppliedProjectionWitness): Boolean {
        val receipt = witness.receipt.snapshot()
        return stagingMutation(receipt.scope) { db ->
        projection.restoreProjection(db, receipt)
        }
    }
    suspend fun localEdit(scope: CalendarScope, eventId: EventId): LocalEditIntent? = access { db ->
        projection.localEdit(db, scope, eventId)
    }
    suspend fun retainUnsupportedBareUpdate(intent: LocalEditIntent): Boolean = guardedEditMutation(intent.scope) { db ->
        localMutations.retainUnsupportedBareUpdate(db, intent)
    }
    /** Bounded metadata index only. No captured contents, preparation payloads or write authority. */
    suspend fun retainedLocalChanges(query: RetainedLocalChangeQuery): RetainedLocalChangePage =
        stagingMutation(query.scope) { db ->
        localMutations.retainedLocalChanges(db, query)
    }

    /** Atomic historical SQL read, including paused/unavailable calendars. No preparation/upload admission. */
    suspend fun inspectLocalEdit(scope: CalendarScope, eventId: EventId, mutationId: MutationId): LocalEditReview? =
        stagingMutation(scope) { db ->
        localMutations.inspectLocalEdit(db, scope, eventId, mutationId)
    }

    /** Exact DELETE history only. A remote tombstone never consumes the local intention. */
    suspend fun inspectLocalDelete(scope: CalendarScope, eventId: EventId, mutationId: MutationId): LocalDeleteReview? =
        stagingMutation(scope) { db ->
        localMutations.inspectLocalDelete(db, scope, eventId, mutationId)
    }

    /** SQL observation only; does not inspect Android or refresh remote access. */
    suspend fun assessLocalTextPreparation(intent: LocalEditIntent): LocalTextPreparationAdmission {
        currentCoroutineContext().ensureActive()
        val frozen = supportedLocalTextIntent(intent) ?: return LocalTextPreparationAdmission.UNQUALIFIED
        return guardedEditMutation(frozen.scope) { db -> localTextPreparationAdmission(db, frozen) }
    }

    /** First admission rechecks the owner, checkpoint, baseline and remote in the insertion transaction. */
    suspend fun retainCurrentLocalTextPreparation(value: LocalTextEditPreparation): LocalTextPreparationAdmission {
        currentCoroutineContext().ensureActive()
        val frozen = LocalTextEditPreparation(value.intent, value.timestamp, value.format, value.protectedPayload)
        val base = encodeChange(frozen.intent.base).also(::requirePreparationSnapshotBudget)
        val local = encodeChange(frozen.intent.base.copy(content = frozen.intent.content)).also(::requirePreparationSnapshotBudget)
        return guardedEditMutation(frozen.intent.scope) { db ->
        preparation.retainCurrentLocalTextPreparation(db, frozen, base, local)
        }
    }
    /** Monotone exact-owner transition after a remote conflict; original snapshots/evidence stay intact. */
    suspend fun conflictLocalTextPreparation(intent: LocalEditIntent): LocalTextPreparationAdmission {
        currentCoroutineContext().ensureActive()
        val frozen = supportedLocalTextIntent(intent) ?: return LocalTextPreparationAdmission.UNQUALIFIED
        return guardedEditMutation(frozen.scope) { db ->
        preparation.conflictLocalTextPreparation(db, frozen)
        }
    }
    private fun localTextPreparationAdmission(db: SQLiteDatabase, intent: LocalEditIntent): LocalTextPreparationAdmission =
        ImportStoreLocalPreparations.localTextPreparationAdmission(db, intent, ::readBaseline)

    /** Reserve the immutable text receipt before the one fresh native text/metadata acknowledgement.
     * The coordinator owns verified-result admission; this transaction qualifies only its SQL source.
     */
    suspend fun prepareTextUpdateAcknowledgement(result: TextUpdateVerifiedResult,
        handoff: CapturedCreateHandoffFrame? = null): TextUpdateAcknowledgementReservation? {
        currentCoroutineContext().ensureActive()
        val frozen = result.snapshot()
        val proposed = TextUpdateAcknowledgementReceipt.of(frozen, UUID.randomUUID().toString(), handoff)
        val encoded = encodeTextUpdateAcknowledgement(proposed)
        return guardedEditMutation(proposed.intent.scope) { db ->
            updates.prepareTextUpdateAcknowledgement(db, frozen, proposed, encoded)
        }
    }

    /** Historical leased inspection, including inactive scopes; never permission for a native write. */
    suspend fun textUpdateAcknowledgement(scope: CalendarScope, event: EventId,
        attempt: MutationId? = null): TextUpdateAcknowledgementRecord? =
        guardedEditMutation(scope) { db -> readTextUpdateAcknowledgement(db, scope, event, attempt) }

    /** Close only the exact historically acknowledged owner. New remote/deferred work survives.
     * A RESTORED replay is read-only and cannot consume a subsequent intent or preparation.
     */
    suspend fun restoreTextUpdateAcknowledgement(witness: AppliedTextUpdateAcknowledgement): Boolean {
        currentCoroutineContext().ensureActive()
        val receipt = witness.receipt.snapshot()
        val intent = receipt.intent
        val scope = intent.scope
        val event = intent.base.eventId
        return guardedEditMutation(scope) { db ->
            updates.restoreTextUpdateAcknowledgement(db, receipt, intent, scope, event)
        }
    }

    /** Historical inspection only. Retained capsules never grant current submission authority. */
    suspend fun localTextEditPreparation(scope: CalendarScope, eventId: EventId): LocalTextEditPreparation? =
        guardedEditMutation(scope) { db -> readLocalTextEditPreparation(db, scope, eventId) }

    suspend fun assessLocalTimePreparation(intent: LocalEditIntent): LocalTimePreparationAdmission {
        currentCoroutineContext().ensureActive()
        val frozen = supportedLocalTimeIntent(intent) ?: return LocalTimePreparationAdmission.UNQUALIFIED
        return guardedEditMutation(frozen.scope) { db -> localTimePreparationAdmission(db, frozen) }
    }

    /** First admission rechecks the owner, checkpoint, baseline and remote in the insertion transaction. */
    suspend fun retainCurrentLocalTimePreparation(value: LocalTimeEditPreparation): LocalTimePreparationAdmission {
        currentCoroutineContext().ensureActive()
        val frozen = LocalTimeEditPreparation(value.intent, value.timestamp, value.format, value.protectedPayload)
        val base = encodeChange(frozen.intent.base).also(::requirePreparationSnapshotBudget)
        val local = encodeChange(frozen.intent.base.copy(content = frozen.intent.content)).also(::requirePreparationSnapshotBudget)
        return guardedEditMutation(frozen.intent.scope) { db ->
        preparation.retainCurrentLocalTimePreparation(db, frozen, base, local)
        }
    }
    /** Monotone exact-owner transition after a remote conflict; original snapshots/evidence stay intact. */
    suspend fun conflictLocalTimePreparation(intent: LocalEditIntent): LocalTimePreparationAdmission {
        currentCoroutineContext().ensureActive()
        val frozen = supportedLocalTimeIntent(intent) ?: return LocalTimePreparationAdmission.UNQUALIFIED
        return guardedEditMutation(frozen.scope) { db ->
        preparation.conflictLocalTimePreparation(db, frozen)
        }
    }
    private fun localTimePreparationAdmission(db: SQLiteDatabase, intent: LocalEditIntent): LocalTimePreparationAdmission =
        ImportStoreLocalPreparations.localTimePreparationAdmission(db, intent, ::readBaseline)

    /** Reserve the immutable text receipt before the one fresh native time/metadata acknowledgement.
     * The coordinator owns verified-result admission; this transaction qualifies only its SQL source.
     */
    suspend fun prepareTimeUpdateAcknowledgement(result: TimeUpdateVerifiedResult,
        handoff: CapturedCreateHandoffFrame? = null): TimeUpdateAcknowledgementReservation? {
        currentCoroutineContext().ensureActive()
        val frozen = result.snapshot()
        val proposed = TimeUpdateAcknowledgementReceipt.of(frozen, UUID.randomUUID().toString(), handoff)
        val encoded = encodeTimeUpdateAcknowledgement(proposed)
        return guardedEditMutation(proposed.intent.scope) { db ->
            updates.prepareTimeUpdateAcknowledgement(db, frozen, proposed, encoded)
        }
    }

    /** Historical leased inspection, including inactive scopes; never permission for a native write. */
    suspend fun timeUpdateAcknowledgement(scope: CalendarScope, event: EventId,
        attempt: MutationId? = null): TimeUpdateAcknowledgementRecord? =
        guardedEditMutation(scope) { db -> readTimeUpdateAcknowledgement(db, scope, event, attempt) }

    /** Close only the exact historically acknowledged owner. New remote/deferred work survives.
     * A RESTORED replay is read-only and cannot consume a subsequent intent or preparation.
     */
    suspend fun restoreTimeUpdateAcknowledgement(witness: AppliedTimeUpdateAcknowledgement): Boolean {
        currentCoroutineContext().ensureActive()
        val receipt = witness.receipt.snapshot()
        val intent = receipt.intent
        val scope = intent.scope
        val event = intent.base.eventId
        return guardedEditMutation(scope) { db ->
            updates.restoreTimeUpdateAcknowledgement(db, receipt, intent, scope, event)
        }
    }

    /** Historical inspection only. Retained capsules never grant current submission authority. */
    suspend fun localTimeEditPreparation(scope: CalendarScope, eventId: EventId): LocalTimeEditPreparation? =
        guardedEditMutation(scope) { db -> readLocalTimeEditPreparation(db, scope, eventId) }

    /** Admission is shared; protected capsule formats and histories remain family-specific. */
    suspend fun assessPersonalPreparation(intent: LocalEditIntent): PersonalPreparationAdmission {
        currentCoroutineContext().ensureActive()
        val frozen = supportedPersonalIntent(intent) ?: return PersonalPreparationAdmission.UNQUALIFIED
        return guardedEditMutation(frozen.scope) { db -> personalPreparationAdmission(db, frozen) }
    }

    /** First admission rechecks the owner, checkpoint, baseline and remote in the insertion transaction. */
    suspend fun retainCurrentPersonalPreparation(value: PersonalEditPreparation): PersonalPreparationAdmission {
        currentCoroutineContext().ensureActive()
        val frozen = PersonalEditPreparation(value.intent, value.timestamp, value.format, value.protectedPayload)
        val base = encodeChange(frozen.intent.base).also(::requirePreparationSnapshotBudget)
        val local = encodeChange(frozen.intent.base.copy(content = frozen.intent.content)).also(::requirePreparationSnapshotBudget)
        return guardedEditMutation(frozen.intent.scope) { db ->
        preparation.retainCurrentPersonalPreparation(db, frozen, base, local)
        }
    }
    /** Monotone exact-owner transition after a remote conflict; original snapshots/evidence stay intact. */
    suspend fun conflictPersonalPreparation(intent: LocalEditIntent): PersonalPreparationAdmission {
        currentCoroutineContext().ensureActive()
        val frozen = supportedPersonalIntent(intent) ?: return PersonalPreparationAdmission.UNQUALIFIED
        return guardedEditMutation(frozen.scope) { db ->
        preparation.conflictPersonalPreparation(db, frozen)
        }
    }
    private fun personalPreparationAdmission(db: SQLiteDatabase, intent: LocalEditIntent): PersonalPreparationAdmission =
        ImportStoreLocalPreparations.personalPreparationAdmission(db, intent, ::readBaseline)

    /** Reserve the immutable personal receipt before the one fresh native personal/metadata acknowledgement.
     * The coordinator owns verified-result admission; this transaction qualifies only its SQL source.
     */
    suspend fun preparePersonalUpdateAcknowledgement(result: PersonalUpdateVerifiedResult,
        handoff: CapturedCreateHandoffFrame? = null): PersonalUpdateAcknowledgementReservation? {
        currentCoroutineContext().ensureActive()
        val frozen = result.snapshot()
        val proposed = PersonalUpdateAcknowledgementReceipt.of(frozen, UUID.randomUUID().toString(), handoff)
        val encoded = encodePersonalUpdateAcknowledgement(proposed)
        return guardedEditMutation(proposed.intent.scope) { db ->
            updates.preparePersonalUpdateAcknowledgement(db, frozen, proposed, encoded)
        }
    }

    /** Historical leased inspection, including inactive scopes; never permission for a native write. */
    suspend fun personalUpdateAcknowledgement(scope: CalendarScope, event: EventId,
        attempt: MutationId? = null): PersonalUpdateAcknowledgementRecord? =
        guardedEditMutation(scope) { db -> readPersonalUpdateAcknowledgement(db, scope, event, attempt) }

    /** Close only the exact historically acknowledged owner. New remote/deferred work survives.
     * A RESTORED replay is read-only and cannot consume a subsequent intent or preparation.
     */
    suspend fun restorePersonalUpdateAcknowledgement(witness: AppliedPersonalUpdateAcknowledgement): Boolean {
        currentCoroutineContext().ensureActive()
        val receipt = witness.receipt.snapshot()
        val intent = receipt.intent
        val scope = intent.scope
        val event = intent.base.eventId
        return guardedEditMutation(scope) { db ->
            updates.restorePersonalUpdateAcknowledgement(db, receipt, intent, scope, event)
        }
    }

    /** Historical inspection only. Retained capsules never grant current submission authority. */
    suspend fun personalEditPreparation(scope: CalendarScope, eventId: EventId): PersonalEditPreparation? =
        guardedEditMutation(scope) { db -> readPersonalEditPreparation(db, scope, eventId) }

    /** Admission is shared; protected capsule formats and histories remain family-specific. */

    /** Text storage checks cancellation around even callbacks that cancel and then return or throw. */
    private suspend fun <T> guardedEditMutation(scope: CalendarScope, action: (SQLiteDatabase) -> T): T {
        val coroutine = currentCoroutineContext()
        coroutine.ensureActive()
        return try {
            access { db ->
                coroutine.ensureActive()
                check(admitTransaction(scope)) { "Inactive import lease" }
                coroutine.ensureActive()
                db.beginTransaction()
                try {
                    requireKnown(db, scope)
                    val result = action(db)
                    check(admitTransaction(scope)) { "Inactive import lease" }
                    coroutine.ensureActive()
                    db.setTransactionSuccessful()
                    result
                } finally { db.endTransaction() }
            }
        } catch (failure: Exception) {
            coroutine.ensureActive()
            throw failure
        }
    }

    /** Freeze an exact captured owner and sealed fragments together, without dispatch admission.
     * Existing evidence is immutable even after its current owner becomes conflicted or paused.
     */
    suspend fun retainLocalEditPreparation(value: LocalEditPreparation): Boolean {
        // Snapshot caller-owned empty lists and serialized observations before the first suspension.
        val frozen = value.copy(intent = LocalEditPreparation.freezeIntent(value.intent))
        val intent = frozen.intent
        val base = encodeChange(intent.base).also(::requirePreparationSnapshotBudget)
        val local = encodeChange(intent.base.copy(content = intent.content)).also(::requirePreparationSnapshotBudget)
        return stagingMutation(intent.scope) { db ->
        preparation.retainLocalEditPreparation(db, frozen, intent, base, local)
        }
    }
    /** SQL observation only; does not inspect Android or refresh remote access. */
    suspend fun assessLocalTitlePreparation(intent: LocalEditIntent): LocalTitlePreparationAdmission =
        stagingMutation(intent.scope) { db -> localTitlePreparationAdmission(db, intent) }

    /** First admission rechecks the owner, checkpoint, baseline and remote in the insertion transaction. */
    suspend fun retainCurrentLocalTitlePreparation(value: LocalEditPreparation): LocalTitlePreparationAdmission {
        val frozen = value.copy(intent = LocalEditPreparation.freezeIntent(value.intent))
        val base = encodeChange(frozen.intent.base).also(::requirePreparationSnapshotBudget)
        val local = encodeChange(frozen.intent.base.copy(content = frozen.intent.content)).also(::requirePreparationSnapshotBudget)
        return stagingMutation(frozen.intent.scope) { db ->
        preparation.retainCurrentLocalTitlePreparation(db, frozen, base, local)
        }
    }
    /** Monotone exact-owner transition after a remote conflict; original snapshots/evidence stay intact. */
    suspend fun conflictLocalTitlePreparation(intent: LocalEditIntent): LocalTitlePreparationAdmission =
        stagingMutation(intent.scope) { db ->
        preparation.conflictLocalTitlePreparation(db, intent)
    }
    private fun localTitlePreparationAdmission(db: SQLiteDatabase, intent: LocalEditIntent): LocalTitlePreparationAdmission =
        ImportStoreLocalPreparations.localTitlePreparationAdmission(db, intent, ::readBaseline)

    /** Reserve the immutable title receipt before the one fresh native metadata write.
     * The coordinator owns verified-result admission; this transaction qualifies only its SQL source.
     */
    suspend fun prepareTitleUpdateAcknowledgement(result: TitleUpdateVerifiedResult,
        handoff: CapturedCreateHandoffFrame? = null): TitleUpdateAcknowledgementReservation? {
        val frozen = TitleUpdateVerifiedResult.of(result.record,
            TitleUpdateSubmissionObservation.Verified(result.attempt, result.event))
        val proposed = TitleUpdateAcknowledgementReceipt.of(frozen, UUID.randomUUID().toString(), handoff)
        val encoded = encodeTitleUpdateAcknowledgement(proposed)
        return stagingMutation(proposed.intent.scope) { db ->
            updates.prepareTitleUpdateAcknowledgement(db, frozen, proposed, encoded)
        }
    }

    /** Historical leased inspection, including inactive scopes; never permission for a native write. */
    suspend fun titleUpdateAcknowledgement(scope: CalendarScope, event: EventId,
        attempt: MutationId? = null): TitleUpdateAcknowledgementRecord? =
        stagingMutation(scope) { db -> readTitleUpdateAcknowledgement(db, scope, event, attempt) }

    /** Close only the exact historically acknowledged owner. New remote/deferred work survives.
     * A RESTORED replay is read-only and cannot consume a subsequent intent or preparation.
     */
    suspend fun restoreTitleUpdateAcknowledgement(witness: AppliedTitleUpdateAcknowledgement): Boolean {
        val receipt = witness.receipt.snapshot()
        val intent = receipt.intent
        val scope = intent.scope
        val event = intent.base.eventId
        return stagingMutation(scope) { db ->
            updates.restoreTitleUpdateAcknowledgement(db, receipt, intent, scope, event)
        }
    }

    /** Historical inspection only: a frozen PAUSED snapshot never resets the current conflict state. */
    suspend fun localEditPreparation(scope: CalendarScope, eventId: EventId): LocalEditPreparation? =
        stagingMutation(scope) { db -> readLocalEditPreparation(db, scope, eventId) }

    /** Decode the complete immutable binding before using even its completion state. */
    suspend fun deleteAcknowledgement(scope: CalendarScope, event: EventId, attempt: MutationId): DeleteAcknowledgementRecord? =
        stagingMutation(scope) { db -> localMutations.readDeleteAcknowledgement(db,scope,event)?.takeIf { it.receipt.attempt.id == attempt } }

    suspend fun deleteAcknowledgement(scope: CalendarScope, event: EventId): DeleteAcknowledgementRecord? =
        stagingMutation(scope) { db -> localMutations.readDeleteAcknowledgement(db,scope,event) }

    suspend fun qualifyLocalDelete(intent: LocalDeleteIntent): Boolean = stagingMutation(intent.scope) { db ->
        localMutations.qualifyLocalDelete(db,intent,true)
    }

    /** Reservation precedes network claim; it is never native acknowledgement authority. */
    suspend fun prepareDeleteAcknowledgement(record: DeleteAttemptRecord): DeleteAcknowledgementReservation? = stagingMutation(record.scope) { db ->
        localMutations.prepareDeleteAcknowledgement(db, record)
    }

    suspend fun restoreDeleteAcknowledgement(witness: AppliedDeleteAcknowledgement): Boolean = stagingMutation(witness.receipt.intent.scope) { db ->
        localMutations.restoreDeleteAcknowledgement(db, witness)
    }

    suspend fun conflictLocalDelete(intent: LocalDeleteIntent, observed: RemoteChange.Upsert): Boolean = stagingMutation(intent.scope) { db ->
        localMutations.conflictLocalDelete(db, intent, observed)
    }

    /** Caller has observed a changed native snapshot; do not fabricate remote evidence. */
    suspend fun conflictAcknowledgedDelete(intent: LocalDeleteIntent): Boolean = stagingMutation(intent.scope) { db ->
        localMutations.conflictAcknowledgedDelete(db, intent)
    }

    suspend fun localDelete(scope: CalendarScope, eventId: EventId): LocalDeleteIntent? = access { db ->
        projection.localDelete(db, scope, eventId)
    }
    /** Capture and projection suppression share one database transaction. No dispatch/reset exists. */
    suspend fun captureLocalEdit(intent: LocalEditIntent): LocalEditIntent {
        require(intent.seriesProfile == null) { "Series observation requires its explicit storage profile" }
        return stagingMutation(intent.scope) { db -> localMutations.captureLocalEdit(db, intent) }
    }

    /** Retention only; no family-membership proof, preparation or dispatch authority. */
    suspend fun captureSeriesEdit(intent: LocalEditIntent): LocalEditIntent {
        val frozen = SeriesEditCandidate(intent).intent
        return stagingMutation(frozen.scope) { db ->
            projection.requireDirectProjection(db, frozen.scope, frozen.base.eventId)
            localMutations.captureLocalEdit(db, frozen, series = true)
        }
    }

    /** Retain a qualified Android tombstone snapshot without consuming its flags or sending DELETE. */
    suspend fun captureLocalDelete(intent: LocalDeleteIntent): LocalDeleteIntent = stagingMutation(intent.scope) { db ->
        localMutations.captureLocalDelete(db, intent)
    }

    private fun readBaseline(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): RemoteChange.Upsert? =
        localMutations.readBaseline(db, scope, eventId)

    /** Stable keyset pagination: retries may remove rows without skipping subsequent issues. */
    suspend fun deferred(scope: CalendarScope, limit: Int = 4, afterEventId: EventId? = null): List<DeferredCalendarEvent> = access { db ->
        ImportStorePageState.deferred(db, scope, limit, afterEventId)
    }
    suspend fun deferredCount(scope: CalendarScope): Int = access { db ->
        ImportStorePageState.deferredCount(db, scope)
    }
    /** Explicit refresh schedules one bounded retry of every retained issue, including after restart. */
    suspend fun prepareDeferredRetry(scope: CalendarScope): Int = stagingMutation(scope) { db ->
        ImportStorePageState.prepareDeferredRetry(db, scope)
    }
    suspend fun pendingDeferredRetries(scope: CalendarScope, limit: Int = 4): List<DeferredCalendarEvent> = access { db ->
        ImportStorePageState.pendingDeferredRetries(db, scope, limit)
    }
    /** A stale local retry cannot acknowledge different retained evidence for the same event. */
    suspend fun acknowledgeDeferredRetry(scope: CalendarScope, event: DeferredCalendarEvent): Int = stagingMutation(scope) { db ->
        ImportStorePageState.acknowledgeDeferredRetry(db, scope, event)
    }
    private suspend fun <T> stagingMutation(scope: CalendarScope, action: (SQLiteDatabase) -> T): T {
        val coroutine = currentCoroutineContext()
        return access { db ->
            guardedImportTransaction(db, scope, coroutine, { admitTransaction(it) }, ::requireKnown, action)
        }
    }
    override suspend fun <T> transaction(scope: CalendarScope, block: SyncStore.Transaction.() -> T): T {
        return stagingMutation(scope) { db ->
            val transaction = ImportStorePageTransaction(db, scope, ::conflicts, localMutations::matchingDeleteReservation)
            val result = block(transaction)
            checkStagingCapacity(db)
            result
        }
    }

    private suspend fun <T> access(action: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { action(writableDatabase) }
    }
    companion object {
        const val STAGING_BUDGET_BYTES: Long = 32L * 1024 * 1024
    }
}
