// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Provider permission metadata qualification, separate from a real client's editing journey. */
class PersonalEventEditingDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val projection = AndroidCalendarProjection(context)

    @Test fun knownEmptyPersonalEventRemainsEditableAfterIncomingUpdate() = fixture { scope, calendar ->
        val base = simple()
        projection.apply(ProjectionIntent(scope, base))
        val row = row(calendar)
        assertPersonal(row, scope)
        val next = base.copy(revision = Revision("second"), content = base.content.copy(title = "Incoming title"))
        AndroidCalendarProjection(context).apply(ProjectionIntent(scope, next))
        assertEquals(row, row(calendar)); assertPersonal(row, scope)
        assertEquals("Incoming title", field(row, Events.TITLE))
        assertNotNull(projection.inspectOwnedEvent(scope, next.eventId, next.revision))
        dirty(scope, row)
        assertEquals("Local title", projection.captureOwnedSimpleEdit(scope, next)?.content?.title)
        assertEquals("1", field(row, Events.DIRTY))
    }

    @Test fun personalAllDayEventCanBeCapturedWithoutInventingParticipants() = fixture { scope, calendar ->
        val base = simple().copy(content = simple().content.copy(time = EventTime.AllDay(
            LocalDate.parse("2030-05-02"), LocalDate.parse("2030-05-03"))))
        projection.apply(ProjectionIntent(scope, base)); val row = row(calendar); assertPersonal(row, scope)
        dirty(scope, row)
        assertEquals(base.content.copy(title = "Local title"), projection.captureOwnedSimpleEdit(scope, base)?.content)
    }

    @Test fun incomingProjectionCannotEraseADirtyPersonalEdit() = fixture { scope, calendar ->
        val base = simple(); projection.apply(ProjectionIntent(scope, base)); val row = row(calendar)
        dirty(scope, row)
        try {
            projection.apply(ProjectionIntent(scope, base.copy(revision = Revision("new"))))
            fail("Dirty content must not be overwritten")
        } catch (_: CalendarProjectionException) { }
        assertPersonal(row, scope); assertEquals("Local title", field(row, Events.TITLE)); assertEquals("1", field(row, Events.DIRTY))
    }

    @Test fun disablingModeKeepsPersonalEventInAReadOnlyCalendar() = fixture { scope, calendar ->
        projection.apply(ProjectionIntent(scope, simple())); val row = row(calendar)
        projection.makeCalendarReadOnly(scope)
        assertPersonal(row, scope)
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL), "_id=?", arrayOf(calendar.toString()), null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(CalendarContract.Calendars.CAL_ACCESS_READ, it.getInt(0))
        }
    }

    @Test fun unknownDetailsCannotAcquirePersonalEditingPrivileges() = fixture { scope, calendar ->
        val base = simple().copy(content = simple().content.copy(details = null))
        projection.apply(ProjectionIntent(scope, base)); val row = row(calendar)
        assertEquals("0", field(row, Events.IS_ORGANIZER)); assertEquals("0", field(row, Events.GUESTS_CAN_MODIFY))
        mutate(scope, row, ContentValues().apply {
            put(Events.ORGANIZER, projection.accountFor(scope.account).name); put(Events.IS_ORGANIZER, 1)
        }); dirty(scope, row)
        assertNull(projection.captureOwnedSimpleEdit(scope, base))
    }

    @Test fun invitationKeepsItsExternalOrganizerAndCannotAcquirePersonalPrivileges() = fixture { scope, calendar ->
        val details = EventDetails(organizer = EventPerson("external@example.test", "External"))
        projection.apply(ProjectionIntent(scope, simple().copy(content = simple().content.copy(details = details))))
        val row = row(calendar)
        assertEquals("external@example.test", field(row, Events.ORGANIZER))
        assertEquals("0", field(row, Events.IS_ORGANIZER)); assertEquals("0", field(row, Events.GUESTS_CAN_MODIFY))
        assertEquals(1, childCount(CalendarContract.Attendees.CONTENT_URI, CalendarContract.Attendees.EVENT_ID, row))
    }

    @Test fun personalPrivilegeDoesNotAdmitUnexpectedGuestFlagsOrChildren() = fixture { scope, calendar ->
        val base = simple(); projection.apply(ProjectionIntent(scope, base)); val row = row(calendar); dirty(scope, row)
        mutate(scope, row, ContentValues().apply { put(Events.GUESTS_CAN_INVITE_OTHERS, 1) })
        assertNull(projection.captureOwnedSimpleEdit(scope, base))
        mutate(scope, row, ContentValues().apply { put(Events.GUESTS_CAN_INVITE_OTHERS, 0) })
        context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, row); put(CalendarContract.Reminders.MINUTES, 10)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
        assertNull(projection.captureOwnedSimpleEdit(scope, base)); assertEquals("1", field(row, Events.DIRTY))
    }

    @Test fun previousZeroFlagPersonalRowsRemainCapturable() = fixture { scope, calendar ->
        val base = simple(); projection.apply(ProjectionIntent(scope, base)); val row = row(calendar)
        mutate(scope, row, ContentValues().apply { putNull(Events.ORGANIZER); put(Events.IS_ORGANIZER, 0) }); dirty(scope, row)
        assertNotNull(projection.captureOwnedSimpleEdit(scope, base))
        assertEquals("0", field(row, Events.IS_ORGANIZER))
    }

    @Test fun anotherOpaqueOwnerCannotBeCapturedAsAPersonalEvent() = fixture { scope, calendar ->
        val base = simple(); projection.apply(ProjectionIntent(scope, base)); val row = row(calendar); dirty(scope, row)
        mutate(scope, row, ContentValues().apply {
            put(Events.ORGANIZER, projection.accountFor(AccountId("another-fixture-account")).name)
        })
        assertNull(projection.captureOwnedSimpleEdit(scope, base)); assertEquals("1", field(row, Events.DIRTY))
    }

    @Test fun personalProjectionProfileDoesNotWidenRawCreationAdmission() = fixture { scope, calendar ->
        projection.apply(ProjectionIntent(scope, simple())); val row = row(calendar)
        mutate(scope, row, ContentValues().apply {
            putNull(Events._SYNC_ID)
            listOf(Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA4).forEach { putNull(it) }
            put(Events.DIRTY, 1)
        })
        assertNull(projection.captureAndStampSimpleCreate(scope, row, LocalEventId(UUID.randomUUID().toString())))
        assertEquals("1", field(row, Events.DIRTY)); assertNull(field(row, Events.SYNC_DATA5))
    }

    private fun simple() = RemoteChange.Upsert(EventId("synthetic"), Revision("first"), EventContent(
        "Calendeer QA — personal editing", EventTime.Timed(Instant.parse("2030-05-02T10:00:00Z"),
            Instant.parse("2030-05-02T11:00:00Z"), ZoneId.of("UTC")), details = EventDetails()))

    private fun fixture(block: suspend (CalendarScope, Long) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        assumeTrue(projection.hasPermissions())
        val scope = CalendarScope(AccountId("personal-edit-fixture-${UUID.randomUUID()}"), CalendarId("fixture"), 0)
        try {
            val calendar = projection.ensureCalendarState(scope, CalendarSummary(scope.calendar, "Calendeer QA — personal editing"), true).id
            block(scope, calendar)
        } finally {
            projection.removeOwnedCalendar(scope)
            AccountManager.get(context).removeAccountExplicitly(projection.accountFor(scope.account))
        }
    }
    private fun row(calendar: Long) = context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID),
        "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
        assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getLong(0)
    }
    private fun field(row: Long, column: String): String? = context.contentResolver.query(Events.CONTENT_URI,
        arrayOf(column), "_id=?", arrayOf(row.toString()), null)!!.use {
        assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
    }
    private fun childCount(uri: android.net.Uri, column: String, row: Long) = context.contentResolver.query(uri,
        arrayOf("_id"), "$column=?", arrayOf(row.toString()), null)!!.use { it.count }
    private fun assertPersonal(row: Long, scope: CalendarScope) {
        assertEquals("Personal event must be editable by a client checking modification privileges", "1", field(row, Events.IS_ORGANIZER))
        assertEquals(projection.accountFor(scope.account).name, field(row, Events.ORGANIZER)); assertEquals("0", field(row, Events.HAS_ATTENDEE_DATA))
        assertEquals("0", field(row, Events.GUESTS_CAN_MODIFY)); assertEquals("0", field(row, Events.SELF_ATTENDEE_STATUS))
        assertEquals(0, childCount(CalendarContract.Attendees.CONTENT_URI, CalendarContract.Attendees.EVENT_ID, row))
        assertEquals(0, childCount(CalendarContract.Reminders.CONTENT_URI, CalendarContract.Reminders.EVENT_ID, row))
    }
    private fun dirty(scope: CalendarScope, row: Long) = mutate(scope, row, ContentValues().apply {
        put(Events.TITLE, "Local title"); put(Events.DIRTY, 1)
    })
    private fun mutate(scope: CalendarScope, row: Long, values: ContentValues) {
        val account = projection.accountFor(scope.account)
        val uri = Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
        assertEquals(1, context.contentResolver.update(uri, values, "_id=?", arrayOf(row.toString())))
    }
}
