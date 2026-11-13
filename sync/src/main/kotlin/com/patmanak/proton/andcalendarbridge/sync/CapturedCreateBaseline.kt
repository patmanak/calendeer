// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** First strictly verified sent content behind a qualified attachment. Not current local content,
 * a Provider clean acknowledgement, or permission to release incoming projection.
 */
class CapturedCreateBaseline(val receipt: CapturedCreateAttachmentReceipt, change: RemoteChange.Upsert) {
    val change = change.copy(content = freezeCapturedCreateContent(change.content))
    init {
        require(this.change.eventId == receipt.binding.eventId && this.change.revision == receipt.binding.verifiedRevision)
        require(receipt.binding.identity.matchesContent(this.change.content)) { "Baseline differs from the frozen capture" }
    }
    val scope: CalendarScope get() = receipt.binding.identity.attempt.scope
    val format: AdapterFormat get() = receipt.binding.identity.captureFormat
    fun copy(receipt: CapturedCreateAttachmentReceipt = this.receipt, change: RemoteChange.Upsert = this.change) =
        CapturedCreateBaseline(receipt, change)
    operator fun component1() = receipt
    operator fun component2() = change
    override fun equals(other: Any?) = other is CapturedCreateBaseline && receipt == other.receipt && change == other.change
    override fun hashCode() = 31 * receipt.hashCode() + change.hashCode()
    override fun toString() = "CapturedCreateBaseline(REDACTED)"
}
