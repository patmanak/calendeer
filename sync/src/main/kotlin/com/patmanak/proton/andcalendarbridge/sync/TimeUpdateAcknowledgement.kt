// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Raw native intention and actual remote result; only CRLF equivalence relates their text fields. */
class TimeUpdateAcknowledgementReceipt(val attempt: TimeUpdateAttemptIdentity, intent: LocalEditIntent,
    projection: ProjectionReceipt, handoff: CapturedCreateHandoffFrame? = null) {
    val intent = LocalTimeEditCandidate(intent).intent
    val projection = projection.snapshot()
    val handoff = handoff?.let(::freezeHandoff)
    init {
        val original = this.intent
        val projected = this.projection
        require(original.base.content.details == EventDetails() && original.base.content.colorRgb == null)
        require(projected.change.content.details == EventDetails() && projected.change.content.colorRgb == null &&
            projected.change.content.recurrence == null)
        require(attempt.scope == original.scope && attempt.localIntentId == original.id &&
            attempt.eventId == original.base.eventId && attempt.baseRevision == original.base.revision)
        require(projected.scope == original.scope && projected.format == original.format && projected.previousBase == original.base &&
            projected.change.eventId == original.base.eventId && projected.change.revision != original.base.revision)
        require(projected.representation == null && projected.previousRepresentation == null)
        TimeUpdateContentPolicy.freezeObservation(original.content, projected.change.content)
        this.handoff?.let {
            require(it.scope == original.scope && it.format == original.format && it.base == original.base)
            CapturedHandoffProjection(it, projected)
        }
    }
    fun copy(attempt: TimeUpdateAttemptIdentity = this.attempt, intent: LocalEditIntent = this.intent,
        projection: ProjectionReceipt = this.projection, handoff: CapturedCreateHandoffFrame? = this.handoff) =
        TimeUpdateAcknowledgementReceipt(attempt, intent, projection, handoff)
    fun snapshot() = copy()
    fun matches(result: TimeUpdateVerifiedResult) = snapshot() == of(result, projection.nonce, handoff)
    override fun equals(other: Any?) = other is TimeUpdateAcknowledgementReceipt &&
        attempt == other.attempt && intent == other.intent && projection == other.projection && handoff == other.handoff
    override fun hashCode() = 31 * (31 * (31 * attempt.hashCode() + intent.hashCode()) + projection.hashCode()) + (handoff?.hashCode() ?: 0)
    override fun toString() = "TimeUpdateAcknowledgementReceipt(redacted)"
    companion object {
        fun of(result: TimeUpdateVerifiedResult, nonce: String, handoff: CapturedCreateHandoffFrame? = null): TimeUpdateAcknowledgementReceipt {
            val frozen = result.snapshot()
            val intent = frozen.record.preparation.intent
            return TimeUpdateAcknowledgementReceipt(frozen.attempt, intent,
                ProjectionReceipt(intent.scope, intent.format, frozen.event, nonce, intent.base), handoff)
        }
        private fun freezeHandoff(frame: CapturedCreateHandoffFrame): CapturedCreateHandoffFrame {
            require(supportsCapturedHandoff(frame.base) && supportsCapturedHandoff(frame.receipt.acknowledgement.baseline.change))
            fun freeze(value: RemoteChange.Upsert) = value.copy(content = value.content.copy(details = EventDetails()))
            val acknowledgement = frame.receipt.acknowledgement
            return frame.copy(receipt = frame.receipt.copy(acknowledgement = acknowledgement.copy(
                baseline = acknowledgement.baseline.copy(change = freeze(acknowledgement.baseline.change)))),
                base = freeze(frame.base), lastProjection = frame.lastProjection?.snapshot())
        }
    }
}

data class AppliedTimeUpdateAcknowledgement(val receipt: TimeUpdateAcknowledgementReceipt) {
    override fun toString() = "AppliedTimeUpdateAcknowledgement(redacted)"
}
enum class TimeUpdateAcknowledgementState { PENDING, RESTORED }
data class TimeUpdateAcknowledgementRecord(val receipt: TimeUpdateAcknowledgementReceipt, val state: TimeUpdateAcknowledgementState) {
    override fun toString() = "TimeUpdateAcknowledgementRecord(state=$state, redacted)"
}
data class TimeUpdateAcknowledgementReservation(val record: TimeUpdateAcknowledgementRecord, val fresh: Boolean) {
    override fun toString() = "TimeUpdateAcknowledgementReservation(redacted)"
}
