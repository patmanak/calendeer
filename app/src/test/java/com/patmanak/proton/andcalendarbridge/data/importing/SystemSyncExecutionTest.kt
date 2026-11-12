// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SystemSyncExecutionTest {
    @Test fun periodicWithPushEnabledSendsBeforeReceiving() = runBlocking {
        val order = mutableListOf<String>()
        executeCalendarPass(false, { order += "receive"; ImportRunResult.COMPLETE },
            { order += "send"; ImportRunResult.COMPLETE })
        assertEquals(listOf("send", "receive"), order)
    }
    @Test fun periodicPullDoesNotRunTheOutgoingPipeline() = runBlocking {
        var sent = false
        assertEquals(ImportRunResult.COMPLETE, executeCalendarPass(true,
            { ImportRunResult.COMPLETE }, { sent = true; ImportRunResult.CONTINUE }))
        assertFalse(sent)
    }

    @Test fun fullManualSyncRunsBothPipelinesAndKeepsTheirContinuation() = runBlocking {
        var sent = false
        assertEquals(ImportRunResult.CONTINUE, executeCalendarPass(false,
            { ImportRunResult.COMPLETE }, { sent = true; ImportRunResult.CONTINUE }))
        assertTrue(sent)
    }
    @Test fun onlyAndroidPushRequiresAutomaticSettings() {
        assertTrue(requiresPushSettings(manual = false, pull = false))
        assertFalse(requiresPushSettings(manual = true, pull = false))
        assertFalse(requiresPushSettings(manual = false, pull = true))
        assertFalse(requiresPushSettings(manual = true, pull = true))
    }
    @Test fun interruptedEnqueueDoesNotWaitAndRestoresTheInterruptFlag() {
        try {
            assertEquals(SystemSyncOutcome.RETRY, relaySystemSync(
                { throw InterruptedException() }, { error("No accepted enqueue acknowledgement") }))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun interruptedResultWaitReturnsRetryWithoutChangingTheExecutor() {
        var enqueued = false
        try {
            assertEquals(SystemSyncOutcome.RETRY, relaySystemSync(
                { enqueued = true }, { throw InterruptedException() }))
            assertTrue(enqueued)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun failedResultReadIsRetryAndSuccessfulOutcomeIsForwarded() {
        assertEquals(SystemSyncOutcome.RETRY, relaySystemSync({}, { throw java.util.concurrent.TimeoutException() }))
        assertEquals(SystemSyncOutcome.SUCCESS, relaySystemSync({}, { SystemSyncOutcome.SUCCESS }))
    }

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
