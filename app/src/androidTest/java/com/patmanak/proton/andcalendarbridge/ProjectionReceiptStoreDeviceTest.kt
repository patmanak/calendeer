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

/** Isolated durable receipt fixtures only; no session, network or shared Calendar Provider access. */
@RunWith(AndroidJUnit4::class)
class ProjectionReceiptStoreDeviceTest {
    private val scope = CalendarScope(AccountId("receipt-fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("receipt-fixture", 1)
    private val original = RemoteChange.Upsert(EventId("fixture-event"), Revision("original"),
        EventContent("Original fixture", EventTime.AllDay(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2))))
    private val applied = original.copy(revision = Revision("applied"), content = original.content.copy(title = "Applied fixture"))
    private fun fixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "projection-receipt-${UUID.randomUUID()}.db"
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
    private suspend fun ImportStore.baseline() {
        queue(original); assertEquals(1, acknowledge(ProjectionIntent(scope, original)))
    }
    private suspend fun ImportStore.receipt(): ProjectionReceipt {
        queue(applied); return prepareProjection(ProjectionIntent(scope, applied), format)
    }
    private suspend fun refused(block: suspend () -> Unit) {
        try { block(); fail("Expected incompatible or inactive receipt refusal") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
        catch (_: SQLiteException) { }
    }

    @Test fun preparationIsNotApplicationOrAcknowledgement() = fixture { store, _ ->
        val receipt = store.receipt()
        assertEquals(applied, receipt.change); assertEquals(scope, receipt.scope); assertEquals(format, receipt.format)
        assertNull(receipt.previousBase); assertNull(store.projectedBaseline(scope, applied.eventId))
        assertEquals(applied, store.pending(scope).single().change)
        assertEquals(receipt, store.projectionReceipt(scope, applied.eventId))
        assertEquals(receipt, store.prepareProjection(ProjectionIntent(scope, applied), format))
    }
    @Test fun exactReceiptSurvivesReopenAndQualifiedRestoreConsumesMatchingPendingOnlyOnce() = fixture { store, name ->
        store.baseline(); val receipt = store.receipt(); assertEquals(original, receipt.previousBase)
        store.close()
        ImportStore(ApplicationProvider.getApplicationContext(), name).use { reopened ->
            assertEquals(receipt, reopened.projectionReceipt(scope, applied.eventId))
            assertEquals(original, reopened.projectedBaseline(scope, applied.eventId))
            assertTrue(reopened.restoreProjection(AppliedProjectionWitness(receipt)))
            assertEquals(applied, reopened.projectedBaseline(scope, applied.eventId))
            assertTrue(reopened.pending(scope).isEmpty()); assertNull(reopened.projectionReceipt(scope, applied.eventId))
            assertFalse(reopened.restoreProjection(AppliedProjectionWitness(receipt)))
            assertEquals(applied, reopened.projectedBaseline(scope, applied.eventId))
        }
    }
    @Test fun differentPendingContentOrAdapterCannotReplaceUnresolvedReceipt() = fixture { store, _ ->
        val receipt = store.receipt()
        refused { store.prepareProjection(ProjectionIntent(scope, applied), format.copy(version = 2)) }
        val newer = applied.copy(revision = Revision("newer"))
        store.queue(newer)
        refused { store.prepareProjection(ProjectionIntent(scope, newer), format) }
        refused { store.prepareProjection(ProjectionIntent(scope, applied), format) }
        assertEquals(receipt, store.projectionReceipt(scope, applied.eventId))
        assertEquals(newer, store.pending(scope).single().change)
    }
    @Test fun forgedWitnessCannotConsumeOrRewriteExactReceipt() = fixture { store, _ ->
        store.baseline(); val receipt = store.receipt()
        listOf(receipt.copy(nonce = UUID.randomUUID().toString()), receipt.copy(format = format.copy(version = 2)),
            receipt.copy(change = applied.copy(content = applied.content.copy(title = "Foreign"))),
            receipt.copy(previousBase = null)).forEach {
            assertFalse(store.restoreProjection(AppliedProjectionWitness(it)))
        }
        assertEquals(receipt, store.projectionReceipt(scope, applied.eventId))
        assertEquals(original, store.projectedBaseline(scope, applied.eventId))
        assertEquals(applied, store.pending(scope).single().change)
    }
    @Test fun newerRemoteImportDoesNotPreventRestoringActuallyAppliedOlderBaseline() = fixture { store, _ ->
        store.baseline(); val receipt = store.receipt()
        val newer = applied.copy(revision = Revision("newer-remote"), content = applied.content.copy(location = "Remote place"))
        store.queue(newer)
        assertTrue(store.restoreProjection(AppliedProjectionWitness(receipt)))
        assertEquals(applied, store.projectedBaseline(scope, applied.eventId))
        assertEquals(newer, store.pending(scope).single().change)
        assertEquals(newer, store.transaction(scope) { remote(applied.eventId) })
        assertNull(store.projectionReceipt(scope, applied.eventId))
    }
    @Test fun tombstoneRetainsItsPendingDeletionDuringOlderAppliedBaselineRestoration() = fixture { store, _ ->
        store.baseline(); val receipt = store.receipt()
        val deleted = RemoteChange.Deleted(applied.eventId, Revision("remote-gone"))
        store.queue(deleted)
        assertTrue(store.restoreProjection(AppliedProjectionWitness(receipt)))
        assertEquals(applied, store.projectedBaseline(scope, applied.eventId))
        assertEquals(deleted, store.pending(scope).single().change)
        assertEquals(deleted, store.transaction(scope) { remote(applied.eventId) })
    }
    @Test fun newerAcknowledgedBaselineNeverRegressesButAlreadyAppliedBaselineIsIdempotentlyAccepted() = fixture { store, _ ->
        store.baseline(); val receipt = store.receipt()
        val newer = applied.copy(revision = Revision("already-newer"))
        store.queue(newer); store.acknowledge(ProjectionIntent(scope, newer))
        assertFalse(store.restoreProjection(AppliedProjectionWitness(receipt)))
        assertEquals(newer, store.projectedBaseline(scope, applied.eventId))
        assertEquals(receipt, store.projectionReceipt(scope, applied.eventId))
        // Independently restore the exact applied baseline to qualify the allowed idempotent case.
        store.queue(applied); store.acknowledge(ProjectionIntent(scope, applied))
        assertTrue(store.restoreProjection(AppliedProjectionWitness(receipt)))
        assertEquals(applied, store.projectedBaseline(scope, applied.eventId))
    }
    @Test fun localIntentPreventsReceiptRestoreWithoutDeletingEitherEvidence() = fixture { store, _ ->
        store.baseline(); val receipt = store.receipt()
        val local = LocalEditIntent(MutationId("local-fixture"), scope, format, original,
            original.content.copy(title = "Local fixture"))
        val captured = store.captureLocalEdit(local)
        assertFalse(store.restoreProjection(AppliedProjectionWitness(receipt)))
        assertEquals(captured, store.localEdit(scope, applied.eventId))
        assertEquals(receipt, store.projectionReceipt(scope, applied.eventId))
        assertEquals(original, store.projectedBaseline(scope, applied.eventId))
        assertTrue(store.pending(scope).isEmpty())
    }
    @Test fun inactiveForeignAndIncompatibleScopesAreNotPreparedOrRestored() = fixture { store, _ ->
        val receipt = store.receipt()
        refused { store.prepareProjection(ProjectionIntent(scope.copy(generation = 1), applied), format) }
        refused { store.prepareProjection(ProjectionIntent(scope.copy(account = AccountId("foreign")), applied), format) }
        store.setEnabled(scope, false)
        refused { store.prepareProjection(ProjectionIntent(scope, applied), format) }
        assertFalse(store.restoreProjection(AppliedProjectionWitness(receipt)))
        store.setEnabled(scope, true)
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format.copy(version = 2)) }
        refused { store.prepareProjection(ProjectionIntent(scope, applied), format) }
        assertFalse(store.restoreProjection(AppliedProjectionWitness(receipt)))
        store.transaction(scope) { checkpoint = null }
        store.remember(scope.account, emptyList())
        assertFalse(store.restoreProjection(AppliedProjectionWitness(receipt)))
        assertEquals(receipt, store.projectionReceipt(scope, applied.eventId))
    }
    @Test fun unqueuedDeferredLocallyCapturedAndUnsupportedChangesHaveNoReceipt() = fixture { store, _ ->
        store.transaction(scope) { putRemote(applied) }
        refused { store.prepareProjection(ProjectionIntent(scope, applied), format) }
        store.queue(applied)
        store.transaction(scope) { (this as StagedSyncTransaction).defer(DeferredCalendarEvent(applied.eventId,
            Revision("unmapped"), format, DeferredEventReason.UNSUPPORTED_CONTENT, "sealed-fixture")) }
        refused { store.prepareProjection(ProjectionIntent(scope, applied), format) }
        store.transaction(scope) { (this as StagedSyncTransaction).clearDeferred(applied.eventId) }
        val detailed = applied.copy(content = applied.content.copy(details = EventDetails(listOf(EventReminder(15)))))
        store.queue(detailed); refused { store.prepareProjection(ProjectionIntent(scope, detailed), format) }
        val gone = RemoteChange.Deleted(applied.eventId, Revision("gone"))
        store.queue(gone); refused { store.prepareProjection(ProjectionIntent(scope, gone), format) }
        assertNull(store.projectionReceipt(scope, applied.eventId))
        store.baseline()
        store.captureLocalEdit(LocalEditIntent(MutationId("local-fixture"), scope, format, original,
            original.content.copy(title = "Local fixture")))
        store.queue(applied); refused { store.prepareProjection(ProjectionIntent(scope, applied), format) }
        assertNull(store.projectionReceipt(scope, applied.eventId))
    }
    @Test fun revokedLeaseRollsBackReceiptPreparationAndRestoration() = fixture { store, _ ->
        store.baseline(); store.queue(applied)
        var calls = 0; store.admitTransaction = { ++calls == 1 }
        refused { store.prepareProjection(ProjectionIntent(scope, applied), format) }
        store.admitTransaction = { true }
        assertNull(store.projectionReceipt(scope, applied.eventId))
        val receipt = store.prepareProjection(ProjectionIntent(scope, applied), format)
        calls = 0; store.admitTransaction = { ++calls == 1 }
        refused { store.restoreProjection(AppliedProjectionWitness(receipt)) }
        store.admitTransaction = { true }
        assertEquals(receipt, store.projectionReceipt(scope, applied.eventId))
        assertEquals(original, store.projectedBaseline(scope, applied.eventId))
        assertEquals(applied, store.pending(scope).single().change)
    }
    @Test fun cancellationBeforeCommitRollsBackBaselinePendingAndReceiptConsumption() = fixture { store, _ ->
        store.baseline(); val receipt = store.receipt()
        val child = Job(currentCoroutineContext()[Job]); var calls = 0
        store.admitTransaction = { if (++calls == 2) child.cancel(); true }
        try { withContext(child) { store.restoreProjection(AppliedProjectionWitness(receipt)) }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        finally { store.admitTransaction = { true } }
        assertEquals(original, store.projectedBaseline(scope, applied.eventId))
        assertEquals(applied, store.pending(scope).single().change)
        assertEquals(receipt, store.projectionReceipt(scope, applied.eventId))
    }
    @Test fun failedReceiptDeletionRollsBackBaselineAndPendingTogether() = fixture { store, _ ->
        store.baseline(); val receipt = store.receipt()
        store.writableDatabase.execSQL("CREATE TRIGGER refuse_receipt_delete BEFORE DELETE ON projection_receipts BEGIN SELECT RAISE(ABORT,'fixture'); END")
        refused { store.restoreProjection(AppliedProjectionWitness(receipt)) }
        assertEquals(original, store.projectedBaseline(scope, applied.eventId))
        assertEquals(applied, store.pending(scope).single().change)
        assertEquals(receipt, store.projectionReceipt(scope, applied.eventId))
    }

    @Test fun independentlyConstructedSchemaEightDoesNotInventReceiptsAndPreservesBaselineAndLocalIntent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "receipt-migration-${UUID.randomUUID()}.db"
        val scopeKey = JSONArray().put(scope.account.value).put(scope.calendar.value).put(scope.generation).toString()
        fun payload(title: String) = JSONObject().apply {
            put("id", original.eventId.value); put("revision", original.revision.value); put("deleted", false)
            put("title", title); put("description", ""); put("location", "")
            put("allDay", true); put("start", "2026-10-01"); put("end", "2026-10-02")
        }.toString()
        val baselinePayload = payload(original.content.title); val localPayload = payload("Local fixture")
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
                db.execSQL("CREATE TABLE calendars (scope TEXT PRIMARY KEY,account TEXT NOT NULL,calendar TEXT NOT NULL,generation INTEGER NOT NULL,title TEXT NOT NULL,color INTEGER,enabled INTEGER NOT NULL DEFAULT 0,checkpoint TEXT,status TEXT NOT NULL DEFAULT 'PAUSED',completed_at INTEGER,next_attempt INTEGER NOT NULL DEFAULT 0,available INTEGER NOT NULL DEFAULT 1)")
                db.execSQL("CREATE TABLE records (scope TEXT NOT NULL,event TEXT NOT NULL,payload TEXT NOT NULL,pending INTEGER NOT NULL,PRIMARY KEY(scope,event))")
                db.execSQL("CREATE TABLE projection_bases (scope TEXT NOT NULL,event TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(scope,event))")
                db.execSQL("CREATE TABLE local_edits (scope TEXT NOT NULL,mutation TEXT NOT NULL,event TEXT NOT NULL,adapter TEXT NOT NULL,version INTEGER NOT NULL,base TEXT NOT NULL,local TEXT NOT NULL,state TEXT NOT NULL,PRIMARY KEY(scope,mutation),UNIQUE(scope,event))")
                db.insertOrThrow("calendars", null, ContentValues().apply {
                    put("scope", scopeKey); put("account", scope.account.value); put("calendar", scope.calendar.value)
                    put("generation", 0); put("title", "Fixture"); put("enabled", 1)
                })
                for (table in listOf("records", "projection_bases")) db.insertOrThrow(table, null, ContentValues().apply {
                    put("scope", scopeKey); put("event", original.eventId.value); put("payload", baselinePayload)
                    if (table == "records") put("pending", 0)
                })
                db.insertOrThrow("local_edits", null, ContentValues().apply {
                    put("scope", scopeKey); put("event", original.eventId.value); put("mutation", "local-fixture")
                    put("adapter", format.id); put("version", format.version); put("base", baselinePayload); put("local", localPayload)
                    put("state", "PAUSED")
                })
                db.version = 8
            }
            ImportStore(context, name).use { store ->
                assertEquals(10, store.readableDatabase.version)
                assertNull(store.projectionReceipt(scope, original.eventId))
                assertEquals(original, store.projectedBaseline(scope, original.eventId))
                assertEquals(LocalEditIntent(MutationId("local-fixture"), scope, format, original,
                    original.content.copy(title = "Local fixture")), store.localEdit(scope, original.eventId))
                store.readableDatabase.rawQuery("SELECT payload,pending FROM records", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(baselinePayload, it.getString(0)); assertEquals(0, it.getInt(1))
                }
            }
        } finally { context.deleteDatabase(name) }
    }
}
