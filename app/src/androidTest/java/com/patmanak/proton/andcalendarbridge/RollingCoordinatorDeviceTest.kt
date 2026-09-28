package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.Context
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real coordinator/store/provider; deterministic dates and synthetic remote, no Proton traffic. */
@RunWith(AndroidJUnit4::class)
class RollingCoordinatorDeviceTest {
    private class Remote(val target: CalendarScope) : CalendarDeltaRemote, RollingCalendarRemote {
        override val format = AdapterFormat("rolling-fixture", 1)
        override val deltaFormat = AdapterFormat("rolling-delta", 1)
        var today = LocalDate.of(2030, 1, 1)
        var zone = ZoneId.of("Europe/Paris")
        var keys = true
        var reset = false
        var feedReads = 0
        var onPlanned: (() -> Unit)? = null
        val queries = mutableListOf<CoverageInterval>()
        val contents = mutableListOf<RemoteChange.Upsert>()
        override suspend fun currentAccount() = target.account
        override suspend fun dispatch(mutation: Mutation) = DispatchResult.Rejected
        override suspend fun fetch(request: PageRequest): RemotePage = error("Unused")
        override suspend fun queryHorizon(scope: CalendarScope) = CoverageInterval(today.minusDays(90), today.plusDays(366), zone)
        private fun cursor(plan: List<CoverageInterval>, index: Int, type: Int) = OpaqueCursor(format,
            plan.joinToString(";") { "${it.start},${it.endExclusive},${it.zone.id}" } + "#$index#$type")
        private fun position(value: String): Triple<List<CoverageInterval>, Int, Int> {
            val parts = value.split('#')
            val plan = parts[0].split(';').map { entry -> entry.split(',').let {
                CoverageInterval(LocalDate.parse(it[0]), LocalDate.parse(it[1]), ZoneId.of(it[2]))
            } }
            return Triple(plan, parts[1].toInt(), parts[2].toInt())
        }
        override suspend fun planWindows(scope: CalendarScope, windows: List<CoverageInterval>, limit: Int): PageCheckpoint {
            onPlanned?.invoke()
            return PageCheckpoint(scope, format, cursor(windows, 0, 0))
        }
        override suspend fun receive(request: PageRequest): ReceivedCalendarPage {
            val encoded = request.checkpoint.cursor ?: cursor(listOf(queryHorizon(target)), 0, 0)
            val (plan, index, type) = position(encoded.value)
            queries += plan[index]
            return ReceivedCalendarPage(request, encoded.value, PageCoverage(plan, index, type == 0, type == 3))
        }
        override suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage {
            if (!keys) throw RemoteImportException(RemoteImportFailure.LOCAL_KEYS)
            val (plan, index, type) = position(received.protectedPayload)
            val window = plan[index]
            val matched = if (type != 0) emptyList() else contents.filter { event ->
                when (val time = event.content.time) {
                    is EventTime.AllDay -> time.start < window.endExclusive && time.endExclusive > window.start
                    is EventTime.Timed -> time.start < window.endExclusive.atStartOfDay(window.zone).toInstant() &&
                        time.end > window.start.atStartOfDay(window.zone).toInstant()
                }
            }
            val next = when {
                type < 3 -> cursor(plan, index, type + 1)
                index < plan.lastIndex -> cursor(plan, index + 1, 0)
                else -> null
            }
            return DecodedCalendarPage(RemotePage(target, format, matched, next, next == null), coverage = received.coverage)
        }
        override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? = null
        override suspend fun latestChanges(scope: CalendarScope) = OpaqueCursor(deltaFormat, "anchor")
        override suspend fun refreshContext(scope: CalendarScope) = Unit
        override suspend fun receiveChanges(scope: CalendarScope, after: OpaqueCursor): ReceivedChangePage {
            feedReads++
            return ReceivedChangePage(scope, after, if (reset) "reset" else "no-changes")
        }
        override suspend fun decodeChanges(received: ReceivedChangePage): DecodedChangePage {
            val lost = received.protectedPayload == "reset"
            if (lost) reset = false
            return DecodedChangePage(target, received.after,
                if (lost) OpaqueCursor(deltaFormat, received.after.value + "-reset") else received.after,
                false, lost, emptyList())
        }
        override suspend fun receiveEvent(scope: CalendarScope, eventId: EventId): ReceivedCalendarEvent = error("No remote changes")
        override suspend fun decodeEvent(received: ReceivedCalendarEvent): DecodedCalendarEvent = error("No remote changes")
    }
    private class Fixture(val context: Context, val target: CalendarScope, val store: ImportStore,
        val projection: AndroidCalendarProjection, val remote: Remote, var coordinator: ImportCoordinator,
        val recreate: () -> ImportCoordinator) {
        val summary = CalendarSummary(target.calendar, "Calendar Bridge rolling fixture")
        suspend fun selection() = store.selections(target.account).single()
        suspend fun ledger() = store.transaction(target) { (this as StagedSyncTransaction).coverage }
        suspend fun finish(fresh: Boolean = true) {
            var result = coordinator.run(true, fresh)
            repeat(20) {
                if (result == ImportRunResult.COMPLETE) return
                result = coordinator.run(true, false)
            }
            fail("Bounded fixture did not finish")
        }
        suspend fun rows(): Map<String, Long> {
            val id = projection.ensureCalendar(target, summary)
            return context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID, Events.TITLE),
                "${Events.CALENDAR_ID}=?", arrayOf(id.toString()), null)!!.use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getString(1), cursor.getLong(0)) }
            }
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val projection = AndroidCalendarProjection(context)
        assumeTrue(projection.hasPermissions())
        val id = UUID.randomUUID().toString()
        val target = CalendarScope(AccountId("rolling-fixture-$id"), CalendarId("fixture"), 0)
        val name = "rolling-fixture-$id.db"
        val store = ImportStore(context, name)
        val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val remote = Remote(target)
        val schedules = AtomicInteger()
        val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { schedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        fun create() = ImportCoordinator(context, store, remote, projection, coroutineScope, scheduler)
        val fixture = Fixture(context, target, store, projection, remote, create(), ::create)
        try {
            store.remember(target.account, listOf(fixture.summary)); store.setEnabled(target, true)
            fixture.coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (schedules.get() == 0) delay(10) }
            fixture.block()
        } finally {
            fixture.coordinator.authenticationChanged(false); coroutineScope.cancel()
            projection.admitOperation = { true }
            projection.removeOwnedCalendar(target)
            AccountManager.get(context).removeAccountExplicitly(projection.accountFor(target.account))
            context.getSharedPreferences("calendar-provider-mappings", Context.MODE_PRIVATE).edit()
                .remove(projection.accountFor(target.account).name + ":" + target.calendar.value).commit()
            store.close(); context.deleteDatabase(name)
        }
    }

    @Test fun sameDateAvoidsRescanAndUnchangedTimedAllDayAndLongEventsEnterNextDay() = fixture {
        val edge = remote.today.plusDays(366)
        fun event(id: String, time: EventTime) = RemoteChange.Upsert(EventId(id), Revision("unchanged"), EventContent(id, time))
        remote.contents += event("retained", EventTime.AllDay(remote.today, remote.today.plusDays(1)))
        remote.contents += event("entering-all-day", EventTime.AllDay(edge, edge.plusDays(1)))
        remote.contents += event("entering-timed", EventTime.Timed(edge.atStartOfDay(remote.zone).toInstant().plusSeconds(3600),
            edge.atStartOfDay(remote.zone).toInstant().plusSeconds(7200), remote.zone))
        remote.contents += event("long-overlap", EventTime.AllDay(edge.minusDays(2), edge.plusDays(3)))
        finish()
        val original = rows()
        assertEquals(setOf("retained", "long-overlap"), original.keys)
        val epoch = ledger().epoch
        remote.queries.clear()
        val feeds = remote.feedReads
        finish()
        assertTrue("Same-date refresh must not repeat event-window requests", remote.queries.isEmpty())
        assertTrue(remote.feedReads > feeds)
        assertEquals(epoch, ledger().epoch)
        remote.today = remote.today.plusDays(1)
        finish()
        assertEquals(List(4) { CoverageInterval(edge, edge.plusDays(1), remote.zone) }, remote.queries)
        assertEquals(4, rows().size)
        original.forEach { (title, id) -> assertEquals(id, rows()[title]) }
        assertEquals(edge.plusDays(1), ledger().observed!!.interval.endExclusive)
        assertEquals("IMPORTED", selection().status)
    }

    @Test fun interruptedExtensionResumesFrozenPlanOfflineThenDrainsTheFeed() = fixture {
        finish()
        val edge = remote.today.plusDays(366)
        remote.today = remote.today.plusDays(1); remote.keys = false; remote.queries.clear()
        coordinator.run(true)
        assertEquals("LOCAL_KEYS", selection().status)
        assertEquals(1, remote.queries.size)
        val epoch = ledger().epoch
        coordinator.authenticationChanged(false)
        coordinator = recreate(); coordinator.authenticationChanged(true)
        remote.today = remote.today.plusDays(10); remote.zone = ZoneId.of("UTC"); remote.keys = true
        coordinator.run(false, false)
        assertEquals(1, remote.queries.size)
        finish(fresh = false)
        assertEquals(epoch, ledger().epoch)
        assertEquals(List(4) { CoverageInterval(edge, edge.plusDays(1), ZoneId.of("Europe/Paris")) }, remote.queries)
        assertEquals(edge.plusDays(1), ledger().observed!!.interval.endExclusive)
    }

    @Test fun resetDiscoveredAfterExtensionRequiresFullRescanAndKeepsHistoryGap() = fixture {
        finish()
        val full = remote.queryHorizon(target)
        remote.today = remote.today.plusDays(1); remote.reset = true; remote.queries.clear()
        assertEquals(ImportRunResult.CONTINUE, coordinator.run(true))
        assertNull(ledger().observed)
        assertTrue(selection().historyGap)
        assertEquals("PENDING", selection().status)
        finish(false)
        assertEquals(8, remote.queries.size)
        assertEquals(full.copy(start = full.start.plusDays(1), endExclusive = full.endExclusive.plusDays(1)), remote.queries.last())
        assertEquals("HISTORY_GAP", selection().status)
    }

    @Test fun changedTimezoneAndKnownDeferredWorkRequireFullScan() = fixture {
        finish(); remote.queries.clear(); remote.zone = ZoneId.of("UTC")
        finish()
        assertEquals(List(4) { remote.queryHorizon(target) }, remote.queries)
        remote.queries.clear()
        store.transaction(target) { (this as StagedSyncTransaction).defer(DeferredCalendarEvent(EventId("unsupported"),
            Revision("v1"), remote.format, DeferredEventReason.UNSUPPORTED_CONTENT, "sealed-fixture")) }
        finish()
        assertEquals(List(4) { remote.queryHorizon(target) }, remote.queries)
        assertEquals("PARTIAL", selection().status)
    }
}
