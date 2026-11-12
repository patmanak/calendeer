// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemPersonalEditProfile
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in isolated SQLite fixtures. Applied witnesses here qualify SQL only, never native ACK. */
@RunWith(AndroidJUnit4::class)
class PersonalPersistenceDeviceTest {
    private val scope = CalendarScope(AccountId("personal-sql-fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("synthetic-import", 3)
    private val timestamp = Instant.parse("2030-04-01T12:00:00Z")
    private val time = EventTime.Timed(Instant.parse("2030-05-01T09:00:00Z"),
        Instant.parse("2030-05-01T10:00:00Z"), ZoneId.of("Europe/Paris"))
    private val base = RemoteChange.Upsert(EventId("personal-event"), Revision("old"),
        EventContent("Original", time, "Original detail", "Original place", colorRgb = 0x123456,
            details = EventDetails(reminders = listOf(EventReminder(10), EventReminder(-15)))))
    private val intent = LocalEditIntent(MutationId("personal-intent"), scope, format, base,
        base.content.copy(title = "Personal\r\nedit", description = "New detail", location = "New place",
            time = EventTime.AllDay(LocalDate.of(2030, 5, 2), LocalDate.of(2030, 5, 4))))
    private fun preparation(value: LocalEditIntent = intent, payload: String = "opaque-personal-preparation") =
        PersonalEditPreparation(value, timestamp, AdapterFormat("personal-preparation", 2), payload)
    private fun personal(value: LocalEditIntent = intent, id: String = "personal-request") =
        PersonalUpdateAttemptRecord(MutationId(id), preparation(value), AdapterFormat("personal-wire", 4), "opaque-personal-wire")
    private fun result(record: PersonalUpdateAttemptRecord = personal().copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN),
        event: RemoteChange.Upsert = record.preparation.intent.base.copy(revision = Revision("new"),
            content = record.preparation.intent.content.let { it.copy(title = it.title.replace("\r\n", "\n")) })) =
        PersonalUpdateVerifiedResult.of(record, PersonalUpdateSubmissionObservation.Verified(record, event))
    private val allow: (CalendarScope) -> Boolean = { true }
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val addedImport = listOf("personal_edit_preparations", "personal_update_acknowledgements", "personal_update_acknowledgement_history")

    private fun journalFixture(block: suspend (TitleUpdateAttemptStore, String) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val name = "personal-journal-${UUID.randomUUID()}.db"
        val store = TitleUpdateAttemptStore(context, name)
        try { block(store, name) } finally { store.close(); context.deleteDatabase(name) }
    }
    private fun importFixture(block: suspend (ImportStore, String) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val name = "personal-import-${UUID.randomUUID()}.db"
        val store = ImportStore(context, name)
        try {
            store.remember(scope.account, listOf(CalendarSummary(scope.calendar, "Synthetic personal SQL")))
            store.setEnabled(scope, true)
            seed(store, base)
            block(store, name)
        } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun seed(store: ImportStore, original: RemoteChange.Upsert) {
        store.transaction(scope) {
            checkpoint = PageCheckpoint(scope, format)
            putRemote(original); putProjection(ProjectionIntent(scope, original))
        }
        assertEquals(1, store.acknowledge(ProjectionIntent(scope, original)))
    }
    private fun tables(db: SQLiteDatabase): List<String> = db.rawQuery(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name!='android_metadata' ORDER BY name", null
    ).use { rows -> buildList { while (rows.moveToNext()) add(rows.getString(0)) } }
    /** Compare actual SQLite storage types and exact bytes; never decode old opaque capsules. */
    private fun bytes(db: SQLiteDatabase, table: String): List<List<String>> {
        val columns = db.rawQuery("PRAGMA table_info($table)", null).use { rows -> buildList {
            while (rows.moveToNext()) add(rows.getString(1))
        } }
        val cells = columns.joinToString(",") { "typeof(\"$it\") || ':' || hex(CAST(\"$it\" AS BLOB))" }
        return db.rawQuery("SELECT $cells FROM $table ORDER BY rowid", null).use { rows -> buildList {
            while (rows.moveToNext()) add(List(rows.columnCount) { rows.getString(it) })
        } }
    }
    private fun snapshot(db: SQLiteDatabase, names: List<String>) = names.associateWith { bytes(db, it) }
    private fun assertPreparation(expected: PersonalEditPreparation, actual: PersonalEditPreparation?) {
        assertNotNull(actual); actual!!
        assertEquals(expected.intent, actual.intent); assertEquals(expected.timestamp, actual.timestamp)
        assertEquals(expected.format, actual.format); assertEquals(expected.protectedPayload, actual.protectedPayload)
    }
    private fun ordinary(event: String, target: CalendarScope = scope): LocalEditIntent {
        val original = base.copy(eventId = EventId(event), content = base.content.copy(colorRgb = null, details = EventDetails()))
        return LocalEditIntent(MutationId("owner-$event"), target, format, original, original.content.copy(title = "Changed"))
    }
    private fun title(local: LocalEditIntent) = TitleUpdateAttemptRecord(MutationId("title-${local.base.eventId.value}"),
        LocalEditPreparation(local, timestamp, format, "opaque-title-preparation\u0000tail"), format, "opaque-title-wire")
    private fun text(local: LocalEditIntent) = TextUpdateAttemptRecord(MutationId("text-${local.base.eventId.value}"),
        LocalTextEditPreparation(local.copy(content = local.content.copy(description = "New\r\nnotes")), timestamp, format,
            "opaque-text-preparation"), format, "opaque-text-wire")
    private fun temporal(local: LocalEditIntent) = TimeUpdateAttemptRecord(MutationId("time-${local.base.eventId.value}"),
        LocalTimeEditPreparation(local.copy(content = local.base.content.copy(time = time.copy(end = time.end.plusSeconds(60)))),
            timestamp, format, "opaque-time-preparation"), format, "opaque-time-wire")
    private fun deletion(local: LocalEditIntent) = DeleteAttemptRecord(MutationId("delete-${local.base.eventId.value}"),
        LocalDeleteIntent(local.id, local.scope, format, local.base, local.base.content), format, "opaque-delete-wire")
    private suspend fun rememberLegacy(store: TitleUpdateAttemptStore, kind: Int, local: LocalEditIntent): Boolean = when (kind) {
        0 -> store.remember(title(local), allow)
        1 -> store.textJournal.remember(text(local), allow)
        2 -> store.timeJournal.remember(temporal(local), allow)
        else -> store.deleteJournal.remember(deletion(local), allow)
    }

    @Test fun sharedNineToTenAddsPersonalTableWithoutChangingAnyOldSqlBytes() = journalFixture { store, name ->
        for (kind in 0..3) assertTrue(rememberLegacy(store, kind, ordinary("legacy-$kind")))
        val text = text(ordinary("legacy-1")); assertTrue(store.textJournal.claim(text, allow))
        assertTrue(store.textJournal.retainEvidence(text.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN), "opaque-evidence", allow))
        val textUnknown = text.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
        val textActual = text.preparation.intent.base.copy(revision = Revision("legacy-text-result"),
            content = text.preparation.intent.content.copy(description = "New\nnotes"))
        val textProof = TextUpdateVerifiedResult.of(textUnknown, TextUpdateSubmissionObservation.Verified(textUnknown, textActual))
        assertTrue(store.textJournal.retainVerified(textProof, allow))
        val textReceipt = TextUpdateAcknowledgementReceipt.of(textProof, UUID.randomUUID().toString())
        assertTrue(store.textJournal.retainCompletion(TextUpdateCompletion.of(textProof,
            TextUpdateAcknowledgementRecord(textReceipt, TextUpdateAcknowledgementState.RESTORED)), allow))
        val old = tables(store.readableDatabase).filter { it != "personal_update_attempts" }
        val before = snapshot(store.readableDatabase, old)
        assertTrue(before.getValue("title_update_attempts").isNotEmpty())
        assertTrue(before.getValue("text_update_attempts").isNotEmpty())
        assertTrue(before.getValue("time_update_attempts").isNotEmpty())
        assertTrue(before.getValue("delete_attempts").isNotEmpty())
        // Reconstruct v9: remove later additions, including their indexes.
        store.writableDatabase.execSQL("DROP TABLE personal_update_attempts")
        store.writableDatabase.execSQL("DROP TABLE conflict_resolution_fences")
        store.writableDatabase.version = 9; store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(11, reopened.readableDatabase.version)
            assertEquals(before, snapshot(reopened.readableDatabase, old))
            assertTrue(bytes(reopened.readableDatabase, "personal_update_attempts").isEmpty())
            assertTrue(reopened.personalJournal.remember(personal(), allow))
            assertEquals(before, snapshot(reopened.readableDatabase, old))
        }
    }
    @Test fun unknownProofAndCompletionReopenWithoutResetAndOnlyCompletionReleasesEvent() = journalFixture { store, name ->
        val prepared = personal(); val journal = store.personalJournal
        assertTrue(journal.remember(prepared, allow)); assertTrue(journal.claim(prepared, allow))
        val unknown = prepared.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
        assertFalse(journal.claim(prepared, allow)); assertFalse(journal.remember(prepared, allow))
        assertTrue(journal.retainEvidence(unknown, "opaque-evidence", allow))
        val proof = result(unknown)
        assertTrue(journal.retainVerified(proof, allow)); assertTrue(journal.retainVerified(proof, allow))
        val nextLocal = intent.copy(scope = scope.copy(generation = 1), id = MutationId("next-owner"),
            base = proof.event, content = proof.event.content.copy(location = "Next place"))
        assertFalse(journal.remember(personal(nextLocal, "next-request"), allow))
        val receipt = PersonalUpdateAcknowledgementReceipt.of(proof, UUID.randomUUID().toString())
        val completion = PersonalUpdateCompletion.of(proof, PersonalUpdateAcknowledgementRecord(receipt, PersonalUpdateAcknowledgementState.RESTORED))
        assertTrue(journal.retainCompletion(completion, allow)); store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            val retained = reopened.personalJournal
            assertEquals(unknown.copy(protectedEvidence = "opaque-evidence"), retained.read(scope, prepared.id, allow))
            assertEquals(proof, retained.readVerified(scope, prepared.id, allow))
            assertEquals(completion, retained.readCompletion(scope, prepared.id, allow))
            assertTrue(retained.retainCompletion(completion, allow)); assertFalse(retained.remember(prepared, allow))
            assertTrue(retained.remember(personal(nextLocal, "next-request"), allow))
            assertFalse(retained.remember(personal(nextLocal, prepared.id.value), allow))
        }
    }
    @Test fun pagedPersonalRecoveryReopensUnknownAndExcludesCompletedHistory() = journalFixture { store, name ->
        val first = personal(intent.copy(base = base.copy(eventId = EventId("a-event"))), "first-request")
        val second = personal(intent.copy(id = MutationId("second-intent"), base = base.copy(eventId = EventId("b-event"))), "second-request")
        val journal = store.personalJournal
        assertTrue(journal.remember(second, allow)); assertTrue(journal.remember(first, allow))
        assertTrue(journal.claim(first, allow))
        val unknown = first.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
        val proof = result(unknown)
        assertTrue(journal.retainVerified(proof, allow))
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            val retained = reopened.personalJournal
            val page = checkNotNull(retained.page(PersonalUpdateWorkQuery(scope, limit = 1), allow))
            assertEquals(listOf(PersonalUpdateWorkRef(first.id, first.eventId, PersonalUpdateAttemptState.OUTCOME_UNKNOWN)), page.entries)
            val next = checkNotNull(page.next)
            assertEquals(listOf(PersonalUpdateWorkRef(second.id, second.eventId, PersonalUpdateAttemptState.PREPARED)),
                checkNotNull(retained.page(PersonalUpdateWorkQuery(scope, limit = 1, cursor = next), allow)).entries)
            assertEquals(proof, retained.readVerified(scope, first.id, allow))
            val receipt = PersonalUpdateAcknowledgementReceipt.of(proof, UUID.randomUUID().toString())
            assertTrue(retained.retainCompletion(PersonalUpdateCompletion.of(proof,
                PersonalUpdateAcknowledgementRecord(receipt, PersonalUpdateAcknowledgementState.RESTORED)), allow))
            assertEquals(listOf(PersonalUpdateWorkRef(second.id, second.eventId, PersonalUpdateAttemptState.PREPARED)),
                checkNotNull(retained.page(PersonalUpdateWorkQuery(scope), allow)).entries)
            assertEquals(unknown, retained.read(scope, first.id, allow))
            assertNull(retained.page(PersonalUpdateWorkQuery(scope)) { false })
        }
    }
    @Test fun personalAndEveryLegacyFamilyReciprocallyBlockAcrossGenerations() = journalFixture { store, _ ->
        for (kind in 0..3) {
            val local = ordinary("legacy-first-$kind")
            assertTrue(rememberLegacy(store, kind, local))
            assertFalse(store.personalJournal.remember(personal(local.copy(scope = scope.copy(generation = 1)), "personal-after-$kind"), allow))
            val other = ordinary("personal-first-$kind")
            assertTrue(store.personalJournal.remember(personal(other, "personal-before-$kind"), allow))
            assertFalse(rememberLegacy(store, kind, other.copy(scope = scope.copy(generation = 2))))
        }
    }
    @Test fun deniedTransactionsAndSubstitutedEvidenceOrProofCannotMutateHistory() = journalFixture { store, _ ->
        val prepared = personal(); val journal = store.personalJournal
        assertFalse(journal.remember(prepared) { false })
        assertNull(journal.read(scope, prepared.id, allow))
        assertTrue(journal.remember(prepared, allow))
        val before = bytes(store.readableDatabase, "personal_update_attempts")
        var checks = 0
        assertFalse(journal.claim(prepared) { ++checks < 2 })
        assertEquals(before, bytes(store.readableDatabase, "personal_update_attempts"))
        assertTrue(journal.claim(prepared, allow))
        val unknown = prepared.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
        assertTrue(journal.retainEvidence(unknown, "first-evidence", allow))
        assertFalse(journal.retainEvidence(unknown, "different-evidence", allow))
        val proof = result(unknown); assertTrue(journal.retainVerified(proof, allow))
        val saved = bytes(store.readableDatabase, "personal_update_attempts")
        assertFalse(journal.retainVerified(result(unknown, proof.event.copy(revision = Revision("different-result"))), allow))
        assertEquals(saved, bytes(store.readableDatabase, "personal_update_attempts"))
    }
    @Test fun importTwentyTwoToTwentyThreePreservesOldRowsAndAddsOnlyEmptyPersonalTables() = importFixture { store, name ->
        val legacy = ordinary("legacy-import-time")
        seed(store, legacy.base)
        val legacyPreparation = temporal(legacy).preparation
        assertEquals(legacyPreparation.intent, store.captureLocalEdit(legacyPreparation.intent))
        assertEquals(LocalTimePreparationAdmission.STORED, store.retainCurrentLocalTimePreparation(legacyPreparation))
        val prior = ordinary("legacy-import-completed")
        seed(store, prior.base)
        val priorWork = temporal(prior).copy(state = TimeUpdateAttemptState.OUTCOME_UNKNOWN)
        assertEquals(priorWork.preparation.intent, store.captureLocalEdit(priorWork.preparation.intent))
        assertEquals(LocalTimePreparationAdmission.STORED, store.retainCurrentLocalTimePreparation(priorWork.preparation))
        val priorActual = prior.base.copy(revision = Revision("legacy-time-result"), content = priorWork.preparation.intent.content)
        val priorProof = TimeUpdateVerifiedResult.of(priorWork, TimeUpdateSubmissionObservation.Verified(priorWork, priorActual))
        val priorReceipt = checkNotNull(store.prepareTimeUpdateAcknowledgement(priorProof)).record.receipt
        assertTrue(store.restoreTimeUpdateAcknowledgement(AppliedTimeUpdateAcknowledgement(priorReceipt)))
        val old = tables(store.readableDatabase).filter { it !in addedImport }
        val before = snapshot(store.readableDatabase, old)
        assertTrue(before.getValue("local_time_edit_preparations").isNotEmpty())
        assertTrue(before.getValue("time_update_acknowledgement_history").isNotEmpty())
        for (table in addedImport) store.writableDatabase.execSQL("DROP TABLE $table")
        store.writableDatabase.version = 22; store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(23, reopened.readableDatabase.version)
            assertEquals(before, snapshot(reopened.readableDatabase, old))
            for (table in addedImport) assertTrue(bytes(reopened.readableDatabase, table).isEmpty())
            assertEquals(legacyPreparation.intent, reopened.localTimeEditPreparation(scope, legacy.base.eventId)!!.intent)
            assertEquals(intent, reopened.captureLocalEdit(intent))
            assertEquals(PersonalPreparationAdmission.STORED, reopened.retainCurrentPersonalPreparation(preparation()))
            assertTrue(checkNotNull(reopened.preparePersonalUpdateAcknowledgement(result())).fresh)
        }
    }
    @Test fun rawPersonalPreparationAndCanonicalAcknowledgementSurviveReopenAndHistoricalReplay() = importFixture { store, name ->
        val capsule = preparation()
        assertEquals(intent, store.captureLocalEdit(intent))
        assertEquals(PersonalPreparationAdmission.STORED, store.retainCurrentPersonalPreparation(capsule))
        assertEquals(PersonalPreparationAdmission.HISTORICAL, store.retainCurrentPersonalPreparation(capsule))
        assertEquals(PersonalPreparationAdmission.UNQUALIFIED, store.retainCurrentPersonalPreparation(preparation(payload = "different")))
        val proof = result(); val reservation = checkNotNull(store.preparePersonalUpdateAcknowledgement(proof))
        assertTrue(reservation.fresh); assertEquals(intent, reservation.record.receipt.intent)
        assertEquals("Personal\nedit", reservation.record.receipt.projection.change.content.title)
        assertEquals(reservation.copy(fresh = false), store.preparePersonalUpdateAcknowledgement(proof))
        store.close()
        ImportStore(context, name).use { reopened ->
            assertPreparation(capsule, reopened.personalEditPreparation(scope, base.eventId))
            assertEquals(reservation.record, reopened.personalUpdateAcknowledgement(scope, base.eventId))
            val witness = AppliedPersonalUpdateAcknowledgement(reservation.record.receipt)
            assertTrue(reopened.restorePersonalUpdateAcknowledgement(witness))
            assertEquals(proof.event, reopened.projectedBaseline(scope, base.eventId))
            assertNull(reopened.localEdit(scope, base.eventId)); assertNull(reopened.personalEditPreparation(scope, base.eventId))
            val later = intent.copy(id = MutationId("later-owner"), base = proof.event, content = proof.event.content.copy(location = "Later place"))
            assertEquals(later, reopened.captureLocalEdit(later))
            assertEquals(PersonalPreparationAdmission.STORED, reopened.retainCurrentPersonalPreparation(preparation(later)))
            val before = snapshot(reopened.readableDatabase, listOf("records", "local_edits", "personal_edit_preparations"))
            assertTrue(reopened.restorePersonalUpdateAcknowledgement(witness))
            assertEquals(before, snapshot(reopened.readableDatabase, before.keys.toList()))
            assertEquals(later, reopened.localEdit(scope, base.eventId))
            assertEquals(PersonalUpdateAcknowledgementState.RESTORED,
                reopened.personalUpdateAcknowledgement(scope, base.eventId, proof.attempt.id)!!.state)
        }
    }
    @Test fun freshSimpleTitleTextAndTimeUsePersonalPreparationsAndReopen() = importFixture { store, name ->
        val edits = listOf(ordinary("fresh-title"), text(ordinary("fresh-text")).preparation.intent,
            temporal(ordinary("fresh-time")).preparation.intent)
        for (edit in edits) {
            assertTrue(SystemPersonalEditProfile.accepts(edit))
            seed(store, edit.base)
            assertEquals(edit, store.captureLocalEdit(edit))
            assertEquals(PersonalPreparationAdmission.STORED, store.retainCurrentPersonalPreparation(preparation(edit)))
        }
        val before = snapshot(store.readableDatabase, listOf("local_edits", "personal_edit_preparations"))
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(before, snapshot(reopened.readableDatabase, before.keys.toList()))
            for (edit in edits) {
                assertPreparation(preparation(edit), reopened.personalEditPreparation(scope, edit.base.eventId))
                assertEquals(PersonalPreparationAdmission.HISTORICAL, reopened.retainCurrentPersonalPreparation(preparation(edit)))
                assertNull(reopened.localEditPreparation(scope, edit.base.eventId))
                assertNull(reopened.localTextEditPreparation(scope, edit.base.eventId))
                assertNull(reopened.localTimeEditPreparation(scope, edit.base.eventId))
            }
        }
    }
    @Test fun legacyPreparationsBlockPersonalTakeoverAndReopenWithoutChangingAnyBytes() = importFixture { store, name ->
        val title = title(ordinary("owned-title")).preparation
        val text = text(ordinary("owned-text")).preparation
        val time = temporal(ordinary("owned-time")).preparation
        val edits = listOf(title.intent, text.intent, time.intent)
        for (edit in edits) {
            seed(store, edit.base)
            assertEquals(edit, store.captureLocalEdit(edit))
        }
        assertEquals(LocalTitlePreparationAdmission.STORED, store.retainCurrentLocalTitlePreparation(title))
        assertEquals(LocalTextPreparationAdmission.STORED, store.retainCurrentLocalTextPreparation(text))
        assertEquals(LocalTimePreparationAdmission.STORED, store.retainCurrentLocalTimePreparation(time))
        val before = snapshot(store.readableDatabase, tables(store.readableDatabase))
        for (edit in edits) {
            assertEquals(PersonalPreparationAdmission.UNQUALIFIED, store.retainCurrentPersonalPreparation(preparation(edit)))
        }
        assertEquals(before, snapshot(store.readableDatabase, before.keys.toList()))
        store.close()
        ImportStore(context, name).use { reopened ->
            assertEquals(before, snapshot(reopened.readableDatabase, before.keys.toList()))
            for (edit in edits) {
                assertEquals(edit, reopened.localEdit(scope, edit.base.eventId))
                assertEquals(PersonalPreparationAdmission.UNQUALIFIED, reopened.retainCurrentPersonalPreparation(preparation(edit)))
                assertNull(reopened.personalEditPreparation(scope, edit.base.eventId))
            }
            assertEquals(before, snapshot(reopened.readableDatabase, before.keys.toList()))
        }
    }
    @Test fun unknownLegacyRequestsRejectPersonalAdoptionAndKeepExactWireOnReopen() = journalFixture { store, name ->
        val title = title(ordinary("unknown-title"))
        val text = text(ordinary("unknown-text"))
        val time = temporal(ordinary("unknown-time"))
        assertTrue(store.remember(title, allow)); assertTrue(store.claim(title, allow))
        assertTrue(store.textJournal.remember(text, allow)); assertTrue(store.textJournal.claim(text, allow))
        assertTrue(store.timeJournal.remember(time, allow)); assertTrue(store.timeJournal.claim(time, allow))
        val edits = listOf(title.preparation.intent, text.preparation.intent, time.preparation.intent)
        val before = snapshot(store.readableDatabase, tables(store.readableDatabase))
        for (edit in edits) assertFalse(store.personalJournal.remember(personal(edit), allow))
        assertEquals(before, snapshot(store.readableDatabase, before.keys.toList()))
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(title.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), reopened.read(scope, title.id))
            assertEquals(text.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN), reopened.textJournal.read(scope, text.id, allow))
            assertEquals(time.copy(state = TimeUpdateAttemptState.OUTCOME_UNKNOWN), reopened.timeJournal.read(scope, time.id, allow))
            for (edit in edits) assertFalse(reopened.personalJournal.remember(personal(edit), allow))
            assertEquals(before, snapshot(reopened.readableDatabase, before.keys.toList()))
        }
    }
    @Test fun incompatibleImportAdditionRollsBackVersionAndEarlierCreatesWithoutTouchingOldRows() = importFixture { store, name ->
        val old = tables(store.readableDatabase).filter { it !in addedImport }
        val before = snapshot(store.readableDatabase, old)
        for (table in addedImport) store.writableDatabase.execSQL("DROP TABLE $table")
        store.writableDatabase.execSQL("CREATE TABLE personal_update_acknowledgements (incompatible TEXT)")
        store.writableDatabase.version = 22; store.close()
        ImportStore(context, name).use { reopened ->
            try { reopened.writableDatabase; fail("Conflicting schema must fail atomically") }
            catch (_: SQLiteException) { }
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            assertEquals(22, raw.version); assertEquals(before, snapshot(raw, old))
            assertFalse(tables(raw).contains("personal_edit_preparations"))
            assertFalse(tables(raw).contains("personal_update_acknowledgement_history"))
        }
    }
}
