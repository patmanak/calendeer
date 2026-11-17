// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProtonOccurrenceSubmissionCodecTest {
    private val scope = CalendarScope(AccountId("account"), CalendarId("calendar"), 0)
    private val master = RemoteChange.Upsert(EventId("master"), Revision("revision"), EventContent("Master",
        EventTime.AllDay(LocalDate.parse("2030-04-01"), LocalDate.parse("2030-04-02")), details = EventDetails(),
        recurrence = EventRecurrence("master@calendeer.invalid", "FREQ=DAILY;COUNT=5")))
    private val child = master.content.copy(title = "Changed", recurrence = null)
    private val intent = LocalCreateIntent(LocalCreateReservation(MutationId("capture"), LocalEventId("11111111-2222-3333-4444-555555555555"), scope,
        AdapterFormat("native", 1), Instant.parse("2030-04-01T12:00:00Z")), child,
        occurrence = OccurrenceEditCandidate(master, RecurrencePoint.AllDay(LocalDate.parse("2030-04-02")), child))
    private val id = MutationId("attempt")
    private val context = json("""{"member":"member","key":"opaque-fingerprint"}""")
    private val source = json("""{"ID":"master","CalendarID":"calendar","UID":"master@calendeer.invalid","Future":{"untouched":[1,null,"x"]}}""")
    private val request = json("""{"MemberID":"member","IsImport":0,"Events":[{"Overwrite":0,"Event":{"SharedKeyPacket":"new-key","SharedEventContent":[{"Type":2,"Data":"signed"}],"IsPersonalSingleEdit":false,"Notifications":null,"Color":null}}]}""")
    private val protector = object : EventPayloadProtector {
        override fun seal(plain: String) = plain
        override fun open(protected: String) = protected
    }
    private val codec = ProtonOccurrenceSubmissionCodec(protector)
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun sealed() = codec.seal(id, intent, context, source, request, JsonNull)
    private fun record(payload: String = sealed()) = CreateAttemptRecord(id, scope, OCCURRENCE_CREATE_SUBMISSION_FORMAT, payload)

    @Test fun roundTripPreservesWholeSourceRequestDefaultsAndUnknownState() {
        val unknown = record().copy(state = CreateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "opaque-evidence")
        val opened = codec.open(unknown, intent)
        assertEquals(intent, opened.intent); assertEquals(source, opened.source)
        assertEquals(context, opened.context); assertEquals(request, opened.request)
        assertEquals(JsonNull, opened.notificationDefaults)
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, unknown.state)
        assertEquals("opaque-evidence", unknown.protectedEvidence)
        val defaults = JsonArray(listOf(json("""{"Type":1,"Trigger":-600}""")))
        val explicit = record(codec.seal(id, intent, context, source, request, defaults))
        assertEquals(defaults, codec.open(explicit, intent).notificationDefaults)
    }
    @Test fun everyDispatchAndCaptureBindingIsRequired() {
        val record = record()
        for (changed in listOf(record.copy(id = MutationId("other")), record.copy(scope = scope.copy(generation = 1)),
            record.copy(format = SERIES_CREATE_SUBMISSION_FORMAT))) refused { codec.open(changed, intent) }
        val reservation = intent.reservation
        for (changed in listOf(intent.copy(reservation = reservation.copy(timestamp = reservation.timestamp.plusSeconds(1))),
            intent.copy(reservation = reservation.copy(id = MutationId("new-capture"))),
            intent.copy(content = child.copy(title = "Newer"), occurrence = OccurrenceEditCandidate(master,
                intent.occurrence!!.original, child.copy(title = "Newer"))),
            intent.copy(occurrence = OccurrenceEditCandidate(master.copy(revision = Revision("newer")),
                intent.occurrence!!.original, child)))) refused { codec.open(record, changed) }
        refused { ProtonCreateSubmissionCodec.open(record, protector) }
    }
    @Test fun malformedDuplicateUnknownOrOversizedEnvelopesAreRejected() {
        val plain = sealed()
        for (changed in listOf(plain.replaceFirst("{", "{\"version\":5,"),
            plain.replaceFirst("{", "{\"extra\":null,"), plain.replace("\"version\":5", "\"version\":4"),
            " ".repeat(4_194_305))) refused { codec.open(record(changed), intent) }
    }
    @Test fun updateMultipleOperationsForeignParentOrMemberCannotBeSealed() {
        for (changed in listOf(request.toString().replace("\"Overwrite\":0", "\"Overwrite\":1"),
            request.toString().replace("\"IsImport\":0", "\"IsImport\":1"),
            request.toString().replace("\"MemberID\":\"member\"", "\"MemberID\":\"foreign\"")))
            refused { codec.seal(id, intent, context, source, json(changed), JsonNull) }
        refused { codec.seal(id, intent, context, json(source.toString().replace("\"ID\":\"master\"", "\"ID\":\"other\"")), request, JsonNull) }
        refused { codec.seal(id, intent, context, source, JsonObject(request + ("Events" to JsonArray(emptyList()))), JsonNull) }
    }
    @Test fun sealingDetachesMutableJsonContainersAndReadbackCannotAlterTheRetainedBytes() {
        val mutableSource = source.toMutableMap()
        val mutableRequest = request.toMutableMap()
        val payload = codec.seal(id, intent, context, JsonObject(mutableSource), JsonObject(mutableRequest), JsonNull)
        mutableSource["Future"] = JsonNull; mutableRequest.clear()
        val retained = record(payload)
        assertEquals(source, codec.open(retained, intent).source)
        assertEquals(request, codec.open(retained, intent).request)
        assertEquals(payload, retained.protectedPayload)
    }
    private fun refused(block: () -> Unit) { assertThrows(Exception::class.java, block) }
}
