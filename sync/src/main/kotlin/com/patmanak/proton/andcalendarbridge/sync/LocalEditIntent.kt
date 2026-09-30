// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Preserved local intent only. Neither state authorizes dispatch or acknowledges a Provider edit. */
enum class LocalEditState { PAUSED, CONFLICT }

/** Immutable supported-field edit against an exact successfully projected remote baseline.
 * Unsupported details, recurrence and color changes cannot silently become partial uploads.
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
        require(base.content.recurrence == null && (base.content.details == null || base.content.details == EventDetails()))
        require(content.recurrence == null && content.details == base.content.details)
        require(content.colorRgb == base.content.colorRgb)
        require(content != base.content) { "Local edit has no supported change" }
    }
    override fun toString() = "LocalEditIntent(REDACTED)"
}
