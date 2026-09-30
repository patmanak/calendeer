// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

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

/** Opt-in isolated SQLite fixtures; no account, network, or Calendar Provider access.
 * Payload strings exercise opacity, not encryption or Proton compatibility.
 */
@RunWith(AndroidJUnit4::class)
class CapturedCreateAttemptStoreDeviceTest {
    private val scope = CalendarScope(AccountId("captured-dispatch-fixture-account"),
        CalendarId("captured-dispatch-fixture-calendar"), 0)
    private val content = EventContent("Synthetic captured dispatch fixture",
        EventTime.AllDay(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)), details = EventDetails())
    private val submission = LocalCreateSubmission(LocalCreateSubmissionReservation(
        LocalCreateIntent(LocalCreateReservation(MutationId("synthetic-capture"),
            LocalEventId("00000000-0000-0000-0000-000000000001"), scope,
            AdapterFormat("synthetic-capture", 1), Instant.parse("2026-01-01T00:00:00Z")), content),
        MutationId("00000000-0000-0000-0000-000000000002"), AdapterFormat("synthetic-submission", 2)),
        "synthetic-opaque-submission-not-keystore-proof")
    private val evidence = "synthetic-opaque-evidence-not-keystore-proof"
    private fun record(value: LocalCreateSubmission = submission): CreateAttemptRecord =
        CreateAttemptRecord(value.reservation.attempt, value.reservation.intent.reservation.scope,
            value.reservation.format, value.protectedPayload)

    private fun changedCapture(value: LocalCreateSubmission = submission,
        transform: (LocalCreateReservation) -> LocalCreateReservation): LocalCreateSubmission =
        value.copy(reservation = value.reservation.copy(intent = value.reservation.intent.copy(
            reservation = transform(value.reservation.intent.reservation))))

    private fun fixture(block: suspend (Context, String, CreateAttemptStore) -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "captured-attempt-fixture-${UUID.randomUUID()}.db"
        val store = CreateAttemptStore(context, name)
        try { block(context, name, store) }
        finally { store.close(); context.deleteDatabase(name) }
    }

    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected immutable captured work rejection") }
        catch (_: IllegalStateException) { }
        catch (_: SQLiteException) { }
    }

    private fun raw(context: Context, name: String, block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath, null,
            SQLiteDatabase.OPEN_READWRITE).use(block)
    }

    @Test fun linkAndPreparedParentAreImmutableAndSurviveReopen() = fixture { context, name, store ->
        val wanted = CapturedCreateDispatchIdentity.of(submission)
        assertEquals(wanted, store.rememberCaptured(submission))
        assertEquals(wanted, store.rememberCaptured(submission))
        assertEquals(record(), store.read(scope, wanted.attempt.id))
        store.close()
        CreateAttemptStore(context, name).use { reopened ->
            assertEquals(wanted, reopened.capturedIdentity(scope, wanted.attempt.id))
            assertEquals(wanted, reopened.rememberCaptured(submission))
            assertTrue(reopened.claimCaptured(record(), wanted))
            assertEquals(record().copy(state = CreateAttemptState.OUTCOME_UNKNOWN), reopened.read(scope, wanted.attempt.id))
            assertFalse(reopened.claimCaptured(record(), wanted))
        }
    }

    @Test fun genericPathsNeverRememberClaimOrAssociateCapturedWork() = fixture { _, _, store ->
        val link = store.rememberCaptured(submission)
        reject { store.remember(record()) }
        assertFalse(store.claim(record()))
        assertFalse(CreateAttemptAdmission(store).dispatch(record()) { fail("Generic admission cannot send captured work") })
        assertTrue(store.claimCaptured(record(), link))
        val unknown = record().copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
        val observation = CreateSubmissionObservation.Verified(link.attempt,
            RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-revision"), content))
        reject { store.bind(unknown, observation) }
        assertNull(store.binding(link.attempt))
        assertNull(store.projectionWitness(link.attempt))
        reject { store.remember(record()) }
        assertFalse(store.claim(record()))
    }

    @Test fun existingGenericPreparedOrUnknownCannotBeAdopted() = fixture { _, _, store ->
        store.remember(record())
        reject { store.rememberCaptured(submission) }
        assertNull(store.capturedIdentity(scope, record().id))
        assertEquals(record(), store.read(scope, record().id))
        assertTrue(store.claim(record()))
        store.retainEvidence(record().copy(state = CreateAttemptState.OUTCOME_UNKNOWN), evidence)
        reject { store.rememberCaptured(submission) }
        assertNull(store.capturedIdentity(scope, record().id))
        assertEquals(record().copy(state = CreateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = evidence),
            store.read(scope, record().id))
    }

    @Test fun identicalLinkPreservesUnknownEvidenceAndPermanentPauseAcrossHelpers() = fixture { context, name, store ->
        val paused = submission.copy(reservation = submission.reservation.copy(
            attempt = MutationId("00000000-0000-0000-0000-000000000004"),
            intent = submission.reservation.intent.copy(reservation = submission.reservation.intent.reservation.copy(
                localId = LocalEventId("00000000-0000-0000-0000-000000000003"), id = MutationId("second-capture")))))
        val link = store.rememberCaptured(submission)
        assertTrue(store.claimCaptured(record(), link))
        val unknown = record().copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
        store.retainEvidence(unknown, evidence)
        val pausedLink = store.rememberCaptured(paused)
        store.pausePrepared(CreatePauseReason.CALENDAR_DISABLED, target = scope)
        store.pausePrepared(CreatePauseReason.PERMISSION_LOST, target = scope)
        store.close()
        CreateAttemptStore(context, name).use { reopened ->
            assertEquals(link, reopened.rememberCaptured(submission))
            assertEquals(unknown.copy(protectedEvidence = evidence), reopened.read(scope, link.attempt.id))
            assertNull(reopened.pauseReason(scope, link.attempt.id))
            assertFalse(reopened.claimCaptured(record(), link))
            assertEquals(pausedLink, reopened.rememberCaptured(paused))
            assertEquals(CreatePauseReason.CALENDAR_DISABLED, reopened.pauseReason(scope, pausedLink.attempt.id))
            assertFalse(reopened.claimCaptured(record(paused), pausedLink))
        }
    }

    @Test fun everyRetainedIdentityDimensionIsComparedBeforeAdmission() = fixture { _, _, store ->
        val link = store.rememberCaptured(submission)
        val changes = listOf(
            link.copy(localId = LocalEventId("00000000-0000-0000-0000-000000000003")),
            link.copy(captureId = MutationId("other-capture")),
            link.copy(captureFormat = link.captureFormat.copy(version = 2)),
            link.copy(captureFormat = AdapterFormat("other-capture-format", 1)),
            link.copy(timestamp = link.timestamp.plusSeconds(1)),
            link.copy(contentFingerprint = "a".repeat(64)),
            link.copy(attempt = link.attempt.copy(payloadFingerprint = "b".repeat(64))),
            link.copy(attempt = link.attempt.copy(format = link.attempt.format.copy(version = 3))),
            link.copy(attempt = link.attempt.copy(id = MutationId("another-attempt"))),
            link.copy(attempt = link.attempt.copy(scope = scope.copy(generation = 1))))
        for (changed in changes) assertFalse(store.claimCaptured(record(), changed))
        for (changed in listOf(record().copy(protectedPayload = "changed-synthetic-payload"),
            record().copy(protectedEvidence = evidence), record().copy(state = CreateAttemptState.OUTCOME_UNKNOWN),
            record().copy(scope = scope.copy(account = AccountId("foreign-account"))),
            record().copy(scope = scope.copy(calendar = CalendarId("foreign-calendar"))))) {
            assertFalse(store.claimCaptured(changed, link))
        }
        assertEquals(record(), store.read(scope, record().id))
        assertTrue(store.claimCaptured(record(), link))
    }

    @Test fun changedSubmissionCannotReplaceAnySnapshotField() = fixture { _, _, store ->
        val link = store.rememberCaptured(submission)
        val changes = listOf(
            submission.copy(protectedPayload = "other-synthetic-payload"),
            submission.copy(reservation = submission.reservation.copy(format = AdapterFormat("other-submission", 2))),
            submission.copy(reservation = submission.reservation.copy(intent = submission.reservation.intent.copy(
                content = content.copy(title = "Changed synthetic title")))),
            changedCapture { it.copy(timestamp = it.timestamp.plusSeconds(1)) },
            changedCapture { it.copy(format = it.format.copy(version = 2)) },
            changedCapture { it.copy(id = MutationId("other-capture")) },
            changedCapture { it.copy(localId = LocalEventId("00000000-0000-0000-0000-000000000003")) })
        for (changed in changes) {
            reject { store.rememberCaptured(changed) }
            assertEquals(link, store.capturedIdentity(scope, link.attempt.id))
            assertEquals(record(), store.read(scope, link.attempt.id))
        }
    }

    @Test fun localCaptureAndAttemptCollisionsAreScopedAndRollBackParents() = fixture { _, _, store ->
        store.rememberCaptured(submission)
        val newAttempt = submission.copy(reservation = submission.reservation.copy(
            attempt = MutationId("00000000-0000-0000-0000-000000000004")))
        val sameLocal = changedCapture(newAttempt) { it.copy(id = MutationId("other-capture")) }
        val sameCapture = changedCapture(newAttempt) {
            it.copy(localId = LocalEventId("00000000-0000-0000-0000-000000000003")) }
        for (collision in listOf(newAttempt, sameLocal, sameCapture)) {
            reject { store.rememberCaptured(collision) }
            assertNull(store.read(scope, collision.reservation.attempt))
        }
        for (foreign in listOf(scope.copy(account = AccountId("foreign-account")),
            scope.copy(calendar = CalendarId("foreign-calendar")), scope.copy(generation = 1))) {
            val scoped = changedCapture { it.copy(scope = foreign) }
            val link = store.rememberCaptured(scoped)
            assertEquals(link, store.capturedIdentity(foreign, record().id))
            assertTrue(store.claimCaptured(record(scoped), link))
        }
        assertEquals(record(), store.read(scope, record().id))
    }

    @Test fun malformedLinkOrChangedParentCannotAuthorizeEitherAdmissionPath() = fixture { context, name, store ->
        val link = store.rememberCaptured(submission)
        val corruptions = listOf("payload_fingerprint='${"c".repeat(64)}'", "adapter='wrong-format'",
            "version=3", "local_id='not-a-uuid'", "capture_adapter=' '", "content_fingerprint='invalid'",
            "timestamp=9223372036854775807")
        for (corruption in corruptions) {
            raw(context, name) { db -> db.execSQL("UPDATE captured_create_links SET $corruption") }
            assertTrue(store.isCaptured(scope, record().id))
            assertNull(store.capturedIdentity(scope, record().id))
            assertFalse(store.claimCaptured(record(), link))
            assertFalse(store.claim(record()))
            reject { store.remember(record()) }
            raw(context, name) { db ->
                db.execSQL("UPDATE captured_create_links SET adapter=?, version=?, payload_fingerprint=?, local_id=?, " +
                    "capture_adapter=?, content_fingerprint=?, timestamp=?", arrayOf<Any>(link.attempt.format.id,
                    link.attempt.format.version, link.attempt.payloadFingerprint, link.localId.value,
                    link.captureFormat.id, link.contentFingerprint, link.timestamp.epochSecond))
            }
        }
        raw(context, name) { it.execSQL("UPDATE create_attempts SET payload='changed-parent-payload'") }
        assertNull(store.capturedIdentity(scope, record().id))
        assertFalse(store.claimCaptured(record(), link)); assertFalse(store.claim(record()))
        reject { store.rememberCaptured(submission) }
        raw(context, name) { it.execSQL("UPDATE create_attempts SET payload=?, evidence=?",
            arrayOf(submission.protectedPayload, evidence)) }
        assertNull(store.capturedIdentity(scope, record().id))
        assertFalse(store.claimCaptured(record(), link)); assertFalse(store.claim(record()))
    }

    @Test fun orphanedRawLinkStillBlocksGenericWorkAndCannotBeReadmitted() = fixture { context, name, store ->
        val link = store.rememberCaptured(submission)
        // Deliberate out-of-band corruption with foreign keys disabled on this raw connection.
        raw(context, name) { db -> db.execSQL("PRAGMA foreign_keys=OFF"); db.execSQL("DELETE FROM create_attempts") }
        assertTrue(store.isCaptured(scope, record().id))
        assertNull(store.capturedIdentity(scope, record().id))
        assertNull(store.read(scope, record().id))
        assertFalse(store.claim(record())); assertFalse(store.claimCaptured(record(), link))
        reject { store.remember(record()) }; reject { store.rememberCaptured(submission) }
    }

    @Test fun foreignKeyAndFullSynchronousAreEnabledOnTheHelperConnection() = fixture { _, _, store ->
        store.rememberCaptured(submission)
        val db = store.writableDatabase
        db.rawQuery("PRAGMA foreign_keys", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        db.rawQuery("PRAGMA synchronous", null).use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
        try { db.execSQL("DELETE FROM create_attempts"); fail("Captured parent must be retained") }
        catch (_: SQLiteException) { }
        assertEquals(record(), store.read(scope, record().id))
    }

    @Test fun concurrentHelpersRememberOneLinkAndAdmitExactlyOnce() = fixture { context, name, store ->
        CreateAttemptStore(context, name).use { other ->
            store.writableDatabase; other.writableDatabase
            repeat(6) { index ->
                val current = changedCapture { it.copy(id = MutationId("capture-$index"),
                    localId = LocalEventId(UUID.randomUUID().toString())) }.let {
                    it.copy(reservation = it.reservation.copy(attempt = MutationId(UUID.randomUUID().toString()))) }
                coroutineScope {
                    val start = CompletableDeferred<Unit>()
                    val first = async(Dispatchers.IO) { start.await(); store.rememberCaptured(current) }
                    val second = async(Dispatchers.IO) { start.await(); other.rememberCaptured(current) }
                    start.complete(Unit)
                    assertEquals(first.await(), second.await())
                }
                val link = CapturedCreateDispatchIdentity.of(current)
                coroutineScope {
                    val start = CompletableDeferred<Unit>()
                    val first = async(Dispatchers.IO) { start.await(); store.claimCaptured(record(current), link) }
                    val second = async(Dispatchers.IO) { start.await(); other.claimCaptured(record(current), link) }
                    start.complete(Unit)
                    assertEquals(1, listOf(first.await(), second.await()).count { it })
                }
                assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, other.read(scope, link.attempt.id)?.state)
                assertFalse(store.claimCaptured(record(current), link))
            }
        }
    }

    @Test fun failedLinkInsertOrClaimRollsBackWithoutGrantingAdmission() = fixture { context, name, store ->
        store.writableDatabase
        raw(context, name) { db -> db.execSQL("""CREATE TRIGGER reject_link BEFORE INSERT ON captured_create_links
            BEGIN SELECT RAISE(ABORT, 'synthetic link rejection'); END""".trimIndent()) }
        reject { store.rememberCaptured(submission) }
        assertNull(store.read(scope, record().id)); assertNull(store.capturedIdentity(scope, record().id))
        raw(context, name) { it.execSQL("DROP TRIGGER reject_link") }
        val link = store.rememberCaptured(submission)
        raw(context, name) { db -> db.execSQL("""CREATE TRIGGER reject_claim BEFORE UPDATE ON create_attempts
            BEGIN SELECT RAISE(ABORT, 'synthetic claim rejection'); END""".trimIndent()) }
        reject { store.claimCaptured(record(), link) }
        assertEquals(record(), store.read(scope, record().id)); assertEquals(link, store.capturedIdentity(scope, record().id))
        raw(context, name) { it.execSQL("DROP TRIGGER reject_claim") }
        assertTrue(store.claimCaptured(record(), link))
    }

    @Test fun failedPauseCanBeRetriedAndNeverResumesCapturedWork() = fixture { context, name, store ->
        val link = store.rememberCaptured(submission)
        raw(context, name) { db -> db.execSQL("""CREATE TRIGGER reject_pause BEFORE INSERT ON create_pauses
            BEGIN SELECT RAISE(ABORT, 'synthetic pause rejection'); END""".trimIndent()) }
        reject { store.pausePrepared(CreatePauseReason.PERMISSION_LOST, target = scope) }
        assertEquals(record(), store.read(scope, record().id))
        store.admitDispatch = { false }
        assertFalse(store.claimCaptured(record(), link))
        raw(context, name) { it.execSQL("DROP TRIGGER reject_pause") }
        store.pausePrepared(CreatePauseReason.PERMISSION_LOST, target = scope)
        store.admitDispatch = { true }
        assertEquals(link, store.rememberCaptured(submission))
        assertEquals(CreatePauseReason.PERMISSION_LOST, store.pauseReason(scope, record().id))
        assertFalse(store.claimCaptured(record(), link))
    }

    @Test fun revokedLeaseRollsBackBothRememberAndClaim() = fixture { _, _, store ->
        var checks = 0
        store.admitDispatch = { ++checks == 1 }
        reject { store.rememberCaptured(submission) }
        assertNull(store.read(scope, record().id)); assertNull(store.capturedIdentity(scope, record().id))
        store.admitDispatch = { true }
        val link = store.rememberCaptured(submission)
        checks = 0; store.admitDispatch = { ++checks == 1 }
        reject { store.claimCaptured(record(), link) }
        assertEquals(record(), store.read(scope, record().id))
        store.admitDispatch = { true }; assertTrue(store.claimCaptured(record(), link))
    }

    @Test fun cancellationBeforeCommitRollsBackLinksAndAdmission() = fixture { context, name, store ->
        coroutineScope {
            val job = async {
                val caller = currentCoroutineContext().job
                var checks = 0
                store.admitDispatch = { if (++checks == 2) caller.cancel(); true }
                store.rememberCaptured(submission)
            }
            try { job.await(); fail("Cancellation must refuse remember commit") }
            catch (_: CancellationException) { }
        }
        store.admitDispatch = { true }
        assertNull(store.read(scope, record().id)); assertNull(store.capturedIdentity(scope, record().id))
        val link = store.rememberCaptured(submission)
        coroutineScope {
            val job = async {
                val caller = currentCoroutineContext().job
                var checks = 0
                store.admitDispatch = { if (++checks == 2) caller.cancel(); true }
                store.claimCaptured(record(), link)
            }
            try { job.await(); fail("Cancellation must refuse UNKNOWN commit") }
            catch (_: CancellationException) { }
        }
        store.close()
        CreateAttemptStore(context, name).use { reopened ->
            assertEquals(record(), reopened.read(scope, record().id))
            assertEquals(link, reopened.capturedIdentity(scope, record().id))
            assertTrue(reopened.claimCaptured(record(), link))
        }
    }

    @Test fun independentlyConstructedVersionFourMigrationPreservesHistoryWithoutFabricatingLinks() =
        fixture { context, name, store ->
            val prepared = record()
            val unknown = prepared.copy(id = MutationId("legacy-unknown"), state = CreateAttemptState.OUTCOME_UNKNOWN,
                protectedEvidence = evidence)
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
                for (current in listOf(prepared, unknown)) legacy.execSQL(
                    "INSERT INTO create_attempts VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any?>(
                        scope.account.value, scope.calendar.value, scope.generation, current.id.value,
                        current.format.id, current.format.version, current.protectedPayload, current.state.name,
                        current.protectedEvidence))
                legacy.execSQL("INSERT INTO create_pauses VALUES (?, ?, ?, ?, ?)", arrayOf<Any>(scope.account.value,
                    scope.calendar.value, scope.generation, prepared.id.value, CreatePauseReason.PROCESS_RESTART.name))
                legacy.execSQL("INSERT INTO create_bindings VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any>(scope.account.value, scope.calendar.value, scope.generation, unknown.id.value,
                        unknown.format.id, unknown.format.version, CreateAttemptIdentity.of(unknown).payloadFingerprint,
                        "legacy-event", "legacy-verified", 41, 59, "legacy-projected"))
                legacy.version = 4
            }
            assertEquals(prepared, store.read(scope, prepared.id)); assertEquals(unknown, store.read(scope, unknown.id))
            assertEquals(5, store.writableDatabase.version)
            assertNull(store.capturedIdentity(scope, prepared.id)); assertNull(store.capturedIdentity(scope, unknown.id))
            val oldIdentity = CreateAttemptIdentity.of(unknown)
            assertEquals(CreatedEventBinding(oldIdentity, EventId("legacy-event"), Revision("legacy-verified")),
                store.binding(oldIdentity))
            assertEquals(OwnedEventWitness(41, 59, Revision("legacy-projected")), store.projectionWitness(oldIdentity))
            assertEquals(CreatePauseReason.PROCESS_RESTART, store.pauseReason(scope, prepared.id))
            assertFalse(store.claim(prepared)); assertFalse(store.claim(unknown.copy(state = CreateAttemptState.PREPARED,
                protectedEvidence = null)))
            reject { store.rememberCaptured(submission) }
            store.close()
            CreateAttemptStore(context, name).use { reopened ->
                assertEquals(unknown, reopened.read(scope, unknown.id))
                assertEquals(CreatePauseReason.PROCESS_RESTART, reopened.pauseReason(scope, prepared.id))
                assertNull(reopened.capturedIdentity(scope, prepared.id))
            }
        }
}
