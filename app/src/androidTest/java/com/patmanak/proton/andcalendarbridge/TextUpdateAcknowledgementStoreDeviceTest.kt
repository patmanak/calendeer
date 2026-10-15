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
import com.patmanak.proton.andcalendarbridge.data.importing.LocalTextPreparationAdmission
import com.patmanak.proton.andcalendarbridge.data.importing.ProjectionRepresentationCodec
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
class TextUpdateAcknowledgementStoreDeviceTest {
    private val scope = CalendarScope(AccountId("text-ack-fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("synthetic-import", 2)
    private val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-base"),
        EventContent("Synthetic original", EventTime.AllDay(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 7)),
            "Synthetic description", "Synthetic location", details = EventDetails()))
    private val intent = LocalEditIntent(MutationId("synthetic-intent"), scope, format, base,
        base.content.copy(title = "Synthetic changed\r\ntitle", description = "Synthetic changed\r\ndescription", location = "Synthetic changed\r\nlocation"))
    private val preparation = LocalTextEditPreparation(intent, Instant.parse("2026-10-05T12:00:01Z"),
        AdapterFormat("synthetic-preparation", 3), "synthetic-opaque-preparation-not-to-duplicate")
    private val work = TextUpdateAttemptRecord(MutationId("synthetic-attempt"), preparation,
        AdapterFormat("synthetic-title-wire", 1), "synthetic-opaque-wire-not-to-duplicate", TextUpdateAttemptState.OUTCOME_UNKNOWN)
    private val updated = base.copy(revision = Revision("synthetic-verified"), content = intent.content.copy(title = intent.content.title.replace("\r\n", "\n"),
        description = intent.content.description.replace("\r\n", "\n"), location = intent.content.location.replace("\r\n", "\n")))
    private fun result(record: TextUpdateAttemptRecord = work, event: RemoteChange.Upsert = updated) =
        TextUpdateVerifiedResult.of(record, TextUpdateSubmissionObservation.Verified(record, event))

