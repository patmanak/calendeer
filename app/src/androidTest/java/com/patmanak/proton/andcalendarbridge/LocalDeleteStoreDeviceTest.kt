// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
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

/** Isolated synthetic SQLite fixtures; no account, network or shared Calendar Provider access. */
@RunWith(AndroidJUnit4::class)
class LocalDeleteStoreDeviceTest {
    private val scope = CalendarScope(AccountId("local-delete-fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("local-delete-fixture", 1)
    private val base = RemoteChange.Upsert(EventId("fixture-event"), Revision("opaque-original"),
        EventContent("Fixture", EventTime.AllDay(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)), colorRgb = 0x123456))
    private fun deletion(value: RemoteChange.Upsert = base, content: EventContent = value.content) =
        LocalDeleteIntent(MutationId("fixture-delete"), scope, format, value, content)
    private fun update(value: RemoteChange.Upsert = base) = LocalEditIntent(MutationId("fixture-update"), scope, format,
        value, value.content.copy(title = "Updated fixture"))
    private fun fixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "local-delete-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Fixture"))); store.setEnabled(scope, true)
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun ImportStore.queue(change: RemoteChange) = transaction(scope) {
        putRemote(change); putProjection(ProjectionIntent(scope, change))
    }
    private suspend fun ImportStore.establish(value: RemoteChange.Upsert = base) {
        queue(value); assertEquals(1, acknowledge(ProjectionIntent(scope, value)))
    }
    private suspend fun refused(block: suspend () -> Unit) {
        try { block(); fail("Expected inactive or immutable mutation refusal") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
        catch (_: SQLiteException) { }
    }

    @Test fun unchangedTombstoneSnapshotIsCapturedPausedWithoutInventingRemoteDeletion() = fixture { store, _ ->
        store.establish(); store.queue(base)
        assertEquals(deletion(), store.captureLocalDelete(deletion()))
        assertEquals(deletion(), store.localDelete(scope, base.eventId)); assertNull(store.localEdit(scope, base.eventId))
        assertEquals(base, store.projectedBaseline(scope, base.eventId))
        assertEquals(base, store.transaction(scope) { remote(base.eventId) })
        assertTrue(store.pending(scope).isEmpty()); assertNull(store.stabilizedProjection(scope, base.eventId))
        assertTrue(store.transaction(scope) { hasPendingMutation(base.eventId) })
        assertEquals(CalendarAccess(), store.transaction(scope) { access })
        assertNull(store.transaction(scope) { mutation(deletion().id) })
    }
    @Test fun modifiedThenDeletedSnapshotAndExactIdentitySurviveReopen() = fixture { store, name ->
        store.establish()
        val content = base.content.copy(title = "Edited then deleted", description = "Retained notes", location = "Retained location",
            time = EventTime.AllDay(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 5)))
        val captured = store.captureLocalDelete(deletion(content = content))
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertEquals(captured, reopened.localDelete(scope, base.eventId))
            assertEquals(base, reopened.projectedBaseline(scope, base.eventId))
            assertEquals(base, reopened.transaction(scope) { remote(base.eventId) })
            assertEquals(captured, reopened.captureLocalDelete(captured))
            assertNull(reopened.localEdit(scope, base.eventId)); assertTrue(reopened.pending(scope).isEmpty())
        }
    }
    @Test fun updateAndDeleteCannotReplaceEachOtherInEitherDirection() = fixture { store, _ ->
        store.establish(); val edit = store.captureLocalEdit(update())
        refused { store.captureLocalDelete(deletion()) }
        refused { store.captureLocalDelete(deletion().copy(id = edit.id)) }
        assertEquals(edit, store.localEdit(scope, base.eventId)); assertNull(store.localDelete(scope, base.eventId))
        val other = base.copy(eventId = EventId("other-event")); store.establish(other)
        val deleted = store.captureLocalDelete(deletion(other))
        refused { store.captureLocalEdit(update(other)) }
        refused { store.captureLocalEdit(update(other).copy(id = deleted.id)) }
        assertEquals(deleted, store.localDelete(scope, other.eventId)); assertNull(store.localEdit(scope, other.eventId))
    }
    @Test fun mutationIdIsUniqueAcrossTargetsAndKindsWithoutPartialSuppression() = fixture { store, _ ->
        store.establish(); store.captureLocalDelete(deletion())
        val other = base.copy(eventId = EventId("other-event")); store.establish(other); store.queue(other)
        refused { store.captureLocalDelete(deletion(other)) }
        refused { store.captureLocalEdit(update(other).copy(id = deletion().id)) }
        assertNull(store.localDelete(scope, other.eventId)); assertNull(store.localEdit(scope, other.eventId))
        assertEquals(other, store.pending(scope).single().change)
        assertEquals(deletion(), store.localDelete(scope, base.eventId))
    }
    @Test fun exactRetryPreservesConflictAndImmutableFirstSnapshot() = fixture { store, _ ->
        store.establish(); store.captureLocalDelete(deletion())
        store.queue(base.copy(revision = Revision("remote-new")))
        val conflicted = deletion().copy(state = LocalEditState.CONFLICT)
        assertEquals(conflicted, store.captureLocalDelete(deletion()))
        refused { store.captureLocalDelete(deletion().copy(id = MutationId("another"))) }
        refused { store.captureLocalDelete(deletion().copy(format = format.copy(version = 2))) }
        refused { store.captureLocalDelete(deletion().copy(content = base.content.copy(title = "Later snapshot"))) }
        refused { store.captureLocalDelete(deletion(base.copy(revision = Revision("forged-base")))) }
        refused { store.captureLocalDelete(conflicted) }
        store.queue(base)
        assertEquals(conflicted, store.localDelete(scope, base.eventId)); assertEquals(conflicted, store.captureLocalDelete(deletion()))
    }
    @Test fun newerImportedVersionBeforeCaptureCoexistsWithBaselineAndDeletionAsConflict() = fixture { store, _ ->
        store.establish()
        val remote = base.copy(revision = Revision("newer-before"), content = base.content.copy(location = "Remote location"))
        store.queue(remote)
        assertEquals(LocalEditState.CONFLICT, store.captureLocalDelete(deletion()).state)
        assertEquals(remote, store.transaction(scope) { remote(base.eventId) })
        assertEquals(base, store.projectedBaseline(scope, base.eventId)); assertTrue(store.pending(scope).isEmpty())
    }
    @Test fun changedContentReusingOpaqueRevisionConservativelyConflictsInsideStore() = fixture { store, _ ->
        store.establish(); store.captureLocalDelete(deletion())
        val changed = base.copy(content = base.content.copy(description = "Remote remapping"))
        store.queue(changed)
        assertEquals(LocalEditState.CONFLICT, store.localDelete(scope, base.eventId)?.state)
        assertEquals(changed, store.transaction(scope) { remote(base.eventId) })
        assertEquals(base, store.projectedBaseline(scope, base.eventId)); assertTrue(store.pending(scope).isEmpty())
    }
    @Test fun authoritativeRemoteTombstoneNeverAcknowledgesLocalDeletionOrResetsConflict() = fixture { store, _ ->
        store.establish(); store.captureLocalDelete(deletion())
        val gone = RemoteChange.Deleted(base.eventId, Revision("remote-gone"))
        store.queue(gone)
        assertEquals(deletion().copy(state = LocalEditState.CONFLICT), store.localDelete(scope, base.eventId))
        assertEquals(gone, store.transaction(scope) { remote(base.eventId) })
        assertEquals(0, store.acknowledge(ProjectionIntent(scope, gone)))
        assertEquals(base, store.projectedBaseline(scope, base.eventId))
        store.queue(base); assertEquals(LocalEditState.CONFLICT, store.localDelete(scope, base.eventId)?.state)
    }
    @Test fun deferredEvidenceConflictsBeforeOrAfterCaptureAndResolutionDoesNotResume() = fixture { store, _ ->
        store.establish()
        val evidence = DeferredCalendarEvent(base.eventId, Revision("unmapped"), format,
            DeferredEventReason.UNSUPPORTED_CONTENT, "sealed-fixture")
        store.transaction(scope) { (this as StagedSyncTransaction).defer(evidence) }
        assertEquals(LocalEditState.CONFLICT, store.captureLocalDelete(deletion()).state)
        store.transaction(scope) { (this as StagedSyncTransaction).clearDeferred(base.eventId) }
        assertEquals(LocalEditState.CONFLICT, store.localDelete(scope, base.eventId)?.state)
        val other = base.copy(eventId = EventId("other-event")); store.establish(other)
        store.captureLocalDelete(deletion(other).copy(id = MutationId("other-delete")))
        store.transaction(scope) { (this as StagedSyncTransaction).defer(evidence.copy(eventId = other.eventId)) }
        assertEquals(LocalEditState.CONFLICT, store.localDelete(scope, other.eventId)?.state)
    }
    @Test fun allProjectionQueuesCountsAndRefreshesProtectRetainedDeletion() = fixture { store, _ ->
        store.establish(); store.captureLocalDelete(deletion())
        store.queue(base); store.requeueAll(scope)
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format, traversalComplete = true) }
        store.beginRefresh(scope)
        assertNull(store.transaction(scope) { checkpoint }); assertTrue(store.pending(scope).isEmpty())
        assertEquals(0, store.selections(scope.account).single().pendingProjectionCount)
        assertEquals(0, store.blockedSeriesCount(scope))
        assertNull(store.stabilizedProjection(scope, base.eventId))
        assertEquals(0, store.acknowledge(ProjectionIntent(scope, base)))
        refused { store.prepareProjection(ProjectionIntent(scope, base), format) }
        refused { store.transaction(scope) { putMutation(Mutation(MutationId("generic"), scope, format,
            MutationKind.DELETE, base.eventId, base.revision)) } }
    }
    @Test fun recurrenceRequeueCannotReviveEventProtectedByLocalDeletion() = fixture { store, _ ->
        store.establish(); store.captureLocalDelete(deletion())
        val child = base.copy(revision = Revision("child"), content = base.content.copy(recurrence =
            EventRecurrence("series-fixture", original = RecurrencePoint.AllDay(LocalDate.of(2026, 10, 1)))))
        val master = base.copy(eventId = EventId("master"), content = base.content.copy(recurrence =
            EventRecurrence("series-fixture", "FREQ=DAILY")))
        store.queue(child); store.queue(master); store.requeueAll(scope)
        assertEquals(listOf(master), store.pending(scope).map { it.change })
        assertEquals(0, store.blockedSeriesCount(scope))
        assertEquals(LocalEditState.CONFLICT, store.localDelete(scope, base.eventId)?.state)
    }
    @Test fun qualifiedProjectionReceiptCannotConsumeExistingDeletion() = fixture { store, _ ->
        store.establish()
        val projected = base.copy(revision = Revision("applied")); store.queue(projected)
        val receipt = store.prepareProjection(ProjectionIntent(scope, projected), format)
        val captured = store.captureLocalDelete(deletion())
        assertFalse(store.restoreProjection(AppliedProjectionWitness(receipt)))
        assertEquals(captured, store.localDelete(scope, base.eventId)); assertEquals(base, store.projectedBaseline(scope, base.eventId))
        assertEquals(receipt, store.projectionReceipt(scope, base.eventId)); assertTrue(store.pending(scope).isEmpty())
    }
    @Test fun inactiveForeignAndForgedBaselinesCannotCaptureDeletion() = fixture { store, _ ->
        refused { store.captureLocalDelete(deletion()) }
        store.establish()
        refused { store.captureLocalDelete(deletion(base.copy(revision = Revision("forged")))) }
        refused { store.captureLocalDelete(deletion().copy(scope = scope.copy(generation = 1))) }
        refused { store.captureLocalDelete(deletion().copy(scope = scope.copy(account = AccountId("foreign")))) }
        store.setEnabled(scope, false); refused { store.captureLocalDelete(deletion()) }
        store.setEnabled(scope, true); store.remember(scope.account, emptyList()); refused { store.captureLocalDelete(deletion()) }
        assertNull(store.localDelete(scope, base.eventId))
    }
    @Test fun revokedLeaseRollsBackCaptureAndGenericConflictWrites() = fixture { store, _ ->
        store.establish(); store.queue(base)
        var calls = 0; store.admitTransaction = { ++calls == 1 }
        refused { store.captureLocalDelete(deletion()) }
        store.admitTransaction = { true }
        assertNull(store.localDelete(scope, base.eventId)); assertEquals(base, store.pending(scope).single().change)
        store.captureLocalDelete(deletion())
        calls = 0; store.admitTransaction = { ++calls == 1 }
        refused { store.queue(base.copy(revision = Revision("interrupted"))) }
        store.admitTransaction = { true }
        assertEquals(deletion(), store.localDelete(scope, base.eventId)); assertEquals(base, store.transaction(scope) { remote(base.eventId) })
        store.admitTransaction = { false }; refused { store.localDelete(scope, base.eventId) }
        store.admitTransaction = { true }
        calls = 0; store.admitTransaction = { ++calls == 1 }; refused { store.localDelete(scope, base.eventId) }
        store.admitTransaction = { true }
    }
    @Test fun cancellationBeforeCaptureCommitRollsBackSuppressionAndSnapshot() = fixture { store, _ ->
        store.establish(); store.queue(base)
        val child = Job(currentCoroutineContext()[Job]); var calls = 0
        store.admitTransaction = { if (++calls == 2) child.cancel(); true }
        try { withContext(child) { store.captureLocalDelete(deletion()) }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        finally { store.admitTransaction = { true } }
        assertNull(store.localDelete(scope, base.eventId)); assertEquals(base, store.pending(scope).single().change)
        assertEquals(base, store.projectedBaseline(scope, base.eventId))
    }
    @Test fun cancelledImportRollsBackNewEvidenceAndDeletionConflictTogether() = fixture { store, _ ->
        store.establish(); store.captureLocalDelete(deletion())
        val child = Job(currentCoroutineContext()[Job])
        try {
            withContext(child) {
                store.transaction(scope) {
                    putRemote(base.copy(revision = Revision("cancelled-import")))
                    (this as StagedSyncTransaction).defer(DeferredCalendarEvent(base.eventId,
                        Revision("cancelled-deferred"), format, DeferredEventReason.UNSUPPORTED_CONTENT, "sealed-fixture"))
                    child.cancel()
                }
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(deletion(), store.localDelete(scope, base.eventId))
        assertEquals(base, store.transaction(scope) { remote(base.eventId) }); assertEquals(0, store.deferredCount(scope))
        assertEquals(base, store.projectedBaseline(scope, base.eventId))
    }
    @Test fun failedSuppressionRollsBackInsertedDeletion() = fixture { store, _ ->
        store.establish(); store.queue(base)
        store.writableDatabase.execSQL("CREATE TRIGGER refuse_pending BEFORE UPDATE OF pending ON records BEGIN SELECT RAISE(ABORT,'fixture'); END")
        refused { store.captureLocalDelete(deletion()) }
        assertNull(store.localDelete(scope, base.eventId)); assertEquals(base, store.pending(scope).single().change)
        assertEquals(base, store.projectedBaseline(scope, base.eventId))
    }
    @Test fun freshSchemaDefaultsToUpdateAndRejectsInvalidKindWithoutChangingUniqueness() = fixture { store, _ ->
        store.establish(); store.captureLocalEdit(update())
        store.readableDatabase.rawQuery("SELECT kind FROM local_edits", null).use { assertTrue(it.moveToFirst()); assertEquals("UPDATE", it.getString(0)) }
        refused { store.writableDatabase.execSQL("UPDATE local_edits SET kind='CREATE'") }
        refused { store.writableDatabase.execSQL("UPDATE local_edits SET kind=NULL") }
        assertEquals(update(), store.localEdit(scope, base.eventId))
        val copy = ContentValues().apply {
            put("scope", JSONArray().put(scope.account.value).put(scope.calendar.value).put(scope.generation).toString())
            put("mutation", "default-kind"); put("event", "another"); put("adapter", format.id); put("version", format.version)
            put("base", "retained-fixture"); put("local", "retained-fixture"); put("state", "PAUSED")
        }
        store.writableDatabase.insertOrThrow("local_edits", null, copy)
        store.readableDatabase.rawQuery("SELECT kind FROM local_edits WHERE mutation='default-kind'", null).use {
            assertTrue(it.moveToFirst()); assertEquals("UPDATE", it.getString(0))
        }
        refused { store.writableDatabase.insertOrThrow("local_edits", null, ContentValues(copy).apply { put("mutation", "another-id") }) }
        refused { store.writableDatabase.insertOrThrow("local_edits", null, ContentValues(copy).apply { put("event", "another-target") }) }
    }

    @Test fun independentlyConstructedSchemaNineMigrationPreservesUpdatesPayloadsAndReceipts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "delete-migration-${UUID.randomUUID()}.db"
        val scopeKey = JSONArray().put(scope.account.value).put(scope.calendar.value).put(scope.generation).toString()
        fun payload(id: String, title: String) = JSONObject().apply {
            put("id", id); put("revision", base.revision.value); put("deleted", false); put("title", title)
            put("description", ""); put("location", ""); put("colorRgb", 0x123456)
            put("allDay", true); put("start", "2026-10-01"); put("end", "2026-10-02")
        }.toString()
        val nonce = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
                db.execSQL("CREATE TABLE calendars (scope TEXT PRIMARY KEY,account TEXT NOT NULL,calendar TEXT NOT NULL,generation INTEGER NOT NULL,title TEXT NOT NULL,color INTEGER,enabled INTEGER NOT NULL DEFAULT 0,checkpoint TEXT,status TEXT NOT NULL DEFAULT 'PAUSED',completed_at INTEGER,next_attempt INTEGER NOT NULL DEFAULT 0,available INTEGER NOT NULL DEFAULT 1)")
                db.execSQL("CREATE TABLE records (scope TEXT NOT NULL,event TEXT NOT NULL,payload TEXT NOT NULL,pending INTEGER NOT NULL,PRIMARY KEY(scope,event))")
                db.execSQL("CREATE TABLE projection_bases (scope TEXT NOT NULL,event TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(scope,event))")
                db.execSQL("CREATE TABLE local_edits (scope TEXT NOT NULL,mutation TEXT NOT NULL,event TEXT NOT NULL,adapter TEXT NOT NULL,version INTEGER NOT NULL,base TEXT NOT NULL,local TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('PAUSED','CONFLICT')),PRIMARY KEY(scope,mutation),UNIQUE(scope,event))")
                db.execSQL("CREATE TABLE projection_receipts (scope TEXT NOT NULL,event TEXT NOT NULL,nonce TEXT NOT NULL UNIQUE,payload TEXT NOT NULL,adapter TEXT NOT NULL,version INTEGER NOT NULL,previous_base TEXT,protocol INTEGER NOT NULL CHECK(protocol=1),PRIMARY KEY(scope,event))")
                db.insertOrThrow("calendars", null, ContentValues().apply {
                    put("scope", scopeKey); put("account", scope.account.value); put("calendar", scope.calendar.value)
                    put("generation", 0); put("title", "Fixture"); put("enabled", 1)
                })
                for ((id, state) in listOf(base.eventId.value to "PAUSED", "conflicted" to "CONFLICT")) {
                    db.insertOrThrow("local_edits", null, ContentValues().apply {
                        put("scope", scopeKey); put("mutation", "legacy-$id"); put("event", id); put("adapter", format.id); put("version", 3)
                        put("base", payload(id, "Fixture")); put("local", payload(id, "Updated fixture")); put("state", state)
                    })
                    for (table in listOf("projection_bases", "records")) db.insertOrThrow(table, null, ContentValues().apply {
                        put("scope", scopeKey); put("event", id); put("payload", payload(id, "Fixture")); if (table == "records") put("pending", 0)
                    })
                }
                db.insertOrThrow("projection_receipts", null, ContentValues().apply {
                    put("scope", scopeKey); put("event", base.eventId.value); put("nonce", nonce); put("payload", payload(base.eventId.value, "Fixture"))
                    put("adapter", format.id); put("version", 3); putNull("previous_base"); put("protocol", 1)
                })
                db.version = 9
            }
            ImportStore(context, name).use { store ->
                assertEquals(15, store.readableDatabase.version)
                store.readableDatabase.rawQuery("SELECT mutation,event,adapter,version,base,local,state,kind FROM local_edits ORDER BY event", null).use {
                    assertEquals(2, it.count)
                    while (it.moveToNext()) {
                        val id = it.getString(1)
                        assertEquals("legacy-$id", it.getString(0)); assertEquals(format.id, it.getString(2)); assertEquals(3, it.getInt(3))
                        assertEquals(payload(id, "Fixture"), it.getString(4)); assertEquals(payload(id, "Updated fixture"), it.getString(5))
                        assertEquals(if (id == "conflicted") "CONFLICT" else "PAUSED", it.getString(6)); assertEquals("UPDATE", it.getString(7))
                    }
                }
                assertEquals(base, store.projectedBaseline(scope, base.eventId)); assertNull(store.localDelete(scope, base.eventId))
                assertEquals(LocalEditState.PAUSED, store.localEdit(scope, base.eventId)?.state)
                assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, EventId("conflicted"))?.state)
                assertEquals(nonce, store.projectionReceipt(scope, base.eventId)?.nonce)
                assertEquals(3, store.projectionReceipt(scope, base.eventId)?.format?.version)
                refused { store.writableDatabase.execSQL("UPDATE local_edits SET kind='INVALID'") }
            }
        } finally { context.deleteDatabase(name) }
    }
}
