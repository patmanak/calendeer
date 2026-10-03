// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated opt-in SQLite fixtures; no Provider, account, network, or encrypted-payload proof. */
@RunWith(AndroidJUnit4::class)
class CapturedCreateLocalChangeStoreDeviceTest {
    private val scope = CalendarScope(AccountId("captured-current-fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("synthetic-import", 1)
    private val content = EventContent("Synthetic sent reference",
        EventTime.AllDay(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)), details = EventDetails())
    private val mutation = MutationId("synthetic-current-mutation")
    private fun baseline(index: Int = 1, target: CalendarScope = scope): CapturedCreateBaseline {
        val capture = LocalCreateIntent(LocalCreateReservation(MutationId("capture-$index"),
            LocalEventId(UUID(0, index.toLong() * 2).toString()), target, format,
            Instant.parse("2026-10-01T00:00:00Z")), content)
        val submission = LocalCreateSubmission(LocalCreateSubmissionReservation(capture,
            MutationId(UUID(0, index.toLong() * 2 + 1).toString()), AdapterFormat("synthetic-submission", 2)), "synthetic-opaque-$index")
        val change = RemoteChange.Upsert(EventId("event-$index"), Revision("sent-reference"), content)
        return CapturedCreateBaseline(CapturedCreateAttachmentReceipt(CapturedCreateBinding(
            CapturedCreateDispatchIdentity.of(submission), change.eventId, change.revision), UUID(0, index.toLong() + 100).toString()), change)
    }
    private val first get() = baseline()
    private fun current(deleted: Boolean = false, value: CapturedCreateBaseline = first,
        local: EventContent = content.copy(title = "First local title")) = CapturedCreateCurrent(value, local, deleted)
    private fun scopeKey(target: CalendarScope = scope) = JSONArray().put(target.account.value).put(target.calendar.value)
        .put(target.generation).toString()
    private fun fixture(remotePresent: Boolean = true, block: suspend (Context, String, ImportStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "captured-current-fixture-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic fixture")))
            store.setEnabled(scope, true)
            store.transaction(scope) { checkpoint = PageCheckpoint(scope, format) }
            if (remotePresent) store.queue(first.change)
            assertTrue(store.retainCapturedBaseline(first, AppliedCapturedCreateAttachment(first.receipt)))
            if (remotePresent) store.queue(first.change)
            block(context, name, store)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun ImportStore.queue(change: RemoteChange) = transaction(scope) {
        putRemote(change); putProjection(ProjectionIntent(scope, change))
    }
    private fun raw(context: Context, name: String, block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    }
    private fun rawPending(store: ImportStore, event: EventId = first.change.eventId): Int? =
        store.writableDatabase.rawQuery("SELECT pending FROM records WHERE scope=? AND event=?", arrayOf(scopeKey(), event.value)).use {
            if (it.moveToFirst()) it.getInt(0) else null
        }
    private fun rawRows(store: ImportStore, table: String): List<List<String?>> =
        store.writableDatabase.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { row ->
            buildList { while (row.moveToNext()) add(List(row.columnCount) { if (row.isNull(it)) null else row.getString(it) }) }
        }
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected captured local snapshot refusal") }
        catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
        catch (_: SQLiteException) { }
    }
    private fun expectedUpdate(state: LocalEditState = LocalEditState.PAUSED) =
        LocalEditIntent(mutation, scope, format, first.change, current().content, state)

    @Test fun unchangedWithoutAnIntentReturnsNullAndTouchesNoQueueBaseOrProvenance() = fixture { _, _, store ->
        val before = listOf("records", "projection_bases", "captured_create_bases", "projection_receipts").associateWith { rawRows(store, it) }
        assertNull(store.retainCapturedLocalChange(current(local = content), mutation))
        assertNull(store.retainCapturedLocalChange(current(local = content), mutation))
        for ((table, rows) in before) assertEquals(rows, rawRows(store, table))
        assertEquals(1, rawPending(store)); assertNull(store.localEdit(scope, first.change.eventId))
    }

    @Test fun updateRetainsFirstSnapshotAcrossReopenWithoutConsumingRawPendingOrReference() = fixture { context, name, store ->
        val before = listOf("records", "projection_bases", "captured_create_bases", "projection_receipts").associateWith { rawRows(store, it) }
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(), mutation))
        assertEquals(expectedUpdate(), store.localEdit(scope, first.change.eventId))
        assertEquals(1, rawPending(store)); assertTrue(store.pending(scope).isEmpty())
        for ((table, rows) in before) assertEquals(rows, rawRows(store, table))
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(LocalEditState.PAUSED, reopened.retainCapturedLocalChange(current(), mutation))
            assertEquals(expectedUpdate(), reopened.localEdit(scope, first.change.eventId))
            assertEquals(1, rawPending(reopened)); assertEquals(first, reopened.capturedBaseline(scope, first.change.eventId))
        }
    }

    @Test fun deletionRetainsEditedContentOrUnchangedContentAsASeparateKind() = fixture { _, _, store ->
        val deletion = current(deleted = true)
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(deletion, mutation))
        assertEquals(LocalDeleteIntent(mutation, scope, format, first.change, deletion.content), store.localDelete(scope, first.change.eventId))
        assertNull(store.localEdit(scope, first.change.eventId)); assertEquals(1, rawPending(store))
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(deletion, mutation))
        reject { store.retainCapturedLocalChange(current(deleted = true, local = content), mutation) }
    }

    @Test fun unchangedTombstoneStillCreatesADeletionIntention() = fixture { _, _, store ->
        val deletion = current(deleted = true, local = content)
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(deletion, mutation))
        assertEquals(content, store.localDelete(scope, first.change.eventId)?.content)
        assertEquals(1, rawPending(store))
    }

    @Test fun absenceOrDivergentRemoteBeforeCaptureProducesConflictWithoutQueueWrites() = fixture(remotePresent = false) { _, _, store ->
        assertEquals(LocalEditState.CONFLICT, store.retainCapturedLocalChange(current(), mutation))
        assertEquals(expectedUpdate(LocalEditState.CONFLICT), store.localEdit(scope, first.change.eventId))
        assertNull(rawPending(store)); assertNull(store.transaction(scope) { remote(first.change.eventId) })
    }

    @Test fun differentRemoteWithSameOpaqueRevisionBeforeCaptureIsRetainedAndConflicts() = fixture { _, _, store ->
        val remote = first.change.copy(content = content.copy(location = "Different remote location"))
        store.queue(remote)
        assertEquals(LocalEditState.CONFLICT, store.retainCapturedLocalChange(current(), mutation))
        assertEquals(remote, store.transaction(scope) { remote(first.change.eventId) })
        assertEquals(1, rawPending(store)); assertEquals(first.change, store.projectedBaseline(scope, first.change.eventId))
    }

    @Test fun laterOrdinaryImportPromotesConflictAndSuppressesProjectionWithoutAcknowledgingIntent() = fixture { _, _, store ->
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(), mutation))
        val remote = first.change.copy(revision = Revision("later-remote"), content = content.copy(description = "Later remote text"))
        store.queue(remote)
        assertEquals(remote, store.transaction(scope) { remote(first.change.eventId) })
        assertEquals(0, rawPending(store)) // Ordinary import suppresses work behind the retained local intent.
        assertEquals(expectedUpdate(LocalEditState.CONFLICT), store.localEdit(scope, first.change.eventId))
        assertEquals(LocalEditState.CONFLICT, store.retainCapturedLocalChange(current(), mutation))
        store.queue(first.change)
        assertEquals(LocalEditState.CONFLICT, store.retainCapturedLocalChange(current(), mutation))
        assertEquals(expectedUpdate(LocalEditState.CONFLICT), store.localEdit(scope, first.change.eventId))
    }

    @Test fun deferredOrMalformedRemotePromotesConflictWithoutClearingItsRawEvidence() = fixture { context, name, store ->
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(), mutation))
        raw(context, name) { it.execSQL("UPDATE records SET payload='unreadable-remote-evidence'") }
        val before = rawRows(store, "records")
        assertEquals(LocalEditState.CONFLICT, store.retainCapturedLocalChange(current(), mutation))
        assertEquals(before, rawRows(store, "records")); assertEquals(1, rawPending(store))
        val deferred = DeferredCalendarEvent(first.change.eventId, first.change.revision, format,
            DeferredEventReason.UNVERIFIED_CONTENT, "synthetic-sealed-evidence")
        store.transaction(scope) { (this as StagedSyncTransaction).defer(deferred) }
        assertEquals(LocalEditState.CONFLICT, store.retainCapturedLocalChange(current(), mutation))
        assertEquals(deferred, store.deferred(scope).single()); assertEquals(1, rawPending(store))
    }

    @Test fun firstIntentCannotBeReplacedByLaterEditDifferentMutationCrossKindOrUnchangedReturn() = fixture { _, _, store ->
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(), mutation))
        for ((snapshot, id) in listOf(current(local = content.copy(title = "Second local edit")) to mutation,
            current() to MutationId("different-current-mutation"), current(deleted = true) to mutation,
            current(local = content) to mutation)) reject { store.retainCapturedLocalChange(snapshot, id) }
        assertEquals(expectedUpdate(), store.localEdit(scope, first.change.eventId)); assertEquals(1, rawPending(store))
    }

    @Test fun creationIdentitiesAndMutationUsedByAnotherEventCannotBeAdopted() = fixture { _, _, store ->
        val identity = first.receipt.binding.identity
        for (id in listOf(identity.captureId, identity.attempt.id, MutationId(identity.localId.value)))
            reject { store.retainCapturedLocalChange(current(), id) }
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(), mutation))
        val second = baseline(2)
        assertTrue(store.retainCapturedBaseline(second, AppliedCapturedCreateAttachment(second.receipt)))
        store.queue(second.change)
        reject { store.retainCapturedLocalChange(current(value = second), mutation) }
        reject { store.retainCapturedLocalChange(current(value = second, local = content), mutation) }
        assertNull(store.localEdit(scope, second.change.eventId)); assertEquals(1, rawPending(store, second.change.eventId))
    }

    @Test fun mutationAndRemoteEventIdentitiesStayIsolatedByTheExactCalendarScope() = fixture { _, _, store ->
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(), mutation))
        val target = scope.copy(calendar = CalendarId("foreign-calendar"))
        store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic original"),
            CalendarSummary(target.calendar, "Synthetic foreign")))
        store.setEnabled(target, true)
        val foreign = baseline(target = target).let { it.copy(receipt = it.receipt.copy(nonce = UUID(0, 777).toString())) }
        store.transaction(target) {
            checkpoint = PageCheckpoint(target, format)
            putRemote(foreign.change); putProjection(ProjectionIntent(target, foreign.change))
        }
        assertTrue(store.retainCapturedBaseline(foreign, AppliedCapturedCreateAttachment(foreign.receipt)))
        store.transaction(target) { putProjection(ProjectionIntent(target, foreign.change)) }
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(value = foreign), mutation))
        assertEquals(target, store.localEdit(target, foreign.change.eventId)?.scope)
        assertEquals(scope, store.localEdit(scope, first.change.eventId)?.scope)
        assertEquals(1, rawPending(store)); assertEquals(2, rawRows(store, "local_edits").size)
        val unknown = baseline(target = scope.copy(generation = 1))
        reject { store.retainCapturedLocalChange(current(value = unknown), mutation) }
    }

    @Test fun rawOutstandingProjectionReceiptAlwaysRefusesEvenWhenItsPayloadIsCorrupt() = fixture { context, name, store ->
        val receipt = retainLegacyOrdinaryProjectionFixture(store, scope, format, first.change)
        val before = rawRows(store, "records")
        reject { store.retainCapturedLocalChange(current(), mutation) }
        reject { store.retainCapturedLocalChange(current(local = content), mutation) }
        raw(context, name) { it.execSQL("UPDATE projection_receipts SET payload='unreadable-ordinary-proof'") }
        reject { store.retainCapturedLocalChange(current(), mutation) }
        assertEquals(before, rawRows(store, "records")); assertEquals(1, rawPending(store))
        assertTrue(rawRows(store, "local_edits").isEmpty()); assertEquals(receipt.nonce, rawRows(store, "projection_receipts").single()[2])
    }

    @Test fun malformedLocalRowCannotBeReplacedOrMistakenForUnchangedAbsence() = fixture { context, name, store ->
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(), mutation))
        val original = rawRows(store, "local_edits").single()
        for (corruption in listOf("base='unreadable-base'", "local='unreadable-local'", "adapter='foreign-format'",
            "version=2", "state='INVALID'", "kind='DELETE'", "mutation='foreign-mutation'")) {
            raw(context, name) { db -> db.execSQL("PRAGMA ignore_check_constraints=ON")
                db.execSQL("UPDATE local_edits SET $corruption") }
            val retained = rawRows(store, "local_edits")
            reject { store.retainCapturedLocalChange(current(), mutation) }
            reject { store.retainCapturedLocalChange(current(local = content), mutation) }
            assertEquals(retained, rawRows(store, "local_edits")); assertEquals(1, rawPending(store))
            raw(context, name) { it.execSQL("UPDATE local_edits SET mutation=?, adapter=?, version=?, base=?, local=?, state=?, kind=?",
                arrayOf(original[1], original[3], original[4], original[5], original[6], original[7], original[8])) }
        }
    }

    @Test fun activeExactCheckpointAndFullProvenanceAreRequiredForChangedAndUnchangedSnapshots() = fixture { context, name, store ->
        store.transaction(scope) { checkpoint = null }
        reject { store.retainCapturedLocalChange(current(), mutation) }
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format.copy(version = 2)) }
        reject { store.retainCapturedLocalChange(current(local = content), mutation) }
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format) }
        store.setEnabled(scope, false); reject { store.retainCapturedLocalChange(current(), mutation) }
        store.setEnabled(scope, true); store.transaction(scope) { checkpoint = PageCheckpoint(scope, format) }
        val original = rawRows(store, "captured_create_bases").single()
        raw(context, name) { it.execSQL("UPDATE captured_create_bases SET nonce='00000000-0000-0000-0000-000000000999'") }
        reject { store.retainCapturedLocalChange(current(), mutation) }
        raw(context, name) { it.execSQL("UPDATE captured_create_bases SET nonce=?", arrayOf(original[17])) }
        raw(context, name) { it.execSQL("DELETE FROM projection_bases") }
        reject { store.retainCapturedLocalChange(current(), mutation) }; assertTrue(rawRows(store, "local_edits").isEmpty())
        assertEquals(1, rawPending(store))
    }

    @Test fun transactionRollbackAndRevokedLeaseCannotRetainOrPromoteAnIntent() = fixture { context, name, store ->
        raw(context, name) { db -> db.execSQL("""CREATE TRIGGER reject_local_insert BEFORE INSERT ON local_edits
            BEGIN SELECT RAISE(ABORT, 'synthetic local rejection'); END""".trimIndent()) }
        reject { store.retainCapturedLocalChange(current(), mutation) }
        assertTrue(rawRows(store, "local_edits").isEmpty()); assertEquals(1, rawPending(store))
        raw(context, name) { it.execSQL("DROP TRIGGER reject_local_insert") }
        var checks = 0
        store.admitTransaction = { ++checks == 1 }
        reject { store.retainCapturedLocalChange(current(), mutation) }
        store.admitTransaction = { true }; assertTrue(rawRows(store, "local_edits").isEmpty())
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(), mutation))
        raw(context, name) { it.execSQL("UPDATE records SET payload='different-remote-evidence'") }
        checks = 0; store.admitTransaction = { ++checks == 1 }
        reject { store.retainCapturedLocalChange(current(), mutation) }
        store.admitTransaction = { true }
        assertEquals(LocalEditState.PAUSED, store.localEdit(scope, first.change.eventId)?.state)
        assertEquals(1, rawPending(store)); assertEquals(LocalEditState.CONFLICT, store.retainCapturedLocalChange(current(), mutation))
    }

    @Test fun cancellationBeforeCommitLeavesNoIntentAfterReopen() = fixture { context, name, store ->
        coroutineScope {
            val job = async {
                val caller = currentCoroutineContext().job
                var calls = 0
                store.admitTransaction = { if (++calls == 2) caller.cancel(); true }
                store.retainCapturedLocalChange(current(), mutation)
            }
            try { job.await(); fail("Cancelled local capture must not commit") } catch (_: CancellationException) { }
        }
        store.close()
        ImportStore(context, name).use { reopened ->
            assertNull(reopened.localEdit(scope, first.change.eventId)); assertEquals(1, rawPending(reopened))
            assertEquals(LocalEditState.PAUSED, reopened.retainCapturedLocalChange(current(), mutation))
        }
    }

    @Test fun simultaneousHelpersRetainOneFirstSnapshotAndExactReplayReturnsItsState() = fixture { context, name, store ->
        ImportStore(context, name).use { other ->
            other.writableDatabase
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val one = async(Dispatchers.IO) { start.await(); store.retainCapturedLocalChange(current(), mutation) }
                val two = async(Dispatchers.IO) { start.await(); other.retainCapturedLocalChange(current(), mutation) }
                start.complete(Unit); assertEquals(LocalEditState.PAUSED, one.await()); assertEquals(LocalEditState.PAUSED, two.await())
            }
            assertEquals(1, rawRows(store, "local_edits").size); assertEquals(expectedUpdate(), other.localEdit(scope, first.change.eventId))
            assertEquals(1, rawPending(store)); assertEquals(first, other.capturedBaseline(scope, first.change.eventId))
        }
    }
}
