// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated opt-in SQLite fixtures. No account, Provider or network mutation. */
@RunWith(AndroidJUnit4::class)
class ConflictResolutionFenceDeviceTest {
    private val scope = CalendarScope(AccountId("fence-fixture-account"), CalendarId("fence-fixture-calendar"), 2)
    private val event = EventId("fence-fixture-event")
    private val local = MutationId("fence-fixture-local")
    private val fence = ConflictResolutionFence(scope, event, local, MutationId("fence-fixture-resolution"), "a".repeat(64))
    private val finalization = ConflictResolutionFinalization(fence, "b".repeat(64))
    private fun prepared(generation: Long = scope.generation, localId: MutationId = local,
        request: MutationId = MutationId("fence-fixture-request")): TitleUpdateAttemptRecord {
        val content = EventContent("Synthetic original", EventTime.Timed(Instant.parse("2026-10-05T10:00:00Z"),
            Instant.parse("2026-10-05T11:00:00Z"), ZoneId.of("UTC")), details = EventDetails())
        val intent = LocalEditIntent(localId, scope.copy(generation = generation), AdapterFormat("fixture", 1),
            RemoteChange.Upsert(event, Revision("fixture-revision"), content), content.copy(title = "Synthetic changed"))
        return TitleUpdateAttemptRecord(request, LocalEditPreparation(intent, Instant.parse("2026-10-05T12:00:00Z"),
            AdapterFormat("fixture-preparation", 1), "opaque-fixture"), AdapterFormat("fixture-wire", 1), "opaque-wire")
    }
    private fun fixture(block: suspend (Context, String, TitleUpdateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "conflict-fence-fixture-${UUID.randomUUID()}.db"
        val store = TitleUpdateAttemptStore(context, name)
        try { block(context, name, store) } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun exactFenceReopensAndReleasedTombstoneBlocksOldWorkerAcrossGenerations() = fixture { context, name, store ->
        assertTrue(store.beginConflictResolutionFence(fence) { true })
        assertTrue(store.beginConflictResolutionFence(fence) { true })
        assertFalse(store.beginConflictResolutionFence(fence.copy(fingerprint = "c".repeat(64))) { true })
        assertFalse(store.remember(prepared(), { true }))
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(fence, reopened.readConflictResolutionFence(scope.copy(generation = 99), event))
            assertFalse(reopened.releaseConflictResolutionFence(finalization.copy(fence = fence.copy(fingerprint = "c".repeat(64)))) { true })
            assertTrue(reopened.releaseConflictResolutionFence(finalization) { true })
            assertTrue(reopened.releaseConflictResolutionFence(finalization) { true })
            assertFalse(reopened.releaseConflictResolutionFence(finalization.copy(proofFingerprint = "d".repeat(64))) { true })
            assertNull(reopened.readConflictResolutionFence(scope, event))
            assertFalse(reopened.beginConflictResolutionFence(fence) { true })
            assertFalse(reopened.remember(prepared(generation = 99), { true }))
            assertFalse(reopened.remember(prepared(generation = 99, localId = MutationId("new-local"), request = fence.resolutionId), { true }))
            assertTrue(reopened.remember(prepared(generation = 99, localId = MutationId("new-local"), request = MutationId("new-request")), { true }))
        }
    }

    @Test fun acquisitionBudgetDoesNotEvictOrIgnoreUnrelatedWork() = fixture { _, _, store ->
        suspend fun rememberUnrelated(index: Int) {
            val initial = prepared(localId = MutationId("unrelated-local-$index"), request = MutationId("unrelated-request-$index"))
            val intent = initial.preparation.intent
            val work = initial.copy(preparation = initial.preparation.copy(
                intent = intent.copy(base = intent.base.copy(eventId = EventId("unrelated-event-$index")))))
            assertTrue(store.remember(work) { true })
        }
        repeat(128) { rememberUnrelated(it) }
        assertTrue(store.beginConflictResolutionFence(fence) { true })
        assertTrue(store.releaseConflictResolutionFence(finalization) { true })
        rememberUnrelated(128)
        val next = fence.copy(eventId = EventId("next-event"), localMutationId = MutationId("next-local"),
            resolutionId = MutationId("next-resolution"))
        try { store.beginConflictResolutionFence(next) { true }; fail("Excess history must refuse") }
        catch (_: IllegalStateException) { }
        assertNull(store.readConflictResolutionFence(scope, next.eventId))
        assertEquals(129, store.readableDatabase.rawQuery("SELECT count(*) FROM title_update_attempts", null).use {
            assertTrue(it.moveToFirst()); it.getInt(0)
        })
    }

    @Test fun acquisitionNeverCreatesAnUnreadableHistoryAtTheRetentionBoundary() = fixture { _, _, store ->
        repeat(128) { index ->
            val historical = fence.copy(localMutationId = MutationId("historical-local-$index"),
                resolutionId = MutationId("historical-resolution-$index"))
            assertTrue(store.beginConflictResolutionFence(historical) { true })
            assertEquals(historical, store.readConflictResolutionFence(scope, event))
            assertTrue(store.releaseConflictResolutionFence(ConflictResolutionFinalization(historical, "b".repeat(64))) { true })
        }
        assertNull(store.readConflictResolutionFence(scope, event))
        assertFalse("Do not insert a fence that its own recovery reader cannot qualify",
            store.beginConflictResolutionFence(fence) { true })
        assertNull(store.readConflictResolutionFence(scope, event))
        assertEquals(128, store.readableDatabase.rawQuery("SELECT count(*) FROM conflict_resolution_fences", null).use {
            assertTrue(it.moveToFirst()); it.getInt(0)
        })
    }

    @Test fun preparedUnknownAndBareReservationRefuseWithoutRetiringAnything() = fixture { _, _, store ->
        val work = prepared()
        assertNotNull(store.reservePreparation(TitleUpdatePreparationReservation.of(work)) { true })
        assertFalse(store.beginConflictResolutionFence(fence) { true })
        assertTrue(store.remember(work, { true }))
        assertFalse(store.beginConflictResolutionFence(fence) { true })
        assertTrue(store.claim(work) { true })
        assertFalse(store.beginConflictResolutionFence(fence) { true })
        assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, store.read(scope, work.id)?.state)
    }

