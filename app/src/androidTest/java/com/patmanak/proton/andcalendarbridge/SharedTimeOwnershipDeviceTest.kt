// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.serialization.TimeUpdateAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated real SQLite ownership only; no account, Provider, crypto or service operation. */
@RunWith(AndroidJUnit4::class)
class SharedTimeOwnershipDeviceTest {
    private val scope = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 3)
    private val allow: (CalendarScope) -> Boolean = { true }
    private val stamp = Instant.parse("2026-10-17T12:00:00Z")
    private val time = EventTime.Timed(stamp, stamp.plusSeconds(3600), ZoneOffset.UTC)
    private val content = EventContent("Fixture", time, "Notes", "Room", details = EventDetails())
    private fun intent(id: String, target: CalendarScope, event: String, replacement: EventContent) = LocalEditIntent(
        MutationId("owner-$id"), target, AdapterFormat("fixture-import", 1),
        RemoteChange.Upsert(EventId(event), Revision("fixture-base"), content), replacement)
    private fun temporal(id: String = "fixture-time", target: CalendarScope = scope, event: String = "fixture-event") =
        TimeUpdateAttemptRecord(MutationId(id), LocalTimeEditPreparation(intent(id, target, event,
            content.copy(time = time.copy(end = time.end.plusSeconds(3600)))), stamp, AdapterFormat("fixture-time-prep", 1), "protected-prep"),
            AdapterFormat("fixture-time-wire", 1), "protected-wire")
    private fun title(id: String = "fixture-title", target: CalendarScope = scope, event: String = "fixture-event") =
        TitleUpdateAttemptRecord(MutationId(id), LocalEditPreparation(intent(id, target, event, content.copy(title = "Edited")),
            stamp, AdapterFormat("fixture-title-prep", 1), "protected-prep"), AdapterFormat("fixture-title-wire", 1), "protected-wire")
    private fun text(id: String = "fixture-text", target: CalendarScope = scope, event: String = "fixture-event") =
        TextUpdateAttemptRecord(MutationId(id), LocalTextEditPreparation(intent(id, target, event, content.copy(description = "Edited")),
            stamp, AdapterFormat("fixture-text-prep", 1), "protected-prep"), AdapterFormat("fixture-text-wire", 1), "protected-wire")
    private fun fixture(action: suspend (Context, String, TitleUpdateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "time-ownership-${UUID.randomUUID()}.db"
        val store = TitleUpdateAttemptStore(context, name)
        try { action(context, name, store) } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun titleReservationPreparedAndUnknownEachHoldTimeAcrossGenerations() = fixture { _, _, store ->
        for (state in 0..2) {
            val title = title(event = "event-$state", id = "title-$state")
            if (state == 0) assertNotNull(store.reservePreparation(TitleUpdatePreparationReservation.of(title), allow))
            else { assertTrue(store.remember(title, allow)); if (state == 2) assertTrue(store.claim(title, allow)) }
            val time = temporal(event = "event-$state", id = "time-$state", target = scope.copy(generation = 4))
            assertFalse(store.timeJournal.remember(time, allow))
            assertNull(store.timeJournal.read(time.scope, time.id, allow))
        }
    }

    @Test fun timePreparedAndUnknownEachHoldBothTitlePathsAndText() = fixture { _, _, store ->
        for (unknown in listOf(false, true)) {
            val time = temporal(event = "event-$unknown", id = "time-$unknown")
            assertTrue(store.timeJournal.remember(time, allow))
            if (unknown) assertTrue(store.timeJournal.claim(time, allow))
            val title = title(event = time.eventId.value, id = "title-$unknown", target = scope.copy(generation = 4))
            assertNull(store.reservePreparation(TitleUpdatePreparationReservation.of(title), allow))
            assertFalse(store.remember(title, allow))
            assertFalse(store.textJournal.remember(text(event = time.eventId.value, id = "text-$unknown"), allow))
        }
    }

    @Test fun textPreparedAndUnknownEachHoldTime() = fixture { _, _, store ->
        for (unknown in listOf(false, true)) {
            val text = text(event = "event-$unknown", id = "text-$unknown")
            assertTrue(store.textJournal.remember(text, allow))
            if (unknown) assertTrue(store.textJournal.claim(text, allow))
            assertFalse(store.timeJournal.remember(temporal(event = text.eventId.value, id = "time-$unknown"), allow))
        }
    }

    @Test fun requestIdsCannotCrossFamiliesEventsOrGenerations() = fixture { _, _, store ->
        val first = temporal(id = "same-request")
        assertTrue(store.timeJournal.remember(first, allow))
        assertFalse(store.remember(title(id = first.id.value, event = "other-event", target = scope.copy(generation = 4)), allow))
        assertFalse(store.textJournal.remember(text(id = first.id.value, event = "other-event"), allow))
        val text = text(id = "used-text", event = "text-event")
        assertTrue(store.textJournal.remember(text, allow))
        assertFalse(store.timeJournal.remember(temporal(id = text.id.value, event = "other-event"), allow))
        val title = title(id = "used-title", event = "title-event")
        assertNotNull(store.reservePreparation(TitleUpdatePreparationReservation.of(title), allow))
        assertFalse(store.timeJournal.remember(temporal(id = title.id.value, event = "other-event"), allow))
    }

    @Test fun unknownRetainsImmutableFirstWireAndEvidenceAcrossHelperReopening() = fixture { context, name, store ->
        val time = temporal()
        assertTrue(store.timeJournal.remember(time, allow)); assertTrue(store.timeJournal.claim(time, allow))
        val unknown = time.copy(state = TimeUpdateAttemptState.OUTCOME_UNKNOWN)
        assertFalse(store.timeJournal.claim(time, allow)); assertFalse(store.timeJournal.remember(time, allow))
        assertTrue(store.timeJournal.retainEvidence(unknown, "protected-first-response", allow))
        assertFalse(store.timeJournal.retainEvidence(unknown, "protected-other-response", allow))
        val retained = unknown.copy(protectedEvidence = "protected-first-response")
        val reopened = TitleUpdateAttemptStore(context, name)
        try { assertEquals(retained, reopened.timeJournal.read(time.scope, time.id, allow)) }
        finally { reopened.close() }
        assertEquals(time.protectedPayload, store.timeJournal.read(time.scope, time.id, allow)!!.protectedPayload)
    }

    @Test fun separateAccountCalendarAndCompletedHistoryHaveScopedOwnership() = fixture { _, _, store ->
        val time = temporal()
        assertTrue(store.timeJournal.remember(time, allow)); assertTrue(store.timeJournal.claim(time, allow))
        for (other in listOf(scope.copy(account = AccountId("other-account")), scope.copy(calendar = CalendarId("other-calendar")))) {
            assertTrue(store.remember(title(target = other), allow))
            assertTrue(store.textJournal.remember(text(target = other, event = "other-event"), allow))
        }
        val unknown = time.copy(state = TimeUpdateAttemptState.OUTCOME_UNKNOWN)
        val event = unknown.preparation.intent.base.copy(revision = Revision("fixture-new"), content = unknown.preparation.intent.content)
        val result = TimeUpdateVerifiedResult.of(unknown, TimeUpdateSubmissionObservation.Verified(unknown, event))
        assertTrue(store.timeJournal.retainVerified(result, allow))
        assertFalse(store.remember(title(), allow))
        val receipt = TimeUpdateAcknowledgementReceipt.of(result, "11111111-2222-3333-4444-555555555555")
        val completion = TimeUpdateCompletion.of(result, TimeUpdateAcknowledgementRecord(receipt, TimeUpdateAcknowledgementState.RESTORED))
        assertTrue(store.timeJournal.retainCompletion(completion, allow))
        assertTrue(store.remember(title(), allow))
        assertFalse(store.textJournal.remember(text(id = time.id.value, event = "other-event"), allow))
    }

    @Test fun revokedAdmissionNeverMeansAbsenceAndDoesNotCommitTimeWork() = fixture { _, _, store ->
        val time = temporal()
        assertFalse(store.timeJournal.remember(time) { false })
        assertNull(store.timeJournal.read(time.scope, time.id, allow))
        assertTrue(store.timeJournal.remember(time, allow))
        assertFalse(store.timeJournal.claim(time) { false })
        assertEquals(time, store.timeJournal.read(time.scope, time.id, allow))
        assertNull(store.timeJournal.read(time.scope, time.id) { false })
        assertFalse(store.remember(title(), allow))
    }

    @Test fun claimRechecksReciprocalOwnershipOfConflictingHistoricalRows() = fixture { _, _, store ->
        // Controlled SQL injection represents an old/corrupt overlapping history. Neither claim
        // may trust the successful earlier remember instead of requalifying every family.
        val title = title()
        assertTrue(store.remember(title, allow))
        val time = temporal()
        rawTime(store.writableDatabase, time)
        assertFalse(store.claim(title, allow))
        assertFalse(store.timeJournal.claim(time, allow))
        assertFalse(store.textJournal.remember(text(), allow))
        assertEquals(TimeUpdateAttemptState.PREPARED, store.timeJournal.read(time.scope, time.id, allow)!!.state)
    }

    @Test fun corruptTemporalHistoryBlocksOtherFamiliesAndNeverCertifiesAbsence() = fixture { _, _, store ->
        val time = temporal()
        assertTrue(store.timeJournal.remember(time, allow))
        store.writableDatabase.execSQL("UPDATE time_update_attempts SET capsule=?", arrayOf(byteArrayOf(1, 2, 3)))
        suspend fun rejected(action: suspend () -> Unit) {
            try { action(); fail("Corrupt history accepted") } catch (_: IllegalStateException) { }
        }
        rejected { store.reservePreparation(TitleUpdatePreparationReservation.of(title()), allow) }
        rejected { store.textJournal.remember(text(), allow) }
        assertTrue(store.remember(title(target = scope.copy(calendar = CalendarId("unrelated"))), allow))
    }

    @Test fun versionSixMigrationPreservesHistoricalCapsulesAndAddsEmptyTimeJournal() = fixture { context, name, store ->
        val title = title(event = "title-event")
        val text = text(event = "text-event")
        assertTrue(store.remember(title, allow)); assertTrue(store.textJournal.remember(text, allow))
        assertTrue(store.textJournal.claim(text, allow))
        // Recreate the prior schema without touching either family's historical bytes.
        store.writableDatabase.execSQL("DROP TABLE personal_update_attempts")
        store.writableDatabase.execSQL("DROP TABLE delete_attempts")
        store.writableDatabase.execSQL("DROP TABLE time_update_attempts")
        store.writableDatabase.version = 6
        store.close()
        val migrated = TitleUpdateAttemptStore(context, name)
        try {
            assertEquals(10, migrated.readableDatabase.version)
            assertEquals(title, migrated.read(title.scope, title.id))
            assertEquals(text.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN), migrated.textJournal.read(text.scope, text.id, allow))
            assertNull(migrated.timeJournal.read(scope, MutationId("missing"), allow))
            assertTrue(migrated.timeJournal.remember(temporal(event = "new-time-event"), allow))
        } finally { migrated.close() }
    }

    private fun rawTime(db: SQLiteDatabase, record: TimeUpdateAttemptRecord) {
        db.insertOrThrow("time_update_attempts", null, ContentValues().apply {
            put("account", record.scope.account.value); put("calendar", record.scope.calendar.value)
            put("generation", record.scope.generation); put("mutation", record.id.value); put("event", record.eventId.value)
            put("state", record.state.name); put("capsule", TimeUpdateAttemptStorageCodec.encode(record))
        })
    }
}
