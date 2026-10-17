// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.junit.Assert.assertTrue

/** Shared bounded, read-only admission audit for controlled production worker fixtures.
 * Existing CREATE histories must be closed and attached; pending work is never resumed.
 */
internal object SystemWorkerFixtureAudit {
    suspend fun assertQuiescent(app: Context, projection: AndroidCalendarProjection, scope: CalendarScope,
        allowPendingIncoming: Boolean = false) {
        assertLocalEditsQuiescent(app, scope, allowPendingIncoming)
        assertCreatesQuiescent(app, scope)
        assertUpdateHistoryQuiescent(app, scope)
        val account = projection.accountFor(scope.account)
        fun uri(value: android.net.Uri) = value.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
        val calendarKey = digest("calendar-v1", scope.account.value, scope.calendar.value, scope.generation.toString())
        app.contentResolver.query(uri(CalendarContract.Calendars.CONTENT_URI), arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=? AND ${CalendarContract.Calendars._SYNC_ID}=?",
            arrayOf(account.name, account.type, calendarKey), null)!!.use { calendars ->
            assertTrue("The production native calendar must be unique", calendars.count <= 1)
            if (calendars.moveToFirst()) app.contentResolver.query(uri(Events.CONTENT_URI), arrayOf(Events._ID),
                "${Events.CALENDAR_ID}=? AND (${Events.DIRTY} IS NULL OR ${Events.DIRTY}!=0 OR ${Events.DELETED} IS NULL OR " +
                    "${Events.DELETED}!=0 OR ${Events._SYNC_ID} IS NULL OR ${Events._SYNC_ID}='' OR ${Events.SYNC_DATA4} IS NULL)",
                arrayOf(calendars.getLong(0).toString()), null)!!.use { rows ->
                assertTrue("The calendar contains another new, dirty, deleted or unowned native row", !rows.moveToFirst())
            }

        }
    }

    /** Recheck every family before general activation; exempt only the enrolled DELETE owner. */
    suspend fun assertDeleteActivationQuiescent(app: Context, scope: CalendarScope, event: EventId) {
        // Incoming synchronization already has read-only consent. Outgoing proofs do not.
        assertLocalEditsQuiescent(app, scope, allowPendingIncoming = true, enrolledDelete = event)
        assertCreatesQuiescent(app, scope)
        assertUpdateHistoryQuiescent(app, scope)
    }

