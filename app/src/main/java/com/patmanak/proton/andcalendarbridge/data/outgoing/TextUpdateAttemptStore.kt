// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Standalone quarantined history only. Never compose this database as dispatch authority.
 * The shared title helper owns cross-family arbitration; no automatic import is supported.
 */
class TextUpdateAttemptStore(context: Context, name: String = "calendar-text-update-attempt-v1.db") :
    SQLiteOpenHelper(context, name, null, 2), TextUpdateAttemptJournal {
    private val lock = Mutex()
    private val journal = SqlTextUpdateAttemptJournal(object : TextAttemptDatabase {
        override suspend fun <T> withDatabase(beforeOpen: () -> Unit, action: (SQLiteDatabase) -> T): T =
            withContext(Dispatchers.IO) { lock.withLock { beforeOpen(); action(writableDatabase) } }
    })
    override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
    override fun onCreate(db: SQLiteDatabase) = TextUpdateAttemptSql.createTable(db)
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "Explicit text journal migration required" }
        TextUpdateAttemptSql.migrateProofs(db)
    }
    override suspend fun remember(record: TextUpdateAttemptRecord, admit: (CalendarScope) -> Boolean) = journal.remember(record, admit)
    override suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean) = journal.read(scope, id, admit)
    override suspend fun claim(expected: TextUpdateAttemptRecord, admit: (CalendarScope) -> Boolean) = journal.claim(expected, admit)
    override suspend fun retainEvidence(expectedUnknown: TextUpdateAttemptRecord, protectedEvidence: String,
        admit: (CalendarScope) -> Boolean) = journal.retainEvidence(expectedUnknown, protectedEvidence, admit)
    override fun toString() = "TextUpdateAttemptStore(redacted)"
}
