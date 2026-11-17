// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Host orchestration only; fake boundaries do not qualify SQL, Provider or service absence. */
class CoordinatorSystemDeleteOperationsTest {
    @Test fun preparedDeletionReservesBeforeSubmissionAndRetainsResultBeforeAckAndCompletion() = runBlocking {
        val f = DeleteOperationsFixture()
        assertFalse(f.process())
        assertEquals(listOf("observe", "prepare", "remember", "reserve", "submit", "qualify", "claim",
            "proof", "wake", "ack", "complete"), f.calls)
        assertEquals(DeleteAttemptState.OUTCOME_UNKNOWN, f.saved!!.state)
        assertNotNull(f.result); assertNotNull(f.completion)
        assertEquals(DeleteAcknowledgementState.RESTORED, f.acknowledgement!!.state)
        assertEquals(f.intent, f.completion!!.receipt.intent)
    }

    @Test fun existingPreparedWireIsReusedWithoutPreparationAndReservationFailurePreventsSend() = runBlocking {
        val f = DeleteOperationsFixture(); f.seed(DeleteAttemptState.PREPARED)
        val first = f.saved!!
        f.process()
        assertFalse("prepare" in f.calls); assertFalse("remember" in f.calls)
        assertEquals(first, f.submitted)
        val denied = DeleteOperationsFixture(); denied.acceptReservation = false
        denied.process()
        assertTrue("reserve" in denied.calls); assertFalse("submit" in denied.calls)
        assertEquals(DeleteAttemptState.PREPARED, denied.saved!!.state)
    }

    @Test fun unknownHistoryIsNeverPreparedReservedClaimedOrResent() = runBlocking {
        val f = DeleteOperationsFixture(); f.seed()
        val unknown = f.saved!!
        f.process(); f.process()
        assertEquals(listOf("observe", "observe"), f.calls)
        assertEquals(unknown, f.saved)
        f.calls.clear()
        val progress = f.operations.inspect(f.query, { true }) { f.calls += "wake" }
        assertEquals(1, progress.completed)
        assertEquals(listOf("inspect", "proof", "wake", "ack", "complete"), f.calls)
        assertEquals(unknown, f.saved)
    }

    @Test fun newlyUnknownSubmissionFailsOnlyThePassThatSentIt() = runBlocking {
        val f = DeleteOperationsFixture()
        f.submitObservation = { DeleteSubmissionObservation.Unknown }
        assertTrue(f.process())
        assertEquals(DeleteAttemptState.OUTCOME_UNKNOWN, f.saved!!.state)
        assertFalse(f.process())
        f.inspectObservation = { DeleteSubmissionObservation.Unknown }
        val progress = f.operations.inspect(f.query, { true }) { fail("No proof") }
        assertEquals(1, progress.held); assertEquals(0, progress.failed)
        assertEquals(1, f.calls.count { it == "submit" })
    }

    @Test fun preparedAndUnknownRemoteConflictsRetainObservedVariantWithoutNativeAck() = runBlocking {
        for (recovery in listOf(false, true)) {
            val f = DeleteOperationsFixture()
            val changed = f.intent.base.copy(revision = Revision("remote-new"),
                content = f.intent.content.copy(title = "Remote variant"))
            if (recovery) {
                f.seed()
                f.inspectObservation = { DeleteSubmissionObservation.Conflict(it, changed) }
                val progress = f.operations.inspect(f.query, { true }) { fail("Conflict is not absence") }
                assertEquals(1, progress.held)
            } else {
                f.submitObservation = { DeleteSubmissionObservation.Conflict(it, changed) }
                assertFalse(f.process())
            }
            assertEquals(changed, f.conflict)
            assertTrue("conflict" in f.calls); assertFalse("proof" in f.calls)
            assertFalse("ack" in f.calls); assertNull(f.completion)
        }
    }

