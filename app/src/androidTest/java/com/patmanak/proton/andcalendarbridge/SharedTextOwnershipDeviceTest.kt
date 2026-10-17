// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.serialization.TextUpdateAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated opt-in SQLite arbitration only. No Provider, authentication, crypto or service operation. */
@RunWith(AndroidJUnit4::class)
class SharedTextOwnershipDeviceTest {
    private val scope = CalendarScope(AccountId("synthetic-shared-account"), CalendarId("synthetic-shared-calendar"), 3)
    private val allow: (CalendarScope) -> Boolean = { true }
    private val content = EventContent("Synthetic original", EventTime.Timed(Instant.parse("2026-10-24T10:00:00Z"),
        Instant.parse("2026-10-24T11:00:00Z"), ZoneId.of("Europe/Paris")),
        "Original\r\nnotes", "Original room", details = EventDetails())

    private fun title(event: String = "synthetic-event", id: String = "synthetic-title", target: CalendarScope = scope): TitleUpdateAttemptRecord {
        val intent = LocalEditIntent(MutationId("owner-$id"), target, AdapterFormat("synthetic-observation", 1),
            RemoteChange.Upsert(EventId(event), Revision("synthetic-base"), content), content.copy(title = "Edited title"))
        return TitleUpdateAttemptRecord(MutationId(id), LocalEditPreparation(intent, Instant.parse("2026-10-13T09:00:00Z"),
            AdapterFormat("synthetic-title-preparation", 1), "opaque-title-preparation"), AdapterFormat("synthetic-title-request", 1), "opaque-title-request")
    }
    private fun text(event: String = "synthetic-event", id: String = "synthetic-text", target: CalendarScope = scope,
        payload: String = "opaque-text-request"): TextUpdateAttemptRecord {
        val intent = LocalEditIntent(MutationId("owner-$id"), target, AdapterFormat("synthetic-observation", 1),
            RemoteChange.Upsert(EventId(event), Revision("synthetic-base"), content),
            content.copy(title = "Edited title", description = "Edited\r\nnotes", location = "Edited room"))
        return TextUpdateAttemptRecord(MutationId(id), LocalTextEditPreparation(intent, Instant.parse("2026-10-13T09:00:00Z"),
            AdapterFormat("synthetic-text-preparation", 1), "opaque-text-preparation"), AdapterFormat("synthetic-text-request", 1), payload)
    }
    private fun fixture(block: suspend (Context, String, TitleUpdateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "shared-text-ownership-${UUID.randomUUID()}.db"
        val store = TitleUpdateAttemptStore(context, name)
        try { block(context, name, store) } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Conflicting or corrupt ownership was accepted") }
        catch (_: IllegalStateException) { }
    }

    @Test fun barePreparedAndUnknownTitleOwnershipEachHoldTextAcrossGenerations() = fixture { _, _, store ->
        for (kind in 0..2) {
            val title = title(event = "event-$kind", id = "title-$kind")
            if (kind == 0) assertEquals(TitleUpdatePreparationReservation.of(title),
                store.reservePreparation(TitleUpdatePreparationReservation.of(title), allow))
            else {
                store.remember(title)
                if (kind == 2) assertTrue(store.claim(title, allow))
            }
            val candidate = text(event = "event-$kind", id = "text-$kind", target = scope.copy(generation = 4))
            assertFalse(store.textJournal.remember(candidate, allow))
            assertNull(store.textJournal.read(candidate.scope, candidate.id, allow))
        }
    }

    @Test fun preparedAndUnknownTextHoldTitleReservationAndBothRememberPaths() = fixture { _, _, store ->
        for (unknown in listOf(false, true)) {
            val text = text(event = "event-$unknown", id = "text-$unknown")
            assertTrue(store.textJournal.remember(text, allow)); assertTrue(store.textJournal.remember(text, allow))
            if (unknown) assertTrue(store.textJournal.claim(text, allow))
            val candidate = title(event = text.eventId.value, id = "title-$unknown", target = scope.copy(generation = 4))
            assertNull(store.reservePreparation(TitleUpdatePreparationReservation.of(candidate), allow))
            assertFalse(store.remember(candidate, allow)); rejected { store.remember(candidate) }
            assertNull(store.read(candidate.scope, candidate.id))
            assertEquals(text.copy(state = if (unknown) TextUpdateAttemptState.OUTCOME_UNKNOWN else TextUpdateAttemptState.PREPARED),
                store.textJournal.read(text.scope, text.id, allow))
        }
    }

    @Test fun bothTitleClaimPathsRecheckTextOwnershipEvenForPreviouslyRetainedWork() = fixture { _, _, store ->
        for (unknown in listOf(false, true)) {
            val title = title(event = "claim-event-$unknown", id = "claim-title-$unknown")
            store.remember(title)
            val competing = text(event = title.preparation.intent.base.eventId.value, id = "claim-text-$unknown")
                .copy(state = if (unknown) TextUpdateAttemptState.OUTCOME_UNKNOWN else TextUpdateAttemptState.PREPARED)
            insertTextCollision(store.writableDatabase, competing)
            store.admitDispatch = allow
            assertFalse(store.claim(title)); assertFalse(store.claim(title, allow))
            assertEquals(title, store.read(title.scope, title.id))
            assertEquals(competing, store.textJournal.read(competing.scope, competing.id, allow))
        }
    }

    @Test fun textClaimRechecksTitleAndBareReservationOwnership() = fixture { _, _, store ->
        for (bare in listOf(false, true)) {
            val title = title(event = "text-claim-$bare", id = "blocking-title-$bare")
            if (bare) assertNotNull(store.reservePreparation(TitleUpdatePreparationReservation.of(title), allow)) else store.remember(title)
            val competing = text(event = title.preparation.intent.base.eventId.value, id = "blocked-text-$bare")
            insertTextCollision(store.writableDatabase, competing)
            assertFalse(store.textJournal.claim(competing, allow))
            assertEquals(competing, store.textJournal.read(competing.scope, competing.id, allow))
        }
    }

    @Test fun requestIdentityCannotCrossFamiliesEventsOrGenerationsIncludingBareReservations() = fixture { _, _, store ->
        for (bare in listOf(false, true)) {
            val title = title(event = "identity-title-$bare", id = "reserved-identity-$bare")
            if (bare) assertNotNull(store.reservePreparation(TitleUpdatePreparationReservation.of(title), allow)) else store.remember(title)
            val candidate = text(event = "different-text-event-$bare", id = title.id.value, target = scope.copy(generation = 8))
            assertFalse(store.textJournal.remember(candidate, allow))
        }
        val text = text(event = "identity-text", id = "text-reserved-identity")
        assertTrue(store.textJournal.remember(text, allow))
        val candidate = title(event = "different-title-event", id = text.id.value, target = scope.copy(generation = 8))
        assertNull(store.reservePreparation(TitleUpdatePreparationReservation.of(candidate), allow))
        assertFalse(store.remember(candidate, allow)); rejected { store.remember(candidate) }
    }

    @Test fun candidateAndAdoptedCanonicalTitleIdsBothCheckTextHistory() = fixture { _, _, store ->
        val first = title(event = "canonical-event", id = "canonical-title")
        store.remember(first)
        val collision = text(event = "other-event", id = first.id.value, target = scope.copy(generation = 9))
        insertTextCollision(store.writableDatabase, collision)
        val proposed = TitleUpdatePreparationReservation.of(first).copy(id = MutationId("new-candidate"))
        assertNull(store.reservePreparation(proposed, allow))
        assertEquals(first, store.read(scope, first.id))
        val second = title(event = "second-canonical-event", id = "second-canonical-title")
        store.remember(second)
        val separate = text(event = "third-event", id = "occupied-candidate")
        assertTrue(store.textJournal.remember(separate, allow))
        assertNull(store.reservePreparation(TitleUpdatePreparationReservation.of(second).copy(id = separate.id), allow))
    }

    @Test fun differentAccountsAndCalendarsCanUseTheSameEventAndRequestIds() = fixture { _, _, store ->
        val title = title(id = "shared-id"); store.remember(title)
        for (other in listOf(scope.copy(account = AccountId("other-account")), scope.copy(calendar = CalendarId("other-calendar")))) {
            val independent = text(id = title.id.value, target = other)
            assertTrue(store.textJournal.remember(independent, allow)); assertEquals(independent, store.textJournal.read(other, independent.id, allow))
        }
        val text = text(event = "reverse-event", id = "reverse-id")
        assertTrue(store.textJournal.remember(text, allow))
        for (other in listOf(scope.copy(account = AccountId("reverse-account")), scope.copy(calendar = CalendarId("reverse-calendar")))) {
            val independent = title(event = text.eventId.value, id = text.id.value, target = other)
            assertTrue(store.remember(independent, allow)); assertEquals(independent, store.read(other, independent.id))
        }
    }

    @Test fun independentHelpersRaceIntoExactlyOneCrossFamilyOwner() = fixture { context, name, store ->
        store.readableDatabase
        TitleUpdateAttemptStore(context, name).use { competitor ->
            competitor.readableDatabase
            repeat(4) { index ->
                val title = title(event = "race-event-$index", id = "race-title-$index")
                val text = text(event = "race-event-$index", id = "race-text-$index")
                val gate = CompletableDeferred<Unit>()
                val outcomes = coroutineScope {
                    val a = async(Dispatchers.IO) { gate.await(); store.remember(title, allow) }
                    val b = async(Dispatchers.IO) { gate.await(); competitor.textJournal.remember(text, allow) }
                    gate.complete(Unit); listOf(a.await(), b.await())
                }
                assertEquals(1, outcomes.count { it })
                assertEquals(1, listOf(store.read(scope, title.id), competitor.textJournal.read(scope, text.id, allow)).count { it != null })
            }
        }
    }

    @Test fun validTitleCompletionReleasesOnlyEventAndNeverHistoricalRequestIdentity() = fixture { _, _, store ->
        val title = title(); val proof = complete(store, title)
        val original = historicalSnapshot(store.readableDatabase)
        val next = text(id = "new-text-owner", target = scope.copy(generation = 4))
        assertTrue(store.textJournal.remember(next, allow))
        assertEquals(proof, store.readCompletion(scope, title.id)); assertEquals(original, historicalSnapshot(store.readableDatabase))
        val reused = text(event = "different-event", id = title.id.value, target = scope.copy(generation = 9))
        assertFalse(store.textJournal.remember(reused, allow))
        val bare = title(event = "bare-other-event", id = "bare-historical-id")
        assertNotNull(store.reservePreparation(TitleUpdatePreparationReservation.of(bare), allow))
        assertFalse(store.textJournal.remember(text(event = "yet-another-event", id = bare.id.value,
            target = scope.copy(generation = 9)), allow))
    }

    @Test fun malformedTitleCompletionOrResultCannotReleaseOwnership() = fixture { _, _, store ->
        for ((index, column) in listOf("completion", "result").withIndex()) {
            val title = title(event = "corrupt-title-$index", id = "corrupt-title-id-$index")
            complete(store, title)
            store.writableDatabase.execSQL("UPDATE title_update_attempts SET $column='{}' WHERE mutation=?", arrayOf(title.id.value))
            val candidate = text(event = title.preparation.intent.base.eventId.value, id = "corrupt-blocked-text-$index")
            rejected { store.textJournal.remember(candidate, allow) }
            assertNull(store.textJournal.read(scope, candidate.id, allow))
        }
    }

    @Test fun corruptTextIndexTypesAndCapsuleCannotLookLikeFreeTitleOwnership() = fixture { _, _, store ->
        val text = text(); assertTrue(store.textJournal.remember(text, allow))
        val canonical = TextUpdateAttemptStorageCodec.encode(text)
        val mutations: List<() -> Unit> = listOf(
            { store.writableDatabase.execSQL("UPDATE text_update_attempts SET capsule=x'0001'") },
            { store.writableDatabase.execSQL("UPDATE text_update_attempts SET capsule='wrong-type'") },
            { store.writableDatabase.execSQL("UPDATE text_update_attempts SET generation=CAST('wrong' AS BLOB)") },
            { store.writableDatabase.execSQL("UPDATE text_update_attempts SET event=CAST(x'80' AS TEXT)") },
            { store.writableDatabase.execSQL("UPDATE text_update_attempts SET event=x'6576656e74'") })
        for (mutate in mutations) {
            mutate()
            // Request-ID lookup must find corruption even when event index bytes were changed.
            val candidate = title(event = "different-event", id = text.id.value)
            rejected { store.reservePreparation(TitleUpdatePreparationReservation.of(candidate), allow) }
            rejected { store.remember(candidate, allow) }
            store.writableDatabase.update("text_update_attempts", ContentValues().apply {
                put("capsule", canonical); put("generation", text.scope.generation); put("event", text.eventId.value)
            }, null, null)
        }
        assertEquals(text, store.textJournal.read(scope, text.id, allow))
    }

    @Test fun largeSharedTextEvidenceReopensWithoutChangingFirstResponse() = fixture { context, name, store ->
        val text = text(payload = "r".repeat(2 * 1024 * 1024 + 73))
        assertTrue(store.textJournal.remember(text, allow)); assertTrue(store.textJournal.claim(text, allow))
        val unknown = text.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
        val evidence = "e".repeat(2 * 1024 * 1024 + 29)
        assertTrue(store.textJournal.retainEvidence(unknown, evidence, allow))
        assertFalse(store.textJournal.retainEvidence(unknown, "different", allow))
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(unknown.copy(protectedEvidence = evidence), reopened.textJournal.read(scope, text.id, allow))
            assertFalse(reopened.remember(title(), allow)); assertFalse(reopened.textJournal.remember(text, allow))
        }
    }

