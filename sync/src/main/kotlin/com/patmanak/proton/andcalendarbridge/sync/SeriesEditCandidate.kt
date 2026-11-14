// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Complete offline master edit. Family/source qualification remains required before preparation. */
class SeriesEditCandidate(intent: LocalEditIntent) {
    val intent = freeze(intent)
    val changedFields: Set<SeriesEditField> = buildSet {
        val original = this@SeriesEditCandidate.intent.base.content
        val next = this@SeriesEditCandidate.intent.content
        if (original.title != next.title) add(SeriesEditField.TITLE)
        if (original.description != next.description || original.location != next.location) add(SeriesEditField.TEXT)
        if (original.time != next.time) add(SeriesEditField.TIME)
        if (original.details!!.reminders != next.details!!.reminders) add(SeriesEditField.REMINDERS)
        if (original.colorRgb != next.colorRgb) add(SeriesEditField.COLOR)
        if (original.recurrence!!.rule != next.recurrence!!.rule) add(SeriesEditField.RULE)
    }.let { java.util.Collections.unmodifiableSet(it) }

    private fun freeze(intent: LocalEditIntent): LocalEditIntent {
        require(intent.state == LocalEditState.PAUSED)
        listOf(intent.id.value, intent.scope.account.value, intent.scope.calendar.value,
            intent.base.eventId.value, intent.base.revision.value, intent.format.id).forEach { boundedPersonalEdit(it, 4_096) }
        val profile = requireNotNull(intent.seriesProfile)
        profile.requireCompatible(intent.base.content, intent.content)
        val uid = intent.base.content.recurrence!!.uid
        boundedPersonalEdit(uid, 4_096)
        require(uid.none { it.isISOControl() })
        require(intent.base.content != intent.content)
        fun frozen(content: EventContent): EventContent = freezePersonalEditContent(content).copy(
            recurrence = content.recurrence!!.copy(additionalDates = emptyList(), excludedDates = emptyList()))
        return intent.copy(base = intent.base.copy(content = frozen(intent.base.content)), content = frozen(intent.content))
    }
    override fun toString() = "SeriesEditCandidate(REDACTED)"
}

enum class SeriesEditField { TITLE, TEXT, TIME, REMINDERS, COLOR, RULE }
