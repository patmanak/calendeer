package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentUris
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

/** Production queue and provider, isolated synthetic account and paginated remote. */
@RunWith(AndroidJUnit4::class)
class RecurrenceCoordinatorDeviceTest {
    private val start = LocalDate.of(2030, 1, 1)
    private val master = RemoteChange.Upsert(EventId("master"), Revision("one"), EventContent("Series fixture",
        EventTime.AllDay(start, start.plusDays(1)), recurrence = EventRecurrence("series-fixture", "FREQ=DAILY;COUNT=3")))
    private val child = RemoteChange.Upsert(EventId("exception"), Revision("one"), EventContent("Moved fixture",
        EventTime.AllDay(start.plusDays(3), start.plusDays(4)), recurrence = EventRecurrence("series-fixture",
            original = RecurrencePoint.AllDay(start.plusDays(1)))))

    private inner class Fixture(val context: Context, val target: CalendarScope, val store: ImportStore,
        val projection: AndroidCalendarProjection, val coordinator: ImportCoordinator) {
        suspend fun selection() = store.selections(target.account).single()
        suspend fun instances(): List<LocalDate> {
            val calendar = projection.ensureCalendar(target, CalendarSummary(target.calendar, "Recurrence fixture"))
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            ContentUris.appendId(builder, start.plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            return context.contentResolver.query(builder.build(), arrayOf(CalendarContract.Instances.BEGIN),
                "${CalendarContract.Instances.CALENDAR_ID}=?", arrayOf(calendar.toString()), CalendarContract.Instances.BEGIN)!!.use { rows ->
                buildList { while (rows.moveToNext()) add(java.time.Instant.ofEpochMilli(rows.getLong(0)).atZone(ZoneOffset.UTC).toLocalDate()) }
            }
        }
        suspend fun apply(change: RemoteChange) = store.transaction(target) {
            putRemote(change); putProjection(ProjectionIntent(target, change))
        }
    }
    private fun fixture(includeMaster: Boolean, recoverDeferred: Boolean = false,
        masterChange: RemoteChange.Upsert = master, childChange: RemoteChange.Upsert = child,
        block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val projection = AndroidCalendarProjection(context)
        assumeTrue(projection.hasPermissions())
        val id = UUID.randomUUID().toString()
        val target = CalendarScope(AccountId("recurrence-coordinator-$id"), CalendarId("fixture"), 0)
        val name = "recurrence-coordinator-$id.db"
        val store = ImportStore(context, name)
        val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val format = AdapterFormat("recurrence-fixture", 1)
        val remote = object : StagedCalendarRemote {
            override val format = format
            override suspend fun currentAccount() = target.account
            override suspend fun dispatch(mutation: Mutation) = DispatchResult.Rejected
            override suspend fun fetch(request: PageRequest): RemotePage = error("Legacy fetch")
            override suspend fun receive(request: PageRequest) = ReceivedCalendarPage(request,
                request.checkpoint.cursor?.value ?: "0")
            override suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage {
                val page = received.protectedPayload.toInt()
                val changes = when {
                    page == 0 -> listOf(childChange)
                    page == 5 && includeMaster -> listOf(masterChange)
                    else -> emptyList()
                }
                return DecodedCalendarPage(RemotePage(target, format, changes,
                    if (page == 5) null else OpaqueCursor(format, (page + 1).toString()), page == 5))
            }
            override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? =
                if (recoverDeferred) master.copy(revision = event.revision) else null
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
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Recurrence fixture")))
            store.setEnabled(target, true); coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (scheduled.get() == 0) delay(10) }
            Fixture(context, target, store, projection, coordinator).block()
        } finally {
            coordinator.authenticationChanged(false); coroutineScope.cancel()
            projection.admitOperation = { true }; projection.removeOwnedCalendar(target)
            AccountManager.get(context).removeAccountExplicitly(projection.accountFor(target.account))
            context.getSharedPreferences("calendar-provider-mappings", Context.MODE_PRIVATE).edit()
                .remove(projection.accountFor(target.account).name + ":" + target.calendar.value).commit()
            store.close(); context.deleteDatabase(name)
        }
    }

