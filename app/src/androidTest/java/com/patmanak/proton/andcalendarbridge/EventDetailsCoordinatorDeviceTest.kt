package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated provider account, real durable coordinator, deterministic remote; no Proton traffic. */
@RunWith(AndroidJUnit4::class)
class EventDetailsCoordinatorDeviceTest {
    private val format = AdapterFormat("details-coordinator-pages", 1)
    private val deltaFormat = AdapterFormat("details-coordinator-delta", 1)
    private val oldMapping = AdapterFormat("details-coordinator-content", 1)
    private val newMapping = oldMapping.copy(version = 2)
    private val day = LocalDate.of(2030, 1, 1)
    private val legacy = RemoteChange.Upsert(EventId("prefix"), Revision("mapping-one"),
        EventContent("Earlier mapping fixture", EventTime.AllDay(day, day.plusDays(1))))
    private val remapped = legacy.copy(revision = Revision("mapping-two"), content = legacy.content.copy(
        details = EventDetails(reminders = listOf(EventReminder(15)))))
    private val tail = remapped.copy(eventId = EventId("tail"), content = remapped.content.copy(title = "Later mapping fixture"))

    private inner class Fixture(val context: Context, val target: CalendarScope, val name: String,
        val projection: AndroidCalendarProjection, var store: ImportStore) {
        val requests = mutableListOf<String?>()
        /** Baseline request count at each feed check proves ordering after the complete rescan. */
        val feedChecks = mutableListOf<Int>()
        var allowTransport = true
        private val scheduled = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { scheduled.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        private val remote = object : CalendarDeltaRemote, ContentMappingRemote {
            override val format = this@EventDetailsCoordinatorDeviceTest.format
            override val deltaFormat = this@EventDetailsCoordinatorDeviceTest.deltaFormat
            override val contentMapping = newMapping
            override suspend fun currentAccount() = target.account
            override suspend fun dispatch(mutation: Mutation) = DispatchResult.Rejected
            override suspend fun fetch(request: PageRequest): RemotePage = error("Unexpected legacy fetch")
            override suspend fun receive(request: PageRequest): ReceivedCalendarPage {
                check(allowTransport) { "Offline fixture attempted transport" }
                requests.add(request.checkpoint.cursor?.value)
                return ReceivedCalendarPage(request, "sealed-fixture")
            }
            override suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage {
                val values = if (received.request.checkpoint.cursor == null) listOf(remapped, tail) else listOf(tail)
                return DecodedCalendarPage(RemotePage(target, format, values, null, true))
            }
            override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? = null
            override suspend fun latestChanges(scope: CalendarScope): OpaqueCursor = error("Existing delta anchor must be retained")
            override suspend fun receiveChanges(scope: CalendarScope, after: OpaqueCursor): ReceivedChangePage {
                check(allowTransport) { "Offline fixture attempted change-feed transport" }
                feedChecks.add(requests.size)
                return ReceivedChangePage(scope, after, "sealed-change-fixture")
            }
            override suspend fun decodeChanges(received: ReceivedChangePage) = DecodedChangePage(
                received.scope, received.after,
                OpaqueCursor(deltaFormat, (received.after.value.toInt() + 1).toString()),
                more = false, reconciliationRequired = false, notices = emptyList())
            override suspend fun receiveEvent(scope: CalendarScope, eventId: EventId): ReceivedCalendarEvent =
                error("No individual event obligation expected")
            override suspend fun decodeEvent(received: ReceivedCalendarEvent): DecodedCalendarEvent =
                error("No individual event receipt expected")
            override suspend fun refreshContext(scope: CalendarScope) {
                check(allowTransport) { "Offline fixture attempted context transport" }
            }
        }
        private var taskScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var coordinator = ImportCoordinator(context, store, remote, projection, taskScope, scheduler)
            private set
        suspend fun start() {
            val before = scheduled.get()
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (scheduled.get() == before) delay(10) }
        }
        suspend fun reopen() {
            coordinator.authenticationChanged(false)
            taskScope.coroutineContext[Job]!!.cancelAndJoin()
            store.close()
            store = ImportStore(context, name)
            taskScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            coordinator = ImportCoordinator(context, store, remote, projection, taskScope, scheduler)
            start()
        }
        suspend fun selection() = store.selections(target.account).single()
        suspend fun assertWaitingForRescan() {
            assertEquals("PENDING", selection().status)
            assertNull(selection().completedAt)
            assertTrue(store.mappingRescanPending(target))
            assertTrue(store.complete(target))
            assertTrue(store.pending(target).isEmpty())
            assertEquals(legacy, store.transaction(target) { remote(legacy.eventId) })
        }
        suspend fun finish() {
            coordinator.authenticationChanged(false)
            taskScope.coroutineContext[Job]!!.cancelAndJoin()
            projection.admitOperation = { true }
            projection.removeOwnedCalendar(target)
            AccountManager.get(context).removeAccountExplicitly(projection.accountFor(target.account))
            context.getSharedPreferences("calendar-provider-mappings", Context.MODE_PRIVATE).edit()
                .remove(projection.accountFor(target.account).name + ":" + target.calendar.value).commit()
            store.close(); context.deleteDatabase(name)
        }
    }

