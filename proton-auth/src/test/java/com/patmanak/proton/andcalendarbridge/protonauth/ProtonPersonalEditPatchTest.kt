// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test
class ProtonPersonalEditPatchTest {
    private val stamp = Instant.parse("2026-10-17T12:00:00Z")
    private val time = EventTime.Timed(Instant.parse("2026-10-17T09:00:00Z"), Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC)
    private val before = EventContent("Original", time, "Notes", "Place", colorRgb = 0xabcd12, details = EventDetails(listOf(EventReminder(5))))
    private val opaque = "X-OPAQUE;X-NOTE=\"a:b\":Keep  bytes\r\n\tand folding\r\n"
    private fun wrap(body: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:fixture\r\nBEGIN:VEVENT\r\nUID:fixture-uid\r\nDTSTAMP:20261016T120000Z\r\n" + body + "END:VEVENT\r\nEND:VCALENDAR\r\n"
    private fun parts(dates: String = "DTSTART:20261017T090000Z\r\nDTEND:20261017T100000Z\r\n") = ProtonEventTextParts(wrap("CREATED:20261015T120000Z\r\nLAST-MODIFIED:20261016T120000Z\r\nSEQUENCE:7\r\n" + dates + opaque),wrap("SUMMARY:Original\r\nDESCRIPTION:Notes\r\nLOCATION:Place\r\n" + opaque))
    private fun patch(after: EventContent, original: ProtonEventTextParts = parts(), base: EventContent = before) = ProtonSharedTextPatcher.patchPersonalAt(original,"fixture-uid",base,after,stamp)
    private fun refuse(action: () -> Unit) { try { action(); fail("Expected refusal") } catch (failure: ProtonPersonalPatchException) { assertNull(failure.cause); assertFalse(failure.toString().contains("Original")) } }
    @Test fun allTextAndTimeChangesAdvanceSequenceExactlyOnceAndPreserveRawUnrelatedProperties() {
        val after = before.copy(title="Next",description="New notes",location="New place",time=time.copy(end=time.end.plusSeconds(3600)))
        val result = patch(after)
        assertTrue(result.clearShared.contains("SEQUENCE:8\r\n")); assertFalse(result.clearShared.contains("SEQUENCE:9"))
        assertTrue(result.clearShared.contains("DTEND:20261017T110000Z\r\n"))
        assertTrue(result.clearShared.contains("LAST-MODIFIED:20261017T120000Z\r\n"))
        for (raw in listOf(result.clearShared,result.encryptedShared)) { assertTrue(raw.contains(opaque)); assertTrue(raw.contains("DTSTAMP:20261017T120000Z\r\n")) }
        val observed = ProtonSharedTextPatcher.observe(result,ProtonSharedTextProperty.entries.toSet(),"fixture-uid")
        assertEquals("Next",observed[ProtonSharedTextProperty.SUMMARY]); assertEquals("New notes",observed[ProtonSharedTextProperty.DESCRIPTION]); assertEquals("New place",observed[ProtonSharedTextProperty.LOCATION])
    }
    @Test fun bothKindTransitionsRetainExclusiveEndAndZoneOnlyRetainsInstants() {
        val day = EventTime.AllDay(LocalDate.of(2026,10,17),LocalDate.of(2026,10,20))
        val dates = "DTSTART;VALUE=DATE:20261017\r\nDTEND;VALUE=DATE:20261020\r\n"
        assertTrue(patch(before.copy(time=day)).clearShared.contains(dates))
        assertTrue(patch(before,parts(dates),before.copy(time=day)).clearShared.contains("DTSTART:20261017T090000Z\r\nDTEND:20261017T100000Z\r\n"))
        assertTrue(patch(before.copy(time=time.copy(zone=ZoneId.of("Europe/Paris")))).clearShared.contains("DTSTART;TZID=Europe/Paris:20261017T110000\r\n"))
        assertTrue(patch(before.copy(time=time.copy(zone=ZoneId.of("Etc/UTC")))).clearShared.contains("DTSTART;TZID=Etc/UTC:20261017T090000\r\n"))
    }
    @Test fun personalOnlyEditAdvancesOneSequenceAndPreservesSharedTextAndUnrelatedFields() {
        val result = patch(before.copy(details = EventDetails(), colorRgb = null))
        assertTrue(result.clearShared.contains("SEQUENCE:8\r\n"))
        assertTrue(result.encryptedShared.contains("SUMMARY:Original\r\nDESCRIPTION:Notes\r\nLOCATION:Place\r\n"))
        assertTrue(result.clearShared.contains(opaque)); assertTrue(result.encryptedShared.contains(opaque))
    }
    @Test fun absentWebCreationAndModificationDatesStayAbsent() {
        val source = parts()
        for (created in listOf(false, true)) for (modified in listOf(false, true)) {
            var clear = source.clearShared
            if (!created) clear = clear.replace("CREATED:20261015T120000Z\r\n", "")
            if (!modified) clear = clear.replace("LAST-MODIFIED:20261016T120000Z\r\n", "")
            val result = patch(before.copy(title = "Next"), ProtonEventTextParts(clear.removeSuffix("\r\n"), source.encryptedShared.removeSuffix("\r\n")))
            assertEquals(created, result.clearShared.contains("CREATED:"))
            assertEquals(modified, result.clearShared.contains("LAST-MODIFIED:"))
            assertTrue(result.clearShared.contains("SEQUENCE:8\r\n"))
            assertFalse(result.clearShared.endsWith("\r\n")); assertFalse(result.encryptedShared.endsWith("\r\n"))
        }
    }
    @Test fun optionalDatesStillRequireUniqueValidChronology() {
        val source = parts()
        for (clear in listOf(source.clearShared.replace("CREATED:20261015T120000Z", "CREATED:20261018T120000Z"),
            source.clearShared.replace("LAST-MODIFIED:20261016T120000Z", "LAST-MODIFIED:20261018T120000Z"),
            source.clearShared.replace("CREATED:20261015T120000Z", "CREATED:20261015T120000Z\r\nCREATED:20261015T120000Z"),
            source.clearShared.replace("CREATED:20261015T120000Z", "CREATED;X-PARAM=value:20261015T120000Z"),
            source.clearShared.replace("CREATED:20261015T120000Z", "CREATED:invalid"))) {
            refuse { patch(before.copy(title = "Next"), ProtonEventTextParts(clear, source.encryptedShared)) }
        }
    }
    @Test fun duplicatePropertiesAndParticipantsAreRefused() {
        val source = parts()
        for (line in listOf("DTSTART:20261017T090000Z", "ATTENDEE:mailto:fixture@example.invalid", "RRULE:FREQ=DAILY"))
            refuse { patch(before.copy(title="Next"), ProtonEventTextParts(source.clearShared.replace(opaque,line+"\r\n"+opaque),source.encryptedShared)) }
        refuse { patch(before.copy(title="Next"),ProtonEventTextParts(source.clearShared,source.encryptedShared.replace("SUMMARY:Original","SUMMARY:Original\r\nSUMMARY:Duplicate"))) }
    }
}
