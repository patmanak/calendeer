// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateWorkStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateWorkStatus
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** One public system-editor creation; VERIFY uses only the production CREATE controller. */
@RunWith(AndroidJUnit4::class)
class CalendarSystemCreateLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun systemEditorCreationReachesProtonAndKeepsTheOriginalAndroidIdentity() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarSystemCreate") == "true")
        val phase = args.getString("systemCreatePhase")
        assertTrue("Explicit system qualification phase required", phase in setOf("LAUNCH", "VERIFY"))
        val token = args.getString("createFixtureToken").orEmpty()
        val accountHash = args.getString("createAccountHash").orEmpty()
        assertTrue("Unique synthetic fixture required", token.matches(Regex("[a-f0-9]{12}")))
        assertTrue("Exact authorized test account required", accountHash.matches(Regex("[a-f0-9]{64}")))
        val start = Instant.parse(args.getString("createFixtureStart"))
        assertTrue("Frozen second-precision start required", start.nano == 0)
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) {
            app.imports.state.value.authenticated && app.imports.state.value.selections.isNotEmpty()
        }
        val projection = AndroidCalendarProjection(app)
        assertTrue("Both calendar grants required", projection.hasPermissions())
        val automaticOnly = args.getString("systemCreateAutomaticOnly") == "true"
        val prefs = app.getSharedPreferences(if (automaticOnly) "system-create-editor-auto-v1"
            else "system-create-editor-live-v1", Context.MODE_PRIVATE)
        val selected = app.imports.state.value.selections.filter { it.enabled && it.available }
        assertTrue("Accessible selected calendar required", selected.isNotEmpty())
        val target = if (phase == "LAUNCH") {
            assertFalse("Never relaunch or replace the retained fixture", prefs.contains("token"))
            selected.first().scope
        } else {
            assertTrue("VERIFY requires the original frozen fixture", prefs.getString("token", null) == token &&
                prefs.getString("start", null) == start.toString())
            CalendarScope(AccountId(checkNotNull(prefs.getString("account", null))),
                CalendarId(checkNotNull(prefs.getString("calendar", null))), prefs.getLong("generation", -1))
        }
        assertTrue("The original scope must remain selected and accessible", selected.any { it.scope == target })
        assertTrue("Authorize the exact retained account", ProtonCreateSubmissionProbe.matchesAccount(app, target.account, accountHash))
        assertTrue("Use the production adapter checkpoint", ImportStore(app).use { reader ->
            reader.transaction(target) { checkpoint?.format == ProtonAuthentication.events(app).format }
        })
        // Resolve and require an existing owned mirror before any mode/UI mutation. No fixture
        // account, mirror or event is inserted by this test.
        val calendar = existingCalendar(app, projection, target)
        val title = "Calendeer QA — création système $token"
        val description = "Création depuis le calendrier Android, synchronisation Proton ; exemple conservé."

        if (phase == "LAUNCH") {
            val previousMode = SystemCreateWorkStore(app).use { it.modeEnabled(target) }
            assertTrue("Freeze original scope and mode before enabling or launching", prefs.edit()
                .putString("token", token).putString("start", start.toString())
                .putString("account", target.account.value).putString("calendar", target.calendar.value)
                .putLong("generation", target.generation).putLong("calendar_row", calendar)
                .putBoolean("previous_mode", previousMode).putBoolean("launch_requested", true).commit())
            val selection = selected.single { it.scope == target }
            compose.runOnUiThread { app.systemCreates.setEnabled(selection, true) }
            assertTrue("Configure the opt-in production mirror", app.imports.configureSystemCalendar(target))
            assertTrue("Preserve the original mirror", calendar == existingCalendar(app, projection, target))
            assertEquals("Expose an editable system calendar", Calendars.CAL_ACCESS_EDITOR,
                calendarAccess(app, projection, target, calendar))
            app.startActivity(Intent(Intent.ACTION_INSERT).apply {
                setDataAndType(Events.CONTENT_URI, "vnd.android.cursor.dir/event")
                setPackage(args.getString("editorPackage") ?: "foundation.e.calendar")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Events.CALENDAR_ID, calendar)
                putExtra(Events.TITLE, title)
                putExtra(Events.DESCRIPTION, description)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toEpochMilli())
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start.plusSeconds(3600).toEpochMilli())
            })
            status("system_editor_launched_once")
            return@runBlocking
        }

        assertTrue("VERIFY never enables or launches a replacement fixture", prefs.getBoolean("launch_requested", false))
        assertTrue("Retain the original owned mirror", prefs.getLong("calendar_row", -1) == calendar)
        assertTrue("The explicitly enabled mode must remain enabled", SystemCreateWorkStore(app).use { it.modeEnabled(target) })
        val row = nativeRow(app, projection, target, calendar, title, start)
        val retainedRow = prefs.getLong("event_row", -1)
        assertTrue("Never substitute the original event", retainedRow == -1L || retainedRow == row)
        assertTrue("Freeze the actual system-created original before verification", prefs.edit().putLong("event_row", row).commit())
        status("system_editor_original_saved")
        app.systemCreates.request()
        withTimeout(120_000) {
            while (true) {
                // Discovery, local capture and networking use the real runtime owner; no test
                // UUIDs, direct Provider writes or direct service submissions are introduced.
                if (!automaticOnly) {
                    app.systemCreates.run(false)
                    app.systemCreates.run(true)
                }
                val finished = SystemCreateWorkStore(app).use { it.read(target, row) }
                if (finished?.status == SystemCreateWorkStatus.COMPLETE) break
                delay(1000)
            }
        }
        val work = SystemCreateWorkStore(app).use { checkNotNull(it.read(target, row)) }
        if (automaticOnly) status("system_creation_background_worker_completed")
        assertEquals(SystemCreateWorkStatus.COMPLETE, work.status)
        assertTrue("Keep the same native row after sending", row ==
            nativeRow(app, projection, target, calendar, title, start))
        assertEquals("Acknowledge only the unchanged original", 0, dirty(app, projection, target, row))
        val attempt = CreateAttemptStore(app).use { checkNotNull(it.read(target, work.attempt)) }
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, attempt.state)
        val submission = LocalCreateStore(app).use { checkNotNull(it.readSubmission(target, work.localId)) }
        assertTrue("Retain the original capture identity", work.captureId == submission.reservation.intent.reservation.id)
        assertTrue("Retain the original attempt identity", work.attempt == submission.reservation.attempt)
        val identity = CapturedCreateDispatchIdentity.of(submission)
        val binding = CreateAttemptStore(app).use { checkNotNull(it.capturedBinding(identity)) }
        val handoff = ImportStore(app).use { checkNotNull(it.capturedHandoff(target, binding.eventId)) }
        assertEquals(CapturedCreateHandoffState.RELEASED, handoff.state)
        assertFalse(handoff.waiting)
        assertNotNull(projection.inspectCapturedHandoffBase(handoff.frame))
        val witness = projection.inspectOwnedEvent(target, binding.eventId, handoff.frame.base.revision)
        assertTrue("Authenticated association belongs to the original row", witness?.let {
            it.eventRow == row && it.calendarRow == calendar
        } == true)

        app.systemCreates.run(false)
        app.systemCreates.run(true)
        assertEquals("Replay keeps the exact work identity", work,
            SystemCreateWorkStore(app).use { it.read(target, row) })
        assertTrue("Replay keeps the single original native event", row ==
            nativeRow(app, projection, target, calendar, title, start))
        CreateAttemptStore(app).use { reopened ->
            assertEquals("Replay never replaces the admitted request or evidence", attempt, reopened.read(target, work.attempt))
            assertFalse("UNKNOWN cannot acquire another claim", reopened.claim(
                attempt.copy(state = CreateAttemptState.PREPARED, protectedEvidence = null)))
        }
        assertTrue("Retain the mode and synthetic example", SystemCreateWorkStore(app).use { it.modeEnabled(target) })
        status("system_creation_authenticated_and_handed_off")
        status("system_creation_original_identity_preserved")
        status("system_creation_replay_non_resendable")
    }

    private fun existingCalendar(app: Context, projection: AndroidCalendarProjection, target: CalendarScope): Long {
        val account = projection.accountFor(target.account)
        val nativeKey = digest("calendar-v1", target.account.value, target.calendar.value, target.generation.toString())
        return app.contentResolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.CAL_SYNC1),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars._SYNC_ID}=?",
            arrayOf(account.name, account.type, nativeKey), null)!!.use {
            assertEquals("Require one existing mirror, never create one for the journey", 1, it.count)
            assertTrue(it.moveToFirst())
            assertTrue("Require the bridge-owned mirror", it.getString(1) == "calendar-bridge-projection-v1")
            it.getLong(0)
        }
    }

    private fun calendarAccess(app: Context, projection: AndroidCalendarProjection, target: CalendarScope, row: Long): Int =
        app.contentResolver.query(syncUri(Calendars.CONTENT_URI, projection, target), arrayOf(Calendars.CALENDAR_ACCESS_LEVEL),
            "${Calendars._ID}=?", arrayOf(row.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getInt(0)
        }

    private fun nativeRow(app: Context, projection: AndroidCalendarProjection, target: CalendarScope,
        calendar: Long, title: String, start: Instant): Long = app.contentResolver.query(
        syncUri(Events.CONTENT_URI, projection, target), arrayOf(Events._ID, Events.DTEND, Events.ALL_DAY),
        "${Events.CALENDAR_ID}=? AND ${Events.TITLE}=? AND ${Events.DTSTART}=?",
        arrayOf(calendar.toString(), title, start.toEpochMilli().toString()), null)!!.use {
        assertEquals("Require exactly one system-created synthetic event", 1, it.count)
        assertTrue(it.moveToFirst())
        assertTrue("Preserve the frozen event duration", it.getLong(1) == start.plusSeconds(3600).toEpochMilli())
        assertEquals("Timed editor fixture required", 0, it.getInt(2))
        it.getLong(0)
    }

    private fun dirty(app: Context, projection: AndroidCalendarProjection, target: CalendarScope, row: Long): Int =
        app.contentResolver.query(syncUri(Events.CONTENT_URI, projection, target), arrayOf(Events.DIRTY, Events.DELETED),
            "${Events._ID}=?", arrayOf(row.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst())
            assertEquals("Preserve a present original", 0, it.getInt(1)); it.getInt(0)
        }

    private fun syncUri(uri: android.net.Uri, projection: AndroidCalendarProjection, target: CalendarScope): android.net.Uri {
        val account = projection.accountFor(target.account)
        return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name).appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type).build()
    }

    private fun digest(vararg values: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
        values.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            hash.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array()); hash.update(bytes)
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun status(name: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putBoolean(name, true) })
}