    private fun fixture(received: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val projection = AndroidCalendarProjection(context)
        assumeTrue(projection.hasPermissions())
        val id = UUID.randomUUID().toString()
        val target = CalendarScope(AccountId("details-coordinator-$id"), CalendarId("fixture"), 0)
        val name = "details-coordinator-$id.db"
        val seed = ImportStore(context, name)
        seed.remember(target.account, listOf(CalendarSummary(target.calendar, "Mapping migration fixture")))
        seed.setEnabled(target, true)
        seed.ensureMappingProfile(target, oldMapping); seed.beginRefresh(target)
        val oldTail = PageCheckpoint(target, format, OpaqueCursor(format, "old-tail"))
        seed.transaction(target) {
            putRemote(legacy); checkpoint = oldTail
            (this as DeltaSyncTransaction).apply {
                deltaCursor = OpaqueCursor(deltaFormat, "0"); deltaMore = false
            }
            if (received) (this as StagedSyncTransaction).received =
                ReceivedCalendarPage(PageRequest(oldTail, 100), "sealed-fixture")
        }
        seed.close()
        val fixture = Fixture(context, target, name, projection, ImportStore(context, name))
        try { fixture.start(); fixture.block() } finally { fixture.finish() }
    }

    @Test fun upgradedMappingFinishesOldTraversalThenAutomaticallyRescansOnNonFreshContinuation() = fixture {
        assertFalse(store.transaction(target) { (this as DeltaSyncTransaction).deltaMore })
        assertEquals(ImportRunResult.CONTINUE, coordinator.run(network = true, fresh = false))
        assertEquals(listOf("old-tail"), requests)
        assertWaitingForRescan()
        reopen()
        assertTrue(store.mappingRescanPending(target))
        val beforeRescan = feedChecks.size
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(network = true, fresh = false))
        assertEquals(listOf("old-tail", null), requests)
        assertTrue("A mapper rescan must rearm and drain the previously drained change feed", feedChecks.size > beforeRescan)
        assertEquals(2, feedChecks.last())
        assertFalse(store.transaction(target) { (this as DeltaSyncTransaction).deltaMore })
        assertFalse(store.mappingRescanPending(target))
        assertEquals("IMPORTED", selection().status); assertNotNull(selection().completedAt)
        assertEquals(remapped, store.transaction(target) { remote(legacy.eventId) })
        assertTrue(store.pending(target).isEmpty())
        // Repeating an ordinary continuation must not admit yet another complete traversal.
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(network = true, fresh = false))
        assertEquals(listOf("old-tail", null), requests)
        assertEquals(beforeRescan + 1, feedChecks.size)
    }

    @Test fun offlineOldReceiptFinishesWithoutTransportOrSpinButCannotPublishMappingCompletion() = fixture(received = true) {
        allowTransport = false
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(network = false, fresh = false))
        assertTrue(requests.isEmpty()); assertTrue(feedChecks.isEmpty()); assertWaitingForRescan()
        reopen()
        repeat(2) {
            assertEquals(ImportRunResult.COMPLETE, coordinator.run(network = false, fresh = false))
            assertWaitingForRescan()
        }
        assertTrue(requests.isEmpty()); assertTrue(feedChecks.isEmpty())
        allowTransport = true
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(network = true, fresh = false))
        assertEquals(listOf<String?>(null), requests)
        assertEquals(listOf(1), feedChecks)
        assertFalse(store.transaction(target) { (this as DeltaSyncTransaction).deltaMore })
        assertFalse(store.mappingRescanPending(target))
        assertEquals("IMPORTED", selection().status); assertNotNull(selection().completedAt)
        assertEquals(remapped, store.transaction(target) { remote(legacy.eventId) })
    }
}
