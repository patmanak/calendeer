// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Retained legacy fixture only. No preparation, dispatch, journal update or new remote event. */
@RunWith(AndroidJUnit4::class)
class CalendarCreateReadbackLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun retainedUnknownCreateIsInspectedThroughStrictGetParsingWithoutChangingItsJournal() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarCreateReadback") == "true")
        val token = args.getString("createFixtureToken").orEmpty()
        val identityHash = args.getString("createAccountHash").orEmpty()
        assertTrue("Explicit retained fixture required", token.matches(Regex("[a-f0-9]{12}")))
        assertTrue("Explicit account identity hash required", identityHash.matches(Regex("[a-f0-9]{64}")))
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.authenticated && app.imports.state.value.selections.isNotEmpty() }
        val fixture = app.getSharedPreferences("create-live-qualification-v1", Context.MODE_PRIVATE)
        assertTrue("Never create or replace the retained fixture", fixture.getString("token", null) == token)
        val scope = CalendarScope(AccountId(fixture.getString("account", null)!!), CalendarId(fixture.getString("calendar", null)!!),
            fixture.getLong("generation", -1))
        assertTrue("Authorize the exact retained account", ProtonCreateSubmissionProbe.matchesAccount(app, scope.account, identityHash))
        assertTrue("Retained calendar must still be selected and accessible",
            app.imports.state.value.selections.any { it.scope == scope && it.enabled && it.available })
        val journalName = "calendar-create-live-qualification-v1.db"
        val id = MutationId("synthetic-live-create-$token")
        val before = CreateAttemptStore(app, journalName).use { it.read(scope, id) } ?: error("Retained journal required")
        assertTrue("Readback never admits a new attempt", before.state == CreateAttemptState.OUTCOME_UNKNOWN)
        val observation = ProtonAuthentication.createSubmissions(app).inspect(before)
        assertTrue("Authenticated legacy readback must remain verified", observation is CreateSubmissionObservation.Verified)
        val verified = observation as CreateSubmissionObservation.Verified
        assertEquals(CreateAttemptIdentity.of(before), verified.attempt)
        CreateAttemptStore(app, journalName).use { reopened ->
            assertTrue("Readback preserves admission and protected evidence", reopened.read(scope, id) == before)
            val binding = reopened.binding(verified.attempt) ?: error("Retained remote association required")
            assertTrue("Current read identifies the same retained event", binding.eventId == verified.event.eventId)
        }
    }
}
