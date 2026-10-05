// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TitleUpdateVerifiedResultTest {
    private val scope = CalendarScope(AccountId("result-fixture-account"), CalendarId("result-fixture-calendar"), 2)
    private val base = RemoteChange.Upsert(EventId("result-fixture-event"), Revision("original-revision"),
        EventContent("Original synthetic title", EventTime.Timed(Instant.parse("2030-05-02T10:00:00Z"),
            Instant.parse("2030-05-02T11:00:00Z"), ZoneId.of("Europe/Paris")),
            "Preserved description", "Preserved location", details = EventDetails()))
    private val intent = LocalEditIntent(MutationId("result-local-intent"), scope, AdapterFormat("result-import", 1),
        base, base.content.copy(title = "Submitted synthetic title"))
    private val unknown = TitleUpdateAttemptRecord(MutationId("result-attempt"), LocalEditPreparation(intent,
        Instant.parse("2030-05-01T10:00:00Z"), AdapterFormat("result-preparation", 1), "sealed-original-preparation"),
        AdapterFormat("result-wire", 1), "sealed-original-wire", TitleUpdateAttemptState.OUTCOME_UNKNOWN)
    private val event = base.copy(revision = Revision("verified-revision"), content = intent.content)
    private fun observed(record: TitleUpdateAttemptRecord = unknown, event: RemoteChange.Upsert = this.event) =
        TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(record), event)
    private fun result(record: TitleUpdateAttemptRecord = unknown) = TitleUpdateVerifiedResult.of(record, observed(record))

    @Test fun fullResultBindsOriginalWorkButNotIndependentlyRetainedResponseEvidence() {
        val first = result()
        assertEquals(unknown, first.record)
        assertEquals(event, first.event)
        assertEquals(TitleUpdateAttemptIdentity.of(unknown), first.attempt)
        assertEquals(first, result(unknown.copy(protectedEvidence = "sealed-response")))
        assertEquals(first.hashCode(), result().hashCode())
        assertFalse(first.toString().contains(intent.content.title))
        assertFalse(first.toString().contains(scope.account.value))
    }

    @Test fun preparedWorkOrSubstitutedIdentityCannotBecomeAResult() {
        val altered = listOf(unknown.copy(state = TitleUpdateAttemptState.PREPARED),
            unknown.copy(id = MutationId("substituted-attempt")),
            unknown.copy(protectedPayload = "substituted-wire"),
            unknown.copy(format = AdapterFormat("other-wire", 2)),
            unknown.copy(preparation = unknown.preparation.copy(protectedPayload = "substituted-preparation")),
            unknown.copy(preparation = unknown.preparation.copy(timestamp = unknown.preparation.timestamp.plusSeconds(1))),
            unknown.copy(preparation = unknown.preparation.copy(intent = intent.copy(scope = scope.copy(generation = 3)))))
        for (record in altered) rejects { TitleUpdateVerifiedResult.of(record, observed()) }
    }

    @Test fun sameTitleDoesNotQualifyADifferentFullContentSnapshot() {
        val wrong = listOf(intent.content.copy(description = "Changed description"),
            intent.content.copy(location = "Changed location"),
            intent.content.copy(time = (intent.content.time as EventTime.Timed).copy(zone = ZoneId.of("UTC"))),
            intent.content.copy(title = "Different title"))
        for (content in wrong) rejects { TitleUpdateVerifiedResult.of(unknown, observed(event = event.copy(content = content))) }
    }

    @Test fun callerOwnedEmptyCollectionsCannotMutateTheRetainedResult() {
        val reminders = mutableListOf<EventReminder>()
        val attendees = mutableListOf<EventAttendee>()
        val details = EventDetails(reminders, attendees = attendees)
        val mutableBase = base.copy(content = base.content.copy(details = details))
        val mutableIntent = intent.copy(base = mutableBase, content = intent.content.copy(details = details))
        val record = unknown.copy(preparation = unknown.preparation.copy(intent = mutableIntent))
        val retained = TitleUpdateVerifiedResult.of(record, observed(record, event.copy(content = mutableIntent.content)))
        reminders += EventReminder(10)
        attendees += EventAttendee(EventPerson("fixture@example.invalid"), AttendanceRole.REQUIRED, AttendanceStatus.ACCEPTED)
        assertEquals(result(), retained)
        assertEquals(EventDetails(), retained.record.preparation.intent.base.content.details)
        assertEquals(EventDetails(), retained.event.content.details)
    }

    @Test fun recoveryInspectsUnknownThenCommitsBeforeReturningWithoutAnySubmission() = runTest {
        val journal = Journal(unknown)
        val port = Port { supplied -> assertEquals(unknown, supplied); observed() }
        val recovered = TitleUpdateResultRecovery(journal, journal, port).recover(scope, unknown.id)
        assertEquals(result(), recovered)
        assertEquals(recovered, journal.retained)
        assertEquals(1, port.inspections)
        assertEquals(1, journal.commits)
        assertEquals(unknown, journal.current)
    }

    @Test fun retainedHistoricalResultIsAvailableOfflineWithoutInspectingOrSending() = runTest {
        val journal = Journal(unknown.copy(protectedEvidence = "response-attached-later"))
        journal.retained = result()
        val port = Port { error("Retained history requires no network") }
        assertEquals(result(), TitleUpdateResultRecovery(journal, journal, port).recover(scope, unknown.id))
        assertEquals(0, port.inspections)
        assertEquals(0, journal.commits)
    }

    @Test fun missingPreparedOrDifferentScopeDoesNotInspectOrRetain() = runTest {
        for (current in listOf(null, unknown.copy(state = TitleUpdateAttemptState.PREPARED))) {
            val journal = Journal(current)
            val port = Port { error("No unknown work") }
            val recovery = TitleUpdateResultRecovery(journal, journal, port)
            assertNull(recovery.recover(scope, unknown.id))
            assertNull(recovery.recover(scope.copy(generation = 3), unknown.id))
            assertEquals(0, port.inspections)
            assertEquals(0, journal.commits)
        }
    }

    @Test fun unknownObservationKeepsAttemptAndDoesNotInventAProof() = runTest {
        val journal = Journal(unknown)
        val port = Port { TitleUpdateSubmissionObservation.Unknown }
        assertNull(TitleUpdateResultRecovery(journal, journal, port).recover(scope, unknown.id))
        assertNull(journal.retained)
        assertEquals(unknown, journal.current)
        assertEquals(1, port.inspections)
        assertEquals(0, journal.commits)
    }

    @Test fun failedRetentionDoesNotReturnUncommittedSuccessAndRetryOnlyInspects() = runTest {
        val journal = Journal(unknown).apply { failCommit = true }
        val port = Port { observed() }
        val recovery = TitleUpdateResultRecovery(journal, journal, port)
        try { recovery.recover(scope, unknown.id); fail("Uncommitted proof must not succeed") }
        catch (_: IllegalStateException) { }
        assertNull(journal.retained)
        assertEquals(unknown, journal.current)
        journal.failCommit = false
        assertEquals(result(), recovery.recover(scope, unknown.id))
        assertEquals(2, port.inspections)
    }

    @Test fun cancellationAndReadbackFailureLeaveWorkUnknown() = runTest {
        for (error in listOf(CancellationException("Synthetic cancellation"), IllegalStateException("Synthetic readback failure"))) {
            val journal = Journal(unknown)
            val recovery = TitleUpdateResultRecovery(journal, journal, Port { throw error })
            try { recovery.recover(scope, unknown.id); fail("Failure must propagate") }
            catch (observed: Exception) { assertSame(error, observed) }
            assertNull(journal.retained)
            assertEquals(unknown, journal.current)
            assertEquals(0, journal.commits)
        }
    }

    @Test fun substitutedAdapterObservationCannotBeRetained() = runTest {
        val journal = Journal(unknown)
        val port = Port { observed(unknown.copy(protectedPayload = "other-wire")) }
        try { TitleUpdateResultRecovery(journal, journal, port).recover(scope, unknown.id); fail("Wrong proof") }
        catch (_: IllegalArgumentException) { }
        assertNull(journal.retained)
        assertEquals(0, journal.commits)
    }

    @Test fun retainedResultFromAnotherWorkIsRejectedWithoutReinspection() = runTest {
        val journal = Journal(unknown)
        journal.retained = result(unknown.copy(protectedPayload = "other-wire"))
        val port = Port { error("Corrupt history must not be bypassed") }
        try { TitleUpdateResultRecovery(journal, journal, port).recover(scope, unknown.id); fail("Wrong history") }
        catch (_: IllegalStateException) { }
        assertEquals(0, port.inspections)
        assertEquals(0, journal.commits)
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected mismatched result rejection") }
        catch (_: IllegalArgumentException) { }
    }
    private class Journal(var current: TitleUpdateAttemptRecord?) : TitleUpdateAttemptJournal, TitleUpdateResultJournal {
        var retained: TitleUpdateVerifiedResult? = null
        var commits = 0
        var failCommit = false
        override suspend fun read(scope: CalendarScope, id: MutationId) = current?.takeIf { it.scope == scope && it.id == id }
        override suspend fun readVerified(scope: CalendarScope, id: MutationId) = retained
        override suspend fun retainVerified(result: TitleUpdateVerifiedResult) {
            check(!failCommit) { "Synthetic commit failure" }
            check(current?.copy(protectedEvidence = null) == result.record)
            check(retained == null || retained == result)
            retained = result
            commits++
        }
        override suspend fun remember(record: TitleUpdateAttemptRecord) = error("Recovery cannot remember work")
        override suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean = error("Recovery cannot admit dispatch")
        override suspend fun retainEvidence(expectedUnknown: TitleUpdateAttemptRecord, protectedEvidence: String) =
            error("Recovery cannot replace response evidence")
    }
    private class Port(private val observe: suspend (TitleUpdateAttemptRecord) -> TitleUpdateSubmissionObservation) : CalendarTitleUpdateSubmissionPort {
        var inspections = 0
        override val titleUpdateFormat = AdapterFormat("result-wire", 1)
        override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation {
            inspections++
            return observe(record)
        }
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord =
            error("Recovery cannot prepare work")
        override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation =
            error("Recovery cannot submit work")
    }
}
