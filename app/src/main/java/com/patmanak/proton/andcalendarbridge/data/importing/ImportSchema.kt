package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodeChange
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.key
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.archiveString
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.archiveText
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.EventId
import com.patmanak.proton.andcalendarbridge.sync.RemoteChange
import org.json.JSONArray

/** Schema definitions and explicit migrations only; SQLiteOpenHelper owns their transaction.
 * The store supplies the existing capacity check for migration-time evidence backfill. */
internal object ImportSchema {
    fun create(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE calendars (scope TEXT PRIMARY KEY, account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL, title TEXT NOT NULL, color INTEGER, enabled INTEGER NOT NULL DEFAULT 0, checkpoint TEXT, status TEXT NOT NULL DEFAULT 'PAUSED', completed_at INTEGER, next_attempt INTEGER NOT NULL DEFAULT 0, native_removal_pending INTEGER NOT NULL DEFAULT 0 CHECK(native_removal_pending IN (0,1)))")
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
        createProjectionRepresentations(db)
        createTitleUpdateAcknowledgements(db)
        createTitleUpdateAcknowledgementHistory(db)
        createTextPreparationAndAcknowledgements(db)
        createTimePreparationAndAcknowledgements(db)
        createPersonalPreparationAndAcknowledgements(db)
        createConflictArchives(db)
        createDeleteAcknowledgements(db)
    }
    fun upgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int, checkCapacity: (SQLiteDatabase) -> Unit) {
        check(oldVersion in 1..23 && newVersion == 24) { "Explicit import schema migration required" }
        addNativeRemovalPending(db)
        if (oldVersion == 23) return
        createPersonalPreparationAndAcknowledgements(db)
        if (oldVersion == 22) return
        createDeleteAcknowledgements(db)
        if (oldVersion == 21) return
        createTimePreparationAndAcknowledgements(db)
        if (oldVersion == 20) return
        // Add these before every legacy branch, including the schema-18 observation rebuild.
        createTextPreparationAndAcknowledgements(db)
        if (oldVersion == 19) return
        if (oldVersion == 18) {
            migrateConflictObservationsFrom18(db)
            return
        }
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
        if (oldVersion < 14) createLocalEditPreparations(db)
        if (oldVersion < 15) createProjectionRepresentations(db)
        if (oldVersion < 16) createTitleUpdateAcknowledgements(db)
        if (oldVersion < 17) createTitleUpdateAcknowledgementHistory(db)
        createConflictArchives(db)
        // Only currently retained evidence is available. Never reconstruct discarded observations.
        var after = 0L
        while (true) {
            val row = db.rawQuery("SELECT rowid,${archiveText("scope", 32768)},${archiveText("event", 4096)} FROM local_edits WHERE state='CONFLICT' AND rowid>? ORDER BY rowid LIMIT 1",
                arrayOf(after.toString())).use { cursor ->
                if (!cursor.moveToFirst()) null else Triple(cursor.getLong(0), archiveString(cursor, 1), archiveString(cursor, 2))
            } ?: break
            after = row.first
            val encodedScope = JSONArray(row.second)
            check(encodedScope.length() == 3) { "Invalid conflict scope" }
            val scope = CalendarScope(AccountId(encodedScope.getString(0)), CalendarId(encodedScope.getString(1)), encodedScope.getLong(2))
            check(key(scope) == row.second) { "Invalid conflict scope" }
            ConflictArchiveSql(db) { checkCapacity(db) }.ensure(scope, EventId(row.third))
        }
        checkCapacity(db)
    }
    private fun addNativeRemovalPending(db: SQLiteDatabase) {
        // Synthetic legacy fixtures may retain this current column while downgrading their version.
        val exists = db.rawQuery("PRAGMA table_info(calendars)", null).use { rows ->
            var found = false
            while (rows.moveToNext()) if (rows.getString(1) == "native_removal_pending") {
                check(rows.getString(2) == "INTEGER" && rows.getInt(3) == 1 && rows.getString(4) == "0" && rows.getInt(5) == 0) {
                    "Incompatible native removal pending column"
                }
                found = true
            }
            found
        }
        if (!exists) db.execSQL("ALTER TABLE calendars ADD COLUMN native_removal_pending INTEGER NOT NULL DEFAULT 0 CHECK(native_removal_pending IN (0,1))")
    }
    private fun createConflictArchives(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE conflict_archives (
            id INTEGER PRIMARY KEY AUTOINCREMENT, scope TEXT NOT NULL, event TEXT NOT NULL,
            mutation TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('UPDATE','DELETE')),
            adapter TEXT NOT NULL, version INTEGER NOT NULL CHECK(version>0),
            base TEXT NOT NULL, local TEXT NOT NULL, UNIQUE(scope,mutation)
        )""".trimIndent())
        db.execSQL("CREATE INDEX conflict_archive_scope ON conflict_archives(scope,id)")
        createConflictObservations(db)
        createConflictArchiveCleanup(db)
    }
    private fun createConflictObservations(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE conflict_observations (
            id INTEGER PRIMARY KEY AUTOINCREMENT, archive_id INTEGER NOT NULL,
            kind TEXT NOT NULL CHECK(kind IN ('VERIFIED','DEFERRED','UNOBSERVED','UNREADABLE')),
            origin TEXT NOT NULL CHECK(origin IN ('CACHED','RECEIVED')), observed_at TEXT,
            adapter TEXT, version INTEGER, revision TEXT, reason TEXT, payload TEXT
        )""".trimIndent())
        db.execSQL("CREATE INDEX conflict_observation_archive ON conflict_observations(archive_id,id)")
    }
    private fun createConflictArchiveCleanup(db: SQLiteDatabase) {
        db.execSQL("""CREATE TRIGGER conflict_archive_scope_delete AFTER DELETE ON calendars BEGIN
            DELETE FROM conflict_observations WHERE archive_id IN (SELECT id FROM conflict_archives WHERE scope=OLD.scope);
            DELETE FROM conflict_archives WHERE scope=OLD.scope;
        END""".trimIndent())
    }
    /** Expand the observation kind constraint without renumbering evidence or changing any bytes. */
    private fun migrateConflictObservationsFrom18(db: SQLiteDatabase) {
        val sequence = db.rawQuery("SELECT CASE WHEN typeof(seq)='integer' AND seq>=0 THEN seq END FROM sqlite_sequence WHERE name='conflict_observations'", null).use { row ->
            if (!row.moveToFirst()) null else {
                check(row.getType(0) == Cursor.FIELD_TYPE_INTEGER && row.getLong(0) >= 0) { "Invalid conflict sequence" }
                row.getLong(0).also { check(!row.moveToNext()) { "Invalid conflict sequence" } }
            }
        }
        db.execSQL("DROP TRIGGER conflict_archive_scope_delete")
        db.execSQL("DROP INDEX conflict_observation_archive")
        db.execSQL("ALTER TABLE conflict_observations RENAME TO conflict_observations_v18")
        createConflictObservations(db)
        db.execSQL("INSERT INTO conflict_observations(id,archive_id,kind,origin,observed_at,adapter,version,revision,reason,payload) " +
            "SELECT id,archive_id,kind,origin,observed_at,adapter,version,revision,reason,payload FROM conflict_observations_v18")
        db.execSQL("DROP TABLE conflict_observations_v18")
        sequence?.let { previous ->
            // AUTOINCREMENT may be ahead of MAX(id) after deletion; retain that historical bound too.
            val maximum = db.rawQuery("SELECT COALESCE(MAX(id),0) FROM conflict_observations", null).use { row -> check(row.moveToFirst()); row.getLong(0) }
            check(previous >= maximum) { "Invalid conflict sequence" }
            val updated = db.update("sqlite_sequence", ContentValues().apply { put("seq", previous) },
                "name='conflict_observations'", null)
            if (updated == 0) db.insertOrThrow("sqlite_sequence", null, ContentValues().apply { put("name", "conflict_observations"); put("seq", previous) })
        }
        createConflictArchiveCleanup(db)
    }
    private fun createTextPreparationAndAcknowledgements(db: SQLiteDatabase) {
        createLocalTextEditPreparations(db)
        createTextUpdateAcknowledgements(db)
        createTextUpdateAcknowledgementHistory(db)
    }
    private fun createTextUpdateAcknowledgements(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE text_update_acknowledgements (
            scope TEXT NOT NULL, event TEXT NOT NULL, nonce TEXT NOT NULL UNIQUE,
            receipt TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('PENDING','RESTORED')),
            PRIMARY KEY(scope,event)
        )""".trimIndent())
    }
    private fun createTextUpdateAcknowledgementHistory(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE text_update_acknowledgement_history (
            scope TEXT NOT NULL, event TEXT NOT NULL, attempt TEXT NOT NULL,
            nonce TEXT NOT NULL UNIQUE, receipt TEXT NOT NULL,
            PRIMARY KEY(scope,event,attempt), UNIQUE(scope,attempt)
        )""".trimIndent())
    }
    private fun createLocalTextEditPreparations(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE local_text_edit_preparations (
            scope TEXT NOT NULL, mutation TEXT NOT NULL, event TEXT NOT NULL,
            adapter TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0),
            base TEXT NOT NULL, local TEXT NOT NULL, state TEXT NOT NULL CHECK(state = 'PAUSED'),
            timestamp TEXT NOT NULL, prep_adapter TEXT NOT NULL,
            prep_version INTEGER NOT NULL CHECK(prep_version > 0), payload TEXT NOT NULL,
            PRIMARY KEY(scope,mutation), UNIQUE(scope,event)
        )""".trimIndent())
    }
    private fun createTimePreparationAndAcknowledgements(db: SQLiteDatabase) {
        createLocalTimeEditPreparations(db)
        createTimeUpdateAcknowledgements(db)
        createTimeUpdateAcknowledgementHistory(db)
    }
    private fun createTimeUpdateAcknowledgements(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE time_update_acknowledgements (
            scope TEXT NOT NULL, event TEXT NOT NULL, nonce TEXT NOT NULL UNIQUE,
            receipt TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('PENDING','RESTORED')),
            PRIMARY KEY(scope,event)
        )""".trimIndent())
    }
    private fun createTimeUpdateAcknowledgementHistory(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE time_update_acknowledgement_history (
            scope TEXT NOT NULL, event TEXT NOT NULL, attempt TEXT NOT NULL,
            nonce TEXT NOT NULL UNIQUE, receipt TEXT NOT NULL,
            PRIMARY KEY(scope,event,attempt), UNIQUE(scope,attempt)
        )""".trimIndent())
    }
    private fun createLocalTimeEditPreparations(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE local_time_edit_preparations (
            scope TEXT NOT NULL, mutation TEXT NOT NULL, event TEXT NOT NULL,
            adapter TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0),
            base TEXT NOT NULL, local TEXT NOT NULL, state TEXT NOT NULL CHECK(state = 'PAUSED'),
            timestamp TEXT NOT NULL, prep_adapter TEXT NOT NULL,
            prep_version INTEGER NOT NULL CHECK(prep_version > 0), payload TEXT NOT NULL,
            PRIMARY KEY(scope,mutation), UNIQUE(scope,event)
        )""".trimIndent())
    }
    private fun createPersonalPreparationAndAcknowledgements(db: SQLiteDatabase) {
        createPersonalEditPreparations(db)
        createPersonalUpdateAcknowledgements(db)
        createPersonalUpdateAcknowledgementHistory(db)
    }
    private fun createPersonalUpdateAcknowledgements(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE personal_update_acknowledgements (
            scope TEXT NOT NULL, event TEXT NOT NULL, nonce TEXT NOT NULL UNIQUE,
            receipt TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('PENDING','RESTORED')),
            PRIMARY KEY(scope,event)
        )""".trimIndent())
    }
    private fun createPersonalUpdateAcknowledgementHistory(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE personal_update_acknowledgement_history (
            scope TEXT NOT NULL, event TEXT NOT NULL, attempt TEXT NOT NULL,
            nonce TEXT NOT NULL UNIQUE, receipt TEXT NOT NULL,
            PRIMARY KEY(scope,event,attempt), UNIQUE(scope,attempt)
        )""".trimIndent())
    }
    private fun createPersonalEditPreparations(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE personal_edit_preparations (
            scope TEXT NOT NULL, mutation TEXT NOT NULL, event TEXT NOT NULL,
            adapter TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0),
            base TEXT NOT NULL, local TEXT NOT NULL, state TEXT NOT NULL CHECK(state = 'PAUSED'),
            timestamp TEXT NOT NULL, prep_adapter TEXT NOT NULL,
            prep_version INTEGER NOT NULL CHECK(prep_version > 0), payload TEXT NOT NULL,
            PRIMARY KEY(scope,mutation), UNIQUE(scope,event)
        )""".trimIndent())
    }
    private fun createTitleUpdateAcknowledgements(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE title_update_acknowledgements (
            scope TEXT NOT NULL, event TEXT NOT NULL, nonce TEXT NOT NULL UNIQUE,
            receipt TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('PENDING','RESTORED')),
            PRIMARY KEY(scope,event)
        )""".trimIndent())
    }
    private fun createTitleUpdateAcknowledgementHistory(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE title_update_acknowledgement_history (
            scope TEXT NOT NULL, event TEXT NOT NULL, attempt TEXT NOT NULL,
            nonce TEXT NOT NULL UNIQUE, receipt TEXT NOT NULL,
            PRIMARY KEY(scope,event,attempt), UNIQUE(scope,attempt)
        )""".trimIndent())
    }
    private fun createProjectionRepresentations(db: SQLiteDatabase) {
        // Historical table helpers are shared by fresh creation and older upgrades.
        // Synthetic legacy fixtures may already contain a known nullable column.
        for ((table, column) in listOf("projection_bases" to "representation",
            "projection_receipts" to "representation", "projection_receipts" to "previous_representation")) {
            val exists = db.rawQuery("PRAGMA table_info($table)", null).use { rows ->
                var found = false
                while (rows.moveToNext()) if (rows.getString(1) == column) {
                    check(rows.getString(2) == "TEXT" && rows.getInt(3) == 0 && rows.isNull(4) && rows.getInt(5) == 0) {
                        "Incompatible projection representation column"
                    }
                    found = true
                }
                found
            }
            if (!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $column TEXT")
        }
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

    private fun createDeleteAcknowledgements(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE delete_acknowledgements (scope TEXT NOT NULL, event TEXT NOT NULL, mutation TEXT NOT NULL, attempt TEXT NOT NULL, payload TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('PENDING','RESTORED')), PRIMARY KEY(scope,event), UNIQUE(scope,mutation), UNIQUE(scope,attempt))")
    }
}
