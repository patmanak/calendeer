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
    private data class ClosedDelete(val intent: LocalDeleteIntent, val row: Long)

    /** Read-only proof of closed histories; never resume or repair retained work. */
    suspend fun closedDeleteRows(app: Context, projection: AndroidCalendarProjection, scope: CalendarScope,
        enrolledDelete: EventId? = null): Set<Long> =
        closedDeletes(app, projection, scope, enrolledDelete).mapTo(mutableSetOf()) { it.row }

    private suspend fun closedDeletes(app: Context, projection: AndroidCalendarProjection, scope: CalendarScope,
        enrolledDelete: EventId? = null): List<ClosedDelete> {
        val closed = mutableListOf<ClosedDelete>()
        TitleUpdateAttemptStore(app).use { shared ->
            ImportStore(app).use { imports ->
                for (row in metadata(shared.readableDatabase, "delete_attempts", listOf("generation", "mutation", "event"),
                    arrayOf(scope.account.value, scope.calendar.value))) {
                    assertTrue("Closed deletion history must belong to the selected generation", row[0].toLong() == scope.generation)
                    val event = EventId(row[2])
                    if (event == enrolledDelete) continue
                    val id = MutationId(row[1]); val journal = shared.deleteJournal
                    val record = checkNotNull(journal.read(scope, id) { it == scope })
                    val result = checkNotNull(journal.readVerified(scope, id) { it == scope })
                    val completion = checkNotNull(journal.readCompletion(scope, id) { it == scope })
                    assertTrue("The calendar retains unfinished or invalid deletion work",
                        record.scope == scope && record.id == id && record.eventId == event &&
                            record.state == DeleteAttemptState.OUTCOME_UNKNOWN && record.protectedEvidence != null &&
                            record.id == DeleteAttemptIdentity.requestId(record.intent, record.format) &&
                            result.record == record.copy(protectedEvidence = null) && completion.result == result &&
                            imports.localDelete(scope, event) == record.intent)
                    val ack = checkNotNull(imports.deleteAcknowledgement(scope, event, id))
                    assertTrue("Closed deletion requires its exact SQL and native acknowledgement",
                        ack.state == DeleteAcknowledgementState.RESTORED && completion == DeleteCompletion.of(result, ack) &&
                            projection.inspectDeleteAcknowledgement(ack.receipt)?.receipt == ack.receipt)
                    closed += ClosedDelete(record.intent, closedDeleteRow(app, projection, record.intent))
                }
            }
        }
        assertTrue("Closed deletion histories must own distinct rows", closed.map { it.row }.distinct().size == closed.size)
        return closed
    }

    private fun closedDeleteRow(app: Context, projection: AndroidCalendarProjection, intent: LocalDeleteIntent): Long {
        val account = projection.accountFor(intent.scope.account)
        fun uri(value: android.net.Uri) = value.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
        val key = checkNotNull(projection.eventIdentityKey(intent.scope, intent.base.eventId))
        val calendarKey = digest("calendar-v1", intent.scope.account.value, intent.scope.calendar.value, intent.scope.generation.toString())
        val calendar = app.contentResolver.query(uri(CalendarContract.Calendars.CONTENT_URI), arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=? AND ${CalendarContract.Calendars._SYNC_ID}=?",
            arrayOf(account.name, account.type, calendarKey), null)!!.use {
            assertTrue("Closed deletion must retain one exact native calendar", it.count == 1 && it.moveToFirst())
            it.getLong(0)
        }
        val unsupported = arrayOf(Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DURATION, Events.EVENT_COLOR,
            Events.EVENT_COLOR_KEY, Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME,
            Events.ORIGINAL_ALL_DAY, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI)
        val emptyFlags = arrayOf(Events.HAS_ATTENDEE_DATA, Events.GUESTS_CAN_MODIFY, Events.GUESTS_CAN_INVITE_OTHERS,
            Events.GUESTS_CAN_SEE_GUESTS, Events.HAS_ALARM, Events.SELF_ATTENDEE_STATUS, Events.ACCESS_LEVEL)
        val columns = arrayOf(Events._ID, Events.DIRTY, Events.DELETED, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION,
            Events.ALL_DAY, Events.DTSTART, Events.DTEND, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE,
            Events.ORGANIZER, Events.IS_ORGANIZER, Events.STATUS, Events.AVAILABILITY) + unsupported + emptyFlags
        val row = app.contentResolver.query(uri(Events.CONTENT_URI), columns,
            "${Events._SYNC_ID}=? AND ${Events.SYNC_DATA4}=? AND ${Events.CALENDAR_ID}=?",
            arrayOf(key, key, calendar.toString()), null)!!.use { rows ->
            assertTrue("Closed deletion must retain one exact native row", rows.count == 1 && rows.moveToFirst())
            fun value(column: String) = rows.getColumnIndexOrThrow(column).let { if (rows.isNull(it)) null else rows.getString(it) }
            val content = intent.content
            val expected = when (val time = content.time) {
                is EventTime.Timed -> listOf("0", time.start.toEpochMilli().toString(), time.end.toEpochMilli().toString(), time.zone.id)
                is EventTime.AllDay -> listOf("1", time.start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli().toString(),
                    time.endExclusive.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli().toString(), "UTC")
            }
            assertTrue("Closed deletion must retain its clean tombstone and exact content",
                value(Events.DIRTY) == "0" && value(Events.DELETED) == "1" &&
                    value(Events.TITLE).orEmpty() == content.title && value(Events.DESCRIPTION).orEmpty() == content.description &&
                    value(Events.EVENT_LOCATION).orEmpty() == content.location &&
                    listOf(value(Events.ALL_DAY), value(Events.DTSTART), value(Events.DTEND), value(Events.EVENT_TIMEZONE)) == expected &&
                    value(Events.EVENT_END_TIMEZONE) == expected[3] && unsupported.all { value(it) == null } &&
                    emptyFlags.all { value(it) == "0" } &&
                    ((value(Events.ORGANIZER) == null && value(Events.IS_ORGANIZER) == "0") ||
                        (value(Events.ORGANIZER) == account.name && value(Events.IS_ORGANIZER) == "1")) &&
                    value(Events.STATUS) == Events.STATUS_CONFIRMED.toString() &&
                    value(Events.AVAILABILITY) == Events.AVAILABILITY_BUSY.toString())
            rows.getLong(rows.getColumnIndexOrThrow(Events._ID))
        }
        for ((table, eventColumn) in listOf(CalendarContract.Reminders.CONTENT_URI to CalendarContract.Reminders.EVENT_ID,
            CalendarContract.Attendees.CONTENT_URI to CalendarContract.Attendees.EVENT_ID,
            CalendarContract.ExtendedProperties.CONTENT_URI to CalendarContract.ExtendedProperties.EVENT_ID)) {
            app.contentResolver.query(uri(table), arrayOf("_id"), "$eventColumn=?", arrayOf(row.toString()), null)!!.use {
                assertTrue("Closed deletion cannot hide newer child rows", it.count == 0)
            }
        }
        return row
    }

    suspend fun assertQuiescent(app: Context, projection: AndroidCalendarProjection, scope: CalendarScope,
        allowPendingIncoming: Boolean = false) {
        val closed = closedDeletes(app, projection, scope)
        assertLocalEditsQuiescent(app, scope, allowPendingIncoming, closedDeletes = closed)
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
            if (calendars.moveToFirst()) app.contentResolver.query(uri(Events.CONTENT_URI), arrayOf(Events._ID, Events.DIRTY, Events.DELETED),
                "${Events.CALENDAR_ID}=? AND (${Events.DIRTY} IS NULL OR ${Events.DIRTY}!=0 OR ${Events.DELETED} IS NULL OR " +
                    "${Events.DELETED}!=0 OR ${Events._SYNC_ID} IS NULL OR ${Events._SYNC_ID}='' OR ${Events.SYNC_DATA4} IS NULL)",
                arrayOf(calendars.getLong(0).toString()), null)!!.use { rows ->
                while (rows.moveToNext()) assertTrue("The calendar contains another new, dirty, deleted or unowned native row",
                    !rows.isNull(1) && rows.getInt(1) == 0 && !rows.isNull(2) && rows.getInt(2) == 1 &&
                        closed.any { it.row == rows.getLong(0) })
            }

        }
    }

    /** Recheck every family before general activation; exempt only the enrolled DELETE owner. */
    suspend fun assertDeleteActivationQuiescent(app: Context, projection: AndroidCalendarProjection,
        scope: CalendarScope, event: EventId) {
        // Incoming synchronization already has read-only consent. Outgoing proofs do not.
        val closed = closedDeletes(app, projection, scope, event)
        assertLocalEditsQuiescent(app, scope, allowPendingIncoming = true, enrolledDelete = event, closedDeletes = closed)
        assertCreatesQuiescent(app, scope)
        assertUpdateHistoryQuiescent(app, scope)
    }

    private fun assertLocalEditsQuiescent(app: Context, scope: CalendarScope,
        allowPendingIncoming: Boolean, enrolledDelete: EventId? = null, closedDeletes: List<ClosedDelete> = emptyList()) {
        val scopeArgs = arrayOf(scope.account.value, scope.calendar.value)
        fun empty(db: SQLiteDatabase, sql: String, args: Array<String> = scopeArgs, message: String) {
            db.rawQuery(sql, args).use { assertTrue(message, !it.moveToFirst()) }
        }
        ImportStore(app).use { store ->
            val inCalendar = "scope IN (SELECT scope FROM calendars WHERE account=? AND calendar=?)"
            for (table in listOf("local_edits", "local_edit_preparations", "local_text_edit_preparations", "local_time_edit_preparations")) {
                if (table == "local_edits") {
                    val selected = listOf("e.kind", "e.event", "e.mutation").joinToString(",") {
                        "CASE WHEN typeof($it)='text' AND length(CAST($it AS BLOB)) BETWEEN 1 AND 4096 THEN $it END"
                    } + ",CASE WHEN typeof(c.generation)='integer' THEN c.generation END"
                    store.readableDatabase.rawQuery("SELECT $selected FROM local_edits e " +
                        "JOIN calendars c ON c.scope=e.scope WHERE c.account=? AND c.calendar=? LIMIT 513", scopeArgs).use { rows ->
                        assertTrue("Bound the retained local owner audit", rows.count <= 512)
                        while (rows.moveToNext()) assertTrue("The calendar retains an unrelated local owner",
                            (0..3).none { rows.isNull(it) } && rows.getString(0) == "DELETE" && rows.getLong(3) == scope.generation &&
                                ((enrolledDelete != null && rows.getString(1) == enrolledDelete.value) || closedDeletes.any {
                                    rows.getString(1) == it.intent.base.eventId.value && rows.getString(2) == it.intent.id.value
                                }))
                    }
                    continue
                }
                empty(store.readableDatabase, "SELECT 1 FROM $table WHERE $inCalendar LIMIT 1",
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
