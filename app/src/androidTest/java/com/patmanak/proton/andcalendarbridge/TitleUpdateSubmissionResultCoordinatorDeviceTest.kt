// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.Account
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.data.outgoing.TitleUpdateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated SQL and controlled transport/native observations. This slice never acknowledges
 * Android; synthetic Verified is not Proton compatibility evidence.
 */
@RunWith(AndroidJUnit4::class)
class TitleUpdateSubmissionResultCoordinatorDeviceTest {
    private class Results(private val source: TitleUpdateAttemptStore) : TitleUpdateResultJournal {
        var retains = 0
        var reads = 0
        var retainedAdmission: ((CalendarScope) -> Boolean)? = null
        var returnFalse = false
        var beforeRetain: suspend () -> Unit = {}
        var duringCommit: () -> Unit = {}
        var afterRetain: suspend () -> Unit = {}
        var afterRead: suspend (TitleUpdateVerifiedResult?) -> TitleUpdateVerifiedResult? = { it }
        override suspend fun retainVerified(result: TitleUpdateVerifiedResult): Unit = error("No unrestricted result retention")
        override suspend fun retainVerified(result: TitleUpdateVerifiedResult, admit: (CalendarScope) -> Boolean): Boolean {
            retainedAdmission = admit
            retains++; beforeRetain()
            if (returnFalse) return false
            var checks = 0
            val retained = source.retainVerified(result) { scope ->
                assertEquals(result.record.scope, scope)
                if (++checks == 2) {
                    assertTrue(source.writableDatabase.inTransaction())
                    source.writableDatabase.rawQuery("SELECT result FROM title_update_attempts WHERE mutation=?",
                        arrayOf(result.record.id.value)).use { assertTrue(it.moveToFirst()); assertFalse(it.isNull(0)) }
                    duringCommit()
                }
                admit(scope)
            }
            afterRetain(); return retained
        }
        override suspend fun readVerified(scope: CalendarScope, id: MutationId): TitleUpdateVerifiedResult? {
            reads++; return afterRead(source.readVerified(scope, id))
        }
    }
    private class UnsupportedResults(private val source: TitleUpdateAttemptStore) : TitleUpdateResultJournal {
        var legacyCalls = 0
        override suspend fun retainVerified(result: TitleUpdateVerifiedResult): Unit {
            legacyCalls++; error("No fallback to unrestricted result retention")
        }
        override suspend fun readVerified(scope: CalendarScope, id: MutationId) = source.readVerified(scope, id)
    }
    private class Journal(private val source: TitleUpdateAttemptStore) : TitleUpdateAttemptJournal {
        var claims = 0
        var afterRead: suspend (TitleUpdateAttemptRecord?) -> TitleUpdateAttemptRecord? = { it }
        override suspend fun remember(record: TitleUpdateAttemptRecord): Unit = error("No preparation retention")
        override suspend fun remember(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean = error("No preparation retention")
        override suspend fun read(scope: CalendarScope, id: MutationId) = afterRead(source.read(scope, id))
        override suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean = error("No legacy claim")
        override suspend fun claim(expected: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            claims++; return source.claim(expected, admit)
        }
        override suspend fun retainEvidence(expectedUnknown: TitleUpdateAttemptRecord, protectedEvidence: String) =
            source.retainEvidence(expectedUnknown, protectedEvidence)
    }
    private open class UnsupportedPort(override val titleUpdateFormat: AdapterFormat?) : CalendarTitleUpdateSubmissionPort {
        var legacySubmissions = 0
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord = error("No preparation")
        override suspend fun prepareSubmission(reservation: TitleUpdatePreparationReservation,
            admit: (CalendarScope) -> Boolean): TitleUpdateAttemptRecord? = error("No preparation")
        override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation {
            legacySubmissions++; error("No unrestricted submission")
        }
        override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
            legacySubmissions++; error("No fallback to three-argument submission")
        }
        override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation = error("No inspection")
        override suspend fun inspect(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation = error("No inspection")
    }
    private class Fixture(allDay: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        private val token = UUID.randomUUID().toString()
        val importName = "title-submit-result-owner-$token.db"
        val journalName = "title-submit-result-attempt-$token.db"
        val target = CalendarScope(AccountId("title-submit-result-$token"), CalendarId("fixture"), 0)
        val format = AdapterFormat("fixture-import", 1)
        val base = RemoteChange.Upsert(EventId("fixture-event"), Revision("fixture-base"), EventContent(
            "Synthetic original", if (allDay) EventTime.AllDay(LocalDate.of(2030, 10, 27), LocalDate.of(2030, 10, 28)) else
                EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
            "Synthetic description", "Synthetic location", details = EventDetails()))
        val intent = LocalEditIntent(MutationId("fixture-local-intent"), target, format, base, base.content.copy(title = "Synthetic prepared title"))
        val preparation = LocalEditPreparation(intent, Instant.parse("2026-10-07T12:00:00Z"), AdapterFormat("fixture-preparation", 1), "synthetic-capsule")
        val record = TitleUpdateAttemptRecord(MutationId("fixture-attempt"), preparation, AdapterFormat("fixture-wire", 1), "synthetic-wire")
        val unknown get() = record.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
        val responseEvidence = "synthetic-response-evidence"
        val evidencedUnknown get() = unknown.copy(protectedEvidence = responseEvidence)
        val expectedEvent = base.copy(revision = Revision("synthetic-updated-revision"), content = intent.content)
        var reportedEvent = expectedEvent
        var reportedAttempt = TitleUpdateAttemptIdentity.of(unknown)
        val store = ImportStore(context, importName)
        val journal = TitleUpdateAttemptStore(context, journalName)
        val guarded = Journal(journal)
        val results = Results(journal)
        val expectedResult get() = TitleUpdateVerifiedResult.of(unknown,
            TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(unknown), expectedEvent))
        var permission = true
        var mode = true
        var account: AccountId? = target.account
        var nativeContent = intent.content
        var nativeAbsent = false
        var captures = 0
        var nativeWrites = 0
        var submissions = 0
        var simulatedSends = 0
        var inRuntime = false
        var forbiddenAccountReentries = 0
        var forbidOrdinaryCapture = false
        var duringAccount: suspend () -> Unit = {}
        var duringCapture: suspend () -> Unit = {}
        var atStage: suspend (String) -> Unit = {}
        var afterSourceDenial: (suspend () -> Unit)? = null
        var swallowWithUnknown = false
        var skipClaim = false
        var omitEvidence = false
        val projection = object : CalendarProjection {
            override var admitOperation: () -> Boolean = { false }
            override fun hasPermissions() = permission
            override fun accountFor(accountId: AccountId) = Account("fixture-$token", "fixture")
            override suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar { nativeWrites++; error("No native write") }
            override suspend fun apply(intent: ProjectionIntent, seriesUid: String?) { nativeWrites++; error("No native write") }
            override suspend fun acknowledgeTitleUpdate(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? {
                nativeWrites++; error("Submission cannot acknowledge Android")
            }
            override suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? {
                check(!forbidOrdinaryCapture) { "CREATE provenance cannot fall back to ordinary capture" }
                check(admitOperation()); assertEquals(target, scope); assertEquals(this@Fixture.base, base)
                captures++; duringCapture(); return if (nativeAbsent) null else OwnedLocalEditSnapshot(1, 1, nativeContent)
            }
        }
        val port = object : UnsupportedPort(record.format) {
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean,
                qualifySource: suspend (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                submissions++; inRuntime = true
                try {
                    assertEquals(this@Fixture.record, record)
                    assertTrue(admit(target)); assertFalse(admit(target.copy(generation = target.generation + 1)))
                    suspend fun qualify(): Boolean {
                        val result = qualifySource(target)
                        if (!result) afterSourceDenial?.let { restore ->
                            restore(); assertFalse(admit(target)); assertFalse(qualifySource(target))
                        }
                        return result && admit(target)
                    }
                    atStage("entered")
                    if (swallowWithUnknown) return TitleUpdateSubmissionObservation.Unknown
                    if (!qualify()) return TitleUpdateSubmissionObservation.Unknown
                    atStage("beforeClaim")
                    if (!qualify()) return TitleUpdateSubmissionObservation.Unknown
                    if (!skipClaim && !journal.claim(record, admit)) return TitleUpdateSubmissionObservation.Unknown
                    atStage("afterClaim")
                    if (!qualify()) return TitleUpdateSubmissionObservation.Unknown
                    simulatedSends++
                    if (!omitEvidence) journal.retainEvidence(unknown, responseEvidence)
                    atStage("afterSend")
                    if (!qualify()) return TitleUpdateSubmissionObservation.Unknown
                    return TitleUpdateSubmissionObservation.Verified(reportedAttempt, reportedEvent)
                } finally { inRuntime = false }
            }
        }
        private val remote = object : ReadOnlyCalendarRemote {
            override val format = this@Fixture.format
            override suspend fun currentAccount(): AccountId? {
                if (inRuntime) { forbiddenAccountReentries++; error("No authentication facade re-entry during runtime") }
                duringAccount(); return account
            }
            override suspend fun fetch(request: PageRequest): RemotePage = error("No download")
            override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No generic dispatch")
        }
        private val scheduled = AtomicInteger()
        private val scheduler = object : ImportScheduler {
            override fun cancel() = Unit
            override fun requestLocal() = Unit
            override fun requestNetwork() { scheduled.incrementAndGet() }
            override fun continueWork(network: Boolean) = Unit
        }
        private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = ImportCoordinator(context, store, remote, projection, jobs, scheduler, systemCreatesEnabled = { mode })
        suspend fun seed() {
            store.remember(target.account, listOf(CalendarSummary(target.calendar, "Synthetic submission fixture")))
            store.setEnabled(target, true)
            store.transaction(target) { putRemote(base); putProjection(ProjectionIntent(target, base)); checkpoint = PageCheckpoint(target, format) }
            assertEquals(1, store.acknowledge(ProjectionIntent(target, base)))
            assertEquals(intent, store.captureLocalEdit(intent)); assertTrue(store.retainLocalEditPreparation(preparation))
            val reservation = TitleUpdatePreparationReservation.of(record)
            assertEquals(reservation, journal.reservePreparation(reservation) { true }); journal.remember(record)
            journal.admitDispatch = { error("No global dispatch authority") }
            coordinator.authenticationChanged(true)
            withTimeout(10_000) { while (scheduled.get() == 0) delay(10) }
        }
        suspend fun submit(value: TitleUpdateAttemptRecord = record, allowNetwork: Boolean = false,
            targetPort: CalendarTitleUpdateSubmissionPort = port) = coordinator.submitLocalTitleAttempt(value, guarded, targetPort, allowNetwork)
        suspend fun submitAndRetain(value: TitleUpdateAttemptRecord = record, allowNetwork: Boolean = false,
            targetResults: TitleUpdateResultJournal = results) =
            coordinator.submitAndRetainLocalTitleAttempt(value, guarded, targetResults, port, allowNetwork)
        suspend fun owner(): LocalEditIntent? { store.admitTransaction = { true }; return store.localEdit(target, base.eventId) }
        suspend fun assertNoAcknowledgement() {
            assertEquals(0, nativeWrites); assertEquals(0, forbiddenAccountReentries)
            assertNull(journal.readCompletion(target, record.id))
        }
        suspend fun close() {
            duringAccount = {}; coordinator.authenticationChanged(false); jobs.coroutineContext[Job]!!.cancelAndJoin()
            journal.close(); store.close(); context.deleteDatabase(importName); context.deleteDatabase(journalName)
        }
    }
    private fun fixture(allDay: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(allDay)
        try { fixture.seed(); fixture.block(); fixture.assertNoAcknowledgement() } finally { fixture.close() }
    }

