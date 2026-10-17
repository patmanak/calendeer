// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.data.serialization.TimeUpdateAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.TimeUpdateProofStorageCodec
import android.database.Cursor
import java.nio.ByteBuffer
import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.*

/** The helper owns its mutex and connection. Neither callback may suspend or re-enter a helper. */
internal interface TimeAttemptDatabase {
    suspend fun <T> withDatabase(beforeOpen: () -> Unit, action: (SQLiteDatabase) -> T): T
}

/** Local persistence, not dispatch authority. Cross-family admission executes in the same transaction. */
internal class SqlTimeUpdateAttemptJournal(
    private val database: TimeAttemptDatabase,
    private val canOwn: (SQLiteDatabase, TimeUpdateAttemptRecord, () -> Unit) -> Boolean = { _, _, _ -> true },
) : TimeUpdateRecoveryJournal {
    override val completionProtocol: Int? = 1
    override suspend fun page(query: TimeUpdateWorkQuery, admit: (CalendarScope) -> Boolean): TimeUpdateWorkPage? =
        access(query.scope, admit, null) { db, check ->
            fun text(column: String, max: Int) = "CASE WHEN typeof($column)='text' AND length(CAST($column AS BLOB)) BETWEEN 1 AND $max THEN CAST($column AS BLOB) END"
            val after = if (query.cursor == null) "" else " AND event COLLATE BINARY > ?"
            val args = buildList {
                add(query.scope.account.value); add(query.scope.calendar.value); add(query.scope.generation.toString())
                query.cursor?.let { add(it.afterEvent.value) }; add((query.limit + 1).toString())
            }.toTypedArray()
            val rows = db.rawQuery("SELECT ${text("mutation", 4096)},${text("event", 4096)},${text("state", 15)} " +
                "FROM time_update_attempts WHERE account=? AND calendar=? AND generation=? AND completion IS NULL$after ORDER BY event COLLATE BINARY LIMIT ?", args).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        check(); require((0..2).all { cursor.getType(it) == Cursor.FIELD_TYPE_BLOB })
                        fun strict(column: Int) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(cursor.getBlob(column))).toString()
                        add(TimeUpdateWorkRef(MutationId(strict(0)), EventId(strict(1)), TimeUpdateAttemptState.valueOf(strict(2))))
                    }
                }
            }
            TimeUpdateWorkPage.fromOrderedMetadata(query, rows)
        }
    override suspend fun retainVerified(result: TimeUpdateVerifiedResult, admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = result.snapshot(); val capsule = TimeUpdateProofStorageCodec.encodeResult(frozen)
        return access(frozen.record.scope, admit, false) { db, check -> TimeUpdateAttemptSql(db, check).retainVerified(frozen, capsule) }
    }
    override suspend fun readVerified(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TimeUpdateVerifiedResult? {
        currentCoroutineContext().ensureActive(); reference(scope, id)
        return access(scope, admit, null) { db, check -> TimeUpdateAttemptSql(db, check).readVerified(scope, id) }
    }
    override suspend fun readCompletion(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TimeUpdateCompletion? {
        currentCoroutineContext().ensureActive(); reference(scope, id)
        return access(scope, admit, null) { db, check -> TimeUpdateAttemptSql(db, check).readCompletion(scope, id) }
    }
    override suspend fun retainCompletion(completion: TimeUpdateCompletion, admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = completion.snapshot(); val capsule = TimeUpdateProofStorageCodec.encodeCompletion(frozen)
        return access(frozen.result.record.scope, admit, false) { db, check ->
            TimeUpdateAttemptSql(db, check).retainCompletion(frozen, capsule) { canOwn(db, frozen.result.record, check) }
        }
    }
    override suspend fun remember(record: TimeUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        if (frozen.state != TimeUpdateAttemptState.PREPARED || frozen.protectedEvidence != null) return false
        val capsule = TimeUpdateAttemptStorageCodec.encode(frozen)
        return access(frozen.scope, admit, false) { db, check ->
            canOwn(db, frozen, check) && TimeUpdateAttemptSql(db, check).remember(frozen, capsule)
        }
    }
    override suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TimeUpdateAttemptRecord? {
        currentCoroutineContext().ensureActive()
        reference(scope, id)
        return access(scope, admit, null) { db, check -> TimeUpdateAttemptSql(db, check).read(scope, id) }
    }
    override suspend fun claim(expected: TimeUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = expected.frozen()
        if (frozen.state != TimeUpdateAttemptState.PREPARED || frozen.protectedEvidence != null) return false
        val capsule = TimeUpdateAttemptStorageCodec.encode(frozen.copy(state = TimeUpdateAttemptState.OUTCOME_UNKNOWN))
        return access(frozen.scope, admit, false) { db, check ->
            canOwn(db, frozen, check) && TimeUpdateAttemptSql(db, check).claim(frozen, capsule)
        }
    }
    override suspend fun retainEvidence(expectedUnknown: TimeUpdateAttemptRecord, protectedEvidence: String,
        admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = expectedUnknown.frozen()
        if (frozen.state != TimeUpdateAttemptState.OUTCOME_UNKNOWN ||
            (frozen.protectedEvidence != null && frozen.protectedEvidence != protectedEvidence)) return false
        val capsule = TimeUpdateAttemptStorageCodec.encode(frozen.copy(protectedEvidence = protectedEvidence))
        // Keeping factual evidence does not acquire ownership or grant another claim.
        return access(frozen.scope, admit, false) { db, check ->
            TimeUpdateAttemptSql(db, check).retainEvidence(frozen, protectedEvidence, capsule)
        }
    }

    private fun reference(scope: CalendarScope, id: MutationId) {
        for (value in listOf(scope.account.value, scope.calendar.value, id.value)) {
            require(value.isNotBlank() && value.length <= LocalTimeEditPreparation.MAX_CONTEXT_BYTES)
            require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <=
                LocalTimeEditPreparation.MAX_CONTEXT_BYTES)
        }
    }
    private class Denied : RuntimeException()
    private suspend fun <T> access(scope: CalendarScope, admit: (CalendarScope) -> Boolean, denied: T,
        action: (SQLiteDatabase, () -> Unit) -> T): T = withContext(Dispatchers.IO) {
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
            database.withDatabase(::active) { db ->
                db.beginTransaction()
                val result = try {
                    operation.ensureActive()
                    val value = action(db) { operation.ensureActive() }
                    active()
                    db.setTransactionSuccessful()
                    value
                } finally { db.endTransaction() }
                // A cancelled return cannot roll back the already durable UNKNOWN commit.
                operation.ensureActive()
                result
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Denied) { operation.ensureActive(); denied }
          catch (_: Exception) { operation.ensureActive(); throw IllegalStateException("Invalid text update journal") }
          finally { closed = true }
    }
    override fun toString() = "SqlTimeUpdateAttemptJournal(redacted)"
}
