package com.patmanak.proton.andcalendarbridge.sync

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class CoverageTrackerTest {
    private val format = AdapterFormat("independent-fixture", 8)
    private val scope = CalendarScope(AccountId("fixture"), CalendarId("fixture"), 0)
    private val request = PageRequest(PageCheckpoint(scope, format), 100)
    private val plan = listOf(interval(1, 4), interval(4, 8))
    private fun interval(start: Int, end: Int) = CoverageInterval(LocalDate.of(2030, 2, start),
        LocalDate.of(2030, 2, end), ZoneOffset.UTC)
    private fun continuing() = request.copy(checkpoint = request.checkpoint.copy(cursor = OpaqueCursor(format, "opaque-next")))
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejected coverage transition") } catch (_: IllegalStateException) { }
    }

    @Test fun receivedTerminalPageIsNotAppliedUntilMatchingTreatmentCommits() {
        val first = PageCoverage(plan, 0, true, false)
        var ledger = CoverageTracker.received(CoverageLedger(), request, first, 10)
        assertEquals(0, ledger.active!!.receivedWindows)
        assertEquals(0L, ledger.active!!.windows[0].appliedPages)
        ledger = CoverageTracker.applied(ledger, first, false, 20)
        val last = first.copy(startsWindow = false, finishesWindow = true)
        ledger = CoverageTracker.received(ledger, continuing(), last, 30)
        assertEquals(1, ledger.active!!.receivedWindows)
        assertEquals(0, ledger.active!!.appliedWindows)
        ledger = CoverageTracker.applied(ledger, last, false, 40)
        assertEquals(1, ledger.active!!.appliedWindows)
        assertEquals(30L, ledger.active!!.windows[0].receivedAt)
        assertEquals(40L, ledger.active!!.windows[0].appliedAt)
        assertFalse(ledger.active!!.finished)
    }

    @Test fun migratedMidWindowPrefixStaysUnknownButLaterFullWindowsCanBeObserved() {
        val partial = PageCoverage(plan, 0, false, true)
        var ledger = CoverageTracker.received(CoverageLedger(), continuing(), partial, 10)
        ledger = CoverageTracker.applied(ledger, partial, false, 20)
        assertEquals(0, ledger.active!!.receivedWindows)
        assertEquals(0, ledger.active!!.appliedWindows)
        val next = PageCoverage(plan, 1, true, true)
        ledger = CoverageTracker.received(ledger, continuing(), next, 30)
        ledger = CoverageTracker.applied(ledger, next, true, 40)
        assertTrue(ledger.active!!.finished)
        assertEquals(1, ledger.active!!.appliedWindows)
        assertFalse(ledger.active!!.windows[0].prefixKnown)
    }

    @Test fun newEpochPreservesOnlyLastFinishedObservationAndCannotMergeMatchingDates() {
        val meta = PageCoverage(listOf(plan[0]), 0, true, true)
        var ledger = CoverageTracker.received(CoverageLedger(), request, meta, 10)
        ledger = CoverageTracker.applied(ledger, meta, true, 20)
        val previous = ledger.active
        ledger = ledger.restart()
        assertEquals(1L, ledger.epoch); assertNull(ledger.active); assertEquals(previous, ledger.previous)
        ledger = CoverageTracker.received(ledger, request, meta, 30)
        assertEquals(0, ledger.active!!.appliedWindows)
        ledger = ledger.restart()
        assertEquals(2L, ledger.epoch); assertEquals(previous, ledger.previous)
        assertNull(ledger.active)
    }

    @Test fun missingChangingOrSkippedMetadataCannotCreateACompleteWindow() {
        val first = PageCoverage(plan, 0, true, false)
        var ledger = CoverageTracker.received(CoverageLedger(), request, first, 10)
        rejected { CoverageTracker.received(ledger, continuing(), first, 11) }
        rejected { CoverageTracker.applied(ledger, first, true, 12) }
        ledger = CoverageTracker.applied(ledger, first, false, 20)
        rejected { CoverageTracker.received(ledger, continuing(), null, 21) }
        rejected { CoverageTracker.received(ledger, continuing(), PageCoverage(plan, 1, true, true), 22) }
        rejected { CoverageTracker.received(ledger, continuing(), first.copy(plan = listOf(interval(1, 5), interval(5, 8))), 23) }
        rejected { CoverageTracker.received(ledger, continuing().copy(checkpoint = continuing().checkpoint.copy(
            format = AdapterFormat("replacement", 1))), first.copy(startsWindow = false), 24) }
    }

    @Test fun replayedApplyIsRejectedAndUnknownLegacyDoesNotInventTimestamps() {
        val unknown = CoverageTracker.received(CoverageLedger(), continuing(), null, 10)
        assertNull(unknown.active)
        assertEquals(unknown, CoverageTracker.applied(unknown, null, true, 20))
        val meta = PageCoverage(listOf(plan[0]), 0, true, true)
        val received = CoverageTracker.received(CoverageLedger(), request, meta, 30)
        val applied = CoverageTracker.applied(received, meta, true, 40)
        rejected { CoverageTracker.applied(applied, meta, true, 50) }
    }

    @Test fun invalidOverlappingPlansAndUnboundedPlansAreRejected() {
        for (invalid in listOf(listOf(plan[0], plan[0]), List(129) { plan[0] })) {
            try { PageCoverage(invalid, 0, true, false); fail() } catch (_: IllegalArgumentException) { }
        }
    }
}
