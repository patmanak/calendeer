// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.*
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
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

/** Isolated Provider candidates; no editable UI, Proton transport, or outgoing CREATE is exercised. */
@RunWith(AndroidJUnit4::class)
class LocalCreateProviderDeviceTest {
    @Test fun simpleTimedCandidateGetsOnlyALocalMarkerAndRetainsItsDirtyFlag() = fixture {
        val before = values()
        val snapshot = projection.captureAndStampSimpleCreate(target, row, localId)!!
        assertEquals(row, snapshot.eventRow); assertEquals(calendar, snapshot.calendarRow)
        assertEquals(content, snapshot.content)
        val after = values()
        assertNotNull(after[Events.SYNC_DATA5])
        assertEquals(before, after - Events.SYNC_DATA5 + (Events.SYNC_DATA5 to null))
        assertEquals("1", after[Events.DIRTY]); assertEquals("0", after[Events.DELETED])
        assertEquals(snapshot, projection.inspectStampedSimpleCreate(target, localId))
    }

    @Test fun ordinaryAndroidAllDayInsertionCanBeStampedWithoutChangingDates() = fixture(allDay = true, ordinary = true) {
        assertEquals("1", field(Events.DIRTY)); assertNull(field(Events._SYNC_ID))
        assertEquals(content, projection.captureAndStampSimpleCreate(target, row, localId)!!.content)
        assertEquals(content, projection.inspectStampedSimpleCreate(target, localId)!!.content)
        assertEquals("1", field(Events.DIRTY)); assertEquals("0", field(Events.DELETED))
    }

    @Test fun inspectionReadsCurrentContentButNeverClearsDirtyOrRestamps() = fixture {
        projection.captureAndStampSimpleCreate(target, row, localId)!!
        val marker = field(Events.SYNC_DATA5)
        mutate(ContentValues().apply { put(Events.TITLE, "Create provider later local edit") })
        val snapshot = projection.inspectStampedSimpleCreate(target, localId)!!
        assertEquals(content.copy(title = "Create provider later local edit"), snapshot.content)
        assertEquals(marker, field(Events.SYNC_DATA5)); assertEquals("1", field(Events.DIRTY))
        assertNull(projection.captureAndStampSimpleCreate(target, row, LocalEventId(UUID.randomUUID().toString())))
        assertEquals(marker, field(Events.SYNC_DATA5))
    }

    @Test fun cleanDeletedAndMissingCandidatesStayUnqualifiedWithoutFlagChanges() = fixture {
        mutate(ContentValues().apply { put(Events.DIRTY, 0) })
        assertNull(projection.captureAndStampSimpleCreate(target, row, localId)); assertNull(field(Events.SYNC_DATA5))
        mutate(ContentValues().apply { put(Events.DIRTY, 1); put(Events.DELETED, 1) })
        assertNull(projection.captureAndStampSimpleCreate(target, row, localId)); assertNull(field(Events.SYNC_DATA5))
        assertEquals("1", field(Events.DELETED))
        context.contentResolver.delete(uri, "${Events._ID}=?", arrayOf(row.toString()))
        assertNull(projection.captureAndStampSimpleCreate(target, row, localId))
        assertNull(projection.inspectStampedSimpleCreate(target, localId))
    }

