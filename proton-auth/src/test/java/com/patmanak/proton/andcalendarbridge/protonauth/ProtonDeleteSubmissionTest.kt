// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ProtonDeleteSubmissionTest {
    @Test fun verifiedDeleteNeedsClaimBeforeOneSendEvidenceBeforeAuthenticatedAbsence() = runTest {
        val f = DeleteSubmissionFixture(); val record = f.seed()
        val result = f.submit(record)
        assertTrue(result is DeleteSubmissionObservation.Verified)
        assertEquals(1, f.count("send")); assertEquals(DeleteAttemptState.OUTCOME_UNKNOWN, f.journal.stored?.state)
        assertTrue(f.steps.indexOf("claimed") < f.steps.indexOf("send"))
        assertTrue(f.steps.indexOf("evidence") < f.steps.lastIndexOf("get"))
        assertNotNull(f.journal.stored?.protectedEvidence)
        assertFalse(requireNotNull(f.journal.retainedLease)(f.scope))
    }
    @Test fun unknownNeverResendsAndMissingReplyCanOnlyBeInspected() = runTest {
        val f = DeleteSubmissionFixture(); val record = f.seed().copy(state = DeleteAttemptState.OUTCOME_UNKNOWN)
        f.journal.stored = record; f.read = f.absent()
        assertEquals(DeleteSubmissionObservation.Unknown, f.submit(record))
        assertEquals(0, f.count("send")); assertEquals(0, f.count("get"))
        assertTrue(f.inspect(record) is DeleteSubmissionObservation.Verified)
        assertEquals(0, f.count("send"))
    }
    @Test fun changedVerifiedRemoteReturnsConflictWithoutClaimOrSend() = runTest {
        val f = DeleteSubmissionFixture(); val record = f.seed()
        f.read = ProtonDeleteEventRead.Present(JsonObject(f.row + ("ModifyTime" to JsonPrimitive(3))))
        f.decodedContent = f.intent.content.copy(title = "Remote concurrent title")
        val result = f.submit(record) as DeleteSubmissionObservation.Conflict
        assertEquals("Remote concurrent title", result.event.content.title)
        assertEquals(record, result.record); assertEquals(0, f.count("claim")); assertEquals(0, f.count("send"))
    }
    @Test fun changedMetadataAloneConflictsAndUnverifiedContentsStayUnknown() = runTest {
        val f = DeleteSubmissionFixture(); val record = f.seed()
        f.read = ProtonDeleteEventRead.Present(JsonObject(f.row + ("ModifyTime" to JsonPrimitive(3))))
        assertTrue(f.submit(record) is DeleteSubmissionObservation.Conflict)
        f.verifyAllowed = false
        assertEquals(DeleteSubmissionObservation.Unknown, f.submit(record)); assertEquals(0, f.count("send"))
    }
    @Test fun cancellationAfterClaimRemainsUnknownWithoutSendingOrResetting() = runTest {
        val f = DeleteSubmissionFixture(); val record = f.seed()
        f.hook = { if (it == "claimed") throw CancellationException("synthetic interruption") }
        try { f.submit(record); fail("Cancellation was swallowed") } catch (_: CancellationException) { }
        assertEquals(DeleteAttemptState.OUTCOME_UNKNOWN, f.journal.stored?.state); assertEquals(0, f.count("send"))
        f.hook = {}; f.read = f.absent()
        assertTrue(f.inspect(requireNotNull(f.journal.stored)) is DeleteSubmissionObservation.Verified)
        assertEquals(0, f.count("send"))
    }
    @Test fun claimFailureAndSourceRevocationDoNotTransmit() = runTest {
        val f = DeleteSubmissionFixture(); val record = f.seed()
        f.hook = { if (it == "claim") throw IOException("synthetic storage") }
        assertEquals(DeleteSubmissionObservation.Unknown, f.submit(record))
        assertEquals(record, f.journal.stored); assertEquals(0, f.count("send"))
        f.hook = { if (it == "claimed") f.sourceAllowed = false }
        assertEquals(DeleteSubmissionObservation.Unknown, f.submit(record))
        assertEquals(DeleteAttemptState.OUTCOME_UNKNOWN, f.journal.stored?.state); assertEquals(0, f.count("send"))
    }
    @Test fun generic404TransportExceptionAndForeignAbsenceCannotResolveUnknown() = runTest {
        val f = DeleteSubmissionFixture(); val prepared = f.seed()
        f.journal.stored = prepared.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN)
        f.read = ProtonDeleteEventRead.Unknown
        assertEquals(DeleteSubmissionObservation.Unknown, f.inspect(requireNotNull(f.journal.stored)))
        f.readFailure = IOException("synthetic network")
        assertEquals(DeleteSubmissionObservation.Unknown, f.inspect(requireNotNull(f.journal.stored)))
        f.readFailure = null; f.read = ProtonDeleteEventRead.Absent("foreign-calendar", f.intent.base.eventId.value)
        assertEquals(DeleteSubmissionObservation.Unknown, f.inspect(requireNotNull(f.journal.stored)))
    }
    @Test fun absentBeforeClaimIsNotSendPermission() = runTest {
        val f = DeleteSubmissionFixture(); val record = f.seed(); f.read = f.absent()
        assertEquals(DeleteSubmissionObservation.Unknown, f.submit(record))
        assertEquals(0, f.count("claim")); assertEquals(0, f.count("send"))
    }
    @Test fun membershipMustStillMatchAfterAbsenceAndLeasesClosePermanently() = runTest {
        val f = DeleteSubmissionFixture(); val prepared = f.seed()
        f.journal.stored = prepared.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN); f.read = f.absent()
        f.hook = { if (it == "get") f.freshContext = JsonObject(f.context + ("member" to JsonPrimitive("changed-member"))) }
        assertEquals(DeleteSubmissionObservation.Unknown, f.inspect(requireNotNull(f.journal.stored)))
        assertFalse(requireNotNull(f.journal.retainedLease)(f.scope))
        f.freshContext = f.context
        assertFalse(requireNotNull(f.journal.retainedLease)(f.scope))
    }
    @Test fun receivedBodyIsRetainedEvenWhenEditConsentClosesDuringSend() = runTest {
        val f = DeleteSubmissionFixture(); val record = f.seed()
        f.hook = { if (it == "send") f.allowed = false }
        assertEquals(DeleteSubmissionObservation.Unknown, f.submit(record))
        val stored = requireNotNull(f.journal.stored)
        assertNotNull(stored.protectedEvidence)
        val plain = f.protector.open(requireNotNull(stored.protectedEvidence))
        assertEquals(f.response, (Json.parseToJsonElement(plain) as JsonObject).string("body"))
        f.allowed = true; f.hook = {}; f.read = f.absent()
        assertTrue(f.inspect(stored) is DeleteSubmissionObservation.Verified)
        assertEquals(1, f.count("send"))
    }
    @Test fun responseSuccessAloneNeverAcknowledgesAndFirstEvidenceCannotBeReplaced() = runTest {
        val f = DeleteSubmissionFixture(); val record = f.seed(); f.disappearAfterSend = false
        assertEquals(DeleteSubmissionObservation.Unknown, f.submit(record))
        val stored = requireNotNull(f.journal.stored); val evidence = stored.protectedEvidence
        f.response = "other response"
        assertEquals(DeleteSubmissionObservation.Unknown, f.submit(stored))
        assertEquals(evidence, f.journal.stored?.protectedEvidence); assertEquals(1, f.count("send"))
    }
    @Test fun evidenceDenialDoesNotGrantAnotherSendAndAllLeasesExpire() = runTest {
        val f = DeleteSubmissionFixture(); val record = f.seed(); f.evidenceAllowed = false
        assertEquals(DeleteSubmissionObservation.Unknown, f.submit(record))
        assertNull(f.journal.stored?.protectedEvidence); assertEquals(DeleteAttemptState.OUTCOME_UNKNOWN, f.journal.stored?.state)
        assertFalse(requireNotNull(f.journal.retainedLease)(f.scope))
    }
    @Test fun preparationRefusesUnchangedMembershipWithChangedBaseOrSignatureFailure() = runTest {
        val f = DeleteSubmissionFixture()
        f.verifyAllowed = false
        assertNull(f.adapter.prepareSubmission(MutationId("request"), f.intent) { true })
        f.verifyAllowed = true; f.decodedContent = f.intent.content.copy(title = "remote changed")
        assertNull(f.adapter.prepareSubmission(MutationId("request"), f.intent) { true })
        assertEquals(0, f.count("send"))
    }
    @Test fun capsuleBindsRawIntentAndOriginalRequest() = runTest {
        val f = DeleteSubmissionFixture(); val record = f.seed()
        val request = ProtonDeleteRequestCodec(f.protector).open(record)
        assertEquals(setOf("MemberID", "Events"), request.request.keys)
        assertEquals(0L, (request.request.array("Events").single() as JsonObject).number("DeletionReason"))
        val changed = f.intent.content.copy(title = "different")
        val substituted = record.copy(intent = f.intent.copy(base = f.intent.base.copy(content = changed), content = changed))
        f.journal.stored = substituted
        assertEquals(DeleteSubmissionObservation.Unknown, f.submit(substituted)); assertEquals(0, f.count("send"))
    }
}

