// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

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
        require(intent.seriesProfile == null)
        require(intent.state == LocalEditState.PAUSED)
        listOf(intent.id.value, intent.scope.account.value, intent.scope.calendar.value,
            intent.base.eventId.value, intent.base.revision.value, intent.format.id).forEach { boundedPersonalEdit(it, 4_096) }
        val original = intent.base.content
        val replacement = intent.content
        require(original.recurrence == null && replacement.recurrence == null)
        require(replacement != original)
        return intent.copy(base = intent.base.copy(content = freezePersonalEditContent(original)),
            content = freezePersonalEditContent(replacement))
    }
}

enum class PersonalEditField { TITLE, TEXT, TIME, REMINDERS, COLOR, RULE }
