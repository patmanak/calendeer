// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in isolated SQL fixtures. Synthetic witnesses prove storage, never service/native behavior. */
@RunWith(AndroidJUnit4::class)
class DeleteAcknowledgementStoreDeviceTest {
    private val scope = CalendarScope(AccountId("delete-ack-fixture"),CalendarId("calendar"),0)
    private val format = AdapterFormat("delete-import-fixture",1)
    private val base = RemoteChange.Upsert(EventId("event"),Revision("opaque-base"),EventContent("Synthetic deletion",
        EventTime.AllDay(LocalDate.of(2030,1,1),LocalDate.of(2030,1,2)),details = EventDetails()))
    private val intent = LocalDeleteIntent(MutationId("owner"),scope,format,base,base.content)
    private val work = DeleteAttemptRecord(MutationId("attempt"),intent,AdapterFormat("opaque-delete-request",3),"opaque-request")
    private val gone = RemoteChange.Deleted(base.eventId,Revision("opaque-absence-observation"))
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun fixture(redundant: Boolean = false, block: suspend (ImportStore,String) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val name = "delete-ack-${UUID.randomUUID()}.db"; val store = ImportStore(context,name)
        try {
            store.remember(scope.account,listOf(CalendarSummary(scope.calendar,"Synthetic deletion")))
            store.setEnabled(scope,true)
            store.transaction(scope) { checkpoint = PageCheckpoint(scope,format); putRemote(base); putProjection(ProjectionIntent(scope,base)) }
            assertEquals(1,store.acknowledge(ProjectionIntent(scope,base)))
            if (redundant) {
                store.transaction(scope) { putRemote(base); putProjection(ProjectionIntent(scope,base)) }
                store.prepareProjection(ProjectionIntent(scope,base),format)
            }
            assertEquals(intent,store.captureLocalDelete(intent))
            block(store,name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun reserve(store: ImportStore) = checkNotNull(store.prepareDeleteAcknowledgement(work))
    private suspend fun queue(store: ImportStore, change: RemoteChange) = store.transaction(scope) {
        putRemote(change); putProjection(ProjectionIntent(scope,change))
    }
    private suspend fun pendingOwner(store: ImportStore, count: Int) {
        assertEquals(count,store.selections(scope.account).single().retainedLocalChangeCount)
        assertEquals(count,store.retainedLocalChanges(RetainedLocalChangeQuery(scope)).entries.size)
    }
    private suspend fun refusal(block: suspend () -> Unit) {
        try { block(); fail("Expected closed acknowledgement refusal") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    @Test fun firstReservationIsImmutableAndNotCompletion() = fixture { store,_ ->
        val first = reserve(store)
        assertTrue(first.fresh); assertEquals(DeleteAcknowledgementState.PENDING,first.record.state)
        assertEquals(first.copy(fresh=false),store.prepareDeleteAcknowledgement(work))
        assertNull(store.prepareDeleteAcknowledgement(work.copy(id=MutationId("foreign-attempt"))))
        assertNull(store.prepareDeleteAcknowledgement(work.copy(protectedPayload="different-request")))
        pendingOwner(store,1)
        assertEquals(base,store.projectedBaseline(scope,base.eventId)); assertEquals(intent,store.localDelete(scope,base.eventId))
        assertTrue(store.qualifyLocalDelete(intent))
    }

    @Test fun identicalInterruptedProjectionSurvivesUntilNativeDeleteAcknowledgement() = fixture(redundant=true) { store,_ ->
        val incoming = checkNotNull(store.projectionReceipt(scope,base.eventId))
        assertTrue(store.qualifyLocalDelete(intent))
        val first = reserve(store)
        assertEquals(incoming,store.projectionReceipt(scope,base.eventId))
        assertFalse(store.restoreProjection(AppliedProjectionWitness(incoming)))
        assertEquals(incoming,store.projectionReceipt(scope,base.eventId))
        assertTrue(store.restoreDeleteAcknowledgement(AppliedDeleteAcknowledgement(first.record.receipt)))
        assertNull(store.projectionReceipt(scope,base.eventId))
        assertFalse(store.restoreProjection(AppliedProjectionWitness(incoming)))
        assertEquals(base,store.projectedBaseline(scope,base.eventId)); assertEquals(intent,store.localDelete(scope,base.eventId))
        store.requeueAll(scope); assertTrue(store.pending(scope).isEmpty()); pendingOwner(store,0)
    }

    @Test fun differentIncomingFormatCannotBorrowDeletionAcknowledgement() = fixture(redundant=true) { store,_ ->
        store.writableDatabase.execSQL("UPDATE projection_receipts SET version=version+1")
        val incoming = checkNotNull(store.projectionReceipt(scope,base.eventId))
        assertFalse(store.qualifyLocalDelete(intent)); assertNull(store.prepareDeleteAcknowledgement(work))
        assertEquals(incoming,store.projectionReceipt(scope,base.eventId)); pendingOwner(store,1)
    }

    @Test fun initialProjectionWithoutExactPreviousBaseStaysHeld() = fixture(redundant=true) { store,_ ->
        store.writableDatabase.execSQL("UPDATE projection_receipts SET previous_base=NULL")
        val incoming = checkNotNull(store.projectionReceipt(scope,base.eventId))
        assertFalse(store.qualifyLocalDelete(intent)); assertNull(store.prepareDeleteAcknowledgement(work))
        assertEquals(incoming,store.projectionReceipt(scope,base.eventId)); pendingOwner(store,1)
    }

    @Test fun differentIncomingContentOrPreviousRevisionStaysHeld() = fixture(redundant=true) { store,_ ->
        for ((column,field) in listOf("payload" to "title", "previous_base" to "revision")) {
            val original = store.readableDatabase.rawQuery("SELECT $column FROM projection_receipts",null).use {
                assertTrue(it.moveToFirst()); it.getString(0)
            }
            val changed = org.json.JSONObject(original).put(field,"Synthetic different observation").toString()
            store.writableDatabase.update("projection_receipts",ContentValues().apply { put(column,changed) },null,null)
            val incoming = checkNotNull(store.projectionReceipt(scope,base.eventId))
            assertFalse(store.qualifyLocalDelete(intent)); assertNull(store.prepareDeleteAcknowledgement(work))
            assertEquals(incoming,store.projectionReceipt(scope,base.eventId)); pendingOwner(store,1)
            store.writableDatabase.update("projection_receipts",ContentValues().apply { put(column,original) },null,null)
        }
        assertTrue(store.qualifyLocalDelete(intent))
    }

    @Test fun projectionRetirementFaultRollsBackEntireDeletionRestoration() = fixture(redundant=true) { store,name ->
        val incoming = checkNotNull(store.projectionReceipt(scope,base.eventId)); val first = reserve(store)
        store.writableDatabase.execSQL("CREATE TRIGGER reject_delete_projection_retirement BEFORE DELETE ON projection_receipts " +
            "BEGIN SELECT RAISE(ABORT,'synthetic deletion projection fault'); END")
        try {
            store.restoreDeleteAcknowledgement(AppliedDeleteAcknowledgement(first.record.receipt))
            fail("Synthetic projection-retirement fault must abort restoration")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        assertEquals(first.record,store.deleteAcknowledgement(scope,base.eventId))
        assertEquals(incoming,store.projectionReceipt(scope,base.eventId)); assertEquals(intent,store.localDelete(scope,base.eventId))
        assertEquals(base,store.projectedBaseline(scope,base.eventId)); pendingOwner(store,1)
        store.close()
        ImportStore(context,name).use { reopened ->
            assertEquals(first.record,reopened.deleteAcknowledgement(scope,base.eventId))
            assertEquals(incoming,reopened.projectionReceipt(scope,base.eventId))
            reopened.writableDatabase.execSQL("DROP TRIGGER reject_delete_projection_retirement")
            assertTrue(reopened.restoreDeleteAcknowledgement(AppliedDeleteAcknowledgement(first.record.receipt)))
            assertNull(reopened.projectionReceipt(scope,base.eventId)); pendingOwner(reopened,0)
        }
    }

    @Test fun onlyExactRetainedNativeReceiptCompletesAndReplayIsIdempotent() = fixture { store,_ ->
        val first = reserve(store)
        val foreign = first.record.receipt.copy(nonce=UUID.randomUUID().toString())
        assertFalse(store.restoreDeleteAcknowledgement(AppliedDeleteAcknowledgement(foreign)))
        pendingOwner(store,1)
        assertTrue(store.restoreDeleteAcknowledgement(AppliedDeleteAcknowledgement(first.record.receipt)))
        assertTrue(store.restoreDeleteAcknowledgement(AppliedDeleteAcknowledgement(first.record.receipt)))
        pendingOwner(store,0)
        assertEquals(DeleteAcknowledgementState.RESTORED,store.deleteAcknowledgement(scope,base.eventId,work.id)?.state)
        assertEquals(intent,store.localDelete(scope,base.eventId)); assertEquals(base,store.projectedBaseline(scope,base.eventId))
    }

    @Test fun absenceWithoutReservationNeverAcknowledgesAndKeepsOldConflictSemantics() = fixture { store,_ ->
        queue(store,gone)
        assertEquals(LocalEditState.CONFLICT,store.localDelete(scope,base.eventId)?.state)
        assertNull(store.deleteAcknowledgement(scope,base.eventId,work.id)); assertNull(store.prepareDeleteAcknowledgement(work))
        assertFalse(store.qualifyLocalDelete(intent)); pendingOwner(store,1)
    }

    @Test fun reservedRemoteDeletionEchoDoesNotCreateConflictOrAcknowledge() = fixture { store,_ ->
        val first = reserve(store); queue(store,gone); queue(store,gone.copy(revision=Revision("another-opaque-observation")))
        assertEquals(intent,store.localDelete(scope,base.eventId)); assertTrue(store.qualifyLocalDelete(intent))
        assertEquals(first.record,store.deleteAcknowledgement(scope,base.eventId,work.id)); pendingOwner(store,1)
        assertTrue(store.restoreDeleteAcknowledgement(AppliedDeleteAcknowledgement(first.record.receipt)))
        queue(store,gone); assertEquals(intent,store.localDelete(scope,base.eventId)); pendingOwner(store,0)
    }

    @Test fun completedOwnerSuppressesStagedOldUpsertAndRefreshForever() = fixture { store,_ ->
        val first = reserve(store); assertTrue(store.restoreDeleteAcknowledgement(AppliedDeleteAcknowledgement(first.record.receipt)))
        repeat(3) { queue(store,base); store.requeueAll(scope); store.beginRefresh(scope) }
        assertTrue(store.pending(scope).isEmpty()); assertTrue(store.transaction(scope) { hasPendingMutation(base.eventId) })
        assertNull(store.stabilizedProjection(scope,base.eventId)); pendingOwner(store,0)
        refusal { store.prepareProjection(ProjectionIntent(scope,base),format) }
        assertEquals(0,store.acknowledge(ProjectionIntent(scope,base)))
    }

    @Test fun distinctRemoteVariantAfterCompletionRemainsVisibleAndMonotoneConflict() = fixture { store,_ ->
        val first = reserve(store); assertTrue(store.restoreDeleteAcknowledgement(AppliedDeleteAcknowledgement(first.record.receipt)))
        val variant = base.copy(content=base.content.copy(title="Remote variant"))
        queue(store,variant)
        assertEquals(LocalEditState.CONFLICT,store.localDelete(scope,base.eventId)?.state); pendingOwner(store,1)
        assertEquals(variant,store.transaction(scope) { remote(base.eventId) }); assertTrue(store.pending(scope).isEmpty())
        assertTrue(store.readableDatabase.rawQuery("SELECT COUNT(*) FROM conflict_observations",null).use { it.moveToFirst(); it.getInt(0)>0 })
        queue(store,gone); queue(store,base)
        assertEquals(LocalEditState.CONFLICT,store.localDelete(scope,base.eventId)?.state)
        assertFalse(store.restoreDeleteAcknowledgement(AppliedDeleteAcknowledgement(first.record.receipt)))
    }

    @Test fun conflictObservationPreservesOriginalRecordAndBase() = fixture { store,_ ->
        reserve(store)
        val variant = base.copy(revision=Revision("opaque-distinct"),content=base.content.copy(location="Remote variant"))
        assertTrue(store.conflictLocalDelete(intent,variant))
        assertEquals(base,store.transaction(scope) { remote(base.eventId) }); assertEquals(base,store.projectedBaseline(scope,base.eventId))
        assertFalse(store.conflictLocalDelete(intent,variant.copy(eventId=EventId("foreign"))))
        assertEquals(LocalEditState.CONFLICT,store.localDelete(scope,base.eventId)?.state)
    }

    @Test fun deferredObservationAfterCompletionIsRetainedAndConflicted() = fixture { store,_ ->
        val first = reserve(store); assertTrue(store.restoreDeleteAcknowledgement(AppliedDeleteAcknowledgement(first.record.receipt)))
        store.transaction(scope) { (this as StagedSyncTransaction).defer(DeferredCalendarEvent(base.eventId,
            Revision("deferred-opaque"),format,DeferredEventReason.UNSUPPORTED_CONTENT,"opaque-deferred")) }
        assertEquals(1,store.deferredCount(scope)); pendingOwner(store,1)
        assertFalse(store.qualifyLocalDelete(intent)); assertTrue(store.pending(scope).isEmpty())
    }

    @Test fun completedBindingAndOpaqueBaseSurviveReopen() = fixture { store,name ->
        val first = reserve(store); assertTrue(store.restoreDeleteAcknowledgement(AppliedDeleteAcknowledgement(first.record.receipt)))
        store.close()
        ImportStore(context,name).use { reopened ->
            assertEquals(26,reopened.readableDatabase.version)
            assertEquals(first.record.copy(state=DeleteAcknowledgementState.RESTORED),reopened.deleteAcknowledgement(scope,base.eventId,work.id))
            assertEquals(first.copy(record=first.record.copy(state=DeleteAcknowledgementState.RESTORED),fresh=false),reopened.prepareDeleteAcknowledgement(work))
            assertEquals(base,reopened.projectedBaseline(scope,base.eventId)); queue(reopened,base); assertTrue(reopened.pending(scope).isEmpty())
            pendingOwner(reopened,0)
        }
    }

    @Test fun malformedReceiptCannotHideOwnerDonateEchoOrRestore() = fixture { store,_ ->
        val first = reserve(store)
        store.writableDatabase.update("delete_acknowledgements",ContentValues().apply { put("payload","{}"); put("state","RESTORED") },null,null)
        refusal { pendingOwner(store,0) }
        refusal { store.retainedLocalChanges(RetainedLocalChangeQuery(scope)) }
        refusal { queue(store,gone) }
        refusal { store.restoreDeleteAcknowledgement(AppliedDeleteAcknowledgement(first.record.receipt)) }
        assertEquals(intent,store.localDelete(scope,base.eventId)); assertEquals(base,store.transaction(scope) { remote(base.eventId) })
    }

    @Test fun nativeChangeAfterCompletionMakesOriginalOwnerVisibleWithoutRemoteFabrication() = fixture { store,_ ->
        val first = reserve(store); assertTrue(store.restoreDeleteAcknowledgement(AppliedDeleteAcknowledgement(first.record.receipt)))
        assertFalse(store.conflictAcknowledgedDelete(intent.copy(id=MutationId("foreign-owner"))))
        assertTrue(store.conflictAcknowledgedDelete(intent)); assertTrue(store.conflictAcknowledgedDelete(intent))
        assertEquals(base,store.transaction(scope) { remote(base.eventId) }); pendingOwner(store,1)
        assertEquals(first.record.copy(state=DeleteAcknowledgementState.RESTORED),store.deleteAcknowledgement(scope,base.eventId))
    }

    @Test fun schema21MigrationAddsReceiptTableWithoutAdoptingOrCompletingOldOwner() = fixture { store,name ->
        store.writableDatabase.execSQL("DROP TABLE personal_edit_preparations")
        store.writableDatabase.execSQL("DROP TABLE personal_update_acknowledgements")
        store.writableDatabase.execSQL("DROP TABLE personal_update_acknowledgement_history")
        store.writableDatabase.execSQL("DROP TABLE delete_acknowledgements")
        store.writableDatabase.version = 21; store.close()
        ImportStore(context,name).use { reopened ->
            assertEquals(26,reopened.readableDatabase.version); assertNull(reopened.deleteAcknowledgement(scope,base.eventId))
            assertEquals(intent,reopened.localDelete(scope,base.eventId)); pendingOwner(reopened,1)
            assertTrue(reserve(reopened).fresh)
        }
    }

    @Test fun qualificationRefusesForeignCheckpointAndUnknownFirstReservation() = fixture { store,_ ->
        assertNull(store.prepareDeleteAcknowledgement(work.copy(state=DeleteAttemptState.OUTCOME_UNKNOWN)))
        store.transaction(scope) { checkpoint = PageCheckpoint(scope,format.copy(version=2)) }
        assertFalse(store.qualifyLocalDelete(intent)); assertNull(store.prepareDeleteAcknowledgement(work))
        store.transaction(scope) { checkpoint = PageCheckpoint(scope,format) }
        assertTrue(reserve(store).fresh)
        store.writableDatabase.update("delete_acknowledgements",ContentValues().apply { put("payload","{}"); },null,null)
        refusal { store.qualifyLocalDelete(intent) }
    }
}
