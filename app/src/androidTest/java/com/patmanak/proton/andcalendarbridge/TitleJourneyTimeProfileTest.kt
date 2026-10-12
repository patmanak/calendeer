// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import com.patmanak.proton.andcalendarbridge.sync.EventTime
import java.time.Duration
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TitleJourneyTimeProfileTest {
    private fun profile(flag: String? = "true") = TitleJourneyTimeProfile.create(flag, true, true, "all-day-fixture")
    @Test fun defaultAndFalsePreserveTheHistoricalTimedProfile() {
        for (argument in listOf(null, "false")) {
            val value = TitleJourneyTimeProfile.create(argument, false, false, null)
            assertEquals(1, value.contentProfile); assertTrue(value.time is EventTime.Timed)
            assertTrue(value.matchesFrozen(1, "2026-10-12T14:00:00Z", "2026-10-12T15:00:00Z"))
        }
    }
    @Test fun allDayHasExclusiveCalendarDatesAcrossDstAndUtcMidnightStorage() {
        val value = profile(); val time = value.time as EventTime.AllDay
        assertEquals("2026-10-24", time.start.toString()); assertEquals("2026-10-27", time.endExclusive.toString())
        assertEquals(72L * 3_600_000L, value.endMillis - value.startMillis)
        assertEquals(73L, Duration.between(time.start.atStartOfDay(ZoneId.of("Europe/Paris")),
            time.endExclusive.atStartOfDay(ZoneId.of("Europe/Paris"))).toHours())
        assertTrue(TitleJourneyTimeProfile.nativeMatches(time, "1", value.startMillis.toString(), value.endMillis.toString(), "UTC", "UTC"))
    }
    @Test fun sourceProfileOrDateSubstitutionCannotResume() {
        val value = profile()
        assertFalse(value.matchesFrozen(1, value.start, value.end))
        assertFalse(profile("false").matchesFrozen(2, value.start, value.end))
        assertFalse(value.matchesFrozen(2, value.start, "2026-10-26"))
        assertFalse(value.matchesFrozen(2, "2026-10-24T00:00:00Z", value.end))
    }
    @Test fun allDayCannotBorrowUnlabeledOrUnqualifiedFixtureNamespaces() {
        for ((automatic, system, label) in listOf(Triple(false, true, "fresh"), Triple(true, false, "fresh"), Triple(true, true, null)))
            assertThrows(IllegalArgumentException::class.java) { TitleJourneyTimeProfile.create("true", automatic, system, label) }
        for (invalid in listOf("", "TRUE", "1", " true"))
            assertThrows(IllegalArgumentException::class.java) { profile(invalid) }
    }
    @Test fun timedReinterpretationInclusiveEndOrLocalTimezoneCannotPassNativeAllDayEvidence() {
        val value = profile()
        assertFalse(TitleJourneyTimeProfile.nativeMatches(value.time, "0", value.startMillis.toString(), value.endMillis.toString(), "UTC", "UTC"))
        assertFalse(TitleJourneyTimeProfile.nativeMatches(value.time, "1", value.startMillis.toString(), (value.endMillis - 86_400_000L).toString(), "UTC", "UTC"))
        assertFalse(TitleJourneyTimeProfile.nativeMatches(value.time, "1", value.startMillis.toString(), value.endMillis.toString(), "Europe/Paris", "Europe/Paris"))
        assertFalse(TitleJourneyTimeProfile.nativeMatches(value.time, "1", value.startMillis.toString(), value.endMillis.toString(), "UTC", null))
    }
}
