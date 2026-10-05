// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in isolated SQLite fixtures: no Provider, network, session or live calendar access. */
@RunWith(AndroidJUnit4::class)
class TitleUpdateResultStoreDeviceTest {
    private val scope = CalendarScope(AccountId("result-fixture-account"), CalendarId("result-fixture-calendar"), 3)
    private val content = EventContent("Synthetic original title", EventTime.Timed(
        Instant.parse("2026-10-05T10:15:00.123456789Z"), Instant.parse("2026-10-05T11:15:00.987654321Z"),
        ZoneId.of("Europe/Paris")), "Synthetic description\nSecond line", "Synthetic location", details = EventDetails())
    private val base = RemoteChange.Upsert(EventId("result-fixture-event"), Revision("synthetic-base-revision"), content)
    private val intent = LocalEditIntent(MutationId("result-fixture-intent"), scope,
        AdapterFormat("synthetic-import", 4), base, content.copy(title = "Synthetic edited title"))
    private val preparation = LocalEditPreparation(intent, Instant.parse("2026-10-05T12:00:01Z"),
        AdapterFormat("synthetic-protected-preparation", 2), "synthetic-opaque-preparation")
    private val prepared = TitleUpdateAttemptRecord(MutationId("result-fixture-attempt"), preparation,
        AdapterFormat("synthetic-protected-update", 1), "synthetic-opaque-update")
    private val evidence = "synthetic-opaque-response-evidence"

