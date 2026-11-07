// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.data.serialization.PersonalUpdateAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.PersonalUpdateProofStorageCodec
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.*

/** The helper owns its mutex and connection. Neither callback may suspend or re-enter a helper. */
internal typealias PersonalAttemptDatabase = AttemptJournalDatabase

/** Local persistence, not dispatch authority. Cross-family admission executes in the same transaction. */
internal class SqlPersonalUpdateAttemptJournal(
    private val database: PersonalAttemptDatabase,
    private val canOwn: (SQLiteDatabase, PersonalUpdateAttemptRecord, () -> Unit) -> Boolean = { _, _, _ -> true },
) : PersonalUpdateRecoveryJournal {
    override val completionProtocol: Int? = 1
    override suspend fun page(query: PersonalUpdateWorkQuery, admit: (CalendarScope) -> Boolean): PersonalUpdateWorkPage? =
        access(query.scope, admit, null) { db, check ->
            val rows = readAttemptWorkRefs(db, "personal_update_attempts", query.scope, query.cursor?.afterEvent, query.limit, check) { id, event, state ->
                PersonalUpdateWorkRef(id, event, PersonalUpdateAttemptState.valueOf(state))
            }
            PersonalUpdateWorkPage.fromOrderedMetadata(query, rows)
        }
    override suspend fun retainVerified(result: PersonalUpdateVerifiedResult, admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = result.snapshot(); val capsule = PersonalUpdateProofStorageCodec.encodeResult(frozen)
        return access(frozen.record.scope, admit, false) { db, check -> PersonalUpdateAttemptSql(db, check).retainVerified(frozen, capsule) }
    }
    override suspend fun readVerified(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): PersonalUpdateVerifiedResult? {
        currentCoroutineContext().ensureActive(); requireAttemptJournalReference(scope, id, PersonalEditPreparation.MAX_CONTEXT_BYTES)
        return access(scope, admit, null) { db, check -> PersonalUpdateAttemptSql(db, check).readVerified(scope, id) }
    }
    override suspend fun readCompletion(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): PersonalUpdateCompletion? {
        currentCoroutineContext().ensureActive(); requireAttemptJournalReference(scope, id, PersonalEditPreparation.MAX_CONTEXT_BYTES)
        return access(scope, admit, null) { db, check -> PersonalUpdateAttemptSql(db, check).readCompletion(scope, id) }
    }
    override suspend fun retainCompletion(completion: PersonalUpdateCompletion, admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = completion.snapshot(); val capsule = PersonalUpdateProofStorageCodec.encodeCompletion(frozen)
        return access(frozen.result.record.scope, admit, false) { db, check ->
            PersonalUpdateAttemptSql(db, check).retainCompletion(frozen, capsule) { canOwn(db, frozen.result.record, check) }
        }
    }
    override suspend fun remember(record: PersonalUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = record.frozen()
        if (frozen.state != PersonalUpdateAttemptState.PREPARED || frozen.protectedEvidence != null) return false
        val capsule = PersonalUpdateAttemptStorageCodec.encode(frozen)
        return access(frozen.scope, admit, false) { db, check ->
            canOwn(db, frozen, check) && PersonalUpdateAttemptSql(db, check).remember(frozen, capsule)
        }
    }
    override suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): PersonalUpdateAttemptRecord? {
        currentCoroutineContext().ensureActive()
        requireAttemptJournalReference(scope, id, PersonalEditPreparation.MAX_CONTEXT_BYTES)
        return access(scope, admit, null) { db, check -> PersonalUpdateAttemptSql(db, check).read(scope, id) }
    }
    override suspend fun claim(expected: PersonalUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = expected.frozen()
        if (frozen.state != PersonalUpdateAttemptState.PREPARED || frozen.protectedEvidence != null) return false
        val capsule = PersonalUpdateAttemptStorageCodec.encode(frozen.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN))
        return access(frozen.scope, admit, false) { db, check ->
            canOwn(db, frozen, check) && PersonalUpdateAttemptSql(db, check).claim(frozen, capsule)
        }
    }
    override suspend fun retainEvidence(expectedUnknown: PersonalUpdateAttemptRecord, protectedEvidence: String,
        admit: (CalendarScope) -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        val frozen = expectedUnknown.frozen()
        if (frozen.state != PersonalUpdateAttemptState.OUTCOME_UNKNOWN ||
            (frozen.protectedEvidence != null && frozen.protectedEvidence != protectedEvidence)) return false
        val capsule = PersonalUpdateAttemptStorageCodec.encode(frozen.copy(protectedEvidence = protectedEvidence))
        // Keeping factual evidence does not acquire ownership or grant another claim.
        return access(frozen.scope, admit, false) { db, check ->
            PersonalUpdateAttemptSql(db, check).retainEvidence(frozen, protectedEvidence, capsule)
        }
    }

    private suspend fun <T> access(scope: CalendarScope, admit: (CalendarScope) -> Boolean, denied: T,
        action: (SQLiteDatabase, () -> Unit) -> T): T =
        attemptJournalTransaction(database, scope, admit, denied, "Invalid personal update journal", action)
    override fun toString() = "SqlPersonalUpdateAttemptJournal(redacted)"
}
