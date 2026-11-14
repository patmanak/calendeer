// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Shared content validation only; recurrence and operation admission belong to the caller. */
internal fun freezePersonalEditContent(content: EventContent): EventContent {
    require(content.details != null && content.details.organizer == null && content.details.attendees.isEmpty())
    require(content.colorRgb == null || content.colorRgb in 0..0xffffff)
    when (val time = content.time) {
        is EventTime.AllDay -> require(time.start.year in 1970..9999 && time.endExclusive.year in 1970..9999)
        is EventTime.Timed -> {
            require(time.zone == ZoneOffset.UTC || time.zone.id in ZoneId.getAvailableZoneIds())
            require(!time.zone.rules.isFixedOffset || time.zone.rules.getOffset(Instant.EPOCH) == ZoneOffset.UTC)
            for (instant in listOf(time.start, time.end)) {
                require(instant.nano == 0 && instant >= Instant.EPOCH && instant <= PERSONAL_EDIT_LAST_TIMESTAMP)
                val local = instant.atZone(time.zone)
                require(local.year in 1970..9999)
                val offsets = time.zone.rules.getValidOffsets(local.toLocalDateTime())
                require(offsets.size == 1 && offsets.single() == local.offset)
            }
        }
    }
    for (text in listOf(content.title, content.description, content.location)) {
        boundedPersonalEdit(text, 65_536)
        require(text.replace("\r\n", "\n").none { it.isISOControl() && it != '\n' && it != '\t' })
    }
    return content.copy(details = content.details.copy(
        reminders = java.util.Collections.unmodifiableList(content.details.reminders.toList()), attendees = emptyList()))
}

internal fun boundedPersonalEdit(value: String, limit: Int) {
    require(value.length <= limit)
    require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <= limit)
}

private val PERSONAL_EDIT_LAST_TIMESTAMP: Instant = Instant.parse("9999-12-31T23:59:59Z")
