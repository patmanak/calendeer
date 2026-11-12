package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.ContentValues
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.requireCapturedAcknowledgementSource
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.readCapturedAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.requireOrdinaryLocalCapture
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.hasCapturedBase
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.capturedFormatMatches
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.readCapturedBase
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.requireHandoffFrame
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.readHandoffPending
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.readHandoffProjectionReceipt
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.readCapturedHandoff
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.encodeHandoffProjection
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreCapturedSql.readProjectionReceipt
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.requeueSupported
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.countDeferred
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.countPending
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.countUnverified
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.readReceived
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.readCoverage
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.writeCoverage
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.hasReconciliation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.readDeltaCursor
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.checkStagingCapacity
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.requireKnown
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.hasLocalMutation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.titleAcknowledgementOwner
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.editAcknowledgementOwner
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.sameTextPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.supportedLocalTextIntent
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readLocalTextEditPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.sameTimePreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.supportedLocalTimeIntent
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readLocalTimeEditPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.samePersonalPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.supportedPersonalIntent
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readPersonalEditPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readLocalEditPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.hasForeignLocalPreparation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.readPreparationOwner
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.hasRawEventRow
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreLocalPreparations.activeProjectionScope
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readTextUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readTextUpdateAcknowledgementHistory
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.requireUnusedTextAcknowledgementIdentity
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.encodeTextUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readTimeUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readTimeUpdateAcknowledgementHistory
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.requireUnusedTimeAcknowledgementIdentity
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.encodeTimeUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readPersonalUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readPersonalUpdateAcknowledgementHistory
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.requireUnusedPersonalAcknowledgementIdentity
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.encodePersonalUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readTitleUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.readTitleUpdateAcknowledgementHistory
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.requireUnusedTitleAcknowledgementIdentity
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.encodeTitleUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStoreUpdateAcknowledgements.archiveTitleUpdateAcknowledgement
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodeChange
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodeCheckpoint
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.encodeChange
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.encodeCheckpoint
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
    SQLiteOpenHelper(context, name, null, 23), SyncStore {
    private val lock = Mutex()
    private val localMutations = ImportStoreLocalMutationSql(::conflicts)
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
            val present = calendars.map { it.id.value }.toSet()
            db.query("calendars", arrayOf("scope", "calendar", "generation"), "account=?", arrayOf(account.value), null, null, null).use { cursor ->
                while (cursor.moveToNext()) if (cursor.getString(1) !in present) {
                    val scope = CalendarScope(account, CalendarId(cursor.getString(1)), cursor.getLong(2))
                    val coverage = readCoverage(db, scope)
                    val invalidated = coverage.copy(observed = null,
                        active = coverage.active?.let { run -> run.copy(windows = run.windows.map { it.copy(prefixKnown = false) }) })
                    if (invalidated != coverage) writeCoverage(db, scope, invalidated)
                    db.update("calendars", ContentValues().apply { put("available", 0); put("status", "UNAVAILABLE") },
                        "scope=?", arrayOf(cursor.getString(0)))
                }
            }
            for (calendar in calendars) {
                val scope = CalendarScope(account, calendar.id, 0)
                val values = ContentValues().apply {
                    put("scope", key(scope)); put("account", account.value); put("calendar", calendar.id.value)
                    put("generation", scope.generation); put("title", calendar.title)
                    if (calendar.colorRgb == null) putNull("color") else put("color", calendar.colorRgb)
                }
                db.insertWithOnConflict("calendars", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                db.execSQL("UPDATE calendars SET status=CASE WHEN enabled=1 THEN 'PENDING' ELSE 'PAUSED' END WHERE scope=? AND available=0", arrayOf(key(scope)))
                db.update("calendars", ContentValues().apply {
                    put("title", calendar.title); put("available", 1)
                    if (calendar.colorRgb == null) putNull("color") else put("color", calendar.colorRgb)
                }, "scope=?", arrayOf(key(scope)))
            }
            checkStagingCapacity(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Local metadata only, including inactive accounts, for closing retained mirror access. */
    suspend fun calendarScopes(): List<CalendarScope> = access { db ->
        db.query("calendars", arrayOf("account", "calendar", "generation"), null, null, null, null, null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(CalendarScope(AccountId(cursor.getString(0)),
                CalendarId(cursor.getString(1)), cursor.getLong(2))) }
        }
    }

    suspend fun selections(account: AccountId): List<Selection> = access { db ->
        // One metadata-only statement keeps retained/conflicted counts coherent, including inactive scopes.
        db.rawQuery("SELECT c.*, (SELECT COUNT(*) FROM local_edits l WHERE l.scope=c.scope) AS retained_local_changes, " +
            "(SELECT COUNT(*) FROM local_edits l WHERE l.scope=c.scope AND l.state='CONFLICT') AS local_conflicts " +
            "FROM calendars c WHERE c.account=? ORDER BY c.rowid", arrayOf(account.value)).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    fun string(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
                    fun long(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
                    val id = CalendarId(string("calendar"))
                    val colorIndex = cursor.getColumnIndexOrThrow("color")
                    val completedIndex = cursor.getColumnIndexOrThrow("completed_at")
                    add(Selection(CalendarScope(account, id, long("generation")),
                        CalendarSummary(id, string("title"), if (cursor.isNull(colorIndex)) null else cursor.getInt(colorIndex)),
                        long("enabled") != 0L, string("status"),
                        if (cursor.isNull(completedIndex)) null else cursor.getLong(completedIndex), long("next_attempt"),
                        countDeferred(db, CalendarScope(account, id, long("generation"))),
                        countUnverified(db, CalendarScope(account, id, long("generation"))), long("available") != 0L,
                        hasReconciliation(db, CalendarScope(account, id, long("generation"))),
                        readCoverage(db, CalendarScope(account, id, long("generation"))).active,
                        countPending(db, CalendarScope(account, id, long("generation"))),
                        cursor.getInt(cursor.getColumnIndexOrThrow("retained_local_changes")) -
                            completedDeleteEvents(db, CalendarScope(account, id, long("generation"))).size,
                        cursor.getInt(cursor.getColumnIndexOrThrow("local_conflicts"))))
                }
            }
        }
    }
    suspend fun deferNetwork(scope: CalendarScope, milliseconds: Long) = access { db ->
        val now = System.currentTimeMillis()
        val delay = milliseconds.coerceAtLeast(0).coerceAtMost(Long.MAX_VALUE - now)
        db.update("calendars", ContentValues().apply { put("next_attempt", now + delay) }, "scope=?", arrayOf(key(scope)))
    }

    suspend fun setEnabled(scope: CalendarScope, enabled: Boolean) = access { db ->
        requireKnown(db, scope)
        db.update("calendars", ContentValues().apply {
            put("enabled", if (enabled) 1 else 0)
            put("status", if (!isAvailable(db, scope)) "UNAVAILABLE" else if (enabled) "PENDING" else "PAUSED")
        }, "scope=?", arrayOf(key(scope)))
    }
    suspend fun status(scope: CalendarScope, status: String, completedAt: Long? = null) = access { db ->
        require(status in setOf("PAUSED", "PENDING", "CREATE_PENDING", "RUNNING", "IMPORTED", "PARTIAL", "INCOMPLETE", "SERIES_PENDING", "HISTORY_GAP", "CAPACITY", "PERMISSION", "AUTH", "NETWORK", "UNSUPPORTED", "LOCAL_KEYS", "UNAVAILABLE", "ERROR"))
        requireKnown(db, scope)
        db.update("calendars", ContentValues().apply {
            put("status", if (isAvailable(db, scope)) status else "UNAVAILABLE")
            if (completedAt != null && isAvailable(db, scope)) put("completed_at", completedAt)
        }, "scope=?", arrayOf(key(scope)))
    }

    /** Admit local mapping semantics without discarding any received or unresolved remote evidence.
     * A changed mapper must observe the old horizon again before rolling coverage can be reused.
     */
    suspend fun ensureMappingProfile(scope: CalendarScope, format: AdapterFormat): Unit = access { db ->
        check(admitTransaction(scope)) { "Inactive import lease" }
        db.beginTransaction()
        try {
            requireKnown(db, scope)
            val previous = db.query("mapping_profiles", arrayOf("adapter", "version"), "scope=?",
                arrayOf(key(scope)), null, null, null).use {
                if (it.moveToFirst()) AdapterFormat(it.getString(0), it.getInt(1)) else null
            }
            if (previous != format) {
                db.insertWithOnConflict("mapping_profiles", null, ContentValues().apply {
                    put("scope", key(scope)); put("adapter", format.id); put("version", format.version)
                    put("rescan_pending", 1)
                }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Could not retain mapping profile" } }
                val coverage = readCoverage(db, scope)
                val invalidated = coverage.copy(observed = null,
                    active = coverage.active?.let { run -> run.copy(windows = run.windows.map { it.copy(prefixKnown = false) }) })
                if (invalidated != coverage) writeCoverage(db, scope, invalidated)
            }
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
            requireKnown(db, scope)
            val checkpoint = db.query("calendars", arrayOf("checkpoint"), "scope=?", arrayOf(key(scope)), null, null, null).use {
                check(it.moveToFirst()); if (it.isNull(0)) null else decodeCheckpoint(scope, it.getString(0))
            }
            val hasReceived = db.rawQuery("SELECT 1 FROM inbox WHERE scope=?", arrayOf(key(scope))).use { it.moveToFirst() }
            if (planned != null) {
                require(checkpoint != null && checkpoint.traversalComplete && !hasReceived) { "Cannot replace an unfinished traversal" }
                require(planned.scope == scope && planned.format == checkpoint.format && planned.cursor != null &&
                    planned.cursor?.format == planned.format && !planned.traversalComplete) { "Invalid planned checkpoint" }
                require(expectedCoverage != null && readCoverage(db, scope) == expectedCoverage) { "Stale planned coverage" }
                require(readDeltaCursor(db, scope) != null) { "Planned refresh requires an established change cursor" }
            }
            if ((checkpoint == null || checkpoint.traversalComplete) && !hasReceived) {
                if (checkpoint != null) writeCoverage(db, scope, readCoverage(db, scope).restart(preserveObservation = planned != null))
                db.update("calendars", ContentValues().apply {
                    if (planned == null) putNull("checkpoint") else put("checkpoint", encodeCheckpoint(planned))
                }, "scope=?", arrayOf(key(scope)))
                requeueSupported(db, scope)
                // The null checkpoint is now the durable obligation to start a complete scan.
                // An extension or delta-only poll cannot satisfy a mapping change.
                if (planned == null) db.update("mapping_profiles", ContentValues().apply { put("rescan_pending", 0) },
                    "scope=?", arrayOf(key(scope)))
                if (planned != null) check(db.update("delta_state", ContentValues().apply { put("more", 1) },
                    "scope=?", arrayOf(key(scope))) == 1) { "Missing change state" }
            }
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
            requireKnown(db, scope)
            val complete = db.query("calendars", arrayOf("checkpoint"), "scope=?", arrayOf(key(scope)), null, null, null).use {
                check(it.moveToFirst()); !it.isNull(0) && decodeCheckpoint(scope, it.getString(0)).traversalComplete
            }
            require(complete && readReceived(db, scope) == null) { "Cannot poll before traversal completion" }
            require(readCoverage(db, scope) == expectedCoverage) { "Stale planned coverage" }
            require(readDeltaCursor(db, scope) != null) { "Refresh requires an established change cursor" }
            requeueSupported(db, scope)
            check(db.update("delta_state", ContentValues().apply { put("more", 1) },
                "scope=?", arrayOf(key(scope))) == 1) { "Missing change state" }
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
        check(admitTransaction(scope)) { "Inactive import lease" }
        requireKnown(db, scope)
        db.rawQuery("SELECT r.payload FROM records r WHERE r.scope=? AND r.event=? AND r.pending=0 AND NOT EXISTS " +
            "(SELECT 1 FROM deferred_events d WHERE d.scope=r.scope AND d.event=r.event) AND NOT EXISTS " +
            "(SELECT 1 FROM local_edits l WHERE l.scope=r.scope AND l.event=r.event)",
            arrayOf(key(scope), eventId.value)).use { cursor ->
            val snapshot = if (cursor.moveToFirst()) decodeChange(cursor.getString(0)) as? RemoteChange.Upsert else null
            check(admitTransaction(scope)) { "Inactive import lease" }
            snapshot
        }
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
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val baseline = readBaseline(db, scope, eventId)
        check(admitTransaction(scope)) { "Inactive import lease" }
        baseline
    }

    /** Saved applied reference, retained after receipt removal; never recalculates finite dates. */
    suspend fun projectedReference(scope: CalendarScope, eventId: EventId): ProjectedEventReference? = access { db ->
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val base = readProjectionBase(db, scope, eventId)
        val reference = base?.let { it.representation ?: ProjectedEventReference.direct(it.change) }
        check(admitTransaction(scope)) { "Inactive import lease" }
        reference
    }

    /** Admission observation only, never a lease across SQL and Provider transactions.
     * Ordinary projection cannot inspect or replace a saved finite representation.
     * Decode every current/previous tag before granting the observation; unknown
     * metadata remains untouched. Legacy NULL and explicit direct are both known.
     */
    suspend fun requireDirectProjection(scope: CalendarScope, eventId: EventId): Unit = access { db ->
        check(admitTransaction(scope)) { "Inactive import lease" }
        db.beginTransactionNonExclusive()
        try {
            requireKnown(db, scope)
            check(activeProjectionScope(db, scope)) { "Inactive projection scope" }
            val base = readProjectionBase(db, scope, eventId)
            val receipt = readProjectionReceipt(db, scope, eventId)
            check(listOf(base?.representation, receipt?.representation, receipt?.previousRepresentation).all {
                it == null || it.representation == ProjectionRepresentation.DIRECT_V1
            }) { "Represented projection requires dedicated admission" }
            check(admitTransaction(scope)) { "Inactive import lease" }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Seed the first sent reference behind exact original-row metadata proof, never a Provider ACK.
     * Existing generic bases cannot be adopted. Exact replay preserves later local intentions and
     * all different/deferred incoming evidence; neither absent records nor new remote data are invented.
     */
    suspend fun retainCapturedBaseline(baseline: CapturedCreateBaseline,
        witness: AppliedCapturedCreateAttachment): Boolean {
        if (witness.receipt != baseline.receipt) return false
        return stagingMutation(baseline.scope) { db ->
            val event = baseline.change.eventId
            if (hasRawEventRow(db, "captured_create_handoffs", baseline.scope, event)) return@stagingMutation false
            if (!activeProjectionScope(db, baseline.scope) ||
                !capturedFormatMatches(db, baseline.scope, baseline.format)) return@stagingMutation false
            if (hasCapturedBase(db, baseline.scope, event)) {
                if (readCapturedBase(db, baseline.scope, event) != baseline) return@stagingMutation false
            } else {
                if (hasRawEventRow(db, "projection_bases", baseline.scope, event) ||
                    hasLocalMutation(db, baseline.scope, event) ||
                    hasRawEventRow(db, "projection_receipts", baseline.scope, event)) return@stagingMutation false
                val receipt = baseline.receipt
                val link = receipt.binding.identity
                if (db.rawQuery("SELECT 1 FROM captured_create_bases WHERE " +
                    "(scope=? AND (attempt=? OR local_id=? OR capture_id=?)) OR nonce=? LIMIT 1",
                    arrayOf(key(baseline.scope), link.attempt.id.value, link.localId.value, link.captureId.value,
                        receipt.nonce)).use { it.moveToFirst() }) return@stagingMutation false
                db.insertOrThrow("captured_create_bases", null, ContentValues().apply {
                    put("scope", key(baseline.scope)); put("event", event.value)
                    put("account", baseline.scope.account.value); put("calendar", baseline.scope.calendar.value)
                    put("generation", baseline.scope.generation); put("attempt", link.attempt.id.value)
                    put("adapter", link.attempt.format.id); put("version", link.attempt.format.version)
                    put("payload_fingerprint", link.attempt.payloadFingerprint); put("local_id", link.localId.value)
                    put("capture_id", link.captureId.value); put("capture_adapter", link.captureFormat.id)
                    put("capture_version", link.captureFormat.version); put("timestamp", link.timestamp.epochSecond)
                    put("content_fingerprint", link.contentFingerprint); put("fingerprint_version", link.fingerprintVersion)
                    put("verified_revision", receipt.binding.verifiedRevision.value); put("nonce", receipt.nonce)
                    put("protocol", receipt.protocol); put("payload", encodeChange(baseline.change))
                })
                db.insertOrThrow("projection_bases", null, ContentValues().apply {
                    put("scope", key(baseline.scope)); put("event", event.value); put("payload", encodeChange(baseline.change))
                })
            }
            // Full serialized content is compared; an opaque revision alone cannot consume work.
            db.update("records", ContentValues().apply { put("pending", 0) },
                "scope=? AND event=? AND payload=? AND pending=1 AND NOT EXISTS " +
                    "(SELECT 1 FROM local_edits l WHERE l.scope=records.scope AND l.event=records.event) AND NOT EXISTS " +
                    "(SELECT 1 FROM deferred_events d WHERE d.scope=records.scope AND d.event=records.event) AND NOT EXISTS " +
                    "(SELECT 1 FROM projection_receipts p WHERE p.scope=records.scope AND p.event=records.event)",
                arrayOf(key(baseline.scope), event.value, encodeChange(baseline.change)))
            true
        }
    }

    /** Historical provenance with a matching current base, never ACK/release permission.
     * Callers must compare the full result with their current source journals and Provider witness;
     * structurally valid storage is not authentication. An advanced base is never rewound here.
     */
    suspend fun capturedBaseline(scope: CalendarScope, event: EventId): CapturedCreateBaseline? = access { db ->
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val baseline = readCapturedBase(db, scope, event)
        check(admitTransaction(scope)) { "Inactive import lease" }
        baseline
    }

    /** Reserve the only volatile admission for a conditional original-row acknowledgement.
     * Existing reservations are inspection-only, including after a crash or a different proposed nonce.
     * No incoming data, sent reference, local intent or Provider row is acknowledged here.
     */
    suspend fun reserveCapturedAcknowledgement(proposed: CapturedCreateAcknowledgementReceipt):
        CapturedCreateAcknowledgementReservation = stagingMutation(proposed.baseline.scope) { db ->
            requireCapturedAcknowledgementSource(db, proposed.baseline)
            val existing = readCapturedAcknowledgement(db, proposed.baseline.scope, proposed.baseline.change.eventId)
            if (existing != null) {
                check(existing.receipt.baseline == proposed.baseline) { "Captured acknowledgement source is immutable" }
                return@stagingMutation CapturedCreateAcknowledgementReservation(existing, fresh = false)
            }
            db.insertOrThrow("captured_create_acknowledgements", null, ContentValues().apply {
                put("scope", key(proposed.baseline.scope)); put("event", proposed.baseline.change.eventId.value)
                put("nonce", proposed.nonce); put("protocol", proposed.protocol)
                put("fingerprint", proposed.fingerprint()); put("state", CapturedCreateAcknowledgementState.UNKNOWN.name)
            })
            CapturedCreateAcknowledgementReservation(CapturedCreateAcknowledgementRecord(proposed,
                CapturedCreateAcknowledgementState.UNKNOWN), fresh = true)
        }

    /** Historical proof under the current exact source, never a clean-row or write permission.
     * Raw malformed/orphaned reservations must throw: they cannot reopen an admission as absence.
     */
    suspend fun capturedAcknowledgement(scope: CalendarScope, event: EventId): CapturedCreateAcknowledgementRecord? = access { db ->
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val record = readCapturedAcknowledgement(db, scope, event)
        check(admitTransaction(scope)) { "Inactive import lease" }
        record
    }

    /** Point-in-time SQL source qualification only; never current Provider cleanliness or hold release.
     * Full sent reference and PROVEN receipt must agree, with no raw outstanding local work.
     */
    suspend fun capturedCleanSource(receipt: CapturedCreateAcknowledgementReceipt): Boolean =
        stagingMutation(receipt.baseline.scope) { db ->
            requireCapturedAcknowledgementSource(db, receipt.baseline)
            val record = readCapturedAcknowledgement(db, receipt.baseline.scope, receipt.baseline.change.eventId)
            record != null && record.receipt == receipt && record.state == CapturedCreateAcknowledgementState.PROVEN
        }

    /** Retain the exact historical Provider batch proof without consuming any import or local work. */
    suspend fun retainCapturedAcknowledgement(witness: AppliedCapturedCreateAcknowledgement): CapturedCreateAcknowledgementRecord =
        stagingMutation(witness.receipt.baseline.scope) { db ->
            val receipt = witness.receipt
            requireCapturedAcknowledgementSource(db, receipt.baseline)
            val existing = checkNotNull(readCapturedAcknowledgement(db, receipt.baseline.scope, receipt.baseline.change.eventId)) {
                "Captured acknowledgement requires its durable reservation"
            }
            check(existing.receipt == receipt) { "Captured acknowledgement proof is immutable" }
            if (existing.state == CapturedCreateAcknowledgementState.UNKNOWN) {
                check(db.update("captured_create_acknowledgements", ContentValues().apply {
                    put("state", CapturedCreateAcknowledgementState.PROVEN.name)
                }, "scope=? AND event=? AND nonce=? AND protocol=? AND fingerprint=? AND state=?",
                    arrayOf(key(receipt.baseline.scope), receipt.baseline.change.eventId.value, receipt.nonce,
                        receipt.protocol.toString(), receipt.fingerprint(), CapturedCreateAcknowledgementState.UNKNOWN.name)) == 1) {
                    "Captured acknowledgement proof was not retained"
                }
            }
            existing.copy(state = CapturedCreateAcknowledgementState.PROVEN)
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
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val events = db.rawQuery("SELECT event FROM captured_create_bases WHERE scope=? UNION " +
            "SELECT event FROM captured_create_acknowledgements WHERE scope=? UNION " +
            "SELECT event FROM captured_create_handoffs WHERE scope=?", arrayOf(key(scope), key(scope), key(scope))).use {
            buildSet { while (it.moveToNext()) add(EventId(it.getString(0))) }
        }
        check(admitTransaction(scope)) { "Inactive import lease" }; events
    }

    suspend fun capturedHandoff(scope: CalendarScope, event: EventId): CapturedCreateHandoffRecord? = access { db ->
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val record = readCapturedHandoff(db, scope, event)
        check(admitTransaction(scope)) { "Inactive import lease" }; record
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
        check(admitTransaction(frame.scope)) { "Inactive import lease" }; requireKnown(db, frame.scope)
        requireHandoffFrame(db, frame)
        val pending = readHandoffPending(db, frame.scope, frame.eventId)
        check(admitTransaction(frame.scope)) { "Inactive import lease" }; pending
    }

    suspend fun capturedHandoffCanProject(frame: CapturedCreateHandoffFrame): Boolean = stagingMutation(frame.scope) { db ->
        ImportStoreCapturedSql.capturedHandoffCanProject(db, frame)
    }

    /** Historical prepared proof is recoverable even when the newest pending value differs. */
    suspend fun capturedHandoffProjection(frame: CapturedCreateHandoffFrame): CapturedHandoffProjection? = access { db ->
        check(admitTransaction(frame.scope)) { "Inactive import lease" }; requireKnown(db, frame.scope)
        check(requireHandoffFrame(db, frame).state == CapturedCreateHandoffState.RELEASED) { "Handoff is pending" }
        val receipt = readHandoffProjectionReceipt(db, frame.scope, frame.eventId)
        val plan = receipt?.let { CapturedHandoffProjection(frame, it) }
        check(admitTransaction(frame.scope)) { "Inactive import lease" }; plan
    }

    suspend fun prepareCapturedHandoffProjection(frame: CapturedCreateHandoffFrame,
        intent: ProjectionIntent): CapturedHandoffProjection = stagingMutation(frame.scope) { db ->
        ImportStoreCapturedSql.prepareCapturedHandoffProjection(db, frame, intent)
    }

    /** Restore only the exact historical write. A committed replay never consumes requeued work. */
    suspend fun restoreCapturedHandoffProjection(witness: AppliedCapturedHandoffProjection): Boolean =
        stagingMutation(witness.plan.frame.scope) { db ->
            val plan = witness.plan; val frame = plan.frame; val receipt = plan.receipt
            val record = readCapturedHandoff(db, frame.scope, frame.eventId) ?: return@stagingMutation false
            if (record.state != CapturedCreateHandoffState.RELEASED || record.frame.receipt != frame.receipt ||
                hasLocalMutation(db, frame.scope, frame.eventId)) return@stagingMutation false
            val outstanding = readHandoffProjectionReceipt(db, frame.scope, frame.eventId)
            if (record.frame == CapturedCreateHandoffFrame(frame.receipt, receipt.change, receipt)) {
                return@stagingMutation outstanding == null
            }
            if (record.frame != frame || outstanding != receipt) return@stagingMutation false
            check(db.update("projection_bases", ContentValues().apply { put("payload", encodeChange(receipt.change)); putNull("representation") },
                "scope=? AND event=? AND payload=? AND representation IS NULL", arrayOf(key(frame.scope), frame.eventId.value, encodeChange(frame.base))) == 1)
            check(db.update("captured_create_handoffs", ContentValues().apply { put("last_projection", encodeHandoffProjection(receipt)) },
                "scope=? AND event=? AND nonce=?", arrayOf(key(frame.scope), frame.eventId.value, frame.receipt.nonce)) == 1)
            db.update("records", ContentValues().apply { put("pending", 0) }, "scope=? AND event=? AND payload=? AND pending=1",
                arrayOf(key(frame.scope), frame.eventId.value, encodeChange(receipt.change)))
            check(db.delete("projection_receipts", "scope=? AND event=? AND nonce=? AND payload=? AND adapter=? AND version=? AND previous_base IS ? AND protocol=? AND representation IS NULL AND previous_representation IS NULL",
                arrayOf(key(frame.scope), frame.eventId.value, receipt.nonce, encodeChange(receipt.change), receipt.format.id,
                    receipt.format.version.toString(), encodeChange(frame.base), receipt.protocol.toString())) == 1)
            true
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

    /** Origin decoding deliberately does not compare projection_bases, which advances after transfer. */
    /** Persist before Provider apply. Preparation alone is never evidence that the row was written. */
    suspend fun prepareProjection(intent: ProjectionIntent, format: AdapterFormat,
        representation: ProjectedEventReference? = null): ProjectionReceipt {
        require(ProjectionReceipt.supports(intent.change)) { "Unsupported projection receipt content" }
        val candidate = ProjectionReceipt(intent.scope, format, intent.change as RemoteChange.Upsert,
            UUID.randomUUID().toString(), null, representation = representation).snapshot()
        return stagingMutation(candidate.scope) { db ->
            requireOrdinaryLocalCapture(db, candidate.scope, candidate.change.eventId)
            check(activeProjectionScope(db, candidate.scope)) { "Inactive projection scope" }
            require(projectionFormatMatches(db, candidate.scope, candidate.format)) { "Incompatible projection format" }
            val change = candidate.change
            val pending = db.rawQuery("SELECT 1 FROM records r WHERE r.scope=? AND r.event=? AND r.payload=? AND r.pending=1 " +
                "AND NOT EXISTS (SELECT 1 FROM local_edits l WHERE l.scope=r.scope AND l.event=r.event) " +
                "AND NOT EXISTS (SELECT 1 FROM deferred_events d WHERE d.scope=r.scope AND d.event=r.event)",
                arrayOf(key(candidate.scope), change.eventId.value, encodeChange(change))).use { it.moveToFirst() }
            require(pending) { "Stale or blocked projection intent" }
            val existing = readProjectionReceipt(db, candidate.scope, change.eventId)
            if (existing != null) {
                require(existing.change == change && existing.format == candidate.format && existing.representation == candidate.representation) { "Unresolved projection receipt" }
                return@stagingMutation existing
            }
            val previous = readProjectionBase(db, candidate.scope, change.eventId)
            val receipt = candidate.copy(previousBase = previous?.change, previousRepresentation = previous?.representation).snapshot()
            db.insertOrThrow("projection_receipts", null, ContentValues().apply {
                put("scope", key(receipt.scope)); put("event", receipt.change.eventId.value); put("nonce", receipt.nonce)
                put("payload", encodeChange(receipt.change)); put("adapter", receipt.format.id); put("version", receipt.format.version)
                receipt.previousBase?.let { put("previous_base", encodeChange(it)) } ?: putNull("previous_base")
                put("protocol", receipt.protocol)
                put("representation", ProjectionRepresentationCodec.encode(receipt.representation))
                put("previous_representation", ProjectionRepresentationCodec.encode(receipt.previousRepresentation))
            })
            receipt
        }
    }

    suspend fun projectionReceipt(scope: CalendarScope, eventId: EventId): ProjectionReceipt? = access { db ->
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val receipt = readProjectionReceipt(db, scope, eventId)
        check(admitTransaction(scope)) { "Inactive import lease" }
        receipt
    }

    /** A qualified atomic Provider write can restore its baseline after a lost local acknowledgement.
     * Newer import evidence is retained. Neither this method nor preparation touches Provider flags.
     */
    suspend fun restoreProjection(witness: AppliedProjectionWitness): Boolean {
        val receipt = witness.receipt.snapshot()
        return stagingMutation(receipt.scope) { db ->
            requireOrdinaryLocalCapture(db, receipt.scope, receipt.change.eventId)
            if (readProjectionReceipt(db, receipt.scope, receipt.change.eventId) != receipt) return@stagingMutation false
            if (!activeProjectionScope(db, receipt.scope) || !projectionFormatMatches(db, receipt.scope, receipt.format) ||
                hasLocalMutation(db, receipt.scope, receipt.change.eventId)) return@stagingMutation false
            val baseline = readProjectionBase(db, receipt.scope, receipt.change.eventId)
            val previousMatches = baseline?.change == receipt.previousBase && baseline?.representation == receipt.previousRepresentation
            val appliedMatches = baseline?.change == receipt.change && baseline?.representation == receipt.representation
            if (!previousMatches && !appliedMatches) return@stagingMutation false
            db.insertWithOnConflict("projection_bases", null, ContentValues().apply {
                put("scope", key(receipt.scope)); put("event", receipt.change.eventId.value); put("payload", encodeChange(receipt.change))
                put("representation", ProjectionRepresentationCodec.encode(receipt.representation))
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            db.update("records", ContentValues().apply { put("pending", 0) },
                "scope=? AND event=? AND payload=? AND pending IN (1,2)",
                arrayOf(key(receipt.scope), receipt.change.eventId.value, encodeChange(receipt.change)))
            check(db.delete("projection_receipts", "scope=? AND event=? AND nonce=? AND payload=? AND adapter=? AND version=? AND previous_base IS ? AND protocol=? AND representation IS ? AND previous_representation IS ?",
                arrayOf(key(receipt.scope), receipt.change.eventId.value, receipt.nonce, encodeChange(receipt.change),
                    receipt.format.id, receipt.format.version.toString(), receipt.previousBase?.let(::encodeChange), receipt.protocol.toString(),
                    ProjectionRepresentationCodec.encode(receipt.representation), ProjectionRepresentationCodec.encode(receipt.previousRepresentation))) == 1)
            true
        }
    }
    private fun projectionFormatMatches(db: SQLiteDatabase, scope: CalendarScope, format: AdapterFormat): Boolean = db.rawQuery(
        "SELECT checkpoint FROM calendars WHERE scope=?", arrayOf(key(scope)),
    ).use { it.moveToFirst() && (it.isNull(0) || decodeCheckpoint(scope, it.getString(0)).format == format) }
    suspend fun localEdit(scope: CalendarScope, eventId: EventId): LocalEditIntent? = access { db ->
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val edit = readLocalEdit(db, scope, eventId)
        check(admitTransaction(scope)) { "Inactive import lease" }
        edit
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
            val admission = localTextPreparationAdmission(db, frozen.intent)
            if (admission == LocalTextPreparationAdmission.HISTORICAL) {
                val retained = readLocalTextEditPreparation(db, frozen.intent.scope, frozen.intent.base.eventId)
                return@guardedEditMutation if (retained != null && sameTextPreparation(retained, frozen)) admission
                    else LocalTextPreparationAdmission.UNQUALIFIED
            }
            if (admission != LocalTextPreparationAdmission.ELIGIBLE) return@guardedEditMutation admission
            insertLocalTextPreparation(db, frozen, base, local)
            LocalTextPreparationAdmission.STORED
        }
    }
    /** Monotone exact-owner transition after a remote conflict; original snapshots/evidence stay intact. */
    suspend fun conflictLocalTextPreparation(intent: LocalEditIntent): LocalTextPreparationAdmission {
        currentCoroutineContext().ensureActive()
        val frozen = supportedLocalTextIntent(intent) ?: return LocalTextPreparationAdmission.UNQUALIFIED
        return guardedEditMutation(frozen.scope) { db ->
            val intent = frozen
            val admission = localTextPreparationAdmission(db, intent)
            if (admission !in listOf(LocalTextPreparationAdmission.ELIGIBLE, LocalTextPreparationAdmission.HISTORICAL))
                return@guardedEditMutation admission
            check(db.update("local_edits", ContentValues().apply { put("state", LocalEditState.CONFLICT.name) },
                "scope=? AND event=? AND mutation=? AND kind='UPDATE'",
                arrayOf(key(intent.scope), intent.base.eventId.value, intent.id.value)) == 1)
            conflicts(db).ensure(intent.scope, intent.base.eventId)
            LocalTextPreparationAdmission.CONFLICT
        }
    }
    private fun localTextPreparationAdmission(db: SQLiteDatabase, intent: LocalEditIntent): LocalTextPreparationAdmission =
        ImportStoreLocalPreparations.localTextPreparationAdmission(db, intent, ::readBaseline)

    private fun insertLocalTextPreparation(db: SQLiteDatabase, value: LocalTextEditPreparation, base: String, local: String) =
        ImportStoreLocalPreparations.insertLocalTextPreparation(db, value, base, local, ::checkStagingCapacity)

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
            val scope = proposed.intent.scope
            val event = proposed.intent.base.eventId
            val historical = readTextUpdateAcknowledgementHistory(db, scope, event, proposed.attempt.id)
            val existing = if (historical == null) readTextUpdateAcknowledgement(db, scope, event) else null
            val exact = if (historical != null) readTextUpdateAcknowledgement(db, scope, event, proposed.attempt.id)
                else existing?.takeIf { it.receipt.attempt.id == proposed.attempt.id }
            if (exact != null) {
                check(exact.receipt.matches(frozen) && exact.receipt.handoff == proposed.handoff) {
                    "Text acknowledgement receipt is immutable"
                }
                return@guardedEditMutation TextUpdateAcknowledgementReservation(exact, fresh = false)
            }
            check(existing == null || existing.state == TextUpdateAcknowledgementState.RESTORED) {
                "Pending text acknowledgement receipt is immutable"
            }
            requireUnusedTextAcknowledgementIdentity(db, proposed)
            if (!textAcknowledgementSource(db, proposed) ||
                !directUpdateAcknowledgementBase(db, scope, event))
                return@guardedEditMutation null
            val owner = editAcknowledgementOwner(db, scope, event)
            val historicalAdmission = owner == proposed.intent &&
                localTextPreparationAdmission(db, proposed.intent) == LocalTextPreparationAdmission.HISTORICAL
            if (!historicalAdmission && !exactUpdateResultEcho(db, proposed.intent, proposed.projection, owner))
                return@guardedEditMutation null
            val preparation = readLocalTextEditPreparation(db, scope, event) ?: return@guardedEditMutation null
            if (!sameTextPreparation(preparation, frozen.record.preparation) || !proposed.attempt.matchesPreparation(preparation))
                return@guardedEditMutation null
            if (existing != null) {
                archiveTextUpdateAcknowledgement(db, existing)
                check(db.delete("text_update_acknowledgements", "scope=? AND event=? AND nonce=? AND receipt=? AND state='RESTORED'",
                    arrayOf(key(scope), event.value, existing.receipt.projection.nonce,
                        encodeTextUpdateAcknowledgement(existing.receipt))) == 1) { "Text acknowledgement slot changed" }
            }
            check(!db.rawQuery("SELECT 1 FROM text_update_acknowledgement_history WHERE nonce=?",
                arrayOf(proposed.projection.nonce)).use { it.moveToFirst() }) { "Text acknowledgement nonce is already retained" }
            db.insertOrThrow("text_update_acknowledgements", null, ContentValues().apply {
                put("scope", key(scope)); put("event", event.value); put("nonce", proposed.projection.nonce)
                put("receipt", encoded); put("state", TextUpdateAcknowledgementState.PENDING.name)
            })
            checkStagingCapacity(db)
            TextUpdateAcknowledgementReservation(TextUpdateAcknowledgementRecord(proposed,
                TextUpdateAcknowledgementState.PENDING), fresh = true)
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
            val retained = readTextUpdateAcknowledgement(db, scope, event, receipt.attempt.id) ?: return@guardedEditMutation false
            if (retained.receipt != receipt) return@guardedEditMutation false
            if (retained.state == TextUpdateAcknowledgementState.RESTORED) return@guardedEditMutation true
            if (!activeProjectionScope(db, scope) || !acknowledgementFormatMatches(db, scope, intent.format) ||
                !textAcknowledgementSource(db, receipt) || !directUpdateAcknowledgementBase(db, scope, event))
                return@guardedEditMutation false
            val owner = editAcknowledgementOwner(db, scope, event) ?: return@guardedEditMutation false
            if (owner.copy(state = LocalEditState.PAUSED) != intent) return@guardedEditMutation false
            val preparation = readLocalTextEditPreparation(db, scope, event) ?: return@guardedEditMutation false
            if (preparation.intent != intent || !receipt.attempt.matchesPreparation(preparation)) return@guardedEditMutation false
            if (readBaseline(db, scope, event) != intent.base) return@guardedEditMutation false
            // The native ACK is now proven. Retire only a still-redundant ordinary
            // plan, atomically with baseline/owner restoration; never claim its apply succeeded.
            if (receipt.handoff == null) retireRedundantUpdateProjection(db, receipt.intent, receipt.handoff)
            val oldBase = encodeChange(intent.base)
            val newBase = encodeChange(receipt.projection.change)
            val local = encodeChange(intent.base.copy(content = intent.content))
            check(db.update("projection_bases", ContentValues().apply { put("payload", newBase) },
                "scope=? AND event=? AND payload=? AND representation IS NULL", arrayOf(key(scope), event.value, oldBase)) == 1)
            receipt.handoff?.let { frame ->
                check(db.update("captured_create_handoffs", ContentValues().apply {
                    put("last_projection", encodeHandoffProjection(receipt.projection)); put("waiting", 0)
                }, "scope=? AND event=? AND nonce=? AND protocol=? AND ack_nonce=? AND ack_fingerprint=? " +
                    "AND fingerprint=? AND state='RELEASED' AND waiting=1 AND last_projection IS ?",
                    arrayOf(key(scope), event.value, frame.receipt.nonce, frame.receipt.protocol.toString(),
                        frame.receipt.acknowledgement.nonce, frame.receipt.acknowledgement.fingerprint(), frame.receipt.fingerprint(),
                        frame.lastProjection?.let(::encodeHandoffProjection))) == 1) {
                    "Transferred text acknowledgement source changed"
                }
            }
            check(db.delete("local_text_edit_preparations", "scope=? AND event=? AND mutation=? AND adapter=? AND version=? " +
                "AND base=? AND local=? AND state=? AND timestamp=? AND prep_adapter=? AND prep_version=? AND payload=?",
                arrayOf(key(scope), event.value, intent.id.value, intent.format.id, intent.format.version.toString(), oldBase,
                    local, LocalEditState.PAUSED.name, preparation.timestamp.toString(), preparation.format.id,
                    preparation.format.version.toString(), preparation.protectedPayload)) == 1)
            check(db.delete("local_edits", "scope=? AND event=? AND mutation=? AND adapter=? AND version=? " +
                "AND base=? AND local=? AND state=? AND kind='UPDATE'",
                arrayOf(key(scope), event.value, intent.id.value, intent.format.id, intent.format.version.toString(),
                    oldBase, local, owner.state.name)) == 1)
            // Advance only an exact old cache; a concurrent/newer update, deletion or absence survives.
            db.update("records", ContentValues().apply { put("payload", newBase); put("pending", 0) },
                "scope=? AND event=? AND payload=?", arrayOf(key(scope), event.value, oldBase))
            db.update("records", ContentValues().apply { put("pending", 0) },
                "scope=? AND event=? AND payload=? AND pending=1", arrayOf(key(scope), event.value, newBase))
            requeueDistinctUpdateObservation(db, receipt.intent, receipt.handoff, newBase)
            check(db.update("text_update_acknowledgements", ContentValues().apply {
                put("state", TextUpdateAcknowledgementState.RESTORED.name)
            }, "scope=? AND event=? AND nonce=? AND receipt=? AND state=?",
                arrayOf(key(scope), event.value, receipt.projection.nonce, encodeTextUpdateAcknowledgement(receipt),
                    TextUpdateAcknowledgementState.PENDING.name)) == 1)
            archiveTextUpdateAcknowledgement(db, TextUpdateAcknowledgementRecord(receipt,
                TextUpdateAcknowledgementState.RESTORED))
            true
        }
    }

    /** The exact owner has been consumed in this transaction. Re-arm only its distinct cached
     * observation, preserving deferred evidence and the normal recurrence/handoff boundaries.
     * This is queue state, never permission to overwrite a later dirty Provider row.
     */
    private fun requeueDistinctUpdateObservation(db: SQLiteDatabase, intent: LocalEditIntent,
        handoff: CapturedCreateHandoffFrame?, acknowledgedPayload: String): Unit =
        localMutations.requeueDistinctUpdateObservation(db, intent, handoff, acknowledgedPayload)

    private fun ordinaryUpdateAcknowledgement(db: SQLiteDatabase, intent: LocalEditIntent,
        handoff: CapturedCreateHandoffFrame?): Boolean =
        localMutations.ordinaryUpdateAcknowledgement(db, intent, handoff)

    /** A dirty edit can interrupt a tracked no-change projection after preparation.
     * This plan carries no content beyond the edit intent's original baseline.
     * Keep it through reservation: only a proven native ACK supersedes it.
     */
    private fun redundantUpdateProjection(pending: ProjectionReceipt, intent: LocalEditIntent,
        handoff: CapturedCreateHandoffFrame?): Boolean =
        localMutations.redundantUpdateProjection(pending, intent, handoff)

    private fun retireRedundantUpdateProjection(db: SQLiteDatabase, intent: LocalEditIntent,
        handoff: CapturedCreateHandoffFrame?): Unit =
        localMutations.retireRedundantUpdateProjection(db, intent, handoff)

    private fun textAcknowledgementSource(db: SQLiteDatabase, receipt: TextUpdateAcknowledgementReceipt): Boolean =
        localMutations.textAcknowledgementSource(db, receipt)

    /** Text/time use the shared edit snapshot reader; title keeps its profile-specific reader. */
    /** A verified result can be imported before its first ACK receipt is reserved.
     * Consume only that exact owner/result, never reopen conflict dispatch.
     * Source (including the complete handoff frame) and capsule equality are
     * checked by the caller in the same transaction.
     */
    private fun exactUpdateResultEcho(db: SQLiteDatabase, intent: LocalEditIntent,
        projection: ProjectionReceipt, owner: LocalEditIntent?): Boolean =
        localMutations.exactUpdateResultEcho(db, intent, projection, owner)

    private fun directUpdateAcknowledgementBase(db: SQLiteDatabase, scope: CalendarScope, event: EventId): Boolean =
        localMutations.directUpdateAcknowledgementBase(db, scope, event)

    private fun acknowledgementFormatMatches(db: SQLiteDatabase, scope: CalendarScope, format: AdapterFormat): Boolean =
        localMutations.acknowledgementFormatMatches(db, scope, format)

    private fun archiveTextUpdateAcknowledgement(db: SQLiteDatabase, record: TextUpdateAcknowledgementRecord) =
        ImportStoreUpdateAcknowledgements.archiveTextUpdateAcknowledgement(db, record, ::checkStagingCapacity)

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
            val admission = localTimePreparationAdmission(db, frozen.intent)
            if (admission == LocalTimePreparationAdmission.HISTORICAL) {
                val retained = readLocalTimeEditPreparation(db, frozen.intent.scope, frozen.intent.base.eventId)
                return@guardedEditMutation if (retained != null && sameTimePreparation(retained, frozen)) admission
                    else LocalTimePreparationAdmission.UNQUALIFIED
            }
            if (admission != LocalTimePreparationAdmission.ELIGIBLE) return@guardedEditMutation admission
            insertLocalTimePreparation(db, frozen, base, local)
            LocalTimePreparationAdmission.STORED
        }
    }
    /** Monotone exact-owner transition after a remote conflict; original snapshots/evidence stay intact. */
    suspend fun conflictLocalTimePreparation(intent: LocalEditIntent): LocalTimePreparationAdmission {
        currentCoroutineContext().ensureActive()
        val frozen = supportedLocalTimeIntent(intent) ?: return LocalTimePreparationAdmission.UNQUALIFIED
        return guardedEditMutation(frozen.scope) { db ->
            val intent = frozen
            val admission = localTimePreparationAdmission(db, intent)
            if (admission !in listOf(LocalTimePreparationAdmission.ELIGIBLE, LocalTimePreparationAdmission.HISTORICAL))
                return@guardedEditMutation admission
            check(db.update("local_edits", ContentValues().apply { put("state", LocalEditState.CONFLICT.name) },
                "scope=? AND event=? AND mutation=? AND kind='UPDATE'",
                arrayOf(key(intent.scope), intent.base.eventId.value, intent.id.value)) == 1)
            conflicts(db).ensure(intent.scope, intent.base.eventId)
            LocalTimePreparationAdmission.CONFLICT
        }
    }
    private fun localTimePreparationAdmission(db: SQLiteDatabase, intent: LocalEditIntent): LocalTimePreparationAdmission =
        ImportStoreLocalPreparations.localTimePreparationAdmission(db, intent, ::readBaseline)

    private fun insertLocalTimePreparation(db: SQLiteDatabase, value: LocalTimeEditPreparation, base: String, local: String) =
        ImportStoreLocalPreparations.insertLocalTimePreparation(db, value, base, local, ::checkStagingCapacity)

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
            val scope = proposed.intent.scope
            val event = proposed.intent.base.eventId
            val historical = readTimeUpdateAcknowledgementHistory(db, scope, event, proposed.attempt.id)
            val existing = if (historical == null) readTimeUpdateAcknowledgement(db, scope, event) else null
            val exact = if (historical != null) readTimeUpdateAcknowledgement(db, scope, event, proposed.attempt.id)
                else existing?.takeIf { it.receipt.attempt.id == proposed.attempt.id }
            if (exact != null) {
                check(exact.receipt.matches(frozen) && exact.receipt.handoff == proposed.handoff) {
                    "Time acknowledgement receipt is immutable"
                }
                return@guardedEditMutation TimeUpdateAcknowledgementReservation(exact, fresh = false)
            }
            check(existing == null || existing.state == TimeUpdateAcknowledgementState.RESTORED) {
                "Pending text acknowledgement receipt is immutable"
            }
            requireUnusedTimeAcknowledgementIdentity(db, proposed)
            if (!timeAcknowledgementSource(db, proposed) ||
                !directUpdateAcknowledgementBase(db, scope, event))
                return@guardedEditMutation null
            val owner = editAcknowledgementOwner(db, scope, event)
            val historicalAdmission = owner == proposed.intent &&
                localTimePreparationAdmission(db, proposed.intent) == LocalTimePreparationAdmission.HISTORICAL
            if (!historicalAdmission && !exactUpdateResultEcho(db, proposed.intent, proposed.projection, owner))
                return@guardedEditMutation null
            val preparation = readLocalTimeEditPreparation(db, scope, event) ?: return@guardedEditMutation null
            if (!sameTimePreparation(preparation, frozen.record.preparation) || !proposed.attempt.matchesPreparation(preparation))
                return@guardedEditMutation null
            if (existing != null) {
                archiveTimeUpdateAcknowledgement(db, existing)
                check(db.delete("time_update_acknowledgements", "scope=? AND event=? AND nonce=? AND receipt=? AND state='RESTORED'",
                    arrayOf(key(scope), event.value, existing.receipt.projection.nonce,
                        encodeTimeUpdateAcknowledgement(existing.receipt))) == 1) { "Time acknowledgement slot changed" }
            }
            check(!db.rawQuery("SELECT 1 FROM time_update_acknowledgement_history WHERE nonce=?",
                arrayOf(proposed.projection.nonce)).use { it.moveToFirst() }) { "Time acknowledgement nonce is already retained" }
            db.insertOrThrow("time_update_acknowledgements", null, ContentValues().apply {
                put("scope", key(scope)); put("event", event.value); put("nonce", proposed.projection.nonce)
                put("receipt", encoded); put("state", TimeUpdateAcknowledgementState.PENDING.name)
            })
            checkStagingCapacity(db)
            TimeUpdateAcknowledgementReservation(TimeUpdateAcknowledgementRecord(proposed,
                TimeUpdateAcknowledgementState.PENDING), fresh = true)
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
            val retained = readTimeUpdateAcknowledgement(db, scope, event, receipt.attempt.id) ?: return@guardedEditMutation false
            if (retained.receipt != receipt) return@guardedEditMutation false
            if (retained.state == TimeUpdateAcknowledgementState.RESTORED) return@guardedEditMutation true
            if (!activeProjectionScope(db, scope) || !acknowledgementFormatMatches(db, scope, intent.format) ||
                !timeAcknowledgementSource(db, receipt) || !directUpdateAcknowledgementBase(db, scope, event))
                return@guardedEditMutation false
            val owner = editAcknowledgementOwner(db, scope, event) ?: return@guardedEditMutation false
            if (owner.copy(state = LocalEditState.PAUSED) != intent) return@guardedEditMutation false
            val preparation = readLocalTimeEditPreparation(db, scope, event) ?: return@guardedEditMutation false
            if (preparation.intent != intent || !receipt.attempt.matchesPreparation(preparation)) return@guardedEditMutation false
            if (readBaseline(db, scope, event) != intent.base) return@guardedEditMutation false
            // The native ACK is now proven. Retire only a still-redundant ordinary
            // plan, atomically with baseline/owner restoration; never claim its apply succeeded.
            if (receipt.handoff == null) retireRedundantUpdateProjection(db, receipt.intent, receipt.handoff)
            val oldBase = encodeChange(intent.base)
            val newBase = encodeChange(receipt.projection.change)
            val local = encodeChange(intent.base.copy(content = intent.content))
            check(db.update("projection_bases", ContentValues().apply { put("payload", newBase) },
                "scope=? AND event=? AND payload=? AND representation IS NULL", arrayOf(key(scope), event.value, oldBase)) == 1)
            receipt.handoff?.let { frame ->
                check(db.update("captured_create_handoffs", ContentValues().apply {
                    put("last_projection", encodeHandoffProjection(receipt.projection)); put("waiting", 0)
                }, "scope=? AND event=? AND nonce=? AND protocol=? AND ack_nonce=? AND ack_fingerprint=? " +
                    "AND fingerprint=? AND state='RELEASED' AND waiting=1 AND last_projection IS ?",
                    arrayOf(key(scope), event.value, frame.receipt.nonce, frame.receipt.protocol.toString(),
                        frame.receipt.acknowledgement.nonce, frame.receipt.acknowledgement.fingerprint(), frame.receipt.fingerprint(),
                        frame.lastProjection?.let(::encodeHandoffProjection))) == 1) {
                    "Transferred time acknowledgement source changed"
                }
            }
            check(db.delete("local_time_edit_preparations", "scope=? AND event=? AND mutation=? AND adapter=? AND version=? " +
                "AND base=? AND local=? AND state=? AND timestamp=? AND prep_adapter=? AND prep_version=? AND payload=?",
                arrayOf(key(scope), event.value, intent.id.value, intent.format.id, intent.format.version.toString(), oldBase,
                    local, LocalEditState.PAUSED.name, preparation.timestamp.toString(), preparation.format.id,
                    preparation.format.version.toString(), preparation.protectedPayload)) == 1)
            check(db.delete("local_edits", "scope=? AND event=? AND mutation=? AND adapter=? AND version=? " +
                "AND base=? AND local=? AND state=? AND kind='UPDATE'",
                arrayOf(key(scope), event.value, intent.id.value, intent.format.id, intent.format.version.toString(),
                    oldBase, local, owner.state.name)) == 1)
            // Advance only an exact old cache; a concurrent/newer update, deletion or absence survives.
            db.update("records", ContentValues().apply { put("payload", newBase); put("pending", 0) },
                "scope=? AND event=? AND payload=?", arrayOf(key(scope), event.value, oldBase))
            db.update("records", ContentValues().apply { put("pending", 0) },
                "scope=? AND event=? AND payload=? AND pending=1", arrayOf(key(scope), event.value, newBase))
            requeueDistinctUpdateObservation(db, receipt.intent, receipt.handoff, newBase)
            check(db.update("time_update_acknowledgements", ContentValues().apply {
                put("state", TimeUpdateAcknowledgementState.RESTORED.name)
            }, "scope=? AND event=? AND nonce=? AND receipt=? AND state=?",
                arrayOf(key(scope), event.value, receipt.projection.nonce, encodeTimeUpdateAcknowledgement(receipt),
                    TimeUpdateAcknowledgementState.PENDING.name)) == 1)
            archiveTimeUpdateAcknowledgement(db, TimeUpdateAcknowledgementRecord(receipt,
                TimeUpdateAcknowledgementState.RESTORED))
            true
        }
    }

    private fun timeAcknowledgementSource(db: SQLiteDatabase, receipt: TimeUpdateAcknowledgementReceipt): Boolean =
        localMutations.timeAcknowledgementSource(db, receipt)

    private fun archiveTimeUpdateAcknowledgement(db: SQLiteDatabase, record: TimeUpdateAcknowledgementRecord) =
        ImportStoreUpdateAcknowledgements.archiveTimeUpdateAcknowledgement(db, record, ::checkStagingCapacity)

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
            val admission = personalPreparationAdmission(db, frozen.intent)
            if (admission == PersonalPreparationAdmission.HISTORICAL) {
                val retained = readPersonalEditPreparation(db, frozen.intent.scope, frozen.intent.base.eventId)
                return@guardedEditMutation if (retained != null && samePersonalPreparation(retained, frozen)) admission
                    else PersonalPreparationAdmission.UNQUALIFIED
            }
            if (admission != PersonalPreparationAdmission.ELIGIBLE) return@guardedEditMutation admission
            insertPersonalPreparation(db, frozen, base, local)
            PersonalPreparationAdmission.STORED
        }
    }
    /** Monotone exact-owner transition after a remote conflict; original snapshots/evidence stay intact. */
    suspend fun conflictPersonalPreparation(intent: LocalEditIntent): PersonalPreparationAdmission {
        currentCoroutineContext().ensureActive()
        val frozen = supportedPersonalIntent(intent) ?: return PersonalPreparationAdmission.UNQUALIFIED
        return guardedEditMutation(frozen.scope) { db ->
            val intent = frozen
            val admission = personalPreparationAdmission(db, intent)
            if (admission !in listOf(PersonalPreparationAdmission.ELIGIBLE, PersonalPreparationAdmission.HISTORICAL))
                return@guardedEditMutation admission
            check(db.update("local_edits", ContentValues().apply { put("state", LocalEditState.CONFLICT.name) },
                "scope=? AND event=? AND mutation=? AND kind='UPDATE'",
                arrayOf(key(intent.scope), intent.base.eventId.value, intent.id.value)) == 1)
            conflicts(db).ensure(intent.scope, intent.base.eventId)
            PersonalPreparationAdmission.CONFLICT
        }
    }
    private fun personalPreparationAdmission(db: SQLiteDatabase, intent: LocalEditIntent): PersonalPreparationAdmission =
        ImportStoreLocalPreparations.personalPreparationAdmission(db, intent, ::readBaseline)

    private fun insertPersonalPreparation(db: SQLiteDatabase, value: PersonalEditPreparation, base: String, local: String) =
        ImportStoreLocalPreparations.insertPersonalPreparation(db, value, base, local, ::checkStagingCapacity)

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
            val scope = proposed.intent.scope
            val event = proposed.intent.base.eventId
            val historical = readPersonalUpdateAcknowledgementHistory(db, scope, event, proposed.attempt.id)
            val existing = if (historical == null) readPersonalUpdateAcknowledgement(db, scope, event) else null
            val exact = if (historical != null) readPersonalUpdateAcknowledgement(db, scope, event, proposed.attempt.id)
                else existing?.takeIf { it.receipt.attempt.id == proposed.attempt.id }
            if (exact != null) {
                check(exact.receipt.matches(frozen) && exact.receipt.handoff == proposed.handoff) {
                    "Personal acknowledgement receipt is immutable"
                }
                return@guardedEditMutation PersonalUpdateAcknowledgementReservation(exact, fresh = false)
            }
            check(existing == null || existing.state == PersonalUpdateAcknowledgementState.RESTORED) {
                "Pending personal acknowledgement receipt is immutable"
            }
            requireUnusedPersonalAcknowledgementIdentity(db, proposed)
            if (!personalAcknowledgementSource(db, proposed) ||
                !directUpdateAcknowledgementBase(db, scope, event))
                return@guardedEditMutation null
            val owner = editAcknowledgementOwner(db, scope, event)
            val historicalAdmission = owner == proposed.intent &&
                personalPreparationAdmission(db, proposed.intent) == PersonalPreparationAdmission.HISTORICAL
            if (!historicalAdmission && !exactUpdateResultEcho(db, proposed.intent, proposed.projection, owner))
                return@guardedEditMutation null
            val preparation = readPersonalEditPreparation(db, scope, event) ?: return@guardedEditMutation null
            if (!samePersonalPreparation(preparation, frozen.record.preparation) || !proposed.attempt.matchesPreparation(preparation))
                return@guardedEditMutation null
            if (existing != null) {
                archivePersonalUpdateAcknowledgement(db, existing)
                check(db.delete("personal_update_acknowledgements", "scope=? AND event=? AND nonce=? AND receipt=? AND state='RESTORED'",
                    arrayOf(key(scope), event.value, existing.receipt.projection.nonce,
                        encodePersonalUpdateAcknowledgement(existing.receipt))) == 1) { "Personal acknowledgement slot changed" }
            }
            check(!db.rawQuery("SELECT 1 FROM personal_update_acknowledgement_history WHERE nonce=?",
                arrayOf(proposed.projection.nonce)).use { it.moveToFirst() }) { "Personal acknowledgement nonce is already retained" }
            db.insertOrThrow("personal_update_acknowledgements", null, ContentValues().apply {
                put("scope", key(scope)); put("event", event.value); put("nonce", proposed.projection.nonce)
                put("receipt", encoded); put("state", PersonalUpdateAcknowledgementState.PENDING.name)
            })
            checkStagingCapacity(db)
            PersonalUpdateAcknowledgementReservation(PersonalUpdateAcknowledgementRecord(proposed,
                PersonalUpdateAcknowledgementState.PENDING), fresh = true)
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
            val retained = readPersonalUpdateAcknowledgement(db, scope, event, receipt.attempt.id) ?: return@guardedEditMutation false
            if (retained.receipt != receipt) return@guardedEditMutation false
            if (retained.state == PersonalUpdateAcknowledgementState.RESTORED) return@guardedEditMutation true
            if (!activeProjectionScope(db, scope) || !acknowledgementFormatMatches(db, scope, intent.format) ||
                !personalAcknowledgementSource(db, receipt) || !directUpdateAcknowledgementBase(db, scope, event))
                return@guardedEditMutation false
            val owner = editAcknowledgementOwner(db, scope, event) ?: return@guardedEditMutation false
            if (owner.copy(state = LocalEditState.PAUSED) != intent) return@guardedEditMutation false
            val preparation = readPersonalEditPreparation(db, scope, event) ?: return@guardedEditMutation false
            if (preparation.intent != intent || !receipt.attempt.matchesPreparation(preparation)) return@guardedEditMutation false
            if (readBaseline(db, scope, event) != intent.base) return@guardedEditMutation false
            // The native ACK is now proven. Retire only a still-redundant ordinary
            // plan, atomically with baseline/owner restoration; never claim its apply succeeded.
            if (receipt.handoff == null) retireRedundantUpdateProjection(db, receipt.intent, receipt.handoff)
            val oldBase = encodeChange(intent.base)
            val newBase = encodeChange(receipt.projection.change)
            val local = encodeChange(intent.base.copy(content = intent.content))
            check(db.update("projection_bases", ContentValues().apply { put("payload", newBase) },
                "scope=? AND event=? AND payload=? AND representation IS NULL", arrayOf(key(scope), event.value, oldBase)) == 1)
            receipt.handoff?.let { frame ->
                check(db.update("captured_create_handoffs", ContentValues().apply {
                    put("last_projection", encodeHandoffProjection(receipt.projection)); put("waiting", 0)
                }, "scope=? AND event=? AND nonce=? AND protocol=? AND ack_nonce=? AND ack_fingerprint=? " +
                    "AND fingerprint=? AND state='RELEASED' AND waiting=1 AND last_projection IS ?",
                    arrayOf(key(scope), event.value, frame.receipt.nonce, frame.receipt.protocol.toString(),
                        frame.receipt.acknowledgement.nonce, frame.receipt.acknowledgement.fingerprint(), frame.receipt.fingerprint(),
                        frame.lastProjection?.let(::encodeHandoffProjection))) == 1) {
                    "Transferred personal acknowledgement source changed"
                }
            }
            check(db.delete("personal_edit_preparations", "scope=? AND event=? AND mutation=? AND adapter=? AND version=? " +
                "AND base=? AND local=? AND state=? AND timestamp=? AND prep_adapter=? AND prep_version=? AND payload=?",
                arrayOf(key(scope), event.value, intent.id.value, intent.format.id, intent.format.version.toString(), oldBase,
                    local, LocalEditState.PAUSED.name, preparation.timestamp.toString(), preparation.format.id,
                    preparation.format.version.toString(), preparation.protectedPayload)) == 1)
            check(db.delete("local_edits", "scope=? AND event=? AND mutation=? AND adapter=? AND version=? " +
                "AND base=? AND local=? AND state=? AND kind='UPDATE'",
                arrayOf(key(scope), event.value, intent.id.value, intent.format.id, intent.format.version.toString(),
                    oldBase, local, owner.state.name)) == 1)
            // Advance only an exact old cache; a concurrent/newer update, deletion or absence survives.
            db.update("records", ContentValues().apply { put("payload", newBase); put("pending", 0) },
                "scope=? AND event=? AND payload=?", arrayOf(key(scope), event.value, oldBase))
            db.update("records", ContentValues().apply { put("pending", 0) },
                "scope=? AND event=? AND payload=? AND pending=1", arrayOf(key(scope), event.value, newBase))
            requeueDistinctUpdateObservation(db, receipt.intent, receipt.handoff, newBase)
            check(db.update("personal_update_acknowledgements", ContentValues().apply {
                put("state", PersonalUpdateAcknowledgementState.RESTORED.name)
            }, "scope=? AND event=? AND nonce=? AND receipt=? AND state=?",
                arrayOf(key(scope), event.value, receipt.projection.nonce, encodePersonalUpdateAcknowledgement(receipt),
                    PersonalUpdateAcknowledgementState.PENDING.name)) == 1)
            archivePersonalUpdateAcknowledgement(db, PersonalUpdateAcknowledgementRecord(receipt,
                PersonalUpdateAcknowledgementState.RESTORED))
            true
        }
    }

    private fun personalAcknowledgementSource(db: SQLiteDatabase, receipt: PersonalUpdateAcknowledgementReceipt): Boolean =
        localMutations.personalAcknowledgementSource(db, receipt)

    private fun archivePersonalUpdateAcknowledgement(db: SQLiteDatabase, record: PersonalUpdateAcknowledgementRecord) =
        ImportStoreUpdateAcknowledgements.archivePersonalUpdateAcknowledgement(db, record, ::checkStagingCapacity)

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
            check(!hasForeignLocalPreparation(db, intent.scope, intent.base.eventId, "local_edit_preparations")) {
                "Another preparation already owns this event"
            }
            val previous = readLocalEditPreparation(db, intent.scope, intent.base.eventId)
            if (previous != null) {
                require(previous == frozen) { "Local edit preparation is immutable" }
                return@stagingMutation false
            }
            check(activeProjectionScope(db, intent.scope)) { "Inactive local preparation scope" }
            require(readPreparationOwner(db, intent.scope, intent.base.eventId) == intent) { "Stale local preparation owner" }
            insertLocalTitlePreparation(db, frozen, base, local)
            true
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
            val admission = localTitlePreparationAdmission(db, frozen.intent)
            if (admission != LocalTitlePreparationAdmission.ELIGIBLE) return@stagingMutation admission
            insertLocalTitlePreparation(db, frozen, base, local)
            LocalTitlePreparationAdmission.STORED
        }
    }
    /** Monotone exact-owner transition after a remote conflict; original snapshots/evidence stay intact. */
    suspend fun conflictLocalTitlePreparation(intent: LocalEditIntent): LocalTitlePreparationAdmission =
        stagingMutation(intent.scope) { db ->
            val admission = localTitlePreparationAdmission(db, intent)
            if (admission !in listOf(LocalTitlePreparationAdmission.ELIGIBLE, LocalTitlePreparationAdmission.HISTORICAL))
                return@stagingMutation admission
            check(db.update("local_edits", ContentValues().apply { put("state", LocalEditState.CONFLICT.name) },
                "scope=? AND event=? AND mutation=? AND kind='UPDATE'",
                arrayOf(key(intent.scope), intent.base.eventId.value, intent.id.value)) == 1)
            conflicts(db).ensure(intent.scope, intent.base.eventId)
            LocalTitlePreparationAdmission.CONFLICT
        }
    private fun localTitlePreparationAdmission(db: SQLiteDatabase, intent: LocalEditIntent): LocalTitlePreparationAdmission =
        ImportStoreLocalPreparations.localTitlePreparationAdmission(db, intent, ::readBaseline)

    private fun insertLocalTitlePreparation(db: SQLiteDatabase, value: LocalEditPreparation, base: String, local: String) =
        ImportStoreLocalPreparations.insertLocalTitlePreparation(db, value, base, local, ::checkStagingCapacity)

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
            val scope = proposed.intent.scope
            val event = proposed.intent.base.eventId
            val historical = readTitleUpdateAcknowledgementHistory(db, scope, event, proposed.attempt.id)
            val existing = if (historical == null) readTitleUpdateAcknowledgement(db, scope, event) else null
            val exact = if (historical != null) readTitleUpdateAcknowledgement(db, scope, event, proposed.attempt.id)
                else existing?.takeIf { it.receipt.attempt.id == proposed.attempt.id }
            if (exact != null) {
                check(exact.receipt.matches(frozen) && exact.receipt.handoff == proposed.handoff) {
                    "Title acknowledgement receipt is immutable"
                }
                return@stagingMutation TitleUpdateAcknowledgementReservation(exact, fresh = false)
            }
            check(existing == null || existing.state == TitleUpdateAcknowledgementState.RESTORED) {
                "Pending title acknowledgement receipt is immutable"
            }
            requireUnusedTitleAcknowledgementIdentity(db, proposed)
            if (!titleAcknowledgementSource(db, proposed) ||
                !directUpdateAcknowledgementBase(db, scope, event))
                return@stagingMutation null
            val owner = titleAcknowledgementOwner(db, scope, event)
            val historicalAdmission = owner == proposed.intent &&
                localTitlePreparationAdmission(db, proposed.intent) == LocalTitlePreparationAdmission.HISTORICAL
            if (!historicalAdmission && !exactUpdateResultEcho(db, proposed.intent, proposed.projection, owner))
                return@stagingMutation null
            val preparation = readLocalEditPreparation(db, scope, event) ?: return@stagingMutation null
            if (preparation != frozen.record.preparation || !proposed.attempt.matchesPreparation(preparation))
                return@stagingMutation null
            if (existing != null) {
                archiveTitleUpdateAcknowledgement(db, existing)
                check(db.delete("title_update_acknowledgements", "scope=? AND event=? AND nonce=? AND receipt=? AND state='RESTORED'",
                    arrayOf(key(scope), event.value, existing.receipt.projection.nonce,
                        encodeTitleUpdateAcknowledgement(existing.receipt))) == 1) { "Title acknowledgement slot changed" }
            }
            check(!db.rawQuery("SELECT 1 FROM title_update_acknowledgement_history WHERE nonce=?",
                arrayOf(proposed.projection.nonce)).use { it.moveToFirst() }) { "Title acknowledgement nonce is already retained" }
            db.insertOrThrow("title_update_acknowledgements", null, ContentValues().apply {
                put("scope", key(scope)); put("event", event.value); put("nonce", proposed.projection.nonce)
                put("receipt", encoded); put("state", TitleUpdateAcknowledgementState.PENDING.name)
            })
            TitleUpdateAcknowledgementReservation(TitleUpdateAcknowledgementRecord(proposed,
                TitleUpdateAcknowledgementState.PENDING), fresh = true)
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
            val retained = readTitleUpdateAcknowledgement(db, scope, event, receipt.attempt.id) ?: return@stagingMutation false
            if (retained.receipt != receipt) return@stagingMutation false
            if (retained.state == TitleUpdateAcknowledgementState.RESTORED) return@stagingMutation true
            if (!activeProjectionScope(db, scope) || !acknowledgementFormatMatches(db, scope, intent.format) ||
                !titleAcknowledgementSource(db, receipt) || !directUpdateAcknowledgementBase(db, scope, event))
                return@stagingMutation false
            val owner = titleAcknowledgementOwner(db, scope, event) ?: return@stagingMutation false
            if (owner.copy(state = LocalEditState.PAUSED) != intent) return@stagingMutation false
            val preparation = readLocalEditPreparation(db, scope, event) ?: return@stagingMutation false
            if (preparation.intent != intent || !receipt.attempt.matchesPreparation(preparation)) return@stagingMutation false
            if (readBaseline(db, scope, event) != intent.base) return@stagingMutation false
            // The native ACK is now proven. Retire only a still-redundant ordinary
            // plan, atomically with baseline/owner restoration; never claim its apply succeeded.
            if (receipt.handoff == null) retireRedundantUpdateProjection(db, receipt.intent, receipt.handoff)
            val oldBase = encodeChange(intent.base)
            val newBase = encodeChange(receipt.projection.change)
            val local = encodeChange(intent.base.copy(content = intent.content))
            check(db.update("projection_bases", ContentValues().apply { put("payload", newBase) },
                "scope=? AND event=? AND payload=? AND representation IS NULL", arrayOf(key(scope), event.value, oldBase)) == 1)
            receipt.handoff?.let { frame ->
                check(db.update("captured_create_handoffs", ContentValues().apply {
                    put("last_projection", encodeHandoffProjection(receipt.projection)); put("waiting", 0)
                }, "scope=? AND event=? AND nonce=? AND protocol=? AND ack_nonce=? AND ack_fingerprint=? " +
                    "AND fingerprint=? AND state='RELEASED' AND waiting=1 AND last_projection IS ?",
                    arrayOf(key(scope), event.value, frame.receipt.nonce, frame.receipt.protocol.toString(),
                        frame.receipt.acknowledgement.nonce, frame.receipt.acknowledgement.fingerprint(), frame.receipt.fingerprint(),
                        frame.lastProjection?.let(::encodeHandoffProjection))) == 1) {
                    "Transferred title acknowledgement source changed"
                }
            }
            check(db.delete("local_edit_preparations", "scope=? AND event=? AND mutation=? AND adapter=? AND version=? " +
                "AND base=? AND local=? AND state=? AND timestamp=? AND prep_adapter=? AND prep_version=? AND payload=?",
                arrayOf(key(scope), event.value, intent.id.value, intent.format.id, intent.format.version.toString(), oldBase,
                    local, LocalEditState.PAUSED.name, preparation.timestamp.toString(), preparation.format.id,
                    preparation.format.version.toString(), preparation.protectedPayload)) == 1)
            check(db.delete("local_edits", "scope=? AND event=? AND mutation=? AND adapter=? AND version=? " +
                "AND base=? AND local=? AND state=? AND kind='UPDATE'",
                arrayOf(key(scope), event.value, intent.id.value, intent.format.id, intent.format.version.toString(),
                    oldBase, local, owner.state.name)) == 1)
            // Advance only an exact old cache; a concurrent/newer update, deletion or absence survives.
            db.update("records", ContentValues().apply { put("payload", newBase); put("pending", 0) },
                "scope=? AND event=? AND payload=?", arrayOf(key(scope), event.value, oldBase))
            db.update("records", ContentValues().apply { put("pending", 0) },
                "scope=? AND event=? AND payload=? AND pending=1", arrayOf(key(scope), event.value, newBase))
            requeueDistinctUpdateObservation(db, receipt.intent, receipt.handoff, newBase)
            check(db.update("title_update_acknowledgements", ContentValues().apply {
                put("state", TitleUpdateAcknowledgementState.RESTORED.name)
            }, "scope=? AND event=? AND nonce=? AND receipt=? AND state=?",
                arrayOf(key(scope), event.value, receipt.projection.nonce, encodeTitleUpdateAcknowledgement(receipt),
                    TitleUpdateAcknowledgementState.PENDING.name)) == 1)
            archiveTitleUpdateAcknowledgement(db, TitleUpdateAcknowledgementRecord(receipt,
                TitleUpdateAcknowledgementState.RESTORED))
            true
        }
    }

    private fun titleAcknowledgementSource(db: SQLiteDatabase, receipt: TitleUpdateAcknowledgementReceipt): Boolean =
        localMutations.titleAcknowledgementSource(db, receipt)

    /** Historical inspection only: a frozen PAUSED snapshot never resets the current conflict state. */
    suspend fun localEditPreparation(scope: CalendarScope, eventId: EventId): LocalEditPreparation? =
        stagingMutation(scope) { db -> readLocalEditPreparation(db, scope, eventId) }

    /** Decode the complete immutable binding before using even its completion state. */
    private fun readDeleteAcknowledgement(db: SQLiteDatabase, scope: CalendarScope, event: EventId): DeleteAcknowledgementRecord? =
        localMutations.readDeleteAcknowledgement(db, scope, event)

    suspend fun deleteAcknowledgement(scope: CalendarScope, event: EventId, attempt: MutationId): DeleteAcknowledgementRecord? =
        stagingMutation(scope) { db -> readDeleteAcknowledgement(db,scope,event)?.takeIf { it.receipt.attempt.id == attempt } }

    suspend fun deleteAcknowledgement(scope: CalendarScope, event: EventId): DeleteAcknowledgementRecord? =
        stagingMutation(scope) { db -> readDeleteAcknowledgement(db,scope,event) }

    private fun matchingDeleteReservation(db: SQLiteDatabase, scope: CalendarScope, event: EventId): Boolean =
        localMutations.matchingDeleteReservation(db, scope, event)

    private fun completedDeleteEvents(db: SQLiteDatabase, scope: CalendarScope): Set<EventId> =
        localMutations.completedDeleteEvents(db, scope)

    private fun qualifyLocalDelete(db: SQLiteDatabase, intent: LocalDeleteIntent, allowReservedAbsence: Boolean): Boolean =
        localMutations.qualifyLocalDelete(db, intent, allowReservedAbsence)

    suspend fun qualifyLocalDelete(intent: LocalDeleteIntent): Boolean = stagingMutation(intent.scope) { db ->
        qualifyLocalDelete(db,intent,true)
    }

    /** A dirty tombstone can interrupt an identical incoming re-projection. Retain its
     * obligation through preparation and sending; only a proven native DELETE ACK supersedes it.
     */
    private fun redundantDeleteProjection(pending: ProjectionReceipt, intent: LocalDeleteIntent): Boolean =
        localMutations.redundantDeleteProjection(pending, intent)

    private fun retireRedundantDeleteProjection(db: SQLiteDatabase, intent: LocalDeleteIntent): Unit =
        localMutations.retireRedundantDeleteProjection(db, intent)

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
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val deletion = readLocalDelete(db, scope, eventId)
        check(admitTransaction(scope)) { "Inactive import lease" }
        deletion
    }
    /** Capture and projection suppression share one database transaction. No dispatch/reset exists. */
    suspend fun captureLocalEdit(intent: LocalEditIntent): LocalEditIntent = stagingMutation(intent.scope) { db ->
        localMutations.captureLocalEdit(db, intent)
    }

    /** Retain a qualified Android tombstone snapshot without consuming its flags or sending DELETE. */
    suspend fun captureLocalDelete(intent: LocalDeleteIntent): LocalDeleteIntent = stagingMutation(intent.scope) { db ->
        localMutations.captureLocalDelete(db, intent)
    }

    private fun readProjectionBase(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): ImportStoreLocalMutationSql.ProjectionBase? =
        localMutations.readProjectionBase(db, scope, eventId)

    private fun readBaseline(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): RemoteChange.Upsert? =
        localMutations.readBaseline(db, scope, eventId)

    private fun readLocalEdit(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): LocalEditIntent? =
        localMutations.readLocalEdit(db, scope, eventId)

    private fun readLocalDelete(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): LocalDeleteIntent? =
        localMutations.readLocalDelete(db, scope, eventId)

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
            coroutine.ensureActive()
            check(admitTransaction(scope)) { "Inactive import lease" }
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
    }
    override suspend fun <T> transaction(scope: CalendarScope, block: SyncStore.Transaction.() -> T): T {
        val coroutine = currentCoroutineContext()
        return access { db ->
        coroutine.ensureActive()
        check(admitTransaction(scope)) { "Inactive import lease" }
        db.beginTransaction()
        try {
            requireKnown(db, scope)
            val transaction = ImportStorePageTransaction(db, scope, ::conflicts, ::matchingDeleteReservation)
            val result = block(transaction)
            checkStagingCapacity(db)
            check(admitTransaction(scope)) { "Inactive import lease" }
            coroutine.ensureActive()
            db.setTransactionSuccessful()
            result
        } finally { db.endTransaction() }
        }
    }

    private suspend fun <T> access(action: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { action(writableDatabase) }
    }
    private fun isAvailable(db: SQLiteDatabase, scope: CalendarScope): Boolean =
        localMutations.isAvailable(db, scope)

    companion object {
        const val STAGING_BUDGET_BYTES: Long = 32L * 1024 * 1024
    }
}
