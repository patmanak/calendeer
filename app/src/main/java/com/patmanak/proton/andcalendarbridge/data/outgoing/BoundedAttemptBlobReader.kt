// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.EventId
import com.patmanak.proton.andcalendarbridge.sync.MutationId
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Synchronous reads on the owning transaction thread; only bounded chunks enter CursorWindow. */
internal class BoundedAttemptBlobReader(
    private val db: SQLiteDatabase,
    private val table: String,
    private val checkCancelled: () -> Unit,
) {
    init {
        require(table in setOf("text_update_attempts", "time_update_attempts", "personal_update_attempts", "delete_attempts"))
    }

    /** Only bounded metadata and the capsule length enter this cursor. The typed caller
     * supplies its own codec limit and must qualify the decoded capsule against every field. */
    fun metadata(where: String, arguments: Array<String>, maxCapsuleBytes: Int): Metadata? {
        checkCancelled()
        fun text(column: String) = "CASE WHEN typeof($column)='text' AND length(CAST($column AS BLOB)) BETWEEN 1 AND 4096 " +
            "THEN CAST($column AS BLOB) END"
        val columns = listOf("rowid", text("account"), text("calendar"),
            "CASE WHEN typeof(generation)='integer' AND generation>=0 THEN generation END",
            text("mutation"), text("event"), text("state"),
            "CASE WHEN typeof(capsule)='blob' AND length(capsule) BETWEEN 1 AND $maxCapsuleBytes " +
                "THEN length(capsule) END").joinToString(",")
        return db.rawQuery("SELECT $columns FROM $table WHERE $where", arguments).use { row ->
            if (!row.moveToFirst()) return null
            require((0..7).all { row.getType(it) == if (it in setOf(0, 3, 7)) Cursor.FIELD_TYPE_INTEGER else Cursor.FIELD_TYPE_BLOB })
            fun strict(column: Int) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(row.getBlob(column))).toString()
            val value = Metadata(row.getLong(0), CalendarScope(AccountId(strict(1)), CalendarId(strict(2)), row.getLong(3)),
                MutationId(strict(4)), EventId(strict(5)), strict(6), row.getInt(7))
            require(!row.moveToNext())
            value
        }
    }

    data class Metadata(val rowId: Long, val scope: CalendarScope, val id: MutationId,
        val event: EventId, val state: String, val length: Int)

    /** The caller has already validated the capsule length and its typed ownership metadata. */
    fun capsule(rowId: Long, length: Int): ByteArray = chunks(rowId, "capsule", length)

    /** A missing row or malformed cell is corruption; only SQL NULL means no proof. */
    fun proof(rowId: Long, column: String): ByteArray? {
        require(column == "result" || column == "completion")
        checkCancelled()
        val length = db.rawQuery("SELECT CASE WHEN $column IS NULL THEN -1 " +
            "WHEN typeof($column)='blob' AND length($column) BETWEEN 1 AND 16777216 THEN length($column) END " +
            "FROM $table WHERE rowid=?", arrayOf(rowId.toString())).use { row ->
            require(row.moveToFirst() && row.getType(0) == Cursor.FIELD_TYPE_INTEGER)
            row.getInt(0).also { require(!row.moveToNext()) }
        }
        if (length == -1) return null
        require(length in 1..16777216)
        return chunks(rowId, column, length)
    }

    private fun chunks(rowId: Long, column: String, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            checkCancelled()
            val count = minOf(CHUNK_BYTES, length - offset)
            db.rawQuery("SELECT substr($column,?,?) FROM $table WHERE rowid=?",
                arrayOf((offset + 1).toString(), count.toString(), rowId.toString())).use { row ->
                require(row.moveToFirst() && row.getType(0) == Cursor.FIELD_TYPE_BLOB)
                val part = row.getBlob(0)
                require(part.size == count && !row.moveToNext())
                part.copyInto(bytes, offset)
            }
            offset += count
        }
        checkCancelled()
        return bytes
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
    }
}
