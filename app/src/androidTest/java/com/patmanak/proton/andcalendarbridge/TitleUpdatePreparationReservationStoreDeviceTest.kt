// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
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

/** Isolated SQLite identity reservations. No session, network or Calendar Provider access. */
@RunWith(AndroidJUnit4::class)
class TitleUpdatePreparationReservationStoreDeviceTest {
    private val scope = CalendarScope(AccountId("reservation-account"), CalendarId("reservation-calendar"), 3)
    private val content = EventContent("Synthetic original", EventTime.Timed(
        Instant.parse("2026-10-05T10:00:00Z"), Instant.parse("2026-10-05T11:00:00Z"), ZoneId.of("Europe/Paris")),
        "Synthetic description", "Synthetic location", details = EventDetails())
    private val base = RemoteChange.Upsert(EventId("reservation-event"), Revision("revision-before"), content)
    private val intent = LocalEditIntent(MutationId("reservation-intent"), scope, AdapterFormat("fixture-import", 1),
        base, content.copy(title = "Synthetic edited"))
    private val preparation = LocalEditPreparation(intent, Instant.parse("2026-10-05T12:00:00Z"),
        AdapterFormat("fixture-preparation", 1), "opaque-preparation")
    private val candidate = TitleUpdatePreparationReservation(MutationId("reservation-attempt"), preparation,
        AdapterFormat("fixture-update", 1))
    private val prepared = TitleUpdateAttemptRecord(candidate.id, preparation, candidate.format, "opaque-first-wire")
    private val reservations = "title_update_preparation_reservations"

    private fun fixture(block: suspend (Context, String, TitleUpdateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "title-reservation-fixture-${UUID.randomUUID()}.db"
        val store = TitleUpdateAttemptStore(context, name)
        try { block(context, name, store) } finally { store.close(); context.deleteDatabase(name) }
    }
    private fun count(store: TitleUpdateAttemptStore, table: String) = store.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM $table", null).use { check(it.moveToFirst()); it.getInt(0) }
    private suspend fun rejected(block: suspend () -> Any?) {
        val result = try { block() } catch (_: IllegalStateException) { return }
        assertTrue("Corrupt or inconsistent state must not grant admission", result == null || result == false)
    }
    private suspend fun legacyRejected(block: suspend () -> Unit) {
        try { block(); fail("Legacy retention must not bypass reservation ownership") } catch (_: IllegalStateException) { }
    }
    private suspend fun closeAttempt(store: TitleUpdateAttemptStore): TitleUpdateVerifiedResult {
        store.remember(prepared); store.admitDispatch = { it == scope }
        assertTrue(store.claim(prepared)); store.admitDispatch = { false }
        val unknown = prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
        val result = TitleUpdateVerifiedResult.of(unknown, TitleUpdateSubmissionObservation.Verified(
            TitleUpdateAttemptIdentity.of(unknown), base.copy(revision = Revision("revision-after"), content = intent.content)))
        store.retainVerified(result)
        val receipt = TitleUpdateAcknowledgementReceipt.of(result, "b0aa36aa-5a12-4f17-a591-e6c10fb07391")
        assertTrue(store.retainCompletion(TitleUpdateCompletion.of(result,
            TitleUpdateAcknowledgementRecord(receipt, TitleUpdateAcknowledgementState.RESTORED))) { it == scope })
        return result
    }
    private fun next(result: TitleUpdateVerifiedResult) = candidate.copy(id = MutationId("next-attempt"),
        preparation = preparation.copy(intent = intent.copy(id = MutationId("next-intent"), base = result.event,
            content = result.event.content.copy(title = "Synthetic next title"))))

    @Test fun bareReservationReopensWithoutInventingWireOrDispatchAuthority() = fixture { context, name, store ->
        assertEquals(candidate, store.reservePreparation(candidate) { it == scope })
        assertEquals(candidate, store.readPreparationReservation(scope, base.eventId))
        assertNull(store.read(scope, candidate.id)); assertEquals(0, count(store, "title_update_attempts"))
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(candidate, reopened.readPreparationReservation(scope, base.eventId))
            assertEquals(candidate, reopened.reservePreparation(candidate.copy(id = MutationId("replacement-id"))) { it == scope })
            assertNull(reopened.read(scope, candidate.id)); assertEquals(1, count(reopened, reservations))
            reopened.admitDispatch = { true }; assertFalse(reopened.claim(prepared))
        }
    }

