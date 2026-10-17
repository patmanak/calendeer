// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.EventTime
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Synthetic offline policy fixtures, never service or dispatch evidence. */
class ProtonTimeEventUpdateTest {
    private val before = timed(3600, 7200)
    private val after = timed(10800, 14400)
    private val clear = json("""{"Type":2,"Data":"new-clear","Signature":"new-clear-signature","Author":"synthetic@example.invalid"}""")
    private val encrypted = json("""{"Type":3,"Data":"new-secret","Signature":"new-secret-signature","Author":"synthetic@example.invalid"}""")
    private val original = json("""{
        "ID":"synthetic-event","CalendarID":"synthetic-calendar","UID":"synthetic-uid",
        "SharedKeyPacket":"synthetic-key-packet","Permissions":3,"IsOrganizer":1,"IsPersonalSingleEdit":false,
        "CreateTime":1,"ModifyTime":2,"LastEditTime":2,"StartTime":3600,"EndTime":7200,
        "StartTimezone":"UTC","EndTimezone":"UTC","FullDay":0,
        "CalendarEvents":[],"AttendeesEvents":[],"Notifications":[],"Attendees":[],"Exdates":[],
        "NotificationOccurrences":[],"AddressID":"synthetic-address","SharedEventID":"synthetic-shared",
        "SharedEvents":[
          {"Type":2,"Data":"old-clear","Signature":"old-clear-signature","Author":"synthetic@example.invalid"},
          {"Type":3,"Data":"old-secret","Signature":"old-secret-signature","Author":"synthetic@example.invalid"}]
    }""")
    private val actual = json("""{
        "ID":"synthetic-event","CalendarID":"synthetic-calendar","UID":"synthetic-uid",
        "SharedKeyPacket":"synthetic-key-packet","Permissions":3,"IsOrganizer":1,"IsPersonalSingleEdit":false,
        "CreateTime":1,"ModifyTime":5,"LastEditTime":5,"StartTime":10800,"EndTime":14400,
        "StartTimezone":"UTC","EndTimezone":"UTC","FullDay":0,
        "CalendarEvents":[],"AttendeesEvents":[],"Notifications":[],"Attendees":[],"Exdates":[],
        "NotificationOccurrences":[],"AddressID":"synthetic-address","SharedEventID":"synthetic-shared",
        "SharedEvents":[
          {"Type":2,"Data":"new-clear","Signature":"new-clear-signature","Author":"synthetic@example.invalid"},
          {"Type":3,"Data":"new-secret","Signature":"new-secret-signature","Author":"synthetic@example.invalid"}]
    }""")
    private val context = buildJsonObject {
        ProtonSimpleEventUpdate.CONTEXT_KEYS.forEach { put(it, "synthetic-$it") }
        put("author", "synthetic@example.invalid")
    }

    @Test fun requestKeepsLiteralWireAndExplicitUnqualifiedBreakingChangeHypothesis() {
        val expected = json("""{"MemberID":"synthetic-member","IsImport":0,"Events":[{"ID":"synthetic-event","Event":{
          "Permissions":1,"IsOrganizer":1,"IsBreakingChange":0,"IsPersonalSingleEdit":false,
          "Notifications":[],"Color":null,"VideoConferencingData":null,"SharedEventContent":[
          {"Type":2,"Data":"new-clear","Signature":"new-clear-signature"},
          {"Type":3,"Data":"new-secret","Signature":"new-secret-signature"}]}}]}""")
        assertEquals(expected, request())
        assertEquals(ProtonSimpleEventUpdate.request(original, clear, encrypted, true, context), request())
        simpleEventUpdateOnlyBytes(request().toString()).fill(0)
    }

    @Test fun temporalReadbackRequiresChangedDatesWhileHistoricalTextRuleStaysExact() {
        assertTrue(matches())
        assertFalse(ProtonSimpleEventUpdate.matchesReadback(original, clear, encrypted, actual))
        assertFalse(matches(row = actual.with("StartTime", 3600).with("EndTime", 7200)))
        assertTrue(ProtonSimpleEventUpdate.matchesReadback(original, clear, encrypted,
            actual.with("StartTime", 3600).with("EndTime", 7200)))
    }

    @Test fun allFiveOriginalMetadataFieldsBindToCompleteBeforeTime() {
        for ((name, value) in listOf("StartTime" to JsonPrimitive(3601), "EndTime" to JsonPrimitive(7201),
            "StartTimezone" to JsonPrimitive("Z"), "EndTimezone" to JsonPrimitive("Europe/Paris"), "FullDay" to JsonPrimitive(1))) {
            val stale = JsonObject(original + (name to value))
            refused { request(source = stale) }
            assertFalse(matches(source = stale))
            refused { request(source = JsonObject(original - name)) }
        }
        refused { request(source = original.with("StartTime", 10800).with("EndTime", 14400)) }
    }

