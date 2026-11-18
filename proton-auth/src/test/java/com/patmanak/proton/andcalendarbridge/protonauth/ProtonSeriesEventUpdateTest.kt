// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ProtonSeriesEventUpdateTest {
    private val f = PersonalSubmissionFixture()
    private val before = f.intent.base.content.copy(recurrence = EventRecurrence("synthetic-uid", "FREQ=DAILY;COUNT=4"))
    private val original = JsonObject(f.originalRow + ("RRule" to JsonPrimitive(before.recurrence!!.rule)))
    private val clear = f.fragments.clearSharedPart
    private val encrypted = f.fragments.encryptedSharedPart
    private fun request(after: EventContent, row: JsonObject = original, base: EventContent = before,
        defaults: JsonObject? = null) = ProtonSeriesEventUpdate.request(row, clear, encrypted, true, f.context, base, after, defaults)
    @Test fun removalRequiresTheSignedRecurrenceSlotAndAllExactOuterExclusions() {
        val time = before.time as EventTime.Timed
        val slot = RecurrencePoint.Timed(time.start)
        val base = before.copy(recurrence = before.recurrence!!.copy(excludedDates = listOf(slot)))
        val row = JsonObject(original + ProtonPersonalEventUpdate.metadata(time) +
            ("Exdates" to JsonArray(listOf(JsonPrimitive(slot.instant.epochSecond)))))
        qualifySeriesRemovalRow(row, base)
        fun refused(row: JsonObject, content: EventContent) {
            try { qualifySeriesRemovalRow(row, content); fail("Invalid deletion family") }
            catch (_: IllegalArgumentException) { }
        }
        refused(JsonObject(row + ("Exdates" to JsonArray(emptyList()))), base)
        val moved = before.copy(recurrence = EventRecurrence(before.recurrence!!.uid, original = slot))
        val child = JsonObject(original + ProtonPersonalEventUpdate.metadata(time) + mapOf(
            "RRule" to JsonNull, "Exdates" to JsonArray(emptyList()), "RecurrenceID" to JsonPrimitive(slot.instant.epochSecond)))
        qualifySeriesRemovalRow(child, moved)
        refused(JsonObject(child + ("RecurrenceID" to JsonPrimitive(slot.instant.epochSecond + 1))), moved)
        refused(JsonObject(child + ("IsOrganizer" to JsonPrimitive(0))), moved)
    }

    private fun event(request: JsonObject) = (request.array("Events").single() as JsonObject).obj("Event")
    private fun actual(after: EventContent): JsonObject {
        val time = after.time as EventTime.Timed
        val zone = if (time.zone.normalized() == java.time.ZoneOffset.UTC) "UTC" else time.zone.id
        return JsonObject(original + mapOf("RRule" to JsonPrimitive(after.recurrence!!.rule), "ModifyTime" to JsonPrimitive(5),
            "StartTime" to JsonPrimitive(time.start.epochSecond), "EndTime" to JsonPrimitive(time.end.epochSecond),
            "StartTimezone" to JsonPrimitive(zone), "EndTimezone" to JsonPrimitive(zone),
            "SharedEvents" to JsonArray(listOf(clear, encrypted))))
    }
    @Test fun validDailyRuleAndPersonalMetadataReachSeparateProfile() {
        val after = before.copy(title = "New series")
        assertEquals(JsonNull, ProtonSeriesEventUpdate.qualify(original, before)["RRule"])
        assertEquals(JsonPrimitive(before.recurrence!!.rule), original["RRule"])
        val result = event(request(after))
        assertEquals(JsonPrimitive(0), result["IsBreakingChange"])
        assertEquals(original["Notifications"], result["Notifications"])
        assertEquals(original["Color"], result["Color"])
        assertTrue(ProtonSeriesEventUpdate.matchesReadback(original, clear, encrypted, actual(after), before, after))
        rejected { ProtonPersonalEventWire.qualify(original) }
    }
    @Test fun breakingBitBindsSemanticRuleAndUtcBoundsWhileZoneOnlyStaysZero() {
        val time = before.time as EventTime.Timed
        val variants = listOf(
            before.copy(recurrence = before.recurrence!!.copy(rule = "COUNT=4;FREQ=DAILY;INTERVAL=1")) to 0,
            before.copy(recurrence = before.recurrence!!.copy(rule = "FREQ=DAILY;COUNT=8")) to 1,
            before.copy(recurrence = before.recurrence!!.copy(rule = "FREQ=DAILY;COUNT=4;INTERVAL=2")) to 1,
            before.copy(time = time.copy(start = time.start.plusSeconds(60))) to 1,
            before.copy(time = time.copy(end = time.end.plusSeconds(60))) to 1,
            before.copy(time = time.copy(zone = ZoneId.of("Europe/Paris"))) to 0)
        for ((after, bit) in variants) assertEquals(JsonPrimitive(bit), event(request(after))["IsBreakingChange"])
    }
    @Test fun readbackQualifiesRuleInsteadOfIgnoringItAndPreservesUnknownFieldRefusal() {
        val after = before.copy(recurrence = before.recurrence!!.copy(rule = "FREQ=DAILY;COUNT=8"))
        assertTrue(ProtonSeriesEventUpdate.matchesReadback(original, clear, encrypted, actual(after), before, after))
        for (value in listOf(JsonNull, JsonPrimitive("FREQ=DAILY;COUNT=9"), buildJsonObject { put("freq", "DAILY") })) {
            assertFalse(ProtonSeriesEventUpdate.matchesReadback(original, clear, encrypted,
                JsonObject(actual(after) + ("RRule" to value)), before, after))
        }
        assertFalse(ProtonSeriesEventUpdate.matchesReadback(original, clear, encrypted,
            JsonObject(actual(after) + ("FutureField" to JsonPrimitive(1))), before, after))
    }
    @Test fun rejectsWrongUidChildrenExclusionsFlagsAndUnsupportedRulesBeforeRequest() {
        for ((name, value) in listOf("RRule" to JsonNull, "RRule" to JsonPrimitive("FREQ=WEEKLY;COUNT=4"),
            "RRule" to JsonPrimitive("FREQ=DAILY;COUNT=8"), "UID" to JsonPrimitive("foreign"),
            "RecurrenceID" to JsonPrimitive(1), "Exdates" to JsonArray(listOf(JsonPrimitive(1))),
            "IsPersonalSingleEdit" to JsonPrimitive(true), "IsOrganizer" to JsonPrimitive(0),
            "Permissions" to JsonPrimitive(0), "FutureField" to JsonNull)) {
            rejected { request(before, JsonObject(original + (name to value))) }
        }
        rejected { request(before.copy(recurrence = null)) }
        rejected { request(before.copy(recurrence = before.recurrence!!.copy(uid = "foreign"))) }
        rejected { request(before.copy(recurrence = before.recurrence!!.copy(rule = "FREQ=DAILY;COUNT=367"))) }
        rejected { request(before.copy(recurrence = before.recurrence!!.copy(excludedDates = listOf(RecurrencePoint.Timed(java.time.Instant.EPOCH))))) }
    }
    @Test fun readbackBindsTemporalAndPersonalFields() {
        for ((name, value) in listOf("StartTime" to JsonPrimitive(1), "EndTime" to JsonPrimitive(1),
            "StartTimezone" to JsonPrimitive("Foreign/Zone"), "FullDay" to JsonPrimitive(1),
            "Color" to JsonPrimitive("#123456"), "Notifications" to JsonArray(emptyList()),
            "IsPersonalSingleEdit" to JsonPrimitive(true))) {
            assertFalse(ProtonSeriesEventUpdate.matchesReadback(original, clear, encrypted,
                JsonObject(actual(before) + (name to value)), before, before))
        }
    }
    @Test fun inheritedNotificationDefaultsStayInheritedWhenUnchanged() {
        val row = JsonObject(original + ("Notifications" to JsonNull))
        assertEquals(JsonNull, event(request(before.copy(title = "Renamed"), row))["Notifications"])
        val changed = before.copy(details = EventDetails(listOf(EventReminder(10))))
        val inherited = PersonalSubmissionFixture(inherited = true)
        val defaults = ProtonPersonalMetadataEdit.captureDefaults(row, inherited.bootstrap, before.copy(recurrence = null), changed.copy(recurrence = null))
        val notifications = event(request(changed, row, defaults = defaults)).array("Notifications")
        assertTrue(notifications.any { (it as JsonObject).number("Type") == 0L })
        assertTrue(notifications.any { (it as JsonObject).string("Trigger") == "-PT10M" })
    }
    @Test fun calendarColorFallbackAndChangedReminderKeepExistingEmailAlarm() {
        val after = before.copy(colorRgb = null, details = EventDetails(listOf(EventReminder(10))))
        val event = event(request(after))
        assertEquals(JsonNull, event["Color"])
        assertEquals(2, event.array("Notifications").size)
        assertTrue(event.array("Notifications").any { (it as JsonObject).number("Type") == 0L })
        val read = JsonObject(actual(after) + mapOf("Notifications" to event.getValue("Notifications"), "Color" to JsonNull))
        assertTrue(ProtonSeriesEventUpdate.matchesReadback(original, clear, encrypted, read, before, after))
    }
    @Test fun allDayRuleChangeRetainsConservativeExclusiveUtcEndReadback() {
        val day = java.time.LocalDate.of(2026, 11, 16)
        val base = before.copy(time = EventTime.AllDay(day, day.plusDays(2)))
        val after = base.copy(recurrence = base.recurrence!!.copy(rule = "FREQ=DAILY;COUNT=8"))
        val metadata = buildJsonObject {
            put("StartTime", day.atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond())
            put("EndTime", day.plusDays(2).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond())
            put("StartTimezone", "UTC"); put("EndTimezone", "UTC"); put("FullDay", 1)
        }
        val row = JsonObject(original + metadata)
        assertEquals(JsonPrimitive(1), event(request(after, row, base))["IsBreakingChange"])
        val read = JsonObject(row + mapOf("RRule" to JsonPrimitive(after.recurrence!!.rule),
            "ModifyTime" to JsonPrimitive(5), "SharedEvents" to JsonArray(listOf(clear, encrypted))))
        assertTrue(ProtonSeriesEventUpdate.matchesReadback(row, clear, encrypted, read, base, after))
        assertFalse(ProtonSeriesEventUpdate.matchesReadback(row, clear, encrypted,
            JsonObject(read + ("EndTime" to JsonPrimitive(day.plusDays(9).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond()))), base, after))
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Unsupported series accepted") } catch (_: IllegalArgumentException) { }
        catch (_: ProtonSingleDispatchDenied) { }
    }
}
