// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.data.importing.ImportStore
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SignOutWorkCounterTest {
    private val account = AccountId("fixture-account")
    private val scope = CalendarScope(account, CalendarId("fixture-calendar"), 0)
    private val row = ImportStore.Selection(scope, CalendarSummary(scope.calendar, "Fixture"), false, "PAUSED", null,
        available = false, retainedLocalChangeCount = 3, localConflictCount = 1)
    private var epoch = 1L
    private var connected = true
    private var current: AccountId? = account
    private fun counter(rows: suspend (AccountId) -> List<ImportStore.Selection> = { listOf(row) },
        creates: suspend (CalendarScope) -> Int = { 2 }) = SignOutWorkCounter({ current }, { epoch }, { connected }, rows, creates)
    @Test fun disabledAndOldGenerationOperationsAreReadFreshEachTime() = runBlocking {
        var count = 2
        val counter = counter(rows = { assertEquals(account, it); listOf(row, row.copy(scope = scope.copy(generation = 1), retainedLocalChangeCount = 1)) },
            creates = { count })
        assertEquals(8, counter.readCount())
        count = 0
        assertEquals(4, counter.readCount())
    }
    @Test fun accountEpochAndAuthenticationChangesInvalidateTheSample() = runBlocking {
        for (change in listOf<() -> Unit>({ epoch++ }, { connected = false }, { current = AccountId("other-account") })) {
            connected = true; current = account
            assertNull(counter(creates = { change(); 2 }).readCount())
        }
    }
    @Test fun emptyAndFailedReadsAreDistinct() = runBlocking {
        assertEquals(0, counter(rows = { emptyList() }).readCount())
        assertNull(counter(rows = { error("Fixture unavailable") }).readCount())
        assertNull(counter(creates = { -1 }).readCount())
    }
    @Test fun foreignOrDuplicateScopesCannotContributeToAConfirmation() = runBlocking {
        assertNull(counter(rows = { listOf(row.copy(scope = scope.copy(account = AccountId("other-account")))) }).readCount())
        assertNull(counter(rows = { listOf(row, row) }).readCount())
    }
    @Test fun cancellationIsNotAnEmptyWorkQueue() = runBlocking {
        try { counter(creates = { throw CancellationException("Fixture cancelled") }).readCount(); fail("Cancellation required") }
        catch (_: CancellationException) { }
    }
}
