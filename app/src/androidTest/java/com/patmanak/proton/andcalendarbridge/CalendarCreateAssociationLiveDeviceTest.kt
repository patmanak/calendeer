// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract.Events
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.importing.CreateAssociationResult
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonAuthentication
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCreateSubmissionProbe
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the retained single-CREATE fixture. This journey never submits or creates an event. */
@RunWith(AndroidJUnit4::class)
class CalendarCreateAssociationLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun retainedCreateBindsToCurrentAndroidRowAndResumesWithoutRemoteInspection() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarCreateAssociation") == "true")
        val token = args.getString("createFixtureToken").orEmpty()
        val identityHash = args.getString("createAccountHash").orEmpty()
        assertTrue("Explicit retained fixture required", token.matches(Regex("[a-f0-9]{12}")))
        assertTrue("Explicit account identity hash required", identityHash.matches(Regex("[a-f0-9]{64}")))
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.authenticated && app.imports.state.value.selections.isNotEmpty() }
        val before = app.imports.state.value.selections.map { it.scope to it.enabled }
        val fixture = app.getSharedPreferences("create-live-qualification-v1", Context.MODE_PRIVATE)
        assertTrue("Do not replace the retained fixture", fixture.getString("token", null) == token)
        val scope = CalendarScope(AccountId(fixture.getString("account", null)!!), CalendarId(fixture.getString("calendar", null)!!),
            fixture.getLong("generation", -1))
        assertTrue("Authorize the exact retained account", ProtonCreateSubmissionProbe.matchesAccount(app, scope.account, identityHash))
        val name = "calendar-create-live-qualification-v1.db"
        val id = MutationId("synthetic-live-create-$token")
        val record = CreateAttemptStore(app, name).use { it.read(scope, id) } ?: error("Retained non-resendable journal required")
        assertTrue("Never admit this fixture again", record.state == CreateAttemptState.OUTCOME_UNKNOWN)
        val port = ProtonAuthentication.createSubmissions(app)
        CreateAttemptStore(app, name).use { journal ->
            assertTrue("Retained create must associate with current import and Provider",
                app.imports.associateCreate(record, journal, port) == CreateAssociationResult.ASSOCIATED)
        }
        val attempt = CreateAttemptIdentity.of(record)
        val original = CreateAttemptStore(app, name).use { it.binding(attempt) } ?: error("Durable remote binding required")
        val offline = object : CalendarCreateSubmissionPort {
            override suspend fun prepareSubmission(draft: CalendarCreateDraft): PreparedCalendarCreate = error("No new preparation")
            override suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal): CreateSubmissionObservation = error("No dispatch")
            override suspend fun inspect(record: CreateAttemptRecord): CreateSubmissionObservation = error("No inspection after durable binding")
        }
        CreateAttemptStore(app, name).use { reopened ->
            assertTrue("Remote anchor must survive reopening", reopened.binding(attempt) == original)
            reopened.clearProjection(attempt)
            assertTrue("Local metadata replay must resume without contacting the create adapter",
                app.imports.associateCreate(record, reopened, offline) == CreateAssociationResult.ASSOCIATED)
            assertTrue("Keep immutable remote proof", reopened.binding(attempt) == original)
            assertTrue("Keep the unknown admission and protected response", reopened.read(scope, id) == record)
            assertTrue("Still refuse repeat dispatch", !reopened.claim(record.copy(state = CreateAttemptState.PREPARED)))
            val witness = reopened.projectionWitness(attempt) ?: error("Current Provider witness required")
            app.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID),
                "${Events.CALENDAR_ID}=? AND ${Events.TITLE}=?", arrayOf(witness.calendarRow.toString(),
                    "Calendeer QA — création directe $token"), null)!!.use {
                assertTrue("Preserve one retained row", it.count == 1 && it.moveToFirst() && it.getLong(0) == witness.eventRow)
            }
        }
        assertTrue("Keep calendar selections", app.imports.state.value.selections.map { it.scope to it.enabled } == before)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("remote_binding_survives_reopen", true)
            putBoolean("local_replay_without_remote_inspection", true)
            putBoolean("current_provider_witness_retained", true)
            putBoolean("retained_fixture_still_single_and_non_resendable", true)
        })
    }
}
