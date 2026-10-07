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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in isolated SQLite fixtures: no Provider, service or authentication capability.
 * Opaque synthetic payloads qualify durable admission, not encryption or Proton compatibility.
 */
@RunWith(AndroidJUnit4::class)
class TitleUpdateGuardedPreparationStoreDeviceTest {
    private val scope = CalendarScope(AccountId("guarded-preparation-account"), CalendarId("guarded-preparation-calendar"), 3)
    private val content = EventContent("Synthetic original", EventTime.Timed(
        Instant.parse("2026-10-05T10:00:00.123456789Z"), Instant.parse("2026-10-05T11:00:00.987654321Z"),
        ZoneId.of("Europe/Paris")), "Synthetic preserved description", "Synthetic location", details = EventDetails())
    private val intent = LocalEditIntent(MutationId("guarded-preparation-intent"), scope, AdapterFormat("fixture-import", 4),
        RemoteChange.Upsert(EventId("guarded-preparation-event"), Revision("synthetic-base"), content),
        content.copy(title = "Synthetic changed"))
    private val prepared = TitleUpdateAttemptRecord(MutationId("guarded-preparation-attempt"),
        LocalEditPreparation(intent, Instant.parse("2026-10-05T12:00:00Z"), AdapterFormat("fixture-preparation", 2),
            "synthetic-opaque-first-preparation"), AdapterFormat("fixture-title-update", 1), "synthetic-opaque-first-wire")

