// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.data.serialization.PersonalUpdateAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.PersonalUpdateProofStorageCodec
import com.patmanak.proton.andcalendarbridge.sync.*

/** Synchronous SQL only, called on the owning writer-transaction thread.
 * Index discovery is never proof: every discovered record is fully decoded and qualified.
 */
internal class PersonalUpdateAttemptSql(private val db: SQLiteDatabase, private val checkCancelled: () -> Unit) {
    private val blobs = BoundedAttemptBlobReader(db, TABLE, checkCancelled)

    /** Local IDs live inside capsules, so fence acquisition qualifies bounded calendar history.
     * Decode every row before deciding: an unrelated hold cannot hide corrupt ownership.
     */
    fun conflictsWithFence(fence: ConflictResolutionFence): Boolean {
        val ids = listOf(fence.localMutationId, fence.resolutionId)
        val conflicts = db.rawQuery("SELECT rowid FROM $TABLE WHERE account=? AND calendar=? LIMIT 129",
            arrayOf(fence.scope.account.value, fence.scope.calendar.value)).use { rows -> buildList {
            while (rows.moveToNext()) {
                checkCancelled(); require(size < 128 && rows.getType(0) == Cursor.FIELD_TYPE_INTEGER)
                val owner = readOwner(rows.getLong(0))
                val collision = owner.id in ids || owner.localId in ids
                add((!owner.completed && owner.event == fence.eventId) || collision)
            }
        } }
        return conflicts.any { it }
    }

    fun hasOwner(scope: CalendarScope, event: EventId, id: MutationId): Boolean =
        owners(scope, event, id).any { !it.completed || it.id == id }
    fun hasExclusiveOwner(scope: CalendarScope, event: EventId, id: MutationId, rowId: Long): Boolean =
        owners(scope, event, id).singleOrNull { !it.completed }?.rowId == rowId
    fun read(scope: CalendarScope, id: MutationId): PersonalUpdateAttemptRecord? = readStored(scope, id)?.record

    fun remember(record: PersonalUpdateAttemptRecord, capsule: ByteArray): Boolean {
        val existing = readStored(record.scope, record.id)
        val owners = owners(record.scope, record.eventId, record.id)
        return if (existing != null) existing.record == record && owners.singleOrNull { !it.completed }?.rowId == existing.rowId
        else if (owners.any { !it.completed || it.id == record.id }) false else {
            db.insertOrThrow(TABLE, null, ContentValues().apply {
                put("account", record.scope.account.value); put("calendar", record.scope.calendar.value)
                put("generation", record.scope.generation); put("mutation", record.id.value)
                put("event", record.eventId.value); put("state", record.state.name); put("capsule", capsule)
            })
            true
        }
    }
    fun claim(expected: PersonalUpdateAttemptRecord, capsule: ByteArray): Boolean {
        val current = readStored(expected.scope, expected.id)
        if (current?.record != expected || owners(expected.scope, expected.eventId, expected.id).singleOrNull { !it.completed }?.rowId != current.rowId)
            return false
        check(db.update(TABLE, ContentValues().apply {
            put("state", PersonalUpdateAttemptState.OUTCOME_UNKNOWN.name); put("capsule", capsule)
        }, "rowid=?", arrayOf(current.rowId.toString())) == 1)
        return true
    }
    fun retainEvidence(expected: PersonalUpdateAttemptRecord, evidence: String, capsule: ByteArray): Boolean {
        val current = readStored(expected.scope, expected.id)
        if (current == null || current.record.copy(protectedEvidence = null) != expected.copy(protectedEvidence = null) ||
            current.record.state != PersonalUpdateAttemptState.OUTCOME_UNKNOWN) return false
        if (current.record.protectedEvidence != null) return current.record.protectedEvidence == evidence
        check(db.update(TABLE, ContentValues().apply { put("capsule", capsule) },
            "rowid=?", arrayOf(current.rowId.toString())) == 1)
        return true
    }

    private data class Stored(val rowId: Long, val record: PersonalUpdateAttemptRecord)
    private data class Owner(val rowId: Long, val id: MutationId, val localId: MutationId, val event: EventId, val completed: Boolean)
    private fun readOwner(rowId: Long): Owner {
        val guest = GuestAttemptSql(db, checkCancelled)
        if (guest.isGuest(rowId)) {
            val owner = guest.readRow(rowId).owner
            return Owner(rowId, owner.id, owner.localIntentId, owner.eventId, false)
        }
        val stored = requireNotNull(readStored("rowid=?", arrayOf(rowId.toString())))
        return Owner(rowId, stored.record.id, stored.record.preparation.intent.id, stored.record.eventId,
            readCompletion(stored) != null)
    }
    private fun identity(scope: CalendarScope, id: MutationId) = arrayOf(scope.account.value, scope.calendar.value,
        scope.generation.toString(), id.value)

    /** Both ownership keys span generations. Validate conflicting records too; corruption is not absence. */
    private fun owners(scope: CalendarScope, event: EventId, id: MutationId): List<Owner> =
        db.rawQuery("SELECT rowid FROM $TABLE WHERE account=? AND calendar=? AND (event=? OR mutation=?)",
            arrayOf(scope.account.value, scope.calendar.value, event.value, id.value)).use { rows ->
            buildList {
                while (rows.moveToNext()) {
                    checkCancelled(); require(rows.getType(0) == Cursor.FIELD_TYPE_INTEGER)
                    add(readOwner(rows.getLong(0)))
                }
            }.also { owners -> require(owners.filter { !it.completed }.groupBy { it.event }.all { it.value.size <= 1 }) }
        }

