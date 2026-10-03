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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated opt-in SQLite admissions and fences; no Provider, network or account proof. */
@RunWith(AndroidJUnit4::class)
class CapturedCreateAcknowledgementStoreDeviceTest {
    private val scope = CalendarScope(AccountId("captured-ack-fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("synthetic-import", 1)
    private val content = EventContent("Synthetic sent reference",
        EventTime.AllDay(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)), details = EventDetails())
    private val mutation = MutationId("synthetic-later-mutation")
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
    private fun proposal(value: CapturedCreateBaseline = first, nonce: Long = 201) =
        CapturedCreateAcknowledgementReceipt(value, UUID(0, nonce).toString())
    private fun current(deleted: Boolean = false, local: EventContent = content.copy(title = "Later local title"),
        acknowledgement: CapturedCreateAcknowledgementReceipt? = null) =
        CapturedCreateCurrent(first, local, deleted, acknowledgement)
    private fun edit() = LocalEditIntent(mutation, scope, format, first.change, current().content)
    private fun deletion() = LocalDeleteIntent(mutation, scope, format, first.change, current().content)
    private fun scopeKey(target: CalendarScope = scope) = JSONArray().put(target.account.value).put(target.calendar.value)
        .put(target.generation).toString()
    private fun fixture(initialize: Boolean = true, block: suspend (Context, String, ImportStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "captured-ack-fixture-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            if (initialize) {
                store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic fixture")))
                store.setEnabled(scope, true)
                store.transaction(scope) { checkpoint = PageCheckpoint(scope, format) }
                assertTrue(store.retainCapturedBaseline(first, AppliedCapturedCreateAttachment(first.receipt)))
                store.queue(first.change)
            }
            block(context, name, store)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun ImportStore.queue(change: RemoteChange) = transaction(scope) {
        putRemote(change); putProjection(ProjectionIntent(scope, change))
    }
    private fun raw(context: Context, name: String, block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    }
    private fun rows(db: SQLiteDatabase, table: String): List<List<String?>> =
        db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { row ->
            buildList { while (row.moveToNext()) add(List(row.columnCount) { if (row.isNull(it)) null else row.getString(it) }) }
        }
    private fun rawRows(store: ImportStore, table: String) = rows(store.writableDatabase, table)
    private fun rawPending(store: ImportStore): Int = store.writableDatabase.rawQuery(
        "SELECT pending FROM records WHERE scope=? AND event=?", arrayOf(scopeKey(), first.change.eventId.value)).use {
            assertTrue(it.moveToFirst()); it.getInt(0)
        }
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected acknowledgement refusal") }
        catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
        catch (_: SQLiteException) { }
    }
    private suspend fun prove(store: ImportStore): CapturedCreateAcknowledgementRecord {
        val reserved = store.reserveCapturedAcknowledgement(proposal())
        return store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(reserved.record.receipt))
    }
    private val untouchedTables get() = listOf("records", "projection_bases", "captured_create_bases", "projection_receipts", "deferred_events")
    private fun checkpoint() = JSONObject().put("adapter", format.id).put("version", format.version)
        .put("cursor", JSONObject.NULL).put("complete", false).toString()
    private fun encoded(change: RemoteChange.Upsert) = JSONObject().apply {
        put("id", change.eventId.value); put("revision", change.revision.value); put("deleted", false)
        put("title", change.content.title); put("description", change.content.description); put("location", change.content.location)
        put("details", JSONObject().put("schema", 1).put("reminders", JSONArray()).put("attendees", JSONArray()))
        val time = change.content.time as EventTime.AllDay
        put("allDay", true); put("start", time.start.toString()); put("end", time.endExclusive.toString())
    }.toString()

    @Test fun firstReservationIsFreshUnknownAndDifferentProposalsKeepTheFirstNonce() = fixture { _, _, store ->
        val before = untouchedTables.associateWith { rawRows(store, it) }
        assertNull(store.capturedAcknowledgement(scope, first.change.eventId))
        val reserved = store.reserveCapturedAcknowledgement(proposal())
        assertTrue(reserved.fresh); assertEquals(proposal(), reserved.record.receipt)
        assertEquals(CapturedCreateAcknowledgementState.UNKNOWN, reserved.record.state)
        for (next in listOf(proposal(), proposal(nonce = 202))) {
            val replay = store.reserveCapturedAcknowledgement(next)
            assertFalse(replay.fresh); assertEquals(reserved.record, replay.record)
        }
        assertEquals(reserved.record, store.capturedAcknowledgement(scope, first.change.eventId))
        for ((table, values) in before) assertEquals(values, rawRows(store, table))
        assertEquals(1, rawPending(store)); assertEquals(1, rawRows(store, "captured_create_acknowledgements").size)
    }