    @Test fun textRevocationAndCancellationRollBackBeforeOwnershipBecomesVisible() = fixture { _, _, store ->
        val text = text(); var checks = 0
        assertFalse(store.textJournal.remember(text) { ++checks < 2 }); assertEquals(2, checks)
        assertNull(store.textJournal.read(scope, text.id, allow))
        for (ordinaryFailure in listOf(false, true)) {
            var cancellations = 0; var ordinary = 0; var returned = 0
            supervisorScope {
                val child = async {
                    val job = currentCoroutineContext().job; checks = 0
                    try {
                        store.textJournal.remember(text) {
                            if (++checks == 2) { job.cancel(); if (ordinaryFailure) throw java.io.IOException("synthetic-sensitive") }
                            true
                        }; returned++
                    } catch (_: CancellationException) { cancellations++ }
                      catch (_: Exception) { ordinary++ }
                }
                try { child.await() } catch (_: CancellationException) { }
            }
            assertEquals(1, cancellations); assertEquals(0, ordinary); assertEquals(0, returned)
            assertNull(store.textJournal.read(scope, text.id, allow))
        }
        assertTrue(store.remember(title(), allow))
    }

    @Test fun schemaFourMigrationPreservesEveryHistoricalCellAndBareReservation() = fixture { context, name, store ->
        val title = title(); val proof = complete(store, title)
        val bare = TitleUpdatePreparationReservation.of(title(event = "migration-bare-event", id = "migration-bare-id"))
        assertEquals(bare, store.reservePreparation(bare, allow))
        val before = historicalSnapshot(store.readableDatabase)
        store.close()
        // Recreate the exact previous additive schema in this isolated fixture only.
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { legacy ->
            legacy.execSQL("DROP TABLE text_update_attempts")
            legacy.execSQL("DROP TABLE delete_attempts")
            legacy.execSQL("DROP TABLE time_update_attempts"); legacy.version = 4
        }
        TitleUpdateAttemptStore(context, name).use { migrated ->
            assertEquals(9, migrated.readableDatabase.version)
            assertEquals(before, historicalSnapshot(migrated.readableDatabase))
            assertEquals(proof, migrated.readCompletion(scope, title.id))
            assertEquals(bare, migrated.readPreparationReservation(bare.scope, bare.eventId))
            migrated.readableDatabase.rawQuery("SELECT COUNT(*) FROM text_update_attempts", null).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            assertFalse(migrated.textJournal.remember(text(event = bare.eventId.value), allow))
            assertTrue(migrated.textJournal.remember(text(id = "migration-new-text"), allow))
            assertEquals(before, historicalSnapshot(migrated.readableDatabase))
        }
    }

