package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ProtonRecurrenceTest {
    @Test fun ambiguousNamedZoneRuleStartIsDeferredRatherThanShifted() {
        refuses("DTSTART;TZID=Europe/Paris:20301027T023000\nDTEND;TZID=Europe/Paris:20301027T033000\nRRULE:FREQ=DAILY;COUNT=3")
    }

    @Test fun finalMergedStartAndRuleDetermineAmbiguity() {
        val bad = ics("DTSTART;TZID=Europe/Paris:20301027T023000\nDTEND;TZID=Europe/Paris:20301027T033000")
        val good = ics("DTSTART;TZID=Europe/Paris:20301027T100000\nDTEND;TZID=Europe/Paris:20301027T110000")
        val rule = ics("RRULE:FREQ=DAILY;COUNT=3")
        try { ProtonICalendar.parse(listOf(good, bad, rule)); fail("Final ambiguous start must be deferred") }
        catch (failure: RemoteImportException) { assertEquals(RemoteImportFailure.UNSUPPORTED, failure.reason) }
        assertEquals(Instant.parse("2030-10-27T09:00:00Z"), (ProtonICalendar.parse(listOf(bad, good, rule)).time as EventTime.Timed).start)
    }

    @Test fun unambiguousBoundaryTimesAndUtcRulesRemainAdmitted() {
        for (hour in listOf("015900", "030000")) assertNotNull(parse(
            "DTSTART;TZID=Europe/Paris:20301027T$hour\nDTEND;TZID=Europe/Paris:20301027T040000\nRRULE:FREQ=DAILY;COUNT=3").recurrence)
        for (hour in listOf("003000", "013000")) assertEquals(Instant.parse("2030-10-27T${hour.substring(0, 2)}:30:00Z"),
            (parse("DTSTART:20301027T${hour}Z\nDTEND:20301027T033000Z\nRRULE:FREQ=DAILY;COUNT=3").time as EventTime.Timed).start)
        assertTrue(parse("DTSTART;VALUE=DATE:20301027\nDTEND;VALUE=DATE:20301028\nRRULE:FREQ=DAILY;COUNT=3").time is EventTime.AllDay)
    }

    @Test fun ambiguousDisplayWithoutRuleDoesNotBecomeAnUnsupportedMaster() {
        val dates = "DTSTART;TZID=Europe/Paris:20301027T023000\nDTEND;TZID=Europe/Paris:20301027T033000"
        assertNull(parse(dates).recurrence)
        assertFalse(parse("$dates\nRECURRENCE-ID:20301026T003000Z").recurrence!!.isMaster)
        assertNull(parse("$dates\nRDATE:20301028T013000Z").recurrence!!.rule)
    }

    private fun refuses(properties: String) {
        try { parse(properties); fail("Ambiguous rule start must be deferred") }
        catch (failure: RemoteImportException) { assertEquals(RemoteImportFailure.UNSUPPORTED, failure.reason) }
    }

    @Test fun weeklyRuleKeepsWallTimeZoneAndCanonicalRfc() {
        val event = parse("DTSTART;TZID=Europe/Paris:20261018T100000\nDTEND;TZID=Europe/Paris:20261018T110000\nRRULE:FREQ=WEEKLY;COUNT=4;BYDAY=SU")
        val time = event.time as EventTime.Timed
        assertEquals("Europe/Paris", time.zone.id)
        assertEquals("synthetic-series", event.recurrence!!.uid)
        assertEquals(setOf("FREQ=WEEKLY", "COUNT=4", "BYDAY=SU"), event.recurrence!!.rule!!.split(';').toSet())
    }

    @Test fun supportsMonthlyOrdinalAndYearlyUntilRules() {
        val monthly = timed("RRULE:FREQ=MONTHLY;BYDAY=-1MO;INTERVAL=2")
        assertTrue(monthly.recurrence!!.rule!!.contains("BYDAY=-1MO"))
        val yearly = timed("RRULE:FREQ=YEARLY;UNTIL=20301231T235959Z;BYMONTH=10")
        assertTrue(yearly.recurrence!!.rule!!.contains("UNTIL=20301231T235959Z"))
    }

    @Test fun allDayRuleExclusionsAndAdditionalDatesStayLiteralDates() {
        val event = parse("DTSTART;VALUE=DATE:20261024\nDTEND;VALUE=DATE:20261025\nRRULE:FREQ=DAILY;UNTIL=20261030\nEXDATE;VALUE=DATE:20261025,20261027\nRDATE;VALUE=DATE:20261101")
        assertEquals(listOf(day("2026-10-25"), day("2026-10-27")), event.recurrence!!.excludedDates)
        assertEquals(listOf(day("2026-11-01")), event.recurrence!!.additionalDates)
        assertTrue(event.recurrence!!.rule!!.contains("UNTIL=20261030"))
    }

    @Test fun namedZoneExdatesRespectDstAndMergeMultiplePropertiesAndParts() {
        val event = ProtonICalendar.parse(listOf(ics("DTSTART;TZID=Europe/Paris:20261018T100000\nDTEND;TZID=Europe/Paris:20261018T110000\nRRULE:FREQ=WEEKLY;COUNT=4\nEXDATE;TZID=Europe/Paris:20261025T100000"),
            ics("EXDATE;TZID=Europe/Paris:20261101T100000\nRDATE:20261108T090000Z\nRDATE:20261115T090000Z")))
        assertEquals(listOf(instant("2026-10-25T09:00:00Z"), instant("2026-11-01T09:00:00Z")), event.recurrence!!.excludedDates)
        assertEquals(2, event.recurrence!!.additionalDates.size)
    }

    @Test fun movedExceptionKeepsOriginalSlotSeparateFromNewStart() {
        val event = parse("DTSTART;TZID=Europe/Paris:20261027T120000\nDTEND;TZID=Europe/Paris:20261027T130000\nRECURRENCE-ID;TZID=Europe/Paris:20261025T100000")
        assertEquals(instant("2026-10-25T09:00:00Z"), event.recurrence!!.original)
        assertEquals(Instant.parse("2026-10-27T11:00:00Z"), (event.time as EventTime.Timed).start)
        assertFalse(event.recurrence!!.isMaster)
    }

    @Test fun cancelledExceptionSuppressesOriginalButIsNotATombstone() {
        val event = timed("RECURRENCE-ID:20261001T100000Z\nSTATUS:CANCELLED")
        assertTrue(event.recurrence!!.cancelled)
        assertEquals(instant("2026-10-01T10:00:00Z"), event.recurrence!!.original)
    }

    @Test fun movedAllDayOccurrenceCanBecomeTimedWithoutChangingOriginalKind() {
        val event = timed("RECURRENCE-ID;VALUE=DATE:20261001")
        assertEquals(day("2026-10-01"), event.recurrence!!.original)
        assertTrue(event.time is EventTime.Timed)
    }

    @Test fun datesOnlyMasterIsRepresentableAndSimpleEventsRemainUnchanged() {
        assertEquals(listOf(instant("2026-10-02T10:00:00Z")), timed("RDATE:20261002T100000Z").recurrence!!.additionalDates)
        assertNull(timed("SUMMARY:Simple").recurrence)
    }

    @Test fun rejectsLossyOrInvalidRules() {
        listOf("FREQ=HOURLY", "FREQ=DAILY;COUNT=0", "FREQ=DAILY;INTERVAL=0", "FREQ=DAILY;COUNT=2;UNTIL=20261003T100000Z",
            "FREQ=DAILY;BYHOUR=24", "FREQ=MONTHLY;BYMONTHDAY=0", "FREQ=MONTHLY;BYDAY=0MO", "FREQ=WEEKLY;BYDAY=2MO",
            "FREQ=WEEKLY;BYMONTHDAY=1", "FREQ=DAILY;BYSETPOS=1", "FREQ=DAILY;X-UNKNOWN=1", "FREQ=DAILY;UNKNOWN=1",
            "FREQ=DAILY;COUNT=2;COUNT=3", "FREQ=DAILY;UNTIL=20261003T100000", "FREQ=DAILY;UNTIL=20261003",
            "FREQ=DAILY;BYSECOND=60", "FREQ=DAILY;INTERVAL=1000001").forEach { unsupported("RRULE:$it") }
    }

    @Test fun defersValidSetPositionSelectorsInsteadOfRelyingOnPartialProviderSupport() {
        listOf("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1",
            "FREQ=YEARLY;BYMONTH=1,2;BYDAY=MO;BYSETPOS=2",
            "FREQ=WEEKLY;BYDAY=MO,FR;BYSETPOS=1").forEach { unsupported("RRULE:$it") }
    }

    @Test fun defersValidYearDayAndWeekNumberSelectorsInsteadOfDroppingThem() {
        listOf("FREQ=YEARLY;BYYEARDAY=1,-1", "FREQ=YEARLY;BYWEEKNO=20;BYDAY=MO")
            .forEach { unsupported("RRULE:$it") }
    }

    @Test fun defersYearlyDaySelectorsWithoutAnExplicitMonth() {
        listOf("FREQ=YEARLY;BYDAY=20MO", "FREQ=YEARLY;BYDAY=MO", "FREQ=YEARLY;BYMONTHDAY=1,-1")
            .forEach { unsupported("RRULE:$it") }
        assertTrue(timed("RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1MO").recurrence!!.rule!!.contains("BYDAY=-1MO"))
        assertTrue(timed("RRULE:FREQ=YEARLY;BYMONTH=10;BYMONTHDAY=1").recurrence!!.rule!!.contains("BYMONTHDAY=1"))
    }

    @Test fun rejectsFloatingUnresolvedAndWrongDateKinds() {
        listOf("RRULE:FREQ=DAILY\nEXDATE:20261002T100000", "RRULE:FREQ=DAILY\nEXDATE;VALUE=DATE:20261002",
            "RRULE:FREQ=DAILY\nRDATE;TZID=Not/AZone:20261002T100000", "RECURRENCE-ID:20261002T100000",
            "RECURRENCE-ID;TZID=Not/AZone:20261002T100000", "RDATE;VALUE=PERIOD:20261002T100000Z/PT1H",
            "RRULE:FREQ=DAILY\nSTATUS:CANCELLED", "RECURRENCE-ID;RANGE=THISANDFUTURE:20261002T100000Z",
            "RECURRENCE-ID:20261002T100000Z\nRRULE:FREQ=DAILY", "RRULE:FREQ=DAILY\nRRULE:FREQ=WEEKLY").forEach(::unsupported)
    }

    @Test fun boundsRecurrenceDateCountWithoutTruncation() {
        unsupported("RDATE:" + List(513) { "20261002T100000Z" }.joinToString(","))
    }

    private fun timed(extra: String) = parse("DTSTART:20261001T100000Z\nDTEND:20261001T110000Z\n$extra")
    private fun parse(properties: String) = ProtonICalendar.parse(listOf(ics(properties)))
    private fun unsupported(extra: String) {
        try { timed(extra); fail("Expected unsupported for synthetic recurrence") }
        catch (failure: RemoteImportException) { assertEquals(RemoteImportFailure.UNSUPPORTED, failure.reason) }
    }
    private fun day(value: String) = RecurrencePoint.AllDay(LocalDate.parse(value))
    private fun instant(value: String) = RecurrencePoint.Timed(Instant.parse(value))
    private fun ics(properties: String) = "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Synthetic fixture//EN\nBEGIN:VEVENT\nUID:synthetic-series\n$properties\nEND:VEVENT\nEND:VCALENDAR\n".replace("\n", "\r\n")
}