    @Test fun reopeningAnUnresolvedAdmissionCannotReadmitAfterPreparationLoss() = fixture { context, name, store ->
        val reserved = store.reserveCapturedAcknowledgement(proposal())
        store.close()
        ImportStore(context, name).use { reopened ->
            val replay = reopened.reserveCapturedAcknowledgement(proposal(nonce = 299))
            assertFalse(replay.fresh); assertEquals(reserved.record, replay.record)
            reject { reopened.retainCapturedLocalChange(current(), mutation) }
            reject { reopened.captureLocalEdit(edit()) }; reject { reopened.captureLocalDelete(deletion()) }
            assertTrue(rawRows(reopened, "local_edits").isEmpty()); assertEquals(1, rawPending(reopened))
        }
    }

    @Test fun exactProviderProofPromotesOnlyStateAndReopenRetainsTheFirstProof() = fixture { context, name, store ->
        val before = untouchedTables.associateWith { rawRows(store, it) }
        val reserved = store.reserveCapturedAcknowledgement(proposal())
        val expected = reserved.record.copy(state = CapturedCreateAcknowledgementState.PROVEN)
        assertEquals(expected, store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(proposal())))
        assertEquals(expected, store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(proposal())))
        assertEquals(expected, store.reserveCapturedAcknowledgement(proposal(nonce = 202)).record)
        for ((table, values) in before) assertEquals(values, rawRows(store, table))
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(expected, reopened.capturedAcknowledgement(scope, first.change.eventId))
            assertFalse(reopened.reserveCapturedAcknowledgement(proposal()).fresh)
            assertEquals(1, rawPending(reopened))
        }
    }

    @Test fun proofCannotBeInventedOrReplaceReservedNonceOrFullSource() = fixture { _, _, store ->
        reject { store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(proposal())) }
        val reserved = store.reserveCapturedAcknowledgement(proposal())
        reject { store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(proposal(nonce = 202))) }
        val changedAttachment = first.copy(receipt = first.receipt.copy(nonce = UUID(0, 777).toString()))
        reject { store.reserveCapturedAcknowledgement(proposal(changedAttachment)) }
        reject { store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(proposal(changedAttachment))) }
        assertEquals(reserved.record, store.capturedAcknowledgement(scope, first.change.eventId)); assertEquals(1, rawPending(store))
    }

    @Test fun rawLocalIntentionRefusesReservationAndPromotionWithoutReplacingIt() = fixture { context, name, store ->
        store.retainCapturedLocalChange(current(), mutation)
        val before = rawRows(store, "local_edits")
        reject { store.reserveCapturedAcknowledgement(proposal()) }
        assertTrue(rawRows(store, "captured_create_acknowledgements").isEmpty())
        raw(context, name) { it.execSQL("DELETE FROM local_edits") }
        val reserved = store.reserveCapturedAcknowledgement(proposal())
        raw(context, name) { it.execSQL("INSERT INTO local_edits VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", before.single().toTypedArray()) }
        reject { store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(proposal())) }
        reject { store.reserveCapturedAcknowledgement(proposal(nonce = 202)) }
        assertEquals(reserved.record, store.capturedAcknowledgement(scope, first.change.eventId))
        raw(context, name) { it.execSQL("UPDATE local_edits SET local='unreadable-local-evidence'") }
        reject { store.reserveCapturedAcknowledgement(proposal()) }
        reject { store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(proposal())) }
        assertEquals(1, rawPending(store))
    }

    @Test fun rawOrdinaryProjectionProofRefusesReservationAndPromotionEvenWhenMalformed() = fixture { context, name, store ->
        val reserved = store.reserveCapturedAcknowledgement(proposal())
        store.prepareProjection(ProjectionIntent(scope, first.change), format)
        raw(context, name) { it.execSQL("UPDATE projection_receipts SET payload='unreadable-projection-proof'") }
        val before = rawRows(store, "projection_receipts")
        reject { store.reserveCapturedAcknowledgement(proposal()) }
        reject { store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(proposal())) }
        assertEquals(before, rawRows(store, "projection_receipts"))
        assertEquals(reserved.record, store.capturedAcknowledgement(scope, first.change.eventId)); assertEquals(1, rawPending(store))
    }

    @Test fun malformedNonceProtocolFingerprintOrStateCannotBecomeAbsenceOrOpenAnyCapture() = fixture { context, name, store ->
        store.reserveCapturedAcknowledgement(proposal())
        for (corruption in listOf("nonce='invalid'", "protocol=2", "protocol=1.5", "fingerprint='invalid'", "state='INVALID'")) {
            raw(context, name) { db -> db.execSQL("PRAGMA ignore_check_constraints=ON")
                db.execSQL("UPDATE captured_create_acknowledgements SET $corruption") }
            val before = rawRows(store, "captured_create_acknowledgements")
            reject { store.capturedAcknowledgement(scope, first.change.eventId) }
            reject { store.reserveCapturedAcknowledgement(proposal(nonce = 202)) }
            reject { store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(proposal())) }
            reject { store.retainCapturedLocalChange(current(local = content), mutation) }
            reject { store.captureLocalEdit(edit()) }; reject { store.captureLocalDelete(deletion()) }
            assertEquals(before, rawRows(store, "captured_create_acknowledgements")); assertTrue(rawRows(store, "local_edits").isEmpty())
            raw(context, name) { it.execSQL("UPDATE captured_create_acknowledgements SET nonce=?, protocol=1, fingerprint=?, state='UNKNOWN'",
                arrayOf(proposal().nonce, proposal().fingerprint())) }
        }
        assertEquals(1, rawPending(store))
    }

    @Test fun orphanOrValidProvenanceDriftIsUnresolvedRatherThanAFreeAdmission() = fixture { context, name, store ->
        store.reserveCapturedAcknowledgement(proposal())
        raw(context, name) { it.execSQL("UPDATE captured_create_bases SET nonce=?", arrayOf(UUID(0, 777).toString())) }
        assertNotNull(store.capturedBaseline(scope, first.change.eventId)) // Structurally valid history is not the expected source.
        reject { store.capturedAcknowledgement(scope, first.change.eventId) }
        reject { store.retainCapturedLocalChange(current(), mutation) }
        raw(context, name) { it.execSQL("DELETE FROM captured_create_bases") }
        reject { store.capturedAcknowledgement(scope, first.change.eventId) }
        reject { store.reserveCapturedAcknowledgement(proposal()) }
        reject { store.captureLocalEdit(edit()) }; reject { store.captureLocalDelete(deletion()) }
        assertEquals(1, rawRows(store, "captured_create_acknowledgements").size); assertEquals(1, rawPending(store))
    }

    @Test fun activeExactCheckpointAndUnadvancedSentReferenceAreRequired() = fixture { context, name, store ->
        store.transaction(scope) { checkpoint = null }
        reject { store.reserveCapturedAcknowledgement(proposal()) }
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format.copy(version = 2)) }
        reject { store.reserveCapturedAcknowledgement(proposal()) }
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format) }
        store.setEnabled(scope, false); reject { store.reserveCapturedAcknowledgement(proposal()) }
        store.setEnabled(scope, true); store.transaction(scope) { checkpoint = PageCheckpoint(scope, format) }
        store.reserveCapturedAcknowledgement(proposal())
        raw(context, name) { it.execSQL("UPDATE projection_bases SET payload='advanced-or-unreadable-base'") }
        reject { store.capturedAcknowledgement(scope, first.change.eventId) }
        reject { store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(proposal())) }
        assertEquals(1, rawRows(store, "captured_create_acknowledgements").size); assertEquals(1, rawPending(store))
    }

    @Test fun remoteChangesAndDeferredEvidenceBeforeAndAfterProofAreNeverConsumed() = fixture { _, _, store ->
        val remote = first.change.copy(content = content.copy(location = "Different remote location"))
        store.queue(remote)
        val deferred = DeferredCalendarEvent(first.change.eventId, first.change.revision, format,
            DeferredEventReason.UNVERIFIED_CONTENT, "synthetic-sealed-evidence")
        store.transaction(scope) { (this as StagedSyncTransaction).defer(deferred) }
        val before = untouchedTables.associateWith { rawRows(store, it) }
        val proven = prove(store)
        for ((table, values) in before) assertEquals(values, rawRows(store, table))
        assertEquals(1, rawPending(store)); assertEquals(remote, store.transaction(scope) { remote(first.change.eventId) })
        assertEquals(deferred, store.deferred(scope).single())
        assertEquals(LocalEditState.CONFLICT, store.retainCapturedLocalChange(current(acknowledgement = proven.receipt), mutation))
        assertEquals(proven, store.capturedAcknowledgement(scope, first.change.eventId)); assertEquals(1, rawPending(store))
    }

    @Test fun provenHistoryPermitsCapturedEditAndDoesNotReacknowledgeOrClearItsFence() = fixture { _, _, store ->
        val proven = prove(store)
        val before = untouchedTables.associateWith { rawRows(store, it) }
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(acknowledgement = proven.receipt), mutation))
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(acknowledgement = proven.receipt), mutation))
        assertEquals(edit(), store.localEdit(scope, first.change.eventId))
        assertEquals(proven, store.capturedAcknowledgement(scope, first.change.eventId))
        reject { store.reserveCapturedAcknowledgement(proposal()) }
        reject { store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(proposal())) }
        for ((table, values) in before) assertEquals(values, rawRows(store, table))
        assertEquals(1, rawPending(store))
    }

    @Test fun aPreAcknowledgementSnapshotCannotBeCapturedAfterTheJournalBecomesProven() = fixture { _, _, store ->
        val stale = current()
        val unchangedStale = current(local = content)
        val proven = prove(store)
        val before = untouchedTables.associateWith { rawRows(store, it) }
        reject { store.retainCapturedLocalChange(stale, mutation) }
        reject { store.retainCapturedLocalChange(unchangedStale, mutation) }
        reject { store.retainCapturedLocalChange(current(acknowledgement = proposal(nonce = 202)), mutation) }
        assertTrue(rawRows(store, "local_edits").isEmpty())
        assertNull(store.retainCapturedLocalChange(current(local = content, acknowledgement = proven.receipt), mutation))
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(acknowledgement = proven.receipt), mutation))
        reject { store.retainCapturedLocalChange(stale, mutation) } // The source fence also precedes replay.
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(acknowledgement = proven.receipt), mutation))
        for ((table, values) in before) assertEquals(values, rawRows(store, table))
        assertEquals(proven, store.capturedAcknowledgement(scope, first.change.eventId)); assertEquals(1, rawPending(store))
    }

    @Test fun snapshotWithAcknowledgementRequiresItsExactDurableJournalRecord() = fixture { _, _, store ->
        val before = untouchedTables.associateWith { rawRows(store, it) }
        reject { store.retainCapturedLocalChange(current(acknowledgement = proposal()), mutation) }
        reject { store.retainCapturedLocalChange(current(local = content, acknowledgement = proposal()), mutation) }
        assertNull(store.capturedAcknowledgement(scope, first.change.eventId)); assertTrue(rawRows(store, "local_edits").isEmpty())
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(), mutation))
        reject { store.retainCapturedLocalChange(current(acknowledgement = proposal()), mutation) }
        assertEquals(edit(), store.localEdit(scope, first.change.eventId))
        for ((table, values) in before) assertEquals(values, rawRows(store, table))
        assertEquals(1, rawPending(store))
    }

    @Test fun provenHistoryCannotAuthorizeGenericUpdateAndTombstoneSnapshots() = fixture { _, _, store ->
        val proven = prove(store)
        reject { store.captureLocalEdit(edit()) }
        reject { store.captureLocalEdit(edit()) }
        assertEquals(proven, store.capturedAcknowledgement(scope, first.change.eventId))
        reject { store.captureLocalDelete(deletion()) }
        reject { store.captureLocalDelete(deletion()) }
        assertEquals(proven, store.capturedAcknowledgement(scope, first.change.eventId))
        assertEquals(1, rawRows(store, "captured_create_acknowledgements").size)
        assertTrue(rawRows(store, "local_edits").isEmpty())
    }

    @Test fun capturedTombstonePreservesProvenHistoryAcrossReopen() = fixture { context, name, store ->
        val proven = prove(store)
        val before = untouchedTables.associateWith { rawRows(store, it) }
        assertEquals(LocalEditState.PAUSED, store.retainCapturedLocalChange(current(deleted = true, acknowledgement = proven.receipt), mutation))
        assertEquals(deletion(), store.localDelete(scope, first.change.eventId))
        for ((table, values) in before) assertEquals(values, rawRows(store, table))
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(proven, reopened.capturedAcknowledgement(scope, first.change.eventId))
            assertEquals(LocalEditState.PAUSED, reopened.retainCapturedLocalChange(current(deleted = true, acknowledgement = proven.receipt), mutation))
            assertEquals(deletion(), reopened.localDelete(scope, first.change.eventId)); assertEquals(1, rawPending(reopened))
        }
    }

    @Test fun unknownOrCorruptFencesAreCheckedBeforeAllExistingIntentionReplays() = fixture { context, name, store ->
        val proven = prove(store)
        store.retainCapturedLocalChange(current(acknowledgement = proven.receipt), mutation)
        raw(context, name) { it.execSQL("UPDATE captured_create_acknowledgements SET state='UNKNOWN'") }
        reject { store.retainCapturedLocalChange(current(), mutation) }; reject { store.captureLocalEdit(edit()) }
        raw(context, name) { it.execSQL("UPDATE local_edits SET kind='DELETE'") }
        reject { store.captureLocalDelete(deletion()) }
        raw(context, name) { it.execSQL("UPDATE captured_create_acknowledgements SET state='PROVEN', fingerprint='corrupt'") }
        reject { store.captureLocalDelete(deletion()) }
        raw(context, name) { it.execSQL("UPDATE local_edits SET kind='UPDATE'") }
        reject { store.retainCapturedLocalChange(current(), mutation) }; reject { store.captureLocalEdit(edit()) }
        assertEquals(1, rawRows(store, "local_edits").size); assertEquals(1, rawPending(store))
    }

    @Test fun nonceCannotBeReusedByAnotherEventAndScopedLookupCannotAdoptForeignHistory() = fixture { _, _, store ->
        val reserved = store.reserveCapturedAcknowledgement(proposal())
        val second = baseline(2)
        assertTrue(store.retainCapturedBaseline(second, AppliedCapturedCreateAttachment(second.receipt)))
        reject { store.reserveCapturedAcknowledgement(proposal(second)) }
        assertNull(store.capturedAcknowledgement(scope, second.change.eventId))
        assertTrue(store.reserveCapturedAcknowledgement(proposal(second, 202)).fresh)
        val foreignScope = scope.copy(calendar = CalendarId("foreign-calendar"))
        store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic original"), CalendarSummary(foreignScope.calendar, "Synthetic foreign")))
        store.setEnabled(foreignScope, true); store.transaction(foreignScope) { checkpoint = PageCheckpoint(foreignScope, format) }
        assertNull(store.capturedAcknowledgement(foreignScope, first.change.eventId))
        reject { store.reserveCapturedAcknowledgement(proposal(baseline(target = foreignScope), 203)) }
        assertEquals(reserved.record, store.capturedAcknowledgement(scope, first.change.eventId))
    }

    @Test fun insertOrPromotionTriggerFailureRollsBackAdmissionAndState() = fixture { context, name, store ->
        val before = untouchedTables.associateWith { rawRows(store, it) }
        raw(context, name) { it.execSQL("CREATE TRIGGER reject_ack_insert BEFORE INSERT ON captured_create_acknowledgements BEGIN SELECT RAISE(ABORT, 'synthetic rejection'); END") }
        reject { store.reserveCapturedAcknowledgement(proposal()) }
        assertNull(store.capturedAcknowledgement(scope, first.change.eventId))
        raw(context, name) { it.execSQL("DROP TRIGGER reject_ack_insert") }
        val reserved = store.reserveCapturedAcknowledgement(proposal())
        raw(context, name) { it.execSQL("CREATE TRIGGER reject_ack_update BEFORE UPDATE ON captured_create_acknowledgements BEGIN SELECT RAISE(ABORT, 'synthetic rejection'); END") }
        reject { store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(proposal())) }
        assertEquals(reserved.record, store.capturedAcknowledgement(scope, first.change.eventId))
        for ((table, values) in before) assertEquals(values, rawRows(store, table))
    }

    @Test fun revokedLeaseCannotReserveOrPromoteAndReadLeaseIsAlsoGuarded() = fixture { _, _, store ->
        var calls = 0; store.admitTransaction = { ++calls == 1 }
        reject { store.reserveCapturedAcknowledgement(proposal()) }
        store.admitTransaction = { true }; assertNull(store.capturedAcknowledgement(scope, first.change.eventId))
        val reserved = store.reserveCapturedAcknowledgement(proposal())
        calls = 0; store.admitTransaction = { ++calls == 1 }
        reject { store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(proposal())) }
        store.admitTransaction = { true }; assertEquals(reserved.record, store.capturedAcknowledgement(scope, first.change.eventId))
        calls = 0; store.admitTransaction = { ++calls == 1 }
        reject { store.capturedAcknowledgement(scope, first.change.eventId) }
        store.admitTransaction = { true }; assertEquals(1, rawPending(store))
    }

    @Test fun cancellationBeforeCommitPreservesAbsenceThenTheUnknownReservation() = fixture { context, name, store ->
        suspend fun cancelled(action: suspend () -> Unit) = coroutineScope {
            val job = async {
                val caller = currentCoroutineContext().job
                var calls = 0; store.admitTransaction = { if (++calls == 2) caller.cancel(); true }
                action()
            }
            try { job.await(); fail("Cancelled acknowledgement must not commit") } catch (_: CancellationException) { }
            store.admitTransaction = { true }
        }
        cancelled { store.reserveCapturedAcknowledgement(proposal()) }
        assertNull(store.capturedAcknowledgement(scope, first.change.eventId))
        val reserved = store.reserveCapturedAcknowledgement(proposal())
        cancelled { store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(proposal())) }
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(reserved.record, reopened.capturedAcknowledgement(scope, first.change.eventId))
            assertFalse(reopened.reserveCapturedAcknowledgement(proposal(nonce = 202)).fresh); assertEquals(1, rawPending(reopened))
        }
    }

    @Test fun simultaneousHelpersGrantExactlyOneFreshAdmissionAndRetainOneProof() = fixture { context, name, store ->
        ImportStore(context, name).use { other ->
            other.writableDatabase
            val admissions = coroutineScope {
                val start = CompletableDeferred<Unit>()
                val one = async(Dispatchers.IO) { start.await(); store.reserveCapturedAcknowledgement(proposal()) }
                val two = async(Dispatchers.IO) { start.await(); other.reserveCapturedAcknowledgement(proposal(nonce = 202)) }
                start.complete(Unit); listOf(one.await(), two.await())
            }
            assertEquals(1, admissions.count { it.fresh }); assertEquals(admissions[0].record, admissions[1].record)
            val receipt = admissions[0].record.receipt
            coroutineScope {
                val one = async(Dispatchers.IO) { store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(receipt)) }
                val two = async(Dispatchers.IO) { other.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(receipt)) }
                assertEquals(one.await(), two.await())
            }
            assertEquals(CapturedCreateAcknowledgementState.PROVEN, other.capturedAcknowledgement(scope, first.change.eventId)?.state)
            assertEquals(1, rawRows(store, "captured_create_acknowledgements").size); assertEquals(1, rawPending(store))
        }
    }

    @Test fun independentVersionElevenMigrationPreservesAllWorkAndProvenanceWithoutInventingAck() =
        fixture(initialize = false) { context, name, store ->
            val path = context.getDatabasePath(name); path.parentFile?.mkdirs()
            val tables = listOf("calendars", "records", "inbox", "deferred_events", "delta_state", "delta_jobs",
                "delta_reconciliation", "coverage_state", "series_members", "mapping_profiles", "projection_bases",
                "local_edits", "projection_receipts", "captured_create_bases")
            val before = mutableMapOf<String, List<List<String?>>>()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
                old.execSQL("CREATE TABLE calendars (scope TEXT PRIMARY KEY, account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL, title TEXT NOT NULL, color INTEGER, enabled INTEGER NOT NULL DEFAULT 0, checkpoint TEXT, status TEXT NOT NULL DEFAULT 'PAUSED', completed_at INTEGER, next_attempt INTEGER NOT NULL DEFAULT 0, available INTEGER NOT NULL DEFAULT 1)")
                old.execSQL("CREATE TABLE records (scope TEXT NOT NULL, event TEXT NOT NULL, payload TEXT NOT NULL, pending INTEGER NOT NULL, PRIMARY KEY(scope,event))")
                old.execSQL("CREATE TABLE inbox (scope TEXT PRIMARY KEY, request_checkpoint TEXT NOT NULL, request_limit INTEGER NOT NULL, payload TEXT NOT NULL, coverage TEXT)")
                old.execSQL("CREATE TABLE deferred_events (scope TEXT NOT NULL, event TEXT NOT NULL, revision TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL, reason TEXT NOT NULL, payload TEXT NOT NULL, retry_pending INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(scope,event))")
                old.execSQL("CREATE TABLE delta_state (scope TEXT PRIMARY KEY, cursor_adapter TEXT, cursor_version INTEGER, cursor_value TEXT, received_adapter TEXT, received_version INTEGER, received_after TEXT, received_payload TEXT, more INTEGER NOT NULL DEFAULT 0)")
                old.execSQL("CREATE TABLE delta_jobs (scope TEXT NOT NULL, notice_id TEXT NOT NULL, event TEXT, notice TEXT NOT NULL, received TEXT, blocked INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(scope,notice_id))")
                old.execSQL("CREATE TABLE delta_reconciliation (id INTEGER PRIMARY KEY, scope TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL, after_cursor TEXT NOT NULL, payload TEXT NOT NULL)")
                old.execSQL("CREATE TABLE coverage_state (scope TEXT PRIMARY KEY, payload TEXT NOT NULL)")
                old.execSQL("CREATE TABLE series_members (scope TEXT NOT NULL, event TEXT NOT NULL, uid TEXT NOT NULL, role TEXT NOT NULL, PRIMARY KEY(scope,event))")
                old.execSQL("CREATE TABLE mapping_profiles (scope TEXT PRIMARY KEY, adapter TEXT NOT NULL, version INTEGER NOT NULL, rescan_pending INTEGER NOT NULL DEFAULT 1)")
                old.execSQL("CREATE TABLE projection_bases (scope TEXT NOT NULL, event TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(scope,event))")
                old.execSQL("CREATE TABLE local_edits (scope TEXT NOT NULL, mutation TEXT NOT NULL, event TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL, base TEXT NOT NULL, local TEXT NOT NULL, state TEXT NOT NULL, kind TEXT NOT NULL DEFAULT 'UPDATE', PRIMARY KEY(scope,mutation), UNIQUE(scope,event))")
                old.execSQL("CREATE TABLE projection_receipts (scope TEXT NOT NULL, event TEXT NOT NULL, nonce TEXT NOT NULL UNIQUE, payload TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL, previous_base TEXT, protocol INTEGER NOT NULL, PRIMARY KEY(scope,event))")
                old.execSQL("""CREATE TABLE captured_create_bases (
                    scope TEXT NOT NULL, event TEXT NOT NULL, account TEXT NOT NULL, calendar TEXT NOT NULL,
                    generation INTEGER NOT NULL CHECK(generation >= 0), attempt TEXT NOT NULL, adapter TEXT NOT NULL,
                    version INTEGER NOT NULL CHECK(version > 0), payload_fingerprint TEXT NOT NULL, local_id TEXT NOT NULL,
                    capture_id TEXT NOT NULL, capture_adapter TEXT NOT NULL, capture_version INTEGER NOT NULL CHECK(capture_version > 0),
                    timestamp INTEGER NOT NULL, content_fingerprint TEXT NOT NULL, fingerprint_version INTEGER NOT NULL CHECK(fingerprint_version = 1),
                    verified_revision TEXT NOT NULL, nonce TEXT NOT NULL UNIQUE, protocol INTEGER NOT NULL CHECK(protocol = 1),
                    payload TEXT NOT NULL, PRIMARY KEY(scope,event), UNIQUE(scope,attempt), UNIQUE(scope,local_id), UNIQUE(scope,capture_id)
                )""".trimIndent())
                old.execSQL("INSERT INTO calendars VALUES (?, ?, ?, ?, ?, NULL, 1, ?, 'BASELINE', NULL, 0, 1)", arrayOf<Any>(scopeKey(), scope.account.value, scope.calendar.value, scope.generation, "Synthetic migration", checkpoint()))
                old.execSQL("INSERT INTO records VALUES (?, ?, ?, 1)", arrayOf(scopeKey(), first.change.eventId.value, encoded(first.change)))
                old.execSQL("INSERT INTO inbox VALUES (?, ?, 4, ?, NULL)", arrayOf(scopeKey(), checkpoint(), "synthetic-sealed-page"))
                old.execSQL("INSERT INTO deferred_events VALUES (?, 'deferred-event', 'deferred-revision', ?, 1, 'UNSUPPORTED_CONTENT', 'synthetic-sealed-event', 1)", arrayOf(scopeKey(), format.id))
                old.execSQL("INSERT INTO delta_state VALUES (?, ?, 1, 'cursor', ?, 1, 'cursor', 'synthetic-received', 1)", arrayOf(scopeKey(), format.id, format.id))
                old.execSQL("INSERT INTO delta_jobs VALUES (?, 'notice', 'job-event', 'synthetic-notice', 'synthetic-event', 1)", arrayOf(scopeKey()))
                old.execSQL("INSERT INTO delta_reconciliation VALUES (1, ?, ?, 1, 'cursor', 'synthetic-history')", arrayOf(scopeKey(), format.id))
                old.execSQL("INSERT INTO coverage_state VALUES (?, ?)", arrayOf(scopeKey(), JSONObject().put("schema", 2).put("epoch", 0)
                    .put("active", JSONObject.NULL).put("previous", JSONObject.NULL).put("observed", JSONObject.NULL).toString()))
                old.execSQL("INSERT INTO series_members VALUES (?, 'series-event', 'synthetic-series', 'master')", arrayOf(scopeKey()))
                old.execSQL("INSERT INTO mapping_profiles VALUES (?, ?, 1, 1)", arrayOf(scopeKey(), format.id))
                old.execSQL("INSERT INTO projection_bases VALUES (?, ?, ?)", arrayOf(scopeKey(), first.change.eventId.value, encoded(first.change)))
                old.execSQL("INSERT INTO projection_bases VALUES (?, 'generic-event', ?)", arrayOf(scopeKey(), encoded(first.change.copy(eventId = EventId("generic-event")))))
                old.execSQL("INSERT INTO local_edits VALUES (?, 'local-mutation', 'generic-event', ?, 1, ?, ?, 'CONFLICT', 'DELETE')", arrayOf(scopeKey(), format.id,
                    encoded(first.change.copy(eventId = EventId("generic-event"))), encoded(first.change.copy(eventId = EventId("generic-event")))))
                old.execSQL("INSERT INTO projection_receipts VALUES (?, 'receipt-event', ?, ?, ?, 1, NULL, 1)", arrayOf(scopeKey(), UUID(0, 500).toString(),
                    encoded(first.change.copy(eventId = EventId("receipt-event"))), format.id))
                val identity = first.receipt.binding.identity
                old.execSQL("INSERT INTO captured_create_bases VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any>(scopeKey(), first.change.eventId.value, scope.account.value, scope.calendar.value, scope.generation,
                        identity.attempt.id.value, identity.attempt.format.id, identity.attempt.format.version, identity.attempt.payloadFingerprint,
                        identity.localId.value, identity.captureId.value, identity.captureFormat.id, identity.captureFormat.version,
                        identity.timestamp.epochSecond, identity.contentFingerprint, identity.fingerprintVersion,
                        first.receipt.binding.verifiedRevision.value, first.receipt.nonce, first.receipt.protocol, encoded(first.change)))
                old.version = 11
                for (table in tables) before[table] = rows(old, table)
            }
            assertEquals(12, store.writableDatabase.version)
            store.writableDatabase.rawQuery("PRAGMA synchronous", null).use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
            for (table in tables) assertEquals("Migration must preserve $table", before[table], rawRows(store, table))
            assertTrue(rawRows(store, "captured_create_acknowledgements").isEmpty())
            assertNull(store.capturedAcknowledgement(scope, first.change.eventId)); assertEquals(first, store.capturedBaseline(scope, first.change.eventId))
            assertTrue(store.reserveCapturedAcknowledgement(proposal()).fresh)
            assertEquals(1, rawPending(store)); assertEquals(1, rawRows(store, "local_edits").size)
        }
}
