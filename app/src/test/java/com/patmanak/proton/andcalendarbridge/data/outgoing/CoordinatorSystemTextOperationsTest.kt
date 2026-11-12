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
class CoordinatorSystemTextOperationsTest {
    @Test fun existingOnlySkipsFreshPreparationButFinishesTheOriginalCapsule() = runBlocking {
        val f = TextOperationsFixture(existingOnly = true)
        assertFalse(f.process())
        assertEquals(listOf("observe"), f.calls)
        assertNull(f.saved)
        f.calls.clear()
        f.retained = f.preparation
        f.process()
        assertFalse("capsule" in f.calls)
        val retained = f.saved!!.preparation
        assertEquals(f.preparation.intent, retained.intent)
        assertEquals(f.preparation.timestamp, retained.timestamp)
        assertEquals(f.preparation.format, retained.format)
        assertEquals(f.preparation.protectedPayload, retained.protectedPayload)
        assertNotNull(f.completion)
        f.calls.clear()
        f.process()
        assertFalse("submit" in f.calls)
        assertFalse("capsule" in f.calls)
    }
    @Test fun preparedWorkRetainsProofThenWakesThenAcknowledgesAndCompletes() = runBlocking {
        val f = TextOperationsFixture()
        assertFalse(f.process())
        assertEquals(listOf("observe", "capsule", "retain-capsule", "qualify", "wire", "qualify", "remember",
            "submit", "qualify", "claim", "proof", "wake", "ack", "complete"), f.calls)
        assertEquals(TextUpdateAttemptState.OUTCOME_UNKNOWN, f.saved!!.state)
        assertNotNull(f.result); assertNotNull(f.completion)
        assertEquals(f.changed.description, f.saved!!.preparation.intent.content.description)
        assertEquals("Edited\nnotes", f.result!!.event.content.description)
    }

    @Test fun firstCapsuleDefinesRequestDespiteLaterTimestampAndRandomTraversalCandidate() = runBlocking {
        val f = TextOperationsFixture(); f.retained = f.preparation
        f.submitObservation = { TextUpdateSubmissionObservation.Unknown }
        f.process(timestamp = f.stamp.plusSeconds(90), candidate = MutationId("ignored-random-one"))
        val first = f.saved!!
        assertEquals(TextUpdateAttemptIdentity.requestId(f.preparation, f.wireFormat), first.id)
        assertEquals(f.stamp, first.preparation.timestamp)
        assertFalse("capsule" in f.calls)
        f.calls.clear()
        f.process(timestamp = f.stamp.plusSeconds(200), candidate = MutationId("ignored-random-two"))
        assertEquals(first, f.saved)
        assertFalse("wire" in f.calls); assertFalse("submit" in f.calls)
    }

    @Test fun concurrentRetentionUsesFirstCapsuleAndFirstWireWithoutReplacingEither() = runBlocking {
        val f = TextOperationsFixture()
        val winner = LocalTextEditPreparation(f.intent, f.stamp.minusSeconds(3), f.preparation.format, "first-capsule")
        f.retainHook = { winner }
        f.rememberHook = { proposed -> proposed.copy(protectedPayload = "first-wire-from-racer") }
        f.process()
        assertEquals("first-capsule", f.saved!!.preparation.protectedPayload)
        assertEquals(winner.timestamp, f.saved!!.preparation.timestamp)
        assertEquals(TextUpdateAttemptIdentity.requestId(winner, f.wireFormat), f.saved!!.id)
        assertEquals("first-wire-from-racer", f.submitted!!.protectedPayload)
        assertNotNull(f.completion)
    }

    @Test fun unknownWorkIsOnlyInspectedByRecoveryNeverPreparedClaimedOrResent() = runBlocking {
        val f = TextOperationsFixture(); f.seedUnknown()
        f.process()
        assertEquals(listOf("observe", "qualify"), f.calls)
        f.calls.clear()
        val progress = f.operations.inspect(f.query, { true }) { f.calls += "wake" }
        assertEquals(1, progress.completed)
        assertEquals(listOf("inspect", "proof", "wake", "ack", "complete"), f.calls)
    }

