// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.LocalEventId
import com.patmanak.proton.andcalendarbridge.sync.MutationId
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SystemCreateWorkStatus { PENDING, CAPTURED, WAITING, UNSUPPORTED, UNKNOWN, COMPLETE }

data class HistoricalCreateIssue(val scope: CalendarScope, val row: Long, val marker: String,
    val reason: HistoricalCreateIssueReason, val after: String?, val id: Long) {
    override fun toString() = "HistoricalCreateIssue(REDACTED, reason=$reason)"
}

/** Row is an Android locator, never a durable identity for an outgoing request. */
data class SystemCreateWork(
    val scope: CalendarScope,
    val row: Long,
    val localId: LocalEventId,
    val captureId: MutationId,
    val attempt: MutationId,
    val timestamp: Instant,
    val status: SystemCreateWorkStatus,
) {
    init {
        require(row > 0 && timestamp.nano == 0)
        require(UUID.fromString(captureId.value).toString() == captureId.value)
        require(UUID.fromString(attempt.value).toString() == attempt.value)
    }
    override fun toString() = "SystemCreateWork(REDACTED, status=$status)"
}

/**
 * Backup-excluded identity journal. No event content, network payload or credentials are stored.
 * Reserve all identities before capture; reopening blocked or uncertain work never allocates a
 * fresh attempt. A completed row can be reserved again only when a caller has independently
 * established that it now belongs to a new, unstamped native event.
 */
