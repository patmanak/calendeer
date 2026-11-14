// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** A local creation rule, deliberately without a remote UID or an exception identity. */
data class CreateSeriesRule(val count: Int, val interval: Int = 1) {
    init { require(count in 2..366 && interval in 1..365) }
    val rule: String get() = "FREQ=DAILY;COUNT=$count" + if (interval == 1) "" else ";INTERVAL=$interval"
    fun supports(time: EventTime): Boolean = try {
        when (time) {
            is EventTime.AllDay -> (0 until count).all { index ->
                val days = index.toLong() * interval
                time.start.plusDays(days).year in 1970..9999 && time.endExclusive.plusDays(days).year in 1970..9999
            }
            is EventTime.Timed -> {
                val zone = time.zone
                val qualifiedZone = zone == ZoneOffset.UTC || (zone.id in ZoneId.getAvailableZoneIds() &&
                    (!zone.rules.isFixedOffset || zone.rules.getOffset(Instant.EPOCH) == ZoneOffset.UTC))
                val start = time.start.atZone(zone).toLocalDateTime()
                val end = time.end.atZone(zone).toLocalDateTime()
                val duration = Duration.between(time.start, time.end)
                qualifiedZone && time.start.nano == 0 && time.end.nano == 0 && time.start >= Instant.EPOCH &&
                    !RecurringLocalTimeRisk.detected(time.start, zone, rule) &&
                    !RecurringLocalTimeRisk.detected(time.end, zone, rule) && (0 until count).all { index ->
                        val days = index.toLong() * interval
                        val slotStart = start.plusDays(days); val slotEnd = end.plusDays(days)
                        val starts = zone.rules.getValidOffsets(slotStart); val ends = zone.rules.getValidOffsets(slotEnd)
                        slotStart.year in 1970..9999 && slotEnd.year in 1970..9999 && starts.size == 1 && ends.size == 1 &&
                            slotStart.toInstant(starts.single()) in Instant.EPOCH..LAST_TIMESTAMP &&
                            slotEnd.toInstant(ends.single()) in Instant.EPOCH..LAST_TIMESTAMP &&
                            Duration.between(slotStart.toInstant(starts.single()), slotEnd.toInstant(ends.single())) == duration
                    }
            }
        }
    } catch (_: java.time.DateTimeException) { false }
    override fun toString() = "CreateSeriesRule(REDACTED)"

    companion object {
        private val LAST_TIMESTAMP = Instant.parse("9999-12-31T23:59:59Z")
        fun parse(source: String): CreateSeriesRule {
            require(source.length in 1..96 && source.matches(Regex("[A-Za-z]+=[A-Za-z0-9]+(;[A-Za-z]+=[A-Za-z0-9]+)*")))
            val fields = source.uppercase(Locale.ROOT).split(';').map { it.split('=') }
            require(fields.map { it[0] }.distinct().size == fields.size)
            val values = fields.associate { it[0] to it[1] }
            require(values.keys.all { it in setOf("FREQ", "COUNT", "INTERVAL") } && values["FREQ"] == "DAILY")
            fun number(value: String?): Int {
                require(value != null && value.matches(Regex("[1-9][0-9]{0,2}")))
                return value.toInt()
            }
            return CreateSeriesRule(number(values["COUNT"]), values["INTERVAL"]?.let(::number) ?: 1)
        }
    }
}
