// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Immutable historical proof; independently attachable response evidence is excluded. */
class PersonalUpdateVerifiedResult private constructor(val record: PersonalUpdateAttemptRecord, val event: RemoteChange.Upsert) {
    val attempt: PersonalUpdateAttemptIdentity get() = PersonalUpdateAttemptIdentity.of(record)
    fun snapshot() = of(record, PersonalUpdateSubmissionObservation.Verified(record, event))
    override fun equals(other: Any?) = other is PersonalUpdateVerifiedResult && record == other.record && event == other.event
    override fun hashCode() = 31 * record.hashCode() + event.hashCode()
    override fun toString() = "PersonalUpdateVerifiedResult(redacted)"
    companion object {
        fun of(record: PersonalUpdateAttemptRecord, observation: PersonalUpdateSubmissionObservation.Verified): PersonalUpdateVerifiedResult {
            val frozen = record.frozen().copy(protectedEvidence = null)
            require(frozen.state == PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
            require(observation.record.frozen().copy(protectedEvidence = null) == frozen)
            val validated = PersonalUpdateSubmissionObservation.Verified(frozen, observation.event)
            return PersonalUpdateVerifiedResult(frozen, validated.event)
        }
    }
}

/** The first full result is immutable. Commit against exact UNKNOWN work excluding evidence only.
 * Scoped admission applies to reads and idempotent writes through commit; denial never means absence.
 * These historical snapshots establish neither current service state nor native ACK authority.
 */
interface PersonalUpdateResultJournal {
    suspend fun retainVerified(result: PersonalUpdateVerifiedResult, admit: (CalendarScope) -> Boolean): Boolean
    suspend fun readVerified(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): PersonalUpdateVerifiedResult?
}
