package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated database fixtures; never reads the signed-in account or Calendar Provider. */
@RunWith(AndroidJUnit4::class)
class CoverageStoreDeviceTest {
    private val scope = CalendarScope(AccountId("coverage-account"), CalendarId("coverage-calendar"), 0)
    private val other = scope.copy(account = AccountId("other-coverage-account"))
    private val format = AdapterFormat("coverage-fixture", 1)
    private val initial = PageCheckpoint(scope, format)
    private val interval = CoverageInterval(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), ZoneId.of("Europe/Paris"))
    private val metadata = PageCoverage(listOf(interval), 0, true, true)
    private val receipt = ReceivedCalendarPage(PageRequest(initial, 100), "sealed-fixture", metadata)
    private val receivedLedger get() = CoverageTracker.received(CoverageLedger(), receipt.request, metadata, 100L)
    private val appliedLedger get() = CoverageTracker.applied(receivedLedger, metadata, true, 200L)
    private val change = RemoteChange.Deleted(EventId("fixture-event"), Revision("gone"))

    private fun fixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "coverage-fixture-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Fixture")))
            store.remember(other.account, listOf(CalendarSummary(other.calendar, "Other fixture")))
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun <T> ImportStore.staged(target: CalendarScope = scope, action: StagedSyncTransaction.() -> T): T =
        transaction(target) { action(this as StagedSyncTransaction) }

    @Test fun receivedAndAppliedProgressSurviveReopenAndProjectionRemainsSeparate() = fixture { store, name ->
        store.staged { received = receipt; coverage = receivedLedger }
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertEquals(receipt, reopened.staged { received })
            assertEquals(receivedLedger, reopened.staged { coverage })
            assertNull(reopened.staged { checkpoint })
            val received = reopened.selections(scope.account).single()
            assertEquals(1, received.coverage!!.receivedWindows)
            assertEquals(0, received.coverage!!.appliedWindows)
            assertEquals(0, received.pendingProjectionCount)
            reopened.staged {
                putRemote(change); putProjection(ProjectionIntent(scope, change))
                coverage = appliedLedger; checkpoint = initial.copy(traversalComplete = true); this.received = null
            }
        }
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertEquals(appliedLedger, reopened.staged { coverage })
            assertNull(reopened.staged { received })
            val applied = reopened.selections(scope.account).single()
            assertTrue(applied.coverage!!.finished)
            assertEquals(1, applied.coverage!!.appliedWindows)
            assertEquals(1, applied.pendingProjectionCount)
            reopened.acknowledge(reopened.pending(scope).single())
            assertEquals(0, reopened.selections(scope.account).single().pendingProjectionCount)
            assertEquals(appliedLedger, reopened.staged { coverage })
        }
    }

    @Test fun receiptAndApplicationCoverageRollBackWithLeaseAndOtherWrites() = fixture { store, _ ->
        try {
            store.staged { received = receipt; coverage = receivedLedger; error("Interrupted receipt") }
            fail("Expected rollback")
        } catch (_: IllegalStateException) { }
        assertNull(store.staged { received }); assertEquals(CoverageLedger(), store.staged { coverage })
        store.staged { received = receipt; coverage = receivedLedger }
        var admitted = true
        store.admitTransaction = { admitted }
        try {
            store.staged {
                coverage = appliedLedger; received = null; checkpoint = initial.copy(traversalComplete = true)
                putRemote(change); putProjection(ProjectionIntent(scope, change)); admitted = false
            }
            fail("Expected lease rollback")
        } catch (_: IllegalStateException) { }
        finally { store.admitTransaction = { true } }
        assertEquals(receipt, store.staged { received }); assertEquals(receivedLedger, store.staged { coverage })
        assertNull(store.staged { checkpoint }); assertNull(store.staged { remote(change.eventId) })
        assertEquals(0, store.selections(scope.account).single().pendingProjectionCount)
    }

    @Test fun refreshRetainsPreviousFinishedRunAndDoesNotRestartNullCheckpointOrPendingInbox() = fixture { store, _ ->
        store.staged { coverage = appliedLedger; checkpoint = initial.copy(traversalComplete = true); received = receipt }
        store.beginRefresh(scope)
        assertEquals(appliedLedger, store.staged { coverage })
        assertNotNull(store.staged { checkpoint })
        assertEquals(receipt, store.staged { received })
        store.staged { received = null }
        store.beginRefresh(scope)
        val restarted = appliedLedger.restart()
        assertEquals(restarted, store.staged { coverage }); assertNull(store.staged { checkpoint })
        assertEquals(appliedLedger.active, restarted.previous)
        store.beginRefresh(scope)
        assertEquals(restarted, store.staged { coverage })
        store.staged { checkpoint = initial.copy(cursor = OpaqueCursor(format, "continuation")) }
        store.beginRefresh(scope)
        assertEquals(restarted, store.staged { coverage })
    }

    @Test fun coverageAndProjectionCountsStayScopedAndUnknownGenerationsAreRejected() = fixture { store, _ ->
        store.staged { coverage = appliedLedger; putRemote(change); putProjection(ProjectionIntent(scope, change)) }
        assertEquals(CoverageLedger(), store.staged(other) { coverage })
        assertNull(store.selections(other.account).single().coverage)
        assertEquals(0, store.selections(other.account).single().pendingProjectionCount)
        try {
            store.staged(other) { received = receipt; coverage = receivedLedger }
            fail("Expected foreign receipt rejection")
        } catch (_: IllegalArgumentException) { }
        try {
            store.staged(scope.copy(generation = 1)) { coverage = receivedLedger }
            fail("Expected unknown generation rejection")
        } catch (_: IllegalStateException) { }
        assertEquals(CoverageLedger(), store.staged(other) { coverage })
        assertEquals(appliedLedger, store.staged { coverage })
    }

    @Test fun receiptMetadataAndCoverageLedgerBothCountTowardsGlobalQuota() = fixture { store, _ ->
        val chunk = 4 * 1024 * 1024
        store.staged(other) {
            (1..8).forEach { index -> defer(DeferredCalendarEvent(EventId("large-$index"), Revision("v1"), format,
                DeferredEventReason.UNSUPPORTED_CONTENT, "x".repeat(if (index == 8) chunk - 1 else chunk))) }
        }
        try {
            store.staged { received = receipt.copy(protectedPayload = "x") }
            fail("Expected metadata quota rejection")
        } catch (_: ImportCapacityException) { }
        assertNull(store.staged { received })
        try {
            store.staged { coverage = receivedLedger; checkpoint = initial }
            fail("Expected ledger quota rejection")
        } catch (_: ImportCapacityException) { }
        assertEquals(CoverageLedger(), store.staged { coverage }); assertNull(store.staged { checkpoint })
        // The single payload byte fits exactly, proving metadata was what exceeded the budget.
        store.staged { received = receipt.copy(protectedPayload = "x", coverage = null) }
        assertEquals(receipt.copy(protectedPayload = "x", coverage = null), store.staged { received })
        // Inbox receipts consume the shared byte budget, but are not deferred event records.
        assertEquals(8, store.deferredCount(other))
        assertEquals(0, store.deferredCount(scope))
    }

    @Test fun oversizedCoveragePayloadRollsBackWithoutReplacingPriorObservation() = fixture { store, _ ->
        store.staged { coverage = receivedLedger }
        val oversized = receivedLedger.copy(active = receivedLedger.active!!.copy(format = AdapterFormat("x".repeat(256 * 1024), 1)))
        try {
            store.staged { received = receipt; coverage = oversized }
            fail("Expected bounded metadata rejection")
        } catch (_: IllegalArgumentException) { }
        assertEquals(receivedLedger, store.staged { coverage }); assertNull(store.staged { received })
    }

    @Test fun versionFourMigrationKeepsLegacyReceiptAndCompletionUnknownWithoutBackfill() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "coverage-legacy-v4-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("CREATE TABLE calendars (scope TEXT PRIMARY KEY, account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL, title TEXT NOT NULL, color INTEGER, enabled INTEGER NOT NULL DEFAULT 0, checkpoint TEXT, status TEXT NOT NULL DEFAULT 'PAUSED', completed_at INTEGER, next_attempt INTEGER NOT NULL DEFAULT 0, available INTEGER NOT NULL DEFAULT 1)")
                db.execSQL("CREATE TABLE records (scope TEXT NOT NULL, event TEXT NOT NULL, payload TEXT NOT NULL, pending INTEGER NOT NULL, PRIMARY KEY(scope,event))")
                db.execSQL("CREATE TABLE inbox (scope TEXT PRIMARY KEY, request_checkpoint TEXT NOT NULL, request_limit INTEGER NOT NULL, payload TEXT NOT NULL)")
                db.execSQL("CREATE TABLE deferred_events (scope TEXT NOT NULL, event TEXT NOT NULL, revision TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL, reason TEXT NOT NULL, payload TEXT NOT NULL, retry_pending INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(scope,event))")
                db.execSQL("CREATE TABLE delta_state (scope TEXT PRIMARY KEY, cursor_adapter TEXT, cursor_version INTEGER, cursor_value TEXT, received_adapter TEXT, received_version INTEGER, received_after TEXT, received_payload TEXT, more INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("CREATE TABLE delta_jobs (scope TEXT NOT NULL, notice_id TEXT NOT NULL, event TEXT, notice TEXT NOT NULL, received TEXT, blocked INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(scope,notice_id))")
                db.execSQL("CREATE TABLE delta_reconciliation (id INTEGER PRIMARY KEY, scope TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL, after_cursor TEXT NOT NULL, payload TEXT NOT NULL)")
                db.execSQL("CREATE INDEX delta_reconciliation_scope ON delta_reconciliation(scope)")
                val key = JSONArray().put(scope.account.value).put(scope.calendar.value).put(0).toString()
                val checkpoint = """{"adapter":"coverage-fixture","version":1,"cursor":null,"complete":true}"""
                db.execSQL("INSERT INTO calendars(scope,account,calendar,generation,title,checkpoint,status,completed_at) VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf<Any>(key, scope.account.value, scope.calendar.value, 0, "Fixture", checkpoint, "IMPORTED", 300L))
                db.execSQL("INSERT INTO records(scope,event,payload,pending) VALUES(?,?,?,?)",
                    arrayOf<Any>(key, "fixture-event", """{"id":"fixture-event","revision":"gone","deleted":true}""", 1))
                db.execSQL("INSERT INTO inbox(scope,request_checkpoint,request_limit,payload) VALUES(?,?,?,?)",
                    arrayOf<Any>(key, """{"adapter":"coverage-fixture","version":1,"cursor":"legacy","complete":false}""", 100, "legacy-sealed-page"))
                db.execSQL("INSERT INTO delta_reconciliation(scope,adapter,version,after_cursor,payload) VALUES(?,?,?,?,?)",
                    arrayOf<Any>(key, "delta-fixture", 1, "old", "sealed-history-gap"))
                db.version = 4
            }
            ImportStore(context, name).use { store ->
                assertEquals(15, store.readableDatabase.version)
                assertEquals(CoverageLedger(), store.staged { coverage })
                val selection = store.selections(scope.account).single()
                assertNull(selection.coverage); assertEquals(300L, selection.completedAt)
                assertTrue(selection.historyGap); assertEquals(1, selection.pendingProjectionCount)
                assertEquals(change, store.pending(scope).single().change)
                val legacy = store.staged { received }!!
                assertNull(legacy.coverage); assertEquals("legacy-sealed-page", legacy.protectedPayload)
                assertEquals("legacy", legacy.request.checkpoint.cursor!!.value)
                store.beginRefresh(scope)
                assertEquals(CoverageLedger(), store.staged { coverage })
                assertEquals(legacy, store.staged { received })
                assertTrue(store.complete(scope))
            }
        } finally { context.deleteDatabase(name) }
    }
}