    @Test fun allFiveReadbackMetadataFieldsMustExactlyEqualAfterTime() {
        for ((name, value) in listOf("StartTime" to JsonPrimitive(10801), "EndTime" to JsonPrimitive(14401),
            "StartTimezone" to JsonPrimitive("Z"), "EndTimezone" to JsonPrimitive("Europe/Paris"), "FullDay" to JsonPrimitive(1),
            "StartTime" to JsonPrimitive("10800"))) {
            assertFalse(name, matches(row = JsonObject(actual + (name to value))))
            assertFalse(name, matches(row = JsonObject(actual - name)))
        }
    }

    @Test fun namedZonesRemainExactAndUtcDomainUsesApiUtc() {
        val old = timed(3600, 7200, "Europe/Paris")
        val next = timed(10800, 14400, "Europe/Paris")
        val source = original.with("StartTimezone", "Europe/Paris").with("EndTimezone", "Europe/Paris")
        val result = actual.with("StartTimezone", "Europe/Paris").with("EndTimezone", "Europe/Paris")
        assertEquals(request(), request(source, old, next))
        assertTrue(matches(source, result, old, next))
        assertFalse(matches(source, result.with("EndTimezone", "CET"), old, next))
        assertTrue(matches()) // Domain Z is deliberately represented by API UTC.
    }

    @Test fun noChangeFalseChangedKindAndZoneTransitionsAreRefused() {
        refused { ProtonTimeEventUpdate.request(original, clear, encrypted, false, context, before, after) }
        refused { request(next = before) }
        assertFalse(matches(next = before))
        val allDay = EventTime.AllDay(LocalDate.of(1970, 1, 1), LocalDate.of(1970, 1, 2))
        for ((old, next) in listOf(before to allDay, allDay to after,
            before to timed(10800, 14400, "Europe/Paris"))) {
            refused { request(old = old, next = next) }
            assertFalse(matches(old = old, next = next))
        }
    }

    @Test fun allDayUtcMetadataKeepsExclusiveNextDate() {
        val old = EventTime.AllDay(LocalDate.of(1970, 1, 1), LocalDate.of(1970, 1, 2))
        val next = EventTime.AllDay(LocalDate.of(1970, 1, 3), LocalDate.of(1970, 1, 4))
        val source = original.with("StartTime", 0).with("EndTime", 86400).with("FullDay", 1)
        val result = actual.with("StartTime", 172800).with("EndTime", 259200).with("FullDay", 1)
        assertEquals(request(), request(source, old, next))
        assertTrue(matches(source, result, old, next))
    }

    @Test fun fixedOffsetsFloatingApiMetadataSubsecondsAndBoundsAreRefused() {
        val unsupported = listOf(
            EventTime.Timed(Instant.ofEpochSecond(10800), Instant.ofEpochSecond(14400), ZoneOffset.ofHours(1)),
            timed(10800, 14400, "Etc/GMT-1"),
            EventTime.Timed(Instant.ofEpochSecond(10800, 1), Instant.ofEpochSecond(14400), ZoneOffset.UTC),
            EventTime.Timed(Instant.ofEpochSecond(-1), Instant.ofEpochSecond(1), ZoneOffset.UTC),
            EventTime.Timed(Instant.parse("9999-12-31T23:59:59Z"), Instant.parse("+10000-01-01T00:00:00Z"), ZoneOffset.UTC))
        for (next in unsupported) {
            refused { request(next = next) }
            assertFalse(matches(next = next))
            refused { request(old = next) }
            assertFalse(matches(old = next))
        }
        refused { request(source = original.with("StartTimezone", "").with("EndTimezone", "")) }
        for (zone in listOf("+01:00", "Etc/GMT-1")) {
            val source = original.with("StartTimezone", zone).with("EndTimezone", zone)
            val result = actual.with("StartTimezone", zone).with("EndTimezone", zone)
            refused { request(source, timed(3600, 7200, zone), timed(10800, 14400, zone)) }
            assertFalse(matches(source, result, timed(3600, 7200, zone), timed(10800, 14400, zone)))
        }
    }

    @Test fun ambiguousLocalEndpointsAreRefusedEvenWithExplicitInstant() {
        val overlap = EventTime.Timed(Instant.parse("2026-10-25T00:30:00Z"),
            Instant.parse("2026-10-25T02:30:00Z"), ZoneId.of("Europe/Paris"))
        val old = timed(3600, 7200, "Europe/Paris")
        val source = original.with("StartTimezone", "Europe/Paris").with("EndTimezone", "Europe/Paris")
        val result = actual.with("StartTimezone", "Europe/Paris").with("EndTimezone", "Europe/Paris")
            .with("StartTime", 1792888200).with("EndTime", 1792895400)
        refused { request(source, old, overlap) }
        assertFalse(matches(source, result, old, overlap))
    }

