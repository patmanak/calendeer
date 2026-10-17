// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.data.importing.LocalTextPreparationAdmission
import com.patmanak.proton.andcalendarbridge.data.importing.LocalTimePreparationAdmission
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in SQLite-only fixtures. Synthetic applied witnesses establish SQL behavior, never native ACK. */
@RunWith(AndroidJUnit4::class)
class TimeUpdateAcknowledgementStoreDeviceTest {
    private val scope = CalendarScope(AccountId("time-ack-fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("synthetic-time-import", 2)
    private val originalTime = EventTime.Timed(Instant.parse("2030-03-30T10:00:00Z"),
        Instant.parse("2030-03-30T11:00:00Z"), ZoneId.of("Europe/Paris"))
    private val replacementTime = originalTime.copy(start = Instant.parse("2030-03-31T10:00:00Z"),
        end = Instant.parse("2030-03-31T12:00:00Z"))
    private val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-base"),
        EventContent("Unchanged\r\ntitle", originalTime, "Unchanged\r\ndescription", "Unchanged\r\nlocation", details = EventDetails()))
    private val intent = LocalEditIntent(MutationId("synthetic-time-intent"), scope, format, base,
        base.content.copy(time = replacementTime))
    private val preparation = LocalTimeEditPreparation(intent, Instant.parse("2030-03-01T12:00:01Z"),
        AdapterFormat("synthetic-time-preparation", 3), "synthetic-opaque-time-preparation")
    private val work = TimeUpdateAttemptRecord(MutationId("synthetic-time-attempt"), preparation,
        AdapterFormat("synthetic-time-wire", 1), "synthetic-opaque-time-wire", TimeUpdateAttemptState.OUTCOME_UNKNOWN)
    private fun canonical(content: EventContent) = content.copy(title = content.title.replace("\r\n", "\n"),
        description = content.description.replace("\r\n", "\n"), location = content.location.replace("\r\n", "\n"))
    private val updated = base.copy(revision = Revision("synthetic-time-verified"), content = canonical(intent.content))
    private fun result(record: TimeUpdateAttemptRecord = work, event: RemoteChange.Upsert = updated) =
        TimeUpdateVerifiedResult.of(record, TimeUpdateSubmissionObservation.Verified(record, event))
    private fun capsule(value: LocalEditIntent = intent, timestamp: Instant = preparation.timestamp,
        format: AdapterFormat = preparation.format, payload: String = preparation.protectedPayload) =
        LocalTimeEditPreparation(value, timestamp, format, payload)
    private fun assertPreparation(expected: LocalTimeEditPreparation, actual: LocalTimeEditPreparation?) {
        assertNotNull(actual); actual!!
        assertEquals(expected.intent, actual.intent); assertEquals(expected.timestamp, actual.timestamp)
        assertEquals(expected.format, actual.format); assertEquals(expected.protectedPayload, actual.protectedPayload)
    }
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun fixture(redundant: Boolean = false, block: suspend (ImportStore, String) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val name = "time-ack-fixture-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic time acknowledgement")))
            store.setEnabled(scope, true)
            store.transaction(scope) { checkpoint = PageCheckpoint(scope, format); putRemote(base); putProjection(ProjectionIntent(scope, base)) }
            assertEquals(1, store.acknowledge(ProjectionIntent(scope, base)))
            if (redundant) {
                store.transaction(scope) { putProjection(ProjectionIntent(scope, base)) }
                val ordinary = store.prepareProjection(ProjectionIntent(scope, base), format)
                assertEquals(base, ordinary.change); assertEquals(base, ordinary.previousBase)
            }
            assertEquals(intent, store.captureLocalEdit(intent))
            assertEquals(LocalTimePreparationAdmission.STORED, store.retainCurrentLocalTimePreparation(preparation))
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun reserve(store: ImportStore) = checkNotNull(store.prepareTimeUpdateAcknowledgement(result()))
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected closed time acknowledgement refusal") }
        catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
        catch (_: SQLiteException) { }
    }
    private fun rows(db: SQLiteDatabase, table: String): List<List<String?>> = db.rawQuery("SELECT * FROM $table ORDER BY rowid", null).use { row ->
        buildList { while (row.moveToNext()) add(List(row.columnCount) { if (row.isNull(it)) null else "${row.getType(it)}:${row.getString(it)}" }) }
    }
    private fun pendingFlag(store: ImportStore) = store.readableDatabase.rawQuery("SELECT pending FROM records WHERE event=?",
        arrayOf(base.eventId.value)).use { assertTrue(it.moveToFirst()); it.getInt(0) }
    private suspend fun sourceIntact(store: ImportStore) {
        assertEquals(intent, store.localEdit(scope, base.eventId))
        assertPreparation(preparation, store.localTimeEditPreparation(scope, base.eventId))
        assertEquals(base, store.projectedBaseline(scope, base.eventId))
    }

    @Test fun durableReservationRetainsRawIntentAndCanonicalResultBeforeWitnessRestores() = fixture { store, name ->
        val first = reserve(store)
        assertTrue(first.fresh); assertEquals(TimeUpdateAcknowledgementState.PENDING, first.record.state)
        assertEquals(intent, first.record.receipt.intent); assertEquals(updated, first.record.receipt.projection.change)
        assertTrue(first.record.receipt.matches(result())); sourceIntact(store)
        assertEquals(first.copy(fresh = false), store.prepareTimeUpdateAcknowledgement(result()))
        store.readableDatabase.rawQuery("SELECT receipt FROM time_update_acknowledgements", null).use {
            assertTrue(it.moveToFirst()); val encoded = it.getString(0)
            assertFalse(encoded.contains(preparation.protectedPayload)); assertFalse(encoded.contains(work.protectedPayload))
        }
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(first.record, reopened.timeUpdateAcknowledgement(scope, base.eventId)); sourceIntact(reopened)
            assertEquals(first.copy(fresh = false), reopened.prepareTimeUpdateAcknowledgement(result()))
            val witness = AppliedTimeUpdateAcknowledgement(first.record.receipt)
            assertTrue(reopened.restoreTimeUpdateAcknowledgement(witness))
            assertEquals(updated, reopened.projectedBaseline(scope, base.eventId))
            assertEquals(updated, reopened.transaction(scope) { remote(base.eventId) }); assertEquals(0, pendingFlag(reopened))
            assertNull(reopened.localEdit(scope, base.eventId)); assertNull(reopened.localTimeEditPreparation(scope, base.eventId))
            assertEquals(TimeUpdateAcknowledgementState.RESTORED, reopened.timeUpdateAcknowledgement(scope, base.eventId)!!.state)
            assertTrue(reopened.restoreTimeUpdateAcknowledgement(witness))
        }
    }

    @Test fun exactResultEchoMayCloseConflictButRawCrLfEchoCannotStandInForActualReadback() = fixture { store, _ ->
        store.transaction(scope) { putRemote(updated.copy(content = intent.content)) }
        assertEquals(LocalEditState.CONFLICT, store.localEdit(scope, base.eventId)!!.state)
        assertNull(store.prepareTimeUpdateAcknowledgement(result()))
        store.transaction(scope) { putRemote(updated); putProjection(ProjectionIntent(scope, updated)) }
        val first = reserve(store)
        assertTrue(store.restoreTimeUpdateAcknowledgement(AppliedTimeUpdateAcknowledgement(first.record.receipt)))
        assertEquals(updated, store.projectedBaseline(scope, base.eventId)); assertEquals(0, pendingFlag(store))
        assertNull(store.localEdit(scope, base.eventId))
    }

    @Test fun historicalReplayPreservesSecondReservationNewerEditCapsuleAndRemoteCache() = fixture { store, name ->
        val first = reserve(store); val witness = AppliedTimeUpdateAcknowledgement(first.record.receipt)
        assertTrue(store.restoreTimeUpdateAcknowledgement(witness))
        val next = intent.copy(id = MutationId("second-time-intent"), base = updated,
            content = updated.content.copy(time = replacementTime.copy(end = replacementTime.end.plusSeconds(60))))
        assertEquals(next, store.captureLocalEdit(next)); val nextCapsule = capsule(next, preparation.timestamp.plusSeconds(1))
        assertEquals(LocalTimePreparationAdmission.STORED, store.retainCurrentLocalTimePreparation(nextCapsule))
        val nextWork = work.copy(id = MutationId("second-time-request"), preparation = nextCapsule)
        val actual = updated.copy(revision = Revision("second-time-result"), content = next.content)
        val second = checkNotNull(store.prepareTimeUpdateAcknowledgement(result(nextWork, actual)))
        assertTrue(second.fresh)
        val newer = actual.copy(revision = Revision("later-remote"), content = actual.content.copy(title = "Later remote title"))
        store.transaction(scope) { putRemote(newer); putProjection(ProjectionIntent(scope, newer)) }
        val owner = store.localEdit(scope, base.eventId)
        val cached = rows(store.readableDatabase, "records")
        assertTrue(store.restoreTimeUpdateAcknowledgement(witness))
        assertEquals(second.record, store.timeUpdateAcknowledgement(scope, base.eventId))
        assertEquals(first.record.copy(state = TimeUpdateAcknowledgementState.RESTORED), store.timeUpdateAcknowledgement(scope, base.eventId, work.id))
        assertEquals(owner, store.localEdit(scope, base.eventId)); assertPreparation(nextCapsule, store.localTimeEditPreparation(scope, base.eventId))
        assertEquals(updated, store.projectedBaseline(scope, base.eventId)); assertEquals(cached, rows(store.readableDatabase, "records"))
        store.close(); ImportStore(context, name).use { reopened ->
            assertTrue(reopened.restoreTimeUpdateAcknowledgement(witness))
            assertEquals(second.record, reopened.timeUpdateAcknowledgement(scope, base.eventId))
            assertEquals(owner, reopened.localEdit(scope, base.eventId)); assertPreparation(nextCapsule, reopened.localTimeEditPreparation(scope, base.eventId))
            assertEquals(cached, rows(reopened.readableDatabase, "records"))
        }
    }

    @Test fun distinctNewRemoteCacheIsPreservedAndRearmedWhenOriginalOwnerRetires() = fixture { store, _ ->
        val first = reserve(store)
        val newer = updated.copy(revision = Revision("remote-after-reservation"), content = updated.content.copy(title = "Later title"))
        store.transaction(scope) { putRemote(newer); putProjection(ProjectionIntent(scope, newer)) }
        assertEquals(0, pendingFlag(store))
        assertTrue(store.restoreTimeUpdateAcknowledgement(AppliedTimeUpdateAcknowledgement(first.record.receipt)))
        assertEquals(newer, store.transaction(scope) { remote(base.eventId) })
        assertEquals(listOf(ProjectionIntent(scope, newer)), store.pending(scope))
        assertEquals(updated, store.projectedBaseline(scope, base.eventId)); assertNull(store.localEdit(scope, base.eventId))
    }

    @Test fun explicitFullyHandedCreateOriginAdvancesOnceAndKeepsOriginalCreateEvidence() = fixture { store, name ->
        val original = base.copy(eventId = EventId("synthetic-transferred-event"))
        val create = LocalCreateIntent(LocalCreateReservation(MutationId("capture"), LocalEventId(UUID(0, 32).toString()),
            scope, format, preparation.timestamp), original.content)
        val submission = LocalCreateSubmission(LocalCreateSubmissionReservation(create, MutationId(UUID(0, 33).toString()),
            AdapterFormat("synthetic-create-wire", 1)), "opaque-create")
        val attachment = CapturedCreateAttachmentReceipt(CapturedCreateBinding(CapturedCreateDispatchIdentity.of(submission),
            original.eventId, original.revision), UUID.randomUUID().toString())
        val baseline = CapturedCreateBaseline(attachment, original)
        assertTrue(store.retainCapturedBaseline(baseline, AppliedCapturedCreateAttachment(attachment)))
        store.transaction(scope) { putRemote(original) }
        val ack = store.reserveCapturedAcknowledgement(CapturedCreateAcknowledgementReceipt(baseline, UUID.randomUUID().toString())).record.receipt
        store.retainCapturedAcknowledgement(AppliedCapturedCreateAcknowledgement(ack))
        val handoff = store.reserveCapturedHandoff(CapturedCreateHandoffReceipt(ack, UUID.randomUUID().toString()))
        val frame = store.retainCapturedHandoff(handoff.frame.receipt, CapturedCreateCleanWitness(ack)).frame
        val local = original.content.copy(time = replacementTime)
        val edit = LocalEditIntent(MutationId("transferred-time-edit"), scope, format, frame.base, local)
        assertEquals(LocalEditState.PAUSED, store.retainCapturedHandoffLocalChange(CapturedHandoffCurrent(frame, local, false), edit.id))
        val sealed = capsule(edit)
        assertEquals(LocalTimePreparationAdmission.STORED, store.retainCurrentLocalTimePreparation(sealed))
        val record = work.copy(id = MutationId("transferred-time-request"), preparation = sealed)
        val actual = original.copy(revision = Revision("transferred-time-result"), content = canonical(local))
        val proof = result(record, actual)
        assertNull(store.prepareTimeUpdateAcknowledgement(proof))
        val first = checkNotNull(store.prepareTimeUpdateAcknowledgement(proof, frame))
        val origins = listOf("captured_create_bases", "captured_create_acknowledgements").associateWith { rows(store.readableDatabase, it) }
        val witness = AppliedTimeUpdateAcknowledgement(first.record.receipt)
        assertTrue(store.restoreTimeUpdateAcknowledgement(witness))
        assertEquals(CapturedCreateHandoffFrame(frame.receipt, actual, first.record.receipt.projection), store.capturedHandoff(scope, original.eventId)!!.frame)
        assertFalse(store.capturedHandoff(scope, original.eventId)!!.waiting)
        assertEquals(origins, origins.keys.associateWith { rows(store.readableDatabase, it) })
        store.close(); ImportStore(context, name).use { reopened ->
            assertTrue(reopened.restoreTimeUpdateAcknowledgement(witness))
            assertEquals(frame, reopened.timeUpdateAcknowledgement(scope, original.eventId, record.id)!!.receipt.handoff)
            assertEquals(origins, origins.keys.associateWith { rows(reopened.readableDatabase, it) })
        }
    }

    @Test fun completeCapsuleBindingAndIncompatibleFamilyAreRequiredForFreshReservation() = fixture { store, _ ->
        val alternatives = listOf(capsule(timestamp = preparation.timestamp.plusSeconds(1)),
            capsule(format = preparation.format.copy(version = 4)), capsule(payload = "different-time-capsule"))
        for (other in alternatives) assertNull(store.prepareTimeUpdateAcknowledgement(result(work.copy(preparation = other))))
        for (family in listOf("local_edit_preparations", "local_text_edit_preparations")) {
            store.writableDatabase.execSQL("INSERT INTO $family SELECT * FROM local_time_edit_preparations")
            assertNull(store.prepareTimeUpdateAcknowledgement(result()))
            store.writableDatabase.execSQL("DELETE FROM $family")
        }
        sourceIntact(store); assertTrue(rows(store.readableDatabase, "time_update_acknowledgements").isEmpty())
    }

    @Test fun changedOwnerCapsuleNonceOrResultCannotConsumeReservedSource() = fixture { store, _ ->
        val unreserved = TimeUpdateAcknowledgementReceipt.of(result(), UUID.randomUUID().toString())
        assertFalse(store.restoreTimeUpdateAcknowledgement(AppliedTimeUpdateAcknowledgement(unreserved)))
        val first = reserve(store)
        val foreign = first.record.receipt.copy(projection = first.record.receipt.projection.copy(nonce = UUID.randomUUID().toString()))
        assertFalse(store.restoreTimeUpdateAcknowledgement(AppliedTimeUpdateAcknowledgement(foreign)))
        reject { store.prepareTimeUpdateAcknowledgement(result(event = updated.copy(revision = Revision("other-result")))) }
        reject { store.prepareTimeUpdateAcknowledgement(result(work.copy(protectedPayload = "other-wire"))) }
        val witness = AppliedTimeUpdateAcknowledgement(first.record.receipt)
        store.writableDatabase.execSQL("UPDATE local_time_edit_preparations SET payload='changed-capsule'")
        assertFalse(store.restoreTimeUpdateAcknowledgement(witness))
        store.writableDatabase.execSQL("UPDATE local_time_edit_preparations SET payload=?", arrayOf(preparation.protectedPayload))
        store.writableDatabase.execSQL("UPDATE local_edits SET mutation='changed-owner'")
        assertFalse(store.restoreTimeUpdateAcknowledgement(witness))
        assertEquals(base, store.projectedBaseline(scope, base.eventId))
        assertEquals(first.record, store.timeUpdateAcknowledgement(scope, base.eventId))
    }

    @Test fun disabledScopeAndCheckpointMismatchCannotAdmitButHistoricalReservationRemainsInspectable() = fixture { store, _ ->
        store.setEnabled(scope, false); assertNull(store.prepareTimeUpdateAcknowledgement(result()))
        store.setEnabled(scope, true)
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format.copy(version = 3)) }
        assertNull(store.prepareTimeUpdateAcknowledgement(result()))
        store.transaction(scope) { checkpoint = PageCheckpoint(scope, format) }
        val first = reserve(store)
        store.setEnabled(scope, false); store.remember(scope.account, emptyList())
        assertEquals(first.copy(fresh = false), store.prepareTimeUpdateAcknowledgement(result()))
        assertFalse(store.restoreTimeUpdateAcknowledgement(AppliedTimeUpdateAcknowledgement(first.record.receipt)))
        sourceIntact(store)
    }

    @Test fun redundantProjectionAndAllSourceRowsRollbackOnRestorationFault() = fixture(redundant = true) { store, name ->
        val ordinary = checkNotNull(store.projectionReceipt(scope, base.eventId))
        val first = reserve(store)
        val tables = listOf("records", "projection_bases", "projection_receipts", "local_edits", "local_time_edit_preparations", "time_update_acknowledgements")
        val before = tables.associateWith { rows(store.readableDatabase, it) }
        store.writableDatabase.execSQL("CREATE TRIGGER reject_time_retirement BEFORE DELETE ON local_edits BEGIN SELECT RAISE(ABORT,'synthetic time retirement fault'); END")
        val witness = AppliedTimeUpdateAcknowledgement(first.record.receipt)
        reject { store.restoreTimeUpdateAcknowledgement(witness) }
        assertEquals(before, tables.associateWith { rows(store.readableDatabase, it) })
        assertEquals(ordinary, store.projectionReceipt(scope, base.eventId)); store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(before, tables.associateWith { rows(reopened.readableDatabase, it) })
            reopened.writableDatabase.execSQL("DROP TRIGGER reject_time_retirement")
            assertTrue(reopened.restoreTimeUpdateAcknowledgement(witness))
            assertNull(reopened.projectionReceipt(scope, base.eventId)); assertEquals(updated, reopened.projectedBaseline(scope, base.eventId))
        }
    }

    @Test fun leaseLossAndCancelledCommitCannotReserveOrRetireSource() = fixture { store, _ ->
        var calls = 0; store.admitTransaction = { ++calls == 1 }
        reject { reserve(store) }
        store.admitTransaction = { true }; assertNull(store.timeUpdateAcknowledgement(scope, base.eventId)); sourceIntact(store)
        val first = reserve(store)
        val child = Job(currentCoroutineContext()[Job]); calls = 0
        store.admitTransaction = { if (++calls == 2) child.cancel(); true }
        try { withContext(child) { store.restoreTimeUpdateAcknowledgement(AppliedTimeUpdateAcknowledgement(first.record.receipt)) }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        finally { store.admitTransaction = { true } }
        sourceIntact(store); assertEquals(first.record, store.timeUpdateAcknowledgement(scope, base.eventId))
    }

    @Test fun independentHelpersReserveOnlyOneFreshNonce() = fixture { store, name ->
        ImportStore(context, name).use { other ->
            val gate = CompletableDeferred<Unit>()
            val reservations = coroutineScope { listOf(store, other).map { helper -> async(Dispatchers.IO) {
                gate.await(); reserve(helper)
            } }.also { gate.complete(Unit) }.awaitAll() }
            assertEquals(1, reservations.count { it.fresh }); assertEquals(reservations[0].record, reservations[1].record)
        }
    }

    @Test fun corruptReservedReceiptCannotBeReinterpretedAsFreshWork() = fixture { store, _ ->
        reserve(store)
        store.writableDatabase.execSQL("UPDATE time_update_acknowledgements SET receipt='not-json'")
        reject { store.timeUpdateAcknowledgement(scope, base.eventId) }
        reject { store.prepareTimeUpdateAcknowledgement(result()) }; sourceIntact(store)
        store.readableDatabase.rawQuery("SELECT receipt FROM time_update_acknowledgements", null).use {
            assertTrue(it.moveToFirst()); assertEquals("not-json", it.getString(0))
        }
    }

    private suspend fun seedOtherFamiliesHistory(store: ImportStore) {
        for (text in listOf(false, true)) {
            val tag = if (text) "text" else "title"
            val original = base.copy(eventId = EventId("migration-$tag-event"),
                content = base.content.copy(title = "Migration original", description = "", location = ""))
            store.transaction(scope) { putRemote(original); putProjection(ProjectionIntent(scope, original)) }
            assertEquals(1, store.acknowledge(ProjectionIntent(scope, original)))
            val changed = if (text) original.content.copy(description = "Migration text") else original.content.copy(title = "Migration title")
            val local = intent.copy(id = MutationId("migration-$tag-intent"), base = original, content = changed)
            assertEquals(local, store.captureLocalEdit(local))
            val actual = original.copy(revision = Revision("migration-$tag-result"), content = changed)
            if (text) {
                val sealed = LocalTextEditPreparation(local, preparation.timestamp, AdapterFormat("migration-text-preparation", 1), "migration-text-capsule")
                assertEquals(LocalTextPreparationAdmission.STORED, store.retainCurrentLocalTextPreparation(sealed))
                val record = TextUpdateAttemptRecord(MutationId("migration-text-request"), sealed,
                    AdapterFormat("migration-text-wire", 1), "migration-text-wire", TextUpdateAttemptState.OUTCOME_UNKNOWN)
                val proof = TextUpdateVerifiedResult.of(record, TextUpdateSubmissionObservation.Verified(record, actual))
                val first = checkNotNull(store.prepareTextUpdateAcknowledgement(proof))
                assertTrue(store.restoreTextUpdateAcknowledgement(AppliedTextUpdateAcknowledgement(first.record.receipt)))
            } else {
                val sealed = LocalEditPreparation(local, preparation.timestamp, AdapterFormat("migration-title-preparation", 1), "migration-title-capsule")
                assertTrue(store.retainLocalEditPreparation(sealed))
                val record = TitleUpdateAttemptRecord(MutationId("migration-title-request"), sealed,
                    AdapterFormat("migration-title-wire", 1), "migration-title-wire", TitleUpdateAttemptState.OUTCOME_UNKNOWN)
                val proof = TitleUpdateVerifiedResult.of(record, TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(record), actual))
                val first = checkNotNull(store.prepareTitleUpdateAcknowledgement(proof))
                assertTrue(store.restoreTitleUpdateAcknowledgement(AppliedTitleUpdateAcknowledgement(first.record.receipt)))
            }
        }
    }

    @Test fun migrationFromTwentyPreservesExistingTitleTextHistoryAndAddsEmptyTimeTables() = fixture { store, name ->
        seedOtherFamiliesHistory(store)
        // A current helper seeded this legacy fixture: remove every new table before marking it version 20.
        val added = listOf("local_time_edit_preparations", "time_update_acknowledgements", "time_update_acknowledgement_history")
        for (table in added) store.writableDatabase.execSQL("DROP TABLE $table")
        val existing = listOf("calendars", "records", "projection_bases", "local_edits", "conflict_archives", "conflict_observations",
            "local_edit_preparations", "title_update_acknowledgements", "title_update_acknowledgement_history",
            "local_text_edit_preparations", "text_update_acknowledgements", "text_update_acknowledgement_history")
        val before = existing.associateWith { rows(store.readableDatabase, it) }
        assertFalse(before.getValue("title_update_acknowledgement_history").isEmpty())
        assertFalse(before.getValue("text_update_acknowledgement_history").isEmpty())
        store.writableDatabase.version = 20; store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(21, reopened.readableDatabase.version)
            assertEquals(before, existing.associateWith { rows(reopened.readableDatabase, it) })
            for (table in added) assertTrue(rows(reopened.readableDatabase, table).isEmpty())
            assertEquals(LocalTimePreparationAdmission.STORED, reopened.retainCurrentLocalTimePreparation(preparation))
            assertTrue(checkNotNull(reopened.prepareTimeUpdateAcknowledgement(result())).fresh)
        }
    }

    @Test fun failedAdditiveMigrationPreservesVersionAndAllExistingHistoryBytes() = fixture { store, name ->
        seedOtherFamiliesHistory(store)
        val old = listOf("calendars", "records", "projection_bases", "local_edits", "title_update_acknowledgements",
            "title_update_acknowledgement_history", "text_update_acknowledgements", "text_update_acknowledgement_history")
        val before = old.associateWith { rows(store.readableDatabase, it) }
        for (table in listOf("local_time_edit_preparations", "time_update_acknowledgements", "time_update_acknowledgement_history"))
            store.writableDatabase.execSQL("DROP TABLE $table")
        // Collision at the second additive table forces rollback of earlier CREATEs too.
        store.writableDatabase.execSQL("CREATE TABLE time_update_acknowledgements (incompatible TEXT)")
        store.writableDatabase.version = 20; store.close()
        ImportStore(context, name).use { reopened -> reject { reopened.writableDatabase } }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            assertEquals(20, raw.version); assertEquals(before, old.associateWith { rows(raw, it) })
            assertFalse(raw.rawQuery("SELECT 1 FROM sqlite_master WHERE name='local_time_edit_preparations'", null).use { it.moveToFirst() })
        }
    }
}
