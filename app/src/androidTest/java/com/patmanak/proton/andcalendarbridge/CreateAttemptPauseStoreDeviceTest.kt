// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.OwnedEventWitness
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreatePauseReason
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in isolated SQLite fixtures; no account, network, or Calendar Provider access. */
@RunWith(AndroidJUnit4::class)
class CreateAttemptPauseStoreDeviceTest {
    private val scope = CalendarScope(AccountId("pause-fixture-account"), CalendarId("pause-fixture-calendar"), 0)
    private val prepared = CreateAttemptRecord(MutationId("pause-fixture-mutation"), scope,
        AdapterFormat("synthetic-protected-fixture", 1), "synthetic-opaque-payload-not-keystore-proof")
    private val unknown = prepared.copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
    private val evidence = "synthetic-opaque-evidence-not-keystore-proof"
    private val event = RemoteChange.Upsert(EventId("pause-fixture-event"), Revision("pause-fixture-first-revision"),
        EventContent("Synthetic pause fixture", EventTime.AllDay(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2))))

    private fun fixture(block: suspend (Context, String, CreateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "create-pause-fixture-${UUID.randomUUID()}.db"
        val store = CreateAttemptStore(context, name)
        try { block(context, name, store) }
        finally { store.close(); context.deleteDatabase(name) }
    }

    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected synthetic dispatch rejection") }
        catch (_: IllegalStateException) { }
        catch (_: SQLiteException) { }
    }

    @Test fun targetIncludesAccountCalendarAndGenerationAndFirstReasonWins() = fixture { _, _, store ->
        val others = listOf(
            prepared.copy(scope = scope.copy(account = AccountId("other-pause-fixture-account"))),
            prepared.copy(scope = scope.copy(calendar = CalendarId("other-pause-fixture-calendar"))),
            prepared.copy(scope = scope.copy(generation = 1)))
        val second = prepared.copy(id = MutationId("second-pause-fixture-mutation"))
        for (record in others + listOf(prepared, second)) store.remember(record)
        store.pausePrepared(CreatePauseReason.CALENDAR_DISABLED,
            account = AccountId("ignored-account-because-target-is-exact"), target = scope)
        store.pausePrepared(CreatePauseReason.PERMISSION_LOST, target = scope)
        for (record in listOf(prepared, second)) {
            assertEquals(CreatePauseReason.CALENDAR_DISABLED, store.pauseReason(record.scope, record.id))
            assertEquals(record, store.read(record.scope, record.id))
            assertFalse(store.claim(record))
        }
        for (record in others) {
            assertNull(store.pauseReason(record.scope, record.id))
            assertTrue(store.claim(record))
        }
    }

    @Test fun accountPauseCoversItsCalendarsAndGenerationsAndGlobalPauseCoversTheRest() = fixture { _, _, store ->
        val related = listOf(prepared,
            prepared.copy(scope = scope.copy(calendar = CalendarId("second-pause-fixture-calendar"))),
            prepared.copy(scope = scope.copy(generation = 2)))
        val foreign = prepared.copy(scope = scope.copy(account = AccountId("foreign-pause-fixture-account")))
        for (record in related + foreign) store.remember(record)
        store.pausePrepared(CreatePauseReason.SESSION_CHANGED, account = scope.account)
        for (record in related) assertEquals(CreatePauseReason.SESSION_CHANGED, store.pauseReason(record.scope, record.id))
        assertNull(store.pauseReason(foreign.scope, foreign.id))
        store.pausePrepared(CreatePauseReason.PROCESS_RESTART)
        for (record in related) assertEquals(CreatePauseReason.SESSION_CHANGED, store.pauseReason(record.scope, record.id))
        assertEquals(CreatePauseReason.PROCESS_RESTART, store.pauseReason(foreign.scope, foreign.id))
        for (record in related + foreign) assertFalse(store.claim(record))
    }

    @Test fun pauseSurvivesOtherHelpersReopenAndRememberWithoutReset() = fixture { context, name, store ->
        store.remember(prepared)
        CreateAttemptStore(context, name).use { other ->
            assertEquals(prepared, other.read(scope, prepared.id))
            store.pausePrepared(CreatePauseReason.CALENDAR_UNAVAILABLE, target = scope)
            assertFalse(other.claim(prepared))
            other.remember(prepared)
            assertEquals(CreatePauseReason.CALENDAR_UNAVAILABLE, other.pauseReason(scope, prepared.id))
            reject { other.remember(prepared.copy(protectedPayload = "changed-synthetic-payload")) }
        }
        store.close()
        CreateAttemptStore(context, name).use { reopened ->
            reopened.remember(prepared)
            assertEquals(prepared, reopened.read(scope, prepared.id))
            assertEquals(CreatePauseReason.CALENDAR_UNAVAILABLE, reopened.pauseReason(scope, prepared.id))
            assertFalse(CreateAttemptAdmission(reopened).dispatch(prepared) { fail("Paused work must never send") })
        }
    }

    @Test fun pauseAndClosedDispatchGatePreserveUnknownLateEvidenceAndExistingBinding() = fixture { context, name, store ->
        store.remember(prepared); assertTrue(store.claim(prepared))
        val identity = CreateAttemptIdentity.of(unknown)
        val binding = store.bind(unknown, CreateSubmissionObservation.Verified(identity, event))
        val witness = OwnedEventWitness(41, 59, event.revision)
        store.retainProjection(binding, witness)
        store.admitDispatch = { false }
        store.pausePrepared(CreatePauseReason.SESSION_CHANGED)
        store.retainEvidence(unknown, evidence)
        val retained = unknown.copy(protectedEvidence = evidence)
        assertNull(store.pauseReason(scope, prepared.id))
        assertEquals(retained, store.read(scope, prepared.id))
        assertEquals(binding, store.binding(identity)); assertEquals(witness, store.projectionWitness(identity))
        assertFalse(store.claim(prepared))
        store.close()
        CreateAttemptStore(context, name).use { reopened ->
            assertEquals(retained, reopened.read(scope, prepared.id))
            assertEquals(binding, reopened.binding(identity)); assertEquals(witness, reopened.projectionWitness(identity))
            assertFalse(reopened.claim(prepared))
        }
    }

    @Test fun closedGateRefusesRememberAndClaimWithoutChangingPreparedWork() = fixture { _, _, store ->
        store.admitDispatch = { false }
        reject { store.remember(prepared) }
        assertNull(store.read(scope, prepared.id)); assertFalse(store.claim(prepared))
        store.admitDispatch = { true }; store.remember(prepared)
        store.admitDispatch = { false }
        assertFalse(store.claim(prepared)); assertEquals(prepared, store.read(scope, prepared.id))
        store.admitDispatch = { true }; assertTrue(store.claim(prepared))
    }

    @Test fun gateRevokedBeforeCommitRollsBackRememberAndClaim() = fixture { _, _, store ->
        var checks = 0
        store.admitDispatch = { checked -> assertEquals(scope, checked); ++checks == 1 }
        reject { store.remember(prepared) }
        assertEquals(2, checks); assertNull(store.read(scope, prepared.id))
        store.admitDispatch = { true }; store.remember(prepared)
        checks = 0; store.admitDispatch = { ++checks == 1 }
        reject { store.claim(prepared) }
        assertEquals(2, checks); assertEquals(prepared, store.read(scope, prepared.id))
        store.admitDispatch = { true }; assertTrue(store.claim(prepared))
    }

    @Test fun coroutineCancellationAtCommitRollsBackRememberAndClaim() = fixture { _, _, store ->
        for (remembering in listOf(true, false)) {
            if (!remembering) { store.admitDispatch = { true }; store.remember(prepared) }
            coroutineScope {
                val child = async {
                    val job = currentCoroutineContext().job
                    var checks = 0
                    store.admitDispatch = { if (++checks == 2) job.cancel(); true }
                    if (remembering) store.remember(prepared) else store.claim(prepared)
                }
                try { child.await(); fail("Expected synthetic cancellation") }
                catch (_: CancellationException) { }
            }
            assertEquals(if (remembering) null else prepared, store.read(scope, prepared.id))
        }
        store.admitDispatch = { true }; assertTrue(store.claim(prepared))
    }

    @Test fun failedPauseInsertRollsBackAllRowsAndLeavesAdmissionPossible() = fixture { context, name, store ->
        val second = prepared.copy(id = MutationId("second-pause-fixture-mutation"))
        store.remember(prepared); store.remember(second)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("""CREATE TRIGGER fixture_reject_pause BEFORE INSERT ON create_pauses
                WHEN NEW.mutation='second-pause-fixture-mutation'
                BEGIN SELECT RAISE(ABORT, 'synthetic fixture rejection'); END""".trimIndent())
        }
        reject { store.pausePrepared(CreatePauseReason.PROCESS_RESTART) }
        for (record in listOf(prepared, second)) {
            assertNull(store.pauseReason(scope, record.id)); assertEquals(record, store.read(scope, record.id))
            assertTrue(store.claim(record))
        }
    }

    @Test fun concurrentPauseAndClaimCannotBothPauseAndAdmitTheSameWork() = fixture { context, name, store ->
        CreateAttemptStore(context, name).use { other ->
            repeat(12) { index ->
                val record = prepared.copy(id = MutationId("race-pause-fixture-$index"))
                store.remember(record); assertEquals(record, other.read(scope, record.id))
                val admitted = coroutineScope {
                    val start = CompletableDeferred<Unit>()
                    val claim = async(Dispatchers.IO) { start.await(); store.claim(record) }
                    val pause = async(Dispatchers.IO) { start.await(); other.pausePrepared(CreatePauseReason.PROCESS_RESTART) }
                    start.complete(Unit); pause.await(); claim.await()
                }
                if (admitted) {
                    assertEquals(record.copy(state = CreateAttemptState.OUTCOME_UNKNOWN), other.read(scope, record.id))
                    assertNull(other.pauseReason(scope, record.id))
                } else {
                    assertEquals(record, other.read(scope, record.id))
                    assertEquals(CreatePauseReason.PROCESS_RESTART, other.pauseReason(scope, record.id))
                }
                assertFalse(other.claim(record))
            }
        }
    }

    @Test fun preparedWorkWithUnexpectedEvidenceIsNeverPausedOrAdmitted() = fixture { context, name, store ->
        store.remember(prepared)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("UPDATE create_attempts SET evidence=?", arrayOf(evidence))
        }
        store.pausePrepared(CreatePauseReason.PROCESS_RESTART)
        assertNull(store.pauseReason(scope, prepared.id)); assertFalse(store.claim(prepared))
        assertEquals(prepared.copy(protectedEvidence = evidence), store.read(scope, prepared.id))
    }

    @Test fun independentVersionThreeMigrationPreservesUnknownEvidenceBindingAndPrepared() = fixture { context, name, store ->
        val path = context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val retained = unknown.copy(protectedEvidence = evidence)
        val identity = CreateAttemptIdentity.of(retained)
        val pending = prepared.copy(id = MutationId("legacy-pause-prepared-fixture"))
        val binding = CreatedEventBinding(identity, event.eventId, event.revision)
        val witness = OwnedEventWitness(41, 59, event.revision)
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            legacy.execSQL("""CREATE TABLE create_attempts (
                account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL CHECK(generation >= 0),
                mutation TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0),
                payload TEXT NOT NULL, state TEXT NOT NULL CHECK(state IN ('PREPARED', 'OUTCOME_UNKNOWN')), evidence TEXT,
                PRIMARY KEY(account, calendar, generation, mutation)
            )""".trimIndent())
            legacy.execSQL("""CREATE TABLE create_bindings (
                account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL CHECK(generation >= 0),
                mutation TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL CHECK(version > 0),
                fingerprint TEXT NOT NULL, event TEXT NOT NULL, verified_revision TEXT NOT NULL,
                provider_calendar INTEGER, provider_event INTEGER, provider_revision TEXT,
                PRIMARY KEY(account, calendar, generation, mutation), UNIQUE(account, calendar, generation, event),
                CHECK ((provider_calendar IS NULL AND provider_event IS NULL AND provider_revision IS NULL)
                    OR (provider_calendar IS NOT NULL AND provider_event IS NOT NULL AND provider_revision IS NOT NULL
                        AND provider_calendar > 0 AND provider_event > 0))
            )""".trimIndent())
            for (record in listOf(retained, pending)) legacy.execSQL(
                "INSERT INTO create_attempts VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any?>(
                    record.scope.account.value, record.scope.calendar.value, record.scope.generation, record.id.value,
                    record.format.id, record.format.version, record.protectedPayload, record.state.name, record.protectedEvidence))
            legacy.execSQL("INSERT INTO create_bindings VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>(
                scope.account.value, scope.calendar.value, scope.generation, prepared.id.value,
                prepared.format.id, prepared.format.version, identity.payloadFingerprint, event.eventId.value,
                event.revision.value, witness.calendarRow, witness.eventRow, witness.revision.value))
            legacy.version = 3
        }
        assertEquals(retained, store.read(scope, prepared.id)); assertEquals(pending, store.read(scope, pending.id))
        assertEquals(binding, store.binding(identity)); assertEquals(witness, store.projectionWitness(identity))
        assertNull(store.pauseReason(scope, prepared.id)); assertNull(store.pauseReason(scope, pending.id))
        store.pausePrepared(CreatePauseReason.PROCESS_RESTART)
        assertNull(store.pauseReason(scope, prepared.id))
        assertEquals(CreatePauseReason.PROCESS_RESTART, store.pauseReason(scope, pending.id))
        assertEquals(retained, store.read(scope, prepared.id)); assertEquals(binding, store.binding(identity))
        assertFalse(store.claim(prepared)); assertFalse(store.claim(pending))
        SQLiteDatabase.openDatabase(path.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { migrated ->
            assertEquals(6, migrated.version)
        }
        store.close()
        CreateAttemptStore(context, name).use { reopened ->
            assertEquals(retained, reopened.read(scope, prepared.id)); assertEquals(binding, reopened.binding(identity))
            assertEquals(witness, reopened.projectionWitness(identity)); assertFalse(reopened.claim(pending))
        }
    }
}
