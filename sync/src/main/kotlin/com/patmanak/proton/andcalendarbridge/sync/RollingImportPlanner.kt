package com.patmanak.proton.andcalendarbridge.sync

/** Contiguous queried dates, not proof of support, projection or change-feed continuity. */
data class CoverageObservation(val format: AdapterFormat, val interval: CoverageInterval) {
    companion object {
        fun completed(previous: CoverageObservation?, run: CoverageRun): CoverageObservation? {
            if (!run.finished || run.appliedWindows != run.windows.size) return null
            val intervals = run.windows.map { it.interval } +
                listOfNotNull(previous?.takeIf { it.format == run.format && it.interval.zone == run.windows.first().interval.zone }?.interval)
            val sorted = intervals.sortedBy { it.start }
            var until = sorted.first().endExclusive
            for (next in sorted.drop(1)) {
                if (next.start > until) return null // Never infer observations across a gap.
                until = maxOf(until, next.endExclusive)
            }
            return CoverageObservation(run.format, CoverageInterval(sorted.first().start, until, sorted.first().zone))
        }
    }
}

object RollingImportPlanner {
    /** Null requests a full scan; an empty list means only the change feed needs checking. */
    fun missing(observed: CoverageObservation?, format: AdapterFormat, desired: CoverageInterval): List<CoverageInterval>? {
        val known = observed?.takeIf { it.format == format }?.interval ?: return null
        if (known.zone != desired.zone || desired.start < known.start || desired.start >= known.endExclusive) return null
        if (desired.endExclusive <= known.endExclusive) return emptyList()
        val result = mutableListOf<CoverageInterval>()
        var from = known.endExclusive
        while (from < desired.endExclusive) {
            if (result.size == 128) return null
            val until = minOf(from.plusDays(42), desired.endExclusive)
            result += CoverageInterval(from, until, desired.zone)
            from = until
        }
        return result
    }
}