    @Test fun exceptionBeforeMasterDoesNotDeadlockPagesAndReplacesOriginalOccurrence() = fixture(true) {
        assertEquals(ImportRunResult.CONTINUE, coordinator.run(true))
        assertEquals(1, store.blockedSeriesCount(target))
        assertEquals(0, instances().size)
        assertNull(selection().completedAt)
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(true, fresh = false))
        assertEquals(0, store.blockedSeriesCount(target))
        assertEquals("IMPORTED", selection().status)
        assertEquals(listOf(start, start.plusDays(2), start.plusDays(3)), instances())
        coordinator.run(false, false)
        assertEquals(listOf(start, start.plusDays(2), start.plusDays(3)), instances())
    }
    @Test fun unknownSeriesStaysVisibleAsBlockedWithoutRetrySpinAndCanResolveLocally() = fixture(false) {
        coordinator.run(true)
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(true, false))
        assertEquals("SERIES_PENDING", selection().status)
        assertNull(selection().completedAt)
        assertEquals(1, selection().pendingProjectionCount)
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(false, false))
        assertEquals("SERIES_PENDING", selection().status)
        apply(master)
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(false, false))
        assertEquals("IMPORTED", selection().status)
        assertEquals(listOf(start, start.plusDays(2), start.plusDays(3)), instances())
    }
    @Test fun masterTombstoneRemovesLinkedInstancesAndStaleChildrenCannotReappear() = fixture(true) {
        coordinator.run(true); coordinator.run(true, false)
        assertEquals(3, instances().size)
        apply(RemoteChange.Deleted(master.eventId, Revision("deleted")))
        coordinator.run(false, false)
        assertTrue(instances().isEmpty())
        apply(child.copy(revision = Revision("late-child")))
        coordinator.run(false, false)
        assertTrue(instances().isEmpty())
        assertEquals(0, selection().pendingProjectionCount)
        assertEquals(child.copy(revision = Revision("late-child")), store.transaction(target) { remote(child.eventId) })
    }

    @Test fun deferredMasterDoesNotBlockOfflineRecoveryWhenMirrorWasRemoved() = fixture(true, recoverDeferred = true) {
        coordinator.run(true); coordinator.run(true, false)
        assertEquals(3, instances().size)
        store.transaction(target) {
            (this as StagedSyncTransaction).defer(DeferredCalendarEvent(master.eventId, Revision("recovered"),
                AdapterFormat("recurrence-fixture", 1), DeferredEventReason.UNSUPPORTED_CONTENT, "sealed-fixture"))
            removeProjection(master.eventId)
        }
        projection.removeOwnedCalendar(target)
        // Exercise reconstruction even when the test Provider reuses the deleted numeric ID.
        store.requeueAll(target)
        store.prepareDeferredRetry(target)
        assertEquals(ImportRunResult.COMPLETE, coordinator.run(false, false))
        assertEquals("IMPORTED", selection().status)
        assertEquals(0, store.deferredCount(target))
        assertEquals(listOf(start, start.plusDays(2), start.plusDays(3)), instances())
    }

    @Test fun exceptionWithNewOriginalTypeWaitsForLaterMasterPage() {
        val instant = start.atTime(10, 0).toInstant(ZoneOffset.UTC)
        val updatedMaster = master.copy(revision = Revision("timed"), content = master.content.copy(
            time = EventTime.Timed(instant, instant.plusSeconds(3600), ZoneOffset.UTC)))
        val updatedChild = child.copy(content = child.content.copy(
            time = EventTime.Timed(instant.plusSeconds(3 * 86400L), instant.plusSeconds(3 * 86400L + 3600), ZoneOffset.UTC),
            recurrence = child.content.recurrence!!.copy(original = RecurrencePoint.Timed(instant.plusSeconds(86400)))))
        fixture(true, masterChange = updatedMaster, childChange = updatedChild) {
            apply(master)
            assertEquals(ImportRunResult.CONTINUE, coordinator.run(true))
            assertEquals(1, store.blockedSeriesCount(target))
            assertNotEquals("ERROR", selection().status)
            assertEquals(ImportRunResult.COMPLETE, coordinator.run(true, false))
            assertEquals("IMPORTED", selection().status)
            assertEquals(0, store.blockedSeriesCount(target))
            assertEquals(listOf(start, start.plusDays(2), start.plusDays(3)), instances())
        }
    }
}