    @Test fun timedAndAllDaySubmissionReturnsOnlyExactDurableProofReopenedFromTheJournal() {
        for (allDay in listOf(false, true)) fixture(allDay) {
            assertEquals(expectedResult, submitAndRetain(allowNetwork = true))
            assertNotNull(results.retainedAdmission)
            assertFalse("The completed invocation must close its storage lease", results.retainedAdmission!!.invoke(target))
            assertEquals(1, submissions); assertEquals(1, guarded.claims); assertEquals(1, simulatedSends)
            assertEquals(1, results.retains); assertTrue(results.reads > 0)
            assertEquals(evidencedUnknown, journal.read(target, record.id)); assertEquals(expectedResult, journal.readVerified(target, record.id))
            TitleUpdateAttemptStore(context, journalName).use { reopened ->
                assertEquals(expectedResult, reopened.readVerified(target, record.id)); assertEquals(evidencedUnknown, reopened.read(target, record.id))
            }
            assertEquals(intent, owner()); assertEquals(preparation, store.localEditPreparation(target, base.eventId))
        }
    }

    @Test fun offlineDefaultCannotSubmitOrCreateAResult() = fixture {
        assertNull(submitAndRetain())
        assertEquals(0, submissions); assertEquals(0, guarded.claims); assertEquals(0, results.retains)
        assertEquals(record, journal.read(target, record.id)); assertNull(journal.readVerified(target, record.id))
    }

