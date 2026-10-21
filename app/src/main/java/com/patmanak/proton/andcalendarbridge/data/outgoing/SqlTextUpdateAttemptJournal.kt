// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.data.serialization.TextUpdateAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.TextUpdateProofStorageCodec
import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.*

/** The helper owns its mutex and connection. Neither callback may suspend or re-enter a helper. */
internal typealias TextAttemptDatabase = AttemptJournalDatabase

/** Local persistence, not dispatch authority. Cross-family admission executes in the same transaction. */
internal class SqlTextUpdateAttemptJournal(
    private val database: TextAttemptDatabase,
    private val canOwn: (SQLiteDatabase, TextUpdateAttemptRecord, () -> Unit) -> Boolean = { _, _, _ -> true },
) : TextUpdateRecoveryJournal {
    override val completionProtocol: Int? = 1
    override suspend fun page(query: TextUpdateWorkQuery, admit: (CalendarScope) -> Boolean): TextUpdateWorkPage? =
        access(query.scope, admit, null) { db, check ->
            val rows = readAttemptWorkRefs(db, "text_update_attempts", query.scope, query.cursor?.afterEvent, query.limit, check) { id, event, state ->
                TextUpdateWorkRef(id, event, TextUpdateAttemptState.valueOf(state))
            }
            TextUpdateWorkPage.fromOrderedMetadata(query, rows)
        }
    override suspend fun retainVerified(result: TextUpdateVerifiedResult, admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = result.snapshot(); val capsule = TextUpdateProofStorageCodec.encodeResult(frozen)
        return access(frozen.record.scope, admit, false) { db, check -> TextUpdateAttemptSql(db, check).retainVerified(frozen, capsule) }
    }
    override suspend fun readVerified(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TextUpdateVerifiedResult? {
        currentCoroutineContext().ensureActive(); reference(scope, id)
        return access(scope, admit, null) { db, check -> TextUpdateAttemptSql(db, check).readVerified(scope, id) }
    }
    override suspend fun readCompletion(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TextUpdateCompletion? {
        currentCoroutineContext().ensureActive(); reference(scope, id)
        return access(scope, admit, null) { db, check -> TextUpdateAttemptSql(db, check).readCompletion(scope, id) }
    }
    override suspend fun retainCompletion(completion: TextUpdateCompletion, admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = completion.snapshot(); val capsule = TextUpdateProofStorageCodec.encodeCompletion(frozen)
        return access(frozen.result.record.scope, admit, false) { db, check ->
            TextUpdateAttemptSql(db, check).retainCompletion(frozen, capsule) { canOwn(db, frozen.result.record, check) }
        }
    }
    override suspend fun remember(record: TextUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        if (frozen.state != TextUpdateAttemptState.PREPARED || frozen.protectedEvidence != null) return false
        val capsule = TextUpdateAttemptStorageCodec.encode(frozen)
        return access(frozen.scope, admit, false) { db, check ->
            canOwn(db, frozen, check) && TextUpdateAttemptSql(db, check).remember(frozen, capsule)
        }
    }
    override suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TextUpdateAttemptRecord? {
        currentCoroutineContext().ensureActive()
        reference(scope, id)
        return access(scope, admit, null) { db, check -> TextUpdateAttemptSql(db, check).read(scope, id) }
    }
    override suspend fun claim(expected: TextUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = expected.frozen()
        if (frozen.state != TextUpdateAttemptState.PREPARED || frozen.protectedEvidence != null) return false
        val capsule = TextUpdateAttemptStorageCodec.encode(frozen.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN))
        return access(frozen.scope, admit, false) { db, check ->
            canOwn(db, frozen, check) && TextUpdateAttemptSql(db, check).claim(frozen, capsule)
        }
    }
    override suspend fun retainEvidence(expectedUnknown: TextUpdateAttemptRecord, protectedEvidence: String,
        admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = expectedUnknown.frozen()
        if (frozen.state != TextUpdateAttemptState.OUTCOME_UNKNOWN ||
            (frozen.protectedEvidence != null && frozen.protectedEvidence != protectedEvidence)) return false
        val capsule = TextUpdateAttemptStorageCodec.encode(frozen.copy(protectedEvidence = protectedEvidence))
        // Keeping factual evidence does not acquire ownership or grant another claim.
        return access(frozen.scope, admit, false) { db, check ->
            TextUpdateAttemptSql(db, check).retainEvidence(frozen, protectedEvidence, capsule)
        }
    }

    private fun reference(scope: CalendarScope, id: MutationId) {
        for (value in listOf(scope.account.value, scope.calendar.value, id.value)) {
            require(value.isNotBlank() && value.length <= LocalTextEditPreparation.MAX_CONTEXT_BYTES)
            require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <=
                LocalTextEditPreparation.MAX_CONTEXT_BYTES)
        }
    }
    private suspend fun <T> access(scope: CalendarScope, admit: (CalendarScope) -> Boolean, denied: T,
        action: (SQLiteDatabase, () -> Unit) -> T): T =
        attemptJournalTransaction(database, scope, admit, denied, "Invalid text update journal", action)
    override fun toString() = "SqlTextUpdateAttemptJournal(redacted)"
}
