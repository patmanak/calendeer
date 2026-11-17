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
class CoordinatorSystemPersonalOperationsTest {
    @Test fun bareSourceRefusalIsReportedUnderTheOriginalLeasesWithoutPreparation() = runBlocking {
        val f = PersonalOperationsFixture()
        f.observedIntent = f.intent.copy(state = LocalEditState.CONFLICT)
        var reports = 0
        f.unsupportedHook = { reference, admit, consent ->
            reports++; assertEquals(f.reference, reference)
            assertTrue(admit(f.scope)); assertTrue(consent(f.intent)); true
        }
        assertFalse(f.process()); assertEquals(1, reports)
        assertEquals(listOf("observe"), f.calls); assertNull(f.saved)
    }

    @Test fun unreportedTransientSourceFailureRequestsRetryThenPersonalCanComplete() = runBlocking {
        val f = PersonalOperationsFixture()
        f.observedIntent = f.intent.copy(state = LocalEditState.CONFLICT)
        assertTrue(f.process()); assertNull(f.saved)
        f.observedIntent = f.intent
        assertFalse(f.process()); assertNotNull(f.completion)
        assertEquals(1, f.calls.count { it == "submit" })
    }
    @Test fun permanentPreparationRefusalHoldsWithoutSchedulingNetworkBackoff() = runBlocking {
        for (reason in listOf(LocalEditPreparationFailure.UNSUPPORTED, LocalEditPreparationFailure.CONFLICT,
            LocalEditPreparationFailure.PERMISSION, LocalEditPreparationFailure.AUTHENTICATION, LocalEditPreparationFailure.NOT_FOUND)) {
            val fixture = PersonalOperationsFixture()
            fixture.capsuleHook = { throw LocalEditPreparationException(reason) }
            assertFalse("Permanent refusal must not request a transient retry: $reason", fixture.process())
            assertEquals(listOf("observe", "capsule"), fixture.calls)
            assertNull(fixture.saved)
        }
    }

    @Test fun transientPreparationFailureRequestsBackoffWithoutClaimingAnything() = runBlocking {
        for (reason in listOf(LocalEditPreparationFailure.NETWORK, LocalEditPreparationFailure.RETRY,
            LocalEditPreparationFailure.STORAGE, LocalEditPreparationFailure.LOCAL_KEYS, LocalEditPreparationFailure.CONTEXT)) {
            val fixture = PersonalOperationsFixture()
            fixture.capsuleHook = { throw LocalEditPreparationException(reason) }
            assertTrue(fixture.process())
            assertEquals(listOf("observe", "capsule"), fixture.calls)
            assertNull(fixture.saved)
        }
    }

    @Test fun preparedWorkRetainsProofThenWakesThenAcknowledgesAndCompletes() = runBlocking {
        val f = PersonalOperationsFixture()
        assertFalse(f.process())
        assertEquals(listOf("observe", "capsule", "retain-capsule", "qualify", "wire", "qualify", "remember",
            "submit", "qualify", "claim", "proof", "wake", "ack", "complete"), f.calls)
        assertEquals(PersonalUpdateAttemptState.OUTCOME_UNKNOWN, f.saved!!.state)
        assertNotNull(f.result); assertNotNull(f.completion)
        assertEquals(f.changed.description, f.saved!!.preparation.intent.content.description)
        assertEquals("Original\nnotes", f.result!!.event.content.description)
    }

    @Test fun firstCapsuleDefinesRequestDespiteLaterTimestampAndRandomTraversalCandidate() = runBlocking {
        val f = PersonalOperationsFixture(); f.retained = f.preparation
        f.submitObservation = { PersonalUpdateSubmissionObservation.Unknown }
        f.process(timestamp = f.stamp.plusSeconds(90), candidate = MutationId("ignored-random-one"))
        val first = f.saved!!
        assertEquals(PersonalUpdateAttemptIdentity.requestId(f.preparation, f.wireFormat), first.id)
        assertEquals(f.stamp, first.preparation.timestamp)
        assertFalse("capsule" in f.calls)
        f.calls.clear()
        f.process(timestamp = f.stamp.plusSeconds(200), candidate = MutationId("ignored-random-two"))
        assertEquals(first, f.saved)
        assertFalse("wire" in f.calls); assertFalse("submit" in f.calls)
    }