class SystemCreateWorkStore(context: Context, name: String = "system-creates.db") :
    SQLiteOpenHelper(context, name, null, 2) {
    private val lock = Mutex()
    private val modes = context.getSharedPreferences("system-create-modes", Context.MODE_PRIVATE)

    override fun onConfigure(db: SQLiteDatabase) {
        db.execSQL("PRAGMA synchronous=FULL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE system_creates (
                account TEXT NOT NULL, calendar TEXT NOT NULL,
                generation INTEGER NOT NULL CHECK(generation >= 0),
                native_row INTEGER NOT NULL CHECK(native_row > 0),
                local_id TEXT NOT NULL, capture_id TEXT NOT NULL, attempt TEXT NOT NULL,
                timestamp TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN
                    ('PENDING','CAPTURED','WAITING','UNSUPPORTED','UNKNOWN','COMPLETE')),
                PRIMARY KEY(account, calendar, generation, native_row),
                UNIQUE(local_id), UNIQUE(capture_id), UNIQUE(attempt)
            )
        """.trimIndent())
        createHistoryIssues(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "Explicit system create work migration required" }
        createHistoryIssues(db)
    }

    private fun createHistoryIssues(db: SQLiteDatabase) {
        db.execSQL("""
        CREATE TABLE historical_create_issues (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL CHECK(generation >= 0),
            native_row INTEGER NOT NULL CHECK(native_row > 0), marker TEXT NOT NULL,
            reason TEXT NOT NULL CHECK(reason IN ('SEARCHING','MISSING_HISTORY','INCOMPLETE_PROOF')),
            after_local TEXT,
            UNIQUE(account, calendar, generation, native_row, marker)
        )
        """.trimIndent())
        db.execSQL("CREATE INDEX historical_create_issues_scope_id ON historical_create_issues(account, calendar, generation, id)")
    }

    suspend fun historyIssue(scope: CalendarScope, row: Long, marker: String?): HistoricalCreateIssue? = query { db ->
        db.query("historical_create_issues", null, "$SCOPE_ROW AND marker=?", args(scope, row) + (marker ?: ""),
            null, null, null).use { if (it.moveToFirst()) historyIssue(it) else null }
    }

    /** Indexed bounded metadata surface for inspection. No protected event contents are loaded. */
    suspend fun historyIssues(scope: CalendarScope, afterId: Long = 0, limit: Int = 25): List<HistoricalCreateIssue> = query { db ->
        require(afterId >= 0 && limit in 1..50)
        db.query("historical_create_issues", null, "$SCOPE AND id>?", scopeArgs(scope) + afterId.toString(),
            null, null, "id ASC", limit.toString()).use { cursor ->
            buildList { while (cursor.moveToNext()) add(historyIssue(cursor)) }
        }
    }

    /** Restoration inserts only an independently proven COMPLETE owner; an existing identity wins. */
    suspend fun retainHistoricalRecovery(scope: CalendarScope, row: Long, marker: String?, result: HistoricalCreateRecovery): Boolean = transaction { db ->
        require(row > 0 && (marker == null || marker.length <= 4096))
        val restored = result.work
        val prior = read(db, scope, row)
        if (restored != null) {
            require(restored.scope == scope && restored.row == row && restored.status == SystemCreateWorkStatus.COMPLETE)
            if (prior != null && prior != restored) return@transaction false
            if (prior == null) {
                // No conflict replacement: uniqueness conflicts preserve every original identity.
                if (db.insertWithOnConflict("system_creates", null, values(restored), SQLiteDatabase.CONFLICT_IGNORE) == -1L)
                    return@transaction false
            }
            db.delete("historical_create_issues", "$SCOPE_ROW AND marker=?", args(scope, row) + (marker ?: ""))
            true
        } else {
            if (prior != null) return@transaction false
            val values = ContentValues().apply {
                put("account", scope.account.value); put("calendar", scope.calendar.value); put("generation", scope.generation)
                put("native_row", row); put("marker", marker ?: ""); put("reason", result.reason.name)
                put("after_local", result.after)
            }
            if (db.update("historical_create_issues", values, "$SCOPE_ROW AND marker=?", args(scope, row) + (marker ?: "")) == 0)
                db.insertOrThrow("historical_create_issues", null, values)
            false
        }
    }

    private fun historyIssue(cursor: Cursor): HistoricalCreateIssue {
        fun text(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
        return HistoricalCreateIssue(CalendarScope(AccountId(text("account")), CalendarId(text("calendar")),
            cursor.getLong(cursor.getColumnIndexOrThrow("generation"))), cursor.getLong(cursor.getColumnIndexOrThrow("native_row")),
            text("marker"), HistoricalCreateIssueReason.valueOf(text("reason")), text("after_local"),
            cursor.getLong(cursor.getColumnIndexOrThrow("id")))
    }

    suspend fun reserve(scope: CalendarScope, row: Long): SystemCreateWork {
        require(row > 0)
        return transaction { db ->
            val previous = read(db, scope, row)
            if (previous != null && previous.status != SystemCreateWorkStatus.COMPLETE) previous
            else {
                val work = SystemCreateWork(scope, row, LocalEventId(UUID.randomUUID().toString()),
                    MutationId(UUID.randomUUID().toString()), MutationId(UUID.randomUUID().toString()),
                    Instant.now().truncatedTo(ChronoUnit.SECONDS), SystemCreateWorkStatus.PENDING)
                // The transaction's write lock spans read and replacement, including other helpers.
                if (previous != null) check(db.delete("system_creates", SCOPE_ROW, args(scope, row)) == 1)
                db.insertOrThrow("system_creates", null, values(work))
                work
            }
        }
    }

    /** Includes completed work so an old immutable identity can be checked after restart. */
    suspend fun read(scope: CalendarScope, row: Long): SystemCreateWork? {
        require(row > 0)
        return query { read(it, scope, row) }
    }

    suspend fun page(scope: CalendarScope, afterRow: Long = 0, limit: Int = 25): List<SystemCreateWork> {
        require(afterRow >= 0 && limit in 1..100)
        return query { db ->
            db.query("system_creates", null,
                "$SCOPE AND native_row > ? AND status != 'COMPLETE'", args(scope, afterRow),
                null, null, "native_row ASC", limit.toString()).use { cursor ->
                buildList { while (cursor.moveToNext()) add(work(cursor)) }
            }
        }
    }

    /** Changes only the exact persisted identity; the caller's old status is intentionally ignored. */
    suspend fun status(work: SystemCreateWork, newStatus: SystemCreateWorkStatus): Boolean = transaction { db ->
        db.update("system_creates", ContentValues().apply { put("status", newStatus.name) },
            "$SCOPE_ROW AND local_id = ? AND capture_id = ? AND attempt = ? AND timestamp = ?",
            args(work.scope, work.row) + arrayOf(work.localId.value, work.captureId.value,
                work.attempt.value, work.timestamp.toString())) == 1
    }

    suspend fun count(scope: CalendarScope): Int = query { db ->
        val pending = db.rawQuery("SELECT COUNT(*) FROM system_creates WHERE $SCOPE AND status != 'COMPLETE'",
            scopeArgs(scope)).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }
        pending + db.rawQuery("SELECT COUNT(*) FROM historical_create_issues WHERE $SCOPE",
            scopeArgs(scope)).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }
    }

    /** Opt-in is scoped to the complete account/calendar/generation and defaults to disabled. */
    fun modeEnabled(scope: CalendarScope): Boolean = modes.getBoolean(modeKey(scope), false)

    @Synchronized fun textsEnabled(scope: CalendarScope): Boolean =
        modeEnabled(scope) && modes.getBoolean("texts-${modeKey(scope)}", false)

    @Synchronized fun setTextsMode(scope: CalendarScope, enabled: Boolean) {
        check(modes.edit().putBoolean("texts-${modeKey(scope)}", enabled && modeEnabled(scope)).commit()) {
            "System text mode was not persisted"
        }
    }

    @Synchronized fun timesEnabled(scope: CalendarScope): Boolean =
        modeEnabled(scope) && modes.getBoolean("times-${modeKey(scope)}", false)

    @Synchronized fun setTimesMode(scope: CalendarScope, enabled: Boolean) {
        check(modes.edit().putBoolean("times-${modeKey(scope)}", enabled && modeEnabled(scope)).commit()) {
            "System time mode was not persisted"
        }
    }

    @Synchronized fun titlesEnabled(scope: CalendarScope): Boolean =
        modeEnabled(scope) && modes.getBoolean("titles-${modeKey(scope)}", false)

    @Synchronized fun deletesEnabled(scope: CalendarScope): Boolean =
        modeEnabled(scope) && modes.getBoolean("deletes-${modeKey(scope)}", false)

    @Synchronized fun setDeletesMode(scope: CalendarScope, enabled: Boolean) {
        check(modes.edit().putBoolean("deletes-${modeKey(scope)}", enabled && modeEnabled(scope)).commit()) {
            "System delete mode was not persisted"
        }
    }

    @Synchronized fun setTitlesMode(scope: CalendarScope, enabled: Boolean) {
        check(modes.edit().putBoolean("titles-${modeKey(scope)}", enabled && modeEnabled(scope)).commit()) {
            "System title mode was not persisted"
        }
    }

    @Synchronized fun setMode(scope: CalendarScope, enabled: Boolean) {
        val update = modes.edit().putBoolean(modeKey(scope), enabled)
        if (!enabled) update.remove("titles-${modeKey(scope)}").remove("texts-${modeKey(scope)}")
            .remove("times-${modeKey(scope)}").remove("deletes-${modeKey(scope)}")
        check(update.commit()) { "System create mode was not persisted" }
    }

    override fun toString() = "SystemCreateWorkStore(REDACTED)"

    private suspend fun <T> query(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock { block(readableDatabase) }
    }

    private suspend fun <T> transaction(block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val db = writableDatabase
            db.beginTransaction()
            try { block(db).also { db.setTransactionSuccessful() } }
            finally { db.endTransaction() }
        }
    }

    private fun read(db: SQLiteDatabase, scope: CalendarScope, row: Long): SystemCreateWork? =
        db.query("system_creates", null, SCOPE_ROW, args(scope, row), null, null, null).use {
            if (it.moveToFirst()) work(it) else null
        }

    private fun work(cursor: Cursor): SystemCreateWork {
        fun text(name: String) = cursor.getString(cursor.getColumnIndexOrThrow(name))
        fun number(name: String) = cursor.getLong(cursor.getColumnIndexOrThrow(name))
        return SystemCreateWork(
            CalendarScope(AccountId(text("account")), CalendarId(text("calendar")), number("generation")),
            number("native_row"), LocalEventId(text("local_id")), MutationId(text("capture_id")),
            MutationId(text("attempt")), Instant.parse(text("timestamp")),
            SystemCreateWorkStatus.valueOf(text("status")),
        )
    }

    private fun values(work: SystemCreateWork) = ContentValues().apply {
        put("account", work.scope.account.value); put("calendar", work.scope.calendar.value)
        put("generation", work.scope.generation); put("native_row", work.row)
        put("local_id", work.localId.value); put("capture_id", work.captureId.value)
        put("attempt", work.attempt.value); put("timestamp", work.timestamp.toString())
        put("status", work.status.name)
    }

    private fun scopeArgs(scope: CalendarScope) =
        arrayOf(scope.account.value, scope.calendar.value, scope.generation.toString())
    private fun args(scope: CalendarScope, row: Long) = scopeArgs(scope) + row.toString()

    private fun modeKey(scope: CalendarScope): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Length framing avoids collisions such as ["a:b", "c"] and ["a", "b:c"].
        listOf("system-create-mode-v1", scope.account.value, scope.calendar.value,
            scope.generation.toString()).forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return "v1-" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val SCOPE = "account = ? AND calendar = ? AND generation = ?"
        const val SCOPE_ROW = "$SCOPE AND native_row = ?"
    }
}
