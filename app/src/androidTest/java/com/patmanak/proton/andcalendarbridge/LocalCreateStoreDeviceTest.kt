// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated SQLite fixtures only. No session, network or Calendar Provider/row locator access. */
@RunWith(AndroidJUnit4::class)
class LocalCreateStoreDeviceTest {
    private val scope = CalendarScope(AccountId("local-create-fixture"), CalendarId("calendar"), 0)
    private val reservation = LocalCreateReservation(MutationId("fixture-create"),
        LocalEventId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), scope, AdapterFormat("local-create-fixture", 1),
        Instant.parse("2026-10-01T12:00:00Z"))
    private val content = EventContent("Fixture create", EventTime.AllDay(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)),
        description = "Retained synthetic description", location = "Fixture location", details = EventDetails())
    private val intent = LocalCreateIntent(reservation, content)
    private fun fixture(block: suspend (Context, String, LocalCreateStore) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "local-create-${UUID.randomUUID()}.db"
        val store = LocalCreateStore(context, name)
        try { block(context, name, store) } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun refused(block: suspend () -> Unit) {
        try { block(); fail("Expected immutable, foreign or inactive journal refusal") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
        catch (_: SQLiteException) { }
    }
    private fun payload(store: LocalCreateStore, localId: LocalEventId = reservation.localId): String =
        store.readableDatabase.rawQuery("SELECT content FROM local_creates WHERE local_event=?", arrayOf(localId.value)).use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getString(0)
        }
    private fun replacePayload(store: LocalCreateStore, localId: LocalEventId, payload: String) =
        store.writableDatabase.update("local_creates", ContentValues().apply { put("content", payload) },
            "local_event=?", arrayOf(localId.value))

    @Test fun firstReservationCommitsBeforeReturnAndReopenNeverAdmitsStampAgain() = fixture { context, name, store ->
        assertNull(store.readReservation(scope, reservation.localId)); assertNull(store.readIntent(scope, reservation.localId))
        assertTrue(store.reserve(reservation)); assertFalse(store.reserve(reservation))
        assertEquals(reservation, store.readReservation(scope, reservation.localId)); assertNull(store.readIntent(scope, reservation.localId))
        store.close()
        LocalCreateStore(context, name).use { reopened ->
            assertEquals(reservation, reopened.readReservation(scope, reservation.localId))
            assertFalse(reopened.reserve(reservation)); assertNull(reopened.readIntent(scope, reservation.localId))
            assertEquals(3, reopened.readableDatabase.version)
        }
    }
    @Test fun firstCapturedIntentSurvivesReopenAndExactReplayDoesNotReplaceContent() = fixture { context, name, store ->
        assertTrue(store.reserve(reservation)); assertEquals(intent, store.retain(intent)); assertEquals(intent, store.retain(intent))
        val originalPayload = payload(store)
        store.close()
        LocalCreateStore(context, name).use { reopened ->
            assertEquals(intent, reopened.readIntent(scope, reservation.localId)); assertFalse(reopened.reserve(reservation))
            assertEquals(intent, reopened.retain(intent)); assertEquals(originalPayload, payload(reopened))
            refused { reopened.retain(intent.copy(content = content.copy(title = "Later create snapshot"))) }
            assertEquals(intent, reopened.readIntent(scope, reservation.localId))
        }
    }
    @Test fun reservationRejectsDifferentMutationAdapterTimestampOrVersionWithoutReopening() = fixture { _, _, store ->
        store.reserve(reservation)
        val changed = listOf(reservation.copy(id = MutationId("another")), reservation.copy(format = AdapterFormat("another", 1)),
            reservation.copy(format = reservation.format.copy(version = 2)), reservation.copy(timestamp = reservation.timestamp.plusSeconds(1)))
        for (candidate in changed) {
            refused { store.reserve(candidate) }
            refused { store.retain(LocalCreateIntent(candidate, content)) }
        }
        assertEquals(reservation, store.readReservation(scope, reservation.localId)); assertFalse(store.reserve(reservation))
        assertNull(store.readIntent(scope, reservation.localId))
    }
    @Test fun intentRequiresAnExistingExactReservationAndDoesNotCreateRowsImplicitly() = fixture { _, _, store ->
        refused { store.retain(intent) }
        assertNull(store.readReservation(scope, reservation.localId))
        store.reserve(reservation)
        val foreign = listOf(reservation.copy(localId = LocalEventId(UUID.randomUUID().toString())),
            reservation.copy(scope = scope.copy(account = AccountId("foreign"))),
            reservation.copy(scope = scope.copy(calendar = CalendarId("foreign"))), reservation.copy(scope = scope.copy(generation = 1)))
        for (candidate in foreign) {
            refused { store.retain(LocalCreateIntent(candidate, content)) }
            assertNull(store.readIntent(candidate.scope, candidate.localId))
        }
        assertEquals(intent, store.retain(intent))
    }
    @Test fun sameMutationCannotReserveAnotherLocalIdentityWithinOneScope() = fixture { _, _, store ->
        assertTrue(store.reserve(reservation))
        val another = reservation.copy(localId = LocalEventId(UUID.randomUUID().toString()))
        refused { store.reserve(another) }
        assertNull(store.readReservation(scope, another.localId)); assertNull(store.readIntent(scope, another.localId))
        assertFalse(store.reserve(reservation)); assertEquals(intent, store.retain(intent))
    }
    @Test fun accountCalendarGenerationAndLocalIdentityRemainIndependent() = fixture { _, _, store ->
        val records = listOf(reservation, reservation.copy(scope = scope.copy(account = AccountId("another-account"))),
            reservation.copy(scope = scope.copy(calendar = CalendarId("another-calendar"))), reservation.copy(scope = scope.copy(generation = 1)),
            reservation.copy(id = MutationId("another-mutation"), localId = LocalEventId(UUID.randomUUID().toString())))
        for ((index, record) in records.withIndex()) {
            assertNull(store.readReservation(record.scope, record.localId)); assertTrue(store.reserve(record))
            store.retain(LocalCreateIntent(record, content.copy(title = "Scoped fixture $index")))
        }
        for ((index, record) in records.withIndex()) {
            assertEquals(record, store.readReservation(record.scope, record.localId)); assertFalse(store.reserve(record))
            assertEquals("Scoped fixture $index", store.readIntent(record.scope, record.localId)?.content?.title)
        }
    }
    @Test fun independentHelpersCommitExactlyOneReservationUnderConcurrentAdmission() = fixture { context, name, store ->
        assertNull(store.readReservation(scope, reservation.localId))
        LocalCreateStore(context, name).use { other ->
            assertNull(other.readReservation(scope, reservation.localId))
            repeat(8) { index ->
                val record = reservation.copy(id = MutationId("concurrent-$index"), localId = LocalEventId(UUID.randomUUID().toString()))
                coroutineScope {
                    val start = CompletableDeferred<Unit>()
                    val first = async(Dispatchers.IO) { start.await(); store.reserve(record) }
                    val second = async(Dispatchers.IO) { start.await(); other.reserve(record) }
                    start.complete(Unit)
                    assertEquals(1, listOf(first.await(), second.await()).count { it })
                }
                assertEquals(record, other.readReservation(scope, record.localId)); assertFalse(store.reserve(record)); assertFalse(other.reserve(record))
            }
        }
    }
    @Test fun competingRetainersPreserveOnlyOneImmutableFirstContent() = fixture { context, name, store ->
        store.reserve(reservation)
        LocalCreateStore(context, name).use { other ->
            assertEquals(reservation, other.readReservation(scope, reservation.localId))
            val secondIntent = intent.copy(content = content.copy(title = "Competing fixture"))
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val first = async(Dispatchers.IO) { start.await(); runCatching { store.retain(intent) } }
                val second = async(Dispatchers.IO) { start.await(); runCatching { other.retain(secondIntent) } }
                start.complete(Unit)
                val outcomes = listOf(first.await(), second.await())
                assertEquals(1, outcomes.count { it.isSuccess })
                val captured = outcomes.single { it.isSuccess }.getOrThrow()
                assertEquals(captured, store.readIntent(scope, reservation.localId)); assertEquals(captured, other.readIntent(scope, reservation.localId))
                assertEquals(captured, other.retain(captured)); assertFalse(other.reserve(reservation))
            }
        }
    }
    @Test fun unicodeEscapesDstTimezoneAndExclusiveAllDayEndRoundTripAsKnownEmptyDetails() = fixture { _, _, store ->
        val timed = content.copy(title = "Fixture 🦌 \"quoted\"", description = "Line one\nLine two\\escape",
            time = EventTime.Timed(Instant.parse("2026-10-25T00:30:00Z"), Instant.parse("2026-10-25T02:30:00Z"), ZoneId.of("Europe/Paris")))
        store.reserve(reservation); store.retain(LocalCreateIntent(reservation, timed))
        assertEquals(timed, store.readIntent(scope, reservation.localId)?.content)
        assertEquals(EventDetails(), store.readIntent(scope, reservation.localId)?.content?.details)
        val another = reservation.copy(id = MutationId("all-day"), localId = LocalEventId(UUID.randomUUID().toString()))
        val allDay = content.copy(time = EventTime.AllDay(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 27)))
        store.reserve(another); store.retain(LocalCreateIntent(another, allDay))
        assertEquals(allDay, store.readIntent(scope, another.localId)?.content)
        assertNull(store.readIntent(scope, another.localId)?.content?.recurrence); assertNull(store.readIntent(scope, another.localId)?.content?.colorRgb)
    }
    @Test fun revokedLeaseRollsBackNewReservationAndRetentionWithoutReopeningExistingWork() = fixture { _, _, store ->
        var calls = 0; store.admitCapture = { ++calls == 1 }
        refused { store.reserve(reservation) }
        store.admitCapture = { true }; assertNull(store.readReservation(scope, reservation.localId))
        assertTrue(store.reserve(reservation))
        calls = 0; store.admitCapture = { ++calls == 1 }
        refused { store.retain(intent) }
        store.admitCapture = { true }; assertNull(store.readIntent(scope, reservation.localId))
        assertEquals(reservation, store.readReservation(scope, reservation.localId)); assertFalse(store.reserve(reservation))
        assertEquals(intent, store.retain(intent))
    }
    @Test fun inactiveEntryAndLeaseLossAfterReadsNeverReturnForeignOrStaleProof() = fixture { _, _, store ->
        store.reserve(reservation); store.retain(intent)
        store.admitCapture = { false }
        refused { store.reserve(reservation) }; refused { store.retain(intent) }
        refused { store.readReservation(scope, reservation.localId) }; refused { store.readIntent(scope, reservation.localId) }
        var calls = 0; store.admitCapture = { ++calls == 1 }; refused { store.readReservation(scope, reservation.localId) }
        calls = 0; refused { store.readIntent(scope, reservation.localId) }
        store.admitCapture = { true }; assertEquals(intent, store.readIntent(scope, reservation.localId)); assertFalse(store.reserve(reservation))
    }
    @Test fun cancellationBeforeReservationCommitCannotConsumeStampPermission() = fixture { _, _, store ->
        val child = Job(currentCoroutineContext()[Job]); var calls = 0
        store.admitCapture = { if (++calls == 2) child.cancel(); true }
        try { withContext(child) { store.reserve(reservation) }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        finally { store.admitCapture = { true } }
        assertNull(store.readReservation(scope, reservation.localId)); assertTrue(store.reserve(reservation)); assertFalse(store.reserve(reservation))
    }
    @Test fun cancellationBeforeRetentionCommitPreservesReservationForInspectionOnly() = fixture { _, _, store ->
        store.reserve(reservation)
        val child = Job(currentCoroutineContext()[Job]); var calls = 0
        store.admitCapture = { if (++calls == 2) child.cancel(); true }
        try { withContext(child) { store.retain(intent) }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        finally { store.admitCapture = { true } }
        assertNull(store.readIntent(scope, reservation.localId)); assertEquals(reservation, store.readReservation(scope, reservation.localId))
        assertFalse(store.reserve(reservation)); assertEquals(intent, store.retain(intent))
    }
    @Test fun failedInsertOrContentUpdateRollsBackWithoutInventingAdmissionOrIntent() = fixture { _, _, store ->
        store.writableDatabase.execSQL("CREATE TRIGGER refuse_reserve BEFORE INSERT ON local_creates BEGIN SELECT RAISE(ABORT,'fixture'); END")
        refused { store.reserve(reservation) }; assertNull(store.readReservation(scope, reservation.localId))
        store.writableDatabase.execSQL("DROP TRIGGER refuse_reserve"); assertTrue(store.reserve(reservation))
        store.writableDatabase.execSQL("CREATE TRIGGER refuse_retain BEFORE UPDATE ON local_creates BEGIN SELECT RAISE(ABORT,'fixture'); END")
        refused { store.retain(intent) }; assertNull(store.readIntent(scope, reservation.localId)); assertFalse(store.reserve(reservation))
        store.writableDatabase.execSQL("DROP TRIGGER refuse_retain"); assertEquals(intent, store.retain(intent))
    }
    @Test fun contentCopiedFromAnotherIdentityIsRejectedWithoutReopeningReservation() = fixture { _, _, store ->
        store.reserve(reservation); store.retain(intent)
        val another = reservation.copy(id = MutationId("another"), localId = LocalEventId(UUID.randomUUID().toString()))
        store.reserve(another); store.retain(LocalCreateIntent(another, content.copy(title = "Another fixture")))
        replacePayload(store, reservation.localId, payload(store, another.localId))
        refused { store.readIntent(scope, reservation.localId) }; refused { store.retain(intent) }
        assertEquals(reservation, store.readReservation(scope, reservation.localId)); assertFalse(store.reserve(reservation))
        assertEquals("Another fixture", store.readIntent(scope, another.localId)?.content?.title)
    }
    @Test fun unknownSchemaFieldsAndMissingEmptyDetailsProofAreNeverSilentlyDiscarded() = fixture { _, _, store ->
        store.reserve(reservation); store.retain(intent)
        val original = payload(store)
        val altered = listOf(JSONObject(original).put("schema", 2).toString(),
            JSONObject(original).put("knownEmptyDetails", false).toString(), JSONObject(original).put("colorRgb", 0x123456).toString())
        for (candidate in altered) {
            replacePayload(store, reservation.localId, candidate)
            refused { store.readIntent(scope, reservation.localId) }; refused { store.retain(intent) }
            assertFalse(store.reserve(reservation))
        }
        replacePayload(store, reservation.localId, original); assertEquals(intent, store.readIntent(scope, reservation.localId))
    }
}
