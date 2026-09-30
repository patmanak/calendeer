// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Durable preparation only. The nonce must be written with the event in one Provider transaction. */
data class ProjectionReceipt(
    val scope: CalendarScope,
    val format: AdapterFormat,
    val change: RemoteChange.Upsert,
    val nonce: String,
    val previousBase: RemoteChange.Upsert?,
    val protocol: Int = 1,
) {
    init {
        require(protocol == 1 && nonce.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
        require(previousBase == null || previousBase.eventId == change.eventId)
        require(supports(change))
    }
    override fun toString() = "ProjectionReceipt(redacted)"

    companion object {
        fun supports(change: RemoteChange): Boolean {
            val content = (change as? RemoteChange.Upsert)?.content ?: return false
            if (content.recurrence != null || (content.details != null && content.details != EventDetails())) return false
            return when (val time = content.time) {
                is EventTime.AllDay -> true
                is EventTime.Timed -> time.start.nano == 0 && time.end.nano == 0
            }
        }
    }
}

/** Supplied only after successful atomic apply or qualified inspection, never by preparation alone. */
data class AppliedProjectionWitness(val receipt: ProjectionReceipt) {
    override fun toString() = "AppliedProjectionWitness(redacted)"
}
