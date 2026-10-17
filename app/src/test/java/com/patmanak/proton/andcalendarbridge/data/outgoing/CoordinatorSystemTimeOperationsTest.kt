// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.data.importing.RetainedLocalChangeListing
import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Host orchestration contracts only; no SQLite, Provider, crypto or service qualification. */
class CoordinatorSystemTimeOperationsTest {
    @Test fun preparedWorkRetainsProofThenWakesThenAcknowledgesAndCompletes() = runBlocking {
        val f = TimeOperationsFixture()
        assertFalse(f.process())
        assertEquals(listOf("observe", "capsule", "retain-capsule", "qualify", "wire", "qualify", "remember",
            "submit", "qualify", "claim", "proof", "wake", "ack", "complete"), f.calls)
        assertEquals(TimeUpdateAttemptState.OUTCOME_UNKNOWN, f.saved!!.state)
        assertNotNull(f.result); assertNotNull(f.completion)
        assertEquals(f.changed.description, f.saved!!.preparation.intent.content.description)
        assertEquals("Original\nnotes", f.result!!.event.content.description)
    }

    @Test fun firstCapsuleDefinesRequestDespiteLaterTimestampAndRandomTraversalCandidate() = runBlocking {
        val f = TimeOperationsFixture(); f.retained = f.preparation
        f.submitObservation = { TimeUpdateSubmissionObservation.Unknown }
        f.process(timestamp = f.stamp.plusSeconds(90), candidate = MutationId("ignored-random-one"))
        val first = f.saved!!
        assertEquals(TimeUpdateAttemptIdentity.requestId(f.preparation, f.wireFormat), first.id)
        assertEquals(f.stamp, first.preparation.timestamp)
        assertFalse("capsule" in f.calls)
        f.calls.clear()
        f.process(timestamp = f.stamp.plusSeconds(200), candidate = MutationId("ignored-random-two"))
        assertEquals(first, f.saved)
        assertFalse("wire" in f.calls); assertFalse("submit" in f.calls)
    }

    @Test fun concurrentRetentionUsesFirstCapsuleAndFirstWireWithoutReplacingEither() = runBlocking {
        val f = TimeOperationsFixture()
        val winner = LocalTimeEditPreparation(f.intent, f.stamp.minusSeconds(3), f.preparation.format, "first-capsule")
        f.retainHook = { winner }
        f.rememberHook = { proposed -> proposed.copy(protectedPayload = "first-wire-from-racer") }
        f.process()
        assertEquals("first-capsule", f.saved!!.preparation.protectedPayload)
        assertEquals(winner.timestamp, f.saved!!.preparation.timestamp)
        assertEquals(TimeUpdateAttemptIdentity.requestId(winner, f.wireFormat), f.saved!!.id)
        assertEquals("first-wire-from-racer", f.submitted!!.protectedPayload)
        assertNotNull(f.completion)
    }

    @Test fun unknownWorkIsOnlyInspectedByRecoveryNeverPreparedClaimedOrResent() = runBlocking {
        val f = TimeOperationsFixture(); f.seedUnknown()
        f.process()
        assertEquals(listOf("observe", "qualify"), f.calls)
        f.calls.clear()
        val progress = f.operations.inspect(f.query, { true }) { f.calls += "wake" }
        assertEquals(1, progress.completed)
        assertEquals(listOf("inspect", "proof", "wake", "ack", "complete"), f.calls)
    }

    @Test fun offlineDurableProofCompletesWithoutTransportAndAbsentProofIsHeld() = runBlocking {
        val f = TimeOperationsFixture(); f.seedUnknown()
        val held = f.operations.recoverOffline(f.query) { true }
        assertEquals(1, held.held); assertTrue(f.calls.isEmpty())
        f.result = f.verified(f.saved!!)
        val recovered = f.operations.recoverOffline(f.query) { true }
        assertEquals(1, recovered.completed)
        assertEquals(listOf("ack", "complete"), f.calls)
    }

    @Test fun deniedAndNonTimeWorkCannotReachPreparationOrTransport() = runBlocking {
        val denied = TimeOperationsFixture(); denied.allowed = false
        denied.process(); assertTrue(denied.calls.isEmpty())
        val title = TimeOperationsFixture()
        title.observedIntent = title.intent.copy(content = title.base.copy(title = "Title only"))
        title.process(); assertEquals(listOf("observe"), title.calls)
        val deletion = TimeOperationsFixture()
        deletion.operations.process(deletion.scope, deletion.reference.copy(kind = MutationKind.DELETE),
            MutationId("unused"), deletion.stamp, { true }) { fail("Unexpected proof") }
        assertTrue(deletion.calls.isEmpty())
    }

