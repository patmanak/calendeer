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
import java.time.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OccurrenceEditProviderDeviceTest {
    @Test fun firstMovedOccurrenceIsAnAssertionOnlyVersionedSnapshotBoundToTheExactOriginalSlot() {
        for (allDay in listOf(false, true)) fixture(allDay) {
            val before = rows()
            val reads = mutableListOf<String>()
            val traced = intercepted({ reads += it }, {})
            val snapshot = checkNotNull(traced.captureOwnedOccurrenceEdit(scope, base, child)) { diagnostic() + "; reads=$reads" }
            assertEquals(parent, snapshot.masterRow); assertEquals(child, snapshot.eventRow)
            assertEquals(base, snapshot.candidate.master)
            assertEquals(AdapterFormat("calendeer-native-occurrence", 1), snapshot.candidate.profile)
            assertEquals(original, snapshot.candidate.original)
            assertEquals("Moved phone occurrence", snapshot.candidate.content.title)
            assertEquals(listOf(EventReminder(10)), snapshot.candidate.content.details!!.reminders)
            assertEquals(0x345678, snapshot.candidate.content.colorRgb)
            assertEquals(EventRecurrence(base.content.recurrence!!.uid, original = original), snapshot.candidate.content.recurrence)
            assertEquals(before, rows()); assertEquals("1", rows().getValue(child)[Events.DIRTY])
        }
    }

    @Test fun wrongSlotDirtyParentAdditionalChildAndUnsupportedNativeFieldsStayUntouched() {
        for (variant in 0..10) fixture {
            when (variant) {
                0 -> change(child, Events.ORIGINAL_INSTANCE_TIME, start.minusSeconds(86400).toEpochMilli())
                1 -> change(parent, Events.DIRTY, 1)
                2 -> insertChild()
                3 -> change(child, Events.ORIGINAL_SYNC_ID, "foreign-series")
                4 -> change(child, Events.SYNC_DATA8, "future-profile")
                5 -> change(child, Events.DELETED, 1)
                6 -> change(child, Events.ORIGINAL_ALL_DAY, 1)
                7 -> checkNotNull(context.contentResolver.insert(uri(CalendarContract.Attendees.CONTENT_URI), ContentValues().apply {
                    put(CalendarContract.Attendees.EVENT_ID, parent); put(CalendarContract.Attendees.ATTENDEE_EMAIL, "synthetic@example.invalid")
                    put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED)
                }))
                8 -> change(parent, Events.ACCESS_LEVEL, Events.ACCESS_PRIVATE)
                9 -> change(parent, Events.EVENT_COLOR, 0x00123456)
                10 -> change(child, Events.GUESTS_CAN_INVITE_OTHERS, 1)
            }
            val before = rows()
            assertNull(projection.captureOwnedOccurrenceEdit(scope, base, child)); assertEquals(before, rows())
        }
    }

    @Test fun familyAndMasterRacesCannotPassTheSameAssertionBatch() {
        for (newChild in listOf(false, true)) fixture {
            var injected = false
            val guarded = intercepted {
                if (!injected) {
                    injected = true
                    if (newChild) insertChild() else change(parent, Events.TITLE, "Concurrent parent edit")
                }
            }
            val observation = runCatching { guarded.captureOwnedOccurrenceEdit(scope, base, child) }.getOrNull()
            assertTrue(injected); assertNull(observation)
            assertEquals("1", rows().getValue(child)[Events.DIRTY])
        }
    }

    @Test fun revokedAdmissionNeverReadsOrCapturesAnOccurrence() = fixture {
        projection.admitOperation = { false }
        try {
            projection.captureOwnedOccurrenceEdit(scope, base, child)
            fail("Revocation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        finally { projection.admitOperation = { true } }
    }

    @Test fun foreignScopeAndUnwitnessedMaskedColorCannotAdoptTheChild() = fixture {
        val before = rows()
        assertNull(projection.captureOwnedOccurrenceEdit(scope.copy(calendar = CalendarId("other")), base, child))
        assertEquals(before, rows())
        assertEquals(1, context.contentResolver.update(uri(Events.CONTENT_URI), ContentValues().apply { putNull(Events.EVENT_COLOR) },
            "${Events._ID}=?", arrayOf(child.toString())))
        val raw = rows()
        assertNull(AndroidCalendarProjection(context) { EventColorMode.CALENDAR }.captureOwnedOccurrenceEdit(scope, base, child))
        assertEquals(raw, rows())
    }

    private class Fixture(val context: Context, val allDay: Boolean) {
        val scope = CalendarScope(AccountId("occurrence-${UUID.randomUUID()}"), CalendarId("fixture"), 0)
        val projection = AndroidCalendarProjection(context)
        val start = Instant.parse("2030-04-01T09:00:00Z")
        val original = if (allDay) RecurrencePoint.AllDay(LocalDate.of(2030, 4, 2)) else RecurrencePoint.Timed(start.plusSeconds(86400))
        val base = RemoteChange.Upsert(EventId("master"), Revision("v1"), EventContent("Master",
            if (allDay) EventTime.AllDay(LocalDate.of(2030, 4, 1), LocalDate.of(2030, 4, 2))
            else EventTime.Timed(start, start.plusSeconds(3600), ZoneOffset.UTC), details = EventDetails(),
            colorRgb = 0x123456, recurrence = EventRecurrence("occurrence@calendeer.invalid", "FREQ=DAILY;COUNT=5")))
        var calendar = -1L; var parent = -1L; var child = -1L
        suspend fun seed() {
            calendar = projection.ensureCalendar(scope, CalendarSummary(scope.calendar, "Synthetic occurrence capture"))
            projection.apply(ProjectionIntent(scope, base)); parent = rows().keys.single(); child = insertChild()
            checkNotNull(context.contentResolver.insert(uri(Reminders.CONTENT_URI), ContentValues().apply {
                put(Reminders.EVENT_ID, child); put(Reminders.MINUTES, 10); put(Reminders.METHOD, Reminders.METHOD_ALERT)
            }))
        }
        fun insertChild(): Long = ContentUris.parseId(checkNotNull(context.contentResolver.insert(uri(Events.CONTENT_URI), ContentValues().apply {
            put(Events.CALENDAR_ID, calendar); put(Events.TITLE, "Moved phone occurrence"); put(Events.ORIGINAL_ID, parent)
            put(Events.ORIGINAL_INSTANCE_TIME, if (allDay) Instant.parse("2030-04-02T00:00:00Z").toEpochMilli() else start.plusSeconds(86400).toEpochMilli())
            put(Events.ORIGINAL_ALL_DAY, if (allDay) 1 else 0)
            put(Events.DTSTART, start.plusSeconds(2 * 86400).toEpochMilli()); put(Events.DTEND, start.plusSeconds(2 * 86400 + 7200).toEpochMilli())
            put(Events.EVENT_TIMEZONE, "UTC"); put(Events.EVENT_END_TIMEZONE, "UTC")
            put(Events.STATUS, Events.STATUS_CONFIRMED); put(Events.IS_ORGANIZER, 1)
            put(Events.ORGANIZER, projection.accountFor(scope.account).name)
            put(Events.GUESTS_CAN_INVITE_OTHERS, 0); put(Events.GUESTS_CAN_SEE_GUESTS, 0)
            put(Events.EVENT_COLOR, 0xff345678.toInt()); put(Events.DIRTY, 1)
        })))
        fun change(id: Long, column: String, value: Any) = assertEquals(1, context.contentResolver.update(uri(Events.CONTENT_URI),
            ContentValues().apply { when (value) { is String -> put(column, value); is Int -> put(column, value); is Long -> put(column, value); else -> error("Fixture value") } },
            "${Events._ID}=?", arrayOf(id.toString())))
        fun uri(source: Uri): Uri = source.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, projection.accountFor(scope.account).name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, projection.accountFor(scope.account).type).build()
        fun rows() = context.contentResolver.query(uri(Events.CONTENT_URI), null, "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getLong(cursor.getColumnIndexOrThrow(Events._ID)),
                cursor.columnNames.indices.associate { i -> cursor.getColumnName(i) to if (cursor.isNull(i)) null else cursor.getString(i) }) }
        }
        fun diagnostic(): String {
            val parentRow = rows().getValue(parent); val childRow = rows().getValue(child)
            val expected = projection.eventValues(base, projection.accountFor(scope.account))
            val different = expected.keySet().filter { parentRow[it] != expected.getAsString(it) }
            val flags = listOf(Events.HAS_ALARM, Events.HAS_ATTENDEE_DATA, Events.IS_ORGANIZER, Events.STATUS,
                Events.AVAILABILITY, Events.ACCESS_LEVEL, Events.SELF_ATTENDEE_STATUS, Events.GUESTS_CAN_MODIFY,
                Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS)
            val sync = AndroidCalendarProjection.ATTACHMENT_SYNC_COLUMNS.filter { childRow[it] != null }
            val childCounts = listOf(CalendarContract.Attendees.CONTENT_URI to CalendarContract.Attendees.EVENT_ID,
                CalendarContract.ExtendedProperties.CONTENT_URI to CalendarContract.ExtendedProperties.EVENT_ID).map { (u, key) ->
                context.contentResolver.query(uri(u), arrayOf("_id"), "$key IN (?,?)", arrayOf(parent.toString(), child.toString()), null)!!.use { it.count }
            }
            val originChecks = listOf(childRow[Events.ORIGINAL_ID] == parent.toString(),
                childRow[Events.ORIGINAL_SYNC_ID] == null || childRow[Events.ORIGINAL_SYNC_ID] == parentRow[Events._SYNC_ID],
                childRow[Events.ORIGINAL_ALL_DAY] == if (allDay) "1" else "0")
            val raw = ContentValues().apply { childRow.forEach { (key, value) -> put(key, value) } }
            val timeReadable = projection.readSimpleTime(raw, personalAllDayUtcAlias = true) != null
            val ownerChecks = listOf(childRow[Events.ORGANIZER] == null,
                childRow[Events.ORGANIZER] == projection.accountFor(scope.account).name)
            val unsupported = listOf(Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DURATION,
                Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI).filter { childRow[it] != null }
            return "Synthetic parent differences=$different; child sync fields=$sync; parent flags=${flags.map { parentRow[it] }}; child flags=${flags.map { childRow[it] }}; participant/extended counts=$childCounts; origin checks=$originChecks; time readable=$timeReadable; owner checks=$ownerChecks; unsupported=$unsupported"
        }
        fun intercepted(onQuery: (String) -> Unit = {}, before: () -> Unit): AndroidCalendarProjection {
            val actualResolver = context.contentResolver
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri): String? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Observation cannot insert")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Observation cannot update")
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Observation cannot delete")
                override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? {
                    onQuery(uri.pathSegments.firstOrNull().orEmpty())
                    return actualResolver.query(uri, columns, selection, args, order)
                }
                override fun applyBatch(ops: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                    assertTrue(ops.all { it.isAssertQuery }); before()
                    return actualResolver.applyBatch(CalendarContract.AUTHORITY, ops)
                }
            }
            val resolver = ContentResolver.wrap(provider)
            return AndroidCalendarProjection(object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getContentResolver() = resolver
            })
        }
        suspend fun close() {
            projection.admitOperation = { true }; projection.removeOwnedCalendar(scope)
            val account = projection.accountFor(scope.account)
            if (AccountManager.get(context).getAccountsByType(account.type).contains(account)) assertTrue(AccountManager.get(context).removeAccountExplicitly(account))
        }
    }
    private fun fixture(allDay: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext(), allDay)
        check(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
}
