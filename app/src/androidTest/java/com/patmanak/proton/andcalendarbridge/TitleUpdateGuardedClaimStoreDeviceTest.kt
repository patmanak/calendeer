// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated opt-in SQLite transactions; no transport, account or Calendar Provider access. */
@RunWith(AndroidJUnit4::class)
class TitleUpdateGuardedClaimStoreDeviceTest {
    private val scope = CalendarScope(AccountId("guarded-claim-account"), CalendarId("guarded-claim-calendar"), 2)
    private val content = EventContent("Synthetic original", EventTime.Timed(
        Instant.parse("2026-10-09T10:00:00Z"), Instant.parse("2026-10-09T11:00:00Z"), ZoneId.of("Europe/Paris")),
        details = EventDetails())
    private val base = RemoteChange.Upsert(EventId("guarded-claim-event"), Revision("original-revision"), content)
    private val intent = LocalEditIntent(MutationId("guarded-claim-intent"), scope, AdapterFormat("fixture-import", 1),
        base, content.copy(title = "Synthetic modified"))
    private val preparation = LocalEditPreparation(intent, Instant.parse("2026-10-09T12:00:00Z"),
        AdapterFormat("fixture-preparation", 1), "opaque-capsule")
    private val prepared = TitleUpdateAttemptRecord(MutationId("guarded-claim-attempt"), preparation,
        AdapterFormat("fixture-wire", 1), "opaque-first-wire")
    private val unknown get() = prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)

    private fun fixture(block: suspend (Context, String, TitleUpdateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "title-guarded-claim-${UUID.randomUUID()}.db"
        val store = TitleUpdateAttemptStore(context, name)
        try { block(context, name, store) } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun denied(block: suspend () -> Boolean) {
        val admitted = try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IllegalStateException) { return }
        assertFalse("Invalid work must not be admitted", admitted)
    }
    private fun assertSqlState(store: TitleUpdateAttemptStore, record: TitleUpdateAttemptRecord, state: TitleUpdateAttemptState) {
        store.readableDatabase.rawQuery("SELECT state,payload,evidence FROM title_update_attempts WHERE mutation=?",
            arrayOf(record.id.value)).use {
            assertTrue(it.moveToFirst()); assertEquals(state.name, it.getString(0))
            assertEquals(record.protectedPayload, it.getString(1)); assertTrue(it.isNull(2)); assertFalse(it.moveToNext())
        }
    }
    private suspend fun completed(store: TitleUpdateAttemptStore): TitleUpdateVerifiedResult {
        store.remember(prepared); store.admitDispatch = { it == scope }; assertTrue(store.claim(prepared))
        store.admitDispatch = { false }
        val verified = TitleUpdateVerifiedResult.of(unknown, TitleUpdateSubmissionObservation.Verified(
            TitleUpdateAttemptIdentity.of(unknown), base.copy(revision = Revision("updated-revision"), content = intent.content)))
        store.retainVerified(verified)
        val receipt = TitleUpdateAcknowledgementReceipt.of(verified, "d91cd4de-718b-41bc-bef7-949b20362629")
        assertTrue(store.retainCompletion(TitleUpdateCompletion.of(verified,
            TitleUpdateAcknowledgementRecord(receipt, TitleUpdateAcknowledgementState.RESTORED))) { it == scope })
        return verified
    }

    @Test fun guardedClaimCommitsUnknownBeforeReturningAndIgnoresGlobalDispatchPermission() = fixture { context, name, store ->
        store.remember(prepared)
        store.admitDispatch = { error("Guarded claim must not consult mutable global admission") }
        var checks = 0
        assertTrue(store.claim(prepared) {
            assertEquals(scope, it)
            if (++checks == 1) assertFalse(store.writableDatabase.inTransaction())
            else { assertTrue(store.writableDatabase.inTransaction()); assertSqlState(store, prepared, TitleUpdateAttemptState.OUTCOME_UNKNOWN) }
            true
        })
        assertTrue(checks >= 2); assertFalse(store.writableDatabase.inTransaction())
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(unknown, reopened.read(scope, prepared.id))
            assertFalse(reopened.claim(prepared) { true }); assertEquals(unknown, reopened.read(scope, prepared.id))
        }
    }

    @Test fun deniedPerCallLeaseCannotFallBackToGloballyAllowedDispatch() = fixture { _, _, store ->
        store.remember(prepared); store.admitDispatch = { true }
        var checks = 0
        assertFalse(store.claim(prepared) { assertEquals(scope, it); checks++; false })
        assertEquals(1, checks); assertEquals(prepared, store.read(scope, prepared.id))
    }

    @Test fun leaseRevokedAfterRealUpdateRollsBackBeforeReopening() = fixture { context, name, store ->
        store.remember(prepared); store.admitDispatch = { true }
        var checks = 0
        denied { store.claim(prepared) {
            assertEquals(scope, it)
            if (++checks == 1) true else {
                assertTrue(store.writableDatabase.inTransaction())
                assertSqlState(store, prepared, TitleUpdateAttemptState.OUTCOME_UNKNOWN); false
            }
        } }
        assertTrue(checks >= 2); assertEquals(prepared, store.read(scope, prepared.id)); store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(prepared, reopened.read(scope, prepared.id)); assertTrue(reopened.claim(prepared) { it == scope })
        }
    }

    @Test fun cancellationReturningFalseAtEitherLeaseBoundaryPropagatesAndRollsBack() = fixture { _, _, store ->
        store.remember(prepared)
        for (cancelAt in listOf(1, 2)) {
            val checks = AtomicInteger(); val normalReturns = AtomicInteger(); val cancellations = AtomicInteger()
            supervisorScope {
                val request = async {
                    val requestJob = currentCoroutineContext()[Job]!!
                    try {
                        store.claim(prepared) {
                            if (checks.incrementAndGet() == cancelAt) {
                                if (cancelAt == 2) {
                                    assertTrue(store.writableDatabase.inTransaction())
                                    assertSqlState(store, prepared, TitleUpdateAttemptState.OUTCOME_UNKNOWN)
                                }
                                requestJob.cancel(CancellationException("Synthetic claim cancellation")); false
                            } else true
                        }
                        normalReturns.incrementAndGet()
                    } catch (cancelled: CancellationException) { cancellations.incrementAndGet(); throw cancelled }
                }
                try { request.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            }
            assertEquals(cancelAt, checks.get()); assertEquals(0, normalReturns.get()); assertEquals(1, cancellations.get())
            assertEquals(prepared, store.read(scope, prepared.id))
        }
    }

    @Test fun twoIndependentHandlesHaveExactlyOneGuardedWinner() = fixture { context, name, store ->
        store.remember(prepared)
        TitleUpdateAttemptStore(context, name).use { other ->
            assertEquals(prepared, other.read(scope, prepared.id))
            store.admitDispatch = { false }; other.admitDispatch = { false }
            val gate = CompletableDeferred<Unit>()
            val outcomes = coroutineScope {
                val first = async(Dispatchers.IO) { gate.await(); store.claim(prepared) { it == scope } }
                val second = async(Dispatchers.IO) { gate.await(); other.claim(prepared) { it == scope } }
                gate.complete(Unit); listOf(first.await(), second.await())
            }
            assertEquals(1, outcomes.count { it }); assertEquals(unknown, other.read(scope, prepared.id))
            assertFalse(store.claim(prepared) { true }); assertFalse(other.claim(prepared) { true })
        }
    }

    @Test fun changedOpaqueWireCapsuleIdentityOrFormatCannotAcquireExistingWork() = fixture { _, _, store ->
        store.remember(prepared)
        val variants = listOf(prepared.copy(id = MutationId("other-attempt")),
            prepared.copy(protectedPayload = "different-wire"), prepared.copy(format = prepared.format.copy(version = 2)),
            prepared.copy(preparation = preparation.copy(protectedPayload = "different-capsule")),
            prepared.copy(preparation = preparation.copy(timestamp = preparation.timestamp.plusSeconds(1))),
            prepared.copy(preparation = preparation.copy(intent = intent.copy(id = MutationId("other-owner")))),
            prepared.copy(preparation = preparation.copy(intent = intent.copy(base = base.copy(revision = Revision("other-revision"))))),
            prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), prepared.copy(protectedEvidence = "invented-evidence"))
        for (value in variants) {
            assertFalse(store.claim(value) { true }); assertEquals(prepared, store.read(scope, prepared.id))
        }
    }

    @Test fun bareReservationAndMissingAttemptCannotBeAdoptedOrInsertedByClaim() = fixture { _, _, store ->
        assertFalse(store.claim(prepared) { true })
        val reservation = TitleUpdatePreparationReservation.of(prepared)
        assertEquals(reservation, store.reservePreparation(reservation) { true })
        assertFalse(store.claim(prepared) { true }); assertNull(store.read(scope, prepared.id))
        assertEquals(reservation, store.readPreparationReservation(scope, base.eventId))
    }

    @Test fun scopeCallbackCannotAuthorizeAnotherAccountCalendarOrGeneration() = fixture { _, _, store ->
        store.remember(prepared); store.admitDispatch = { true }
        for (other in listOf(scope.copy(account = AccountId("other-account")), scope.copy(calendar = CalendarId("other-calendar")),
            scope.copy(generation = scope.generation + 1))) {
            val value = prepared.copy(preparation = preparation.copy(intent = intent.copy(scope = other)))
            var observed: CalendarScope? = null
            assertFalse(store.claim(value) { observed = it; it == scope })
            assertEquals(other, observed); assertEquals(prepared, store.read(scope, prepared.id))
        }
    }

    @Test fun unknownEvidenceRemainsImmutableAndCannotBeClaimedAgain() = fixture { _, _, store ->
        store.remember(prepared); assertTrue(store.claim(prepared) { true })
        store.retainEvidence(unknown, "opaque-evidence")
        val original = store.read(scope, prepared.id)
        assertFalse(store.claim(prepared) { true }); assertFalse(store.claim(unknown) { true })
        assertEquals(original, store.read(scope, prepared.id))
    }

    @Test fun movedReservationIndexStillBlocksGuardedClaimThroughFullOwnershipHistory() = fixture { _, _, store ->
        assertEquals(TitleUpdatePreparationReservation.of(prepared),
            store.reservePreparation(TitleUpdatePreparationReservation.of(prepared)) { true })
        store.remember(prepared)
        store.writableDatabase.execSQL("UPDATE title_update_preparation_reservations SET event='other-event'")
        denied { store.claim(prepared) { true } }
        assertEquals(prepared, store.read(scope, prepared.id))
    }

    @Test fun completedOrCorruptClosedHistoryCannotReopenAnOldOrNewDispatch() = fixture { _, _, store ->
        val verified = completed(store)
        assertFalse(store.claim(prepared) { true }); assertEquals(unknown, store.read(scope, prepared.id))
        val next = prepared.copy(id = MutationId("next-attempt"), preparation = preparation.copy(intent = intent.copy(
            id = MutationId("next-intent"), base = verified.event, content = verified.event.content.copy(title = "Synthetic next title"))))
        store.remember(next)
        store.writableDatabase.execSQL("UPDATE title_update_attempts SET completion='{}' WHERE mutation=?", arrayOf(prepared.id.value))
        denied { store.claim(next) { true } }
        assertEquals(next, store.read(scope, next.id)); assertEquals(unknown, store.read(scope, prepared.id))
    }

    @Test fun deferredCommitFailureNeverReturnsTrueAndReopensAsPrepared() = fixture { context, name, store ->
        store.remember(prepared)
        store.writableDatabase.execSQL("CREATE TABLE claim_fixture_parent(id INTEGER PRIMARY KEY)")
        store.writableDatabase.execSQL("CREATE TABLE claim_fixture_child(parent INTEGER REFERENCES claim_fixture_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        store.writableDatabase.execSQL("CREATE TRIGGER reject_guarded_claim_commit AFTER UPDATE OF state ON title_update_attempts WHEN NEW.state='OUTCOME_UNKNOWN' BEGIN INSERT INTO claim_fixture_child(parent) VALUES(1); END")
        var checks = 0
        try { store.claim(prepared) { checks++; true }; fail("Deferred commit failure must propagate") }
        catch (_: SQLiteException) { }
        assertTrue(checks >= 2); store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(prepared, reopened.read(scope, prepared.id))
            reopened.writableDatabase.execSQL("DROP TRIGGER reject_guarded_claim_commit")
            assertTrue(reopened.claim(prepared) { it == scope })
        }
    }
}
