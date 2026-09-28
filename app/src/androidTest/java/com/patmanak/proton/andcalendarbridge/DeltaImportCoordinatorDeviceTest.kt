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
import com.patmanak.proton.andcalendarbridge.sync.catalog.*
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated database and synthetic owned provider account. No real Proton transport or scheduler. */
@RunWith(AndroidJUnit4::class)
class DeltaImportCoordinatorDeviceTest {
    private class FakeDeltaRemote(private val target: CalendarScope) : CalendarDeltaRemote {
        override val format = AdapterFormat("coordinator-events-fixture", 1)
        override val deltaFormat = AdapterFormat("coordinator-delta-fixture", 1)
        val calls = mutableListOf<String>()
        val operations = mutableListOf<String>()
        var networkAvailable = true
        var localKeysAvailable = true
        var catalogFailure = false
        var contextFailure = false
        var contextReady = true
        var missingEvent = false
        var afterBaselineReceived: (() -> Unit)? = null
        val eventId = EventId("fixture-event")
        var current: RemoteChange.Upsert? = event("v1", "Calendar Bridge fixture original")
        private var revision = 0
        private var resetAt = -1
        private var receiptId = 0
        private val notices = mutableListOf<Pair<Int, CalendarChangeNotice>>()
        private val baselineReceipts = mutableMapOf<String, List<RemoteChange>>()
        private val changeReceipts = mutableMapOf<String, DecodedChangePage>()
        private val eventReceipts = mutableMapOf<String, RemoteChange.Upsert>()

