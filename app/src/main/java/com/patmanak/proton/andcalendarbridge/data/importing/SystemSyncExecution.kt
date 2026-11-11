// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

internal enum class SystemSyncOutcome { SUCCESS, RETRY, AUTH, PERMISSION, STALE, DISABLED }

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
