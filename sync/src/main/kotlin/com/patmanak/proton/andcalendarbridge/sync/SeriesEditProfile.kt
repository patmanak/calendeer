// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Offline shape, not proof of complete remote family membership or absence of native children. */
data class SeriesEditProfile(val baseRule: CreateSeriesRule, val replacementRule: CreateSeriesRule) {
    internal fun requireCompatible(base: EventContent, replacement: EventContent) {
        val original = requireNotNull(base.recurrence)
        val next = requireNotNull(replacement.recurrence)
        require(original.uid == next.uid)
        require(original == EventRecurrence(original.uid, baseRule.rule))
        require(next == EventRecurrence(original.uid, replacementRule.rule))
        require(baseRule.supports(base.time) && replacementRule.supports(replacement.time))
    }
    override fun toString() = "SeriesEditProfile(REDACTED)"
    companion object {
        /** Owned personal master snapshot before asynchronous qualification; no write authority. */
        fun freezeMaster(content: EventContent): EventContent {
            val recurrence = requireNotNull(content.recurrence)
            val frozen = freezePersonalEditContent(content).copy(recurrence = recurrence.copy(
                additionalDates = java.util.Collections.unmodifiableList(recurrence.additionalDates.toList()),
                excludedDates = java.util.Collections.unmodifiableList(recurrence.excludedDates.toList())))
            of(frozen, frozen)
            boundedPersonalEdit(recurrence.uid, 4_096)
            require(recurrence.uid.none { it.isISOControl() })
            return frozen
        }

        /** Canonical shape only; not a proof of complete native or remote family membership. */
        fun of(base: EventContent, replacement: EventContent): SeriesEditProfile = SeriesEditProfile(
            CreateSeriesRule.parse(requireNotNull(base.recurrence?.rule)),
            CreateSeriesRule.parse(requireNotNull(replacement.recurrence?.rule))).also {
                it.requireCompatible(base, replacement)
            }
    }
}
