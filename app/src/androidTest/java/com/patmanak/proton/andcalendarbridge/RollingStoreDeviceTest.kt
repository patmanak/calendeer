package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local rolling-observation fixtures. No device account or Calendar Provider access. */
@RunWith(AndroidJUnit4::class)
class RollingStoreDeviceTest {
    private val scope = CalendarScope(AccountId("rolling-account"), CalendarId("rolling-calendar"), 0)
    private val other = scope.copy(account = AccountId("other-rolling-account"))
    private val format = AdapterFormat("rolling-fixture", 1)
    private val interval = CoverageInterval(LocalDate.of(2026, 10, 1), LocalDate.of(2027, 10, 1), ZoneId.of("Europe/Paris"))
    private val observation = CoverageObservation(format, interval)
    private val finished = CoverageRun(format, listOf(WindowCoverage(interval, true, 1, 1, true, true, 100L, 200L)), 0, true)
    private val ledger = CoverageLedger(epoch = 4, active = finished, observed = observation)
    private val completed = PageCheckpoint(scope, format, traversalComplete = true)
    private val planned = PageCheckpoint(scope, format, OpaqueCursor(format, "opaque-extension-plan"))
    private val deltaCursor = OpaqueCursor(AdapterFormat("delta-fixture", 1), "established-cursor")
    private val change = RemoteChange.Deleted(EventId("retained-event"), Revision("gone"))
    private val receipt = ReceivedCalendarPage(PageRequest(planned, 100), "sealed-pending-page")