    @Test fun offlineUnknownIsHeldAndDurableResultResumesAckWithoutAnyTransport() = runBlocking {
        val f = DeleteOperationsFixture(); f.seed()
        val held = f.operations.recoverOffline(f.query) { true }
        assertEquals(1, held.held); assertTrue(f.calls.isEmpty())
        f.result = f.verified(f.saved!!)
        val recovered = f.operations.recoverOffline(f.query) { true }
        assertEquals(1, recovered.completed)
        assertEquals(listOf("ack", "complete"), f.calls)
        assertNotNull(f.completion)
    }

    @Test fun foreignConflictRecordCannotDonateVariantDuringSubmissionOrInspection() = runBlocking {
        for (recovery in listOf(false, true)) for (variant in 0..3) {
            val f = DeleteOperationsFixture()
            val changed = f.intent.base.copy(revision = Revision("foreign-remote"),
                content = f.intent.content.copy(title = "Foreign conflict"))
            val observation: (DeleteAttemptRecord) -> DeleteSubmissionObservation = { record ->
                val foreign = when (variant) {
                    0 -> record.copy(id = MutationId("foreign-request"))
                    1 -> record.copy(protectedPayload = "foreign-wire")
                    2 -> record.copy(intent = record.intent.copy(id = MutationId("foreign-owner")))
                    else -> record.copy(intent = record.intent.copy(scope = f.scope.copy(generation = 9)))
                }
                DeleteSubmissionObservation.Conflict(foreign, changed)
            }
            if (recovery) {
                f.seed(); f.inspectObservation = observation
                f.operations.inspect(f.query, { true }) { fail("Foreign conflict is not proof") }
            } else {
                f.submitObservation = observation; f.process()
            }
            assertNull("Foreign variant $variant, recovery=$recovery", f.conflict)
            assertFalse("conflict" in f.calls); assertFalse("ack" in f.calls)
            assertNull(f.result); assertNull(f.completion)
        }
    }

    @Test fun failedProofOrAckCannotCompleteAndDurableProofSurvivesAckFailure() = runBlocking {
        val noProof = DeleteOperationsFixture(); noProof.acceptProof = false
        assertTrue(noProof.process())
        assertFalse("wake" in noProof.calls); assertFalse("ack" in noProof.calls)
        assertNull(noProof.result); assertNull(noProof.completion)
        val noAck = DeleteOperationsFixture(); noAck.acceptAck = false
        noAck.process()
        assertNotNull(noAck.result); assertTrue("wake" in noAck.calls)
        assertFalse("complete" in noAck.calls); assertNull(noAck.completion)
        noAck.calls.clear(); noAck.acceptAck = true
        assertEquals(1, noAck.operations.recoverOffline(noAck.query) { true }.completed)
        assertEquals(listOf("ack", "complete"), noAck.calls)
    }

    @Test fun inspectionReportsFailureWhenItCannotStoreVerifiedEvidence() = runBlocking {
        val f = DeleteOperationsFixture(); f.seed(); f.acceptProof = false
        val progress = f.operations.inspect(f.query, { true }) { fail("No retained proof") }
        assertEquals(1, progress.failed); assertEquals(0, progress.completed)
        assertFalse("submit" in f.calls); assertFalse("ack" in f.calls)
    }

    @Test fun revokedModeKeepsLateEvidenceButRevokedSessionCannotRetainIt() = runBlocking {
        for (logout in listOf(false, true)) {
            val f = DeleteOperationsFixture()
            f.submitObservation = {
                f.allowed = false
                if (logout) f.sessionOpen = false
                DeleteSubmissionObservation.Verified(it)
            }
            f.process()
            assertEquals(!logout, f.result != null)
            assertEquals(!logout, "wake" in f.calls)
            assertFalse("ack" in f.calls); assertNull(f.completion)
            assertEquals(DeleteAttemptState.OUTCOME_UNKNOWN, f.saved!!.state)
            assertFalse(f.sendLease!!(f.scope)); assertFalse(f.evidenceLease!!(f.scope))
        }
    }

