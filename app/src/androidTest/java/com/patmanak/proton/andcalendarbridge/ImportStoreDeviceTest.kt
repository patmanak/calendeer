package com.patmanak.proton.andcalendarbridge

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated database fixtures only. No account, network or shared Calendar Provider access. */
@RunWith(AndroidJUnit4::class)
class ImportStoreDeviceTest {
    private val scope = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 0)
    private val format = AdapterFormat("fixture", 1)
    private fun change(version: String = "one") = RemoteChange.Upsert(EventId("fixture-event"), Revision(version),
        EventContent("Fixture", EventTime.AllDay(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26))))

    private fun fixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "import-fixture-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Fixture")))
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun localHoldSurvivesReopenAndFreshRefreshRearmsWithoutConsumingWork() = fixture { store, name ->
        val intent = ProjectionIntent(scope, change())
        store.setEnabled(scope, true)
        store.transaction(scope) {
            putRemote(intent.change); putProjection(intent)
            checkpoint = PageCheckpoint(scope, format, traversalComplete = true)
        }
        assertTrue(store.holdLocalProjection(intent))
        assertTrue(store.pending(scope).isEmpty())
        assertEquals(1, store.selections(scope.account).single().pendingProjectionCount)
        store.close()
        val reopened = ImportStore(ApplicationProvider.getApplicationContext(), name)
        try {
            assertEquals(1, reopened.heldProjectionCount(scope)); assertTrue(reopened.pending(scope).isEmpty())
            reopened.beginRefresh(scope)
            assertEquals(listOf(intent), reopened.pending(scope))
            assertEquals(0, reopened.heldProjectionCount(scope))
            val newer = ProjectionIntent(scope, change("newer"))
            reopened.transaction(scope) { putRemote(newer.change); putProjection(newer) }
            assertFalse(reopened.holdLocalProjection(intent))
            assertEquals(listOf(newer), reopened.pending(scope))
        } finally { reopened.close() }
    }

    @Test fun moreThanOneBatchOfHeldRowsStillAllowsLaterIndependentWork() = fixture { store, _ ->
        val held = (0..100).map { index -> ProjectionIntent(scope, change().copy(eventId = EventId("held-$index"))) }
        val independent = ProjectionIntent(scope, change().copy(eventId = EventId("independent")))
        store.transaction(scope) {
            for (intent in held + independent) { putRemote(intent.change); putProjection(intent) }
        }
        for (intent in store.pending(scope)) assertTrue(store.holdLocalProjection(intent))
        assertEquals(100, store.heldProjectionCount(scope))
        assertEquals(listOf(held.last(), independent), store.pending(scope))
        assertTrue(store.holdLocalProjection(held.last()))
        assertEquals(listOf(independent), store.pending(scope))
        assertEquals(102, store.selections(scope.account).single().pendingProjectionCount)
    }

    @Test fun pageAndCheckpointRollBackTogether() = fixture { store, _ ->
        try {
            store.transaction(scope) {
                putRemote(change()); putProjection(ProjectionIntent(scope, change()))
                checkpoint = PageCheckpoint(scope, format, traversalComplete = true)
                error("fixture interruption")
            }
            fail("Expected rollback")
        } catch (_: IllegalStateException) { }
        assertNull(store.transaction(scope) { checkpoint })
        assertNull(store.transaction(scope) { remote(EventId("fixture-event")) })
        assertTrue(store.pending(scope).isEmpty())
    }

    @Test fun committedQueueAndSelectionSurviveDatabaseReopen() = fixture { store, name ->
        store.setEnabled(scope, true)
        store.transaction(scope) {
            putRemote(change()); putProjection(ProjectionIntent(scope, change()))
            checkpoint = PageCheckpoint(scope, format, OpaqueCursor(format, "next"))
        }
        store.close()
        val reopened = ImportStore(ApplicationProvider.getApplicationContext(), name)
        try {
            assertTrue(reopened.selections(scope.account).single().enabled)
            assertEquals(ProjectionIntent(scope, change()), reopened.pending(scope).single())
            assertEquals("next", reopened.transaction(scope) { checkpoint?.cursor?.value })
        } finally { reopened.close() }
    }

    @Test fun oldAcknowledgementCannotConsumeNewProjection() = fixture { store, _ ->
        val old = ProjectionIntent(scope, change())
        val newer = ProjectionIntent(scope, change("two"))
        store.transaction(scope) { putRemote(old.change); putProjection(old) }
        store.transaction(scope) { putRemote(newer.change); putProjection(newer) }
        assertEquals(0, store.acknowledge(old))
        assertEquals(newer, store.pending(scope).single())
        assertEquals(1, store.acknowledge(newer))
        assertTrue(store.pending(scope).isEmpty())
    }

    @Test fun customColorSurvivesReopenAndLegacyNullPayloadStillAcknowledges() = fixture { store, name ->
        val legacy = ProjectionIntent(scope, change())
        store.transaction(scope) { putRemote(legacy.change); putProjection(legacy) }
        assertEquals(1, store.acknowledge(store.pending(scope).single()))
        val colored = change("color").let { it.copy(content = it.content.copy(colorRgb = 0xAABBCC)) }
        store.transaction(scope) { putRemote(colored); putProjection(ProjectionIntent(scope, colored)) }
        store.close()
        val reopened = ImportStore(ApplicationProvider.getApplicationContext(), name)
        try {
            assertEquals(0xAABBCC, (reopened.pending(scope).single().change as RemoteChange.Upsert).content.colorRgb)
            assertEquals(1, reopened.acknowledge(reopened.pending(scope).single()))
        } finally { reopened.close() }
    }

    @Test fun refreshRequeuesKnownRowsOnlyAfterCompleteTraversal() = fixture { store, _ ->
        store.transaction(scope) {
            putRemote(change()); checkpoint = PageCheckpoint(scope, format, OpaqueCursor(format, "next"))
        }
        store.beginRefresh(scope)
        assertEquals("next", store.transaction(scope) { checkpoint?.cursor?.value })
        assertTrue(store.pending(scope).isEmpty())
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format, traversalComplete = true) }
        store.beginRefresh(scope)
        assertNull(store.transaction(scope) { checkpoint })
        assertEquals(change(), store.pending(scope).single().change)
    }

    @Test fun inactiveLeaseRollsBackEvenIfRevokedInsideTransaction() = fixture { store, _ ->
        var admitted = true
        store.admitTransaction = { admitted }
        try {
            store.transaction(scope) { putRemote(change()); admitted = false }
            fail("Expected inactive lease")
        } catch (_: IllegalStateException) { }
        store.admitTransaction = { true }
        assertNull(store.transaction(scope) { remote(EventId("fixture-event")) })
    }

    @Test fun foreignScopeAndOutgoingWritesAreRejected() = fixture { store, _ ->
        try {
            store.transaction(scope.copy(account = AccountId("other"))) { checkpoint }
            fail("Expected scope rejection")
        } catch (_: IllegalStateException) { }
        try {
            store.transaction(scope) { putMutation(Mutation(MutationId("fixture-create"), scope, format,
                MutationKind.CREATE, content = change().content)) }
            fail("Expected unsupported write")
        } catch (_: IllegalStateException) { }
    }
}
