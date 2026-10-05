// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.os.Bundle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateWorkStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonTitleUpdateShapeProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in mutation of one retained synthetic event. No native acknowledgement or worker activation.
 * Restart reads the exact frozen preparation/attempt; UNKNOWN never admits another PUT.
 */
class CalendarTitleUpdateSubmissionLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun singleTitleUpdateIsFullyReadBackAndRestartOnlyInspects() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarTitleUpdateSubmission") == "true")
        val token = args.getString("createFixtureToken").orEmpty()
        val hash = args.getString("createAccountHash").orEmpty()
        assertTrue(token.matches(Regex("[a-f0-9]{12}")) && hash.matches(Regex("[a-f0-9]{64}")))
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.authenticated && app.imports.state.value.selections.isNotEmpty() }
        val fixture = app.getSharedPreferences("title-update-live-qualification-v1", Context.MODE_PRIVATE)
        assertEquals("Only use the frozen authorized fixture", token, fixture.getString("token", null))
        assertTrue("The original preparation must already be durable", fixture.getBoolean("preparation_retained", false))
        val scope = CalendarScope(AccountId(checkNotNull(fixture.getString("account", null))),
            CalendarId(checkNotNull(fixture.getString("calendar", null))), fixture.getLong("generation", -1))
        assertTrue(ProtonCreateSubmissionProbe.matchesAccount(app, scope.account, hash))
        val event = EventId(checkNotNull(fixture.getString("event", null)))
        val preparation = ImportStore(app, "title-update-live-preparation-v1.db").use {
            checkNotNull(it.localEditPreparation(scope, event))
        }
        assertTrue("Retain the exact original intent", fixture.getString("intent", null) == preparation.intent.id.value)
        assertTrue("Retain the exact preparation time", fixture.getString("timestamp", null) == preparation.timestamp.toString())
        assertEquals("Calendeer QA — création système $token", preparation.intent.base.content.title)
        assertEquals("Calendeer QA — titre UPDATE $token", preparation.intent.content.title)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            ProtonTitleUpdateShapeProbe.profileDiagnostics(preparation).forEach { (key, value) ->
                putString("title_update_profile_$key", value)
            }
        })
        val id = MutationId(checkNotNull(fixture.getString("attempt", null)))
        val port = ProtonAuthentication.titleUpdates(app)
        val journalName = "calendar-title-update-live-qualification-v1.db"
        val inspectOnly = args.getString("titleUpdateInspectOnly") == "true"
        val record = TitleUpdateAttemptStore(app, journalName).use { journal ->
            val existing = journal.read(scope, id)
            assertTrue("Missing work after a request must never create a new attempt", existing != null ||
                (!inspectOnly && !fixture.getBoolean("submission_requested", false)))
            existing ?: port.prepareSubmission(id, preparation).also { journal.remember(it) }
        }
        assertEquals(preparation, record.preparation)
        val observation = TitleUpdateAttemptStore(app, journalName).use { journal ->
            SystemCreateWorkStore(app).use { modes ->
                // Local checks only: the authentication facade owns a non-reentrant mutex.
                journal.admitDispatch = { candidate ->
                    val current = app.imports.state.value
                    candidate == scope && current.authenticated && current.hasPermission && modes.modeEnabled(scope) &&
                        current.selections.any { it.scope == scope && it.enabled && it.available }
                }
                if (inspectOnly || record.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN) port.inspect(record)
                else {
                    assertTrue("Freeze request before entering the adapter", fixture.edit().putBoolean("submission_requested", true).commit())
                    port.submit(record, journal)
                }
            }
        }
        val unknown = TitleUpdateAttemptStore(app, journalName).use { checkNotNull(it.read(scope, id)) }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("title_update_unknown_durable", unknown.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN)
            putBoolean("title_update_response_retained", unknown.protectedEvidence != null)
            putBoolean("title_update_full_readback_verified", observation is TitleUpdateSubmissionObservation.Verified)
            ProtonTitleUpdateShapeProbe.responseSummary(unknown).forEach { (key, value) ->
                putString("title_update_response_$key", value)
            }
        })
        if (observation !is TitleUpdateSubmissionObservation.Verified) {
            val diagnostics = ProtonTitleUpdateShapeProbe.readbackDiagnostics(app, unknown)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                diagnostics.forEach { (key, value) -> putString("title_update_readback_$key", value) }
            })
        }
        assertEquals("Uncertain outcomes are permanently inspection-only", TitleUpdateAttemptState.OUTCOME_UNKNOWN, unknown.state)
        assertTrue("Full authenticated metadata and signed content are required", observation is TitleUpdateSubmissionObservation.Verified)
        val verified = observation as TitleUpdateSubmissionObservation.Verified
        assertEquals(TitleUpdateAttemptIdentity.of(unknown), verified.attempt)
        assertTrue("Complete event content must match", preparation.intent.content == verified.event.content)
        assertTrue("Only the original event can qualify", event == verified.event.eventId)
        assertTrue("Observed revision must differ from the original", preparation.intent.base.revision != verified.event.revision)
        TitleUpdateAttemptStore(app, journalName).use { reopened ->
            reopened.admitDispatch = { true }
            assertTrue(!reopened.claim(record))
            reopened.admitDispatch = { error("Readback must not enter dispatch admission") }
            assertTrue(port.submit(unknown, reopened) is TitleUpdateSubmissionObservation.Verified)
            assertEquals(unknown, reopened.read(scope, id))
        }
        // The independent historical preparation is retained; there is deliberately no Provider ACK.
        ImportStore(app, "title-update-live-preparation-v1.db").use {
            assertEquals(preparation, it.localEditPreparation(scope, event))
        }
        assertTrue(fixture.edit().putBoolean("full_readback_verified", true).commit())
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("title_update_restart_inspects_without_readmission", true)
            putBoolean("title_update_original_preparation_retained", true)
        })
    }
}