    @Test fun deniedOrWrongKindWorkNeverObservesOwner() = runBlocking {
        val f = DeleteOperationsFixture(); f.allowed = false
        f.process(); assertTrue(f.calls.isEmpty())
        f.allowed = true
        f.operations.process(f.scope, f.reference.copy(kind = MutationKind.UPDATE), MutationId("unused"),
            f.stamp, { true }) { fail("Unexpected proof") }
        assertTrue(f.calls.isEmpty())
    }

    @Test fun foreignOwnerAndSubstitutedWireAreRejectedBeforeReservationOrSubmit() = runBlocking {
        for (variant in 0..5) {
            val f = DeleteOperationsFixture()
            when (variant) {
                0 -> f.observed = f.intent.copy(scope = f.scope.copy(generation = 3))
                1 -> f.observed = f.intent.copy(id = MutationId("other-owner"))
                2 -> f.observed = f.intent.copy(base = f.intent.base.copy(eventId = EventId("other-event")))
                3 -> f.prepareHook = { it.copy(id = MutationId("other-request")) }
                4 -> f.prepareHook = { it.copy(format = AdapterFormat("other-wire", 1)) }
                5 -> f.prepareHook = { it.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN) }
            }
            f.process()
            assertFalse("Variant $variant reserved", "reserve" in f.calls)
            assertFalse("Variant $variant submitted", "submit" in f.calls)
            assertNull(f.saved)
        }
    }

    @Test fun scopedAdmissionCannotEscapeReopenOrSurviveOperation() = runBlocking {
        val f = DeleteOperationsFixture()
        f.prepareAdmissionHook = { lease ->
            assertFalse(lease(f.scope.copy(calendar = CalendarId("foreign-calendar"))))
            assertTrue(lease(f.scope))
            f.allowed = false; assertFalse(lease(f.scope))
            f.allowed = true; assertFalse(lease(f.scope))
        }
        f.process()
        assertFalse("remember" in f.calls); assertFalse("submit" in f.calls)
        assertFalse(f.sendLease!!(f.scope))
        val successful = DeleteOperationsFixture(); successful.process()
        assertFalse(successful.sendLease!!(successful.scope))
        assertFalse(successful.evidenceLease!!(successful.scope))
    }

    @Test fun recoveryRevocationClosesLeaseAndDiscardsContinuation() = runBlocking {
        val f = DeleteOperationsFixture(); f.seed()
        f.inspectAdmissionHook = { lease ->
            f.allowed = false; assertFalse(lease(f.scope))
            f.allowed = true; assertFalse(lease(f.scope))
        }
        val progress = f.operations.inspect(f.query, { f.allowed }) { fail("Revoked proof") }
        assertTrue(progress.inactive); assertNull(progress.next)
        assertEquals(listOf("inspect"), f.calls)
        assertFalse(f.sendLease!!(f.scope))
    }

    @Test fun offlinePageCursorIsForwardedWithoutInspectingOrPreparingRequests() = runBlocking {
        val f = DeleteOperationsFixture(); f.seed()
        f.pageRows = listOf(DeleteWorkRef(f.saved!!.id, f.saved!!.eventId, f.saved!!.state),
            DeleteWorkRef(MutationId("next-request"), EventId("next-event"), DeleteAttemptState.OUTCOME_UNKNOWN))
        val progress = f.operations.recoverOffline(TitleUpdateWorkQuery(f.scope, limit = 1)) { true }
        assertEquals(1, progress.held)
        assertEquals(TitleUpdateWorkCursor(f.scope, f.intent.base.eventId), progress.next)
        assertTrue(f.calls.isEmpty())
        f.pageRows = emptyList()
        val next = f.operations.recoverOffline(TitleUpdateWorkQuery(f.scope, 1, progress.next)) { true }
        assertNull(next.next)
        assertEquals(DeleteWorkCursor(f.scope, f.intent.base.eventId), f.lastPageQuery!!.cursor)
    }

    @Test fun proofForAnotherWireFailsClosedBeforeAcknowledgement() = runBlocking {
        val f = DeleteOperationsFixture(); f.seed()
        f.result = f.verified(f.saved!!.copy(protectedPayload = "foreign-wire"))
        val progress = f.operations.recoverOffline(f.query) { true }
        assertEquals(1, progress.failed); assertFalse("ack" in f.calls); assertNull(f.completion)
    }

    @Test fun cancellationAtEachBoundaryRemainsCancellationAndCannotContinue() = runBlocking {
        for (stage in listOf("observe", "prepare", "remember", "reserve", "submit", "claim", "proof", "ack", "page", "inspect")) {
            supervisorScope {
                val f = DeleteOperationsFixture(); f.cancelAt = stage
                val recovery = stage in listOf("page", "inspect")
                if (recovery) f.seed()
                val work = async {
                    if (recovery) f.operations.inspect(f.query, { true }) { f.calls += "wake" } else f.process()
                }
                try { work.await(); fail("Cancellation swallowed at $stage") }
                catch (_: CancellationException) { /* Expected, including cancellation masked by IO failure. */ }
                assertTrue(work.isCancelled)
                assertNull("Completed after cancellation at $stage", f.completion)
                if (stage == "claim") assertEquals(DeleteAttemptState.OUTCOME_UNKNOWN, f.saved!!.state)
                f.sendLease?.let { assertFalse(it(f.scope)) }
                f.evidenceLease?.let { assertFalse(it(f.scope)) }
            }
        }
    }
}

