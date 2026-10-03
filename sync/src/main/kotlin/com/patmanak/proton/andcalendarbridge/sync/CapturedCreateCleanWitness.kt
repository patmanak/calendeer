// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Point-in-time assertion of the exact clean original, including its acknowledgement history.
 * This observation grants no future write, durable release, remote version or deletion authority.
 * A later projection must assert its own current reference and preserve intervening local changes.
 */
data class CapturedCreateCleanWitness(val receipt: CapturedCreateAcknowledgementReceipt) {
    override fun toString() = "CapturedCreateCleanWitness(REDACTED)"
}
