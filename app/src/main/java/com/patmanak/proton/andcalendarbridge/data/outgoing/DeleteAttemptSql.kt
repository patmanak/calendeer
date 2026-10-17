// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.data.serialization.DeleteAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.DeleteProofStorageCodec
import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Synchronous SQL only, called on the owning writer-transaction thread.
 * Index discovery is never proof: every discovered record is fully decoded and qualified.
 */
internal class DeleteAttemptSql(private val db: SQLiteDatabase, private val checkCancelled: () -> Unit) {
    fun hasOwner(scope: CalendarScope, event: EventId, id: MutationId): Boolean =
        owners(scope, event, id).isNotEmpty()
    fun read(scope: CalendarScope, id: MutationId): DeleteAttemptRecord? =
        readStored(db, scope, id)?.also { readCompletion(it) }?.record

    fun remember(record: DeleteAttemptRecord, capsule: ByteArray): Boolean {
        val existing = readStored(db, record.scope, record.id)
        val owners = owners(record.scope, record.eventId, record.id)
        return if (existing != null) existing.record == record && owners.singleOrNull()?.rowId == existing.rowId
        else if (owners.isNotEmpty()) false else {
            db.insertOrThrow(TABLE, null, ContentValues().apply {
                put("account", record.scope.account.value); put("calendar", record.scope.calendar.value)
                put("generation", record.scope.generation); put("mutation", record.id.value)
                put("event", record.eventId.value); put("state", record.state.name); put("capsule", capsule)
            })
            true
        }
    }
    fun claim(expected: DeleteAttemptRecord, capsule: ByteArray): Boolean {
        val current = readStored(db, expected.scope, expected.id)
        if (current?.record != expected || owners(expected.scope, expected.eventId, expected.id).singleOrNull()?.rowId != current.rowId)
            return false
        check(db.update(TABLE, ContentValues().apply {
            put("state", DeleteAttemptState.OUTCOME_UNKNOWN.name); put("capsule", capsule)
        }, "rowid=?", arrayOf(current.rowId.toString())) == 1)
        return true
    }
    fun retainEvidence(expected: DeleteAttemptRecord, evidence: String, capsule: ByteArray): Boolean {
        val current = readStored(db, expected.scope, expected.id)
        current?.let { readCompletion(it) }
        if (current == null || current.record.copy(protectedEvidence = null) != expected.copy(protectedEvidence = null) ||
            current.record.state != DeleteAttemptState.OUTCOME_UNKNOWN) return false
        if (current.record.protectedEvidence != null) return current.record.protectedEvidence == evidence
        check(db.update(TABLE, ContentValues().apply { put("capsule", capsule) },
            "rowid=?", arrayOf(current.rowId.toString())) == 1)
        return true
    }

    private data class Stored(val rowId: Long, val record: DeleteAttemptRecord)
    private data class Owner(val rowId: Long, val id: MutationId, val event: EventId, val completed: Boolean)
    private fun identity(scope: CalendarScope, id: MutationId) = arrayOf(scope.account.value, scope.calendar.value,
        scope.generation.toString(), id.value)

