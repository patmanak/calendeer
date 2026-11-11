// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** A revoked operation lease must not permanently terminate the application-owned observer.
 * Real cancellation of the collecting coroutine still propagates.
 */
internal suspend fun observeCurrentCalendarSignal(block: suspend () -> Unit) {
    try { block() }
    catch (_: CancellationException) { currentCoroutineContext().ensureActive() }
}
