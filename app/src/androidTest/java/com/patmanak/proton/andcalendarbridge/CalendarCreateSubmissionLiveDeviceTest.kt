// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract.Events
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

/** Explicit opt-in mutation. Retains one synthetic event and its journal; reruns never send it twice. */
@RunWith(AndroidJUnit4::class)
class CalendarCreateSubmissionLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun singleCreateIsVerifiedAndRetainedWithoutReadmission() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarCreateSubmission") == "true")
        val token = args.getString("createFixtureToken").orEmpty()
        val identity = args.getString("createAccountHash").orEmpty()
        assertTrue("Explicit fixture token required", token.matches(Regex("[a-f0-9]{12}")))
        assertTrue("Explicit authorized account identity hash required", identity.matches(Regex("[a-f0-9]{64}")))
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.authenticated && app.imports.state.value.selections.isNotEmpty() }
        val fixture = app.getSharedPreferences("create-live-qualification-v1", Context.MODE_PRIVATE)
        val selected = app.imports.state.value.selections.filter { it.enabled && it.available }
        assertTrue("Selected accessible calendar required", selected.isNotEmpty())
        val scope = if (fixture.contains("token")) {
            assertTrue("Keep the original uncertain fixture; do not substitute a new token", fixture.getString("token", null) == token)
            CalendarScope(AccountId(fixture.getString("account", null)!!), CalendarId(fixture.getString("calendar", null)!!),
                fixture.getLong("generation", -1))
        } else selected.first().scope
        assertTrue("Frozen scope must remain selected and accessible", selected.any { it.scope == scope })
        assertTrue("Account must match the explicitly authorized identity",
            ProtonCreateSubmissionProbe.matchesAccount(app, scope.account, identity))
        if (!fixture.contains("token")) assertTrue("Freeze identity before any preparation or upload", fixture.edit()
            .putString("token", token).putString("account", scope.account.value).putString("calendar", scope.calendar.value)
            .putLong("generation", scope.generation).commit())

        val id = MutationId("synthetic-live-create-$token")
        val title = "Calendeer QA — création directe $token"
        val time = EventTime.Timed(Instant.parse("2026-10-01T14:00:00Z"), Instant.parse("2026-10-01T15:00:00Z"), ZoneId.of("UTC"))
        val draft = CalendarCreateDraft(scope, id, Instant.parse("2026-09-30T10:00:00Z"),
            EventContent(title, time, "Création par la passerelle ; relecture vérifiée ; exemple conservé.", "Test Calendeer"))
        val port = ProtonAuthentication.createSubmissions(app)
        val journalName = "calendar-create-live-qualification-v1.db"
        val record = CreateAttemptStore(app, journalName).use { journal ->
            val existing = journal.read(scope, id)
            assertTrue("Missing journal after an upload request must block re-creation", existing != null ||
                !fixture.getBoolean("submission_requested", false))
            existing ?: port.prepareSubmission(draft).let { receipt ->
                CreateAttemptRecord(id, scope, receipt.format, receipt.protectedPayload).also { journal.remember(it) }
            }
        }
        if (args.getString("createInspectOnly") != "true")
            assertTrue("Record the fixture submission request before entering the upload port",
                fixture.edit().putBoolean("submission_requested", true).commit())
        val observation = CreateAttemptStore(app, journalName).use { journal ->
            if (args.getString("createInspectOnly") == "true") port.inspect(record) else port.submit(record, journal)
        }
        val unknown = CreateAttemptStore(app, journalName).use { it.read(scope, id)!! }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("admitted_unknown", unknown.state == CreateAttemptState.OUTCOME_UNKNOWN)
            putBoolean("authenticated_readback_verified", observation is CreateSubmissionObservation.Verified)
            putString("response_summary", ProtonCreateSubmissionProbe.responseSummary(unknown))
        })
        assertTrue("A live attempt must remain durably non-resendable", unknown.state == CreateAttemptState.OUTCOME_UNKNOWN)
        assertTrue("Creation requires authenticated readback; an unresolved attempt must not be resent",
            observation is CreateSubmissionObservation.Verified)
        assertTrue("Journal must retain protected response evidence", unknown.protectedEvidence != null)
        CreateAttemptStore(app, journalName).use { reopened ->
            assertTrue("Reopened journal refuses a second admission", !reopened.claim(record))
            assertTrue("Repeating submit only reads the admitted attempt", port.submit(unknown, reopened) is CreateSubmissionObservation.Verified)
        }
        // Let the existing read-only production worker project the remote event, without an outgoing Provider mapping.
        val projection = AndroidCalendarProjection(app)
        assertTrue("Calendar permission required for projection verification", projection.hasPermissions())
        app.imports.requestSync()
        withTimeout(180_000) {
            while (true) {
                val providerAccount = projection.accountFor(scope.account)
                val mapping = app.getSharedPreferences("calendar-provider-mappings", Context.MODE_PRIVATE)
                    .getLong(providerAccount.name + ":" + scope.calendar.value, -1)
                val count = if (mapping <= 0) 0 else app.contentResolver.query(Events.CONTENT_URI,
                    arrayOf(Events.DTSTART, Events.DTEND), "${Events.CALENDAR_ID}=? AND ${Events.TITLE}=?",
                    arrayOf(mapping.toString(), title), null)!!.use { cursor ->
                    assertTrue("Synthetic event must project only once", cursor.count <= 1)
                    if (cursor.moveToFirst()) {
                        assertTrue("Projected event must retain expected time", cursor.getLong(0) == time.start.toEpochMilli() &&
                            cursor.getLong(1) == time.end.toEpochMilli())
                    }
                    cursor.count
                }
                if (count == 1 && !app.imports.state.value.busy) break
                delay(500)
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("retained_fixture_projected_once", true)
            putBoolean("second_admission_refused_after_reopen", true)
        })
    }
}
