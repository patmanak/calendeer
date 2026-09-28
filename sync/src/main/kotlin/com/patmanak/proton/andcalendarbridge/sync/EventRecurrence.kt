package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate

/** RFC 5545 recurrence identity: an original slot, independent of a moved event's start. */
sealed interface RecurrencePoint {
    data class AllDay(val date: LocalDate) : RecurrencePoint
    data class Timed(val instant: Instant) : RecurrencePoint
}

/** Standard calendar data, independent of the remote protocol and Android provider columns. */
data class EventRecurrence(
    val uid: String,
    val rule: String? = null,
    val additionalDates: List<RecurrencePoint> = emptyList(),
    val excludedDates: List<RecurrencePoint> = emptyList(),
    val original: RecurrencePoint? = null,
    val cancelled: Boolean = false,
) {
    init {
        require(uid.isNotBlank() && uid.length <= 1024 && '\r' !in uid && '\n' !in uid)
        require(rule == null || rule.isNotBlank() && rule.length <= 4096 && '\r' !in rule && '\n' !in rule)
        require(additionalDates.size <= 512 && excludedDates.size <= 512)
        require(if (original == null) rule != null || additionalDates.isNotEmpty()
            else rule == null && additionalDates.isEmpty() && excludedDates.isEmpty())
        require(!cancelled || original != null)
    }
    val isMaster get() = original == null
}
