// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated native account/calendar only. The Events observer deliberately matches production's
 * broad subscription. Run with other Provider writers idle: a root notification cannot identify
 * its originating calendar, so unrelated activity must not be attributed to this fixture.
 */
@RunWith(AndroidJUnit4::class)
class CalendarAccessIdempotenceDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val projection = AndroidCalendarProjection(context)

    @Before fun explicitFixtureAndPermissions() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        assertTrue("Explicit fixture requires pre-granted calendar permissions", projection.hasPermissions())
    }

    @Test fun repeatedEditableConfigurationDoesNotNotifyEventsObserver() = runBlocking {
        fixture { scope, summary, row, observer ->
            val before = observer.count.get()
            val repeated = projection.ensureCalendarState(scope, summary, true)
            observer.settle()
            assertTrue("Keep the same editable native calendar", repeated.id == row && !repeated.created &&
                access(row) == Calendars.CAL_ACCESS_EDITOR)
            assertEquals("Identical editable configuration must not trigger the production Events observer",
                before, observer.count.get())
        }
    }

    @Test fun readOnlyTransitionNotifiesButRepeatedReadOnlyDoesNot() = runBlocking {
        fixture { scope, _, row, observer ->
            val beforeTransition = observer.count.get()
            projection.makeCalendarReadOnly(scope)
            observer.settle()
            assertEquals(Calendars.CAL_ACCESS_READ, access(row))
            assertTrue("A real access transition must reach the production Events observer",
                observer.count.get() > beforeTransition)
            val beforeRepeat = observer.count.get()
            projection.makeCalendarReadOnly(scope)
            observer.settle()
            assertEquals(Calendars.CAL_ACCESS_READ, access(row))
            assertEquals("Repeated read-only configuration must not trigger the production Events observer",
                beforeRepeat, observer.count.get())
        }
    }

    @Test fun changedNameColorAndModeAreAppliedOnceOnTheSameRow() = runBlocking {
        fixture { scope, _, row, observer ->
            val changed = CalendarSummary(scope.calendar, "Synthetic changed access fixture", 0xD13E42)
            val beforeChange = observer.count.get()
            val updated = projection.ensureCalendarState(scope, changed, false)
            observer.settle()
            assertTrue("A real configuration change must retain the original row", updated.id == row && !updated.created)
            context.contentResolver.query(Calendars.CONTENT_URI,
                arrayOf(Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_COLOR, Calendars.CALENDAR_ACCESS_LEVEL),
                "${Calendars._ID}=?", arrayOf(row.toString()), null)!!.use {
                assertTrue("Require the changed isolated calendar", it.count == 1 && it.moveToFirst())
                assertTrue("Name, opaque color and access must all be updated", it.getString(0) == changed.title &&
                    it.getInt(1) == (0xD13E42 or 0xFF000000.toInt()) && it.getInt(2) == Calendars.CAL_ACCESS_READ)
            }
            assertTrue("Real metadata changes must notify the production Events observer", observer.count.get() > beforeChange)
            val beforeRepeat = observer.count.get()
            val repeated = projection.ensureCalendarState(scope, changed, false)
            observer.settle()
            assertTrue("Repeated changed configuration retains its row and access", repeated.id == row && !repeated.created &&
                access(row) == Calendars.CAL_ACCESS_READ)
            assertEquals("An identical changed configuration must be silent", beforeRepeat, observer.count.get())
        }
    }

    @Test fun revokedLeaseRejectsAnOtherwiseIdenticalConfiguration() = runBlocking {
        fixture { scope, summary, row, observer ->
            val allowed = AtomicBoolean(true)
            projection.admitOperation = { allowed.get() }
            val before = observer.count.get()
            allowed.set(false)
            var refused = false
            try {
                projection.ensureCalendarState(scope, summary, true)
            } catch (_: CancellationException) {
                refused = true
            } finally {
                // Restore only this fixture's lease so its isolated native rows can be removed.
                allowed.set(true)
            }
            observer.settle()
            assertTrue("An unchanged configuration cannot bypass a revoked operation lease", refused)
            assertEquals(Calendars.CAL_ACCESS_EDITOR, access(row))
            assertEquals("A refused no-op must not notify or modify the Provider", before, observer.count.get())
        }
    }

    private suspend fun fixture(block: suspend (CalendarScope, CalendarSummary, Long, Notifications) -> Unit) {
        val scope = CalendarScope(AccountId("access-idempotence-${UUID.randomUUID()}"), CalendarId("synthetic-calendar"), 0)
        val summary = CalendarSummary(scope.calendar, "Synthetic calendar access fixture", 0x176D8D)
        val handlerThread = HandlerThread("calendar-access-fixture-observer").apply { start() }
        val observer = Notifications(Handler(handlerThread.looper))
        var registered = false
        try {
            val created = projection.ensureCalendarState(scope, summary, true)
            assertTrue("Create an isolated editable calendar", created.created && access(created.id) == Calendars.CAL_ACCESS_EDITOR)
            // Drain initial account/calendar creation before registering the same broad URI as production.
            delay(1_000)
            context.contentResolver.registerContentObserver(Events.CONTENT_URI, true, observer)
            registered = true
            observer.settle()
            val baseline = observer.count.get()
            observer.settle()
            assertEquals("Require an idle Provider before measuring configuration", baseline, observer.count.get())
            block(scope, summary, created.id, observer)
        } finally {
            if (registered) context.contentResolver.unregisterContentObserver(observer)
            handlerThread.quitSafely()
            handlerThread.join(2_000)
            projection.removeOwnedCalendar(scope)
            val account = projection.accountFor(scope.account)
            val manager = AccountManager.get(context)
            if (manager.getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).contains(account))
                assertTrue("Remove only the isolated fixture account", manager.removeAccountExplicitly(account))
        }
    }

    private class Notifications(handler: Handler) : ContentObserver(handler) {
        val count = AtomicInteger()
        private val lastChange = AtomicLong()
        override fun onChange(selfChange: Boolean) {
            count.incrementAndGet()
            lastChange.set(SystemClock.elapsedRealtime())
        }
        suspend fun settle() {
            val start = SystemClock.elapsedRealtime()
            withTimeout(10_000) {
                while (SystemClock.elapsedRealtime() - maxOf(start, lastChange.get()) < 1_000) delay(50)
            }
        }
    }

    private fun access(row: Long): Int = context.contentResolver.query(Calendars.CONTENT_URI,
        arrayOf(Calendars.CALENDAR_ACCESS_LEVEL), "${Calendars._ID}=?", arrayOf(row.toString()), null)!!.use {
        assertTrue("Require exactly the isolated calendar row", it.count == 1 && it.moveToFirst())
        it.getInt(0)
    }
}
