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
import org.json.JSONException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated durable handoff transactions; synthetic evidence never qualifies a real Provider write. */
@RunWith(AndroidJUnit4::class)
class CapturedHandoffStoreDeviceTest {
    private val scope = CalendarScope(AccountId("captured-handoff-fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("synthetic-import", 1)
    private val content = EventContent("Synthetic original",
        EventTime.AllDay(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)), details = EventDetails())
    private val original: CapturedCreateBaseline get() {
        val capture = LocalCreateIntent(LocalCreateReservation(MutationId("synthetic-capture"),
            LocalEventId(UUID(0, 2).toString()), scope, format, Instant.parse("2026-10-01T00:00:00Z")), content)
        val submission = LocalCreateSubmission(LocalCreateSubmissionReservation(capture,
            MutationId(UUID(0, 3).toString()), AdapterFormat("synthetic-submission", 2)), "synthetic-opaque")
        val change = RemoteChange.Upsert(EventId("captured-event"), Revision("original-revision"), content)
        return CapturedCreateBaseline(CapturedCreateAttachmentReceipt(CapturedCreateBinding(
            CapturedCreateDispatchIdentity.of(submission), change.eventId, change.revision), UUID(0, 101).toString()), change)
    }
    private val acknowledgement get() = CapturedCreateAcknowledgementReceipt(original, UUID(0, 201).toString())
    private fun handoff(nonce: Long = 301) = CapturedCreateHandoffReceipt(acknowledgement, UUID(0, nonce).toString())
    private fun incoming(number: Int = 1) = original.change.copy(revision = Revision("remote-$number"),
        content = content.copy(title = "Synthetic incoming $number"))
    private fun intent(change: RemoteChange = incoming()) = ProjectionIntent(scope, change)
    private val mutation = MutationId("synthetic-handoff-local-change")
    private fun scopeKey() = JSONArray().put(scope.account.value).put(scope.calendar.value).put(scope.generation).toString()

    private inner class Fixture(val context: Context, val name: String, val store: ImportStore) {
        suspend fun prove() {
            val reserved = store.reserveCapturedAcknowledgement(acknowledgement).record.receipt
            store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(reserved))
        }
        suspend fun release(): CapturedCreateHandoffFrame {
            prove(); val reserved = store.reserveCapturedHandoff(handoff())
            return store.retainCapturedHandoff(reserved.frame.receipt, CapturedCreateCleanWitness(acknowledgement)).frame
        }
        suspend fun queue(change: RemoteChange = incoming()) = store.transaction(scope) {
            putRemote(change); putProjection(intent(change))
        }
        fun raw(block: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath,
            null, SQLiteDatabase.OPEN_READWRITE).use(block)
        fun rows(table: String) = store.writableDatabase.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { row ->
            buildList { while (row.moveToNext()) add(List(row.columnCount) { if (row.isNull(it)) null else row.getString(it) }) }
        }
        fun snapshot() = listOf("records", "projection_bases", "captured_create_bases", "captured_create_acknowledgements",
            "captured_create_handoffs", "projection_receipts", "local_edits", "deferred_events").associateWith(::rows)
        fun unchanged(before: Map<String, List<List<String?>>>) {
            for ((table, expected) in before) assertEquals("Refusal changed $table", expected, rows(table))
        }
        fun pending() = store.writableDatabase.rawQuery("SELECT pending FROM records WHERE scope=? AND event=?",
            arrayOf(scopeKey(), original.change.eventId.value)).use { assertTrue(it.moveToFirst()); it.getInt(0) }
        suspend fun plan(frame: CapturedCreateHandoffFrame, change: RemoteChange = incoming()): CapturedHandoffProjection {
            queue(change); return store.prepareCapturedHandoffProjection(frame, intent(change))
        }
        suspend fun advanced(): Pair<CapturedCreateHandoffFrame, CapturedHandoffProjection> {
            val frame = release(); val plan = plan(frame)
            assertTrue(store.restoreCapturedHandoffProjection(AppliedCapturedHandoffProjection(plan)))
            return store.capturedHandoff(scope, original.change.eventId)!!.frame to plan
        }
        fun current(frame: CapturedCreateHandoffFrame, deleted: Boolean = false) = CapturedHandoffCurrent(frame,
            frame.base.content.copy(description = "Synthetic system edit"), deleted)
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "captured-handoff-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic fixture")))
            store.setEnabled(scope, true); store.transaction(scope) { checkpoint = PageCheckpoint(scope, format) }
            assertTrue(store.retainCapturedBaseline(original, AppliedCapturedCreateAttachment(original.receipt)))
            store.transaction(scope) { putRemote(original.change) }
            Fixture(context, name, store).block()
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected handoff refusal") }
        catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
        catch (_: SQLiteException) { }
        catch (_: JSONException) { }
        catch (_: ClassCastException) { }
    }

    @Test fun reservationRequiresProvenExactAcknowledgement() = fixture {
        val before = snapshot(); reject { store.reserveCapturedHandoff(handoff()) }; unchanged(before)
        store.reserveCapturedAcknowledgement(acknowledgement)
        val unknown = snapshot(); reject { store.reserveCapturedHandoff(handoff()) }; unchanged(unknown)
        prove(); val record = store.reserveCapturedHandoff(handoff())
        assertEquals(CapturedCreateHandoffState.PENDING, record.state); assertFalse(record.waiting)
        assertNull(record.frame.lastProjection); assertEquals(original.change, record.frame.base)
    }

    @Test fun reservationReplayRetainsFirstNonceWithoutConsumingIncomingWork() = fixture {
        prove(); queue(); val record = store.reserveCapturedHandoff(handoff())
        val before = snapshot(); assertEquals(record, store.reserveCapturedHandoff(handoff(302))); unchanged(before)
        assertEquals(1, pending()); assertEquals(incoming(), store.capturedHandoffPending(record.frame)?.change)
    }

    @Test fun promotionRetainsIncomingQueuesAndImmutableOrigin() = fixture {
        prove(); queue(); val origin = rows("captured_create_bases"); val ack = rows("captured_create_acknowledgements")
        val reserved = store.reserveCapturedHandoff(handoff())
        val record = store.retainCapturedHandoff(reserved.frame.receipt, CapturedCreateCleanWitness(acknowledgement))
        assertEquals(CapturedCreateHandoffState.RELEASED, record.state); assertTrue(store.capturedHandoffCanProject(record.frame))
        assertEquals(1, pending()); assertEquals(origin, rows("captured_create_bases")); assertEquals(ack, rows("captured_create_acknowledgements"))
    }

    @Test fun promotionRequiresExactReservedNonceAndWitness() = fixture {
        prove(); store.reserveCapturedHandoff(handoff()); val before = snapshot()
        reject { store.retainCapturedHandoff(handoff(302), CapturedCreateCleanWitness(acknowledgement)) }
        reject { store.retainCapturedHandoff(handoff(), CapturedCreateCleanWitness(acknowledgement.copy(nonce = UUID(0, 202).toString()))) }
        unchanged(before)
    }

    @Test fun pendingRoleRefusesProjectionWaitingAndLocalCapture() = fixture {
        prove(); val record = store.reserveCapturedHandoff(handoff()); queue(); val before = snapshot()
        assertFalse(store.capturedHandoffCanProject(record.frame))
        reject { store.prepareCapturedHandoffProjection(record.frame, intent()) }
        reject { store.capturedHandoffProjection(record.frame) }
        assertFalse(store.waitCapturedHandoff(record.frame)); assertFalse(store.resumeCapturedHandoff(CapturedHandoffCleanWitness(record.frame)))
        reject { store.retainCapturedHandoffLocalChange(current(record.frame), mutation) }; unchanged(before)
    }

    @Test fun anyHandoffRoleFencesOriginalBaselinesAcknowledgementsAndReconciliation() = fixture {
        prove(); val record = store.reserveCapturedHandoff(handoff()); val before = snapshot()
        assertNull(store.capturedBaseline(scope, original.change.eventId))
        reject { store.capturedAcknowledgement(scope, original.change.eventId) }
        reject { store.reserveCapturedAcknowledgement(acknowledgement) }
        reject { store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(acknowledgement)) }
        reject { store.capturedCleanSource(acknowledgement) }
        reject { store.retainCapturedLocalChange(CapturedCreateCurrent(original, content.copy(title = "Edit"), false, acknowledgement), mutation) }
        assertFalse(store.retainCapturedBaseline(original, AppliedCapturedCreateAttachment(original.receipt)))
        assertEquals(record, store.capturedHandoff(scope, original.change.eventId)); unchanged(before)
    }

    @Test fun specializedPreparationPersistsBeforeApplyAndReplaysSameReceipt() = fixture {
        val frame = release(); val plan = plan(frame); val before = snapshot()
        assertEquals(plan, store.prepareCapturedHandoffProjection(frame, intent()))
        assertEquals(plan, store.capturedHandoffProjection(frame)); unchanged(before)
        assertEquals(frame.base, plan.receipt.previousBase); assertEquals(frame.base, store.projectedBaseline(scope, frame.eventId)); assertEquals(1, pending())
    }

    @Test fun stalePayloadCannotPrepareEvenWhenRevisionMatches() = fixture {
        val frame = release(); val plan = plan(frame); queue(incoming().copy(content = content.copy(title = "Same revision different payload")))
        val before = snapshot(); reject { store.prepareCapturedHandoffProjection(frame, intent()) }; unchanged(before)
        assertEquals(plan, store.capturedHandoffProjection(frame))
    }

    @Test fun olderPreparedReceiptRemainsRecoverableAfterNewerIncomingPayload() = fixture {
        val frame = release(); val plan = plan(frame); queue(incoming(2)); val before = snapshot()
        assertEquals(plan, store.capturedHandoffProjection(frame)); unchanged(before)
        reject { store.prepareCapturedHandoffProjection(frame, intent(incoming(2))) }; unchanged(before)
        assertTrue(store.restoreCapturedHandoffProjection(AppliedCapturedHandoffProjection(plan)))
        val advanced = store.capturedHandoff(scope, frame.eventId)!!.frame
        assertEquals(incoming(), advanced.base); assertEquals(incoming(2), store.capturedHandoffPending(advanced)?.change); assertEquals(1, pending())
    }

    @Test fun restorationAdvancesOnlyCurrentReferenceAndConsumesExactPayload() = fixture {
        val frame = release(); val origin = rows("captured_create_bases"); val ack = rows("captured_create_acknowledgements")
        val plan = plan(frame); assertTrue(store.restoreCapturedHandoffProjection(AppliedCapturedHandoffProjection(plan)))
        val record = store.capturedHandoff(scope, frame.eventId)!!
        assertEquals(incoming(), record.frame.base); assertEquals(plan.receipt, record.frame.lastProjection)
        assertNull(store.capturedHandoffProjection(record.frame)); assertEquals(0, pending())
        assertEquals(origin, rows("captured_create_bases")); assertEquals(ack, rows("captured_create_acknowledgements"))
    }

    @Test fun committedReplayNeverConsumesAnIdenticalRequeuedRemoteValue() = fixture {
        val (frame, plan) = advanced(); queue(plan.receipt.change); val before = snapshot()
        assertTrue(store.restoreCapturedHandoffProjection(AppliedCapturedHandoffProjection(plan)))
        unchanged(before); assertEquals(1, pending()); assertEquals(frame, store.capturedHandoff(scope, frame.eventId)!!.frame)
    }

    @Test fun nextProjectionUsesAdvancedReferenceAndRetainsCreationHistory() = fixture {
        val (frame, _) = advanced(); val plan = plan(frame, incoming(2))
        assertEquals(incoming(), plan.receipt.previousBase)
        assertTrue(store.restoreCapturedHandoffProjection(AppliedCapturedHandoffProjection(plan)))
        val current = store.capturedHandoff(scope, frame.eventId)!!.frame
        assertEquals(incoming(2), current.base); assertEquals(original, current.receipt.acknowledgement.baseline)
    }

    @Test fun wrongPreparedNonceCannotAdvanceOrConsume() = fixture {
        val frame = release(); val plan = plan(frame); val before = snapshot()
        val wrong = plan.copy(receipt = plan.receipt.copy(nonce = UUID(0, 999).toString()))
        assertFalse(store.restoreCapturedHandoffProjection(AppliedCapturedHandoffProjection(wrong))); unchanged(before)
    }

    @Test fun rawLocalIntentionBlocksReservationBeforeItsPayloadCanBeDecoded() = fixture {
        prove(); store.retainCapturedLocalChange(CapturedCreateCurrent(original, content.copy(title = "Local"), false, acknowledgement), mutation)
        raw { it.execSQL("UPDATE local_edits SET local='unreadable'") }
        val before = snapshot(); reject { store.reserveCapturedHandoff(handoff()) }; unchanged(before)
    }

    @Test fun rawOutstandingOrdinaryProofBlocksReservation() = fixture {
        prove(); raw { it.execSQL("INSERT INTO projection_receipts VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(scopeKey(), original.change.eventId.value, UUID(0, 701).toString(), "unreadable", format.id, 1, null, 1)) }
        val before = snapshot(); reject { store.reserveCapturedHandoff(handoff()) }; unchanged(before)
    }

    @Test fun rawReceiptBlocksLocalCaptureAndCleanResume() = fixture {
        val frame = release(); plan(frame); store.waitCapturedHandoff(frame); val before = snapshot()
        reject { store.retainCapturedHandoffLocalChange(current(frame), mutation) }
        reject { store.resumeCapturedHandoff(CapturedHandoffCleanWitness(frame)) }; unchanged(before)
    }

    @Test fun localEditAfterAdvancementUsesNewBaseAndWaitsWithoutConsumingIncoming() = fixture {
        val (frame, _) = advanced(); queue(frame.base); val originalRows = rows("captured_create_bases")
        assertEquals(LocalEditState.PAUSED, store.retainCapturedHandoffLocalChange(current(frame), mutation))
        assertEquals(frame.base, store.localEdit(scope, frame.eventId)!!.base)
        assertTrue(store.capturedHandoff(scope, frame.eventId)!!.waiting); assertFalse(store.capturedHandoffCanProject(frame))
        assertEquals(1, pending()); assertEquals(originalRows, rows("captured_create_bases")); assertEquals(frame.base, store.projectedBaseline(scope, frame.eventId))
    }

    @Test fun postHandoffTombstoneIsRetainedAgainstCurrentBase() = fixture {
        val (frame, _) = advanced()
        assertEquals(LocalEditState.PAUSED, store.retainCapturedHandoffLocalChange(current(frame, deleted = true), mutation))
        assertEquals(frame.base, store.localDelete(scope, frame.eventId)!!.base); assertNull(store.localEdit(scope, frame.eventId))
        assertTrue(store.capturedHandoff(scope, frame.eventId)!!.waiting)
    }

    @Test fun localReplayCannotBypassFullFrameOrImmutableContent() = fixture {
        val (frame, _) = advanced(); val current = current(frame)
        store.retainCapturedHandoffLocalChange(current, mutation); val before = snapshot()
        reject { store.retainCapturedHandoffLocalChange(current.copy(frame = CapturedCreateHandoffFrame(frame.receipt, original.change)), mutation) }
        reject { store.retainCapturedHandoffLocalChange(current.copy(content = current.content.copy(title = "Later local")), mutation) }
        reject { store.retainCapturedHandoffLocalChange(current, MutationId("different-mutation")) }
        reject { store.retainCapturedHandoffLocalChange(current.copy(deleted = true), mutation) }; unchanged(before)
    }

    @Test fun localConflictIsMonotoneWhenRemoteChangesThenReturnsToCurrentBase() = fixture {
        val frame = release(); val current = current(frame)
        assertEquals(LocalEditState.PAUSED, store.retainCapturedHandoffLocalChange(current, mutation))
        queue(incoming()); assertEquals(LocalEditState.CONFLICT, store.retainCapturedHandoffLocalChange(current, mutation))
        queue(frame.base); assertEquals(LocalEditState.CONFLICT, store.retainCapturedHandoffLocalChange(current, mutation))
        assertEquals(0, pending()); assertTrue(store.capturedHandoff(scope, frame.eventId)!!.waiting)
    }

    @Test fun missingOrDeferredRemoteEvidenceProducesConflictWithoutQueueConsumption() = fixture {
        val frame = release(); raw { it.execSQL("DELETE FROM records") }
        assertEquals(LocalEditState.CONFLICT, store.retainCapturedHandoffLocalChange(current(frame), mutation))
        assertTrue(rows("records").isEmpty()); assertEquals(frame.base, store.projectedBaseline(scope, frame.eventId))
    }

    @Test fun unchangedCurrentSnapshotCreatesNoIntentionAndNeverClearsWaiting() = fixture {
        val frame = release(); store.waitCapturedHandoff(frame); val before = snapshot()
        assertNull(store.retainCapturedHandoffLocalChange(CapturedHandoffCurrent(frame, frame.base.content, false), mutation))
        unchanged(before); assertTrue(store.capturedHandoff(scope, frame.eventId)!!.waiting)
    }

    @Test fun waitingIsDurableAndOnlyExactCurrentCleanWitnessCanResume() = fixture {
        val (frame, _) = advanced(); assertTrue(store.waitCapturedHandoff(frame)); val before = snapshot()
        reject { store.resumeCapturedHandoff(CapturedHandoffCleanWitness(CapturedCreateHandoffFrame(frame.receipt, original.change))) }; unchanged(before)
        assertTrue(store.resumeCapturedHandoff(CapturedHandoffCleanWitness(frame)))
        assertFalse(store.capturedHandoff(scope, frame.eventId)!!.waiting); assertTrue(store.capturedHandoffCanProject(frame))
    }

    @Test fun restorationRetainsAWaitingFlagSetAfterPreparation() = fixture {
        val frame = release(); val plan = plan(frame); store.waitCapturedHandoff(frame)
        assertTrue(store.restoreCapturedHandoffProjection(AppliedCapturedHandoffProjection(plan)))
        val current = store.capturedHandoff(scope, frame.eventId)!!
        assertTrue(current.waiting); assertEquals(incoming(), current.frame.base); assertFalse(store.capturedHandoffCanProject(current.frame))
    }

    @Test fun deleteRichAndUnknownIncomingStayHeldWithoutChangingTheCurrentBase() = fixture {
        val frame = release()
        for (change in listOf(RemoteChange.Deleted(frame.eventId, Revision("deleted")), incoming().copy(content = content.copy(details = null)),
            incoming().copy(content = content.copy(colorRgb = 0xff0000)))) {
            queue(change); val before = snapshot(); assertFalse(store.capturedHandoffCanProject(frame))
            reject { store.prepareCapturedHandoffProjection(frame, intent(change)) }; unchanged(before); assertEquals(1, pending())
        }
    }

    @Test fun deferredEvidenceHoldsEvenAnOtherwiseSupportedIncomingValue() = fixture {
        val frame = release(); queue()
        val deferred = DeferredCalendarEvent(frame.eventId, Revision("deferred"), format,
            DeferredEventReason.UNVERIFIED_CONTENT, "synthetic-sealed-evidence")
        store.transaction(scope) { (this as StagedSyncTransaction).defer(deferred) }
        val before = snapshot(); assertFalse(store.capturedHandoffCanProject(frame))
        reject { store.prepareCapturedHandoffProjection(frame, intent()) }; unchanged(before)
        assertEquals(deferred, store.deferred(scope).single())
    }

    @Test fun preparedHistoricalReceiptRecoversDespiteNewerUnsupportedIncoming() = fixture {
        val frame = release(); val plan = plan(frame); val deletion = RemoteChange.Deleted(frame.eventId, Revision("newer-delete"))
        queue(deletion); assertFalse(store.capturedHandoffCanProject(frame)); assertEquals(plan, store.capturedHandoffProjection(frame))
        assertTrue(store.restoreCapturedHandoffProjection(AppliedCapturedHandoffProjection(plan)))
        val current = store.capturedHandoff(scope, frame.eventId)!!.frame
        assertEquals(deletion, store.capturedHandoffPending(current)?.change); assertFalse(store.capturedHandoffCanProject(current)); assertEquals(1, pending())
    }

    @Test fun genericPrepareAcknowledgeRestoreAndCaptureRefuseBeforeReplay() = fixture {
        val frame = release(); val plan = plan(frame); val before = snapshot()
        reject { store.prepareProjection(intent(), format) }; reject { store.acknowledge(intent()) }
        reject { store.restoreProjection(AppliedProjectionWitness(plan.receipt)) }
        reject { store.captureLocalEdit(LocalEditIntent(mutation, scope, format, frame.base, content.copy(title = "Local"))) }
        reject { store.captureLocalDelete(LocalDeleteIntent(mutation, scope, format, frame.base, content)) }
        unchanged(before)
    }

    @Test fun corruptHandoffFieldsRemainRawFencesRatherThanAbsentWork() = fixture {
        val frame = release()
        val validRecord = store.capturedHandoff(scope, frame.eventId)
        val validSnapshot = snapshot()
        val validRole = rows("captured_create_handoffs").single()
        for (corruption in listOf("nonce='invalid'", "ack_fingerprint='invalid'", "fingerprint='invalid'", "state='invalid'", "waiting=2", "protocol=2")) {
            assertEquals(validRecord, store.capturedHandoff(scope, frame.eventId))
            raw { it.execSQL("PRAGMA ignore_check_constraints=ON"); it.execSQL("UPDATE captured_create_handoffs SET $corruption") }
            val before = snapshot(); reject { store.capturedHandoff(scope, original.change.eventId) }
            reject { store.acknowledge(intent(original.change)) }; reject { store.prepareProjection(intent(original.change), format) }
            assertEquals(setOf(original.change.eventId), store.capturedProvenanceEvents(scope)); unchanged(before)
            raw { it.execSQL("UPDATE captured_create_handoffs SET nonce=?,protocol=?,ack_nonce=?,ack_fingerprint=?,fingerprint=?,state=?,waiting=?,last_projection=?",
                validRole.drop(2).toTypedArray()) }
            assertEquals(validRecord, store.capturedHandoff(scope, frame.eventId))
            unchanged(validSnapshot)
        }
    }

    @Test fun orphanHandoffRoleAloneKeepsTheRawGenericFence() = fixture {
        release(); raw { it.execSQL("DELETE FROM captured_create_bases"); it.execSQL("DELETE FROM captured_create_acknowledgements") }
        val before = snapshot(); reject { store.capturedHandoff(scope, original.change.eventId) }
        reject { store.acknowledge(intent(original.change)) }; assertEquals(setOf(original.change.eventId), store.capturedProvenanceEvents(scope))
        unchanged(before)
    }

    @Test fun changedOriginOrAcknowledgementCannotBeAdoptedByAnExistingRole() = fixture {
        release(); raw { it.execSQL("UPDATE captured_create_bases SET capture_adapter='foreign-origin'") }
        val before = snapshot(); reject { store.capturedHandoff(scope, original.change.eventId) }; unchanged(before)
    }

    @Test fun missingOrUnprovenAcknowledgementMakesAnExistingRoleUnresolved() = fixture {
        release(); raw { it.execSQL("UPDATE captured_create_acknowledgements SET state='UNKNOWN'") }
        val before = snapshot(); reject { store.capturedHandoff(scope, original.change.eventId) }; unchanged(before)
    }

    @Test fun currentBaseCannotAdvanceWithoutMatchingHistoricalProjection() = fixture {
        release(); queue(); raw { it.execSQL("UPDATE projection_bases SET payload=(SELECT payload FROM records LIMIT 1)") }
        val before = snapshot(); reject { store.capturedHandoff(scope, original.change.eventId) }; unchanged(before)
    }

    @Test fun malformedLastProjectionCannotReopenTheOriginalReference() = fixture {
        val (frame, _) = advanced(); raw { it.execSQL("UPDATE captured_create_handoffs SET last_projection='unreadable'") }
        val before = snapshot(); reject { store.capturedHandoff(scope, frame.eventId) }
        assertNull(store.capturedBaseline(scope, frame.eventId)); unchanged(before)
    }

    @Test fun malformedPreparedReceiptRefusesRecoveryAndNewPreparation() = fixture {
        val frame = release(); plan(frame); raw { it.execSQL("UPDATE projection_receipts SET previous_base=NULL") }
        val before = snapshot(); reject { store.capturedHandoffProjection(frame) }; reject { store.capturedHandoffCanProject(frame) }
        reject { store.prepareCapturedHandoffProjection(frame, intent()) }; unchanged(before)
    }

    @Test fun preparedProofWithoutPendingWorkStaysHeldForExplicitHistoricalRecovery() = fixture {
        val frame = release(); val plan = plan(frame)
        store.transaction(scope) { removeProjection(frame.eventId) }
        val before = snapshot(); assertFalse(store.capturedHandoffCanProject(frame)); unchanged(before)
        assertEquals(plan, store.capturedHandoffProjection(frame))
        assertTrue(store.restoreCapturedHandoffProjection(AppliedCapturedHandoffProjection(plan)))
        val current = store.capturedHandoff(scope, frame.eventId)!!.frame
        assertTrue(store.capturedHandoffCanProject(current)); assertNull(store.capturedHandoffPending(current))
    }

    @Test fun numericCoercionCannotQualifyMalformedHistoricalOrigin() = fixture {
        release(); raw { it.execSQL("UPDATE captured_create_bases SET timestamp=timestamp+0.5") }
        val before = snapshot(); reject { store.capturedHandoff(scope, original.change.eventId) }; unchanged(before)
    }

    @Test fun leaseLossBeforeCommitRollsBackPreparationAndWaiting() = fixture {
        val frame = release(); queue(); val before = snapshot()
        var checks = 0; store.admitTransaction = { ++checks == 1 }
        reject { store.prepareCapturedHandoffProjection(frame, intent()) }
        store.admitTransaction = { true }; unchanged(before)
        checks = 0; store.admitTransaction = { ++checks == 1 }
        reject { store.waitCapturedHandoff(frame) }; store.admitTransaction = { true }; unchanged(before)
    }

    @Test fun leaseLossBeforeCommitRollsBackRestoreAndPreservesPreparedProof() = fixture {
        val frame = release(); val plan = plan(frame); val before = snapshot()
        var checks = 0; store.admitTransaction = { ++checks == 1 }
        reject { store.restoreCapturedHandoffProjection(AppliedCapturedHandoffProjection(plan)) }
        store.admitTransaction = { true }; unchanged(before)
        assertTrue(store.restoreCapturedHandoffProjection(AppliedCapturedHandoffProjection(plan)))
    }

    @Test fun formatSelectionAndAvailabilityGatesAreCheckedAgainstHistoricalOrigin() = fixture {
        val frame = release(); queue(); store.transaction(scope) { checkpoint = PageCheckpoint(scope, format.copy(version = 2)) }
        reject { store.capturedHandoff(scope, frame.eventId) }; reject { store.prepareCapturedHandoffProjection(frame, intent()) }
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format) }; store.setEnabled(scope, false)
        reject { store.capturedHandoff(scope, frame.eventId) }
        store.setEnabled(scope, true); store.transaction(scope) { checkpoint = PageCheckpoint(scope, format) }
        assertEquals(frame, store.capturedHandoff(scope, frame.eventId)!!.frame)
    }

    @Test fun reopeningRetainsRoleCurrentReferenceWaitingAndHistoricalReceipt() = fixture {
        val (frame, _) = advanced(); val plan = plan(frame, incoming(2)); store.waitCapturedHandoff(frame); val before = snapshot()
        store.close(); ImportStore(context, name).use { reopened ->
            assertEquals(14, reopened.writableDatabase.version)
            assertEquals(frame, reopened.capturedHandoff(scope, frame.eventId)!!.frame)
            assertTrue(reopened.capturedHandoff(scope, frame.eventId)!!.waiting)
            assertEquals(plan, reopened.capturedHandoffProjection(frame))
            assertTrue(reopened.restoreCapturedHandoffProjection(AppliedCapturedHandoffProjection(plan)))
            assertTrue(reopened.capturedHandoff(scope, frame.eventId)!!.waiting)
        }
        assertEquals(before["captured_create_bases"], rows("captured_create_bases"))
    }

    @Test fun migrationFromTwelvePreservesEvidenceWithoutInventingAHandoff() = fixture {
        prove(); queue(); val origin = rows("captured_create_bases"); val ack = rows("captured_create_acknowledgements")
        store.close(); raw {
            it.execSQL("DROP TABLE local_edit_preparations")
            it.execSQL("DROP TABLE captured_create_handoffs"); it.version = 12
        }
        ImportStore(context, name).use { migrated ->
            assertEquals(14, migrated.writableDatabase.version); assertNull(migrated.capturedHandoff(scope, original.change.eventId))
            assertEquals(original, migrated.capturedBaseline(scope, original.change.eventId))
            assertEquals(acknowledgement, migrated.capturedAcknowledgement(scope, original.change.eventId)!!.receipt)
            assertTrue(migrated.writableDatabase.rawQuery("SELECT * FROM captured_create_handoffs", null).use { !it.moveToFirst() })
        }
        assertEquals(origin, rows("captured_create_bases")); assertEquals(ack, rows("captured_create_acknowledgements"))
    }
}