    /** Both ownership keys span generations. Validate conflicting records too; corruption is not absence. */
    private fun owners(scope: CalendarScope, event: EventId, id: MutationId): List<Owner> =
        db.rawQuery("SELECT rowid FROM $TABLE WHERE account=? AND calendar=? AND (event=? OR mutation=?)",
            arrayOf(scope.account.value, scope.calendar.value, event.value, id.value)).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    checkCancelled(); require(rows.getType(0) == Cursor.FIELD_TYPE_INTEGER)
                    val stored = requireNotNull(readStored(db, "rowid=?", arrayOf(rows.getLong(0).toString())))
                    // Non-null completion alone never releases an event. Decode one whole history at a time.
                    val completed = readCompletion(stored) != null
                    add(Owner(stored.rowId, stored.record.id, stored.record.eventId, completed))
                }
            }.also { owners -> require(owners.groupBy { it.event }.all { it.value.size <= 1 }) }
        }

    fun readVerified(scope: CalendarScope, id: MutationId): DeleteVerifiedResult? =
        readStored(db, scope, id)?.let { readCompletion(it); readVerified(it) }
    private fun readVerified(stored: Stored): DeleteVerifiedResult? {
        val bytes = proofBlob(stored.rowId, "result") ?: return null
        val result = DeleteProofStorageCodec.decodeResult(bytes)
        require(stored.record.state == DeleteAttemptState.OUTCOME_UNKNOWN &&
            result.record == stored.record.copy(protectedEvidence = null))
        return result
    }
    fun retainVerified(value: DeleteVerifiedResult, capsule: ByteArray): Boolean {
        val stored = readStored(db, value.record.scope, value.record.id) ?: return false
        if (stored.record.state != DeleteAttemptState.OUTCOME_UNKNOWN ||
            stored.record.copy(protectedEvidence = null) != value.record) return false
        readCompletion(stored)
        val first = readVerified(stored)
        if (first != null) return first == value
        require(proofBlob(stored.rowId, "completion") == null)
        check(db.update(TABLE, ContentValues().apply { put("result", capsule) },
            "rowid=? AND result IS NULL", arrayOf(stored.rowId.toString())) == 1)
        return true
    }
    fun readCompletion(scope: CalendarScope, id: MutationId): DeleteCompletion? =
        readStored(db, scope, id)?.let(::readCompletion)
    private fun readCompletion(stored: Stored): DeleteCompletion? {
        val result = readVerified(stored)
        val bytes = proofBlob(stored.rowId, "completion") ?: return null
        val completion = DeleteProofStorageCodec.decodeCompletion(bytes)
        require(completion.result == result && completion.result.record == stored.record.copy(protectedEvidence = null))
        return completion
    }
    fun retainCompletion(value: DeleteCompletion, capsule: ByteArray, admitOwnership: () -> Boolean): Boolean {
        val stored = readStored(db, value.result.record.scope, value.result.record.id) ?: return false
        if (stored.record.state != DeleteAttemptState.OUTCOME_UNKNOWN ||
            stored.record.copy(protectedEvidence = null) != value.result.record) return false
        val first = readCompletion(stored)
        if (!admitOwnership()) return false
        val owner = owners(stored.record.scope, stored.record.eventId, stored.record.id).singleOrNull()
        if (owner?.rowId != stored.rowId) return false
        if (first != null) return first == value
        if (readVerified(stored) != value.result) return false
        check(db.update(TABLE, ContentValues().apply { put("completion", capsule) },
            "rowid=? AND completion IS NULL", arrayOf(stored.rowId.toString())) == 1)
        return true
    }
    private fun readStored(db: SQLiteDatabase, scope: CalendarScope, id: MutationId): Stored? =
        readStored(db, "account=? AND calendar=? AND generation=? AND mutation=?", identity(scope, id))?.also {
            require(it.record.scope == scope && it.record.id == id)
        }

    /** No full BLOB enters a CursorWindow. Every metadata cell is bounded before materialization. */
    private fun readStored(db: SQLiteDatabase, where: String, arguments: Array<String>): Stored? {
        checkCancelled()
        fun text(column: String) = "CASE WHEN typeof($column)='text' AND length(CAST($column AS BLOB)) BETWEEN 1 AND 4096 " +
            "THEN CAST($column AS BLOB) END"
        val columns = listOf("rowid", text("account"), text("calendar"),
            "CASE WHEN typeof(generation)='integer' AND generation>=0 THEN generation END",
            text("mutation"), text("event"), text("state"),
            "CASE WHEN typeof(capsule)='blob' AND length(capsule) BETWEEN 1 AND ${DeleteAttemptStorageCodec.MAX_CAPSULE_BYTES} " +
                "THEN length(capsule) END").joinToString(",")
        val metadata = db.rawQuery("SELECT $columns FROM $TABLE WHERE $where", arguments).use { row ->
            if (!row.moveToFirst()) return null
            require((0..7).all { row.getType(it) == if (it in setOf(0, 3, 7)) Cursor.FIELD_TYPE_INTEGER else Cursor.FIELD_TYPE_BLOB })
            fun strict(column: Int) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(row.getBlob(column))).toString()
            val value = Metadata(row.getLong(0), CalendarScope(AccountId(strict(1)), CalendarId(strict(2)), row.getLong(3)),
                MutationId(strict(4)), EventId(strict(5)), strict(6), row.getInt(7))
            require(!row.moveToNext())
            value
        }
        val bytes = ByteArrayOutputStream(metadata.length)
        var offset = 0
        while (offset < metadata.length) {
            checkCancelled()
            val count = minOf(CHUNK_BYTES, metadata.length - offset)
            db.rawQuery("SELECT substr(capsule,?,?) FROM $TABLE WHERE rowid=?",
                arrayOf((offset + 1).toString(), count.toString(), metadata.rowId.toString())).use { chunk ->
                require(chunk.moveToFirst() && chunk.getType(0) == Cursor.FIELD_TYPE_BLOB)
                val part = chunk.getBlob(0)
                require(part.size == count && !chunk.moveToNext())
                bytes.write(part)
            }
            offset += count
        }
        checkCancelled()
        require(bytes.size() == metadata.length)
        val record = DeleteAttemptStorageCodec.decode(bytes.toByteArray())
        require(record.scope == metadata.scope && record.id == metadata.id && record.eventId == metadata.event &&
            record.state.name == metadata.state)
        return Stored(metadata.rowId, record)
    }
    private data class Metadata(val rowId: Long, val scope: CalendarScope, val id: MutationId,
        val event: EventId, val state: String, val length: Int)

    /** Fixed internal column names only; metadata is bounded before materializing any BLOB. */
    private fun proofBlob(rowId: Long, column: String): ByteArray? {
        require(column == "result" || column == "completion")
        checkCancelled()
        val length = db.rawQuery("SELECT CASE WHEN $column IS NULL THEN -1 " +
            "WHEN typeof($column)='blob' AND length($column) BETWEEN 1 AND 16777216 THEN length($column) END " +
            "FROM $TABLE WHERE rowid=?", arrayOf(rowId.toString())).use { row ->
            require(row.moveToFirst() && row.getType(0) == Cursor.FIELD_TYPE_INTEGER)
            row.getInt(0).also { require(!row.moveToNext()) }
        }
        if (length == -1) return null
        require(length in 1..16777216)
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            checkCancelled()
            val count = minOf(CHUNK_BYTES, length - offset)
            db.rawQuery("SELECT substr($column,?,?) FROM $TABLE WHERE rowid=?",
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

    companion object {
        private const val TABLE = "delete_attempts"
        private const val CHUNK_BYTES = 64 * 1024
        fun createTable(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE delete_attempts (
                account TEXT NOT NULL, calendar TEXT NOT NULL,
                generation INTEGER NOT NULL CHECK(generation >= 0),
                mutation TEXT NOT NULL, event TEXT NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('PREPARED','OUTCOME_UNKNOWN')),
                capsule BLOB NOT NULL, result BLOB, completion BLOB,
                PRIMARY KEY(account,calendar,generation,mutation),
                UNIQUE(account,calendar,mutation)
            )""".trimIndent())
            db.execSQL("CREATE UNIQUE INDEX delete_unresolved_event ON delete_attempts(account,calendar,event)")
        }
        /** SQLiteOpenHelper owns the upgrade transaction. Copy opaque bytes without interpretation. */
        fun migrateProofs(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE delete_attempts ADD COLUMN result BLOB")
            db.execSQL("ALTER TABLE delete_attempts ADD COLUMN completion BLOB")
        }
    }
}
