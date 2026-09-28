package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionFailure
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated fixture data only. These assertions establish projection, not delivered notifications. */
@RunWith(AndroidJUnit4::class)
class EventDetailsProviderDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val projection = AndroidCalendarProjection(context)
    private val resolver get() = context.contentResolver
    private val organizer = EventPerson("organizer@example.test", "Organizer")
    private val guest = EventPerson("guest@example.test", "Guest")
    private val rich = EventDetails(
        reminders = listOf(EventReminder(15), EventReminder(0)),
        organizer = organizer,
        attendees = listOf(EventAttendee(organizer, AttendanceRole.REQUIRED, AttendanceStatus.ACCEPTED),
            EventAttendee(guest, AttendanceRole.OPTIONAL, AttendanceStatus.TENTATIVE)),
    )

    @Before fun optIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        assumeTrue(projection.hasPermissions())
    }

    @Test fun legacyEventDoesNotInventSelfAttendeeAndCanBeUpdated() = runBlocking {
        fixture { scope, _, calendar, _ ->
            val event = timed("legacy", null)
            projection.apply(ProjectionIntent(scope, event))
            val before = eventRow(calendar, "legacy")
            assertTrue(attendees(before.id).isEmpty())
            assertEquals(Attendees.ATTENDEE_STATUS_NONE, before.selfStatus)
            projection.apply(ProjectionIntent(scope, event.copy(revision = Revision("v2"),
                content = event.content.copy(description = "Updated fixture"))))
            assertEquals(before, eventRow(calendar, "legacy"))
            assertTrue(attendees(before.id).isEmpty())
        }
    }

    @Test fun newParentBackReferencesAndReplayPreserveIdentityWithoutDuplicateChildren() = runBlocking {
        fixture { scope, _, calendar, _ ->
            val event = timed("details", rich)
            projection.apply(ProjectionIntent(scope, event))
            val first = eventRow(calendar, "details")
            assertEquals(listOf(0, 15), reminders(first.id))
            val people = attendees(first.id)
            assertEquals(2, people.size)
            assertEquals(Attendees.RELATIONSHIP_ORGANIZER, people.single { it.email == organizer.email }.relationship)
            assertEquals(Attendees.TYPE_OPTIONAL, people.single { it.email == guest.email }.role)
            assertEquals(Attendees.ATTENDEE_STATUS_TENTATIVE, people.single { it.email == guest.email }.status)
            assertEquals(organizer.email, first.organizer)
            assertEquals(1, first.hasAlarm)
            assertEquals(1, first.hasAttendees)
            assertEquals(Attendees.ATTENDEE_STATUS_NONE, first.selfStatus)
            assertEquals(0, first.isOrganizer)
            AndroidCalendarProjection(context).apply(ProjectionIntent(scope, event))
            assertEquals(first, eventRow(calendar, "details"))
            assertEquals(listOf(0, 15), reminders(first.id))
            assertEquals(people, attendees(first.id))
        }
    }

    @Test fun replacingAndRemovingDetailsClearsStaleRowsAndMetadata() = runBlocking {
        fixture { scope, _, calendar, _ ->
            val event = timed("replace", rich)
            projection.apply(ProjectionIntent(scope, event))
            val id = eventRow(calendar, "replace").id
            val replacement = EventDetails(listOf(EventReminder(120)), attendees = listOf(
                EventAttendee(guest, AttendanceRole.RESOURCE, AttendanceStatus.DECLINED)))
            projection.apply(ProjectionIntent(scope, event.copy(revision = Revision("v2"),
                content = event.content.copy(details = replacement))))
            assertEquals(id, eventRow(calendar, "replace").id)
            assertNull(eventRow(calendar, "replace").organizer)
            assertEquals(listOf(120), reminders(id))
            assertEquals(listOf(Person(guest.email, guest.name, Attendees.TYPE_RESOURCE,
                Attendees.ATTENDEE_STATUS_DECLINED, Attendees.RELATIONSHIP_ATTENDEE)), attendees(id))
            // Null models the legacy snapshot. Both legacy and an explicit empty snapshot clear rows.
            for (details in listOf(null, EventDetails())) {
                projection.apply(ProjectionIntent(scope, event.copy(revision = Revision("empty"),
                    content = event.content.copy(details = details))))
                val row = eventRow(calendar, "replace")
                assertEquals(id, row.id)
                assertNull(row.organizer)
                assertEquals(0, row.hasAlarm)
                assertEquals(0, row.hasAttendees)
                assertEquals(Attendees.ATTENDEE_STATUS_NONE, row.selfStatus)
                assertTrue(reminders(id).isEmpty())
                assertTrue(attendees(id).isEmpty())
            }
        }
    }

    @Test fun organizerWithoutParticipantRowIsAddedOnceAndAllStatusesRemainDistinct() = runBlocking {
        fixture { scope, _, calendar, _ ->
            val statuses = AttendanceStatus.entries.mapIndexed { index, status ->
                EventAttendee(EventPerson("person$index@example.test"), AttendanceRole.NONE, status)
            }
            val details = EventDetails(organizer = organizer, attendees = statuses)
            projection.apply(ProjectionIntent(scope, timed("roles", details)))
            val people = attendees(eventRow(calendar, "roles").id)
            assertEquals(6, people.size)
            assertEquals(1, people.count { it.email == organizer.email })
            val expected = listOf(Attendees.ATTENDEE_STATUS_INVITED, Attendees.ATTENDEE_STATUS_ACCEPTED,
                Attendees.ATTENDEE_STATUS_DECLINED, Attendees.ATTENDEE_STATUS_TENTATIVE,
                Attendees.ATTENDEE_STATUS_NONE)
            expected.forEachIndexed { index, status ->
                assertEquals(status, people.single { it.email == "person$index@example.test" }.status)
            }
        }
    }

    @Test fun allDaySignedOffsetsAndExistingCalendarCapabilitiesAreRetained() = runBlocking {
        fixture { scope, _, calendar, _ ->
            // Simulate a mirror created by the previous feature version.
            resolver.update(syncUri(Calendars.CONTENT_URI, scope), ContentValues().apply {
                put(Calendars.MAX_REMINDERS, 0)
                put(Calendars.ALLOWED_REMINDERS, "")
            }, "${Calendars._ID}=?", arrayOf(calendar.toString()))
            assertEquals(calendar, projection.ensureCalendar(scope, CalendarSummary(scope.calendar, "Details fixture", 0x167D8D)))
            resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars.MAX_REMINDERS, Calendars.ALLOWED_REMINDERS,
                Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAN_ORGANIZER_RESPOND),
                "${Calendars._ID}=?", arrayOf(calendar.toString()), null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(5, it.getInt(0))
                assertEquals(Reminders.METHOD_ALERT.toString(), it.getString(1))
                assertEquals(Calendars.CAL_ACCESS_READ, it.getInt(2))
                assertEquals(0, it.getInt(3))
            }
            val event = RemoteChange.Upsert(EventId("all-day"), Revision("v1"), EventContent("all-day",
                EventTime.AllDay(LocalDate.parse("2030-03-31"), LocalDate.parse("2030-04-01")),
                details = EventDetails(reminders = listOf(EventReminder(-540), EventReminder(1440)))))
            projection.apply(ProjectionIntent(scope, event))
            val row = eventRow(calendar, "all-day")
            assertEquals(listOf(-540, 1440), reminders(row.id))
            assertEquals(1, row.hasAlarm)
        }
    }

    @Test fun movedAndCancelledExceptionsHaveTheirOwnRemindersAndCascadeIsScoped() = runBlocking {
        fixture { first, second, firstCalendar, secondCalendar ->
            val master = timed("master", rich, EventRecurrence("series", "FREQ=DAILY;COUNT=3"))
            val moved = timed("moved", EventDetails(listOf(EventReminder(30))), EventRecurrence("series",
                original = RecurrencePoint.Timed(Instant.parse("2030-07-02T10:00:00Z"))))
            for (scope in listOf(first, second)) {
                projection.apply(ProjectionIntent(scope, master))
                projection.apply(ProjectionIntent(scope, moved))
            }
            val masterId = eventRow(firstCalendar, "master").id
            val movedId = eventRow(firstCalendar, "moved").id
            assertEquals(listOf(0, 15), reminders(masterId))
            assertEquals(listOf(30), reminders(movedId))
            projection.apply(ProjectionIntent(first, moved.copy(revision = Revision("cancelled"),
                content = moved.content.copy(recurrence = moved.content.recurrence!!.copy(cancelled = true)))))
            assertEquals(movedId, eventRow(firstCalendar, "moved").id)
            assertEquals(0, eventRow(firstCalendar, "moved").hasAlarm)
            assertTrue(reminders(movedId).isEmpty())
            assertEquals(listOf(0, 15), reminders(masterId))
            projection.apply(ProjectionIntent(first, RemoteChange.Deleted(master.eventId, Revision("deleted"))), "series")
            assertTrue(reminders(masterId).isEmpty())
            assertTrue(reminders(movedId).isEmpty())
            assertTrue(attendees(masterId).isEmpty())
            assertEquals(listOf(0, 15), reminders(eventRow(secondCalendar, "master").id))
            assertEquals(listOf(30), reminders(eventRow(secondCalendar, "moved").id))
        }
    }

    @Test fun ownershipConflictPreservesExistingDetailsAndOtherCalendar() = runBlocking {
        fixture { first, second, firstCalendar, secondCalendar ->
            val event = timed("owned", rich)
            projection.apply(ProjectionIntent(first, event))
            projection.apply(ProjectionIntent(second, event))
            val id = eventRow(firstCalendar, "owned").id
            val oldPeople = attendees(id)
            val uri = syncUri(Events.CONTENT_URI, first)
            resolver.update(uri, ContentValues().apply { put(Events.SYNC_DATA1, "foreign-fixture-marker") },
                "${Events._ID}=?", arrayOf(id.toString()))
            try {
                try {
                    projection.apply(ProjectionIntent(first, event.copy(revision = Revision("remove"),
                        content = event.content.copy(details = EventDetails()))))
                    fail("A foreign parent identity was overwritten")
                } catch (failure: CalendarProjectionException) {
                    assertEquals(CalendarProjectionFailure.IDENTITY_CONFLICT, failure.category)
                }
                assertEquals(listOf(0, 15), reminders(id))
                assertEquals(oldPeople, attendees(id))
                assertEquals(listOf(0, 15), reminders(eventRow(secondCalendar, "owned").id))
            } finally {
                resolver.update(uri, ContentValues().apply { put(Events.SYNC_DATA1, "calendar-bridge-projection-v1") },
                    "${Events._ID}=?", arrayOf(id.toString()))
            }
        }
    }

    private fun timed(id: String, details: EventDetails?, recurrence: EventRecurrence? = null) =
        RemoteChange.Upsert(EventId(id), Revision("v1"), EventContent(id, EventTime.Timed(
            Instant.parse("2030-07-01T10:00:00Z"), Instant.parse("2030-07-01T11:00:00Z"), ZoneId.of("UTC")),
            recurrence = recurrence, details = details))

    private data class Row(val id: Long, val organizer: String?, val hasAlarm: Int, val hasAttendees: Int,
        val selfStatus: Int, val isOrganizer: Int)
    private fun eventRow(calendar: Long, title: String) = resolver.query(Events.CONTENT_URI,
        arrayOf(Events._ID, Events.ORGANIZER, Events.HAS_ALARM, Events.HAS_ATTENDEE_DATA,
            Events.SELF_ATTENDEE_STATUS, Events.IS_ORGANIZER),
        "${Events.CALENDAR_ID}=? AND ${Events.TITLE}=?", arrayOf(calendar.toString(), title), null)!!.use {
        assertTrue(it.moveToFirst())
        assertEquals(1, it.count)
        Row(it.getLong(0), it.getString(1), it.getInt(2), it.getInt(3), it.getInt(4), it.getInt(5))
    }
    private fun reminders(event: Long) = resolver.query(Reminders.CONTENT_URI,
        arrayOf(Reminders.MINUTES, Reminders.METHOD), "${Reminders.EVENT_ID}=?", arrayOf(event.toString()),
        "${Reminders.MINUTES} ASC")!!.use {
        buildList { while (it.moveToNext()) {
            assertEquals(Reminders.METHOD_ALERT, it.getInt(1))
            add(it.getInt(0))
        } }
    }
    private data class Person(val email: String, val name: String, val role: Int, val status: Int, val relationship: Int)
    private fun attendees(event: Long) = resolver.query(Attendees.CONTENT_URI,
        arrayOf(Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_TYPE,
            Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_RELATIONSHIP),
        "${Attendees.EVENT_ID}=?", arrayOf(event.toString()), "${Attendees.ATTENDEE_EMAIL} ASC")!!.use {
        buildList { while (it.moveToNext()) {
            assertFalse("Unexpected attendee without email", it.isNull(0))
            assertFalse("Unexpected attendee without projected name", it.isNull(1))
            add(Person(it.getString(0), it.getString(1), it.getInt(2), it.getInt(3), it.getInt(4)))
        } }
    }
    private fun syncUri(uri: Uri, scope: CalendarScope): Uri {
        val account = projection.accountFor(scope.account)
        return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type).build()
    }
    private suspend fun fixture(block: suspend (CalendarScope, CalendarScope, Long, Long) -> Unit) {
        val account = AccountId("details-fixture-" + UUID.randomUUID())
        val first = CalendarScope(account, CalendarId("first"), 0)
        val second = CalendarScope(account, CalendarId("second"), 0)
        try {
            val firstId = projection.ensureCalendar(first, CalendarSummary(first.calendar, "Details fixture", 0x167D8D))
            val secondId = projection.ensureCalendar(second, CalendarSummary(second.calendar, "Details fixture second", 0x167D8D))
            block(first, second, firstId, secondId)
        } finally {
            projection.removeOwnedCalendar(first)
            projection.removeOwnedCalendar(second)
            val manager = AccountManager.get(context)
            val fixtureAccount = projection.accountFor(account)
            if (manager.getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == fixtureAccount }) {
                assertTrue(manager.removeAccountExplicitly(fixtureAccount))
            }
        }
    }
}
