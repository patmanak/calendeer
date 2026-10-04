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
        require(supportsCapturedHandoff(acknowledgement.baseline.change))
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
data class CapturedCreateHandoffFrame(
    val receipt: CapturedCreateHandoffReceipt,
    val base: RemoteChange.Upsert,
    val lastProjection: ProjectionReceipt? = null,
) {
    init {
        require(base.eventId == receipt.eventId && supportsCapturedHandoff(base))
        if (lastProjection == null) require(base == receipt.acknowledgement.baseline.change)
        else {
            require(lastProjection.representation == null && lastProjection.previousRepresentation == null)
            require(lastProjection.scope == receipt.scope && lastProjection.format == receipt.format && lastProjection.change == base)
            require(lastProjection.previousBase?.let(::supportsCapturedHandoff) == true)
            require(lastProjection.nonce !in listOf(receipt.nonce, receipt.acknowledgement.nonce,
                receipt.acknowledgement.baseline.receipt.nonce))
        }
    }
    val scope: CalendarScope get() = receipt.scope
    val format: AdapterFormat get() = receipt.format
    val eventId: EventId get() = receipt.eventId
    override fun toString() = "CapturedCreateHandoffFrame(REDACTED)"
}

enum class CapturedCreateHandoffState { PENDING, RELEASED }
data class CapturedCreateHandoffRecord(val frame: CapturedCreateHandoffFrame,
    val state: CapturedCreateHandoffState, val waiting: Boolean = false) {
    init { if (state == CapturedCreateHandoffState.PENDING) require(!waiting && frame.lastProjection == null) }
    override fun toString() = "CapturedCreateHandoffRecord(REDACTED)"
}

/** Conditional incoming projection; never admission to ordinary projection or insertion. */
data class CapturedHandoffProjection(val frame: CapturedCreateHandoffFrame, val receipt: ProjectionReceipt) {
    init {
        require(receipt.representation == null && receipt.previousRepresentation == null)
        require(receipt.scope == frame.scope && receipt.format == frame.format && receipt.previousBase == frame.base)
        require(receipt.change.eventId == frame.eventId && supportsCapturedHandoff(receipt.change))
        require(receipt.nonce !in listOf(frame.receipt.nonce, frame.receipt.acknowledgement.nonce,
            frame.receipt.acknowledgement.baseline.receipt.nonce, frame.lastProjection?.nonce))
    }
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

data class CapturedHandoffCurrent(val frame: CapturedCreateHandoffFrame, val content: EventContent, val deleted: Boolean) {
    init { CapturedCreateCurrent(frame.receipt.acknowledgement.baseline, content, deleted) }
    val unchanged: Boolean get() = !deleted && content == frame.base.content
    override fun toString() = "CapturedHandoffCurrent(REDACTED)"
}

sealed interface CapturedHandoffCurrentObservation {
    data class Supported(val snapshot: CapturedHandoffCurrent) : CapturedHandoffCurrentObservation {
        override fun toString() = "CapturedHandoffCurrentObservation.Supported(REDACTED)"
    }
    data object Unsupported : CapturedHandoffCurrentObservation
    data object Unresolved : CapturedHandoffCurrentObservation
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
