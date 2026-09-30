// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.*
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.provider.CalendarContract.Attendees
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
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
import org.junit.runner.RunWith

/** Isolated Calendar Provider fixtures, including actual non-sync-adapter deletion. */
@RunWith(AndroidJUnit4::class)
class LocalDeleteProviderDeviceTest {
    @Test fun timedTombstoneCanRetainUnchangedContentWithoutAcknowledgingFlags() = fixture {
        tombstone()
        val snapshot = projection.captureOwnedSimpleDelete(target, base)!!
        assertEquals(base.content, snapshot.content)
        assertEquals(row(), snapshot.eventRow); assertEquals(calendar, snapshot.calendarRow)
        assertEquals("1", field(Events.DIRTY)); assertEquals("1", field(Events.DELETED))
    }

    @Test fun ordinaryAndroidDeleteLeavesACapturableAllDayTombstone() = fixture(allDay = true) {
        val id = row()
        assertEquals(1, context.contentResolver.delete(Events.CONTENT_URI,
            "${Events._ID}=?", arrayOf(id.toString())))
        assertEquals("1", field(Events.DIRTY)); assertEquals("1", field(Events.DELETED))
        val snapshot = projection.captureOwnedSimpleDelete(target, base)!!
        assertEquals(id, snapshot.eventRow); assertEquals(base.content, snapshot.content)
        assertEquals("UTC", field(Events.EVENT_TIMEZONE))
        assertEquals("1", field(Events.DIRTY)); assertEquals("1", field(Events.DELETED))
    }

    @Test fun editedThenDeletedRowKeepsItsTextAndSupportedTime() = fixture {
        val changed = (base.content.time as EventTime.Timed).copy(start = Instant.parse("2030-10-27T01:00:00Z"))
        mutate(ContentValues().apply {
            put(Events.TITLE, "Delete provider local edit"); put(Events.DESCRIPTION, "Edited before removal")
            put(Events.DTSTART, changed.start.toEpochMilli())
        }); tombstone()
        assertEquals(base.content.copy(title = "Delete provider local edit", description = "Edited before removal", time = changed),
            projection.captureOwnedSimpleDelete(target, base)!!.content)
        assertEquals("1", field(Events.DIRTY)); assertEquals("1", field(Events.DELETED))
    }

    @Test fun dirtyAndDeletedMustBothBeSetAndAbsenceNeverInventsADeletion() = fixture {
        assertNull(projection.captureOwnedSimpleDelete(target, base))
        mutate(ContentValues().apply { put(Events.DIRTY, 1) })
        assertNull(projection.captureOwnedSimpleDelete(target, base))
        mutate(ContentValues().apply { put(Events.DIRTY, 0); put(Events.DELETED, 1) })
        assertNull(projection.captureOwnedSimpleDelete(target, base))
        assertEquals(1, context.contentResolver.delete(uri, "${Events._ID}=?", arrayOf(row().toString())))
        assertNull(projection.captureOwnedSimpleDelete(target, base))
    }

    @Test fun wrongRevisionScopeAndForeignOwnershipRemainUnqualified() = fixture {
        tombstone()
        assertNull(projection.captureOwnedSimpleDelete(target, base.copy(revision = Revision("wrong"))))
        assertNull(projection.captureOwnedSimpleDelete(target.copy(generation = 1), base))
        mutate(ContentValues().apply { put(Events.SYNC_DATA1, "foreign fixture marker") })
        assertRefused { projection.captureOwnedSimpleDelete(target, base) }
        assertEquals("foreign fixture marker", field(Events.SYNC_DATA1))
        assertEquals("1", field(Events.DIRTY)); assertEquals("1", field(Events.DELETED))
    }

