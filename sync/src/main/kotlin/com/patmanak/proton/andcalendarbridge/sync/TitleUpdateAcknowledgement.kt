// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Metadata-only acknowledgement of an imported or transferred CREATE event. The caller must retain the
 * verified result and reserve this exact receipt durably before its one fresh native write.
 * CREATE provenance must be supplied explicitly; no dispatch or current access is implied.
 */
data class TitleUpdateAcknowledgementReceipt(
    val attempt: TitleUpdateAttemptIdentity,
    val intent: LocalEditIntent,
    val projection: ProjectionReceipt,
    val handoff: CapturedCreateHandoffFrame? = null,
) {
    init {
        LocalEditPreparation.freezeIntent(intent)
        require(intent.base.content.details == EventDetails() && intent.base.content.colorRgb == null)
        require(attempt.scope == intent.scope && attempt.localIntentId == intent.id &&
            attempt.eventId == intent.base.eventId && attempt.baseRevision == intent.base.revision)
        require(projection.scope == intent.scope && projection.format == intent.format &&
            projection.previousBase == intent.base && projection.change.eventId == intent.base.eventId &&
            projection.change.content == intent.content && projection.change.revision != intent.base.revision)
        require(projection.representation == null && projection.previousRepresentation == null)
        handoff?.let {
            require(it.scope == intent.scope && it.format == intent.format && it.base == intent.base)
            CapturedHandoffProjection(it, projection)
        }
    }
    fun snapshot(): TitleUpdateAcknowledgementReceipt {
        val frozenHandoff = handoff?.let { frame ->
            // Every admitted handoff has known-empty details. Own both the immutable
            // creation baseline and the current reference before suspension or storage.
            require(supportsCapturedHandoff(frame.receipt.acknowledgement.baseline.change) &&
                supportsCapturedHandoff(frame.base))
            fun freeze(value: RemoteChange.Upsert) = value.copy(content = value.content.copy(details = EventDetails()))
            val acknowledgement = frame.receipt.acknowledgement
            frame.copy(receipt = frame.receipt.copy(acknowledgement = acknowledgement.copy(
                baseline = acknowledgement.baseline.copy(change = freeze(acknowledgement.baseline.change)))),
                base = freeze(frame.base), lastProjection = frame.lastProjection?.snapshot())
        }
        return copy(intent = LocalEditPreparation.freezeIntent(intent), projection = projection.snapshot(), handoff = frozenHandoff)
    }
    fun matches(result: TitleUpdateVerifiedResult) = snapshot() == of(result, projection.nonce, handoff)
    override fun toString() = "TitleUpdateAcknowledgementReceipt(redacted)"
    companion object {
        fun of(result: TitleUpdateVerifiedResult, nonce: String,
            handoff: CapturedCreateHandoffFrame? = null): TitleUpdateAcknowledgementReceipt {
            val intent = LocalEditPreparation.freezeIntent(result.record.preparation.intent)
            return TitleUpdateAcknowledgementReceipt(result.attempt, intent,
                ProjectionReceipt(intent.scope, intent.format, result.event, nonce, intent.base), handoff).snapshot()
        }
    }
}

/** Historical successful atomic metadata write or qualified marker inspection, never preparation. */
data class AppliedTitleUpdateAcknowledgement(val receipt: TitleUpdateAcknowledgementReceipt) {
    override fun toString() = "AppliedTitleUpdateAcknowledgement(redacted)"
}

enum class TitleUpdateAcknowledgementState { PENDING, RESTORED }
data class TitleUpdateAcknowledgementRecord(val receipt: TitleUpdateAcknowledgementReceipt,
    val state: TitleUpdateAcknowledgementState) {
    override fun toString() = "TitleUpdateAcknowledgementRecord(state=$state, redacted)"
}
/** Only a fresh, committed reservation may attempt the native write. Existing receipts inspect only. */
data class TitleUpdateAcknowledgementReservation(val record: TitleUpdateAcknowledgementRecord, val fresh: Boolean)
