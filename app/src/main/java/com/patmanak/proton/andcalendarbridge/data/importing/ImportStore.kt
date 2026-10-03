package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.CursorWindow
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteException
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.DateTimeException
import java.util.UUID
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONException

/** App-private, backup-excluded durable import queue. Every page and checkpoint commits together. */
enum class LocalTitlePreparationAdmission { ELIGIBLE, STORED, HISTORICAL, INACTIVE, UNQUALIFIED, CONFLICT }

class ImportStore(context: Context, name: String = "calendar-import-v1.db") :
    SQLiteOpenHelper(context, name, null, 14), SyncStore {
    private val lock = Mutex()
    /** Check the run lease both before its transaction and before durable commit. */
    @Volatile var admitTransaction: (CalendarScope) -> Boolean = { true }
    override fun onConfigure(db: SQLiteDatabase) {
        db.execSQL("PRAGMA synchronous=FULL")
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE calendars (scope TEXT PRIMARY KEY, account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL, title TEXT NOT NULL, color INTEGER, enabled INTEGER NOT NULL DEFAULT 0, checkpoint TEXT, status TEXT NOT NULL DEFAULT 'PAUSED', completed_at INTEGER, next_attempt INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE records (scope TEXT NOT NULL, event TEXT NOT NULL, payload TEXT NOT NULL, pending INTEGER NOT NULL, PRIMARY KEY(scope,event))")
        createStaging(db)
        createDelta(db)
        createReconciliation(db)
        createCoverage(db)
        createSeries(db)
        createMappingProfiles(db)
        createLocalEdits(db)
        createProjectionReceipts(db)
        createCapturedBases(db)
        createCapturedAcknowledgements(db)
        createCapturedHandoffs(db)
        createLocalEditPreparations(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..13 && newVersion == 14) { "Explicit import schema migration required" }
        if (oldVersion < 2) createStaging(db)
        if (oldVersion < 3) createDelta(db)
        if (oldVersion < 4) createReconciliation(db)
        if (oldVersion < 5) createCoverage(db)
        if (oldVersion < 6) createSeries(db)
        if (oldVersion < 7) createMappingProfiles(db)
        if (oldVersion < 8) {
            createLocalEdits(db)
            // Legacy clean cache is only a baseline candidate; capture still qualifies the live owner row.
            db.rawQuery("SELECT r.scope,r.event,r.payload FROM records r WHERE r.pending=0 AND NOT EXISTS " +
                "(SELECT 1 FROM deferred_events d WHERE d.scope=r.scope AND d.event=r.event)", null).use { cursor ->
                while (cursor.moveToNext()) {
                    if (decodeChange(cursor.getString(2)) is RemoteChange.Upsert) db.insertOrThrow("projection_bases", null,
                        ContentValues().apply {
                            put("scope", cursor.getString(0)); put("event", cursor.getString(1)); put("payload", cursor.getString(2))
                        })
                }
            }
        }
        if (oldVersion < 9) createProjectionReceipts(db)
        if (oldVersion in 8..9) db.execSQL("ALTER TABLE local_edits ADD COLUMN kind TEXT NOT NULL DEFAULT 'UPDATE' CHECK(kind IN ('UPDATE','DELETE'))")
        if (oldVersion < 11) createCapturedBases(db)
        if (oldVersion < 12) createCapturedAcknowledgements(db)
        if (oldVersion < 13) createCapturedHandoffs(db)
        createLocalEditPreparations(db)
    }
    private fun createLocalEditPreparations(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE local_edit_preparations (
            scope TEXT NOT NULL, mutation TEXT NOT NULL, event TEXT NOT NULL,
            adapter TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0),
            base TEXT NOT NULL, local TEXT NOT NULL, state TEXT NOT NULL CHECK(state = 'PAUSED'),
            timestamp TEXT NOT NULL, prep_adapter TEXT NOT NULL,
            prep_version INTEGER NOT NULL CHECK(prep_version > 0), payload TEXT NOT NULL,
            PRIMARY KEY(scope,mutation), UNIQUE(scope,event)
        )""".trimIndent())
    }
    private fun createCapturedHandoffs(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE captured_create_handoffs (
            scope TEXT NOT NULL, event TEXT NOT NULL, nonce TEXT NOT NULL UNIQUE,
            protocol INTEGER NOT NULL CHECK(protocol = 1), ack_nonce TEXT NOT NULL,
            ack_fingerprint TEXT NOT NULL, fingerprint TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('PENDING','RELEASED')),
            waiting INTEGER NOT NULL DEFAULT 0 CHECK(waiting IN (0,1)), last_projection TEXT,
            PRIMARY KEY(scope,event)
        )""".trimIndent())
    }
    private fun createCapturedAcknowledgements(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE captured_create_acknowledgements (
            scope TEXT NOT NULL, event TEXT NOT NULL, nonce TEXT NOT NULL UNIQUE,
            protocol INTEGER NOT NULL CHECK(protocol = 1), fingerprint TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('UNKNOWN','PROVEN')), PRIMARY KEY(scope,event)
        )""".trimIndent())
    }
    private fun createCapturedBases(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE captured_create_bases (
            scope TEXT NOT NULL, event TEXT NOT NULL,
            account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL CHECK(generation >= 0),
            attempt TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0),
            payload_fingerprint TEXT NOT NULL, local_id TEXT NOT NULL, capture_id TEXT NOT NULL,
            capture_adapter TEXT NOT NULL, capture_version INTEGER NOT NULL CHECK(capture_version > 0),
            timestamp INTEGER NOT NULL, content_fingerprint TEXT NOT NULL,
            fingerprint_version INTEGER NOT NULL CHECK(fingerprint_version = 1),
            verified_revision TEXT NOT NULL, nonce TEXT NOT NULL UNIQUE,
            protocol INTEGER NOT NULL CHECK(protocol = 1), payload TEXT NOT NULL,
            PRIMARY KEY(scope,event), UNIQUE(scope,attempt), UNIQUE(scope,local_id), UNIQUE(scope,capture_id)
        )""".trimIndent())
    }
    private fun createProjectionReceipts(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE projection_receipts (scope TEXT NOT NULL, event TEXT NOT NULL, nonce TEXT NOT NULL UNIQUE, payload TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL, previous_base TEXT, protocol INTEGER NOT NULL CHECK(protocol=1), PRIMARY KEY(scope,event))")
    }
    private fun createLocalEdits(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE projection_bases (scope TEXT NOT NULL, event TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(scope,event))")
        db.execSQL("CREATE TABLE local_edits (scope TEXT NOT NULL, mutation TEXT NOT NULL, event TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL, base TEXT NOT NULL, local TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('PAUSED','CONFLICT')), kind TEXT NOT NULL DEFAULT 'UPDATE' CHECK(kind IN ('UPDATE','DELETE')), PRIMARY KEY(scope,mutation), UNIQUE(scope,event))")
    }
    private fun createMappingProfiles(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE mapping_profiles (scope TEXT PRIMARY KEY, adapter TEXT NOT NULL, version INTEGER NOT NULL, rescan_pending INTEGER NOT NULL DEFAULT 1)")
    }
    private fun createSeries(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE series_members (scope TEXT NOT NULL, event TEXT NOT NULL, uid TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('master','exception')), PRIMARY KEY(scope,event))")
        db.execSQL("CREATE INDEX series_members_uid ON series_members(scope,uid,role)")
    }
    private fun createStaging(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE inbox (scope TEXT PRIMARY KEY, request_checkpoint TEXT NOT NULL, request_limit INTEGER NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE deferred_events (scope TEXT NOT NULL, event TEXT NOT NULL, revision TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL, reason TEXT NOT NULL, payload TEXT NOT NULL, retry_pending INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(scope,event))")
    }
    private fun createDelta(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE calendars ADD COLUMN available INTEGER NOT NULL DEFAULT 1")
        db.execSQL("CREATE TABLE delta_state (scope TEXT PRIMARY KEY, cursor_adapter TEXT, cursor_version INTEGER, cursor_value TEXT, received_adapter TEXT, received_version INTEGER, received_after TEXT, received_payload TEXT, more INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE delta_jobs (scope TEXT NOT NULL, notice_id TEXT NOT NULL, event TEXT, notice TEXT NOT NULL, received TEXT, blocked INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(scope,notice_id))")
    }
    private fun createReconciliation(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE delta_reconciliation (id INTEGER PRIMARY KEY, scope TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL, after_cursor TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX delta_reconciliation_scope ON delta_reconciliation(scope)")
    }
    private fun createCoverage(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE coverage_state (scope TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("ALTER TABLE inbox ADD COLUMN coverage TEXT")
    }

    data class Selection(val scope: CalendarScope, val summary: CalendarSummary, val enabled: Boolean,
        val status: String, val completedAt: Long?, val nextAttempt: Long = 0, val deferredCount: Int = 0,
        val unverifiedCount: Int = 0, val available: Boolean = true, val historyGap: Boolean = false,
        val coverage: CoverageRun? = null, val pendingProjectionCount: Int = 0) {
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

    suspend fun selections(account: AccountId): List<Selection> = access { db ->
        db.query("calendars", null, "account=?", arrayOf(account.value), null, null, "rowid").use { cursor ->
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
                        countPending(db, CalendarScope(account, id, long("generation")))))
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
        requireKnown(db, scope)
        db.query("mapping_profiles", arrayOf("rescan_pending"), "scope=?", arrayOf(key(scope)),
            null, null, null).use { it.moveToFirst() && it.getInt(0) != 0 }
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
    private fun requeueSupported(db: SQLiteDatabase, scope: CalendarScope) {
        db.update("records",
        ContentValues().apply { put("pending", 1) },
        "scope=? AND NOT EXISTS (SELECT 1 FROM deferred_events d WHERE d.scope=records.scope AND d.event=records.event) " +
            "AND NOT EXISTS (SELECT 1 FROM local_edits l WHERE l.scope=records.scope AND l.event=records.event)", arrayOf(key(scope)))
        suppressInactiveChildren(db, scope)
    }
    suspend fun complete(scope: CalendarScope) = access { db ->
        db.query("calendars", arrayOf("checkpoint"), "scope=?", arrayOf(key(scope)), null, null, null).use {
            it.moveToFirst() && !it.isNull(0) && decodeCheckpoint(scope, it.getString(0)).traversalComplete
        }
    }
    /** Excluded identities remain pending. Filter before the batch limit to avoid starvation. */
    suspend fun pending(scope: CalendarScope, limit: Int = 100, excludedEvents: Set<EventId> = emptySet()): List<ProjectionIntent> = access { db ->
        require(limit in 1..100); requireKnown(db, scope)
        val masters = masterStates(db, scope)
        db.rawQuery("SELECT r.payload,r.event FROM records r LEFT JOIN series_members s ON s.scope=r.scope AND s.event=r.event WHERE r.scope=? AND r.pending=1 AND NOT EXISTS (SELECT 1 FROM local_edits l WHERE l.scope=r.scope AND l.event=r.event) ORDER BY CASE WHEN s.role='master' THEN 0 ELSE 1 END,r.rowid", arrayOf(key(scope))).use { cursor ->
            buildList {
                while (size < limit && cursor.moveToNext()) {
                    if (EventId(cursor.getString(1)) in excludedEvents) continue
                    val change = decodeChange(cursor.getString(0))
                    val recurrence = (change as? RemoteChange.Upsert)?.content?.recurrence
                    if (recurrence == null || recurrence.isMaster || masters[recurrence.uid]?.accepts(recurrence.original) == true)
                        add(ProjectionIntent(scope, change))
                }
            }
        }
    }
    /** Missing or incompatible parents remain visible work without blocking their later pages. */
    suspend fun blockedSeriesCount(scope: CalendarScope): Int = access { db ->
        requireKnown(db, scope)
        val masters = masterStates(db, scope)
        db.rawQuery("SELECT r.payload FROM records r JOIN series_members s ON s.scope=r.scope AND s.event=r.event WHERE r.scope=? AND r.pending=1 AND s.role='exception' AND NOT EXISTS (SELECT 1 FROM local_edits l WHERE l.scope=r.scope AND l.event=r.event)", arrayOf(key(scope))).use { cursor ->
            var count = 0
            while (cursor.moveToNext()) {
                val recurrence = (decodeChange(cursor.getString(0)) as? RemoteChange.Upsert)?.content?.recurrence
                if (recurrence != null && !recurrence.isMaster) {
                    val parent = masters[recurrence.uid]
                    if (parent != MasterState.INACTIVE && parent?.accepts(recurrence.original) != true) count++
                }
            }
            count
        }
    }
    suspend fun deletedSeriesUid(scope: CalendarScope, eventId: EventId): String? = access { db ->
        requireKnown(db, scope)
        seriesMember(db, scope, eventId)?.takeIf { it.role == "master" }?.uid
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
        requireOrdinaryLocalCapture(db, intent.scope, intent.change.eventId)
        val consumed = db.update("records", ContentValues().apply { put("pending", 0) },
            "scope=? AND event=? AND payload=? AND pending=1 AND NOT EXISTS " +
                "(SELECT 1 FROM local_edits l WHERE l.scope=records.scope AND l.event=records.event)",
            arrayOf(key(intent.scope), intent.change.eventId.value, encodeChange(intent.change)))
        if (consumed != 0) when (intent.change) {
            is RemoteChange.Upsert -> db.insertWithOnConflict("projection_bases", null, ContentValues().apply {
                put("scope", key(intent.scope)); put("event", intent.change.eventId.value); put("payload", encodeChange(intent.change))
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            is RemoteChange.Deleted -> db.delete("projection_bases", "scope=? AND event=?", arrayOf(key(intent.scope), intent.change.eventId.value))
        }
        consumed
    }

    /** Last successful projection acknowledgement, independent of newer imported remote evidence. */
    suspend fun projectedBaseline(scope: CalendarScope, eventId: EventId): RemoteChange.Upsert? = access { db ->
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val baseline = readBaseline(db, scope, eventId)
        check(admitTransaction(scope)) { "Inactive import lease" }
        baseline
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

    private fun requireCapturedAcknowledgementSource(db: SQLiteDatabase, baseline: CapturedCreateBaseline) {
        val scope = baseline.scope
        val event = baseline.change.eventId
        check(activeProjectionScope(db, scope) && capturedFormatMatches(db, scope, baseline.format) &&
            readCapturedBase(db, scope, event) == baseline) { "Captured acknowledgement requires identical sent reference" }
        check(!hasLocalMutation(db, scope, event) && !hasRawEventRow(db, "projection_receipts", scope, event)) {
            "Captured acknowledgement cannot bypass unresolved local work"
        }
    }

    private fun readCapturedAcknowledgement(db: SQLiteDatabase, scope: CalendarScope, event: EventId):
        CapturedCreateAcknowledgementRecord? = db.rawQuery(
            "SELECT nonce,protocol,fingerprint,state FROM captured_create_acknowledgements WHERE scope=? AND event=?",
            arrayOf(key(scope), event.value)).use { row ->
            if (!row.moveToFirst()) return@use null
            val baseline = checkNotNull(readCapturedBase(db, scope, event)) { "Captured acknowledgement source is unresolved" }
            val receipt: CapturedCreateAcknowledgementReceipt
            val state: CapturedCreateAcknowledgementState
            try {
                check(row.getString(1) == "1") { "Captured acknowledgement protocol is malformed" }
                receipt = CapturedCreateAcknowledgementReceipt(baseline, row.getString(0), row.getInt(1))
                state = CapturedCreateAcknowledgementState.valueOf(row.getString(3))
            } catch (_: IllegalArgumentException) {
                throw IllegalStateException("Captured acknowledgement evidence is malformed")
            }
            check(row.getString(2) == receipt.fingerprint()) { "Captured acknowledgement source or fingerprint changed" }
            CapturedCreateAcknowledgementRecord(receipt, state)
        }

    private fun requireCapturedAcknowledgementResolved(db: SQLiteDatabase, scope: CalendarScope, event: EventId):
        CapturedCreateAcknowledgementRecord? {
        val record = readCapturedAcknowledgement(db, scope, event)
        check(record == null || record.state == CapturedCreateAcknowledgementState.PROVEN) {
            "Captured acknowledgement outcome is unresolved"
        }
        return record
    }

    /** Generic snapshots carry no captured provenance and cannot adopt captured work, even on replay.
     * Raw presence fences malformed/orphaned rows and historical PROVEN acknowledgements alike.
     */
    private fun requireOrdinaryLocalCapture(db: SQLiteDatabase, scope: CalendarScope, event: EventId) {
        check(!hasRawEventRow(db, "captured_create_bases", scope, event) &&
            !hasRawEventRow(db, "captured_create_acknowledgements", scope, event) &&
            !hasRawEventRow(db, "captured_create_handoffs", scope, event)) {
            "Captured work requires its exact specialized local snapshot"
        }
    }

    /** Retain the first supported local edit/tombstone behind its exact captured sent reference.
     * Only local_edits is changed. Incoming records, raw pending flags, bases and receipts survive.
     * Null means unchanged with no retained local intent; it grants no future clean acknowledgement.
     */
    suspend fun retainCapturedLocalChange(snapshot: CapturedCreateCurrent, mutation: MutationId): LocalEditState? =
        stagingMutation(snapshot.baseline.scope) { db ->
            val baseline = snapshot.baseline
            val link = baseline.receipt.binding.identity
            val scope = baseline.scope
            val event = baseline.change.eventId
            val acknowledgement = requireCapturedAcknowledgementResolved(db, scope, event)
            check(acknowledgement?.receipt == snapshot.acknowledgement) {
                "Captured current acknowledgement source changed"
            }
            require(mutation != link.captureId && mutation != link.attempt.id && mutation.value != link.localId.value) {
                "Local change requires a separate mutation identity"
            }
            check(activeProjectionScope(db, scope) && capturedFormatMatches(db, scope, baseline.format) &&
                readCapturedBase(db, scope, event) == baseline) { "Current local change requires identical captured baseline" }
            check(!hasRawEventRow(db, "projection_receipts", scope, event)) { "Ordinary projection proof is unresolved" }
            if (snapshot.deleted) LocalDeleteIntent(mutation, scope, baseline.format, baseline.change, snapshot.content)
            else if (!snapshot.unchanged) LocalEditIntent(mutation, scope, baseline.format, baseline.change, snapshot.content)
            val kind = if (snapshot.deleted) "DELETE" else "UPDATE"
            val basePayload = encodeChange(baseline.change)
            val localPayload = encodeChange(baseline.change.copy(content = snapshot.content))
            val previous = db.rawQuery("SELECT mutation,adapter,version,base,local,state,kind FROM local_edits WHERE scope=? AND event=?",
                arrayOf(key(scope), event.value)).use { row ->
                if (!row.moveToFirst()) null else {
                    check(!snapshot.unchanged && row.getString(0) == mutation.value && row.getString(1) == baseline.format.id &&
                        row.getInt(2) == baseline.format.version && row.getString(3) == basePayload &&
                        row.getString(4) == localPayload && row.getString(6) == kind) { "Captured local intention is immutable" }
                    LocalEditState.valueOf(row.getString(5))
                }
            }
            if (previous == null) {
                check(!db.rawQuery("SELECT 1 FROM local_edits WHERE scope=? AND mutation=?", arrayOf(key(scope), mutation.value))
                    .use { it.moveToFirst() }) { "Local mutation identity already belongs to other work" }
                if (snapshot.unchanged) return@stagingMutation null
            }
            val hasDeferred = hasRawEventRow(db, "deferred_events", scope, event)
            val exactRemote = db.rawQuery("SELECT payload FROM records WHERE scope=? AND event=?",
                arrayOf(key(scope), event.value)).use { row -> row.moveToFirst() && row.getString(0) == basePayload }
            val state = if (previous == LocalEditState.CONFLICT || hasDeferred || !exactRemote) LocalEditState.CONFLICT else LocalEditState.PAUSED
            if (previous != null) {
                if (previous != state) check(db.update("local_edits", ContentValues().apply { put("state", state.name) },
                    "scope=? AND event=? AND mutation=? AND state=?", arrayOf(key(scope), event.value, mutation.value,
                        previous.name)) == 1) { "Captured local conflict state was not retained" }
            } else {
                db.insertOrThrow("local_edits", null, ContentValues().apply {
                    put("scope", key(scope)); put("mutation", mutation.value); put("event", event.value)
                    put("adapter", baseline.format.id); put("version", baseline.format.version)
                    put("base", basePayload); put("local", localPayload); put("state", state.name); put("kind", kind)
                })
            }
            state
        }

    private fun hasRawEventRow(db: SQLiteDatabase, table: String, scope: CalendarScope, event: EventId): Boolean =
        db.rawQuery("SELECT 1 FROM $table WHERE scope=? AND event=?", arrayOf(key(scope), event.value)).use { it.moveToFirst() }
    private fun hasCapturedBase(db: SQLiteDatabase, scope: CalendarScope, event: EventId) =
        hasRawEventRow(db, "captured_create_bases", scope, event)

    private fun capturedFormatMatches(db: SQLiteDatabase, scope: CalendarScope, format: AdapterFormat): Boolean =
        db.rawQuery("SELECT checkpoint,account,calendar,generation FROM calendars WHERE scope=?", arrayOf(key(scope))).use {
            try { it.moveToFirst() && !it.isNull(0) && it.getString(1) == scope.account.value &&
                it.getString(2) == scope.calendar.value && it.getLong(3) == scope.generation &&
                decodeCheckpoint(scope, it.getString(0)).format == format }
            catch (_: IllegalArgumentException) { false }
            catch (_: JSONException) { false }
        }

    private fun readCapturedBase(db: SQLiteDatabase, scope: CalendarScope, event: EventId): CapturedCreateBaseline? {
        if (hasRawEventRow(db, "captured_create_handoffs", scope, event)) return null
        if (!activeProjectionScope(db, scope)) return null
        return db.query("captured_create_bases", arrayOf("account", "calendar", "generation", "attempt", "adapter", "version",
            "payload_fingerprint", "local_id", "capture_id", "capture_adapter", "capture_version", "timestamp",
            "content_fingerprint", "fingerprint_version", "verified_revision", "nonce", "protocol", "payload"),
            "scope=? AND event=?", arrayOf(key(scope), event.value), null, null, null).use { row ->
            if (!row.moveToFirst()) null else {
                val baseline = decodeCapturedBase(row, scope, event) ?: return@use null
                if (!capturedFormatMatches(db, scope, baseline.format)) return@use null
                db.rawQuery("SELECT payload FROM projection_bases WHERE scope=? AND event=?", arrayOf(key(scope), event.value)).use { base ->
                    if (!base.moveToFirst() || base.getString(0) != encodeChange(baseline.change)) null else baseline
                }
            }
        }
    }

    private fun decodeCapturedBase(row: Cursor, scope: CalendarScope, event: EventId): CapturedCreateBaseline? = try {
        val storedScope = CalendarScope(AccountId(row.getString(0)), CalendarId(row.getString(1)), row.getLong(2))
        if (storedScope != scope) null else {
            val attempt = CreateAttemptIdentity(scope, MutationId(row.getString(3)),
                AdapterFormat(row.getString(4), row.getInt(5)), row.getString(6))
            val link = CapturedCreateDispatchIdentity(attempt, LocalEventId(row.getString(7)), MutationId(row.getString(8)),
                AdapterFormat(row.getString(9), row.getInt(10)), Instant.ofEpochSecond(row.getLong(11)), row.getString(12), row.getInt(13))
            val binding = CapturedCreateBinding(link, event, Revision(row.getString(14)))
            val receipt = CapturedCreateAttachmentReceipt(binding, row.getString(15), row.getInt(16))
            val change = decodeChange(row.getString(17)) as? RemoteChange.Upsert
            if (change == null || change.eventId != event || row.getString(17) != encodeChange(change)) null
            else CapturedCreateBaseline(receipt, change)
        }
    } catch (_: IllegalArgumentException) { null }
    catch (_: DateTimeException) { null }
    catch (_: JSONException) { null }
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
            val existing = readCapturedHandoff(db, receipt.scope, receipt.eventId)
            if (existing != null) {
                check(existing.frame.receipt.acknowledgement == receipt.acknowledgement) { "Handoff source is immutable" }
                if (existing.state == CapturedCreateHandoffState.PENDING) requireHandoffNoLocalWork(db, receipt.scope, receipt.eventId)
                return@stagingMutation existing
            }
            requireCapturedAcknowledgementSource(db, receipt.acknowledgement.baseline)
            check(readCapturedAcknowledgement(db, receipt.scope, receipt.eventId) ==
                CapturedCreateAcknowledgementRecord(receipt.acknowledgement, CapturedCreateAcknowledgementState.PROVEN)) {
                "Handoff requires exact proven acknowledgement"
            }
            db.insertOrThrow("captured_create_handoffs", null, ContentValues().apply {
                put("scope", key(receipt.scope)); put("event", receipt.eventId.value); put("nonce", receipt.nonce)
                put("protocol", receipt.protocol); put("ack_nonce", receipt.acknowledgement.nonce)
                put("ack_fingerprint", receipt.acknowledgement.fingerprint()); put("fingerprint", receipt.fingerprint())
                put("state", CapturedCreateHandoffState.PENDING.name); put("waiting", 0); putNull("last_projection")
            })
            checkNotNull(readCapturedHandoff(db, receipt.scope, receipt.eventId))
        }

    suspend fun retainCapturedHandoff(receipt: CapturedCreateHandoffReceipt,
        witness: CapturedCreateCleanWitness): CapturedCreateHandoffRecord = stagingMutation(receipt.scope) { db ->
        check(witness.receipt == receipt.acknowledgement) { "Handoff clean source differs" }
        val existing = requireHandoffFrame(db, CapturedCreateHandoffFrame(receipt, receipt.acknowledgement.baseline.change))
        requireHandoffNoLocalWork(db, receipt.scope, receipt.eventId)
        if (existing.state == CapturedCreateHandoffState.PENDING) {
            check(db.update("captured_create_handoffs", ContentValues().apply { put("state", "RELEASED") },
                "scope=? AND event=? AND nonce=? AND state='PENDING'", arrayOf(key(receipt.scope), receipt.eventId.value, receipt.nonce)) == 1)
        }
        checkNotNull(readCapturedHandoff(db, receipt.scope, receipt.eventId))
    }

    /** Current pending content is read afresh after receipt recovery; stale intents cannot prepare. */
    suspend fun capturedHandoffPending(frame: CapturedCreateHandoffFrame): ProjectionIntent? = access { db ->
        check(admitTransaction(frame.scope)) { "Inactive import lease" }; requireKnown(db, frame.scope)
        requireHandoffFrame(db, frame)
        val pending = readHandoffPending(db, frame.scope, frame.eventId)
        check(admitTransaction(frame.scope)) { "Inactive import lease" }; pending
    }

    suspend fun capturedHandoffCanProject(frame: CapturedCreateHandoffFrame): Boolean = stagingMutation(frame.scope) { db ->
        val record = requireHandoffFrame(db, frame)
        val outstanding = readHandoffProjectionReceipt(db, frame.scope, frame.eventId)
        outstanding?.let { CapturedHandoffProjection(frame, it) }
        val pending = readHandoffPending(db, frame.scope, frame.eventId)
        record.state == CapturedCreateHandoffState.RELEASED && !record.waiting &&
            !hasLocalMutation(db, frame.scope, frame.eventId) && !hasRawEventRow(db, "deferred_events", frame.scope, frame.eventId) &&
            (pending != null || outstanding == null) && pending?.change?.let(::supportsCapturedHandoff) != false
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
        val record = requireHandoffFrame(db, frame)
        check(record.state == CapturedCreateHandoffState.RELEASED && !record.waiting) { "Handoff cannot project" }
        check(intent.scope == frame.scope && intent.change.eventId == frame.eventId && supportsCapturedHandoff(intent.change)) {
            "Unsupported handoff projection"
        }
        check(!hasLocalMutation(db, frame.scope, frame.eventId) && !hasRawEventRow(db, "deferred_events", frame.scope, frame.eventId) &&
            readHandoffPending(db, frame.scope, frame.eventId) == intent) { "Stale or blocked handoff projection" }
        val existing = readHandoffProjectionReceipt(db, frame.scope, frame.eventId)
        if (existing != null) {
            val plan = CapturedHandoffProjection(frame, existing)
            check(plan.receipt.change == intent.change) { "Unresolved handoff projection" }; return@stagingMutation plan
        }
        val receipt = ProjectionReceipt(frame.scope, frame.format, intent.change as RemoteChange.Upsert,
            UUID.randomUUID().toString(), frame.base)
        val plan = CapturedHandoffProjection(frame, receipt)
        db.insertOrThrow("projection_receipts", null, ContentValues().apply {
            put("scope", key(frame.scope)); put("event", frame.eventId.value); put("nonce", receipt.nonce)
            put("payload", encodeChange(receipt.change)); put("adapter", frame.format.id); put("version", frame.format.version)
            put("previous_base", encodeChange(frame.base)); put("protocol", receipt.protocol)
        })
        plan
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
            check(db.update("projection_bases", ContentValues().apply { put("payload", encodeChange(receipt.change)) },
                "scope=? AND event=? AND payload=?", arrayOf(key(frame.scope), frame.eventId.value, encodeChange(frame.base))) == 1)
            check(db.update("captured_create_handoffs", ContentValues().apply { put("last_projection", encodeHandoffProjection(receipt)) },
                "scope=? AND event=? AND nonce=?", arrayOf(key(frame.scope), frame.eventId.value, frame.receipt.nonce)) == 1)
            db.update("records", ContentValues().apply { put("pending", 0) }, "scope=? AND event=? AND payload=? AND pending=1",
                arrayOf(key(frame.scope), frame.eventId.value, encodeChange(receipt.change)))
            check(db.delete("projection_receipts", "scope=? AND event=? AND nonce=? AND payload=? AND adapter=? AND version=? AND previous_base IS ? AND protocol=?",
                arrayOf(key(frame.scope), frame.eventId.value, receipt.nonce, encodeChange(receipt.change), receipt.format.id,
                    receipt.format.version.toString(), encodeChange(frame.base), receipt.protocol.toString())) == 1)
            true
        }

    suspend fun waitCapturedHandoff(frame: CapturedCreateHandoffFrame): Boolean = stagingMutation(frame.scope) { db ->
        val record = requireHandoffFrame(db, frame)
        if (record.state != CapturedCreateHandoffState.RELEASED) return@stagingMutation false
        if (!record.waiting) check(db.update("captured_create_handoffs", ContentValues().apply { put("waiting", 1) },
            "scope=? AND event=? AND nonce=? AND waiting=0", arrayOf(key(frame.scope), frame.eventId.value, frame.receipt.nonce)) == 1)
        true
    }

    suspend fun resumeCapturedHandoff(witness: CapturedHandoffCleanWitness): Boolean = stagingMutation(witness.frame.scope) { db ->
        val frame = witness.frame; val record = requireHandoffFrame(db, frame)
        if (record.state != CapturedCreateHandoffState.RELEASED) return@stagingMutation false
        requireHandoffNoLocalWork(db, frame.scope, frame.eventId)
        if (record.waiting) check(db.update("captured_create_handoffs", ContentValues().apply { put("waiting", 0) },
            "scope=? AND event=? AND nonce=? AND waiting=1", arrayOf(key(frame.scope), frame.eventId.value, frame.receipt.nonce)) == 1)
        true
    }

    /** Captures only a current full frame. Neither the origin nor any incoming queue is rewritten. */
    suspend fun retainCapturedHandoffLocalChange(snapshot: CapturedHandoffCurrent, mutation: MutationId): LocalEditState? =
        stagingMutation(snapshot.frame.scope) { db ->
            val frame = snapshot.frame; val scope = frame.scope; val event = frame.eventId
            check(requireHandoffFrame(db, frame).state == CapturedCreateHandoffState.RELEASED) { "Handoff is pending" }
            check(!hasRawEventRow(db, "projection_receipts", scope, event)) { "Handoff projection proof is unresolved" }
            val link = frame.receipt.acknowledgement.baseline.receipt.binding.identity
            require(mutation != link.captureId && mutation != link.attempt.id && mutation.value != link.localId.value) {
                "Local change requires a separate mutation identity"
            }
            if (snapshot.deleted) LocalDeleteIntent(mutation, scope, frame.format, frame.base, snapshot.content)
            else if (!snapshot.unchanged) LocalEditIntent(mutation, scope, frame.format, frame.base, snapshot.content)
            val kind = if (snapshot.deleted) "DELETE" else "UPDATE"
            val base = encodeChange(frame.base); val local = encodeChange(frame.base.copy(content = snapshot.content))
            val previous = db.rawQuery("SELECT mutation,adapter,version,base,local,state,kind FROM local_edits WHERE scope=? AND event=?",
                arrayOf(key(scope), event.value)).use { row ->
                if (!row.moveToFirst()) null else {
                    check(!snapshot.unchanged && row.getString(0) == mutation.value && row.getString(1) == frame.format.id &&
                        row.getString(2) == frame.format.version.toString() && row.getString(3) == base && row.getString(4) == local &&
                        row.getString(6) == kind) { "Handoff local intention is immutable" }
                    LocalEditState.valueOf(row.getString(5))
                }
            }
            if (previous == null) {
                check(!db.rawQuery("SELECT 1 FROM local_edits WHERE scope=? AND mutation=?", arrayOf(key(scope), mutation.value)).use { it.moveToFirst() }) {
                    "Local mutation identity already belongs to other work"
                }
                if (snapshot.unchanged) return@stagingMutation null
            }
            val exactRemote = db.rawQuery("SELECT payload FROM records WHERE scope=? AND event=?", arrayOf(key(scope), event.value))
                .use { it.moveToFirst() && it.getString(0) == base }
            val state = if (previous == LocalEditState.CONFLICT || !exactRemote || hasRawEventRow(db, "deferred_events", scope, event))
                LocalEditState.CONFLICT else LocalEditState.PAUSED
            if (previous != null) {
                if (state != previous) check(db.update("local_edits", ContentValues().apply { put("state", state.name) },
                    "scope=? AND event=? AND mutation=? AND state=?", arrayOf(key(scope), event.value, mutation.value, previous.name)) == 1)
            } else db.insertOrThrow("local_edits", null, ContentValues().apply {
                put("scope", key(scope)); put("event", event.value); put("mutation", mutation.value)
                put("adapter", frame.format.id); put("version", frame.format.version); put("base", base)
                put("local", local); put("state", state.name); put("kind", kind)
            })
            check(db.update("captured_create_handoffs", ContentValues().apply { put("waiting", 1) },
                "scope=? AND event=? AND nonce=?", arrayOf(key(scope), event.value, frame.receipt.nonce)) == 1)
            state
        }

    private fun requireHandoffNoLocalWork(db: SQLiteDatabase, scope: CalendarScope, event: EventId) {
        check(!hasLocalMutation(db, scope, event) && !hasRawEventRow(db, "projection_receipts", scope, event)) {
            "Handoff cannot bypass unresolved local work"
        }
    }
    private fun requireHandoffFrame(db: SQLiteDatabase, frame: CapturedCreateHandoffFrame): CapturedCreateHandoffRecord {
        val record = checkNotNull(readCapturedHandoff(db, frame.scope, frame.eventId)) { "Handoff source is absent" }
        check(record.frame == frame) { "Handoff current reference changed" }; return record
    }
    private fun readHandoffPending(db: SQLiteDatabase, scope: CalendarScope, event: EventId): ProjectionIntent? =
        db.rawQuery("SELECT payload FROM records WHERE scope=? AND event=? AND pending=1", arrayOf(key(scope), event.value)).use {
            if (!it.moveToFirst()) null else {
                val change = decodeChange(it.getString(0))
                check(change.eventId == event && encodeChange(change) == it.getString(0)) { "Handoff pending evidence is malformed" }
                ProjectionIntent(scope, change)
            }
        }
    private fun readHandoffProjectionReceipt(db: SQLiteDatabase, scope: CalendarScope, event: EventId): ProjectionReceipt? {
        val receipt = readProjectionReceipt(db, scope, event) ?: return null
        db.rawQuery("SELECT nonce,payload,adapter,version,previous_base,protocol FROM projection_receipts WHERE scope=? AND event=?",
            arrayOf(key(scope), event.value)).use {
            check(it.moveToFirst() && it.getString(0) == receipt.nonce && it.getString(1) == encodeChange(receipt.change) &&
                it.getString(2) == receipt.format.id && it.getString(3) == receipt.format.version.toString() &&
                !it.isNull(4) && it.getString(4) == receipt.previousBase?.let(::encodeChange) && it.getString(5) == "1") {
                "Handoff prepared proof is malformed"
            }
        }
        return receipt
    }

    /** Origin decoding deliberately does not compare projection_bases, which advances after transfer. */
    private fun readCapturedHistoricalBase(db: SQLiteDatabase, scope: CalendarScope, event: EventId): CapturedCreateBaseline? =
        db.query("captured_create_bases", arrayOf("account", "calendar", "generation", "attempt", "adapter", "version",
            "payload_fingerprint", "local_id", "capture_id", "capture_adapter", "capture_version", "timestamp",
            "content_fingerprint", "fingerprint_version", "verified_revision", "nonce", "protocol", "payload"),
            "scope=? AND event=?", arrayOf(key(scope), event.value), null, null, null).use {
            if (!it.moveToFirst()) null else checkNotNull(decodeCapturedBase(it, scope, event)) { "Handoff origin is malformed" }.also { baseline ->
                val identity = baseline.receipt.binding.identity
                check(it.getString(2) == scope.generation.toString() && it.getString(5) == identity.attempt.format.version.toString() &&
                    it.getString(10) == identity.captureFormat.version.toString() && it.getString(11) == identity.timestamp.epochSecond.toString() &&
                    it.getString(13) == identity.fingerprintVersion.toString() && it.getString(16) == baseline.receipt.protocol.toString()) {
                    "Handoff origin numeric evidence is malformed"
                }
            }
        }

    private fun readCapturedHandoff(db: SQLiteDatabase, scope: CalendarScope, event: EventId): CapturedCreateHandoffRecord? =
        db.rawQuery("SELECT nonce,protocol,ack_nonce,ack_fingerprint,fingerprint,state,waiting,last_projection " +
            "FROM captured_create_handoffs WHERE scope=? AND event=?", arrayOf(key(scope), event.value)).use { row ->
            if (!row.moveToFirst()) return@use null
            check(activeProjectionScope(db, scope)) { "Inactive handoff scope" }
            val origin = checkNotNull(readCapturedHistoricalBase(db, scope, event)) { "Handoff origin is absent" }
            check(capturedFormatMatches(db, scope, origin.format)) { "Incompatible handoff format" }
            val acknowledgement = db.rawQuery("SELECT nonce,protocol,fingerprint,state FROM captured_create_acknowledgements WHERE scope=? AND event=?",
                arrayOf(key(scope), event.value)).use { ack ->
                check(ack.moveToFirst() && ack.getString(1) == "1" && ack.getString(3) == "PROVEN") { "Handoff acknowledgement is unresolved" }
                CapturedCreateAcknowledgementReceipt(origin, ack.getString(0), ack.getInt(1)).also {
                    check(ack.getString(2) == it.fingerprint()) { "Handoff acknowledgement source changed" }
                }
            }
            check(row.getString(1) == "1" && row.getString(2) == acknowledgement.nonce && row.getString(3) == acknowledgement.fingerprint()) {
                "Handoff source is malformed"
            }
            val receipt = CapturedCreateHandoffReceipt(acknowledgement, row.getString(0), row.getInt(1))
            check(row.getString(4) == receipt.fingerprint() && row.getString(6) in listOf("0", "1")) { "Handoff proof is malformed" }
            val base = db.rawQuery("SELECT payload FROM projection_bases WHERE scope=? AND event=?", arrayOf(key(scope), event.value)).use {
                check(it.moveToFirst()) { "Handoff current reference is absent" }
                val change = decodeChange(it.getString(0)) as? RemoteChange.Upsert
                check(change != null && change.eventId == event && encodeChange(change) == it.getString(0)) { "Handoff current reference is malformed" }
                change
            }
            val last = if (row.isNull(7)) null else decodeHandoffProjection(scope, row.getString(7))
            CapturedCreateHandoffRecord(CapturedCreateHandoffFrame(receipt, base, last),
                CapturedCreateHandoffState.valueOf(row.getString(5)), row.getString(6) == "1")
        }

    private fun encodeHandoffProjection(receipt: ProjectionReceipt): String = JSONObject().apply {
        put("adapter", receipt.format.id); put("version", receipt.format.version); put("nonce", receipt.nonce)
        put("protocol", receipt.protocol); put("change", encodeChange(receipt.change))
        put("previous", receipt.previousBase?.let(::encodeChange) ?: JSONObject.NULL)
    }.toString()
    private fun decodeHandoffProjection(scope: CalendarScope, payload: String): ProjectionReceipt {
        val json = JSONObject(payload)
        check(json.length() == 6) { "Handoff projection proof is malformed" }
        val receipt = ProjectionReceipt(scope, AdapterFormat(json.getString("adapter"), json.getInt("version")),
            decodeChange(json.getString("change")) as RemoteChange.Upsert, json.getString("nonce"),
            if (json.isNull("previous")) null else decodeChange(json.getString("previous")) as RemoteChange.Upsert, json.getInt("protocol"))
        check(encodeHandoffProjection(receipt) == payload) { "Handoff projection proof is not canonical" }; return receipt
    }

    /** Persist before Provider apply. Preparation alone is never evidence that the row was written. */
    suspend fun prepareProjection(intent: ProjectionIntent, format: AdapterFormat): ProjectionReceipt =
        stagingMutation(intent.scope) { db ->
            requireOrdinaryLocalCapture(db, intent.scope, intent.change.eventId)
            check(activeProjectionScope(db, intent.scope)) { "Inactive projection scope" }
            require(ProjectionReceipt.supports(intent.change)) { "Unsupported projection receipt content" }
            require(projectionFormatMatches(db, intent.scope, format)) { "Incompatible projection format" }
            val change = intent.change as RemoteChange.Upsert
            val pending = db.rawQuery("SELECT 1 FROM records r WHERE r.scope=? AND r.event=? AND r.payload=? AND r.pending=1 " +
                "AND NOT EXISTS (SELECT 1 FROM local_edits l WHERE l.scope=r.scope AND l.event=r.event) " +
                "AND NOT EXISTS (SELECT 1 FROM deferred_events d WHERE d.scope=r.scope AND d.event=r.event)",
                arrayOf(key(intent.scope), change.eventId.value, encodeChange(change))).use { it.moveToFirst() }
            require(pending) { "Stale or blocked projection intent" }
            val existing = readProjectionReceipt(db, intent.scope, change.eventId)
            if (existing != null) {
                require(existing.change == change && existing.format == format) { "Unresolved projection receipt" }
                return@stagingMutation existing
            }
            val receipt = ProjectionReceipt(intent.scope, format, change, UUID.randomUUID().toString(),
                readBaseline(db, intent.scope, change.eventId))
            db.insertOrThrow("projection_receipts", null, ContentValues().apply {
                put("scope", key(receipt.scope)); put("event", receipt.change.eventId.value); put("nonce", receipt.nonce)
                put("payload", encodeChange(receipt.change)); put("adapter", receipt.format.id); put("version", receipt.format.version)
                receipt.previousBase?.let { put("previous_base", encodeChange(it)) } ?: putNull("previous_base")
                put("protocol", receipt.protocol)
            })
            receipt
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
    suspend fun restoreProjection(witness: AppliedProjectionWitness): Boolean = stagingMutation(witness.receipt.scope) { db ->
        val receipt = witness.receipt
        requireOrdinaryLocalCapture(db, receipt.scope, receipt.change.eventId)
        if (readProjectionReceipt(db, receipt.scope, receipt.change.eventId) != receipt) return@stagingMutation false
        if (!activeProjectionScope(db, receipt.scope) || !projectionFormatMatches(db, receipt.scope, receipt.format) ||
            hasLocalMutation(db, receipt.scope, receipt.change.eventId)) return@stagingMutation false
        val baseline = readBaseline(db, receipt.scope, receipt.change.eventId)
        if (baseline != receipt.previousBase && baseline != receipt.change) return@stagingMutation false
        db.insertWithOnConflict("projection_bases", null, ContentValues().apply {
            put("scope", key(receipt.scope)); put("event", receipt.change.eventId.value); put("payload", encodeChange(receipt.change))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        db.update("records", ContentValues().apply { put("pending", 0) },
            "scope=? AND event=? AND payload=? AND pending=1",
            arrayOf(key(receipt.scope), receipt.change.eventId.value, encodeChange(receipt.change)))
        check(db.delete("projection_receipts", "scope=? AND event=? AND nonce=? AND payload=? AND adapter=? AND version=? AND previous_base IS ? AND protocol=?",
            arrayOf(key(receipt.scope), receipt.change.eventId.value, receipt.nonce, encodeChange(receipt.change),
                receipt.format.id, receipt.format.version.toString(), receipt.previousBase?.let(::encodeChange), receipt.protocol.toString())) == 1)
        true
    }
    private fun activeProjectionScope(db: SQLiteDatabase, scope: CalendarScope): Boolean = db.rawQuery(
        "SELECT 1 FROM calendars WHERE scope=? AND enabled=1 AND available=1", arrayOf(key(scope)),
    ).use { it.moveToFirst() }
    private fun projectionFormatMatches(db: SQLiteDatabase, scope: CalendarScope, format: AdapterFormat): Boolean = db.rawQuery(
        "SELECT checkpoint FROM calendars WHERE scope=?", arrayOf(key(scope)),
    ).use { it.moveToFirst() && (it.isNull(0) || decodeCheckpoint(scope, it.getString(0)).format == format) }
    private fun readProjectionReceipt(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): ProjectionReceipt? = db.rawQuery(
        "SELECT nonce,payload,adapter,version,previous_base,protocol FROM projection_receipts WHERE scope=? AND event=?",
        arrayOf(key(scope), eventId.value),
    ).use {
        if (!it.moveToFirst()) null else ProjectionReceipt(scope, AdapterFormat(it.getString(2), it.getInt(3)),
            decodeChange(it.getString(1)) as RemoteChange.Upsert, it.getString(0),
            if (it.isNull(4)) null else decodeChange(it.getString(4)) as RemoteChange.Upsert, it.getInt(5)).also { receipt ->
            check(receipt.change.eventId == eventId) { "Projection receipt identity mismatch" }
        }
    }
    suspend fun localEdit(scope: CalendarScope, eventId: EventId): LocalEditIntent? = access { db ->
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val edit = readLocalEdit(db, scope, eventId)
        check(admitTransaction(scope)) { "Inactive import lease" }
        edit
    }
    /** Atomic historical SQL read, including paused/unavailable calendars. No preparation/upload admission. */
    suspend fun inspectLocalEdit(scope: CalendarScope, eventId: EventId, mutationId: MutationId): LocalEditReview? =
        stagingMutation(scope) { db ->
            val update = db.rawQuery("SELECT kind FROM local_edits WHERE scope=? AND event=?",
                arrayOf(key(scope), eventId.value)).use { it.moveToFirst() && it.getString(0) == "UPDATE" }
            if (!update) return@stagingMutation null
            val owner = readPreparationOwner(db, scope, eventId) ?: return@stagingMutation null
            if (owner.id != mutationId) return@stagingMutation null
            val imported = stagedQuery(db, "SELECT payload FROM records WHERE scope=? AND event=?",
                arrayOf(key(scope), eventId.value)).use { if (it.moveToFirst()) decodeChange(it.getString(0)) else null }
            val format = db.rawQuery("SELECT checkpoint FROM calendars WHERE scope=?", arrayOf(key(scope))).use {
                if (!it.moveToFirst() || it.isNull(0)) null else decodeCheckpoint(scope, it.getString(0)).format
            }
            val retained = readLocalEditPreparation(db, scope, eventId)
            LocalEditReview(owner, readBaseline(db, scope, eventId), imported, format,
                hasRawEventRow(db, "deferred_events", scope, eventId),
                retained?.let { RetainedLocalTitleInfo(it.format, it.timestamp) })
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
            LocalTitlePreparationAdmission.CONFLICT
        }
    private fun localTitlePreparationAdmission(db: SQLiteDatabase, intent: LocalEditIntent): LocalTitlePreparationAdmission {
        val target = intent.scope
        if (!activeProjectionScope(db, target)) return LocalTitlePreparationAdmission.INACTIVE
        val format = db.rawQuery("SELECT checkpoint FROM calendars WHERE scope=?", arrayOf(key(target))).use {
            if (!it.moveToFirst() || it.isNull(0)) null else decodeCheckpoint(target, it.getString(0)).format
        }
        if (format != intent.format) return LocalTitlePreparationAdmission.UNQUALIFIED
        val owner = readPreparationOwner(db, target, intent.base.eventId) ?: return LocalTitlePreparationAdmission.UNQUALIFIED
        if (owner != intent) return if (owner.state == LocalEditState.CONFLICT && owner.copy(state = LocalEditState.PAUSED) == intent)
            LocalTitlePreparationAdmission.CONFLICT else LocalTitlePreparationAdmission.UNQUALIFIED
        if (owner.state == LocalEditState.CONFLICT) return LocalTitlePreparationAdmission.CONFLICT
        if (readBaseline(db, target, intent.base.eventId) != intent.base) return LocalTitlePreparationAdmission.UNQUALIFIED
        val remote = stagedQuery(db, "SELECT payload FROM records WHERE scope=? AND event=?",
            arrayOf(key(target), intent.base.eventId.value)).use { if (it.moveToFirst()) decodeChange(it.getString(0)) else null }
        if (remote != intent.base || hasRawEventRow(db, "deferred_events", target, intent.base.eventId))
            return LocalTitlePreparationAdmission.CONFLICT
        // A cache hit is historical evidence, never a fresh crypto/access qualification.
        if (readLocalEditPreparation(db, target, intent.base.eventId) != null) return LocalTitlePreparationAdmission.HISTORICAL
        return LocalTitlePreparationAdmission.ELIGIBLE
    }
    private fun insertLocalTitlePreparation(db: SQLiteDatabase, value: LocalEditPreparation, base: String, local: String) {
        val intent = value.intent
        db.insertOrThrow("local_edit_preparations", null, ContentValues().apply {
            put("scope", key(intent.scope)); put("mutation", intent.id.value); put("event", intent.base.eventId.value)
            put("adapter", intent.format.id); put("version", intent.format.version)
            put("base", base); put("local", local); put("state", intent.state.name)
            put("timestamp", value.timestamp.toString()); put("prep_adapter", value.format.id)
            put("prep_version", value.format.version); put("payload", value.protectedPayload)
        })
        checkStagingCapacity(db)
    }
    /** Historical inspection only: a frozen PAUSED snapshot never resets the current conflict state. */
    suspend fun localEditPreparation(scope: CalendarScope, eventId: EventId): LocalEditPreparation? =
        stagingMutation(scope) { db -> readLocalEditPreparation(db, scope, eventId) }

    private fun requirePreparationSnapshotBudget(value: String) {
        require(value.length <= LocalEditPreparation.MAX_PAYLOAD_BYTES) { "Local preparation observation is too large" }
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining()
        require(bytes <= LocalEditPreparation.MAX_PAYLOAD_BYTES) { "Local preparation observation is too large" }
    }
    private fun preparationVersion(cursor: Cursor, column: Int): Int {
        require(cursor.getType(column) == Cursor.FIELD_TYPE_INTEGER && cursor.getLong(column) in 1..Int.MAX_VALUE.toLong())
        return cursor.getInt(column)
    }
    private fun readPreparationOwner(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): LocalEditIntent? =
        try {
            stagedQuery(db, "SELECT mutation,adapter,version,base,local,state,kind FROM local_edits WHERE scope=? AND event=?",
                arrayOf(key(scope), eventId.value)).use { row ->
                if (!row.moveToFirst()) null else {
                    require(row.getString(6) == "UPDATE")
                    val baseJson = row.getString(3).also(::requirePreparationSnapshotBudget)
                    val localJson = row.getString(4).also(::requirePreparationSnapshotBudget)
                    val base = decodeChange(baseJson) as RemoteChange.Upsert
                    val local = decodeChange(localJson) as RemoteChange.Upsert
                    require(base.eventId == eventId && local.eventId == eventId && local.revision == base.revision)
                    LocalEditIntent(MutationId(row.getString(0)), scope, AdapterFormat(row.getString(1), preparationVersion(row, 2)),
                        base, local.content, LocalEditState.valueOf(row.getString(5)))
                }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: SQLiteException) { throw failure }
        catch (_: Exception) { throw IllegalStateException("Invalid local preparation owner") }

    private fun readLocalEditPreparation(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): LocalEditPreparation? =
        try {
            stagedQuery(db, "SELECT mutation,adapter,version,base,local,state,timestamp,prep_adapter,prep_version,payload FROM local_edit_preparations WHERE scope=? AND event=?",
                arrayOf(key(scope), eventId.value)).use { row ->
                if (!row.moveToFirst()) null else {
                    val baseJson = row.getString(3).also(::requirePreparationSnapshotBudget)
                    val localJson = row.getString(4).also(::requirePreparationSnapshotBudget)
                    val base = decodeChange(baseJson) as RemoteChange.Upsert
                    val local = decodeChange(localJson) as RemoteChange.Upsert
                    require(base.eventId == eventId && local.eventId == eventId && local.revision == base.revision)
                    val frozen = LocalEditIntent(MutationId(row.getString(0)), scope,
                        AdapterFormat(row.getString(1), preparationVersion(row, 2)), base, local.content,
                        LocalEditState.valueOf(row.getString(5)))
                    val timestamp = Instant.parse(row.getString(6))
                    require(timestamp.toString() == row.getString(6)) { "Noncanonical local preparation time" }
                    val preparation = LocalEditPreparation(frozen, timestamp,
                        AdapterFormat(row.getString(7), preparationVersion(row, 8)), row.getString(9))
                    val owner = readPreparationOwner(db, scope, eventId)
                    require(owner?.copy(state = LocalEditState.PAUSED) == frozen) { "Local preparation owner mismatch" }
                    preparation
                }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: SQLiteException) { throw failure }
        catch (_: Exception) { throw IllegalStateException("Invalid retained edit preparation") }
    suspend fun localDelete(scope: CalendarScope, eventId: EventId): LocalDeleteIntent? = access { db ->
        check(admitTransaction(scope)) { "Inactive import lease" }; requireKnown(db, scope)
        val deletion = readLocalDelete(db, scope, eventId)
        check(admitTransaction(scope)) { "Inactive import lease" }
        deletion
    }
    /** Capture and projection suppression share one database transaction. No dispatch/reset exists. */
    suspend fun captureLocalEdit(intent: LocalEditIntent): LocalEditIntent = stagingMutation(intent.scope) { db ->
        require(intent.state == LocalEditState.PAUSED) { "Caller cannot declare conflict state" }
        check(isAvailable(db, intent.scope) && db.rawQuery("SELECT enabled FROM calendars WHERE scope=?",
            arrayOf(key(intent.scope))).use { it.moveToFirst() && it.getInt(0) != 0 }) { "Inactive local capture scope" }
        requireOrdinaryLocalCapture(db, intent.scope, intent.base.eventId)
        val previous = readLocalEdit(db, intent.scope, intent.base.eventId)
        if (previous != null) {
            require(previous.copy(state = LocalEditState.PAUSED) == intent) { "Local edit is immutable" }
            return@stagingMutation previous
        }
        require(!hasLocalMutation(db, intent.scope, intent.base.eventId)) { "Local mutation kind collision" }
        require(readBaseline(db, intent.scope, intent.base.eventId) == intent.base) { "Stale projection baseline" }
        val hasDeferred = db.rawQuery("SELECT 1 FROM deferred_events WHERE scope=? AND event=?",
            arrayOf(key(intent.scope), intent.base.eventId.value)).use { it.moveToFirst() }
        val remote = db.rawQuery("SELECT payload FROM records WHERE scope=? AND event=?",
            arrayOf(key(intent.scope), intent.base.eventId.value)).use { if (it.moveToFirst()) decodeChange(it.getString(0)) else null }
        val captured = intent.copy(state = if (hasDeferred || remote != intent.base) LocalEditState.CONFLICT else LocalEditState.PAUSED)
        db.insertOrThrow("local_edits", null, ContentValues().apply {
            put("kind", "UPDATE")
            put("scope", key(intent.scope)); put("mutation", intent.id.value); put("event", intent.base.eventId.value)
            put("adapter", intent.format.id); put("version", intent.format.version); put("base", encodeChange(intent.base))
            put("local", encodeChange(intent.base.copy(content = intent.content))); put("state", captured.state.name)
        })
        db.update("records", ContentValues().apply { put("pending", 0) }, "scope=? AND event=?",
            arrayOf(key(intent.scope), intent.base.eventId.value))
        captured
    }
    /** Retain a qualified Android tombstone snapshot without consuming its flags or sending DELETE. */
    suspend fun captureLocalDelete(intent: LocalDeleteIntent): LocalDeleteIntent = stagingMutation(intent.scope) { db ->
        require(intent.state == LocalEditState.PAUSED) { "Caller cannot declare conflict state" }
        check(activeProjectionScope(db, intent.scope)) { "Inactive local capture scope" }
        requireOrdinaryLocalCapture(db, intent.scope, intent.base.eventId)
        val previous = readLocalDelete(db, intent.scope, intent.base.eventId)
        if (previous != null) {
            require(previous.copy(state = LocalEditState.PAUSED) == intent) { "Local deletion is immutable" }
            return@stagingMutation previous
        }
        require(!hasLocalMutation(db, intent.scope, intent.base.eventId)) { "Local mutation kind collision" }
        require(readBaseline(db, intent.scope, intent.base.eventId) == intent.base) { "Stale projection baseline" }
        val hasDeferred = db.rawQuery("SELECT 1 FROM deferred_events WHERE scope=? AND event=?",
            arrayOf(key(intent.scope), intent.base.eventId.value)).use { it.moveToFirst() }
        val remote = db.rawQuery("SELECT payload FROM records WHERE scope=? AND event=?",
            arrayOf(key(intent.scope), intent.base.eventId.value)).use { if (it.moveToFirst()) decodeChange(it.getString(0)) else null }
        val captured = intent.copy(state = if (hasDeferred || remote != intent.base) LocalEditState.CONFLICT else LocalEditState.PAUSED)
        db.insertOrThrow("local_edits", null, ContentValues().apply {
            put("scope", key(intent.scope)); put("mutation", intent.id.value); put("event", intent.base.eventId.value); put("kind", "DELETE")
            put("adapter", intent.format.id); put("version", intent.format.version); put("base", encodeChange(intent.base))
            put("local", encodeChange(intent.base.copy(content = intent.content))); put("state", captured.state.name)
        })
        db.update("records", ContentValues().apply { put("pending", 0) }, "scope=? AND event=?",
            arrayOf(key(intent.scope), intent.base.eventId.value))
        captured
    }
    private fun readBaseline(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): RemoteChange.Upsert? =
        db.rawQuery("SELECT payload FROM projection_bases WHERE scope=? AND event=?",
            arrayOf(key(scope), eventId.value)).use { if (it.moveToFirst()) decodeChange(it.getString(0)) as? RemoteChange.Upsert else null }
    private fun readLocalEdit(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): LocalEditIntent? =
        db.rawQuery("SELECT mutation,adapter,version,base,local,state FROM local_edits WHERE scope=? AND event=? AND kind='UPDATE'",
            arrayOf(key(scope), eventId.value)).use { if (!it.moveToFirst()) null else LocalEditIntent(
            MutationId(it.getString(0)), scope, AdapterFormat(it.getString(1), it.getInt(2)),
            decodeChange(it.getString(3)) as RemoteChange.Upsert, (decodeChange(it.getString(4)) as RemoteChange.Upsert).content,
            LocalEditState.valueOf(it.getString(5))) }
    private fun readLocalDelete(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): LocalDeleteIntent? =
        db.rawQuery("SELECT mutation,adapter,version,base,local,state FROM local_edits WHERE scope=? AND event=? AND kind='DELETE'",
            arrayOf(key(scope), eventId.value)).use {
            if (!it.moveToFirst()) null else {
                val base = decodeChange(it.getString(3)) as RemoteChange.Upsert
                val local = decodeChange(it.getString(4)) as RemoteChange.Upsert
                check(base.eventId == eventId && local.eventId == eventId && local.revision == base.revision) { "Local deletion identity mismatch" }
                LocalDeleteIntent(MutationId(it.getString(0)), scope, AdapterFormat(it.getString(1), it.getInt(2)),
                    base, local.content, LocalEditState.valueOf(it.getString(5)))
            }
        }
    private fun hasLocalMutation(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): Boolean = db.rawQuery(
        "SELECT 1 FROM local_edits WHERE scope=? AND event=?", arrayOf(key(scope), eventId.value),
    ).use { it.moveToFirst() }
    private fun localMutationBase(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): RemoteChange.Upsert? = db.rawQuery(
        "SELECT base FROM local_edits WHERE scope=? AND event=?", arrayOf(key(scope), eventId.value),
    ).use {
        if (!it.moveToFirst()) null else (decodeChange(it.getString(0)) as RemoteChange.Upsert).also { base ->
            check(base.eventId == eventId) { "Local mutation identity mismatch" }
        }
    }

    /** Stable keyset pagination: retries may remove rows without skipping subsequent issues. */
    suspend fun deferred(scope: CalendarScope, limit: Int = 4, afterEventId: EventId? = null): List<DeferredCalendarEvent> = access { db ->
        require(limit in 1..4); requireKnown(db, scope)
        val condition = if (afterEventId == null) "scope=?" else "scope=? AND event>?"
        val args = if (afterEventId == null) arrayOf(key(scope)) else arrayOf(key(scope), afterEventId.value)
        stagedQuery(db, "SELECT event,revision,adapter,version,reason,payload FROM deferred_events WHERE $condition ORDER BY event LIMIT $limit", args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(DeferredCalendarEvent(EventId(cursor.getString(0)), Revision(cursor.getString(1)),
                    AdapterFormat(cursor.getString(2), cursor.getInt(3)), DeferredEventReason.valueOf(cursor.getString(4)), cursor.getString(5)))
            }
        }
    }
    suspend fun deferredCount(scope: CalendarScope): Int = access { db ->
        requireKnown(db, scope); countDeferred(db, scope)
    }
    /** Explicit refresh schedules one bounded retry of every retained issue, including after restart. */
    suspend fun prepareDeferredRetry(scope: CalendarScope): Int = stagingMutation(scope) { db ->
        db.update("deferred_events", ContentValues().apply { put("retry_pending", 1) }, "scope=?", arrayOf(key(scope)))
    }
    suspend fun pendingDeferredRetries(scope: CalendarScope, limit: Int = 4): List<DeferredCalendarEvent> = access { db ->
        require(limit in 1..4); requireKnown(db, scope)
        stagedQuery(db, "SELECT event,revision,adapter,version,reason,payload FROM deferred_events WHERE scope=? AND retry_pending=1 ORDER BY event LIMIT $limit", arrayOf(key(scope))).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(DeferredCalendarEvent(EventId(cursor.getString(0)), Revision(cursor.getString(1)),
                    AdapterFormat(cursor.getString(2), cursor.getInt(3)), DeferredEventReason.valueOf(cursor.getString(4)), cursor.getString(5)))
            }
        }
    }
    /** A stale local retry cannot acknowledge different retained evidence for the same event. */
    suspend fun acknowledgeDeferredRetry(scope: CalendarScope, event: DeferredCalendarEvent): Int = stagingMutation(scope) { db ->
        db.update("deferred_events", ContentValues().apply { put("retry_pending", 0) },
            "scope=? AND event=? AND revision=? AND adapter=? AND version=? AND reason=? AND payload=? AND retry_pending=1",
            arrayOf(key(scope), event.eventId.value, event.revision.value, event.format.id, event.format.version.toString(), event.reason.name, event.protectedPayload))
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
    private fun countDeferred(db: SQLiteDatabase, scope: CalendarScope): Int = db.rawQuery(
        "SELECT COUNT(*) FROM deferred_events WHERE scope=?", arrayOf(key(scope)),
    ).use { check(it.moveToFirst()); it.getInt(0) }
    private fun countPending(db: SQLiteDatabase, scope: CalendarScope): Int = db.rawQuery(
        "SELECT COUNT(*) FROM records r WHERE scope=? AND pending=1 AND NOT EXISTS (SELECT 1 FROM local_edits l WHERE l.scope=r.scope AND l.event=r.event)", arrayOf(key(scope)),
    ).use { check(it.moveToFirst()); it.getInt(0) }
    private data class SeriesMember(val uid: String, val role: String)
    private enum class MasterState {
        INACTIVE, ALL_DAY, TIMED;
        fun accepts(original: RecurrencePoint?): Boolean = when (this) {
            INACTIVE -> false
            ALL_DAY -> original is RecurrencePoint.AllDay
            TIMED -> original is RecurrencePoint.Timed
        }
    }
    private fun seriesMember(db: SQLiteDatabase, scope: CalendarScope, eventId: EventId): SeriesMember? =
        db.rawQuery("SELECT uid,role FROM series_members WHERE scope=? AND event=?", arrayOf(key(scope), eventId.value)).use {
            if (it.moveToFirst()) SeriesMember(it.getString(0), it.getString(1)) else null
        }
    /** Deferred parent contents cannot establish a usable dependency, even with a cached old copy. */
    private fun masterStates(db: SQLiteDatabase, scope: CalendarScope): Map<String, MasterState> =
        db.rawQuery("SELECT s.uid,r.payload,EXISTS(SELECT 1 FROM deferred_events d WHERE d.scope=s.scope AND d.event=s.event) FROM series_members s JOIN records r ON r.scope=s.scope AND r.event=s.event WHERE s.scope=? AND s.role='master'", arrayOf(key(scope))).use { cursor ->
            buildMap {
                val unusable = mutableSetOf<String>()
                while (cursor.moveToNext()) {
                    val uid = cursor.getString(0)
                    if (cursor.getInt(2) != 0) { unusable.add(uid); continue }
                    val content = (decodeChange(cursor.getString(1)) as? RemoteChange.Upsert)?.content
                    val recurrence = content?.recurrence
                    val active = recurrence != null && recurrence.isMaster && recurrence.uid == uid
                    if (active) put(uid, if (content!!.time is EventTime.AllDay) MasterState.ALL_DAY else MasterState.TIMED)
                    else if (uid !in this) put(uid, MasterState.INACTIVE)
                }
                // An older inactive association must not turn an unusable new parent into a deletion.
                unusable.forEach { uid -> if (get(uid) == MasterState.INACTIVE) remove(uid) }
            }
        }
    private fun suppressInactiveChildren(db: SQLiteDatabase, scope: CalendarScope) {
        val masters = masterStates(db, scope)
        db.rawQuery("SELECT r.event,r.payload FROM records r JOIN series_members s ON s.scope=r.scope AND s.event=r.event WHERE r.scope=? AND r.pending=1 AND s.role='exception'", arrayOf(key(scope))).use { cursor ->
            while (cursor.moveToNext()) {
                val recurrence = (decodeChange(cursor.getString(1)) as? RemoteChange.Upsert)?.content?.recurrence
                if (recurrence != null && !recurrence.isMaster && masters[recurrence.uid] == MasterState.INACTIVE)
                    db.update("records", ContentValues().apply { put("pending", 0) }, "scope=? AND event=?", arrayOf(key(scope), cursor.getString(0)))
            }
        }
    }
    private fun updateSeries(db: SQLiteDatabase, scope: CalendarScope, change: RemoteChange) {
        val previous = seriesMember(db, scope, change.eventId)
        val recurrence = (change as? RemoteChange.Upsert)?.content?.recurrence
        if (recurrence != null) {
            val role = if (recurrence.isMaster) "master" else "exception"
            require(previous == null || previous == SeriesMember(recurrence.uid, role)) { "Recurrence identity changed" }
            if (recurrence.isMaster) db.rawQuery("SELECT r.payload FROM series_members s JOIN records r ON r.scope=s.scope AND r.event=s.event WHERE s.scope=? AND s.uid=? AND s.role='master' AND s.event<>?",
                arrayOf(key(scope), recurrence.uid, change.eventId.value)).use { cursor ->
                while (cursor.moveToNext()) {
                    val other = (decodeChange(cursor.getString(0)) as? RemoteChange.Upsert)?.content?.recurrence
                    require(other == null || !other.isMaster || other.uid != recurrence.uid) { "Ambiguous recurrence master" }
                }
            }
            db.insertWithOnConflict("series_members", null, ContentValues().apply {
                put("scope", key(scope)); put("event", change.eventId.value); put("uid", recurrence.uid); put("role", role)
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
        } else if (change is RemoteChange.Upsert && previous?.role == "exception") {
            db.delete("series_members", "scope=? AND event=?", arrayOf(key(scope), change.eventId.value))
        }
        // Deleted records retain the association. A master becoming simple does too.
    }
    private fun requeueSeriesChildren(db: SQLiteDatabase, scope: CalendarScope, uid: String) {
        val state = masterStates(db, scope)[uid] ?: return
        db.rawQuery("SELECT r.event,r.payload FROM records r JOIN series_members s ON s.scope=r.scope AND s.event=r.event WHERE r.scope=? AND s.uid=? AND s.role='exception' AND NOT EXISTS (SELECT 1 FROM deferred_events d WHERE d.scope=r.scope AND d.event=r.event) AND NOT EXISTS (SELECT 1 FROM local_edits l WHERE l.scope=r.scope AND l.event=r.event)", arrayOf(key(scope), uid)).use { cursor ->
            while (cursor.moveToNext()) {
                val recurrence = (decodeChange(cursor.getString(1)) as? RemoteChange.Upsert)?.content?.recurrence
                if (recurrence != null && !recurrence.isMaster)
                    db.update("records", ContentValues().apply { put("pending", if (state != MasterState.INACTIVE) 1 else 0) },
                        "scope=? AND event=?", arrayOf(key(scope), cursor.getString(0)))
            }
        }
    }
    private fun countUnverified(db: SQLiteDatabase, scope: CalendarScope): Int = db.rawQuery(
        "SELECT COUNT(*) FROM deferred_events WHERE scope=? AND reason=?",
        arrayOf(key(scope), DeferredEventReason.UNVERIFIED_CONTENT.name),
    ).use { check(it.moveToFirst()); it.getInt(0) }

    // The default CursorWindow is smaller than an admitted sealed page. Bound it explicitly;
    // four UTF-8 bytes per allowed character also accommodates non-ASCII adapter envelopes.
    private fun stagedQuery(db: SQLiteDatabase, sql: String, args: Array<String>): SQLiteCursor =
        (db.rawQuery(sql, args) as SQLiteCursor).apply {
            window = CursorWindow("Calendar sealed import", ReceivedCalendarPage.MAX_PAYLOAD_CHARS.toLong() * 4 + COVERAGE_MAX_BYTES + 64 * 1024)
        }

    private fun readReceived(db: SQLiteDatabase, scope: CalendarScope): ReceivedCalendarPage? = stagedQuery(db,
        "SELECT request_checkpoint,request_limit,payload,coverage FROM inbox WHERE scope=?", arrayOf(key(scope)),
    ).use {
        if (!it.moveToFirst()) null else ReceivedCalendarPage(PageRequest(decodeCheckpoint(scope, it.getString(0)), it.getInt(1)), it.getString(2),
            if (it.isNull(3)) null else decodePageCoverage(it.getString(3)))
    }

    private fun readCoverage(db: SQLiteDatabase, scope: CalendarScope): CoverageLedger = db.rawQuery(
        "SELECT payload FROM coverage_state WHERE scope=?", arrayOf(key(scope)),
    ).use { if (it.moveToFirst()) decodeCoverage(it.getString(0)) else CoverageLedger() }

    private fun writeCoverage(db: SQLiteDatabase, scope: CalendarScope, value: CoverageLedger) {
        db.insertWithOnConflict("coverage_state", null, ContentValues().apply {
            put("scope", key(scope)); put("payload", encodeCoverage(value))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Could not retain coverage" } }
    }

    // This schema stores project-owned observations, never adapter cursor internals.
    private fun intervalJson(value: CoverageInterval) = JSONObject().apply {
        put("start", value.start.toString()); put("end", value.endExclusive.toString()); put("zone", value.zone.id)
    }
    private fun readInterval(value: JSONObject) = CoverageInterval(LocalDate.parse(value.getString("start")),
        LocalDate.parse(value.getString("end")), ZoneId.of(value.getString("zone")))
    private fun boundedCoverage(payload: String): String {
        require(payload.toByteArray(Charsets.UTF_8).size <= COVERAGE_MAX_BYTES) { "Coverage payload exceeds limit" }
        return payload
    }
    private fun coverageJson(payload: String, maximumSchema: Int = 1): JSONObject = JSONObject(boundedCoverage(payload)).also {
        require(it.getInt("schema") in 1..maximumSchema) { "Unknown coverage schema" }
    }
    private fun encodePageCoverage(value: PageCoverage): String = boundedCoverage(JSONObject().apply {
        put("schema", 1); put("plan", JSONArray(value.plan.map(::intervalJson)))
        put("index", value.windowIndex); put("starts", value.startsWindow); put("finishes", value.finishesWindow)
    }.toString())
    private fun decodePageCoverage(payload: String): PageCoverage = coverageJson(payload).let {
        val plan = it.getJSONArray("plan")
        require(plan.length() in 1..128)
        PageCoverage((0 until plan.length()).map { index -> readInterval(plan.getJSONObject(index)) },
            it.getInt("index"), it.getBoolean("starts"), it.getBoolean("finishes"))
    }
    private fun validateRun(value: CoverageRun) {
        PageCoverage(value.windows.map { it.interval }, 0, true, false) // Validate disjoint dates and zone.
        value.windows.forEach {
            require((it.receivedAt ?: 0) >= 0)
            require((it.appliedAt ?: 0) >= 0)
            require(!it.prefixKnown || it.receivedPages > 0)
            require(!it.terminalReceived || it.receivedPages > 0)
            require(!it.terminalApplied || it.appliedPages == it.receivedPages)
            require((it.receivedPages == 0L) == (it.receivedAt == null))
            require((it.appliedPages == 0L) == (it.appliedAt == null))
        }
        require(value.lastIndex >= 0 || value.windows.all { it.receivedPages == 0L })
        require(!value.finished || value.lastIndex == value.windows.lastIndex && value.windows.last().terminalApplied)
    }
    private fun runJson(value: CoverageRun): JSONObject {
        validateRun(value)
        return JSONObject().apply {
            put("adapter", value.format.id); put("version", value.format.version)
            put("lastIndex", value.lastIndex); put("finished", value.finished)
            put("windows", JSONArray(value.windows.map { window -> JSONObject().apply {
                put("interval", intervalJson(window.interval)); put("prefixKnown", window.prefixKnown)
                put("receivedPages", window.receivedPages); put("appliedPages", window.appliedPages)
                put("terminalReceived", window.terminalReceived); put("terminalApplied", window.terminalApplied)
                put("receivedAt", window.receivedAt ?: JSONObject.NULL); put("appliedAt", window.appliedAt ?: JSONObject.NULL)
            } }))
        }
    }
    private fun readRun(value: JSONObject): CoverageRun {
        val windows = value.getJSONArray("windows")
        require(windows.length() in 1..128)
        return CoverageRun(AdapterFormat(value.getString("adapter"), value.getInt("version")),
            (0 until windows.length()).map { index -> windows.getJSONObject(index).let {
                WindowCoverage(readInterval(it.getJSONObject("interval")), it.getBoolean("prefixKnown"),
                    it.getLong("receivedPages"), it.getLong("appliedPages"), it.getBoolean("terminalReceived"),
                    it.getBoolean("terminalApplied"), if (it.isNull("receivedAt")) null else it.getLong("receivedAt"),
                    if (it.isNull("appliedAt")) null else it.getLong("appliedAt"))
            } }, value.getInt("lastIndex"), value.getBoolean("finished")).also(::validateRun)
    }
    private fun encodeCoverage(value: CoverageLedger): String {
        require(value.previous?.finished != false)
        return boundedCoverage(JSONObject().apply {
            put("schema", 2); put("epoch", value.epoch)
            put("active", value.active?.let(::runJson) ?: JSONObject.NULL)
            put("previous", value.previous?.let(::runJson) ?: JSONObject.NULL)
            put("observed", value.observed?.let { observation -> JSONObject().apply {
                put("adapter", observation.format.id); put("version", observation.format.version)
                put("interval", intervalJson(observation.interval))
            } } ?: JSONObject.NULL)
        }.toString())
    }
    private fun decodeCoverage(payload: String): CoverageLedger = coverageJson(payload, maximumSchema = 2).let {
        CoverageLedger(it.getLong("epoch"), if (it.isNull("active")) null else readRun(it.getJSONObject("active")),
            if (it.isNull("previous")) null else readRun(it.getJSONObject("previous")),
            if (it.getInt("schema") == 1 || it.isNull("observed")) null else it.getJSONObject("observed").let { observation ->
                CoverageObservation(AdapterFormat(observation.getString("adapter"), observation.getInt("version")),
                    readInterval(observation.getJSONObject("interval")))
            }).also { ledger ->
            require(ledger.previous?.finished != false)
        }
    }

    private fun ensureDeltaState(db: SQLiteDatabase, scope: CalendarScope) {
        db.insertWithOnConflict("delta_state", null, ContentValues().apply { put("scope", key(scope)) }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    private fun hasReconciliation(db: SQLiteDatabase, scope: CalendarScope): Boolean = db.rawQuery(
        "SELECT EXISTS(SELECT 1 FROM delta_reconciliation WHERE scope=?)", arrayOf(key(scope)),
    ).use { check(it.moveToFirst()); it.getInt(0) != 0 }
    private fun readReconciliationFormat(db: SQLiteDatabase, scope: CalendarScope): AdapterFormat? = db.rawQuery(
        "SELECT adapter,version FROM delta_reconciliation WHERE scope=? ORDER BY id LIMIT 1", arrayOf(key(scope)),
    ).use { if (it.moveToFirst()) AdapterFormat(it.getString(0), it.getInt(1)) else null }
    private fun readDeltaCursor(db: SQLiteDatabase, scope: CalendarScope): OpaqueCursor? = db.rawQuery(
        "SELECT cursor_adapter,cursor_version,cursor_value FROM delta_state WHERE scope=?", arrayOf(key(scope)),
    ).use {
        if (!it.moveToFirst() || it.isNull(2)) null else OpaqueCursor(AdapterFormat(it.getString(0), it.getInt(1)), it.getString(2))
    }
    private fun readDeltaReceived(db: SQLiteDatabase, scope: CalendarScope): ReceivedChangePage? = stagedQuery(db,
        "SELECT received_adapter,received_version,received_after,received_payload FROM delta_state WHERE scope=?", arrayOf(key(scope)),
    ).use {
        if (!it.moveToFirst() || it.isNull(3)) null else ReceivedChangePage(scope,
            OpaqueCursor(AdapterFormat(it.getString(0), it.getInt(1)), it.getString(2)), it.getString(3))
    }
    private fun encodeNotice(value: CalendarChangeNotice) = JSONObject().apply {
        put("id", value.id); put("kind", value.kind.name)
        value.eventId?.let { put("event", it.value) }; value.revision?.let { put("revision", it.value) }
    }.toString()
    private fun decodeNotice(payload: String): CalendarChangeNotice = JSONObject(payload).let {
        CalendarChangeNotice(it.getString("id"), CalendarNoticeKind.valueOf(it.getString("kind")),
            if (it.isNull("event")) null else EventId(it.getString("event")),
            if (it.isNull("revision")) null else Revision(it.getString("revision")))
    }
    private fun encodeReceivedEvent(value: ReceivedCalendarEvent) = JSONObject().apply {
        put("event", value.eventId.value); put("revision", value.revision.value)
        put("adapter", value.format.id); put("version", value.format.version); put("payload", value.protectedPayload)
    }.toString()
    private fun decodeReceivedEvent(scope: CalendarScope, payload: String): ReceivedCalendarEvent = JSONObject(payload).let {
        ReceivedCalendarEvent(scope, EventId(it.getString("event")), Revision(it.getString("revision")),
            AdapterFormat(it.getString("adapter"), it.getInt("version")), it.getString("payload"))
    }

    private fun checkStagingCapacity(db: SQLiteDatabase) {
        val bytes = db.rawQuery("SELECT COALESCE((SELECT SUM(length(CAST(payload AS BLOB))+COALESCE(length(CAST(coverage AS BLOB)),0)) FROM inbox),0) + COALESCE((SELECT SUM(length(CAST(payload AS BLOB))) FROM coverage_state),0) + COALESCE((SELECT SUM(length(CAST(payload AS BLOB))) FROM deferred_events),0) + COALESCE((SELECT SUM(length(CAST(received_payload AS BLOB))) FROM delta_state),0) + COALESCE((SELECT SUM(length(CAST(notice AS BLOB))+COALESCE(length(CAST(received AS BLOB)),0)) FROM delta_jobs),0) + COALESCE((SELECT SUM(length(CAST(payload AS BLOB))) FROM delta_reconciliation),0) + COALESCE((SELECT SUM(length(CAST(payload AS BLOB))+length(CAST(base AS BLOB))+length(CAST(local AS BLOB))) FROM local_edit_preparations),0)", null).use {
            check(it.moveToFirst()); it.getLong(0)
        }
        if (bytes > STAGING_BUDGET_BYTES) throw ImportCapacityException()
    }

    override suspend fun <T> transaction(scope: CalendarScope, block: SyncStore.Transaction.() -> T): T {
        val coroutine = currentCoroutineContext()
        return access { db ->
        coroutine.ensureActive()
        check(admitTransaction(scope)) { "Inactive import lease" }
        db.beginTransaction()
        try {
            requireKnown(db, scope)
            val transaction = object : DeltaSyncTransaction {
                override var coverage: CoverageLedger
                    get() = readCoverage(db, scope)
                    set(value) = writeCoverage(db, scope, value)
                override val reconciliationFormat: AdapterFormat?
                    get() = readReconciliationFormat(db, scope)
                override fun retainReconciliation(receipt: ReceivedChangePage) {
                    require(receipt.scope == scope)
                    val format = receipt.after.format
                    require(reconciliationFormat == null || reconciliationFormat == format)
                    require(deltaCursor == null || deltaCursor?.format == format)
                    require(deltaReceived == null || deltaReceived?.after?.format == format)
                    db.insertOrThrow("delta_reconciliation", null, ContentValues().apply {
                        put("scope", key(scope)); put("adapter", format.id); put("version", format.version)
                        put("after_cursor", receipt.after.value); put("payload", receipt.protectedPayload)
                    })
                }
                override var deltaCursor: OpaqueCursor?
                    get() = readDeltaCursor(db, scope)
                    set(value) {
                        val previous = readDeltaCursor(db, scope)
                        require(value == null || previous == null || value.format == previous.format)
                        require(value == null || reconciliationFormat == null || value.format == reconciliationFormat)
                        val pending = readDeltaReceived(db, scope)
                        require(pending == null || value != null && value.format == pending.after.format)
                        ensureDeltaState(db, scope)
                        db.update("delta_state", ContentValues().apply {
                            if (value == null) { putNull("cursor_adapter"); putNull("cursor_version"); putNull("cursor_value") }
                            else { put("cursor_adapter", value.format.id); put("cursor_version", value.format.version); put("cursor_value", value.value) }
                        }, "scope=?", arrayOf(key(scope)))
                    }
                override var deltaReceived: ReceivedChangePage?
                    get() = readDeltaReceived(db, scope)
                    set(value) {
                        if (value != null) {
                            require(value.scope == scope)
                            require(reconciliationFormat == null || value.after.format == reconciliationFormat)
                            val current = readDeltaCursor(db, scope)
                            require(current == null || current == value.after)
                            val previous = readDeltaReceived(db, scope)
                            check(previous == null || previous == value) { "A received change page is still pending" }
                        }
                        ensureDeltaState(db, scope)
                        db.update("delta_state", ContentValues().apply {
                            if (value == null) { putNull("received_adapter"); putNull("received_version"); putNull("received_after"); putNull("received_payload") }
                            else { put("received_adapter", value.after.format.id); put("received_version", value.after.format.version); put("received_after", value.after.value); put("received_payload", value.protectedPayload) }
                        }, "scope=?", arrayOf(key(scope)))
                    }
                override var deltaMore: Boolean
                    get() = db.rawQuery("SELECT more FROM delta_state WHERE scope=?", arrayOf(key(scope))).use { it.moveToFirst() && it.getInt(0) != 0 }
                    set(value) {
                        ensureDeltaState(db, scope)
                        db.update("delta_state", ContentValues().apply { put("more", if (value) 1 else 0) }, "scope=?", arrayOf(key(scope)))
                    }
                override fun deltaJobs(limit: Int, blocked: Boolean): List<CalendarChangeJob> {
                    require(limit in 1..20)
                    // Only context/content notices lack an event ID. Refresh their keys/settings
                    // before selecting a bounded group of individual event obligations.
                    return stagedQuery(db, "SELECT notice,received,blocked FROM delta_jobs WHERE scope=? AND blocked=? ORDER BY CASE WHEN event IS NULL THEN 0 ELSE 1 END,rowid LIMIT $limit",
                        arrayOf(key(scope), if (blocked) "1" else "0")).use { cursor ->
                        buildList { while (cursor.moveToNext()) add(CalendarChangeJob(decodeNotice(cursor.getString(0)),
                            if (cursor.isNull(1)) null else decodeReceivedEvent(scope, cursor.getString(1)), cursor.getInt(2) != 0)) }
                    }
                }
                override fun putDeltaJob(job: CalendarChangeJob) {
                    job.received?.let {
                        require(job.notice.kind == CalendarNoticeKind.EVENT_CHANGED && it.scope == scope && it.eventId == job.notice.eventId)
                        require(checkpoint == null || it.format == checkpoint?.format)
                    }
                    val values = ContentValues().apply {
                        put("scope", key(scope)); put("notice_id", job.notice.id); put("notice", encodeNotice(job.notice))
                        job.notice.eventId?.let { put("event", it.value) } ?: putNull("event")
                        job.received?.let { put("received", encodeReceivedEvent(it)) } ?: putNull("received")
                        put("blocked", if (job.blocked) 1 else 0)
                    }
                    if (db.update("delta_jobs", values, "scope=? AND notice_id=?", arrayOf(key(scope), job.notice.id)) == 0)
                        db.insertOrThrow("delta_jobs", null, values)
                }
                override fun removeDeltaJob(expected: CalendarChangeJob): Boolean = db.delete("delta_jobs",
                    "scope=? AND notice_id=? AND notice=? AND received IS ? AND blocked=?", arrayOf(key(scope), expected.notice.id,
                        encodeNotice(expected.notice), expected.received?.let(::encodeReceivedEvent), if (expected.blocked) "1" else "0")) != 0
                override fun clearBlockedEvent(eventId: EventId) {
                    db.delete("delta_jobs", "scope=? AND event=? AND blocked=1", arrayOf(key(scope), eventId.value))
                }
                override fun retryBlockedJobs() {
                    db.update("delta_jobs", ContentValues().apply { put("blocked", 0) }, "scope=? AND blocked=1", arrayOf(key(scope)))
                }
                override var received: ReceivedCalendarPage?
                    get() = readReceived(db, scope)
                    set(value) {
                        if (value == null) {
                            db.delete("inbox", "scope=?", arrayOf(key(scope)))
                        } else {
                            require(value.request.checkpoint.scope == scope && value.request.limit in 1..100)
                            val previous = readReceived(db, scope)
                            check(previous == null || previous == value) { "A received page is still pending" }
                            if (previous == null) db.insertOrThrow("inbox", null, ContentValues().apply {
                                put("scope", key(scope)); put("request_checkpoint", encodeCheckpoint(value.request.checkpoint))
                                put("request_limit", value.request.limit); put("payload", value.protectedPayload)
                                value.coverage?.let { put("coverage", encodePageCoverage(it)) } ?: putNull("coverage")
                            })
                        }
                    }
                override fun defer(event: DeferredCalendarEvent) {
                    db.insertWithOnConflict("deferred_events", null, ContentValues().apply {
                        put("scope", key(scope)); put("event", event.eventId.value); put("revision", event.revision.value)
                        put("adapter", event.format.id); put("version", event.format.version); put("reason", event.reason.name)
                        put("payload", event.protectedPayload); put("retry_pending", 0)
                    }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Could not retain deferred event" } }
                    db.update("local_edits", ContentValues().apply { put("state", LocalEditState.CONFLICT.name) },
                        "scope=? AND event=?", arrayOf(key(scope), event.eventId.value))
                }
                override fun clearDeferred(eventId: EventId) {
                    val removed = db.delete("deferred_events", "scope=? AND event=?", arrayOf(key(scope), eventId.value))
                    if (removed != 0) seriesMember(db, scope, eventId)?.takeIf { it.role == "master" }?.let {
                        requeueSeriesChildren(db, scope, it.uid)
                    }
                }
                override fun deferred(eventId: EventId): DeferredCalendarEvent? = stagedQuery(db,
                    "SELECT revision,adapter,version,reason,payload FROM deferred_events WHERE scope=? AND event=?",
                    arrayOf(key(scope), eventId.value),
                ).use {
                    if (!it.moveToFirst()) null else DeferredCalendarEvent(eventId, Revision(it.getString(0)),
                        AdapterFormat(it.getString(1), it.getInt(2)), DeferredEventReason.valueOf(it.getString(3)), it.getString(4))
                }
                override var checkpoint: PageCheckpoint?
                    get() = db.query("calendars", arrayOf("checkpoint"), "scope=?", arrayOf(key(scope)), null, null, null).use {
                        check(it.moveToFirst()); if (it.isNull(0)) null else decodeCheckpoint(scope, it.getString(0))
                    }
                    set(value) {
                        require(value == null || value.scope == scope)
                        db.update("calendars", ContentValues().apply {
                            if (value == null) putNull("checkpoint") else put("checkpoint", encodeCheckpoint(value))
                        }, "scope=?", arrayOf(key(scope)))
                    }
                override val access = CalendarAccess()
                override fun remote(eventId: EventId): RemoteChange? = db.query("records", arrayOf("payload"),
                    "scope=? AND event=?", arrayOf(key(scope), eventId.value), null, null, null).use {
                    if (it.moveToFirst()) decodeChange(it.getString(0)) else null
                }
                override fun putRemote(change: RemoteChange) {
                    updateSeries(db, scope, change)
                    db.insertWithOnConflict("records", null, ContentValues().apply {
                        put("scope", key(scope)); put("event", change.eventId.value)
                        put("payload", encodeChange(change)); put("pending", 0)
                    }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
                    localMutationBase(db, scope, change.eventId)?.let { base ->
                        if (change != base) db.update("local_edits", ContentValues().apply {
                            put("state", LocalEditState.CONFLICT.name)
                        }, "scope=? AND event=?", arrayOf(key(scope), change.eventId.value))
                    }
                    seriesMember(db, scope, change.eventId)?.takeIf { it.role == "master" }?.let {
                        requeueSeriesChildren(db, scope, it.uid)
                    }
                }
                override fun hasPendingMutation(eventId: EventId) = hasLocalMutation(db, scope, eventId)
                override fun putProjection(intent: ProjectionIntent) {
                    require(intent.scope == scope && remote(intent.change.eventId) == intent.change)
                    val recurrence = (intent.change as? RemoteChange.Upsert)?.content?.recurrence
                    val inactiveParent = recurrence != null && !recurrence.isMaster && masterStates(db, scope)[recurrence.uid] == MasterState.INACTIVE
                    db.update("records", ContentValues().apply { put("pending", if (inactiveParent || hasPendingMutation(intent.change.eventId)) 0 else 1) },
                        "scope=? AND event=?", arrayOf(key(scope), intent.change.eventId.value))
                }
                override fun removeProjection(eventId: EventId) {
                    db.update("records", ContentValues().apply { put("pending", 0) },
                        "scope=? AND event=?", arrayOf(key(scope), eventId.value))
                }
                override fun mutation(id: MutationId): Mutation? = null
                override fun putMutation(mutation: Mutation): Unit = error("Outgoing changes are not enabled")
            }
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
    private fun requireKnown(db: SQLiteDatabase, scope: CalendarScope) {
        db.rawQuery("SELECT 1 FROM calendars WHERE scope=?", arrayOf(key(scope))).use {
            check(it.moveToFirst()) { "Unknown import scope" }
        }
    }
    private fun isAvailable(db: SQLiteDatabase, scope: CalendarScope): Boolean = db.rawQuery(
        "SELECT available FROM calendars WHERE scope=?", arrayOf(key(scope)),
    ).use { it.moveToFirst() && it.getInt(0) != 0 }
    private fun key(scope: CalendarScope) = org.json.JSONArray()
        .put(scope.account.value).put(scope.calendar.value).put(scope.generation).toString()

    private fun encodeCheckpoint(value: PageCheckpoint) = JSONObject().apply {
        put("adapter", value.format.id); put("version", value.format.version)
        put("cursor", value.cursor?.value ?: JSONObject.NULL); put("complete", value.traversalComplete)
    }.toString()
    private fun decodeCheckpoint(scope: CalendarScope, payload: String): PageCheckpoint = JSONObject(payload).let {
        val format = AdapterFormat(it.getString("adapter"), it.getInt("version"))
        PageCheckpoint(scope, format, if (it.isNull("cursor")) null else OpaqueCursor(format, it.getString("cursor")), it.getBoolean("complete"))
    }
    private fun encodeChange(value: RemoteChange) = JSONObject().apply {
        put("id", value.eventId.value); put("revision", value.revision.value)
        put("deleted", value is RemoteChange.Deleted)
        if (value is RemoteChange.Upsert) {
            val content = value.content
            put("title", content.title); put("description", content.description); put("location", content.location)
            // Omit null to preserve legacy payload encoding and pending acknowledgement equality.
            content.colorRgb?.let { put("colorRgb", it) }
            content.recurrence?.let { put("recurrence", encodeRecurrence(it)) }
            content.details?.let { put("details", encodeDetails(it)) }
            when (val time = content.time) {
                is EventTime.AllDay -> { put("allDay", true); put("start", time.start.toString()); put("end", time.endExclusive.toString()) }
                is EventTime.Timed -> { put("allDay", false); put("start", time.start.toString()); put("end", time.end.toString()); put("zone", time.zone.id) }
            }
        }
    }.toString()
    private fun decodeChange(payload: String): RemoteChange = JSONObject(payload).let {
        val id = EventId(it.getString("id")); val revision = Revision(it.getString("revision"))
        if (it.getBoolean("deleted")) RemoteChange.Deleted(id, revision) else {
            val time = if (it.getBoolean("allDay")) EventTime.AllDay(LocalDate.parse(it.getString("start")), LocalDate.parse(it.getString("end")))
            else EventTime.Timed(Instant.parse(it.getString("start")), Instant.parse(it.getString("end")), ZoneId.of(it.getString("zone")))
            RemoteChange.Upsert(id, revision, EventContent(it.getString("title"), time, it.getString("description"),
                it.getString("location"), if (it.isNull("colorRgb")) null else it.getInt("colorRgb"),
                if (it.isNull("recurrence")) null else decodeRecurrence(it.getJSONObject("recurrence")),
                if (it.isNull("details")) null else decodeDetails(it.getJSONObject("details"))))
        }
    }
    private fun encodePerson(value: EventPerson) = JSONObject().apply {
        put("email", value.email); put("name", value.name)
    }
    private fun decodePerson(value: JSONObject) = EventPerson(value.getString("email"), value.getString("name"))
    private fun encodeDetails(value: EventDetails) = JSONObject().apply {
        put("schema", 1)
        put("reminders", JSONArray().apply { value.reminders.forEach { put(it.minutesBefore) } })
        value.organizer?.let { put("organizer", encodePerson(it)) }
        put("attendees", JSONArray().apply { value.attendees.forEach { attendee ->
            put(JSONObject().apply {
                put("person", encodePerson(attendee.person)); put("role", attendee.role.name); put("status", attendee.status.name)
            })
        } })
    }
    private fun decodeDetails(value: JSONObject): EventDetails {
        require(value.getInt("schema") == 1) { "Unsupported event details schema" }
        val reminders = value.getJSONArray("reminders")
        val attendees = value.getJSONArray("attendees")
        require(reminders.length() <= 5 && attendees.length() <= 100)
        return EventDetails(List(reminders.length()) { EventReminder(reminders.getInt(it)) },
            if (value.isNull("organizer")) null else decodePerson(value.getJSONObject("organizer")),
            List(attendees.length()) { index -> attendees.getJSONObject(index).let {
                EventAttendee(decodePerson(it.getJSONObject("person")), AttendanceRole.valueOf(it.getString("role")),
                    AttendanceStatus.valueOf(it.getString("status")))
            } })
    }
    private fun encodeRecurrence(value: EventRecurrence) = JSONObject().apply {
        put("schema", 1); put("uid", value.uid)
        value.rule?.let { put("rule", it) }
        put("additional", JSONArray().apply { value.additionalDates.forEach { put(encodePoint(it)) } })
        put("excluded", JSONArray().apply { value.excludedDates.forEach { put(encodePoint(it)) } })
        value.original?.let { put("original", encodePoint(it)) }
        put("cancelled", value.cancelled)
    }
    private fun encodePoint(value: RecurrencePoint) = JSONObject().apply {
        when (value) {
            is RecurrencePoint.AllDay -> { put("type", "date"); put("value", value.date.toString()) }
            is RecurrencePoint.Timed -> { put("type", "instant"); put("value", value.instant.toString()) }
        }
    }
    private fun decodePoint(value: JSONObject): RecurrencePoint = when (value.getString("type")) {
        "date" -> RecurrencePoint.AllDay(LocalDate.parse(value.getString("value")))
        "instant" -> RecurrencePoint.Timed(Instant.parse(value.getString("value")))
        else -> error("Unsupported recurrence date type")
    }
    private fun decodeRecurrence(value: JSONObject): EventRecurrence {
        require(value.getInt("schema") == 1) { "Unsupported recurrence schema" }
        fun points(name: String): List<RecurrencePoint> = value.getJSONArray(name).let { array ->
            require(array.length() <= 512)
            List(array.length()) { decodePoint(array.getJSONObject(it)) }
        }
        return EventRecurrence(value.getString("uid"), if (value.isNull("rule")) null else value.getString("rule"),
            points("additional"), points("excluded"), if (value.isNull("original")) null else decodePoint(value.getJSONObject("original")),
            value.getBoolean("cancelled"))
    }
    companion object {
        const val STAGING_BUDGET_BYTES: Long = 32L * 1024 * 1024
        private const val COVERAGE_MAX_BYTES = 256 * 1024
    }
}
