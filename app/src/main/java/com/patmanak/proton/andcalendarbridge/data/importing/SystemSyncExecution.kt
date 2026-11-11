// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

internal enum class SystemSyncOutcome { SUCCESS, RETRY, AUTH, PERMISSION, STALE, DISABLED }

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
