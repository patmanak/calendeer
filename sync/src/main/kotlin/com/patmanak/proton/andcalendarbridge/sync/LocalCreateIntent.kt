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
class LocalCreateIntent(val reservation: LocalCreateReservation, content: EventContent,
    val series: CreateSeriesRule? = null, val occurrence: OccurrenceEditCandidate? = null) {
    val content: EventContent = freezeCapturedCreateContent(content)
    init {
        require(occurrence == null || (series == null && this.content == occurrence.content.copy(recurrence = null)))
        require(series?.supports(this.content.time) != false) { "Unsupported series time" }
        val time = content.time
        if (time is EventTime.Timed) require(time.start.nano == 0 && time.end.nano == 0)
    }
    fun copy(reservation: LocalCreateReservation = this.reservation, content: EventContent = this.content,
        series: CreateSeriesRule? = this.series, occurrence: OccurrenceEditCandidate? = this.occurrence) =
        LocalCreateIntent(reservation, content, series, occurrence)
    operator fun component1() = reservation
    operator fun component2() = content
    override fun equals(other: Any?) = other is LocalCreateIntent && reservation == other.reservation &&
        content == other.content && series == other.series && occurrence == other.occurrence
    override fun hashCode(): Int = (31 * reservation.hashCode() + content.hashCode()).let {
        val legacy = if (series == null) it else 31 * it + series.hashCode()
        if (occurrence == null) legacy else 31 * legacy + occurrence.hashCode()
    }
    override fun toString() = "LocalCreateIntent(REDACTED)"
}

/** Own and revalidate personal metadata, including lists supplied through mutable callers. */
internal fun freezeCapturedCreateContent(content: EventContent): EventContent {
    val details = requireNotNull(content.details) { "Known personal details required" }
    val frozen = details.copy(
        reminders = java.util.Collections.unmodifiableList(details.reminders.toList()),
        attendees = java.util.Collections.unmodifiableList(details.attendees.toList()))
    require(content.recurrence == null && frozen.organizer == null && frozen.attendees.isEmpty())
    return content.copy(details = frozen)
}

/** Immutable adapter-protected preparation of one captured snapshot, never dispatch admission. */
data class LocalCreatePreparation(val intent: LocalCreateIntent, val format: AdapterFormat, val protectedPayload: String) {
    init { require(protectedPayload.isNotBlank() && intent.occurrence == null) }
    override fun toString() = "LocalCreatePreparation(REDACTED)"
}
