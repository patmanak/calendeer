// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.content.ContentValues
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
import java.time.Instant
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

/** Opt-in isolated SQLite fixtures (-e providerFixture true). No network, account or Provider IO.
 * Synthetic opaque strings exercise persistence, not Keystore or server compatibility.
 */
@RunWith(AndroidJUnit4::class)
class CapturedCreateAttachmentStoreDeviceTest {
    private val scope = CalendarScope(AccountId("attachment-fixture-account"), CalendarId("attachment-fixture-calendar"), 0)
    private val captureFormat = AdapterFormat("synthetic-capture", 1)
    private val content = EventContent("Synthetic attachment fixture",
        EventTime.AllDay(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)), details = EventDetails())
    private val event = RemoteChange.Upsert(EventId("synthetic-attachment-event"), Revision("synthetic-first-revision"), content)
    private val evidence = "synthetic-opaque-evidence-not-keystore-proof"
    private fun submission(index: Int = 1, target: CalendarScope = scope) = LocalCreateSubmission(
        LocalCreateSubmissionReservation(LocalCreateIntent(LocalCreateReservation(MutationId("capture-$index"),
            LocalEventId(UUID(0, index.toLong() * 2).toString()), target, captureFormat,
            Instant.parse("2026-01-01T00:00:00Z")), content), MutationId(UUID(0, index.toLong() * 2 + 1).toString()),
            AdapterFormat("synthetic-submission", 2)), "synthetic-opaque-submission-$index-not-keystore-proof")
    private fun prepared(value: LocalCreateSubmission = submission()) = CreateAttemptRecord(value.reservation.attempt,
        value.reservation.intent.reservation.scope, value.reservation.format, value.protectedPayload)
    private suspend fun bound(store: CreateAttemptStore, value: LocalCreateSubmission = submission(),
        change: RemoteChange.Upsert = event): Pair<CreateAttemptRecord, CapturedCreateBinding> {
        val link = store.rememberCaptured(value)
        assertTrue(store.claimCaptured(prepared(value), link))
        val unknown = prepared(value).copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
        return unknown to store.bindCaptured(unknown, link, CreateSubmissionObservation.Verified(link.attempt, change))
    }
    private fun fixture(block: suspend (Context, String, CreateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "captured-attachment-fixture-${UUID.randomUUID()}.db"
        val store = CreateAttemptStore(context, name)
        try { block(context, name, store) }
        finally { store.close(); context.deleteDatabase(name) }
    }
    private fun raw(context: Context, name: String, block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE).use(block)
    }
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected captured attachment refusal") }
        catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
        catch (_: SQLiteException) { }
    }

    @Test fun firstNonceSurvivesReopenAndReplayWithoutChangingUnknownEvidenceOrHold() = fixture { context, name, store ->
        val (unknown, binding) = bound(store)
        store.retainEvidence(unknown, evidence)
        val before = store.capturedProjectionHold(scope, captureFormat)
        assertNull(store.capturedAttachment(binding))
        val receipt = store.prepareCapturedAttachment(binding)
        assertEquals(UUID.fromString(receipt.nonce).toString(), receipt.nonce)
        assertEquals(1, receipt.protocol); assertEquals(binding, receipt.binding)
        assertEquals(receipt, store.prepareCapturedAttachment(binding))
        assertEquals(receipt, store.capturedAttachment(binding))
        assertEquals(before, store.capturedProjectionHold(scope, captureFormat))
        assertEquals(unknown.copy(protectedEvidence = evidence), store.read(scope, unknown.id))
        assertFalse(store.claimCaptured(prepared(), binding.identity))
        store.close()
        CreateAttemptStore(context, name).use { reopened ->
            assertEquals(receipt, reopened.capturedAttachment(binding))
            assertEquals(receipt, reopened.prepareCapturedAttachment(binding))
            assertEquals(before, reopened.capturedProjectionHold(scope, captureFormat))
            assertEquals(unknown.copy(protectedEvidence = evidence), reopened.read(scope, unknown.id))
        }
    }

    @Test fun exactParentLinkAndBindingAreRequiredForEveryReplay() = fixture { _, _, store ->
        val (_, binding) = bound(store)
        val receipt = store.prepareCapturedAttachment(binding)
        val identity = binding.identity
        val changed = listOf(binding.copy(eventId = EventId("foreign-event")),
            binding.copy(verifiedRevision = Revision("foreign-revision")),
            binding.copy(identity = identity.copy(timestamp = identity.timestamp.plusSeconds(1))),
            binding.copy(identity = identity.copy(localId = LocalEventId(UUID(0, 99).toString()))),
            binding.copy(identity = identity.copy(captureId = MutationId("foreign-capture"))),
            binding.copy(identity = identity.copy(captureFormat = captureFormat.copy(version = 2))),
            binding.copy(identity = identity.copy(contentFingerprint = "a".repeat(64))),
            binding.copy(identity = identity.copy(attempt = identity.attempt.copy(payloadFingerprint = "b".repeat(64)))),
            binding.copy(identity = identity.copy(attempt = identity.attempt.copy(scope = scope.copy(generation = 1)))))
        for (foreign in changed) {
            assertNull(store.capturedAttachment(foreign)); reject { store.prepareCapturedAttachment(foreign) }
            assertEquals(receipt, store.capturedAttachment(binding))
        }
    }

    @Test fun preparedOrUnboundGenericWorkCannotAcquireAnAttachmentPlan() = fixture { _, _, store ->
        val link = store.rememberCaptured(submission())
        val wanted = CapturedCreateBinding(link, event.eventId, event.revision)
        reject { store.prepareCapturedAttachment(wanted) }; assertNull(store.capturedAttachment(wanted))
        assertTrue(store.claimCaptured(prepared(), link))
        reject { store.prepareCapturedAttachment(wanted) }; assertNull(store.capturedAttachment(wanted))
        val generic = prepared(submission(2))
        store.remember(generic); assertTrue(store.claim(generic))
        val foreign = CapturedCreateBinding(CapturedCreateDispatchIdentity.of(submission(2)), event.eventId, event.revision)
        reject { store.prepareCapturedAttachment(foreign) }; assertNull(store.capturedAttachment(foreign))
        assertTrue(store.capturedProjectionHold(scope, captureFormat).scopeBlocked)
    }

    @Test fun malformedReceiptCannotBeReplacedAndItsHoldBecomesConservative() = fixture { context, name, store ->
        val (_, binding) = bound(store)
        val receipt = store.prepareCapturedAttachment(binding)
        for (corruption in listOf("nonce='not-a-uuid'", "nonce='AAAAAAAA-0000-0000-0000-000000000000'", "protocol=2",
            "event='foreign-event'", "verified_revision='foreign-revision'", "local_id='invalid-uuid'",
            "timestamp=timestamp+1", "content_fingerprint='${"c".repeat(64)}'")) {
            raw(context, name) { db -> db.execSQL("PRAGMA ignore_check_constraints=ON")
                db.execSQL("UPDATE captured_create_attachments SET $corruption") }
            assertNull(store.capturedAttachment(binding)); reject { store.prepareCapturedAttachment(binding) }
            assertTrue(store.capturedProjectionHold(scope, captureFormat).scopeBlocked)
            raw(context, name) { db -> db.execSQL("UPDATE captured_create_attachments SET nonce=?, protocol=1, event=?, " +
                "verified_revision=?, local_id=?, timestamp=?, content_fingerprint=?", arrayOf<Any>(receipt.nonce,
                binding.eventId.value, binding.verifiedRevision.value, binding.identity.localId.value,
                binding.identity.timestamp.epochSecond, binding.identity.contentFingerprint)) }
            assertEquals(receipt, store.capturedAttachment(binding))
        }
    }

    @Test fun validProofDriftCannotReinterpretTheOriginalAttachmentSnapshot() = fixture { context, name, store ->
        val (_, binding) = bound(store)
        store.prepareCapturedAttachment(binding)
        raw(context, name) { db ->
            db.execSQL("UPDATE captured_create_links SET timestamp=timestamp+1")
            db.execSQL("UPDATE captured_create_bindings SET timestamp=timestamp+1")
        }
        val changed = checkNotNull(store.capturedIdentity(scope, binding.identity.attempt.id))
        val changedBinding = checkNotNull(store.capturedBinding(changed))
        assertNotEquals(binding, changedBinding)
        assertNull(store.capturedAttachment(binding)); assertNull(store.capturedAttachment(changedBinding))
        reject { store.prepareCapturedAttachment(changedBinding) }
        assertTrue(store.capturedProjectionHold(scope, captureFormat).scopeBlocked)
    }

    @Test fun orphanedRawPlanBlocksGenericWorkAndCannotAuthorizeReplacementProofOrAdoption() = fixture { context, name, store ->
        val (unknown, binding) = bound(store)
        store.prepareCapturedAttachment(binding)
        raw(context, name) { db -> db.execSQL("PRAGMA foreign_keys=OFF"); db.execSQL("DELETE FROM captured_create_bindings") }
        assertNull(store.capturedAttachment(binding)); reject { store.prepareCapturedAttachment(binding) }
        reject { store.bindCaptured(unknown, binding.identity, CreateSubmissionObservation.Verified(binding.identity.attempt, event)) }
        raw(context, name) { db -> db.execSQL("PRAGMA foreign_keys=OFF"); db.execSQL("DELETE FROM captured_create_links") }
        assertTrue(store.isCaptured(scope, unknown.id)); assertTrue(store.capturedProjectionHold(scope, captureFormat).scopeBlocked)
        reject { store.remember(prepared()) }
        reject { store.bind(unknown, CreateSubmissionObservation.Verified(binding.identity.attempt, event)) }
        assertNull(store.binding(binding.identity.attempt))
        raw(context, name) { it.execSQL("UPDATE create_attempts SET state='PREPARED'") }
        assertFalse(store.claim(prepared()))
        raw(context, name) { db -> db.execSQL("PRAGMA foreign_keys=OFF"); db.execSQL("DELETE FROM create_attempts") }
        reject { store.rememberCaptured(submission()) }; reject { store.remember(prepared()) }
        assertNull(store.read(scope, unknown.id)); assertTrue(store.capturedProjectionHold(scope, captureFormat).scopeBlocked)
    }

    @Test fun nonceAndEventCollisionsCannotReplaceAnExistingPlan() = fixture { context, name, store ->
        val (_, first) = bound(store)
        val firstReceipt = store.prepareCapturedAttachment(first)
        val (_, second) = bound(store, submission(2), event.copy(eventId = EventId("second-event")))
        val secondReceipt = store.prepareCapturedAttachment(second)
        assertNotEquals(firstReceipt.nonce, secondReceipt.nonce)
        raw(context, name) { db ->
            try { db.execSQL("UPDATE captured_create_attachments SET nonce=? WHERE mutation=?",
                arrayOf(firstReceipt.nonce, second.identity.attempt.id.value)); fail("Nonce collision must fail") }
            catch (_: SQLiteException) { }
            try { db.execSQL("UPDATE captured_create_attachments SET event=? WHERE mutation=?",
                arrayOf(first.eventId.value, second.identity.attempt.id.value)); fail("Event collision must fail") }
            catch (_: SQLiteException) { }
        }
        assertEquals(firstReceipt, store.capturedAttachment(first)); assertEquals(secondReceipt, store.capturedAttachment(second))
    }

    @Test fun foreignKeyAndSynchronousFullPreserveTheAttachmentParentChain() = fixture { _, _, store ->
        val (_, binding) = bound(store)
        val receipt = store.prepareCapturedAttachment(binding)
        val db = store.writableDatabase
        db.rawQuery("PRAGMA foreign_keys", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        db.rawQuery("PRAGMA synchronous", null).use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
        for (table in listOf("captured_create_bindings", "captured_create_links", "create_attempts")) {
            try { db.execSQL("DELETE FROM $table"); fail("Receipt parent chain cannot be deleted") }
            catch (_: SQLiteException) { }
        }
        assertEquals(receipt, store.capturedAttachment(binding))
    }

    @Test fun failedInsertRevokedGateAndClosedGateCannotCommitANonce() = fixture { context, name, store ->
        val (_, binding) = bound(store)
        raw(context, name) { db -> db.execSQL("""CREATE TRIGGER reject_attachment BEFORE INSERT ON captured_create_attachments
            BEGIN SELECT RAISE(ABORT, 'synthetic attachment rejection'); END""".trimIndent()) }
        reject { store.prepareCapturedAttachment(binding) }; assertNull(store.capturedAttachment(binding))
        raw(context, name) { it.execSQL("DROP TRIGGER reject_attachment") }
        store.admitAssociation = { false }
        reject { store.prepareCapturedAttachment(binding) }; assertNull(store.capturedAttachment(binding))
        var checks = 0
        store.admitAssociation = { ++checks == 1 }
        reject { store.prepareCapturedAttachment(binding) }; assertNull(store.capturedAttachment(binding))
        store.admitAssociation = { true }
        val receipt = store.prepareCapturedAttachment(binding)
        store.admitAssociation = { false }
        reject { store.prepareCapturedAttachment(binding) }; assertEquals(receipt, store.capturedAttachment(binding))
    }

    @Test fun cancellationBeforeCommitLeavesNoInventedNonceAfterReopen() = fixture { context, name, store ->
        val (unknown, binding) = bound(store)
        coroutineScope {
            val job = async {
                val caller = currentCoroutineContext().job
                var checks = 0
                store.admitAssociation = { if (++checks == 2) caller.cancel(); true }
                store.prepareCapturedAttachment(binding)
            }
            try { job.await(); fail("Cancelled receipt cannot commit") }
            catch (_: CancellationException) { }
        }
        store.close()
        CreateAttemptStore(context, name).use { reopened ->
            assertNull(reopened.capturedAttachment(binding)); assertEquals(unknown, reopened.read(scope, unknown.id))
            assertEquals(CapturedProjectionHold(events = setOf(event.eventId)), reopened.capturedProjectionHold(scope, captureFormat))
            val receipt = reopened.prepareCapturedAttachment(binding)
            assertEquals(receipt, reopened.capturedAttachment(binding))
        }
    }

    @Test fun competingHelpersReserveOnlyOneNonceAndReturnTheSameCommittedReceipt() = fixture { context, name, store ->
        val (_, binding) = bound(store)
        CreateAttemptStore(context, name).use { other ->
            other.writableDatabase
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val first = async(Dispatchers.IO) { start.await(); store.prepareCapturedAttachment(binding) }
                val second = async(Dispatchers.IO) { start.await(); other.prepareCapturedAttachment(binding) }
                start.complete(Unit)
                val receipt = first.await()
                assertEquals(receipt, second.await())
                assertEquals(receipt, other.capturedAttachment(binding))
            }
            store.writableDatabase.rawQuery("SELECT count(*) FROM captured_create_attachments", null).use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        }
    }

    @Test fun unreadablePlanStorageNeverReturnsAnEmptyHold() = fixture { context, name, store ->
        val (_, binding) = bound(store)
        raw(context, name) { it.execSQL("DROP TABLE captured_create_attachments") }
        try { store.capturedProjectionHold(scope, captureFormat); fail("Unreadable attachment storage cannot release import") }
        catch (_: SQLiteException) { }
        try { store.capturedAttachment(binding); fail("Unreadable receipt cannot be treated as absence") }
        catch (_: SQLiteException) { }
    }

    @Test fun independentlyConstructedVersionSixMigrationPreservesProofsHistoryAndInventsNoNonces() =
        fixture { context, name, store ->
            val unknown = prepared().copy(state = CreateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = evidence)
            val link = CapturedCreateDispatchIdentity.of(submission())
            val binding = CapturedCreateBinding(link, event.eventId, event.revision)
            val paused = prepared(submission(2))
            val pausedLink = CapturedCreateDispatchIdentity.of(submission(2))
            val generic = unknown.copy(id = MutationId("legacy-generic"))
            val genericIdentity = CreateAttemptIdentity.of(generic)
            val path = context.getDatabasePath(name)
            path.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
                legacy.execSQL("""CREATE TABLE create_attempts (account TEXT NOT NULL, calendar TEXT NOT NULL,
                    generation INTEGER NOT NULL, mutation TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL,
                    payload TEXT NOT NULL, state TEXT NOT NULL, evidence TEXT,
                    PRIMARY KEY(account, calendar, generation, mutation))""".trimIndent())
                legacy.execSQL("""CREATE TABLE create_pauses (account TEXT NOT NULL, calendar TEXT NOT NULL,
                    generation INTEGER NOT NULL, mutation TEXT NOT NULL, reason TEXT NOT NULL,
                    PRIMARY KEY(account, calendar, generation, mutation))""".trimIndent())
                legacy.execSQL("""CREATE TABLE create_bindings (account TEXT NOT NULL, calendar TEXT NOT NULL,
                    generation INTEGER NOT NULL, mutation TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL,
                    fingerprint TEXT NOT NULL, event TEXT NOT NULL, verified_revision TEXT NOT NULL,
                    provider_calendar INTEGER, provider_event INTEGER, provider_revision TEXT,
                    PRIMARY KEY(account, calendar, generation, mutation), UNIQUE(account, calendar, generation, event))""".trimIndent())
                val captureColumns = """account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL,
                    mutation TEXT NOT NULL, adapter TEXT NOT NULL, version INTEGER NOT NULL, payload_fingerprint TEXT NOT NULL,
                    local_id TEXT NOT NULL, capture_id TEXT NOT NULL, capture_adapter TEXT NOT NULL,
                    capture_version INTEGER NOT NULL, timestamp INTEGER NOT NULL, content_fingerprint TEXT NOT NULL,
                    fingerprint_version INTEGER NOT NULL""".trimIndent()
                legacy.execSQL("""CREATE TABLE captured_create_links ($captureColumns,
                    PRIMARY KEY(account, calendar, generation, mutation), UNIQUE(account, calendar, generation, local_id),
                    UNIQUE(account, calendar, generation, capture_id), FOREIGN KEY(account, calendar, generation, mutation)
                    REFERENCES create_attempts(account, calendar, generation, mutation))""".trimIndent())
                legacy.execSQL("""CREATE TABLE captured_create_bindings ($captureColumns, event TEXT NOT NULL,
                    verified_revision TEXT NOT NULL, PRIMARY KEY(account, calendar, generation, mutation),
                    UNIQUE(account, calendar, generation, event), FOREIGN KEY(account, calendar, generation, mutation)
                    REFERENCES captured_create_links(account, calendar, generation, mutation))""".trimIndent())
                for (current in listOf(unknown, paused, generic)) legacy.execSQL(
                    "INSERT INTO create_attempts VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any?>(scope.account.value,
                        scope.calendar.value, scope.generation, current.id.value, current.format.id, current.format.version,
                        current.protectedPayload, current.state.name, current.protectedEvidence))
                fun capturedValues(current: CapturedCreateDispatchIdentity) = ContentValues().apply {
                    put("account", scope.account.value); put("calendar", scope.calendar.value); put("generation", scope.generation)
                    put("mutation", current.attempt.id.value); put("adapter", current.attempt.format.id)
                    put("version", current.attempt.format.version); put("payload_fingerprint", current.attempt.payloadFingerprint)
                    put("local_id", current.localId.value); put("capture_id", current.captureId.value)
                    put("capture_adapter", current.captureFormat.id); put("capture_version", current.captureFormat.version)
                    put("timestamp", current.timestamp.epochSecond); put("content_fingerprint", current.contentFingerprint)
                    put("fingerprint_version", current.fingerprintVersion)
                }
                for (current in listOf(link, pausedLink)) legacy.insertOrThrow("captured_create_links", null, capturedValues(current))
                legacy.insertOrThrow("captured_create_bindings", null, capturedValues(link).apply {
                    put("event", event.eventId.value); put("verified_revision", event.revision.value)
                })
                legacy.execSQL("INSERT INTO create_pauses VALUES (?, ?, ?, ?, ?)", arrayOf<Any>(scope.account.value,
                    scope.calendar.value, scope.generation, paused.id.value, CreatePauseReason.PROCESS_RESTART.name))
                legacy.execSQL("INSERT INTO create_bindings VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>(
                    scope.account.value, scope.calendar.value, scope.generation, generic.id.value, generic.format.id,
                    generic.format.version, genericIdentity.payloadFingerprint, "legacy-event", "legacy-proof", 41, 59, "legacy-projected"))
                legacy.version = 6
            }
            assertEquals(7, store.writableDatabase.version)
            assertEquals(unknown, store.read(scope, unknown.id)); assertEquals(paused, store.read(scope, paused.id))
            assertEquals(generic, store.read(scope, generic.id)); assertEquals(binding, store.capturedBinding(link))
            assertEquals(pausedLink, store.capturedIdentity(scope, paused.id))
            assertEquals(CreatePauseReason.PROCESS_RESTART, store.pauseReason(scope, paused.id))
            assertEquals(CreatedEventBinding(genericIdentity, EventId("legacy-event"), Revision("legacy-proof")), store.binding(genericIdentity))
            assertEquals(OwnedEventWitness(41, 59, Revision("legacy-projected")), store.projectionWitness(genericIdentity))
            assertNull(store.capturedAttachment(binding))
            store.writableDatabase.rawQuery("SELECT count(*) FROM captured_create_attachments", null).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            assertEquals(CapturedProjectionHold(events = setOf(event.eventId)), store.capturedProjectionHold(scope, captureFormat))
            store.close()
            CreateAttemptStore(context, name).use { reopened ->
                assertNull(reopened.capturedAttachment(binding))
                assertEquals(unknown, reopened.read(scope, unknown.id)); assertEquals(binding, reopened.capturedBinding(link))
                val receipt = reopened.prepareCapturedAttachment(binding)
                assertEquals(receipt, reopened.capturedAttachment(binding))
                assertEquals(CapturedProjectionHold(events = setOf(event.eventId)), reopened.capturedProjectionHold(scope, captureFormat))
            }
        }
}
