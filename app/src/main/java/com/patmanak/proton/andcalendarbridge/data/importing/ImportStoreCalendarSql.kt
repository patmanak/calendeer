package com.patmanak.proton.andcalendarbridge.data.importing

import android.content.ContentValues
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.requeueSupported
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.countDeferred
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.countPending
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.countUnverified
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.readReceived
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.readCoverage
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.writeCoverage
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.hasReconciliation
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.readDeltaCursor
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStorePageState.requireKnown
import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.decodeCheckpoint
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.encodeCheckpoint
import com.patmanak.proton.andcalendarbridge.data.importing.ImportPayloadCodec.key
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary

/** Calendar SQL only; ImportStore retains IO, mutex, cancellation and transaction ownership. */
internal class ImportStoreCalendarSql(
    private val localMutations: ImportStoreLocalMutationSql,
) {
    fun remember(db: SQLiteDatabase, account: AccountId, calendars: List<CalendarSummary>): Unit = run {
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
    }

    fun calendarScopes(db: SQLiteDatabase): List<CalendarScope> = run {
        db.query("calendars", arrayOf("account", "calendar", "generation"), null, null, null, null, null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(CalendarScope(AccountId(cursor.getString(0)),
                CalendarId(cursor.getString(1)), cursor.getLong(2))) }
        }
    }

    /** Independent of account availability, selection and outgoing work admission. */
    fun queueNativeRemovals(db: SQLiteDatabase) {
        db.execSQL("UPDATE calendars SET native_removal_pending=1 WHERE native_removal_pending=0")
    }

    fun pendingNativeRemovals(db: SQLiteDatabase): List<CalendarScope> =
        db.query("calendars", arrayOf("account", "calendar", "generation"), "native_removal_pending=1",
            null, null, null, "rowid").use { cursor ->
            buildList { while (cursor.moveToNext()) add(CalendarScope(AccountId(cursor.getString(0)),
                CalendarId(cursor.getString(1)), cursor.getLong(2))) }
        }

    /** Acknowledges only this scope; repeated or unknown acknowledgements are harmless. */
    fun completeNativeRemoval(db: SQLiteDatabase, scope: CalendarScope) {
        db.update("calendars", ContentValues().apply { put("native_removal_pending", 0) },
            "scope=? AND native_removal_pending=1", arrayOf(key(scope)))
    }

    fun selections(db: SQLiteDatabase, account: AccountId): List<ImportStore.Selection> = run {
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
                    add(ImportStore.Selection(CalendarScope(account, id, long("generation")),
                        CalendarSummary(id, string("title"), if (cursor.isNull(colorIndex)) null else cursor.getInt(colorIndex)),
                        long("enabled") != 0L, string("status"),
                        if (cursor.isNull(completedIndex)) null else cursor.getLong(completedIndex), long("next_attempt"),
                        countDeferred(db, CalendarScope(account, id, long("generation"))),
                        countUnverified(db, CalendarScope(account, id, long("generation"))), long("available") != 0L,
                        hasReconciliation(db, CalendarScope(account, id, long("generation"))),
                        readCoverage(db, CalendarScope(account, id, long("generation"))).active,
                        countPending(db, CalendarScope(account, id, long("generation"))),
                        cursor.getInt(cursor.getColumnIndexOrThrow("retained_local_changes")) -
                            localMutations.completedDeleteEvents(db, CalendarScope(account, id, long("generation"))).size,
                        cursor.getInt(cursor.getColumnIndexOrThrow("local_conflicts"))))
                }
            }
        }
    }

    fun deferNetwork(db: SQLiteDatabase, scope: CalendarScope, milliseconds: Long) = run {
        val now = System.currentTimeMillis()
        val delay = milliseconds.coerceAtLeast(0).coerceAtMost(Long.MAX_VALUE - now)
        db.update("calendars", ContentValues().apply { put("next_attempt", now + delay) }, "scope=?", arrayOf(key(scope)))
    }

    fun setEnabled(db: SQLiteDatabase, scope: CalendarScope, enabled: Boolean) = run {
        requireKnown(db, scope)
        db.update("calendars", ContentValues().apply {
            put("enabled", if (enabled) 1 else 0)
            put("status", if (!localMutations.isAvailable(db, scope)) "UNAVAILABLE" else if (enabled) "PENDING" else "PAUSED")
        }, "scope=?", arrayOf(key(scope)))
    }

    fun status(db: SQLiteDatabase, scope: CalendarScope, status: String, completedAt: Long?) = run {
        require(status in setOf("PAUSED", "PENDING", "CREATE_PENDING", "RUNNING", "IMPORTED", "PARTIAL", "INCOMPLETE", "SERIES_PENDING", "HISTORY_GAP", "CAPACITY", "PERMISSION", "AUTH", "NETWORK", "UNSUPPORTED", "LOCAL_KEYS", "UNAVAILABLE", "ERROR"))
        requireKnown(db, scope)
        db.update("calendars", ContentValues().apply {
            put("status", if (localMutations.isAvailable(db, scope)) status else "UNAVAILABLE")
            if (completedAt != null && localMutations.isAvailable(db, scope)) put("completed_at", completedAt)
        }, "scope=?", arrayOf(key(scope)))
    }

    fun ensureMappingProfile(db: SQLiteDatabase, scope: CalendarScope, format: AdapterFormat): Unit = run {
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
    }

    fun beginRefresh(db: SQLiteDatabase, scope: CalendarScope, planned: PageCheckpoint?, expectedCoverage: CoverageLedger?): Unit = run {
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
    }

    fun beginDeltaRefresh(db: SQLiteDatabase, scope: CalendarScope, expectedCoverage: CoverageLedger): Unit = run {
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
    }

}
