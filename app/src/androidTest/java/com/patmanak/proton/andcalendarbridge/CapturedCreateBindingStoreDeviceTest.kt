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

/** Opt-in isolated SQLite fixtures (-e providerFixture true). No account, network or Provider IO.
 * Synthetic opaque strings exercise persistence, not Keystore encryption or Proton acceptance.
 */
@RunWith(AndroidJUnit4::class)
class CapturedCreateBindingStoreDeviceTest {
    private val scope = CalendarScope(AccountId("captured-proof-fixture-account"),
        CalendarId("captured-proof-fixture-calendar"), 0)
    private val content = EventContent("Synthetic captured proof fixture",
        EventTime.AllDay(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)), details = EventDetails())
    private val evidence = "synthetic-opaque-evidence-not-keystore-proof"
    private val captureFormat = AdapterFormat("synthetic-capture", 1)
    private val event = RemoteChange.Upsert(EventId("synthetic-captured-event"), Revision("synthetic-first-revision"), content)
    private fun submission(index: Int = 1, target: CalendarScope = scope) = LocalCreateSubmission(
        LocalCreateSubmissionReservation(LocalCreateIntent(LocalCreateReservation(MutationId("synthetic-capture-$index"),
            LocalEventId(UUID(0, index.toLong() * 2).toString()), target, captureFormat,
            Instant.parse("2026-01-01T00:00:00Z")), content),
            MutationId(UUID(0, index.toLong() * 2 + 1).toString()), AdapterFormat("synthetic-submission", 2)),
        "synthetic-opaque-submission-$index-not-keystore-proof")
    private fun prepared(value: LocalCreateSubmission = submission()) = CreateAttemptRecord(value.reservation.attempt,
        value.reservation.intent.reservation.scope, value.reservation.format, value.protectedPayload)
    private suspend fun admitted(store: CreateAttemptStore, value: LocalCreateSubmission = submission()):
        Pair<CreateAttemptRecord, CapturedCreateDispatchIdentity> {
        val link = store.rememberCaptured(value)
        assertTrue(store.claimCaptured(prepared(value), link))
        return prepared(value).copy(state = CreateAttemptState.OUTCOME_UNKNOWN) to link
    }
    private fun observation(link: CapturedCreateDispatchIdentity, change: RemoteChange.Upsert = event) =
        CreateSubmissionObservation.Verified(link.attempt, change)

    private fun fixture(block: suspend (Context, String, CreateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "captured-binding-fixture-${UUID.randomUUID()}.db"
        val store = CreateAttemptStore(context, name)
        try { block(context, name, store) }
        finally { store.close(); context.deleteDatabase(name) }
    }
    private fun raw(context: Context, name: String, block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE).use(block)
    }
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected captured proof rejection") }
        catch (_: IllegalStateException) { }
        catch (_: IllegalArgumentException) { }
        catch (_: SQLiteException) { }
    }

    @Test fun validPreparedAndPausedWorkNeverHoldButUnboundUnknownHoldsTheScope() = fixture { _, _, store ->
        assertEquals(CapturedProjectionHold(), store.capturedProjectionHold(scope, captureFormat))
        val first = submission()
        val link = store.rememberCaptured(first)
        assertEquals(CapturedProjectionHold(), store.capturedProjectionHold(scope, captureFormat))
        store.pausePrepared(CreatePauseReason.PROCESS_RESTART, target = scope)
        assertEquals(CapturedProjectionHold(), store.capturedProjectionHold(scope, captureFormat))
        assertFalse(store.claimCaptured(prepared(first), link))
        val second = submission(2)
        admitted(store, second)
        assertEquals(CapturedProjectionHold(scopeBlocked = true), store.capturedProjectionHold(scope, captureFormat))
        assertEquals(CapturedProjectionHold(), store.capturedProjectionHold(scope.copy(generation = 1), captureFormat))
    }

    @Test fun firstProofIsImmutableAndHoldsOnlyItsRemoteEventAcrossReopen() = fixture { context, name, store ->
        val (unknown, link) = admitted(store)
        store.retainEvidence(unknown, evidence)
        val retained = unknown.copy(protectedEvidence = evidence)
        val wanted = CapturedCreateBinding(link, event.eventId, event.revision)
        assertEquals(wanted, store.bindCaptured(retained, link, observation(link)))
        assertEquals(wanted, store.bindCaptured(retained, link, observation(link)))
        reject { store.bindCaptured(retained, link, observation(link, event.copy(revision = Revision("changed-revision")))) }
        reject { store.bindCaptured(retained, link, observation(link, event.copy(eventId = EventId("changed-event")))) }
        assertEquals(retained, store.read(scope, link.attempt.id))
        assertFalse(store.claimCaptured(prepared(), link))
        store.close()
        CreateAttemptStore(context, name).use { reopened ->
            assertEquals(wanted, reopened.capturedBinding(link))
            assertEquals(CapturedProjectionHold(events = setOf(event.eventId)), reopened.capturedProjectionHold(scope, captureFormat))
            assertEquals(retained, reopened.read(scope, link.attempt.id))
            assertNull(reopened.binding(link.attempt)); assertNull(reopened.projectionWitness(link.attempt))
        }
    }

    @Test fun retainedBindingCannotInterpretItsEventIdUnderAnotherImportFormat() = fixture { _, _, store ->
        val (unknown, link) = admitted(store)
        val proof = store.bindCaptured(unknown, link, observation(link))
        assertEquals(CapturedProjectionHold(events = setOf(event.eventId)), store.capturedProjectionHold(scope, captureFormat))
        for (foreign in listOf(captureFormat.copy(version = 2), AdapterFormat("foreign-import-adapter", 1))) {
            assertEquals(CapturedProjectionHold(scopeBlocked = true), store.capturedProjectionHold(scope, foreign))
            assertEquals(proof, store.capturedBinding(link))
            assertEquals(unknown, store.read(scope, link.attempt.id))
        }
        assertEquals(CapturedProjectionHold(events = setOf(event.eventId)), store.capturedProjectionHold(scope, captureFormat))
        assertEquals(link, store.capturedIdentity(scope, link.attempt.id))
    }

    @Test fun everyUnknownRecordLinkAndObservationDimensionMustMatchBeforeBinding() = fixture { _, _, store ->
        val (unknown, link) = admitted(store)
        for (changed in listOf(unknown.copy(state = CreateAttemptState.PREPARED),
            unknown.copy(protectedEvidence = evidence), unknown.copy(protectedPayload = "changed-synthetic-payload"),
            unknown.copy(format = unknown.format.copy(version = 3)),
            unknown.copy(scope = scope.copy(generation = 1)))) {
            reject { store.bindCaptured(changed, link, observation(link)) }
        }
        for (changed in listOf(link.copy(timestamp = link.timestamp.plusSeconds(1)),
            link.copy(captureId = MutationId("changed-capture")),
            link.copy(localId = LocalEventId(UUID(0, 50).toString())),
            link.copy(captureFormat = link.captureFormat.copy(version = 2)),
            link.copy(contentFingerprint = "a".repeat(64)),
            link.copy(attempt = link.attempt.copy(payloadFingerprint = "b".repeat(64))))) {
            reject { store.bindCaptured(unknown, changed, observation(changed)) }
            assertNull(store.capturedBinding(changed))
        }
        val foreign = link.copy(attempt = link.attempt.copy(id = MutationId("foreign-attempt")))
        reject { store.bindCaptured(unknown, link, observation(foreign)) }
        assertNull(store.capturedBinding(link))
        assertEquals(CapturedProjectionHold(scopeBlocked = true), store.capturedProjectionHold(scope, captureFormat))
        assertEquals(CapturedCreateBinding(link, event.eventId, event.revision), store.bindCaptured(unknown, link, observation(link)))
    }

    @Test fun severalCapturedAttemptsCombineScopeAndEventHoldsButGenericUnknownNeverHolds() = fixture { _, _, store ->
        val generic = prepared().copy(id = MutationId("synthetic-generic"))
        store.remember(generic); assertTrue(store.claim(generic))
        assertEquals(CapturedProjectionHold(), store.capturedProjectionHold(scope, captureFormat))
        val (first, firstLink) = admitted(store)
        store.bindCaptured(first, firstLink, observation(firstLink))
        val (second, secondLink) = admitted(store, submission(2))
        assertEquals(CapturedProjectionHold(scopeBlocked = true, events = setOf(event.eventId)), store.capturedProjectionHold(scope, captureFormat))
        val secondEvent = event.copy(eventId = EventId("second-captured-event"))
        store.bindCaptured(second, secondLink, observation(secondLink, secondEvent))
        assertEquals(CapturedProjectionHold(events = setOf(event.eventId, secondEvent.eventId)), store.capturedProjectionHold(scope, captureFormat))
    }

    @Test fun uniquenessCoversCapturedAndGenericBindingsInBothDirectionsAndIncludesScope() = fixture { _, _, store ->
        val (first, firstLink) = admitted(store)
        store.bindCaptured(first, firstLink, observation(firstLink))
        val (second, secondLink) = admitted(store, submission(2))
        reject { store.bindCaptured(second, secondLink, observation(secondLink)) }
        val generic = prepared().copy(id = MutationId("synthetic-generic"))
        store.remember(generic); assertTrue(store.claim(generic))
        val genericUnknown = generic.copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
        reject { store.bind(genericUnknown, CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(genericUnknown), event)) }
        val genericEvent = event.copy(eventId = EventId("generic-remote-event"))
        store.bind(genericUnknown, CreateSubmissionObservation.Verified(CreateAttemptIdentity.of(genericUnknown), genericEvent))
        reject { store.bindCaptured(second, secondLink, observation(secondLink, genericEvent)) }
        reject { store.bind(first, observation(firstLink)) }
        for (target in listOf(scope.copy(account = AccountId("foreign-account")),
            scope.copy(calendar = CalendarId("foreign-calendar")), scope.copy(generation = 1))) {
            val (foreign, foreignLink) = admitted(store, submission(target = target))
            assertEquals(event.eventId, store.bindCaptured(foreign, foreignLink, observation(foreignLink)).eventId)
        }
        assertNull(store.capturedBinding(secondLink))
    }

    @Test fun validLinkDriftAfterBindingInvalidatesTheStoredProofAndBlocksScope() = fixture { context, name, store ->
        val (unknown, link) = admitted(store)
        store.bindCaptured(unknown, link, observation(link))
        raw(context, name) { it.execSQL("UPDATE captured_create_links SET timestamp=timestamp+1") }
        val changed = checkNotNull(store.capturedIdentity(scope, link.attempt.id))
        assertNotEquals(link, changed)
        assertNull(store.capturedBinding(link)); assertNull(store.capturedBinding(changed))
        assertEquals(CapturedProjectionHold(scopeBlocked = true), store.capturedProjectionHold(scope, captureFormat))
        reject { store.bindCaptured(unknown, changed, observation(changed)) }
        assertEquals(unknown, store.read(scope, link.attempt.id))
    }

    @Test fun malformedBindingSnapshotOrEventNeverFreesProjectionOrAllowsReplacement() = fixture { context, name, store ->
        val (unknown, link) = admitted(store)
        store.bindCaptured(unknown, link, observation(link))
        val wanted = CapturedCreateBinding(link, event.eventId, event.revision)
        val corruptions = listOf("payload_fingerprint='${"c".repeat(64)}'", "local_id='invalid-uuid'",
            "timestamp=timestamp+1", "content_fingerprint='${"d".repeat(64)}'", "event=' '", "verified_revision=' '")
        for (corruption in corruptions) {
            raw(context, name) { it.execSQL("UPDATE captured_create_bindings SET $corruption") }
            assertNull(store.capturedBinding(link))
            assertEquals(CapturedProjectionHold(scopeBlocked = true), store.capturedProjectionHold(scope, captureFormat))
            reject { store.bindCaptured(unknown, link, observation(link)) }
            raw(context, name) { db -> db.execSQL("UPDATE captured_create_bindings SET payload_fingerprint=?, local_id=?, " +
                "timestamp=?, content_fingerprint=?, event=?, verified_revision=?", arrayOf<Any>(link.attempt.payloadFingerprint,
                link.localId.value, link.timestamp.epochSecond, link.contentFingerprint, event.eventId.value, event.revision.value)) }
            assertEquals(wanted, store.capturedBinding(link))
        }
    }

    @Test fun corruptedParentOrLinkBlocksScopeEvenWhenPreparedOrRawProofIsPresent() = fixture { context, name, store ->
        val (unknown, link) = admitted(store)
        store.bindCaptured(unknown, link, observation(link))
        raw(context, name) { it.execSQL("UPDATE create_attempts SET payload='changed-parent-payload'") }
        assertNull(store.capturedBinding(link)); assertTrue(store.capturedProjectionHold(scope, captureFormat).scopeBlocked)
        raw(context, name) { it.execSQL("UPDATE create_attempts SET payload=?, state='PREPARED'", arrayOf(unknown.protectedPayload)) }
        assertNull(store.capturedBinding(link)); assertTrue(store.capturedProjectionHold(scope, captureFormat).scopeBlocked)
        raw(context, name) { db ->
            db.execSQL("PRAGMA ignore_check_constraints=ON"); db.execSQL("UPDATE create_attempts SET state='INVALID'")
        }
        assertNull(store.capturedBinding(link)); assertTrue(store.capturedProjectionHold(scope, captureFormat).scopeBlocked)
        raw(context, name) { it.execSQL("UPDATE create_attempts SET state='OUTCOME_UNKNOWN'") }
        raw(context, name) { it.execSQL("UPDATE captured_create_links SET local_id='invalid-uuid'") }
        assertNull(store.capturedBinding(link)); assertTrue(store.capturedProjectionHold(scope, captureFormat).scopeBlocked)
    }

    @Test fun orphanedParentLinkOrProofAlwaysBlocksTheAffectedScope() = fixture { context, name, store ->
        val (unknown, link) = admitted(store)
        store.bindCaptured(unknown, link, observation(link))
        raw(context, name) { db -> db.execSQL("PRAGMA foreign_keys=OFF"); db.execSQL("DELETE FROM create_attempts") }
        assertNull(store.capturedBinding(link)); assertTrue(store.capturedProjectionHold(scope, captureFormat).scopeBlocked)
        raw(context, name) { db -> db.execSQL("PRAGMA foreign_keys=OFF"); db.execSQL("DELETE FROM captured_create_links") }
        assertNull(store.capturedBinding(link)); assertTrue(store.capturedProjectionHold(scope, captureFormat).scopeBlocked)
        assertEquals(CapturedProjectionHold(), store.capturedProjectionHold(scope.copy(generation = 1), captureFormat))
    }

    @Test fun orphanedCapturedProofStillRefusesEveryGenericPathOrNewCapturedAdoption() = fixture { context, name, store ->
        val (unknown, link) = admitted(store)
        store.bindCaptured(unknown, link, observation(link))
        raw(context, name) { db -> db.execSQL("PRAGMA foreign_keys=OFF"); db.execSQL("DELETE FROM captured_create_links") }
        assertTrue(store.isCaptured(scope, link.attempt.id))
        assertNull(store.capturedIdentity(scope, link.attempt.id))
        assertNull(store.capturedBinding(link))
        assertTrue(store.capturedProjectionHold(scope, captureFormat).scopeBlocked)
        reject { store.remember(prepared()) }
        reject { store.bind(unknown, observation(link)) }
        assertNull(store.binding(link.attempt))
        raw(context, name) { it.execSQL("UPDATE create_attempts SET state='PREPARED'") }
        assertFalse(store.claim(prepared()))
        assertEquals(prepared(), store.read(scope, link.attempt.id))
        reject { store.rememberCaptured(submission()) }
        raw(context, name) { db -> db.execSQL("PRAGMA foreign_keys=OFF"); db.execSQL("DELETE FROM create_attempts") }
        assertTrue(store.isCaptured(scope, link.attempt.id))
        reject { store.remember(prepared()) }
        reject { store.rememberCaptured(submission()) }
        assertNull(store.read(scope, link.attempt.id))
        assertTrue(store.capturedProjectionHold(scope, captureFormat).scopeBlocked)
    }

    @Test fun unreadableBindingStorageThrowsRatherThanReturningAnEmptyHold() = fixture { context, name, store ->
        admitted(store)
        raw(context, name) { it.execSQL("DROP TABLE captured_create_bindings") }
        try { store.capturedProjectionHold(scope, captureFormat); fail("Unreadable hold cannot permit projection") }
        catch (_: SQLiteException) { }
    }

    @Test fun bindingForeignKeyAndSynchronousFullRemainEnabled() = fixture { _, _, store ->
        val (unknown, link) = admitted(store)
        store.bindCaptured(unknown, link, observation(link))
        val db = store.writableDatabase
        db.rawQuery("PRAGMA foreign_keys", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        db.rawQuery("PRAGMA synchronous", null).use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
        try { db.execSQL("DELETE FROM captured_create_links"); fail("Bound local link must be retained") }
        catch (_: SQLiteException) { }
        assertEquals(CapturedCreateBinding(link, event.eventId, event.revision), store.capturedBinding(link))
    }

    @Test fun failedInsertOrRevokedLeaseRollsBackProofAndKeepsTheScopeBlocked() = fixture { context, name, store ->
        val (unknown, link) = admitted(store)
        raw(context, name) { db -> db.execSQL("""CREATE TRIGGER reject_captured_binding BEFORE INSERT ON captured_create_bindings
            BEGIN SELECT RAISE(ABORT, 'synthetic proof rejection'); END""".trimIndent()) }
        reject { store.bindCaptured(unknown, link, observation(link)) }
        assertNull(store.capturedBinding(link)); assertTrue(store.capturedProjectionHold(scope, captureFormat).scopeBlocked)
        raw(context, name) { it.execSQL("DROP TRIGGER reject_captured_binding") }
        var checks = 0
        store.admitAssociation = { ++checks == 1 }
        reject { store.bindCaptured(unknown, link, observation(link)) }
        assertNull(store.capturedBinding(link)); assertTrue(store.capturedProjectionHold(scope, captureFormat).scopeBlocked)
        assertEquals(unknown, store.read(scope, link.attempt.id))
        store.admitAssociation = { true }
        assertEquals(event.eventId, store.bindCaptured(unknown, link, observation(link)).eventId)
    }

    @Test fun cancellationBeforeProofCommitLeavesUnknownUnboundAcrossReopen() = fixture { context, name, store ->
        val (unknown, link) = admitted(store)
        coroutineScope {
            val job = async {
                val caller = currentCoroutineContext().job
                var checks = 0
                store.admitAssociation = { if (++checks == 2) caller.cancel(); true }
                store.bindCaptured(unknown, link, observation(link))
            }
            try { job.await(); fail("Cancelled proof cannot commit") }
            catch (_: CancellationException) { }
        }
        store.close()
        CreateAttemptStore(context, name).use { reopened ->
            assertNull(reopened.capturedBinding(link))
            assertEquals(unknown, reopened.read(scope, link.attempt.id))
            assertTrue(reopened.capturedProjectionHold(scope, captureFormat).scopeBlocked)
            assertEquals(event.eventId, reopened.bindCaptured(unknown, link, observation(link)).eventId)
        }
    }

    @Test fun competingHelpersRetainOneProofAndArbitrateCrossTableEventUniqueness() = fixture { context, name, store ->
        CreateAttemptStore(context, name).use { other ->
            val (unknown, link) = admitted(store)
            other.writableDatabase
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val first = async(Dispatchers.IO) { start.await(); store.bindCaptured(unknown, link, observation(link)) }
                val second = async(Dispatchers.IO) { start.await(); other.bindCaptured(unknown, link, observation(link)) }
                start.complete(Unit); assertEquals(first.await(), second.await())
            }
            val (next, nextLink) = admitted(store, submission(2))
            val generic = prepared().copy(id = MutationId("synthetic-competing-generic"))
            store.remember(generic); assertTrue(store.claim(generic))
            val genericUnknown = generic.copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
            val competingEvent = event.copy(eventId = EventId("synthetic-competing-event"))
            coroutineScope {
                val start = CompletableDeferred<Unit>()
                val first = async(Dispatchers.IO) {
                    start.await()
                    try { store.bindCaptured(next, nextLink, observation(nextLink, competingEvent)); true }
                    catch (_: IllegalStateException) { false }
                    catch (_: SQLiteException) { false }
                }
                val second = async(Dispatchers.IO) {
                    start.await()
                    try { other.bind(genericUnknown, CreateSubmissionObservation.Verified(
                        CreateAttemptIdentity.of(genericUnknown), competingEvent)); true }
                    catch (_: IllegalStateException) { false }
                    catch (_: SQLiteException) { false }
                }
                start.complete(Unit); assertEquals(1, listOf(first.await(), second.await()).count { it })
            }
            val capturedProof = store.capturedBinding(nextLink)
            val genericProof = other.binding(CreateAttemptIdentity.of(genericUnknown))
            assertEquals(1, listOf(capturedProof, genericProof).count { it != null })
            assertEquals(next, other.read(scope, next.id)); assertEquals(genericUnknown, store.read(scope, genericUnknown.id))
        }
    }

    @Test fun independentlyConstructedVersionFiveMigrationPreservesAllPriorRowsAndInventsNoProof() =
        fixture { context, name, store ->
            val captured = prepared().copy(state = CreateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = evidence)
            val link = CapturedCreateDispatchIdentity.of(submission())
            val paused = prepared(submission(2))
            val pausedLink = CapturedCreateDispatchIdentity.of(submission(2))
            val generic = captured.copy(id = MutationId("synthetic-legacy-generic"))
            val genericIdentity = CreateAttemptIdentity.of(generic)
            val path = context.getDatabasePath(name)
            path.parentFile?.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
                legacy.execSQL("""CREATE TABLE create_attempts (
                    account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL, mutation TEXT NOT NULL,
                    adapter TEXT NOT NULL, version INTEGER NOT NULL, payload TEXT NOT NULL, state TEXT NOT NULL,
                    evidence TEXT, PRIMARY KEY(account, calendar, generation, mutation))""".trimIndent())
                legacy.execSQL("""CREATE TABLE create_pauses (
                    account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL, mutation TEXT NOT NULL,
                    reason TEXT NOT NULL, PRIMARY KEY(account, calendar, generation, mutation))""".trimIndent())
                legacy.execSQL("""CREATE TABLE create_bindings (
                    account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL, mutation TEXT NOT NULL,
                    adapter TEXT NOT NULL, version INTEGER NOT NULL, fingerprint TEXT NOT NULL, event TEXT NOT NULL,
                    verified_revision TEXT NOT NULL, provider_calendar INTEGER, provider_event INTEGER,
                    provider_revision TEXT, PRIMARY KEY(account, calendar, generation, mutation),
                    UNIQUE(account, calendar, generation, event))""".trimIndent())
                legacy.execSQL("""CREATE TABLE captured_create_links (
                    account TEXT NOT NULL, calendar TEXT NOT NULL, generation INTEGER NOT NULL, mutation TEXT NOT NULL,
                    adapter TEXT NOT NULL, version INTEGER NOT NULL, payload_fingerprint TEXT NOT NULL,
                    local_id TEXT NOT NULL, capture_id TEXT NOT NULL, capture_adapter TEXT NOT NULL,
                    capture_version INTEGER NOT NULL, timestamp INTEGER NOT NULL, content_fingerprint TEXT NOT NULL,
                    fingerprint_version INTEGER NOT NULL, PRIMARY KEY(account, calendar, generation, mutation),
                    UNIQUE(account, calendar, generation, local_id), UNIQUE(account, calendar, generation, capture_id),
                    FOREIGN KEY(account, calendar, generation, mutation)
                        REFERENCES create_attempts(account, calendar, generation, mutation))""".trimIndent())
                for (current in listOf(captured, paused, generic)) legacy.execSQL(
                    "INSERT INTO create_attempts VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any?>(scope.account.value,
                        scope.calendar.value, scope.generation, current.id.value, current.format.id, current.format.version,
                        current.protectedPayload, current.state.name, current.protectedEvidence))
                for (current in listOf(link, pausedLink)) legacy.insertOrThrow("captured_create_links", null, ContentValues().apply {
                    put("account", scope.account.value); put("calendar", scope.calendar.value); put("generation", scope.generation)
                    put("mutation", current.attempt.id.value); put("adapter", current.attempt.format.id)
                    put("version", current.attempt.format.version); put("payload_fingerprint", current.attempt.payloadFingerprint)
                    put("local_id", current.localId.value); put("capture_id", current.captureId.value)
                    put("capture_adapter", current.captureFormat.id); put("capture_version", current.captureFormat.version)
                    put("timestamp", current.timestamp.epochSecond); put("content_fingerprint", current.contentFingerprint)
                    put("fingerprint_version", current.fingerprintVersion)
                })
                legacy.execSQL("INSERT INTO create_pauses VALUES (?, ?, ?, ?, ?)", arrayOf<Any>(scope.account.value,
                    scope.calendar.value, scope.generation, paused.id.value, CreatePauseReason.PROCESS_RESTART.name))
                legacy.execSQL("INSERT INTO create_bindings VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>(
                    scope.account.value, scope.calendar.value, scope.generation, generic.id.value, generic.format.id,
                    generic.format.version, genericIdentity.payloadFingerprint, "legacy-event", "legacy-proof", 41, 59, "legacy-projected"))
                legacy.version = 5
            }
            assertEquals(captured, store.read(scope, captured.id)); assertEquals(paused, store.read(scope, paused.id))
            assertEquals(generic, store.read(scope, generic.id)); assertEquals(6, store.writableDatabase.version)
            assertEquals(link, store.capturedIdentity(scope, captured.id)); assertEquals(pausedLink, store.capturedIdentity(scope, paused.id))
            assertNull(store.capturedBinding(link)); assertNull(store.capturedBinding(pausedLink))
            assertEquals(CreatePauseReason.PROCESS_RESTART, store.pauseReason(scope, paused.id))
            assertEquals(CreatedEventBinding(genericIdentity, EventId("legacy-event"), Revision("legacy-proof")), store.binding(genericIdentity))
            assertEquals(OwnedEventWitness(41, 59, Revision("legacy-projected")), store.projectionWitness(genericIdentity))
            assertEquals(CapturedProjectionHold(scopeBlocked = true), store.capturedProjectionHold(scope, captureFormat))
            assertFalse(store.claimCaptured(prepared(), link)); assertFalse(store.claimCaptured(paused, pausedLink))
            store.close()
            CreateAttemptStore(context, name).use { reopened ->
                assertEquals(captured, reopened.read(scope, captured.id)); assertEquals(paused, reopened.read(scope, paused.id))
                assertNull(reopened.capturedBinding(link)); assertTrue(reopened.capturedProjectionHold(scope, captureFormat).scopeBlocked)
                assertEquals(event.eventId, reopened.bindCaptured(captured, link, observation(link)).eventId)
                assertEquals(CapturedProjectionHold(events = setOf(event.eventId)), reopened.capturedProjectionHold(scope, captureFormat))
            }
        }
}
