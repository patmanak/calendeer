package com.patmanak.proton.andcalendarbridge.sync.catalog

import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarCatalogControllerTest {
    private val item = CalendarSummary(CalendarId("fixture-calendar"), "Fixture calendar", 0x008888)
    private fun found(items: List<CalendarSummary> = listOf(item)) =
        CalendarCatalogResult.Success(AccountId("fixture-account"), items)

    @Test fun onlyAuthenticationStartsDiscoveryAndRecreationDoesNotReload() = runTest {
        var calls = 0
        val controller = CalendarCatalogController({ calls++; found() }, this) { testScheduler.currentTime }
        controller.refresh()
        runCurrent()
        assertEquals(0, calls)
        controller.setAuthenticated(true)
        assertEquals(CalendarCatalogStatus.LOADING, controller.state.value.status)
        runCurrent()
        controller.setAuthenticated(true)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(listOf(item), controller.state.value.calendars)
    }

    @Test fun emptySuccessIsDifferentFromFailedDiscovery() = runTest {
        var result: CalendarCatalogResult = CalendarCatalogResult.Failure(CalendarCatalogFailure.UNREACHABLE)
        val controller = CalendarCatalogController({ result }, this) { testScheduler.currentTime }
        controller.setAuthenticated(true)
        runCurrent()
        assertEquals(CalendarCatalogStatus.ERROR, controller.state.value.status)
        result = found(emptyList())
        controller.refresh()
        runCurrent()
        assertEquals(CalendarCatalogStatus.READY, controller.state.value.status)
        assertTrue(controller.state.value.calendars.isEmpty())
    }

    @Test fun transientFailureRetainsSnapshotButRevokedAccessClearsIt() = runTest {
        var result: CalendarCatalogResult = found()
        val controller = CalendarCatalogController({ result }, this) { testScheduler.currentTime }
        controller.setAuthenticated(true)
        runCurrent()
        result = CalendarCatalogResult.Failure(CalendarCatalogFailure.UNREACHABLE)
        controller.refresh()
        runCurrent()
        assertEquals(listOf(item), controller.state.value.calendars)
        result = CalendarCatalogResult.Failure(CalendarCatalogFailure.AUTHENTICATION_REQUIRED)
        controller.refresh()
        runCurrent()
        assertTrue(controller.state.value.calendars.isEmpty())
        assertEquals(CalendarCatalogStatus.ERROR, controller.state.value.status)
    }

    @Test fun signOutDiscardsPendingResultAndNextAccountStartsEmpty() = runTest {
        val pending = CompletableDeferred<CalendarCatalogResult>()
        var calls = 0
        val controller = CalendarCatalogController({
            if (++calls == 1) pending.await() else found(emptyList())
        }, this) { testScheduler.currentTime }
        controller.setAuthenticated(true)
        runCurrent()
        controller.setAuthenticated(false)
        pending.complete(found())
        runCurrent()
        assertEquals(CalendarCatalogStatus.SIGNED_OUT, controller.state.value.status)
        assertTrue(controller.state.value.calendars.isEmpty())
        controller.setAuthenticated(true)
        runCurrent()
        assertEquals(CalendarCatalogStatus.READY, controller.state.value.status)
        assertTrue(controller.state.value.calendars.isEmpty())
    }

    @Test fun duplicateRefreshIsNotParallelAndRetryAfterIsHonored() = runTest {
        val pending = CompletableDeferred<CalendarCatalogResult>()
        var calls = 0
        val controller = CalendarCatalogController({ calls++; pending.await() }, this) { testScheduler.currentTime }
        controller.setAuthenticated(true)
        runCurrent()
        controller.refresh()
        runCurrent()
        assertEquals(1, calls)
        pending.complete(CalendarCatalogResult.Failure(CalendarCatalogFailure.RATE_LIMITED, 5_000))
        runCurrent()
        assertTrue(controller.state.value.isRetryBlocked)
        controller.refresh()
        assertEquals(1, calls)
        advanceTimeBy(4_999)
        runCurrent()
        assertFalse(controller.state.value.canRefresh)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(controller.state.value.canRefresh)
    }

    @Test fun unexpectedFailureIsNotAnEmptyCatalogAndDoesNotExposeExceptionText() = runTest {
        val controller = CalendarCatalogController({ error("fixture private server detail") }, this) { testScheduler.currentTime }
        controller.setAuthenticated(true)
        runCurrent()
        assertEquals(CalendarCatalogFailure.TRY_AGAIN, controller.state.value.failure)
        assertFalse(controller.state.value.toString().contains("private server"))
    }

    @Test fun nonCooperativeOldAccountResultCannotReplaceNewAccountCatalog() = runTest {
        var pending: Continuation<CalendarCatalogResult>? = null
        var calls = 0
        val controller = CalendarCatalogController({
            if (++calls == 1) suspendCoroutine { pending = it }
            else CalendarCatalogResult.Success(AccountId("fixture-account-b"), emptyList())
        }, this) { testScheduler.currentTime }
        controller.setAuthenticated(true)
        runCurrent()
        assertNotNull(pending)
        controller.setAuthenticated(false)
        controller.setAuthenticated(true)
        runCurrent()
        assertEquals(CalendarCatalogStatus.READY, controller.state.value.status)
        pending!!.resume(found())
        runCurrent()
        assertTrue(controller.state.value.calendars.isEmpty())
        assertEquals(CalendarCatalogStatus.READY, controller.state.value.status)
    }
}
