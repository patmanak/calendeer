// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import com.patmanak.proton.andcalendarbridge.experiments.FiniteDailyRecurrenceExperiment
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Lives in testDebug because its fixture entry point is absent from release source sets. */
class FiniteDailyRecurrenceExperimentTest {
    private fun time(start: String, zone: String = "Europe/Paris") = EventTime.Timed(Instant.parse(start),
        Instant.parse(start).plusSeconds(3600), ZoneId.of(zone))
    private fun rule(value: String = "FREQ=DAILY;COUNT=3", excluded: List<RecurrencePoint> = emptyList()) =
        EventRecurrence("finite-fixture-series", value, excludedDates = excluded)

    @Test fun independentLocalDatesChooseTheEarlyFoldAndDoNotCarryTheGapHourForward() {
        for ((start, expected) in listOf(
            "2030-10-26T00:30:00Z" to listOf("2030-10-26T00:30:00Z", "2030-10-27T00:30:00Z", "2030-10-28T01:30:00Z"),
            "2030-03-30T01:30:00Z" to listOf("2030-03-30T01:30:00Z", "2030-03-31T01:30:00Z", "2030-04-01T00:30:00Z"),
        )) {
            val canonical = rule()
            val schedule = FiniteDailyRecurrenceExperiment.plan(time(start), canonical)
            assertEquals(expected.map(Instant::parse), schedule.dates)
            assertEquals(canonical, schedule.canonical); assertEquals("FREQ=DAILY;COUNT=3", canonical.rule)
            assertNull(schedule.native.rule)
            assertEquals(schedule.dates.map { RecurrencePoint.Timed(it) }, schedule.native.additionalDates)
            assertEquals(canonical.uid, schedule.native.uid)
        }
    }

    @Test fun exclusionsDoNotAffectCountOrSubsequentNominalDates() {
        val excluded = listOf(RecurrencePoint.Timed(Instant.parse("2030-03-31T01:30:00Z")))
        val schedule = FiniteDailyRecurrenceExperiment.plan(time("2030-03-30T01:30:00Z"), rule(excluded = excluded))
        assertEquals(3, schedule.dates.size)
        assertEquals(Instant.parse("2030-04-01T00:30:00Z"), schedule.dates.last())
        assertEquals(excluded, schedule.canonical.excludedDates); assertEquals(excluded, schedule.native.excludedDates)
    }

    @Test fun halfHourGapUsesItsOwnPrecedingOffset() {
        val schedule = FiniteDailyRecurrenceExperiment.plan(time("2030-10-04T15:45:00Z", "Australia/Lord_Howe"), rule())
        assertEquals(listOf("2030-10-04T15:45:00Z", "2030-10-05T15:45:00Z", "2030-10-06T15:15:00Z").map(Instant::parse), schedule.dates)
    }

    @Test fun intervalSkippingTransitionAndSingleOccurrencePreserveExactStart() {
        val start = "2030-10-26T00:30:00Z"
        assertEquals(listOf("2030-10-26T00:30:00Z", "2030-10-28T01:30:00Z", "2030-10-30T01:30:00Z").map(Instant::parse),
            FiniteDailyRecurrenceExperiment.plan(time(start), rule("INTERVAL=2;COUNT=3;FREQ=DAILY")).dates)
        assertEquals(listOf(Instant.parse(start)), FiniteDailyRecurrenceExperiment.plan(time(start), rule("FREQ=DAILY;COUNT=1")).dates)
    }

    @Test fun experimentBoundsAdmitThirtyTwoDatesAndOneYearSpan() {
        assertEquals(32, FiniteDailyRecurrenceExperiment.plan(time("2030-01-01T09:00:00Z"), rule("FREQ=DAILY;COUNT=32")).dates.size)
        assertEquals(2, FiniteDailyRecurrenceExperiment.plan(time("2030-01-01T09:00:00Z"), rule("FREQ=DAILY;COUNT=2;INTERVAL=366")).dates.size)
    }

