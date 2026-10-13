// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateHandoffFrame

/** Fixture relation only. Both the pre-editor frame and the full historical ACK stay immutable. */
internal object TransferredTitleFrame {
    fun matchesPreEditorFrame(expected: CapturedCreateHandoffFrame, historicalAck: CapturedCreateHandoffFrame?): Boolean {
        if (historicalAck == null) return false
        if (historicalAck == expected) return true
        if (historicalAck.receipt != expected.receipt || historicalAck.base != expected.base ||
            historicalAck.scope != expected.scope || historicalAck.format != expected.format) return false
        val last = historicalAck.lastProjection ?: return false
        return last.scope == expected.scope && last.format == expected.format && last.protocol == 1 &&
            last.nonce.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) &&
            last.change == expected.base && last.previousBase == expected.base &&
            last.representation == null && last.previousRepresentation == null
    }
}
