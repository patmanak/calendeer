// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ProtonCalendarSignedProfileTest {
    private val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:synthetic-uid\r\nDTSTAMP:20261024T100000Z\r\nTRANSP:OPAQUE\r\nSTATUS:CONFIRMED\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
    private fun row(type: Int = 2, data: String = text): JsonObject {
        val original = PersonalSubmissionFixture(inherited = true).originalRow
        return JsonObject(original + ("CalendarEvents" to buildJsonArray { add(buildJsonObject {
            put("Type", type); put("Data", data); put("Signature", "synthetic-signature"); put("Author", "synthetic@example.invalid")
        }) }))
    }
    @Test fun signedCalendarDefaultsAreStructurallyEligibleWithoutDroppingThem() {
        ProtonPersonalEventWire.qualify(row())
    }
    @Test fun unsupportedPartitionsRemainHeld() {
        for (value in listOf(row(3), row(data = text.replace("TRANSP:OPAQUE", "EXDATE:20261025T100000Z")),
            row(data = text.replace("TRANSP:OPAQUE", "X-UNKNOWN:keep")), row(data = text.replace("STATUS:CONFIRMED", "STATUS:CANCELLED")))) {
            assertTrue(runCatching { ProtonPersonalEventWire.qualify(value) }.isFailure)
        }
    }
    @Test fun onlyTheBoundTimestampChangesAndOtherBytesSurvive() {
        val before = text.replace("TRANSP:OPAQUE\r\nSTATUS:CONFIRMED\r\n", "")
        val after = before.replace("20261024T100000Z", "20261024T110000Z")
        assertEquals(text.replace("20261024T100000Z", "20261024T110000Z"),
            ProtonSharedTextPatcher.calendarPart(text, "synthetic-uid", before, after))
        for (bad in listOf(text.replace("UID:synthetic-uid", "UID:foreign"),
            text.replace("DTSTAMP:20261024T100000Z", "DTSTAMP:20261024T090000Z"),
            text.replace("TRANSP:OPAQUE", "TRANSP:OPAQUE\r\nTRANSP:OPAQUE"),
            text.replace("TRANSP:OPAQUE", "BEGIN:VALARM\r\nACTION:DISPLAY\r\nEND:VALARM"),
            text.replace("TRANSP:OPAQUE", "TRANSP;X-UNKNOWN=value:OPAQUE"))) {
            assertTrue(runCatching { ProtonSharedTextPatcher.calendarPart(bad, "synthetic-uid", before, after) }.isFailure)
        }
    }
    @Test fun officialWebFramingKeepsItsMissingFinalCrLf() {
        val before = text.replace("TRANSP:OPAQUE\r\nSTATUS:CONFIRMED\r\n", "").removeSuffix("\r\n")
        val after = before.replace("20261024T100000Z", "20261024T110000Z")
        assertEquals(text.replace("20261024T100000Z", "20261024T110000Z").removeSuffix("\r\n"),
            ProtonSharedTextPatcher.calendarPart(text.removeSuffix("\r\n"), "synthetic-uid", before, after))
        assertTrue(runCatching { ProtonSharedTextPatcher.calendarPart(text.replace("\r\n", "\n"), "synthetic-uid") }.isFailure)
    }
    @Test fun versionedProfileSurvivesReopenAndExactRequestReadback() = runBlocking {
        val fixture = PersonalSubmissionFixture(inherited = true)
        val oldClear = fixture.fragments.clearSharedPart
        val originalClear = JsonObject(oldClear + ("Data" to JsonPrimitive(text.replace("TRANSP:OPAQUE\r\nSTATUS:CONFIRMED\r\n", ""))))
        val clear = JsonObject(originalClear + mapOf("Data" to JsonPrimitive(originalClear.string("Data").replace("20261024T100000Z", "20261024T110000Z")),
            "Signature" to JsonPrimitive("synthetic-new-signature")))
        val original = JsonObject(row() + ("SharedEvents" to JsonArray(listOf(originalClear, fixture.fragments.encryptedSharedPart))))
        val calendar = JsonObject(ProtonPersonalCalendarPart.original(original)!! + mapOf("Author" to oldClear.getValue("Author")))
        val row = JsonObject(original + ("CalendarEvents" to JsonArray(listOf(calendar))))
        val next = JsonObject(calendar + mapOf("Data" to JsonPrimitive(text.replace("20261024T100000Z", "20261024T110000Z")),
            "Signature" to JsonPrimitive("synthetic-calendar-signature")))
        val codec = ProtonPersonalPreparationCodec(fixture.protector)
        val prep = codec.sealLocal(fixture.intent, "synthetic-uid", fixture.timestamp,
            ProtonTimestampedSharedPersonal(row, clear, fixture.fragments.encryptedSharedPart, true, calendarPart = next))
        assertEquals(next, codec.openLocal(prep).calendarPart)
        val requestCodec = ProtonPersonalUpdateRequestCodec(fixture.protector)
        val request = requestCodec.seal(com.patmanak.proton.andcalendarbridge.sync.MutationId("synthetic-calendar-request"), prep, fixture.context)
        val opened = requestCodec.open(request)
        assertEquals(next, opened.fragments.calendarPart)
        val wire = (opened.request.array("Events").single() as JsonObject).obj("Event")
        assertEquals(JsonArray(listOf(JsonObject(next - "Author"))), wire["CalendarEventContent"])
        val actual = JsonObject(fixture.readRow + ("SharedEvents" to JsonArray(listOf(clear, fixture.fragments.encryptedSharedPart))) +
            ("CalendarEvents" to JsonArray(listOf(next))))
        assertTrue(ProtonPersonalEventUpdate.matchesReadback(row, clear, fixture.fragments.encryptedSharedPart, actual,
            fixture.intent.base.content, fixture.intent.content, calendarPart = next))
        assertFalse(ProtonPersonalEventUpdate.matchesReadback(row, clear, fixture.fragments.encryptedSharedPart,
            JsonObject(actual + ("CalendarEvents" to JsonArray(listOf(calendar)))), fixture.intent.base.content, fixture.intent.content, calendarPart = next))
        assertTrue(runCatching { codec.sealLocal(fixture.intent, "synthetic-uid", fixture.timestamp,
            ProtonTimestampedSharedPersonal(row, clear, fixture.fragments.encryptedSharedPart, true)) }.isFailure)
        val stored = fixture.values.getValue(prep.protectedPayload)
        fixture.values[prep.protectedPayload] = stored.replace("\"calendarSignedProfile\":{\"version\":1", "\"calendarSignedProfile\":{\"version\":2")
        assertTrue(runCatching { codec.openLocal(prep) }.isFailure)
    }
}
