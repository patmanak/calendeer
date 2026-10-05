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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated opt-in SQLite fixtures; no Provider, session or network capability. */
@RunWith(AndroidJUnit4::class)
class TitleUpdateCompletionJournalDeviceTest {
    private val scope = CalendarScope(AccountId("completion-account"), CalendarId("completion-calendar"), 3)
    private val content = EventContent("Synthetic original", EventTime.Timed(
        Instant.parse("2026-10-05T10:00:00Z"), Instant.parse("2026-10-05T11:00:00Z"), ZoneId.of("Europe/Paris")),
        "Synthetic description", "Synthetic location", details = EventDetails())
    private val base = RemoteChange.Upsert(EventId("completion-event"), Revision("revision-before"), content)
    private val intent = LocalEditIntent(MutationId("completion-intent"), scope, AdapterFormat("fixture-import", 1),
        base, content.copy(title = "Synthetic edited"))
    private val prepared = TitleUpdateAttemptRecord(MutationId("completion-attempt"),
        LocalEditPreparation(intent, Instant.parse("2026-10-05T12:00:00Z"), AdapterFormat("fixture-preparation", 1),
            "opaque-preparation"), AdapterFormat("fixture-update", 1), "opaque-update")

    private fun fixture(block: suspend (Context, String, TitleUpdateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "title-completion-fixture-${UUID.randomUUID()}.db"
        val store = TitleUpdateAttemptStore(context, name)
        try { block(context, name, store) } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun result(store: TitleUpdateAttemptStore, value: TitleUpdateAttemptRecord = prepared,
        retain: Boolean = true): TitleUpdateVerifiedResult {
        store.remember(value); store.admitDispatch = { it == value.scope }
        assertTrue(store.claim(value)); store.admitDispatch = { false }
        val unknown = value.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
        return TitleUpdateVerifiedResult.of(unknown, TitleUpdateSubmissionObservation.Verified(
            TitleUpdateAttemptIdentity.of(unknown), value.preparation.intent.base.copy(
                revision = Revision("revision-after-${value.id.value}"), content = value.preparation.intent.content)))
            .also { if (retain) store.retainVerified(it) }
    }
    private fun completion(result: TitleUpdateVerifiedResult, nonce: String = "97af8411-bf54-4d0c-8ab3-22a9f163348e") =
        TitleUpdateCompletion.of(result, TitleUpdateAcknowledgementRecord(
            TitleUpdateAcknowledgementReceipt.of(result, nonce), TitleUpdateAcknowledgementState.RESTORED))
    private fun next(value: TitleUpdateVerifiedResult, generation: Long = scope.generation) = prepared.copy(
        id = MutationId("next-attempt"), preparation = prepared.preparation.copy(intent = intent.copy(
            id = MutationId("next-intent"), scope = scope.copy(generation = generation), base = value.event,
            content = value.event.content.copy(title = "Synthetic next title"))))
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("Expected journal rejection") } catch (_: IllegalStateException) { }
    }