        private fun event(version: String, title: String) = RemoteChange.Upsert(eventId, Revision(version),
            EventContent(title, EventTime.AllDay(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 2))))
        fun publishUpdate() {
            revision++
            current = event("update-$revision", "Calendar Bridge fixture updated")
            notices += revision to CalendarChangeNotice("change-$revision", CalendarNoticeKind.EVENT_CHANGED, eventId)
        }
        fun publishDelete(): RemoteChange.Deleted {
            revision++
            current = null
            val deleted = RemoteChange.Deleted(eventId, Revision("delete-$revision"))
            notices += revision to CalendarChangeNotice("change-$revision", CalendarNoticeKind.EVENT_DELETED, eventId, deleted.revision)
            return deleted
        }
        fun loseHistory() {
            revision++
            resetAt = revision
            current = null // A missing snapshot row is deliberately not a deletion notice.
        }
        private fun network(call: String) {
            calls += call
            operations += call
            check(networkAvailable) { "Unexpected network access during offline fixture" }
        }
        suspend fun loadCatalog(): CalendarCatalogResult {
            network("catalog")
            return if (catalogFailure) CalendarCatalogResult.Failure(CalendarCatalogFailure.UNREACHABLE)
            else CalendarCatalogResult.Success(target.account, listOf(CalendarSummary(target.calendar, "Calendar Bridge delta fixture")))
        }
        override suspend fun currentAccount() = target.account
        override suspend fun dispatch(mutation: Mutation) = DispatchResult.Rejected
        override suspend fun fetch(request: PageRequest): RemotePage = error("Unexpected legacy fetch")
        override suspend fun receive(request: PageRequest): ReceivedCalendarPage {
            network("baseline")
            val payload = "baseline-${++receiptId}"
            baselineReceipts[payload] = listOfNotNull(current)
            afterBaselineReceived?.let { afterBaselineReceived = null; it() }
            return ReceivedCalendarPage(request, payload)
        }
        override suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage {
            operations += "decode-baseline"
            check(contextReady) { "Context must be refreshed before decoding" }
            return DecodedCalendarPage(RemotePage(target, format, baselineReceipts.getValue(received.protectedPayload), null, true))
        }
        override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? = null
        override suspend fun latestChanges(scope: CalendarScope): OpaqueCursor {
            network("latest")
            return OpaqueCursor(deltaFormat, revision.toString())
        }
        override suspend fun receiveChanges(scope: CalendarScope, after: OpaqueCursor): ReceivedChangePage {
            network("changes")
            val payload = "changes-${++receiptId}"
            val reset = after.value.toInt() < resetAt
            changeReceipts[payload] = DecodedChangePage(target, after, OpaqueCursor(deltaFormat, revision.toString()),
                more = false, reconciliationRequired = reset, notices = if (reset)
                    listOf(CalendarChangeNotice("untrusted-reset-delete", CalendarNoticeKind.EVENT_DELETED, eventId, Revision("untrusted")))
                else notices.filter { it.first > after.value.toInt() }.map { it.second })
            return ReceivedChangePage(target, after, payload)
        }
        override suspend fun decodeChanges(received: ReceivedChangePage) = changeReceipts.getValue(received.protectedPayload)
        override suspend fun receiveEvent(scope: CalendarScope, eventId: EventId): ReceivedCalendarEvent {
            network("event")
            if (missingEvent) throw RemoteImportException(RemoteImportFailure.NOT_FOUND)
            val value = current ?: throw RemoteImportException(RemoteImportFailure.NOT_FOUND)
            val payload = "event-${++receiptId}"
            eventReceipts[payload] = value
            return ReceivedCalendarEvent(target, eventId, value.revision, format, payload)
        }
        override suspend fun decodeEvent(received: ReceivedCalendarEvent): DecodedCalendarEvent {
            operations += "decode-event"
            check(contextReady) { "Context must be refreshed before decoding" }
            if (!localKeysAvailable) throw RemoteImportException(RemoteImportFailure.LOCAL_KEYS)
            return DecodedCalendarEvent(change = eventReceipts.getValue(received.protectedPayload))
        }
        override suspend fun refreshContext(scope: CalendarScope) {
            network("context")
            if (contextFailure) throw RemoteImportException(RemoteImportFailure.NETWORK)
            contextReady = true
        }
    }

    private class CatalogDeltaRemote(private val delegate: FakeDeltaRemote) : CalendarDeltaRemote by delegate, CalendarCatalogPort {
        override suspend fun load() = delegate.loadCatalog()
    }

    private class Fixture(val context: Context, val target: CalendarScope, val store: ImportStore,
        val projection: AndroidCalendarProjection, val remote: FakeDeltaRemote, val coordinator: ImportCoordinator) {
        val summary = CalendarSummary(target.calendar, "Calendar Bridge delta fixture")
        suspend fun selection() = store.selections(target.account).single()
        suspend fun rows(): List<Pair<Long, String>> {
            val id = projection.ensureCalendar(target, summary)
            return context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID, Events.TITLE),
                "${Events.CALENDAR_ID}=?", arrayOf(id.toString()), Events._ID)!!.use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getLong(0) to cursor.getString(1)) }
            }
        }
        suspend fun <T> delta(action: DeltaSyncTransaction.() -> T): T =
            store.transaction(target) { action(this as DeltaSyncTransaction) }
        suspend fun catalog(available: Boolean) = coordinator.rememberCatalog(CalendarCatalogState(
            status = CalendarCatalogStatus.READY, account = target.account,
            calendars = if (available) listOf(summary) else emptyList()))
    }

    private fun fixture(withCatalog: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val projection = AndroidCalendarProjection(context)
        assumeTrue(projection.hasPermissions())
        val id = UUID.randomUUID().toString()
        val target = CalendarScope(AccountId("delta-coordinator-fixture-$id"), CalendarId("fixture"), 0)
        val name = "delta-coordinator-fixture-$id.db"
        val store = ImportStore(context, name)
        val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val remote = FakeDeltaRemote(target)
        val networkSchedules = AtomicInteger()
        val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { networkSchedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        val transport: CalendarDeltaRemote = if (withCatalog) CatalogDeltaRemote(remote) else remote
        val coordinator = ImportCoordinator(context, store, transport, projection, coroutineScope, scheduler)
        val fixture = Fixture(context, target, store, projection, remote, coordinator)
        try {
            store.remember(target.account, listOf(fixture.summary))
            store.setEnabled(target, true)
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (networkSchedules.get() == 0) delay(10) }
            fixture.block()
        } finally {
            coordinator.authenticationChanged(false)
            coroutineScope.cancel()
            projection.admitOperation = { true }
            projection.removeOwnedCalendar(target)
            AccountManager.get(context).removeAccountExplicitly(projection.accountFor(target.account))
            context.getSharedPreferences("calendar-provider-mappings", Context.MODE_PRIVATE).edit()
                .remove(projection.accountFor(target.account).name + ":" + target.calendar.value).commit()
            store.close(); context.deleteDatabase(name)
        }
    }

    @Test fun baselineAnchorThenRemoteUpdateAndExplicitDeleteReachTheSameOwnedMirror() = fixture {
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(true))
        assertEquals("latest", remote.calls.first())
        assertTrue(remote.calls.indexOf("latest") < remote.calls.indexOf("baseline"))
        val original = rows().single()
        assertEquals("Calendar Bridge fixture original", original.second)
        remote.publishUpdate()
        coordinator.run(true)
        assertEquals(original.first, rows().single().first)
        assertEquals("Calendar Bridge fixture updated", rows().single().second)
        assertTrue(remote.calls.contains("event"))
        val tombstone = remote.publishDelete()
        coordinator.run(true)
        assertTrue(rows().isEmpty())
        val eventId = remote.eventId
        assertEquals(tombstone, delta { remote(eventId) })
        assertEquals("IMPORTED", selection().status)
        assertTrue(delta { deltaJobs() }.isEmpty())
    }

    @Test fun eventReceivedAfterBaselineCanBeDecodedAndProjectedWithNetworkUnavailable() = fixture {
        // Publish during the baseline read: the pre-baseline anchor must retain this concurrent edit.
        remote.afterBaselineReceived = {
            remote.publishUpdate()
            remote.localKeysAvailable = false
        }
        coordinator.run(true)
        assertEquals("LOCAL_KEYS", selection().status)
        val pending = delta { deltaJobs() }.single()
        assertNotNull(pending.received)
        assertEquals("Calendar Bridge fixture original",
            (store.pending(target).single().change as RemoteChange.Upsert).content.title)
        val callsBefore = remote.calls.toList()
        remote.localKeysAvailable = true
        remote.networkAvailable = false
        coordinator.run(false, fresh = false)
        assertEquals(callsBefore, remote.calls)
        assertTrue(delta { deltaJobs() }.isEmpty())
        assertEquals("Calendar Bridge fixture updated", rows().single().second)
        assertEquals("IMPORTED", selection().status)
    }

    @Test fun missingEventStaysIncompleteAndUnavailableCatalogKeepsOwnedDataUntilRecovery() = fixture {
        coordinator.run(true)
        val originalId = rows().single().first
        remote.publishUpdate()
        remote.missingEvent = true
        coordinator.run(true)
        assertEquals("INCOMPLETE", selection().status)
        assertEquals(1, delta { deltaJobs(blocked = true) }.size)
        assertEquals(originalId, rows().single().first)
        remote.networkAvailable = false
        val callsBefore = remote.calls.toList()
        coordinator.run(false, fresh = false)
        assertEquals("INCOMPLETE", selection().status)
        catalog(false)
        assertFalse(selection().available)
        assertTrue(selection().enabled)
        assertEquals("UNAVAILABLE", selection().status)
        coordinator.run(true)
        assertEquals(callsBefore, remote.calls)
        assertEquals(originalId, rows().single().first)
        assertEquals(1, delta { deltaJobs(blocked = true) }.size)
        catalog(true)
        remote.networkAvailable = true
        remote.missingEvent = false
        coordinator.run(true)
        assertTrue(selection().available)
        assertTrue(delta { deltaJobs(blocked = true) }.isEmpty())
        assertEquals("IMPORTED", selection().status)
        assertEquals(originalId, rows().single().first)
        assertEquals("Calendar Bridge fixture updated", rows().single().second)
    }

    @Test fun failedCatalogMustBeReloadedBeforeDataOnNonFreshWorkerRetry() = fixture(withCatalog = true) {
        remote.catalogFailure = true
        assertEquals(ImportRunResult.RETRY, coordinator.run(true))
        assertEquals(listOf("catalog"), remote.calls)
        assertEquals("NETWORK", selection().status)
        assertNull(delta { deltaCursor })
        remote.catalogFailure = false
        val callsBefore = remote.calls.size
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(true, fresh = false))
        val retried = remote.calls.drop(callsBefore)
        assertEquals("catalog", retried.first())
        assertTrue(retried.indexOf("catalog") < retried.indexOf("baseline"))
        assertEquals("IMPORTED", selection().status)
        assertEquals("Calendar Bridge fixture original", rows().single().second)
    }

    @Test fun failedContextMustRefreshBeforeDecodingOnNonFreshWorkerRetry() = fixture {
        remote.contextReady = false
        remote.contextFailure = true
        assertEquals(ImportRunResult.RETRY, coordinator.run(true))
        assertEquals("NETWORK", selection().status)
        assertEquals(1, remote.calls.count { it == "context" })
        assertFalse(remote.operations.contains("decode-baseline"))
        remote.contextFailure = false
        val operationsBefore = remote.operations.size
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(true, fresh = false))
        val retried = remote.operations.drop(operationsBefore)
        assertEquals("context", retried.first())
        assertTrue(retried.indexOf("context") < retried.indexOf("decode-baseline"))
        assertEquals("IMPORTED", selection().status)
        assertEquals("Calendar Bridge fixture original", rows().single().second)
    }

    @Test fun repeatedlyMissingEventDoesNotStarveSubsequentBaselineRefreshes() = fixture {
        coordinator.run(true)
        remote.publishUpdate()
        remote.missingEvent = true
        coordinator.run(true)
        assertEquals("INCOMPLETE", selection().status)
        var baselineCount = remote.calls.count { it == "baseline" }
        repeat(2) {
            coordinator.run(true, fresh = true)
            assertEquals(++baselineCount, remote.calls.count { it == "baseline" })
            assertEquals("INCOMPLETE", selection().status)
            assertEquals(1, delta { deltaJobs(blocked = true) }.size)
            assertTrue(delta { deltaJobs() }.isEmpty())
            assertEquals(1, rows().size)
        }
    }

    @Test fun resetRescansRetainsUncertainRowsAndKeepsWarningAfterNewChangesAndOfflineRun() = fixture {
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(true))
        val original = rows().single()
        val completed = selection().completedAt
        assertNotNull(completed)
        remote.loseHistory()
        assertEquals(ImportRunResult.CONTINUE, coordinator.run(true))
        assertTrue(selection().historyGap)
        assertEquals("PENDING", selection().status)
        assertNull(delta { checkpoint })
        assertEquals(original, rows().single())
        assertEquals(completed, selection().completedAt)

        remote.networkAvailable = false
        val calls = remote.calls.toList()
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(false, fresh = false))
        assertEquals(calls, remote.calls)
        assertEquals("PENDING", selection().status)
        assertTrue(selection().historyGap)

        remote.networkAvailable = true
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(true, fresh = false))
        assertEquals("HISTORY_GAP", selection().status)
        assertEquals(original, rows().single())
        assertEquals(completed, selection().completedAt)
        remote.publishUpdate()
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(true))
        assertEquals(original.first, rows().single().first)
        assertEquals("Calendar Bridge fixture updated", rows().single().second)
        assertEquals("HISTORY_GAP", selection().status)
        assertEquals(completed, selection().completedAt)

        remote.networkAvailable = false
        val afterRecovery = remote.calls.toList()
        coordinator.run(false, fresh = false)
        assertEquals(afterRecovery, remote.calls)
        assertEquals("HISTORY_GAP", selection().status)
        assertTrue(selection().historyGap)
        assertEquals(completed, selection().completedAt)

        val unsupported = DeferredCalendarEvent(EventId("unsupported-recent"), Revision("v1"), remote.format,
            DeferredEventReason.UNSUPPORTED_CONTENT, "sealed-unsupported")
        delta { defer(unsupported) }
        coordinator.run(false, fresh = false)
        assertEquals("PARTIAL", selection().status)
        assertTrue(selection().historyGap)
        assertEquals(1, selection().deferredCount)
        assertEquals(completed, selection().completedAt)
    }
}