    private fun assertLocalEditsQuiescent(app: Context, scope: CalendarScope,
        allowPendingIncoming: Boolean, enrolledDelete: EventId? = null) {
        val scopeArgs = arrayOf(scope.account.value, scope.calendar.value)
        fun empty(db: SQLiteDatabase, sql: String, args: Array<String> = scopeArgs, message: String) {
            db.rawQuery(sql, args).use { assertTrue(message, !it.moveToFirst()) }
        }
        ImportStore(app).use { store ->
            val inCalendar = "scope IN (SELECT scope FROM calendars WHERE account=? AND calendar=?)"
            for (table in listOf("local_edits", "local_edit_preparations", "local_text_edit_preparations", "local_time_edit_preparations")) {
                val exempt = table == "local_edits" && enrolledDelete != null
                val exclusion = if (exempt) " AND NOT (kind='DELETE' AND event=? AND scope IN " +
                    "(SELECT scope FROM calendars WHERE account=? AND calendar=? AND generation=?))" else ""
                val args = if (exempt) scopeArgs + arrayOf(enrolledDelete!!.value) + scopeArgs +
                    arrayOf(scope.generation.toString()) else scopeArgs
                empty(store.readableDatabase, "SELECT 1 FROM $table WHERE $inCalendar$exclusion LIMIT 1", args,
                    message = "The calendar retains an unrelated local owner or capsule")
            }
            for (table in listOf("text_update_acknowledgements", "time_update_acknowledgements")) {
                empty(store.readableDatabase, "SELECT 1 FROM $table WHERE $inCalendar AND state!='RESTORED' LIMIT 1",
                    message = "The calendar retains an unfinished acknowledgement")
            }
            val deletionExclusion = if (enrolledDelete == null) "" else
                " AND NOT (event=? AND scope IN (SELECT scope FROM calendars WHERE account=? AND calendar=? AND generation=?))"
            val deletionArgs = if (enrolledDelete == null) scopeArgs else
                scopeArgs + arrayOf(enrolledDelete.value) + scopeArgs + arrayOf(scope.generation.toString())
            empty(store.readableDatabase,
                "SELECT 1 FROM delete_acknowledgements WHERE $inCalendar AND state!='RESTORED'$deletionExclusion LIMIT 1",
                deletionArgs, "The calendar retains an unrelated unfinished deletion acknowledgement")
            if (!allowPendingIncoming) {
                empty(store.readableDatabase, "SELECT 1 FROM projection_receipts WHERE $inCalendar LIMIT 1",
                    message = "The calendar retains an unfinished projection receipt")
                empty(store.readableDatabase, "SELECT 1 FROM records WHERE $inCalendar AND pending!=0 LIMIT 1",
                    message = "The calendar retains a pending incoming projection")
            }
            empty(store.readableDatabase, "SELECT 1 FROM title_update_acknowledgements WHERE $inCalendar AND state!='RESTORED' LIMIT 1",
                message = "The calendar retains an unfinished title acknowledgement")
            empty(store.readableDatabase, "SELECT 1 FROM captured_create_handoffs WHERE $inCalendar AND (state!='RELEASED' OR waiting!=0) LIMIT 1",
                message = "The calendar retains an unfinished CREATE handoff")
        }
    }

    private suspend fun assertUpdateHistoryQuiescent(app: Context, scope: CalendarScope) {
        val scopeArgs = arrayOf(scope.account.value, scope.calendar.value)
        TitleUpdateAttemptStore(app).use { store ->
            for (row in metadata(store.readableDatabase, "text_update_attempts", listOf("generation", "mutation", "event"), scopeArgs)) {
                val retainedScope = scope.copy(generation = row[0].toLong())
                val id = MutationId(row[1])
                val journal = store.textJournal
                val record = journal.read(retainedScope, id) { it == retainedScope }
                val result = journal.readVerified(retainedScope, id) { it == retainedScope }
                val completion = journal.readCompletion(retainedScope, id) { it == retainedScope }
                assertTrue("The calendar retains unfinished or invalid text work", record != null && result != null &&
                    completion != null && record.state == TextUpdateAttemptState.OUTCOME_UNKNOWN &&
                    record.scope == retainedScope && record.id == id && record.eventId.value == row[2] &&
                    result.record == record.copy(protectedEvidence = null) && completion.result == result &&
                    completion.receipt.matches(result))
            }
            for (row in metadata(store.readableDatabase, "time_update_attempts", listOf("generation", "mutation", "event"), scopeArgs)) {
                val retainedScope = scope.copy(generation = row[0].toLong())
                val id = MutationId(row[1]); val journal = store.timeJournal
                val record = journal.read(retainedScope, id) { it == retainedScope }
                val result = journal.readVerified(retainedScope, id) { it == retainedScope }
                val completion = journal.readCompletion(retainedScope, id) { it == retainedScope }
                assertTrue("The calendar retains unfinished or invalid time work", record != null && result != null && completion != null &&
                    record.state == TimeUpdateAttemptState.OUTCOME_UNKNOWN && record.protectedEvidence != null && record.scope == retainedScope &&
                    record.id == id && record.eventId.value == row[2] && result.record == record.copy(protectedEvidence = null) &&
                    completion.result == result && completion.receipt.matches(result))
            }
            val closed = metadata(store.readableDatabase, "title_update_attempts", listOf("generation", "mutation", "event"), scopeArgs)
            for (row in closed) {
                val retainedScope = scope.copy(generation = row[0].toLong())
                val completion = store.readCompletion(retainedScope, MutationId(row[1]))
                assertTrue("The calendar retains an unfinished or invalid title attempt", completion != null &&
                        completion.result.record.scope == retainedScope && completion.result.record.id.value == row[1] &&
                        completion.result.event.eventId.value == row[2])
            }
            for (row in metadata(store.readableDatabase, "title_update_preparation_reservations", listOf("generation", "mutation", "event"), scopeArgs)) {
                val reservation = store.readPreparationReservation(scope.copy(generation = row[0].toLong()), EventId(row[2]))
                assertTrue("The calendar retains a bare or mismatched title reservation", row in closed && reservation == null)
            }
        }
    }

