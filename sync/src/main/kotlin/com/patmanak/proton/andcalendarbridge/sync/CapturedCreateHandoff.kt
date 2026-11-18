// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.security.MessageDigest

/** Durable transfer admission. Original creation evidence remains immutable after transfer. */
data class CapturedCreateHandoffReceipt(
    val acknowledgement: CapturedCreateAcknowledgementReceipt,
    val nonce: String,
    val protocol: Int = 1,
) {
    init {
        require(protocol == 1 && nonce.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
        require(nonce != acknowledgement.nonce && nonce != acknowledgement.baseline.receipt.nonce)
        require(supportsCapturedHandoffOrigin(acknowledgement.baseline))
    }
    val scope: CalendarScope get() = acknowledgement.baseline.scope
    val format: AdapterFormat get() = acknowledgement.baseline.format
    val eventId: EventId get() = acknowledgement.baseline.change.eventId
    fun fingerprint(): String = MessageDigest.getInstance("SHA-256")
        .digest("captured-handoff-v1:${acknowledgement.fingerprint()}:$nonce".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    override fun toString() = "CapturedCreateHandoffReceipt(REDACTED)"
}

/** Current projection reference is separate from immutable original creation evidence. */
class CapturedCreateHandoffFrame(
    receipt: CapturedCreateHandoffReceipt,
    base: RemoteChange.Upsert,
    lastProjection: ProjectionReceipt? = null,
) {
    val receipt: CapturedCreateHandoffReceipt = receipt.let {
        val acknowledgement = it.acknowledgement
        val baseline = acknowledgement.baseline
        require(supportsCapturedHandoffOrigin(baseline))
        it.copy(acknowledgement = acknowledgement.copy(baseline = baseline.copy(
            change = baseline.change.copy(content = freezeHandoffContent(baseline.change.content)))))
    }
    val base: RemoteChange.Upsert = base.copy(content = freezeHandoffContent(base.content))
    val lastProjection: ProjectionReceipt? = lastProjection?.snapshot()
    init {
        require(this.base.eventId == this.receipt.eventId && supportsCapturedHandoffContent(this.receipt.acknowledgement.baseline, this.base))
        val last = this.lastProjection
        if (last == null) require(this.base == this.receipt.acknowledgement.baseline.change)
        else {
            require(last.representation == null && last.previousRepresentation == null)
            require(last.scope == this.receipt.scope && last.format == this.receipt.format && last.change == this.base)
            require(last.previousBase?.let { supportsCapturedHandoffContent(this.receipt.acknowledgement.baseline, it) } == true)
            require(last.nonce !in listOf(this.receipt.nonce, this.receipt.acknowledgement.nonce,
                this.receipt.acknowledgement.baseline.receipt.nonce))
        }
    }
    val scope: CalendarScope get() = receipt.scope
    val format: AdapterFormat get() = receipt.format
    val eventId: EventId get() = receipt.eventId
    fun copy(receipt: CapturedCreateHandoffReceipt = this.receipt, base: RemoteChange.Upsert = this.base,
        lastProjection: ProjectionReceipt? = this.lastProjection) = CapturedCreateHandoffFrame(receipt, base, lastProjection)
    operator fun component1() = receipt
    operator fun component2() = base
    operator fun component3() = lastProjection
    override fun equals(other: Any?) = other is CapturedCreateHandoffFrame &&
        receipt == other.receipt && base == other.base && lastProjection == other.lastProjection
    override fun hashCode() = 31 * (31 * receipt.hashCode() + base.hashCode()) + (lastProjection?.hashCode() ?: 0)
    override fun toString() = "CapturedCreateHandoffFrame(REDACTED)"
}

enum class CapturedCreateHandoffState { PENDING, RELEASED }
data class CapturedCreateHandoffRecord(val frame: CapturedCreateHandoffFrame,
    val state: CapturedCreateHandoffState, val waiting: Boolean = false) {
    init { if (state == CapturedCreateHandoffState.PENDING) require(!waiting && frame.lastProjection == null) }
    override fun toString() = "CapturedCreateHandoffRecord(REDACTED)"
}

/** Conditional incoming projection; never admission to ordinary projection or insertion. */
class CapturedHandoffProjection(val frame: CapturedCreateHandoffFrame, receipt: ProjectionReceipt) {
    val receipt: ProjectionReceipt = receipt.snapshot()
    init {
        require(this.receipt.representation == null && this.receipt.previousRepresentation == null)
        require(this.receipt.scope == frame.scope && this.receipt.format == frame.format && this.receipt.previousBase == frame.base)
        require(this.receipt.change.eventId == frame.eventId && supportsCapturedHandoffContent(frame.receipt.acknowledgement.baseline, this.receipt.change))
        require(this.receipt.nonce !in listOf(frame.receipt.nonce, frame.receipt.acknowledgement.nonce,
            frame.receipt.acknowledgement.baseline.receipt.nonce, frame.lastProjection?.nonce))
    }
    fun copy(frame: CapturedCreateHandoffFrame = this.frame, receipt: ProjectionReceipt = this.receipt) =
        CapturedHandoffProjection(frame, receipt)
    operator fun component1() = frame
    operator fun component2() = receipt
    override fun equals(other: Any?) = other is CapturedHandoffProjection && frame == other.frame && receipt == other.receipt
    override fun hashCode() = 31 * frame.hashCode() + receipt.hashCode()
    override fun toString() = "CapturedHandoffProjection(REDACTED)"
}

/** Historical atomic write proof, independent of subsequent local edits. */
data class AppliedCapturedHandoffProjection(val plan: CapturedHandoffProjection) {
    override fun toString() = "AppliedCapturedHandoffProjection(REDACTED)"
}

/** Point-in-time observation only. Every subsequent projection must reassert this reference. */
data class CapturedHandoffCleanWitness(val frame: CapturedCreateHandoffFrame) {
    override fun toString() = "CapturedHandoffCleanWitness(REDACTED)"
}

class CapturedHandoffCurrent(val frame: CapturedCreateHandoffFrame, content: EventContent, val deleted: Boolean) {
    val content: EventContent = freezeHandoffContent(content)
    init { require(supportsCapturedHandoffContent(frame.receipt.acknowledgement.baseline, frame.base.copy(content = this.content))) }
    val unchanged: Boolean get() = !deleted && content == frame.base.content
    fun copy(frame: CapturedCreateHandoffFrame = this.frame, content: EventContent = this.content,
        deleted: Boolean = this.deleted) = CapturedHandoffCurrent(frame, content, deleted)
    operator fun component1() = frame
    operator fun component2() = content
    operator fun component3() = deleted
    override fun equals(other: Any?) = other is CapturedHandoffCurrent &&
        frame == other.frame && content == other.content && deleted == other.deleted
    override fun hashCode() = 31 * (31 * frame.hashCode() + content.hashCode()) + deleted.hashCode()
    override fun toString() = "CapturedHandoffCurrent(REDACTED)"
}

sealed interface CapturedHandoffCurrentObservation {
    data class Supported(val snapshot: CapturedHandoffCurrent) : CapturedHandoffCurrentObservation {
        override fun toString() = "CapturedHandoffCurrentObservation.Supported(REDACTED)"
    }
    data object Unsupported : CapturedHandoffCurrentObservation
    data object Unresolved : CapturedHandoffCurrentObservation
}

private fun freezeHandoffContent(content: EventContent): EventContent {
    val details = requireNotNull(content.details)
    return content.copy(details = details.copy(
        reminders = java.util.Collections.unmodifiableList(details.reminders.toList()),
        attendees = java.util.Collections.unmodifiableList(details.attendees.toList())),
        recurrence = content.recurrence?.let { it.copy(
            additionalDates = java.util.Collections.unmodifiableList(it.additionalDates.toList()),
            excludedDates = java.util.Collections.unmodifiableList(it.excludedDates.toList())) })
}

/** Transfer capability only. It never widens the simple personal-update submission profile. */
fun supportsCapturedHandoffOrigin(baseline: CapturedCreateBaseline): Boolean =
    if (baseline.receipt.binding.identity.fingerprintVersion == 1) supportsPersonalCapturedOrigin(baseline)
    else if (baseline.occurrenceIntent != null) baseline.receipt.binding.identity.matchesOccurrence(baseline.occurrenceIntent) &&
        supportsCapturedHandoffContent(baseline, baseline.change)
    else supportsCapturedHandoffContent(baseline, baseline.change) && baseline.receipt.binding.identity.matchesContent(baseline.change.content)

fun supportsCapturedHandoffContent(origin: CapturedCreateBaseline, change: RemoteChange): Boolean {
    if (origin.receipt.binding.identity.fingerprintVersion == 1) return supportsPersonalHandoff(change)
    val value = (change as? RemoteChange.Upsert)?.content ?: return false
    if (change.eventId != origin.change.eventId ||
        !supportsPersonalHandoff(change.copy(content = value.copy(recurrence = null)))) return false
    val occurrence = origin.occurrenceIntent?.occurrence
    if (occurrence != null) return try {
        value.recurrence == occurrence.content.recurrence && ProjectionReceipt.supports(change) &&
            OccurrenceEditCandidate(occurrence.master, occurrence.original, value.copy(recurrence = null)).content == value
    } catch (_: IllegalArgumentException) { false }
    return try {
        capturedCreateSeries(value)
        value.recurrence!!.uid == origin.change.content.recurrence!!.uid && ProjectionReceipt.supports(change)
    } catch (_: IllegalArgumentException) { false }
}

/** Original personal creation admission requires the complete frozen dispatch identity. */
fun supportsPersonalCapturedOrigin(baseline: CapturedCreateBaseline): Boolean =
    supportsPersonalHandoff(baseline.change) && baseline.receipt.binding.identity.matchesContent(baseline.change.content)

/** Current content after transfer; historical admission remains the strict profile below. */
fun supportsPersonalHandoff(change: RemoteChange): Boolean {
    val value = (change as? RemoteChange.Upsert)?.content ?: return false
    val details = value.details ?: return false
    if (details.organizer != null || details.attendees.isNotEmpty() || value.recurrence != null ||
        value.colorRgb?.let { it !in 0..0xffffff } == true || !ProjectionReceipt.supports(change)) return false
    return try {
        for (text in listOf(value.title, value.description, value.location))
            Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
        true
    } catch (_: java.nio.charset.CharacterCodingException) { false }
}

/** Deliberately bounded to known-empty simple content; unknown details are never discarded. */
fun supportsCapturedHandoff(change: RemoteChange): Boolean {
    val value = (change as? RemoteChange.Upsert)?.content ?: return false
    return try {
        // Never substitute unknown details or discard richer fields to admit a transfer.
        if (value.details != EventDetails() || value.recurrence != null || value.colorRgb != null) return false
        if (value.time is EventTime.Timed && (value.time.start.nano != 0 || value.time.end.nano != 0)) return false
        for (text in listOf(value.title, value.description, value.location))
            Charsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
        true
    } catch (_: java.nio.charset.CharacterCodingException) { false }
}
