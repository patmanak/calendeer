// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Immutable historical proof; independently attachable response evidence is excluded. */
class TimeUpdateVerifiedResult private constructor(val record: TimeUpdateAttemptRecord, val event: RemoteChange.Upsert) {
    val attempt: TimeUpdateAttemptIdentity get() = TimeUpdateAttemptIdentity.of(record)
    fun snapshot() = of(record, TimeUpdateSubmissionObservation.Verified(record, event))
    override fun equals(other: Any?) = other is TimeUpdateVerifiedResult && record == other.record && event == other.event
    override fun hashCode() = 31 * record.hashCode() + event.hashCode()
    override fun toString() = "TimeUpdateVerifiedResult(redacted)"
    companion object {
        fun of(record: TimeUpdateAttemptRecord, observation: TimeUpdateSubmissionObservation.Verified): TimeUpdateVerifiedResult {
            val frozen = record.frozen().copy(protectedEvidence = null)
            require(frozen.state == TimeUpdateAttemptState.OUTCOME_UNKNOWN)
            require(observation.record.frozen().copy(protectedEvidence = null) == frozen)
            val validated = TimeUpdateSubmissionObservation.Verified(frozen, observation.event)
            return TimeUpdateVerifiedResult(frozen, validated.event)
        }
    }
}

/** The first full result is immutable. Commit against exact UNKNOWN work excluding evidence only.
 * Scoped admission applies to reads and idempotent writes through commit; denial never means absence.
 * These historical snapshots establish neither current service state nor native ACK authority.
 */
interface TimeUpdateResultJournal {
    suspend fun retainVerified(result: TimeUpdateVerifiedResult, admit: (CalendarScope) -> Boolean): Boolean
    suspend fun readVerified(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TimeUpdateVerifiedResult?
}