    @Test fun concurrentRetentionUsesFirstCapsuleAndFirstWireWithoutReplacingEither() = runBlocking {
        val f = PersonalOperationsFixture()
        val winner = PersonalEditPreparation(f.intent, f.stamp.minusSeconds(3), f.preparation.format, "first-capsule")
        f.retainHook = { winner }
        f.rememberHook = { proposed -> proposed.copy(protectedPayload = "first-wire-from-racer") }
        f.process()
        assertEquals("first-capsule", f.saved!!.preparation.protectedPayload)
        assertEquals(winner.timestamp, f.saved!!.preparation.timestamp)
        assertEquals(PersonalUpdateAttemptIdentity.requestId(winner, f.wireFormat), f.saved!!.id)
        assertEquals("first-wire-from-racer", f.submitted!!.protectedPayload)
        assertNotNull(f.completion)
    }

    @Test fun unknownWorkIsOnlyInspectedByRecoveryNeverPreparedClaimedOrResent() = runBlocking {
        val f = PersonalOperationsFixture(); f.seedUnknown()
        f.process()
        assertEquals(listOf("observe", "qualify"), f.calls)
        f.calls.clear()
        val progress = f.operations.inspect(f.query, { true }) { f.calls += "wake" }
        assertEquals(1, progress.completed)
        assertEquals(listOf("inspect", "proof", "wake", "ack", "complete"), f.calls)
    }

    @Test fun newlyUnknownSubmissionFailsOnlyThePassThatSentIt() = runBlocking {
        val f = PersonalOperationsFixture()
        f.submitObservation = { PersonalUpdateSubmissionObservation.Unknown }
        assertTrue(f.process())
        assertEquals(PersonalUpdateAttemptState.OUTCOME_UNKNOWN, f.saved!!.state)
        assertFalse(f.process())
        f.inspectHook = { PersonalUpdateSubmissionObservation.Unknown }
        val progress = f.operations.inspect(f.query, { true }) { fail("No proof") }
        assertEquals(1, progress.held); assertEquals(0, progress.failed)
        assertEquals(1, f.calls.count { it == "submit" })
    }

    @Test fun offlineDurableProofCompletesWithoutTransportAndAbsentProofIsHeld() = runBlocking {
        val f = PersonalOperationsFixture(); f.seedUnknown()
        val held = f.operations.recoverOffline(f.query) { true }
        assertEquals(1, held.held); assertTrue(f.calls.isEmpty())
        f.result = f.verified(f.saved!!)
        val recovered = f.operations.recoverOffline(f.query) { true }
        assertEquals(1, recovered.completed)
        assertEquals(listOf("ack", "complete"), f.calls)
    }

    @Test fun deniedAndDeletionWorkCannotReachPreparationOrTransport() = runBlocking {
        val denied = PersonalOperationsFixture(); denied.allowed = false
        denied.process(); assertTrue(denied.calls.isEmpty())
        val deletion = PersonalOperationsFixture()
        deletion.operations.process(deletion.scope, deletion.reference.copy(kind = MutationKind.DELETE),
            MutationId("unused"), deletion.stamp, { true }) { fail("Unexpected proof") }
        assertTrue(deletion.calls.isEmpty())
    }

    @Test fun preparedAllDayWorkKeepsItsExclusiveDateAndIndependentTimeHistory() = runBlocking {
        val f = PersonalOperationsFixture(allDay = true)
        assertTrue(SystemPersonalEditProfile.accepts(f.intent))
        assertFalse(f.process())
        assertEquals(f.changed.time, f.saved!!.preparation.intent.content.time)
        assertEquals(f.changed.time, f.result!!.event.content.time)
        assertNotNull(f.completion)
        assertTrue("capsule" in f.calls); assertTrue("submit" in f.calls)
        assertEquals(PersonalUpdateAttemptState.OUTCOME_UNKNOWN, f.saved!!.state)
    }

    @Test fun scopedLeaseCannotReopenOrEscapeAfterPreparationReturns() = runBlocking {
        val f = PersonalOperationsFixture()
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
        val f = PersonalOperationsFixture(); f.process()
        assertFalse(f.retainedLease!!(f.scope)); assertFalse(f.evidenceLease!!(f.scope))
        assertFalse(f.evidenceLease!!(f.scope.copy(calendar = CalendarId("other-calendar"))))
    }