    @Test fun namedZoneElapsedDurationAcrossDstKeepsLiteralEpochMetadata() {
        val old = EventTime.Timed(Instant.parse("2026-03-28T12:00:00Z"),
            Instant.parse("2026-03-28T13:00:00Z"), ZoneId.of("Europe/Paris"))
        val next = EventTime.Timed(Instant.parse("2026-03-28T23:00:00Z"),
            Instant.parse("2026-03-29T22:00:00Z"), ZoneId.of("Europe/Paris"))
        val source = original.with("StartTime", 1774699200).with("EndTime", 1774702800)
            .with("StartTimezone", "Europe/Paris").with("EndTimezone", "Europe/Paris")
        val result = actual.with("StartTime", 1774738800).with("EndTime", 1774821600)
            .with("StartTimezone", "Europe/Paris").with("EndTimezone", "Europe/Paris")
        assertEquals(request(), request(source, old, next))
        assertTrue(matches(source, result, old, next))
    }

    @Test fun originalAndActualKeepClosedSimpleEventEligibility() {
        for ((name, value) in listOf("FutureField" to JsonPrimitive(1), "Attendees" to JsonArray(listOf(JsonPrimitive("guest"))),
            "Notifications" to JsonArray(listOf(JsonPrimitive("alarm"))), "Color" to JsonPrimitive("#123456"),
            "RRule" to JsonPrimitive("FREQ=DAILY"), "IsOrganizer" to JsonPrimitive(0))) {
            val source = JsonObject(original + (name to value))
            refused { request(source = source) }
            assertFalse(matches(source = source))
            assertFalse(matches(row = JsonObject(actual + (name to value))))
        }
    }

    @Test fun nonTemporalFieldsAndSignedPartsMustRemainExact() {
        for ((name, value) in listOf("ID" to JsonPrimitive("different"), "CalendarID" to JsonPrimitive("different"),
            "SharedKeyPacket" to JsonPrimitive("different"), "AddressID" to JsonPrimitive("different"),
            "Permissions" to JsonPrimitive(1), "CreateTime" to JsonPrimitive(0)))
            assertFalse(name, matches(row = JsonObject(actual + (name to value))))
        for (name in listOf("Data", "Signature", "Author")) {
            val changed = JsonObject(clear + (name to JsonPrimitive("different")))
            assertFalse(matches(row = JsonObject(actual + ("SharedEvents" to JsonArray(listOf(changed, encrypted))))))
        }
        assertFalse(matches(row = JsonObject(actual + ("SharedEvents" to original.getValue("SharedEvents")))))
        assertFalse(matches(row = JsonObject(actual - "AddressID")))
    }

    @Test fun modificationClockRulesKeepOriginalBaselineAndPresence() {
        assertFalse(matches(row = actual.with("ModifyTime", 1).with("LastEditTime", 1)))
        assertFalse(matches(row = actual.with("LastEditTime", 1)))
        assertFalse(matches(row = actual.with("LastEditTime", 4)))
        assertTrue(matches(row = actual.with("LastEditTime", 2)))
        assertFalse(matches(row = JsonObject(actual - "LastEditTime")))
        assertFalse(matches(source = JsonObject(original - "LastEditTime")))
        assertTrue(matches(source = JsonObject(original - "LastEditTime"), row = JsonObject(actual - "LastEditTime")))
    }

    @Test fun originalKnownOptionalFieldsCannotBeLostBySyntheticTemporalBaseline() {
        assertFalse(matches(row = JsonObject(actual - "NotificationOccurrences")))
        assertFalse(matches(row = JsonObject(actual - "SharedEventID")))
        val source = original.with("ModifyTime", 10).with("LastEditTime", 10)
        assertFalse(matches(source = source))
        assertTrue(matches(source = source, row = actual.with("ModifyTime", 12).with("LastEditTime", 12)))
    }

    @Test fun contextAndPartValidationRemainTheExistingClosedWireProfile() {
        refused { ProtonTimeEventUpdate.request(original, clear, encrypted, true, JsonObject(context - "member"), before, after) }
        refused { ProtonTimeEventUpdate.request(original, clear.with("Author", "other@example.invalid"), encrypted, true, context, before, after) }
        refused { ProtonTimeEventUpdate.request(original, clear.with("Signature", ""), encrypted, true, context, before, after) }
    }

    private fun request(source: JsonObject = original, old: EventTime = before, next: EventTime = after) =
        ProtonTimeEventUpdate.request(source, clear, encrypted, true, context, old, next)
    private fun matches(source: JsonObject = original, row: JsonObject = actual, old: EventTime = before, next: EventTime = after) =
        ProtonTimeEventUpdate.matchesReadback(source, clear, encrypted, row, old, next)
    private fun refused(action: () -> Unit) {
        try { action(); fail("Temporal profile must refuse") } catch (_: Exception) { }
    }
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
    private fun timed(start: Long, end: Long, zone: String = "Z") =
        EventTime.Timed(Instant.ofEpochSecond(start), Instant.ofEpochSecond(end), ZoneId.of(zone))
    private fun JsonObject.with(name: String, value: Long) = JsonObject(this + (name to JsonPrimitive(value)))
    private fun JsonObject.with(name: String, value: String) = JsonObject(this + (name to JsonPrimitive(value)))
}
