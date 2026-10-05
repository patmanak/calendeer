// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated SQLite and fake-remote fixtures only; no session, network or shared Provider access. */
@RunWith(AndroidJUnit4::class)
class LocalEditStoreDeviceTest {
    private val scope = CalendarScope(AccountId("local-edit-fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("local-edit-fixture", 1)
    private val base = RemoteChange.Upsert(EventId("fixture-event"), Revision("opaque-one"),
        EventContent("Fixture", EventTime.AllDay(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)), colorRgb = 0x123456))
    private fun edit(value: RemoteChange.Upsert = base) = LocalEditIntent(MutationId("fixture-edit"), scope, format,
        value, value.content.copy(title = "Locally edited"))
    private fun fixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "local-edit-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Fixture")))
            store.setEnabled(scope, true)
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun ImportStore.queue(change: RemoteChange) = transaction(scope) {
        putRemote(change); putProjection(ProjectionIntent(scope, change))
    }
    private suspend fun ImportStore.establish() {
        queue(base); assertEquals(1, acknowledge(ProjectionIntent(scope, base)))
    }
    private suspend fun refused(block: suspend () -> Unit) {
        try { block(); fail("Expected immutable or inactive capture refusal") } catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
        catch (_: SQLiteException) { }
    }

    @Test fun baselineExistsOnlyAfterMatchingAcknowledgementAndRetainsOlderSnapshotDuringImport() = fixture { store, _ ->
        store.queue(base); assertNull(store.projectedBaseline(scope, base.eventId))
        assertEquals(1, store.acknowledge(ProjectionIntent(scope, base)))
        assertEquals(base, store.projectedBaseline(scope, base.eventId))
        val newer = base.copy(revision = Revision("opaque-two"))
        store.queue(newer)
        assertEquals(0, store.acknowledge(ProjectionIntent(scope, base)))
        assertEquals(base, store.projectedBaseline(scope, base.eventId))
        assertEquals(1, store.acknowledge(ProjectionIntent(scope, newer)))
        assertEquals(newer, store.projectedBaseline(scope, base.eventId))
    }
    @Test fun tombstoneKeepsBaselineUntilSuccessfulDeletionAcknowledgement() = fixture { store, _ ->
        store.establish()
        val deleted = RemoteChange.Deleted(base.eventId, Revision("opaque-gone"))
        store.queue(deleted)
        assertEquals(base, store.projectedBaseline(scope, base.eventId))
        assertEquals(1, store.acknowledge(ProjectionIntent(scope, deleted)))
        assertNull(store.projectedBaseline(scope, base.eventId))
    }
    @Test fun captureIsDurableImmutableAndSuppressesEveryImportProjectionPath() = fixture { store, name ->
        store.establish(); assertEquals(edit(), store.captureLocalEdit(edit()))
        assertEquals(edit(), store.captureLocalEdit(edit()))
        store.requeueAll(scope)
        store.transaction(scope) {
            assertTrue(hasPendingMutation(base.eventId)); assertEquals(base, remote(base.eventId))
            putProjection(ProjectionIntent(scope, base))
            assertEquals(CalendarAccess(), access); assertNull(mutation(edit().id))
        }
        assertTrue(store.pending(scope).isEmpty()); assertNull(store.stabilizedProjection(scope, base.eventId))
        assertEquals(0, store.selections(scope.account).single().pendingProjectionCount)
        assertEquals(0, store.acknowledge(ProjectionIntent(scope, base)))
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertEquals(edit(), reopened.localEdit(scope, base.eventId))
            assertEquals(base, reopened.projectedBaseline(scope, base.eventId))
            assertEquals(base, reopened.transaction(scope) { remote(base.eventId) })
            assertTrue(reopened.pending(scope).isEmpty())
        }
    }
    @Test fun changedRemoteContentWithSameOpaqueRevisionConflictsAndNeverResets() = fixture { store, _ ->
        store.establish(); store.captureLocalEdit(edit())
        val changed = base.copy(content = base.content.copy(description = "Remote edit"))
        store.queue(changed)
        val conflicted = edit().copy(state = LocalEditState.CONFLICT)
        assertEquals(conflicted, store.localEdit(scope, base.eventId))
        assertEquals(changed, store.transaction(scope) { remote(base.eventId) })
        assertEquals(base, store.projectedBaseline(scope, base.eventId))
        store.queue(base); assertEquals(conflicted, store.captureLocalEdit(edit()))
        assertTrue(store.pending(scope).isEmpty())
    }
    @Test fun newRemoteRevisionArrivingBeforeCaptureIsRetainedAsConflictAtomically() = fixture { store, _ ->
        store.establish()
        val remote = base.copy(revision = Revision("opaque-new"), content = base.content.copy(location = "Remote place"))
        store.queue(remote)
        assertEquals(1, store.pending(scope).size)
        assertEquals(edit().copy(state = LocalEditState.CONFLICT), store.captureLocalEdit(edit()))
        assertEquals(remote, store.transaction(scope) { remote(base.eventId) })
        assertTrue(store.pending(scope).isEmpty())
    }
    @Test fun tombstoneWithLocalIntentConflictsAndCannotDeleteLocalProjection() = fixture { store, _ ->
        store.establish(); store.captureLocalEdit(edit())
        val gone = RemoteChange.Deleted(base.eventId, Revision("gone"))
        store.queue(gone); store.requeueAll(scope)
        assertEquals(gone, store.transaction(scope) { remote(base.eventId) })
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)?.state)
        assertEquals(base, store.projectedBaseline(scope, base.eventId))
        assertTrue(store.pending(scope).isEmpty())
        assertEquals(0, store.acknowledge(ProjectionIntent(scope, gone)))
    }
    @Test fun deferredEvidenceBlocksCapturedIntentWithoutReplacingEitherContent() = fixture { store, _ ->
        store.establish(); store.captureLocalEdit(edit())
        val evidence = DeferredCalendarEvent(base.eventId, Revision("unmapped"), format,
            DeferredEventReason.UNSUPPORTED_CONTENT, "sealed-fixture")
        store.transaction(scope) { (this as StagedSyncTransaction).defer(evidence) }
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)?.state)
        store.transaction(scope) { (this as StagedSyncTransaction).clearDeferred(base.eventId) }
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)?.state)
        assertEquals(base, store.transaction(scope) { remote(base.eventId) })
        assertTrue(store.pending(scope).isEmpty())
    }
    @Test fun deferredArrivingBeforeCaptureConservativelyRetainsConflict() = fixture { store, _ ->
        store.establish()
        store.transaction(scope) { (this as StagedSyncTransaction).defer(DeferredCalendarEvent(base.eventId,
            Revision("unmapped"), format, DeferredEventReason.UNSUPPORTED_CONTENT, "sealed-fixture")) }
        assertEquals(LocalEditState.CONFLICT, store.captureLocalEdit(edit()).state)
        assertEquals(1, store.deferredCount(scope))
    }
    @Test fun unknownForgedAndInactiveScopesCannotAdmitEdits() = fixture { store, _ ->
        refused { store.captureLocalEdit(edit()) }
        store.establish()
        refused { store.captureLocalEdit(edit(base.copy(revision = Revision("forged")))) }
        refused { store.captureLocalEdit(edit().copy(scope = scope.copy(generation = 1))) }
        refused { store.captureLocalEdit(edit().copy(scope = scope.copy(account = AccountId("other")))) }
        store.setEnabled(scope, false); refused { store.captureLocalEdit(edit()) }
        store.setEnabled(scope, true); store.remember(scope.account, emptyList()); refused { store.captureLocalEdit(edit()) }
        assertNull(store.localEdit(scope, base.eventId))
    }
    @Test fun localIdentityCannotBeReusedOrAlteredForAnotherEvent() = fixture { store, _ ->
        store.establish(); store.captureLocalEdit(edit())
        refused { store.captureLocalEdit(edit().copy(id = MutationId("second"))) }
        refused { store.captureLocalEdit(edit().copy(content = base.content.copy(title = "Different"))) }
        refused { store.captureLocalEdit(edit().copy(format = format.copy(version = 2))) }
        refused { store.captureLocalEdit(edit().copy(state = LocalEditState.CONFLICT)) }
        val other = base.copy(eventId = EventId("other"))
        store.queue(other); store.acknowledge(ProjectionIntent(scope, other))
        refused { store.captureLocalEdit(edit(other)) }
        assertNull(store.localEdit(scope, other.eventId)); assertEquals(edit(), store.localEdit(scope, base.eventId))
    }
    @Test fun leaseLossRollsBackCaptureAndProjectionSuppression() = fixture { store, _ ->
        store.establish(); store.queue(base)
        var calls = 0
        store.admitTransaction = { ++calls == 1 }
        refused { store.captureLocalEdit(edit()) }
        store.admitTransaction = { true }
        assertNull(store.localEdit(scope, base.eventId)); assertEquals(base, store.pending(scope).single().change)
        store.admitTransaction = { false }; refused { store.captureLocalEdit(edit()) }
        store.admitTransaction = { true }; assertNull(store.localEdit(scope, base.eventId))
    }
    @Test fun cancelledCaptureRollsBackEvenAfterInsertBeforeCommit() = fixture { store, _ ->
        store.establish(); store.queue(base)
        val child = Job(currentCoroutineContext()[Job]); var calls = 0
        store.admitTransaction = { if (++calls == 2) child.cancel(); true }
        try { withContext(child) { store.captureLocalEdit(edit()) }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        finally { store.admitTransaction = { true } }
        assertNull(store.localEdit(scope, base.eventId)); assertEquals(base, store.pending(scope).single().change)
    }
    @Test fun failedInsertRollsBackWithoutHidingPendingImport() = fixture { store, _ ->
        store.establish(); store.queue(base)
        store.writableDatabase.execSQL("CREATE TRIGGER refuse_local BEFORE INSERT ON local_edits BEGIN SELECT RAISE(ABORT,'fixture'); END")
        refused { store.captureLocalEdit(edit()) }
        assertNull(store.localEdit(scope, base.eventId)); assertEquals(base, store.pending(scope).single().change)
    }
    @Test fun baselineAcknowledgementAndConflictUpdatesRollBackWithRevokedLease() = fixture { store, _ ->
        store.queue(base); var calls = 0; store.admitTransaction = { ++calls == 1 }
        refused { store.acknowledge(ProjectionIntent(scope, base)) }
        store.admitTransaction = { true }; assertNull(store.projectedBaseline(scope, base.eventId))
        assertEquals(base, store.pending(scope).single().change)
        store.acknowledge(ProjectionIntent(scope, base)); store.captureLocalEdit(edit())
        calls = 0; store.admitTransaction = { ++calls == 1 }
        refused { store.queue(base.copy(revision = Revision("new"))) }
        store.admitTransaction = { true }
        assertEquals(edit(), store.localEdit(scope, base.eventId)); assertEquals(base, store.transaction(scope) { remote(base.eventId) })
    }
    @Test fun cancellationInsideImportTransactionRollsBackRemoteAndConflictTogether() = fixture { store, _ ->
        store.establish(); store.captureLocalEdit(edit())
        val child = Job(currentCoroutineContext()[Job])
        try {
            withContext(child) {
                store.transaction(scope) {
                    putRemote(base.copy(revision = Revision("cancelled-remote")))
                    child.cancel()
                }
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(base, store.transaction(scope) { remote(base.eventId) })
        assertEquals(edit(), store.localEdit(scope, base.eventId))
        assertEquals(base, store.projectedBaseline(scope, base.eventId))
    }
    @Test fun pageEngineKeepsLocalIntentWhileCommittingRemoteAndCheckpoint() = fixture { store, _ ->
        store.establish(); store.captureLocalEdit(edit())
        val newer = base.copy(revision = Revision("remote-page"))
        var fetches = 0; var dispatches = 0
        val remote = object : RemoteCalendarAdapter {
            override val format = this@LocalEditStoreDeviceTest.format
            override suspend fun fetch(request: PageRequest): RemotePage {
                fetches++; return RemotePage(scope, format, listOf(newer), null, true)
            }
            override suspend fun dispatch(mutation: Mutation): DispatchResult { dispatches++; error("No writes authorized") }
        }
        assertEquals(ImportProgress(1, true), SyncEngine(store, remote, AccountSerialExecutor()).importPages(scope))
        assertEquals(1, fetches); assertEquals(0, dispatches)
        assertTrue(store.complete(scope)); assertEquals(newer, store.transaction(scope) { remote(base.eventId) })
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)?.state)
        assertTrue(store.pending(scope).isEmpty())
    }
    @Test fun sealedPageEngineProcessesReceiptWithoutNetworkAndPreservesConflict() = fixture { store, _ ->
        store.establish(); store.captureLocalEdit(edit())
        val checkpoint = PageCheckpoint(scope, format)
        val receipt = ReceivedCalendarPage(PageRequest(checkpoint, 100), "sealed-local-page")
        store.transaction(scope) { (this as StagedSyncTransaction).received = receipt }
        val newer = base.copy(revision = Revision("sealed-page"))
        var receives = 0; var decodes = 0
        val remote = object : StagedCalendarRemote {
            override val format = this@LocalEditStoreDeviceTest.format
            override suspend fun currentAccount(): AccountId = scope.account
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No writes authorized")
            override suspend fun fetch(request: PageRequest): RemotePage = error("Receipt path only")
            override suspend fun receive(request: PageRequest): ReceivedCalendarPage { receives++; error("Network disabled") }
            override suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage {
                assertEquals(receipt, received); decodes++
                return DecodedCalendarPage(RemotePage(scope, format, listOf(newer), null, true))
            }
            override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? = null
        }
        assertEquals(ImportProgress(1, true), StagedImportEngine(store, remote, AccountSerialExecutor()).run(scope, false))
        assertEquals(0, receives); assertEquals(1, decodes)
        assertNull(store.transaction(scope) { (this as StagedSyncTransaction).received })
        assertEquals(newer, store.transaction(scope) { remote(base.eventId) })
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)?.state)
        assertTrue(store.pending(scope).isEmpty())
    }
    @Test fun changedSimpleEventBecomingSeriesChildCannotBeRequeuedThroughItsMaster() = fixture { store, _ ->
        store.establish(); store.captureLocalEdit(edit())
        val master = base.copy(eventId = EventId("master"), content = base.content.copy(
            recurrence = EventRecurrence("series-fixture", "FREQ=DAILY")))
        val child = base.copy(revision = Revision("child"), content = base.content.copy(
            recurrence = EventRecurrence("series-fixture", original = RecurrencePoint.AllDay(LocalDate.of(2026, 10, 1)))))
        store.queue(child); store.queue(master)
        assertEquals(listOf(master), store.pending(scope).map { it.change })
        store.acknowledge(ProjectionIntent(scope, master))
        store.queue(master.copy(revision = Revision("master-new")))
        store.requeueAll(scope)
        assertEquals(listOf(master.copy(revision = Revision("master-new"))), store.pending(scope).map { it.change })
        assertEquals(0, store.blockedSeriesCount(scope))
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)?.state)
    }

    @Test fun independentlyConstructedSchemaSevenMigratesOnlySettledUpsertsAndPreservesEvidence() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "local-migration-${UUID.randomUUID()}.db"
        val scopeKey = JSONArray().put(scope.account.value).put(scope.calendar.value).put(scope.generation).toString()
        fun payload(id: String, deleted: Boolean = false) = JSONObject().apply {
            put("id", id); put("revision", "legacy-revision"); put("deleted", deleted)
            if (!deleted) { put("title", "Legacy"); put("description", ""); put("location", "")
                put("allDay", true); put("start", "2026-10-01"); put("end", "2026-10-02") }
        }.toString()
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
                db.execSQL("CREATE TABLE calendars (scope TEXT PRIMARY KEY, account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL, title TEXT NOT NULL, color INTEGER, enabled INTEGER NOT NULL DEFAULT 0, checkpoint TEXT, status TEXT NOT NULL DEFAULT 'PAUSED', completed_at INTEGER, next_attempt INTEGER NOT NULL DEFAULT 0, available INTEGER NOT NULL DEFAULT 1)")
                db.execSQL("CREATE TABLE records (scope TEXT NOT NULL,event TEXT NOT NULL,payload TEXT NOT NULL,pending INTEGER NOT NULL,PRIMARY KEY(scope,event))")
                db.execSQL("CREATE TABLE deferred_events (scope TEXT NOT NULL,event TEXT NOT NULL,revision TEXT NOT NULL,adapter TEXT NOT NULL,version INTEGER NOT NULL,reason TEXT NOT NULL,payload TEXT NOT NULL,retry_pending INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(scope,event))")
                db.execSQL("CREATE TABLE mapping_profiles(scope TEXT PRIMARY KEY,adapter TEXT NOT NULL,version INTEGER NOT NULL,rescan_pending INTEGER NOT NULL DEFAULT 1)")
                db.execSQL("INSERT INTO calendars(scope,account,calendar,generation,title,enabled) VALUES(?,?,?,?,?,1)",
                    arrayOf(scopeKey,scope.account.value,scope.calendar.value,0,"Fixture"))
                listOf("stable" to 0, "pending" to 1, "deferred" to 0, "deleted" to 0).forEach { (id, pending) ->
                    db.execSQL("INSERT INTO records VALUES(?,?,?,?)", arrayOf(scopeKey,id,payload(id,id == "deleted"),pending))
                }
                db.execSQL("INSERT INTO deferred_events(scope,event,revision,adapter,version,reason,payload) VALUES(?,?,?,?,?,?,?)",
                    arrayOf(scopeKey,"deferred","unmapped","legacy",1,"UNSUPPORTED_CONTENT","retained-sealed"))
                db.execSQL("INSERT INTO mapping_profiles VALUES(?,?,?,?)", arrayOf(scopeKey,"legacy",7,1))
                db.version = 7
            }
            ImportStore(context, name).use { store ->
                assertEquals(17, store.readableDatabase.version)
                assertEquals("Legacy", store.projectedBaseline(scope, EventId("stable"))?.content?.title)
                listOf("pending","deferred","deleted").forEach { assertNull(store.projectedBaseline(scope, EventId(it))) }
                assertNull(store.localEdit(scope, EventId("stable")))
                store.readableDatabase.rawQuery("SELECT event,payload,pending FROM records ORDER BY event", null).use { cursor ->
                    assertEquals(4, cursor.count)
                    while (cursor.moveToNext()) { val id = cursor.getString(0); assertEquals(payload(id,id == "deleted"), cursor.getString(1)) }
                }
                assertEquals(1, store.deferredCount(scope)); assertTrue(store.mappingRescanPending(scope))
            }
        } finally { context.deleteDatabase(name) }
    }
}
