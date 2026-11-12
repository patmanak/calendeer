// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

internal enum class SystemSyncOutcome { SUCCESS, RETRY, AUTH, PERMISSION, STALE, DISABLED }

/** Only unsolicited Android push requires the native automatic-sync switches. */
internal fun requiresPushSettings(manual: Boolean, pull: Boolean): Boolean = !manual && !pull

/** Periodic pull cannot send a dirty local edit when native push is disabled. */
internal suspend fun executeCalendarPass(pullOnly: Boolean, incoming: suspend () -> ImportRunResult,
    outgoing: suspend () -> ImportRunResult): ImportRunResult {
    val sent = if (pullOnly) ImportRunResult.COMPLETE else outgoing()
    val received = incoming()
    return when {
        received == ImportRunResult.RETRY || sent == ImportRunResult.RETRY -> ImportRunResult.RETRY
        received == ImportRunResult.CONTINUE || sent == ImportRunResult.CONTINUE -> ImportRunResult.CONTINUE
        else -> ImportRunResult.COMPLETE
    }
}

/** A failed/interrupted wait is not authority to cancel the independent executor. */
internal fun relaySystemSync(enqueue: () -> Unit, await: () -> SystemSyncOutcome): SystemSyncOutcome = try {
    enqueue()
    await()
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    SystemSyncOutcome.RETRY
} catch (_: Exception) {
    SystemSyncOutcome.RETRY
}

/** A system run owns all its pages, rather than acknowledging a hidden successor. */
internal suspend fun executeSystemSync(
    admit: suspend () -> SystemSyncOutcome?,
    pass: suspend (first: Boolean) -> ImportRunResult,
    maxPasses: Int = 16,
): SystemSyncOutcome {
    require(maxPasses > 0)
    repeat(maxPasses) { index ->
        admit()?.let { return it }
        when (pass(index == 0)) {
            ImportRunResult.COMPLETE -> return admit() ?: SystemSyncOutcome.SUCCESS
            ImportRunResult.RETRY -> return SystemSyncOutcome.RETRY
            ImportRunResult.CONTINUE -> Unit
        }
    }
    return SystemSyncOutcome.RETRY
}
