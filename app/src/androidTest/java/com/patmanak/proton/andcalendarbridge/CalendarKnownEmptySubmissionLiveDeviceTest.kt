// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Separate retained fixture: PREPARE is read-only, SUBMIT is allowed once, reruns use INSPECT only. */
@RunWith(AndroidJUnit4::class)
class CalendarKnownEmptySubmissionLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun explicitEmptyCreateIsRetainedVerifiedAndProjectedWithoutReminders() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarKnownEmptyCreate") == "true")
        val phase = args.getString("createKnownEmptyPhase")
        assertTrue("Explicit qualification phase required", phase in listOf("PREPARE", "SUBMIT", "INSPECT"))
        val token = args.getString("createFixtureToken").orEmpty()
        val identityHash = args.getString("createAccountHash").orEmpty()
        assertTrue("Explicit unique fixture required", token.matches(Regex("[a-f0-9]{12}")))
        assertTrue("Explicit account identity hash required", identityHash.matches(Regex("[a-f0-9]{64}")))
        val stamp = Instant.parse(args.getString("createFixtureStamp"))
        val start = Instant.parse(args.getString("createFixtureStart"))
        assertTrue("Freeze second-precision timestamps", stamp.nano == 0 && start.nano == 0)
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.authenticated && app.imports.state.value.selections.isNotEmpty() }
        val projection = AndroidCalendarProjection(app)
        assertTrue("Calendar permission required before qualification", projection.hasPermissions())
        val prefs = app.getSharedPreferences("known-empty-create-live-qualification-v2", Context.MODE_PRIVATE)
        val selected = app.imports.state.value.selections.filter { it.enabled && it.available }
        assertTrue("Selected accessible calendar required", selected.isNotEmpty())
        val scope = if (prefs.contains("token")) {
            assertTrue("Keep the original fixture identity", prefs.getString("token", null) == token)
            assertTrue("Keep frozen timestamps", prefs.getString("stamp", null) == stamp.toString() &&
                prefs.getString("start", null) == start.toString())
            CalendarScope(AccountId(prefs.getString("account", null)!!), CalendarId(prefs.getString("calendar", null)!!),
                prefs.getLong("generation", -1))
        } else {
            assertTrue("Only PREPARE can freeze a new fixture", phase == "PREPARE")
            selected.first().scope
        }
        assertTrue("Frozen calendar must remain selected and accessible", selected.any { it.scope == scope })
        assertTrue("Authorize the exact test account", ProtonCreateSubmissionProbe.matchesAccount(app, scope.account, identityHash))
        if (!prefs.contains("token")) assertTrue("Freeze identity before preparation", prefs.edit()
            .putString("token", token).putString("stamp", stamp.toString()).putString("start", start.toString())
            .putString("account", scope.account.value).putString("calendar", scope.calendar.value).putLong("generation", scope.generation).commit())
        val id = MutationId("synthetic-known-empty-live-create-$token")
        val title = "Calendeer QA — création sans rappels $token"
        val time = EventTime.Timed(start, start.plusSeconds(3600), ZoneId.of("Europe/Paris"))
        val draft = CalendarCreateDraft(scope, id, stamp, EventContent(title, time,
            "Création sans rappels par la passerelle ; relecture stricte ; exemple conservé.", "Test Calendeer", details = EventDetails()))
        val name = "calendar-known-empty-create-live-qualification-v2.db"
        val port = ProtonAuthentication.createSubmissions(app)
        val existing = CreateAttemptStore(app, name).use { it.read(scope, id) }
        if (phase == "PREPARE") {
            assertTrue("Never prepare after any submission request", !prefs.getBoolean("submission_requested", false))
            val prepared = existing ?: port.prepareSubmission(draft).let { receipt ->
                CreateAttemptRecord(id, scope, receipt.format, receipt.protectedPayload).also { record ->
                    CreateAttemptStore(app, name).use { it.remember(record) }
                }
            }
            assertTrue("Keep unadmitted work", prepared.state == CreateAttemptState.PREPARED)
            assertTrue("Dedicated explicit-empty submission required",
                ProtonCreateSubmissionProbe.knownEmptyPreparationSummary(prepared) == "SUBMISSION_V2_EXPLICIT_EMPTY")
            return@runBlocking
        }
        val record = existing ?: error("Missing journal blocks all re-creation")
        assertTrue("Keep the dedicated scoped submission format", record.format == AdapterFormat("proton-calendar-create-submission", 2))
        val observation = if (phase == "SUBMIT") {
            assertTrue("Never repeat a submission request", !prefs.getBoolean("submission_requested", false))
            assertTrue("Only the first unadmitted attempt can submit", record.state == CreateAttemptState.PREPARED)
            assertTrue("Freeze submission request before entering the port", prefs.edit().putBoolean("submission_requested", true).commit())
            CreateAttemptStore(app, name).use { port.submit(record, it) }
        } else {
            assertTrue("Inspection requires an already admitted attempt", record.state == CreateAttemptState.OUTCOME_UNKNOWN)
            port.inspect(record)
        }
        val unknown = CreateAttemptStore(app, name).use { it.read(scope, id)!! }
        val readShape = if (phase == "INSPECT" && observation !is CreateSubmissionObservation.Verified)
            ProtonCreateSubmissionProbe.knownEmptyReadShape(app, unknown) else "NOT_REQUESTED"
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("admitted_unknown", unknown.state == CreateAttemptState.OUTCOME_UNKNOWN)
            putBoolean("explicit_empty_readback_verified", observation is CreateSubmissionObservation.Verified)
            putString("retained_response_summary", ProtonCreateSubmissionProbe.responseSummary(unknown))
            putString("retained_read_shape", readShape)
        })
        assertTrue("Admitted work stays non-resendable", unknown.state == CreateAttemptState.OUTCOME_UNKNOWN)
        assertTrue("Authenticated raw-empty readback required; never resend an unresolved attempt",
            observation is CreateSubmissionObservation.Verified)
        val verified = observation as CreateSubmissionObservation.Verified
        val actual = verified.event.content
        assertTrue("Readback must retain known-empty details", actual.details == EventDetails())
        CreateAttemptStore(app, name).use { reopened ->
            assertTrue("Exact work and evidence survive reopening", reopened.read(scope, id) == unknown)
            assertTrue("Never readmit the retained preparation", !reopened.claim(unknown.copy(state = CreateAttemptState.PREPARED, protectedEvidence = null)))
        }
        // Use ordinary read-only import. No captured intention is dispatched or acknowledged here.
        app.imports.requestSync()
        withTimeout(180_000) {
            while (true) {
                val witness = projection.inspectOwnedEvent(scope, verified.event.eventId, verified.event.revision)
                if (witness != null && !app.imports.state.value.busy) {
                    app.contentResolver.query(Events.CONTENT_URI,
                        arrayOf(Events.DTSTART, Events.DTEND, Events.HAS_ALARM, Events.TITLE),
                        "${Events._ID}=? AND ${Events.CALENDAR_ID}=?",
                        arrayOf(witness.eventRow.toString(), witness.calendarRow.toString()), null)!!.use { cursor ->
                        assertTrue("One owned projection of the verified remote event", cursor.count == 1 && cursor.moveToFirst())
                        assertTrue("Keep event times", cursor.getLong(0) == time.start.toEpochMilli() && cursor.getLong(1) == time.end.toEpochMilli())
                        assertTrue("No local alarm", cursor.getInt(2) == 0)
                        assertTrue("Keep recognizable fixture title", cursor.getString(3) == title)
                    }
                    app.contentResolver.query(Reminders.CONTENT_URI, arrayOf(Reminders._ID), "${Reminders.EVENT_ID}=?",
                        arrayOf(witness.eventRow.toString()), null)!!.use { assertTrue("No Android reminder rows", it.count == 0) }
                    assertTrue("Keep the exact owned identity and revision after child checks",
                        projection.inspectOwnedEvent(scope, verified.event.eventId, verified.event.revision) == witness)
                    break
                }
                delay(500)
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("retained_fixture_projected_once_without_reminders", true)
            putBoolean("second_admission_refused_after_reopen", true)
        })
    }
}
