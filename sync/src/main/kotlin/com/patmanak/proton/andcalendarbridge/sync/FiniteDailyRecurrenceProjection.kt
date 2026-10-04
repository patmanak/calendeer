// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import com.patmanak.proton.andcalendarbridge.sync.EventRecurrence
import com.patmanak.proton.andcalendarbridge.sync.EventTime
import com.patmanak.proton.andcalendarbridge.sync.RecurrencePoint
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Collections

/** Bounded pure policy. No runtime import caller or historical reloading API. */
object FiniteDailyRecurrenceProjection {
    class Schedule internal constructor(val canonical: EventRecurrence, val dates: List<Instant>, val native: EventRecurrence) {
        override fun toString() = "FiniteDailySchedule(redacted)"
    }

    fun plan(time: EventTime, recurrence: EventRecurrence): Schedule {
        val rule = recurrence.rule ?: refuse()
        if (time !is EventTime.Timed || !recurrence.isMaster ||
            recurrence.additionalDates.isNotEmpty() || recurrence.excludedDates.size > 32 ||
            recurrence.excludedDates.any { it !is RecurrencePoint.Timed || it.instant.nano != 0 } ||
            time.start.nano != 0 || time.end.nano != 0) refuse()
        val fields = rule.split(';').map { it.split('=') }
        if (fields.size !in 2..3 || fields.any { it.size != 2 } ||
            fields.map { it[0] }.distinct().size != fields.size) refuse()
        val values = fields.associate { it[0] to it[1] }
        if (values.keys.any { it !in setOf("FREQ", "COUNT", "INTERVAL") } || values["FREQ"] != "DAILY") refuse()
        fun number(value: String?): Int = value?.takeIf { it.matches(Regex("[1-9][0-9]*")) }
            ?.toIntOrNull() ?: refuse()
        val count = number(values["COUNT"]).takeIf { it in 1..32 } ?: refuse()
        val interval = number(values["INTERVAL"] ?: "1").takeIf { it in 1..366 } ?: refuse()
        if ((count - 1L) * interval > 366) refuse()
        return try {
            val duration = Duration.between(time.start, time.end)
            if (duration.seconds !in 1..604800) refuse()
            val nominal = time.start.atZone(time.zone).toLocalDateTime()
            if (nominal.year !in 1..9999 || time.zone.rules.getValidOffsets(nominal).size != 1) refuse()
            fun nativeYear(instant: Instant) {
                if (instant.atOffset(ZoneOffset.UTC).year !in 1..9999) refuse()
            }
            nativeYear(time.start); nativeYear(time.end)
            recurrence.excludedDates.forEach { nativeYear((it as RecurrencePoint.Timed).instant) }
            val dates = frozen((0 until count).map { index ->
                // Always use the nominal start; never carry a normalized gap hour forward.
                val local = nominal.plusDays(index.toLong() * interval)
                if (local.year !in 1..9999) refuse()
                val offsets = time.zone.rules.getValidOffsets(local)
                val offset = offsets.firstOrNull() ?: time.zone.rules.getTransition(local)?.offsetBefore ?: refuse()
                local.toInstant(offset).also { nativeYear(it); nativeYear(it.plus(duration)) }
            })
            val canonical = recurrence.copy(additionalDates = frozen(recurrence.additionalDates),
                excludedDates = frozen(recurrence.excludedDates))
            Schedule(canonical, dates, canonical.copy(rule = null,
                additionalDates = frozen(dates.map { RecurrencePoint.Timed(it) })))
        } catch (_: DateTimeException) {
            refuse()
        } catch (_: ArithmeticException) {
            refuse()
        }
    }

    private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    private fun refuse(): Nothing = throw IllegalArgumentException("Unsupported finite daily experiment")
}
