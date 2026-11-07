// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.sync.AppliedPersonalUpdateAcknowledgement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A retained receipt is history, not permission to replay a write. The separately
 * advertised recovery capability inspects again and uses an exact native CAS.
 * Unknown capability versions remain inspection-only. No transport enters here.
 */
internal suspend fun recoverPersonalAcknowledgement(
    fresh: Boolean,
    recoveryProtocol: Int?,
    inspect: suspend () -> AppliedPersonalUpdateAcknowledgement?,
    qualifyCurrentOwner: suspend () -> Boolean,
    applyFresh: suspend () -> AppliedPersonalUpdateAcknowledgement?,
    resume: suspend () -> AppliedPersonalUpdateAcknowledgement?,
): AppliedPersonalUpdateAcknowledgement? {
    suspend fun <T> active(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val value = try { action() } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            throw failure
        }
        currentCoroutineContext().ensureActive()
        return value
    }
    if (fresh) return active(applyFresh)
    active(inspect)?.let { return it }
    if (recoveryProtocol != 1 || !active(qualifyCurrentOwner)) return null
    return active(resume)
}
