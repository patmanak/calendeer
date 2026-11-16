// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Raw native intention and actual remote result; only CRLF equivalence relates their text fields. */
class PersonalUpdateAcknowledgementReceipt private constructor(val attempt: PersonalUpdateAttemptIdentity, intent: LocalEditIntent,
    projection: ProjectionReceipt, handoff: CapturedCreateHandoffFrame?, val isSeries: Boolean) {
    constructor(attempt: PersonalUpdateAttemptIdentity, intent: LocalEditIntent,
        projection: ProjectionReceipt, handoff: CapturedCreateHandoffFrame? = null) : this(attempt, intent, projection, handoff, false)
    val intent = if (isSeries) SeriesEditCandidate(intent).intent else PersonalEditCandidate(intent).intent
    val projection = projection.snapshot()
    val handoff = handoff?.let { freezeHandoff(it, isSeries) }
    init {
        val original = this.intent
        val projected = this.projection
        require(projected.change.content.details == original.content.details && projected.change.content.colorRgb == original.content.colorRgb)
        if (isSeries) {
            val observed = SeriesEditProfile.freezeMaster(projected.change.content)
            require(observed.recurrence == original.content.recurrence)
            PersonalUpdateContentPolicy.freezeObservation(original.content.copy(recurrence = null), observed.copy(recurrence = null))
        } else {
            require(projected.change.content.recurrence == null)
            PersonalUpdateContentPolicy.freezeObservation(original.content, projected.change.content)
        }
        require(attempt.scope == original.scope && attempt.localIntentId == original.id &&
            attempt.eventId == original.base.eventId && attempt.baseRevision == original.base.revision)
        require(projected.scope == original.scope && projected.format == original.format && projected.previousBase == original.base &&
            projected.change.eventId == original.base.eventId && projected.change.revision != original.base.revision)
        require(projected.representation == null && projected.previousRepresentation == null)
        this.handoff?.let {
            require(it.scope == original.scope && it.format == original.format && it.base == original.base)
            CapturedHandoffProjection(it, projected)
        }
    }
    fun copy(attempt: PersonalUpdateAttemptIdentity = this.attempt, intent: LocalEditIntent = this.intent,
        projection: ProjectionReceipt = this.projection, handoff: CapturedCreateHandoffFrame? = this.handoff) =
        PersonalUpdateAcknowledgementReceipt(attempt, intent, projection, handoff, isSeries)
    fun snapshot() = copy()
    fun matches(result: PersonalUpdateVerifiedResult) = snapshot() == of(result, projection.nonce, handoff)
    override fun equals(other: Any?) = other is PersonalUpdateAcknowledgementReceipt &&
        isSeries == other.isSeries && attempt == other.attempt && intent == other.intent && projection == other.projection && handoff == other.handoff
    override fun hashCode() = 31 * (31 * (31 * attempt.hashCode() + intent.hashCode()) + projection.hashCode()) + (handoff?.hashCode() ?: 0)
    override fun toString() = "PersonalUpdateAcknowledgementReceipt(redacted)"
    companion object {
        fun forSeries(attempt: PersonalUpdateAttemptIdentity, intent: LocalEditIntent,
            projection: ProjectionReceipt, handoff: CapturedCreateHandoffFrame? = null) =
            PersonalUpdateAcknowledgementReceipt(attempt, intent, projection, handoff, true)
        fun of(result: PersonalUpdateVerifiedResult, nonce: String, handoff: CapturedCreateHandoffFrame? = null): PersonalUpdateAcknowledgementReceipt {
            val frozen = result.snapshot()
            val intent = frozen.record.preparation.intent
            return PersonalUpdateAcknowledgementReceipt(frozen.attempt, intent,
                ProjectionReceipt(intent.scope, intent.format, frozen.event, nonce, intent.base), handoff, frozen.record.preparation.isSeries)
        }
        private fun freezeHandoff(frame: CapturedCreateHandoffFrame, series: Boolean): CapturedCreateHandoffFrame {
            val origin = frame.receipt.acknowledgement.baseline
            if (series) require(origin.receipt.binding.identity.fingerprintVersion == 2 &&
                supportsCapturedHandoffOrigin(origin) && supportsCapturedHandoffContent(origin, frame.base))
            else require(supportsPersonalHandoff(frame.base) && supportsPersonalCapturedOrigin(origin))
            fun freeze(value: RemoteChange.Upsert) = value.copy(content = value.content.copy(details = value.content.details!!.let {
                it.copy(reminders = java.util.Collections.unmodifiableList(it.reminders.toList()),
                    attendees = java.util.Collections.unmodifiableList(it.attendees.toList()))
            }))
            val acknowledgement = frame.receipt.acknowledgement
            return frame.copy(receipt = frame.receipt.copy(acknowledgement = acknowledgement.copy(
                baseline = acknowledgement.baseline.copy(change = freeze(acknowledgement.baseline.change)))),
                base = freeze(frame.base), lastProjection = frame.lastProjection?.snapshot())
        }
    }
}

data class AppliedPersonalUpdateAcknowledgement(val receipt: PersonalUpdateAcknowledgementReceipt) {
    override fun toString() = "AppliedPersonalUpdateAcknowledgement(redacted)"
}
enum class PersonalUpdateAcknowledgementState { PENDING, RESTORED }
data class PersonalUpdateAcknowledgementRecord(val receipt: PersonalUpdateAcknowledgementReceipt, val state: PersonalUpdateAcknowledgementState) {
    override fun toString() = "PersonalUpdateAcknowledgementRecord(state=$state, redacted)"
}
data class PersonalUpdateAcknowledgementReservation(val record: PersonalUpdateAcknowledgementRecord, val fresh: Boolean) {
    override fun toString() = "PersonalUpdateAcknowledgementReservation(redacted)"
}