    @Test fun missingWireAndUnknownWorkCannotResubmitUsingOldPreparedInput() {
        fixture {
            journal.writableDatabase.execSQL("DELETE FROM title_update_attempts")
            assertNull(submitAndRetain(allowNetwork = true)); assertEquals(0, submissions); assertEquals(0, results.retains)
        }
        fixture {
            assertTrue(journal.claim(record) { true })
            assertNull(submitAndRetain(allowNetwork = true)); assertNull(submitAndRetain(unknown, true))
            assertEquals(0, submissions); assertEquals(0, results.retains); assertEquals(unknown, journal.read(target, record.id))
        }
        fixture {
            assertEquals(expectedResult, submitAndRetain(allowNetwork = true))
            assertNull(submitAndRetain(allowNetwork = true))
            assertEquals(1, submissions); assertEquals(1, guarded.claims); assertEquals(1, results.retains)
            assertEquals(expectedResult, journal.readVerified(target, record.id))
        }
    }

    @Test fun unsupportedGuardedResultJournalCannotFallBackAfterOneCommittedSend() = fixture {
        val unsupported = UnsupportedResults(journal)
        assertNull(submitAndRetain(allowNetwork = true, targetResults = unsupported))
        assertEquals(1, submissions); assertEquals(1, simulatedSends); assertEquals(0, unsupported.legacyCalls)
        assertEquals(evidencedUnknown, journal.read(target, record.id)); assertNull(journal.readVerified(target, record.id))
        assertEquals(intent, owner())
    }