    @Test fun everyRemoteSyncMarkerPreventsLocalCreateCapture() = fixture {
        for (column in listOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3,
            Events.SYNC_DATA4, Events.SYNC_DATA6, Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)) {
            mutate(ContentValues().apply { put(column, "fixture foreign sync marker") })
            assertNull("Remote marker $column must not become a local CREATE", projection.captureAndStampSimpleCreate(target, row, localId))
            assertNull(field(Events.SYNC_DATA5)); assertEquals("1", field(Events.DIRTY))
            mutate(ContentValues().apply { putNull(column) })
        }
    }

    @Test fun unsupportedColorFlagsAndPrecisionDoNotAcquireAMarker() = fixture {
        for ((column, value) in listOf(Events.EVENT_COLOR to 0xFFAA1122.toInt().toString(),
            Events.ORGANIZER to "create-fixture@example.invalid", Events.ACCESS_LEVEL to Events.ACCESS_PRIVATE.toString(),
            Events.AVAILABILITY to Events.AVAILABILITY_FREE.toString(), Events.CUSTOM_APP_PACKAGE to "example.create.fixture",
            Events.DTSTART to ((content.time as EventTime.Timed).start.toEpochMilli() + 1).toString())) {
            val original = field(column)
            mutate(ContentValues().apply { put(column, value) })
            assertNull("Unsupported $column must remain unstamped", projection.captureAndStampSimpleCreate(target, row, localId))
            assertNull(field(Events.SYNC_DATA5)); assertEquals("1", field(Events.DIRTY))
            mutate(ContentValues().apply { if (original == null) putNull(column) else put(column, original) })
        }
    }

    @Test fun unsupportedReminderAndRecurrenceCannotBeSilentlyDropped() = fixture {
        val reminder = context.contentResolver.insert(syncUri(Reminders.CONTENT_URI), ContentValues().apply {
            put(Reminders.EVENT_ID, row); put(Reminders.MINUTES, 10); put(Reminders.METHOD, Reminders.METHOD_ALERT)
        })!!
        assertNull(projection.captureAndStampSimpleCreate(target, row, localId)); assertNull(field(Events.SYNC_DATA5))
        context.contentResolver.delete(syncUri(reminder), null, null)
        mutate(ContentValues().apply { put(Events.RRULE, "FREQ=DAILY;COUNT=3"); put(Events.DURATION, "PT7200S"); putNull(Events.DTEND) })
        assertNull(projection.captureAndStampSimpleCreate(target, row, localId)); assertNull(field(Events.SYNC_DATA5))
        assertEquals("FREQ=DAILY;COUNT=3", field(Events.RRULE)); assertEquals("1", field(Events.DIRTY))
    }

    @Test fun candidateInAnotherOwnedCalendarCannotBeStampedForThisScope() = fixture {
        val other = target.copy(calendar = CalendarId("other"))
        try {
            val otherCalendar = projection.ensureCalendar(other, CalendarSummary(other.calendar, "Create other fixture"))
            mutate(ContentValues().apply { put(Events.CALENDAR_ID, otherCalendar) })
            assertNull(projection.captureAndStampSimpleCreate(target, row, localId))
            assertNull(projection.inspectStampedSimpleCreate(target, localId))
            assertNull(field(Events.SYNC_DATA5))
        } finally { projection.removeOwnedCalendar(other) }
    }

    @Test fun duplicateLocalMarkerCannotEstablishAUniqueCreateSnapshot() = fixture {
        projection.captureAndStampSimpleCreate(target, row, localId)!!
        val duplicate = candidateValues().apply { put(Events.SYNC_DATA5, field(Events.SYNC_DATA5)); put(Events.TITLE, "Create duplicate fixture") }
        assertNotNull(context.contentResolver.insert(uri, duplicate))
        assertRefused { projection.inspectStampedSimpleCreate(target, localId) }
        assertEquals("1", field(Events.DIRTY))
    }

    @Test fun tombstoneAfterStampCannotQualifyACreateRecovery() = fixture {
        projection.captureAndStampSimpleCreate(target, row, localId)!!
        val marker = field(Events.SYNC_DATA5)
        mutate(ContentValues().apply { put(Events.DELETED, 1) })
        assertNull(projection.inspectStampedSimpleCreate(target, localId))
        assertEquals(marker, field(Events.SYNC_DATA5)); assertEquals("1", field(Events.DELETED))
    }

    @Test fun anExistingStampedTombstoneBlocksStampingTheSameIdentityOnAnotherCandidate() = fixture {
        projection.captureAndStampSimpleCreate(target, row, localId)!!
        val originalRow = row
        val marker = field(Events.SYNC_DATA5)
        mutate(ContentValues().apply { put(Events.DELETED, 1) })
        val anotherRow = ContentUris.parseId(context.contentResolver.insert(uri, candidateValues().apply {
            put(Events.TITLE, "Create provider second unstamped candidate")
        })!!)
        assertRefused { projection.captureAndStampSimpleCreate(target, anotherRow, localId) }
        assertEquals(originalRow, row); assertEquals(marker, field(Events.SYNC_DATA5)); assertEquals("1", field(Events.DELETED))
        context.contentResolver.query(uri, arrayOf(Events.SYNC_DATA5, Events.DIRTY), "${Events._ID}=?",
            arrayOf(anotherRow.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertEquals(1, it.getInt(1))
        }
    }

    @Test fun concurrentEditBeforeStampAssertionsPreservesContentAndLeavesNoMarker() = fixture {
        val real = context.contentResolver
        var injected = false
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Stamp must not insert")
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Stamp must not delete")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Stamp uses atomic batch")
            override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? =
                real.query(uri, columns, selection, args, order)
            override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                assertEquals(1, operations.count { it.isUpdate }); assertTrue(operations.all { it.isUpdate || it.isAssertQuery })
                injected = true; mutate(ContentValues().apply { put(Events.TITLE, "Create concurrent fixture edit") })
                return real.applyBatch(CalendarContract.AUTHORITY, operations)
            }
        }
        val wrappedResolver = ContentResolver.wrap(provider)
        val wrapped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver() = wrappedResolver
        }
        assertRefused { AndroidCalendarProjection(wrapped).captureAndStampSimpleCreate(target, row, localId) }
        assertTrue(injected); assertNull(field(Events.SYNC_DATA5))
        assertEquals("Create concurrent fixture edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
    }

    private class Fixture(val context: Context, val allDay: Boolean, val ordinary: Boolean) {
        val target = CalendarScope(AccountId("create-provider-${UUID.randomUUID()}"), CalendarId("fixture"), 0)
        val localId = LocalEventId(UUID.randomUUID().toString())
        val projection = AndroidCalendarProjection(context)
        val content = EventContent("Create provider fixture", if (allDay)
            EventTime.AllDay(LocalDate.of(2030, 3, 30), LocalDate.of(2030, 4, 1)) else
            EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
            details = EventDetails())
        var calendar = -1L
        var row = -1L
        val uri get() = syncUri(Events.CONTENT_URI)
        fun syncUri(uri: Uri): Uri {
            val account = projection.accountFor(target.account)
            return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
        }
        fun candidateValues() = ContentValues().apply {
            put(Events.CALENDAR_ID, calendar); put(Events.TITLE, content.title); put(Events.DESCRIPTION, content.description)
            put(Events.EVENT_LOCATION, content.location); put(Events.DIRTY, 1); put(Events.DELETED, 0)
            put(Events.STATUS, Events.STATUS_CONFIRMED); put(Events.HAS_ATTENDEE_DATA, 0); put(Events.HAS_ALARM, 0)
            putNull(Events.ORGANIZER); put(Events.IS_ORGANIZER, 0); put(Events.GUESTS_CAN_MODIFY, 0)
            put(Events.GUESTS_CAN_INVITE_OTHERS, 0); put(Events.GUESTS_CAN_SEE_GUESTS, 0)
            put(Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT); put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            putNull(Events.EVENT_COLOR); putNull(Events.EVENT_COLOR_KEY)
            when (val time = content.time) {
                is EventTime.Timed -> {
                    put(Events.DTSTART, time.start.toEpochMilli()); put(Events.DTEND, time.end.toEpochMilli())
                    put(Events.ALL_DAY, 0); put(Events.EVENT_TIMEZONE, time.zone.id); put(Events.EVENT_END_TIMEZONE, time.zone.id)
                }
                is EventTime.AllDay -> {
                    put(Events.DTSTART, time.start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                    put(Events.DTEND, time.endExclusive.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                    put(Events.ALL_DAY, 1); put(Events.EVENT_TIMEZONE, "UTC"); put(Events.EVENT_END_TIMEZONE, "UTC")
                }
            }
        }
        suspend fun seed() {
            calendar = projection.ensureCalendar(target, CalendarSummary(target.calendar, "Calendeer local create provider fixture"))
            row = ContentUris.parseId(context.contentResolver.insert(if (ordinary) Events.CONTENT_URI else uri, candidateValues())!!)
        }
        fun mutate(values: ContentValues) {
            assertEquals(1, context.contentResolver.update(uri, values, "${Events._ID}=?", arrayOf(row.toString())))
        }
        fun field(column: String): String? = context.contentResolver.query(uri, arrayOf(column),
            "${Events._ID}=?", arrayOf(row.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
        }
        fun values(): Map<String, String?> {
            val columns = listOf(Events.CALENDAR_ID, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART,
                Events.DTEND, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.DIRTY, Events.DELETED,
                Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA4, Events.SYNC_DATA5,
                Events.SYNC_DATA6, Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)
            return columns.associateWith(::field)
        }
        suspend fun close() {
            projection.removeOwnedCalendar(target); AccountManager.get(context).removeAccountExplicitly(projection.accountFor(target.account))
        }
    }
    private fun fixture(allDay: Boolean = false, ordinary: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext(), allDay, ordinary)
        assumeTrue(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
    private suspend fun assertRefused(block: suspend () -> OwnedLocalEditSnapshot?) {
        var refused = false
        try { refused = block() == null } catch (_: CalendarProjectionException) { refused = true }
        assertTrue("Invalid candidate must not become a captured local CREATE", refused)
    }
}
