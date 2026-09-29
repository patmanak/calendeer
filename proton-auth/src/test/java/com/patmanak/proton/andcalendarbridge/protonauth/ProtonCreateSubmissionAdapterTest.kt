// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test

/** Synthetic ports test admission/reconciliation policy, not server or cryptographic compatibility. */
class ProtonCreateSubmissionAdapterTest {
    @Test fun utcAliasesInReadbackPreserveTimedSemantics() = runTest {
        val original = EventContent("Synthetic", EventTime.Timed(Instant.parse("2026-10-01T10:00:00Z"),
            Instant.parse("2026-10-01T11:00:00Z"), java.time.ZoneId.of("UTC")))
        val f = CreateSubmissionFixture(original)
        f.verified = f.verified!!.copy(content = original.copy(time = (original.time as EventTime.Timed).copy(zone = java.time.ZoneOffset.UTC)))
        assertTrue(f.adapter.submit(f.record, f.journal) is CreateSubmissionObservation.Verified)
    }
    @Test fun freshContextAndDurableUnknownPrecedeTheOnlySendAndVerifiedReadback() = runTest {
        val f = CreateSubmissionFixture()
        val observed = f.adapter.submit(f.record, f.journal) as CreateSubmissionObservation.Verified
        assertEquals(CreateAttemptIdentity.of(f.record), observed.attempt)
        assertEquals(listOf("prepare", "claim", "send", "evidence", "uid:0", "event", "bootstrap", "verify"), f.steps)
        assertEquals(1, f.sends)
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, f.journal.stored!!.state)
        val evidence = Json.parseToJsonElement(f.protector.open(f.journal.stored!!.protectedEvidence!!)).jsonObject
        assertEquals(true, evidence["complete"]!!.jsonPrimitive.boolean)
        assertEquals(f.response, evidence.string("body"))
        assertTrue(f.adapter.submit(f.record, f.journal) is CreateSubmissionObservation.Verified)
        assertEquals(1, f.sends)
        assertEquals(1, f.prepares)
    }

    @Test fun successfulResponseWithoutAuthenticatedReadbackIsNeverAcknowledged() = runTest {
        val f = CreateSubmissionFixture()
        f.rows = emptyList()
        assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(f.record, f.journal))
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, f.journal.stored!!.state)
        assertNotNull(f.journal.stored!!.protectedEvidence)
        f.steps.clear()
        assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(f.record, f.journal))
        assertEquals(listOf("uid:0"), f.steps)
        assertEquals(1, f.sends)
    }

    @Test fun lostAcknowledgementCanBeInspectedLaterWithoutAnotherSend() = runTest {
        val f = CreateSubmissionFixture()
        f.sendFailure = IOException("synthetic lost acknowledgement")
        f.rows = emptyList()
        assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(f.record, f.journal))
        val evidence = Json.parseToJsonElement(f.protector.open(f.journal.stored!!.protectedEvidence!!)).jsonObject
        assertEquals(false, evidence["complete"]!!.jsonPrimitive.boolean)
        assertEquals("", evidence.string("body"))
        f.rows = listOf(f.row)
        assertTrue(f.adapter.inspect(f.journal.stored!!) is CreateSubmissionObservation.Verified)
        assertTrue(f.adapter.submit(f.record, f.journal) is CreateSubmissionObservation.Verified)
        assertEquals(1, f.sends)
        assertEquals(1, f.prepares)
    }

    @Test fun changedRightsOrKeyContextRefusesAdmissionBeforeAnySend() = runTest {
        for (key in listOf("permissions", "key", "signingKey", "passphrase", "member")) {
            val f = CreateSubmissionFixture()
            f.freshContext = JsonObject(f.context + (key to JsonPrimitive("changed")))
            assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(f.record, f.journal))
            assertEquals(listOf("prepare"), f.steps)
            assertEquals(CreateAttemptState.PREPARED, f.journal.stored!!.state)
            assertEquals(0, f.sends)
        }
    }

    @Test fun absentForeignOrReplacedSessionCannotAdmitSend() = runTest {
        for (replacement in listOf(null, CatalogSession(UserId("foreign-user"), SessionId("foreign-session")))) {
            val f = CreateSubmissionFixture()
            f.current = replacement
            assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(f.record, f.journal))
            assertTrue(f.steps.isEmpty())
            assertEquals(0, f.sends)
        }
        val f = CreateSubmissionFixture()
        f.after = { if (it == "prepare") f.current = f.initial.copy(sessionId = SessionId("replacement-session")) }
        assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(f.record, f.journal))
        assertEquals(listOf("prepare"), f.steps)
        assertEquals(CreateAttemptState.PREPARED, f.journal.stored!!.state)
    }

    @Test fun sessionReplacementDuringReadbackCannotBecomeVerified() = runTest {
        for (stage in listOf("send", "uid:0", "event", "bootstrap", "verify")) {
            val f = CreateSubmissionFixture()
            f.after = { if (it == stage) f.current = f.initial.copy(sessionId = SessionId("replacement-session")) }
            assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(f.record, f.journal))
            assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, f.journal.stored!!.state)
            assertEquals(1, f.sends)
        }
    }

    @Test fun sessionReplacementDuringDurableClaimPreservesUnknownWithoutSending() = runTest {
        val f = CreateSubmissionFixture()
        f.after = { if (it == "claim") f.current = f.initial.copy(sessionId = SessionId("replacement-session")) }
        assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(f.record, f.journal))
        assertEquals(listOf("prepare", "claim"), f.steps)
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, f.journal.stored!!.state)
        assertNull(f.journal.stored!!.protectedEvidence)
        assertEquals(0, f.sends)
    }

    @Test fun duplicateForeignOrMissingUidRowsCannotVerifyOrPermitResend() = runTest {
        val sample = CreateSubmissionFixture()
        for (rows in listOf(emptyList(), listOf(sample.row, sample.row),
            listOf(sample.row, JsonObject(sample.row + ("ID" to JsonPrimitive("second-event")))),
            listOf(JsonObject(sample.row + ("CalendarID" to JsonPrimitive("foreign-calendar")))),
            listOf(JsonObject(sample.row + ("UID" to JsonPrimitive("foreign-uid")))))) {
            val f = CreateSubmissionFixture()
            f.rows = rows
            assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(f.record, f.journal))
            assertFalse(f.steps.contains("event"))
            assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(f.record, f.journal))
            assertEquals(1, f.sends)
        }
    }

    @Test fun candidateIdentifierAndIndividualReadbackMustMatchTheUniqueUidResult() = runTest {
        val mismatch = CreateSubmissionFixture()
        mismatch.response = mismatch.response.replace("synthetic-event", "other-candidate")
        assertEquals(CreateSubmissionObservation.Unknown, mismatch.adapter.submit(mismatch.record, mismatch.journal))
        assertFalse(mismatch.steps.contains("event"))
        for (key in listOf("ID", "CalendarID", "UID")) {
            val f = CreateSubmissionFixture()
            f.individualRow = JsonObject(f.row + (key to JsonPrimitive("foreign")))
            assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(f.record, f.journal))
            assertFalse(f.steps.contains("verify"))
        }
    }

    @Test fun failedSignatureOrChangedContentCannotBecomeVerified() = runTest {
        for (actual in listOf(null, RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-revision"),
            CreateSubmissionFixture().draft.content.copy(title = "Different")))) {
            val f = CreateSubmissionFixture()
            f.verified = actual
            assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(f.record, f.journal))
            assertEquals(1, f.sends)
        }
    }

    @Test fun cancellationAfterClaimPropagatesAndRetainsUnknownWithoutEvidenceOrRetry() = runTest {
        val f = CreateSubmissionFixture()
        f.sendFailure = CancellationException("synthetic cancellation")
        try { f.adapter.submit(f.record, f.journal); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, f.journal.stored!!.state)
        assertNull(f.journal.stored!!.protectedEvidence)
        assertTrue(f.adapter.submit(f.record, f.journal) is CreateSubmissionObservation.Verified)
        assertEquals(1, f.sends)
    }

    @Test fun journalClaimFailureNeverCallsTransport() = runTest {
        val f = CreateSubmissionFixture()
        f.journal.claimFailure = IOException("synthetic commit failure")
        assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(f.record, f.journal))
        assertEquals(listOf("prepare", "claim"), f.steps)
        assertEquals(CreateAttemptState.PREPARED, f.journal.stored!!.state)
        assertEquals(0, f.sends)
    }

    @Test fun oldForeignOrChangedReceiptsCannotReachContextOrTransport() = runTest {
        for (change in listOf<(CreateAttemptRecord) -> CreateAttemptRecord>(
            { it.copy(format = CREATE_PREPARATION_FORMAT) },
            { it.copy(scope = it.scope.copy(generation = 2)) },
            { it.copy(protectedPayload = it.protectedPayload + "changed") })) {
            val f = CreateSubmissionFixture()
            val changed = change(f.record)
            if (changed.format == CREATE_PREPARATION_FORMAT) f.journal.stored = changed
            assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(changed, f.journal))
            assertTrue(f.steps.isEmpty())
            assertEquals(0, f.sends)
        }
    }
}

