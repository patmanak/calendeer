package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.util.Base64
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.ImportRunResult
import com.patmanak.proton.andcalendarbridge.sync.DeltaSyncTransaction
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in observation of a uniquely named fixture created/edited/deleted in the official client.
 * This test never uploads, edits account settings, or directly changes Calendar Provider rows.
 * Cleanup removes only this token's private qualification preferences, never calendar data.
 */
@RunWith(AndroidJUnit4::class)
class CalendarRemoteMutationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private data class FixtureRow(val id: Long, val title: String, val displayColor: Int) {
        override fun toString() = "FixtureRow(REDACTED)"
    }
    private data class Snapshot(val otherCount: Int, val otherDigest: String, val fixtures: List<FixtureRow>) {
        override fun toString() = "FixtureSnapshot(REDACTED)"
    }

    @Test fun officialClientMutationIsReflectedWithoutChangingOtherEvents() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("calendarRemoteMutation") == "true")
        val token = arguments.getString("fixtureToken") ?: ""
        assertTrue("Fixture token must be exactly eight hexadecimal characters", Regex("[0-9a-fA-F]{8}").matches(token))
        val phase = arguments.getString("mutationPhase") ?: ""
        assertTrue("Unknown fixture phase", phase in setOf("baseline", "created", "updated", "deleted", "cleanup"))
        val app = compose.activity.application as BridgeApplication
        val preferences = app.getSharedPreferences("calendar-remote-fixture-$token", Context.MODE_PRIVATE)
        if (phase == "cleanup") {
            assertTrue("Fixture preference cleanup failed", preferences.edit().clear().commit())
            report(Bundle().apply { putBoolean("fixture_preferences_removed", true) })
            return
        }

        assertTrue("Calendar grants required", AndroidCalendarProjection(app).hasPermissions())
        compose.waitUntil(60_000) {
            app.imports.state.value.authenticated && app.imports.state.value.selections.any { it.enabled && it.available }
        }
        val selected = app.imports.state.value.selections.filter { it.enabled && it.available }
        assertEquals("Qualification requires exactly one selected available calendar", 1, selected.size)
        val selection = selected.single()
        val scopeDigest = digestStrings(listOf(selection.scope.account.value, selection.scope.calendar.value, selection.scope.generation.toString()))
        if (phase != "baseline") {
            assertTrue("Baseline qualification must exist", preferences.getBoolean("baseline_checked", false))
            assertTrue("Fixture account/calendar must match its baseline", preferences.getString("scope_digest", null) == scopeDigest)
        }

        runBlocking {
            withTimeout(180_000) {
                var result = app.imports.run(network = true, fresh = true)
                var continuations = 0
                while (result == ImportRunResult.CONTINUE && continuations < 10) {
                    continuations++
                    result = app.imports.run(network = true, fresh = false)
                }
                assertTrue("Import did not complete within the bounded fixture traversal", result == ImportRunResult.COMPLETE)
                val imported = app.imports.store.selections(selection.scope.account).single { it.scope == selection.scope }
                assertTrue("Fixture import is not fully caught up", imported.status == "IMPORTED")
                val caughtUp = app.imports.store.transaction(selection.scope) {
                    val delta = this as DeltaSyncTransaction
                    delta.deltaCursor != null && delta.deltaReceived == null && !delta.deltaMore &&
                        delta.deltaJobs(1).isEmpty() && delta.deltaJobs(1, true).isEmpty()
                }
                assertTrue("Change-feed obligations remain unresolved", caughtUp)
            }
        }

        val account = AndroidCalendarProjection(app).accountFor(selection.scope.account)
        val calendarId = app.contentResolver.query(Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_ACCESS_LEVEL),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.CAL_SYNC1}=?",
            arrayOf(account.name, account.type, "calendar-bridge-projection-v1"), null)!!.use {
            assertEquals("Qualification requires exactly one owned mirror", 1, it.count)
            assertTrue(it.moveToFirst())
            assertTrue("Mirror must remain read-only", it.getInt(1) == Calendars.CAL_ACCESS_READ)
            it.getLong(0)
        }
        val initialTitle = "BridgeQA-$token-initial"
        val updatedTitle = "BridgeQA-$token-updated"
        val snapshot = snapshot(app, calendarId, initialTitle, updatedTitle)
        if (phase == "baseline") {
            assertFalse("Use a new token or explicitly clean its previous qualification", preferences.getBoolean("baseline_checked", false))
            assertEquals("Fixture must not exist before baseline capture", 0, snapshot.fixtures.size)
            assertTrue("Could not persist qualification baseline", preferences.edit()
                .putBoolean("baseline_checked", true).putString("scope_digest", scopeDigest)
                .putLong("calendar_id", calendarId).putInt("other_count", snapshot.otherCount)
                .putString("other_digest", snapshot.otherDigest).commit())
        } else {
            assertTrue("Owned mirror identity changed", preferences.getLong("calendar_id", -1) == calendarId)
            assertEquals("Unrelated event count changed", preferences.getInt("other_count", -1), snapshot.otherCount)
            assertTrue("Unrelated event contents or identities changed", preferences.getString("other_digest", null) == snapshot.otherDigest)
            when (phase) {
                "created" -> {
                    assertEquals("Exactly one created fixture must be projected", 1, snapshot.fixtures.size)
                    val row = snapshot.fixtures.single()
                    assertTrue("Created fixture title is not the expected synthetic title", row.title == initialTitle)
                    if (preferences.getBoolean("created_checked", false))
                        assertTrue("Created fixture was duplicated or recreated", preferences.getLong("event_id", -1) == row.id)
                    assertTrue("Could not persist created fixture observation", preferences.edit()
                        .putBoolean("created_checked", true).putLong("event_id", row.id)
                        .putInt("initial_color", row.displayColor).commit())
                }
                "updated" -> {
                    assertTrue("Creation phase must have been qualified first", preferences.getBoolean("created_checked", false))
                    assertEquals("Exactly one updated fixture must be projected", 1, snapshot.fixtures.size)
                    val row = snapshot.fixtures.single()
                    assertTrue("Updated fixture title is not the expected synthetic title", row.title == updatedTitle)
                    assertTrue("Update recreated the fixture instead of retaining its provider identity", preferences.getLong("event_id", -1) == row.id)
                    assertTrue("Updated fixture color must differ from its creation color", preferences.getInt("initial_color", row.displayColor) != row.displayColor)
                    assertTrue("Could not persist updated fixture observation", preferences.edit().putBoolean("updated_checked", true).commit())
                }
                "deleted" -> {
                    assertTrue("Update phase must have been qualified first", preferences.getBoolean("updated_checked", false))
                    assertEquals("Deleted fixture is still projected", 0, snapshot.fixtures.size)
                    assertTrue("Could not persist deletion observation", preferences.edit().putBoolean("deleted_checked", true).commit())
                }
            }
        }
        report(Bundle().apply {
            putInt("fixture_event_count", snapshot.fixtures.size)
            putInt("preserved_other_event_count", snapshot.otherCount)
            putBoolean("other_events_preserved", true)
            putBoolean("fixture_phase_verified", true)
            putBoolean("read_only_calendar", true)
            putBoolean("fixture_provider_identity_preserved", phase == "updated")
            putBoolean("fixture_color_changed", phase == "updated")
            putBoolean("fixture_deletion_observed", phase == "deleted")
        })
    }

    private fun snapshot(context: Context, calendarId: Long, initialTitle: String, updatedTitle: String): Snapshot {
        val columns = arrayOf(Events._ID, Events.TITLE, Events.DTSTART, Events.DTEND, Events.DURATION,
            Events.EVENT_TIMEZONE, Events.ALL_DAY, Events.EVENT_COLOR, Events.DISPLAY_COLOR,
            Events.DESCRIPTION, Events.EVENT_LOCATION)
        val digest = MessageDigest.getInstance("SHA-256")
        val fixtures = mutableListOf<FixtureRow>()
        var others = 0
        context.contentResolver.query(Events.CONTENT_URI, columns, "${Events.CALENDAR_ID}=?",
            arrayOf(calendarId.toString()), "${Events._ID} ASC")!!.use { cursor ->
            while (cursor.moveToNext()) {
                val title = if (cursor.isNull(1)) null else cursor.getString(1)
                if (title == initialTitle || title == updatedTitle) {
                    fixtures += FixtureRow(cursor.getLong(0), title!!, cursor.getInt(8))
                } else {
                    others++
                    columns.indices.forEach { index -> addValue(digest, if (cursor.isNull(index)) null else cursor.getString(index)) }
                }
            }
        }
        return Snapshot(others, Base64.encodeToString(digest.digest(), Base64.NO_WRAP), fixtures)
    }

    private fun digestStrings(values: List<String>): String = MessageDigest.getInstance("SHA-256").let { digest ->
        values.forEach { addValue(digest, it) }
        Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }
    private fun addValue(digest: MessageDigest, value: String?) {
        if (value == null) digest.update(ByteBuffer.allocate(4).putInt(-1).array())
        else {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
    }
    private fun report(values: Bundle) = InstrumentationRegistry.getInstrumentation().sendStatus(0, values)
}
