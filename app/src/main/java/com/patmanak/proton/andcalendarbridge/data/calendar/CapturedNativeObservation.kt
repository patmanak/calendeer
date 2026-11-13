// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import com.patmanak.proton.andcalendarbridge.sync.*

/** Internal native observation; initial CREATE and transferred personal content validate separately. */
internal sealed interface CapturedNativeObservation {
    data class Snapshot(val content: EventContent, val deleted: Boolean) {
        override fun toString() = "CapturedNativeSnapshot(REDACTED)"
    }
    data class Supported(val snapshot: Snapshot) : CapturedNativeObservation {
        override fun toString() = "CapturedNativeObservation.Supported(REDACTED)"
    }
    data object Unsupported : CapturedNativeObservation
    data object Unresolved : CapturedNativeObservation

    fun forCreate(base: CapturedCreateBaseline, ack: CapturedCreateAcknowledgementReceipt? = null): CapturedCreateCurrentObservation = when (this) {
        is Supported -> CapturedCreateCurrentObservation.Supported(CapturedCreateCurrent(base, snapshot.content, snapshot.deleted, ack))
        Unsupported -> CapturedCreateCurrentObservation.Unsupported
        Unresolved -> CapturedCreateCurrentObservation.Unresolved
    }
}