internal class CreateSubmissionFixture(content: EventContent? = null) {
    val scope = CalendarScope(AccountId("synthetic-user"), CalendarId("synthetic-calendar"), 1)
    val draft = CalendarCreateDraft(scope, MutationId("synthetic-correlation"), Instant.parse("2026-09-29T10:00:00Z"),
        content ?: EventContent("Synthetic create", EventTime.AllDay(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02")), "Synthetic description", "Synthetic location"))
    val uid = "synthetic-create@calendeer.test"
    val context = buildJsonObject { put("member", "synthetic-member"); put("permissions", 16); put("key", "synthetic-key")
        put("signingKey", "synthetic-signing-fingerprint"); put("passphrase", "synthetic-passphrase") }
    var freshContext = context
    val fragment = buildJsonObject { put("Permissions", 1); put("IsOrganizer", 1); put("SharedKeyPacket", "synthetic-packet")
        put("SharedEventContent", JsonArray(emptyList())) }
    val protector = object : EventPayloadProtector {
        override fun seal(plain: String) = "test-sealed:$plain"
        override fun open(protected: String): String { require(protected.startsWith("test-sealed:")); return protected.removePrefix("test-sealed:") }
    }
    fun envelope(qualified: JsonObject = context, request: JsonObject = ProtonCreateSubmissionCodec.request(qualified.string("member"), fragment)) = buildJsonObject {
        put("version", 1); put("account", scope.account.value); put("calendar", scope.calendar.value); put("generation", scope.generation)
        put("correlation", draft.correlation.value); put("timestamp", draft.timestamp.toString()); put("uid", uid)
        put("context", qualified); put("request", request); put("expected", ProtonCreateSubmissionCodec.expected(draft.content))
    }
    val record = CreateAttemptRecord(draft.correlation, scope, CREATE_SUBMISSION_FORMAT, protector.seal(envelope().toString()))
    val steps = mutableListOf<String>()
    var after: (String) -> Unit = {}
    fun step(value: String) { steps += value; after(value) }
    val journal = MemoryCreateJournal(record, ::step)
    val initial = CatalogSession(UserId(scope.account.value), SessionId("synthetic-session"))
    var current: CatalogSession? = initial
    var sends = 0
    var prepares = 0
    var sendFailure: Exception? = null
    var response = """{"Code":1001,"Responses":[{"Index":0,"Response":{"Code":1000,"Event":{"ID":"synthetic-event","CalendarID":"synthetic-calendar","UID":"$uid","ModifyTime":10}}}]}"""
    val row = buildJsonObject { put("ID", "synthetic-event"); put("CalendarID", scope.calendar.value); put("UID", uid); put("ModifyTime", 10) }
    var rows: List<JsonObject> = listOf(row)
    var individualRow = row
    var verified: RemoteChange.Upsert? = RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-revision"), draft.content)
    val adapter = ProtonCreateSubmissionAdapter(CalendarCreatePreparationPort { freshDraft ->
        assertEquals(draft, freshDraft); prepares++; step("prepare")
        PreparedCalendarCreate(scope, CREATE_SUBMISSION_FORMAT, protector.seal(envelope(freshContext).toString()))
    }, protector, CatalogSessionSource { current }, object : CreateSubmissionRemote {
        override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject): String {
            assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, journal.stored!!.state)
            assertEquals(initial, session); assertEquals(scope.calendar.value, calendar)
            assertEquals(ProtonCreateSubmissionCodec.request(context.string("member"), fragment), request)
            sends++; step("send"); sendFailure?.let { throw it }; return response
        }
        override suspend fun byUid(session: CatalogSession, calendar: String, uid: String, page: Int): List<JsonObject> {
            assertEquals(this@CreateSubmissionFixture.uid, uid); assertEquals(scope.calendar.value, calendar)
            step("uid:$page"); return rows
        }
        override suspend fun event(session: CatalogSession, calendar: String, id: String): JsonObject {
            assertEquals("synthetic-event", id); step("event"); return individualRow
        }
    }, object : EventImportRemote {
        override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage = error("No window fetch")
        override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject { step("bootstrap"); return buildJsonObject {} }
    }, CreateEventVerifier { _, _, _, _, expectedUid -> assertEquals(uid, expectedUid); step("verify"); verified })
}

internal class MemoryCreateJournal(initial: CreateAttemptRecord, private val step: (String) -> Unit) : CreateAttemptJournal {
    var stored: CreateAttemptRecord? = initial
    var claimFailure: Exception? = null
    override suspend fun remember(record: CreateAttemptRecord) {
        require(record.state == CreateAttemptState.PREPARED && record.protectedEvidence == null)
        val previous = stored
        require(previous == null || previous.copy(state = CreateAttemptState.PREPARED, protectedEvidence = null) == record)
        if (previous == null) stored = record
    }
    override suspend fun read(scope: CalendarScope, id: MutationId) = stored?.takeIf { it.scope == scope && it.id == id }
    override suspend fun claim(expected: CreateAttemptRecord): Boolean {
        step("claim"); claimFailure?.let { throw it }
        if (expected.state != CreateAttemptState.PREPARED || expected.protectedEvidence != null || stored != expected) return false
        stored = expected.copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
        return true
    }
    override suspend fun retainEvidence(expectedUnknown: CreateAttemptRecord, protectedEvidence: String) {
        require(expectedUnknown.state == CreateAttemptState.OUTCOME_UNKNOWN && protectedEvidence.isNotBlank())
        val actual = requireNotNull(stored)
        require(actual.copy(protectedEvidence = expectedUnknown.protectedEvidence) == expectedUnknown)
        require(actual.protectedEvidence == null || actual.protectedEvidence == protectedEvidence)
        step("evidence"); stored = actual.copy(protectedEvidence = protectedEvidence)
    }
}
