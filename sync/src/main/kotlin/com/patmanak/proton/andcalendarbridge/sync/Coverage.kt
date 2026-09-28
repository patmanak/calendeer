package com.patmanak.proton.andcalendarbridge.sync

import java.time.LocalDate
import java.time.ZoneId

/** Query dates, not event dates or a claim of an atomic server snapshot. */
data class CoverageInterval(val start: LocalDate, val endExclusive: LocalDate, val zone: ZoneId) {
    init { require(start < endExclusive) }
}

/** Adapter-independent evidence. A window ends only after every query partition/page. */
data class PageCoverage(val plan: List<CoverageInterval>, val windowIndex: Int,
    val startsWindow: Boolean, val finishesWindow: Boolean) {
    init {
        require(plan.size in 1..128 && windowIndex in plan.indices)
        require(plan.map { it.zone }.distinct().size == 1)
        val sorted = plan.sortedBy { it.start }
        require(sorted.zipWithNext().all { (a, b) -> a.endExclusive <= b.start })
    }
}

data class WindowCoverage(val interval: CoverageInterval, val prefixKnown: Boolean = false,
    val receivedPages: Long = 0, val appliedPages: Long = 0,
    val terminalReceived: Boolean = false, val terminalApplied: Boolean = false,
    val receivedAt: Long? = null, val appliedAt: Long? = null) {
    init {
        require(receivedPages >= 0 && appliedPages in 0..receivedPages)
        require(!terminalApplied || terminalReceived)
        require(!prefixKnown || receivedPages > 0)
        require(!terminalReceived || receivedPages > 0)
        require(!terminalApplied || appliedPages > 0)
        require((receivedPages == 0L) == (receivedAt == null))
        require((appliedPages == 0L) == (appliedAt == null))
    }
    val fullyReceived get() = prefixKnown && terminalReceived
    val fullyApplied get() = prefixKnown && terminalApplied
}

data class CoverageRun(val format: AdapterFormat, val windows: List<WindowCoverage>,
    val lastIndex: Int = -1, val finished: Boolean = false) {
    init {
        require(windows.size in 1..128 && lastIndex in -1..windows.lastIndex)
        PageCoverage(windows.map { it.interval }, 0, false, false)
        require(lastIndex != -1 || windows.all { it.receivedPages == 0L })
        require(!finished || lastIndex == windows.lastIndex && windows.last().terminalApplied)
    }
    val receivedWindows get() = windows.count { it.fullyReceived }
    val appliedWindows get() = windows.count { it.fullyApplied }
}

/** One active traversal and one previous finished observation; no unbounded history. */
data class CoverageLedger(val epoch: Long = 0, val active: CoverageRun? = null, val previous: CoverageRun? = null,
    val observed: CoverageObservation? = null) {
    init { require(epoch >= 0) }
    fun restart(preserveObservation: Boolean = false): CoverageLedger = CoverageLedger(Math.addExact(epoch, 1),
        previous = active?.takeIf { it.finished } ?: previous,
        observed = observed.takeIf { preserveObservation })
}

/** Pure reducers called in the same transaction as the receipt or applied checkpoint. */
object CoverageTracker {
    fun received(ledger: CoverageLedger, request: PageRequest, metadata: PageCoverage?, now: Long): CoverageLedger {
        if (metadata == null) {
            check(ledger.active == null) { "Coverage metadata disappeared within a traversal" }
            return ledger
        }
        val old = ledger.active
        val run = old ?: CoverageRun(request.checkpoint.format, metadata.plan.map(::WindowCoverage))
        check(!run.finished && run.format == request.checkpoint.format)
        check(run.windows.map { it.interval } == metadata.plan) { "Coverage plan changed" }
        if (old == null && request.checkpoint.cursor == null)
            check(metadata.windowIndex == 0 && metadata.startsWindow) { "Invalid coverage origin" }
        if (run.lastIndex >= 0) {
            val last = run.windows[run.lastIndex]
            check(last.receivedPages == last.appliedPages) { "Coverage receipt still pending" }
            if (metadata.windowIndex == run.lastIndex) check(!last.terminalReceived && !metadata.startsWindow)
            else check(metadata.windowIndex == run.lastIndex + 1 && last.terminalApplied && metadata.startsWindow)
        }
        val window = run.windows[metadata.windowIndex]
        val next = window.copy(prefixKnown = window.prefixKnown || window.receivedPages == 0L && metadata.startsWindow,
            receivedPages = Math.addExact(window.receivedPages, 1), terminalReceived = metadata.finishesWindow,
            receivedAt = now)
        return ledger.copy(active = run.copy(windows = run.windows.toMutableList().also { it[metadata.windowIndex] = next },
            lastIndex = metadata.windowIndex))
    }

    fun applied(ledger: CoverageLedger, metadata: PageCoverage?, traversalComplete: Boolean, now: Long): CoverageLedger {
        if (metadata == null) {
            check(ledger.active == null) { "Missing applied coverage metadata" }
            return ledger
        }
        val run = checkNotNull(ledger.active)
        check(!run.finished && run.windows.map { it.interval } == metadata.plan && run.lastIndex == metadata.windowIndex)
        val window = run.windows[metadata.windowIndex]
        check(window.receivedPages == window.appliedPages + 1 && window.terminalReceived == metadata.finishesWindow)
        check(traversalComplete == (metadata.finishesWindow && metadata.windowIndex == run.windows.lastIndex))
        val next = window.copy(appliedPages = window.receivedPages, terminalApplied = metadata.finishesWindow, appliedAt = now)
        val updated = run.copy(windows = run.windows.toMutableList().also { it[metadata.windowIndex] = next },
            finished = traversalComplete)
        return ledger.copy(active = updated, observed = if (traversalComplete)
            CoverageObservation.completed(ledger.observed, updated) else ledger.observed)
    }
}