    @Test fun resultRetentionExceptionPreservesUnknownEvidenceWithoutReportingSuccess() = fixture {
        results.beforeRetain = { throw IllegalStateException("Synthetic result retention failure") }
        assertNull(submitAndRetain(allowNetwork = true))
        assertEquals(1, results.retains); assertEquals(1, simulatedSends)
        assertEquals(evidencedUnknown, journal.read(target, record.id)); assertNull(journal.readVerified(target, record.id))
        assertNull(submitAndRetain(allowNetwork = true)); assertEquals(1, simulatedSends)
    }

    @Test fun deferredResultCommitFailureKeepsUnknownAndEvidenceAfterReopening() = fixture {
        journal.writableDatabase.execSQL("CREATE TABLE result_fixture_parent(id INTEGER PRIMARY KEY)")
        journal.writableDatabase.execSQL("CREATE TABLE result_fixture_child(parent INTEGER REFERENCES result_fixture_parent(id) DEFERRABLE INITIALLY DEFERRED)")
        journal.writableDatabase.execSQL("CREATE TRIGGER reject_submission_result_commit AFTER UPDATE OF result ON title_update_attempts WHEN NEW.result IS NOT NULL BEGIN INSERT INTO result_fixture_child(parent) VALUES(1); END")
        var beforeCommit = false
        results.duringCommit = { beforeCommit = true }
        assertNull(submitAndRetain(allowNetwork = true)); assertTrue(beforeCommit)
        // Simulate restart: close the connection pool that encountered the failed COMMIT.
        journal.close()
        TitleUpdateAttemptStore(context, journalName).use { reopened ->
            assertEquals(evidencedUnknown, reopened.read(target, record.id)); assertNull(reopened.readVerified(target, record.id))
        }
        assertEquals(1, simulatedSends); assertEquals(intent, owner())
    }

    @Test fun accessRevocationAtResultEntryOrBeforeCommitDeniesOrRollsBackProof() {
        for (beforeCommit in listOf(false, true)) fixture {
            if (beforeCommit) results.duringCommit = { permission = false; coordinator.permissionsChanged() }
            else results.beforeRetain = { permission = false; coordinator.permissionsChanged() }
            assertNull(submitAndRetain(allowNetwork = true))
            assertEquals(1, results.retains); assertEquals(1, simulatedSends)
            assertEquals(evidencedUnknown, journal.read(target, record.id)); assertNull(journal.readVerified(target, record.id))
            TitleUpdateAttemptStore(context, journalName).use { reopened -> assertNull(reopened.readVerified(target, record.id)) }
        }
    }

    @Test fun accessRevocationAfterResultCommitHidesProofWithoutDeletingDurableHistory() = fixture {
        results.afterRetain = { permission = false; coordinator.permissionsChanged() }
        assertNull(submitAndRetain(allowNetwork = true))
        assertEquals(1, results.retains); assertEquals(1, simulatedSends)
        assertEquals(expectedResult, journal.readVerified(target, record.id)); assertEquals(evidencedUnknown, journal.read(target, record.id))
        TitleUpdateAttemptStore(context, journalName).use { reopened -> assertEquals(expectedResult, reopened.readVerified(target, record.id)) }
    }