    @Test fun failedSchemaFourMigrationPreservesVersionHistoryAndExistingTextTable() = fixture { context, name, store ->
        complete(store, title())
        val bare = TitleUpdatePreparationReservation.of(title(event = "rollback-bare-event", id = "rollback-bare-id"))
        assertEquals(bare, store.reservePreparation(bare, allow))
        val existing = text(event = "rollback-text-event", id = "rollback-text-id")
        assertTrue(store.textJournal.remember(existing, allow))
        val expectedCapsule = TextUpdateAttemptStorageCodec.encode(existing)
        val before = historicalSnapshot(store.readableDatabase)
        store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DROP TABLE delete_attempts")
            it.execSQL("DROP TABLE time_update_attempts")
            it.version = 4 // Unexpected pre-existing nonempty text table must not be silently adopted.
        }
        TitleUpdateAttemptStore(context, name).use { migration ->
            try { migration.writableDatabase; fail("Conflicting migration schema accepted") }
            catch (_: android.database.sqlite.SQLiteException) { }
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { preserved ->
            assertEquals(4, preserved.version); assertEquals(before, historicalSnapshot(preserved))
            preserved.rawQuery("SELECT capsule FROM text_update_attempts", null).use {
                assertTrue(it.moveToFirst()); assertArrayEquals(expectedCapsule, it.getBlob(0)); assertFalse(it.moveToNext())
            }
        }
    }

