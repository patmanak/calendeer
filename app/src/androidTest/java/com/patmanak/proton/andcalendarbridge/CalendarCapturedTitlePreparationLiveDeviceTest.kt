// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
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

/** Real Provider capture and authenticated preparation; only the Provider namespace is isolated. */
@RunWith(AndroidJUnit4::class)
class CalendarCapturedTitlePreparationLiveDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun isolatedAndroidTitleCapturesPreparesAndRecoversWithoutUpload() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("calendarCapturedTitlePreparation") == "true")
        val token = args.getString("createFixtureToken").orEmpty()
        val identityHash = args.getString("createAccountHash").orEmpty()
        assertTrue("An explicit retained fixture and account are required",
            token.matches(Regex("[a-f0-9]{12}")) && identityHash.matches(Regex("[a-f0-9]{64}")))
        val app = compose.activity.application as BridgeApplication
        compose.waitUntil(60_000) { app.imports.state.value.authenticated &&
            app.imports.state.value.selections.isNotEmpty() && !app.imports.state.value.busy }
        val prefs = app.getSharedPreferences("known-empty-create-live-qualification-v2", Context.MODE_PRIVATE)
        assertTrue("Only a previously submitted retained fixture can be read",
            prefs.getString("token", null) == token && prefs.getBoolean("submission_requested", false))
        val source = CalendarScope(AccountId(requireNotNull(prefs.getString("account", null))),
            CalendarId(requireNotNull(prefs.getString("calendar", null))), prefs.getLong("generation", -1))
        assertTrue("Authorize the exact account before event reads",
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
            val result = submissionPort.inspect(originalAttempt)
            assertTrue("Require authenticated known-empty fixture readback", result is CreateSubmissionObservation.Verified)
            return (result as CreateSubmissionObservation.Verified).event
        }
        val base = observe()
        assertTrue("Require the retained simple empty-detail fixture",
            base.content.title == "Calendeer QA — création sans rappels $token" &&
                base.content.details == EventDetails() && base.content.recurrence == null)
        val production = app.imports.store
        assertTrue("Require the acknowledged production baseline without a captured edit",
            production.projectedBaseline(source, base.eventId) == base && production.localEdit(source, base.eventId) == null)
        val provider = AndroidCalendarProjection(app)
        val native = AndroidCalendarProjection(app)
        assertTrue("Calendar permission is required", provider.hasPermissions())
        val originalWitness = provider.inspectOwnedEvent(source, base.eventId, base.revision)
        assertTrue("Require the existing production projection", originalWitness != null)
        fun snapshot(row: Long, calendar: Long): ContentValues = app.contentResolver.query(Events.CONTENT_URI, null,
            "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(row.toString(), calendar.toString()), null)!!.use {
            assertTrue("Require exactly one isolated or production row", it.count == 1 && it.moveToFirst())
            ContentValues().apply { DatabaseUtils.cursorRowToContentValues(it, this) }
        }
        val originalRow = snapshot(originalWitness!!.eventRow, originalWitness.calendarRow)
        val existingAccount = native.accountFor(source.account)
        assertTrue("Never create or remove the retained Android account",
            AccountManager.get(app).getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == existingAccount })
        fun calendars(): Set<Long> = app.contentResolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID), "${CalendarContract.Calendars.ACCOUNT_NAME}=? AND ${CalendarContract.Calendars.ACCOUNT_TYPE}=?",
            arrayOf(existingAccount.name, existingAccount.type), null)!!.use {
            buildSet { while (it.moveToNext()) add(it.getLong(0)) }
        }
        val originalCalendars = calendars()
        val scope = source.copy(generation = 0)
        val isolatedScope = source.copy(generation = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1))
        assertTrue("The private Provider namespace must differ from every selected scope",
            isolatedScope != scope && beforeSelections.none { it.first == isolatedScope })
        val remote = ProtonAuthentication.events(app)
        val format = remote.format
        val intent = LocalEditIntent(MutationId("synthetic-captured-title-${UUID.randomUUID()}"), scope,
            format, base, base.content.copy(title = "Calendeer QA — captured Android title"))
        val timestamp = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val name = "captured-title-live-${UUID.randomUUID()}.db"
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val schedules = AtomicInteger()
        val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        var captures = 0
        // Preserve the real account/calendar for authenticated reads, but capture from a
        // separate native Provider generation. No synthetic snapshot is returned by this port.
        val isolatedProjection = object : CalendarProjection {
            override var admitOperation: () -> Boolean
                get() = native.admitOperation
                set(value) { native.admitOperation = value }
            override fun hasPermissions() = native.hasPermissions()
            override fun accountFor(accountId: AccountId) = native.accountFor(accountId)
            override suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
                assertTrue("Only the exact isolated capture is authorized", scope == intent.scope && base == intent.base)
                captures++
                return native.captureOwnedSimpleEdit(isolatedScope, base)
            }
            override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar = error("No coordinator projection writes")
            override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) = error("No coordinator projection writes")
        }
        val actualPort = ProtonAuthentication.localEditPreparations(app)
        var preparations = 0
        val counted = object : CalendarLocalEditPreparationPort {
            override val localTitleFormat get() = actualPort.localTitleFormat
            override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant): LocalEditPreparation {
                preparations++
                return actualPort.prepareLocalTitle(intent, timestamp)
            }
        }
        var created = false
        var calendar = 0L
        try {
            native.admitOperation = { true }
            val ensured = native.ensureCalendarState(isolatedScope, CalendarSummary(isolatedScope.calendar, "Calendeer QA — temporary title capture"))
            calendar = ensured.id
            created = ensured.created && calendar !in originalCalendars && calendar != originalWitness.calendarRow
            assertTrue("Never reuse a previously existing Provider calendar", created)
            native.apply(ProjectionIntent(isolatedScope, base))
            val row = app.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?",
                arrayOf(calendar.toString()), null)!!.use {
                assertTrue("Require the one seeded native row", it.count == 1 && it.moveToFirst()); it.getLong(0)
            }
            fun edit(title: String) {
                val uri = Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, existingAccount.name)
                    .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, existingAccount.type).build()
                assertTrue("Edit only the exact temporary row", app.contentResolver.update(uri,
                    ContentValues().apply { put(Events.TITLE, title); put(Events.DIRTY, 1) },
                    "${Events._ID}=? AND ${Events.CALENDAR_ID}=?", arrayOf(row.toString(), calendar.toString())) == 1)
            }
            edit(intent.content.title)
            ImportStore(app, name).use { store ->
                store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic qualification")))
                store.setEnabled(scope, true)
                store.transaction(scope) { putRemote(base); putProjection(ProjectionIntent(scope, base)); checkpoint = PageCheckpoint(scope, format) }
                assertTrue("Seed only the isolated acknowledged baseline", store.acknowledge(ProjectionIntent(scope, base)) == 1)
                val owner = ImportCoordinator(app, store, remote, isolatedProjection, jobs, scheduler)
                try {
                    owner.authenticationChanged(true)
                    withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
                    val result = owner.captureAndPrepareLocalTitle(scope, base.eventId, intent.id, timestamp, counted)
                    assertTrue("Real capture and preparation must retain a capsule: ${result.name}", result == LocalTitlePreparationResult.RETAINED)
                    val record = ImportStore(app, name).use { observer ->
                        val saved = requireNotNull(observer.localEditPreparation(scope, base.eventId))
                        assertTrue("Require the exact Provider-derived intent", saved.intent == intent && saved.timestamp == timestamp &&
                            observer.localEdit(scope, base.eventId) == intent)
                        saved
                    }
                    assertTrue("Capture does not acknowledge Android", snapshot(row, calendar).getAsInteger(Events.DIRTY) == 1)
                    edit("Calendeer QA — later Android title")
                    assertTrue("Replay is historical without fresh capture or preparation",
                        owner.captureAndPrepareLocalTitle(scope, base.eventId, intent.id, timestamp.plusSeconds(60), counted) ==
                            LocalTitlePreparationResult.HISTORICAL && captures == 1 && preparations == 1)
                    ImportStore(app, name).use { reopened ->
                        val recovered = requireNotNull(reopened.localEditPreparation(scope, base.eventId))
                        assertTrue("Retain the exact first capsule and acknowledged baseline", recovered == record &&
                            reopened.localEdit(scope, base.eventId) == intent && reopened.projectedBaseline(scope, base.eventId) == base &&
                            reopened.pending(scope).isEmpty())
                        assertTrue("Fresh protected recovery verifies original and renewed native signatures",
                            ProtonLocalTitlePreparationProbe.verifyRecovered(app, recovered))
                        assertTrue("The capsule refuses a substituted local intent", !ProtonLocalTitlePreparationProbe.verifyRecovered(app,
                            recovered.copy(intent = intent.copy(content = intent.content.copy(title = "Different expectation")))))
                    }
                    val later = snapshot(row, calendar)
                    assertTrue("Keep the newer Android title dirty", later.getAsString(Events.TITLE) == "Calendeer QA — later Android title" &&
                        later.getAsInteger(Events.DIRTY) == 1)
                    assertTrue("All coordinator admission is closed", !isolatedProjection.admitOperation())
                } finally { owner.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin() }
            }
            assertTrue("An authenticated read must retain the entire original remote event", observe() == base)
            assertTrue("Keep the production baseline and absent edit unchanged",
                production.projectedBaseline(source, base.eventId) == base && production.localEdit(source, base.eventId) == null)
            assertTrue("Keep the original Android identity and complete Provider row",
                provider.inspectOwnedEvent(source, base.eventId, base.revision) == originalWitness &&
                    snapshot(originalWitness.eventRow, originalWitness.calendarRow) == originalRow)
            assertTrue("Keep the retained session and selections", app.imports.state.value.authenticated &&
                app.imports.state.value.selections.map { it.scope to it.enabled } == beforeSelections)
            assertTrue("Keep the non-resendable CREATE journal unchanged",
                CreateAttemptStore(app, "calendar-known-empty-create-live-qualification-v2.db").use { it.read(originalAttempt.scope, originalAttempt.id) } == originalAttempt)
        } finally {
            withContext(NonCancellable) {
                jobs.coroutineContext[Job]!!.cancelAndJoin()
                try {
                    if (created) {
                        native.admitOperation = { true }
                        native.removeOwnedCalendar(isolatedScope)
                        app.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
                            "${CalendarContract.Calendars._ID}=?", arrayOf(calendar.toString()), null)!!.use {
                            assertTrue("Only the isolated calendar is removed", it.count == 0)
                        }
                    }
                } finally { app.deleteDatabase(name) }
            }
        }
        assertTrue("Keep the original Android account and projection after cleanup",
            AccountManager.get(app).getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == existingAccount } &&
                snapshot(originalWitness.eventRow, originalWitness.calendarRow) == originalRow && calendars() == originalCalendars)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("real_android_capture_and_title_preparation_recovered", true)
            putBoolean("historical_replay_preserves_newer_android_edit", true)
            putBoolean("remote_and_production_provider_unchanged", true)
            putBoolean("isolated_provider_cleanup_complete", true)
            putBoolean("outgoing_update_enabled", false)
        })
    }
}
