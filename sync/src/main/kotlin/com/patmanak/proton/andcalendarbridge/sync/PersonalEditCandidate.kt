// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Offline personal event v2 observation. Not a protected request, dispatch permit or acknowledgement. */
class PersonalEditCandidate(intent: LocalEditIntent) {
    val intent: LocalEditIntent = freeze(intent)
    val changedFields: Set<PersonalEditField> = buildSet {
        if (this@PersonalEditCandidate.intent.base.content.title != this@PersonalEditCandidate.intent.content.title) add(PersonalEditField.TITLE)
        if (this@PersonalEditCandidate.intent.base.content.description != this@PersonalEditCandidate.intent.content.description || this@PersonalEditCandidate.intent.base.content.location != this@PersonalEditCandidate.intent.content.location) add(PersonalEditField.TEXT)
        if (this@PersonalEditCandidate.intent.base.content.time != this@PersonalEditCandidate.intent.content.time) add(PersonalEditField.TIME)
        if (this@PersonalEditCandidate.intent.base.content.details?.reminders != this@PersonalEditCandidate.intent.content.details?.reminders) add(PersonalEditField.REMINDERS)
        if (this@PersonalEditCandidate.intent.base.content.colorRgb != this@PersonalEditCandidate.intent.content.colorRgb) add(PersonalEditField.COLOR)
    }.let { java.util.Collections.unmodifiableSet(it) }
    val requiredConsent: Set<PersonalEditField> get() = changedFields
    override fun toString() = "PersonalEditCandidate(REDACTED)"

    private fun freeze(intent: LocalEditIntent): LocalEditIntent {
        require(intent.state == LocalEditState.PAUSED)
        listOf(intent.id.value, intent.scope.account.value, intent.scope.calendar.value,
            intent.base.eventId.value, intent.base.revision.value, intent.format.id).forEach { bounded(it, 4_096) }
        val original = intent.base.content
        val replacement = intent.content
        require(original.details != null && original.details.organizer == null && original.details.attendees.isEmpty())
        require(listOf(original, replacement).all { it.colorRgb == null || it.colorRgb in 0..0xffffff })
        require(original.recurrence == null && replacement.recurrence == null)
        require(replacement.details != null && replacement.details.organizer == null && replacement.details.attendees.isEmpty())
        require(replacement != original)
        for (time in listOf(original.time, replacement.time)) when (time) {
            is EventTime.AllDay -> require(time.start.year in 1970..9999 && time.endExclusive.year in 1970..9999)
            is EventTime.Timed -> {
              require(time.zone == ZoneOffset.UTC || time.zone.id in ZoneId.getAvailableZoneIds())
              require(!time.zone.rules.isFixedOffset || time.zone.rules.getOffset(Instant.EPOCH) == ZoneOffset.UTC)
              for (instant in listOf(time.start, time.end)) {
                // An overlap has two possible offsets, including half-hour DST transitions.
                require(instant.nano == 0 && instant >= Instant.EPOCH && instant <= LAST_TIMESTAMP)
                val local = instant.atZone(time.zone)
                require(local.year in 1970..9999)
                val offsets = time.zone.rules.getValidOffsets(local.toLocalDateTime())
                require(offsets.size == 1 && offsets.single() == local.offset)
            }
        }
        }
        for (text in listOf(original.title, original.description, original.location, replacement.title, replacement.description, replacement.location)) {
            bounded(text, 65_536)
            require(text.replace("\r\n", "\n").none { it.isISOControl() && it != '\n' && it != '\t' })
        }
        fun frozen(content: EventContent) = content.copy(details = content.details!!.copy(reminders = java.util.Collections.unmodifiableList(content.details.reminders.toList()), attendees = emptyList()))
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

enum class PersonalEditField { TITLE, TEXT, TIME, REMINDERS, COLOR }
