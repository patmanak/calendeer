// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.sync.*

internal object PersonalEditConsent {
    fun allowed(intent: LocalEditIntent, titles: (CalendarScope) -> Boolean,
        texts: (CalendarScope) -> Boolean, times: (CalendarScope) -> Boolean): Boolean = try {
        PersonalEditCandidate(intent).requiredConsent.all { field -> when (field) {
            PersonalEditField.TITLE -> titles(intent.scope)
            PersonalEditField.TEXT -> texts(intent.scope)
            PersonalEditField.TIME -> times(intent.scope)
        } }
    } catch (_: Exception) { false }
}
