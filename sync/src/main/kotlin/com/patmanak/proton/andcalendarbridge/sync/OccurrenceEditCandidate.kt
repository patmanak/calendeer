// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Duration

/** Inactive native-observation profile. It is neither a durable request nor remote family proof. */
class OccurrenceEditCandidate(master: RemoteChange.Upsert, val original: RecurrencePoint, replacement: EventContent) {
    val profile = AdapterFormat("calendeer-native-occurrence", 1)
    val master = freezeMaster(master)
    private val personal = freezePersonalEditContent(replacement)
    val content: EventContent
    init {
        require(personal.recurrence == null)
        require(personal.description == this.master.content.description && personal.location == this.master.content.location)
        val recurrence = requireNotNull(this.master.content.recurrence)
        val rule = CreateSeriesRule.parse(requireNotNull(recurrence.rule))
        val time = this.master.content.time
        val index = (0 until rule.count).singleOrNull { index ->
            val days = index.toLong() * rule.interval
            when (time) {
                is EventTime.AllDay -> original == RecurrencePoint.AllDay(time.start.plusDays(days))
                is EventTime.Timed -> original == RecurrencePoint.Timed(time.start.atZone(time.zone).plusDays(days).toInstant())
            }
        } ?: throw IllegalArgumentException("Original slot is outside the supported master")
        val days = index.toLong() * rule.interval
        val generatedTime = when (time) {
            is EventTime.AllDay -> EventTime.AllDay(time.start.plusDays(days), time.endExclusive.plusDays(days))
            is EventTime.Timed -> {
                val start = time.start.atZone(time.zone).plusDays(days).toInstant()
                EventTime.Timed(start, start.plus(Duration.between(time.start, time.end)), time.zone)
            }
        }
        require(personal != this.master.content.copy(time = generatedTime, recurrence = null))
        content = personal.copy(recurrence = EventRecurrence(recurrence.uid, original = original))
    }
    override fun toString() = "OccurrenceEditCandidate(REDACTED)"
    companion object {
        /** Snapshot inputs before a native or adapter suspension; identifiers remain opaque. */
        fun freezeMaster(master: RemoteChange.Upsert): RemoteChange.Upsert {
            boundedPersonalEdit(master.eventId.value, 4_096)
            boundedPersonalEdit(master.revision.value, 4_096)
            return master.copy(content = SeriesEditProfile.freezeMaster(master.content))
        }
    }
}
