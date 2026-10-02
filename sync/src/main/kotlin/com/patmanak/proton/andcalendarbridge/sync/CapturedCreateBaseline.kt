// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** First strictly verified sent content behind a qualified attachment. Not current local content,
 * a Provider clean acknowledgement, or permission to release incoming projection.
 */
data class CapturedCreateBaseline(val receipt: CapturedCreateAttachmentReceipt, val change: RemoteChange.Upsert) {
    init {
        require(change.eventId == receipt.binding.eventId && change.revision == receipt.binding.verifiedRevision)
        require(receipt.binding.identity.matchesContent(change.content)) { "Baseline differs from the frozen capture" }
    }
    val scope: CalendarScope get() = receipt.binding.identity.attempt.scope
    val format: AdapterFormat get() = receipt.binding.identity.captureFormat
    override fun toString() = "CapturedCreateBaseline(REDACTED)"
}