/** Independent single-owner journal with immutable first request/result/completion semantics. */
private class DeleteOperationsFixture {
    val scope = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 2)
    val stamp = Instant.parse("2033-03-20T09:00:00Z")
    private val content = EventContent("Original title", EventTime.Timed(stamp.plusSeconds(3600),
        stamp.plusSeconds(7200), ZoneId.of("Europe/Paris")), "Raw\r\nnotes", "Room", details = EventDetails())
    val intent = LocalDeleteIntent(MutationId("fixture-owner"), scope, AdapterFormat("fixture-observation", 2),
        RemoteChange.Upsert(EventId("fixture-event"), Revision("fixture-base"), content), content)
    private val wireFormat = AdapterFormat("fixture-delete-wire", 1)
    val reference = RetainedLocalChangeRef(intent.base.eventId, intent.id, MutationKind.DELETE, LocalEditState.PAUSED)
    val query = TitleUpdateWorkQuery(scope)
    val calls = mutableListOf<String>()
    var allowed = true
    var sessionOpen = true
    var observed = intent
    var saved: DeleteAttemptRecord? = null
    var submitted: DeleteAttemptRecord? = null
    var result: DeleteVerifiedResult? = null
    var completion: DeleteCompletion? = null
    var acknowledgement: DeleteAcknowledgementRecord? = null
    var conflict: RemoteChange.Upsert? = null
    var acceptReservation = true
    var acceptProof = true
    var acceptAck = true
    var sendLease: ((CalendarScope) -> Boolean)? = null
    var evidenceLease: ((CalendarScope) -> Boolean)? = null
    var prepareHook: ((DeleteAttemptRecord) -> DeleteAttemptRecord)? = null
    var prepareAdmissionHook: (((CalendarScope) -> Boolean) -> Unit)? = null
    var inspectAdmissionHook: (((CalendarScope) -> Boolean) -> Unit)? = null
    var submitObservation: ((DeleteAttemptRecord) -> DeleteSubmissionObservation)? = null
    var inspectObservation: ((DeleteAttemptRecord) -> DeleteSubmissionObservation)? = null
    var pageRows: List<DeleteWorkRef>? = null
    var lastPageQuery: DeleteWorkQuery? = null
    var cancelAt: String? = null
    private var reserved: DeleteAttemptIdentity? = null
    private suspend fun boundary(stage: String) {
        if (cancelAt == stage) {
            currentCoroutineContext().cancel()
            throw IOException("Synthetic boundary failure after cancellation")
        }
    }
    fun verified(record: DeleteAttemptRecord) = DeleteVerifiedResult.of(record, DeleteSubmissionObservation.Verified(record))
    fun seed(state: DeleteAttemptState = DeleteAttemptState.OUTCOME_UNKNOWN) {
        saved = DeleteAttemptRecord(DeleteAttemptIdentity.requestId(intent, wireFormat), intent, wireFormat, "first-wire", state)
    }
    private fun admitted(admit: (CalendarScope) -> Boolean) = admit(scope)

    val journal = object : DeleteRecoveryJournal {
        override val completionProtocol = 1
        override suspend fun remember(record: DeleteAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "remember"; check(admitted(admit)); boundary("remember")
            check(record.state == DeleteAttemptState.PREPARED)
            if (saved == null) saved = record.frozen()
            return saved == record
        }
        override suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): DeleteAttemptRecord? {
            if (scope != this@DeleteOperationsFixture.scope || !admit(scope)) return null
            return saved?.takeIf { it.id == id }?.frozen()
        }
        override suspend fun claim(expected: DeleteAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "claim"
            if (!admitted(admit) || saved != expected || expected.state != DeleteAttemptState.PREPARED) return false
            saved = expected.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN)
            boundary("claim"); return true
        }
        override suspend fun retainEvidence(expectedUnknown: DeleteAttemptRecord, protectedEvidence: String,
            admit: (CalendarScope) -> Boolean): Boolean {
            if (!admitted(admit) || expectedUnknown.state != DeleteAttemptState.OUTCOME_UNKNOWN ||
                saved?.copy(protectedEvidence = null) != expectedUnknown.copy(protectedEvidence = null)) return false
            if (saved!!.protectedEvidence == null) saved = saved!!.copy(protectedEvidence = protectedEvidence)
            return saved!!.protectedEvidence == protectedEvidence
        }
        override suspend fun retainVerified(result: DeleteVerifiedResult, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "proof"; check(admitted(admit)); check(result.record == saved!!.copy(protectedEvidence = null))
            boundary("proof")
            if (!acceptProof) return false
            if (this@DeleteOperationsFixture.result == null) this@DeleteOperationsFixture.result = result.snapshot()
            return this@DeleteOperationsFixture.result == result
        }
        override suspend fun readVerified(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): DeleteVerifiedResult? {
            if (scope != this@DeleteOperationsFixture.scope || !admit(scope) || saved?.id != id) return null
            return result?.snapshot()
        }
        override suspend fun retainCompletion(completion: DeleteCompletion, admit: (CalendarScope) -> Boolean): Boolean {
            check(admitted(admit)); check(completion.result == result)
            check(acknowledgement?.state == DeleteAcknowledgementState.RESTORED)
            if (this@DeleteOperationsFixture.completion == null) this@DeleteOperationsFixture.completion = completion.snapshot()
            return this@DeleteOperationsFixture.completion == completion
        }
        override suspend fun readCompletion(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): DeleteCompletion? {
            if (scope != this@DeleteOperationsFixture.scope || !admit(scope) || saved?.id != id) return null
            return completion?.snapshot()
        }
        override suspend fun page(query: DeleteWorkQuery, admit: (CalendarScope) -> Boolean): DeleteWorkPage? {
            lastPageQuery = query; check(query.scope == scope)
            if (!admitted(admit)) return null
            boundary("page")
            val rows = pageRows ?: if (completion == null) listOfNotNull(saved?.let {
                DeleteWorkRef(it.id, it.eventId, it.state)
            }) else emptyList()
            return DeleteWorkPage.fromOrderedMetadata(query, rows)
        }
    }
    private val owners = object : SystemDeleteLocalOwner {
        override suspend fun observe(scope: CalendarScope, event: EventId, owner: MutationId,
            admit: (CalendarScope) -> Boolean): LocalDeleteIntent? {
            calls += "observe"; check(scope == this@DeleteOperationsFixture.scope)
            check(event == intent.base.eventId && owner == intent.id && admitted(admit)); sendLease = admit
            boundary("observe"); return observed
        }
        override suspend fun reserve(record: DeleteAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "reserve"; check(admitted(admit) && saved == record); boundary("reserve")
            if (!acceptReservation) return false
            reserved = DeleteAttemptIdentity.of(record); return true
        }
        override suspend fun submit(record: DeleteAttemptRecord, journal: DeleteRecoveryJournal,
            port: CalendarDeleteSubmissionPort, admit: (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): DeleteSubmissionObservation =
            port.submit(record, journal, admit, { requested ->
                calls += "qualify"; requested == scope && admitted(admit) && reserved == DeleteAttemptIdentity.of(record)
            }, admitEvidence)
        override suspend fun conflict(intent: LocalDeleteIntent, observed: RemoteChange.Upsert,
            admit: (CalendarScope) -> Boolean): Boolean {
            calls += "conflict"; check(admitted(admit) && intent == this@DeleteOperationsFixture.intent)
            conflict = observed; return true
        }
        override suspend fun acknowledge(result: DeleteVerifiedResult, journal: DeleteRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            calls += "ack"; check(admitted(admit) && this@DeleteOperationsFixture.result == result); boundary("ack")
            if (!acceptAck) return false
            val receipt = DeleteAcknowledgementReceipt.of(DeleteSubmissionObservation.Verified(result.record),
                "00000000-0000-0000-0000-000000000099")
            acknowledgement = DeleteAcknowledgementRecord(receipt, DeleteAcknowledgementState.RESTORED); return true
        }
        override suspend fun complete(result: DeleteVerifiedResult, journal: DeleteRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            calls += "complete"; check(admitted(admit)); check(acknowledgement != null)
            return journal.retainCompletion(DeleteCompletion.of(result, acknowledgement!!), admit)
        }
    }
    private val submissions = object : CalendarDeleteSubmissionPort {
        override val deleteFormat = wireFormat
        override suspend fun prepareSubmission(id: MutationId, intent: LocalDeleteIntent,
            admit: (CalendarScope) -> Boolean): DeleteAttemptRecord? {
            calls += "prepare"; check(admitted(admit) && intent == this@DeleteOperationsFixture.intent); sendLease = admit
            boundary("prepare"); prepareAdmissionHook?.invoke(admit)
            val record = DeleteAttemptRecord(id, intent, wireFormat, "prepared-wire")
            return prepareHook?.invoke(record) ?: record
        }
        override suspend fun submit(record: DeleteAttemptRecord, journal: DeleteAttemptJournal,
            admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): DeleteSubmissionObservation {
            calls += "submit"; sendLease = admit; evidenceLease = admitEvidence
            check(admitted(admit) && saved == record && reserved == DeleteAttemptIdentity.of(record))
            boundary("submit"); check(qualifySource(scope)); check(journal.claim(record, admit))
            submitted = record.frozen()
            return submitObservation?.invoke(saved!!) ?: DeleteSubmissionObservation.Verified(saved!!)
        }
        override suspend fun inspect(record: DeleteAttemptRecord, journal: DeleteAttemptJournal,
            admit: (CalendarScope) -> Boolean): DeleteSubmissionObservation {
            calls += "inspect"; check(admitted(admit) && record == saved && record.state == DeleteAttemptState.OUTCOME_UNKNOWN)
            sendLease = admit; boundary("inspect"); inspectAdmissionHook?.invoke(admit)
            return if (!admitted(admit)) DeleteSubmissionObservation.Unknown
                else inspectObservation?.invoke(record) ?: DeleteSubmissionObservation.Verified(record)
        }
    }
    val operations = CoordinatorSystemDeleteOperations(owners, journal, submissions,
        discoverLocal = { error("Direct orchestration tests do not discover native changes") },
        evidenceAdmission = { target -> { requested -> sessionOpen && requested == target } })
    suspend fun process() = operations.process(scope, reference, MutationId("ignored-traversal-id"), stamp,
        { it == scope && allowed }) { calls += "wake" }
}