/** Synthetic transport/verification fixtures do not qualify real service or cryptography behavior. */
internal class DeleteSubmissionFixture {
    private val seed = TitleSubmissionFixture()
    val scope = seed.scope
    val initial = seed.initial
    val row = seed.row
    val bootstrap = seed.bootstrap
    val context = JsonObject(seed.context + buildJsonObject {
        put("permissions", 16); put("flags", 1)
        listOf("calendarKey", "signingKey", "calendar").forEach { put(it, ProtonSimpleEventUpdate.hash("synthetic-$it")) }
    })
    val intent = LocalDeleteIntent(MutationId("synthetic-delete"), scope, EVENT_FORMAT,
        seed.intent.base.copy(revision = row.revision(bootstrap)), seed.content)
    val values = linkedMapOf<String, String>()
    val protector = object : EventPayloadProtector {
        override fun seal(plain: String): String = "delete-protected-${values.size}".also { values[it] = plain }
        override fun open(protected: String): String = requireNotNull(values[protected])
    }
    val steps = mutableListOf<String>()
    var hook: suspend (String) -> Unit = {}
    suspend fun step(value: String) { steps += value; hook(value) }
    fun count(value: String) = steps.count { it == value }
    var current: CatalogSession? = initial
    var freshContext = context
    var read: ProtonDeleteEventRead = ProtonDeleteEventRead.Present(row)
    var readFailure: Exception? = null
    var decodedContent = seed.content
    var verifyAllowed = true
    var allowed = true
    var sourceAllowed = true
    var evidenceAllowed = true
    var disappearAfterSend = true
    var response = "{\"Code\":1001,\"Responses\":[{\"Index\":0,\"Response\":{\"Code\":1000}}]}"
    fun absent() = ProtonDeleteEventRead.Absent(scope.calendar.value, intent.base.eventId.value)
    val journal = Journal()
    val adapter = ProtonDeleteSubmissionAdapter(protector, { step("session"); current }, { _, actualScope, checkpoint ->
        assertEquals(scope, actualScope); checkpoint(); step("context"); checkpoint(); freshContext
    }, object : DeleteSubmissionRemote {
        override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject): String {
            assertEquals(initial, session); assertEquals(scope.calendar.value, calendar)
            assertEquals(DeleteAttemptState.OUTCOME_UNKNOWN, journal.stored?.state)
            step("send"); if (disappearAfterSend) read = absent(); return response
        }
        override suspend fun event(session: CatalogSession, calendar: String, id: String): ProtonDeleteEventRead {
            assertEquals(initial, session); assertEquals(scope.calendar.value, calendar); assertEquals(intent.base.eventId.value, id)
            step("get"); readFailure?.let { throw it }; return read
        }
    }, object : EventImportRemote {
        override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage = error("No list absence")
        override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject { step("bootstrap"); return this@DeleteSubmissionFixture.bootstrap }
    }, CreateEventVerifier { session, calendar, _, row, uid, policy ->
        assertEquals(initial, session); assertEquals(scope.calendar.value, calendar); assertEquals("synthetic-uid", uid)
        assertEquals(CreatedEventReadbackPolicy.KNOWN_EMPTY_V2, policy); step("verify")
        if (verifyAllowed) intent.base.copy(revision = row.revision(bootstrap), content = decodedContent) else null
    })
    suspend fun seed(): DeleteAttemptRecord {
        val record = requireNotNull(adapter.prepareSubmission(MutationId("synthetic-delete-request"), intent) { it == scope && allowed })
        journal.stored = record; steps.clear(); return record
    }
    suspend fun submit(record: DeleteAttemptRecord) = adapter.submit(record, journal,
        { it == scope && allowed }, { assertEquals(scope, it); step("source"); sourceAllowed }, { it == scope && evidenceAllowed })
    suspend fun inspect(record: DeleteAttemptRecord) = adapter.inspect(record, journal) { it == scope && allowed }

    inner class Journal : DeleteAttemptJournal {
        var stored: DeleteAttemptRecord? = null
        var retainedLease: ((CalendarScope) -> Boolean)? = null
        override suspend fun remember(record: DeleteAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean = error("No adapter remember")
        override suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): DeleteAttemptRecord? {
            retainedLease = admit
            if (!admit(scope)) return null
            step("read"); return stored?.takeIf { it.scope == scope && it.id == id }
        }
        override suspend fun claim(expected: DeleteAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            if (!admit(expected.scope)) return false
            step("claim")
            if (!admit(expected.scope) || stored != expected || expected.state != DeleteAttemptState.PREPARED) return false
            stored = expected.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN); step("claimed"); return true
        }
        override suspend fun retainEvidence(expectedUnknown: DeleteAttemptRecord, protectedEvidence: String, admit: (CalendarScope) -> Boolean): Boolean {
            retainedLease = admit
            if (!admit(expectedUnknown.scope)) return false
            step("evidence")
            val actual = stored ?: return false
            if (!admit(expectedUnknown.scope) || actual != expectedUnknown || actual.state != DeleteAttemptState.OUTCOME_UNKNOWN) return false
            stored = actual.copy(protectedEvidence = protectedEvidence); return true
        }
    }
}