    private fun LocalTextEditPreparation.copy(intent: LocalEditIntent = this.intent, timestamp: Instant = this.timestamp,
        format: AdapterFormat = this.format, protectedPayload: String = this.protectedPayload) =
        LocalTextEditPreparation(intent, timestamp, format, protectedPayload)
    private fun assertPreparation(expected: LocalTextEditPreparation, actual: LocalTextEditPreparation?) {
        assertNotNull(actual); actual!!
        assertEquals(expected.intent, actual.intent); assertEquals(expected.timestamp, actual.timestamp)
        assertEquals(expected.format, actual.format); assertEquals(expected.protectedPayload, actual.protectedPayload)
    }
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun fixture(redundant: Boolean = false, block: suspend (ImportStore, String) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val name = "text-ack-fixture-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic fixture")))
            store.setEnabled(scope, true)
            store.transaction(scope) {
                checkpoint = PageCheckpoint(scope, format)
                putRemote(base); putProjection(ProjectionIntent(scope, base))
            }
            assertEquals(1, store.acknowledge(ProjectionIntent(scope, base)))
            if (redundant) {
                store.transaction(scope) { putProjection(ProjectionIntent(scope, base)) }
                val pending = store.prepareProjection(ProjectionIntent(scope, base), format)
                assertEquals(base, pending.change); assertEquals(base, pending.previousBase)
            }
            assertEquals(intent, store.captureLocalEdit(intent))
            assertEquals(LocalTextPreparationAdmission.STORED, store.retainCurrentLocalTextPreparation(preparation))
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun reserve(store: ImportStore): TextUpdateAcknowledgementReservation =
        checkNotNull(store.prepareTextUpdateAcknowledgement(result()))
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
        assertPreparation(preparation, store.localTextEditPreparation(scope, base.eventId))
        assertEquals(base, store.projectedBaseline(scope, base.eventId))
    }

    @Test fun redundantOrdinaryReceiptSurvivesPendingAndRollbackUntilProvenRestoration() = fixture(redundant = true) { store, name ->
        val old = checkNotNull(store.projectionReceipt(scope, base.eventId))
        val first = reserve(store); assertEquals(old, store.projectionReceipt(scope, base.eventId))
        store.writableDatabase.execSQL("CREATE TRIGGER reject_text_base BEFORE UPDATE ON projection_bases BEGIN SELECT RAISE(ABORT,'synthetic text base fault'); END")
        val witness = AppliedTextUpdateAcknowledgement(first.record.receipt)
        reject { store.restoreTextUpdateAcknowledgement(witness) }
        assertEquals(old, store.projectionReceipt(scope, base.eventId)); sourceIntact(store)
        assertEquals(first.record, store.textUpdateAcknowledgement(scope, base.eventId))
        store.writableDatabase.execSQL("DROP TRIGGER reject_text_base"); store.close()
        ImportStore(context, name).use { reopened ->
            assertTrue(reopened.restoreTextUpdateAcknowledgement(witness))
            assertNull(reopened.projectionReceipt(scope, base.eventId))
            assertTrue(reopened.restoreTextUpdateAcknowledgement(witness))
            assertEquals(updated, reopened.projectedBaseline(scope, base.eventId))
        }
    }
    @Test fun exactVerifiedEchoBeforeReservationAdmitsButDifferentRawEchoDoesNot() = fixture { store, _ ->
        store.transaction(scope) { putRemote(updated.copy(content = intent.content)) }
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)!!.state)
        assertNull(store.prepareTextUpdateAcknowledgement(result()))
        store.transaction(scope) { putRemote(updated) }
        val reserved = reserve(store)
        assertEquals(intent, reserved.record.receipt.intent)
        assertEquals(updated, reserved.record.receipt.projection.change)
        assertTrue(store.restoreTextUpdateAcknowledgement(AppliedTextUpdateAcknowledgement(reserved.record.receipt)))
        assertEquals(updated, store.projectedBaseline(scope, base.eventId))
    }
    @Test fun oldRestoredHistorySurvivesSecondAcknowledgementAndPendingWork() = fixture { store, _ ->
        val first = reserve(store); val firstWitness = AppliedTextUpdateAcknowledgement(first.record.receipt)
        assertTrue(store.restoreTextUpdateAcknowledgement(firstWitness))
        val next = intent.copy(id = MutationId("second-local-intent"), base = updated,
            content = updated.content.copy(description = "Second\r\ndescription"))
        assertEquals(next, store.captureLocalEdit(next))
        val capsule = preparation.copy(intent = next, timestamp = preparation.timestamp.plusSeconds(1))
        assertEquals(LocalTextPreparationAdmission.STORED, store.retainCurrentLocalTextPreparation(capsule))
        val record = work.copy(id = MutationId("second-request"), preparation = capsule)
        val actual = updated.copy(revision = Revision("second-result"), content = next.content.copy(description = "Second\ndescription"))
        val second = checkNotNull(store.prepareTextUpdateAcknowledgement(result(record, actual)))
        assertTrue(second.fresh)
        val before = store.textUpdateAcknowledgement(scope, base.eventId)
        assertTrue(store.restoreTextUpdateAcknowledgement(firstWitness))
        assertEquals(before, store.textUpdateAcknowledgement(scope, base.eventId))
        assertEquals(first.record.copy(state = TextUpdateAcknowledgementState.RESTORED),
            store.textUpdateAcknowledgement(scope, base.eventId, work.id))
        assertEquals(next, store.localEdit(scope, base.eventId)); assertPreparation(capsule, store.localTextEditPreparation(scope, base.eventId))
    }
    @Test fun explicitInitialAndAdvancedCreateHandoffKeepOriginalEvidenceAndAdvanceOnce() {
        for (advanced in listOf(false, true)) fixture { store, name ->
            val original = base.copy(eventId = EventId("synthetic-transferred-event"))
            val create = LocalCreateIntent(LocalCreateReservation(MutationId("capture"), LocalEventId(UUID(0, 22).toString()),
                scope, format, preparation.timestamp), original.content)
            val submission = LocalCreateSubmission(LocalCreateSubmissionReservation(create, MutationId(UUID(0, 23).toString()),
                AdapterFormat("synthetic-create-wire", 1)), "opaque-create")
            val attachment = CapturedCreateAttachmentReceipt(CapturedCreateBinding(CapturedCreateDispatchIdentity.of(submission),
                original.eventId, original.revision), UUID.randomUUID().toString())
            val baseline = CapturedCreateBaseline(attachment, original)
            assertTrue(store.retainCapturedBaseline(baseline, AppliedCapturedCreateAttachment(attachment)))
            store.transaction(scope) { putRemote(original) }
            val ack = store.reserveCapturedAcknowledgement(CapturedCreateAcknowledgementReceipt(baseline, UUID.randomUUID().toString())).record.receipt
            store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(ack))
            val reserved = store.reserveCapturedHandoff(CapturedCreateHandoffReceipt(ack, UUID.randomUUID().toString()))
            var frame = store.retainCapturedHandoff(reserved.frame.receipt, CapturedCreateCleanWitness(ack)).frame
            if (advanced) {
                val incoming = original.copy(revision = Revision("incoming-before-text"), content = original.content.copy(title = "Intermediate"))
                store.transaction(scope) { putRemote(incoming); putProjection(ProjectionIntent(scope, incoming)) }
                val plan = store.prepareCapturedHandoffProjection(frame, ProjectionIntent(scope, incoming))
                assertTrue(store.restoreCapturedHandoffProjection(AppliedCapturedHandoffProjection(plan)))
                frame = store.capturedHandoff(scope, original.eventId)!!.frame
            }
            val local = frame.base.content.copy(description = "Transferred\r\ndetails", location = "Transferred\r\nplace")
            val edit = LocalEditIntent(MutationId("transferred-text-edit"), scope, format, frame.base, local)
            assertEquals(LocalEditState.PAUSED, store.retainCapturedHandoffLocalChange(CapturedHandoffCurrent(frame, local, false), edit.id))
            val capsule = preparation.copy(intent = edit)
            assertEquals(LocalTextPreparationAdmission.STORED, store.retainCurrentLocalTextPreparation(capsule))
            val record = work.copy(id = MutationId("transferred-text-request"), preparation = capsule)
            val actual = frame.base.copy(revision = Revision("transferred-result"), content = local.copy(description = "Transferred\ndetails", location = "Transferred\nplace"))
            val proof = result(record, actual)
            assertNull(store.prepareTextUpdateAcknowledgement(proof))
            val first = checkNotNull(store.prepareTextUpdateAcknowledgement(proof, frame))
            val origins = listOf("captured_create_bases", "captured_create_acknowledgements").associateWith { table ->
                store.readableDatabase.rawQuery("SELECT * FROM $table", null).use { row -> buildList {
                    while (row.moveToNext()) add(List(row.columnCount) { if (row.isNull(it)) null else row.getString(it) })
                } }
            }
            assertTrue(store.restoreTextUpdateAcknowledgement(AppliedTextUpdateAcknowledgement(first.record.receipt)))
            assertEquals(CapturedCreateHandoffFrame(frame.receipt, actual, first.record.receipt.projection), store.capturedHandoff(scope, original.eventId)!!.frame)
            assertFalse(store.capturedHandoff(scope, original.eventId)!!.waiting)
            for ((table, expected) in origins) store.readableDatabase.rawQuery("SELECT * FROM $table", null).use { row ->
                assertEquals(expected, buildList { while (row.moveToNext()) add(List(row.columnCount) { if (row.isNull(it)) null else row.getString(it) }) })
            }
            store.close(); ImportStore(context, name).use { reopened ->
                assertTrue(reopened.restoreTextUpdateAcknowledgement(AppliedTextUpdateAcknowledgement(first.record.receipt)))
                assertEquals(frame, reopened.textUpdateAcknowledgement(scope, original.eventId, record.id)!!.receipt.handoff)
            }
        }
    }
    @Test fun freshReservationCommitsOneImmutableNonceAndReopensForInspectionOnly() = fixture { store, name ->
        val reserved = store.prepareTextUpdateAcknowledgement(result())
        assertNotNull(reserved)
        val first = reserved!!
        assertTrue(first.fresh); assertEquals(TextUpdateAcknowledgementState.PENDING, first.record.state)
        assertTrue(first.record.receipt.matches(result()))
        sourceIntact(store)
        assertEquals(first.record, store.textUpdateAcknowledgement(scope, base.eventId))
        assertEquals(first.copy(fresh = false), store.prepareTextUpdateAcknowledgement(result()))
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(first.record, reopened.textUpdateAcknowledgement(scope, base.eventId))
            assertEquals(first.copy(fresh = false), reopened.prepareTextUpdateAcknowledgement(result()))
            sourceIntact(reopened)
            reopened.readableDatabase.rawQuery("SELECT receipt FROM text_update_acknowledgements", null).use {
                assertTrue(it.moveToFirst()); val frame = it.getString(0)
                assertFalse(frame.contains(preparation.protectedPayload)); assertFalse(frame.contains(work.protectedPayload))
            }
        }
    }

    @Test fun appliedExactReceiptAtomicallyRestoresBaseAndRetiresOnlyOriginalEditAndPreparation() = fixture { store, name ->
        val first = reserve(store)
        assertTrue(store.restoreTextUpdateAcknowledgement(AppliedTextUpdateAcknowledgement(first.record.receipt)))
        assertEquals(updated, store.projectedBaseline(scope, base.eventId))
        assertEquals(updated, store.transaction(scope) { remote(base.eventId) })
        assertEquals(0, pendingFlag(store))
        assertNull(store.localEdit(scope, base.eventId)); assertNull(store.localTextEditPreparation(scope, base.eventId))
        assertEquals(TextUpdateAcknowledgementState.RESTORED, store.textUpdateAcknowledgement(scope, base.eventId)!!.state)
        store.close()
        ImportStore(context, name).use { reopened ->
            assertTrue(reopened.restoreTextUpdateAcknowledgement(AppliedTextUpdateAcknowledgement(first.record.receipt)))
            assertEquals(updated, reopened.projectedBaseline(scope, base.eventId))
            assertNull(reopened.localTextEditPreparation(scope, base.eventId))
        }
    }

    @Test fun verifiedRemoteReadbackAfterReservationMayConflictOriginalOwnerButRestoreClosesIt() = fixture { store, _ ->
        val first = reserve(store)
        store.transaction(scope) { putRemote(updated); putProjection(ProjectionIntent(scope, updated)) }
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)!!.state)
        assertTrue(store.restoreTextUpdateAcknowledgement(AppliedTextUpdateAcknowledgement(first.record.receipt)))
        assertNull(store.localEdit(scope, base.eventId)); assertNull(store.localTextEditPreparation(scope, base.eventId))
        assertEquals(updated, store.transaction(scope) { remote(base.eventId) }); assertEquals(0, pendingFlag(store))
    }

    @Test fun newerRemoteAndItsPendingFlagSurviveHistoricalAcknowledgementRestoration() = fixture { store, _ ->
        val first = reserve(store)
        val newer = updated.copy(revision = Revision("synthetic-newer"), content = updated.content.copy(title = "Synthetic remote later edit"))
        store.transaction(scope) { putRemote(newer); putProjection(ProjectionIntent(scope, newer)) }
        store.writableDatabase.execSQL("UPDATE records SET pending=1")
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)!!.state)
        assertTrue(store.restoreTextUpdateAcknowledgement(AppliedTextUpdateAcknowledgement(first.record.receipt)))
        assertEquals(newer, store.transaction(scope) { remote(base.eventId) }); assertEquals(1, pendingFlag(store))
        assertEquals(updated, store.projectedBaseline(scope, base.eventId))
        assertNull(store.localEdit(scope, base.eventId)); assertEquals(0, rowCount(store, "local_text_edit_preparations"))
    }

    @Test fun distinctObservationRequeuesOnlyItsEventAndSurvivesRestartWithoutReplayRearming() = fixture { store, name ->
        val first = reserve(store)
        val newer = updated.copy(revision = Revision("synthetic-distinct"), content = updated.content.copy(title = "Synthetic distinct observation"))
        val neighbour = base.copy(eventId = EventId("synthetic-neighbour"))
        val other = scope.copy(account = AccountId("synthetic-other-account"))
        store.remember(other.account, listOf(CalendarSummary(other.calendar, "Synthetic other fixture")))
        store.setEnabled(other, true)
        store.transaction(other) { putRemote(newer) }
        store.transaction(scope) { putRemote(neighbour); putRemote(newer); putProjection(ProjectionIntent(scope, newer)) }
        assertTrue(store.pending(scope).isEmpty())
        val witness = AppliedTextUpdateAcknowledgement(first.record.receipt)
        assertTrue(store.restoreTextUpdateAcknowledgement(witness))
        val expected = listOf(ProjectionIntent(scope, newer))
        assertEquals(expected, store.pending(scope)); assertTrue(store.pending(other).isEmpty())
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(expected, reopened.pending(scope)); assertTrue(reopened.pending(other).isEmpty())
            assertEquals(neighbour, reopened.transaction(scope) { remote(neighbour.eventId) })
            assertEquals(updated, reopened.projectedBaseline(scope, base.eventId))
            assertEquals(1, reopened.acknowledge(expected.single()))
            assertTrue(reopened.restoreTextUpdateAcknowledgement(witness))
            assertTrue(reopened.pending(scope).isEmpty())
            assertEquals(newer, reopened.projectedBaseline(scope, base.eventId))
        }
    }

    @Test fun distinctOrdinaryDeletionRequeuesWithoutInventingContent() = fixture { store, _ ->
        val first = reserve(store)
        val deleted = RemoteChange.Deleted(base.eventId, Revision("synthetic-later-deletion"))
        store.transaction(scope) { putRemote(deleted); putProjection(ProjectionIntent(scope, deleted)) }
        assertEquals(0, pendingFlag(store))
        assertTrue(store.restoreTextUpdateAcknowledgement(AppliedTextUpdateAcknowledgement(first.record.receipt)))
        assertEquals(listOf(ProjectionIntent(scope, deleted)), store.pending(scope))
        assertEquals(deleted, store.transaction(scope) { remote(base.eventId) })
    }

    @Test fun deferredObservationIsNotRearmedOrDiscarded() = fixture { store, _ ->
        val first = reserve(store)
        val deleted = RemoteChange.Deleted(base.eventId, Revision("synthetic-deferred-deletion"))
        val deferred = DeferredCalendarEvent(base.eventId, Revision("synthetic-deferred"), format,
            DeferredEventReason.UNVERIFIED_CONTENT, "synthetic-sealed-deferred")
        store.transaction(scope) {
            putRemote(deleted); putProjection(ProjectionIntent(scope, deleted))
            (this as StagedSyncTransaction).defer(deferred)
        }
        assertEquals(0, pendingFlag(store))
        assertTrue(store.restoreTextUpdateAcknowledgement(AppliedTextUpdateAcknowledgement(first.record.receipt)))
        assertEquals(0, pendingFlag(store)); assertTrue(store.pending(scope).isEmpty())
        assertEquals(deleted, store.transaction(scope) { remote(base.eventId) })
        assertEquals(deferred, store.transaction(scope) { (this as StagedSyncTransaction).deferred(base.eventId) })
    }

    @Test fun missingOrInactiveRecurrenceParentKeepsItsExistingDependencyGate() {
        for (inactive in listOf(false, true)) fixture { store, _ ->
            val first = reserve(store)
            val uid = "synthetic-series"
            val master = base.copy(eventId = EventId("synthetic-master"),
                content = base.content.copy(recurrence = EventRecurrence(uid, rule = "FREQ=DAILY;COUNT=3")))
            val child = updated.copy(revision = Revision("synthetic-child"), content = updated.content.copy(
                recurrence = EventRecurrence(uid, original = RecurrencePoint.AllDay(LocalDate.of(2026, 10, 5)))))
            store.transaction(scope) {
                if (inactive) { putRemote(master); putRemote(RemoteChange.Deleted(master.eventId, Revision("synthetic-parent-deleted"))) }
                putRemote(child); putProjection(ProjectionIntent(scope, child))
            }
            assertTrue(store.restoreTextUpdateAcknowledgement(AppliedTextUpdateAcknowledgement(first.record.receipt)))
            assertTrue(store.pending(scope).isEmpty())
            assertEquals(if (inactive) 0 else 1, store.blockedSeriesCount(scope))
            assertEquals(child, store.transaction(scope) { remote(base.eventId) })
        }
    }

    @Test fun faultAfterRearmingRollsBackQueueBaselineOwnerAndReceiptTogether() = fixture { store, name ->
        val first = reserve(store)
        val newer = updated.copy(revision = Revision("synthetic-after-reservation"))
        store.transaction(scope) { putRemote(newer); putProjection(ProjectionIntent(scope, newer)) }
        store.writableDatabase.execSQL("CREATE TRIGGER reject_restored_receipt BEFORE UPDATE OF state ON text_update_acknowledgements WHEN NEW.state='RESTORED' BEGIN SELECT RAISE(ABORT,'synthetic restored receipt fault'); END")
        val witness = AppliedTextUpdateAcknowledgement(first.record.receipt)
        reject { store.restoreTextUpdateAcknowledgement(witness) }
        assertEquals(0, pendingFlag(store)); assertEquals(base, store.projectedBaseline(scope, base.eventId))
        assertEquals(intent.copy(state = LocalEditState.CONFLICT), store.localEdit(scope, base.eventId))
        assertPreparation(preparation, store.localTextEditPreparation(scope, base.eventId)); assertEquals(first.record, store.textUpdateAcknowledgement(scope, base.eventId))
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(0, pendingFlag(reopened)); reopened.writableDatabase.execSQL("DROP TRIGGER reject_restored_receipt")
            assertTrue(reopened.restoreTextUpdateAcknowledgement(witness))
            assertEquals(listOf(ProjectionIntent(scope, newer)), reopened.pending(scope))
            assertNull(reopened.localEdit(scope, base.eventId)); assertNull(reopened.localTextEditPreparation(scope, base.eventId))
        }
    }

    @Test fun absentCacheIsNotSynthesizedByRestoration() = fixture { store, _ ->
        val first = reserve(store)
        store.writableDatabase.execSQL("DELETE FROM records")
        assertTrue(store.restoreTextUpdateAcknowledgement(AppliedTextUpdateAcknowledgement(first.record.receipt)))
        assertNull(store.transaction(scope) { remote(base.eventId) }); assertTrue(store.pending(scope).isEmpty())
        assertEquals(updated, store.projectedBaseline(scope, base.eventId))
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
        assertTrue(store.restoreTextUpdateAcknowledgement(AppliedTextUpdateAcknowledgement(first.record.receipt)))
        assertEquals(deleted, store.transaction(scope) { remote(base.eventId) }); assertEquals(1, pendingFlag(store))
        assertEquals(deferred, store.transaction(scope) { (this as StagedSyncTransaction).deferred(base.eventId) })
        assertEquals(updated, store.projectedBaseline(scope, base.eventId)); assertNull(store.localEdit(scope, base.eventId))
    }

    @Test fun restoredReplayNeverConsumesNewEditOrPreparationAgainstUpdatedBase() = fixture { store, _ ->
        val first = reserve(store)
        val witness = AppliedTextUpdateAcknowledgement(first.record.receipt)
        assertTrue(store.restoreTextUpdateAcknowledgement(witness))
        val next = intent.copy(id = MutationId("synthetic-next-intent"), base = updated,
            content = updated.content.copy(description = "Synthetic second local details"))
        val nextPreparation = preparation.copy(intent = next, timestamp = preparation.timestamp.plusSeconds(1))
        assertEquals(next, store.captureLocalEdit(next))
        assertEquals(LocalTextPreparationAdmission.STORED, store.retainCurrentLocalTextPreparation(nextPreparation))
        assertTrue(store.restoreTextUpdateAcknowledgement(witness))
        assertEquals(next, store.localEdit(scope, base.eventId)); assertPreparation(nextPreparation, store.localTextEditPreparation(scope, base.eventId))
        assertEquals(updated, store.projectedBaseline(scope, base.eventId))
    }

    @Test fun reservationReplayIsHistoricalAfterPauseConflictAndUnavailability() = fixture { store, _ ->
        val first = reserve(store)
        store.transaction(scope) { putRemote(updated) }
        store.setEnabled(scope, false); store.remember(scope.account, emptyList())
        assertEquals(first.copy(fresh = false), store.prepareTextUpdateAcknowledgement(result()))
        assertEquals(first.record, store.textUpdateAcknowledgement(scope, base.eventId))
        assertFalse(store.restoreTextUpdateAcknowledgement(AppliedTextUpdateAcknowledgement(first.record.receipt)))
        assertEquals(1, rowCount(store, "local_edits")); assertEquals(1, rowCount(store, "local_text_edit_preparations"))
    }

    @Test fun freshReservationRejectsInactiveIncompatibleAndDistinctConflictedWork() = fixture { store, _ ->
        store.setEnabled(scope, false); assertNull(store.prepareTextUpdateAcknowledgement(result()))
        store.setEnabled(scope, true)
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format.copy(version = 3)) }
        assertNull(store.prepareTextUpdateAcknowledgement(result()))
        store.transaction(scope) {
            checkpoint = PageCheckpoint(scope, format)
            putRemote(updated.copy(revision = Revision("synthetic-distinct-concurrent-result")))
        }
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)?.state)
        assertNull(store.prepareTextUpdateAcknowledgement(result()))
        assertEquals(0, rowCount(store, "text_update_acknowledgements"))
    }

    @Test fun freshReservationRequiresCompleteIdenticalStoredPreparation() = fixture { store, _ ->
        val variants = listOf(preparation.copy(timestamp = preparation.timestamp.plusSeconds(1)),
            preparation.copy(format = preparation.format.copy(version = 4)), preparation.copy(protectedPayload = "other-sealed"))
        variants.forEach { assertNull(store.prepareTextUpdateAcknowledgement(result(work.copy(preparation = it)))) }
        sourceIntact(store); assertEquals(0, rowCount(store, "text_update_acknowledgements"))
    }

    @Test fun freshReservationCannotAdoptRepresentedBaseOrMissingPreparation() = fixture { store, _ ->
        store.writableDatabase.execSQL("UPDATE projection_bases SET representation='unqualified-representation'")
        assertNull(store.prepareTextUpdateAcknowledgement(result()))
        store.writableDatabase.execSQL("UPDATE projection_bases SET representation=NULL")
        store.writableDatabase.execSQL("DELETE FROM local_text_edit_preparations")
        assertNull(store.prepareTextUpdateAcknowledgement(result()))
        assertEquals(intent, store.localEdit(scope, base.eventId)); assertEquals(base, store.projectedBaseline(scope, base.eventId))
        assertEquals(0, rowCount(store, "text_update_acknowledgements"))
    }

    @Test fun freshReservationCannotBypassAlreadyDeferredRemoteEvidence() = fixture { store, _ ->
        val deferred = DeferredCalendarEvent(base.eventId, Revision("synthetic-deferred-before-reserve"), format,
            DeferredEventReason.UNVERIFIED_CONTENT, "synthetic-opaque-deferred-before-reserve")
        store.transaction(scope) { (this as StagedSyncTransaction).defer(deferred) }
        assertNull(store.prepareTextUpdateAcknowledgement(result()))
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)!!.state)
        assertPreparation(preparation, store.localTextEditPreparation(scope, base.eventId))
        assertEquals(deferred, store.transaction(scope) { (this as StagedSyncTransaction).deferred(base.eventId) })
        assertEquals(0, rowCount(store, "text_update_acknowledgements"))
    }

    @Test fun incompatibleResultCannotReplaceAlreadyReservedReceipt() = fixture { store, _ ->
        val first = reserve(store)
        reject { store.prepareTextUpdateAcknowledgement(result(event = updated.copy(revision = Revision("other-result")))) }
        reject { store.prepareTextUpdateAcknowledgement(result(work.copy(protectedPayload = "other-wire"))) }
        assertEquals(first.record, store.textUpdateAcknowledgement(scope, base.eventId)); sourceIntact(store)
    }

    @Test fun changedOwnerOrPreparationAfterReservationCannotBeConsumed() = fixture { store, name ->
        val first = reserve(store)
        val witness = AppliedTextUpdateAcknowledgement(first.record.receipt)
        raw(name) { it.execSQL("UPDATE local_text_edit_preparations SET payload='changed-preparation'") }
        assertFalse(store.restoreTextUpdateAcknowledgement(witness))
        assertEquals(base, store.projectedBaseline(scope, base.eventId)); assertEquals(1, rowCount(store, "local_edits"))
        raw(name) { db ->
            db.execSQL("UPDATE local_text_edit_preparations SET payload=?", arrayOf(preparation.protectedPayload))
            db.execSQL("UPDATE local_edits SET mutation='changed-owner'")
        }
        assertFalse(store.restoreTextUpdateAcknowledgement(witness))
        assertEquals(1, rowCount(store, "local_text_edit_preparations"))
        assertEquals(first.record, store.textUpdateAcknowledgement(scope, base.eventId))
    }

    @Test fun missingOrDifferentReservedNonceNeverRestoresAnything() = fixture { store, _ ->
        val unreserved = TextUpdateAcknowledgementReceipt.of(result(), UUID.randomUUID().toString())
        assertFalse(store.restoreTextUpdateAcknowledgement(AppliedTextUpdateAcknowledgement(unreserved)))
        val first = reserve(store)
        val foreign = first.record.receipt.copy(projection = first.record.receipt.projection.copy(nonce = UUID.randomUUID().toString()))
        assertFalse(store.restoreTextUpdateAcknowledgement(AppliedTextUpdateAcknowledgement(foreign)))
        sourceIntact(store); assertEquals(first.record, store.textUpdateAcknowledgement(scope, base.eventId))
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
        store.writableDatabase.execSQL("CREATE TRIGGER reject_title_reservation AFTER INSERT ON text_update_acknowledgements BEGIN SELECT RAISE(ABORT,'fixture'); END")
        reject { reserve(store) }
        assertNull(store.textUpdateAcknowledgement(scope, base.eventId)); sourceIntact(store)
        store.writableDatabase.execSQL("DROP TRIGGER reject_title_reservation")
        val first = reserve(store)
        store.writableDatabase.execSQL("CREATE TRIGGER reject_title_retirement BEFORE DELETE ON local_edits BEGIN SELECT RAISE(ABORT,'fixture'); END")
        reject { store.restoreTextUpdateAcknowledgement(AppliedTextUpdateAcknowledgement(first.record.receipt)) }
        sourceIntact(store); assertEquals(base, store.transaction(scope) { remote(base.eventId) })
        assertEquals(first.record, store.textUpdateAcknowledgement(scope, base.eventId))
    }

    @Test fun leaseLossAndCancellationBeforeCommitDoNotAdmitFreshReceiptOrRetireWork() = fixture { store, _ ->
        var calls = 0
        store.admitTransaction = { ++calls == 1 }
        reject { reserve(store) }
        store.admitTransaction = { true }; assertNull(store.textUpdateAcknowledgement(scope, base.eventId)); sourceIntact(store)
        val first = reserve(store)
        val child = Job(currentCoroutineContext()[Job]); calls = 0
        store.admitTransaction = { if (++calls == 2) child.cancel(); true }
        try { withContext(child) { store.restoreTextUpdateAcknowledgement(AppliedTextUpdateAcknowledgement(first.record.receipt)) }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        finally { store.admitTransaction = { true } }
        sourceIntact(store); assertEquals(first.record, store.textUpdateAcknowledgement(scope, base.eventId))
    }

    @Test fun malformedHistoricalReceiptIsRejectedWithoutReinterpretingCurrentSource() = fixture { store, name ->
        reserve(store)
        raw(name) { db -> db.update("text_update_acknowledgements", ContentValues().apply { put("receipt", "not-json") }, null, null) }
        reject { store.textUpdateAcknowledgement(scope, base.eventId) }
        reject { store.prepareTextUpdateAcknowledgement(result()) }
        sourceIntact(store)
    }

    @Test fun capturedProvenanceAndDistinctOrdinaryProjectionCannotBeBypassed() = fixture { store, _ ->
        store.writableDatabase.execSQL("INSERT INTO captured_create_handoffs(scope,event,nonce,protocol,ack_nonce,ack_fingerprint,fingerprint,state,waiting) SELECT scope,event,?,1,?,?,?,'PENDING',0 FROM projection_bases",
            arrayOf(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "synthetic-ack", "synthetic-frame"))
        assertNull(store.prepareTextUpdateAcknowledgement(result())); sourceIntact(store)
        store.writableDatabase.execSQL("DELETE FROM captured_create_handoffs")
        store.writableDatabase.execSQL("INSERT INTO projection_receipts(scope,event,nonce,payload,adapter,version,previous_base,protocol) SELECT scope,event,?,payload,?,2,payload,1 FROM projection_bases",
            arrayOf(UUID.randomUUID().toString(), format.id))
        val original = checkNotNull(store.projectionReceipt(scope, base.eventId))
        val payload = store.readableDatabase.rawQuery("SELECT payload FROM projection_receipts", null).use {
            check(it.moveToFirst()); it.getString(0)
        }
        val otherRevision = JSONObject(payload).put("revision", "synthetic-distinct-revision").toString()
        val otherContent = JSONObject(payload).put("title", "Synthetic distinct projection").toString()
        val represented = ProjectionRepresentationCodec.encode(ProjectedEventReference.direct(base))
        val distinctProfiles = listOf(
            ContentValues().apply { put("payload", otherRevision) },
            ContentValues().apply { put("payload", otherContent) },
            ContentValues().apply { put("previous_base", otherRevision) },
            ContentValues().apply { put("representation", represented) },
            ContentValues().apply { put("previous_representation", represented) },
        )
        val reset = ContentValues().apply {
            put("payload", payload); put("previous_base", payload)
            putNull("representation"); putNull("previous_representation")
        }
        for (profile in distinctProfiles) {
            store.writableDatabase.update("projection_receipts", reset, null, null)
            store.writableDatabase.update("projection_receipts", profile, null, null)
            val pending = checkNotNull(store.projectionReceipt(scope, base.eventId))
            assertNull(store.prepareTextUpdateAcknowledgement(result()))
            assertNull(store.textUpdateAcknowledgement(scope, base.eventId))
            assertEquals(pending, store.projectionReceipt(scope, base.eventId)); sourceIntact(store)
        }
        store.writableDatabase.update("projection_receipts", reset, null, null)
        val reserved = reserve(store)
        assertEquals(TextUpdateAcknowledgementState.PENDING, reserved.record.state)
        assertTrue(reserved.record.receipt.matches(result()))
        assertEquals(original, store.projectionReceipt(scope, base.eventId)); sourceIntact(store)
    }
}