    private fun fixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "rolling-store-fixture-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Fixture")))
            store.remember(other.account, listOf(CalendarSummary(other.calendar, "Other fixture")))
            store.transaction(scope) { (this as DeltaSyncTransaction).deltaCursor = this@RollingStoreDeviceTest.deltaCursor }
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun <T> ImportStore.staged(target: CalendarScope = scope, action: StagedSyncTransaction.() -> T): T =
        transaction(target) { action(this as StagedSyncTransaction) }

    @Test fun observationAndPlannedRefreshSurviveReopenWithoutLosingPriorRunOrRecords() = fixture { store, name ->
        store.staged { coverage = ledger; checkpoint = completed; putRemote(change) }
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertEquals(ledger, reopened.staged { coverage })
            reopened.beginRefresh(scope, planned, ledger)
        }
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            val current = reopened.staged { coverage }
            assertEquals(5L, current.epoch); assertNull(current.active)
            assertEquals(finished, current.previous); assertEquals(observation, current.observed)
            assertEquals(planned, reopened.staged { checkpoint })
            assertTrue(reopened.transaction(scope) { (this as DeltaSyncTransaction).deltaMore })
            assertEquals(change, reopened.pending(scope).single().change)
            assertEquals(CoverageLedger(), reopened.staged(other) { coverage })
        }
    }

    @Test fun schemaOneLedgerNeverInfersObservationFromFinishedCoverage() = fixture { store, name ->
        store.staged { coverage = ledger; checkpoint = completed }
        val legacy = store.readableDatabase.rawQuery("SELECT payload FROM coverage_state", null).use {
            assertTrue(it.moveToFirst()); JSONObject(it.getString(0)).apply { put("schema", 1); remove("observed") }.toString()
        }
        store.writableDatabase.update("coverage_state", ContentValues().apply { put("payload", legacy) }, null, null)
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertEquals(ledger.copy(observed = null), reopened.staged { coverage })
            assertEquals(completed, reopened.staged { checkpoint })
            reopened.beginRefresh(scope)
            assertEquals(5L, reopened.staged { coverage }.epoch)
            assertNull(reopened.staged { coverage }.observed)
            assertEquals(finished, reopened.staged { coverage }.previous)
        }
    }

    @Test fun plannedRefreshRollbackRestoresCheckpointObservationAndProjectionQueue() = fixture { store, _ ->
        store.staged { coverage = ledger; checkpoint = completed; putRemote(change) }
        var checks = 0
        store.admitTransaction = { ++checks == 1 }
        try {
            store.beginRefresh(scope, planned, ledger)
            fail("Expected revoked lease rollback")
        } catch (_: IllegalStateException) { }
        finally { store.admitTransaction = { true } }
        assertEquals(2, checks)
        assertEquals(ledger, store.staged { coverage }); assertEquals(completed, store.staged { checkpoint })
        assertFalse(store.transaction(scope) { (this as DeltaSyncTransaction).deltaMore })
        assertEquals(change, store.staged { remote(change.eventId) }); assertTrue(store.pending(scope).isEmpty())
        store.beginRefresh(scope)
        assertNull(store.staged { coverage }.observed)
        assertNull(store.staged { checkpoint })
        assertEquals(5L, store.staged { coverage }.epoch)
    }

    @Test fun plannedRefreshRejectsInvalidOrBusyPlansWithoutAnyPartialRestart() = fixture { store, _ ->
        store.staged { coverage = ledger; checkpoint = completed }
        val different = AdapterFormat("different-fixture", 1)
        val invalid = listOf(
            planned.copy(scope = other),
            PageCheckpoint(scope, different, OpaqueCursor(different, "other-plan")),
            planned.copy(cursor = OpaqueCursor(different, "wrong-cursor-format")),
            PageCheckpoint(scope, format),
            planned.copy(traversalComplete = true),
        )
        for (candidate in invalid) {
            try { store.beginRefresh(scope, candidate, ledger); fail("Expected invalid plan rejection") }
            catch (_: IllegalArgumentException) { }
            assertEquals(ledger, store.staged { coverage }); assertEquals(completed, store.staged { checkpoint })
        }
        for (checkpoint in listOf(null, planned, completed)) {
            store.staged { this.checkpoint = checkpoint; received = if (checkpoint == completed) receipt else null }
            try { store.beginRefresh(scope, planned, ledger); fail("Expected unfinished or received traversal rejection") }
            catch (_: IllegalArgumentException) { }
            assertEquals(ledger, store.staged { coverage }); assertEquals(checkpoint, store.staged { this.checkpoint })
            if (checkpoint == completed) assertEquals(receipt, store.staged { received })
        }
    }

    @Test fun plannedRefreshRequiresUnchangedObservationAndEstablishedDeltaCursor() = fixture { store, _ ->
        store.staged { coverage = ledger; checkpoint = completed }
        for (expected in listOf(null, ledger.copy(epoch = ledger.epoch + 1), ledger.copy(observed = null))) {
            try { store.beginRefresh(scope, planned, expected); fail("Expected stale plan rejection") }
            catch (_: IllegalArgumentException) { }
            assertEquals(ledger, store.staged { coverage }); assertEquals(completed, store.staged { checkpoint })
            assertFalse(store.transaction(scope) { (this as DeltaSyncTransaction).deltaMore })
        }
        store.transaction(scope) { (this as DeltaSyncTransaction).deltaCursor = null }
        try { store.beginRefresh(scope, planned, ledger); fail("Expected missing change cursor rejection") }
        catch (_: IllegalArgumentException) { }
        assertEquals(ledger, store.staged { coverage }); assertEquals(completed, store.staged { checkpoint })
        assertFalse(store.transaction(scope) { (this as DeltaSyncTransaction).deltaMore })
    }

    @Test fun authoritativeAbsenceInvalidatesObservationButKeepsActiveReceiptRecordsAndOtherScopes() = fixture { store, name ->
        val active = finished.copy(windows = listOf(WindowCoverage(interval, true, 1, 0, true, false, 300L, null)), finished = false)
        val current = ledger.copy(active = active, previous = finished)
        val metadata = PageCoverage(listOf(interval), 0, true, true)
        val pendingReceipt = receipt.copy(coverage = metadata)
        store.setEnabled(scope, true)
        store.staged { coverage = current; checkpoint = planned; received = pendingReceipt; putRemote(change) }
        store.staged(other) { coverage = ledger }
        store.remember(scope.account, emptyList())
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            val invalidated = current.copy(observed = null, active = active.copy(windows = active.windows.map { it.copy(prefixKnown = false) }))
            assertEquals(invalidated, reopened.staged { coverage })
            assertEquals(pendingReceipt, reopened.staged { received }); assertEquals(planned, reopened.staged { checkpoint })
            assertEquals(change, reopened.staged { remote(change.eventId) })
            val absent = reopened.selections(scope.account).single()
            assertFalse(absent.available); assertTrue(absent.enabled)
            assertEquals(ledger, reopened.staged(other) { coverage })
            reopened.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Fixture returned")))
            assertTrue(reopened.selections(scope.account).single().available)
            assertNull(reopened.staged { coverage }.observed)
            assertEquals(pendingReceipt, reopened.staged { received })
            reopened.staged {
                coverage = CoverageTracker.applied(coverage, received!!.coverage, true, 400L)
                received = null; checkpoint = completed
            }
            assertNull(reopened.staged { coverage }.observed)
            assertEquals(0, reopened.staged { coverage }.active!!.appliedWindows)
            assertEquals(4L, reopened.staged { coverage }.epoch)
            assertEquals(change, reopened.staged { remote(change.eventId) })
        }
    }

    @Test fun deltaOnlyRefreshSurvivesRestartWithoutChangingCheckpointOrCoverage() = fixture { store, name ->
        store.staged { coverage = ledger; checkpoint = completed; putRemote(change) }
        store.beginDeltaRefresh(scope, ledger)
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertEquals(ledger, reopened.staged { coverage }); assertEquals(completed, reopened.staged { checkpoint })
            assertEquals(change, reopened.pending(scope).single().change)
            reopened.transaction(scope) {
                val delta = this as DeltaSyncTransaction
                assertTrue(delta.deltaMore); assertEquals(this@RollingStoreDeviceTest.deltaCursor, delta.deltaCursor)
            }
        }
    }

    @Test fun deltaOnlyRefreshRejectsStaleCoverageOrLeaseWithoutPartialQueueChanges() = fixture { store, _ ->
        store.staged { coverage = ledger; checkpoint = completed; putRemote(change) }
        try {
            store.beginDeltaRefresh(scope, ledger.copy(epoch = 5))
            fail("Expected stale delta-only admission rejection")
        } catch (_: IllegalArgumentException) { }
        var checks = 0
        store.admitTransaction = { ++checks == 1 }
        try {
            store.beginDeltaRefresh(scope, ledger)
            fail("Expected delta-only lease rollback")
        } catch (_: IllegalStateException) { }
        finally { store.admitTransaction = { true } }
        assertEquals(2, checks)
        assertEquals(ledger, store.staged { coverage }); assertEquals(completed, store.staged { checkpoint })
        assertTrue(store.pending(scope).isEmpty())
        assertFalse(store.transaction(scope) { (this as DeltaSyncTransaction).deltaMore })
        assertEquals(change, store.staged { remote(change.eventId) })
    }

    @Test fun deltaOnlyRefreshRequiresFinishedTraversalWithoutReceiptAndExistingDeltaCursor() = fixture { store, _ ->
        store.staged { coverage = ledger }
        for (checkpoint in listOf(null, planned, completed)) {
            store.staged { this.checkpoint = checkpoint; received = if (checkpoint == completed) receipt else null }
            try { store.beginDeltaRefresh(scope, ledger); fail("Expected unfinished or received traversal rejection") }
            catch (_: IllegalArgumentException) { }
            assertEquals(ledger, store.staged { coverage }); assertEquals(checkpoint, store.staged { this.checkpoint })
            assertFalse(store.transaction(scope) { (this as DeltaSyncTransaction).deltaMore })
        }
        store.transaction(scope) { val delta = this as DeltaSyncTransaction; delta.received = null; delta.deltaCursor = null }
        try { store.beginDeltaRefresh(scope, ledger); fail("Expected missing change cursor rejection") }
        catch (_: IllegalArgumentException) { }
        assertEquals(ledger, store.staged { coverage }); assertEquals(completed, store.staged { checkpoint })
        assertFalse(store.transaction(scope) { (this as DeltaSyncTransaction).deltaMore })
    }
}
