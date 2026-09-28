package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.Context
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Production coordinator/store/provider, synthetic remote and isolated owned account only. */
@RunWith(AndroidJUnit4::class)
class CoverageCoordinatorDeviceTest {
    @Test fun receiptCoverageSurvivesMissingKeysAndOfflineApplyRemainsSeparateFromProjection() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val projection = AndroidCalendarProjection(context)
        assumeTrue(projection.hasPermissions())
        val id = UUID.randomUUID().toString()
        val calendarScope = CalendarScope(AccountId("coverage-coordinator-$id"), CalendarId("fixture"), 0)
        val name = "coverage-coordinator-$id.db"
        val store = ImportStore(context, name)
        val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val format = AdapterFormat("fixture-coverage", 1)
        val interval = CoverageInterval(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 2, 1), ZoneOffset.UTC)
        val metadata = PageCoverage(listOf(interval), 0, startsWindow = true, finishesWindow = true)
        val issue = DeferredCalendarEvent(EventId("unsupported-fixture"), Revision("one"), format,
            DeferredEventReason.UNSUPPORTED_CONTENT, "sealed-deferred-fixture")
        val change = RemoteChange.Upsert(EventId("supported-fixture"), Revision("one"),
            EventContent("Calendar Bridge coverage fixture", EventTime.AllDay(interval.start, interval.start.plusDays(1))))
        var requests = 0
        var keysAvailable = false
        var blockProjectionAfterDecode = true
        val remote = object : StagedCalendarRemote {
            override val format = format
            override suspend fun currentAccount() = calendarScope.account
            override suspend fun dispatch(mutation: Mutation) = DispatchResult.Rejected
            override suspend fun fetch(request: PageRequest): RemotePage = error("Unexpected legacy fetch")
            override suspend fun receive(request: PageRequest): ReceivedCalendarPage {
                check(++requests == 1) { "Offline recovery must not download another page" }
                return ReceivedCalendarPage(request, "sealed-coverage-fixture", metadata)
            }
            override suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage {
                if (!keysAvailable) throw RemoteImportException(RemoteImportFailure.LOCAL_KEYS)
                // Inject a denied provider operation without revoking the application's actual permission.
                if (blockProjectionAfterDecode) projection.admitOperation = { throw SecurityException("Synthetic projection barrier") }
                return DecodedCalendarPage(RemotePage(calendarScope, format, listOf(change), null, true),
                    listOf(issue), received.coverage)
            }
            override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? = null
        }
        val scheduled = AtomicInteger()
        val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { scheduled.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        val coordinator = ImportCoordinator(context, store, remote, projection, coroutineScope, scheduler)
        try {
            store.remember(calendarScope.account, listOf(CalendarSummary(calendarScope.calendar, "Coverage fixture")))
            store.setEnabled(calendarScope, true)
            store.transaction(calendarScope) {
                (this as DeltaSyncTransaction).retainReconciliation(ReceivedChangePage(calendarScope,
                    OpaqueCursor(AdapterFormat("fixture-delta", 1), "old-history"), "sealed-gap-fixture"))
            }
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (scheduled.get() == 0) delay(10) }
            coordinator.run(network = true)
            val received = store.selections(calendarScope.account).single()
            assertEquals("LOCAL_KEYS", received.status)
            assertEquals(1, received.coverage!!.receivedWindows)
            assertEquals(0, received.coverage!!.appliedWindows)
            assertFalse(received.coverage!!.finished)
            assertEquals(0, received.pendingProjectionCount)
            assertNull(received.completedAt)
            assertTrue(received.historyGap)
            store.transaction(calendarScope) {
                assertEquals(metadata, (this as StagedSyncTransaction).received!!.coverage)
                assertNull(checkpoint)
            }

            keysAvailable = true
            coordinator.run(network = false)
            val applied = store.selections(calendarScope.account).single()
            assertEquals("PERMISSION", applied.status)
            assertEquals(1, applied.coverage!!.receivedWindows)
            assertEquals(1, applied.coverage!!.appliedWindows)
            assertTrue(applied.coverage!!.finished)
            assertEquals(1, applied.pendingProjectionCount)
            assertEquals(1, applied.deferredCount)
            assertTrue(applied.historyGap)
            assertNull(applied.completedAt)
            store.transaction(calendarScope) {
                val staged = this as StagedSyncTransaction
                assertNull(staged.received)
                assertTrue(checkpoint!!.traversalComplete)
                assertEquals(change, remote(change.eventId))
                assertEquals(issue, staged.deferred(issue.eventId))
            }

            blockProjectionAfterDecode = false
            assertEquals(ImportRunResult.COMPLETE, coordinator.run(network = false))
            val projected = store.selections(calendarScope.account).single()
            assertEquals("PARTIAL", projected.status)
            assertEquals(applied.coverage, projected.coverage)
            assertEquals(0, projected.pendingProjectionCount)
            assertEquals(1, projected.deferredCount)
            assertTrue(projected.historyGap)
            assertNull("A retained history gap prevents complete synchronization claims", projected.completedAt)
            assertEquals(1, requests)
            val providerId = projection.ensureCalendar(calendarScope, projected.summary)
            context.contentResolver.query(CalendarContract.Events.CONTENT_URI,
                arrayOf(CalendarContract.Events.TITLE), "${CalendarContract.Events.CALENDAR_ID}=?",
                arrayOf(providerId.toString()), null)!!.use { rows ->
                assertEquals(1, rows.count)
                assertTrue(rows.moveToFirst())
                assertEquals("Calendar Bridge coverage fixture", rows.getString(0))
            }
            assertEquals(projected.coverage, coordinator.state.value.selections.single().coverage)
        } finally {
            coordinator.authenticationChanged(false)
            coroutineScope.cancel()
            projection.admitOperation = { true }
            projection.removeOwnedCalendar(calendarScope)
            AccountManager.get(context).removeAccountExplicitly(projection.accountFor(calendarScope.account))
            context.getSharedPreferences("calendar-provider-mappings", Context.MODE_PRIVATE).edit()
                .remove(projection.accountFor(calendarScope.account).name + ":" + calendarScope.calendar.value).commit()
            store.close()
            context.deleteDatabase(name)
        }
    }
}
