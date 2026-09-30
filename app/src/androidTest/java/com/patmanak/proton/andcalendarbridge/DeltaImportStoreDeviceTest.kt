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

/** Durable change-feed fixtures only; no remote service, account or Calendar Provider access. */
@RunWith(AndroidJUnit4::class)
class DeltaImportStoreDeviceTest {
    private val scope = CalendarScope(AccountId("delta-account"), CalendarId("delta-calendar"), 0)
    private val other = scope.copy(account = AccountId("other-delta-account"))
    private val format = AdapterFormat("event-fixture", 1)
    private val deltaFormat = AdapterFormat("delta-fixture", 1)
    private val before = OpaqueCursor(deltaFormat, "before")
    private val after = OpaqueCursor(deltaFormat, "after")
    private fun notice(id: String = "notice-z", event: String = "event-a") =
        CalendarChangeNotice(id, CalendarNoticeKind.EVENT_CHANGED, EventId(event))
    private fun job(id: String = "notice-z", event: String = "event-a", blocked: Boolean = false) =
        CalendarChangeJob(notice(id, event), blocked = blocked)
    private fun received(payload: String = "sealed-event", target: CalendarScope = scope) =
        ReceivedCalendarEvent(target, EventId("event-a"), Revision("v1"), format, payload)
    private fun page(payload: String = "sealed-changes", target: CalendarScope = scope) =
        ReceivedChangePage(target, before, payload)
    private fun change() = RemoteChange.Upsert(EventId("event-a"), Revision("v1"),
        EventContent("Fixture", EventTime.AllDay(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26))))

    private fun fixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "delta-import-fixture-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Fixture")))
            store.remember(other.account, listOf(CalendarSummary(other.calendar, "Other fixture")))
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun <T> ImportStore.delta(target: CalendarScope = scope, action: DeltaSyncTransaction.() -> T): T =
        transaction(target) { action(this as DeltaSyncTransaction) }

    @Test fun receivedChangesAndAppliedJobsUseAtomicCommitAndSurviveRestart() = fixture { store, name ->
        val receipt = page("x".repeat(3 * 1024 * 1024))
        store.delta { deltaCursor = before; deltaReceived = receipt }
        try {
            store.delta {
                putDeltaJob(job().copy(received = received())); deltaCursor = after
                deltaMore = true; deltaReceived = null
                error("Fixture interruption")
            }
            fail("Expected rollback")
        } catch (_: IllegalStateException) { }
        assertEquals(before, store.delta { deltaCursor })
        assertEquals(receipt.protectedPayload.length, store.delta { deltaReceived }?.protectedPayload?.length)
        assertTrue(store.delta { deltaJobs() }.isEmpty())
        val retained = job().copy(received = received("x".repeat(3 * 1024 * 1024)))
        store.delta {
            putDeltaJob(retained); deltaCursor = after; deltaMore = true; deltaReceived = null
        }
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertEquals(after, reopened.delta { deltaCursor })
            assertNull(reopened.delta { deltaReceived })
            assertTrue(reopened.delta { deltaMore })
            assertEquals(retained, reopened.delta { deltaJobs() }.single())
            assertFalse(reopened.complete(scope))
        }
    }

    @Test fun staleJobCompletionCannotDeleteNewEvidenceOrBlockedState() = fixture { store, _ ->
        val old = job()
        val newer = old.copy(received = received())
        store.delta { putDeltaJob(old); putDeltaJob(job("notice-a", "event-b")) }
        store.delta { putDeltaJob(newer) }
        assertFalse(store.delta { removeDeltaJob(old) })
        assertEquals(listOf("notice-z", "notice-a"), store.delta { deltaJobs() }.map { it.notice.id })
        val blocked = newer.copy(blocked = true)
        store.delta { putDeltaJob(blocked) }
        assertFalse(store.delta { removeDeltaJob(newer) })
        assertEquals(blocked, store.delta { deltaJobs(blocked = true) }.single())
        assertTrue(store.delta { removeDeltaJob(blocked) })
        assertEquals("notice-a", store.delta { deltaJobs() }.single().notice.id)
    }

    @Test fun ambiguousBlockedReadIsRetainedAndFreshPassRetriesItWithoutCrossScopeDeletion() = fixture { store, name ->
        val ambiguous = job(blocked = true)
        store.delta {
            putDeltaJob(ambiguous); putDeltaJob(job("notice-b", "event-b", blocked = true))
            putDeltaJob(job("notice-active", "event-a"))
        }
        store.delta(other) { putDeltaJob(ambiguous) }
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertEquals(2, reopened.delta { deltaJobs(blocked = true) }.size)
            reopened.delta { clearBlockedEvent(EventId("event-a")) }
            assertEquals("notice-b", reopened.delta { deltaJobs(blocked = true) }.single().notice.id)
            assertEquals("notice-active", reopened.delta { deltaJobs() }.single().notice.id)
            assertEquals(ambiguous, reopened.delta(other) { deltaJobs(blocked = true) }.single())
            reopened.delta { retryBlockedJobs() }
            assertTrue(reopened.delta { deltaJobs(blocked = true) }.isEmpty())
            assertEquals(listOf("notice-b", "notice-active"), reopened.delta { deltaJobs() }.map { it.notice.id })
        }
    }

    @Test fun deltaScopeKindFormatAndLeaseGuardsPreserveExistingEvidence() = fixture { store, _ ->
        store.delta { deltaCursor = before; deltaReceived = page() }
        val badJobs = listOf(
            job().copy(received = received(target = other)),
            job(event = "event-b").copy(received = received()),
            CalendarChangeJob(CalendarChangeNotice("context", CalendarNoticeKind.CONTEXT_CHANGED), received()),
        )
        badJobs.forEach {
            try { store.delta { putDeltaJob(it) }; fail("Expected invalid event receipt rejection") }
            catch (_: IllegalArgumentException) { }
        }
        try { store.delta { deltaReceived = page(target = other) }; fail("Expected foreign scope rejection") }
        catch (_: IllegalArgumentException) { }
        try { store.delta { deltaCursor = OpaqueCursor(AdapterFormat("different", 1), "next") }; fail("Expected format rejection") }
        catch (_: IllegalArgumentException) { }
        try { store.delta { deltaReceived = page("replacement") }; fail("Expected pending receipt rejection") }
        catch (_: IllegalStateException) { }
        var admitted = true
        store.admitTransaction = { admitted }
        try {
            store.delta { putDeltaJob(job()); deltaReceived = null; deltaCursor = after; admitted = false }
            fail("Expected inactive lease rejection")
        } catch (_: IllegalStateException) { }
        store.admitTransaction = { true }
        assertEquals(before, store.delta { deltaCursor })
        assertNotNull(store.delta { deltaReceived })
        assertTrue(store.delta { deltaJobs() }.isEmpty())
    }

    @Test fun deltaReceiptsAndUnresolvedNoticesShareTheGlobalRetentionBudget() = fixture { store, _ ->
        val large = "x".repeat(4 * 1024 * 1024)
        store.delta {
            (1..7).forEach { defer(DeferredCalendarEvent(EventId("event-$it"), Revision("v1"), format,
                DeferredEventReason.UNSUPPORTED_CONTENT, large)) }
            deltaCursor = before; deltaReceived = page(large)
        }
        try {
            store.delta { putDeltaJob(job()); deltaCursor = after }
            fail("Expected shared staging capacity rejection")
        } catch (_: ImportCapacityException) { }
        assertEquals(before, store.delta { deltaCursor })
        assertTrue(store.delta { deltaJobs() }.isEmpty())
        store.delta { deltaReceived = null; putDeltaJob(job().copy(received = received())) }
        assertEquals(1, store.delta { deltaJobs() }.size)
    }

    @Test fun authoritativeCatalogAbsenceAndReappearancePreserveSelectionAndPendingData() = fixture { store, _ ->
        store.setEnabled(scope, true)
        store.delta {
            putRemote(change()); putProjection(ProjectionIntent(scope, change()))
            deltaCursor = before; putDeltaJob(job())
        }
        store.remember(scope.account, emptyList())
        val absent = store.selections(scope.account).single()
        assertFalse(absent.available)
        assertTrue(absent.enabled)
        assertEquals("UNAVAILABLE", absent.status)
        store.status(scope, "IMPORTED", 123L)
        store.setEnabled(scope, true)
        assertEquals("UNAVAILABLE", store.selections(scope.account).single().status)
        assertNull(store.selections(scope.account).single().completedAt)
        assertEquals(change(), store.pending(scope).single().change)
        assertEquals(job(), store.delta { deltaJobs() }.single())
        assertTrue(store.selections(other.account).single().available)
        store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Renamed fixture", 0x123456)))
        val present = store.selections(scope.account).single()
        assertTrue(present.available)
        assertTrue(present.enabled)
        assertEquals("PENDING", present.status)
        assertEquals("Renamed fixture", present.summary.title)
        assertEquals(0x123456, present.summary.colorRgb)
        assertEquals(before, store.delta { deltaCursor })
        assertEquals(1, store.pending(scope).size)
    }

    @Test fun resetArchiveCommitsWithCursorAndScanAndSurvivesReopenWithoutLosingJobs() = fixture { store, name ->
        val checkpoint = PageCheckpoint(scope, format, null, true)
        val tombstone = RemoteChange.Deleted(EventId("deleted-event"), Revision("gone"))
        val unsupported = DeferredCalendarEvent(EventId("unsupported"), Revision("v1"), format,
            DeferredEventReason.UNSUPPORTED_CONTENT, "sealed-unsupported")
        val pendingJob = job().copy(received = received())
        store.delta {
            this.checkpoint = checkpoint; deltaCursor = before; deltaReceived = page()
            putRemote(change()); putProjection(ProjectionIntent(scope, change())); putRemote(tombstone)
            defer(unsupported); putDeltaJob(pendingJob); putDeltaJob(job("blocked", "blocked-event", true))
        }
        store.delta {
            retainReconciliation(deltaReceived!!); deltaCursor = after; deltaMore = true
            deltaReceived = null; this.checkpoint = null
        }
        store.status(scope, "HISTORY_GAP")
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            val selection = reopened.selections(scope.account).single()
            assertTrue(selection.historyGap)
            assertEquals("HISTORY_GAP", selection.status)
            assertFalse(reopened.selections(other.account).single().historyGap)
            reopened.delta {
                assertEquals(deltaFormat, reconciliationFormat)
                assertEquals(after, deltaCursor); assertTrue(deltaMore); assertNull(deltaReceived)
                assertNull(this.checkpoint); assertEquals(change(), remote(change().eventId))
                assertEquals(tombstone, remote(tombstone.eventId)); assertEquals(unsupported, deferred(unsupported.eventId))
                assertEquals(pendingJob, deltaJobs().single())
                assertEquals("blocked", deltaJobs(blocked = true).single().notice.id)
            }
            assertEquals(change(), reopened.pending(scope).single().change)
            reopened.readableDatabase.rawQuery("SELECT adapter,version,after_cursor,payload FROM delta_reconciliation", null).use {
                assertTrue(it.moveToFirst()); assertEquals(deltaFormat.id, it.getString(0)); assertEquals(deltaFormat.version, it.getInt(1))
                assertEquals(before.value, it.getString(2)); assertEquals(page().protectedPayload, it.getString(3)); assertFalse(it.moveToNext())
            }
            reopened.beginRefresh(scope)
            reopened.delta { this.checkpoint = checkpoint; deltaMore = false }
            reopened.setEnabled(scope, false)
            reopened.remember(scope.account, emptyList())
            reopened.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Fixture")))
            assertTrue(reopened.selections(scope.account).single().historyGap)
        }
    }

    @Test fun interruptedResetAndRevokedLeaseRollBackArchiveCursorAndCheckpointTogether() = fixture { store, _ ->
        val checkpoint = PageCheckpoint(scope, format, null, true)
        store.delta { this.checkpoint = checkpoint; deltaCursor = before; deltaReceived = page() }
        for (revokeLease in listOf(false, true)) {
            var admitted = true
            store.admitTransaction = { admitted }
            try {
                store.delta {
                    retainReconciliation(deltaReceived!!); deltaCursor = after; deltaMore = true
                    deltaReceived = null; this.checkpoint = null
                    if (revokeLease) admitted = false else error("Fixture interruption")
                }
                fail("Expected reset rollback")
            } catch (_: IllegalStateException) { }
            finally { store.admitTransaction = { true } }
            store.delta {
                assertNull(reconciliationFormat); assertEquals(before, deltaCursor); assertFalse(deltaMore)
                assertEquals(page(), deltaReceived); assertEquals(checkpoint, this.checkpoint)
            }
            assertFalse(store.selections(scope.account).single().historyGap)
        }
    }

    @Test fun archiveRejectsForeignScopesAndIncompatibleFormatsInBothDirections() = fixture { store, _ ->
        val incompatible = OpaqueCursor(AdapterFormat("another-delta", 2), "other")
        suspend fun rejected(action: DeltaSyncTransaction.() -> Unit) {
            try { store.delta(action = action); fail("Expected archive scope or format rejection") }
            catch (_: IllegalArgumentException) { }
        }
        store.delta { deltaCursor = before; deltaReceived = page() }
        rejected { retainReconciliation(page(target = other)) }
        rejected { retainReconciliation(ReceivedChangePage(scope, incompatible, "sealed-other")) }
        assertFalse(store.selections(scope.account).single().historyGap)
        store.delta { retainReconciliation(page()); deltaReceived = null; deltaCursor = null }
        rejected { retainReconciliation(ReceivedChangePage(scope, incompatible, "sealed-other")) }
        rejected { deltaCursor = incompatible }
        rejected { deltaReceived = ReceivedChangePage(scope, incompatible, "sealed-other") }
        store.delta { retainReconciliation(ReceivedChangePage(scope, after, "sealed-next-reset")) }
        store.delta(other) { retainReconciliation(ReceivedChangePage(other, incompatible, "sealed-other-scope")) }
        assertEquals(deltaFormat, store.delta { reconciliationFormat })
        assertEquals(incompatible.format, store.delta(other) { reconciliationFormat })
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM delta_reconciliation", null).use {
            assertTrue(it.moveToFirst()); assertEquals(3, it.getInt(0))
        }
        try {
            store.delta(scope.copy(generation = 1)) { retainReconciliation(page(target = scope.copy(generation = 1))) }
            fail("Expected unknown generation rejection")
        } catch (_: IllegalStateException) { }
    }

    @Test fun archiveAndPendingReceiptUseFinalTransactionSizeAndShareQuotaAcrossScopes() = fixture { store, name ->
        val large = "x".repeat(4 * 1024 * 1024)
        store.delta(other) {
            (1..7).forEach { defer(DeferredCalendarEvent(EventId("event-$it"), Revision("v1"), format,
                DeferredEventReason.UNSUPPORTED_CONTENT, large)) }
        }
        store.delta { deltaCursor = before; deltaReceived = page(large) }
        // The receipt temporarily exists twice inside the transaction; final retained bytes stay at 32 MiB.
        store.delta { retainReconciliation(deltaReceived!!); deltaReceived = null; deltaCursor = after }
        try {
            store.delta { retainReconciliation(ReceivedChangePage(scope, after, "x")); deltaMore = true }
            fail("Expected global archive capacity rejection")
        } catch (_: ImportCapacityException) { }
        assertFalse(store.delta { deltaMore })
        assertEquals(after, store.delta { deltaCursor })
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertTrue(reopened.selections(scope.account).single().historyGap)
            assertFalse(reopened.selections(other.account).single().historyGap)
            reopened.readableDatabase.rawQuery("SELECT COUNT(*),SUM(length(CAST(payload AS BLOB))) FROM delta_reconciliation", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertEquals(4 * 1024 * 1024, it.getInt(1))
            }
            try {
                reopened.delta(other) { deltaCursor = before; putDeltaJob(job()) }
                fail("Expected archive to count against another scope's budget")
            } catch (_: ImportCapacityException) { }
            assertNull(reopened.delta(other) { deltaCursor })
            assertTrue(reopened.delta(other) { deltaJobs() }.isEmpty())
        }
    }

    @Test fun schemaOneTwoAndThreeUpgradeWithoutLosingPriorState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (version in 1..3) {
            val name = "legacy-delta-v$version-${UUID.randomUUID()}.db"
            val path = context.getDatabasePath(name)
            path.parentFile!!.mkdirs()
            try {
                SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                    db.execSQL("CREATE TABLE calendars (scope TEXT PRIMARY KEY, account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL, title TEXT NOT NULL, color INTEGER, enabled INTEGER NOT NULL DEFAULT 0, checkpoint TEXT, status TEXT NOT NULL DEFAULT 'PAUSED', completed_at INTEGER, next_attempt INTEGER NOT NULL DEFAULT 0)")
                    db.execSQL("CREATE TABLE records (scope TEXT NOT NULL, event TEXT NOT NULL, payload TEXT NOT NULL, pending INTEGER NOT NULL, PRIMARY KEY(scope,event))")
                    val scopeKey = JSONArray().put(scope.account.value).put(scope.calendar.value).put(0).toString()
                    db.execSQL("INSERT INTO calendars(scope,account,calendar,generation,title,enabled,status) VALUES(?,?,?,?,?,?,?)",
                        arrayOf<Any>(scopeKey, scope.account.value, scope.calendar.value, 0, "Fixture", 1, "PENDING"))
                    db.execSQL("INSERT INTO records(scope,event,payload,pending) VALUES(?,?,?,?)",
                        arrayOf<Any>(scopeKey, "legacy-deleted", """{"id":"legacy-deleted","revision":"gone","deleted":true}""", 1))
                    if (version >= 2) {
                        db.execSQL("CREATE TABLE inbox (scope TEXT PRIMARY KEY, request_checkpoint TEXT NOT NULL, request_limit INTEGER NOT NULL, payload TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE deferred_events (scope TEXT NOT NULL, event TEXT NOT NULL, revision TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL, reason TEXT NOT NULL, payload TEXT NOT NULL, retry_pending INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(scope,event))")
                        db.execSQL("INSERT INTO inbox(scope,request_checkpoint,request_limit,payload) VALUES(?,?,?,?)", arrayOf<Any>(scopeKey,
                            """{"adapter":"event-fixture","version":1,"cursor":null,"complete":false}""", 100, "legacy-sealed-page"))
                        db.execSQL("INSERT INTO deferred_events(scope,event,revision,adapter,version,reason,payload,retry_pending) VALUES(?,?,?,?,?,?,?,?)",
                            arrayOf<Any>(scopeKey, "event-a", "v1", "event-fixture", 1, "UNSUPPORTED_CONTENT", "legacy-sealed-event", 1))
                    }
                    if (version == 3) {
                        db.execSQL("ALTER TABLE calendars ADD COLUMN available INTEGER NOT NULL DEFAULT 1")
                        db.execSQL("CREATE TABLE delta_state (scope TEXT PRIMARY KEY, cursor_adapter TEXT, cursor_version INTEGER, cursor_value TEXT, received_adapter TEXT, received_version INTEGER, received_after TEXT, received_payload TEXT, more INTEGER NOT NULL DEFAULT 0)")
                        db.execSQL("CREATE TABLE delta_jobs (scope TEXT NOT NULL, notice_id TEXT NOT NULL, event TEXT, notice TEXT NOT NULL, received TEXT, blocked INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(scope,notice_id))")
                        db.execSQL("INSERT INTO delta_state(scope,cursor_adapter,cursor_version,cursor_value,received_adapter,received_version,received_after,received_payload,more) VALUES(?,?,?,?,?,?,?,?,?)",
                            arrayOf<Any>(scopeKey, deltaFormat.id, deltaFormat.version, before.value, deltaFormat.id, deltaFormat.version, before.value, "legacy-sealed-changes", 1))
                        db.execSQL("INSERT INTO delta_jobs(scope,notice_id,event,notice,received,blocked) VALUES(?,?,?,?,?,?)",
                            arrayOf<Any>(scopeKey, "legacy-notice", "event-a", """{"id":"legacy-notice","kind":"EVENT_CHANGED","event":"event-a"}""",
                                """{"event":"event-a","revision":"v1","adapter":"event-fixture","version":1,"payload":"legacy-job-receipt"}""", 1))
                    }
                    db.version = version
                }
                ImportStore(context, name).use { store ->
                    assertTrue(store.selections(scope.account).single().available)
                    assertTrue(store.selections(scope.account).single().enabled)
                    assertEquals(10, store.readableDatabase.version)
                    assertFalse(store.selections(scope.account).single().historyGap)
                    assertNull(store.delta { reconciliationFormat })
                    assertEquals(RemoteChange.Deleted(EventId("legacy-deleted"), Revision("gone")), store.pending(scope).single().change)
                    if (version >= 2) {
                        assertEquals("legacy-sealed-page", store.delta { received }?.protectedPayload)
                        assertEquals("legacy-sealed-event", store.pendingDeferredRetries(scope).single().protectedPayload)
                    }
                    if (version == 3) {
                        assertEquals(before, store.delta { deltaCursor })
                        assertTrue(store.delta { deltaMore })
                        assertEquals("legacy-sealed-changes", store.delta { deltaReceived }?.protectedPayload)
                        val retained = store.delta { deltaJobs(blocked = true) }.single()
                        assertEquals("legacy-notice", retained.notice.id)
                        assertEquals("legacy-job-receipt", retained.received?.protectedPayload)
                        store.delta { retainReconciliation(deltaReceived!!); deltaReceived = null; deltaCursor = after }
                        assertTrue(store.selections(scope.account).single().historyGap)
                        assertEquals(retained, store.delta { deltaJobs(blocked = true) }.single())
                    } else {
                        assertNull(store.delta { deltaCursor })
                        assertFalse(store.delta { deltaMore })
                    }
                    store.delta { deltaCursor = before; putDeltaJob(job()) }
                    assertEquals(job(), store.delta { deltaJobs() }.single())
                }
            } finally { context.deleteDatabase(name) }
        }
    }
}
