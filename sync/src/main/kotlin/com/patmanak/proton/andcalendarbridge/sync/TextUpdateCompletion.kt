// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Historical closure only. It never resets UNKNOWN or permits reusing its request identity. */
class TextUpdateCompletion private constructor(val result: TextUpdateVerifiedResult, val receipt: TextUpdateAcknowledgementReceipt) {
    fun snapshot() = of(result, TextUpdateAcknowledgementRecord(receipt, TextUpdateAcknowledgementState.RESTORED))
    override fun equals(other: Any?) = other is TextUpdateCompletion && result == other.result && receipt == other.receipt
    override fun hashCode() = 31 * result.hashCode() + receipt.hashCode()
    override fun toString() = "TextUpdateCompletion(redacted)"
    companion object {
        fun of(result: TextUpdateVerifiedResult, acknowledgement: TextUpdateAcknowledgementRecord): TextUpdateCompletion {
            require(acknowledgement.state == TextUpdateAcknowledgementState.RESTORED)
            val frozen = result.snapshot()
            val receipt = acknowledgement.receipt.snapshot()
            require(receipt.matches(frozen))
            return TextUpdateCompletion(frozen, receipt)
        }
    }
}

/** Append closure only against the first durable result and exact RESTORED receipt.
 * Successful commit may release an event for different work, never its historical request ID.
 * Failed/denied reads do not certify absence. No state reset, delete or resend is exposed.
 */
interface TextUpdateCompletionJournal : TextUpdateResultJournal {
    val completionProtocol: Int?
    suspend fun readCompletion(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TextUpdateCompletion?
    suspend fun retainCompletion(completion: TextUpdateCompletion, admit: (CalendarScope) -> Boolean): Boolean
}

/** One shared store supplies attempts and their immutable result/completion history. */
interface TextUpdateRecoveryJournal : TextUpdateAttemptJournal, TextUpdateCompletionJournal, TextUpdateWorkJournal
