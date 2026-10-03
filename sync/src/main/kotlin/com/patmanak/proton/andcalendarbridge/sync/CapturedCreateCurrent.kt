// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Supported current local content behind an exact attachment and sent reference.
 * Point-in-time evidence only: never authority to clean Android or release projection later.
 */
data class CapturedCreateCurrent(val baseline: CapturedCreateBaseline, val content: EventContent, val deleted: Boolean,
    val acknowledgement: CapturedCreateAcknowledgementReceipt? = null) {
    init {
        require(acknowledgement == null || acknowledgement.baseline == baseline)
        require(content.details == EventDetails() && content.recurrence == null && content.colorRgb == null)
        val time = content.time
        if (time is EventTime.Timed) require(time.start.nano == 0 && time.end.nano == 0)
        try {
            for (text in listOf(content.title, content.description, content.location))
                Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        } catch (_: CharacterCodingException) { throw IllegalArgumentException("Invalid current captured text") }
    }
    val unchanged: Boolean get() = !deleted && content == baseline.change.content
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
