// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import com.patmanak.proton.andcalendarbridge.sync.EventTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Closed synthetic source/worker enrollment. Date bounds are literal independent fixtures. */
internal class TemporalJourneyProfile private constructor(val name: String, val before: EventTime, val after: EventTime) {
    val allDay get() = before is EventTime.AllDay
    val kind get() = if (allDay) 2 else 1
    val zone get() = (before as? EventTime.Timed)?.zone?.id ?: "UTC"
    val start get() = when (val t = before) { is EventTime.AllDay -> t.start.toString(); is EventTime.Timed -> t.start.toString() }
    val end get() = when (val t = before) { is EventTime.AllDay -> t.endExclusive.toString(); is EventTime.Timed -> t.end.toString() }
    fun matchesFrozen(kind: Int, start: String?, end: String?) = kind == this.kind && start == this.start && end == this.end
    override fun toString() = "TemporalJourneyProfile(redacted)"
    companion object {
        fun create(name: String?): TemporalJourneyProfile {
            val selected = name ?: "utc"
            fun timed(zone: String, a: String, b: String) = EventTime.Timed(Instant.parse(a), Instant.parse(b), ZoneId.of(zone))
            fun days(a: String, b: String) = EventTime.AllDay(LocalDate.parse(a), LocalDate.parse(b))
            val pair = when (selected) {
                "utc" -> timed("Z", "2026-10-12T14:00:00Z", "2026-10-12T15:00:00Z") to
                    timed("Z", "2026-10-12T16:00:00Z", "2026-10-12T18:00:00Z")
                "all-day-one" -> days("2026-10-24", "2026-10-25") to days("2026-10-25", "2026-10-26")
                "all-day-fall" -> days("2026-10-24", "2026-10-27") to days("2026-10-25", "2026-10-29")
                "paris-fall" -> timed("Europe/Paris", "2026-10-24T10:00:00Z", "2026-10-24T11:00:00Z") to
                    timed("Europe/Paris", "2026-10-24T22:00:00Z", "2026-10-25T23:00:00Z")
                "paris-spring" -> timed("Europe/Paris", "2027-03-27T11:00:00Z", "2027-03-27T12:00:00Z") to
                    timed("Europe/Paris", "2027-03-27T23:00:00Z", "2027-03-28T22:00:00Z")
                "new-york-spring" -> timed("America/New_York", "2027-03-13T17:00:00Z", "2027-03-13T18:00:00Z") to
                    timed("America/New_York", "2027-03-14T05:00:00Z", "2027-03-15T04:00:00Z")
                "lord-howe-fall" -> timed("Australia/Lord_Howe", "2027-04-03T01:00:00Z", "2027-04-03T02:00:00Z") to
                    timed("Australia/Lord_Howe", "2027-04-03T13:00:00Z", "2027-04-04T13:30:00Z")
                "kathmandu" -> timed("Asia/Kathmandu", "2026-10-24T04:15:00Z", "2026-10-24T05:15:00Z") to
                    timed("Asia/Kathmandu", "2026-10-25T04:15:00Z", "2026-10-25T06:45:00Z")
                else -> error("Unknown closed temporal fixture profile")
            }
            return TemporalJourneyProfile(selected, pair.first, pair.second)
        }
    }
}
