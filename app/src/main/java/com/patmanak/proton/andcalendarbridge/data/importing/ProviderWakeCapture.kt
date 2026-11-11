// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.ProviderDirtyFingerprint

/** A signal is consumed only after bounded capture and a durable continuation/network handoff. */
internal suspend fun captureProviderWake(
    pending: String,
    previousFingerprint: String?,
    continuedFingerprint: String?,
    readFingerprint: suspend () -> ProviderDirtyFingerprint,
    capture: suspend () -> ImportRunResult,
    continueLocal: suspend (String, String?) -> Unit,
    enqueueNetwork: suspend () -> Unit,
    acknowledge: suspend (String, String?) -> Unit,
    continued: Boolean = false,
): ImportRunResult {
    val fingerprint = if (continued) continuedFingerprint else readFingerprint().let {
        if (it.dirtyRows == 0 || (it.hash != null && it.hash == previousFingerprint)) {
            acknowledge(pending, it.hash)
            return ImportRunResult.COMPLETE
        }
        it.hash
    }
    return when (capture()) {
        ImportRunResult.RETRY -> ImportRunResult.RETRY
        ImportRunResult.CONTINUE -> {
            continueLocal(pending, fingerprint)
            ImportRunResult.COMPLETE
        }
        ImportRunResult.COMPLETE -> {
            enqueueNetwork()
            acknowledge(pending, fingerprint)
            ImportRunResult.COMPLETE
        }
    }
}
