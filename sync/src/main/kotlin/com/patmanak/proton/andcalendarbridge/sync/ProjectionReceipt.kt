// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.util.Collections

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

    /** Own all collection data before crossing a suspension/storage/Provider boundary. */
    fun snapshot(): ProjectionReceipt {
        require(supports(change)) { "Unsupported projection receipt content" }
        fun freeze(value: RemoteChange.Upsert): RemoteChange.Upsert {
            val content = value.content
            val details = content.details?.let {
                require(it.reminders.size <= 5 && it.attendees.size <= 100)
                EventDetails(Collections.unmodifiableList(it.reminders.toList()), it.organizer,
                    Collections.unmodifiableList(it.attendees.toList()))
            }
            val recurrence = content.recurrence?.let {
                require(it.additionalDates.size <= 512 && it.excludedDates.size <= 512)
                it.copy(additionalDates = Collections.unmodifiableList(it.additionalDates.toList()),
                    excludedDates = Collections.unmodifiableList(it.excludedDates.toList()))
            }
            return value.copy(content = content.copy(details = details, recurrence = recurrence))
        }
        return copy(change = freeze(change), previousBase = previousBase?.let(::freeze))
    }

    companion object {
        fun supports(change: RemoteChange): Boolean {
            val content = (change as? RemoteChange.Upsert)?.content ?: return false
            if (content.recurrence != null) return false
            content.details?.let {
                if (it.organizer != null || it.attendees.isNotEmpty() || it.reminders.size > 5 ||
                    it.reminders.distinct().size != it.reminders.size) return false
            }
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
