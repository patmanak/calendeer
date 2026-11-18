package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.patmanak.proton.andcalendarbridge.sync.*

/** A second request inside the existing update owner, sharing its connection, transactions and uniqueness constraints. */
internal class GuestDeliveryJournal(
    private val database: AttemptJournalDatabase,
    private val canOwn: (SQLiteDatabase, CalendarScope, EventId, MutationId, MutationId, () -> Unit) -> Boolean,
) {
    suspend fun remember(record: GuestJournalRecord, admit: (CalendarScope) -> Boolean) =
        transaction(record.owner.scope, admit, false) { db, check ->
            val owner = record.owner
            if (record.progress.phase != GuestDeliveryPhase.MAIL_READY || record.protectedProof != null ||
                !canOwn(db, owner.scope, owner.eventId, owner.id, owner.localIntentId, check)) return@transaction false
            val sql = GuestAttemptSql(db, check)
            val previous = sql.read(owner.scope, owner.id)
            val updates = PersonalUpdateAttemptSql(db, check)
            if (previous != null) return@transaction previous.second.same(record) &&
                updates.hasExclusiveOwner(owner.scope, owner.eventId, owner.id, previous.first)
            if (updates.hasOwner(owner.scope, owner.eventId, owner.id)) return@transaction false
            db.insertOrThrow("personal_update_attempts", null, ContentValues().apply {
                put("account", owner.scope.account.value); put("calendar", owner.scope.calendar.value)
                put("generation", owner.scope.generation); put("mutation", owner.id.value); put("event", owner.eventId.value)
                put("state", "PREPARED"); put("guest", 1); put("capsule", record.encode())
            })
            true
        }

    suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): GuestJournalRecord? =
        transaction(scope, admit, null) { db, check -> GuestAttemptSql(db, check).read(scope, id)?.second }

    suspend fun page(query: PersonalUpdateWorkQuery, admit: (CalendarScope) -> Boolean): PersonalUpdateWorkPage? =
        transaction(query.scope, admit, null) { db, check ->
            val refs = readAttemptWorkRefs(db, "personal_update_attempts", query.scope, query.cursor?.afterEvent,
                query.limit, check, guest = true) { id, event, state -> PersonalUpdateWorkRef(id, event, PersonalUpdateAttemptState.valueOf(state)) }
            PersonalUpdateWorkPage.fromOrderedMetadata(query, refs)
        }

    suspend fun claimMail(expected: GuestJournalRecord, admit: (CalendarScope) -> Boolean) =
        advance(expected, expected.advance(expected.progress.claimMail()), true, admit)

    suspend fun mailAccepted(expected: GuestJournalRecord, time: Long, admit: (CalendarScope) -> Boolean) =
        advance(expected, expected.advance(expected.progress.mailAccepted(expected.binding, time)), false, admit)

    suspend fun claimCalendar(expected: GuestJournalRecord, admit: (CalendarScope) -> Boolean) =
        advance(expected, expected.advance(expected.progress.claimCalendar()), true, admit)

    suspend fun calendarVerified(expected: GuestJournalRecord, protectedProof: String, admit: (CalendarScope) -> Boolean) =
        advance(expected, expected.advance(expected.progress.calendarVerified(expected.binding,
            GuestJournalRecord.hash(protectedProof)), protectedProof), false, admit)

    private suspend fun advance(expected: GuestJournalRecord, next: GuestJournalRecord?, claim: Boolean,
        admit: (CalendarScope) -> Boolean): Boolean {
        if (next == null) return false
        val owner = expected.owner
        return transaction(owner.scope, admit, false) { db, check ->
            val current = GuestAttemptSql(db, check).read(owner.scope, owner.id) ?: return@transaction false
            if (!current.second.same(expected)) return@transaction false
            if (claim && (!canOwn(db, owner.scope, owner.eventId, owner.id, owner.localIntentId, check) ||
                !PersonalUpdateAttemptSql(db, check).hasExclusiveOwner(owner.scope, owner.eventId, owner.id, current.first)))
                return@transaction false
            db.update("personal_update_attempts", ContentValues().apply {
                put("state", "OUTCOME_UNKNOWN"); put("capsule", next.encode())
            }, "rowid=? AND guest=1 AND completion IS NULL", arrayOf(current.first.toString())) == 1
        }
    }

    private suspend fun <T> transaction(scope: CalendarScope, admit: (CalendarScope) -> Boolean, denied: T,
        action: (SQLiteDatabase, () -> Unit) -> T): T =
        attemptJournalTransaction(database, scope, admit, denied, "Invalid guest update journal", action)
}

internal class GuestAttemptSql(private val db: SQLiteDatabase, private val check: () -> Unit) {
    private val blobs = BoundedAttemptBlobReader(db, "personal_update_attempts", check)

    fun isGuest(row: Long) = db.rawQuery("SELECT guest FROM personal_update_attempts WHERE rowid=?",
        arrayOf(row.toString())).use {
        require(it.moveToFirst() && it.getType(0) == Cursor.FIELD_TYPE_INTEGER)
        when (it.getLong(0)) { 0L -> false; 1L -> true; else -> error("Invalid update format") }
    }

    fun readRow(row: Long) = requireNotNull(read("rowid=? AND guest=1", arrayOf(row.toString()))).second

    fun read(scope: CalendarScope, id: MutationId) = read(
        "account=? AND calendar=? AND generation=? AND mutation=? AND guest=1",
        arrayOf(scope.account.value, scope.calendar.value, scope.generation.toString(), id.value))

    private fun read(where: String, args: Array<String>): Pair<Long, GuestJournalRecord>? {
        val meta = blobs.metadata(where, args, GuestJournalRecord.MAX_BYTES) ?: return null
        val record = GuestJournalRecord.decode(blobs.capsule(meta.rowId, meta.length))
        require(record.owner.scope == meta.scope && record.owner.id == meta.id && record.owner.eventId == meta.event)
        val expectedState = if (record.progress.phase == GuestDeliveryPhase.MAIL_READY) "PREPARED" else "OUTCOME_UNKNOWN"
        require(meta.state == expectedState && blobs.proof(meta.rowId, "completion") == null && blobs.proof(meta.rowId, "result") == null)
        return meta.rowId to record
    }
}