    fun readVerified(scope: CalendarScope, id: MutationId): PersonalUpdateVerifiedResult? =
        readStored(scope, id)?.let(::readVerified)
    private fun readVerified(stored: Stored): PersonalUpdateVerifiedResult? {
        val bytes = proofBlob(stored.rowId, "result") ?: return null
        val result = PersonalUpdateProofStorageCodec.decodeResult(bytes)
        require(stored.record.state == PersonalUpdateAttemptState.OUTCOME_UNKNOWN &&
            result.record == stored.record.copy(protectedEvidence = null))
        return result
    }
    fun retainVerified(value: PersonalUpdateVerifiedResult, capsule: ByteArray): Boolean {
        val stored = readStored(value.record.scope, value.record.id) ?: return false
        if (stored.record.state != PersonalUpdateAttemptState.OUTCOME_UNKNOWN ||
            stored.record.copy(protectedEvidence = null) != value.record) return false
        val first = readVerified(stored)
        if (first != null) return first == value
        require(proofBlob(stored.rowId, "completion") == null)
        check(db.update(TABLE, ContentValues().apply { put("result", capsule) },
            "rowid=? AND result IS NULL", arrayOf(stored.rowId.toString())) == 1)
        return true
    }
    fun readCompletion(scope: CalendarScope, id: MutationId): PersonalUpdateCompletion? =
        readStored(scope, id)?.let(::readCompletion)
    private fun readCompletion(stored: Stored): PersonalUpdateCompletion? {
        val result = readVerified(stored)
        val bytes = proofBlob(stored.rowId, "completion") ?: return null
        val completion = PersonalUpdateProofStorageCodec.decodeCompletion(bytes)
        require(completion.result == result && completion.result.record == stored.record.copy(protectedEvidence = null))
        return completion
    }
    fun retainCompletion(value: PersonalUpdateCompletion, capsule: ByteArray, admitOwnership: () -> Boolean): Boolean {
        val stored = readStored(value.result.record.scope, value.result.record.id) ?: return false
        if (stored.record.state != PersonalUpdateAttemptState.OUTCOME_UNKNOWN ||
            stored.record.copy(protectedEvidence = null) != value.result.record) return false
        val first = readCompletion(stored)
        if (first != null) return first == value
        if (readVerified(stored) != value.result || !admitOwnership() ||
            owners(stored.record.scope, stored.record.eventId, stored.record.id).singleOrNull { !it.completed }?.rowId != stored.rowId)
            return false
        check(db.update(TABLE, ContentValues().apply { put("completion", capsule) },
            "rowid=? AND completion IS NULL", arrayOf(stored.rowId.toString())) == 1)
        return true
    }
    private fun readStored(scope: CalendarScope, id: MutationId): Stored? =
        readStored("account=? AND calendar=? AND generation=? AND mutation=?", identity(scope, id))?.also {
            require(it.record.scope == scope && it.record.id == id)
        }

    /** No full BLOB enters a CursorWindow. Every metadata cell is bounded before materialization. */
    private fun readStored(where: String, arguments: Array<String>): Stored? {
        val metadata = blobs.metadata("($where) AND guest=0", arguments, PersonalUpdateAttemptStorageCodec.MAX_CAPSULE_BYTES) ?: return null
        val bytes = blobs.capsule(metadata.rowId, metadata.length)
        val record = PersonalUpdateAttemptStorageCodec.decode(bytes)
        require(record.scope == metadata.scope && record.id == metadata.id && record.eventId == metadata.event &&
            record.state.name == metadata.state)
        return Stored(metadata.rowId, record)
    }

    private fun proofBlob(rowId: Long, column: String): ByteArray? = blobs.proof(rowId, column)

    companion object {
        private const val TABLE = "personal_update_attempts"
        fun createTable(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE personal_update_attempts (
                account TEXT NOT NULL, calendar TEXT NOT NULL,
                generation INTEGER NOT NULL CHECK(generation >= 0),
                mutation TEXT NOT NULL, event TEXT NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('PREPARED','OUTCOME_UNKNOWN')),
                guest INTEGER NOT NULL DEFAULT 0 CHECK(guest IN (0,1)),
                capsule BLOB NOT NULL, result BLOB, completion BLOB,
                PRIMARY KEY(account,calendar,generation,mutation),
                UNIQUE(account,calendar,mutation)
            )""".trimIndent())
            db.execSQL("CREATE UNIQUE INDEX personal_update_unresolved_event ON personal_update_attempts(account,calendar,event) WHERE completion IS NULL")
        }
        /** SQLiteOpenHelper owns the upgrade transaction. Copy opaque bytes without interpretation. */
        fun migrateProofs(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE personal_update_attempts RENAME TO personal_update_attempts_legacy")
            createTable(db)
            db.execSQL("INSERT INTO personal_update_attempts(account,calendar,generation,mutation,event,state,capsule,result,completion) " +
                "SELECT account,calendar,generation,mutation,event,state,capsule,NULL,NULL FROM personal_update_attempts_legacy")
            db.execSQL("DROP TABLE personal_update_attempts_legacy")
        }
    }
}
