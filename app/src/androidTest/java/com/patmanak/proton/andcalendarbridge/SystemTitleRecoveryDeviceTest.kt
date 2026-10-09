// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.Account
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated preferences and real SQLite. The guarded native port below traps every event
 * operation: PREPARED/UNKNOWN-without-proof and bare reservations cannot reach the Provider.
 */
@RunWith(AndroidJUnit4::class)
class SystemTitleRecoveryDeviceTest {
    private val target = CalendarScope(AccountId("cursor-fixture-account"), CalendarId("cursor-fixture-calendar"), 0)
    private fun fixture(block: suspend (Context, String) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "title-cursor-fixture-${UUID.randomUUID()}"
        try { block(context, name) } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun cursorSurvivesReopeningAndScopeFramingDoesNotCollide() = fixture { context, name ->
        val scopes = listOf(target, target.copy(generation = 1), target.copy(account = AccountId("other")),
            target.copy(calendar = CalendarId("other")),
            CalendarScope(AccountId("a:b"), CalendarId("c"), 0), CalendarScope(AccountId("a"), CalendarId("b:c"), 0))
        val store = AndroidTitleRecoveryCursorStore(context, name)
        scopes.forEachIndexed { index, scope -> store.write(scope, TitleUpdateWorkCursor(scope, EventId("é-😀-$index"))) }
        val reopened = AndroidTitleRecoveryCursorStore(context, name)
        scopes.forEachIndexed { index, scope ->
            assertEquals(TitleUpdateWorkCursor(scope, EventId("é-😀-$index")), reopened.read(scope))
        }
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        assertEquals(scopes.size, preferences.all.size)
        assertTrue(preferences.all.keys.all { it.matches(Regex("v1-[a-f0-9]{64}")) })
        reopened.write(target, null)
        assertNull(AndroidTitleRecoveryCursorStore(context, name).read(target))
        scopes.drop(1).forEach { assertNotNull(reopened.read(it)) }
    }

    @Test fun incompatibleWritesDoNotReplaceAnExistingDurableCursor() = fixture { context, name ->
        val store = AndroidTitleRecoveryCursorStore(context, name)
        val original = TitleUpdateWorkCursor(target, EventId("original"))
        store.write(target, original)
        for (bad in listOf(original.copy(protocol = 2), original.copy(scope = target.copy(generation = 1)))) {
            try { store.write(target, bad); fail("Incompatible write must be rejected") } catch (_: IllegalArgumentException) { }
            assertEquals(original, AndroidTitleRecoveryCursorStore(context, name).read(target))
        }
    }

    @Test fun corruptStoredCursorFailsClosedAndControllerClearsOnlyThatScope() = fixture { context, name ->
        val store = AndroidTitleRecoveryCursorStore(context, name)
        val other = target.copy(calendar = CalendarId("other"))
        val cursor = TitleUpdateWorkCursor(target, EventId("synthetic-event"))
        store.write(target, cursor)
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val key = prefs.all.keys.single()
        val valid = checkNotNull(prefs.getString(key, null))
        store.write(other, TitleUpdateWorkCursor(other, EventId("keep")))
        val invalid = listOf("not-json", JSONObject(valid).put("protocol", 2).toString(),
            JSONObject(valid).put("scope", "foreign-scope").toString(),
            JSONObject(valid).put("event", "bad\nidentity").toString(), JSONObject(valid).put("extra", true).toString())
        for (value in invalid) {
            store.write(target, cursor)
            assertTrue(prefs.edit().putString(key, value).commit())
            try { store.read(target); fail("Corruption must not be treated as a fresh valid cursor") }
            catch (failure: IllegalStateException) { assertEquals("Invalid title recovery cursor", failure.message) }
            var calls = 0
            val controller = SystemTitleRecoveryController(store, { calls++; TitleUpdateOfflineRecoveryProgress() })
            assertEquals(SystemTitleRecoveryProgress(failed = true), controller.run(target) { true })
            assertEquals(0, calls); assertNull(store.read(target)); assertNotNull(store.read(other))
        }
    }

    @Test fun explicitResetIsDurableAndPreservesAnotherScopesCursor() = fixture { context, name ->
        val store = AndroidTitleRecoveryCursorStore(context, name)
        val other = target.copy(generation = 1)
        store.write(target, TitleUpdateWorkCursor(target, EventId("reset")))
        store.write(other, TitleUpdateWorkCursor(other, EventId("keep")))
        val controller = SystemTitleRecoveryController(store, { error("Reset cannot recover work") })
        controller.reset(target)
        assertNull(AndroidTitleRecoveryCursorStore(context, name).read(target))
        assertEquals(TitleUpdateWorkCursor(other, EventId("keep")), store.read(other))
    }

    @Test fun completedTraversalSurvivesReopeningAndOnlyFreshTriggerStartsItAgain() = fixture { context, name ->
        val cursors = AndroidTitleRecoveryCursorStore(context, name)
        val other = target.copy(generation = 1)
        val partial = TitleUpdateWorkCursor(other, EventId("partial"))
        cursors.write(other, partial)
        var calls = 0
        val controller = SystemTitleRecoveryController(cursors, { calls++; TitleUpdateOfflineRecoveryProgress(held = 1) })
        assertEquals(SystemTitleRecoveryProgress(), controller.run(target, fresh = true) { true })
        val reopened = AndroidTitleRecoveryCursorStore(context, name)
        assertTrue(reopened.exhausted(target)); assertNull(reopened.read(target))
        assertFalse(reopened.exhausted(other)); assertEquals(partial, reopened.read(other))
        val continued = SystemTitleRecoveryController(reopened, { calls++; TitleUpdateOfflineRecoveryProgress() })
        repeat(3) { assertEquals(SystemTitleRecoveryProgress(), continued.run(target, fresh = false) { true }) }
        assertEquals(1, calls)
        assertEquals(SystemTitleRecoveryProgress(), continued.run(target, fresh = true) { true })
        assertEquals(2, calls); assertTrue(reopened.exhausted(target))
        continued.reset(target)
        assertFalse(AndroidTitleRecoveryCursorStore(context, name).exhausted(target))
        assertEquals(partial, reopened.read(other))
    }

    @Test fun exhaustedMarkerAtomicallyReplacesCursorAndAnyExplicitWriteClearsIt() = fixture { context, name ->
        val cursor = TitleUpdateWorkCursor(target, EventId("previous"))
        val store = AndroidTitleRecoveryCursorStore(context, name)
        store.write(target, cursor)
        store.markExhausted(target)
        val reopened = AndroidTitleRecoveryCursorStore(context, name)
        assertTrue(reopened.exhausted(target)); assertNull(reopened.read(target))
        reopened.write(target, cursor)
        assertFalse(store.exhausted(target)); assertEquals(cursor, store.read(target))
        reopened.markExhausted(target); reopened.write(target, null)
        assertFalse(store.exhausted(target)); assertNull(store.read(target))
    }

    private class NoEventProjection : CalendarProjection {
        var eventCalls = 0
        override var admitOperation: () -> Boolean = { false }
        override val titleUpdateAcknowledgementProtocol = 1
        override fun hasPermissions() = true
        override fun accountFor(accountId: AccountId) = Account("synthetic-title-recovery", "fixture")
        override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar {
            eventCalls++; error("No calendar creation")
        }
        override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) {
            eventCalls++; error("No event projection")
        }
        override suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
            eventCalls++; error("Unproved work cannot capture native events")
        }
        override suspend fun acknowledgeTitleUpdate(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? {
            eventCalls++; error("Unproved work cannot acknowledge native events")
        }
        override suspend fun inspectTitleUpdateAcknowledgement(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? {
            eventCalls++; error("Unproved work cannot inspect native events")
        }
    }

    @Test fun reopenedPagesHoldPreparedUnknownAndBareReservationsWithoutNativeOrServiceCalls() = fixture { context, name ->
        val importName = "$name-import.db"
        val journalName = "$name-attempts.db"
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val projection = NoEventProjection()
        val importFormat = AdapterFormat("synthetic-title-recovery", 1)
        fun prepared(event: String): TitleUpdateAttemptRecord {
            val base = RemoteChange.Upsert(EventId(event), Revision("base"), EventContent("Original synthetic event",
                EventTime.AllDay(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 2)), details = EventDetails()))
            val intent = LocalEditIntent(MutationId("intent-$event"), target, importFormat, base, base.content.copy(title = "Changed synthetic title"))
            return TitleUpdateAttemptRecord(MutationId("attempt-$event"), LocalEditPreparation(intent,
                Instant.parse("2030-01-01T00:00:00Z"), AdapterFormat("synthetic-preparation", 1), "synthetic-protected-capsule"),
                AdapterFormat("synthetic-title-wire", 1), "synthetic-protected-wire")
        }
        val first = prepared("a")
        val second = prepared("b")
        val bare = TitleUpdatePreparationReservation.of(prepared("c"))
        try {
            ImportStore(context, importName).use { imports ->
                TitleUpdateAttemptStore(context, journalName).use { journal ->
                    imports.remember(target.account, listOf(CalendarSummary(target.calendar, "Synthetic recovery")))
                    imports.setEnabled(target, true)
                    imports.transaction(target) { checkpoint = PageCheckpoint(target, importFormat) }
                    journal.remember(first); journal.remember(second)
                    assertTrue(journal.claim(second) { it == target })
                    assertEquals(bare, journal.reservePreparation(bare) { it == target })
                    val unknown = checkNotNull(journal.read(target, second.id))
                    assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, unknown.state)
                    assertNull(journal.readVerified(target, second.id))
                    journal.admitDispatch = { error("Offline pages never claim dispatch") }
                    val schedules = AtomicInteger()
                    val scheduler = object : ImportScheduler {
                        override fun cancel() = Unit
                        override fun requestLocal() = Unit
                        override fun requestNetwork() { schedules.incrementAndGet() }
                        override fun continueWork(network: Boolean) = Unit
                    }
                    val remote = object : ReadOnlyCalendarRemote {
                        override val format = importFormat
                        override suspend fun currentAccount() = target.account
                        override suspend fun fetch(request: PageRequest): RemotePage = error("No network import")
                        override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No submission")
                    }
                    val owner = ImportCoordinator(context, imports, remote, projection, jobs, scheduler,
                        systemCreatesEnabled = { it == target })
                    try {
                        owner.authenticationChanged(true)
                        withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
                        val seen = mutableListOf<TitleUpdateWorkQuery>()
                        val recovery: suspend (TitleUpdateWorkQuery) -> TitleUpdateOfflineRecoveryProgress = { query ->
                            seen += query
                            owner.recoverLocalTitles(query, journal).also { assertEquals(1, it.held); assertEquals(0, it.failed) }
                        }
                        val cursors = AndroidTitleRecoveryCursorStore(context, name)
                        val controller = SystemTitleRecoveryController(cursors, recovery, pageSize = 1)
                        assertEquals(SystemTitleRecoveryProgress(more = true), controller.run(target) { true })
                        assertEquals(TitleUpdateWorkCursor(target, first.preparation.intent.base.eventId), cursors.read(target))
                        val reopened = SystemTitleRecoveryController(AndroidTitleRecoveryCursorStore(context, name), recovery, pageSize = 1)
                        assertEquals(SystemTitleRecoveryProgress(), reopened.run(target) { true })
                        assertNull(cursors.read(target)); assertEquals(2, seen.size); assertNotNull(seen.last().cursor)
                        assertEquals(SystemTitleRecoveryProgress(more = true), reopened.run(target) { true })
                        assertNull(seen.last().cursor)
                        assertEquals(first, journal.read(target, first.id)); assertEquals(unknown, journal.read(target, second.id))
                        assertEquals(bare, journal.readPreparationReservation(target, bare.eventId))
                        assertNull(journal.read(target, bare.id)); assertNull(journal.readVerified(target, second.id))
                        assertEquals(0, projection.eventCalls)
                    } finally { owner.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin() }
                }
            }
            TitleUpdateAttemptStore(context, journalName).use { reopened ->
                assertEquals(first, reopened.read(target, first.id))
                assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, reopened.read(target, second.id)!!.state)
                assertEquals(bare, reopened.readPreparationReservation(target, bare.eventId))
            }
        } finally {
            jobs.coroutineContext[Job]!!.cancelAndJoin()
            context.deleteDatabase(importName); context.deleteDatabase(journalName)
        }
    }
}
