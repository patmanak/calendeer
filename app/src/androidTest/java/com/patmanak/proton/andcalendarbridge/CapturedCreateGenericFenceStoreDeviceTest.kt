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
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated opt-in raw SQLite fences; no handoff, release, Provider or account mutation. */
@RunWith(AndroidJUnit4::class)
class CapturedCreateGenericFenceStoreDeviceTest {
    private val scope = CalendarScope(AccountId("captured-generic-fence-fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("synthetic-import", 1)
    private val content = EventContent("Synthetic sent reference",
        EventTime.AllDay(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)), details = EventDetails())
    private val mutation = MutationId("synthetic-local-mutation")
    private fun baseline(): CapturedCreateBaseline {
        val capture = LocalCreateIntent(LocalCreateReservation(MutationId("synthetic-capture"),
            LocalEventId(UUID(0, 2).toString()), scope, format, Instant.parse("2026-10-01T00:00:00Z")), content)
        val submission = LocalCreateSubmission(LocalCreateSubmissionReservation(capture,
            MutationId(UUID(0, 3).toString()), AdapterFormat("synthetic-submission", 2)), "synthetic-opaque")
        val change = RemoteChange.Upsert(EventId("captured-event"), Revision("sent-reference"), content)
        return CapturedCreateBaseline(CapturedCreateAttachmentReceipt(CapturedCreateBinding(
            CapturedCreateDispatchIdentity.of(submission), change.eventId, change.revision), UUID(0, 101).toString()), change)
    }
    private val first get() = baseline()
    private fun proposal(nonce: Long = 201) = CapturedCreateAcknowledgementReceipt(first, UUID(0, nonce).toString())
    private fun localContent() = content.copy(title = "Synthetic local edit")
    private fun edit(base: RemoteChange.Upsert = first.change, target: CalendarScope = scope) =
        LocalEditIntent(mutation, target, format, base, base.content.copy(title = "Synthetic local edit"))
    private fun deletion(base: RemoteChange.Upsert = first.change, target: CalendarScope = scope) =
        LocalDeleteIntent(mutation, target, format, base, base.content.copy(title = "Synthetic local edit"))
    private fun current(deleted: Boolean = false, ack: CapturedCreateAcknowledgementReceipt? = null) =
        CapturedCreateCurrent(first, localContent(), deleted, ack)
    private fun scopeKey(target: CalendarScope = scope) = JSONArray().put(target.account.value)
        .put(target.calendar.value).put(target.generation).toString()
    private fun fixture(block: suspend (Context, String, ImportStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "captured-generic-fence-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic fixture")))
            store.setEnabled(scope, true)
            store.transaction(scope) { checkpoint = PageCheckpoint(scope, format) }
            assertTrue(store.retainCapturedBaseline(first, AppliedCapturedCreateAttachment(first.receipt)))
            store.queue(first.change)
            block(context, name, store)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun ImportStore.queue(change: RemoteChange, target: CalendarScope = scope) = transaction(target) {
        putRemote(change); putProjection(ProjectionIntent(target, change))
    }
    private fun raw(context: Context, name: String, block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    }
    private fun rows(store: ImportStore, table: String): List<List<String?>> =
        store.writableDatabase.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { row ->
            buildList { while (row.moveToNext()) add(List(row.columnCount) { if (row.isNull(it)) null else row.getString(it) }) }
        }
    private fun snapshot(store: ImportStore) = listOf("records", "projection_bases", "captured_create_bases",
        "captured_create_acknowledgements", "projection_receipts", "local_edits", "deferred_events")
        .associateWith { rows(store, it) }
    private fun unchanged(store: ImportStore, previous: Map<String, List<List<String?>>>) {
        for ((table, values) in previous) assertEquals("Refusal must preserve $table", values, rows(store, table))
    }
    private fun pending(store: ImportStore): Int = store.writableDatabase.rawQuery(
        "SELECT pending FROM records WHERE scope=? AND event=?", arrayOf(scopeKey(), first.change.eventId.value)).use {
            assertTrue(it.moveToFirst()); it.getInt(0)
        }
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected captured generic fence refusal") }
        catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
        catch (_: SQLiteException) { }
    }
    private suspend fun blocked(store: ImportStore) {
        val before = snapshot(store)
        reject { store.captureLocalEdit(edit()) }; reject { store.captureLocalDelete(deletion()) }
        unchanged(store, before)
    }
    private suspend fun prove(store: ImportStore): CapturedCreateAcknowledgementReceipt {
        val receipt = store.reserveCapturedAcknowledgement(proposal()).record.receipt
        store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(receipt))
        return receipt
    }

    @Test fun capturedProvenanceBeforeAnyAckRefusesBothGenericCapturesWithoutConsumingPending() = fixture { _, _, store ->
        assertNull(store.capturedAcknowledgement(scope, first.change.eventId))
        blocked(store)
        assertEquals(1, pending(store)); assertEquals(first.change, store.projectedBaseline(scope, first.change.eventId))
        assertTrue(rows(store, "local_edits").isEmpty())
    }

    @Test fun unknownAckRefusesGenericUpdateAndDeletionAndPreservesItsFirstReservation() = fixture { _, _, store ->
        val reservation = store.reserveCapturedAcknowledgement(proposal())
        blocked(store)
        assertEquals(reservation.record, store.capturedAcknowledgement(scope, first.change.eventId))
        assertEquals(1, pending(store)); assertTrue(rows(store, "local_edits").isEmpty())
    }

    @Test fun provenAckStillRefusesGenericPathsWhileExactSpecializedUpdateRemainsAvailable() = fixture { _, _, store ->
        val receipt = prove(store)
        blocked(store)
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(ack = receipt), mutation))
        blocked(store) // A specialized intention cannot be adopted through generic replay.
        assertEquals(edit(), store.localEdit(scope, first.change.eventId)); assertEquals(1, pending(store))
        assertEquals(receipt, store.capturedAcknowledgement(scope, first.change.eventId)?.receipt)
    }

    @Test fun exactSpecializedTombstoneRemainsAvailableBehindProvenAck() = fixture { _, _, store ->
        val receipt = prove(store)
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(deleted = true, ack = receipt), mutation))
        blocked(store)
        assertEquals(deletion(), store.localDelete(scope, first.change.eventId)); assertEquals(1, pending(store))
    }

    @Test fun specializedPreAckSnapshotRemainsAvailableWithoutOpeningGenericReplay() = fixture { _, _, store ->
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(), mutation))
        blocked(store)
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(), mutation))
        assertEquals(1, pending(store))
    }

    @Test fun malformedCapturedProvenanceIsRawWorkRatherThanAbsence() = fixture { context, name, store ->
        for (corruption in listOf("nonce='invalid'", "payload='unreadable-captured-content'", "capture_adapter='foreign'", "protocol=2")) {
            raw(context, name) { db -> db.execSQL("PRAGMA ignore_check_constraints=ON")
                db.execSQL("UPDATE captured_create_bases SET $corruption") }
            blocked(store); assertEquals(1, pending(store))
        }
        assertTrue(rows(store, "captured_create_acknowledgements").isEmpty())
        // A control with identical ordinary base/content confirms provenance presence is the fence.
        raw(context, name) { it.execSQL("DELETE FROM captured_create_bases") }
        assertEquals(edit(), store.captureLocalEdit(edit()))
    }

    @Test fun orphanAckAloneAndItsMalformedFieldsCannotOpenGenericCaptures() = fixture { context, name, store ->
        prove(store)
        raw(context, name) { it.execSQL("DELETE FROM captured_create_bases") }
        blocked(store)
        for (corruption in listOf("nonce='invalid'", "fingerprint='invalid'", "state='INVALID'", "protocol=2")) {
            raw(context, name) { db -> db.execSQL("PRAGMA ignore_check_constraints=ON")
                db.execSQL("UPDATE captured_create_acknowledgements SET $corruption") }
            blocked(store); assertEquals(1, pending(store))
        }
        raw(context, name) { it.execSQL("DELETE FROM captured_create_acknowledgements") }
        assertEquals(deletion(), store.captureLocalDelete(deletion()))
    }

    @Test fun ordinaryUpdateReplayCannotBypassProvenanceThatAppearedAfterItsFirstCapture() = fixture { context, name, store ->
        val captured = rows(store, "captured_create_bases").single()
        raw(context, name) { it.execSQL("DELETE FROM captured_create_bases") }
        assertEquals(edit(), store.captureLocalEdit(edit())); assertEquals(edit(), store.captureLocalEdit(edit()))
        raw(context, name) { it.execSQL("INSERT INTO captured_create_bases VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", captured.toTypedArray()) }
        blocked(store)
        assertEquals(edit(), store.localEdit(scope, first.change.eventId)); assertEquals(0, pending(store))
    }

    @Test fun ordinaryDeletionReplayCannotBypassAnOrphanAckThatAppearedLater() = fixture { context, name, store ->
        val receipt = prove(store)
        val ack = rows(store, "captured_create_acknowledgements").single()
        raw(context, name) { db -> db.execSQL("DELETE FROM captured_create_bases"); db.execSQL("DELETE FROM captured_create_acknowledgements") }
        assertEquals(deletion(), store.captureLocalDelete(deletion())); assertEquals(deletion(), store.captureLocalDelete(deletion()))
        raw(context, name) { it.execSQL("INSERT INTO captured_create_acknowledgements VALUES (?, ?, ?, ?, ?, ?)", ack.toTypedArray()) }
        blocked(store)
        assertEquals(deletion(), store.localDelete(scope, first.change.eventId)); assertEquals(0, pending(store))
        assertEquals(receipt.nonce, rows(store, "captured_create_acknowledgements").single()[2])
    }

    @Test fun unrelatedOrdinaryEventsInTheSameScopeStillAcceptBothKinds() = fixture { _, _, store ->
        prove(store); blocked(store)
        val updateBase = first.change.copy(eventId = EventId("ordinary-update"))
        val deleteBase = first.change.copy(eventId = EventId("ordinary-delete"))
        for (base in listOf(updateBase, deleteBase)) {
            store.queue(base); assertEquals(1, store.acknowledge(ProjectionIntent(scope, base))); store.queue(base)
        }
        assertEquals(edit(updateBase), store.captureLocalEdit(edit(updateBase)))
        val deleteIntent = deletion(deleteBase).copy(id = MutationId("synthetic-delete-mutation"))
        assertEquals(deleteIntent, store.captureLocalDelete(deleteIntent)); assertEquals(deleteIntent, store.captureLocalDelete(deleteIntent))
        assertEquals(1, pending(store)); assertEquals(first.change, store.projectedBaseline(scope, first.change.eventId))
    }

    @Test fun rawFenceIsScopedAndDoesNotRejectTheSameOpaqueEventInAnotherCalendar() = fixture { _, _, store ->
        val foreign = scope.copy(calendar = CalendarId("ordinary-calendar"))
        store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic captured"), CalendarSummary(foreign.calendar, "Synthetic ordinary")))
        store.setEnabled(foreign, true); store.transaction(foreign) { checkpoint = PageCheckpoint(foreign, format) }
        store.queue(first.change, foreign); assertEquals(1, store.acknowledge(ProjectionIntent(foreign, first.change)))
        store.queue(first.change, foreign)
        val ordinary = edit(target = foreign)
        assertEquals(ordinary, store.captureLocalEdit(ordinary)); assertEquals(ordinary, store.captureLocalEdit(ordinary))
        blocked(store); assertEquals(1, pending(store)); assertEquals(scope, store.capturedBaseline(scope, first.change.eventId)?.scope)
    }

    @Test fun reopeningRetainsTheRawOrphanFenceAndDoesNotChangeTheSchema() = fixture { context, name, store ->
        prove(store)
        raw(context, name) { it.execSQL("DELETE FROM captured_create_bases") }
        val before = snapshot(store)
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(13, reopened.writableDatabase.version)
            blocked(reopened); unchanged(reopened, before); assertEquals(1, pending(reopened))
        }
    }

    @Test fun cleanSourceRequiresExactProvenAckButMakesNoDataOrPendingChanges() = fixture { _, _, store ->
        val before = snapshot(store)
        assertFalse(store.capturedCleanSource(proposal())); unchanged(store, before)
        store.reserveCapturedAcknowledgement(proposal())
        val unknown = snapshot(store)
        assertFalse(store.capturedCleanSource(proposal())); unchanged(store, unknown)
        val receipt = prove(store)
        val proven = snapshot(store)
        assertTrue(store.capturedCleanSource(receipt)); assertTrue(store.capturedCleanSource(receipt))
        assertFalse(store.capturedCleanSource(proposal(202))); unchanged(store, proven)
        assertEquals(1, pending(store))
    }

    @Test fun cleanSourceRefusesFullSourceMismatchAndCorruptAckEvidence() = fixture { context, name, store ->
        val receipt = prove(store)
        val different = receipt.copy(baseline = first.copy(receipt = first.receipt.copy(nonce = UUID(0, 777).toString())))
        val before = snapshot(store)
        reject { store.capturedCleanSource(different) }; unchanged(store, before)
        raw(context, name) { it.execSQL("UPDATE captured_create_acknowledgements SET fingerprint='unreadable-proof'") }
        val corrupted = snapshot(store)
        reject { store.capturedCleanSource(receipt) }; unchanged(store, corrupted); assertEquals(1, pending(store))
    }

    @Test fun cleanSourceRefusesRawLocalIntentEvenWhenMalformed() = fixture { context, name, store ->
        val receipt = prove(store)
        store.retainCapturedLocalChange(current(ack = receipt), mutation)
        raw(context, name) { it.execSQL("UPDATE local_edits SET local='unreadable-local-evidence'") }
        val before = snapshot(store)
        reject { store.capturedCleanSource(receipt) }; unchanged(store, before); assertEquals(1, pending(store))
    }

    @Test fun cleanSourceRefusesRawOrdinaryReceiptEvenWhenMalformed() = fixture { context, name, store ->
        val receipt = prove(store)
        retainLegacyOrdinaryProjectionFixture(store, scope, format, first.change)
        raw(context, name) { it.execSQL("UPDATE projection_receipts SET payload='unreadable-projection-proof'") }
        val before = snapshot(store)
        reject { store.capturedCleanSource(receipt) }; unchanged(store, before); assertEquals(1, pending(store))
    }

    @Test fun cleanSourcePreservesNewRemoteAndDeferredWorkAsPointInTimeQualification() = fixture { _, _, store ->
        val receipt = prove(store)
        val remote = first.change.copy(revision = Revision("later-remote"), content = content.copy(description = "Synthetic incoming change"))
        store.queue(remote)
        val deferred = DeferredCalendarEvent(first.change.eventId, remote.revision, format,
            DeferredEventReason.UNVERIFIED_CONTENT, "synthetic-sealed-evidence")
        store.transaction(scope) { (this as StagedSyncTransaction).defer(deferred) }
        val before = snapshot(store)
        assertTrue(store.capturedCleanSource(receipt)); unchanged(store, before)
        assertEquals(remote, store.transaction(scope) { remote(first.change.eventId) })
        assertEquals(deferred, store.deferred(scope).single()); assertEquals(1, pending(store))
    }

    @Test fun cleanSourceRequiresActiveExactCheckpointAndBothLeaseChecks() = fixture { _, _, store ->
        val receipt = prove(store)
        val before = snapshot(store)
        var checks = 0; store.admitTransaction = { ++checks == 1 }
        reject { store.capturedCleanSource(receipt) }
        store.admitTransaction = { true }; unchanged(store, before)
        store.transaction(scope) { checkpoint = null }
        reject { store.capturedCleanSource(receipt) }
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format.copy(version = 2)) }
        reject { store.capturedCleanSource(receipt) }
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format) }
        store.setEnabled(scope, false); reject { store.capturedCleanSource(receipt) }
        store.setEnabled(scope, true); store.transaction(scope) { checkpoint = PageCheckpoint(scope, format) }
        assertTrue(store.capturedCleanSource(receipt)); unchanged(store, before); assertEquals(1, pending(store))
    }
}
