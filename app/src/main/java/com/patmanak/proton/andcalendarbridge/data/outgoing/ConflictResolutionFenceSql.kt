// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** All calls require the shared journal's writer transaction. Released rows are permanent tombstones. */
internal class ConflictResolutionFenceSql(private val db: SQLiteDatabase) {
    data class Retained(val fence: ConflictResolutionFence, val proof: String?)

    fun history(scope: CalendarScope, event: EventId, ids: List<MutationId> = emptyList()): List<Retained> {
        val identityClause = if (ids.isEmpty()) "" else
            " OR local_mutation IN (${ids.joinToString { "?" }}) OR resolution IN (${ids.joinToString { "?" }})"
        val args = arrayOf(scope.account.value, scope.calendar.value, event.value) +
            ids.map { it.value }.toTypedArray() + ids.map { it.value }.toTypedArray()
        val fields = listOf("event", "local_mutation", "resolution", "fingerprint", "proof")
        val columns = "generation," + fields.joinToString { column ->
            "CASE WHEN typeof($column)='text' AND length(CAST($column AS BLOB)) BETWEEN 1 AND 4096 THEN CAST($column AS BLOB) END"
        } + ",proof IS NULL"
        return db.rawQuery("SELECT $columns FROM $TABLE WHERE account=? AND calendar=? AND (event=?$identityClause) LIMIT 129", args).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    require(size < 128 && rows.getType(0) == Cursor.FIELD_TYPE_INTEGER)
                    fun token(index: Int): String {
                        require(rows.getType(index) == Cursor.FIELD_TYPE_BLOB)
                        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(rows.getBlob(index))).toString()
                    }
                    val fence = ConflictResolutionFence(scope.copy(generation = rows.getLong(0)), EventId(token(1)),
                        MutationId(token(2)), MutationId(token(3)), token(4))
                    val proof = if (rows.getInt(6) == 1) null else token(5).also {
                        ConflictResolutionFinalization(fence, it)
                    }
                    add(Retained(fence, proof))
                }
            }.also { history -> require(history.filter { it.proof == null }.groupBy { it.fence.eventId }.all { it.value.size == 1 }) }
        }
    }

    fun blocks(scope: CalendarScope, event: EventId, request: MutationId, local: MutationId): Boolean =
        history(scope, event, listOf(request, local)).any {
            (it.proof == null && it.fence.eventId == event) ||
                it.fence.localMutationId in listOf(request, local) || it.fence.resolutionId in listOf(request, local)
        }

    fun insert(fence: ConflictResolutionFence) {
        db.insertOrThrow(TABLE, null, ContentValues().apply {
            put("account", fence.scope.account.value); put("calendar", fence.scope.calendar.value)
            put("generation", fence.scope.generation); put("event", fence.eventId.value)
            put("local_mutation", fence.localMutationId.value); put("resolution", fence.resolutionId.value)
            put("fingerprint", fence.fingerprint)
        })
    }

    fun release(value: ConflictResolutionFinalization): Boolean {
        val fence = value.fence
        val retained = history(fence.scope, fence.eventId, listOf(fence.localMutationId, fence.resolutionId))
            .singleOrNull { it.fence == fence } ?: return false
        if (retained.proof != null) return retained.proof == value.proofFingerprint
        return db.update(TABLE, ContentValues().apply { put("proof", value.proofFingerprint) },
            "account=? AND calendar=? AND resolution=? AND proof IS NULL",
            arrayOf(fence.scope.account.value, fence.scope.calendar.value, fence.resolutionId.value)) == 1
    }

    companion object {
        private const val TABLE = "conflict_resolution_fences"
        fun createTable(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE $TABLE (
                account TEXT NOT NULL, calendar TEXT NOT NULL,
                generation INTEGER NOT NULL CHECK(generation>=0), event TEXT NOT NULL,
                local_mutation TEXT NOT NULL, resolution TEXT NOT NULL,
                fingerprint TEXT NOT NULL, proof TEXT,
                PRIMARY KEY(account,calendar,resolution), UNIQUE(account,calendar,local_mutation)
            )""".trimIndent())
            db.execSQL("CREATE UNIQUE INDEX conflict_resolution_active_event ON $TABLE(account,calendar,event) WHERE proof IS NULL")
        }
    }
}
