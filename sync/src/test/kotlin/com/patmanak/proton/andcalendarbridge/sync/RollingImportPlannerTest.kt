package com.patmanak.proton.andcalendarbridge.sync

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RollingImportPlannerTest {
    private val format = AdapterFormat("fixture", 1)
    private val zone = ZoneId.of("Europe/Paris")
    private val start = LocalDate.of(2026, 1, 1)
    private fun interval(from: Long, until: Long) = CoverageInterval(start.plusDays(from), start.plusDays(until), zone)
    private val observed = CoverageObservation(format, interval(0, 456))

    @Test fun sameHorizonNeedsNoWindowAndNextDayLoadsOnlyTheEnteringDay() {
        assertEquals(emptyList<CoverageInterval>(), RollingImportPlanner.missing(observed, format, interval(0, 456)))
        assertEquals(listOf(interval(456, 457)), RollingImportPlanner.missing(observed, format, interval(1, 457)))
    }
    @Test fun severalWeeksAwayAreBoundedAndDoNotLoseTheLastDay() {
        assertEquals(listOf(interval(456, 498), interval(498, 540), interval(540, 546)),
            RollingImportPlanner.missing(observed, format, interval(90, 546)))
    }
    @Test fun unknownFormatZoneBackwardBoundaryOrDisjointHorizonRequiresFullScan() {
        assertNull(RollingImportPlanner.missing(null, format, interval(1, 457)))
        assertNull(RollingImportPlanner.missing(observed, AdapterFormat("new", 1), interval(1, 457)))
        assertNull(RollingImportPlanner.missing(observed, format, interval(1, 457).copy(zone = ZoneId.of("UTC"))))
        assertNull(RollingImportPlanner.missing(observed, format, interval(-1, 455)))
        assertNull(RollingImportPlanner.missing(observed, format, interval(500, 956)))
    }
    @Test fun daylightSavingUsesCalendarDatesRatherThanTwentyFourHourDurations() {
        val spring = CoverageObservation(format, CoverageInterval(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 29), zone))
        val desired = CoverageInterval(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 31), zone)
        val tail = RollingImportPlanner.missing(spring, format, desired)!!.single()
        assertEquals(LocalDate.of(2026, 3, 29), tail.start)
        assertEquals(LocalDate.of(2026, 3, 31), tail.endExclusive)
        assertEquals(47 * 3600L, tail.endExclusive.atStartOfDay(zone).toEpochSecond() - tail.start.atStartOfDay(zone).toEpochSecond())
    }
    @Test fun onlyCompleteKnownProcessingExtendsObservedBoundsAndResetForgetsThem() {
        val scope = CalendarScope(AccountId("fixture"), CalendarId("calendar"), 0)
        val metadata = PageCoverage(listOf(interval(456, 457)), 0, true, true)
        val request = PageRequest(PageCheckpoint(scope, format, OpaqueCursor(format, "opaque-extension")), 100)
        val original = CoverageLedger(observed = observed)
        val received = CoverageTracker.received(original, request, metadata, 10)
        assertEquals(observed, received.observed)
        val applied = CoverageTracker.applied(received, metadata, true, 20)
        assertEquals(CoverageObservation(format, interval(0, 457)), applied.observed)
        assertEquals(applied.observed, applied.restart(preserveObservation = true).observed)
        assertNull(applied.restart().observed)
        val unknown = metadata.copy(startsWindow = false)
        val partial = CoverageTracker.applied(CoverageTracker.received(CoverageLedger(), request, unknown, 10), unknown, true, 20)
        assertNull(partial.observed)
    }
    @Test fun disjointObservationsNeverFillAnUnqueriedHole() {
        val run = CoverageRun(format, listOf(WindowCoverage(interval(500, 501), true, 1, 1, true, true, 10, 20)), 0, true)
        assertNull(CoverageObservation.completed(observed, run))
    }
}
