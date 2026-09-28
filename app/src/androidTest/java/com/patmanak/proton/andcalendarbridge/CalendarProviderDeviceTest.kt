package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionFailure
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.EventContent
import com.patmanak.proton.andcalendarbridge.sync.EventId
import com.patmanak.proton.andcalendarbridge.sync.EventTime
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in only: -e providerFixture true. Creates/removes only uniquely identified synthetic rows. */
@RunWith(AndroidJUnit4::class)
class CalendarProviderDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val projection = AndroidCalendarProjection(context)

    @Before fun optInAndPermissions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        assumeTrue("Calendar permissions must already be granted for this explicit fixture run", projection.hasPermissions())
    }

    @Test fun replayAfterNewAdapterInstanceDoesNotDuplicateOrDeleteOtherScope() = runBlocking {
        withFixture { first, second ->
            val firstId = projection.ensureCalendar(first, summary(first))
            val secondId = projection.ensureCalendar(second, summary(second))
            assertNotEquals(firstId, secondId)
            assertEquals(firstId, AndroidCalendarProjection(context).ensureCalendar(first, summary(first)))
            val event = timed("fixture original")
            projection.apply(ProjectionIntent(first, event))
            AndroidCalendarProjection(context).apply(ProjectionIntent(first, event))
            projection.apply(ProjectionIntent(second, event))
            val initial = eventRow(firstId)
            assertEquals(1, countEvents(firstId))
            assertEquals(1, countEvents(secondId))
            val update = event.copy(revision = Revision("fixture-v2"), content = event.content.copy(title = "fixture updated"))
            projection.apply(ProjectionIntent(first, update))
            assertEquals(initial.id, eventRow(firstId).id)
            assertEquals("fixture updated", eventRow(firstId).title)
            assertEquals(event.content.time.let { (it as EventTime.Timed).start.toEpochMilli() }, eventRow(firstId).start)
            val delete = ProjectionIntent(first, RemoteChange.Deleted(event.eventId, Revision("fixture-v3")))
            projection.apply(delete)
            AndroidCalendarProjection(context).apply(delete)
            assertEquals(0, countEvents(firstId))
            assertEquals(1, countEvents(secondId))
        }
    }

    @Test fun allDayUsesUtcExclusiveEndAndCalendarIsReadOnly() = runBlocking {
        withFixture { first, _ ->
            val id = projection.ensureCalendar(first, summary(first))
            val event = RemoteChange.Upsert(EventId("fixture-all-day"), Revision("fixture-v1"),
                EventContent("fixture all day", EventTime.AllDay(LocalDate.parse("2030-03-30"), LocalDate.parse("2030-04-01"))))
            projection.apply(ProjectionIntent(first, event))
            val row = eventRow(id)
            assertEquals(1, row.allDay)
            assertEquals("UTC", row.timezone)
            assertEquals(Instant.parse("2030-03-30T00:00:00Z").toEpochMilli(), row.start)
            assertEquals(Instant.parse("2030-04-01T00:00:00Z").toEpochMilli(), row.end)
            context.contentResolver.query(Calendars.CONTENT_URI, arrayOf(Calendars.CALENDAR_ACCESS_LEVEL),
                "${Calendars._ID}=?", arrayOf(id.toString()), null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(Calendars.CAL_ACCESS_READ, it.getInt(0))
            }
        }
    }

    @Test fun eventColorOverridesCalendarAndCanReturnToInheritedColorWithoutRecreation() = runBlocking {
        withFixture { first, _ ->
            val calendar = projection.ensureCalendar(first, summary(first))
            val base = timed("fixture color")
            projection.apply(ProjectionIntent(first, base.copy(content = base.content.copy(colorRgb = 0xD93025))))
            val id = eventRow(calendar).id
            fun colors(): Pair<Int?, Int> = context.contentResolver.query(Events.CONTENT_URI,
                arrayOf(Events.EVENT_COLOR, Events.DISPLAY_COLOR), "${Events._ID}=?", arrayOf(id.toString()), null)!!.use {
                assertTrue(it.moveToFirst()); (if (it.isNull(0)) null else it.getInt(0)) to it.getInt(1)
            }
            assertEquals(0xFFD93025.toInt() to 0xFFD93025.toInt(), colors())
            projection.apply(ProjectionIntent(first, base.copy(revision = Revision("color-blue"),
                content = base.content.copy(colorRgb = 0x4285F4))))
            assertEquals(0xFF4285F4.toInt() to 0xFF4285F4.toInt(), colors())
            projection.apply(ProjectionIntent(first, base.copy(revision = Revision("color-inherited"))))
            assertEquals(null to 0xFF167D8D.toInt(), colors())
            assertEquals(id, eventRow(calendar).id)
            assertEquals(1, countEvents(calendar))
        }
    }

    @Test fun revokedPermissionStopsBeforeAccountOrCalendarCreation() = runBlocking {
        val deniedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int = PackageManager.PERMISSION_DENIED
        }
        val denied = AndroidCalendarProjection(deniedContext)
        val account = AccountId("calendar-provider-fixture-" + UUID.randomUUID())
        val scope = CalendarScope(account, CalendarId("fixture-calendar"), 0)
        try {
            denied.ensureCalendar(scope, summary(scope))
            throw AssertionError("Projection was admitted without calendar permissions")
        } catch (failure: CalendarProjectionException) {
            assertEquals(CalendarProjectionFailure.PERMISSION_REQUIRED, failure.category)
        }
        assertTrue(AccountManager.get(context).getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE)
            .none { it == projection.accountFor(account) })
    }

    private suspend fun withFixture(block: suspend (CalendarScope, CalendarScope) -> Unit) {
        val account = AccountId("calendar-provider-fixture-" + UUID.randomUUID())
        val first = CalendarScope(account, CalendarId("fixture-a"), 0)
        val second = CalendarScope(account, CalendarId("fixture-b"), 0)
        try { block(first, second) }
        finally {
            // Exact generated account and two exact scopes only; never enumerate/delete another app's rows.
            projection.removeOwnedCalendar(first)
            projection.removeOwnedCalendar(second)
            val fixtureAccount = projection.accountFor(account)
            val manager = AccountManager.get(context)
            if (manager.getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == fixtureAccount }) {
                assertTrue(manager.removeAccountExplicitly(fixtureAccount))
            }
        }
    }

    private fun summary(scope: CalendarScope) = CalendarSummary(scope.calendar, "Calendar Bridge provider fixture", 0x167D8D)

    private fun timed(title: String) = RemoteChange.Upsert(EventId("fixture-shared-event-id"), Revision("fixture-v1"),
        EventContent(title, EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"),
            Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris"))))

    private fun countEvents(calendar: Long): Int = context.contentResolver.query(Events.CONTENT_URI,
        arrayOf(Events._ID), "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use { it.count }

    private fun eventRow(calendar: Long): FixtureRow = context.contentResolver.query(Events.CONTENT_URI,
        arrayOf(Events._ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.EVENT_TIMEZONE),
        "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
        assertTrue(it.moveToFirst())
        assertEquals(1, it.count)
        FixtureRow(it.getLong(0), it.getString(1), it.getLong(2), it.getLong(3), it.getInt(4), it.getString(5))
    }
    private data class FixtureRow(val id: Long, val title: String, val start: Long, val end: Long,
        val allDay: Int, val timezone: String)
}
