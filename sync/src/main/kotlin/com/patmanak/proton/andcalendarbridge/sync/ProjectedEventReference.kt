// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.util.Collections

/** Representation versions are independent of remote adapter and receipt protocols. */
enum class ProjectionRepresentation(val id: String, val version: Int) {
    DIRECT_V1("direct", 1), FINITE_DAILY_V1("finite-daily-dates", 1);
}

/** Pure proposed reference; no scope, persistence, acknowledgement or write authority.
 * Factories plan new references only. Reopening must eventually use persisted dates,
 * not these factories or the current timezone database. Direct is not Provider admission.
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
        fun direct(change: RemoteChange.Upsert): ProjectedEventReference {
            val canonical = freeze(change)
            return ProjectedEventReference(canonical, ProjectionRepresentation.DIRECT_V1, frozen(emptyList()), canonical)
        }

        fun finiteDaily(change: RemoteChange.Upsert): ProjectedEventReference {
            val canonical = freeze(change)
            val recurrence = canonical.content.recurrence ?: throw IllegalArgumentException("Missing finite daily rule")
            val schedule = FiniteDailyRecurrenceProjection.plan(canonical.content.time, recurrence)
            val native = canonical.copy(content = canonical.content.copy(recurrence = schedule.native))
            return ProjectedEventReference(canonical, ProjectionRepresentation.FINITE_DAILY_V1, schedule.dates, native)
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
