// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Historical closure only. It never resets UNKNOWN or permits reusing its request identity. */
class TimeUpdateCompletion private constructor(val result: TimeUpdateVerifiedResult, val receipt: TimeUpdateAcknowledgementReceipt) {
    fun snapshot() = of(result, TimeUpdateAcknowledgementRecord(receipt, TimeUpdateAcknowledgementState.RESTORED))
    override fun equals(other: Any?) = other is TimeUpdateCompletion && result == other.result && receipt == other.receipt
    override fun hashCode() = 31 * result.hashCode() + receipt.hashCode()
    override fun toString() = "TimeUpdateCompletion(redacted)"
    companion object {
        fun of(result: TimeUpdateVerifiedResult, acknowledgement: TimeUpdateAcknowledgementRecord): TimeUpdateCompletion {
            require(acknowledgement.state == TimeUpdateAcknowledgementState.RESTORED)
            val frozen = result.snapshot()
            val receipt = acknowledgement.receipt.snapshot()
            require(receipt.matches(frozen))
            return TimeUpdateCompletion(frozen, receipt)
        }
    }
}

/** Append closure only against the first durable result and exact RESTORED receipt.
 * Successful commit may release an event for different work, never its historical request ID.
 * Failed/denied reads do not certify absence. No state reset, delete or resend is exposed.
 */
interface TimeUpdateCompletionJournal : TimeUpdateResultJournal {
    val completionProtocol: Int?
    suspend fun readCompletion(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TimeUpdateCompletion?
    suspend fun retainCompletion(completion: TimeUpdateCompletion, admit: (CalendarScope) -> Boolean): Boolean
}

/** One shared store supplies attempts and their immutable result/completion history. */
interface TimeUpdateRecoveryJournal : TimeUpdateAttemptJournal, TimeUpdateCompletionJournal, TimeUpdateWorkJournal
