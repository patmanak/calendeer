// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Immutable historical proof; independently attachable response evidence is excluded. */
class TextUpdateVerifiedResult private constructor(val record: TextUpdateAttemptRecord, val event: RemoteChange.Upsert) {
    val attempt: TextUpdateAttemptIdentity get() = TextUpdateAttemptIdentity.of(record)
    fun snapshot() = of(record, TextUpdateSubmissionObservation.Verified(record, event))
    override fun equals(other: Any?) = other is TextUpdateVerifiedResult && record == other.record && event == other.event
    override fun hashCode() = 31 * record.hashCode() + event.hashCode()
    override fun toString() = "TextUpdateVerifiedResult(redacted)"
    companion object {
        fun of(record: TextUpdateAttemptRecord, observation: TextUpdateSubmissionObservation.Verified): TextUpdateVerifiedResult {
            val frozen = record.frozen().copy(protectedEvidence = null)
            require(frozen.state == TextUpdateAttemptState.OUTCOME_UNKNOWN)
            require(observation.record.frozen().copy(protectedEvidence = null) == frozen)
            val validated = TextUpdateSubmissionObservation.Verified(frozen, observation.event)
            return TextUpdateVerifiedResult(frozen, validated.event)
        }
    }
}

/** The first full result is immutable. Commit against exact UNKNOWN work excluding evidence only.
 * Scoped admission applies to reads and idempotent writes through commit; denial never means absence.
 * These historical snapshots establish neither current service state nor native ACK authority.
 */
interface TextUpdateResultJournal {
    suspend fun retainVerified(result: TextUpdateVerifiedResult, admit: (CalendarScope) -> Boolean): Boolean
    suspend fun readVerified(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TextUpdateVerifiedResult?
}
