// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import com.patmanak.proton.andcalendarbridge.sync.EventTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Typed, synthetic live-fixture enrollment only. Never interprets a production journal. */
internal class TitleJourneyTimeProfile private constructor(val contentProfile: Int, val start: String, val end: String) {
    val allDay get() = contentProfile == 2
    val time: EventTime = if (allDay) EventTime.AllDay(LocalDate.parse(start), LocalDate.parse(end))
        else EventTime.Timed(Instant.parse(start), Instant.parse(end), ZoneOffset.UTC)
    val startMillis get() = bounds(time).first
    val endMillis get() = bounds(time).second
    fun matchesFrozen(profile: Int, start: String?, end: String?) = profile == contentProfile && start == this.start && end == this.end
    override fun toString() = "TitleJourneyTimeProfile(REDACTED)"

    companion object {
        fun create(argument: String?, automaticSource: Boolean, systemJourney: Boolean, label: String?): TitleJourneyTimeProfile {
            require(argument == null || argument == "false" || argument == "true") { "Invalid title journey time profile" }
            val allDay = argument == "true"
            require(!allDay || automaticSource && systemJourney && label != null && label.matches(Regex("[a-z][a-z0-9-]{0,23}"))) {
                "All-day journey requires an isolated labeled automatic source"
            }
            return if (allDay) TitleJourneyTimeProfile(2, "2026-10-24", "2026-10-27")
                else TitleJourneyTimeProfile(1, "2026-10-12T14:00:00Z", "2026-10-12T15:00:00Z")
        }
        fun bounds(time: EventTime): Pair<Long, Long> = when (time) {
            is EventTime.Timed -> time.start.toEpochMilli() to time.end.toEpochMilli()
            is EventTime.AllDay -> time.start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to
                time.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        fun nativeMatches(time: EventTime, allDay: String?, start: String?, end: String?, zone: String?, endZone: String?): Boolean {
            val bounds = bounds(time)
            if (start != bounds.first.toString() || end != bounds.second.toString()) return false
            return when (time) {
                is EventTime.AllDay -> allDay == "1" && zone == "UTC" && endZone == "UTC" &&
                    bounds.first % 86_400_000L == 0L && bounds.second % 86_400_000L == 0L
                is EventTime.Timed -> allDay == "0" && zone != null && runCatching {
                    ZoneId.of(zone).normalized() == time.zone.normalized() &&
                        (endZone == null || ZoneId.of(endZone).normalized() == time.zone.normalized())
                }.getOrDefault(false)
            }
        }
    }
}
