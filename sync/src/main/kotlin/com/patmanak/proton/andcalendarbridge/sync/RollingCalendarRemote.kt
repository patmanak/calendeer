package com.patmanak.proton.andcalendarbridge.sync

/** Optional query planning; implementations freeze dates/zone in an opaque, scoped continuation. */
interface RollingCalendarRemote : StagedCalendarRemote {
    /** Local planning only, without transport calls. */
    suspend fun queryHorizon(scope: CalendarScope): CoverageInterval
    /** Start a nonempty plan of disjoint, same-zone windows of at most 42 days each. */
    suspend fun planWindows(scope: CalendarScope, windows: List<CoverageInterval>, limit: Int = 100): PageCheckpoint
}
