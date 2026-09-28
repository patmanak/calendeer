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

/** Owned synthetic provider account only; isolated store and scheduler, no real account transport. */
@RunWith(AndroidJUnit4::class)
class StagedImportCoordinatorDeviceTest {
    @Test fun finalLargePagePublishesCompletionAfterOfflineProjectionDrain() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val projection = AndroidCalendarProjection(context)
        assumeTrue(projection.hasPermissions())
        val id = UUID.randomUUID().toString()
        val calendarScope = CalendarScope(AccountId("coordinator-fixture-$id"), CalendarId("fixture"), 0)
        val name = "coordinator-fixture-$id.db"
        val store = ImportStore(context, name)
        val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val format = AdapterFormat("fixture-staged", 1)
        var requests = 0
        var recovered: RemoteChange.Upsert? = null
        var keysAvailable = true
        val remote = object : StagedCalendarRemote {
            override val format = format
            override suspend fun currentAccount() = calendarScope.account
            override suspend fun dispatch(mutation: Mutation) = DispatchResult.Rejected
            override suspend fun fetch(request: PageRequest): RemotePage = error("Unexpected legacy fetch")
            override suspend fun receive(request: PageRequest): ReceivedCalendarPage {
                requests++; return ReceivedCalendarPage(request, "sealed-fixture")
            }
            override suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage {
                if (!keysAvailable) throw RemoteImportException(RemoteImportFailure.LOCAL_KEYS)
                val page = received.request.checkpoint.cursor?.value?.toInt() ?: 0
                val events = (0 until 100).map { offset ->
                    RemoteChange.Upsert(EventId("fixture-${page * 100 + offset}"), Revision("one"),
                        EventContent("Calendar Bridge fixture", EventTime.AllDay(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 2))))
                }
                return DecodedCalendarPage(RemotePage(calendarScope, format, events,
                    if (page == 3) null else OpaqueCursor(format, (page + 1).toString()), page == 3))
            }
            override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? = recovered
        }
        val networkSchedules = AtomicInteger()
        val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { networkSchedules.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        val coordinator = ImportCoordinator(context, store, remote, projection, coroutineScope, scheduler)
        try {
            store.remember(calendarScope.account, listOf(CalendarSummary(calendarScope.calendar, "Calendar Bridge fixture")))
            store.setEnabled(calendarScope, true)
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (networkSchedules.get() == 0) delay(10) }
            assertEquals(ImportRunResult.CONTINUE, coordinator.run(true))
            assertNull(store.selections(calendarScope.account).single().completedAt)
            repeat(4) { coordinator.run(false) }
            val finished = store.selections(calendarScope.account).single()
            assertEquals("IMPORTED", finished.status)
            assertNotNull("Completion must follow final projection acknowledgement", finished.completedAt)
            assertEquals(4, requests)
            val timestamp = finished.completedAt
            coordinator.run(false)
            assertEquals(timestamp, store.selections(calendarScope.account).single().completedAt)
            // Refresh must arm previously unsupported content before any network worker runs.
            val issue = DeferredCalendarEvent(EventId("recovered"), Revision("one"), format,
                DeferredEventReason.UNSUPPORTED_CONTENT, "sealed-event")
            store.transaction(calendarScope) { (this as StagedSyncTransaction).defer(issue) }
            assertTrue(store.pendingDeferredRetries(calendarScope).isEmpty())
            recovered = RemoteChange.Upsert(issue.eventId, issue.revision, EventContent("Recovered fixture",
                EventTime.AllDay(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 2))))
            val scheduledBefore = networkSchedules.get()
            coordinator.requestSync()
            withTimeout(10_000) { while (networkSchedules.get() == scheduledBefore) delay(10) }
            assertEquals(1, store.pendingDeferredRetries(calendarScope).size)
            coordinator.run(false)
            assertEquals(0, store.deferredCount(calendarScope))
            assertEquals(4, requests)
            store.transaction(calendarScope) {
                checkpoint = null
                (this as StagedSyncTransaction).received = ReceivedCalendarPage(
                    PageRequest(PageCheckpoint(calendarScope, format), 100), "sealed-fixture")
            }
            keysAvailable = false
            coordinator.run(false)
            assertEquals("LOCAL_KEYS", store.selections(calendarScope.account).single().status)
            keysAvailable = true
            coordinator.run(false)
            assertEquals("PENDING", store.selections(calendarScope.account).single().status)
            assertEquals(4, requests)
        } finally {
            coordinator.authenticationChanged(false)
            coroutineScope.cancel()
            projection.admitOperation = { true }
            projection.removeOwnedCalendar(calendarScope)
            AccountManager.get(context).removeAccountExplicitly(projection.accountFor(calendarScope.account))
            context.getSharedPreferences("calendar-provider-mappings", Context.MODE_PRIVATE).edit()
                .remove(projection.accountFor(calendarScope.account).name + ":" + calendarScope.calendar.value).commit()
            store.close(); context.deleteDatabase(name)
        }
    }
}
