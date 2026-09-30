// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProtonCreateSubmissionCodecTest {
    @Test fun requestUsesMemberAtRootAndCreateOverwriteWithoutInventedActionOrIdentity() {
        val f = CreateSubmissionFixture()
        val request = ProtonCreateSubmissionCodec.request("synthetic-member", f.fragment)
        assertEquals(setOf("MemberID", "IsImport", "Events"), request.keys)
        assertEquals("synthetic-member", request.string("MemberID"))
        assertEquals(0L, request.number("IsImport"))
        val wrapper = request.array("Events").single().jsonObject
        assertEquals(setOf("Overwrite", "Event"), wrapper.keys)
        assertEquals(0L, wrapper.number("Overwrite"))
        assertEquals(f.fragment, wrapper.obj("Event"))
        assertFalse(request.toString().contains("Action"))
        assertFalse(wrapper.obj("Event").keys.any { it in setOf("ID", "CalendarID", "ModifyTime") })
    }

    @Test fun submissionRoundtripPreservesFrozenScopeCorrelationTimestampAndExpectedContent() {
        val f = CreateSubmissionFixture()
        val decoded = ProtonCreateSubmissionCodec.open(f.record, f.protector)
        assertEquals(f.draft, decoded.draft)
        assertEquals(f.uid, decoded.uid)
        assertEquals(f.context, decoded.context)
        assertEquals(f.envelope().obj("request"), decoded.request)
        assertFalse(decoded.toString().contains("synthetic"))
    }

    @Test fun oldFragmentFormatCannotBeInterpretedAsDispatchableSubmission() {
        val f = CreateSubmissionFixture()
        refused(CreatePreparationFailure.UNSUPPORTED) { ProtonCreateSubmissionCodec.open(f.record.copy(format = CREATE_PREPARATION_FORMAT), f.protector) }
        refused(CreatePreparationFailure.UNSUPPORTED) { ProtonCreateSubmissionCodec.open(f.record.copy(format = CREATE_SUBMISSION_FORMAT.copy(version = 2)), f.protector) }
        for (format in listOf(LOCAL_CREATE_PREPARATION_FORMAT.copy(version = 1), LOCAL_CREATE_PREPARATION_FORMAT)) {
            refused(CreatePreparationFailure.UNSUPPORTED) { ProtonCreateSubmissionCodec.open(f.record.copy(format = format),
                object : EventPayloadProtector {
                    override fun seal(plain: String): String = error("No crypto")
                    override fun open(protected: String): String = error("Local preparation must be refused before opening")
                }) }
        }
    }

    @Test fun envelopeScopeVersionAndCorrelationCannotBeSubstituted() {
        val f = CreateSubmissionFixture()
        for ((key, changed) in listOf("version" to JsonPrimitive(2), "account" to JsonPrimitive("foreign-user"),
            "calendar" to JsonPrimitive("foreign-calendar"), "generation" to JsonPrimitive(2), "correlation" to JsonPrimitive("foreign-correlation"),
            "unexpected" to JsonPrimitive(1))) {
            val envelope = JsonObject(f.envelope() + (key to changed))
            refused(CreatePreparationFailure.CONTEXT) { ProtonCreateSubmissionCodec.open(f.record.copy(protectedPayload = f.protector.seal(envelope.toString())), f.protector) }
        }
    }

    @Test fun memberImportOverwriteExtraActionAndExistingEventIdentityAreRefused() {
        val f = CreateSubmissionFixture()
        val original = f.envelope().obj("request")
        fun wrap(value: JsonObject) = JsonObject(original + ("Events" to JsonArray(listOf(value))))
        val wrapper = original.array("Events").single().jsonObject
        val requests = listOf(JsonObject(original + ("MemberID" to JsonPrimitive("foreign-member"))),
            JsonObject(original + ("Action" to JsonPrimitive(0))),
            JsonObject(original + ("IsImport" to JsonPrimitive(1))),
            wrap(JsonObject(wrapper + ("Overwrite" to JsonPrimitive(1)))),
            wrap(JsonObject(wrapper + ("Action" to JsonPrimitive(0)))),
            wrap(JsonObject(wrapper + ("Event" to JsonObject(f.fragment + ("ID" to JsonPrimitive("existing-event")))))),
            wrap(JsonObject(wrapper + ("Event" to JsonObject(f.fragment + ("CalendarID" to JsonPrimitive("existing-calendar")))))))
        for (request in requests) refused(CreatePreparationFailure.CONTEXT) {
            ProtonCreateSubmissionCodec.open(f.record.copy(protectedPayload = f.protector.seal(f.envelope(request = request).toString())), f.protector)
        }
    }

    @Test fun expectedContentNormalizesNewlinesAndPreservesUtcOrAllDaySemantics() {
        val f = CreateSubmissionFixture()
        val content = f.draft.content.copy(title = "Synthetic\r\nsecond", description = "One\r\nTwo", location = "Room\r\nA",
            time = EventTime.Timed(Instant.parse("2026-10-02T14:00:00Z"), Instant.parse("2026-10-02T15:00:00Z"), ZoneOffset.UTC))
        val expected = ProtonCreateSubmissionCodec.expected(content)
        assertEquals("Synthetic\nsecond", expected.string("title"))
        assertEquals("One\nTwo", expected.string("description"))
        assertEquals("Room\nA", expected.string("location"))
        assertEquals("timed", expected.string("type"))
        assertEquals("Z", expected.string("zone"))
        val allDay = ProtonCreateSubmissionCodec.expected(f.draft.content)
        assertEquals("date", allDay.string("type"))
        assertEquals("2026-10-01", allDay.string("start"))
        assertEquals("2026-10-02", allDay.string("end"))
        assertFalse(allDay.containsKey("zone"))
    }

    private fun refused(reason: CreatePreparationFailure, action: () -> Any) {
        try { action(); fail("Invalid submission must fail closed") }
        catch (failure: CreatePreparationException) { assertEquals(reason, failure.reason); assertNull(failure.cause) }
    }
}
