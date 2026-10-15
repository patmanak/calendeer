// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Raw native intention and actual remote result; only CRLF equivalence relates their text fields. */
class TextUpdateAcknowledgementReceipt(val attempt: TextUpdateAttemptIdentity, intent: LocalEditIntent,
    projection: ProjectionReceipt, handoff: CapturedCreateHandoffFrame? = null) {
    val intent = LocalTextEditPreparation.freezeIntent(intent)
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
            projected.change.eventId == original.base.eventId && projected.change.revision != original.base.revision &&
            TextUpdateContentPolicy.sameSubmittedContent(projected.change.content, original.content))
        require(projected.representation == null && projected.previousRepresentation == null)
        this.handoff?.let {
            require(it.scope == original.scope && it.format == original.format && it.base == original.base)
            CapturedHandoffProjection(it, projected)
        }
    }
    fun copy(attempt: TextUpdateAttemptIdentity = this.attempt, intent: LocalEditIntent = this.intent,
        projection: ProjectionReceipt = this.projection, handoff: CapturedCreateHandoffFrame? = this.handoff) =
        TextUpdateAcknowledgementReceipt(attempt, intent, projection, handoff)
    fun snapshot() = copy()
    fun matches(result: TextUpdateVerifiedResult) = snapshot() == of(result, projection.nonce, handoff)
    override fun equals(other: Any?) = other is TextUpdateAcknowledgementReceipt &&
        attempt == other.attempt && intent == other.intent && projection == other.projection && handoff == other.handoff
    override fun hashCode() = 31 * (31 * (31 * attempt.hashCode() + intent.hashCode()) + projection.hashCode()) + (handoff?.hashCode() ?: 0)
    override fun toString() = "TextUpdateAcknowledgementReceipt(redacted)"
    companion object {
        fun of(result: TextUpdateVerifiedResult, nonce: String, handoff: CapturedCreateHandoffFrame? = null): TextUpdateAcknowledgementReceipt {
            val frozen = result.snapshot()
            val intent = frozen.record.preparation.intent
            return TextUpdateAcknowledgementReceipt(frozen.attempt, intent,
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

data class AppliedTextUpdateAcknowledgement(val receipt: TextUpdateAcknowledgementReceipt) {
    override fun toString() = "AppliedTextUpdateAcknowledgement(redacted)"
}
enum class TextUpdateAcknowledgementState { PENDING, RESTORED }
data class TextUpdateAcknowledgementRecord(val receipt: TextUpdateAcknowledgementReceipt, val state: TextUpdateAcknowledgementState) {
    override fun toString() = "TextUpdateAcknowledgementRecord(state=$state, redacted)"
}
data class TextUpdateAcknowledgementReservation(val record: TextUpdateAcknowledgementRecord, val fresh: Boolean) {
    override fun toString() = "TextUpdateAcknowledgementReservation(redacted)"
}