    @Test fun offlineDurableProofCompletesWithoutTransportAndAbsentProofIsHeld() = runBlocking {
        val f = TextOperationsFixture(); f.seedUnknown()
        val held = f.operations.recoverOffline(f.query) { true }
        assertEquals(1, held.held); assertTrue(f.calls.isEmpty())
        f.result = f.verified(f.saved!!)
        val recovered = f.operations.recoverOffline(f.query) { true }
        assertEquals(1, recovered.completed)
        assertEquals(listOf("ack", "complete"), f.calls)
    }

    @Test fun deniedAndNonTextWorkCannotReachPreparationOrTransport() = runBlocking {
        val denied = TextOperationsFixture(); denied.allowed = false
        denied.process(); assertTrue(denied.calls.isEmpty())
        val title = TextOperationsFixture()
        title.observedIntent = title.intent.copy(content = title.base.copy(title = "Title only"))
        title.process(); assertEquals(listOf("observe"), title.calls)
        val deletion = TextOperationsFixture()
        deletion.operations.process(deletion.scope, deletion.reference.copy(kind = MutationKind.DELETE),
            MutationId("unused"), deletion.stamp, { true }) { fail("Unexpected proof") }
        assertTrue(deletion.calls.isEmpty())
    }

    @Test fun scopedLeaseCannotReopenOrEscapeAfterPreparationReturns() = runBlocking {
        val f = TextOperationsFixture()
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
        val f = TextOperationsFixture(); f.process()
        assertFalse(f.retainedLease!!(f.scope)); assertFalse(f.evidenceLease!!(f.scope))
        assertFalse(f.evidenceLease!!(f.scope.copy(calendar = CalendarId("other-calendar"))))
    }

    @Test fun modeRevocationRetainsVerifiedProofAndWakesButDoesNotAcknowledge() = runBlocking {
        val f = TextOperationsFixture()
        f.submitObservation = { unknown ->
            f.allowed = false
            TextUpdateSubmissionObservation.Verified(unknown, f.verified(unknown).event)
        }
        f.process()
        assertNotNull(f.result); assertTrue("wake" in f.calls)
        assertFalse("ack" in f.calls); assertNull(f.completion)
        assertFalse(f.evidenceLease!!(f.scope)); assertFalse(f.retainedLease!!(f.scope))
    }

    @Test fun logoutRevokesEvidenceRetentionEvenWhenTheLateReplyIsVerified() = runBlocking {
        val f = TextOperationsFixture()
        f.submitObservation = { unknown ->
            f.allowed = false; f.sessionOpen = false
            TextUpdateSubmissionObservation.Verified(unknown, f.verified(unknown).event)
        }
        f.process()
        assertNull(f.result); assertNull(f.completion)
        assertFalse("proof" in f.calls); assertFalse("wake" in f.calls); assertFalse("ack" in f.calls)
        assertEquals(TextUpdateAttemptState.OUTCOME_UNKNOWN, f.saved!!.state)
        assertFalse(f.evidenceLease!!(f.scope))
    }

    @Test fun substitutedPreparationOrWireCannotBeDispatched() = runBlocking {
        for (variant in 0..3) {
            val f = TextOperationsFixture()
            when (variant) {
                0 -> f.capsuleHook = { LocalTextEditPreparation(f.intent, f.stamp.plusSeconds(1), f.preparation.format, "wrong-time") }
                1 -> f.wireHook = { it.copy(id = MutationId("wrong-request")) }
                2 -> f.wireHook = { it.copy(preparation = LocalTextEditPreparation(f.intent, f.stamp, f.preparation.format, "other-capsule")) }
                3 -> f.rememberHook = { it.copy(format = AdapterFormat("other-format", 1)) }
            }
            f.process()
            assertFalse("variant $variant dispatched", "submit" in f.calls)
            assertNull(f.result); assertNull(f.completion)
        }
    }

