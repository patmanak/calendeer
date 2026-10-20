// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/** Synchronous reads on the owning transaction thread; only bounded chunks enter CursorWindow. */
internal class BoundedAttemptBlobReader(
    private val db: SQLiteDatabase,
    private val table: String,
    private val checkCancelled: () -> Unit,
) {
    init {
        require(table in setOf("text_update_attempts", "time_update_attempts", "delete_attempts"))
    }

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
