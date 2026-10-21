// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.data.serialization.TimeUpdateAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.TimeUpdateProofStorageCodec
import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.*

/** The helper owns its mutex and connection. Neither callback may suspend or re-enter a helper. */
internal typealias TimeAttemptDatabase = AttemptJournalDatabase

/** Local persistence, not dispatch authority. Cross-family admission executes in the same transaction. */
internal class SqlTimeUpdateAttemptJournal(
    private val database: TimeAttemptDatabase,
    private val canOwn: (SQLiteDatabase, TimeUpdateAttemptRecord, () -> Unit) -> Boolean = { _, _, _ -> true },
) : TimeUpdateRecoveryJournal {
    override val completionProtocol: Int? = 1
    override suspend fun page(query: TimeUpdateWorkQuery, admit: (CalendarScope) -> Boolean): TimeUpdateWorkPage? =
        access(query.scope, admit, null) { db, check ->
            val rows = readAttemptWorkRefs(db, "time_update_attempts", query.scope, query.cursor?.afterEvent, query.limit, check) { id, event, state ->
                TimeUpdateWorkRef(id, event, TimeUpdateAttemptState.valueOf(state))
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
    private suspend fun <T> access(scope: CalendarScope, admit: (CalendarScope) -> Boolean, denied: T,
        action: (SQLiteDatabase, () -> Unit) -> T): T =
        attemptJournalTransaction(database, scope, admit, denied, "Invalid time update journal", action)
    override fun toString() = "SqlTimeUpdateAttemptJournal(redacted)"
}