    @Test fun modeRevocationRetainsVerifiedProofAndWakesButDoesNotAcknowledge() = runBlocking {
        val f = PersonalOperationsFixture()
        f.submitObservation = { unknown ->
            f.allowed = false
            PersonalUpdateSubmissionObservation.Verified(unknown, f.verified(unknown).event)
        }
        f.process()
        assertNotNull(f.result); assertTrue("wake" in f.calls)
        assertFalse("ack" in f.calls); assertNull(f.completion)
        assertFalse(f.evidenceLease!!(f.scope)); assertFalse(f.retainedLease!!(f.scope))
    }

    @Test fun logoutRevokesEvidenceRetentionEvenWhenTheLateReplyIsVerified() = runBlocking {
        val f = PersonalOperationsFixture()
        f.submitObservation = { unknown ->
            f.allowed = false; f.sessionOpen = false
            PersonalUpdateSubmissionObservation.Verified(unknown, f.verified(unknown).event)
        }
        f.process()
        assertNull(f.result); assertNull(f.completion)
        assertFalse("proof" in f.calls); assertFalse("wake" in f.calls); assertFalse("ack" in f.calls)
        assertEquals(PersonalUpdateAttemptState.OUTCOME_UNKNOWN, f.saved!!.state)
        assertFalse(f.evidenceLease!!(f.scope))
    }

    @Test fun substitutedPreparationOrWireCannotBeDispatched() = runBlocking {
        for (variant in 0..3) {
            val f = PersonalOperationsFixture()
            when (variant) {
                0 -> f.capsuleHook = { PersonalEditPreparation(f.intent, f.stamp.plusSeconds(1), f.preparation.format, "wrong-time") }
                1 -> f.wireHook = { it.copy(id = MutationId("wrong-request")) }
                2 -> f.wireHook = { it.copy(preparation = PersonalEditPreparation(f.intent, f.stamp, f.preparation.format, "other-capsule")) }
                3 -> f.rememberHook = { it.copy(format = AdapterFormat("other-format", 1)) }
            }
            f.process()
            assertFalse("variant $variant dispatched", "submit" in f.calls)
            assertNull(f.result); assertNull(f.completion)
        }
    }

