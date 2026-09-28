package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import org.junit.Assert.*
import org.junit.Test

class ProtonEventDecodeBudgetTest {
    @Test fun oversizedUnsupportedRecordCannotConsumeTheNextRecordsBudget() {
        val page = EventDecodeBudget()
        val unsupported = EventTextBudget()
        unsupported.consume("x".repeat(EventTextBudget.MAX_EVENT_BYTES - 10))
        reason(RemoteImportFailure.UNSUPPORTED) { unsupported.consume("x".repeat(20)) }
        val next = EventTextBudget()
        next.consume("ok".repeat(100))
        page.accept(next)
        assertEquals(200, next.bytes)
    }
    @Test fun acceptedAggregateLimitBlocksPageInsteadOfMisclassifyingValidContent() {
        val page = EventDecodeBudget()
        repeat(8) { page.accept(EventTextBudget().also { it.consume("x".repeat(EventTextBudget.MAX_EVENT_BYTES)) }) }
        reason(RemoteImportFailure.CAPACITY) { page.accept(EventTextBudget().also { it.consume("x") }) }
    }
    @Test fun utf8BoundCountsBytesAndIndependentRetryHasAFreshBudget() {
        val event = EventTextBudget()
        event.consume("é".repeat(EventTextBudget.MAX_EVENT_BYTES / 2))
        assertEquals(EventTextBudget.MAX_EVENT_BYTES, event.bytes)
        reason(RemoteImportFailure.UNSUPPORTED) { event.consume("x") }
        EventTextBudget().consume("retry")
    }
    private fun reason(expected: RemoteImportFailure, block: () -> Unit) {
        try { block(); fail("Expected bounded failure") }
        catch (failure: RemoteImportException) { assertEquals(expected, failure.reason) }
    }
}
