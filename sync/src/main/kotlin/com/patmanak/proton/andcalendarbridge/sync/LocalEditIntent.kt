// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Preserved local intent only. Neither state authorizes dispatch or acknowledges a Provider edit. */
enum class LocalEditState { PAUSED, CONFLICT }

/** Immutable supported-field edit against an exact successfully projected remote baseline.
 * Personal reminders/color remain complete observations; participants and recurrence stay held.
 */
data class LocalEditIntent(
    val id: MutationId,
    val scope: CalendarScope,
    val format: AdapterFormat,
    val base: RemoteChange.Upsert,
    val content: EventContent,
    val state: LocalEditState = LocalEditState.PAUSED,
) {
    init {
        require(base.content.recurrence == null && (base.content.details == null || (base.content.details.organizer == null && base.content.details.attendees.isEmpty())))
        require(content.recurrence == null)
        require(if (base.content.details == null) content.details == null && content.colorRgb == base.content.colorRgb
            else content.details?.let { it.organizer == null && it.attendees.isEmpty() } == true)
        require(content != base.content) { "Local edit has no supported change" }
    }
    override fun toString() = "LocalEditIntent(REDACTED)"
}