    @Test fun restoredCompletionReopensAndAllowsDistinctNextAttemptWithoutReopeningUnknown() = fixture { context, name, store ->
        val verified = result(store)
        store.retainEvidence(verified.record, "opaque-evidence")
        val original = store.read(scope, prepared.id)
        val beforeColumns = originalColumns(store)
        val proof = completion(verified)
        assertTrue(store.retainCompletion(proof) { it == scope })
        assertEquals(1, store.completionProtocol)
        assertEquals(original, store.read(scope, prepared.id))
        assertEquals(verified, store.readVerified(scope, prepared.id))
        assertEquals(beforeColumns, originalColumns(store))
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(proof, reopened.readCompletion(scope, prepared.id))
            assertEquals(original, reopened.read(scope, prepared.id))
            assertEquals(beforeColumns, originalColumns(reopened))
            reopened.admitDispatch = { true }
            assertFalse(reopened.claim(prepared))
            val second = next(verified)
            reopened.remember(second)
            assertTrue(reopened.claim(second))
            assertEquals(original, reopened.read(scope, prepared.id))
        }
    }

    @Test fun completionDefaultAdmissionDeniesAndRevocationRollsBack() = fixture { _, _, store ->
        val proof = completion(result(store))
        assertFalse(store.retainCompletion(proof))
        assertNull(store.readCompletion(scope, prepared.id))
        var checks = 0
        rejects { store.retainCompletion(proof) { ++checks == 1 } }
        assertTrue(checks >= 2)
        assertNull(store.readCompletion(scope, prepared.id))
        assertTrue(store.retainCompletion(proof) { true })
    }

    @Test fun completionRequiresFirstDurableExactResultAndIsImmutable() = fixture { _, _, store ->
        val verified = result(store, retain = false)
        val proof = completion(verified)
        rejects { store.retainCompletion(proof) { true } }
        assertNull(store.readCompletion(scope, prepared.id))
        store.retainVerified(verified)
        assertTrue(store.retainCompletion(proof) { true })
        assertTrue(store.retainCompletion(proof) { true })
        rejects { store.retainCompletion(completion(verified, "3ba6e447-79a7-481d-964d-6d06b3353f14")) { true } }
        assertEquals(proof, store.readCompletion(scope, prepared.id))
    }

    @Test fun structurallyValidDifferentResultCannotCloseFirstRetainedResult() = fixture { _, _, store ->
        val verified = result(store)
        val different = TitleUpdateVerifiedResult.of(verified.record, TitleUpdateSubmissionObservation.Verified(
            verified.attempt, verified.event.copy(revision = Revision("different-verified-revision"))))
        rejects { store.retainCompletion(completion(different)) { true } }
        assertNull(store.readCompletion(scope, prepared.id))
        assertEquals(verified, store.readVerified(scope, prepared.id))
    }

    @Test fun structurallyValidDifferentProtectedWorkCannotCloseStoredAttempt() = fixture { _, _, store ->
        val verified = result(store)
        val differentWork = verified.record.copy(protectedPayload = "different-opaque-update")
        val different = TitleUpdateVerifiedResult.of(differentWork, TitleUpdateSubmissionObservation.Verified(
            TitleUpdateAttemptIdentity.of(differentWork), verified.event))
        rejects { store.retainCompletion(completion(different)) { true } }
        assertNull(store.readCompletion(scope, prepared.id))
        assertEquals(verified.record, store.read(scope, prepared.id))
    }

    @Test fun unresolvedAttemptBlocksNewGenerationAndCompletedIntentCannotBeReused() = fixture { _, _, store ->
        val verified = result(store)
        rejects { store.remember(next(verified, 4)) }
        assertTrue(store.retainCompletion(completion(verified)) { true })
        val reused = next(verified, 4).let { it.copy(preparation = it.preparation.copy(intent =
            it.preparation.intent.copy(id = intent.id))) }
        rejects { store.remember(reused) }
        val second = next(verified, 4)
        store.remember(second); store.admitDispatch = { true }
        assertTrue(store.claim(second))
    }

    @Test fun malformedNonNullCompletionDoesNotReleaseEventAdmission() = fixture { _, _, store ->
        val verified = result(store)
        store.writableDatabase.execSQL("UPDATE title_update_attempts SET completion='{}'")
        rejects { store.readCompletion(scope, prepared.id) }
        rejects { store.remember(next(verified)) }
        assertNull(store.read(scope, MutationId("next-attempt")))
    }

    @Test fun malformedClosedHistoryBlocksClaimOfAlreadyPreparedNextAttempt() = fixture { _, _, store ->
        val verified = result(store)
        assertTrue(store.retainCompletion(completion(verified)) { true })
        val second = next(verified)
        store.remember(second)
        store.writableDatabase.execSQL("UPDATE title_update_attempts SET completion='{}' WHERE mutation=?",
            arrayOf(prepared.id.value))
        store.admitDispatch = { true }
        rejects { store.claim(second) }
        assertEquals(second, store.read(scope, second.id))
    }

    @Test fun closedResultCorruptionCannotReleaseEventOrBeReadAsCompletion() = fixture { _, _, store ->
        val verified = result(store)
        assertTrue(store.retainCompletion(completion(verified)) { true })
        store.writableDatabase.execSQL("UPDATE title_update_attempts SET result='{}'")
        rejects { store.readCompletion(scope, prepared.id) }
        rejects { store.remember(next(verified)) }
    }

    @Test fun nonCanonicalCompletionFailsClosed() = fixture { _, _, store ->
        val verified = result(store)
        assertTrue(store.retainCompletion(completion(verified)) { true })
        store.writableDatabase.execSQL("UPDATE title_update_attempts SET completion=' ' || completion")
        rejects { store.readCompletion(scope, prepared.id) }
        rejects { store.remember(next(verified)) }
    }

    @Test fun concurrentHelpersAdmitOnlyOneNewUnresolvedAttemptAfterCompletion() = fixture { context, name, store ->
        val verified = result(store)
        assertTrue(store.retainCompletion(completion(verified)) { true })
        TitleUpdateAttemptStore(context, name).use { other ->
            // Open both connections before racing their writer transactions.
            other.readableDatabase
            val first = next(verified)
            val second = first.copy(id = MutationId("competing-attempt"), preparation = first.preparation.copy(
                intent = first.preparation.intent.copy(id = MutationId("competing-intent"))))
            val gate = CompletableDeferred<Unit>()
            suspend fun insert(target: TitleUpdateAttemptStore, value: TitleUpdateAttemptRecord): Boolean {
                gate.await()
                return try { target.remember(value); true } catch (_: IllegalStateException) { false }
            }
            val outcomes = coroutineScope {
                val a = async(Dispatchers.IO) { insert(store, first) }
                val b = async(Dispatchers.IO) { insert(other, second) }
                gate.complete(Unit)
                listOf(a.await(), b.await())
            }
            assertEquals(1, outcomes.count { it })
            assertEquals(completion(verified), other.readCompletion(scope, prepared.id))
            other.readableDatabase.rawQuery("SELECT COUNT(*) FROM title_update_attempts WHERE completion IS NULL", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        }
    }

    @Test fun partialIndexRejectsSecondUnresolvedEventAcrossGenerations() = fixture { _, _, store ->
        val verified = result(store)
        assertTrue(store.retainCompletion(completion(verified)) { true })
        store.remember(next(verified))
        try {
            store.writableDatabase.execSQL("""INSERT INTO title_update_attempts
                (account,calendar,generation,mutation,event,preparation,adapter,version,payload,state)
                SELECT account,calendar,generation+1,'index-competing-attempt',event,preparation,adapter,version,payload,state
                FROM title_update_attempts WHERE completion IS NULL""".trimIndent())
            fail("Partial unique index must hold across generations")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
    }

    @Test fun migrationOneAndTwoPreserveOpaqueBytesAndNeverInferCompletion() = fixture { context, _, _ ->
        for (version in 1..2) {
            val name = "title-completion-legacy-${UUID.randomUUID()}.db"
            try {
                context.getDatabasePath(name).parentFile!!.mkdirs()
                SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { raw ->
                    raw.execSQL("CREATE TABLE title_update_attempts(account TEXT NOT NULL,calendar TEXT NOT NULL,generation INTEGER NOT NULL,mutation TEXT NOT NULL,event TEXT NOT NULL,preparation TEXT NOT NULL,adapter TEXT NOT NULL,version INTEGER NOT NULL,payload TEXT NOT NULL,state TEXT NOT NULL,evidence TEXT${if (version == 2) ",result TEXT" else ""},PRIMARY KEY(account,calendar,generation,mutation),UNIQUE(account,calendar,event))")
                    raw.insertOrThrow("title_update_attempts", null, ContentValues().apply {
                        put("account", "legacy-account"); put("calendar", "legacy-calendar"); put("generation", 7)
                        put("mutation", "legacy-attempt"); put("event", "legacy-event"); put("preparation", "opaque legacy preparation\n")
                        put("adapter", "legacy-adapter"); put("version", 2); put("payload", "opaque legacy ciphertext\n")
                        put("state", "OUTCOME_UNKNOWN"); put("evidence", "opaque legacy evidence\n")
                        if (version == 2) put("result", "opaque legacy result\n")
                    }); raw.version = version
                }
                TitleUpdateAttemptStore(context, name).use { migrated ->
                    assertEquals(3, migrated.readableDatabase.version)
                    migrated.readableDatabase.rawQuery("SELECT preparation,payload,evidence,result,completion,state FROM title_update_attempts", null).use {
                        assertTrue(it.moveToFirst()); assertEquals("opaque legacy preparation\n", it.getString(0))
                        assertEquals("opaque legacy ciphertext\n", it.getString(1)); assertEquals("opaque legacy evidence\n", it.getString(2))
                        assertEquals(if (version == 2) "opaque legacy result\n" else null, it.getString(3))
                        assertTrue(it.isNull(4)); assertEquals("OUTCOME_UNKNOWN", it.getString(5)); assertFalse(it.moveToNext())
                    }
                }
            } finally { context.deleteDatabase(name) }
        }
    }

    private fun originalColumns(store: TitleUpdateAttemptStore): List<String?> = store.readableDatabase.rawQuery(
        "SELECT account,calendar,generation,mutation,event,preparation,adapter,version,payload,state,evidence,result FROM title_update_attempts WHERE mutation=?",
        arrayOf(prepared.id.value)).use { row ->
        check(row.moveToFirst()); (0 until row.columnCount).map { if (row.isNull(it)) null else row.getString(it) }
    }
}
