// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class ProtonPersonalEventUpdateTest {
    private fun metadata(time: EventTime): JsonObject = buildJsonObject {
        when(time) {
            is EventTime.AllDay -> {
                put("StartTime",time.start.atStartOfDay(ZoneOffset.UTC).toEpochSecond()); put("EndTime",time.endExclusive.atStartOfDay(ZoneOffset.UTC).toEpochSecond())
                put("StartTimezone","UTC");put("EndTimezone","UTC");put("FullDay",1)
            }
            is EventTime.Timed -> {
                put("StartTime",time.start.epochSecond);put("EndTime",time.end.epochSecond)
                val id = if(time.zone == ZoneOffset.UTC) "UTC" else time.zone.id
                put("StartTimezone",id);put("EndTimezone",id);put("FullDay",0)
            }
        }
    }
    @Test fun bothKindTransitionsAndZoneOnlyAndZoneWithDatesBindExactMetadataReadback() {
        val f = PersonalSubmissionFixture()
        val timed = f.intent.base.content.time as EventTime.Timed
        val day = EventTime.AllDay(LocalDate.of(2026,10,24),LocalDate.of(2026,10,27))
        val variants = listOf(timed to day,day to timed,timed to timed.copy(zone=ZoneId.of("Europe/Paris")),
            timed to timed.copy(zone=ZoneId.of("Etc/UTC")),timed to timed.copy(start=timed.start.plusSeconds(3600),end=timed.end.plusSeconds(7200),zone=ZoneId.of("Europe/Paris")))
        for((before,after) in variants) {
            val original = JsonObject(f.originalRow + metadata(before))
            val clear = f.fragments.clearSharedPart; val encrypted = f.fragments.encryptedSharedPart
            val request = ProtonPersonalEventUpdate.request(original,clear,encrypted,true,f.context,before,after)
            assertEquals(original["Notifications"],(request.array("Events").single() as JsonObject).obj("Event")["Notifications"])
            val actual = JsonObject(original + metadata(after) + mapOf("ModifyTime" to JsonPrimitive(5),"SharedEvents" to JsonArray(listOf(clear,encrypted))))
            assertTrue(ProtonPersonalEventUpdate.matchesReadback(original,clear,encrypted,actual,before,after))
            for((name,value) in metadata(after)) {
                val wrong = when(name) { "FullDay" -> JsonPrimitive(if(value.jsonPrimitive.int == 0) 1 else 0); "StartTime","EndTime" -> JsonPrimitive(value.jsonPrimitive.long+1);else -> JsonPrimitive("Foreign/Zone") }
                assertFalse(ProtonPersonalEventUpdate.matchesReadback(original,clear,encrypted,JsonObject(actual+(name to wrong)),before,after))
            }
        }
    }
}