    private suspend fun complete(store: TitleUpdateAttemptStore, value: TitleUpdateAttemptRecord): TitleUpdateCompletion {
        store.remember(value); assertTrue(store.claim(value, allow))
        val unknown = value.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
        store.retainEvidence(unknown, "opaque-first-title-evidence")
        val result = TitleUpdateVerifiedResult.of(unknown, TitleUpdateSubmissionObservation.Verified(
            TitleUpdateAttemptIdentity.of(unknown), value.preparation.intent.base.copy(revision = Revision("after-${value.id.value}"),
                content = value.preparation.intent.content)))
        store.retainVerified(result)
        val completion = TitleUpdateCompletion.of(result, TitleUpdateAcknowledgementRecord(
            TitleUpdateAcknowledgementReceipt.of(result, "97af8411-bf54-4d0c-8ab3-22a9f163348e"), TitleUpdateAcknowledgementState.RESTORED))
        assertTrue(store.retainCompletion(completion, allow))
        return completion
    }

    /** Explicit collision injection for independent claim guards; never bypasses production APIs outside this fixture. */
    private fun insertTextCollision(db: SQLiteDatabase, value: TextUpdateAttemptRecord) {
        db.insertOrThrow("text_update_attempts", null, ContentValues().apply {
            put("account", value.scope.account.value); put("calendar", value.scope.calendar.value); put("generation", value.scope.generation)
            put("mutation", value.id.value); put("event", value.eventId.value); put("state", value.state.name)
            put("capsule", TextUpdateAttemptStorageCodec.encode(value))
        })
    }
    private fun historicalSnapshot(db: SQLiteDatabase): List<List<String?>> {
        val tables = linkedMapOf("title_update_attempts" to listOf("account", "calendar", "generation", "mutation", "event",
            "preparation", "adapter", "version", "payload", "state", "evidence", "result", "completion"),
            "title_update_preparation_reservations" to listOf("account", "calendar", "generation", "mutation", "event", "preparation", "adapter", "version"))
        return tables.flatMap { (table, columns) ->
            val projection = columns.joinToString(",") { "typeof($it),hex(CAST($it AS BLOB))" }
            db.rawQuery("SELECT $projection FROM $table ORDER BY account,calendar,generation,mutation", null).use { row ->
                buildList { while (row.moveToNext()) add(listOf(table) + (0 until row.columnCount).map { row.getString(it) }) }
            }
        }
    }
}