    @Test fun allDayOwnerCannotPrepareOrReserveAutomaticTimeWork() = runBlocking {
        val f = TimeOperationsFixture()
        val day = java.time.LocalDate.of(2033, 3, 20)
        f.observedIntent = f.intent.copy(
            base = f.intent.base.copy(content = f.base.copy(time = EventTime.AllDay(day, day.plusDays(1)))),
            content = f.base.copy(time = EventTime.AllDay(day.plusDays(1), day.plusDays(3))))
        // The broader cryptographic candidate is valid, but its wire convention is unqualified.
        LocalTimeEditCandidate(f.observedIntent)
        assertFalse(SystemTimeEditProfile.accepts(f.observedIntent))
        f.process()
        assertEquals(listOf("observe"), f.calls)
        assertNull(f.retained); assertNull(f.saved); assertNull(f.result)
    }

    @Test fun scopedLeaseCannotReopenOrEscapeAfterPreparationReturns() = runBlocking {
        val f = TimeOperationsFixture()
        f.capsuleHook = { lease ->
            assertFalse(lease(f.scope.copy(generation = f.scope.generation + 1)))
            assertTrue(lease(f.scope))
            f.allowed = false; assertFalse(lease(f.scope))
            f.allowed = true; assertFalse(lease(f.scope))
            f.preparation
        }
        f.process()
        assertFalse("retain-capsule" in f.calls); assertFalse("wire" in f.calls)
        assertFalse(f.retainedLease!!(f.scope))
    }

    @Test fun successfulRunExpiresBothSendingAndEvidenceCallbacks() = runBlocking {
        val f = TimeOperationsFixture(); f.process()
        assertFalse(f.retainedLease!!(f.scope)); assertFalse(f.evidenceLease!!(f.scope))
        assertFalse(f.evidenceLease!!(f.scope.copy(calendar = CalendarId("other-calendar"))))
    }

    @Test fun modeRevocationRetainsVerifiedProofAndWakesButDoesNotAcknowledge() = runBlocking {
        val f = TimeOperationsFixture()
        f.submitObservation = { unknown ->
            f.allowed = false
            TimeUpdateSubmissionObservation.Verified(unknown, f.verified(unknown).event)
        }
        f.process()
        assertNotNull(f.result); assertTrue("wake" in f.calls)
        assertFalse("ack" in f.calls); assertNull(f.completion)
        assertFalse(f.evidenceLease!!(f.scope)); assertFalse(f.retainedLease!!(f.scope))
    }

    @Test fun logoutRevokesEvidenceRetentionEvenWhenTheLateReplyIsVerified() = runBlocking {
        val f = TimeOperationsFixture()
        f.submitObservation = { unknown ->
            f.allowed = false; f.sessionOpen = false
            TimeUpdateSubmissionObservation.Verified(unknown, f.verified(unknown).event)
        }
        f.process()
        assertNull(f.result); assertNull(f.completion)
        assertFalse("proof" in f.calls); assertFalse("wake" in f.calls); assertFalse("ack" in f.calls)
        assertEquals(TimeUpdateAttemptState.OUTCOME_UNKNOWN, f.saved!!.state)
        assertFalse(f.evidenceLease!!(f.scope))
    }

    @Test fun substitutedPreparationOrWireCannotBeDispatched() = runBlocking {
        for (variant in 0..3) {
            val f = TimeOperationsFixture()
            when (variant) {
                0 -> f.capsuleHook = { LocalTimeEditPreparation(f.intent, f.stamp.plusSeconds(1), f.preparation.format, "wrong-time") }
                1 -> f.wireHook = { it.copy(id = MutationId("wrong-request")) }
                2 -> f.wireHook = { it.copy(preparation = LocalTimeEditPreparation(f.intent, f.stamp, f.preparation.format, "other-capsule")) }
                3 -> f.rememberHook = { it.copy(format = AdapterFormat("other-format", 1)) }
            }
            f.process()
            assertFalse("variant $variant dispatched", "submit" in f.calls)
            assertNull(f.result); assertNull(f.completion)
        }
    }

    @Test fun substitutedOwnerSnapshotCannotReachAuthenticatedPreparation() = runBlocking {
        for (variant in 0..2) {
            val f = TimeOperationsFixture()
            f.observedIntent = when (variant) {
                0 -> f.intent.copy(id = MutationId("other-owner"))
                1 -> f.intent.copy(base = f.intent.base.copy(eventId = EventId("other-event")))
                else -> f.intent.copy(scope = f.scope.copy(generation = f.scope.generation + 1))
            }
            f.process()
            assertFalse("variant $variant reached authenticated preparation", "capsule" in f.calls)
            assertFalse("submit" in f.calls); assertNull(f.saved)
        }
    }

