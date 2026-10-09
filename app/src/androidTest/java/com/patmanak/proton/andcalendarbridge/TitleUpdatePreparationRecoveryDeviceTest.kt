// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real isolated SQLite with controlled preparation; no service or Provider operations. */
@RunWith(AndroidJUnit4::class)
class TitleUpdatePreparationRecoveryDeviceTest {
    private val scope = CalendarScope(AccountId("recovery-account"), CalendarId("recovery-calendar"), 1)
    private val content = EventContent("Synthetic original", EventTime.AllDay(LocalDate.parse("2026-10-09"),
        LocalDate.parse("2026-10-10")), details = EventDetails())
    private val preparation = LocalEditPreparation(LocalEditIntent(MutationId("recovery-intent"), scope,
        AdapterFormat("fixture-import", 1), RemoteChange.Upsert(EventId("recovery-event"), Revision("before"), content),
        content.copy(title = "Synthetic changed")), Instant.parse("2026-10-09T12:00:00Z"),
        AdapterFormat("fixture-preparation", 1), "synthetic-opaque-capsule")
    private val candidate = TitleUpdatePreparationReservation(MutationId("recovery-attempt"), preparation,
        AdapterFormat("fixture-wire", 1))
    private fun wire(reservation: TitleUpdatePreparationReservation, payload: String = "synthetic-first-wire") =
        TitleUpdateAttemptRecord(reservation.id, reservation.preparation, reservation.format, payload)

    private class Port(override val titleUpdateFormat: AdapterFormat,
        val prepare: suspend (TitleUpdatePreparationReservation, (CalendarScope) -> Boolean) -> TitleUpdateAttemptRecord?) : CalendarTitleUpdateSubmissionPort {
        var calls = 0
        override suspend fun prepareSubmission(reservation: TitleUpdatePreparationReservation, admit: (CalendarScope) -> Boolean): TitleUpdateAttemptRecord? {
            calls++; return prepare(reservation, admit)
        }
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord = error("No unrestricted preparation")
        override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation = error("No submission")
        override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation = error("No inspection")
    }
    private fun fixture(block: suspend (Context, String, TitleUpdateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "title-preparation-recovery-${UUID.randomUUID()}.db"
        val store = TitleUpdateAttemptStore(context, name)
        try { block(context, name, store) } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun reopeningBareThenPreparedWorkKeepsCanonicalIdentityAndNeverPreparesDurableWireAgain() = fixture { context, name, store ->
        assertEquals(candidate, store.reservePreparation(candidate) { true }); store.close()
        val port = Port(candidate.format) { reserved, admit -> assertEquals(candidate, reserved); assertTrue(admit(scope)); wire(reserved) }
        TitleUpdateAttemptStore(context, name).use { reopened ->
            val recovery = TitleUpdatePreparationRecovery(reopened, port)
            assertNull(recovery.prepare(candidate.copy(id = MutationId("proposed-after-restart"))) { true })
            assertEquals(0, port.calls)
            assertEquals(wire(candidate), recovery.prepare(candidate.copy(id = MutationId("another-proposal")), true) { true })
            assertEquals(1, port.calls)
        }
        TitleUpdateAttemptStore(context, name).use { reopened ->
            val forbidden = Port(candidate.format) { _, _ -> error("Durable replay must not prepare") }
            assertEquals(wire(candidate), TitleUpdatePreparationRecovery(reopened, forbidden).prepare(candidate, true) { true })
            assertEquals(0, forbidden.calls); assertEquals(candidate, reopened.readPreparationReservation(scope, candidate.eventId))
        }
    }

    @Test fun failedWireCommitPreservesReservationForCanonicalRetryWithoutInventingSuccess() = fixture { _, _, store ->
        val ids = mutableListOf<MutationId>()
        val port = Port(candidate.format) { reserved, _ -> ids += reserved.id; wire(reserved, "synthetic-wire-${ids.size}") }
        store.writableDatabase.execSQL("CREATE TRIGGER reject_recovery_wire BEFORE INSERT ON title_update_attempts BEGIN SELECT RAISE(ABORT,'Synthetic failed wire commit'); END")
        try {
            TitleUpdatePreparationRecovery(store, port).prepare(candidate, true) { true }; fail("A failed commit must propagate")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(candidate, store.readPreparationReservation(scope, candidate.eventId)); assertNull(store.read(scope, candidate.id))
        store.writableDatabase.execSQL("DROP TRIGGER reject_recovery_wire")
        val retained = TitleUpdatePreparationRecovery(store, port).prepare(candidate.copy(id = MutationId("retry-proposal")), true) { true }
        assertEquals(wire(candidate, "synthetic-wire-2"), retained); assertEquals(listOf(candidate.id, candidate.id), ids)
        assertEquals(retained, store.read(scope, candidate.id))
    }

    @Test fun independentHelperWinningWithDifferentWireReturnsOnlyItsDurableRecord() = fixture { context, name, store ->
        TitleUpdateAttemptStore(context, name).use { other ->
            val winner = wire(candidate, "synthetic-independent-first-wire")
            val port = Port(candidate.format) { reserved, _ ->
                assertEquals(candidate, reserved); assertTrue(other.remember(winner) { true })
                wire(reserved, "synthetic-losing-randomized-wire")
            }
            assertEquals(winner, TitleUpdatePreparationRecovery(store, port).prepare(candidate, true) { true })
            assertEquals(winner, store.read(scope, candidate.id)); assertEquals(1, port.calls)
        }
    }

    @Test fun unknownTransitionDuringPreparationNeverReturnsOrRequeuesPreparedWork() = fixture { context, name, store ->
        TitleUpdateAttemptStore(context, name).use { other ->
            val port = Port(candidate.format) { reserved, _ ->
                val first = wire(reserved); assertTrue(other.remember(first) { true })
                other.admitDispatch = { true }; assertTrue(other.claim(first)); wire(reserved, "synthetic-later-wire")
            }
            assertNull(TitleUpdatePreparationRecovery(store, port).prepare(candidate, true) { true })
            val unknown = wire(candidate).copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
            assertEquals(unknown, store.read(scope, candidate.id))
            assertNull(TitleUpdatePreparationRecovery(store, port).prepare(candidate, true) { true })
            assertEquals(1, port.calls); assertEquals(unknown, store.read(scope, candidate.id))
        }
    }
}
