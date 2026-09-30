// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated SQLite staging fixtures. Opaque strings prove persistence, not encryption or dispatch. */
@RunWith(AndroidJUnit4::class)
class LocalCreateSubmissionStoreDeviceTest {
    private val scope = CalendarScope(AccountId("submission-staging-fixture"), CalendarId("calendar"), 0)
    private val capture = LocalCreateReservation(MutationId("fixture-captured-create"),
        LocalEventId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), scope, AdapterFormat("capture-fixture", 1),
        Instant.parse("2026-10-01T12:00:00Z"))
    private val content = EventContent("Synthetic staged create",
        EventTime.AllDay(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)),
        description = "Synthetic notes", location = "Synthetic location", details = EventDetails())
    private val intent = LocalCreateIntent(capture, content)
    private val reservation = LocalCreateSubmissionReservation(intent,
        MutationId("11111111-2222-3333-4444-555555555555"), AdapterFormat("submission-fixture", 2))
    private val submission = LocalCreateSubmission(reservation, "synthetic-opaque-submission")

    private fun fixture(block: suspend (Context, String, LocalCreateStore) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "local-submission-${UUID.randomUUID()}.db"
        val store = LocalCreateStore(context, name)
        try { block(context, name, store) } finally { store.close(); context.deleteDatabase(name) }
    }
    private suspend fun refused(block: suspend () -> Unit) {
        try { block(); fail("Expected foreign, immutable or inactive staging refusal") }
        catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
        catch (_: SQLiteException) { }
    }
    private suspend fun LocalCreateStore.capture(value: LocalCreateIntent = intent) {
        assertTrue(reserve(value.reservation)); assertEquals(value, retain(value))
    }
    private fun contentPayload(store: LocalCreateStore, localId: LocalEventId = capture.localId): String =
        store.readableDatabase.rawQuery("SELECT content FROM local_creates WHERE local_event=?", arrayOf(localId.value)).use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getString(0)
        }
    private fun anotherIntent(): LocalCreateIntent = intent.copy(reservation = capture.copy(
        id = MutationId("another-capture"), localId = LocalEventId("bbbbbbbb-cccc-dddd-eeee-ffffffffffff")))

    @Test fun attemptIdentityIsCanonicalAndDistinctAndDraftUsesOnlyFrozenCapture() = runBlocking {
        assertEquals(CalendarCreateDraft(scope, reservation.attempt, capture.timestamp, content), reservation.draft)
        val sameCaptureId = intent.copy(reservation = capture.copy(id = reservation.attempt))
        val invalid = listOf("not-a-uuid", "11111111-2222-3333-4444-55555555555", capture.localId.value,
            "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")
        for (value in invalid) refused { reservation.copy(attempt = MutationId(value)) }
        refused { reservation.copy(intent = sameCaptureId) }
        refused { submission.copy(protectedPayload = "   ") }
    }

    @Test fun missingOrOnlyReservedCaptureCannotCreateSubmissionLink() = fixture { _, _, store ->
        assertNull(store.readSubmissionReservation(scope, capture.localId)); assertNull(store.readSubmission(scope, capture.localId))
        refused { store.reserveSubmission(reservation) }
        assertNull(store.readReservation(scope, capture.localId))
        assertTrue(store.reserve(capture)); refused { store.reserveSubmission(reservation) }
        assertNull(store.readSubmissionReservation(scope, capture.localId)); assertNull(store.readIntent(scope, capture.localId))
        assertFalse(store.reserve(capture)); store.retain(intent)
        assertNotNull(store.reserveSubmission(reservation))
    }

    @Test fun durableReservedGapReopensWithoutRecreatingPermitOrPayload() = fixture { context, name, store ->
        store.capture(); val original = contentPayload(store)
        assertNotNull(store.reserveSubmission(reservation)); assertNull(store.reserveSubmission(reservation))
        assertEquals(reservation, store.readSubmissionReservation(scope, capture.localId)); assertNull(store.readSubmission(scope, capture.localId))
        store.close()
        LocalCreateStore(context, name).use { reopened ->
            assertEquals(3, reopened.readableDatabase.version)
            assertEquals(reservation, reopened.readSubmissionReservation(scope, capture.localId))
            assertNull(reopened.reserveSubmission(reservation)); assertNull(reopened.readSubmission(scope, capture.localId))
            assertEquals(original, contentPayload(reopened)); assertEquals(intent, reopened.readIntent(scope, capture.localId))
            assertFalse(reopened.reserve(capture))
        }
    }

    @Test fun firstOpaqueSubmissionReopensImmutableAndPermitCannotBeReused() = fixture { context, name, store ->
        store.capture(); val original = contentPayload(store)
        val permit = checkNotNull(store.reserveSubmission(reservation))
        assertEquals(submission, store.retainSubmission(permit, submission))
        refused { store.retainSubmission(permit, submission) }
        refused { store.retainSubmission(permit, submission.copy(protectedPayload = "synthetic-randomized-retry")) }
        assertNull(store.reserveSubmission(reservation)); assertEquals(submission, store.readSubmission(scope, capture.localId))
        store.close()
        LocalCreateStore(context, name).use { reopened ->
            assertEquals(submission, reopened.readSubmission(scope, capture.localId))
            assertEquals(reservation, reopened.readSubmissionReservation(scope, capture.localId))
            assertNull(reopened.reserveSubmission(reservation)); refused { reopened.retainSubmission(permit, submission) }
            assertEquals(original, contentPayload(reopened)); assertEquals(intent, reopened.readIntent(scope, capture.localId))
        }
    }

    @Test fun anotherStoreCannotUseEvenAnExactFirstStoresPermit() = fixture { context, name, store ->
        store.capture(); val permit = checkNotNull(store.reserveSubmission(reservation))
        LocalCreateStore(context, name).use { other ->
            refused { other.retainSubmission(permit, submission) }
            assertEquals(reservation, other.readSubmissionReservation(scope, capture.localId))
            assertNull(other.readSubmission(scope, capture.localId)); assertNull(other.reserveSubmission(reservation))
        }
        assertNull(store.readSubmission(scope, capture.localId))
    }

    @Test fun changedSnapshotIdentityFormatAndAttemptCannotReplaceDurableReservation() = fixture { _, _, store ->
        store.capture(); assertNotNull(store.reserveSubmission(reservation))
        val candidates = listOf(reservation.copy(intent = intent.copy(content = content.copy(title = "Changed snapshot"))),
            reservation.copy(intent = intent.copy(reservation = capture.copy(timestamp = capture.timestamp.plusSeconds(1)))),
            reservation.copy(format = reservation.format.copy(version = 3)), reservation.copy(format = AdapterFormat("foreign-format", 2)),
            reservation.copy(attempt = MutationId("22222222-3333-4444-5555-666666666666")))
        for (candidate in candidates) refused { store.reserveSubmission(candidate) }
        assertEquals(reservation, store.readSubmissionReservation(scope, capture.localId))
        assertNull(store.reserveSubmission(reservation)); assertNull(store.readSubmission(scope, capture.localId))
        assertEquals(intent, store.readIntent(scope, capture.localId))
    }

    @Test fun foreignCapturedSnapshotIsRefusedBeforeAnySubmissionReservation() = fixture { _, _, store ->
        store.capture()
        val candidates = listOf(intent.copy(content = content.copy(description = "Different content")),
            intent.copy(reservation = capture.copy(id = MutationId("foreign-mutation"))),
            intent.copy(reservation = capture.copy(format = AdapterFormat("foreign-capture", 1))),
            intent.copy(reservation = capture.copy(scope = scope.copy(account = AccountId("foreign")))),
            intent.copy(reservation = capture.copy(scope = scope.copy(calendar = CalendarId("foreign")))),
            intent.copy(reservation = capture.copy(scope = scope.copy(generation = 1))))
        for (candidate in candidates) {
            refused { store.reserveSubmission(reservation.copy(intent = candidate)) }
            assertNull(store.readSubmissionReservation(candidate.reservation.scope, candidate.reservation.localId))
        }
        assertNull(store.readSubmissionReservation(scope, capture.localId)); assertNotNull(store.reserveSubmission(reservation))
    }

    @Test fun competingHelpersReserveOnePermitForTheSameCapturedSnapshot() = fixture { context, name, store ->
        store.capture()
        LocalCreateStore(context, name).use { other ->
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val first = async(Dispatchers.IO) { start.await(); store.reserveSubmission(reservation) }
                val second = async(Dispatchers.IO) { start.await(); other.reserveSubmission(reservation) }
                start.complete(Unit)
                assertEquals(1, listOf(first.await(), second.await()).count { it != null })
            }
            assertEquals(reservation, other.readSubmissionReservation(scope, capture.localId))
            assertNull(other.reserveSubmission(reservation)); assertNull(store.reserveSubmission(reservation))
            assertNull(other.readSubmission(scope, capture.localId))
        }
    }

    @Test fun sameScopedAttemptCannotLinkTwoCapturesAcrossIndependentHelpers() = fixture { context, name, store ->
        val secondIntent = anotherIntent(); val secondReservation = reservation.copy(intent = secondIntent)
        store.capture(); store.capture(secondIntent)
        LocalCreateStore(context, name).use { other ->
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val first = async(Dispatchers.IO) { start.await(); runCatching { store.reserveSubmission(reservation) } }
                val second = async(Dispatchers.IO) { start.await(); runCatching { other.reserveSubmission(secondReservation) } }
                start.complete(Unit)
                val results = listOf(first.await(), second.await())
                assertEquals(1, results.count { it.isSuccess && it.getOrNull() != null })
                assertEquals(1, results.count { it.isFailure })
            }
            val links = listOf(store.readSubmissionReservation(scope, capture.localId),
                other.readSubmissionReservation(scope, secondIntent.reservation.localId))
            assertEquals(1, links.count { it != null }); assertEquals(reservation.attempt, links.filterNotNull().single().attempt)
            assertEquals(intent, store.readIntent(scope, capture.localId)); assertEquals(secondIntent, other.readIntent(scope, secondIntent.reservation.localId))
        }
    }

    @Test fun outgoingAttemptCannotAliasOtherCapturedIdentitiesInEitherReservationOrder() = fixture { _, _, store ->
        store.capture()
        val secondIntent = anotherIntent(); store.capture(secondIntent)
        val capturedMutationUuid = secondIntent.copy(reservation = secondIntent.reservation.copy(
            id = MutationId("cccccccc-dddd-eeee-ffff-000000000000"),
            localId = LocalEventId("dddddddd-eeee-ffff-0000-111111111111")))
        store.capture(capturedMutationUuid)
        refused { store.reserveSubmission(reservation.copy(attempt = MutationId(secondIntent.reservation.localId.value))) }
        refused { store.reserveSubmission(reservation.copy(attempt = capturedMutationUuid.reservation.id)) }
        assertNull(store.readSubmissionReservation(scope, capture.localId))
        assertNotNull(store.reserveSubmission(reservation))
        val attemptAsLocalIdentity = capture.copy(id = MutationId("new-capture"), localId = LocalEventId(reservation.attempt.value))
        val attemptAsCaptureMutation = capture.copy(id = reservation.attempt,
            localId = LocalEventId("eeeeeeee-ffff-0000-1111-222222222222"))
        refused { store.reserve(attemptAsLocalIdentity) }; refused { store.reserve(attemptAsCaptureMutation) }
        assertNull(store.readReservation(scope, attemptAsLocalIdentity.localId))
        assertNull(store.readReservation(scope, attemptAsCaptureMutation.localId))
        assertEquals(reservation, store.readSubmissionReservation(scope, capture.localId)); assertNull(store.reserveSubmission(reservation))
    }

    @Test fun foreignScopeReadsCannotReturnAnExistingReservationOrSubmission() = fixture { _, _, store ->
        store.capture(); val permit = checkNotNull(store.reserveSubmission(reservation)); store.retainSubmission(permit, submission)
        for (foreign in listOf(scope.copy(account = AccountId("foreign")), scope.copy(calendar = CalendarId("foreign")),
            scope.copy(generation = 1))) {
            assertNull(store.readSubmissionReservation(foreign, capture.localId)); assertNull(store.readSubmission(foreign, capture.localId))
        }
        val anotherId = LocalEventId("bbbbbbbb-cccc-dddd-eeee-ffffffffffff")
        assertNull(store.readSubmissionReservation(scope, anotherId)); assertNull(store.readSubmission(scope, anotherId))
        assertEquals(submission, store.readSubmission(scope, capture.localId))
    }

    @Test fun deniedOrRevokedReservationLeaseRollsBackBeforeDurablePermit() = fixture { _, _, store ->
        store.capture(); store.admitCapture = { false }; refused { store.reserveSubmission(reservation) }
        store.admitCapture = { true }; assertNull(store.readSubmissionReservation(scope, capture.localId))
        var calls = 0; store.admitCapture = { ++calls == 1 }; refused { store.reserveSubmission(reservation) }
        store.admitCapture = { true }; assertNull(store.readSubmissionReservation(scope, capture.localId))
        assertNotNull(store.reserveSubmission(reservation)); assertNull(store.reserveSubmission(reservation))
    }

    @Test fun deniedPayloadLeaseConsumesPermitButPreservesInspectOnlyGap() = fixture { _, _, store ->
        store.capture(); val permit = checkNotNull(store.reserveSubmission(reservation))
        store.admitCapture = { false }; refused { store.retainSubmission(permit, submission) }
        store.admitCapture = { true }
        assertEquals(reservation, store.readSubmissionReservation(scope, capture.localId)); assertNull(store.readSubmission(scope, capture.localId))
        assertNull(store.reserveSubmission(reservation)); refused { store.retainSubmission(permit, submission) }
        assertEquals(intent, store.readIntent(scope, capture.localId))
    }

    @Test fun cancellationBeforeReservationCommitDoesNotInventDurableLink() = fixture { _, _, store ->
        store.capture(); val child = Job(currentCoroutineContext()[Job]); var calls = 0
        store.admitCapture = { if (++calls == 2) child.cancel(); true }
        try { withContext(child) { store.reserveSubmission(reservation) }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        finally { store.admitCapture = { true } }
        assertNull(store.readSubmissionReservation(scope, capture.localId)); assertNull(store.readSubmission(scope, capture.localId))
        assertEquals(intent, store.readIntent(scope, capture.localId))
        assertNotNull(store.reserveSubmission(reservation)); assertNull(store.reserveSubmission(reservation))
    }

    @Test fun cancellationAfterReservationCommitBeforeTicketDeliveryCannotReacquirePreparation() = fixture { _, _, store ->
        store.capture()
        val child = Job(currentCoroutineContext()[Job])
        val deliveries = java.util.concurrent.atomic.AtomicInteger()
        // Entry uses the first dispatch. The IO transaction commits before the second dispatch
        // delivers its result back to the caller; cancel there rather than before SQLite commit.
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                if (deliveries.incrementAndGet() == 2) child.cancel()
                Dispatchers.Default.dispatch(context, block)
            }
        }
        try {
            withContext(dispatcher + child) {
                store.reserveSubmission(reservation)
                fail("Cancelled caller must not receive its committed preparation ticket")
            }
            fail("Expected cancellation at ticket delivery")
        } catch (_: CancellationException) { }
        assertEquals(2, deliveries.get())
        assertEquals(reservation, store.readSubmissionReservation(scope, capture.localId))
        assertNull(store.readSubmission(scope, capture.localId)); assertNull(store.reserveSubmission(reservation))
        assertEquals(intent, store.readIntent(scope, capture.localId))
    }

    @Test fun cancellationBeforePayloadCommitConsumesPermitAndKeepsDurableGap() = fixture { _, _, store ->
        store.capture(); val original = contentPayload(store); val permit = checkNotNull(store.reserveSubmission(reservation))
        val child = Job(currentCoroutineContext()[Job]); var calls = 0
        store.admitCapture = { if (++calls == 2) child.cancel(); true }
        try { withContext(child) { store.retainSubmission(permit, submission) }; fail("Expected cancellation") }
        catch (_: CancellationException) { }
        finally { store.admitCapture = { true } }
        assertEquals(reservation, store.readSubmissionReservation(scope, capture.localId)); assertNull(store.readSubmission(scope, capture.localId))
        assertNull(store.reserveSubmission(reservation)); refused { store.retainSubmission(permit, submission) }
        assertEquals(original, contentPayload(store)); assertEquals(intent, store.readIntent(scope, capture.localId))
    }

    @Test fun inactiveEntryAndLeaseLossAfterReadDoNotReturnStagingProof() = fixture { _, _, store ->
        store.capture(); val permit = checkNotNull(store.reserveSubmission(reservation)); store.retainSubmission(permit, submission)
        store.admitCapture = { false }
        refused { store.readSubmissionReservation(scope, capture.localId) }; refused { store.readSubmission(scope, capture.localId) }
        var calls = 0; store.admitCapture = { ++calls == 1 }; refused { store.readSubmissionReservation(scope, capture.localId) }
        calls = 0; refused { store.readSubmission(scope, capture.localId) }
        store.admitCapture = { true }; assertEquals(submission, store.readSubmission(scope, capture.localId))
    }

    @Test fun failedReservationInsertDoesNotInventAPermitAndPayloadFailureCannotReacquireOne() = fixture { context, name, store ->
        store.capture(); val original = contentPayload(store)
        store.writableDatabase.execSQL("CREATE TRIGGER refuse_submission BEFORE INSERT ON local_create_submissions BEGIN SELECT RAISE(ABORT,'fixture'); END")
        refused { store.reserveSubmission(reservation) }; assertNull(store.readSubmissionReservation(scope, capture.localId))
        store.writableDatabase.execSQL("DROP TRIGGER refuse_submission")
        val permit = checkNotNull(store.reserveSubmission(reservation))
        store.writableDatabase.execSQL("CREATE TRIGGER refuse_payload BEFORE UPDATE OF payload ON local_create_submissions BEGIN SELECT RAISE(ABORT,'fixture'); END")
        refused { store.retainSubmission(permit, submission) }
        store.writableDatabase.execSQL("DROP TRIGGER refuse_payload")
        assertEquals(reservation, store.readSubmissionReservation(scope, capture.localId)); assertNull(store.readSubmission(scope, capture.localId))
        assertNull(store.reserveSubmission(reservation)); refused { store.retainSubmission(permit, submission) }
        assertEquals(original, contentPayload(store)); assertEquals(intent, store.readIntent(scope, capture.localId))
        store.close()
        LocalCreateStore(context, name).use { reopened ->
            assertEquals(reservation, reopened.readSubmissionReservation(scope, capture.localId)); assertNull(reopened.readSubmission(scope, capture.localId))
            assertNull(reopened.reserveSubmission(reservation)); refused { reopened.retainSubmission(permit, submission) }
        }
    }

    @Test fun corruptedLinkSnapshotIsRefusedInsteadOfFallingBackToCapturedIdentity() = fixture { _, _, store ->
        store.capture(); val original = contentPayload(store); val permit = checkNotNull(store.reserveSubmission(reservation))
        store.retainSubmission(permit, submission)
        val altered = JSONObject(original).put("description", "Changed retained snapshot").toString()
        store.writableDatabase.update("local_create_submissions", ContentValues().apply { put("captured_content", altered) }, null, null)
        refused { store.readSubmissionReservation(scope, capture.localId) }; refused { store.readSubmission(scope, capture.localId) }
        refused { store.reserveSubmission(reservation) }
        assertEquals(intent, store.readIntent(scope, capture.localId)); assertFalse(store.reserve(capture))
        store.writableDatabase.update("local_create_submissions", ContentValues().apply { put("captured_content", original) }, null, null)
        assertEquals(submission, store.readSubmission(scope, capture.localId)); assertNull(store.reserveSubmission(reservation))
    }

    @Test fun mismatchedPayloadReservationIsRefusedWithoutReplacingTheFirstLink() = fixture { _, _, store ->
        store.capture(); val permit = checkNotNull(store.reserveSubmission(reservation))
        refused { store.retainSubmission(permit, submission.copy(reservation = reservation.copy(format = AdapterFormat("foreign", 2)))) }
        assertEquals(reservation, store.readSubmissionReservation(scope, capture.localId)); assertNull(store.readSubmission(scope, capture.localId))
        assertNull(store.reserveSubmission(reservation)); assertEquals(intent, store.readIntent(scope, capture.localId))
    }

    @Test fun independentlyConstructedSchemaTwoMigrationPreservesRawCaptureAndLegacyPreparation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>(); val name = "submission-migration-${UUID.randomUUID()}.db"
        val secondIntent = anotherIntent()
        val legacyPreparation = LocalCreatePreparation(secondIntent, AdapterFormat("legacy-preparation-fixture", 1), "synthetic-legacy-preparation")
        val records = listOf(intent, secondIntent)
        val raw = records.associate { it.reservation.localId to legacyContent(it) }
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { db ->
                db.execSQL("""CREATE TABLE local_creates(account TEXT NOT NULL,calendar TEXT NOT NULL,
                    generation INTEGER NOT NULL CHECK(generation>=0),local_event TEXT NOT NULL,mutation TEXT NOT NULL,
                    adapter TEXT NOT NULL,version INTEGER NOT NULL CHECK(version>0),created_at TEXT NOT NULL,
                    marker_protocol INTEGER NOT NULL CHECK(marker_protocol=1),content TEXT,prep_adapter TEXT,prep_version INTEGER,prep_payload TEXT,
                    PRIMARY KEY(account,calendar,generation,local_event),UNIQUE(account,calendar,generation,mutation),
                    CHECK ((prep_adapter IS NULL AND prep_version IS NULL AND prep_payload IS NULL) OR
                    (prep_adapter IS NOT NULL AND prep_version IS NOT NULL AND prep_payload IS NOT NULL AND prep_version>0)))""".trimIndent())
                for (record in records) db.insertOrThrow("local_creates", null, ContentValues().apply {
                    val identity = record.reservation
                    put("account", scope.account.value); put("calendar", scope.calendar.value); put("generation", scope.generation)
                    put("local_event", identity.localId.value); put("mutation", identity.id.value); put("adapter", identity.format.id)
                    put("version", identity.format.version); put("created_at", identity.timestamp.toString()); put("marker_protocol", identity.markerProtocol)
                    put("content", raw.getValue(identity.localId))
                    if (record == secondIntent) {
                        put("prep_adapter", legacyPreparation.format.id); put("prep_version", legacyPreparation.format.version)
                        put("prep_payload", legacyPreparation.protectedPayload)
                    }
                })
                db.version = 2
            }
            LocalCreateStore(context, name).use { store ->
                assertEquals(3, store.readableDatabase.version)
                for (record in records) {
                    assertEquals(record, store.readIntent(scope, record.reservation.localId))
                    assertEquals(raw.getValue(record.reservation.localId), contentPayload(store, record.reservation.localId))
                    assertFalse(store.reserve(record.reservation)); assertNull(store.readSubmissionReservation(scope, record.reservation.localId))
                    assertNull(store.readSubmission(scope, record.reservation.localId))
                }
                assertNull(store.readPreparation(scope, capture.localId)); assertEquals(legacyPreparation, store.readPreparation(scope, secondIntent.reservation.localId))
                val permit = checkNotNull(store.reserveSubmission(reservation))
                assertEquals(submission, store.retainSubmission(permit, submission))
                assertEquals(legacyPreparation, store.readPreparation(scope, secondIntent.reservation.localId))
            }
        } finally { context.deleteDatabase(name) }
    }

    /** Construct v2 content independently rather than using the production serializer being migrated. */
    private fun legacyContent(value: LocalCreateIntent): String = JSONObject().apply {
        val identity = value.reservation
        put("schema", 1); put("knownEmptyDetails", true)
        put("reservation", JSONObject().apply {
            put("id", identity.id.value); put("localId", identity.localId.value); put("account", identity.scope.account.value)
            put("calendar", identity.scope.calendar.value); put("generation", identity.scope.generation)
            put("adapter", identity.format.id); put("version", identity.format.version)
            put("timestamp", identity.timestamp.toString()); put("markerProtocol", identity.markerProtocol)
        })
        put("title", value.content.title); put("description", value.content.description); put("location", value.content.location)
        put("allDay", true); put("start", "2026-10-01"); put("end", "2026-10-02")
    }.toString()
}
