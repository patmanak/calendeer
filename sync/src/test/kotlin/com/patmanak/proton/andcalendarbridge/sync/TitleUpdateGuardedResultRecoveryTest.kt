// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TitleUpdateGuardedResultRecoveryTest {
    private val scope = CalendarScope(AccountId("guarded-result-account"), CalendarId("fixture"), 2)
    private val base = RemoteChange.Upsert(EventId("guarded-result-event"), Revision("synthetic-base"),
        EventContent("Synthetic original", EventTime.AllDay(LocalDate.of(2030, 5, 2), LocalDate.of(2030, 5, 3)),
            "Preserved description", "Preserved location", details = EventDetails()))
    private val intent = LocalEditIntent(MutationId("synthetic-intent"), scope, AdapterFormat("synthetic-import", 1),
        base, base.content.copy(title = "Synthetic edited"))
    private val work = TitleUpdateAttemptRecord(MutationId("synthetic-attempt"), LocalEditPreparation(intent,
        Instant.parse("2030-05-01T12:00:00Z"), AdapterFormat("synthetic-preparation", 1), "synthetic-sealed-preparation"),
        AdapterFormat("synthetic-wire", 1), "synthetic-sealed-wire", TitleUpdateAttemptState.OUTCOME_UNKNOWN)
    private fun observation(record: TitleUpdateAttemptRecord = work) = TitleUpdateSubmissionObservation.Verified(
        TitleUpdateAttemptIdentity.of(record), base.copy(revision = Revision("synthetic-observed"), content = intent.content))
    private fun proof(record: TitleUpdateAttemptRecord = work) = TitleUpdateVerifiedResult.of(record, observation(record))

    private inner class Fixture {
        var active = true
        var current: TitleUpdateAttemptRecord? = work
        var retained: TitleUpdateVerifiedResult? = null
        var reads = 0
        var inspections = 0
        var commits = 0
        var denyCommit = false
        var fakeCommit = false
        var after: (String) -> Unit = {}
        var inspectFailure: Exception? = null
        var commitFailure: Exception? = null
        var observed = observation()
        val admit: (CalendarScope) -> Boolean = { active && it == scope }
        val attempts = object : TitleUpdateAttemptJournal {
            override suspend fun read(scope: CalendarScope, id: MutationId): TitleUpdateAttemptRecord? {
                reads++; after("attempt"); return current
            }
            override suspend fun remember(record: TitleUpdateAttemptRecord) = error("Recovery cannot prepare")
            override suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean = error("Recovery cannot claim")
            override suspend fun retainEvidence(expectedUnknown: TitleUpdateAttemptRecord, protectedEvidence: String) =
                error("Recovery cannot replace response evidence")
        }
        val results = object : TitleUpdateResultJournal {
            override suspend fun readVerified(scope: CalendarScope, id: MutationId): TitleUpdateVerifiedResult? {
                reads++; after("history"); return retained
            }
            override suspend fun retainVerified(result: TitleUpdateVerifiedResult) = error("Unrestricted retention forbidden")
            override suspend fun retainVerified(result: TitleUpdateVerifiedResult, admit: (CalendarScope) -> Boolean): Boolean {
                after("before-commit"); commitFailure?.let { throw it }
                if (denyCommit || !admit(result.record.scope)) return false
                if (!fakeCommit) {
                    check(retained == null || retained == result)
                    retained = result; commits++
                }
                after("after-commit"); return true
            }
        }
        val port = object : CalendarTitleUpdateSubmissionPort {
            override val titleUpdateFormat = work.format
            override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord =
                error("Recovery cannot prepare")
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation =
                error("Recovery cannot submit")
            override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation = error("Unrestricted inspection forbidden")
            override suspend fun inspect(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                if (!admit(record.scope)) return TitleUpdateSubmissionObservation.Unknown
                inspections++; after("inspect"); inspectFailure?.let { throw it }; return observed
            }
        }
        val recovery = TitleUpdateGuardedResultRecovery(attempts, results, port)
        suspend fun recover(network: Boolean = true) = recovery.recover(scope, work.id, network, admit)
    }

    @Test fun defaultDenialAndExplicitRevocationPerformNoReadsOrInspection() = runTest {
        val f = Fixture()
        assertNull(f.recovery.recover(scope, work.id, allowNetwork = true))
        f.active = false; assertNull(f.recover())
        assertEquals(0, f.reads); assertEquals(0, f.inspections); assertEquals(0, f.commits)
    }
    @Test fun durableHistoryIsReturnedOfflineWithoutInspectionOrRetention() = runTest {
        val f = Fixture(); f.retained = proof()
        assertEquals(proof(), f.recover(network = false))
        assertEquals(0, f.inspections); assertEquals(0, f.commits)
    }
    @Test fun missingProofOfflineAndPreparedWorkStayHeld() = runTest {
        val f = Fixture(); assertNull(f.recover(network = false))
        f.current = work.copy(state = TitleUpdateAttemptState.PREPARED); assertNull(f.recover())
        f.current = null; assertNull(f.recover())
        assertEquals(0, f.inspections); assertEquals(0, f.commits)
    }
    @Test fun guardedInspectionCommitsThenReadsExactDurableProofWithoutResending() = runTest {
        val f = Fixture()
        assertEquals(proof(), f.recover())
        assertEquals(proof(), f.retained); assertEquals(work, f.current)
        assertEquals(1, f.inspections); assertEquals(1, f.commits)
        assertEquals(proof(), f.recover()); assertEquals(1, f.inspections); assertEquals(1, f.commits)
    }
    @Test fun scopeOrAttemptSubstitutionCannotInspectEvenIfJournalReturnsIt() = runTest {
        for (wrong in listOf(work.copy(id = MutationId("foreign")), work.copy(preparation = work.preparation.copy(
                intent = intent.copy(scope = scope.copy(generation = 7)))))) {
            val f = Fixture(); f.current = wrong
            try { f.recover(); fail("Substituted work must fail") } catch (_: IllegalStateException) { }
            assertEquals(0, f.inspections); assertEquals(0, f.commits)
        }
    }
    @Test fun corruptHistoryAndForeignObservationCannotBecomeDurableSuccess() = runTest {
        val wrong = work.copy(protectedPayload = "foreign-wire")
        val historical = Fixture(); historical.retained = proof(wrong)
        try { historical.recover(); fail("Wrong history") } catch (_: IllegalStateException) { }
        assertEquals(0, historical.inspections)
        val fresh = Fixture(); fresh.observed = observation(wrong)
        try { fresh.recover(); fail("Wrong observation") } catch (_: IllegalArgumentException) { }
        assertNull(fresh.retained); assertEquals(0, fresh.commits)
    }
    @Test fun leaseRevokedDuringAttemptHistoryInspectionOrCommitStopsRecovery() = runTest {
        for (stage in listOf("attempt", "history", "inspect", "before-commit")) {
            val f = Fixture(); f.after = { if (it == stage) f.active = false }
            assertNull(f.recover()); assertNull(f.retained); assertEquals(0, f.commits)
            assertEquals(if (stage in listOf("attempt", "history")) 0 else 1, f.inspections)
        }
    }
    @Test fun successfulCommitFollowedByRevocationRemainsDurableButCannotBeReturned() = runTest {
        val f = Fixture(); f.after = { if (it == "after-commit") f.active = false }
        assertNull(f.recover()); assertEquals(proof(), f.retained)
        f.active = true; f.after = {}
        assertEquals(proof(), f.recover(network = false)); assertEquals(1, f.inspections); assertEquals(1, f.commits)
    }
    @Test fun originalEpochCannotBeReadmittedAfterPolicyOffAndOnDuringInspection() = runTest {
        val f = Fixture(); var revision = 0
        val expected = revision
        f.after = { if (it == "inspect") { f.active = false; revision++; f.active = true; revision++ } }
        assertNull(f.recovery.recover(scope, work.id, true) { it == scope && f.active && revision == expected })
        assertNull(f.retained); assertEquals(1, f.inspections); assertEquals(0, f.commits)
    }
    @Test fun incompatibleWireFormatBlocksNetworkButDoesNotReinterpretHistoricalProof() = runTest {
        val f = Fixture(); val old = work.copy(format = AdapterFormat("synthetic-old-wire", 1))
        f.current = old
        assertNull(f.recover()); assertEquals(0, f.inspections)
        f.retained = proof(old)
        assertEquals(proof(old), f.recover(network = false)); assertEquals(0, f.inspections)
    }
    @Test fun deniedOrInventedCommitCannotReturnAnUnretainedObservation() = runTest {
        val denied = Fixture(); denied.denyCommit = true
        assertNull(denied.recover()); assertNull(denied.retained)
        val invented = Fixture(); invented.fakeCommit = true
        try { invented.recover(); fail("A boolean is not durable history") } catch (_: IllegalStateException) { }
        assertNull(invented.retained)
    }
    @Test fun inspectionAndCommitCancellationPropagateWithoutReturningProof() = runTest {
        for (stage in listOf("inspect", "commit")) {
            val f = Fixture(); val cancelled = CancellationException("Synthetic cancellation")
            if (stage == "inspect") f.inspectFailure = cancelled else f.commitFailure = cancelled
            try { f.recover(); fail("Cancellation must propagate") } catch (error: CancellationException) { assertSame(cancelled, error) }
            assertNull(f.retained); assertEquals(0, f.commits)
        }
    }
    @Test fun unsupportedGuardedPortAndJournalNeverFallBackToLegacyMethods() = runTest {
        val f = Fixture()
        val legacyPort = object : CalendarTitleUpdateSubmissionPort {
            override val titleUpdateFormat = work.format
            override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord =
                error("Legacy preparation forbidden")
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation =
                error("Legacy submission forbidden")
            override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation =
                error("Legacy inspection forbidden")
        }
        assertNull(TitleUpdateGuardedResultRecovery(f.attempts, f.results, legacyPort).recover(scope, work.id, true, f.admit))
        val denied = object : TitleUpdateResultJournal {
            override suspend fun readVerified(scope: CalendarScope, id: MutationId): TitleUpdateVerifiedResult? = null
            override suspend fun retainVerified(result: TitleUpdateVerifiedResult) = error("Legacy fallback forbidden")
        }
        assertNull(TitleUpdateGuardedResultRecovery(f.attempts, denied, f.port).recover(scope, work.id, true, f.admit))
        assertEquals(0, f.commits)
    }
}
