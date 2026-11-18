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
import com.patmanak.proton.andcalendarbridge.data.outgoing.TextUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.serialization.TextUpdateAttemptStorageCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.TextUpdateProofStorageCodec
import com.patmanak.proton.andcalendarbridge.data.serialization.TextUpdateAcknowledgementCodec
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in isolated SQLite history. No Provider, account, cryptographic or service operation. */
@RunWith(AndroidJUnit4::class)
class TextUpdateProofStoreDeviceTest {
    private val scope = CalendarScope(AccountId("proof-account"), CalendarId("proof-calendar"), 3)
    private val allow: (CalendarScope) -> Boolean = { true }
    private val content = EventContent("Original", EventTime.Timed(Instant.parse("2026-10-24T10:00:00Z"),
        Instant.parse("2026-10-24T11:00:00Z"), ZoneId.of("Europe/Paris")), "Old\r\nnotes", "Old room", details = EventDetails())
    private fun work(event: String = "proof-event", id: String = "proof-request", target: CalendarScope = scope,
        payload: String = "opaque-request"): TextUpdateAttemptRecord {
        val intent = LocalEditIntent(MutationId("intent-$id"), target, AdapterFormat("fixture-import", 1),
            RemoteChange.Upsert(EventId(event), Revision("revision-before"), content),
            content.copy(title = "New", description = "New\r\nnotes", location = "New room"))
        return TextUpdateAttemptRecord(MutationId(id), LocalTextEditPreparation(intent, Instant.parse("2026-10-15T09:00:00Z"),
            AdapterFormat("fixture-text-preparation", 1), "opaque-preparation"), AdapterFormat("fixture-request", 1), payload)
    }
    private fun title(event: String = "proof-event", id: String = "next-title", target: CalendarScope = scope): TitleUpdateAttemptRecord {
        val intent = LocalEditIntent(MutationId("intent-$id"), target, AdapterFormat("fixture-import", 1),
            RemoteChange.Upsert(EventId(event), Revision("revision-before"), content), content.copy(title = "New title"))
        return TitleUpdateAttemptRecord(MutationId(id), LocalEditPreparation(intent, Instant.parse("2026-10-15T09:00:00Z"),
            AdapterFormat("fixture-title", 1), "opaque-title-preparation"), AdapterFormat("fixture-request", 1), "opaque-title-request")
    }
    private fun result(record: TextUpdateAttemptRecord, revision: String = "revision-after"): TextUpdateVerifiedResult {
        val unknown = record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = null)
        val event = unknown.preparation.intent.base.copy(revision = Revision(revision),
            content = unknown.preparation.intent.content.copy(description = "New\nnotes"))
        return TextUpdateVerifiedResult.of(unknown, TextUpdateSubmissionObservation.Verified(unknown, event))
    }
    private fun completion(result: TextUpdateVerifiedResult) = TextUpdateCompletion.of(result,
        TextUpdateAcknowledgementRecord(TextUpdateAcknowledgementReceipt.of(result,
            "97af8411-bf54-4d0c-8ab3-22a9f163348e"), TextUpdateAcknowledgementState.RESTORED))
    private fun fixture(block: suspend (Context, String, TitleUpdateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "text-proof-fixture-${UUID.randomUUID()}.db"
        val store = TitleUpdateAttemptStore(context, name)
        try { block(context, name, store) } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun retained(store: TitleUpdateAttemptStore, record: TextUpdateAttemptRecord = work()): TextUpdateVerifiedResult {
        assertTrue(store.textJournal.remember(record, allow)); assertTrue(store.textJournal.claim(record, allow))
        return result(record).also { assertTrue(store.textJournal.retainVerified(it, allow)) }
    }
    private suspend fun corrupt(block: suspend () -> Unit) {
        try { block(); fail("Corrupt proof must not release ownership") } catch (_: IllegalStateException) { }
    }

    @Test fun fullRawIntentAndActualNormalizedObservationSurviveReopening() = fixture { context, name, store ->
        val value = retained(store)
        assertTrue(store.textJournal.retainEvidence(value.record, "first-evidence", allow))
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(value, reopened.textJournal.readVerified(scope, value.record.id, allow))
            assertEquals("New\r\nnotes", value.record.preparation.intent.content.description)
            assertEquals("New\nnotes", value.event.content.description)
            assertEquals("first-evidence", reopened.textJournal.read(scope, value.record.id, allow)?.protectedEvidence)
        }
    }
    @Test fun firstVerifiedResultIsImmutableAndCannotAttachToDifferentWork() = fixture { _, _, store ->
        val value = retained(store)
        assertTrue(store.textJournal.retainVerified(value, allow))
        assertFalse(store.textJournal.retainVerified(result(value.record, "different-revision"), allow))
        assertFalse(store.textJournal.retainVerified(result(value.record.copy(protectedPayload = "different-request")), allow))
        assertEquals(value, store.textJournal.readVerified(scope, value.record.id, allow))
    }
    @Test fun preparedOrMissingWorkCannotAcquireProofAndProofDoesNotReleaseEvent() = fixture { _, _, store ->
        val record = work(); val value = result(record)
        assertFalse(store.textJournal.retainVerified(value, allow))
        assertTrue(store.textJournal.remember(record, allow))
        assertFalse(store.textJournal.retainVerified(value, allow))
        assertTrue(store.textJournal.claim(record, allow)); assertTrue(store.textJournal.retainVerified(value, allow))
        assertFalse(store.textJournal.remember(work(id = "next"), allow))
        assertFalse(store.remember(title(), allow)); assertFalse(store.textJournal.claim(record, allow))
    }
    @Test fun completionRequiresFirstExactResultAndRestoredReceipt() = fixture { _, _, store ->
        val record = work(); val value = result(record); val proof = completion(value)
        assertTrue(store.textJournal.remember(record, allow)); assertTrue(store.textJournal.claim(record, allow))
        assertFalse(store.textJournal.retainCompletion(proof, allow))
        assertTrue(store.textJournal.retainVerified(value, allow))
        assertFalse(store.textJournal.retainCompletion(completion(result(record, "another-revision")), allow))
        assertTrue(store.textJournal.retainCompletion(proof, allow)); assertTrue(store.textJournal.retainCompletion(proof, allow))
        assertEquals(proof, store.textJournal.readCompletion(scope, record.id, allow))
    }
    @Test fun completedTextReleasesEventButNeverOldRequestIdentityOrUnknownState() = fixture { context, name, store ->
        val value = retained(store); val proof = completion(value)
        assertTrue(store.textJournal.retainCompletion(proof, allow))
        val old = store.textJournal.read(scope, value.record.id, allow)
        val next = work(id = "distinct-request", target = scope.copy(generation = 4))
        assertTrue(store.textJournal.remember(next, allow))
        assertFalse(store.textJournal.remember(work(event = "other-event", target = scope.copy(generation = 5)), allow))
        assertFalse(store.remember(title(event = "other-event", id = value.record.id.value), allow))
        assertFalse(store.textJournal.claim(work(), allow)); assertFalse(store.textJournal.remember(work(), allow))
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(old, reopened.textJournal.read(scope, value.record.id, allow))
            assertEquals(proof, reopened.textJournal.readCompletion(scope, value.record.id, allow))
            assertEquals(next, reopened.textJournal.read(next.scope, next.id, allow))
        }
    }
    @Test fun validCompletionPermitsNewTitleAndPreservesHistoricalEvidence() = fixture { _, _, store ->
        val value = retained(store); assertTrue(store.textJournal.retainCompletion(completion(value), allow))
        val next = title(target = scope.copy(generation = 4))
        assertNotNull(store.reservePreparation(TitleUpdatePreparationReservation.of(next), allow))
        assertTrue(store.remember(next, allow)); assertTrue(store.claim(next, allow))
        assertTrue(store.textJournal.retainEvidence(value.record, "late-factual-evidence", allow))
        assertEquals("late-factual-evidence", store.textJournal.read(scope, value.record.id, allow)?.protectedEvidence)
    }
    @Test fun nonNullMalformedCompletionAndCorruptResultNeverReleaseEitherFamily() = fixture { _, _, store ->
        val value = retained(store)
        for (column in listOf("completion", "result")) {
            if (column == "completion") store.writableDatabase.execSQL("UPDATE text_update_attempts SET completion=x'0001'")
            else {
                store.writableDatabase.execSQL("UPDATE text_update_attempts SET completion=NULL")
                assertTrue(store.textJournal.retainCompletion(completion(value), allow))
                store.writableDatabase.execSQL("UPDATE text_update_attempts SET result=x'0001'")
            }
            corrupt { store.remember(title(), allow) }
            corrupt { store.textJournal.remember(work(id = "different-request"), allow) }
        }
    }
    @Test fun resultAndCompletionLeasesRevokeOrCancelBeforeCommit() = fixture { _, _, store ->
        val record = work(); val value = result(record)
        assertTrue(store.textJournal.remember(record, allow)); assertTrue(store.textJournal.claim(record, allow))
        var checks = 0
        assertFalse(store.textJournal.retainVerified(value) { ++checks < 2 })
        assertNull(store.textJournal.readVerified(scope, record.id, allow))
        assertTrue(store.textJournal.retainVerified(value, allow))
        checks = 0; assertFalse(store.textJournal.retainCompletion(completion(value)) { ++checks < 2 })
        assertNull(store.textJournal.readCompletion(scope, record.id, allow))
        var cancelled = 0
        supervisorScope {
            val child = async {
                val job = currentCoroutineContext().job; checks = 0
                try { store.textJournal.retainCompletion(completion(value)) {
                    if (++checks == 2) { job.cancel(); throw java.io.IOException("synthetic-sensitive") }; true
                } } catch (_: CancellationException) { cancelled++ }
            }
            try { child.await() } catch (_: CancellationException) { }
        }
        assertEquals(1, cancelled); assertNull(store.textJournal.readCompletion(scope, record.id, allow))
    }
    @Test fun resultReadsAreScopedAndDeniedReadsGrantNoAbsence() = fixture { _, _, store ->
        val value = retained(store)
        assertNull(store.textJournal.readVerified(scope, value.record.id) { false })
        assertNull(store.textJournal.readVerified(scope.copy(generation = 4), value.record.id, allow))
        assertFalse(store.textJournal.retainCompletion(completion(value)) { false })
        assertEquals(value, store.textJournal.readVerified(scope, value.record.id, allow))
    }
    @Test fun largeProofAndCompletionUseSegmentedBlobReads() = fixture { context, name, store ->
        val record = work(payload = "p".repeat(2 * 1024 * 1024 + 37))
        val value = retained(store, record); val proof = completion(value)
        assertTrue(store.textJournal.retainCompletion(proof, allow))
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(value, reopened.textJournal.readVerified(scope, record.id, allow))
            assertEquals(proof, reopened.textJournal.readCompletion(scope, record.id, allow))
        }
    }
    @Test fun additiveMigrationPreservesExactPreexistingAttemptCapsule() = fixture { context, name, store ->
        val record = work(); assertTrue(store.textJournal.remember(record, allow)); store.close()
        val capsule = TextUpdateAttemptStorageCodec.encode(record)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE personal_update_attempts")
            db.execSQL("DROP TABLE delete_attempts")
            db.execSQL("DROP TABLE time_update_attempts")
            db.execSQL("DROP TABLE text_update_attempts")
            db.execSQL("CREATE TABLE text_update_attempts(account TEXT NOT NULL,calendar TEXT NOT NULL,generation INTEGER NOT NULL CHECK(generation>=0),mutation TEXT NOT NULL,event TEXT NOT NULL,state TEXT NOT NULL,capsule BLOB NOT NULL,PRIMARY KEY(account,calendar,generation,mutation),UNIQUE(account,calendar,event),UNIQUE(account,calendar,mutation))")
            db.insertOrThrow("text_update_attempts", null, ContentValues().apply {
                put("account", scope.account.value); put("calendar", scope.calendar.value); put("generation", scope.generation)
                put("mutation", record.id.value); put("event", record.eventId.value); put("state", record.state.name); put("capsule", capsule)
            })
            db.execSQL("DROP TABLE conflict_resolution_fences"); db.version = 5
        }
        TitleUpdateAttemptStore(context, name).use { migrated ->
            assertEquals(12, migrated.readableDatabase.version); assertEquals(record, migrated.textJournal.read(scope, record.id, allow))
            migrated.readableDatabase.rawQuery("SELECT capsule,result,completion FROM text_update_attempts", null).use {
                assertTrue(it.moveToFirst()); assertArrayEquals(capsule, it.getBlob(0)); assertTrue(it.isNull(1)); assertTrue(it.isNull(2))
            }
        }
    }
    @Test fun independentHelpersCompeteForOneOwnerAfterCompletedText() = fixture { context, name, store ->
        val value = retained(store); assertTrue(store.textJournal.retainCompletion(completion(value), allow))
        TitleUpdateAttemptStore(context, name).use { other ->
            other.readableDatabase
            val gate = CompletableDeferred<Unit>()
            val outcomes = coroutineScope {
                val a = async(Dispatchers.IO) { gate.await(); store.remember(title(), allow) }
                val b = async(Dispatchers.IO) { gate.await(); other.textJournal.remember(work(id = "next-text"), allow) }
                gate.complete(Unit); listOf(a.await(), b.await())
            }
            assertEquals(1, outcomes.count { it })
        }
    }
    @Test fun transferredCompletionWithExistingProjectionReopensExactRawAndObservedContents() = fixture { _, _, _ ->
        val value = result(work())
        val intent = value.record.preparation.intent
        val original = intent.base.copy(revision = Revision("original-create-revision"))
        val capture = LocalCreateIntent(LocalCreateReservation(MutationId("synthetic-create-capture"),
            LocalEventId(UUID(0, 2).toString()), scope, intent.format, value.record.preparation.timestamp), content)
        val submission = LocalCreateSubmission(LocalCreateSubmissionReservation(capture, MutationId(UUID(0, 3).toString()),
            AdapterFormat("fixture-create", 1)), "opaque-create-request")
        val binding = CapturedCreateBinding(CapturedCreateDispatchIdentity.of(submission), original.eventId, original.revision)
        val baseline = CapturedCreateBaseline(CapturedCreateAttachmentReceipt(binding, UUID(0, 4).toString()), original)
        val handoff = CapturedCreateHandoffFrame(CapturedCreateHandoffReceipt(
            CapturedCreateAcknowledgementReceipt(baseline, UUID(0, 5).toString()), UUID(0, 6).toString()), intent.base,
            ProjectionReceipt(scope, intent.format, intent.base, UUID(0, 7).toString(), original))
        val receipt = TextUpdateAcknowledgementReceipt.of(value, UUID(0, 8).toString(), handoff)
        val proof = TextUpdateCompletion.of(value, TextUpdateAcknowledgementRecord(receipt, TextUpdateAcknowledgementState.RESTORED))
        assertEquals(proof, TextUpdateProofStorageCodec.decodeCompletion(TextUpdateProofStorageCodec.encodeCompletion(proof)))
    }
    @Test fun proofFramingRejectsChangedTagsVersionsLengthsTruncationTrailingBytesAndMalformedUtf8() = fixture { _, _, _ ->
        val bytes = TextUpdateProofStorageCodec.encodeResult(result(work()))
        val tag = "CALENDAR_TEXT_UPDATE_RESULT"
        assertArrayEquals(tag.toByteArray(), bytes.copyOfRange(0, tag.length))
        val invalid = listOf(bytes.copyOf().also { it[0] = 0 },
            bytes.copyOf().also { ByteBuffer.wrap(it).putInt(tag.length, 2) },
            bytes.copyOf().also { ByteBuffer.wrap(it).putInt(tag.length + 4, -1) },
            bytes.copyOf().also { ByteBuffer.wrap(it).putInt(tag.length + 4, Int.MAX_VALUE) },
            bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0),
            bytes.copyOf().also { it[it.lastIndex] = 0x80.toByte() })
        for (payload in invalid) {
            try { TextUpdateProofStorageCodec.decodeResult(payload); fail("Malformed framing was accepted") }
            catch (failure: IllegalArgumentException) { assertEquals("Invalid text proof capsule", failure.message); assertNull(failure.cause) }
        }
        val completion = TextUpdateProofStorageCodec.encodeCompletion(completion(result(work())))
        for (payload in listOf(completion.copyOf(20), completion + byteArrayOf(0))) {
            try { TextUpdateProofStorageCodec.decodeCompletion(payload); fail("Malformed completion was accepted") }
            catch (failure: IllegalArgumentException) { assertEquals("Invalid text proof capsule", failure.message); assertNull(failure.cause) }
        }
    }
    @Test fun receiptEncodingRejectsUnknownKindCoercionsDuplicateKeysAndTrailingWhitespace() = fixture { _, _, _ ->
        val receipt = TextUpdateAcknowledgementReceipt.of(result(work()), UUID(0, 8).toString())
        val payload = TextUpdateAcknowledgementCodec.encode(receipt)
        val invalid = listOf(payload.replace("text-update-acknowledgement", "title-update-acknowledgement"),
            payload.replace("\"generation\":3", "\"generation\":\"3\""),
            payload.replaceFirst("{", "{\"schema\":1,"), payload + " ")
        for (text in invalid) {
            assertNotEquals(payload, text)
            try { TextUpdateAcknowledgementCodec.decode(text); fail("Foreign or noncanonical receipt was accepted") }
            catch (_: Exception) { }
        }
    }
    @Test fun timedAndAllDayProofsKeepExactTimeAndRawVersusObservedTexts() = fixture { _, _, _ ->
        val timed = work()
        val time = EventTime.AllDay(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-27"))
        val intent = timed.preparation.intent
        val dayIntent = intent.copy(base = intent.base.copy(content = intent.base.content.copy(time = time)),
            content = intent.content.copy(time = time))
        val allDay = timed.copy(preparation = LocalTextEditPreparation(dayIntent, timed.preparation.timestamp,
            timed.preparation.format, timed.preparation.protectedPayload))
        for (record in listOf(timed, allDay)) {
            val value = result(record)
            val actual = TextUpdateProofStorageCodec.decodeResult(TextUpdateProofStorageCodec.encodeResult(value))
            assertEquals(value, actual); assertEquals(record.preparation.intent.content.time, actual.event.content.time)
            assertEquals("New\r\nnotes", actual.record.preparation.intent.content.description)
            assertEquals("New\nnotes", actual.event.content.description)
        }
    }

    @Test fun oldestOfThreeCompletedHistoriesStillBlocksBothFamiliesWhenCorrupt() = fixture { context, name, store ->
        val completed = (1..3).map { index ->
            val value = retained(store, work(id = "completed-$index", target = scope.copy(generation = index.toLong())))
            completion(value).also { assertTrue(store.textJournal.retainCompletion(it, allow)) }
        }
        for (proof in completed) {
            val historicalId = proof.result.record.id.value
            assertFalse(store.textJournal.remember(work(event = "different-event", id = historicalId,
                target = scope.copy(generation = 9)), allow))
            assertFalse(store.remember(title(event = "different-event", id = historicalId,
                target = scope.copy(generation = 9)), allow))
        }
        store.writableDatabase.execSQL("UPDATE text_update_attempts SET completion=x'0001' WHERE mutation=?",
            arrayOf(completed.first().result.record.id.value))
        store.close()
        TitleUpdateAttemptStore(context, name).use { reopened ->
            assertEquals(completed.last(), reopened.textJournal.readCompletion(completed.last().result.record.scope,
                completed.last().result.record.id, allow))
            val nextTitle = title(target = scope.copy(generation = 9))
            corrupt { reopened.reservePreparation(TitleUpdatePreparationReservation.of(nextTitle), allow) }
            corrupt { reopened.remember(nextTitle, allow) }
            corrupt { reopened.textJournal.remember(work(id = "fourth-request", target = scope.copy(generation = 9)), allow) }
            corrupt { reopened.textJournal.remember(work(event = "different-event",
                id = completed.first().result.record.id.value, target = scope.copy(generation = 9)), allow) }
            reopened.readableDatabase.rawQuery("SELECT state FROM text_update_attempts ORDER BY generation", null).use {
                var count = 0
                while (it.moveToNext()) { assertEquals("OUTCOME_UNKNOWN", it.getString(0)); count++ }
                assertEquals(3, count)
            }
        }
    }

    @Test fun standaloneVersionOneUpgradePreservesUnknownCapsuleBytesAndEvidence() = fixture { context, _, _ ->
        val name = "standalone-text-proof-migration-${UUID.randomUUID()}.db"
        val record = work().copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "original-evidence")
        val capsule = TextUpdateAttemptStorageCodec.encode(record)
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
                createLegacyTextTable(db); insertLegacyText(db, record, capsule); db.version = 1
            }
            TextUpdateAttemptStore(context, name).use { migrated ->
                assertEquals(2, migrated.readableDatabase.version)
                assertEquals(record, migrated.read(scope, record.id, allow))
                assertFalse(migrated.claim(work(), allow))
                migrated.readableDatabase.rawQuery("SELECT capsule,result,completion FROM text_update_attempts", null).use {
                    assertTrue(it.moveToFirst()); assertArrayEquals(capsule, it.getBlob(0))
                    assertTrue(it.isNull(1)); assertTrue(it.isNull(2)); assertFalse(it.moveToNext())
                }
            }
            TextUpdateAttemptStore(context, name).use { reopened -> assertEquals(record, reopened.read(scope, record.id, allow)) }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun sharedVersionFiveFailureAfterRenameRollsBackTableVersionAndOpaqueCapsule() = fixture { context, name, store ->
        val record = work().copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "historical-evidence")
        val capsule = TextUpdateAttemptStorageCodec.encode(record)
        store.readableDatabase; store.close()
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TABLE personal_update_attempts")
            db.execSQL("DROP TABLE delete_attempts")
            db.execSQL("DROP TABLE time_update_attempts")
            db.execSQL("DROP TABLE text_update_attempts")
            createLegacyTextTable(db); insertLegacyText(db, record, capsule)
            // Force CREATE INDEX failure after the migration has renamed and rebuilt the attempt table.
            db.execSQL("CREATE TABLE migration_index_collision(value TEXT)")
            db.execSQL("CREATE INDEX text_update_unresolved_event ON migration_index_collision(value)")
            db.execSQL("DROP TABLE conflict_resolution_fences")
            db.version = 5
        }
        try {
            TitleUpdateAttemptStore(context, name).use { it.readableDatabase }
            fail("Migration must report the injected schema collision")
        } catch (_: SQLiteException) { }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            assertEquals(5, db.version)
            db.rawQuery("SELECT capsule,state FROM text_update_attempts", null).use {
                assertTrue(it.moveToFirst()); assertArrayEquals(capsule, it.getBlob(0))
                assertEquals("OUTCOME_UNKNOWN", it.getString(1)); assertFalse(it.moveToNext())
            }
            db.rawQuery("PRAGMA table_info(text_update_attempts)", null).use {
                val columns = buildList { while (it.moveToNext()) add(it.getString(1)) }
                assertEquals(listOf("account", "calendar", "generation", "mutation", "event", "state", "capsule"), columns)
            }
            db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='text_update_attempts_legacy'", null).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            db.rawQuery("SELECT tbl_name FROM sqlite_master WHERE name='text_update_unresolved_event'", null).use {
                assertTrue(it.moveToFirst()); assertEquals("migration_index_collision", it.getString(0))
            }
            db.execSQL("DROP TABLE migration_index_collision")
        }
        TitleUpdateAttemptStore(context, name).use { migrated ->
            assertEquals(12, migrated.readableDatabase.version)
            assertEquals(record, migrated.textJournal.read(scope, record.id, allow))
        }
    }

    /** Independently reconstructed schema used before result/completion columns existed. */
    private fun createLegacyTextTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE text_update_attempts(account TEXT NOT NULL,calendar TEXT NOT NULL," +
            "generation INTEGER NOT NULL CHECK(generation>=0),mutation TEXT NOT NULL,event TEXT NOT NULL," +
            "state TEXT NOT NULL CHECK(state IN ('PREPARED','OUTCOME_UNKNOWN')),capsule BLOB NOT NULL," +
            "PRIMARY KEY(account,calendar,generation,mutation)," +
            "UNIQUE(account,calendar,event),UNIQUE(account,calendar,mutation))")
    }
    private fun insertLegacyText(db: SQLiteDatabase, record: TextUpdateAttemptRecord, capsule: ByteArray) {
        db.insertOrThrow("text_update_attempts", null, ContentValues().apply {
            put("account", record.scope.account.value); put("calendar", record.scope.calendar.value)
            put("generation", record.scope.generation); put("mutation", record.id.value); put("event", record.eventId.value)
            put("state", record.state.name); put("capsule", capsule)
        })
    }
}
