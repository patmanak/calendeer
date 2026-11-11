// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SystemSyncExecutionTest {
    @Test fun androidSuccessRequiresDrainingAllPages() = runBlocking {
        var remaining = 2
        assertEquals(SystemSyncOutcome.SUCCESS, executeSystemSync({ null }, {
            if (--remaining > 0) ImportRunResult.CONTINUE else ImportRunResult.COMPLETE
        }))
        assertEquals("An enqueue acknowledgement is not a completed sync", 0, remaining)
    }

    @Test fun pageBudgetDoesNotReportSuccess() = runBlocking {
        assertEquals(SystemSyncOutcome.RETRY,
            executeSystemSync({ null }, { ImportRunResult.CONTINUE }, maxPasses = 2))
    }

    @Test fun revokedAccountStopsBeforeNextPage() = runBlocking {
        var passes = 0
        assertEquals(SystemSyncOutcome.STALE, executeSystemSync(
            { if (passes > 0) SystemSyncOutcome.STALE else null },
            { passes++; ImportRunResult.CONTINUE }))
        assertEquals(1, passes)
    }

    @Test fun revocationAfterLastPageCannotBecomeSuccess() = runBlocking {
        var done = false
        assertEquals(SystemSyncOutcome.DISABLED, executeSystemSync(
            { if (done) SystemSyncOutcome.DISABLED else null },
            { done = true; ImportRunResult.COMPLETE }))
    }

    @Test fun retryHasNoContinuationOrSuccess() = runBlocking {
        assertEquals(SystemSyncOutcome.RETRY, executeSystemSync({ null }, { ImportRunResult.RETRY }))
    }

    @Test fun cancellationPropagates() = runBlocking {
        try {
            executeSystemSync({ null }, { throw kotlinx.coroutines.CancellationException() })
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }

    @Test fun incomingRefreshIsFreshOnlyOnFirstPage() = runBlocking {
        val flags = mutableListOf<Boolean>()
        executeSystemSync({ null }, { first ->
            flags += first
            if (flags.size == 2) ImportRunResult.COMPLETE else ImportRunResult.CONTINUE
        })
        assertEquals(listOf(true, false), flags)
    }
}
