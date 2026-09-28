package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local, isolated SQLite fixtures. No device account, network or Calendar Provider access. */
@RunWith(AndroidJUnit4::class)
class StagedImportStoreDeviceTest {
    private val scope = CalendarScope(AccountId("staging-account"), CalendarId("staging-calendar"), 0)
    private val other = scope.copy(account = AccountId("other-staging-account"))
    private val format = AdapterFormat("sealed-fixture", 1)
    private val initial = PageCheckpoint(scope, format)
    private val complete = initial.copy(traversalComplete = true)
    private fun receipt(payload: String = "opaque-sealed-page", target: CalendarScope = scope) =
        ReceivedCalendarPage(PageRequest(initial.copy(scope = target), 100), payload)
    private fun issue(id: String = "event-b", payload: String = "opaque-sealed-event", revision: String = "v1") =
        DeferredCalendarEvent(EventId(id), Revision(revision), format, DeferredEventReason.UNSUPPORTED_CONTENT, payload)
    private fun change(id: String = "event-a") = RemoteChange.Upsert(EventId(id), Revision("v1"),
        EventContent("Fixture", EventTime.AllDay(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26))))

    private fun fixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "staged-import-fixture-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Fixture")))
            store.remember(other.account, listOf(CalendarSummary(other.calendar, "Other fixture")))
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun <T> ImportStore.staged(target: CalendarScope = scope, action: StagedSyncTransaction.() -> T): T =
        transaction(target) { action(this as StagedSyncTransaction) }

    @Test fun largeReceivedPageSurvivesReopenWithoutAdvancingAppliedCheckpoint() = fixture { store, name ->
        // Larger than a default CursorWindow; valid maximum envelopes must remain readable.
        val page = receipt("x".repeat(3 * 1024 * 1024))
        store.staged { received = page }
        assertFalse(store.complete(scope))
        assertNull(store.staged { checkpoint })
        store.close()
        val reopened = ImportStore(ApplicationProvider.getApplicationContext(), name)
        try {
            assertEquals(page, reopened.staged { received })
            assertNull(reopened.staged { checkpoint })
            assertTrue(reopened.pending(scope).isEmpty())
        } finally { reopened.close() }
    }

    @Test fun applyingPageAndIssuesIsAtomicAndLeaseRevocationPreservesReceipt() = fixture { store, _ ->
        val page = receipt()
        store.staged { received = page }
        var admitted = true
        store.admitTransaction = { admitted }
        try {
            store.staged {
                putRemote(change()); putProjection(ProjectionIntent(scope, change()))
                defer(issue()); checkpoint = complete; received = null
                admitted = false
            }
            fail("Expected inactive lease rollback")
        } catch (_: IllegalStateException) { }
        store.admitTransaction = { true }
        assertEquals(page, store.staged { received })
        assertNull(store.staged { checkpoint })
        assertNull(store.staged { remote(change().eventId) })
        assertEquals(0, store.deferredCount(scope))
        assertTrue(store.pending(scope).isEmpty())
        store.staged {
            putRemote(change()); putProjection(ProjectionIntent(scope, change()))
            defer(issue()); checkpoint = complete; received = null
        }
        assertNull(store.staged { received })
        assertTrue(store.complete(scope))
        assertEquals(issue(), store.deferred(scope).single())
        assertEquals(ProjectionIntent(scope, change()), store.pending(scope).single())
    }

    @Test fun occupiedInboxRejectsReplacementAndForeignScope() = fixture { store, _ ->
        val original = receipt()
        store.staged { received = original }
        store.staged { received = original } // The same durable receipt is idempotent.
        try {
            store.staged { received = receipt("different-page") }
            fail("Expected occupied inbox rejection")
        } catch (_: IllegalStateException) { }
        try {
            store.staged { received = receipt(target = other) }
            fail("Expected foreign scope rejection")
        } catch (_: IllegalArgumentException) { }
        assertEquals(original, store.staged { received })
        assertNull(store.staged(other) { received })
    }

    @Test fun refreshRetainsInboxAndNeverRequeuesAnOlderDeferredVersion() = fixture { store, _ ->
        store.staged {
            putRemote(change()); putRemote(change("event-b")); defer(issue())
            checkpoint = complete; received = receipt()
        }
        store.beginRefresh(scope)
        assertEquals(complete, store.staged { checkpoint })
        assertNotNull(store.staged { received })
        assertTrue(store.pending(scope).isEmpty())
        store.staged { received = null }
        store.beginRefresh(scope)
        assertNull(store.staged { checkpoint })
        assertEquals(listOf(change()), store.pending(scope).map { it.change })
        assertEquals(1, store.deferredCount(scope))
        store.acknowledge(store.pending(scope).single())
        store.requeueAll(scope)
        assertEquals(listOf(change()), store.pending(scope).map { it.change })
    }

    @Test fun deferredPaginationIsScopedStableAndDurableAcrossReopen() = fixture { store, name ->
        store.staged {
            defer(issue("event-c")); defer(issue("event-a")); defer(issue("event-b"))
        }
        store.staged(other) { defer(issue("event-b", revision = "other")) }
        store.status(scope, "PARTIAL")
        store.close()
        val reopened = ImportStore(ApplicationProvider.getApplicationContext(), name)
        try {
            val first = reopened.deferred(scope, 2)
            assertEquals(listOf(EventId("event-a"), EventId("event-b")), first.map { it.eventId })
            reopened.staged { clearDeferred(first.first().eventId) }
            assertEquals(listOf(EventId("event-c")), reopened.deferred(scope, 2, first.last().eventId).map { it.eventId })
            reopened.staged { defer(issue("event-b", revision = "newer")) }
            assertEquals("newer", reopened.staged { deferred(EventId("event-b")) }?.revision?.value)
            assertEquals("other", reopened.staged(other) { deferred(EventId("event-b")) }?.revision?.value)
            assertEquals(2, reopened.selections(scope.account).single().deferredCount)
            assertEquals("PARTIAL", reopened.selections(scope.account).single().status)
            reopened.status(scope, "CAPACITY")
            assertEquals("CAPACITY", reopened.selections(scope.account).single().status)
        } finally { reopened.close() }
    }

    @Test fun stagingBudgetCountsUtf8BytesAcrossInboxIssuesAndScopesWithAtomicRollback() = fixture { store, _ ->
        val fourMiB = 4 * 1024 * 1024
        val eightMiBBytes = "é".repeat(fourMiB)
        store.staged {
            defer(issue("large-a", eightMiBBytes)); defer(issue("large-b", eightMiBBytes))
            defer(issue("large-c", eightMiBBytes)); received = receipt("x".repeat(fourMiB))
        }
        store.staged(other) { defer(issue("large-d", "x".repeat(fourMiB))) }
        try {
            store.staged(other) {
                defer(issue("overflow", "é")); checkpoint = complete.copy(scope = other)
            }
            fail("Expected storage budget rejection")
        } catch (_: ImportCapacityException) { }
        assertEquals(3, store.deferredCount(scope))
        assertEquals(1, store.deferredCount(other))
        assertNull(store.staged(other) { checkpoint })
        assertEquals(fourMiB, store.staged { received }?.protectedPayload?.length)
        // Replacement within one transaction is judged on the resulting retained bytes.
        store.staged { received = null; defer(issue("replacement", "x".repeat(fourMiB))) }
        assertEquals(4, store.deferredCount(scope))
    }

    @Test fun boundedRetryQueueSurvivesReopenAndStaleAcknowledgementDoesNotConsumeNewEvidence() = fixture { store, name ->
        store.staged { (1..6).forEach { defer(issue("event-$it")) } }
        assertTrue(store.pendingDeferredRetries(scope).isEmpty())
        assertEquals(6, store.prepareDeferredRetry(scope))
        val first = store.pendingDeferredRetries(scope)
        assertEquals(4, first.size)
        first.forEach { assertEquals(1, store.acknowledgeDeferredRetry(scope, it)) }
        store.close()
        val reopened = ImportStore(ApplicationProvider.getApplicationContext(), name)
        try {
            val last = reopened.pendingDeferredRetries(scope)
            assertEquals(listOf(EventId("event-5"), EventId("event-6")), last.map { it.eventId })
            val newer = last.first().copy(protectedPayload = "different-sealed-evidence")
            reopened.staged { defer(newer) }
            reopened.prepareDeferredRetry(scope)
            assertEquals(0, reopened.acknowledgeDeferredRetry(scope, last.first()))
            // Advance the first batch again; the changed row must still have its retry pending.
            reopened.pendingDeferredRetries(scope).forEach { reopened.acknowledgeDeferredRetry(scope, it) }
            assertEquals(newer, reopened.pendingDeferredRetries(scope).first())
            reopened.staged { clearDeferred(newer.eventId) }
            assertEquals(listOf(EventId("event-6")), reopened.pendingDeferredRetries(scope).map { it.eventId })
        } finally { reopened.close() }
    }

    @Test fun versionOneMigrationRetainsSelectionCheckpointAndLegacyProjectionAcknowledgement() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "legacy-import-fixture-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("CREATE TABLE calendars (scope TEXT PRIMARY KEY, account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL, title TEXT NOT NULL, color INTEGER, enabled INTEGER NOT NULL DEFAULT 0, checkpoint TEXT, status TEXT NOT NULL DEFAULT 'PAUSED', completed_at INTEGER, next_attempt INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE records (scope TEXT NOT NULL, event TEXT NOT NULL, payload TEXT NOT NULL, pending INTEGER NOT NULL, PRIMARY KEY(scope,event))")
                val scopeKey = JSONArray().put(scope.account.value).put(scope.calendar.value).put(0).toString()
                val checkpointJson = """{"adapter":"sealed-fixture","version":1,"cursor":"legacy-next","complete":false}"""
                val eventJson = """{"id":"event-a","revision":"v1","deleted":false,"title":"Fixture","description":"","location":"","allDay":true,"start":"2026-10-25","end":"2026-10-26"}"""
                db.execSQL("INSERT INTO calendars(scope,account,calendar,generation,title,enabled,checkpoint,status) VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf<Any>(scopeKey, scope.account.value, scope.calendar.value, 0, "Fixture", 1, checkpointJson, "PENDING"))
                db.execSQL("INSERT INTO records(scope,event,payload,pending) VALUES(?,?,?,?)", arrayOf<Any>(scopeKey, "event-a", eventJson, 1))
                db.version = 1
            }
            ImportStore(context, name).use { store ->
                assertTrue(store.selections(scope.account).single().enabled)
                assertEquals("legacy-next", store.staged { checkpoint }?.cursor?.value)
                assertEquals(0, store.deferredCount(scope))
                assertNull(store.staged { received })
                assertEquals(change(), store.pending(scope).single().change)
                assertEquals(1, store.acknowledge(store.pending(scope).single()))
                store.staged { received = receipt(); defer(issue()) }
                assertEquals(1, store.deferredCount(scope))
                assertNotNull(store.staged { received })
            }
        } finally { context.deleteDatabase(name) }
    }
}