    @Test fun resultForAnotherWireIsRejectedBeforeNativeAcknowledgement() = runBlocking {
        val f = TimeOperationsFixture(); f.seedUnknown()
        f.result = f.verified(f.saved!!.copy(protectedPayload = "substituted-wire"))
        val progress = f.operations.recoverOffline(f.query) { true }
        assertEquals(1, progress.failed); assertFalse("ack" in f.calls); assertNull(f.completion)
    }

    @Test fun failedProofRetentionCannotWakeOrAcknowledgeAndFailedAckCannotComplete() = runBlocking {
        val denied = TimeOperationsFixture(); denied.acceptProof = false
        denied.process(); assertFalse("wake" in denied.calls); assertFalse("ack" in denied.calls)
        val noAck = TimeOperationsFixture(); noAck.acceptAck = false
        noAck.process(); assertTrue("wake" in noAck.calls); assertTrue("ack" in noAck.calls)
        assertFalse("complete" in noAck.calls); assertNotNull(noAck.result)
    }

    @Test fun offlinePagePreservesItsCursorAndNeverInspectsUnknownTransport() = runBlocking {
        val f = TimeOperationsFixture(); f.seedUnknown()
        f.pageRows = listOf(TimeUpdateWorkRef(f.saved!!.id, f.saved!!.eventId, f.saved!!.state),
            TimeUpdateWorkRef(MutationId("next-request"), EventId("next-event"), TimeUpdateAttemptState.OUTCOME_UNKNOWN))
        val progress = f.operations.recoverOffline(TitleUpdateWorkQuery(f.scope, limit = 1)) { true }
        assertEquals(1, progress.held)
        assertEquals(TitleUpdateWorkCursor(f.scope, f.intent.base.eventId), progress.next)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun revocationDuringInspectionPreventsAckAndExpiresRecoveryCallback() = runBlocking {
        val f = TimeOperationsFixture(); f.seedUnknown()
        f.inspectHook = { lease ->
            f.allowed = false; assertFalse(lease(f.scope))
            f.allowed = true; assertFalse(lease(f.scope))
            TimeUpdateSubmissionObservation.Unknown
        }
        val progress = f.operations.inspect(f.query, { f.allowed }) { fail("Revoked proof wake") }
        assertTrue(progress.inactive); assertNull(progress.next)
        assertEquals(listOf("inspect"), f.calls)
        assertFalse(f.retainedLease!!(f.scope))
    }

}

/** Strict single-owner in-memory boundary, deliberately independent of SQL/crypto implementation. */
internal class TimeOperationsFixture {
    val scope = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 2)
    val stamp = Instant.parse("2033-03-20T09:00:00Z")
    val base = EventContent("Original title", EventTime.Timed(stamp.plusSeconds(3600), stamp.plusSeconds(7200),
        ZoneId.of("Europe/Paris")), "Original\r\nnotes", "Original\r\nroom", details = EventDetails())
    val changed = base.copy(time = EventTime.Timed(stamp.plusSeconds(10800), stamp.plusSeconds(18000), ZoneId.of("Europe/Paris")))
    val intent = LocalEditIntent(MutationId("fixture-owner"), scope, AdapterFormat("fixture-observation", 2),
        RemoteChange.Upsert(EventId("fixture-event"), Revision("fixture-base"), base), changed)
    val preparation = LocalTimeEditPreparation(intent, stamp, AdapterFormat("fixture-capsule", 1), "sealed-capsule")
    val wireFormat = AdapterFormat("fixture-wire", 1)
    val reference = RetainedLocalChangeRef(intent.base.eventId, intent.id, MutationKind.UPDATE, LocalEditState.PAUSED)
    val query = TitleUpdateWorkQuery(scope)
    val calls = mutableListOf<String>()
    var allowed = true
    var sessionOpen = true
    var observedIntent = intent
    var retained: LocalTimeEditPreparation? = null
    var saved: TimeUpdateAttemptRecord? = null
    var submitted: TimeUpdateAttemptRecord? = null
    var result: TimeUpdateVerifiedResult? = null
    var completion: TimeUpdateCompletion? = null
    var acceptProof = true
    var acceptAck = true
    var retainedLease: ((CalendarScope) -> Boolean)? = null
    var evidenceLease: ((CalendarScope) -> Boolean)? = null
    var capsuleHook: (suspend ((CalendarScope) -> Boolean) -> LocalTimeEditPreparation)? = null
    var retainHook: ((LocalTimeEditPreparation) -> LocalTimeEditPreparation)? = null
    var wireHook: ((TimeUpdateAttemptRecord) -> TimeUpdateAttemptRecord)? = null
    var rememberHook: ((TimeUpdateAttemptRecord) -> TimeUpdateAttemptRecord)? = null
    var submitObservation: ((TimeUpdateAttemptRecord) -> TimeUpdateSubmissionObservation)? = null
    var inspectHook: (((CalendarScope) -> Boolean) -> TimeUpdateSubmissionObservation)? = null
    var pageRows: List<TimeUpdateWorkRef>? = null
    var cancelAt: String? = null
    var cancellationHits = 0
    var throwAfterCancellation = false
    private suspend fun cancelHere(stage: String): Boolean {
        if (cancelAt != stage) return false
        cancellationHits++
        currentCoroutineContext().cancel()
        if (throwAfterCancellation) throw IOException("Synthetic failure after cancellation")
        return true
    }
    private fun admitted(admit: (CalendarScope) -> Boolean) = admit(scope)
    fun verified(record: TimeUpdateAttemptRecord): TimeUpdateVerifiedResult = TimeUpdateVerifiedResult.of(record,
        TimeUpdateSubmissionObservation.Verified(record, intent.base.copy(revision = Revision("fixture-result"),
            content = changed.copy(description = "Original\nnotes"))))
    fun seedUnknown() {
        retained = preparation
        saved = TimeUpdateAttemptRecord(TimeUpdateAttemptIdentity.requestId(preparation, wireFormat), preparation,
            wireFormat, "sealed-first-wire", TimeUpdateAttemptState.OUTCOME_UNKNOWN)
    }