    private fun fixture(block: suspend (Context, String, TitleUpdateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "guarded-title-preparation-${UUID.randomUUID()}.db"
        val store = TitleUpdateAttemptStore(context, name)
        try { block(context, name, store) } finally { store.close(); context.deleteDatabase(name) }
    }
    private fun rows(store: TitleUpdateAttemptStore): Int = store.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM title_update_attempts", null).use { assertTrue(it.moveToFirst()); it.getInt(0) }
    private fun columns(store: TitleUpdateAttemptStore, id: MutationId = prepared.id): List<String?> =
        store.readableDatabase.rawQuery("SELECT account,calendar,generation,mutation,event,preparation,adapter,version,payload,state,evidence,result,completion " +
            "FROM title_update_attempts WHERE mutation=?", arrayOf(id.value)).use {
            assertTrue(it.moveToFirst()); List(it.columnCount) { column -> if (it.isNull(column)) null else it.getString(column) }
        }
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("Corrupt title history must fail closed") } catch (_: IllegalStateException) { }
    }
    private suspend fun revoked(block: suspend () -> Boolean) {
        try { assertFalse("Revoked preparation must not succeed", block()) } catch (_: IllegalStateException) { }
    }
    private fun next(scope: CalendarScope = this.scope, base: RemoteChange.Upsert = intent.base): TitleUpdateAttemptRecord =
        prepared.copy(id = MutationId("guarded-preparation-next-attempt"), preparation = prepared.preparation.copy(
            intent = intent.copy(id = MutationId("guarded-preparation-next-intent"), scope = scope, base = base,
                content = base.content.copy(title = "Synthetic next changed"))))

    @Test fun exactPreparedRepeatReopensCompleteFirstSnapshotWithoutChangingAnyColumn() = fixture { context, name, store ->
        val checked = mutableListOf<CalendarScope>()
        assertTrue(store.remember(prepared) { checked += it; it == scope })
        val original = columns(store)
        assertTrue(store.remember(prepared) { checked += it; it == scope })
        assertEquals(original, columns(store)); assertTrue(checked.size >= 4); assertTrue(checked.all { it == scope })
        assertEquals(1, rows(store)); store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(prepared, reopened.read(scope, prepared.id)); assertEquals(original, columns(reopened))
            assertTrue(reopened.remember(prepared) { it == scope }); assertEquals(original, columns(reopened))
            assertFalse(reopened.claim(prepared))
        }
    }

    @Test fun deniedLeaseAndUnsupportedJournalCannotRememberThroughLegacyFallback() = fixture { _, _, store ->
        var checks = 0
        assertFalse(store.remember(prepared) { checks++; false }); assertEquals(1, checks)
        assertNull(store.read(scope, prepared.id)); assertEquals(0, rows(store))
        val unsupported = object : TitleUpdateAttemptJournal {
            override suspend fun remember(record: TitleUpdateAttemptRecord) = error("Legacy remember must not run")
            override suspend fun read(scope: CalendarScope, id: MutationId): TitleUpdateAttemptRecord? = error("No read")
            override suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean = error("No claim")
            override suspend fun retainEvidence(expectedUnknown: TitleUpdateAttemptRecord, protectedEvidence: String) = error("No evidence")
        }
        assertFalse(unsupported.remember(prepared) { true })
        assertEquals(0, rows(store))
    }

    @Test fun unknownOrEvidenceBearingInputCannotCreateDurablePreparedWork() = fixture { _, _, store ->
        for (record in listOf(prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN),
            prepared.copy(protectedEvidence = "synthetic-evidence"))) {
            assertFalse(store.remember(record) { true }); assertNull(store.read(scope, prepared.id))
        }
        assertEquals(0, rows(store))
    }

    @Test fun storedUnknownReturnsFalseAndLegacyRememberCannotReinitializeIt() = fixture { context, name, store ->
        store.remember(prepared); store.admitDispatch = { it == scope }; assertTrue(store.claim(prepared))
        val unknown = prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
        store.retainEvidence(unknown, "synthetic-first-response"); val original = columns(store)
        assertFalse(store.remember(prepared) { true }); assertEquals(original, columns(store))
        // Legacy compatibility deliberately retains its old idempotent UNKNOWN behavior.
        store.remember(prepared); assertEquals(original, columns(store)); store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(unknown.copy(protectedEvidence = "synthetic-first-response"), reopened.read(scope, prepared.id))
            assertFalse(reopened.remember(prepared) { true }); assertEquals(original, columns(reopened))
            reopened.admitDispatch = { true }; assertFalse(reopened.claim(prepared))
        }
    }

    @Test fun changedPreparedIdentityPayloadOrContextCannotReplaceFirstWire() = fixture { _, _, store ->
        store.remember(prepared); val original = columns(store)
        val changed = listOf(prepared.copy(protectedPayload = "synthetic-different-wire"),
            prepared.copy(format = prepared.format.copy(version = 2)),
            prepared.copy(preparation = prepared.preparation.copy(protectedPayload = "synthetic-different-preparation")),
            prepared.copy(preparation = prepared.preparation.copy(timestamp = prepared.preparation.timestamp.plusSeconds(1))),
            prepared.copy(preparation = prepared.preparation.copy(intent = intent.copy(id = MutationId("other-local-intent")))),
            prepared.copy(preparation = prepared.preparation.copy(intent = intent.copy(base = intent.base.copy(revision = Revision("different-base"))))))
        changed.forEach { assertFalse(store.remember(it) { true }); assertEquals(original, columns(store)) }
        assertEquals(prepared, store.read(scope, prepared.id)); assertEquals(1, rows(store))
    }

    @Test fun anotherUnresolvedAttemptOrGenerationCannotBypassRemoteEventOwnership() = fixture { context, name, store ->
        store.remember(prepared); val original = columns(store)
        val candidates = listOf(next(), next(scope.copy(generation = scope.generation + 1)))
        for (candidate in candidates) { assertFalse(store.remember(candidate) { true }); assertNull(store.read(candidate.scope, candidate.id)) }
        assertEquals(original, columns(store)); store.admitDispatch = { true }; assertTrue(store.claim(prepared)); store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            for (candidate in candidates) { assertFalse(reopened.remember(candidate) { true }); assertNull(reopened.read(candidate.scope, candidate.id)) }
            assertEquals(prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), reopened.read(scope, prepared.id))
            assertEquals(1, rows(reopened))
        }
    }

    @Test fun closedHistoryMustQualifyBeforeAdmittingNextDistinctPreparation() = fixture { context, name, store ->
        // A projection receipt requires whole-second times; other fixtures
        // retain nanosecond snapshots to qualify the journal's lossless persistence.
        val receiptTime = content.time as EventTime.Timed
        val receiptContent = content.copy(time = receiptTime.copy(
            start = receiptTime.start.truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
            end = receiptTime.end.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)))
        val intent = this.intent.copy(base = this.intent.base.copy(content = receiptContent),
            content = receiptContent.copy(title = this.intent.content.title))
        val prepared = this.prepared.copy(preparation = this.prepared.preparation.copy(intent = intent))
        store.remember(prepared); store.admitDispatch = { true }; assertTrue(store.claim(prepared))
        val unknown = prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
        val result = TitleUpdateVerifiedResult.of(unknown, TitleUpdateSubmissionObservation.Verified(
            TitleUpdateAttemptIdentity.of(unknown), intent.base.copy(revision = Revision("synthetic-after"), content = intent.content)))
        store.retainVerified(result)
        val completion = TitleUpdateCompletion.of(result, TitleUpdateAcknowledgementRecord(
            TitleUpdateAcknowledgementReceipt.of(result, "786842d0-571f-4c3e-84ee-89f4689e9928"), TitleUpdateAcknowledgementState.RESTORED))
        assertTrue(store.retainCompletion(completion) { true }); val original = columns(store)
        val candidate = prepared.copy(id = MutationId("guarded-preparation-next-attempt"),
            preparation = prepared.preparation.copy(intent = intent.copy(id = MutationId("guarded-preparation-next-intent"),
                scope = scope.copy(generation = scope.generation + 1), base = result.event,
                content = result.event.content.copy(title = "Synthetic next changed"))))
        assertFalse(store.remember(prepared) { true })
        val reusedIntent = candidate.copy(preparation = candidate.preparation.copy(
            intent = candidate.preparation.intent.copy(id = intent.id)))
        assertFalse(store.remember(reusedIntent) { true }); assertEquals(original, columns(store))
        for (column in listOf("preparation", "result", "completion")) {
            val retained = store.readableDatabase.rawQuery("SELECT $column FROM title_update_attempts WHERE mutation=?",
                arrayOf(prepared.id.value)).use { assertTrue(it.moveToFirst()); it.getString(0) }
            store.writableDatabase.execSQL("UPDATE title_update_attempts SET $column='{}' WHERE mutation=?", arrayOf(prepared.id.value))
            rejects { store.remember(candidate) { true } }
            assertNull(store.read(candidate.scope, candidate.id)); assertEquals(1, rows(store))
            store.writableDatabase.execSQL("UPDATE title_update_attempts SET $column=? WHERE mutation=?", arrayOf(retained, prepared.id.value))
        }
        assertEquals(original, columns(store)); assertTrue(store.remember(candidate) { it == candidate.scope })
        val nextColumns = columns(store, candidate.id)
        // An exact PREPARED repeat must still qualify older closed generations.
        val retainedCompletion = original.last()
        store.writableDatabase.execSQL("UPDATE title_update_attempts SET completion='{}' WHERE mutation=?", arrayOf(prepared.id.value))
        rejects { store.remember(candidate) { it == candidate.scope } }
        assertEquals(nextColumns, columns(store, candidate.id))
        store.writableDatabase.execSQL("UPDATE title_update_attempts SET completion=? WHERE mutation=?", arrayOf(retainedCompletion, prepared.id.value))
        assertEquals(original, columns(store)); store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(completion, reopened.readCompletion(scope, prepared.id))
            assertEquals(result, reopened.readVerified(scope, prepared.id)); assertEquals(candidate, reopened.read(candidate.scope, candidate.id))
            assertEquals(original, columns(reopened)); assertEquals(2, rows(reopened))
        }
    }

    @Test fun revocationAfterObservableInsertRollsBackAndIdempotentRepeatRechecksLease() = fixture { context, name, store ->
        var sawInserted = false
        revoked { store.remember(prepared) {
            val visible = rows(store) > 0
            if (visible) sawInserted = true
            !visible
        } }
        assertTrue("The lease must be checked after INSERT inside its transaction", sawInserted)
        assertEquals(0, rows(store)); assertNull(store.read(scope, prepared.id)); store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertNull(reopened.read(scope, prepared.id)); assertTrue(reopened.remember(prepared) { true })
            val original = columns(reopened); var checks = 0
            revoked { reopened.remember(prepared) { ++checks == 1 } }
            assertTrue("Even an exact repeat must check its lease before commit", checks >= 2)
            assertEquals(original, columns(reopened)); assertEquals(prepared, reopened.read(scope, prepared.id))
        }
    }

    @Test fun cancellationFromLeaseAfterObservableInsertPropagatesAndRollsBackAfterReopen() = fixture { context, name, store ->
        var sawInserted = false
        try {
            store.remember(prepared) {
                if (rows(store) > 0) { sawInserted = true; throw CancellationException("synthetic preparation cancellation") }
                true
            }
            fail("Preparation cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue("Cancellation must occur after the SQL INSERT", sawInserted)
        assertEquals(0, rows(store)); store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertNull(reopened.read(scope, prepared.id)); assertEquals(0, rows(reopened))
            assertTrue(reopened.remember(prepared) { true }); assertEquals(prepared, reopened.read(scope, prepared.id))
        }
    }
}
