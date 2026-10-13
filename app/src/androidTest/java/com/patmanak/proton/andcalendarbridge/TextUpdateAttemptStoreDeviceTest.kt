// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.outgoing.TextUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in isolated SQLite fixtures. Opaque synthetic payloads are not crypto or server evidence. */
@RunWith(AndroidJUnit4::class)
class TextUpdateAttemptStoreDeviceTest {
    private val scope = CalendarScope(AccountId("synthetic-text-account"), CalendarId("synthetic-text-calendar"), 3)
    private val content = EventContent("Synthetic title", EventTime.Timed(
        Instant.parse("2026-10-24T10:00:00.123456789Z"), Instant.parse("2026-10-24T11:00:00.987654321Z"),
        ZoneId.of("Europe/Paris")), "Original\r\nnotes", "Old room", details = EventDetails())
    private fun prepared(event: String = "synthetic-event", id: String = "synthetic-request",
        target: CalendarScope = scope, payload: String = "synthetic-protected-request"): TextUpdateAttemptRecord {
        val edit = LocalEditIntent(MutationId("intent-$id"), target, AdapterFormat("synthetic-observation", 2),
            RemoteChange.Upsert(EventId(event), Revision("synthetic-revision"), content),
            content.copy(title = "New title", description = "New\r\nnotes", location = "New room"))
        return TextUpdateAttemptRecord(MutationId(id), LocalTextEditPreparation(edit, Instant.parse("2026-10-13T09:00:00Z"),
            AdapterFormat("synthetic-text-preparation", 4), "synthetic-protected-preparation"),
            AdapterFormat("synthetic-text-request", 7), payload)
    }
    private val allow: (CalendarScope) -> Boolean = { true }
    private fun fixture(block: suspend (Context, String, TextUpdateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "text-attempt-fixture-${UUID.randomUUID()}.db"
        val store = TextUpdateAttemptStore(context, name)
        try { block(context, name, store) }
        finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun corrupt(block: suspend () -> Unit) {
        try { block(); fail("Corrupt retained work cannot look absent") }
        catch (failure: IllegalStateException) {
            assertEquals("Invalid text update journal", failure.message); assertNull(failure.cause)
        }
    }

    @Test fun exactWholeIntentAndOpaqueFormatsReopenWithFullSynchronousCommit() = fixture { context, name, store ->
        val value = prepared(); assertTrue(store.remember(value, allow)); assertTrue(store.remember(value, allow))
        TextUpdateAttemptStore(context, name).use { observer ->
            val actual = requireNotNull(observer.read(scope, value.id, allow))
            assertEquals(value, actual); assertEquals("New\r\nnotes", actual.preparation.intent.content.description)
            assertEquals(content.time, actual.preparation.intent.content.time)
            observer.readableDatabase.rawQuery("PRAGMA synchronous", null).use {
                assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
            }
        }
    }
    @Test fun unknownCannotResetOrBeReboundAcrossGenerations() = fixture { context, name, store ->
        val value = prepared(); assertTrue(store.remember(value, allow)); assertTrue(store.claim(value, allow))
        val unknown = value.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
        TextUpdateAttemptStore(context, name).use { observer -> assertEquals(unknown, observer.read(scope, value.id, allow)) }
        assertFalse(store.claim(value, allow)); assertFalse(store.claim(unknown, allow)); assertFalse(store.remember(value, allow))
        assertFalse(store.remember(prepared(id = "other-request"), allow))
        assertFalse(store.remember(prepared(id = "next-generation", target = scope.copy(generation = 4)), allow))
        assertFalse(store.remember(prepared(event = "other-event", target = scope.copy(generation = 4)), allow))
        assertEquals(unknown, store.read(scope, value.id, allow))
    }
    @Test fun accountAndCalendarIsolationDoNotWeakenEventOwnership() = fixture { _, _, store ->
        val value = prepared(); assertTrue(store.remember(value, allow))
        for (other in listOf(scope.copy(account = AccountId("other-account")), scope.copy(calendar = CalendarId("other-calendar")))) {
            val independent = prepared(target = other)
            assertTrue(store.remember(independent, allow)); assertEquals(independent, store.read(other, independent.id, allow))
        }
        assertNull(store.read(scope.copy(generation = 4), value.id, allow))
    }
    @Test fun completePreparedWorkMustMatchBeforeRetentionOrClaim() = fixture { _, _, store ->
        val value = prepared(); assertTrue(store.remember(value, allow))
        val preparation = value.preparation; val intent = preparation.intent
        fun replace(next: LocalEditIntent) = value.copy(preparation = LocalTextEditPreparation(next,
            preparation.timestamp, preparation.format, preparation.protectedPayload))
        val variants = listOf(value.copy(protectedPayload = "different-request"),
            value.copy(format = value.format.copy(version = value.format.version + 1)),
            replace(intent.copy(id = MutationId("different-intent"))),
            replace(intent.copy(base = intent.base.copy(revision = Revision("different-base")))),
            replace(intent.copy(content = intent.content.copy(location = "Different room"))),
            value.copy(preparation = LocalTextEditPreparation(intent, preparation.timestamp.plusSeconds(1),
                preparation.format, preparation.protectedPayload)),
            value.copy(preparation = LocalTextEditPreparation(intent, preparation.timestamp,
                preparation.format, "different-preparation")))
        for (other in variants) {
            assertFalse(store.remember(other, allow)); assertFalse(store.claim(other, allow))
            assertEquals(value, store.read(scope, value.id, allow))
        }
        assertTrue(store.claim(value, allow))
    }
    @Test fun initialDenialAndPrecommitRevocationLeaveNoMutation() = fixture { _, _, store ->
        val value = prepared()
        assertFalse(store.remember(value) { false }); assertNull(store.read(scope, value.id) { false })
        var checks = 0; assertFalse(store.remember(value) { ++checks < 2 }); assertEquals(2, checks)
        assertNull(store.read(scope, value.id, allow))
        assertTrue(store.remember(value, allow))
        assertFalse(store.claim(value) { false })
        checks = 0; assertFalse(store.claim(value) { ++checks < 2 }); assertEquals(2, checks)
        assertEquals(value, store.read(scope, value.id, allow))
        checks = 0; assertNull(store.read(scope, value.id) { ++checks < 2 }); assertEquals(2, checks)
    }
    @Test fun twoHelpersRetainOneOwnerAndClaimExactlyOnce() = fixture { context, name, store ->
        val value = prepared(); assertTrue(store.remember(value, allow))
        TextUpdateAttemptStore(context, name).use { competitor ->
            val results = coroutineScope { listOf(async(Dispatchers.IO) { store.claim(value, allow) },
                async(Dispatchers.IO) { competitor.claim(value, allow) }).awaitAll() }
            assertEquals(1, results.count { it }); assertEquals(value.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN),
                competitor.read(scope, value.id, allow))
            val first = prepared(event = "race-event", id = "first-owner")
            val second = prepared(event = "race-event", id = "second-owner")
            val owners = coroutineScope { listOf(async(Dispatchers.IO) { store.remember(first, allow) },
                async(Dispatchers.IO) { competitor.remember(second, allow) }).awaitAll() }
            assertEquals(1, owners.count { it })
        }
    }
    @Test fun firstEvidenceIsImmutableAndNeverChangesTheUnknownIntent() = fixture { _, _, store ->
        val value = prepared(); assertTrue(store.remember(value, allow))
        assertFalse(store.retainEvidence(value, "synthetic-evidence", allow)); assertTrue(store.claim(value, allow))
        val unknown = value.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
        assertTrue(store.retainEvidence(unknown, "synthetic-evidence", allow))
        assertTrue(store.retainEvidence(unknown, "synthetic-evidence", allow))
        val retained = unknown.copy(protectedEvidence = "synthetic-evidence")
        assertTrue(store.retainEvidence(retained, "synthetic-evidence", allow))
        assertFalse(store.retainEvidence(unknown, "changed-evidence", allow))
        assertFalse(store.retainEvidence(unknown.copy(protectedPayload = "changed-request"), "synthetic-evidence", allow))
        assertFalse(store.retainEvidence(unknown.copy(protectedEvidence = "different-expected"), "synthetic-evidence", allow))
        assertEquals(retained, store.read(scope, value.id, allow))
    }
    @Test fun largeCapsulesReopenWithoutMaterializingOneCursorWindowCell() = fixture { context, name, store ->
        val value = prepared(payload = "s".repeat(2 * 1024 * 1024 + 73))
        assertTrue(store.remember(value, allow)); assertEquals(value, store.read(scope, value.id, allow))
        assertTrue(store.claim(value, allow))
        val unknown = value.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
        val evidence = "e".repeat(2 * 1024 * 1024 + 29)
        assertTrue(store.retainEvidence(unknown, evidence, allow))
        TextUpdateAttemptStore(context, name).use {
            assertEquals(unknown.copy(protectedEvidence = evidence), it.read(scope, value.id, allow))
        }
    }
    @Test fun cancellationInsideLeasesRollsBackAndDominatesOrdinaryFailure() = fixture { _, _, store ->
        val value = prepared(); assertTrue(store.remember(value, allow))
        for (ordinaryFailure in listOf(false, true)) {
            var cancellations = 0; var ordinary = 0; var returned = 0
            supervisorScope {
                val child = async {
                    val taskJob = currentCoroutineContext().job
                    var checks = 0
                    try {
                        store.claim(value) {
                            if (++checks == 2) {
                                taskJob.cancel(CancellationException("Synthetic lease cancellation"))
                                if (ordinaryFailure) throw java.io.IOException("synthetic-sensitive")
                            }
                            true
                        }; returned++
                    } catch (_: CancellationException) { cancellations++ }
                      catch (_: Exception) { ordinary++ }
                }
                try { child.await() } catch (_: CancellationException) { }
            }
            assertEquals(1, cancellations); assertEquals(0, ordinary); assertEquals(0, returned)
            assertEquals(value, store.read(scope, value.id, allow))
        }
    }
    @Test fun evidenceRevocationAndCancellationCannotAttachAResponse() = fixture { _, _, store ->
        val value = prepared(); assertTrue(store.remember(value, allow)); assertTrue(store.claim(value, allow))
        val unknown = value.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
        var checks = 0
        assertFalse(store.retainEvidence(unknown, "synthetic-evidence") { ++checks < 2 })
        assertEquals(unknown, store.read(scope, value.id, allow))
        var cancellations = 0
        supervisorScope {
            val child = async {
                val job = currentCoroutineContext().job; checks = 0
                try {
                    store.retainEvidence(unknown, "synthetic-evidence") {
                        if (++checks == 2) { job.cancel(); throw java.io.IOException("synthetic-sensitive") }; true
                    }; fail("Cancellation was hidden")
                } catch (_: CancellationException) { cancellations++ }
            }
            try { child.await() } catch (_: CancellationException) { }
        }
        assertEquals(1, cancellations); assertEquals(unknown, store.read(scope, value.id, allow))
    }
    @Test fun malformedSqlCellsAndIndexDisagreementFailClosed() = fixture { _, _, store ->
        val value = prepared(); assertTrue(store.remember(value, allow))
        for ((column, replacement) in listOf("event" to "different-event", "state" to "OUTCOME_UNKNOWN", "capsule" to "wrong-sql-type")) {
            val saved = store.readableDatabase.rawQuery("SELECT $column FROM text_update_attempts", null).use {
                assertTrue(it.moveToFirst()); if (column == "capsule") it.getBlob(0) else it.getString(0)
            }
            store.writableDatabase.update("text_update_attempts", ContentValues().apply { put(column, replacement) }, null, null)
            corrupt { store.read(scope, value.id, allow) }
            store.writableDatabase.update("text_update_attempts", ContentValues().apply {
                if (saved is ByteArray) put(column, saved) else put(column, saved as String)
            }, null, null)
        }
        store.writableDatabase.update("text_update_attempts", ContentValues().apply { put("capsule", ByteArray(16 * 1024 * 1024 + 1)) }, null, null)
        corrupt { store.read(scope, value.id, allow) }
    }
    @Test fun metadataIsBoundedAndStrictBeforeAnyLargeCursorAllocation() = fixture { _, _, store ->
        val value = prepared(); assertTrue(store.remember(value, allow))
        val mutations = listOf<() -> Unit>(
            { store.writableDatabase.update("text_update_attempts", ContentValues().apply {
                put("event", "x".repeat(3 * 1024 * 1024)) }, null, null) },
            { store.writableDatabase.execSQL("UPDATE text_update_attempts SET event=CAST(x'80' AS TEXT)") },
            { store.writableDatabase.execSQL("UPDATE text_update_attempts SET event=x'6173736574'") })
        for (mutate in mutations) {
            mutate(); corrupt { store.read(scope, value.id, allow) }
            store.writableDatabase.update("text_update_attempts", ContentValues().apply { put("event", value.eventId.value) }, null, null)
        }
        assertEquals(value, store.read(scope, value.id, allow))
    }
}
