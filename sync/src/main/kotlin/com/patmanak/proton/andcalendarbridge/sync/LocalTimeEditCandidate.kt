// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

/** Offline time-only observation. Not a protected request, dispatch permit or acknowledgement. */
class LocalTimeEditCandidate(intent: LocalEditIntent) {
    val intent: LocalEditIntent = freeze(intent)
    override fun toString() = "LocalTimeEditCandidate(REDACTED)"

    private fun freeze(intent: LocalEditIntent): LocalEditIntent {
        require(intent.seriesProfile == null)
        require(intent.state == LocalEditState.PAUSED)
        listOf(intent.id.value, intent.scope.account.value, intent.scope.calendar.value,
            intent.base.eventId.value, intent.base.revision.value, intent.format.id).forEach { bounded(it, 4_096) }
        val original = intent.base.content
        val replacement = intent.content
        require(original.details == EventDetails() && original.colorRgb == null && original.recurrence == null)
        require(replacement == original.copy(time = replacement.time) && replacement.time != original.time)
        require(when (val old = original.time) {
            is EventTime.AllDay -> replacement.time is EventTime.AllDay
            is EventTime.Timed -> replacement.time is EventTime.Timed && old.zone == replacement.time.zone
        })
        for (time in listOf(original.time, replacement.time)) when (time) {
            is EventTime.AllDay -> require(time.start.year in 1970..9999 && time.endExclusive.year in 1970..9999)
            is EventTime.Timed -> for (instant in listOf(time.start, time.end)) {
                // Zone-rule eligibility is evaluated at the adapter boundary, not in a durable observation.
                require(instant.nano == 0 && instant >= Instant.EPOCH && instant <= LAST_TIMESTAMP)
                require(instant.atZone(time.zone).year in 1970..9999)
            }
        }
        for (text in listOf(original.title, original.description, original.location)) {
            bounded(text, 65_536)
            require(text.replace("\r\n", "\n").none { it.isISOControl() && it != '\n' && it != '\t' })
        }
        fun frozen(content: EventContent) = content.copy(details = EventDetails())
        return intent.copy(base = intent.base.copy(content = frozen(original)), content = frozen(replacement))
    }

    private fun bounded(value: String, limit: Int) {
        require(value.length <= limit)
        require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <= limit)
    }

    private companion object {
        val LAST_TIMESTAMP: Instant = Instant.parse("9999-12-31T23:59:59Z")
    }
}
