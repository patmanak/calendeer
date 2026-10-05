// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated SQLite only. Synthetic CREATE/UPDATE proofs qualify persistence, never a service or Provider. */
@RunWith(AndroidJUnit4::class)
class CapturedHandoffTitleAcknowledgementStoreDeviceTest {
    private val scope = CalendarScope(AccountId("handoff-title-fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("synthetic-handoff-title-import", 1)
    private val content = EventContent("Synthetic created original", EventTime.AllDay(
        LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 8)), "Synthetic preserved description", "Synthetic location", details = EventDetails())
    private val original: CapturedCreateBaseline get() {
        val capture = LocalCreateIntent(LocalCreateReservation(MutationId("synthetic-capture"), LocalEventId(UUID(0, 2).toString()),
            scope, format, Instant.parse("2026-10-06T00:00:00Z")), content)
        val submission = LocalCreateSubmission(LocalCreateSubmissionReservation(capture,
            MutationId(UUID(0, 3).toString()), AdapterFormat("synthetic-create-wire", 2)), "synthetic-opaque-create-not-to-duplicate")
        val change = RemoteChange.Upsert(EventId("synthetic-created-event"), Revision("synthetic-create-revision"), content)
        return CapturedCreateBaseline(CapturedCreateAttachmentReceipt(CapturedCreateBinding(CapturedCreateDispatchIdentity.of(submission),
            change.eventId, change.revision), UUID(0, 101).toString()), change)
    }
    private val acknowledgement get() = CapturedCreateAcknowledgementReceipt(original, UUID(0, 201).toString())
    private val handoff get() = CapturedCreateHandoffReceipt(acknowledgement, UUID(0, 301).toString())
    private data class Work(val frame: CapturedCreateHandoffFrame, val intent: LocalEditIntent,
        val preparation: LocalEditPreparation, val result: TitleUpdateVerifiedResult)

    private inner class Fixture(val context: Context, val name: String, val store: ImportStore) {
        suspend fun stage(advanced: Boolean = false): Work {
            val reservedAck = store.reserveCapturedAcknowledgement(acknowledgement).record.receipt
            store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(reservedAck))
            val reserved = store.reserveCapturedHandoff(handoff)
            var frame = store.retainCapturedHandoff(reserved.frame.receipt, CapturedCreateCleanWitness(acknowledgement)).frame
            if (advanced) {
                val incoming = frame.base.copy(revision = Revision("synthetic-incoming-revision"), content = content.copy(title = "Synthetic projected incoming"))
                store.transaction(scope) { putRemote(incoming); putProjection(ProjectionIntent(scope, incoming)) }
                val plan = store.prepareCapturedHandoffProjection(frame, ProjectionIntent(scope, incoming))
                assertTrue(store.restoreCapturedHandoffProjection(AppliedCapturedHandoffProjection(plan)))
                frame = store.capturedHandoff(scope, original.change.eventId)!!.frame
            }
            val local = frame.base.content.copy(title = "Synthetic submitted title")
            val intent = LocalEditIntent(MutationId("synthetic-title-intent"), scope, format, frame.base, local)
            assertEquals(LocalEditState.PAUSED, store.retainCapturedHandoffLocalChange(CapturedHandoffCurrent(frame, local, false), intent.id))
            val preparation = LocalEditPreparation(intent, Instant.parse("2026-10-06T12:00:01Z"),
                AdapterFormat("synthetic-title-preparation", 3), "synthetic-sealed-title-preparation-not-to-duplicate")
            assertTrue(store.retainLocalEditPreparation(preparation))
            val record = TitleUpdateAttemptRecord(MutationId("synthetic-title-attempt"), preparation,
                AdapterFormat("synthetic-title-wire", 1), "synthetic-title-wire-not-to-duplicate", TitleUpdateAttemptState.OUTCOME_UNKNOWN)
            val updated = frame.base.copy(revision = Revision("synthetic-title-verified"), content = local)
            val result = TitleUpdateVerifiedResult.of(record, TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(record), updated))
            return Work(frame, intent, preparation, result)
        }
        suspend fun reserve(work: Work): TitleUpdateAcknowledgementReservation = checkNotNull(
            store.prepareTitleUpdateAcknowledgement(work.result, work.frame))
        fun rows(table: String) = store.readableDatabase.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { row ->
            buildList { while (row.moveToNext()) add(List(row.columnCount) { if (row.isNull(it)) null else row.getString(it) }) }
        }
        fun origins() = listOf("captured_create_bases", "captured_create_acknowledgements").associateWith(::rows)
        fun snapshot() = listOf("records", "projection_bases", "captured_create_bases", "captured_create_acknowledgements",
            "captured_create_handoffs", "projection_receipts", "local_edits", "local_edit_preparations", "deferred_events",
            "title_update_acknowledgements").associateWith(::rows)
        fun raw(block: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,
            null, SQLiteDatabase.OPEN_READWRITE).use(block)
        fun archive() = store.readableDatabase.rawQuery("SELECT receipt FROM title_update_acknowledgements ORDER BY rowid", null).use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        suspend fun sourceIntact(work: Work) {
            assertEquals(work.frame, store.capturedHandoff(scope, work.frame.eventId)!!.frame)
            assertTrue(store.capturedHandoff(scope, work.frame.eventId)!!.waiting)
            assertEquals(work.intent, store.localEdit(scope, work.frame.eventId))
            assertEquals(work.preparation, store.localEditPreparation(scope, work.frame.eventId))
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "handoff-title-ack-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic handoff title fixture")))
            store.setEnabled(scope, true); store.transaction(scope) { checkpoint = PageCheckpoint(scope, format) }
            assertTrue(store.retainCapturedBaseline(original, AppliedCapturedCreateAttachment(original.receipt)))
            store.transaction(scope) { putRemote(original.change) }
            Fixture(context, name, store).block()
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected fail-closed transferred-title rejection") }
        catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
        catch (_: SQLiteException) { }
    }

    @Test fun releasedWaitingInitialHandoffReservesCompleteHistoricalFrameAndReopens() = fixture {
        val work = stage()
        val reserved = store.prepareTitleUpdateAcknowledgement(work.result, work.frame)
        assertNotNull(reserved)
        val first = reserved!!
        assertTrue(first.fresh); assertEquals(work.frame, first.record.receipt.handoff)
        assertTrue(first.record.receipt.matches(work.result)); assertEquals(2, JSONObject(archive()).getInt("schema"))
        assertFalse(archive().contains(work.preparation.protectedPayload)); assertFalse(archive().contains(work.result.record.protectedPayload))
        sourceIntact(work); store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(17, reopened.readableDatabase.version)
            assertEquals(first.record, reopened.titleUpdateAcknowledgement(scope, work.frame.eventId))
            assertEquals(first.copy(fresh = false), reopened.prepareTitleUpdateAcknowledgement(work.result, work.frame))
        }
    }

    @Test fun initialRestoreAdvancesCurrentFrameWithoutChangingOriginalCreationEvidence() = fixture {
        val work = stage(); val first = reserve(work); val immutableOrigins = origins()
        assertTrue(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(first.record.receipt)))
        val current = store.capturedHandoff(scope, work.frame.eventId)!!
        assertEquals(CapturedCreateHandoffState.RELEASED, current.state); assertFalse(current.waiting)
        assertEquals(CapturedCreateHandoffFrame(work.frame.receipt, work.result.event, first.record.receipt.projection), current.frame)
        assertEquals(immutableOrigins, origins()); assertNull(store.localEdit(scope, work.frame.eventId))
        assertNull(store.localEditPreparation(scope, work.frame.eventId))
        assertEquals(work.result.event, store.transaction(scope) { remote(work.frame.eventId) })
        assertEquals(TitleUpdateAcknowledgementState.RESTORED, store.titleUpdateAcknowledgement(scope, work.frame.eventId)!!.state)
    }

    @Test fun advancedHandoffRetainsPreviousProjectionInArchiveThenAdvancesExactlyOnce() = fixture {
        val work = stage(advanced = true); assertNotNull(work.frame.lastProjection)
        val first = reserve(work); val prior = work.frame.lastProjection!!
        assertEquals(prior, first.record.receipt.handoff!!.lastProjection)
        val immutableOrigins = origins()
        assertTrue(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(first.record.receipt)))
        assertEquals(first.record.receipt.projection, store.capturedHandoff(scope, work.frame.eventId)!!.frame.lastProjection)
        assertEquals(immutableOrigins, origins()); store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(first.record.copy(state = TitleUpdateAcknowledgementState.RESTORED), reopened.titleUpdateAcknowledgement(scope, work.frame.eventId))
            assertEquals(prior, reopened.titleUpdateAcknowledgement(scope, work.frame.eventId)!!.receipt.handoff!!.lastProjection)
        }
    }

    @Test fun archivedReceiptIsIndependentOfCurrentHandoffTablesAndInactiveScope() = fixture {
        val work = stage(advanced = true); val first = reserve(work)
        assertTrue(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(first.record.receipt)))
        store.setEnabled(scope, false)
        raw { db -> db.execSQL("DELETE FROM captured_create_handoffs"); db.execSQL("DELETE FROM captured_create_acknowledgements"); db.execSQL("DELETE FROM captured_create_bases") }
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(first.record.copy(state = TitleUpdateAcknowledgementState.RESTORED), reopened.titleUpdateAcknowledgement(scope, work.frame.eventId))
        }
    }

    @Test fun ordinarySchemaOneArchiveRemainsByteForByteStable() = fixture {
        val base = original.change.copy(eventId = EventId("synthetic-ordinary"))
        store.transaction(scope) { putRemote(base); putProjection(ProjectionIntent(scope, base)) }
        assertEquals(1, store.acknowledge(ProjectionIntent(scope, base)))
        val intent = LocalEditIntent(MutationId("synthetic-ordinary-edit"), scope, format, base, base.content.copy(title = "Synthetic ordinary changed"))
        assertEquals(intent, store.captureLocalEdit(intent))
        val preparation = LocalEditPreparation(intent, Instant.parse("2026-10-06T12:00:01Z"), AdapterFormat("synthetic-preparation", 1), "synthetic-sealed")
        store.retainLocalEditPreparation(preparation)
        val record = TitleUpdateAttemptRecord(MutationId("synthetic-ordinary-attempt"), preparation, AdapterFormat("synthetic-wire", 1), "synthetic-wire",
            TitleUpdateAttemptState.OUTCOME_UNKNOWN)
        val result = TitleUpdateVerifiedResult.of(record, TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(record),
            base.copy(revision = Revision("synthetic-ordinary-new"), content = intent.content)))
        val first = store.prepareTitleUpdateAcknowledgement(result)!!
        val serialized = archive(); assertEquals(1, JSONObject(serialized).getInt("schema")); assertFalse(JSONObject(serialized).has("handoff"))
        store.close(); ImportStore(context, name).use { reopened ->
            assertEquals(first.copy(fresh = false), reopened.prepareTitleUpdateAcknowledgement(result))
            reopened.readableDatabase.rawQuery("SELECT receipt FROM title_update_acknowledgements", null).use {
                assertTrue(it.moveToFirst()); assertEquals(serialized, it.getString(0))
            }
        }
    }

    @Test fun explicitWrongFrameOrOriginCannotAdoptLiveTransferredWork() = fixture {
        val work = stage(advanced = true)
        val wrongProjection = work.frame.copy(lastProjection = work.frame.lastProjection!!.copy(nonce = UUID.randomUUID().toString()))
        assertNull(store.prepareTitleUpdateAcknowledgement(work.result, wrongProjection))
        val wrongOrigin = work.frame.copy(receipt = work.frame.receipt.copy(acknowledgement =
            work.frame.receipt.acknowledgement.copy(nonce = UUID.randomUUID().toString())))
        assertNull(store.prepareTitleUpdateAcknowledgement(work.result, wrongOrigin))
        assertNull(store.prepareTitleUpdateAcknowledgement(work.result))
        sourceIntact(work); assertTrue(rows("title_update_acknowledgements").isEmpty())
    }

    @Test fun pendingNotWaitingAndIncomingProjectionReceiptCannotReserveTitleAck() = fixture {
        val work = stage()
        store.writableDatabase.execSQL("UPDATE captured_create_handoffs SET waiting=0")
        assertNull(store.prepareTitleUpdateAcknowledgement(work.result, work.frame))
        store.writableDatabase.execSQL("UPDATE captured_create_handoffs SET state='PENDING'")
        assertNull(store.prepareTitleUpdateAcknowledgement(work.result, work.frame))
        store.writableDatabase.execSQL("UPDATE captured_create_handoffs SET state='RELEASED',waiting=1")
        store.writableDatabase.execSQL("INSERT INTO projection_receipts(scope,event,nonce,payload,adapter,version,previous_base,protocol) SELECT scope,event,?,payload,?,1,payload,1 FROM projection_bases",
            arrayOf(UUID.randomUUID().toString(), format.id))
        assertNull(store.prepareTitleUpdateAcknowledgement(work.result, work.frame))
        assertTrue(rows("title_update_acknowledgements").isEmpty())
    }

    @Test fun unresolvedOriginalAckAndPreparationMismatchRemainHeld() = fixture {
        val work = stage()
        assertNull(store.prepareTitleUpdateAcknowledgement(work.result.copyForPreparation(work.preparation.copy(protectedPayload = "different-sealed")), work.frame))
        store.writableDatabase.execSQL("UPDATE captured_create_acknowledgements SET state='UNKNOWN'")
        reject { store.prepareTitleUpdateAcknowledgement(work.result, work.frame) }
        assertTrue(rows("title_update_acknowledgements").isEmpty()); assertEquals(work.intent, store.localEdit(scope, work.frame.eventId))
    }

    @Test fun nativeProofMayCloseOriginalIntentAfterRemoteEchoChangedItToConflict() = fixture {
        val work = stage(); val first = reserve(work)
        store.transaction(scope) { putRemote(work.result.event); putProjection(ProjectionIntent(scope, work.result.event)) }
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, work.frame.eventId)!!.state)
        assertTrue(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(first.record.receipt)))
        assertNull(store.localEdit(scope, work.frame.eventId)); assertFalse(store.capturedHandoff(scope, work.frame.eventId)!!.waiting)
        assertEquals(work.result.event, store.transaction(scope) { remote(work.frame.eventId) })
    }

    @Test fun laterDeletionDeferredDataAndPendingFlagSurviveHistoricalRestoration() = fixture {
        val work = stage(advanced = true); val first = reserve(work); val immutableOrigins = origins()
        val deleted = RemoteChange.Deleted(work.frame.eventId, Revision("synthetic-later-delete"))
        val deferred = DeferredCalendarEvent(work.frame.eventId, Revision("synthetic-deferred"), format,
            DeferredEventReason.UNSUPPORTED_CONTENT, "synthetic-sealed-deferred")
        store.transaction(scope) { putRemote(deleted); putProjection(ProjectionIntent(scope, deleted)); (this as StagedSyncTransaction).defer(deferred) }
        store.writableDatabase.execSQL("UPDATE records SET pending=1")
        assertTrue(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(first.record.receipt)))
        assertEquals(deleted, store.transaction(scope) { remote(work.frame.eventId) })
        assertEquals(deferred, store.transaction(scope) { (this as StagedSyncTransaction).deferred(work.frame.eventId) })
        assertEquals("1", rows("records").single()[3]); assertEquals(immutableOrigins, origins())
        assertEquals(work.result.event, store.capturedHandoff(scope, work.frame.eventId)!!.frame.base)
    }

    @Test fun restoredReplayPreservesNewHandoffOwnerPreparationAndWaitingState() = fixture {
        val work = stage(); val first = reserve(work); val witness = AppliedTitleUpdateAcknowledgement(first.record.receipt)
        assertTrue(store.restoreTitleUpdateAcknowledgement(witness))
        val nextFrame = store.capturedHandoff(scope, work.frame.eventId)!!.frame
        val nextIntent = work.intent.copy(id = MutationId("synthetic-next-edit"), base = nextFrame.base,
            content = nextFrame.base.content.copy(title = "Synthetic later local title"))
        assertEquals(LocalEditState.PAUSED, store.retainCapturedHandoffLocalChange(CapturedHandoffCurrent(nextFrame, nextIntent.content, false), nextIntent.id))
        val nextPreparation = work.preparation.copy(intent = nextIntent, timestamp = work.preparation.timestamp.plusSeconds(1))
        assertTrue(store.retainLocalEditPreparation(nextPreparation))
        val before = snapshot()
        assertTrue(store.restoreTitleUpdateAcknowledgement(witness)); assertEquals(before, snapshot())
        assertEquals(nextIntent, store.localEdit(scope, nextFrame.eventId)); assertEquals(nextPreparation, store.localEditPreparation(scope, nextFrame.eventId))
        assertTrue(store.capturedHandoff(scope, nextFrame.eventId)!!.waiting)
    }

    @Test fun changedOwnerPreparationFrameOrDeleteKindCannotBeRestored() = fixture {
        val work = stage(); val first = reserve(work); val witness = AppliedTitleUpdateAcknowledgement(first.record.receipt)
        store.writableDatabase.execSQL("UPDATE local_edit_preparations SET payload='changed-sealed'")
        assertFalse(store.restoreTitleUpdateAcknowledgement(witness))
        store.writableDatabase.execSQL("UPDATE local_edit_preparations SET payload=?", arrayOf(work.preparation.protectedPayload))
        store.writableDatabase.execSQL("UPDATE local_edits SET kind='DELETE'")
        assertFalse(store.restoreTitleUpdateAcknowledgement(witness))
        store.writableDatabase.execSQL("UPDATE local_edits SET kind='UPDATE'")
        store.writableDatabase.execSQL("UPDATE captured_create_handoffs SET waiting=0")
        assertFalse(store.restoreTitleUpdateAcknowledgement(witness))
        assertEquals(work.frame.base, store.projectedBaseline(scope, work.frame.eventId))
        assertEquals(1, rows("local_edits").size); assertEquals(1, rows("local_edit_preparations").size)
        assertEquals(first.record, store.titleUpdateAcknowledgement(scope, work.frame.eventId))
    }

    @Test fun sqlFaultDuringFrameAdvanceOrOwnerRetirementRollsBackWholeTransaction() = fixture {
        val work = stage(advanced = true); val first = reserve(work); val before = snapshot()
        store.writableDatabase.execSQL("CREATE TRIGGER fixture_frame_fail BEFORE UPDATE OF last_projection ON captured_create_handoffs BEGIN SELECT RAISE(ABORT,'fixture'); END")
        reject { store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(first.record.receipt)) }
        assertEquals(before, snapshot()); sourceIntact(work)
        store.writableDatabase.execSQL("DROP TRIGGER fixture_frame_fail")
        store.writableDatabase.execSQL("CREATE TRIGGER fixture_owner_fail BEFORE DELETE ON local_edits BEGIN SELECT RAISE(ABORT,'fixture'); END")
        reject { store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(first.record.receipt)) }
        assertEquals(before, snapshot()); sourceIntact(work)
    }

    @Test fun independentHelpersReserveOneFreshTransferredReceiptOnly() = fixture {
        val work = stage()
        ImportStore(context, name).use { other ->
            val gate = CompletableDeferred<Unit>()
            val reservations = coroutineScope {
                listOf(store, other).map { helper -> async(Dispatchers.IO) { gate.await()
                    checkNotNull(helper.prepareTitleUpdateAcknowledgement(work.result, work.frame)) } }.also { gate.complete(Unit) }.awaitAll()
            }
            assertEquals(1, reservations.count { it.fresh }); assertEquals(reservations[0].record, reservations[1].record)
        }
    }

    @Test fun corruptTransferredArchiveIsRejectedWithoutReadingOrChangingLiveOrigin() = fixture {
        val work = stage(); reserve(work); val canonical = archive(); val immutableOrigins = origins()
        val malformed = listOf(JSONObject(canonical).put("schema", 1).toString(),
            JSONObject(canonical).put("handoff", JSONObject.NULL).toString(),
            JSONObject(canonical).apply { getJSONObject("handoff").put("unexpected", true) }.toString())
        malformed.forEach { value ->
            raw { it.update("title_update_acknowledgements", ContentValues().apply { put("receipt", value) }, null, null) }
            reject { store.titleUpdateAcknowledgement(scope, work.frame.eventId) }
            assertEquals(immutableOrigins, origins()); sourceIntact(work)
        }
    }

    private fun TitleUpdateVerifiedResult.copyForPreparation(preparation: LocalEditPreparation): TitleUpdateVerifiedResult {
        val retainedRecord = this.record.copy(preparation = preparation)
        return TitleUpdateVerifiedResult.of(retainedRecord, TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(retainedRecord), event))
    }
}
