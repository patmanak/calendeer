// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.provider.CalendarContract.Events
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import org.junit.Assert.*
import org.junit.Test

/** Real Provider and SQLite, controlled authenticated-observation port. */
class OccurrenceBindingDeviceTest {
    internal class Port : OccurrenceCaptureDeviceTest.SubmissionPort() {
        val attempt = MutationId(java.util.UUID.randomUUID().toString())
        var remoteId = EventId("synthetic-remote-child")
        var inspections = 0
        var afterRead: suspend () -> Unit = {}

        override suspend fun inspectCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft): CreateSubmissionObservation {
            inspections++
            val content = readCapturedExpectation(record, expected) ?: return CreateSubmissionObservation.Unknown
            afterRead()
            return CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(record),
                RemoteChange.Upsert(remoteId, Revision("verified-child-revision"), content))
        }
    }

    private suspend fun OccurrenceCaptureDeviceTest.Fixture.unknown(port: Port) {
        assertEquals(LocalEditCaptureResult.RETAINED, capture())
        val owner = coordinator()
        assertEquals(LocalCreateSubmissionResult.RETAINED, owner.stageLocalCreateSubmission(scope, localId, id, port.attempt, port))
        owner.submitLocalCreate(scope, localId, id, port.attempt, port)
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, attempts.read(scope, port.attempt)?.state)
    }

    @Test fun bindingSurvivesReopenAndNeverReleasesTheParentOrWritesTheDirtyChild() = OccurrenceCaptureDeviceTest().fixture {
        val port = Port()
        unknown(port)
        val before = native.rows()
        val identity = checkNotNull(attempts.capturedIdentity(scope, port.attempt))
        val unknown = checkNotNull(attempts.read(scope, port.attempt))
        val detached = checkNotNull(retained()?.occurrence).content
        val observation = CreateSubmissionObservation.Verified(identity.attempt,
            RemoteChange.Upsert(port.remoteId, Revision("verified-child-revision"), detached))
        attempts.admitAssociation = { it == scope }
        try {
            attempts.bindCaptured(unknown, identity, observation)
            fail("Occurrence binding requires the original captured context")
        } catch (_: IllegalArgumentException) {
            assertNull(attempts.capturedBinding(identity))
        } finally {
            attempts.admitAssociation = { false }
        }
        assertEquals(CapturedCreateBindingResult.RETAINED, coordinator().bindLocalCreateObservation(scope, localId, id, port.attempt, port))
        assertEquals(port.remoteId, attempts.capturedBinding(identity)?.eventId)
        assertTrue(attempts.capturedProjectionHold(scope, reservation.format).holds(native.base.eventId))
        journal.close()
        journal = LocalCreateStore(context, name)
        assertEquals(CapturedCreateBindingResult.RETAINED, coordinator().bindLocalCreateObservation(scope, localId, id, port.attempt))
        assertEquals(1, port.inspections)
        assertEquals(1, port.sends)
        assertEquals(before, native.rows())
        assertEquals("1", before.getValue(native.child)[Events.DIRTY])
        native.change(native.child, Events.TITLE, "Newer edit after binding")
        assertEquals(CapturedCreateBindingResult.UNRESOLVED, coordinator().bindLocalCreateObservation(scope, localId, id, port.attempt))
        assertEquals(CapturedCreateAttachmentResult.UNRESOLVED, coordinator().attachLocalCreateBinding(scope, localId, id, port.attempt))
        assertNull(native.rows().getValue(native.child)[Events.SYNC_DATA6])
        CreateAttemptStore(context, checkNotNull(attempts.databaseName)).use { reopened ->
            assertEquals(port.remoteId, reopened.capturedBinding(identity)?.eventId)
            assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, reopened.read(scope, port.attempt)?.state)
            assertTrue(reopened.capturedProjectionHold(scope, reservation.format).scopeBlocked)
        }
    }

    @Test fun attachmentOnlyChangesOwnershipAndAckClearsOnlyTheExactChild() = OccurrenceCaptureDeviceTest().fixture {
        val port = Port()
        unknown(port)
        val owner = coordinator(port)
        val before = native.rows()
        assertEquals(CapturedCreateBindingResult.RETAINED, owner.bindLocalCreateObservation(scope, localId, id, port.attempt, port))
        repeat(2) { assertEquals(CapturedCreateAttachmentResult.ATTACHED, owner.attachLocalCreateBinding(scope, localId, id, port.attempt)) }
        val attached = native.rows().getValue(native.child)
        assertEquals("1", attached[Events.DIRTY])
        assertNotNull(attached[Events.SYNC_DATA6])
        repeat(2) { assertEquals(CapturedCreateBaselineResult.RETAINED, owner.retainLocalCreateBaseline(scope, localId, id, port.attempt)) }
        val reconcile = MutationId("fixture-reconcile")
        assertEquals(CapturedCreateReconciliationResult.UNCHANGED, owner.reconcileLocalCreate(scope, localId, id, port.attempt, reconcile))
        repeat(2) { assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, owner.acknowledgeLocalCreate(scope, localId, id, port.attempt)) }
        val after = native.rows()
        assertEquals(before.getValue(native.parent), after.getValue(native.parent))
        assertEquals("0", after.getValue(native.child)[Events.DIRTY])
        assertNotNull(after.getValue(native.child)[Events.SYNC_DATA7])
        assertEquals(attached[Events.SYNC_DATA6], after.getValue(native.child)[Events.SYNC_DATA6])
        assertEquals(1, port.sends)
        assertEquals(CapturedCreateCleanResult.CLEAN, owner.qualifyAcknowledgedLocalCreate(scope, localId, id, port.attempt))
        native.mode = EventColorMode.CALENDAR
        native.projection.admitOperation = { true }
        native.projection.reprojectEventColors(scope)
        assertEquals(CapturedCreateCleanResult.CLEAN, owner.qualifyAcknowledgedLocalCreate(scope, localId, id, port.attempt))
        assertEquals(CapturedCreateReconciliationResult.UNCHANGED, owner.reconcileLocalCreate(scope, localId, id, port.attempt, reconcile))
        native.change(native.child, Events.TITLE, "Another edit after clean")
        native.change(native.child, Events.DIRTY, 1)
        assertEquals(CapturedCreateCleanResult.UNRESOLVED, owner.qualifyAcknowledgedLocalCreate(scope, localId, id, port.attempt))
        assertEquals(CapturedCreateReconciliationResult.UNRESOLVED, owner.reconcileLocalCreate(scope, localId, id, port.attempt, reconcile))
    }

    @Test fun transferredChildProjectionAdvancesOnlyItsOriginalSlotAndKeepsANewerLocalEdit() = OccurrenceCaptureDeviceTest().fixture {
        val port = Port()
        unknown(port)
        val owner = coordinator(port)
        assertEquals(CapturedCreateBindingResult.RETAINED, owner.bindLocalCreateObservation(scope, localId, id, port.attempt, port))
        assertEquals(CapturedCreateAttachmentResult.ATTACHED, owner.attachLocalCreateBinding(scope, localId, id, port.attempt))
        assertEquals(CapturedCreateBaselineResult.RETAINED, owner.retainLocalCreateBaseline(scope, localId, id, port.attempt))
        assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, owner.acknowledgeLocalCreate(scope, localId, id, port.attempt))
        repeat(2) { assertEquals(CapturedCreateHandoffResult.HANDED_OFF, owner.handoffLocalCreate(scope, localId, id, port.attempt)) }
        store.admitTransaction = { it == scope }
        native.projection.admitOperation = { true }
        editable = false
        val receiving = ImportCoordinatorProjection(store, reader, projection, attempts, { 0L }, { 0L },
            { true }, { true }, { _, account -> account == scope.account }, owner::releasedProjectionSource, owner::releasedMasterSource)
        val master = native.base.copy(revision = Revision("master-metadata"), content = native.base.content.copy(
            title = "New master title", description = "Master-only description"))
        suspend fun queue(change: RemoteChange): ProjectionIntent = ProjectionIntent(scope, change).also { intent ->
            store.transaction(scope) { putRemote(change); putProjection(intent) }
        }
        val masterPending = queue(master)
        val initial = checkNotNull(store.capturedHandoff(scope, port.remoteId)).frame
        val child = native.rows().getValue(native.child)
        assertEquals(listOf(masterPending), receiving.runnableProjections(scope))
        val prepared = store.prepareProjection(masterPending, initial.format)
        assertNotNull(native.projection.projectOccurrenceMaster(initial, prepared, inspect = false))
        // Simulate loss of the response after the Provider committed its parent-only batch.
        assertEquals(native.base, store.projectedBaseline(scope, master.eventId))
        receiving.projectPending(masterPending, 0L)
        assertEquals(child, native.rows().getValue(native.child))
        assertEquals(master, store.projectedBaseline(scope, master.eventId))
        val frame = checkNotNull(store.capturedHandoff(scope, port.remoteId)).frame
        assertEquals(master, frame.occurrenceMaster)
        val next = frame.base.copy(revision = Revision("verified-next"), content = frame.base.content.copy(title = "Remote update"))
        val pending = queue(next)
        assertEquals(listOf(pending), receiving.runnableProjections(scope))
        val plan = store.prepareCapturedHandoffProjection(frame, pending)
        val parent = native.rows().getValue(native.parent)
        assertNotNull(native.projection.applyCapturedHandoffProjection(plan))
        native.change(native.child, Events.TITLE, "Subsequent edit")
        native.change(native.child, Events.DIRTY, 1)
        val before = native.rows()
        val historical = checkNotNull(native.projection.inspectCapturedHandoffProjection(plan))
        assertTrue(store.restoreCapturedHandoffProjection(historical))
        val advanced = checkNotNull(store.capturedHandoff(scope, port.remoteId)).frame
        assertEquals(next, advanced.base)
        assertNull(native.projection.inspectCapturedHandoffBase(advanced))
        assertEquals(before, native.rows())
        assertEquals(parent, before.getValue(native.parent))
        assertEquals(1, port.sends)
        val dirty = native.rows().getValue(native.child)
        val another = master.copy(revision = Revision("master-dirty-child"), content = master.content.copy(location = "New location"))
        receiving.projectPending(queue(another), 0L)
        assertEquals(dirty, native.rows().getValue(native.child))
        assertEquals(another, store.projectedBaseline(scope, master.eventId))
        val stable = native.rows()
        val structural = another.copy(revision = Revision("master-structural"), content = another.content.copy(
            recurrence = another.content.recurrence!!.copy(rule = "FREQ=DAILY;COUNT=2")))
        for (change in listOf(structural, RemoteChange.Deleted(another.eventId, Revision("master-gone")))) {
            queue(change)
            assertTrue(receiving.runnableProjections(scope).isEmpty())
            receiving.publishImportProgress(scope, "IMPORTED")
            assertEquals("CREATE_PENDING", store.selections(scope.account).single { it.scope == scope }.status)
            assertEquals(stable, native.rows())
        }
    }

    @Test fun aNewerChildBetweenReservationAndAckNeverRearmsTheNativeWrite() = OccurrenceCaptureDeviceTest().fixture {
        val port = Port()
        unknown(port)
        val original = checkNotNull(retained()).content.title
        var writes = 0
        projection = object : CalendarProjection by native.projection {
            override suspend fun applyOccurrenceReceipt(step: OccurrenceNativeReceipt): Boolean {
                if (step.action == OccurrenceReceiptAction.ACKNOWLEDGE) {
                    writes++
                    native.change(native.child, Events.TITLE, "Newer local occurrence")
                }
                return native.projection.applyOccurrenceReceipt(step)
            }
        }
        val owner = coordinator(port)
        assertEquals(CapturedCreateBindingResult.RETAINED, owner.bindLocalCreateObservation(scope, localId, id, port.attempt, port))
        assertEquals(CapturedCreateAttachmentResult.ATTACHED, owner.attachLocalCreateBinding(scope, localId, id, port.attempt))
        assertEquals(CapturedCreateBaselineResult.RETAINED, owner.retainLocalCreateBaseline(scope, localId, id, port.attempt))
        assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, owner.acknowledgeLocalCreate(scope, localId, id, port.attempt))
        val after = native.rows().getValue(native.child)
        assertEquals("Newer local occurrence", after[Events.TITLE])
        assertEquals("1", after[Events.DIRTY])
        assertNull(after[Events.SYNC_DATA7])
        native.change(native.child, Events.TITLE, original)
        assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, coordinator(port).acknowledgeLocalCreate(scope, localId, id, port.attempt))
        assertEquals(1, writes)
        store.admitTransaction = { it == scope }
        assertEquals(CapturedCreateAcknowledgementState.UNKNOWN, store.capturedAcknowledgement(scope, port.remoteId)?.state)
        assertEquals("1", native.rows().getValue(native.child)[Events.DIRTY])
    }

    @Test fun aMasterIdOrChangedFamilyConsentOrCaptureCannotBindUnknownWork() {
        for (race in listOf("master-id", "new-child-edit", "sibling", "master-cache", "capture-context", "consent")) {
            OccurrenceCaptureDeviceTest().fixture {
                val port = Port()
                unknown(port)
                port.afterRead = {
                    when (race) {
                        "master-id" -> port.remoteId = native.base.eventId
                        "new-child-edit" -> native.change(native.child, Events.TITLE, "Subsequent edit")
                        "sibling" -> { native.insertChild(); Unit }
                        "master-cache" -> store.transaction(scope) { putRemote(native.base.copy(revision = Revision("newer-master"))) }
                        "capture-context" -> journal.writableDatabase.execSQL("UPDATE local_creates SET occurrence_capture=NULL,occurrence_slot=NULL")
                        "consent" -> editable = false
                        else -> error("Unexpected case")
                    }
                }
                val result = coordinator().bindLocalCreateObservation(scope, localId, id, port.attempt, port)
                assertTrue(race, result != CapturedCreateBindingResult.RETAINED)
                assertNull(race, attempts.capturedBinding(checkNotNull(attempts.capturedIdentity(scope, port.attempt))))
                assertTrue(attempts.capturedProjectionHold(scope, reservation.format).scopeBlocked)
                assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, attempts.read(scope, port.attempt)?.state)
                assertEquals("1", native.rows().getValue(native.child)[Events.DIRTY])
                assertEquals(1, port.sends)
            }
        }
    }

    @Test fun interruptedAckRestartsTheWholeSequenceByInspectionEvenAfterColorMasking() = OccurrenceCaptureDeviceTest().fixture {
        val port = Port()
        unknown(port)
        var writes = 0
        projection = object : CalendarProjection by native.projection {
            override suspend fun applyOccurrenceReceipt(step: OccurrenceNativeReceipt): Boolean {
                val applied = native.projection.applyOccurrenceReceipt(step)
                if (step.action == OccurrenceReceiptAction.ACKNOWLEDGE) {
                    assertTrue(applied)
                    writes++
                    throw java.io.IOException("Synthetic lost ACK response")
                }
                return applied
            }
        }
        val owner = coordinator(port)
        assertEquals(CapturedCreateBindingResult.RETAINED, owner.bindLocalCreateObservation(scope, localId, id, port.attempt, port))
        assertEquals(CapturedCreateAttachmentResult.ATTACHED, owner.attachLocalCreateBinding(scope, localId, id, port.attempt))
        assertEquals(CapturedCreateBindingResult.RETAINED, coordinator(port).bindLocalCreateObservation(scope, localId, id, port.attempt))
        assertEquals(CapturedCreateBaselineResult.RETAINED, owner.retainLocalCreateBaseline(scope, localId, id, port.attempt))
        assertEquals(CapturedCreateAcknowledgementResult.UNRESOLVED, owner.acknowledgeLocalCreate(scope, localId, id, port.attempt))
        store.admitTransaction = { it == scope }
        assertEquals(CapturedCreateAcknowledgementState.UNKNOWN, store.capturedAcknowledgement(scope, port.remoteId)?.state)
        native.mode = EventColorMode.CALENDAR
        native.projection.admitOperation = { true }
        native.projection.reprojectEventColors(scope)
        assertNull(native.rows().getValue(native.child)[Events.EVENT_COLOR])
        assertNotNull(native.rows().getValue(native.child)[Events.SYNC_DATA3])
        native.change(native.child, Events.TITLE, "Newer edit after the lost response")
        native.change(native.child, Events.DIRTY, 1)
        val retry = coordinator(port)
        val before = native.rows()
        assertEquals(CapturedCreateBindingResult.RETAINED, retry.bindLocalCreateObservation(scope, localId, id, port.attempt))
        assertEquals(CapturedCreateAttachmentResult.ATTACHED, retry.attachLocalCreateBinding(scope, localId, id, port.attempt))
        assertEquals(CapturedCreateBaselineResult.RETAINED, retry.retainLocalCreateBaseline(scope, localId, id, port.attempt))
        assertEquals(CapturedCreateAcknowledgementResult.ACKNOWLEDGED, retry.acknowledgeLocalCreate(scope, localId, id, port.attempt))
        assertEquals(before, native.rows())
        assertEquals("1", before.getValue(native.child)[Events.DIRTY])
        assertEquals(1, writes)
        assertEquals(1, port.inspections)
        assertEquals(1, port.sends)
    }
}