    val journal = object : TimeUpdateRecoveryJournal {
        override val completionProtocol = 1
        override suspend fun remember(record: TimeUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "remember"; check(admitted(admit)); check(saved == null)
            saved = rememberHook?.invoke(record) ?: record.frozen(); return saved == record
        }
        override suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TimeUpdateAttemptRecord? {
            check(scope == this@TimeOperationsFixture.scope && admitted(admit))
            if (cancelHere("read")) return null
            return saved?.takeIf { it.id == id }?.frozen()
        }
        override suspend fun claim(expected: TimeUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "claim"; check(admitted(admit)); check(saved == expected && expected.state == TimeUpdateAttemptState.PREPARED)
            saved = expected.copy(state = TimeUpdateAttemptState.OUTCOME_UNKNOWN); return true
        }
        override suspend fun retainEvidence(expectedUnknown: TimeUpdateAttemptRecord, protectedEvidence: String,
            admit: (CalendarScope) -> Boolean): Boolean = error("Unexpected evidence operation")
        override suspend fun retainVerified(result: TimeUpdateVerifiedResult, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "proof"; check(admitted(admit)); check(result.record == saved!!.copy(protectedEvidence = null))
            if (acceptProof) this@TimeOperationsFixture.result = result.snapshot()
            cancelHere("proof")
            return acceptProof
        }
        override suspend fun readVerified(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TimeUpdateVerifiedResult? {
            check(scope == this@TimeOperationsFixture.scope && admitted(admit)); check(id == saved!!.id); return result
        }
        override suspend fun retainCompletion(completion: TimeUpdateCompletion, admit: (CalendarScope) -> Boolean): Boolean {
            check(admitted(admit)); check(completion.result == result); this@TimeOperationsFixture.completion = completion; return true
        }
        override suspend fun readCompletion(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TimeUpdateCompletion? {
            check(scope == this@TimeOperationsFixture.scope && admitted(admit)); check(id == saved!!.id); return completion
        }
        override suspend fun page(query: TimeUpdateWorkQuery, admit: (CalendarScope) -> Boolean): TimeUpdateWorkPage? {
            check(query.scope == scope && admitted(admit))
            if (cancelHere("page")) return null
            return TimeUpdateWorkPage.fromOrderedMetadata(query, pageRows ?: listOfNotNull(saved?.let { TimeUpdateWorkRef(it.id, it.eventId, it.state) }))
        }
    }
    private val owners = object : SystemTimeLocalOwner {
        override suspend fun observe(scope: CalendarScope, event: EventId, owner: MutationId,
            admit: (CalendarScope) -> Boolean): TimeLocalOwnerSnapshot? {
            calls += "observe"; check(scope == this@TimeOperationsFixture.scope && event == intent.base.eventId && owner == intent.id)
            check(admitted(admit))
            if (cancelHere("observe")) return null
            return if (SystemTimeEditProfile.accepts(observedIntent)) TimeLocalOwnerSnapshot(observedIntent, retained) else null
        }
        override suspend fun retain(preparation: LocalTimeEditPreparation, admit: (CalendarScope) -> Boolean): LocalTimeEditPreparation? {
            calls += "retain-capsule"; check(admitted(admit))
            if (cancelHere("retain-capsule")) return null
            retained = retainHook?.invoke(preparation) ?: preparation; return retained
        }
        override suspend fun qualify(preparation: LocalTimeEditPreparation, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "qualify"; check(preparation.intent == intent); return admitted(admit)
        }
        override suspend fun submit(record: TimeUpdateAttemptRecord, journal: TimeUpdateRecoveryJournal,
            port: CalendarTimeUpdateSubmissionPort, admit: (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): TimeUpdateSubmissionObservation =
            port.submit(record, journal, admit, { requested -> requested == scope && qualify(record.preparation, admit) }, admitEvidence)
        override suspend fun acknowledge(result: TimeUpdateVerifiedResult, journal: TimeUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            calls += "ack"; check(admitted(admit)); check(this@TimeOperationsFixture.result == result)
            if (cancelHere("ack")) return true
            return acceptAck
        }
        override suspend fun complete(result: TimeUpdateVerifiedResult, journal: TimeUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            calls += "complete"; check(acceptAck && admitted(admit))
            val receipt = TimeUpdateAcknowledgementReceipt.of(result, "00000000-0000-0000-0000-000000000099")
            return journal.retainCompletion(TimeUpdateCompletion.of(result,
                TimeUpdateAcknowledgementRecord(receipt, TimeUpdateAcknowledgementState.RESTORED)), admit)
        }
    }
    private val capsules = object : CalendarLocalTimePreparationPort {
        override val localTimeFormat = preparation.format
        override suspend fun prepareLocalTime(intent: LocalEditIntent, timestamp: Instant): LocalTimeEditPreparation =
            error("Unguarded preparation forbidden")
        override suspend fun prepareLocalTime(intent: LocalEditIntent, timestamp: Instant,
            admit: (CalendarScope) -> Boolean): LocalTimeEditPreparation? {
            calls += "capsule"; check(intent == this@TimeOperationsFixture.intent && admitted(admit)); retainedLease = admit
            if (cancelHere("capsule")) return null
            return capsuleHook?.invoke(admit) ?: LocalTimeEditPreparation(intent, timestamp, preparation.format, preparation.protectedPayload)
        }
    }
    private val submissions = object : CalendarTimeUpdateSubmissionPort {
        override val timeUpdateFormat = wireFormat
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalTimeEditPreparation,
            admit: (CalendarScope) -> Boolean): TimeUpdateAttemptRecord? {
            calls += "wire"; check(admitted(admit)); check(id == TimeUpdateAttemptIdentity.requestId(preparation, wireFormat))
            if (cancelHere("wire")) return null
            val candidate = TimeUpdateAttemptRecord(id, preparation, wireFormat, "sealed-new-wire")
            return wireHook?.invoke(candidate) ?: candidate
        }
        override suspend fun submit(record: TimeUpdateAttemptRecord, journal: TimeUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): TimeUpdateSubmissionObservation {
            calls += "submit"; retainedLease = admit; evidenceLease = admitEvidence
            check(admitted(admit) && record == saved && qualifySource(scope)); check(journal.claim(record, admit))
            submitted = record.frozen()
            val unknown = saved!!
            return submitObservation?.invoke(unknown) ?: TimeUpdateSubmissionObservation.Verified(unknown, verified(unknown).event)
        }
        override suspend fun inspect(record: TimeUpdateAttemptRecord, journal: TimeUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean): TimeUpdateSubmissionObservation {
            calls += "inspect"; check(admitted(admit)); check(record == saved && record.state == TimeUpdateAttemptState.OUTCOME_UNKNOWN)
            retainedLease = admit
            return inspectHook?.invoke(admit) ?: TimeUpdateSubmissionObservation.Verified(record, verified(record).event)
        }
    }
    val operations = CoordinatorSystemTimeOperations(owners, journal, capsules, submissions,
        discoverLocal = {
            cancelHere("discover")
            error("Discovery was not requested by these direct owner/recovery tests")
        },
        evidenceAdmission = { target ->
            var open = sessionOpen
            val lease: (CalendarScope) -> Boolean = { requested ->
                if (!sessionOpen) open = false
                open && requested == target
            }
            lease
        })
    suspend fun process(timestamp: Instant = stamp, candidate: MutationId = MutationId("random-traversal-candidate")): Boolean =
        operations.process(scope, reference, candidate, timestamp, { it == scope && allowed }) { calls += "wake" }
}
