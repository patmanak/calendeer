// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Durable native acknowledgement identity. No synthetic remote tombstone or revision. */
class DeleteAcknowledgementReceipt(val attempt: DeleteAttemptIdentity, intent: LocalDeleteIntent, val nonce: String) {
    val intent = DeleteSubmissionPolicy.freezeIntent(intent)
    init {
        require(nonce.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
        require(attempt.scope == this.intent.scope && attempt.localIntentId == this.intent.id &&
            attempt.eventId == this.intent.base.eventId && attempt.baseRevision == this.intent.base.revision &&
            attempt.intentFingerprint == DeleteAttemptIdentity.intentFingerprint(this.intent))
    }
    fun copy(attempt: DeleteAttemptIdentity = this.attempt, intent: LocalDeleteIntent = this.intent,
        nonce: String = this.nonce) = DeleteAcknowledgementReceipt(attempt, intent, nonce)
    fun snapshot() = copy()
    fun matches(result: DeleteSubmissionObservation.Verified) = this == of(result, nonce)
    override fun equals(other: Any?) = other is DeleteAcknowledgementReceipt &&
        attempt == other.attempt && intent == other.intent && nonce == other.nonce
    override fun hashCode() = listOf(attempt, intent, nonce).hashCode()
    override fun toString() = "DeleteAcknowledgementReceipt(redacted)"
    companion object {
        fun of(result: DeleteSubmissionObservation.Verified, nonce: String): DeleteAcknowledgementReceipt {
            val record = result.record.frozen()
            return DeleteAcknowledgementReceipt(DeleteAttemptIdentity.of(record), record.intent, nonce)
        }
    }
}
data class AppliedDeleteAcknowledgement(val receipt: DeleteAcknowledgementReceipt) {
    override fun toString() = "AppliedDeleteAcknowledgement(redacted)"
}
enum class DeleteAcknowledgementState { PENDING, RESTORED }
data class DeleteAcknowledgementRecord(val receipt: DeleteAcknowledgementReceipt, val state: DeleteAcknowledgementState) {
    override fun toString() = "DeleteAcknowledgementRecord(state=$state, redacted)"
}
data class DeleteAcknowledgementReservation(val record: DeleteAcknowledgementRecord, val fresh: Boolean) {
    override fun toString() = "DeleteAcknowledgementReservation(redacted)"
}
