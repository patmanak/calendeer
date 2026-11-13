// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.archiveText
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.archiveString
import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.stagedQuery

/** Read-only discovery, never ownership or permission to resend. Callers retain their store lock. */
internal object LegacyUpdateCompatibility {
    fun hasPendingRequests(db: SQLiteDatabase, scope: CalendarScope, check: () -> Unit): Boolean {
        val args = arrayOf(scope.account.value, scope.calendar.value)
        for (table in listOf("title_update_attempts", "text_update_attempts", "time_update_attempts")) {
            if (exists(db, "SELECT 1 FROM $table WHERE account=? AND calendar=? AND completion IS NULL LIMIT 1", args, check))
                return true
        }
        // A bare reservation can block personal ownership across generations without a wire row.
        return exists(db, "SELECT 1 FROM title_update_preparation_reservations r WHERE r.account=? AND r.calendar=? " +
            "AND NOT EXISTS (SELECT 1 FROM title_update_attempts t WHERE t.account=r.account AND t.calendar=r.calendar " +
            "AND t.generation=r.generation AND t.mutation=r.mutation AND t.completion IS NOT NULL) LIMIT 1", args, check)
    }

    fun hasRetainedCapsules(db: SQLiteDatabase, scope: CalendarScope, check: () -> Unit): Boolean {
        val args = arrayOf(scope.account.value, scope.calendar.value)
        for (table in listOf("local_edit_preparations", "local_text_edit_preparations", "local_time_edit_preparations")) {
            if (exists(db, "SELECT 1 FROM $table WHERE scope IN (SELECT scope FROM calendars WHERE account=? AND calendar=?) LIMIT 1",
                    args, check)) return true
        }
        return false
    }

    /** Old title intents may have dates/zones which personal-v2 cannot safely represent. */
    fun hasUnsupportedIntent(db: SQLiteDatabase, scopeKey: String, read: (EventId) -> LocalEditIntent?, check: () -> Unit): Boolean {
        var after: String? = null
        while (true) {
            check()
            val position = if (after == null) "" else " AND event COLLATE BINARY > ?"
            val args = if (after == null) arrayOf(scopeKey) else arrayOf(scopeKey, requireNotNull(after))
            val ids = stagedQuery(db, "SELECT ${archiveText("event", 4096)} FROM local_edits WHERE scope=? AND kind='UPDATE'" +
                position + " ORDER BY event COLLATE BINARY LIMIT 25", args).use { cursor ->
                buildList { while (cursor.moveToNext()) add(EventId(archiveString(cursor, 0))) }
            }
            for (event in ids) {
                check()
                val intent = read(event) ?: return true
                val supported = SystemPersonalEditProfile.accepts(intent)
                check()
                if (!supported) return true
            }
            if (ids.size < 25) return false
            after = ids.last().value
        }
    }

    private fun exists(db: SQLiteDatabase, sql: String, args: Array<String>, check: () -> Unit): Boolean {
        check()
        val found = db.rawQuery(sql, args).use { it.moveToFirst() }
        check()
        return found
    }
}
