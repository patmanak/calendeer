// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.IOException
import java.time.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test

class ProtonOccurrenceCreateAdapterTest {
    private class Fixture {
        val scope = CalendarScope(AccountId("fixture"), CalendarId("calendar"), 0)
        val originalSession = CatalogSession(UserId("fixture"), SessionId("session"))
        var session: CatalogSession? = originalSession
        val master = RemoteChange.Upsert(EventId("master"), Revision("revision"), EventContent("Master",
            EventTime.AllDay(LocalDate.parse("2030-04-01"), LocalDate.parse("2030-04-02")), details = EventDetails(),
            recurrence = EventRecurrence("master@calendeer.invalid", "FREQ=DAILY;COUNT=5")))
        val child = master.content.copy(title = "Edited", recurrence = null)
        val intent = LocalCreateIntent(LocalCreateReservation(MutationId("capture"), LocalEventId("11111111-2222-3333-4444-555555555555"),
            scope, EVENT_FORMAT, Instant.parse("2030-04-01T12:00:00Z")), child,
            occurrence = OccurrenceEditCandidate(master, RecurrencePoint.AllDay(LocalDate.parse("2030-04-02")), child))
        val reserved = LocalCreateSubmissionReservation.forOccurrence(intent, MutationId("22222222-3333-4444-5555-666666666666"), OCCURRENCE_CREATE_SUBMISSION_FORMAT)
        val protector = object : EventPayloadProtector { override fun seal(plain: String) = plain; override fun open(protected: String) = protected }
        val codec = ProtonOccurrenceSubmissionCodec(protector)
        var row = json("""{"ID":"master","CalendarID":"calendar","UID":"master@calendeer.invalid","RecurrenceID":null,"Exdates":[]}""")
        var context = json("""{"member":"member","key":"key-one"}""")
        val request = json("""{"MemberID":"member","IsImport":0,"Events":[{"Overwrite":0,"Event":{"Permissions":1,"IsOrganizer":1,"SharedKeyPacket":"packet","SharedEventContent":[{"Type":2,"Data":"clear","Signature":"signature","Author":""},{"Type":3,"Data":"encrypted","Signature":"signature","Author":""}],"Notifications":null,"Color":null,"IsPersonalSingleEdit":false}}]}""")
        var extraChild = false
        var sends = 0
        var reads = 0
        var preparations = 0
        var loseResponse = false
        var afterCrypto: () -> Unit = {}
        var onRead: () -> Unit = {}
        var sent: JsonObject? = null
        var childRow: JsonObject? = null
        var familyOverride: List<JsonObject>? = null
        var observedChild = RemoteChange.Upsert(EventId("child"), Revision("observed"), intent.occurrence!!.content)
        val remote = object : CreateSubmissionRemote {
            override suspend fun byUid(session: CatalogSession, calendar: String, uid: String, page: Int): List<JsonObject> {
                reads++; onRead(); check(calendar == "calendar" && uid == "master@calendeer.invalid" && page == 0)
                return familyOverride ?: if (extraChild) listOf(row, JsonObject(row + ("ID" to JsonPrimitive("child")))) else listOfNotNull(row, childRow)
            }
            override suspend fun event(session: CatalogSession, calendar: String, id: String): JsonObject {
                reads++; onRead(); return if (id == "master") row else checkNotNull(childRow)
            }
            override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject, allowsTransport: () -> Boolean): String = error("Historical sender must refuse")
            override suspend fun sendOccurrence(session: CatalogSession, calendar: String, request: JsonObject, allowsTransport: () -> Boolean): String {
                sends++; sent = request
                if (loseResponse) throw IOException("Synthetic lost response")
                return "{\"Code\":1000}"
            }
        }
        val events = object : EventImportRemote {
            override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage = error("No window reads")
            override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject { reads++; onRead(); return json("{}") }
        }
        val adapter = ProtonOccurrenceCreateAdapter(CatalogSessionSource { session }, EventContextRefresher { reads++; onRead() },
            CreateCalendarMetadataRemote { _, _ -> reads++; onRead(); json("{}") }, events, remote,
            OccurrenceCreateCryptography { _, captured, id, _, _, source ->
                preparations++
                val operation = request.array("Events").single() as JsonObject
                val generated = JsonObject(request + ("Events" to JsonArray(listOf(JsonObject(operation +
                    ("Event" to JsonObject(operation.obj("Event") + ("SharedKeyPacket" to JsonPrimitive("packet-$preparations")))))))))
                val record = CreateAttemptRecord(id, scope, OCCURRENCE_CREATE_SUBMISSION_FORMAT,
                    codec.seal(id, captured, context, source, generated, JsonNull))
                afterCrypto(); record
            }, SeriesFamilyEventVerifier { _, _, _, _, _ -> master }, protector, OccurrenceChildVerifier { _, _, _, _, _ -> observedChild })
        suspend fun prepared(): CreateAttemptRecord = adapter.prepare(reserved.draft).let {
            CreateAttemptRecord(reserved.attempt, scope, it.format, it.protectedPayload)
        }
        val port = ProtonCreateSubmissionAdapter(CalendarCreatePreparationPort { error("No preparation") }, protector,
            CatalogSessionSource { session }, remote, events, CreateEventVerifier { _, _, _, _, _, _ -> error("Wrong verifier") }, adapter)
        suspend fun unknownWithChild(): CreateAttemptRecord {
            val record = prepared()
            val event = codec.open(record, intent).request.array("Events").single().jsonObject.obj("Event")
            childRow = buildJsonObject {
                put("ID", "child"); put("UID", row.string("UID")); put("CalendarID", "calendar")
                put("RecurrenceID", Instant.parse("2030-04-02T00:00:00Z").epochSecond)
                put("StartTime", Instant.parse("2030-04-01T00:00:00Z").epochSecond)
                put("EndTime", Instant.parse("2030-04-02T00:00:00Z").epochSecond)
                put("StartTimezone", "UTC"); put("EndTimezone", "UTC"); put("FullDay", 1)
                for (key in listOf("Exdates", "CalendarEvents", "Attendees", "AttendeesEvents")) put(key, JsonArray(emptyList()))
                for (key in listOf("SharedKeyPacket", "IsOrganizer", "Permissions", "IsPersonalSingleEdit", "Notifications", "Color")) put(key, event.getValue(key))
                put("SharedEvents", JsonArray(event.array("SharedEventContent").map { JsonObject(it.jsonObject + ("Author" to JsonPrimitive("synthetic@example.invalid"))) }))
                put("CreateTime", 1); put("ModifyTime", 2)
            }
            resetCounts()
            return record.copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
        }
        fun journal(record: CreateAttemptRecord) = Journal(record)
        fun resetCounts() { reads = 0; preparations = 0; sends = 0 }
    }
    private class Journal(var value: CreateAttemptRecord) : CreateAttemptJournal {
        var claims = 0
        var allow = true
        var onClaim: () -> Unit = {}
        override fun allowsTransport(scope: CalendarScope) = allow && value.scope == scope
        override suspend fun remember(record: CreateAttemptRecord) { check(value == record) }
        override suspend fun read(scope: CalendarScope, id: MutationId) = value.takeIf { it.scope == scope && it.id == id }
        override suspend fun claim(expected: CreateAttemptRecord): Boolean {
            onClaim()
            if (!allow || value != expected || value.state != CreateAttemptState.PREPARED) return false
            claims++; value = value.copy(state = CreateAttemptState.OUTCOME_UNKNOWN); return true
        }
        override suspend fun retainEvidence(expectedUnknown: CreateAttemptRecord, protectedEvidence: String) {
            check(value == expectedUnknown && value.protectedEvidence == null)
            value = value.copy(protectedEvidence = protectedEvidence)
        }
    }
    @Test fun requalificationSendsTheExactRetainedRequestOnceThenUnknownIsOfflineInspectionOnly() = runTest {
        val f = Fixture(); val record = f.prepared(); val journal = f.journal(record)
        f.resetCounts()
        assertSame(CreateSubmissionObservation.Unknown, f.adapter.submit(record, f.reserved.draft, journal))
        assertEquals(f.codec.open(record, f.intent).request, f.sent); assertEquals(1, f.sends); assertEquals(1, journal.claims)
        assertNotNull(journal.value.protectedEvidence); assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, journal.value.state)
        f.resetCounts(); f.session = null
        f.adapter.submit(record, f.reserved.draft, journal)
        assertEquals(0, f.reads); assertEquals(0, f.preparations); assertEquals(0, f.sends); assertEquals(1, journal.claims)
    }
    @Test fun lostResponseRequiresTheExactAuthenticatedChildAndUnchangedMasterWithoutAnotherSend() = runTest {
        val f = Fixture()
        val record = f.unknownWithChild()
        val result = f.port.inspectCaptured(record, f.reserved.draft)
        assertTrue(result is CreateSubmissionObservation.Verified)
        assertEquals(CreateAttemptIdentity.of(record), (result as CreateSubmissionObservation.Verified).attempt)
        assertEquals(f.observedChild, result.event)
        assertEquals(0, f.preparations); assertEquals(0, f.sends)
    }
    @Test fun familyAmbiguityOrAnyUnprovenChildKeepsTheOutcomeUnknown() = runTest {
        for (variant in 0..10) {
            val f = Fixture()
            val record = f.unknownWithChild()
            val child = checkNotNull(f.childRow)
            when (variant) {
                0 -> f.childRow = null
                1 -> f.familyOverride = listOf(f.row, child, child)
                2 -> f.familyOverride = listOf(f.row, child, JsonObject(child + ("ID" to JsonPrimitive("other"))))
                3 -> f.row = JsonObject(f.row + ("changed" to JsonPrimitive(true)))
                4 -> f.childRow = JsonObject(child + ("SharedKeyPacket" to JsonPrimitive("other-request")))
                5 -> f.childRow = JsonObject(child + ("RecurrenceID" to JsonPrimitive(42)))
                6 -> f.childRow = JsonObject(child + ("Notifications" to JsonArray(emptyList())))
                7 -> f.childRow = JsonObject(child + ("Color" to JsonPrimitive("#415df0")))
                8 -> f.observedChild = f.observedChild.copy(content = f.observedChild.content.copy(title = "newer"))
                9 -> f.onRead = { f.session = null }
                10 -> f.childRow = JsonObject(child + ("FutureField" to JsonNull))
            }
            assertSame("variant $variant", CreateSubmissionObservation.Unknown, f.port.inspectCaptured(record, f.reserved.draft))
            assertEquals(0, f.preparations); assertEquals(0, f.sends)
        }
    }
    @Test fun inspectionRequiresCapturedUnknownAndConsistentProtectedResponseIdentity() = runTest {
        val f = Fixture()
        val record = f.unknownWithChild()
        assertSame(CreateSubmissionObservation.Unknown, f.port.inspect(record))
        assertSame(CreateSubmissionObservation.Unknown, f.port.inspectCaptured(record.copy(state = CreateAttemptState.PREPARED), f.reserved.draft))
        assertEquals(0, f.reads)
        fun evidence(id: String, correlation: String = record.id.value) = buildJsonObject {
            put("version", 1); put("account", f.scope.account.value); put("calendar", f.scope.calendar.value)
            put("generation", f.scope.generation); put("correlation", correlation)
            put("body", """{"Code":1000,"Responses":[{"Index":0,"Response":{"Code":1000,"Event":{"ID":"$id","CalendarID":"calendar","UID":"master@calendeer.invalid","ModifyTime":2}}}]}""")
        }.toString()
        for (mismatch in listOf(evidence("child", "foreign"), evidence("other"))) {
            assertSame(CreateSubmissionObservation.Unknown, f.port.inspectCaptured(record.copy(protectedEvidence = mismatch), f.reserved.draft))
        }
        assertTrue(f.port.inspectCaptured(record.copy(protectedEvidence = evidence("child")), f.reserved.draft) is CreateSubmissionObservation.Verified)
        f.onRead = { throw CancellationException("Synthetic readback cancellation") }
        assertTrue(runCatching { f.port.inspectCaptured(record, f.reserved.draft) }.exceptionOrNull() is CancellationException)
        assertEquals(0, f.preparations); assertEquals(0, f.sends)
    }
    @Test fun childAppearingBeforeOrDuringPreparationKeepsTheRequestUnclaimed() = runTest {
        for (late in listOf(false, true)) {
            val f = Fixture(); val record = f.prepared(); val journal = f.journal(record)
            if (late) f.afterCrypto = { f.extraChild = true } else f.extraChild = true
            assertTrue(runCatching { f.adapter.submit(record, f.reserved.draft, journal) }.isFailure)
            assertEquals(0, journal.claims); assertEquals(0, f.sends); assertEquals(record, journal.value)
        }
    }
    @Test fun changedContextOrSourceCannotSubstituteARegeneratedRequest() = runTest {
        for (changeKey in listOf(false, true)) {
            val f = Fixture(); val record = f.prepared(); val journal = f.journal(record)
            if (changeKey) f.context = json("""{"member":"member","key":"rotated"}""")
            else f.row = JsonObject(f.row + ("Future" to JsonPrimitive("different")))
            assertTrue(runCatching { f.adapter.submit(record, f.reserved.draft, journal) }.isFailure)
            assertEquals(0, journal.claims); assertEquals(0, f.sends); assertEquals(record, journal.value)
        }
    }
    @Test fun editAccessRevokedAfterOccurrenceClaimCannotAuthorizeTransportOrReplay() = runTest {
        val f = Fixture()
        val record = f.prepared()
        val retained = f.journal(record)
        var editable = true
        retained.onClaim = { editable = false }
        val journal = object : CreateAttemptJournal by retained {
            override fun allowsTransport(scope: CalendarScope) = editable && scope == f.scope
        }
        assertTrue(runCatching { f.adapter.submit(record, f.reserved.draft, journal) }.isFailure)
        assertEquals(record.copy(state = CreateAttemptState.OUTCOME_UNKNOWN), retained.value)
        assertEquals(0, f.sends)
        editable = true
        assertSame(CreateSubmissionObservation.Unknown, f.adapter.submit(record, f.reserved.draft, journal))
        assertEquals(0, f.sends)
        assertEquals(1, retained.claims)
    }

    @Test fun finalLocalClaimVetoAndSessionRevocationNeverSend() = runTest {
        val f = Fixture(); val record = f.prepared(); val journal = f.journal(record)
        journal.allow = false
        f.adapter.submit(record, f.reserved.draft, journal)
        assertEquals(record, journal.value); assertEquals(0, f.sends)
        journal.allow = true; journal.onClaim = { f.session = null }
        assertTrue(runCatching { f.adapter.submit(record, f.reserved.draft, journal) }.isFailure)
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, journal.value.state); assertEquals(0, f.sends)
    }
    @Test fun lostResponseNeverResendsAndCancellationIsNotTurnedIntoContextFailure() = runTest {
        val f = Fixture(); val record = f.prepared(); val journal = f.journal(record)
        f.loseResponse = true
        assertTrue(runCatching { f.adapter.submit(record, f.reserved.draft, journal) }.isFailure)
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, journal.value.state); assertEquals(1, f.sends)
        f.adapter.submit(record, f.reserved.draft, journal)
        assertEquals(1, f.sends)
        val job = Job()
        f.onRead = { job.cancel(); throw IllegalArgumentException("Synthetic callback failure after cancel") }
        val cancelled = runCatching { withContext(job) { f.adapter.prepare(f.reserved.draft) } }
        assertTrue(cancelled.exceptionOrNull() is CancellationException)
    }
    @Test fun unsupportedColourAndChangedRichTitleRefuseBeforeAnyContextRead() = runTest {
        val f = Fixture()
        for (content in listOf(f.child.copy(colorRgb = 0x123456), f.child.copy(title = "A & B"))) {
            val intent = f.intent.copy(content = content, occurrence = OccurrenceEditCandidate(f.master, f.intent.occurrence!!.original, content))
            val reserved = LocalCreateSubmissionReservation.forOccurrence(intent, f.reserved.attempt, OCCURRENCE_CREATE_SUBMISSION_FORMAT)
            val failure = runCatching { f.adapter.prepare(reserved.draft) }.exceptionOrNull() as CreatePreparationException
            assertEquals(CreatePreparationFailure.UNSUPPORTED, failure.reason); assertEquals(0, f.reads)
        }
    }
    companion object { private fun json(value: String) = Json.parseToJsonElement(value).jsonObject }
}