    /** General editing also enables CREATE; reject any unrelated resumable creation. */
    suspend fun assertCreatesQuiescent(app: Context, scope: CalendarScope) {
        val scopeArgs = arrayOf(scope.account.value, scope.calendar.value)
        fun empty(db: SQLiteDatabase, sql: String, args: Array<String> = scopeArgs, message: String) {
            db.rawQuery(sql, args).use { assertTrue(message, !it.moveToFirst()) }
        }
        val finishedCreates = SystemCreateWorkStore(app).use { store ->
            empty(store.readableDatabase, "SELECT 1 FROM system_creates WHERE account=? AND calendar=? AND status!='COMPLETE' LIMIT 1",
                message = "The calendar retains unfinished system CREATE work")
            metadata(store.readableDatabase, "system_creates", listOf("generation", "attempt", "local_id", "capture_id"), scopeArgs)
        }
        LocalCreateStore(app).use { store ->
            for (row in metadata(store.readableDatabase, "local_creates", listOf("generation", "local_event", "mutation"), scopeArgs)) {
                assertTrue("A retained CREATE capture lacks closed production work", finishedCreates.count {
                    it[0] == row[0] && it[2] == row[1] && it[3] == row[2]
                } == 1)
            }
            for (row in metadata(store.readableDatabase, "local_create_submissions", listOf("generation", "local_event", "attempt"), scopeArgs)) {
                assertTrue("A retained CREATE reservation lacks closed production work", finishedCreates.count {
                    it[0] == row[0] && it[2] == row[1] && it[1] == row[2]
                } == 1)
            }
        }
        CreateAttemptStore(app).use { store ->
            for (row in metadata(store.readableDatabase, "create_attempts", listOf("generation", "mutation", "state"), scopeArgs)) {
                val completed = finishedCreates.singleOrNull { it[0] == row[0] && it[1] == row[1] }
                assertTrue("An existing CREATE attempt is not closed production history", row[2] == "OUTCOME_UNKNOWN" && completed != null)
                val work = checkNotNull(completed)
                store.readableDatabase.rawQuery("SELECT 1 FROM captured_create_attachments WHERE account=? AND calendar=? AND generation=? " +
                    "AND mutation=? AND local_id=? AND capture_id=? LIMIT 2",
                    scopeArgs + arrayOf(row[0], row[1], work[2], work[3])).use {
                    assertTrue("Closed CREATE work must retain its original attachment", it.count == 1)
                }
            }
        }
    }

    /** Only bounded metadata is read; no pending event/payload is decoded or resumed. */
    private fun metadata(db: SQLiteDatabase, table: String, columns: List<String>, args: Array<String>): List<List<String>> {
        val selected = columns.joinToString(",") {
            val type = if (it == "generation") "integer" else "text"
            "CASE WHEN typeof($it)='$type' AND length(CAST($it AS BLOB)) BETWEEN 1 AND 4096 THEN CAST($it AS TEXT) END"
        }
        return db.rawQuery("SELECT $selected FROM $table WHERE account=? AND calendar=? LIMIT 513", args).use { cursor ->
            assertTrue("The enrollment metadata exceeds its bounded audit", cursor.count <= 512)
            buildList { while (cursor.moveToNext()) add(columns.indices.map {
                assertTrue("The enrollment metadata is incomplete", !cursor.isNull(it)); cursor.getString(it)
            }) }
        }
    }

    private fun digest(vararg values: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

}