    @Test fun independentHandlesRacingDifferentIdsReturnOneCanonicalReservation() = fixture { context, name, store ->
        store.writableDatabase
        TitleUpdateAttemptStore(context, name).use { other ->
            other.writableDatabase
            val second = candidate.copy(id = MutationId("competing-attempt"))
            val gate = CompletableDeferred<Unit>()
            val winners = coroutineScope {
                val a = async(Dispatchers.IO) { gate.await(); store.reservePreparation(candidate) { it == scope } }
                val b = async(Dispatchers.IO) { gate.await(); other.reservePreparation(second) { it == scope } }
                gate.complete(Unit); listOf(a.await(), b.await())
            }
            assertNotNull(winners[0]); assertEquals(winners[0], winners[1])
            assertTrue(winners[0] == candidate || winners[0] == second)
            assertEquals(winners[0], store.readPreparationReservation(scope, base.eventId))
            assertEquals(1, count(other, reservations)); assertEquals(0, count(other, "title_update_attempts"))
        }
    }

    @Test fun retainedWireSurvivesRestartAndCannotBeReplacedByRepeatedPreparation() = fixture { context, name, store ->
        assertEquals(candidate, store.reservePreparation(candidate) { true })
        assertTrue(store.remember(prepared) { it == scope })
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(candidate, reopened.readPreparationReservation(scope, base.eventId))
            assertEquals(candidate, reopened.reservePreparation(candidate.copy(id = MutationId("proposed-again"))) { true })
            assertFalse(reopened.remember(prepared.copy(protectedPayload = "new-randomized-wire")) { true })
            legacyRejected { reopened.remember(prepared.copy(protectedPayload = "new-randomized-wire")) }
            assertEquals(prepared, reopened.read(scope, prepared.id))
        }
    }

    @Test fun compatibleHistoricalPreparedWorkOwnsTheCanonicalId() = fixture { _, _, store ->
        store.remember(prepared)
        assertEquals(candidate, store.reservePreparation(candidate.copy(id = MutationId("new-proposal"))) { true })
        assertEquals(candidate, store.readPreparationReservation(scope, base.eventId))
        assertEquals(prepared, store.read(scope, prepared.id)); assertEquals(1, count(store, "title_update_attempts"))
    }

    @Test fun completeCapsuleAndWireFormatSubstitutionsCannotReuseBareReservation() = fixture { _, _, store ->
        assertEquals(candidate, store.reservePreparation(candidate) { true })
        val variants = listOf(
            candidate.copy(format = AdapterFormat("different-wire", 1)),
            candidate.copy(format = candidate.format.copy(version = 2)),
            candidate.copy(preparation = preparation.copy(timestamp = preparation.timestamp.plusSeconds(1))),
            candidate.copy(preparation = preparation.copy(protectedPayload = "different-capsule")),
            candidate.copy(preparation = preparation.copy(format = AdapterFormat("different-preparation", 1))),
            candidate.copy(preparation = preparation.copy(intent = intent.copy(id = MutationId("different-intent")))),
            candidate.copy(preparation = preparation.copy(intent = intent.copy(format = AdapterFormat("different-import", 1)))),
            candidate.copy(preparation = preparation.copy(intent = intent.copy(base = base.copy(revision = Revision("different-base"))))),
            candidate.copy(preparation = preparation.copy(intent = intent.copy(content = intent.content.copy(title = "Different title")))),
        )
        for (value in variants) assertNull(store.reservePreparation(value) { true })
        assertEquals(candidate, store.readPreparationReservation(scope, base.eventId)); assertEquals(1, count(store, reservations))
    }

    @Test fun bareReservationBlocksLegacyAndGuardedBypassAcrossGenerations() = fixture { _, _, store ->
        assertEquals(candidate, store.reservePreparation(candidate) { true })
        val alternatives = listOf(prepared.copy(id = MutationId("bypass-id")), prepared.copy(
            id = MutationId("bypass-generation"), preparation = preparation.copy(intent = intent.copy(scope = scope.copy(generation = 4)))))
        for (value in alternatives) {
            val reserved = store.reservePreparation(TitleUpdatePreparationReservation.of(value)) { true }
            if (value.scope == scope) assertEquals(candidate, reserved) else assertNull(reserved)
            assertFalse(store.remember(value) { true }); legacyRejected { store.remember(value) }
        }
        assertNull(store.readPreparationReservation(scope.copy(generation = 4), base.eventId))
        assertEquals(0, count(store, "title_update_attempts")); assertEquals(candidate, store.readPreparationReservation(scope, base.eventId))
        assertTrue(store.remember(prepared) { true })
    }

    @Test fun unknownReservationIsReadableButNeverPreparationAdmission() = fixture { _, _, store ->
        assertEquals(candidate, store.reservePreparation(candidate) { true }); store.remember(prepared)
        store.admitDispatch = { true }; assertTrue(store.claim(prepared))
        store.retainEvidence(prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), "opaque-evidence")
        val original = store.read(scope, prepared.id)
        assertEquals(candidate, store.readPreparationReservation(scope, base.eventId))
        assertNull(store.reservePreparation(candidate) { true })
        assertNull(store.reservePreparation(candidate.copy(id = MutationId("unknown-bypass"))) { true })
        assertFalse(store.remember(prepared) { true }); assertFalse(store.claim(prepared))
        assertEquals(original, store.read(scope, prepared.id))
    }

    @Test fun accountsCalendarsEventsAndExactGenerationKeepReservationsScoped() = fixture { _, _, store ->
        assertEquals(candidate, store.reservePreparation(candidate) { true })
        val alternatives = listOf(
            candidate.copy(preparation = preparation.copy(intent = intent.copy(scope = scope.copy(account = AccountId("other-account"))))),
            candidate.copy(preparation = preparation.copy(intent = intent.copy(scope = scope.copy(calendar = CalendarId("other-calendar"))))),
            candidate.copy(id = MutationId("other-event-attempt"), preparation = preparation.copy(intent = intent.copy(
                id = MutationId("other-event-intent"), base = base.copy(eventId = EventId("other-event"))))),
        )
        for (value in alternatives) {
            assertEquals(value, store.reservePreparation(value) { it == value.scope })
            assertEquals(value, store.readPreparationReservation(value.scope, value.eventId))
        }
        assertNull(store.readPreparationReservation(scope.copy(generation = 4), base.eventId))
        assertEquals(candidate, store.readPreparationReservation(scope, base.eventId)); assertEquals(4, count(store, reservations))
    }

    @Test fun validCompletionReleasesOnlyLogicalOwnerAndPreservesUnknownHistory() = fixture { _, _, store ->
        assertEquals(candidate, store.reservePreparation(candidate) { true })
        val result = closeAttempt(store)
        val original = store.read(scope, candidate.id)
        assertNull(store.readPreparationReservation(scope, base.eventId))
        val second = next(result)
        assertEquals(second, store.reservePreparation(second) { true })
        assertEquals(second, store.readPreparationReservation(scope, base.eventId))
        assertEquals(2, count(store, reservations)); assertEquals(original, store.read(scope, candidate.id))
        assertEquals(result, store.readVerified(scope, candidate.id)); assertNotNull(store.readCompletion(scope, candidate.id))
        assertNull(store.reservePreparation(candidate) { true })
        store.admitDispatch = { true }; assertFalse(store.claim(prepared))
    }

    @Test fun corruptCompletionNeverReleasesReservationOrAllowsNextWire() = fixture { _, _, store ->
        assertEquals(candidate, store.reservePreparation(candidate) { true })
        val result = closeAttempt(store)
        store.writableDatabase.execSQL("UPDATE title_update_attempts SET completion='{}'")
        val second = next(result)
        rejected { store.readPreparationReservation(scope, base.eventId) }
        rejected { store.reservePreparation(second) { true } }
        rejected { store.remember(TitleUpdateAttemptRecord(second.id, second.preparation, second.format, "next-wire")) { true } }
        assertNull(store.read(scope, second.id)); assertEquals(1, count(store, reservations))
    }

    @Test fun corruptReservationBlocksReadReserveRememberAndClaim() = fixture { _, _, store ->
        assertEquals(candidate, store.reservePreparation(candidate) { true }); store.remember(prepared)
        store.writableDatabase.execSQL("UPDATE $reservations SET preparation='{}'")
        rejected { store.readPreparationReservation(scope, base.eventId) }
        rejected { store.reservePreparation(candidate) { true } }
        rejected { store.remember(prepared) { true } }
        legacyRejected { store.remember(prepared) }
        store.admitDispatch = { true }; rejected { store.claim(prepared) }
        assertEquals(prepared, store.read(scope, prepared.id))
    }

    @Test fun movedReservationEventCannotHideItsOwnershipAndAdmitDispatch() = fixture { _, _, store ->
        assertEquals(candidate, store.reservePreparation(candidate) { true }); store.remember(prepared)
        store.writableDatabase.execSQL("UPDATE $reservations SET event='other-event' WHERE mutation=?", arrayOf(candidate.id.value))
        store.admitDispatch = { true }
        // Exercise the irreversible boundary first: event-filtered history must not hide
        // a reservation whose metadata now contradicts its unchanged complete capsule.
        rejected { store.claim(prepared) }
        assertEquals(prepared, store.read(scope, prepared.id))
        rejected { store.readPreparationReservation(scope, base.eventId) }
        rejected { store.reservePreparation(candidate) { true } }
        rejected { store.remember(prepared) { true } }
        legacyRejected { store.remember(prepared) }
        assertEquals(prepared, store.read(scope, prepared.id))
        assertEquals(1, count(store, reservations)); assertEquals(1, count(store, "title_update_attempts"))
    }

    @Test fun movedAttemptEventCannotTurnItsReservationIntoAnUnclaimedBareOwner() = fixture { _, _, store ->
        assertEquals(candidate, store.reservePreparation(candidate) { true }); store.remember(prepared)
        store.writableDatabase.execSQL("UPDATE title_update_attempts SET event='other-event' WHERE mutation=?", arrayOf(prepared.id.value))
        store.admitDispatch = { true }
        rejected { store.claim(prepared) }
        rejected { store.readPreparationReservation(scope, base.eventId) }
        rejected { store.reservePreparation(candidate) { true } }
        rejected { store.remember(prepared) { true } }
        legacyRejected { store.remember(prepared) }
        store.readableDatabase.rawQuery("SELECT state,payload,evidence,event FROM title_update_attempts WHERE mutation=?",
            arrayOf(prepared.id.value)).use {
            assertTrue(it.moveToFirst()); assertEquals(TitleUpdateAttemptState.PREPARED.name, it.getString(0))
            assertEquals(prepared.protectedPayload, it.getString(1)); assertTrue(it.isNull(2))
            assertEquals("other-event", it.getString(3)); assertFalse(it.moveToNext())
        }
        assertEquals(1, count(store, reservations)); assertEquals(1, count(store, "title_update_attempts"))
    }

    @Test fun leaseDenialAndRevocationAfterObservableInsertRollbackOnReopen() = fixture { context, name, store ->
        var calls = 0
        assertNull(store.reservePreparation(candidate) { assertEquals(scope, it); calls++; false })
        assertEquals(1, calls); assertEquals(0, count(store, reservations))
        calls = 0
        rejected {
            store.reservePreparation(candidate) {
                assertEquals(scope, it)
                if (++calls == 1) { assertFalse(store.writableDatabase.inTransaction()); true }
                else {
                    assertTrue(store.writableDatabase.inTransaction()); assertEquals(1, count(store, reservations)); false
                }
            }
        }
        assertTrue(calls >= 2); assertEquals(0, count(store, reservations)); store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertNull(reopened.readPreparationReservation(scope, base.eventId))
            assertEquals(candidate, reopened.reservePreparation(candidate) { it == scope })
        }
    }

    @Test fun identicalReplayStillChecksLeaseImmediatelyBeforeCommit() = fixture { _, _, store ->
        assertEquals(candidate, store.reservePreparation(candidate) { true })
        var calls = 0
        rejected { store.reservePreparation(candidate.copy(id = MutationId("second-proposal"))) {
            if (++calls == 1) true else { assertTrue(store.writableDatabase.inTransaction()); false }
        } }
        assertTrue(calls >= 2); assertEquals(1, count(store, reservations))
        assertEquals(candidate, store.readPreparationReservation(scope, base.eventId))
    }

    @Test fun cancellationAfterInsertRollsBackWithoutCancellingFixtureCleanup() = fixture { context, name, store ->
        var calls = 0
        try {
            store.reservePreparation(candidate) {
                if (++calls > 1) {
                    assertTrue(store.writableDatabase.inTransaction()); assertEquals(1, count(store, reservations))
                    throw CancellationException("Synthetic reservation cancellation")
                }
                true
            }
            fail("Reservation cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(calls >= 2); assertEquals(0, count(store, reservations)); store.close()
        TitleUpdateAttemptStore(context, name).use { reopened -> assertNull(reopened.readPreparationReservation(scope, base.eventId)) }
    }

    @Test fun migrationVersionsOneTwoAndThreePreserveAllOpaqueHistoricalBytes() = fixture { context, _, _ ->
        for (version in 1..3) {
            val name = "title-reservation-legacy-${UUID.randomUUID()}.db"
            try {
                context.getDatabasePath(name).parentFile!!.mkdirs()
                SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { raw ->
                    raw.execSQL("CREATE TABLE title_update_attempts(account TEXT NOT NULL,calendar TEXT NOT NULL,generation INTEGER NOT NULL,mutation TEXT NOT NULL,event TEXT NOT NULL,preparation TEXT NOT NULL,adapter TEXT NOT NULL,version INTEGER NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL,evidence TEXT${if (version >= 2) ",result TEXT" else ""}${if (version >= 3) ",completion TEXT" else ""},PRIMARY KEY(account,calendar,generation,mutation)${if (version < 3) ",UNIQUE(account,calendar,event)" else ""})")
                    if (version == 3) raw.execSQL("CREATE UNIQUE INDEX title_update_unresolved_event ON title_update_attempts(account,calendar,event) WHERE completion IS NULL")
                    raw.insertOrThrow("title_update_attempts", null, ContentValues().apply {
                        put("account", "legacy-account"); put("calendar", "legacy-calendar"); put("generation", 7)
                        put("mutation", "legacy-attempt"); put("event", "legacy-event"); put("preparation", "opaque legacy preparation\n")
                        put("adapter", "legacy-adapter"); put("version", 2); put("payload", "opaque legacy ciphertext\n")
                        put("state", "OUTCOME_UNKNOWN"); put("evidence", "opaque legacy evidence\n")
                        if (version >= 2) put("result", "opaque legacy result\n")
                        if (version >= 3) put("completion", "opaque legacy completion\n")
                    })
                    raw.execSQL("CREATE TRIGGER prohibit_historical_update BEFORE UPDATE ON title_update_attempts BEGIN SELECT RAISE(ABORT,'Historical rows are immutable'); END")
                    raw.version = version
                }
                TitleUpdateAttemptStore(context, name).use { migrated ->
                    assertEquals(4, migrated.readableDatabase.version); assertEquals(0, count(migrated, reservations))
                    migrated.readableDatabase.rawQuery("SELECT preparation,payload,evidence,result,completion,state FROM title_update_attempts", null).use {
                        assertTrue(it.moveToFirst()); assertEquals("opaque legacy preparation\n", it.getString(0))
                        assertEquals("opaque legacy ciphertext\n", it.getString(1)); assertEquals("opaque legacy evidence\n", it.getString(2))
                        assertEquals(if (version >= 2) "opaque legacy result\n" else null, it.getString(3))
                        assertEquals(if (version >= 3) "opaque legacy completion\n" else null, it.getString(4))
                        assertEquals("OUTCOME_UNKNOWN", it.getString(5)); assertFalse(it.moveToNext())
                    }
                }
            } finally { context.deleteDatabase(name) }
        }
    }
}