    private fun fixture(block: suspend (Context, String, TitleUpdateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "title-result-fixture-${UUID.randomUUID()}.db"
        val store = TitleUpdateAttemptStore(context, name)
        try { block(context, name, store) }
        finally { store.close(); context.deleteDatabase(name) }
    }

    private suspend fun unknown(store: TitleUpdateAttemptStore, value: TitleUpdateAttemptRecord = prepared): TitleUpdateAttemptRecord {
        store.remember(value)
        store.admitDispatch = { it == value.scope }
        assertTrue(store.claim(value))
        store.admitDispatch = { false }
        return value.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
    }
    private fun result(value: TitleUpdateAttemptRecord, revision: String = "synthetic-verified-revision") =
        TitleUpdateVerifiedResult.of(value, TitleUpdateSubmissionObservation.Verified(
            TitleUpdateAttemptIdentity.of(value), value.preparation.intent.base.copy(
                revision = Revision(revision), content = value.preparation.intent.content)))
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); fail("Expected retained-result rejection") }
        catch (_: IllegalStateException) { }
    }

    @Test fun verifiedResultDurablyReopensWithCompleteNanosecondSnapshotAndNoDispatchAdmission() = fixture { context, name, store ->
        val work = unknown(store)
        val verified = result(work)
        store.retainVerified(verified)
        assertEquals(verified, store.readVerified(scope, work.id))
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(verified, reopened.readVerified(scope, work.id))
            assertEquals(work, reopened.read(scope, work.id))
            assertFalse(reopened.claim(prepared))
            reopened.readableDatabase.rawQuery("PRAGMA synchronous", null).use {
                assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
            }
        }
    }

    @Test fun completeAllDayResultRetainsExclusiveEndAndPreservedFields() = fixture { context, name, store ->
        val day = content.copy(time = EventTime.AllDay(LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-27")))
        val dayIntent = intent.copy(base = base.copy(content = day), content = day.copy(title = intent.content.title))
        val dayPrepared = prepared.copy(preparation = preparation.copy(intent = dayIntent))
        val work = unknown(store, dayPrepared)
        val verified = result(work)
        store.retainVerified(verified); store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(verified, reopened.readVerified(scope, work.id))
            assertEquals(dayIntent.content, reopened.readVerified(scope, work.id)!!.event.content)
        }
    }

    @Test fun missingOrPreparedAttemptCannotAcceptVerifiedResult() = fixture { _, _, store ->
        val verified = result(prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN))
        rejects { store.retainVerified(verified) }
        assertNull(store.readVerified(scope, prepared.id))
        store.remember(prepared)
        rejects { store.retainVerified(verified) }
        assertEquals(prepared, store.read(scope, prepared.id))
        assertNull(store.readVerified(scope, prepared.id))
    }

    @Test fun completeRecordMismatchCannotAttachResultOrReplaceUnknownWork() = fixture { _, _, store ->
        val work = unknown(store)
        val variants = listOf(
            work.copy(protectedPayload = "different-opaque-update"),
            work.copy(format = AdapterFormat(work.format.id, 2)),
            work.copy(preparation = preparation.copy(timestamp = preparation.timestamp.plusSeconds(1))),
            work.copy(preparation = preparation.copy(protectedPayload = "different-opaque-preparation")),
            work.copy(preparation = preparation.copy(intent = intent.copy(id = MutationId("different-intent")))),
            work.copy(preparation = preparation.copy(intent = intent.copy(base = base.copy(revision = Revision("different-base"))))),
            work.copy(id = MutationId("different-attempt")),
            work.copy(preparation = preparation.copy(intent = intent.copy(scope = scope.copy(generation = 4))))
        )
        variants.forEach { rejects { store.retainVerified(result(it)) } }
        assertNull(store.readVerified(scope, work.id))
        assertEquals(work, store.read(scope, work.id))
        store.retainVerified(result(work))
        assertEquals(result(work), store.readVerified(scope, work.id))
    }

    @Test fun exactRepetitionsAreIdempotentButFirstObservedRevisionIsImmutable() = fixture { _, _, store ->
        val work = unknown(store)
        val first = result(work)
        store.retainVerified(first); store.retainVerified(first)
        rejects { store.retainVerified(result(work, "different-verified-revision")) }
        assertEquals(first, store.readVerified(scope, work.id))
        assertEquals(work, store.read(scope, work.id))
    }

    @Test fun independentHelpersRaceToExactlyOneImmutableFirstResult() = fixture { context, name, store ->
        val work = unknown(store)
        TitleUpdateAttemptStore(context, name).use { other ->
            val start = CompletableDeferred<Unit>()
            val choices = listOf(result(work), result(work, "other-observed-revision"))
            val wins = coroutineScope {
                listOf(store, other).mapIndexed { index, helper -> async(Dispatchers.IO) {
                    start.await()
                    try { helper.retainVerified(choices[index]); index }
                    catch (_: IllegalStateException) { -1 }
                } }.also { start.complete(Unit) }.map { it.await() }
            }
            assertEquals(1, wins.count { it >= 0 })
            val winner = choices[wins.single { it >= 0 }]
            assertEquals(winner, store.readVerified(scope, work.id))
            assertEquals(winner, other.readVerified(scope, work.id))
        }
    }

    @Test fun responseEvidenceBeforeOrAfterResultDoesNotChangeResultIdentity() = fixture { context, name, store ->
        val work = unknown(store)
        val verified = result(work)
        store.retainVerified(verified)
        store.retainEvidence(work, evidence)
        store.retainVerified(result(work.copy(protectedEvidence = evidence)))
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(verified, reopened.readVerified(scope, work.id))
            assertEquals(work.copy(protectedEvidence = evidence), reopened.read(scope, work.id))
        }
    }

    @Test fun preexistingResponseEvidenceIsPreservedWithoutBeingDecoded() = fixture { _, _, store ->
        val work = unknown(store)
        store.retainEvidence(work, evidence)
        val verified = result(work.copy(protectedEvidence = evidence))
        store.retainVerified(verified)
        assertEquals(result(work), store.readVerified(scope, work.id))
        assertEquals(evidence, store.read(scope, work.id)!!.protectedEvidence)
    }

    @Test fun versionOneMigrationPreservesUnknownEvidenceAndNeverReopensDispatch() = fixture { context, name, store ->
        store.close()
        seedVersionOne(context, name)
        TitleUpdateAttemptStore(context, name).use { migrated ->
            val work = prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = evidence)
            assertEquals(2, migrated.readableDatabase.version)
            assertEquals(work, migrated.read(scope, work.id))
            assertNull(migrated.readVerified(scope, work.id))
            migrated.admitDispatch = { true }
            assertFalse(TitleUpdateAttemptAdmission(migrated).dispatch(prepared) { fail("Never resend migrated work") })
            migrated.remember(prepared)
            assertEquals(work, migrated.read(scope, work.id))
            migrated.retainVerified(result(work))
            assertEquals(result(work), migrated.readVerified(scope, work.id))
            assertEquals(work, migrated.read(scope, work.id))
        }
    }

    @Test fun corruptResultFramesFailClosedWithoutReplacingUnknownOrOpaqueEvidence() = fixture { context, name, store ->
        val work = unknown(store)
        store.retainEvidence(work, evidence)
        store.retainVerified(result(work))
        val original = store.readableDatabase.rawQuery("SELECT result FROM title_update_attempts", null).use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        store.close()
        val corruptions = listOf("not-json", JSONObject(original).put("unexpected", true).toString(),
            JSONObject(original).put("schema", "1").toString(), JSONObject(original).put("schema", 2).toString(),
            JSONObject(original).apply { getJSONObject("event").put("id", "different-event") }.toString(),
            JSONObject(original).apply { getJSONObject("event").put("revision", base.revision.value) }.toString(),
            JSONObject(original).apply { getJSONObject("event").getJSONObject("content").put("description", "changed-description") }.toString(),
            JSONObject(original).apply { getJSONObject("event").getJSONObject("content").put("knownEmptyDetails", false) }.toString(),
            JSONObject(original).apply { getJSONObject("event").getJSONObject("content").put("color", 42) }.toString(),
            JSONObject(original).apply { getJSONObject("attempt").put("version", "1") }.toString(),
            JSONObject(original).apply { getJSONObject("attempt").put("generation", 4) }.toString())
        corruptions.forEach { corrupted ->
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.update("title_update_attempts", ContentValues().apply { put("result", corrupted) }, null, null)
            }
            TitleUpdateAttemptStore(context, name).use { reopened ->
                rejects { reopened.readVerified(scope, work.id) }
                assertEquals(work.copy(protectedEvidence = evidence), reopened.read(scope, work.id))
            }
        }
    }

    @Test fun validRecordSubstitutionCannotRebindHistoricalResult() = fixture { context, name, store ->
        val work = unknown(store)
        store.retainVerified(result(work)); store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.update("title_update_attempts", ContentValues().apply { put("payload", "substituted-opaque-update") }, null, null)
        }
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(work.copy(protectedPayload = "substituted-opaque-update"), reopened.read(scope, work.id))
            rejects { reopened.readVerified(scope, work.id) }
        }
    }

    @Test fun deferredCommitFailureNeverReturnsDurableResultAndLeavesUnknownIntact() = fixture { context, name, store ->
        val work = unknown(store)
        store.retainEvidence(work, evidence)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.setForeignKeyConstraintsEnabled(true)
            raw.execSQL("CREATE TABLE result_fixture_parent(id INTEGER PRIMARY KEY)")
            raw.execSQL("CREATE TABLE result_fixture_child(parent INTEGER REFERENCES result_fixture_parent(id) DEFERRABLE INITIALLY DEFERRED)")
            raw.execSQL("CREATE TRIGGER result_fixture_fail AFTER UPDATE OF result ON title_update_attempts BEGIN INSERT INTO result_fixture_child(parent) VALUES(1); END")
        }
        try { store.retainVerified(result(work)); fail("Expected actual SQLite deferred commit failure") }
        catch (_: SQLiteException) { }
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertNull(reopened.readVerified(scope, work.id))
            assertEquals(work.copy(protectedEvidence = evidence), reopened.read(scope, work.id))
            reopened.writableDatabase.execSQL("DROP TRIGGER result_fixture_fail")
            reopened.retainVerified(result(work))
            assertEquals(result(work), reopened.readVerified(scope, work.id))
        }
    }

    @Test fun resultReadsRemainScopedToCompleteAccountCalendarGenerationAndAttempt() = fixture { _, _, store ->
        val work = unknown(store)
        store.retainVerified(result(work))
        listOf(scope.copy(account = AccountId("different-account")), scope.copy(calendar = CalendarId("different-calendar")),
            scope.copy(generation = 4)).forEach { assertNull(store.readVerified(it, work.id)) }
        assertNull(store.readVerified(scope, MutationId("different-attempt")))
    }

    /** Fixed v1 schema and fixture frame, independent of the v2 writer being qualified. */
    private fun seedVersionOne(context: Context, name: String) {
        val snapshot = JSONObject().apply {
            put("schema", 1); put("id", intent.id.value)
            put("account", scope.account.value); put("calendar", scope.calendar.value); put("generation", scope.generation)
            put("intentAdapter", intent.format.id); put("intentVersion", intent.format.version); put("intentState", intent.state.name)
            put("event", base.eventId.value); put("revision", base.revision.value)
            val time = content.time as EventTime.Timed
            put("base", JSONObject().apply {
                put("title", content.title); put("description", content.description); put("location", content.location)
                put("knownEmptyDetails", true); put("allDay", false)
                put("start", time.start.toString()); put("end", time.end.toString()); put("zone", time.zone.id)
            })
            put("newTitle", intent.content.title); put("timestamp", preparation.timestamp.toString())
            put("preparationAdapter", preparation.format.id); put("preparationVersion", preparation.format.version)
            put("preparationPayload", preparation.protectedPayload)
        }.toString()
        context.getDatabasePath(name).parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { raw ->
            raw.execSQL("CREATE TABLE title_update_attempts(account TEXT NOT NULL,calendar TEXT NOT NULL,generation INTEGER NOT NULL CHECK(generation>=0),mutation TEXT NOT NULL,event TEXT NOT NULL,preparation TEXT NOT NULL,adapter TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),payload TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('PREPARED','OUTCOME_UNKNOWN')),evidence TEXT,PRIMARY KEY(account,calendar,generation,mutation),UNIQUE(account,calendar,event))")
            raw.insertOrThrow("title_update_attempts", null, ContentValues().apply {
                put("account", scope.account.value); put("calendar", scope.calendar.value); put("generation", scope.generation)
                put("mutation", prepared.id.value); put("event", base.eventId.value); put("preparation", snapshot)
                put("adapter", prepared.format.id); put("version", prepared.format.version); put("payload", prepared.protectedPayload)
                put("state", TitleUpdateAttemptState.OUTCOME_UNKNOWN.name); put("evidence", evidence)
            })
            raw.version = 1
        }
    }
}