    @Test fun substitutedOwnerSnapshotCannotReachAuthenticatedPreparation() = runBlocking {
        for (variant in 0..2) {
            val f = PersonalOperationsFixture()
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
        val f = PersonalOperationsFixture(); f.seedUnknown()
        f.result = f.verified(f.saved!!.copy(protectedPayload = "substituted-wire"))
        val progress = f.operations.recoverOffline(f.query) { true }
        assertEquals(1, progress.failed); assertFalse("ack" in f.calls); assertNull(f.completion)
    }

    @Test fun failedProofRetentionCannotWakeOrAcknowledgeAndFailedAckCannotComplete() = runBlocking {
        val denied = PersonalOperationsFixture(); denied.acceptProof = false
        assertTrue(denied.process()); assertFalse("wake" in denied.calls); assertFalse("ack" in denied.calls)
        val noAck = PersonalOperationsFixture(); noAck.acceptAck = false
        noAck.process(); assertTrue("wake" in noAck.calls); assertTrue("ack" in noAck.calls)
        assertFalse("complete" in noAck.calls); assertNotNull(noAck.result)
    }

    @Test fun inspectionReportsFailureWhenItCannotStoreVerifiedEvidence() = runBlocking {
        val f = PersonalOperationsFixture(); f.seedUnknown(); f.acceptProof = false
        val progress = f.operations.inspect(f.query, { true }) { fail("No retained proof") }
        assertEquals(1, progress.failed); assertEquals(0, progress.completed)
        assertFalse("submit" in f.calls); assertFalse("ack" in f.calls)
    }

    @Test fun offlinePagePreservesItsCursorAndNeverInspectsUnknownTransport() = runBlocking {
        val f = PersonalOperationsFixture(); f.seedUnknown()
        f.pageRows = listOf(PersonalUpdateWorkRef(f.saved!!.id, f.saved!!.eventId, f.saved!!.state),
            PersonalUpdateWorkRef(MutationId("next-request"), EventId("next-event"), PersonalUpdateAttemptState.OUTCOME_UNKNOWN))
        val progress = f.operations.recoverOffline(TitleUpdateWorkQuery(f.scope, limit = 1)) { true }
        assertEquals(1, progress.held)
        assertEquals(TitleUpdateWorkCursor(f.scope, f.intent.base.eventId), progress.next)
        assertTrue(f.calls.isEmpty())
    }

    @Test fun revocationDuringInspectionPreventsAckAndExpiresRecoveryCallback() = runBlocking {
        val f = PersonalOperationsFixture(); f.seedUnknown()
        f.inspectHook = { lease ->
            f.allowed = false; assertFalse(lease(f.scope))
            f.allowed = true; assertFalse(lease(f.scope))
            PersonalUpdateSubmissionObservation.Unknown
        }
        val progress = f.operations.inspect(f.query, { f.allowed }) { fail("Revoked proof wake") }
        assertTrue(progress.inactive); assertNull(progress.next)
        assertEquals(listOf("inspect"), f.calls)
        assertFalse(f.retainedLease!!(f.scope))
    }

    @Test fun eachChangedFieldMustRemainConsentedBeforePreparationAndUnknownInspection() = runBlocking {
        val f = PersonalOperationsFixture(); f.fieldsAllowed = false
        f.process()
        assertEquals(listOf("observe"), f.calls)
        assertNull(f.saved)
        f.seedUnknown(); f.calls.clear()
        val held = f.operations.inspect(f.query, { true }) { fail("No proof expected") }
        assertEquals(1, held.held); assertTrue(f.calls.isEmpty())
        // An already retained fact can finish locally without new sending consent.
        f.result = f.verified(f.saved!!)
        val recovered = f.operations.recoverOffline(f.query) { true }
        assertEquals(1, recovered.completed)
        assertEquals(listOf("ack", "complete"), f.calls)
    }

    @Test fun fieldConsentLostDuringPreparationClosesThisLeasePermanently() = runBlocking {
        val f = PersonalOperationsFixture()
        f.capsuleHook = { lease ->
            f.fieldsAllowed = false; assertFalse(lease(f.scope))
            f.fieldsAllowed = true; assertFalse(lease(f.scope))
            f.preparation
        }
        f.process()
        assertFalse("retain-capsule" in f.calls); assertNull(f.saved)
    }

}

/** Strict single-owner in-memory boundary, deliberately independent of SQL/crypto implementation. */
internal class PersonalOperationsFixture(allDay: Boolean = false) {
    val scope = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 2)
    val stamp = Instant.parse("2033-03-20T09:00:00Z")
    val base = EventContent("Original title", if (allDay)
        EventTime.AllDay(java.time.LocalDate.of(2033, 3, 20), java.time.LocalDate.of(2033, 3, 21)) else
        EventTime.Timed(stamp.plusSeconds(3600), stamp.plusSeconds(7200), ZoneId.of("Europe/Paris")), "Original\r\nnotes", "Original\r\nroom", colorRgb = 0x8562cc, details = EventDetails(reminders = listOf(EventReminder(10), EventReminder(60))))
    val changed = base.copy(title = "Changed title", time = when (val old = base.time) {
        is EventTime.AllDay -> EventTime.AllDay(old.start.plusDays(1), old.endExclusive.plusDays(2))
        is EventTime.Timed -> EventTime.Timed(stamp.plusSeconds(10800), stamp.plusSeconds(18000), ZoneId.of("Europe/Paris"))
    })
    val intent = LocalEditIntent(MutationId("fixture-owner"), scope, AdapterFormat("fixture-observation", 2),
        RemoteChange.Upsert(EventId("fixture-event"), Revision("fixture-base"), base), changed)
    val preparation = PersonalEditPreparation(intent, stamp, AdapterFormat("fixture-capsule", 1), "sealed-capsule")
    val wireFormat = AdapterFormat("fixture-wire", 1)
    val reference = RetainedLocalChangeRef(intent.base.eventId, intent.id, MutationKind.UPDATE, LocalEditState.PAUSED)
    val query = TitleUpdateWorkQuery(scope)
    val calls = mutableListOf<String>()
    var allowed = true
    var sessionOpen = true
    var fieldsAllowed = true
    var observedIntent = intent
    var unsupportedHook: (suspend (RetainedLocalChangeRef, (CalendarScope) -> Boolean, (LocalEditIntent) -> Boolean) -> Boolean)? = null
    var retained: PersonalEditPreparation? = null
    var saved: PersonalUpdateAttemptRecord? = null
    var submitted: PersonalUpdateAttemptRecord? = null
    var result: PersonalUpdateVerifiedResult? = null
    var completion: PersonalUpdateCompletion? = null
    var acceptProof = true
    var acceptAck = true
    var retainedLease: ((CalendarScope) -> Boolean)? = null
    var evidenceLease: ((CalendarScope) -> Boolean)? = null
    var capsuleHook: (suspend ((CalendarScope) -> Boolean) -> PersonalEditPreparation)? = null
    var retainHook: ((PersonalEditPreparation) -> PersonalEditPreparation)? = null
    var wireHook: ((PersonalUpdateAttemptRecord) -> PersonalUpdateAttemptRecord)? = null
    var rememberHook: ((PersonalUpdateAttemptRecord) -> PersonalUpdateAttemptRecord)? = null
    var submitObservation: ((PersonalUpdateAttemptRecord) -> PersonalUpdateSubmissionObservation)? = null
    var inspectHook: (((CalendarScope) -> Boolean) -> PersonalUpdateSubmissionObservation)? = null
    var pageRows: List<PersonalUpdateWorkRef>? = null
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
    fun verified(record: PersonalUpdateAttemptRecord): PersonalUpdateVerifiedResult = PersonalUpdateVerifiedResult.of(record,
        PersonalUpdateSubmissionObservation.Verified(record, intent.base.copy(revision = Revision("fixture-result"),
            content = changed.copy(description = "Original\nnotes"))))
    fun seedUnknown() {
        retained = preparation
        saved = PersonalUpdateAttemptRecord(PersonalUpdateAttemptIdentity.requestId(preparation, wireFormat), preparation,
            wireFormat, "sealed-first-wire", PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
    }

    val journal = object : PersonalUpdateRecoveryJournal {
        override val completionProtocol = 1
        override suspend fun remember(record: PersonalUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "remember"; check(admitted(admit)); check(saved == null)
            saved = rememberHook?.invoke(record) ?: record.frozen(); return saved == record
        }
        override suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): PersonalUpdateAttemptRecord? {
            check(scope == this@PersonalOperationsFixture.scope && admitted(admit))
            if (cancelHere("read")) return null
            return saved?.takeIf { it.id == id }?.frozen()
        }
        override suspend fun claim(expected: PersonalUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "claim"; check(admitted(admit)); check(saved == expected && expected.state == PersonalUpdateAttemptState.PREPARED)
            saved = expected.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN); return true
        }
        override suspend fun retainEvidence(expectedUnknown: PersonalUpdateAttemptRecord, protectedEvidence: String,
            admit: (CalendarScope) -> Boolean): Boolean = error("Unexpected evidence operation")
        override suspend fun retainVerified(result: PersonalUpdateVerifiedResult, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "proof"; check(admitted(admit)); check(result.record == saved!!.copy(protectedEvidence = null))
            if (acceptProof) this@PersonalOperationsFixture.result = result.snapshot()
            cancelHere("proof")
            return acceptProof
        }
        override suspend fun readVerified(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): PersonalUpdateVerifiedResult? {
            check(scope == this@PersonalOperationsFixture.scope && admitted(admit)); check(id == saved!!.id); return result
        }
        override suspend fun retainCompletion(completion: PersonalUpdateCompletion, admit: (CalendarScope) -> Boolean): Boolean {
            check(admitted(admit)); check(completion.result == result); this@PersonalOperationsFixture.completion = completion; return true
        }
        override suspend fun readCompletion(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): PersonalUpdateCompletion? {
            check(scope == this@PersonalOperationsFixture.scope && admitted(admit)); check(id == saved!!.id); return completion
        }
        override suspend fun page(query: PersonalUpdateWorkQuery, admit: (CalendarScope) -> Boolean): PersonalUpdateWorkPage? {
            check(query.scope == scope && admitted(admit))
            if (cancelHere("page")) return null
            return PersonalUpdateWorkPage.fromOrderedMetadata(query, pageRows ?: listOfNotNull(saved?.let { PersonalUpdateWorkRef(it.id, it.eventId, it.state) }))
        }
    }
    private val owners = object : SystemPersonalLocalOwner {
        override suspend fun observe(scope: CalendarScope, event: EventId, owner: MutationId,
            admit: (CalendarScope) -> Boolean): PersonalLocalOwnerSnapshot? {
            calls += "observe"; check(scope == this@PersonalOperationsFixture.scope && event == intent.base.eventId && owner == intent.id)
            check(admitted(admit))
            if (cancelHere("observe")) return null
            return if (SystemPersonalEditProfile.accepts(observedIntent)) PersonalLocalOwnerSnapshot(observedIntent, retained) else null
        }
        override suspend fun retain(preparation: PersonalEditPreparation, admit: (CalendarScope) -> Boolean): PersonalEditPreparation? {
            calls += "retain-capsule"; check(admitted(admit))
            if (cancelHere("retain-capsule")) return null
            retained = retainHook?.invoke(preparation) ?: preparation; return retained
        }
        override suspend fun qualify(preparation: PersonalEditPreparation, admit: (CalendarScope) -> Boolean): Boolean {
            calls += "qualify"; check(preparation.intent == intent); return admitted(admit)
        }
        override suspend fun submit(record: PersonalUpdateAttemptRecord, journal: PersonalUpdateRecoveryJournal,
            port: CalendarPersonalUpdatePort, admit: (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): PersonalUpdateSubmissionObservation =
            port.submit(record, journal, admit, { requested -> requested == scope && qualify(record.preparation, admit) }, admitEvidence)
        override suspend fun acknowledge(result: PersonalUpdateVerifiedResult, journal: PersonalUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            calls += "ack"; check(admitted(admit)); check(this@PersonalOperationsFixture.result == result)
            if (cancelHere("ack")) return true
            return acceptAck
        }
        override suspend fun complete(result: PersonalUpdateVerifiedResult, journal: PersonalUpdateRecoveryJournal,
            admit: (CalendarScope) -> Boolean): Boolean {
            calls += "complete"; check(acceptAck && admitted(admit))
            val receipt = PersonalUpdateAcknowledgementReceipt.of(result, "00000000-0000-0000-0000-000000000099")
            return journal.retainCompletion(PersonalUpdateCompletion.of(result,
                PersonalUpdateAcknowledgementRecord(receipt, PersonalUpdateAcknowledgementState.RESTORED)), admit)
        }
    }
    private val capsules = object : CalendarPersonalPreparationPort {
        override val personalFormat = preparation.format
        override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: Instant): PersonalEditPreparation =
            error("Unguarded preparation forbidden")
        override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: Instant,
            admit: (CalendarScope) -> Boolean): PersonalEditPreparation? {
            calls += "capsule"; check(intent == this@PersonalOperationsFixture.intent && admitted(admit)); retainedLease = admit
            if (cancelHere("capsule")) return null
            return capsuleHook?.invoke(admit) ?: PersonalEditPreparation(intent, timestamp, preparation.format, preparation.protectedPayload)
        }
    }
    private val submissions = object : CalendarPersonalUpdatePort {
        override val personalUpdateFormat = wireFormat
        override suspend fun prepareSubmission(id: MutationId, preparation: PersonalEditPreparation,
            admit: (CalendarScope) -> Boolean): PersonalUpdateAttemptRecord? {
            calls += "wire"; check(admitted(admit)); check(id == PersonalUpdateAttemptIdentity.requestId(preparation, wireFormat))
            if (cancelHere("wire")) return null
            val candidate = PersonalUpdateAttemptRecord(id, preparation, wireFormat, "sealed-new-wire")
            return wireHook?.invoke(candidate) ?: candidate
        }
        override suspend fun submit(record: PersonalUpdateAttemptRecord, journal: PersonalUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): PersonalUpdateSubmissionObservation {
            calls += "submit"; retainedLease = admit; evidenceLease = admitEvidence
            check(admitted(admit) && record == saved && qualifySource(scope)); check(journal.claim(record, admit))
            submitted = record.frozen()
            val unknown = saved!!
            return submitObservation?.invoke(unknown) ?: PersonalUpdateSubmissionObservation.Verified(unknown, verified(unknown).event)
        }
        override suspend fun inspect(record: PersonalUpdateAttemptRecord, journal: PersonalUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean): PersonalUpdateSubmissionObservation {
            calls += "inspect"; check(admitted(admit)); check(record == saved && record.state == PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
            retainedLease = admit
            return inspectHook?.invoke(admit) ?: PersonalUpdateSubmissionObservation.Verified(record, verified(record).event)
        }
    }
    val operations = CoordinatorSystemPersonalOperations(owners, journal, capsules, submissions,
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
        }, consentAdmission = { target -> { intent -> fieldsAllowed && intent.scope == target } },
        retainUnsupported = { _, reference, admit, consent -> unsupportedHook?.invoke(reference, admit, consent) ?: false })
    suspend fun process(timestamp: Instant = stamp, candidate: MutationId = MutationId("random-traversal-candidate")): Boolean =
        operations.process(scope, reference, candidate, timestamp, { it == scope && allowed }) { calls += "wake" }
}
