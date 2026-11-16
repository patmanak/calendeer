// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.*
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Provider, synthetic family only; no session, service or client UI. */
@RunWith(AndroidJUnit4::class)
class RecurringFamilyColorDeviceTest {
    @Test fun pagedColorMaskAndRestoreIncludesMovedChangedKindAndCancelledExceptions() = fixture {
        val before = rows()
        mode = EventColorMode.CALENDAR
        val first = projection().reprojectEventColors(scope, limit = 1)
        assertTrue(first.hasMore)
        // Recreate the adapter between pages, as after an interrupted offline pass.
        var page = projection().reprojectEventColors(scope, first.afterRow, 1)
        var pages = 2
        while (page.hasMore) { page = projection().reprojectEventColors(scope, page.afterRow, 1); pages++ }
        assertEquals(4, pages)
        val masked = rows()
        assertEquals(before.keys, masked.keys)
        before.forEach { (id, row) ->
            val after = masked.getValue(id)
            assertEquals(stable(row), stable(after)); assertNull(after[Events.EVENT_COLOR])
            assertEquals(row[Events.EVENT_COLOR]!!.toLong().toInt() and 0xffffff,
                NativeColorStyle.read(after[Events.SYNC_DATA3])!!.colorRgb)
        }
        mode = EventColorMode.EVENT
        page = projection().reprojectEventColors(scope, limit = 1)
        while (page.hasMore) page = projection().reprojectEventColors(scope, page.afterRow, 1)
        assertEquals(before, rows())
    }

    @Test fun dirtyExceptionAndForeignRowsStayUntouchedWhileOtherFamilyColorsConverge() = fixture {
        val child = rows().entries.first { it.value[Events.TITLE] == "Moved occurrence" }.key
        assertEquals(1, context.contentResolver.update(uri(Events.CONTENT_URI), ContentValues().apply {
            put(Events.DIRTY, 1); put(Events.TITLE, "Newer phone occurrence")
        }, "${Events._ID}=?", arrayOf(child.toString())))
        val foreign = ContentUris.parseId(checkNotNull(context.contentResolver.insert(uri(Events.CONTENT_URI), ContentValues().apply {
            put(Events.CALENDAR_ID, calendar); put(Events.TITLE, "Foreign fixture")
            put(Events.DTSTART, Instant.parse("2030-05-01T15:00:00Z").toEpochMilli())
            put(Events.DTEND, Instant.parse("2030-05-01T16:00:00Z").toEpochMilli())
            put(Events.EVENT_TIMEZONE, "UTC"); put(Events.EVENT_COLOR, 0xff998877.toInt()); put(Events.DIRTY, 0)
        })))
        val before = rows()
        mode = EventColorMode.CALENDAR
        var page = projection().reprojectEventColors(scope, limit = 1)
        while (page.hasMore) page = projection().reprojectEventColors(scope, page.afterRow, 1)
        val after = rows()
        assertEquals(before.getValue(child), after.getValue(child)); assertEquals(before.getValue(foreign), after.getValue(foreign))
        after.filterKeys { it != child && it != foreign }.forEach { (id, row) ->
            assertNull(row[Events.EVENT_COLOR]); assertEquals(stable(before.getValue(id)), stable(row))
        }
    }

    private class Fixture(val context: Context) {
        val scope = CalendarScope(AccountId("family-colors-${UUID.randomUUID()}"), CalendarId("fixture"), 0)
        var mode = EventColorMode.EVENT
        fun projection() = AndroidCalendarProjection(context) { mode }
        var calendar = -1L
        private val uid = "family@calendeer.invalid"
        private val start = Instant.parse("2030-05-01T09:00:00Z")
        suspend fun seed() {
            val adapter = projection()
            calendar = adapter.ensureCalendar(scope, CalendarSummary(scope.calendar, "Synthetic family colors"))
            val master = EventContent("Master", EventTime.Timed(start, start.plusSeconds(3600), ZoneOffset.UTC),
                colorRgb = 0x123456, recurrence = EventRecurrence(uid, "FREQ=DAILY;COUNT=6"), details = EventDetails())
            adapter.apply(ProjectionIntent(scope, RemoteChange.Upsert(EventId("master"), Revision("v1"), master)))
            val variants = listOf(
                master.copy(title = "Moved occurrence", time = EventTime.Timed(start.plusSeconds(2 * 86400 + 7200),
                    start.plusSeconds(2 * 86400 + 10800), ZoneOffset.UTC), colorRgb = 0x654321),
                master.copy(title = "Changed kind", time = EventTime.AllDay(LocalDate.of(2030, 5, 4), LocalDate.of(2030, 5, 5)), colorRgb = 0xabcdef),
                master.copy(title = "Cancelled occurrence", time = EventTime.Timed(start.plusSeconds(3 * 86400),
                    start.plusSeconds(3 * 86400 + 3600), ZoneOffset.UTC), colorRgb = 0x987654))
            variants.forEachIndexed { i, content ->
                val child = content.copy(recurrence = EventRecurrence(uid, original = RecurrencePoint.Timed(start.plusSeconds((i + 1) * 86400L)),
                    cancelled = i == 2))
                adapter.apply(ProjectionIntent(scope, RemoteChange.Upsert(EventId("child-$i"), Revision("v1"), child)))
            }
            assertEquals(4, rows().size)
        }
        fun uri(source: Uri): Uri = source.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, projection().accountFor(scope.account).name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, projection().accountFor(scope.account).type).build()
        fun rows(): Map<Long, Map<String, String?>> = context.contentResolver.query(uri(Events.CONTENT_URI), columns,
            "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), "${Events._ID} ASC")!!.use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), columns.indices.associate { index ->
                columns[index] to if (cursor.isNull(index)) null else cursor.getString(index) }) }
        }
        suspend fun close() {
            projection().removeOwnedCalendar(scope)
            val account = projection().accountFor(scope.account)
            val manager = AccountManager.get(context)
            if (manager.getAccountsByType(account.type).contains(account)) assertTrue(manager.removeAccountExplicitly(account))
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext())
        check(fixture.projection().hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
    companion object {
        private val columns = arrayOf(Events._ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.ALL_DAY,
            Events.RRULE, Events.DURATION, Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME,
            Events.ORIGINAL_ALL_DAY, Events.STATUS, Events.DIRTY, Events.DELETED, Events._SYNC_ID,
            Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA4, Events.EVENT_COLOR, Events.EVENT_COLOR_KEY)
        private fun stable(row: Map<String, String?>) = row - setOf(Events.EVENT_COLOR, Events.EVENT_COLOR_KEY, Events.SYNC_DATA3)
    }
}
