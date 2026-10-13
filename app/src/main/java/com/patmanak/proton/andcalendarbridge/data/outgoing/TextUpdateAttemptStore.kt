// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.data.serialization.TextUpdateAttemptStorageCodec
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Quarantined local history only. Not composed into production and never dispatch authority.
 * Transactional arbitration with title ownership is required before any broader sending.
 */
class TextUpdateAttemptStore(context: Context, name: String = "calendar-text-update-attempt-v1.db") :
    SQLiteOpenHelper(context, name, null, 1), TextUpdateAttemptJournal {
    private val lock = Mutex()
    override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE text_update_attempts (
            account TEXT NOT NULL, calendar TEXT NOT NULL,
            generation INTEGER NOT NULL CHECK(generation >= 0),
            mutation TEXT NOT NULL, event TEXT NOT NULL,
            state TEXT NOT NULL CHECK(state IN ('PREPARED','OUTCOME_UNKNOWN')),
            capsule BLOB NOT NULL,
            PRIMARY KEY(account,calendar,generation,mutation),
            UNIQUE(account,calendar,event), UNIQUE(account,calendar,mutation)
        )""".trimIndent())
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Explicit text journal migration required")
    }
    override suspend fun remember(record: TextUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        if (frozen.state != TextUpdateAttemptState.PREPARED || frozen.protectedEvidence != null) return false
        val capsule = TextUpdateAttemptStorageCodec.encode(frozen)
        return access(frozen.scope, admit, false) { db ->
            val existing = readStored(db, frozen.scope, frozen.id)
            val owners = owners(db, frozen)
            if (existing != null) existing.record == frozen && owners.singleOrNull()?.rowId == existing.rowId
            else if (owners.isNotEmpty()) false else {
                db.insertOrThrow(TABLE, null, ContentValues().apply {
                    put("account", frozen.scope.account.value); put("calendar", frozen.scope.calendar.value)
                    put("generation", frozen.scope.generation); put("mutation", frozen.id.value)
                    put("event", frozen.eventId.value); put("state", frozen.state.name); put("capsule", capsule)
                })
                true
            }
        }
    }
    override suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TextUpdateAttemptRecord? {
        currentCoroutineContext().ensureActive()
        reference(scope, id)
        return access(scope, admit, null) { readStored(it, scope, id)?.record }
    }
    override suspend fun claim(expected: TextUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = expected.frozen()
        if (frozen.state != TextUpdateAttemptState.PREPARED || frozen.protectedEvidence != null) return false
        val unknown = frozen.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
        val capsule = TextUpdateAttemptStorageCodec.encode(unknown)
        return access(frozen.scope, admit, false) { db ->
            val current = readStored(db, frozen.scope, frozen.id)
            if (current?.record != frozen || owners(db, frozen).singleOrNull()?.rowId != current.rowId) false
            else {
                check(db.update(TABLE, ContentValues().apply {
                    put("state", unknown.state.name); put("capsule", capsule)
                }, "rowid=?", arrayOf(current.rowId.toString())) == 1)
                true
            }
        }
    }
    override suspend fun retainEvidence(expectedUnknown: TextUpdateAttemptRecord, protectedEvidence: String,
        admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = expectedUnknown.frozen()
        if (frozen.state != TextUpdateAttemptState.OUTCOME_UNKNOWN ||
            (frozen.protectedEvidence != null && frozen.protectedEvidence != protectedEvidence)) return false
        val withEvidence = frozen.copy(protectedEvidence = protectedEvidence)
        val capsule = TextUpdateAttemptStorageCodec.encode(withEvidence)
        return access(frozen.scope, admit, false) { db ->
            val current = readStored(db, frozen.scope, frozen.id)
            if (current == null || current.record.copy(protectedEvidence = null) != frozen.copy(protectedEvidence = null) ||
                current.record.state != TextUpdateAttemptState.OUTCOME_UNKNOWN ||
                owners(db, frozen).singleOrNull()?.rowId != current.rowId) false
            else if (current.record.protectedEvidence != null) current.record.protectedEvidence == protectedEvidence
            else {
                check(db.update(TABLE, ContentValues().apply { put("capsule", capsule) },
                    "rowid=?", arrayOf(current.rowId.toString())) == 1)
                true
            }
        }
    }

    private data class Stored(val rowId: Long, val record: TextUpdateAttemptRecord)
    private fun identity(scope: CalendarScope, id: MutationId) = arrayOf(scope.account.value, scope.calendar.value,
        scope.generation.toString(), id.value)

    /** Both ownership keys span generations. Validate conflicting records too; corruption is not absence. */
    private suspend fun owners(db: SQLiteDatabase, record: TextUpdateAttemptRecord): List<Stored> {
        val ids = db.rawQuery("SELECT rowid FROM $TABLE WHERE account=? AND calendar=? AND (event=? OR mutation=?) LIMIT 3",
            arrayOf(record.scope.account.value, record.scope.calendar.value, record.eventId.value, record.id.value)).use { rows ->
            buildList { while (rows.moveToNext()) { require(rows.getType(0) == Cursor.FIELD_TYPE_INTEGER); add(rows.getLong(0)) } }
        }
        require(ids.size <= 2)
        return ids.map { requireNotNull(readStored(db, "rowid=?", arrayOf(it.toString()))) }
    }
    private suspend fun readStored(db: SQLiteDatabase, scope: CalendarScope, id: MutationId): Stored? =
        readStored(db, "account=? AND calendar=? AND generation=? AND mutation=?", identity(scope, id))?.also {
            require(it.record.scope == scope && it.record.id == id)
        }

    /** No full BLOB enters a CursorWindow. Every metadata cell is bounded before materialization. */
    private suspend fun readStored(db: SQLiteDatabase, where: String, arguments: Array<String>): Stored? {
        currentCoroutineContext().ensureActive()
        fun text(column: String) = "CASE WHEN typeof($column)='text' AND length(CAST($column AS BLOB)) BETWEEN 1 AND 4096 " +
            "THEN CAST($column AS BLOB) END"
        val columns = listOf("rowid", text("account"), text("calendar"),
            "CASE WHEN typeof(generation)='integer' AND generation>=0 THEN generation END",
            text("mutation"), text("event"), text("state"),
            "CASE WHEN typeof(capsule)='blob' AND length(capsule) BETWEEN 1 AND ${TextUpdateAttemptStorageCodec.MAX_CAPSULE_BYTES} " +
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
            currentCoroutineContext().ensureActive()
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
        currentCoroutineContext().ensureActive()
        require(bytes.size() == metadata.length)
        val record = TextUpdateAttemptStorageCodec.decode(bytes.toByteArray())
        require(record.scope == metadata.scope && record.id == metadata.id && record.eventId == metadata.event &&
            record.state.name == metadata.state)
        return Stored(metadata.rowId, record)
    }
    private data class Metadata(val rowId: Long, val scope: CalendarScope, val id: MutationId,
        val event: EventId, val state: String, val length: Int)

    private fun reference(scope: CalendarScope, id: MutationId) {
        for (value in listOf(scope.account.value, scope.calendar.value, id.value)) {
            require(value.isNotBlank() && value.length <= LocalTextEditPreparation.MAX_CONTEXT_BYTES)
            require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <=
                LocalTextEditPreparation.MAX_CONTEXT_BYTES)
        }
    }
    private class Denied : RuntimeException()
    private suspend fun <T> access(scope: CalendarScope, admit: (CalendarScope) -> Boolean, denied: T,
        action: suspend (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            val operation = currentCoroutineContext()
            var closed = false
            fun active() {
                operation.ensureActive()
                if (closed) throw Denied()
                val allowed = admit(scope)
                if (!allowed) closed = true
                operation.ensureActive()
                if (!allowed || closed) throw Denied()
            }
            try {
                active()
                val db = writableDatabase
                db.beginTransaction()
                val result = try {
                    operation.ensureActive()
                    val value = action(db)
                    active()
                    db.setTransactionSuccessful()
                    value
                } finally { db.endTransaction() }
                // A cancelled return cannot roll back the already durable UNKNOWN commit.
                operation.ensureActive()
                result
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Denied) { operation.ensureActive(); denied }
              catch (_: Exception) { operation.ensureActive(); throw IllegalStateException("Invalid text update journal") }
              finally { closed = true }
        }
    }
    private companion object {
        const val TABLE = "text_update_attempts"
        const val CHUNK_BYTES = 64 * 1024
    }
    override fun toString() = "TextUpdateAttemptStore(redacted)"
}
