// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.sync.*

internal object PersonalEditConsent {
    fun allowed(intent: LocalEditIntent, titles: (CalendarScope) -> Boolean,
        texts: (CalendarScope) -> Boolean, times: (CalendarScope) -> Boolean,
        editable: (CalendarScope) -> Boolean): Boolean = try {
        val required = if (intent.seriesProfile != null) SeriesEditCandidate(intent).changedFields.map { PersonalEditField.valueOf(it.name) }
            else PersonalEditCandidate(intent).requiredConsent
        required.all { field -> when (field) {
            PersonalEditField.TITLE -> titles(intent.scope)
            PersonalEditField.TEXT -> texts(intent.scope)
            PersonalEditField.TIME, PersonalEditField.RULE -> times(intent.scope)
            PersonalEditField.REMINDERS, PersonalEditField.COLOR -> editable(intent.scope)
        } }
    } catch (_: Exception) { false }
}
