// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Prepared before a metadata-only original-row attachment. Never grants import release or ACK. */
data class CapturedCreateAttachmentReceipt(val binding: CapturedCreateBinding, val nonce: String, val protocol: Int = 1) {
    init {
        require(protocol == 1)
        require(nonce.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
    }
    override fun toString() = "CapturedCreateAttachmentReceipt(REDACTED)"
}

/** Historical metadata-write proof, not proof of current content or a clean Android row. */
data class AppliedCapturedCreateAttachment(val receipt: CapturedCreateAttachmentReceipt) {
    override fun toString() = "AppliedCapturedCreateAttachment(REDACTED)"
}
