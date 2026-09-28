package com.patmanak.proton.andcalendarbridge

import android.os.Bundle
import android.content.ContentUris
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.CalendarContract
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.ui.calendars.CalendarImportTags
import com.patmanak.proton.andcalendarbridge.sync.DeltaSyncTransaction
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly enables the single test calendar and retains its requested read-only mirror. */
@RunWith(AndroidJUnit4::class)
class CalendarImportLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun selectedCalendarImportsThroughBackgroundQueue() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("calendarLiveImport") == "true")
        val app = compose.activity.application as BridgeApplication
        assertTrue("Calendar grants required", AndroidCalendarProjection(app).hasPermissions())
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        compose.waitUntil(60_000) {
            connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }
        compose.waitUntil(60_000) { app.imports.state.value.selections.isNotEmpty() }
        assertEquals("This opt-in fixture requires exactly one retained test calendar", 1, app.imports.state.value.selections.size)
        val previousCompletion = app.imports.state.value.selections.single().completedAt ?: 0L
        if (!app.imports.state.value.selections.single().enabled) {
            compose.onNodeWithTag(CalendarImportTags.TOGGLE).performScrollTo().performClick()
        } else if (!app.imports.state.value.busy) {
            compose.onNodeWithTag(CalendarImportTags.SYNC).performScrollTo().performClick()
        }
        compose.waitUntil(180_000) {
            val current = app.imports.state.value.selections.single()
            // A local worker can briefly retain an earlier network error. Only a newly
            // completed import is success; persistent failures remain a bounded timeout.
            current.status == "IMPORTED" && (current.completedAt ?: 0L) > previousCompletion
        }
        val selection = app.imports.state.value.selections.single()
        assertEquals("Import closed status: ${selection.status}", "IMPORTED", selection.status)
        val coverage = selection.coverage
        assertNotNull("A fresh scan must retain its observed intervals", coverage)
        assertTrue(coverage!!.finished)
        assertEquals(coverage.windows.size, coverage.receivedWindows)
        assertEquals(coverage.windows.size, coverage.appliedWindows)
        assertEquals(0, selection.pendingProjectionCount)
        val deltaCaughtUp = runBlocking {
            app.imports.store.transaction(selection.scope) {
                val delta = this as DeltaSyncTransaction
                delta.deltaCursor != null && delta.deltaReceived == null && !delta.deltaMore &&
                    delta.deltaJobs(1).isEmpty() && delta.deltaJobs(1, true).isEmpty()
            }
        }
        assertTrue("Calendar change feed must be caught up before completion", deltaCaughtUp)
        assertFalse("Mapping migration must finish before completion", runBlocking {
            app.imports.store.mappingRescanPending(selection.scope)
        })
        var reusedHorizon = false
        if (InstrumentationRegistry.getArguments().getString("rollingRefresh") == "true") {
            val before = runBlocking { app.imports.store.transaction(selection.scope) { (this as DeltaSyncTransaction).coverage } }
            assertNotNull("A complete fresh scan must establish rolling observations", before.observed)
            runBlocking {
                assertEquals(com.patmanak.proton.andcalendarbridge.data.importing.ImportRunResult.COMPLETE,
                    app.imports.run(network = true, fresh = true))
                app.imports.store.transaction(selection.scope) {
                    val delta = this as DeltaSyncTransaction
                    assertEquals("Same-day refresh must reuse the baseline epoch", before, delta.coverage)
                    assertFalse(delta.deltaMore)
                    assertNull(delta.deltaReceived)
                    assertTrue(delta.deltaJobs(1).isEmpty())
                    assertTrue(delta.deltaJobs(1, true).isEmpty())
                }
            }
            reusedHorizon = true
        }
        val account = AndroidCalendarProjection(app).accountFor(selection.scope.account)
        val calendarId = app.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            "account_name=? AND account_type=?", arrayOf(account.name, account.type), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst())
            assertEquals(CalendarContract.Calendars.CAL_ACCESS_READ, it.getInt(1)); it.getLong(0)
        }
        val eventIds = app.contentResolver.query(CalendarContract.Events.CONTENT_URI, arrayOf(CalendarContract.Events._ID),
            "calendar_id=?", arrayOf(calendarId.toString()), null)!!.use {
            buildList { while (it.moveToNext()) add(it.getLong(0)) }
        }
        val customColors = app.contentResolver.query(CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events.EVENT_COLOR), "calendar_id=? AND eventColor IS NOT NULL",
            arrayOf(calendarId.toString()), null)!!.use { it.count }
        val reminderCount = eventIds.sumOf { eventId -> app.contentResolver.query(CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
            "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(eventId.toString()), null)!!.use {
            while (it.moveToNext()) assertEquals(CalendarContract.Reminders.METHOD_ALERT, it.getInt(1))
            it.count
        } }
        verifyRecurrenceFixture(app, calendarId)
        verifyReminderFixture(app, calendarId)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putInt("projected_event_count", eventIds.size); putBoolean("read_only_calendar", true)
            putInt("custom_event_color_count", customColors)
            putInt("local_reminder_count", reminderCount)
            putBoolean("retained_session", true)
            putBoolean("calendar_delta_caught_up", deltaCaughtUp)
            putInt("received_period_count", coverage.receivedWindows)
            putInt("processed_period_count", coverage.appliedWindows)
            putBoolean("rolling_same_horizon_reused", reusedHorizon)
        })
    }

    private fun verifyReminderFixture(app: BridgeApplication, calendarId: Long) {
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("reminderFixture") ?: return
        require(token.matches(Regex("[0-9a-f]{8}")))
        val minutes = args.getString("reminderMinutes")?.toInt() ?: 10
        val eventId = app.contentResolver.query(CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID, CalendarContract.Events.ALL_DAY),
            "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.TITLE}=?",
            arrayOf(calendarId.toString(), "BridgeQA-$token-reminder"), null)!!.use {
            assertEquals("Synthetic reminder event count", 1, it.count); assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(1)); it.getLong(0)
        }
        app.contentResolver.query(CalendarContract.Reminders.CONTENT_URI,
            arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
            "${CalendarContract.Reminders.EVENT_ID}=?", arrayOf(eventId.toString()), null)!!.use {
            assertEquals("Synthetic reminder count", 1, it.count); assertTrue(it.moveToFirst())
            assertEquals(minutes, it.getInt(0)); assertEquals(CalendarContract.Reminders.METHOD_ALERT, it.getInt(1))
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("live_reminder_mapping_verified", true)
        })
    }

    /** Optional retained, synthetic fixture authored through the official Proton client. */
    private fun verifyRecurrenceFixture(app: BridgeApplication, calendarId: Long) {
        val args = InstrumentationRegistry.getArguments()
        val token = args.getString("recurrenceFixture") ?: return
        require(token.matches(Regex("[0-9a-f]{8}")))
        val phase = args.getString("recurrencePhase") ?: "created"
        require(phase in setOf("created", "moved"))
        val title = "BridgeQA-$token-series"
        val masterStart = app.contentResolver.query(CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events.DTSTART, CalendarContract.Events.RRULE, CalendarContract.Events.ALL_DAY, CalendarContract.Events.DURATION),
            "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.TITLE}=? AND ${CalendarContract.Events.RRULE} IS NOT NULL",
            arrayOf(calendarId.toString(), title), null)!!.use {
            assertEquals("Synthetic master count", 1, it.count); assertTrue(it.moveToFirst())
            val fields = it.getString(1).split(';').toSet()
            assertTrue("Synthetic daily rule", "FREQ=DAILY" in fields)
            assertTrue("Synthetic count", "COUNT=3" in fields)
            assertEquals(1, it.getInt(2)); assertEquals("P1D", it.getString(3))
            it.getLong(0)
        }
        val day = 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, masterStart - day)
            ContentUris.appendId(it, masterStart + 8 * day)
        }.build()
        val actual = app.contentResolver.query(uri, arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END),
            "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.TITLE}=?", arrayOf(calendarId.toString(), title),
            CalendarContract.Instances.BEGIN)!!.use {
            buildList { while (it.moveToNext()) {
                assertEquals("Synthetic one-day duration", day, it.getLong(1) - it.getLong(0))
                add((it.getLong(0) - masterStart) / day)
            } }
        }
        assertEquals("Synthetic recurrence dates", if (phase == "created") listOf(0L, 1L, 2L) else listOf(0L, 2L, 3L), actual)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("live_recurrence_instances_verified", true)
            putBoolean("live_moved_occurrence_verified", phase == "moved")
            putInt("live_recurrence_instance_count", actual.size)
        })
    }
}
