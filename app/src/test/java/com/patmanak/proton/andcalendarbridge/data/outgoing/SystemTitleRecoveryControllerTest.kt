// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Pure scheduling policy: a position is neither ownership proof nor a reason to resend. */
class SystemTitleRecoveryControllerTest {
    private val target = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 2)
    private val next = TitleUpdateWorkCursor(target, EventId("held-event"))
    private class Cursors : TitleRecoveryCursorStore {
        val saved = mutableMapOf<CalendarScope, TitleUpdateWorkCursor>()
        val done = mutableSetOf<CalendarScope>()
        val writes = mutableListOf<Pair<CalendarScope, TitleUpdateWorkCursor?>>()
        var reads = 0
        var duringRead: suspend () -> Unit = {}
        var duringWrite: suspend (TitleUpdateWorkCursor?) -> Unit = {}
        override suspend fun read(scope: CalendarScope): TitleUpdateWorkCursor? {
            reads++; duringRead(); return saved[scope]
        }
        override suspend fun write(scope: CalendarScope, cursor: TitleUpdateWorkCursor?) {
            writes += scope to cursor
            done.remove(scope)
            if (cursor == null) saved.remove(scope) else saved[scope] = cursor
            duringWrite(cursor)
        }
        override suspend fun exhausted(scope: CalendarScope) = scope in done
        override suspend fun markExhausted(scope: CalendarScope) {
            write(scope, null)
            done.add(scope)
        }
    }

    @Test fun heldFirstPageContinuesAfterRestartThenRestartsAtBeginning() = runBlocking {
        val cursors = Cursors()
        val queries = mutableListOf<TitleUpdateWorkQuery>()
        val first = SystemTitleRecoveryController(cursors, { query ->
            queries += query; TitleUpdateOfflineRecoveryProgress(held = 1, next = next)
        }, pageSize = 1)
        assertEquals(SystemTitleRecoveryProgress(more = true), first.run(target) { true })
        assertEquals(listOf(TitleUpdateWorkQuery(target, 1)), queries)
        assertEquals(next, cursors.saved[target])
        val reopened = SystemTitleRecoveryController(cursors, { query ->
            queries += query; TitleUpdateOfflineRecoveryProgress(completed = 1)
        }, pageSize = 1)
        assertEquals(SystemTitleRecoveryProgress(), reopened.run(target) { true })
        assertEquals(next, queries.last().cursor); assertNull(cursors.saved[target])
        reopened.run(target) { true }
        assertNull(queries.last().cursor)
        assertEquals(3, queries.size)
    }

    @Test fun heldOrFailedWithoutNextDoesNotScheduleAnImmediateRetry() = runBlocking {
        for (progress in listOf(TitleUpdateOfflineRecoveryProgress(held = 2), TitleUpdateOfflineRecoveryProgress(failed = 2))) {
            val cursors = Cursors(); cursors.saved[target] = next
            var calls = 0
            val controller = SystemTitleRecoveryController(cursors, { calls++; progress })
            assertEquals(SystemTitleRecoveryProgress(failed = progress.failed > 0), controller.run(target) { true })
            assertEquals(1, calls); assertNull(cursors.saved[target])
        }
    }

    @Test fun FailedEntriesDoNotStarveAValidContinuation() = runBlocking {
        val cursors = Cursors()
        val controller = SystemTitleRecoveryController(cursors, {
            TitleUpdateOfflineRecoveryProgress(held = 1, failed = 1, next = next)
        })
        assertEquals(SystemTitleRecoveryProgress(more = true, failed = true), controller.run(target) { true })
        assertEquals(next, cursors.saved[target])
    }

    @Test fun incompatibleStoredCursorIsClearedWithoutCallingRecovery() = runBlocking {
        for (bad in listOf(next.copy(scope = target.copy(generation = 3)), next.copy(protocol = 2))) {
            val cursors = Cursors(); cursors.saved[target] = bad
            var calls = 0
            val controller = SystemTitleRecoveryController(cursors, { calls++; TitleUpdateOfflineRecoveryProgress() })
            assertEquals(SystemTitleRecoveryProgress(failed = true), controller.run(target) { true })
            assertEquals(0, calls); assertNull(cursors.saved[target])
            assertEquals(SystemTitleRecoveryProgress(), controller.run(target) { true })
            assertEquals(1, calls)
        }
    }

    @Test fun incompatibleReturnedContinuationCannotEscapeItsScopeOrProtocol() = runBlocking {
        for (bad in listOf(next.copy(scope = target.copy(account = AccountId("other"))), next.copy(protocol = 2))) {
            val cursors = Cursors(); var calls = 0
            val controller = SystemTitleRecoveryController(cursors, { calls++; TitleUpdateOfflineRecoveryProgress(next = bad) })
            assertEquals(SystemTitleRecoveryProgress(failed = true), controller.run(target) { true })
            assertEquals(1, calls); assertNull(cursors.saved[target])
        }
    }

    @Test fun revokedBeforeRunClearsContinuationWithoutRecovery() = runBlocking {
        val cursors = Cursors(); cursors.saved[target] = next
        var calls = 0
        val controller = SystemTitleRecoveryController(cursors, { calls++; TitleUpdateOfflineRecoveryProgress() })
        assertEquals(SystemTitleRecoveryProgress(), controller.run(target) { false })
        assertEquals(0, calls); assertNull(cursors.saved[target])
    }

    @Test fun revocationDuringCursorReadPreventsRecovery() = runBlocking {
        val cursors = Cursors(); var allowed = true; var calls = 0
        cursors.duringRead = { allowed = false }
        val controller = SystemTitleRecoveryController(cursors, { calls++; TitleUpdateOfflineRecoveryProgress() })
        assertEquals(SystemTitleRecoveryProgress(), controller.run(target) { allowed })
        assertEquals(0, calls); assertNull(cursors.saved[target])
    }

    @Test fun revocationAfterRecoveryDropsItsContinuation() = runBlocking {
        val cursors = Cursors(); var allowed = true; var calls = 0
        val controller = SystemTitleRecoveryController(cursors, {
            calls++; allowed = false; TitleUpdateOfflineRecoveryProgress(completed = 1, next = next)
        })
        assertEquals(SystemTitleRecoveryProgress(), controller.run(target) { allowed })
        assertEquals(1, calls); assertNull(cursors.saved[target])
        assertFalse(cursors.writes.any { it.second == next })
    }

    @Test fun revocationDuringCursorCommitCannotAdvertiseMoreWork() = runBlocking {
        val cursors = Cursors(); var allowed = true
        cursors.duringWrite = { if (it != null) allowed = false }
        val controller = SystemTitleRecoveryController(cursors, { TitleUpdateOfflineRecoveryProgress(next = next) })
        assertEquals(SystemTitleRecoveryProgress(), controller.run(target) { allowed })
        assertNull(cursors.saved[target])
        assertEquals(listOf(next, null), cursors.writes.map { it.second })
    }

    @Test fun inactiveRecoveryResetsPositionWithoutContinuation() = runBlocking {
        val cursors = Cursors(); cursors.saved[target] = next
        val controller = SystemTitleRecoveryController(cursors, { TitleUpdateOfflineRecoveryProgress(inactive = true) })
        assertEquals(SystemTitleRecoveryProgress(), controller.run(target) { true })
        assertNull(cursors.saved[target])
    }

    @Test fun corruptionAndRecoveryFailureAreBoundedAndRestartOnLaterInvocation() = runBlocking {
        for (failRead in listOf(true, false)) {
            val cursors = Cursors(); cursors.saved[target] = next; var calls = 0
            if (failRead) cursors.duringRead = { throw IllegalStateException("Synthetic cursor corruption") }
            val controller = SystemTitleRecoveryController(cursors, { calls++; throw IllegalStateException("Synthetic recovery failure") })
            assertEquals(SystemTitleRecoveryProgress(failed = true), controller.run(target) { true })
            assertEquals(if (failRead) 0 else 1, calls); assertNull(cursors.saved[target])
        }
    }

    @Test fun failedCursorCommitCannotReturnAContinuation() = runBlocking {
        val cursors = Cursors()
        cursors.duringWrite = { if (it != null) throw IllegalStateException("Synthetic commit failure") }
        val controller = SystemTitleRecoveryController(cursors, { TitleUpdateOfflineRecoveryProgress(next = next) })
        assertEquals(SystemTitleRecoveryProgress(failed = true), controller.run(target) { true })
    }

    @Test fun swallowedCancellationInRecoveryCannotReturnNormallyOrCommitContinuation() = runBlocking {
        val cursors = Cursors()
        val controller = SystemTitleRecoveryController(cursors, {
            currentCoroutineContext().cancel(); TitleUpdateOfflineRecoveryProgress(next = next)
        })
        supervisorScope {
            val task = async { controller.run(target) { true }; error("Normal return after cancellation") }
            try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        }
        assertTrue(cursors.writes.isEmpty())
    }

    @Test fun swallowedCancellationInCursorCommitCannotReturnNormally() = runBlocking {
        val cursors = Cursors()
        cursors.duringWrite = { currentCoroutineContext().cancel() }
        val controller = SystemTitleRecoveryController(cursors, { TitleUpdateOfflineRecoveryProgress(next = next) })
        supervisorScope {
            val task = async { controller.run(target) { true }; error("Normal return after cancellation") }
            try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        }
        assertEquals(1, cursors.writes.size)
    }

    @Test fun concurrentRunsSerializeReadRecoverAndCommitAsOnePage() = runBlocking {
        val cursors = Cursors()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val queries = mutableListOf<TitleUpdateWorkQuery>()
        val controller = SystemTitleRecoveryController(cursors, { query ->
            queries += query
            if (queries.size == 1) {
                entered.complete(Unit); release.await(); TitleUpdateOfflineRecoveryProgress(next = next)
            } else TitleUpdateOfflineRecoveryProgress()
        })
        withTimeout(5_000) {
            val first = async { controller.run(target) { true } }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { controller.run(target) { true } }
            try { assertEquals(1, cursors.reads); assertEquals(1, queries.size) } finally { release.complete(Unit) }
            assertTrue(first.await().more); assertFalse(second.await().more)
        }
        assertEquals(listOf(null, next), queries.map { it.cursor }); assertNull(cursors.saved[target])
    }

    @Test fun endingOneScopeNeverErasesAnotherScopesPosition() = runBlocking {
        val other = target.copy(calendar = CalendarId("other-calendar"))
        val cursors = Cursors(); cursors.saved[target] = next; cursors.saved[other] = next.copy(scope = other)
        val controller = SystemTitleRecoveryController(cursors, { TitleUpdateOfflineRecoveryProgress() })
        controller.run(target) { true }
        assertNull(cursors.saved[target]); assertEquals(next.copy(scope = other), cursors.saved[other])
    }

    @Test fun pageSizeIsValidatedAndDefaultIsTwentyFive() = runBlocking {
        for (size in listOf(0, 51)) {
            try { SystemTitleRecoveryController(Cursors(), { TitleUpdateOfflineRecoveryProgress() }, size); fail("Invalid page size") }
            catch (_: IllegalArgumentException) { }
        }
        var limit = -1
        SystemTitleRecoveryController(Cursors(), { limit = it.limit; TitleUpdateOfflineRecoveryProgress() }).run(target) { true }
        assertEquals(25, limit)
    }

    @Test fun explicitResetClearsOnlyItsTargetAndNextRunStartsAtBeginning() = runBlocking {
        val cursors = Cursors(); val other = target.copy(generation = 3)
        cursors.saved[target] = next; cursors.saved[other] = next.copy(scope = other)
        var query: TitleUpdateWorkQuery? = null
        val controller = SystemTitleRecoveryController(cursors, { query = it; TitleUpdateOfflineRecoveryProgress() })
        controller.reset(target)
        assertNull(query); assertNull(cursors.saved[target]); assertEquals(next.copy(scope = other), cursors.saved[other])
        controller.run(target) { true }
        assertNull(query!!.cursor)
    }

    @Test fun staggeredCalendarPagesTerminateWithoutRestartingTheCompletedCalendar() = runBlocking {
        val other = target.copy(calendar = CalendarId("two-page-calendar"))
        val otherNext = next.copy(scope = other)
        val cursors = Cursors()
        val queries = mutableListOf<TitleUpdateWorkQuery>()
        val recover: suspend (TitleUpdateWorkQuery) -> TitleUpdateOfflineRecoveryProgress = { query ->
            queries += query
            if (query.scope == other && query.cursor == null) TitleUpdateOfflineRecoveryProgress(held = 1, next = otherNext)
            else TitleUpdateOfflineRecoveryProgress(held = 1)
        }
        val controller = SystemTitleRecoveryController(cursors, recover, pageSize = 1)
        assertFalse(controller.run(target, fresh = true) { true }.more)
        assertTrue(controller.run(other, fresh = true) { true }.more)
        assertTrue(cursors.exhausted(target)); assertFalse(cursors.exhausted(other))
        // A resumed worker continues every scope, including the one which already finished.
        assertFalse(controller.run(target, fresh = false) { true }.more)
        assertFalse(controller.run(other, fresh = false) { true }.more)
        assertEquals(listOf(target, other, other), queries.map { it.scope })
        assertEquals(listOf(null, null, otherNext), queries.map { it.cursor })
        assertTrue(cursors.exhausted(target)); assertTrue(cursors.exhausted(other))
        val reopened = SystemTitleRecoveryController(cursors, recover, pageSize = 1)
        repeat(3) {
            assertFalse(reopened.run(target, fresh = false) { true }.more)
            assertFalse(reopened.run(other, fresh = false) { true }.more)
        }
        assertEquals(3, queries.size)
        assertFalse(reopened.run(target, fresh = true) { true }.more)
        assertTrue(reopened.run(other, fresh = true) { true }.more)
        assertEquals(5, queries.size)
    }

    @Test fun anotherWorkSourcesContinuationCannotRevisitExhaustedHeldTitles() = runBlocking {
        val cursors = Cursors(); var calls = 0
        val controller = SystemTitleRecoveryController(cursors, { calls++; TitleUpdateOfflineRecoveryProgress(held = 1) })
        assertEquals(SystemTitleRecoveryProgress(), controller.run(target, fresh = true) { true })
        repeat(4) { assertEquals(SystemTitleRecoveryProgress(), controller.run(target, fresh = false) { true }) }
        assertEquals(1, calls); assertTrue(cursors.exhausted(target))
        assertEquals(SystemTitleRecoveryProgress(), controller.run(target, fresh = true) { true })
        assertEquals(2, calls)
    }

    @Test fun freshInvocationKeepsAnIncompleteCursorButExplicitResetClearsItsTraversal() = runBlocking {
        val cursors = Cursors(); val queries = mutableListOf<TitleUpdateWorkQuery>()
        val controller = SystemTitleRecoveryController(cursors, {
            queries += it
            if (it.cursor == null) TitleUpdateOfflineRecoveryProgress(next = next) else TitleUpdateOfflineRecoveryProgress()
        })
        controller.run(target, fresh = true) { true }
        val reopened = SystemTitleRecoveryController(cursors, { queries += it; TitleUpdateOfflineRecoveryProgress() })
        reopened.run(target, fresh = true) { true }
        assertEquals(next, queries.last().cursor); assertTrue(cursors.exhausted(target))
        reopened.reset(target)
        assertFalse(cursors.exhausted(target)); assertNull(cursors.saved[target])
        reopened.run(target, fresh = false) { true }
        assertNull(queries.last().cursor)
    }
}
