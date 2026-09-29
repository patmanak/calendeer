package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ProtonICalendarTest {
    @Test fun confirmationBindsEverySignedPartUidToExpectedUid() {
        val date = ics("DTSTART:20261001T100000Z\nDTEND:20261001T110000Z")
        val display = ics("SUMMARY:Meet")
        assertEquals("Meet", ProtonICalendar.parse(listOf(date, display), expectedUid = "synthetic").title)
        try {
            ProtonICalendar.parse(listOf(date, display), expectedUid = "foreign")
            fail("Expected signed UID refusal")
        } catch (failure: RemoteImportException) { assertEquals(RemoteImportFailure.UNSUPPORTED, failure.reason) }
    }
    @Test fun parsesTimedEventAndMergesEncryptedParts() {
        val content = ProtonICalendar.parse(listOf(ics("DTSTART:20261001T100000Z\nDTEND:20261001T110000Z\nSUMMARY:Meet"),
            ics("DESCRIPTION:Notes\nLOCATION:Room")))
        assertEquals("Meet", content.title)
        assertEquals("Notes", content.description)
        assertEquals("Room", content.location)
        assertEquals(Instant.parse("2026-10-01T10:00:00Z"), (content.time as EventTime.Timed).start)
    }
    @Test fun allDayDatesStayDatesAcrossTimeZonesAndNormalizeLegacyEnd() {
        val time = ProtonICalendar.parse(listOf(ics("DTSTART;VALUE=DATE:20261001\nDTEND;VALUE=DATE:20261001"))).time
        assertEquals(EventTime.AllDay(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02")), time)
    }
    @Test fun tzidKeepsNamedZoneAcrossDst() {
        val time = ProtonICalendar.parse(listOf(ics("DTSTART;TZID=Europe/Paris:20261025T100000\nDTEND;TZID=Europe/Paris:20261025T110000"))).time as EventTime.Timed
        assertEquals(ZoneId.of("Europe/Paris"), time.zone)
        assertEquals(Instant.parse("2026-10-25T09:00:00Z"), time.start)
    }
    @Test fun rejectsUnsupportedRecurrenceFormsRatherThanFlattening() {
        for (line in listOf("RRULE:FREQ=SECONDLY", "RDATE;VALUE=PERIOD:20261002T100000Z/20261002T110000Z", "EXDATE:20261002T100000Z", "RECURRENCE-ID;RANGE=THISANDFUTURE:20261002T100000Z", "EXRULE:FREQ=DAILY")) {
            unsupported(ics("DTSTART:20261001T100000Z\nDTEND:20261001T110000Z\n$line"))
        }
    }
    @Test fun floatingMissingAndCancelledEventsAreNotSilentlyProjected() {
        unsupported(ics("DTSTART:20261001T100000\nDTEND:20261001T110000"))
        unsupported(ics("DTSTART:20261001T100000Z"))
        unsupported(ics("DTSTART:20261001T100000Z\nDTEND:20261001T110000Z\nSTATUS:CANCELLED"))
        unsupported(ics("DTSTART:20261001T100000Z\nDTEND:20261001T110000"))
        unsupported(ics("DTSTART:20261001T100000Z\nDTEND:20261001T110000Z\nRRULE:not-a-rule"))
    }
    @Test fun floatingOverrideCannotBorrowUtcFromPreviousPart() {
        try {
            ProtonICalendar.parse(listOf(ics("DTSTART:20261001T100000Z\nDTEND:20261001T110000Z"), ics("DTSTART:20261001T100000")))
            fail("Expected unsupported")
        } catch (failure: RemoteImportException) { assertEquals(RemoteImportFailure.UNSUPPORTED, failure.reason) }
    }
    private fun unsupported(text: String) {
        try { ProtonICalendar.parse(listOf(text)); fail("Expected unsupported") } catch (failure: RemoteImportException) { assertEquals(RemoteImportFailure.UNSUPPORTED, failure.reason) }
    }
    private fun ics(properties: String) = "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Synthetic fixture//EN\nBEGIN:VEVENT\nUID:synthetic\n$properties\nEND:VEVENT\nEND:VCALENDAR\n".replace("\n", "\r\n")
}
