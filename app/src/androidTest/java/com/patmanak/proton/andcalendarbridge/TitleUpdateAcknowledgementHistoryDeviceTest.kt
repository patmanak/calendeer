// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
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
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated SQLite cycles with synthetic verified results and witnesses, never service/Provider proof. */
@RunWith(AndroidJUnit4::class)
class TitleUpdateAcknowledgementHistoryDeviceTest {
    private val scope = CalendarScope(AccountId("synthetic-title-history"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("synthetic-history-import", 1)
    private val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-original"),
        EventContent("Synthetic original", EventTime.AllDay(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 8)),
            "Synthetic preserved description", "Synthetic location", details = EventDetails()))
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private data class Cycle(val intent: LocalEditIntent, val preparation: LocalEditPreparation,
        val result: TitleUpdateVerifiedResult)
    private fun cycle(previous: RemoteChange.Upsert, number: Int, eventScope: CalendarScope = scope,
        attempt: MutationId = MutationId("synthetic-attempt-$number"),
        intentId: MutationId = MutationId("synthetic-intent-$number")): Cycle {
        val intent = LocalEditIntent(intentId, eventScope, format, previous,
            previous.content.copy(title = "Synthetic title $number"))
        val preparation = LocalEditPreparation(intent, Instant.parse("2026-10-07T00:00:00Z").plusSeconds(number.toLong()),
            AdapterFormat("synthetic-history-preparation", 2), "synthetic-sealed-preparation-$number")
        val record = TitleUpdateAttemptRecord(attempt, preparation, AdapterFormat("synthetic-history-wire", 1),
            "synthetic-sealed-wire-$number", TitleUpdateAttemptState.OUTCOME_UNKNOWN)
        val updated = previous.copy(revision = Revision("synthetic-verified-$number"), content = intent.content)
        return Cycle(intent, preparation, TitleUpdateVerifiedResult.of(record,
            TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(record), updated)))
    }
    private suspend fun stage(store: ImportStore, cycle: Cycle) {
        assertEquals(cycle.intent, store.captureLocalEdit(cycle.intent))
        assertTrue(store.retainLocalEditPreparation(cycle.preparation))
    }
    private suspend fun reserve(store: ImportStore, cycle: Cycle) = checkNotNull(store.prepareTitleUpdateAcknowledgement(cycle.result))
    private suspend fun settle(store: ImportStore, cycle: Cycle): TitleUpdateAcknowledgementRecord {
        val receipt = reserve(store, cycle).record.receipt
        assertTrue(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(receipt)))
        return TitleUpdateAcknowledgementRecord(receipt, TitleUpdateAcknowledgementState.RESTORED)
    }
    private fun fixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val name = "title-history-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic history fixture")))
            store.setEnabled(scope, true)
            store.transaction(scope) { checkpoint = PageCheckpoint(scope, format); putRemote(base); putProjection(ProjectionIntent(scope, base)) }
            assertEquals(1, store.acknowledge(ProjectionIntent(scope, base)))
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private fun rows(store: ImportStore, table: String) = store.readableDatabase.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use {
        buildList { while (it.moveToNext()) add(List(it.columnCount) { column -> if (it.isNull(column)) null else it.getString(column) }) }
    }
    private fun snapshot(store: ImportStore) = listOf("records", "projection_bases", "local_edits", "local_edit_preparations",
        "title_update_acknowledgements", "title_update_acknowledgement_history").associateWith { rows(store, it) }
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected immutable history rejection") }
        catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
        catch (_: SQLiteException) { }
    }

    @Test fun secondCycleReservesFreshSlotAndOldAttemptReopensWithoutConsumingNewOwner() = fixture { store, name ->
        val first = cycle(base, 1); stage(store, first); val archived = settle(store, first)
        val next = cycle(first.result.event, 2); stage(store, next)
        val reserved = store.prepareTitleUpdateAcknowledgement(next.result)
        assertNotNull("A restored event must admit a new qualified cycle", reserved)
        assertTrue(reserved!!.fresh)
        assertNotEquals(archived.receipt.projection.nonce, reserved.record.receipt.projection.nonce)
        val before = snapshot(store)
        assertEquals(archived, store.titleUpdateAcknowledgement(scope, base.eventId, first.result.attempt.id))
        assertEquals(reserved.record, store.titleUpdateAcknowledgement(scope, base.eventId))
        assertTrue(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(archived.receipt)))
        assertEquals(TitleUpdateAcknowledgementReservation(archived, false), store.prepareTitleUpdateAcknowledgement(first.result))
        assertEquals(before, snapshot(store))
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(archived, reopened.titleUpdateAcknowledgement(scope, base.eventId, first.result.attempt.id))
            assertEquals(reserved.record, reopened.titleUpdateAcknowledgement(scope, base.eventId, next.result.attempt.id))
            assertEquals(next.intent, reopened.localEdit(scope, base.eventId))
            assertEquals(next.preparation, reopened.localEditPreparation(scope, base.eventId))
        }
    }

    @Test fun restorationArchivesExactCanonicalReceiptInSameDurableCommit() = fixture { store, name ->
        val first = cycle(base, 1); stage(store, first)
        val reserved = reserve(store, first)
        val canonical = rows(store, "title_update_acknowledgements").single()[3]
        assertTrue(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(reserved.record.receipt)))
        val history = rows(store, "title_update_acknowledgement_history").single()
        assertTrue(history.contains(canonical)); assertFalse(history.any { it?.contains(first.preparation.protectedPayload) == true })
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(TitleUpdateAcknowledgementState.RESTORED,
                reopened.titleUpdateAcknowledgement(scope, base.eventId, first.result.attempt.id)!!.state)
            assertEquals(history, rows(reopened, "title_update_acknowledgement_history").single())
        }
    }

    @Test fun pendingCycleCannotBeReplacedByAnotherAttemptOrChangedResultIdentity() = fixture { store, _ ->
        val first = cycle(base, 1); stage(store, first); val pending = reserve(store, first)
        val another = cycle(base, 2, intentId = first.intent.id)
        reject { store.prepareTitleUpdateAcknowledgement(another.result) }
        val changedRecord = first.result.record.copy(protectedPayload = "synthetic-different-wire")
        val changed = TitleUpdateVerifiedResult.of(changedRecord,
            TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(changedRecord), first.result.event))
        reject { store.prepareTitleUpdateAcknowledgement(changed) }
        assertEquals(pending.record, store.titleUpdateAcknowledgement(scope, base.eventId))
        assertEquals(first.intent, store.localEdit(scope, base.eventId))
    }

    @Test fun reusedAttemptAndHistoricIntentCannotStartNewCycle() = fixture { store, _ ->
        val first = cycle(base, 1); stage(store, first); settle(store, first)
        val reused = cycle(first.result.event, 2, intentId = first.intent.id)
        stage(store, reused)
        reject { store.prepareTitleUpdateAcknowledgement(reused.result) }
        val changedAttempt = cycle(first.result.event, 3, attempt = first.result.attempt.id, intentId = first.intent.id)
        reject { store.prepareTitleUpdateAcknowledgement(changedAttempt.result) }
        assertEquals(reused.intent, store.localEdit(scope, base.eventId))
        assertEquals(1, rows(store, "title_update_acknowledgement_history").size)
    }

    @Test fun inactiveFreshSourceCannotRotateRestoredSlotOrArchiveUnqualifiedWork() = fixture { store, _ ->
        val first = cycle(base, 1); stage(store, first); val archived = settle(store, first)
        val next = cycle(first.result.event, 2); stage(store, next)
        store.setEnabled(scope, false)
        val before = snapshot(store)
        assertNull(store.prepareTitleUpdateAcknowledgement(next.result))
        assertEquals(before, snapshot(store))
        assertEquals(archived, store.titleUpdateAcknowledgement(scope, base.eventId))
    }

    @Test fun archiveCommitFailureRollsBackBaselineCacheOwnerAndReceiptState() = fixture { store, _ ->
        val first = cycle(base, 1); stage(store, first); val pending = reserve(store, first)
        store.writableDatabase.execSQL("CREATE TRIGGER reject_title_history BEFORE INSERT ON title_update_acknowledgement_history BEGIN SELECT RAISE(ABORT,'fixture'); END")
        val before = snapshot(store)
        reject { store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(pending.record.receipt)) }
        assertEquals(before, snapshot(store))
        store.writableDatabase.execSQL("DROP TRIGGER reject_title_history")
        assertTrue(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(pending.record.receipt)))
    }

    @Test fun replacementCommitFailurePreservesArchiveCurrentSlotAndFreshSource() = fixture { store, _ ->
        val first = cycle(base, 1); stage(store, first); settle(store, first)
        val next = cycle(first.result.event, 2); stage(store, next)
        store.writableDatabase.execSQL("CREATE TRIGGER reject_title_cycle BEFORE INSERT ON title_update_acknowledgements BEGIN SELECT RAISE(ABORT,'fixture'); END")
        val before = snapshot(store)
        reject { store.prepareTitleUpdateAcknowledgement(next.result) }
        assertEquals(before, snapshot(store))
        store.writableDatabase.execSQL("DROP TRIGGER reject_title_cycle")
        assertTrue(reserve(store, next).fresh)
    }

    @Test fun legacyVersionSixteenRestoredSlotIsArchivedWithoutReencodingBeforeRotation() = fixture { store, name ->
        val first = cycle(base, 1); stage(store, first); val archived = settle(store, first)
        val canonical = rows(store, "title_update_acknowledgements").single()[3]
        store.writableDatabase.execSQL("DROP TABLE title_update_acknowledgement_history")
        store.writableDatabase.version = 16; store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(17, reopened.readableDatabase.version)
            assertTrue(rows(reopened, "title_update_acknowledgement_history").isEmpty())
            assertEquals(canonical, rows(reopened, "title_update_acknowledgements").single()[3])
            val next = cycle(first.result.event, 2); stage(reopened, next); assertTrue(reserve(reopened, next).fresh)
            assertTrue(rows(reopened, "title_update_acknowledgement_history").single().contains(canonical))
            assertEquals(archived, reopened.titleUpdateAcknowledgement(scope, base.eventId, first.result.attempt.id))
        }
    }

    @Test fun independentHelpersRotateToExactlyOneFreshNonce() = fixture { store, name ->
        val first = cycle(base, 1); stage(store, first); settle(store, first)
        val next = cycle(first.result.event, 2); stage(store, next)
        ImportStore(context, name).use { other ->
            val gate = CompletableDeferred<Unit>()
            val reservations = coroutineScope {
                listOf(store, other).map { helper -> async(Dispatchers.IO) { gate.await(); reserve(helper, next) } }
                    .also { gate.complete(Unit) }.awaitAll()
            }
            assertEquals(1, reservations.count { it.fresh }); assertEquals(reservations[0].record, reservations[1].record)
            assertEquals(1, rows(store, "title_update_acknowledgement_history").size)
        }
    }

    @Test fun malformedArchiveFailsClosedWithoutRebuildingItFromNewOwner() = fixture { store, _ ->
        val first = cycle(base, 1); stage(store, first); val archived = settle(store, first)
        val next = cycle(first.result.event, 2); stage(store, next); reserve(store, next)
        store.writableDatabase.execSQL("UPDATE title_update_acknowledgement_history SET receipt='not-json'")
        val before = snapshot(store)
        reject { store.titleUpdateAcknowledgement(scope, base.eventId, first.result.attempt.id) }
        reject { store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(archived.receipt)) }
        reject { store.prepareTitleUpdateAcknowledgement(first.result) }
        assertEquals(before, snapshot(store))
    }

    @Test fun legacyVersionSixteenPendingReceiptRemainsUnarchivedAndImmutable() = fixture { store, name ->
        val first = cycle(base, 1); stage(store, first); val pending = reserve(store, first)
        val canonical = rows(store, "title_update_acknowledgements")
        store.writableDatabase.execSQL("DROP TABLE title_update_acknowledgement_history")
        store.writableDatabase.version = 16; store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(17, reopened.readableDatabase.version)
            assertEquals(canonical, rows(reopened, "title_update_acknowledgements"))
            assertTrue(rows(reopened, "title_update_acknowledgement_history").isEmpty())
            assertEquals(pending.copy(fresh = false), reserve(reopened, first))
            assertEquals(first.preparation, reopened.localEditPreparation(scope, base.eventId))
        }
    }

    @Test fun eventCannotReuseAnotherEventsArchivedAttemptOrLocalIntent() = fixture { store, _ ->
        val first = cycle(base, 1); stage(store, first); settle(store, first)
        val otherBase = base.copy(eventId = EventId("synthetic-other-event"))
        store.transaction(scope) { putRemote(otherBase); putProjection(ProjectionIntent(scope, otherBase)) }
        assertEquals(1, store.acknowledge(ProjectionIntent(scope, otherBase)))
        val other = cycle(otherBase, 2, attempt = first.result.attempt.id)
        stage(store, other)
        reject { reserve(store, other) }
        val sameIntent = cycle(otherBase, 3, intentId = first.intent.id)
        reject { store.prepareTitleUpdateAcknowledgement(sameIntent.result) }
        assertEquals(other.intent, store.localEdit(scope, otherBase.eventId))
        assertNull(store.titleUpdateAcknowledgement(scope, otherBase.eventId))
    }

    @Test fun identityReuseIsScopedAndDifferentCalendarMayKeepItsOwnCycle() = fixture { store, _ ->
        val first = cycle(base, 1); stage(store, first); settle(store, first)
        val otherScope = scope.copy(calendar = CalendarId("synthetic-other-calendar"))
        store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic fixture"),
            CalendarSummary(otherScope.calendar, "Synthetic other fixture")))
        store.setEnabled(otherScope, true)
        store.transaction(otherScope) { checkpoint = PageCheckpoint(otherScope, format); putRemote(base); putProjection(ProjectionIntent(otherScope, base)) }
        assertEquals(1, store.acknowledge(ProjectionIntent(otherScope, base)))
        val other = cycle(base, 1, eventScope = otherScope); stage(store, other)
        assertTrue(reserve(store, other).fresh)
        assertEquals(first.result.attempt.id, other.result.attempt.id)
        assertEquals(other.intent, store.localEdit(otherScope, base.eventId))
    }

    @Test fun changedArchiveNonceOrAttemptColumnCannotSubstituteHistoricalIdentity() = fixture { store, _ ->
        val first = cycle(base, 1); stage(store, first); val archived = settle(store, first)
        val next = cycle(first.result.event, 2); stage(store, next); reserve(store, next)
        store.writableDatabase.execSQL("UPDATE title_update_acknowledgement_history SET nonce=?", arrayOf(UUID.randomUUID().toString()))
        reject { store.titleUpdateAcknowledgement(scope, base.eventId, first.result.attempt.id) }
        reject { store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(archived.receipt)) }
        store.writableDatabase.execSQL("UPDATE title_update_acknowledgement_history SET nonce=?,attempt='synthetic-wrong-attempt'",
            arrayOf(archived.receipt.projection.nonce))
        reject { store.titleUpdateAcknowledgement(scope, base.eventId, MutationId("synthetic-wrong-attempt")) }
        assertEquals(next.intent, store.localEdit(scope, base.eventId))
    }

    @Test fun leaseLossBeforeRestoreCommitRollsBackArchiveAndExactOriginalSource() = fixture { store, _ ->
        val first = cycle(base, 1); stage(store, first); val pending = reserve(store, first)
        val before = snapshot(store); var calls = 0
        store.admitTransaction = { ++calls == 1 }
        try { reject { store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(pending.record.receipt)) } }
        finally { store.admitTransaction = { true } }
        assertEquals(before, snapshot(store))
        assertTrue(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(pending.record.receipt)))
    }

    @Test fun transferredCreateCyclesRetainCompleteOldFramesWhileNewOwnerWaits() = fixture { store, name ->
        val event = base.copy(eventId = EventId("synthetic-created-history-event"))
        val capture = LocalCreateIntent(LocalCreateReservation(MutationId("synthetic-create-capture"),
            LocalEventId(UUID(0, 901).toString()), scope, format, Instant.parse("2026-10-07T00:00:00Z")), event.content)
        val submission = LocalCreateSubmission(LocalCreateSubmissionReservation(capture,
            MutationId(UUID(0, 902).toString()), AdapterFormat("synthetic-create-wire", 1)), "synthetic-opaque-create")
        val creation = CapturedCreateBaseline(CapturedCreateAttachmentReceipt(CapturedCreateBinding(
            CapturedCreateDispatchIdentity.of(submission), event.eventId, event.revision), UUID(0, 903).toString()), event)
        assertTrue(store.retainCapturedBaseline(creation, AppliedCapturedCreateAttachment(creation.receipt)))
        store.transaction(scope) { putRemote(event) }
        val ack = store.reserveCapturedAcknowledgement(CapturedCreateAcknowledgementReceipt(creation, UUID(0, 904).toString())).record.receipt
        store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(ack))
        val handoff = store.reserveCapturedHandoff(CapturedCreateHandoffReceipt(ack, UUID(0, 905).toString()))
        val initial = store.retainCapturedHandoff(handoff.frame.receipt, CapturedCreateCleanWitness(ack)).frame
        val first = cycle(initial.base, 11)
        assertEquals(LocalEditState.PAUSED, store.retainCapturedHandoffLocalChange(
            CapturedHandoffCurrent(initial, first.intent.content, false), first.intent.id))
        assertTrue(store.retainLocalEditPreparation(first.preparation))
        val firstReservation = checkNotNull(store.prepareTitleUpdateAcknowledgement(first.result, initial))
        val canonical = rows(store, "title_update_acknowledgements").single()[3]
        assertTrue(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(firstReservation.record.receipt)))
        val advanced = store.capturedHandoff(scope, event.eventId)!!.frame
        val next = cycle(advanced.base, 12)
        assertEquals(LocalEditState.PAUSED, store.retainCapturedHandoffLocalChange(
            CapturedHandoffCurrent(advanced, next.intent.content, false), next.intent.id))
        assertTrue(store.retainLocalEditPreparation(next.preparation))
        val pending = checkNotNull(store.prepareTitleUpdateAcknowledgement(next.result, advanced))
        assertTrue(pending.fresh)
        val before = snapshot(store); val frameBefore = rows(store, "captured_create_handoffs")
        val old = store.titleUpdateAcknowledgement(scope, event.eventId, first.result.attempt.id)!!
        assertEquals(initial, old.receipt.handoff)
        assertTrue(rows(store, "title_update_acknowledgement_history").single().contains(canonical))
        assertTrue(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(old.receipt)))
        assertEquals(before, snapshot(store)); assertEquals(frameBefore, rows(store, "captured_create_handoffs"))
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(old, reopened.titleUpdateAcknowledgement(scope, event.eventId, first.result.attempt.id))
            assertEquals(advanced, reopened.capturedHandoff(scope, event.eventId)!!.frame)
            assertTrue(reopened.capturedHandoff(scope, event.eventId)!!.waiting)
            assertEquals(next.preparation, reopened.localEditPreparation(scope, event.eventId))
        }
    }

    @Test fun sameAttemptCurrentAndArchiveDivergenceFailsClosed() = fixture { store, _ ->
        val first = cycle(base, 1); stage(store, first); val archived = settle(store, first)
        store.writableDatabase.execSQL("UPDATE title_update_acknowledgements SET state='PENDING'")
        reject { store.titleUpdateAcknowledgement(scope, base.eventId, first.result.attempt.id) }
        reject { store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(archived.receipt)) }
        reject { store.prepareTitleUpdateAcknowledgement(first.result) }
        assertNull(store.localEdit(scope, base.eventId))
        assertEquals(first.result.event, store.projectedBaseline(scope, base.eventId))
    }

    @Test fun oldArchiveReplayDoesNotDecodeOrChangeCorruptNewCurrentSlot() = fixture { store, _ ->
        val first = cycle(base, 1); stage(store, first); val archived = settle(store, first)
        val next = cycle(first.result.event, 2); stage(store, next); reserve(store, next)
        store.writableDatabase.execSQL("UPDATE title_update_acknowledgements SET receipt='not-json'")
        val before = snapshot(store)
        assertEquals(archived, store.titleUpdateAcknowledgement(scope, base.eventId, first.result.attempt.id))
        assertTrue(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(archived.receipt)))
        assertEquals(TitleUpdateAcknowledgementReservation(archived, false), store.prepareTitleUpdateAcknowledgement(first.result))
        assertEquals(before, snapshot(store))
        reject { store.titleUpdateAcknowledgement(scope, base.eventId) }
    }
}