    @Test fun twoHelpersSerializeBeginAgainstCapturedWorkerRemember() = fixture { context, name, store ->
        TitleUpdateAttemptStore(context, name).use { other ->
            other.writableDatabase
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val begin = async(Dispatchers.IO) { start.await(); store.beginConflictResolutionFence(fence) { true } }
                val remember = async(Dispatchers.IO) { start.await(); other.remember(prepared(), { true }) }
                start.complete(Unit)
                assertTrue(begin.await() xor remember.await())
            }
        }
    }

    @Test fun cancellationAndDeniedLeaseCannotLeaveFence() = fixture { _, _, store ->
        assertFalse(store.beginConflictResolutionFence(fence) { false })
        try {
            store.beginConflictResolutionFence(fence) { throw CancellationException("Synthetic cancellation") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertNull(store.readConflictResolutionFence(scope, event))
    }

    @Test fun twoHelpersCannotFenceWhilePreparedWorkerClaims() = fixture { context, name, store ->
        val work = prepared()
        assertTrue(store.remember(work) { true })
        TitleUpdateAttemptStore(context, name).use { other ->
            other.writableDatabase
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val begin = async(Dispatchers.IO) { start.await(); store.beginConflictResolutionFence(fence) { true } }
                val claim = async(Dispatchers.IO) { start.await(); other.claim(work) { true } }
                start.complete(Unit)
                assertFalse(begin.await()); assertTrue(claim.await())
            }
        }
        assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, store.read(scope, work.id)?.state)
        assertNull(store.readConflictResolutionFence(scope, event))
    }

    @Test fun cancellationDuringFinalAdmissionRollsBackFence() = fixture { _, _, store ->
        var admissions = 0
        try {
            store.beginConflictResolutionFence(fence) {
                if (++admissions == 2) throw CancellationException("Synthetic cancelled commit")
                true
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(2, admissions)
        assertNull(store.readConflictResolutionFence(scope, event))
    }

    @Test fun corruptFenceCannotBeMistakenForAbsence() = fixture { _, _, store ->
        assertTrue(store.beginConflictResolutionFence(fence) { true })
        store.writableDatabase.execSQL("UPDATE conflict_resolution_fences SET fingerprint=zeroblob(5000)")
        try { store.readConflictResolutionFence(scope, event); fail("Corrupt fence must refuse") }
        catch (_: IllegalStateException) { }
    }

    @Test fun migrationTenToElevenPreservesAllOpaqueOldBytes() = fixture { context, name, store ->
        assertTrue(store.remember(prepared(), { true }))
        val db = store.writableDatabase
        // Reconstruct the exact pre-fence schema without invoking the upgrade implementation.
        db.execSQL("DROP TABLE conflict_resolution_fences")
        val tables = listOf("text_update_attempts", "time_update_attempts", "personal_update_attempts", "delete_attempts")
        for (table in tables) {
            db.execSQL("INSERT INTO $table(account,calendar,generation,mutation,event,state,capsule,result,completion) " +
                "VALUES('old-account','old-calendar',1,'old-request','old-event','OUTCOME_UNKNOWN',X'00FFFE',X'0100FF',X'FF0002')")
        }
        db.version = 10
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(11, reopened.writableDatabase.version)
            assertEquals(prepared(), reopened.read(scope, prepared().id))
            for (table in tables) {
                reopened.readableDatabase.rawQuery("SELECT hex(capsule),hex(result),hex(completion),state FROM $table", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("00FFFE", it.getString(0))
                    assertEquals("0100FF", it.getString(1)); assertEquals("FF0002", it.getString(2))
                    assertEquals("OUTCOME_UNKNOWN", it.getString(3))
                }
            }
            assertFalse(reopened.beginConflictResolutionFence(fence) { true })
        }
    }

    /** Return the captured worker's claim, so refusal also proves its authority was not hijacked. */
    private suspend fun rememberFamily(store: TitleUpdateAttemptStore, family: Int,
        title: TitleUpdateAttemptRecord): suspend () -> Boolean {
        val intent = title.preparation.intent
        val timestamp = title.preparation.timestamp
        val format = AdapterFormat("interevent-fixture", 1)
        return when (family) {
            0 -> {
                assertTrue(store.remember(title) { true })
                suspend { store.claim(title) { true } }
            }
            1 -> {
                val work = TextUpdateAttemptRecord(title.id, LocalTextEditPreparation(
                    intent.copy(content = intent.base.content.copy(description = "Synthetic notes")), timestamp,
                    format, "opaque-preparation"), format, "opaque-wire")
                assertTrue(store.textJournal.remember(work) { true })
                suspend { store.textJournal.claim(work) { true } }
            }
            2 -> {
                val work = TimeUpdateAttemptRecord(title.id, LocalTimeEditPreparation(
                    intent.copy(content = intent.base.content.copy(time = EventTime.Timed(
                        Instant.parse("2026-10-06T10:00:00Z"), Instant.parse("2026-10-06T11:00:00Z"), ZoneId.of("UTC")))),
                    timestamp, format, "opaque-preparation"), format, "opaque-wire")
                assertTrue(store.timeJournal.remember(work) { true })
                suspend { store.timeJournal.claim(work) { true } }
            }
            3 -> {
                val work = PersonalUpdateAttemptRecord(title.id, PersonalEditPreparation(intent, timestamp,
                    format, "opaque-preparation"), format, "opaque-wire")
                assertTrue(store.personalJournal.remember(work) { true })
                suspend { store.personalJournal.claim(work) { true } }
            }
            4 -> {
                val work = DeleteAttemptRecord(title.id, LocalDeleteIntent(intent.id, intent.scope,
                    intent.format, intent.base, intent.base.content), format, "opaque-wire")
                assertTrue(store.deleteJournal.remember(work) { true })
                suspend { store.deleteJournal.claim(work) { true } }
            }
            else -> {
                val reservation = TitleUpdatePreparationReservation.of(title)
                assertEquals(reservation, store.reservePreparation(reservation) { true })
                suspend {
                    assertEquals(reservation, store.readPreparationReservation(title.scope, title.preparation.intent.base.eventId))
                    true
                }
            }
        }
    }

    @Test fun fenceCannotHijackOtherEventRequestOrLocalIdentityInAnyFamilyOrReservation() {
        for (family in 0..5) for (ownerIsRequest in listOf(false, true)) for (fenceUsesResolution in listOf(false, true)) {
            fixture { _, _, store ->
                val work = prepared(generation = 1)
                val claim = rememberFamily(store, family, work)
                val collision = if (ownerIsRequest) work.id else work.preparation.intent.id
                val candidate = fence.copy(scope = scope.copy(generation = 99), eventId = EventId("another-fixture-event"),
                    localMutationId = if (fenceUsesResolution) MutationId("unrelated-local") else collision,
                    resolutionId = if (fenceUsesResolution) collision else MutationId("unrelated-resolution"))
                assertFalse(store.beginConflictResolutionFence(candidate) { true })
                assertNull(store.readConflictResolutionFence(candidate.scope, candidate.eventId))
                assertTrue(claim())
            }
        }
    }

    @Test fun allFamiliesConsultFenceAndKeepOldUnknownHistory() = fixture { context, name, store ->
        val title = prepared()
        val baseIntent = title.preparation.intent
        val text = TextUpdateAttemptRecord(MutationId("text-request"), LocalTextEditPreparation(
            baseIntent.copy(content = baseIntent.base.content.copy(description = "Synthetic notes")),
            title.preparation.timestamp, AdapterFormat("fixture-text", 1), "opaque-text"),
            AdapterFormat("fixture-wire", 1), "opaque-wire")
        val time = TimeUpdateAttemptRecord(MutationId("time-request"), LocalTimeEditPreparation(
            baseIntent.copy(content = baseIntent.base.content.copy(time = EventTime.Timed(
                Instant.parse("2026-10-06T10:00:00Z"), Instant.parse("2026-10-06T11:00:00Z"), ZoneId.of("UTC")))),
            title.preparation.timestamp, AdapterFormat("fixture-time", 1), "opaque-time"),
            AdapterFormat("fixture-wire", 1), "opaque-wire")
        val personal = PersonalUpdateAttemptRecord(MutationId("personal-request"), PersonalEditPreparation(
            baseIntent, title.preparation.timestamp, AdapterFormat("fixture-personal", 1), "opaque-personal"),
            AdapterFormat("fixture-wire", 1), "opaque-wire")
        val delete = DeleteAttemptRecord(MutationId("delete-request"), LocalDeleteIntent(local, scope,
            baseIntent.format, baseIntent.base, baseIntent.base.content), AdapterFormat("fixture-wire", 1), "opaque-wire")
        assertTrue(store.beginConflictResolutionFence(fence) { true })
        assertFalse(store.textJournal.remember(text) { true }); assertFalse(store.textJournal.claim(text) { true })
        assertFalse(store.timeJournal.remember(time) { true }); assertFalse(store.timeJournal.claim(time) { true })
        assertFalse(store.personalJournal.remember(personal) { true }); assertFalse(store.personalJournal.claim(personal) { true })
        assertFalse(store.deleteJournal.remember(delete) { true }); assertFalse(store.deleteJournal.claim(delete) { true })
        assertTrue(store.releaseConflictResolutionFence(finalization) { true })
        assertFalse(store.textJournal.remember(text) { true })
        assertFalse(store.timeJournal.remember(time) { true })
        assertFalse(store.personalJournal.remember(personal) { true })
        assertFalse(store.deleteJournal.remember(delete) { true })
        // Independent database per family proves pre-existing PREPARED and UNKNOWN refuse fencing.
        for (family in 0..3) {
            val isolatedName = "$name-family-$family"
            TitleUpdateAttemptStore(context, isolatedName).use { isolated ->
                when (family) {
                    0 -> assertTrue(isolated.textJournal.remember(text) { true })
                    1 -> assertTrue(isolated.timeJournal.remember(time) { true })
                    2 -> assertTrue(isolated.personalJournal.remember(personal) { true })
                    else -> assertTrue(isolated.deleteJournal.remember(delete) { true })
                }
                assertFalse(isolated.beginConflictResolutionFence(fence.copy(scope = scope.copy(generation = 99))) { true })
                when (family) {
                    0 -> assertTrue(isolated.textJournal.claim(text) { true })
                    1 -> assertTrue(isolated.timeJournal.claim(time) { true })
                    2 -> assertTrue(isolated.personalJournal.claim(personal) { true })
                    else -> assertTrue(isolated.deleteJournal.claim(delete) { true })
                }
                assertFalse(isolated.beginConflictResolutionFence(fence) { true })
            }
            context.deleteDatabase(isolatedName)
        }
    }
}