    @Test fun sqlOrNativeChangeDuringIndependentResultCommitPreservesHistoryButHoldsReturn() {
        for (sql in listOf(false, true)) fixture {
            results.duringCommit = {
                if (sql) store.writableDatabase.execSQL("UPDATE local_edits SET state='CONFLICT'")
                else nativeContent = intent.content.copy(title = "Synthetic later native edit")
            }
            assertNull(submitAndRetain(allowNetwork = true))
            assertEquals(1, results.retains); assertEquals(1, simulatedSends)
            assertEquals(expectedResult, journal.readVerified(target, record.id)); assertEquals(evidencedUnknown, journal.read(target, record.id))
            assertEquals(if (sql) intent.copy(state = LocalEditState.CONFLICT) else intent, owner())
        }
    }

    @Test fun missingOrSubstitutedFinalResultCannotBeReturnedAsTheCommittedProof() {
        for (missing in listOf(false, true)) fixture {
            var finalReads = 0
            results.afterRead = { proof ->
                if (proof == null) null else {
                    finalReads++
                    if (missing) null else TitleUpdateVerifiedResult.of(unknown, TitleUpdateSubmissionObservation.Verified(
                        TitleUpdateAttemptIdentity.of(unknown), expectedEvent.copy(revision = Revision("different-readback-revision"))))
                }
            }
            assertNull(submitAndRetain(allowNetwork = true)); assertTrue(finalReads > 0)
            assertEquals(expectedResult, journal.readVerified(target, record.id)); assertEquals(evidencedUnknown, journal.read(target, record.id))
            assertEquals(1, simulatedSends)
        }
    }

    @Test fun cancellationSwallowedAsFalseOrNullCannotReturnNormallyAfterSend() {
        for (duringRead in listOf(false, true)) fixture {
            val normalReturns = AtomicInteger()
            suspend fun cancelCall() { currentCoroutineContext().cancel(CancellationException("Synthetic result cancellation")) }
            if (duringRead) results.afterRead = { proof -> if (proof != null) cancelCall(); null }
            else { results.returnFalse = true; results.beforeRetain = { cancelCall() } }
            supervisorScope {
                val request = async { submitAndRetain(allowNetwork = true); normalReturns.incrementAndGet() }
                try { request.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            }
            assertEquals(0, normalReturns.get()); assertEquals(1, simulatedSends)
            assertEquals(evidencedUnknown, journal.read(target, record.id))
            assertEquals(if (duringRead) expectedResult else null, journal.readVerified(target, record.id))
        }
    }

    @Test fun concurrentFirstObservationCannotBeReplacedByTheReturnedSendObservation() = fixture {
        val winner = TitleUpdateVerifiedResult.of(unknown, TitleUpdateSubmissionObservation.Verified(
            TitleUpdateAttemptIdentity.of(unknown), expectedEvent.copy(revision = Revision("concurrent-observation-revision"))))
        TitleUpdateAttemptStore(context, journalName).use { other ->
            other.writableDatabase
            results.beforeRetain = { assertTrue(other.retainVerified(winner) { it == target }) }
            assertNull(submitAndRetain(allowNetwork = true))
            assertEquals(winner, other.readVerified(target, record.id))
        }
        assertEquals(1, results.retains); assertEquals(1, simulatedSends)
        assertEquals(winner, journal.readVerified(target, record.id)); assertEquals(evidencedUnknown, journal.read(target, record.id))
    }

    @Test fun observationOnlyApiStillDoesNotPersistVerifiedResults() = fixture {
        val observation = submit(allowNetwork = true) as? TitleUpdateSubmissionObservation.Verified
        assertNotNull(observation); assertEquals(expectedEvent, observation!!.event)
        assertEquals(0, results.retains); assertNull(journal.readVerified(target, record.id))
        assertEquals(evidencedUnknown, journal.read(target, record.id)); assertEquals(1, simulatedSends)
    }

    @Test fun accountChangeAfterRuntimeDeniesProofWithoutAuthenticationReentry() = fixture {
        atStage = { if (it == "afterSend") account = AccountId("different-current-account") }
        assertNull(submitAndRetain(allowNetwork = true))
        assertEquals(1, simulatedSends); assertEquals(0, results.retains); assertEquals(0, forbiddenAccountReentries)
        assertEquals(evidencedUnknown, journal.read(target, record.id)); assertNull(journal.readVerified(target, record.id))
    }

    @Test fun permissionOffOnDuringResultRetentionCannotObtainANewPolicyEpoch() = fixture {
        results.beforeRetain = {
            permission = false; coordinator.permissionsChanged(); permission = true; coordinator.permissionsChanged()
        }
        assertNull(submitAndRetain(allowNetwork = true))
        assertEquals(1, results.retains); assertEquals(1, simulatedSends)
        assertEquals(evidencedUnknown, journal.read(target, record.id)); assertNull(journal.readVerified(target, record.id))
    }
}
