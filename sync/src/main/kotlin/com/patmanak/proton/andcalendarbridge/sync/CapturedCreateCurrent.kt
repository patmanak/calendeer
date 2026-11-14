// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Supported current local content behind an exact attachment and sent reference.
 * Point-in-time evidence only: never authority to clean Android or release projection later.
 */
class CapturedCreateCurrent(val baseline: CapturedCreateBaseline, content: EventContent, val deleted: Boolean,
    val acknowledgement: CapturedCreateAcknowledgementReceipt? = null) {
    val content = if (baseline.receipt.binding.identity.fingerprintVersion == 2) freezeCapturedSeriesContent(content).also {
        require(it.recurrence!!.uid == baseline.change.content.recurrence!!.uid) { "Current series UID differs from its baseline" }
    } else freezeCapturedCreateContent(content)
    init {
        require(acknowledgement == null || acknowledgement.baseline == baseline)
        // Preserve the original pre-handoff profile for historical known-empty captures.
        if (supportsCapturedHandoff(baseline.change)) require(this.content.details == EventDetails() && this.content.colorRgb == null)
        val time = content.time
        if (time is EventTime.Timed) require(time.start.nano == 0 && time.end.nano == 0)
        try {
            for (text in listOf(content.title, content.description, content.location))
                Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        } catch (_: CharacterCodingException) { throw IllegalArgumentException("Invalid current captured text") }
    }
    val unchanged: Boolean get() = !deleted && content == baseline.change.content
    fun copy(baseline: CapturedCreateBaseline = this.baseline, content: EventContent = this.content,
        deleted: Boolean = this.deleted, acknowledgement: CapturedCreateAcknowledgementReceipt? = this.acknowledgement) =
        CapturedCreateCurrent(baseline, content, deleted, acknowledgement)
    operator fun component1() = baseline
    operator fun component2() = content
    operator fun component3() = deleted
    operator fun component4() = acknowledgement
    override fun equals(other: Any?) = other is CapturedCreateCurrent && baseline == other.baseline && content == other.content &&
        deleted == other.deleted && acknowledgement == other.acknowledgement
    override fun hashCode() = 31 * (31 * (31 * baseline.hashCode() + content.hashCode()) + deleted.hashCode()) +
        (acknowledgement?.hashCode() ?: 0)
    override fun toString() = "CapturedCreateCurrent(REDACTED)"
}

/** Unsupported data remains intact. Missing or ambiguous identity cannot establish deletion. */
sealed interface CapturedCreateCurrentObservation {
    data class Supported(val snapshot: CapturedCreateCurrent) : CapturedCreateCurrentObservation {
        override fun toString() = "CapturedCreateCurrentObservation.Supported(REDACTED)"
    }
    data object Unsupported : CapturedCreateCurrentObservation
    data object Unresolved : CapturedCreateCurrentObservation
}
