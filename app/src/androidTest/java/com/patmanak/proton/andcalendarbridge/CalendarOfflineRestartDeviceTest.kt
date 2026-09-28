package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.net.ConnectivityManager
import android.os.Bundle
import android.provider.CalendarContract
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.ImportRunResult
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in two-process journey. Host must stop the app and disconnect networking between phases.
 * Only a content digest is retained locally; existing events and the session are never reset.
 */
@RunWith(AndroidJUnit4::class)
class CalendarOfflineRestartDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun retainedSessionAndOwnedEventsSurviveAnOfflineProcessRestart() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("calendarOfflineRestart") == "true")
        val phase = arguments.getString("offlinePhase")
        require(phase in setOf("prepare", "verify", "cleanup"))
        val app = compose.activity.application as BridgeApplication
        val preferences = app.getSharedPreferences("offline-restart-test-fixture", Context.MODE_PRIVATE)
        if (phase == "cleanup") {
            assertTrue(preferences.edit().clear().commit())
            return
        }
        compose.waitUntil(60_000) {
            app.imports.state.value.authenticated && app.imports.state.value.selections.isNotEmpty()
        }
        val selection = app.imports.state.value.selections.single()
        assertTrue("An enabled, available test calendar is required", selection.enabled && selection.available)
        val projection = AndroidCalendarProjection(app)
        assertTrue("Existing calendar grants required", projection.hasPermissions())
        val account = projection.accountFor(selection.scope.account)
        val calendar = app.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID), "account_name=? AND account_type=?",
            arrayOf(account.name, account.type), null)!!.use {
            assertEquals("Exactly one owned test calendar required", 1, it.count)
            assertTrue(it.moveToFirst()); it.getLong(0)
        }
        fun snapshot(): Pair<Int, String> {
            val digest = MessageDigest.getInstance("SHA-256")
            val columns = arrayOf(CalendarContract.Events._ID, CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART, CalendarContract.Events.DTEND, CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.EVENT_TIMEZONE, CalendarContract.Events.DESCRIPTION,
                CalendarContract.Events.EVENT_LOCATION, CalendarContract.Events.EVENT_COLOR)
            val count = app.contentResolver.query(CalendarContract.Events.CONTENT_URI, columns,
                "calendar_id=?", arrayOf(calendar.toString()), CalendarContract.Events._ID)!!.use { cursor ->
                while (cursor.moveToNext()) for (index in columns.indices) {
                    val bytes = if (cursor.isNull(index)) byteArrayOf(0) else
                        byteArrayOf(1) + cursor.getString(index).toByteArray(Charsets.UTF_8)
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                    digest.update(bytes)
                }
                cursor.count
            }
            return count to digest.digest().joinToString("") { "%02x".format(it) }
        }
        val before = snapshot()
        assertTrue("At least one imported event is required", before.first > 0)
        if (phase == "prepare") {
            assertTrue(preferences.edit().putInt("count", before.first).putString("digest", before.second)
                .putInt("pid", android.os.Process.myPid()).commit())
        } else {
            assertTrue("Preparation evidence is required", preferences.contains("digest"))
            assertTrue("The application must have restarted in a different process",
                preferences.getInt("pid", -1) != android.os.Process.myPid())
            val connectivity = app.getSystemService(ConnectivityManager::class.java)
            assertNull("Host must disable networking before this phase", connectivity.activeNetwork)
            assertTrue("Retained provider content changed across restart",
                preferences.getInt("count", -1) == before.first && preferences.getString("digest", null) == before.second)
            val localResult = runBlocking { app.imports.run(network = false, fresh = false) }
            assertTrue("Local processing must not request a network retry", localResult != ImportRunResult.RETRY)
            assertTrue("Local processing reported a blocking failure",
                app.imports.state.value.selections.single().status !in
                    setOf("ERROR", "AUTH", "LOCAL_KEYS", "CAPACITY", "UNSUPPORTED", "PERMISSION", "UNAVAILABLE"))
            assertNull("Network became available during offline verification", connectivity.activeNetwork)
            assertTrue("Offline local processing changed the retained provider content", before == snapshot())
            assertTrue("Session must remain restored offline", app.imports.state.value.authenticated)
            assertTrue(preferences.edit().clear().commit())
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putBoolean("offline_process_restart", true)
                putBoolean("retained_session", true)
                putInt("unchanged_owned_event_count", before.first)
            })
        }
    }
}
