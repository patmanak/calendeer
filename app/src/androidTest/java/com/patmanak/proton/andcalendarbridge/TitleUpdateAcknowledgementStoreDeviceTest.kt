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

/** SQLite-only ordinary-event fixtures. A synthetic witness proves SQL behavior, never native ACK. */
@RunWith(AndroidJUnit4::class)
class TitleUpdateAcknowledgementStoreDeviceTest {
    private val scope = CalendarScope(AccountId("title-ack-fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("synthetic-import", 2)
    private val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-base"),
        EventContent("Synthetic original", EventTime.AllDay(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 7)),
            "Synthetic description", "Synthetic location", details = EventDetails()))
    private val intent = LocalEditIntent(MutationId("synthetic-intent"), scope, format, base,
        base.content.copy(title = "Synthetic changed title"))
    private val preparation = LocalEditPreparation(intent, Instant.parse("2026-10-05T12:00:01Z"),
        AdapterFormat("synthetic-preparation", 3), "synthetic-opaque-preparation-not-to-duplicate")
    private val work = TitleUpdateAttemptRecord(MutationId("synthetic-attempt"), preparation,
        AdapterFormat("synthetic-title-wire", 1), "synthetic-opaque-wire-not-to-duplicate", TitleUpdateAttemptState.OUTCOME_UNKNOWN)
    private val updated = base.copy(revision = Revision("synthetic-verified"), content = intent.content)
    private fun result(record: TitleUpdateAttemptRecord = work, event: RemoteChange.Upsert = updated) =
        TitleUpdateVerifiedResult.of(record, TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(record), event))
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun fixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val name = "title-ack-fixture-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic fixture")))
            store.setEnabled(scope, true)
            store.transaction(scope) {
                checkpoint = PageCheckpoint(scope, format)
                putRemote(base); putProjection(ProjectionIntent(scope, base))
            }
            assertEquals(1, store.acknowledge(ProjectionIntent(scope, base)))
            assertEquals(intent, store.captureLocalEdit(intent))
            assertTrue(store.retainLocalEditPreparation(preparation))
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun reserve(store: ImportStore): TitleUpdateAcknowledgementReservation =
        checkNotNull(store.prepareTitleUpdateAcknowledgement(result()))
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected fail-closed acknowledgement rejection") }
        catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
        catch (_: SQLiteException) { }
    }
    private fun raw(name: String, block: (SQLiteDatabase) -> Unit) =
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun rowCount(store: ImportStore, table: String) = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use {
        check(it.moveToFirst()); it.getInt(0)
    }
    private fun pendingFlag(store: ImportStore) = store.readableDatabase.rawQuery("SELECT pending FROM records", null).use {
        check(it.moveToFirst()); it.getInt(0)
    }
    private suspend fun sourceIntact(store: ImportStore) {
        assertEquals(intent, store.localEdit(scope, base.eventId))
        assertEquals(preparation, store.localEditPreparation(scope, base.eventId))
        assertEquals(base, store.projectedBaseline(scope, base.eventId))
    }

    @Test fun freshReservationCommitsOneImmutableNonceAndReopensForInspectionOnly() = fixture { store, name ->
        val reserved = store.prepareTitleUpdateAcknowledgement(result())
        assertNotNull(reserved)
        val first = reserved!!
        assertTrue(first.fresh); assertEquals(TitleUpdateAcknowledgementState.PENDING, first.record.state)
        assertTrue(first.record.receipt.matches(result()))
        sourceIntact(store)
        assertEquals(first.record, store.titleUpdateAcknowledgement(scope, base.eventId))
        assertEquals(first.copy(fresh = false), store.prepareTitleUpdateAcknowledgement(result()))
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(first.record, reopened.titleUpdateAcknowledgement(scope, base.eventId))
            assertEquals(first.copy(fresh = false), reopened.prepareTitleUpdateAcknowledgement(result()))
            sourceIntact(reopened)
            reopened.readableDatabase.rawQuery("SELECT receipt FROM title_update_acknowledgements", null).use {
                assertTrue(it.moveToFirst()); val frame = it.getString(0)
                assertFalse(frame.contains(preparation.protectedPayload)); assertFalse(frame.contains(work.protectedPayload))
            }
        }
    }

    @Test fun appliedExactReceiptAtomicallyRestoresBaseAndRetiresOnlyOriginalEditAndPreparation() = fixture { store, name ->
        val first = reserve(store)
        assertTrue(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(first.record.receipt)))
        assertEquals(updated, store.projectedBaseline(scope, base.eventId))
        assertEquals(updated, store.transaction(scope) { remote(base.eventId) })
        assertEquals(0, pendingFlag(store))
        assertNull(store.localEdit(scope, base.eventId)); assertNull(store.localEditPreparation(scope, base.eventId))
        assertEquals(TitleUpdateAcknowledgementState.RESTORED, store.titleUpdateAcknowledgement(scope, base.eventId)!!.state)
        store.close()
        ImportStore(context, name).use { reopened ->
            assertTrue(reopened.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(first.record.receipt)))
            assertEquals(updated, reopened.projectedBaseline(scope, base.eventId))
            assertNull(reopened.localEditPreparation(scope, base.eventId))
        }
    }

    @Test fun verifiedRemoteReadbackAfterReservationMayConflictOriginalOwnerButRestoreClosesIt() = fixture { store, _ ->
        val first = reserve(store)
        store.transaction(scope) { putRemote(updated); putProjection(ProjectionIntent(scope, updated)) }
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)!!.state)
        assertTrue(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(first.record.receipt)))
        assertNull(store.localEdit(scope, base.eventId)); assertNull(store.localEditPreparation(scope, base.eventId))
        assertEquals(updated, store.transaction(scope) { remote(base.eventId) }); assertEquals(0, pendingFlag(store))
    }

    @Test fun newerRemoteAndItsPendingFlagSurviveHistoricalAcknowledgementRestoration() = fixture { store, _ ->
        val first = reserve(store)
        val newer = updated.copy(revision = Revision("synthetic-newer"), content = updated.content.copy(title = "Synthetic remote later edit"))
        store.transaction(scope) { putRemote(newer); putProjection(ProjectionIntent(scope, newer)) }
        store.writableDatabase.execSQL("UPDATE records SET pending=1")
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)!!.state)
        assertTrue(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(first.record.receipt)))
        assertEquals(newer, store.transaction(scope) { remote(base.eventId) }); assertEquals(1, pendingFlag(store))
        assertEquals(updated, store.projectedBaseline(scope, base.eventId))
        assertNull(store.localEdit(scope, base.eventId)); assertEquals(0, rowCount(store, "local_edit_preparations"))
    }

    @Test fun laterRemoteDeletionAndDeferredProtectedDataRemainWholeAfterRestoration() = fixture { store, _ ->
        val first = reserve(store)
        val deleted = RemoteChange.Deleted(base.eventId, Revision("synthetic-deleted-later"))
        val deferred = DeferredCalendarEvent(base.eventId, Revision("synthetic-deferred"), format,
            DeferredEventReason.UNSUPPORTED_CONTENT, "synthetic-sealed-deferred")
        store.transaction(scope) {
            putRemote(deleted); putProjection(ProjectionIntent(scope, deleted))
            (this as StagedSyncTransaction).defer(deferred)
        }
        store.writableDatabase.execSQL("UPDATE records SET pending=1")
        assertTrue(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(first.record.receipt)))
        assertEquals(deleted, store.transaction(scope) { remote(base.eventId) }); assertEquals(1, pendingFlag(store))
        assertEquals(deferred, store.transaction(scope) { (this as StagedSyncTransaction).deferred(base.eventId) })
        assertEquals(updated, store.projectedBaseline(scope, base.eventId)); assertNull(store.localEdit(scope, base.eventId))
    }

    @Test fun restoredReplayNeverConsumesNewEditOrPreparationAgainstUpdatedBase() = fixture { store, _ ->
        val first = reserve(store)
        val witness = AppliedTitleUpdateAcknowledgement(first.record.receipt)
        assertTrue(store.restoreTitleUpdateAcknowledgement(witness))
        val next = intent.copy(id = MutationId("synthetic-next-intent"), base = updated,
            content = updated.content.copy(title = "Synthetic second local edit"))
        val nextPreparation = preparation.copy(intent = next, timestamp = preparation.timestamp.plusSeconds(1))
        assertEquals(next, store.captureLocalEdit(next))
        assertTrue(store.retainLocalEditPreparation(nextPreparation))
        assertTrue(store.restoreTitleUpdateAcknowledgement(witness))
        assertEquals(next, store.localEdit(scope, base.eventId)); assertEquals(nextPreparation, store.localEditPreparation(scope, base.eventId))
        assertEquals(updated, store.projectedBaseline(scope, base.eventId))
    }

    @Test fun reservationReplayIsHistoricalAfterPauseConflictAndUnavailability() = fixture { store, _ ->
        val first = reserve(store)
        store.transaction(scope) { putRemote(updated) }
        store.setEnabled(scope, false); store.remember(scope.account, emptyList())
        assertEquals(first.copy(fresh = false), store.prepareTitleUpdateAcknowledgement(result()))
        assertEquals(first.record, store.titleUpdateAcknowledgement(scope, base.eventId))
        assertFalse(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(first.record.receipt)))
        assertEquals(1, rowCount(store, "local_edits")); assertEquals(1, rowCount(store, "local_edit_preparations"))
    }

    @Test fun freshReservationRejectsInactiveIncompatibleAndInitiallyConflictedWork() = fixture { store, _ ->
        store.setEnabled(scope, false); assertNull(store.prepareTitleUpdateAcknowledgement(result()))
        store.setEnabled(scope, true)
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format.copy(version = 3)) }
        assertNull(store.prepareTitleUpdateAcknowledgement(result()))
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format); putRemote(updated) }
        assertNull(store.prepareTitleUpdateAcknowledgement(result()))
        assertEquals(0, rowCount(store, "title_update_acknowledgements"))
    }

    @Test fun freshReservationRequiresCompleteIdenticalStoredPreparation() = fixture { store, _ ->
        val variants = listOf(preparation.copy(timestamp = preparation.timestamp.plusSeconds(1)),
            preparation.copy(format = preparation.format.copy(version = 4)), preparation.copy(protectedPayload = "other-sealed"))
        variants.forEach { assertNull(store.prepareTitleUpdateAcknowledgement(result(work.copy(preparation = it)))) }
        sourceIntact(store); assertEquals(0, rowCount(store, "title_update_acknowledgements"))
    }

    @Test fun freshReservationCannotAdoptRepresentedBaseOrMissingPreparation() = fixture { store, _ ->
        store.writableDatabase.execSQL("UPDATE projection_bases SET representation='unqualified-representation'")
        assertNull(store.prepareTitleUpdateAcknowledgement(result()))
        store.writableDatabase.execSQL("UPDATE projection_bases SET representation=NULL")
        store.writableDatabase.execSQL("DELETE FROM local_edit_preparations")
        assertNull(store.prepareTitleUpdateAcknowledgement(result()))
        assertEquals(intent, store.localEdit(scope, base.eventId)); assertEquals(base, store.projectedBaseline(scope, base.eventId))
        assertEquals(0, rowCount(store, "title_update_acknowledgements"))
    }

    @Test fun freshReservationCannotBypassAlreadyDeferredRemoteEvidence() = fixture { store, _ ->
        val deferred = DeferredCalendarEvent(base.eventId, Revision("synthetic-deferred-before-reserve"), format,
            DeferredEventReason.UNVERIFIED_CONTENT, "synthetic-opaque-deferred-before-reserve")
        store.transaction(scope) { (this as StagedSyncTransaction).defer(deferred) }
        assertNull(store.prepareTitleUpdateAcknowledgement(result()))
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)!!.state)
        assertEquals(preparation, store.localEditPreparation(scope, base.eventId))
        assertEquals(deferred, store.transaction(scope) { (this as StagedSyncTransaction).deferred(base.eventId) })
        assertEquals(0, rowCount(store, "title_update_acknowledgements"))
    }

    @Test fun incompatibleResultCannotReplaceAlreadyReservedReceipt() = fixture { store, _ ->
        val first = reserve(store)
        reject { store.prepareTitleUpdateAcknowledgement(result(event = updated.copy(revision = Revision("other-result")))) }
        reject { store.prepareTitleUpdateAcknowledgement(result(work.copy(protectedPayload = "other-wire"))) }
        assertEquals(first.record, store.titleUpdateAcknowledgement(scope, base.eventId)); sourceIntact(store)
    }

    @Test fun changedOwnerOrPreparationAfterReservationCannotBeConsumed() = fixture { store, name ->
        val first = reserve(store)
        val witness = AppliedTitleUpdateAcknowledgement(first.record.receipt)
        raw(name) { it.execSQL("UPDATE local_edit_preparations SET payload='changed-preparation'") }
        assertFalse(store.restoreTitleUpdateAcknowledgement(witness))
        assertEquals(base, store.projectedBaseline(scope, base.eventId)); assertEquals(1, rowCount(store, "local_edits"))
        raw(name) { db ->
            db.execSQL("UPDATE local_edit_preparations SET payload=?", arrayOf(preparation.protectedPayload))
            db.execSQL("UPDATE local_edits SET mutation='changed-owner'")
        }
        assertFalse(store.restoreTitleUpdateAcknowledgement(witness))
        assertEquals(1, rowCount(store, "local_edit_preparations"))
        assertEquals(first.record, store.titleUpdateAcknowledgement(scope, base.eventId))
    }

    @Test fun missingOrDifferentReservedNonceNeverRestoresAnything() = fixture { store, _ ->
        val unreserved = TitleUpdateAcknowledgementReceipt.of(result(), UUID.randomUUID().toString())
        assertFalse(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(unreserved)))
        val first = reserve(store)
        val foreign = first.record.receipt.copy(projection = first.record.receipt.projection.copy(nonce = UUID.randomUUID().toString()))
        assertFalse(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(foreign)))
        sourceIntact(store); assertEquals(first.record, store.titleUpdateAcknowledgement(scope, base.eventId))
    }

    @Test fun independentHelpersReserveOneFreshNonceOnly() = fixture { store, name ->
        ImportStore(context, name).use { other ->
            val gate = CompletableDeferred<Unit>()
            val reservations = coroutineScope {
                listOf(store, other).map { helper -> async(Dispatchers.IO) { gate.await(); reserve(helper) } }
                    .also { gate.complete(Unit) }.awaitAll()
            }
            assertEquals(1, reservations.count { it.fresh })
            assertEquals(reservations[0].record, reservations[1].record)
        }
    }

    @Test fun sqlFaultDuringReservationOrRetirementRollsBackWholeState() = fixture { store, _ ->
        store.writableDatabase.execSQL("CREATE TRIGGER reject_title_reservation AFTER INSERT ON title_update_acknowledgements BEGIN SELECT RAISE(ABORT,'fixture'); END")
        reject { reserve(store) }
        assertNull(store.titleUpdateAcknowledgement(scope, base.eventId)); sourceIntact(store)
        store.writableDatabase.execSQL("DROP TRIGGER reject_title_reservation")
        val first = reserve(store)
        store.writableDatabase.execSQL("CREATE TRIGGER reject_title_retirement BEFORE DELETE ON local_edits BEGIN SELECT RAISE(ABORT,'fixture'); END")
        reject { store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(first.record.receipt)) }
        sourceIntact(store); assertEquals(base, store.transaction(scope) { remote(base.eventId) })
        assertEquals(first.record, store.titleUpdateAcknowledgement(scope, base.eventId))
    }

    @Test fun leaseLossAndCancellationBeforeCommitDoNotAdmitFreshReceiptOrRetireWork() = fixture { store, _ ->
        var calls = 0
        store.admitTransaction = { ++calls == 1 }
        reject { reserve(store) }
        store.admitTransaction = { true }; assertNull(store.titleUpdateAcknowledgement(scope, base.eventId)); sourceIntact(store)
        val first = reserve(store)
        val child = Job(currentCoroutineContext()[Job]); calls = 0
        store.admitTransaction = { if (++calls == 2) child.cancel(); true }
        try { withContext(child) { store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(first.record.receipt)) }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        finally { store.admitTransaction = { true } }
        sourceIntact(store); assertEquals(first.record, store.titleUpdateAcknowledgement(scope, base.eventId))
    }

    @Test fun additiveVersionFifteenMigrationPreservesOriginalOwnerPreparationAndRemote() = fixture { store, name ->
        store.writableDatabase.execSQL("DROP TABLE title_update_acknowledgements")
        store.writableDatabase.version = 15; store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(16, reopened.readableDatabase.version)
            sourceIntact(reopened); assertEquals(base, reopened.transaction(scope) { remote(base.eventId) })
            assertNull(reopened.titleUpdateAcknowledgement(scope, base.eventId)); assertTrue(reserve(reopened).fresh)
        }
    }

    @Test fun malformedHistoricalReceiptIsRejectedWithoutReinterpretingCurrentSource() = fixture { store, name ->
        reserve(store)
        raw(name) { db -> db.update("title_update_acknowledgements", ContentValues().apply { put("receipt", "not-json") }, null, null) }
        reject { store.titleUpdateAcknowledgement(scope, base.eventId) }
        reject { store.prepareTitleUpdateAcknowledgement(result()) }
        sourceIntact(store)
    }

    @Test fun capturedProvenanceAndUnresolvedOrdinaryProjectionCannotBeBypassed() = fixture { store, _ ->
        store.writableDatabase.execSQL("INSERT INTO captured_create_handoffs(scope,event,nonce,protocol,ack_nonce,ack_fingerprint,fingerprint,state,waiting) SELECT scope,event,?,1,?,?,?,'PENDING',0 FROM projection_bases",
            arrayOf(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "synthetic-ack", "synthetic-frame"))
        assertNull(store.prepareTitleUpdateAcknowledgement(result()))
        store.writableDatabase.execSQL("DELETE FROM captured_create_handoffs")
        store.writableDatabase.execSQL("INSERT INTO projection_receipts(scope,event,nonce,payload,adapter,version,previous_base,protocol) SELECT scope,event,?,payload,?,2,payload,1 FROM projection_bases",
            arrayOf(UUID.randomUUID().toString(), format.id))
        assertNull(store.prepareTitleUpdateAcknowledgement(result())); sourceIntact(store)
    }
}
