// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentValues
import android.content.ContentProvider
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Reminders
import android.provider.CalendarContract.ExtendedProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.EventContent
import com.patmanak.proton.andcalendarbridge.sync.EventId
import com.patmanak.proton.andcalendarbridge.sync.EventTime
import com.patmanak.proton.andcalendarbridge.sync.EventDetails
import com.patmanak.proton.andcalendarbridge.sync.EventRecurrence
import com.patmanak.proton.andcalendarbridge.sync.RecurrencePoint
import com.patmanak.proton.andcalendarbridge.sync.ProjectionIntent
import com.patmanak.proton.andcalendarbridge.sync.RemoteChange
import com.patmanak.proton.andcalendarbridge.sync.Revision
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit isolated Provider fixtures; these do not qualify a calendar application's editing UI. */
@RunWith(AndroidJUnit4::class)
class LocalEditProviderDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val projection = AndroidCalendarProjection(context)

    @Before fun optInAndPermissions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        assumeTrue("Calendar permissions must already be granted", projection.hasPermissions())
    }

    @Test fun dirtyLocalUpdateSurvivesIncomingProjection() = runBlocking {
        withFixture { scope, calendar ->
            val base = timed()
            projection.apply(ProjectionIntent(scope, base))
            val row = eventRow(calendar)
            mutate(scope, row, ContentValues().apply {
                put(Events.TITLE, "fixture local edit")
                put(Events.DIRTY, 1)
            })
            val incoming = base.copy(revision = Revision("fixture-next"),
                content = base.content.copy(title = "fixture remote edit"))
            var refused = false
            try { projection.apply(ProjectionIntent(scope, incoming)) }
            catch (_: CalendarProjectionException) { refused = true }
            assertTrue("Incoming projection must refuse to overwrite a dirty local event", refused)
            assertEquals("fixture local edit", field(row, Events.TITLE))
            assertEquals("1", field(row, Events.DIRTY))
            assertEquals(row, eventRow(calendar))
        }
    }

    @Test fun dirtyLocalUpdateSurvivesIncomingDeletion() = runBlocking {
        withFixture { scope, calendar ->
            val base = timed()
            projection.apply(ProjectionIntent(scope, base))
            val row = eventRow(calendar)
            dirty(scope, row)
            requireRefusal { projection.apply(ProjectionIntent(scope,
                RemoteChange.Deleted(base.eventId, Revision("fixture-deleted")))) }
            assertEquals(row, eventRow(calendar))
            assertEquals("1", field(row, Events.DIRTY))
        }
    }

    @Test fun locallyDeletedRowCannotBeResurrectedByAnImport() = runBlocking {
        withFixture { scope, calendar ->
            val base = timed()
            projection.apply(ProjectionIntent(scope, base))
            val row = eventRow(calendar)
            mutate(scope, row, ContentValues().apply { put(Events.DIRTY, 0); put(Events.DELETED, 1) })
            requireRefusal { projection.apply(ProjectionIntent(scope, base.copy(revision = Revision("fixture-next")))) }
            context.contentResolver.query(syncUri(scope, Events.CONTENT_URI), arrayOf(Events.DELETED),
                "${Events._ID}=?", arrayOf(row.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        }
    }

    @Test fun timedSnapshotPreservesEditedTextZoneAndDirtyMarker() = runBlocking {
        withFixture { scope, calendar ->
            val base = timed().copy(content = timed().content.copy(colorRgb = 0x6200EE))
            projection.apply(ProjectionIntent(scope, base))
            val row = eventRow(calendar)
            val edited = EventContent("fixture local title", EventTime.Timed(
                Instant.parse("2030-10-27T01:00:00Z"), Instant.parse("2030-10-27T03:00:00Z"),
                ZoneId.of("Europe/Paris")), "fixture local description", "fixture local location", 0x6200EE)
            val editedTime = edited.time as EventTime.Timed
            mutate(scope, row, ContentValues().apply {
                put(Events.TITLE, edited.title); put(Events.DESCRIPTION, edited.description)
                put(Events.EVENT_LOCATION, edited.location)
                put(Events.DTSTART, editedTime.start.toEpochMilli())
                put(Events.DTEND, editedTime.end.toEpochMilli()); put(Events.DIRTY, 1)
            })
            val snapshot = projection.captureOwnedSimpleEdit(scope, base)
            assertNotNull(snapshot)
            assertEquals(calendar, snapshot!!.calendarRow); assertEquals(row, snapshot.eventRow)
            assertEquals(edited, snapshot.content)
            assertEquals("OwnedLocalEditSnapshot(redacted)", snapshot.toString())
            assertEquals("1", field(row, Events.DIRTY))
            assertEquals(snapshot, AndroidCalendarProjection(context).captureOwnedSimpleEdit(scope, base))
        }
    }

    @Test fun allDaySnapshotUsesUtcDatesAndExclusiveEnd() = runBlocking {
        withFixture { scope, calendar ->
            val base = timed().copy(content = EventContent("fixture all day",
                EventTime.AllDay(LocalDate.parse("2030-03-30"), LocalDate.parse("2030-04-01"))))
            projection.apply(ProjectionIntent(scope, base))
            val row = eventRow(calendar)
            mutate(scope, row, ContentValues().apply {
                put(Events.DTSTART, Instant.parse("2030-03-31T00:00:00Z").toEpochMilli())
                put(Events.DTEND, Instant.parse("2030-04-03T00:00:00Z").toEpochMilli()); put(Events.DIRTY, 1)
            })
            assertEquals(base.content.copy(time = EventTime.AllDay(LocalDate.parse("2030-03-31"),
                LocalDate.parse("2030-04-03"))), projection.captureOwnedSimpleEdit(scope, base)!!.content)
            assertEquals("1", field(row, Events.DIRTY))
        }
    }

    @Test fun cleanWrongRevisionAndWrongScopeCannotCapture() = runBlocking {
        withFixture { scope, calendar ->
            val base = timed()
            projection.apply(ProjectionIntent(scope, base))
            val row = eventRow(calendar)
            assertNull(projection.captureOwnedSimpleEdit(scope, base))
            dirty(scope, row)
            assertNull(projection.captureOwnedSimpleEdit(scope, base.copy(revision = Revision("fixture-other"))))
            assertNull(projection.captureOwnedSimpleEdit(scope.copy(generation = 1), base))
            assertNull(projection.captureOwnedSimpleEdit(scope, base.copy(eventId = EventId("fixture-other"))))
            assertEquals("1", field(row, Events.DIRTY))
        }
    }

    @Test fun unsupportedBaselineDetailsAndRecurrenceRemainUncaptured() = runBlocking {
        withFixture { scope, calendar ->
            val base = timed()
            projection.apply(ProjectionIntent(scope, base))
            val row = eventRow(calendar)
            dirty(scope, row)
            assertNull(projection.captureOwnedSimpleEdit(scope,
                base.copy(content = base.content.copy(details = EventDetails(reminders = listOf(
                    com.patmanak.proton.andcalendarbridge.sync.EventReminder(10)))))))
            assertNull(projection.captureOwnedSimpleEdit(scope,
                base.copy(content = base.content.copy(recurrence = EventRecurrence("fixture-series", "FREQ=DAILY;COUNT=3")))))
            assertEquals("1", field(row, Events.DIRTY))
        }
    }

    @Test fun knownEmptyDetailsFromTheCurrentImportProfileArePreserved() = runBlocking {
        withFixture { scope, calendar ->
            val base = timed().copy(content = timed().content.copy(details = EventDetails()))
            projection.apply(ProjectionIntent(scope, base))
            val row = eventRow(calendar)
            dirty(scope, row)
            assertEquals(base.content.copy(title = "fixture local edit"),
                projection.captureOwnedSimpleEdit(scope, base)!!.content)
            assertEquals("1", field(row, Events.DIRTY))
        }
    }

    @Test fun changedEventColorIsRefusedWithoutClearingTheEdit() = runBlocking {
        withFixture { scope, calendar ->
            val base = timed().copy(content = timed().content.copy(colorRgb = 0x6200EE))
            projection.apply(ProjectionIntent(scope, base))
            val row = eventRow(calendar)
            mutate(scope, row, ContentValues().apply { put(Events.EVENT_COLOR, 0xFFCC1122.toInt()); put(Events.DIRTY, 1) })
            assertNull(projection.captureOwnedSimpleEdit(scope, base))
            assertEquals(0xFFCC1122.toInt().toString(), field(row, Events.EVENT_COLOR))
            assertEquals("1", field(row, Events.DIRTY))
        }
    }

    @Test fun providerChildDetailsCannotBeSilentlyDiscarded() = runBlocking {
        withFixture { scope, calendar ->
            val base = timed()
            projection.apply(ProjectionIntent(scope, base))
            val row = eventRow(calendar)
            dirty(scope, row)
            val reminder = context.contentResolver.insert(syncUri(scope, Reminders.CONTENT_URI), ContentValues().apply {
                put(Reminders.EVENT_ID, row); put(Reminders.MINUTES, 10); put(Reminders.METHOD, Reminders.METHOD_ALERT)
            })!!
            assertNull(projection.captureOwnedSimpleEdit(scope, base))
            assertEquals(1, context.contentResolver.delete(syncUri(scope, reminder), null, null))
            val attendee = context.contentResolver.insert(syncUri(scope, Attendees.CONTENT_URI), ContentValues().apply {
                put(Attendees.EVENT_ID, row); put(Attendees.ATTENDEE_NAME, "Fixture attendee")
                put(Attendees.ATTENDEE_EMAIL, "fixture@example.invalid")
                put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED)
                put(Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_INVITED)
                put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE)
            })!!
            assertNull(projection.captureOwnedSimpleEdit(scope, base))
            assertEquals(1, context.contentResolver.delete(syncUri(scope, attendee), null, null))
            val property = context.contentResolver.insert(syncUri(scope, ExtendedProperties.CONTENT_URI), ContentValues().apply {
                put(ExtendedProperties.EVENT_ID, row); put(ExtendedProperties.NAME, "fixture.unsupported")
                put(ExtendedProperties.VALUE, "fixture value")
            })!!
            assertNull(projection.captureOwnedSimpleEdit(scope, base))
            assertEquals(1, context.contentResolver.delete(syncUri(scope, property), null, null))
            assertEquals("1", field(row, Events.DIRTY))
        }
    }

    @Test fun unsupportedProviderFlagsAndCustomApplicationRemainUncaptured() = runBlocking {
        withFixture { scope, calendar ->
            val base = timed()
            projection.apply(ProjectionIntent(scope, base))
            val row = eventRow(calendar)
            dirty(scope, row)
            val changes = listOf(
                Events.ORGANIZER to "fixture@example.invalid",
                Events.IS_ORGANIZER to "1",
                Events.GUESTS_CAN_MODIFY to "1",
                Events.ACCESS_LEVEL to Events.ACCESS_PRIVATE.toString(),
                Events.AVAILABILITY to Events.AVAILABILITY_FREE.toString(),
                Events.CUSTOM_APP_PACKAGE to "example.fixture",
                Events.CUSTOM_APP_URI to "fixture://unsupported",
            )
            for ((column, value) in changes) {
                val original = field(row, column)
                mutate(scope, row, ContentValues().apply { put(column, value) })
                assertNull("Unsupported Provider field must be refused: $column", projection.captureOwnedSimpleEdit(scope, base))
                mutate(scope, row, ContentValues().apply {
                    if (original == null) putNull(column) else put(column, original)
                })
            }
            assertEquals("1", field(row, Events.DIRTY))
        }
    }

    @Test fun providerRecurrenceCannotBeCapturedAsASimpleEvent() = runBlocking {
        withFixture { scope, calendar ->
            val base = timed()
            projection.apply(ProjectionIntent(scope, base))
            val row = eventRow(calendar)
            mutate(scope, row, ContentValues().apply {
                put(Events.RRULE, "FREQ=DAILY;COUNT=3"); put(Events.DURATION, "PT7200S")
                putNull(Events.DTEND); put(Events.DIRTY, 1)
            })
            assertNull(projection.captureOwnedSimpleEdit(scope, base))
            assertEquals("FREQ=DAILY;COUNT=3", field(row, Events.RRULE))
            assertEquals("1", field(row, Events.DIRTY))
        }
    }

    @Test fun duplicateProviderIdentityCannotBeCaptured() = runBlocking {
        withFixture { scope, calendar ->
            val base = timed()
            projection.apply(ProjectionIntent(scope, base))
            val row = eventRow(calendar)
            dirty(scope, row)
            val columns = arrayOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA4,
                Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.DTSTART, Events.DTEND)
            val values = context.contentResolver.query(Events.CONTENT_URI, columns,
                "${Events._ID}=?", arrayOf(row.toString()), null)!!.use {
                assertTrue(it.moveToFirst()); ContentValues().apply {
                    columns.forEachIndexed { index, column -> put(column, it.getString(index)) }
                }
            }.apply { put(Events.CALENDAR_ID, calendar); put(Events.TITLE, "fixture duplicate"); put(Events.DIRTY, 1) }
            assertNotNull(context.contentResolver.insert(syncUri(scope, Events.CONTENT_URI), values))
            var refused = false
            try { refused = projection.captureOwnedSimpleEdit(scope, base) == null }
            catch (_: CalendarProjectionException) { refused = true }
            assertTrue("Duplicate identity must remain uncaptured", refused)
            assertEquals("1", field(row, Events.DIRTY))
        }
    }

    @Test fun unsupportedTimePrecisionAndAllDayTimezoneAreRefused() = runBlocking {
        withFixture { scope, calendar ->
            val base = timed()
            projection.apply(ProjectionIntent(scope, base))
            val row = eventRow(calendar)
            val original = (base.content.time as EventTime.Timed).start.toEpochMilli()
            mutate(scope, row, ContentValues().apply { put(Events.DTSTART, original + 1); put(Events.DIRTY, 1) })
            assertNull(projection.captureOwnedSimpleEdit(scope, base))
            mutate(scope, row, ContentValues().apply {
                put(Events.DTSTART, Instant.parse("2030-03-31T00:00:00Z").toEpochMilli())
                put(Events.DTEND, Instant.parse("2030-04-01T00:00:00Z").toEpochMilli())
                put(Events.ALL_DAY, 1); put(Events.EVENT_TIMEZONE, "Europe/Paris")
                put(Events.EVENT_END_TIMEZONE, "Europe/Paris")
            })
            assertNull(projection.captureOwnedSimpleEdit(scope, base))
            assertEquals("1", field(row, Events.DIRTY))
        }
    }

    @Test fun dirtySeriesChildPreventsDeletionAndMasterConversionAtomically() = runBlocking {
        withFixture { scope, calendar ->
            // This is a dirty-child protection fixture, independent of finite/DST admission.
            val simple = timed().copy(content = timed().content.copy(time =
                (timed().content.time as EventTime.Timed).copy(zone = ZoneId.of("UTC"))))
            val master = simple.copy(content = simple.content.copy(recurrence = EventRecurrence("fixture-series", "FREQ=DAILY")))
            projection.apply(ProjectionIntent(scope, master))
            val masterRow = eventRow(calendar)
            val child = simple.copy(eventId = EventId("fixture-child"), content = simple.content.copy(
                title = "fixture child", recurrence = EventRecurrence("fixture-series",
                    original = RecurrencePoint.Timed(Instant.parse("2030-10-28T00:30:00Z")))))
            projection.apply(ProjectionIntent(scope, child))
            val childRow = context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID),
                "${Events.CALENDAR_ID}=? AND ${Events.TITLE}=?", arrayOf(calendar.toString(), "fixture child"), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getLong(0)
            }
            mutate(scope, childRow, ContentValues().apply { put(Events.DIRTY, 1) })
            requireRefusal { projection.apply(ProjectionIntent(scope,
                RemoteChange.Deleted(master.eventId, Revision("fixture-deleted"))), "fixture-series") }
            requireRefusal { projection.apply(ProjectionIntent(scope, timed().copy(revision = Revision("fixture-simple")))) }
            assertEquals("FREQ=DAILY", field(masterRow, Events.RRULE))
            assertEquals("1", field(childRow, Events.DIRTY))
            assertEquals("fixture child", field(childRow, Events.TITLE))
        }
    }

    @Test fun editChangedBeforeAssertionBatchIsNotCapturedOrAcknowledged() = runBlocking {
        withFixture { scope, calendar ->
            val base = timed()
            projection.apply(ProjectionIntent(scope, base))
            val row = eventRow(calendar)
            dirty(scope, row)
            var injected = false
            val real = context.contentResolver
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri): String? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Snapshot must not insert")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Snapshot must not update")
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Snapshot must not delete")
                override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? =
                    real.query(uri, columns, selection, args, order)
                override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                    assertTrue("Snapshot batch must contain assertions only", operations.all { it.isAssertQuery })
                    injected = true
                    mutate(scope, row, ContentValues().apply { put(Events.TITLE, "fixture concurrent edit") })
                    return real.applyBatch(CalendarContract.AUTHORITY, operations)
                }
            }
            val wrappedResolver = ContentResolver.wrap(provider)
            val wrapped = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getContentResolver() = wrappedResolver
            }
            var refused = false
            try { refused = AndroidCalendarProjection(wrapped).captureOwnedSimpleEdit(scope, base) == null }
            catch (_: CalendarProjectionException) { refused = true }
            assertTrue("Concurrent edit must not qualify the earlier snapshot", refused)
            assertTrue("Race must reach the assertion batch", injected)
            assertEquals("fixture concurrent edit", field(row, Events.TITLE))
            assertEquals("1", field(row, Events.DIRTY))
        }
    }

    private suspend fun withFixture(block: suspend (CalendarScope, Long) -> Unit) {
        val account = AccountId("local-edit-provider-fixture-" + UUID.randomUUID())
        val scope = CalendarScope(account, CalendarId("fixture-calendar"), 0)
        try {
            val calendar = projection.ensureCalendar(scope,
                CalendarSummary(scope.calendar, "Calendeer local edit provider fixture", 0x167D8D))
            block(scope, calendar)
        } finally {
            projection.removeOwnedCalendar(scope)
            val fixtureAccount = projection.accountFor(account)
            val manager = AccountManager.get(context)
            if (manager.getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == fixtureAccount }) {
                assertTrue(manager.removeAccountExplicitly(fixtureAccount))
            }
        }
    }

    private fun timed() = RemoteChange.Upsert(EventId("fixture-event"), Revision("fixture-base"),
        EventContent("fixture baseline", EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"),
            Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris"))))

    private fun syncUri(scope: CalendarScope, uri: Uri): Uri {
        val account = projection.accountFor(scope.account)
        return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
    }

    private fun mutate(scope: CalendarScope, row: Long, values: ContentValues) {
        assertEquals(1, context.contentResolver.update(syncUri(scope, Events.CONTENT_URI), values,
            "${Events._ID}=?", arrayOf(row.toString())))
    }

    private fun dirty(scope: CalendarScope, row: Long) = mutate(scope, row,
        ContentValues().apply { put(Events.TITLE, "fixture local edit"); put(Events.DIRTY, 1) })

    private suspend fun requireRefusal(block: suspend () -> Unit) {
        var refused = false
        try { block() } catch (_: CalendarProjectionException) { refused = true }
        assertTrue("Projection must refuse to discard a local edit", refused)
    }

    private fun eventRow(calendar: Long): Long = context.contentResolver.query(Events.CONTENT_URI,
        arrayOf(Events._ID), "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
        assertEquals(1, it.count)
        assertTrue(it.moveToFirst())
        it.getLong(0)
    }

    private fun field(row: Long, column: String): String? = context.contentResolver.query(Events.CONTENT_URI,
        arrayOf(column), "${Events._ID}=?", arrayOf(row.toString()), null)!!.use {
        assertEquals(1, it.count)
        assertTrue(it.moveToFirst())
        if (it.isNull(0)) null else it.getString(0)
    }
}
