// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.patmanak.proton.andcalendarbridge.sync.AdapterFormat
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.CreateAttemptJournal
import com.patmanak.proton.andcalendarbridge.sync.CreateAttemptRecord
import com.patmanak.proton.andcalendarbridge.sync.CreateAttemptState
import com.patmanak.proton.andcalendarbridge.sync.MutationId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * App-private, backup-excluded dispatch journal. Payloads must already be adapter-protected.
 * Independent SQLite helpers/processes arbitrate admission through the same committed row.
 * This does not capture Provider edits, send requests, or acknowledge remote creation.
 */
class CreateAttemptStore(context: Context, name: String = "calendar-create-attempt-v1.db") :
    SQLiteOpenHelper(context, name, null, 1), CreateAttemptJournal {
    private val lock = Mutex()

    override fun onConfigure(db: SQLiteDatabase) {
        // Admission must return only after SQLite has durably recorded uncertainty.
        db.execSQL("PRAGMA synchronous=FULL")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE create_attempts (
                account TEXT NOT NULL,
                calendar TEXT NOT NULL,
                generation INTEGER NOT NULL CHECK(generation >= 0),
                mutation TEXT NOT NULL,
                adapter TEXT NOT NULL,
                version INTEGER NOT NULL CHECK(version > 0),
                payload TEXT NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('PREPARED', 'OUTCOME_UNKNOWN')),
                PRIMARY KEY(account, calendar, generation, mutation)
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Explicit create journal schema migration required")
    }

    override suspend fun remember(record: CreateAttemptRecord) {
        require(record.state == CreateAttemptState.PREPARED) { "Only prepared work can be remembered" }
        access { db ->
            transaction(db) {
                val existing = read(db, record.scope, record.id)
                if (existing != null) {
                    check(existing.copy(state = CreateAttemptState.PREPARED) == record) {
                        "Create attempt is immutable"
                    }
                } else {
                    db.insertOrThrow("create_attempts", null, ContentValues().apply {
                        put("account", record.scope.account.value)
                        put("calendar", record.scope.calendar.value)
                        put("generation", record.scope.generation)
                        put("mutation", record.id.value)
                        put("adapter", record.format.id)
                        put("version", record.format.version)
                        put("payload", record.protectedPayload)
                        put("state", CreateAttemptState.PREPARED.name)
                    })
                }
            }
        }
    }

    override suspend fun read(scope: CalendarScope, id: MutationId): CreateAttemptRecord? =
        access { db -> read(db, scope, id) }

    override suspend fun claim(expected: CreateAttemptRecord): Boolean {
        if (expected.state != CreateAttemptState.PREPARED) return false
        return access { db ->
            transaction(db) {
                db.update("create_attempts", ContentValues().apply {
                    put("state", CreateAttemptState.OUTCOME_UNKNOWN.name)
                }, "$IDENTITY AND adapter=? AND version=? AND payload=? AND state=?",
                    identity(expected.scope, expected.id) + arrayOf(expected.format.id,
                        expected.format.version.toString(), expected.protectedPayload,
                        CreateAttemptState.PREPARED.name)) == 1
            }
        }
    }

    private fun read(db: SQLiteDatabase, scope: CalendarScope, id: MutationId): CreateAttemptRecord? =
        db.query("create_attempts", arrayOf("adapter", "version", "payload", "state"),
            IDENTITY, identity(scope, id), null, null, null).use { cursor ->
            if (!cursor.moveToFirst()) null else CreateAttemptRecord(id, scope,
                AdapterFormat(cursor.getString(0), cursor.getInt(1)), cursor.getString(2),
                CreateAttemptState.valueOf(cursor.getString(3)))
        }

    private fun identity(scope: CalendarScope, id: MutationId) = arrayOf(scope.account.value,
        scope.calendar.value, scope.generation.toString(), id.value)

    private suspend fun <T> access(action: suspend (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            currentCoroutineContext().ensureActive()
            action(writableDatabase)
        }
    }

    private suspend fun <T> transaction(db: SQLiteDatabase, action: () -> T): T {
        db.beginTransaction()
        try {
            val result = action()
            currentCoroutineContext().ensureActive()
            db.setTransactionSuccessful()
            return result
        } finally {
            // A failed commit throws before the caller can admit dispatch.
            db.endTransaction()
        }
    }

    private companion object {
        const val IDENTITY = "account=? AND calendar=? AND generation=? AND mutation=?"
    }
}
