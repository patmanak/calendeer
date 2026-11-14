// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Preserved local intent only. Neither state authorizes dispatch or acknowledges a Provider edit. */
enum class LocalEditState { PAUSED, CONFLICT }

/** Immutable supported-field edit against an exact successfully projected remote baseline.
 * Series observations require an explicit separate profile; this grants no writer capability.
 */
data class LocalEditIntent(
    val id: MutationId,
    val scope: CalendarScope,
    val format: AdapterFormat,
    val base: RemoteChange.Upsert,
    val content: EventContent,
    val state: LocalEditState = LocalEditState.PAUSED,
    val seriesProfile: SeriesEditProfile? = null,
) {
    init {
        require(base.content.details == null || (base.content.details.organizer == null && base.content.details.attendees.isEmpty()))
        if (seriesProfile == null) require(base.content.recurrence == null && content.recurrence == null)
        else {
            require(base.content.details != null)
            seriesProfile.requireCompatible(base.content, content)
        }
        require(if (base.content.details == null) content.details == null && content.colorRgb == base.content.colorRgb
            else content.details?.let { it.organizer == null && it.attendees.isEmpty() } == true)
        require(content != base.content) { "Local edit has no supported change" }
    }
    override fun toString() = "LocalEditIntent(REDACTED)"
}
