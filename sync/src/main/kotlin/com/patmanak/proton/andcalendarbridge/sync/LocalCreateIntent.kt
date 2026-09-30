// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.util.UUID

/** Bridge-owned local identity, never a remote event ID or an Android row locator. */
data class LocalEventId(val value: String) {
    init { require(UUID.fromString(value).toString() == value) { "Canonical local UUID required" } }
    override fun toString() = "LocalEventId(REDACTED)"
}

/** Durable one-shot permission to stamp an identity. Reopening permits inspection only. */
data class LocalCreateReservation(
    val id: MutationId,
    val localId: LocalEventId,
    val scope: CalendarScope,
    val format: AdapterFormat,
    val timestamp: Instant,
    val markerProtocol: Int = 1,
) {
    init { require(timestamp.nano == 0 && markerProtocol == 1) }
    override fun toString() = "LocalCreateReservation(REDACTED)"
}

/** First committed supported snapshot, permanently paused. No dispatch or remote association. */
data class LocalCreateIntent(val reservation: LocalCreateReservation, val content: EventContent) {
    init {
        require(content.recurrence == null && content.details == EventDetails() && content.colorRgb == null)
        val time = content.time
        if (time is EventTime.Timed) require(time.start.nano == 0 && time.end.nano == 0)
    }
    override fun toString() = "LocalCreateIntent(REDACTED)"
}

/** Immutable adapter-protected preparation of one captured snapshot, never dispatch admission. */
data class LocalCreatePreparation(val intent: LocalCreateIntent, val format: AdapterFormat, val protectedPayload: String) {
    init { require(protectedPayload.isNotBlank()) }
    override fun toString() = "LocalCreatePreparation(REDACTED)"
}
