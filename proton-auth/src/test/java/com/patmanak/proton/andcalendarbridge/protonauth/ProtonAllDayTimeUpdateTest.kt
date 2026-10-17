// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** API expectations measured independently from controlled one-day and multi-day CREATEs. */
class ProtonAllDayTimeUpdateTest {
    private val seed = TitleSubmissionFixture()
    private val context = JsonObject(seed.context + buildJsonObject {
        put("permissions", 16); put("flags", 1)
        for (name in listOf("calendarKey", "signingKey", "calendar")) put(name, ProtonSimpleEventUpdate.hash("synthetic-$name"))
    })
    private val before = days("2026-10-24", "2026-10-27")
    private val after = days("2026-10-25", "2026-10-29")
    private fun metadata(start: String, end: String) = buildJsonObject {
        put("FullDay", 1); put("StartTime", Instant.parse(start).epochSecond); put("EndTime", Instant.parse(end).epochSecond)
        put("StartTimezone", "UTC"); put("EndTimezone", "UTC")
    }
    private val old = metadata("2026-10-24T00:00:00Z", "2026-10-27T00:00:00Z")
    private val next = metadata("2026-10-25T00:00:00Z", "2026-10-29T00:00:00Z")
    private val source = JsonObject(seed.row + old)
    private val clear = seed.fragments.clearSharedPart
    private val encrypted = seed.fragments.encryptedSharedPart
    private val actual = JsonObject(source + next + mapOf("SharedEvents" to JsonArray(listOf(clear, encrypted))))
    private fun request(row: JsonObject = source, original: EventTime = before, replacement: EventTime = after) =
        ProtonTimeEventUpdate.request(row, clear, encrypted, true, context, original, replacement)
    private fun matches(row: JsonObject = actual, original: JsonObject = source, a: EventTime = before, b: EventTime = after) =
        ProtonTimeEventUpdate.matchesReadback(original, clear, encrypted, row, a, b)
    private fun refused(action: () -> Unit) { try { action(); fail("Expected all-day refusal") } catch (_: Exception) { } }

    @Test fun multiDayDateMoveAndResizeUsesUtcExclusiveMetadataAcrossAutumnDst() {
        assertEquals(ProtonSimpleEventUpdate.request(source, clear, encrypted, true, context), request())
        assertTrue(matches())
        assertFalse(matches(original = source, row = actual.with("EndTime", Instant.parse("2026-10-29T00:00:00Z").epochSecond - 1)))
        assertFalse(matches(row = actual.with("StartTime", Instant.parse("2026-10-24T22:00:00Z").epochSecond)))
        // Domain dates, wire fields and signed parts retain different roles.
        assertFalse(ProtonSimpleEventUpdate.matchesReadback(source, clear, encrypted, actual))
    }

    @Test fun singleDayEndsOnNextMidnightRatherThanSameDayOrLastSecond() {
        val a = days("2026-10-24", "2026-10-25"); val b = days("2026-10-25", "2026-10-26")
        val first = JsonObject(source + metadata("2026-10-24T00:00:00Z", "2026-10-25T00:00:00Z"))
        val result = JsonObject(actual + metadata("2026-10-25T00:00:00Z", "2026-10-26T00:00:00Z"))
        request(first, a, b); assertTrue(matches(result, first, a, b))
        for ((invalidBefore, invalidAfter) in listOf(
            Instant.parse("2026-10-24T00:00:00Z").epochSecond to Instant.parse("2026-10-25T00:00:00Z").epochSecond,
            Instant.parse("2026-10-24T23:59:59Z").epochSecond to Instant.parse("2026-10-25T23:59:59Z").epochSecond)) {
            assertFalse(matches(result.with("EndTime", invalidAfter), first, a, b))
            refused { request(first.with("EndTime", invalidBefore), a, b) }
        }
    }

    @Test fun eachOriginalAndReadbackFieldIsExactAndNonTemporalFieldsStayClosed() {
        for (name in listOf("StartTime", "EndTime", "FullDay", "StartTimezone", "EndTimezone")) {
            refused { request(JsonObject(source - name)) }
            assertFalse(matches(JsonObject(actual - name)))
            val invalid = if (name.endsWith("Timezone")) JsonPrimitive("Europe/Paris") else JsonPrimitive(0)
            refused { request(JsonObject(source + (name to invalid))) }
            assertFalse(matches(JsonObject(actual + (name to invalid))))
        }
        for (zone in listOf("Z", "", "Etc/UTC", "Europe/Paris")) {
            refused { request(source.with("StartTimezone", zone)) }
            assertFalse(matches(actual.with("EndTimezone", zone)))
        }
        assertFalse(matches(JsonObject(actual + ("FutureMetadata" to JsonPrimitive(1)))))
    }

    @Test fun protectedAllDayRequestReopensWithoutChangingHistoricalTimedFormat() = runTest {
        val values = mutableMapOf<String, String>()
        val protector = object : EventPayloadProtector {
            override fun seal(plain: String) = "synthetic-all-day-${values.size}".also { values[it] = plain }
            override fun open(protected: String) = values.getValue(protected)
        }
        val base = seed.content.copy(time = before, details = EventDetails())
        val intent = seed.intent.copy(base = seed.intent.base.copy(content = base), content = base.copy(time = after))
        val capsule = ProtonTimePreparationCodec(protector).sealLocal(intent, "synthetic-uid", seed.timestamp,
            ProtonTimestampedSharedTime(source, clear, encrypted, true))
        val codec = ProtonTimeUpdateRequestCodec(protector)
        val record = codec.seal(MutationId("synthetic-all-day-wire"), capsule, context)
        assertEquals(AdapterFormat("proton-calendar-time-update-request", 1), record.format)
        val reopened = codec.open(record)
        assertEquals(request(), reopened.request); assertEquals(source, reopened.fragments.originalRow)
        assertEquals(before, record.preparation.intent.base.content.time); assertEquals(after, record.preparation.intent.content.time)
        val root = Json.parseToJsonElement(values.getValue(record.protectedPayload)) as JsonObject
        val bound = root.getValue("preparation") as JsonObject
        for (name in listOf("base", "replacement")) {
            val time = ((bound.getValue(name) as JsonObject).getValue("time") as JsonObject)
            assertEquals(JsonPrimitive("ALL_DAY"), time["type"])
        }
    }

    @Test fun minimumAndMaximumSupportedCivilDatesRetainUtcBounds() {
        for ((a, b, aMeta, bMeta) in listOf(
            listOf("1970-01-01", "1970-01-02", "1970-01-01T00:00:00Z", "1970-01-02T00:00:00Z"),
            listOf("9999-12-29", "9999-12-30", "9999-12-29T00:00:00Z", "9999-12-30T00:00:00Z"))) {
            val start = LocalDate.parse(a); val end = LocalDate.parse(b)
            val prior = EventTime.AllDay(start, end); val later = EventTime.AllDay(start, end.plusDays(1))
            val first = JsonObject(source + metadata(aMeta, bMeta))
            val result = JsonObject(actual + metadata(aMeta, end.plusDays(1).toString()+"T00:00:00Z"))
            request(first, prior, later); assertTrue(matches(result, first, prior, later))
        }
    }
    private fun days(a: String, b: String) = EventTime.AllDay(LocalDate.parse(a), LocalDate.parse(b))
    private fun JsonObject.with(name: String, value: Long) = JsonObject(this + (name to JsonPrimitive(value)))
    private fun JsonObject.with(name: String, value: String) = JsonObject(this + (name to JsonPrimitive(value)))
}
