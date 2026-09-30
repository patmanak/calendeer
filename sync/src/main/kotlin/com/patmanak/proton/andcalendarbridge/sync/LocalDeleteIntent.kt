// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Immutable local deletion snapshot. It neither acknowledges Android nor authorizes dispatch.
 * Content preserves edits made before deletion; it is not a remote tombstone or an UPDATE.
 */
data class LocalDeleteIntent(
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
        for (time in listOf(base.content.time, content.time)) {
            if (time is EventTime.Timed) require(time.start.nano == 0 && time.end.nano == 0)
        }
    }
    override fun toString() = "LocalDeleteIntent(REDACTED)"
}
