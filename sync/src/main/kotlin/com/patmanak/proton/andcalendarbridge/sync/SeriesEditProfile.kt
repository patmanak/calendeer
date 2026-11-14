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
        /** Canonical shape only; not a proof of complete native or remote family membership. */
        fun of(base: EventContent, replacement: EventContent): SeriesEditProfile = SeriesEditProfile(
            CreateSeriesRule.parse(requireNotNull(base.recurrence?.rule)),
            CreateSeriesRule.parse(requireNotNull(replacement.recurrence?.rule))).also {
                it.requireCompatible(base, replacement)
            }
    }
}
