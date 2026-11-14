// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class CreateSeriesRuleTest {
    @Test fun finiteDailyRulesHaveOneCanonicalMeaningWithoutAUid() {
        assertEquals(CreateSeriesRule(3), CreateSeriesRule.parse("FREQ=DAILY;COUNT=3"))
        assertEquals("FREQ=DAILY;COUNT=3", CreateSeriesRule.parse("COUNT=3;INTERVAL=1;FREQ=DAILY").rule)
        assertEquals("FREQ=DAILY;COUNT=366;INTERVAL=365", CreateSeriesRule.parse("freq=daily;interval=365;count=366").rule)
    }
    @Test fun unboundedComplexDuplicateAndAmbiguousRulesAreNeverNormalizedIntoSupport() {
        for (rule in listOf("FREQ=DAILY", "FREQ=WEEKLY;COUNT=3", "FREQ=DAILY;COUNT=3;BYHOUR=2",
            "FREQ=DAILY;COUNT=3;UNTIL=20300101", "FREQ=DAILY;COUNT=3;COUNT=4", "FREQ=DAILY;COUNT=03",
            "FREQ=DAILY;COUNT=1", "FREQ=DAILY;COUNT=367", "FREQ=DAILY;COUNT=3;INTERVAL=0",
            "FREQ=DAILY;COUNT=3;INTERVAL=366", "FREQ=DAILY;COUNT=3;WKST=MO", " FREQ=DAILY;COUNT=3",
            "FREQ=DAILY;COUNT=3\n", "FREQ=DAILY;COUNT=3;", "FREQ=DAILY;COUNT=3;COUNT=3")) {
            assertThrows(rule, IllegalArgumentException::class.java) { CreateSeriesRule.parse(rule) }
        }
    }
    @Test fun ordinaryNoonSeriesRetainWallTimeAcrossBothSeasonalChanges() {
        for (date in listOf("2030-03-29", "2030-10-25"))
            assertTrue(CreateSeriesRule(5).supports(timed(date, "12:00", "13:00", "Europe/Paris")))
    }
    @Test fun futureGapAndFoldAtEitherEndpointRemainUnsupported() {
        for (date in listOf("2030-03-29", "2030-10-25")) {
            assertFalse(CreateSeriesRule(5).supports(timed(date, "02:30", "03:30", "Europe/Paris")))
            assertFalse(CreateSeriesRule(5).supports(timed(date, "01:30", "02:30", "Europe/Paris")))
            // Endpoints are valid but the elapsed duration changes on the transition date.
            assertFalse(CreateSeriesRule(5).supports(timed(date, "01:30", "04:30", "Europe/Paris")))
        }
    }
    @Test fun intervalSelectionTestsOnlyTheSlotsActuallyInTheSeries() {
        assertTrue(CreateSeriesRule(2, 3).supports(timed("2030-03-29", "02:30", "03:30", "Europe/Paris")))
        assertTrue(CreateSeriesRule(5).supports(timed("2030-03-29", "02:30", "03:30", "UTC")))
    }
    @Test fun allDaySeriesKeepAnExclusiveEndAndStayInsideTheQualifiedYears() {
        assertTrue(CreateSeriesRule(366).supports(EventTime.AllDay(LocalDate.parse("2030-01-01"), LocalDate.parse("2030-01-03"))))
        assertFalse(CreateSeriesRule(366).supports(EventTime.AllDay(LocalDate.parse("9999-12-30"), LocalDate.parse("9999-12-31"))))
        assertFalse(CreateSeriesRule(2).supports(EventTime.AllDay(LocalDate.parse("1969-12-31"), LocalDate.parse("1970-01-01"))))
    }
    @Test fun fractionalAndUnsupportedFixedOffsetTimesCannotAcquireSeriesAdmission() {
        val valid = timed("2030-01-01", "12:00", "13:00", "UTC")
        assertFalse(CreateSeriesRule(3).supports(valid.copy(start = valid.start.plusNanos(1))))
        assertFalse(CreateSeriesRule(3).supports(timed("2030-01-01", "12:00", "13:00", "+01:00")))
    }
    @Test fun everySlotMustAlsoStayInsideTheUtcTimestampRange() {
        assertFalse(CreateSeriesRule(2).supports(timed("9999-12-30", "20:00", "21:00", "America/New_York")))
        assertFalse(CreateSeriesRule(2).supports(timed("1970-01-01", "01:00", "02:00", "Asia/Tokyo")))
    }
    private fun timed(date: String, start: String, end: String, zone: String): EventTime.Timed {
        val id = ZoneId.of(zone)
        return EventTime.Timed(LocalDateTime.parse("${date}T$start").atZone(id).toInstant(),
            LocalDateTime.parse("${date}T$end").atZone(id).toInstant(), id)
    }
}
