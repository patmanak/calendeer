// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.Duration
import java.time.DateTimeException
import java.time.ZoneOffset
import java.util.Collections

/** Representation versions are independent of remote adapter and receipt protocols. */
enum class ProjectionRepresentation(val id: String, val version: Int) {
    DIRECT_V1("direct", 1), FINITE_DAILY_V1("finite-daily-dates", 1);
}

/** Pure proposed reference; no scope, persistence, acknowledgement or write authority.
 * Creation factories calculate new references; restore uses saved dates without
 * timezone recalculation. Neither structural decoding nor Direct is Provider admission.
 */
class ProjectedEventReference private constructor(
    val canonical: RemoteChange.Upsert,
    val representation: ProjectionRepresentation,
    val dates: List<Instant>,
    val nativeChange: RemoteChange.Upsert,
) {
    override fun toString() = "ProjectedEventReference(redacted)"
    override fun equals(other: Any?): Boolean = other is ProjectedEventReference &&
        canonical == other.canonical && representation == other.representation && dates == other.dates
    override fun hashCode(): Int = 31 * (31 * canonical.hashCode() + representation.hashCode()) + dates.hashCode()

    companion object {
        /** Structural historical decoding only, never calculation or write authority. */
        fun restore(change: RemoteChange.Upsert, representation: ProjectionRepresentation, dates: List<Instant>): ProjectedEventReference {
            try {
            val canonical = freeze(change)
            val saved = frozen(dates)
            if (representation == ProjectionRepresentation.DIRECT_V1) {
                require(saved.isEmpty()) { "Direct reference cannot contain dates" }
                return ProjectedEventReference(canonical, representation, saved, canonical)
            }
            val time = canonical.content.time as? EventTime.Timed ?: throw IllegalArgumentException("Invalid stored finite time")
            val recurrence = canonical.content.recurrence ?: throw IllegalArgumentException("Missing stored finite rule")
            require(recurrence.isMaster && recurrence.additionalDates.isEmpty() && recurrence.excludedDates.size <= 32)
            val fields = recurrence.rule?.split(';')?.map { it.split('=') } ?: throw IllegalArgumentException("Missing stored finite rule")
            require(fields.size in 2..3 && fields.all { it.size == 2 } && fields.map { it[0] }.distinct().size == fields.size)
            val values = fields.associate { it[0] to it[1] }
            require(values.keys.all { it in setOf("FREQ", "COUNT", "INTERVAL") } && values["FREQ"] == "DAILY")
            fun number(value: String?): Int = value?.takeIf { it.matches(Regex("[1-9][0-9]*")) }?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid stored finite bound")
            val count = number(values["COUNT"]); val interval = number(values["INTERVAL"] ?: "1")
            require(count in 1..32 && interval in 1..366 && (count - 1L) * interval <= 366)
            val duration = Duration.between(time.start, time.end)
            require(duration.seconds in 1..604800 && saved.size == count && saved.first() == time.start)
            // Whole-day gaps can produce equal instants. Preserve saved order and duplicates.
            require(saved.zipWithNext().all { (before, after) -> after >= before })
            fun bounded(value: Instant) { require(value.nano == 0 && value.atOffset(ZoneOffset.UTC).year in 1..9999) }
            bounded(time.start); bounded(time.end)
            saved.forEach { bounded(it); bounded(it.plus(duration)) }
            recurrence.excludedDates.forEach { bounded((it as RecurrencePoint.Timed).instant) }
            val native = canonical.copy(content = canonical.content.copy(recurrence = recurrence.copy(rule = null,
                additionalDates = frozen(saved.map { RecurrencePoint.Timed(it) }))))
            return ProjectedEventReference(canonical, representation, saved, native)
            } catch (_: DateTimeException) {
                throw IllegalArgumentException("Invalid stored projection date")
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("Invalid stored projection date")
            }
        }

        fun direct(change: RemoteChange.Upsert): ProjectedEventReference {
            val canonical = freeze(change)
            return ProjectedEventReference(canonical, ProjectionRepresentation.DIRECT_V1, frozen(emptyList()), canonical)
        }

        fun finiteDaily(change: RemoteChange.Upsert): ProjectedEventReference {
            val canonical = freeze(change)
            val recurrence = canonical.content.recurrence ?: throw IllegalArgumentException("Missing finite daily rule")
            val schedule = FiniteDailyRecurrenceProjection.plan(canonical.content.time, recurrence)
            return restore(canonical, ProjectionRepresentation.FINITE_DAILY_V1, schedule.dates)
        }

        private fun freeze(change: RemoteChange.Upsert): RemoteChange.Upsert {
            // Revalidate aliases mutated after their domain constructors, then own every list.
            require(ProjectionReceipt.supports(change)) { "Unsupported projection reference content" }
            val content = change.content
            val details = content.details?.let { EventDetails(frozen(it.reminders), it.organizer, frozen(it.attendees)) }
            val recurrence = content.recurrence?.let {
                it.copy(additionalDates = frozen(it.additionalDates), excludedDates = frozen(it.excludedDates))
            }
            return change.copy(content = content.copy(recurrence = recurrence, details = details))
        }

        private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    }
}
