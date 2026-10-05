// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Historical closure of one UNKNOWN attempt; never permission to send that attempt again.
 * Binding validation does not establish persistence: callers must read the actual durable
 * verified result and RESTORED acknowledgement before retaining a completion.
 */
class TitleUpdateCompletion private constructor(
    val result: TitleUpdateVerifiedResult,
    val receipt: TitleUpdateAcknowledgementReceipt,
) {
    fun snapshot(): TitleUpdateCompletion = of(result,
        TitleUpdateAcknowledgementRecord(receipt, TitleUpdateAcknowledgementState.RESTORED))
    override fun equals(other: Any?) = other is TitleUpdateCompletion && result == other.result && receipt == other.receipt
    override fun hashCode() = 31 * result.hashCode() + receipt.hashCode()
    override fun toString() = "TitleUpdateCompletion(redacted)"
    companion object {
        fun of(result: TitleUpdateVerifiedResult, acknowledgement: TitleUpdateAcknowledgementRecord): TitleUpdateCompletion {
            require(acknowledgement.state == TitleUpdateAcknowledgementState.RESTORED)
            val frozen = TitleUpdateVerifiedResult.of(result.record,
                TitleUpdateSubmissionObservation.Verified(result.attempt, result.event))
            val receipt = acknowledgement.receipt.snapshot()
            require(receipt.matches(frozen))
            return TitleUpdateCompletion(frozen, receipt)
        }
    }
}

/** Append an immutable closure against the original UNKNOWN work and first retained result.
 * Admission is scoped to this invocation, denied by default and checked through commit.
 * Historical reads grant no dispatch. Completion may release an event's hold for a distinct
 * new intention, but must leave the old state, payload, preparation and result unchanged.
 */
interface TitleUpdateCompletionJournal : TitleUpdateResultJournal {
    val completionProtocol: Int? get() = null
    suspend fun readCompletion(scope: CalendarScope, id: MutationId): TitleUpdateCompletion? = null
    suspend fun retainCompletion(completion: TitleUpdateCompletion,
        admit: (CalendarScope) -> Boolean = { false }): Boolean = false
}
