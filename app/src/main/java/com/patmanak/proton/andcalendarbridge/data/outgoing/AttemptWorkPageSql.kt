// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.EventId
import com.patmanak.proton.andcalendarbridge.sync.MutationId
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Metadata discovery only, on the journal's owning transaction thread.
 * Includes one lookahead row; each typed journal validates its state and constructs its page.
 * Capsules and proofs never enter this cursor and listing grants no ownership or send authority.
 */
internal fun <T> readAttemptWorkRefs(
    db: SQLiteDatabase, table: String, scope: CalendarScope, afterEvent: EventId?, limit: Int,
    checkCancelled: () -> Unit, guest: Boolean = false, readRef: (MutationId, EventId, String) -> T,
): List<T> {
    require(table in setOf("text_update_attempts", "time_update_attempts", "personal_update_attempts", "delete_attempts"))
    fun text(column: String, max: Int) = "CASE WHEN typeof($column)='text' AND length(CAST($column AS BLOB)) BETWEEN 1 AND $max THEN CAST($column AS BLOB) END"
    val after = if (afterEvent == null) "" else " AND event COLLATE BINARY > ?"
    require(!guest || table == "personal_update_attempts")
    val family = if (table != "personal_update_attempts") "" else if (guest) " AND guest=1" else " AND guest=0"
    val args = buildList {
        add(scope.account.value); add(scope.calendar.value); add(scope.generation.toString())
        afterEvent?.let { add(it.value) }; add((limit + 1).toString())
    }.toTypedArray()
    return db.rawQuery("SELECT ${text("mutation", 4096)},${text("event", 4096)},${text("state", 15)} " +
        "FROM $table WHERE account=? AND calendar=? AND generation=? AND completion IS NULL$family$after ORDER BY event COLLATE BINARY LIMIT ?", args).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                checkCancelled(); require((0..2).all { cursor.getType(it) == Cursor.FIELD_TYPE_BLOB })
                fun strict(column: Int) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(cursor.getBlob(column))).toString()
                add(readRef(MutationId(strict(0)), EventId(strict(1)), strict(2)))
            }
        }
    }
}