    @Test fun substitutedOwnerSnapshotCannotReachAuthenticatedPreparation() = runBlocking {
        for (variant in 0..2) {
            val f = TextOperationsFixture()
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
        val f = TextOperationsFixture(); f.seedUnknown()
        f.result = f.verified(f.saved!!.copy(protectedPayload = "substituted-wire"))
        val progress = f.operations.recoverOffline(f.query) { true }
        assertEquals(1, progress.failed); assertFalse("ack" in f.calls); assertNull(f.completion)
    }

    @Test fun failedProofRetentionCannotWakeOrAcknowledgeAndFailedAckCannotComplete() = runBlocking {
        val denied = TextOperationsFixture(); denied.acceptProof = false
        denied.process(); assertFalse("wake" in denied.calls); assertFalse("ack" in denied.calls)
        val noAck = TextOperationsFixture(); noAck.acceptAck = false
        noAck.process(); assertTrue("wake" in noAck.calls); assertTrue("ack" in noAck.calls)
        assertFalse("complete" in noAck.calls); assertNotNull(noAck.result)
    }

    @Test fun offlinePagePreservesItsCursorAndNeverInspectsUnknownTransport() = runBlocking {
        val f = TextOperationsFixture(); f.seedUnknown()
        f.pageRows = listOf(TextUpdateWorkRef(f.saved!!.id, f.saved!!.eventId, f.saved!!.state),
            TextUpdateWorkRef(MutationId("next-request"), EventId("next-event"), TextUpdateAttemptState.OUTCOME_UNKNOWN))
        val progress = f.operations.recoverOffline(TitleUpdateWorkQuery(f.scope, limit = 1)) { true }
        assertEquals(1, progress.held)
        assertEquals(TitleUpdateWorkCursor(f.scope, f.intent.base.eventId), progress.next)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun revocationDuringInspectionPreventsAckAndExpiresRecoveryCallback() = runBlocking {
        val f = TextOperationsFixture(); f.seedUnknown()
        f.inspectHook = { lease ->
            f.allowed = false; assertFalse(lease(f.scope))
            f.allowed = true; assertFalse(lease(f.scope))
            TextUpdateSubmissionObservation.Unknown
        }
        val progress = f.operations.inspect(f.query, { f.allowed }) { fail("Revoked proof wake") }
        assertTrue(progress.inactive); assertNull(progress.next)
        assertEquals(listOf("inspect"), f.calls)
        assertFalse(f.retainedLease!!(f.scope))
    }

    @Test fun cancellationReturnOrOrdinaryFailureIsClassifiedInsideChild() = runBlocking {
        for (throwOrdinary in listOf(false, true)) supervisorScope {
            val f = TextOperationsFixture(); var cancelled = 0; var ordinary = 0; var returned = 0
            f.capsuleHook = {
                currentCoroutineContext().cancel()
                if (throwOrdinary) throw IOException("synthetic cancellation")
                f.preparation
            }
            val task = async {
                try { f.process(); returned++ }
                catch (_: CancellationException) { cancelled++ }
                catch (_: Exception) { ordinary++ }
            }
            try { task.await() } catch (_: CancellationException) { }
            assertEquals(1, cancelled); assertEquals(0, ordinary); assertEquals(0, returned)
            assertFalse("remember" in f.calls); assertFalse("submit" in f.calls)
            assertFalse(f.retainedLease!!(f.scope))
        }
    }

    @Test fun cancelledObserveReturningNullCannotReturnNormally() = runBlocking {
        assertSilentCancellation("observe")
    }

    @Test fun cancelledCapsuleReturningNullCannotReturnNormally() = runBlocking {
        assertSilentCancellation("capsule")
    }

    @Test fun cancelledRetentionReturningNullCannotReturnNormally() = runBlocking {
        assertSilentCancellation("retain-capsule")
    }

    @Test fun cancelledWireReturningNullCannotReturnNormally() = runBlocking {
        assertSilentCancellation("wire")
    }

    @Test fun cancelledPageReturningNullCannotBecomeInactiveSuccess() = runBlocking {
        assertSilentCancellation("page", recovery = true)
    }

    @Test fun cancelledReadReturningNullCannotBecomeMissingHistory() = runBlocking {
        assertSilentCancellation("read", recovery = true)
    }

    @Test fun cancelledProofReturningFalseCannotReturnNormally() = runBlocking {
        assertSilentCancellation("proof", proofAccepted = false)
    }

    @Test fun cancelledProofReturningTrueCannotWakeOrAcknowledge() = runBlocking {
        assertSilentCancellation("proof", proofAccepted = true)
    }

    @Test fun cancelledInspectionProofReturningFalsePropagatesCancellation() = runBlocking {
        assertSilentCancellation("proof", recovery = true, proofAccepted = false)
    }

    @Test fun cancelledInspectionProofReturningTrueCannotWakeOrAcknowledge() = runBlocking {
        assertSilentCancellation("proof", recovery = true, proofAccepted = true)
    }

    @Test fun cancelledAcknowledgementReturningTrueCannotCompleteSubmission() = runBlocking {
        assertSilentCancellation("ack")
    }

    @Test fun cancelledOfflineAcknowledgementReturningTrueCannotCompleteHistory() = runBlocking {
        assertSilentCancellation("ack", recovery = true)
    }

    @Test fun discoveryCancellationFollowedByIOExceptionStillPropagatesCancellation() = runBlocking {
        assertCancelledIOException("discover")
    }

    @Test fun pageCancellationFollowedByIOExceptionStillPropagatesCancellation() = runBlocking {
        assertCancelledIOException("page")
    }

    private suspend fun assertCancelledIOException(stage: String) = supervisorScope {
        val f = TextOperationsFixture()
        f.cancelAt = stage; f.throwAfterCancellation = true
        var cancellations = 0; var ordinary = 0; var returns = 0
        val child = async {
            try {
                if (stage == "discover") f.operations.discover(RetainedLocalChangeQuery(f.scope))
                else f.operations.recoverOffline(f.query) { true }
                returns++
            } catch (_: CancellationException) { cancellations++ }
              catch (_: Exception) { ordinary++ }
        }
        try { child.await() } catch (_: CancellationException) { }
        assertEquals(1, f.cancellationHits)
        assertEquals("Cancellation must take precedence inside the child at $stage", 1, cancellations)
        assertEquals(0, ordinary); assertEquals(0, returns)
        assertNull(f.saved); assertNull(f.result); assertNull(f.completion)
        assertTrue(f.calls.isEmpty())
    }

    private suspend fun assertSilentCancellation(stage: String, recovery: Boolean = false,
        proofAccepted: Boolean = true) = supervisorScope {
        val f = TextOperationsFixture()
        f.cancelAt = stage; f.acceptProof = proofAccepted
        if (recovery) {
            f.seedUnknown()
            if (stage == "ack") f.result = f.verified(f.saved!!)
        }
        var cancellations = 0; var ordinary = 0; var returns = 0
        val child = async {
            try {
                when {
                    !recovery -> f.process()
                    stage == "proof" -> f.operations.inspect(f.query, { true }) { f.calls += "wake" }
                    else -> f.operations.recoverOffline(f.query) { true }
                }
                returns++
            } catch (_: CancellationException) { cancellations++ }
              catch (_: Exception) { ordinary++ }
        }
        try { child.await() } catch (_: CancellationException) { }
        assertEquals("Cancellation injection must be reached at $stage", 1, f.cancellationHits)
        if (stage == "proof") {
            assertFalse("No wake after cancelled proof retention", "wake" in f.calls)
            assertFalse("No ACK after cancelled proof retention", "ack" in f.calls)
        }
        assertFalse("No completion after cancellation at $stage", "complete" in f.calls)
        assertNull(f.completion)
        assertEquals("Cancellation must be observed inside the child at $stage", 1, cancellations)
        assertEquals(0, ordinary); assertEquals(0, returns)
    }
}

/** Strict single-owner in-memory boundary, deliberately independent of SQL/crypto implementation. */
internal class TextOperationsFixture(existingOnly: Boolean = false) {
    val scope = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 2)
    val stamp = Instant.parse("2033-03-20T09:00:00Z")
    val base = EventContent("Original title", EventTime.Timed(stamp.plusSeconds(3600), stamp.plusSeconds(7200),
        ZoneId.of("Europe/Paris")), "Original\r\nnotes", "Original\r\nroom", details = EventDetails())
    val changed = base.copy(title = "Edited title", description = "Edited\r\nnotes", location = "Edited room")
    val intent = LocalEditIntent(MutationId("fixture-owner"), scope, AdapterFormat("fixture-observation", 2),
        RemoteChange.Upsert(EventId("fixture-event"), Revision("fixture-base"), base), changed)
    val preparation = LocalTextEditPreparation(intent, stamp, AdapterFormat("fixture-capsule", 1), "sealed-capsule")
    val wireFormat = AdapterFormat("fixture-wire", 1)
    val reference = RetainedLocalChangeRef(intent.base.eventId, intent.id, MutationKind.UPDATE, LocalEditState.PAUSED)
    val query = TitleUpdateWorkQuery(scope)
    val calls = mutableListOf<String>()
    var allowed = true
    var sessionOpen = true
    var observedIntent = intent
    var retained: LocalTextEditPreparation? = null
    var saved: TextUpdateAttemptRecord? = null
    var submitted: TextUpdateAttemptRecord? = null
    var result: TextUpdateVerifiedResult? = null
    var completion: TextUpdateCompletion? = null
    var acceptProof = true
    var acceptAck = true
    var retainedLease: ((CalendarScope) -> Boolean)? = null
    var evidenceLease: ((CalendarScope) -> Boolean)? = null
    var capsuleHook: (suspend ((CalendarScope) -> Boolean) -> LocalTextEditPreparation)? = null
    var retainHook: ((LocalTextEditPreparation) -> LocalTextEditPreparation)? = null
    var wireHook: ((TextUpdateAttemptRecord) -> TextUpdateAttemptRecord)? = null
    var rememberHook: ((TextUpdateAttemptRecord) -> TextUpdateAttemptRecord)? = null
    var submitObservation: ((TextUpdateAttemptRecord) -> TextUpdateSubmissionObservation)? = null
    var inspectHook: (((CalendarScope) -> Boolean) -> TextUpdateSubmissionObservation)? = null
    var pageRows: List<TextUpdateWorkRef>? = null
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
    fun verified(record: TextUpdateAttemptRecord): TextUpdateVerifiedResult = TextUpdateVerifiedResult.of(record,
        TextUpdateSubmissionObservation.Verified(record, intent.base.copy(revision = Revision("fixture-result"),
            content = changed.copy(description = "Edited\nnotes"))))
    fun seedUnknown() {
        retained = preparation
        saved = TextUpdateAttemptRecord(TextUpdateAttemptIdentity.requestId(preparation, wireFormat), preparation,
            wireFormat, "sealed-first-wire", TextUpdateAttemptState.OUTCOME_UNKNOWN)
    }

    val journal = object : TextUpdateRecoveryJournal {
        override val completionProtocol = 1
        override suspend fun remember(record: TextUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "remember"; check(admitted(admit)); check(saved == null)
            saved = rememberHook?.invoke(record) ?: record.frozen(); return saved == record
        }
        override suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TextUpdateAttemptRecord? {
            check(scope == this@TextOperationsFixture.scope && admitted(admit))
            if (cancelHere("read")) return null
            return saved?.takeIf { it.id == id }?.frozen()
        }
        override suspend fun claim(expected: TextUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "claim"; check(admitted(admit)); check(saved == expected && expected.state == TextUpdateAttemptState.PREPARED)
            saved = expected.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN); return true
        }
        override suspend fun retainEvidence(expectedUnknown: TextUpdateAttemptRecord, protectedEvidence: String,
            admit: (CalendarScope) -> Boolean): Boolean = error("Unexpected evidence operation")
        override suspend fun retainVerified(result: TextUpdateVerifiedResult, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "proof"; check(admitted(admit)); check(result.record == saved!!.copy(protectedEvidence = null))
            if (acceptProof) this@TextOperationsFixture.result = result.snapshot()
            cancelHere("proof")
            return acceptProof
        }
        override suspend fun readVerified(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TextUpdateVerifiedResult? {
            check(scope == this@TextOperationsFixture.scope && admitted(admit)); check(id == saved!!.id); return result
        }
        override suspend fun retainCompletion(completion: TextUpdateCompletion, admit: (CalendarScope) -> Boolean): Boolean {
            check(admitted(admit)); check(completion.result == result); this@TextOperationsFixture.completion = completion; return true
        }
        override suspend fun readCompletion(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TextUpdateCompletion? {
            check(scope == this@TextOperationsFixture.scope && admitted(admit)); check(id == saved!!.id); return completion
        }
        override suspend fun page(query: TextUpdateWorkQuery, admit: (CalendarScope) -> Boolean): TextUpdateWorkPage? {
            check(query.scope == scope && admitted(admit))
            if (cancelHere("page")) return null
            return TextUpdateWorkPage.fromOrderedMetadata(query, pageRows ?: listOfNotNull(saved?.let { TextUpdateWorkRef(it.id, it.eventId, it.state) }))
        }
    }
    private val owners = object : SystemTextLocalOwner {
        override suspend fun observe(scope: CalendarScope, event: EventId, owner: MutationId,
            admit: (CalendarScope) -> Boolean): TextLocalOwnerSnapshot? {
            calls += "observe"; check(scope == this@TextOperationsFixture.scope && event == intent.base.eventId && owner == intent.id)
            check(admitted(admit))
            if (cancelHere("observe")) return null
            return TextLocalOwnerSnapshot(observedIntent, retained)
        }
        override suspend fun retain(preparation: LocalTextEditPreparation, admit: (CalendarScope) -> Boolean): LocalTextEditPreparation? {
            calls += "retain-capsule"; check(admitted(admit))
            if (cancelHere("retain-capsule")) return null
            retained = retainHook?.invoke(preparation) ?: preparation; return retained
        }
        override suspend fun qualify(preparation: LocalTextEditPreparation, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "qualify"; check(preparation.intent == intent); return admitted(admit)
        }
        override suspend fun submit(record: TextUpdateAttemptRecord, journal: TextUpdateRecoveryJournal,
            port: CalendarTextUpdateSubmissionPort, admit: (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation =
            port.submit(record, journal, admit, { requested -> requested == scope && qualify(record.preparation, admit) }, admitEvidence)
        override suspend fun acknowledge(result: TextUpdateVerifiedResult, journal: TextUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            calls += "ack"; check(admitted(admit)); check(this@TextOperationsFixture.result == result)
            if (cancelHere("ack")) return true
            return acceptAck
        }
        override suspend fun complete(result: TextUpdateVerifiedResult, journal: TextUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            calls += "complete"; check(acceptAck && admitted(admit))
            val receipt = TextUpdateAcknowledgementReceipt.of(result, "00000000-0000-0000-0000-000000000099")
            return journal.retainCompletion(TextUpdateCompletion.of(result,
                TextUpdateAcknowledgementRecord(receipt, TextUpdateAcknowledgementState.RESTORED)), admit)
        }
    }
    private val capsules = object : CalendarLocalTextPreparationPort {
        override val localTextFormat = preparation.format
        override suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: Instant): LocalTextEditPreparation =
            error("Unguarded preparation forbidden")
        override suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: Instant,
            admit: (CalendarScope) -> Boolean): LocalTextEditPreparation? {
            calls += "capsule"; check(intent == this@TextOperationsFixture.intent && admitted(admit)); retainedLease = admit
            if (cancelHere("capsule")) return null
            return capsuleHook?.invoke(admit) ?: LocalTextEditPreparation(intent, timestamp, preparation.format, preparation.protectedPayload)
        }
    }
    private val submissions = object : CalendarTextUpdateSubmissionPort {
        override val textUpdateFormat = wireFormat
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalTextEditPreparation,
            admit: (CalendarScope) -> Boolean): TextUpdateAttemptRecord? {
            calls += "wire"; check(admitted(admit)); check(id == TextUpdateAttemptIdentity.requestId(preparation, wireFormat))
            if (cancelHere("wire")) return null
            val candidate = TextUpdateAttemptRecord(id, preparation, wireFormat, "sealed-new-wire")
            return wireHook?.invoke(candidate) ?: candidate
        }
        override suspend fun submit(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation {
            calls += "submit"; retainedLease = admit; evidenceLease = admitEvidence
            check(admitted(admit) && record == saved && qualifySource(scope)); check(journal.claim(record, admit))
            submitted = record.frozen()
            val unknown = saved!!
            return submitObservation?.invoke(unknown) ?: TextUpdateSubmissionObservation.Verified(unknown, verified(unknown).event)
        }
        override suspend fun inspect(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation {
            calls += "inspect"; check(admitted(admit)); check(record == saved && record.state == TextUpdateAttemptState.OUTCOME_UNKNOWN)
            retainedLease = admit
            return inspectHook?.invoke(admit) ?: TextUpdateSubmissionObservation.Verified(record, verified(record).event)
        }
    }
    val operations = CoordinatorSystemTextOperations(owners, journal, capsules, submissions,
        discoverLocal = {
            cancelHere("discover")
            error("Discovery was not requested by these direct owner/recovery tests")
        },
        existingOnly = existingOnly, evidenceAdmission = { target ->
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
