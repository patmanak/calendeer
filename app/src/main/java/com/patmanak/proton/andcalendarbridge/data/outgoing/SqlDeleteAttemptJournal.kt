// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.data.serialization.DeleteAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.DeleteProofStorageCodec
import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.*

/** The helper owns its mutex and connection. Neither callback may suspend or re-enter a helper. */
internal typealias DeleteAttemptDatabase = AttemptJournalDatabase

/** Local persistence, not dispatch authority. Cross-family admission executes in the same transaction. */
internal class SqlDeleteAttemptJournal(
    private val database: DeleteAttemptDatabase,
    private val canOwn: (SQLiteDatabase, DeleteAttemptRecord, () -> Unit) -> Boolean = { _, _, _ -> true },
) : DeleteRecoveryJournal {
    override val completionProtocol: Int? = 1
    override suspend fun page(query: DeleteWorkQuery, admit: (CalendarScope) -> Boolean): DeleteWorkPage? =
        access(query.scope, admit, null) { db, check ->
            val rows = readAttemptWorkRefs(db, "delete_attempts", query.scope, query.cursor?.afterEvent, query.limit, check) { id, event, state ->
                DeleteWorkRef(id, event, DeleteAttemptState.valueOf(state))
            }
            DeleteWorkPage.fromOrderedMetadata(query, rows)
        }
    override suspend fun retainVerified(result: DeleteVerifiedResult, admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = result.snapshot(); val capsule = DeleteProofStorageCodec.encodeResult(frozen)
        return access(frozen.record.scope, admit, false) { db, check -> DeleteAttemptSql(db, check).retainVerified(frozen, capsule) }
    }
    override suspend fun readVerified(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): DeleteVerifiedResult? {
        currentCoroutineContext().ensureActive(); reference(scope, id)
        return access(scope, admit, null) { db, check -> DeleteAttemptSql(db, check).readVerified(scope, id) }
    }
    override suspend fun readCompletion(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): DeleteCompletion? {
        currentCoroutineContext().ensureActive(); reference(scope, id)
        return access(scope, admit, null) { db, check -> DeleteAttemptSql(db, check).readCompletion(scope, id) }
    }
    override suspend fun retainCompletion(completion: DeleteCompletion, admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = completion.snapshot(); val capsule = DeleteProofStorageCodec.encodeCompletion(frozen)
        return access(frozen.result.record.scope, admit, false) { db, check ->
            DeleteAttemptSql(db, check).retainCompletion(frozen, capsule) { canOwn(db, frozen.result.record, check) }
        }
    }
    override suspend fun remember(record: DeleteAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        if (frozen.state != DeleteAttemptState.PREPARED || frozen.protectedEvidence != null) return false
        val capsule = DeleteAttemptStorageCodec.encode(frozen)
        return access(frozen.scope, admit, false) { db, check ->
            canOwn(db, frozen, check) && DeleteAttemptSql(db, check).remember(frozen, capsule)
        }
    }
    override suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): DeleteAttemptRecord? {
        currentCoroutineContext().ensureActive()
        reference(scope, id)
        return access(scope, admit, null) { db, check -> DeleteAttemptSql(db, check).read(scope, id) }
    }
    override suspend fun claim(expected: DeleteAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = expected.frozen()
        if (frozen.state != DeleteAttemptState.PREPARED || frozen.protectedEvidence != null) return false
        val capsule = DeleteAttemptStorageCodec.encode(frozen.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN))
        return access(frozen.scope, admit, false) { db, check ->
            canOwn(db, frozen, check) && DeleteAttemptSql(db, check).claim(frozen, capsule)
        }
    }
    override suspend fun retainEvidence(expectedUnknown: DeleteAttemptRecord, protectedEvidence: String,
        admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = expectedUnknown.frozen()
        if (frozen.state != DeleteAttemptState.OUTCOME_UNKNOWN ||
            (frozen.protectedEvidence != null && frozen.protectedEvidence != protectedEvidence)) return false
        val capsule = DeleteAttemptStorageCodec.encode(frozen.copy(protectedEvidence = protectedEvidence))
        // Keeping factual evidence does not acquire ownership or grant another claim.
        return access(frozen.scope, admit, false) { db, check ->
            DeleteAttemptSql(db, check).retainEvidence(frozen, protectedEvidence, capsule)
        }
    }

    private fun reference(scope: CalendarScope, id: MutationId) {
        for (value in listOf(scope.account.value, scope.calendar.value, id.value)) {
            require(value.isNotBlank() && value.length <= 4096)
            require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <=
                4096)
        }
    }
    private suspend fun <T> access(scope: CalendarScope, admit: (CalendarScope) -> Boolean, denied: T,
        action: (SQLiteDatabase, () -> Unit) -> T): T =
        attemptJournalTransaction(database, scope, admit, denied, "Invalid delete journal", action)
    override fun toString() = "SqlDeleteAttemptJournal(redacted)"
}