    @Test fun duplicateIdentityCannotQualifyATombstone() = fixture {
        tombstone()
        val columns = arrayOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA4,
            Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.DTSTART, Events.DTEND)
        val values = context.contentResolver.query(uri, columns, "${Events._ID}=?", arrayOf(row().toString()), null)!!.use {
            assertTrue(it.moveToFirst()); ContentValues().apply {
                columns.forEachIndexed { index, column -> put(column, it.getString(index)) }
            }
        }.apply { put(Events.CALENDAR_ID, calendar); put(Events.TITLE, "Delete provider duplicate"); put(Events.DIRTY, 1); put(Events.DELETED, 1) }
        assertNotNull(context.contentResolver.insert(uri, values))
        assertRefused { projection.captureOwnedSimpleDelete(target, base) }
        context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
            assertEquals(2, it.count)
        }
    }

    @Test fun reminderAndAttendeeRowsCannotBeDiscardedByDeletionCapture() = fixture {
        val id = row(); tombstone()
        val reminder = context.contentResolver.insert(syncUri(Reminders.CONTENT_URI), ContentValues().apply {
            put(Reminders.EVENT_ID, id); put(Reminders.MINUTES, 10); put(Reminders.METHOD, Reminders.METHOD_ALERT)
        })!!
        assertNull(projection.captureOwnedSimpleDelete(target, base))
        assertEquals(1, context.contentResolver.delete(syncUri(reminder), null, null))
        assertNotNull(context.contentResolver.insert(syncUri(Attendees.CONTENT_URI), ContentValues().apply {
            put(Attendees.EVENT_ID, id); put(Attendees.ATTENDEE_EMAIL, "delete-fixture@example.invalid")
            put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED); put(Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_INVITED)
            put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE)
        }))
        assertNull(projection.captureOwnedSimpleDelete(target, base))
        assertEquals("1", field(Events.DIRTY)); assertEquals("1", field(Events.DELETED))
    }

    @Test fun unsupportedBaselineRecurrenceFlagsAndColorRemainUncaptured() = fixture {
        tombstone()
        assertNull(projection.captureOwnedSimpleDelete(target, base.copy(content = base.content.copy(
            recurrence = EventRecurrence("delete-fixture-series", "FREQ=DAILY;COUNT=3")))))
        assertNull(projection.captureOwnedSimpleDelete(target, base.copy(content = base.content.copy(
            details = EventDetails(reminders = listOf(EventReminder(10)))))))
        for ((column, value) in listOf(Events.ORGANIZER to "delete-fixture@example.invalid",
            Events.ACCESS_LEVEL to Events.ACCESS_PRIVATE.toString(), Events.AVAILABILITY to Events.AVAILABILITY_FREE.toString(),
            Events.CUSTOM_APP_PACKAGE to "example.delete.fixture", Events.EVENT_COLOR to 0xFFAA1122.toInt().toString())) {
            val original = field(column)
            mutate(ContentValues().apply { put(column, value) })
            assertNull("Unsupported $column cannot be silently dropped", projection.captureOwnedSimpleDelete(target, base))
            mutate(ContentValues().apply { if (original == null) putNull(column) else put(column, original) })
        }
        assertEquals("1", field(Events.DIRTY)); assertEquals("1", field(Events.DELETED))
    }

    @Test fun tombstoneChangedBeforeAssertionCannotReturnTheEarlierSnapshot() = fixture {
        tombstone()
        val real = context.contentResolver
        var injected = false
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Capture is read-only")
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Capture is read-only")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Capture is read-only")
            override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? =
                real.query(uri, columns, selection, args, order)
            override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                assertTrue(operations.all { it.isAssertQuery }); injected = true
                mutate(ContentValues().apply { put(Events.TITLE, "Delete provider concurrent edit") })
                return real.applyBatch(CalendarContract.AUTHORITY, operations)
            }
        }
        val wrappedResolver = ContentResolver.wrap(provider)
        val wrapped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver() = wrappedResolver
        }
        assertRefused { AndroidCalendarProjection(wrapped).captureOwnedSimpleDelete(target, base) }
        assertTrue("Race must reach the assertion batch", injected)
        assertEquals("Delete provider concurrent edit", field(Events.TITLE))
        assertEquals("1", field(Events.DIRTY)); assertEquals("1", field(Events.DELETED))
    }

    private class Fixture(val context: Context, allDay: Boolean) {
        val target = CalendarScope(AccountId("delete-provider-${UUID.randomUUID()}"), CalendarId("fixture"), 0)
        val projection = AndroidCalendarProjection(context)
        val base = RemoteChange.Upsert(EventId("event"), Revision("base"), EventContent("Delete provider fixture",
            if (allDay) EventTime.AllDay(LocalDate.of(2030, 3, 30), LocalDate.of(2030, 4, 1)) else
                EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
            details = EventDetails()))
        var calendar = -1L
        val uri get() = syncUri(Events.CONTENT_URI)
        fun syncUri(uri: Uri): Uri {
            val account = projection.accountFor(target.account)
            return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
        }
        suspend fun seed() {
            calendar = projection.ensureCalendar(target, CalendarSummary(target.calendar, "Calendeer local delete provider fixture"))
            projection.apply(ProjectionIntent(target, base))
        }
        fun row() = context.contentResolver.query(uri, arrayOf(Events._ID),
            "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getLong(0)
        }
        fun field(column: String): String? = context.contentResolver.query(uri, arrayOf(column),
            "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
        }
        fun mutate(values: ContentValues) {
            assertEquals(1, context.contentResolver.update(uri, values,
                "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString())))
        }
        fun tombstone() = mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.DELETED, 1) })
        suspend fun close() {
            projection.removeOwnedCalendar(target); AccountManager.get(context).removeAccountExplicitly(projection.accountFor(target.account))
        }
    }
    private fun fixture(allDay: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext(), allDay)
        assumeTrue(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
    private suspend fun assertRefused(block: suspend () -> OwnedLocalEditSnapshot?) {
        var refused = false
        try { refused = block() == null } catch (_: CalendarProjectionException) { refused = true }
        assertTrue("Invalid or changed owned identity must not qualify a deletion", refused)
    }
}
