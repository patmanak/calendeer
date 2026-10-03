// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.Account
import android.content.Context
import android.os.Bundle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.protonauth.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in authenticated preparation of an existing synthetic fixture; no upload or Provider edits. */
@RunWith(AndroidJUnit4::class)
class CalendarLocalTitlePreparationLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun retainedFixtureTitlePreparesThroughRealPortAndRecoversWithoutUpload() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarLocalTitlePreparation") == "true")
        val token = args.getString("createFixtureToken").orEmpty()
        val identityHash = args.getString("createAccountHash").orEmpty()
        assertTrue("An explicit retained fixture and account are required",
            token.matches(Regex("[a-f0-9]{12}")) && identityHash.matches(Regex("[a-f0-9]{64}")))
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.authenticated &&
            app.imports.state.value.selections.isNotEmpty() && !app.imports.state.value.busy }
        val prefs = app.getSharedPreferences("known-empty-create-live-qualification-v2", Context.MODE_PRIVATE)
        assertTrue("Only the previously submitted retained fixture can be read",
            prefs.getString("token", null) == token && prefs.getBoolean("submission_requested", false))
        val source = CalendarScope(AccountId(requireNotNull(prefs.getString("account", null))),
            CalendarId(requireNotNull(prefs.getString("calendar", null))), prefs.getLong("generation", -1))
        assertTrue("The exact test account must be authorized before event reads",
            ProtonCreateSubmissionProbe.matchesAccount(app, source.account, identityHash))
        val beforeSelections = app.imports.state.value.selections.map { it.scope to it.enabled }
        assertTrue("The retained calendar must remain selected and accessible",
            app.imports.state.value.selections.any { it.scope == source && it.enabled && it.available })
        val originalAttempt = CreateAttemptStore(app, "calendar-known-empty-create-live-qualification-v2.db").use {
            requireNotNull(it.read(source, MutationId("synthetic-known-empty-live-create-$token")))
        }
        assertTrue("Never admit a new submission", originalAttempt.state == CreateAttemptState.OUTCOME_UNKNOWN)
        val submissionPort = ProtonAuthentication.createSubmissions(app)
        suspend fun observe(): RemoteChange.Upsert {
            val observation = submissionPort.inspect(originalAttempt)
            assertTrue("Authenticated known-empty fixture readback is required", observation is CreateSubmissionObservation.Verified)
            return (observation as CreateSubmissionObservation.Verified).event
        }
        val base = observe()
        assertTrue("Keep the recognizable simple empty-detail fixture",
            base.content.title == "Calendeer QA — création sans rappels $token" &&
                base.content.details == EventDetails() && base.content.recurrence == null)
        val production = app.imports.store
        assertTrue("Require an acknowledged imported baseline", production.projectedBaseline(source, base.eventId) == base)
        assertTrue("Never capture into the production journal", production.localEdit(source, base.eventId) == null)
        val provider = AndroidCalendarProjection(app)
        assertTrue("Calendar permission is required", provider.hasPermissions())
        val witness = provider.inspectOwnedEvent(source, base.eventId, base.revision)
        assertTrue("Require the existing owned Android projection", witness != null)
        val remote = ProtonAuthentication.events(app)
        val format = remote.format
        val scope = source.copy(generation = 0)
        val intent = LocalEditIntent(MutationId("synthetic-local-title-${UUID.randomUUID()}"), scope,
            format, base, base.content.copy(title = "Calendeer QA — prepared title only"))
        val timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val name = "title-live-preparation-${UUID.randomUUID()}.db"
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val schedules = AtomicInteger()
        val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        val noWrites = object : CalendarProjection {
            override var admitOperation: () -> Boolean = { false }
            override fun hasPermissions() = provider.hasPermissions()
            override fun accountFor(accountId: AccountId) = Account("synthetic-title-live", "fixture")
            override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar = error("No Provider writes")
            override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No Provider writes")
        }
        var retained: LocalEditPreparation? = null
        try {
            ImportStore(app, name).use { store ->
                store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic qualification")))
                store.setEnabled(scope, true)
                store.transaction(scope) { putRemote(base); putProjection(ProjectionIntent(scope, base)); checkpoint = PageCheckpoint(scope, format) }
                assertTrue("Seed only the isolated historical baseline", store.acknowledge(ProjectionIntent(scope, base)) == 1)
                assertTrue("Capture only into the isolated journal", store.captureLocalEdit(intent) == intent)
                val owner = ImportCoordinator(app, store, remote, noWrites, jobs, scheduler)
                var calls = 0
                val actualPort = ProtonAuthentication.localEditPreparations(app)
                val counted = object : CalendarLocalEditPreparationPort {
                    override val localTitleFormat get() = actualPort.localTitleFormat
                    override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant): LocalEditPreparation {
                        calls++; return actualPort.prepareLocalTitle(intent, timestamp)
                    }
                }
                try {
                    owner.authenticationChanged(true)
                    withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
                    val result = owner.prepareLocalTitle(scope, base.eventId, intent.id, timestamp, counted)
                    assertTrue("Real title preparation must be retained: ${result.name}", result == LocalTitlePreparationResult.RETAINED)
                    assertTrue("Replay stays historical without fresh preparation",
                        owner.prepareLocalTitle(scope, base.eventId, intent.id, timestamp.plusSeconds(60), counted) == LocalTitlePreparationResult.HISTORICAL && calls == 1)
                    // The owner's transaction lease is closed after each command. Inspect through
                    // an independent helper rather than reopening that command's admission.
                    ImportStore(app, name).use { observer ->
                        retained = observer.localEditPreparation(scope, base.eventId)
                        assertTrue("Keep the exact paused owner", observer.localEdit(scope, base.eventId) == intent)
                    }
                } finally { owner.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin() }
            }
            ImportStore(app, name).use { reopened ->
                val recovered = reopened.localEditPreparation(scope, base.eventId)
                assertTrue("Keep the exact first protected record across helpers", recovered != null && recovered == retained &&
                    recovered.intent == intent && recovered.timestamp == timestamp)
                assertTrue("Fresh Keystore opening must recover valid original and renewed native signatures",
                    ProtonLocalTitlePreparationProbe.verifyRecovered(app, requireNotNull(recovered)))
                assertTrue("The sealed record must refuse a different local intent",
                    !ProtonLocalTitlePreparationProbe.verifyRecovered(app, recovered.copy(intent =
                        intent.copy(content = intent.content.copy(title = "Different local expectation")))))
                val damaged = recovered.protectedPayload.toCharArray().apply {
                    val index = size / 2
                    this[index] = if (this[index] == 'A') 'B' else 'A'
                }
                assertTrue("The sealed record must refuse damaged ciphertext",
                    !ProtonLocalTitlePreparationProbe.verifyRecovered(app,
                        recovered.copy(protectedPayload = String(damaged))))
                assertTrue("Keep the owner and historical baseline", reopened.localEdit(scope, base.eventId) == intent &&
                    reopened.projectedBaseline(scope, base.eventId) == base && reopened.pending(scope).isEmpty())
            }
            assertTrue("A second authenticated read must retain the complete original event", observe() == base)
            assertTrue("Production baseline and edit journal must remain unchanged",
                production.projectedBaseline(source, base.eventId) == base && production.localEdit(source, base.eventId) == null)
            assertTrue("Retain the original Android identity", provider.inspectOwnedEvent(source, base.eventId, base.revision) == witness)
            assertTrue("Keep authenticated session and selections", app.imports.state.value.authenticated &&
                app.imports.state.value.selections.map { it.scope to it.enabled } == beforeSelections)
            assertTrue("Keep the non-resendable CREATE journal unchanged",
                CreateAttemptStore(app, "calendar-known-empty-create-live-qualification-v2.db").use { it.read(originalAttempt.scope, originalAttempt.id) } == originalAttempt)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putBoolean("real_title_preparation_retained_and_recovered", true)
                putBoolean("remote_fixture_and_provider_identity_unchanged", true)
                putBoolean("outgoing_update_enabled", false)
            })
        } finally { jobs.coroutineContext[Job]!!.cancelAndJoin(); app.deleteDatabase(name) }
    }
}
