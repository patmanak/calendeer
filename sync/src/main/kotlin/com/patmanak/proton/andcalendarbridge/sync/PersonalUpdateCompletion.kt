// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Historical closure only. It never resets UNKNOWN or permits reusing its request identity. */
class PersonalUpdateCompletion private constructor(val result: PersonalUpdateVerifiedResult, val receipt: PersonalUpdateAcknowledgementReceipt) {
    fun snapshot() = of(result, PersonalUpdateAcknowledgementRecord(receipt, PersonalUpdateAcknowledgementState.RESTORED))
    override fun equals(other: Any?) = other is PersonalUpdateCompletion && result == other.result && receipt == other.receipt
    override fun hashCode() = 31 * result.hashCode() + receipt.hashCode()
    override fun toString() = "PersonalUpdateCompletion(redacted)"
    companion object {
        fun of(result: PersonalUpdateVerifiedResult, acknowledgement: PersonalUpdateAcknowledgementRecord): PersonalUpdateCompletion {
            require(acknowledgement.state == PersonalUpdateAcknowledgementState.RESTORED)
            val frozen = result.snapshot()
            val receipt = acknowledgement.receipt.snapshot()
            require(receipt.matches(frozen))
            return PersonalUpdateCompletion(frozen, receipt)
        }
    }
}

/** Append closure only against the first durable result and exact RESTORED receipt.
 * Successful commit may release an event for different work, never its historical request ID.
 * Failed/denied reads do not certify absence. No state reset, delete or resend is exposed.
 */
interface PersonalUpdateCompletionJournal : PersonalUpdateResultJournal {
    val completionProtocol: Int?
    suspend fun readCompletion(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): PersonalUpdateCompletion?
    suspend fun retainCompletion(completion: PersonalUpdateCompletion, admit: (CalendarScope) -> Boolean): Boolean
}

/** One shared store supplies attempts and their immutable result/completion history. */
interface PersonalUpdateRecoveryJournal : PersonalUpdateAttemptJournal, PersonalUpdateCompletionJournal, PersonalUpdateWorkJournal
