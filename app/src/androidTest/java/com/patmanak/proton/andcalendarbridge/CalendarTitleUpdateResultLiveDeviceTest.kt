// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in recovery of one retained historical result, normally after force-stop/restart.
 * No production Proton port is obtained. This proves this recovery path needs no remote calls;
 * it does not claim that unrelated application startup/background work has no network traffic.
 */
class CalendarTitleUpdateResultLiveDeviceTest {
    @Test fun retainedVerifiedResultRecoversWithoutSubmissionOrInspection() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarTitleUpdateResultOffline") == "true")
        val token = args.getString("createFixtureToken").orEmpty()
        val hash = args.getString("createAccountHash").orEmpty()
        assertTrue(token.matches(Regex("[a-f0-9]{12}")) && hash.matches(Regex("[a-f0-9]{64}")))
        val app = ApplicationProvider.getApplicationContext<Context>()
        val fixture = app.getSharedPreferences("title-update-live-qualification-v1", Context.MODE_PRIVATE)
        assertTrue("Require the enrolled retained fixture", fixture.getString("token", null) == token)
        assertTrue("Require the previously qualified historical account", fixture.getString("result_account_hash", null) == hash)
        assertTrue("Require a durable verified observation", fixture.getBoolean("verified_result_retained", false))
        assertTrue("Require the original frozen preparation", fixture.getBoolean("preparation_retained", false))
        val scope = CalendarScope(AccountId(checkNotNull(fixture.getString("account", null))),
            CalendarId(checkNotNull(fixture.getString("calendar", null))), fixture.getLong("generation", -1))
        val id = MutationId(checkNotNull(fixture.getString("attempt", null)))
        val event = EventId(checkNotNull(fixture.getString("event", null)))
        val preparation = ImportStore(app, "title-update-live-preparation-v1.db").use {
            checkNotNull(it.localEditPreparation(scope, event))
        }
        assertTrue("Historical intent remains the enrolled intent", preparation.intent.id.value == fixture.getString("intent", null))
        assertTrue("Historical timestamp remains unchanged", preparation.timestamp.toString() == fixture.getString("timestamp", null))
        assertTrue("Original synthetic content remains intact", preparation.intent.base.content.title == "Calendeer QA — création système $token")
        assertTrue("Changed synthetic content remains intact", preparation.intent.content.title == "Calendeer QA — titre UPDATE $token")
        var remoteCalls = 0
        val unavailableRemote = object : CalendarTitleUpdateSubmissionPort {
            override val titleUpdateFormat: AdapterFormat? = null
            override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord {
                remoteCalls++; error("Offline recovery must never prepare")
            }
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation {
                remoteCalls++; error("Offline recovery must never submit")
            }
            override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation {
                remoteCalls++; error("Retained-result recovery must never inspect")
            }
        }
        val journalName = "calendar-title-update-live-qualification-v1.db"
        val before = TitleUpdateAttemptStore(app, journalName).use { checkNotNull(it.read(scope, id)) }
        assertTrue("Retained attempts remain UNKNOWN", before.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN)
        assertTrue("The first response evidence remains protected", before.protectedEvidence != null)
        assertTrue("The complete preparation is unchanged", before.preparation == preparation)
        val recovered = TitleUpdateAttemptStore(app, journalName).use { journal ->
            journal.admitDispatch = { error("Historical result recovery must never admit dispatch") }
            val historical = checkNotNull(journal.readVerified(scope, id))
            val result = checkNotNull(TitleUpdateResultRecovery(journal, journal, unavailableRemote).recover(scope, id))
            assertTrue("Recovery returns the exact historical result", result == historical)
            assertTrue("Attempt identity is retained", result.attempt == TitleUpdateAttemptIdentity.of(before))
            assertTrue("Result binds the complete attempt independently of response evidence", result.record == before.copy(protectedEvidence = null))
            assertTrue("Original target is retained", result.event.eventId == event)
            assertTrue("Complete verified content is retained", result.event.content == preparation.intent.content)
            assertTrue("Observed revision differs from the original", result.event.revision != preparation.intent.base.revision)
            assertTrue("Recovery preserves the attempt and response evidence", journal.read(scope, id) == before)
            result
        }
        TitleUpdateAttemptStore(app, journalName).use { reopened ->
            assertTrue("Result remains durable across a second reopening", reopened.readVerified(scope, id) == recovered)
            assertTrue("Reopening retains UNKNOWN, evidence and preparation", reopened.read(scope, id) == before)
        }
        ImportStore(app, "title-update-live-preparation-v1.db").use {
            assertTrue("Independent historical preparation remains intact", it.localEditPreparation(scope, event) == preparation)
        }
        assertTrue("Recovery made no remote-port calls", remoteCalls == 0)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("title_update_result_recovered_offline", true)
            putBoolean("title_update_result_same_attempt_and_complete_content", true)
            putBoolean("title_update_result_unknown_evidence_preparation_intact", true)
            putBoolean("title_update_result_no_remote_port_calls", true)
        })
    }
}
