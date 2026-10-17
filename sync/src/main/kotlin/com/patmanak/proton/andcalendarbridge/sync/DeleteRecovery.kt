// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Historical authenticated absence. It does not assert authorship or current service state. */
class DeleteVerifiedResult private constructor(val record: DeleteAttemptRecord) {
    val attempt: DeleteAttemptIdentity get() = DeleteAttemptIdentity.of(record)
    fun snapshot() = of(record, DeleteSubmissionObservation.Verified(record))
    override fun equals(other: Any?) = other is DeleteVerifiedResult && record == other.record
    override fun hashCode() = record.hashCode()
    override fun toString() = "DeleteVerifiedResult(redacted)"
    companion object {
        fun of(record: DeleteAttemptRecord, observation: DeleteSubmissionObservation.Verified): DeleteVerifiedResult {
            val frozen = record.frozen().copy(protectedEvidence = null)
            require(frozen.state == DeleteAttemptState.OUTCOME_UNKNOWN)
            require(observation.record.copy(protectedEvidence = null) == frozen)
            return DeleteVerifiedResult(frozen)
        }
    }
}

/** Append-only closure. Neither the event hold nor the old request identity is released. */
class DeleteCompletion private constructor(val result: DeleteVerifiedResult, val receipt: DeleteAcknowledgementReceipt) {
    fun snapshot() = of(result, DeleteAcknowledgementRecord(receipt, DeleteAcknowledgementState.RESTORED))
    override fun equals(other: Any?) = other is DeleteCompletion && result == other.result && receipt == other.receipt
    override fun hashCode() = 31 * result.hashCode() + receipt.hashCode()
    override fun toString() = "DeleteCompletion(redacted)"
    companion object {
        fun of(result: DeleteVerifiedResult, acknowledgement: DeleteAcknowledgementRecord): DeleteCompletion {
            require(acknowledgement.state == DeleteAcknowledgementState.RESTORED)
            val frozen = result.snapshot()
            val receipt = acknowledgement.receipt.snapshot()
            require(receipt.matches(DeleteSubmissionObservation.Verified(frozen.record)))
            return DeleteCompletion(frozen, receipt)
        }
    }
}

/** Full attempts/proofs share one owner and transaction. A closed deletion remains a barrier.
 * UNKNOWN is read-only inspection; neither proof retention nor completion permits retransmission.
 * Every method requires a scoped admission that stays closed once denied.
 */
interface DeleteRecoveryJournal : DeleteAttemptJournal {
    val completionProtocol: Int?
    suspend fun retainVerified(result: DeleteVerifiedResult, admit: (CalendarScope) -> Boolean): Boolean
    suspend fun readVerified(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): DeleteVerifiedResult?
    suspend fun retainCompletion(completion: DeleteCompletion, admit: (CalendarScope) -> Boolean): Boolean
    suspend fun readCompletion(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): DeleteCompletion?
    suspend fun page(query: DeleteWorkQuery, admit: (CalendarScope) -> Boolean): DeleteWorkPage?
}