    @Test fun outOfScopeRulesAndAmbiguousStartRefuseWithoutPartialSchedules() {
        for (value in listOf("FREQ=DAILY", "FREQ=DAILY;COUNT=33", "FREQ=DAILY;COUNT=2;INTERVAL=367",
            "FREQ=DAILY;COUNT=3;INTERVAL=366", "FREQ=DAILY;COUNT=3;BYHOUR=2", "FREQ=WEEKLY;COUNT=3",
            "FREQ=DAILY;COUNT=3;COUNT=2", "FREQ=DAILY;COUNT=3;INTERVAL=0", "FREQ=DAILY;COUNT=0",
            "FREQ=DAILY;UNTIL=20301028T013000Z", "FREQ=DAILY;COUNT=+3", "freq=daily;COUNT=3")) {
            assertRefused { FiniteDailyRecurrenceExperiment.plan(time("2030-10-26T00:30:00Z"), rule(value)) }
        }
        for (start in listOf("2030-10-27T00:30:00Z", "2030-10-27T01:30:00Z"))
            assertRefused { FiniteDailyRecurrenceExperiment.plan(time(start), rule()) }
    }

    @Test fun unsupportedTypesAdditionalDatesAndTemporalBoundsRefuse() {
        val base = time("2030-10-26T00:30:00Z")
        assertRefused { FiniteDailyRecurrenceExperiment.plan(EventTime.AllDay(LocalDate.parse("2030-10-26"), LocalDate.parse("2030-10-27")), rule()) }
        assertRefused { FiniteDailyRecurrenceExperiment.plan(base, rule().copy(additionalDates = listOf(RecurrencePoint.Timed(base.start)))) }
        assertRefused { FiniteDailyRecurrenceExperiment.plan(base, EventRecurrence("series", original = RecurrencePoint.Timed(base.start))) }
        assertRefused { FiniteDailyRecurrenceExperiment.plan(base, rule(excluded = listOf(RecurrencePoint.AllDay(LocalDate.parse("2030-10-27"))))) }
        assertRefused { FiniteDailyRecurrenceExperiment.plan(base.copy(start = base.start.plusNanos(1)), rule()) }
        assertRefused { FiniteDailyRecurrenceExperiment.plan(base.copy(end = base.end.plusNanos(1)), rule()) }
        assertRefused { FiniteDailyRecurrenceExperiment.plan(base.copy(end = base.start.plusSeconds(604801)), rule()) }
        assertRefused { FiniteDailyRecurrenceExperiment.plan(time("9999-12-31T10:00:00Z", "UTC"), rule()) }
        assertRefused { FiniteDailyRecurrenceExperiment.plan(base, rule(excluded = (1..33).map { RecurrencePoint.Timed(base.start.plusSeconds(it.toLong())) })) }
        assertRefused { FiniteDailyRecurrenceExperiment.plan(base, rule(excluded = listOf(RecurrencePoint.Timed(Instant.parse("+10000-01-01T00:00:00Z"))))) }
    }

    @Test fun scheduleSnapshotsListsAndDoesNotExposeContentsInDiagnostics() {
        val excluded = mutableListOf<RecurrencePoint>(RecurrencePoint.Timed(Instant.parse("2030-10-27T00:30:00Z")))
        val additions = mutableListOf<RecurrencePoint>()
        val schedule = FiniteDailyRecurrenceExperiment.plan(time("2030-10-26T00:30:00Z"), rule(excluded = excluded).copy(additionalDates = additions))
        excluded.clear()
        additions += RecurrencePoint.Timed(Instant.parse("2030-10-27T01:30:00Z"))
        assertTrue(schedule.canonical.additionalDates.isEmpty())
        assertEquals(1, schedule.canonical.excludedDates.size); assertEquals(1, schedule.native.excludedDates.size)
        assertThrows(UnsupportedOperationException::class.java) { (schedule.dates as MutableList).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (schedule.native.excludedDates as MutableList).clear() }
        assertEquals("FiniteDailySchedule(redacted)", schedule.toString())
    }

    @Test fun everyGeneratedNominalDateMustStayInsideTheExperimentYearBound() {
        assertRefused { FiniteDailyRecurrenceExperiment.plan(time("9999-12-30T10:00:00Z", "Etc/GMT-14"), rule("FREQ=DAILY;COUNT=2")) }
    }

    private fun assertRefused(block: () -> Any) {
        val failure = assertThrows(IllegalArgumentException::class.java) { block(); Unit }
        assertEquals("Unsupported finite daily experiment", failure.message)
    }
}
