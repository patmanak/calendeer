// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** The captured exception remains on the same generated slot under this exact finite-series geometry. */
fun compatibleOccurrenceMaster(anchor: RemoteChange.Upsert, observed: RemoteChange): Boolean {
    val next = observed as? RemoteChange.Upsert ?: return false
    if (next.eventId != anchor.eventId || next.content.time != anchor.content.time ||
        next.content.recurrence != anchor.content.recurrence) return false
    return try { OccurrenceEditCandidate.freezeMaster(next) == next }
    catch (_: IllegalArgumentException) { false }
    catch (_: java.nio.charset.CharacterCodingException) { false }
}
