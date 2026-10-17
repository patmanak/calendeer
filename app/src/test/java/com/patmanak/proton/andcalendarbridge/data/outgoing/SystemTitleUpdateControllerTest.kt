// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.data.importing.RetainedLocalChangeListing
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Scheduling and scope admission only. These operations do not stand in for Proton crypto,
 * durable claim, canonical reservation recovery, native ownership or service compatibility.
 */
class SystemTitleUpdateControllerTest {
    private val target = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 0)
    private fun ref(event: String, kind: MutationKind = MutationKind.UPDATE, state: LocalEditState = LocalEditState.PAUSED) =
        RetainedLocalChangeRef(EventId(event), MutationId("intent-$event"), kind, state)
    private fun page(query: RetainedLocalChangeQuery, vararg refs: RetainedLocalChangeRef) =
        RetainedLocalChangeListing.Available(RetainedLocalChangePage.fromOrderedMetadata(query, refs.toList()))

    private class Cursors : TitleRecoveryCursorStore {
        val positions = mutableMapOf<CalendarScope, TitleUpdateWorkCursor>()
        val done = mutableSetOf<CalendarScope>()
        override suspend fun read(scope: CalendarScope) = positions[scope]
        override suspend fun exhausted(scope: CalendarScope) = scope in done
        override suspend fun write(scope: CalendarScope, cursor: TitleUpdateWorkCursor?) {
            done.remove(scope)
            if (cursor == null) positions.remove(scope) else positions[scope] = cursor
        }
        override suspend fun markExhausted(scope: CalendarScope) { positions.remove(scope); done.add(scope) }
    }

    private class Operations : SystemTitleUpdateOperations {
        val calls = mutableListOf<String>()
        val discovered = mutableListOf<RetainedLocalChangeQuery>()
        val inspected = mutableListOf<TitleUpdateWorkQuery>()
        val processed = mutableListOf<RetainedLocalChangeRef>()
        val proposals = mutableListOf<Pair<MutationId, Instant>>()
        var discoverBlock: suspend (RetainedLocalChangeQuery) -> RetainedLocalChangeListing = {
            RetainedLocalChangeListing.Available(RetainedLocalChangePage.fromOrderedMetadata(it, emptyList()))
        }
        var inspectBlock: suspend (TitleUpdateWorkQuery, (CalendarScope) -> Boolean, () -> Unit) -> TitleUpdateOfflineRecoveryProgress =
            { _, _, _ -> TitleUpdateOfflineRecoveryProgress() }
        var processBlock: suspend (CalendarScope, RetainedLocalChangeRef, (CalendarScope) -> Boolean, () -> Unit) -> Boolean =
            { _, _, _, _ -> false }
        override suspend fun discover(query: RetainedLocalChangeQuery): RetainedLocalChangeListing {
            calls += "discover"; discovered += query; return discoverBlock(query)
        }
        override suspend fun inspect(query: TitleUpdateWorkQuery, admission: (CalendarScope) -> Boolean,
            proofRetained: () -> Unit): TitleUpdateOfflineRecoveryProgress {
            calls += "inspect"; inspected += query; return inspectBlock(query, admission, proofRetained)
        }
        override suspend fun process(scope: CalendarScope, reference: RetainedLocalChangeRef, attempt: MutationId,
            timestamp: Instant, admission: (CalendarScope) -> Boolean, proofRetained: () -> Unit): Boolean {
            calls += "process"; processed += reference; proposals += attempt to timestamp
            return processBlock(scope, reference, admission, proofRetained)
        }
    }

    @Test fun noNetworkOrNoAdmissionCannotDiscoverInspectOrProcessAnything() = runBlocking {
        val changes = Cursors(); val attempts = Cursors(); val operations = Operations(); var wakes = 0
        val cursor = TitleUpdateWorkCursor(target, EventId("partial"))
        changes.positions[target] = cursor; attempts.positions[target] = cursor
        val controller = SystemTitleUpdateController(changes, attempts, operations, { wakes++ })
        assertEquals(SystemTitleRecoveryProgress(), controller.run(target, network = false) { true })
        assertTrue(operations.calls.isEmpty()); assertEquals(0, wakes)
        assertEquals(SystemTitleRecoveryProgress(), controller.run(target, network = true) { false })
        assertTrue(operations.calls.isEmpty()); assertEquals(0, wakes)
        assertNull(changes.positions[target]); assertNull(attempts.positions[target])
    }

    @Test fun inspectsOldAttemptsBeforeDiscoveringAndProcessesOnlyPausedUpdates() = runBlocking {
        val operations = Operations(); val update = ref("a")
        operations.discoverBlock = { page(it, update, ref("b", MutationKind.DELETE), ref("c", state = LocalEditState.CONFLICT)) }
        var retainedLease: ((CalendarScope) -> Boolean)? = null
        operations.processBlock = { scope, _, admission, _ ->
            assertEquals(target, scope); assertTrue(admission(scope)); assertFalse(admission(scope.copy(generation = 1)))
            assertTrue(admission(scope)); retainedLease = admission; false
        }
        var wakes = 0
        val controller = SystemTitleUpdateController(Cursors(), Cursors(), operations, { wakes++ })
        assertEquals(SystemTitleRecoveryProgress(), controller.run(target, network = true) { true })
        assertEquals(listOf("inspect", "discover", "process"), operations.calls)
        assertEquals(listOf(update), operations.processed)
        assertEquals(25, operations.inspected.single().limit); assertEquals(25, operations.discovered.single().limit)
        assertEquals(0, wakes); assertFalse(checkNotNull(retainedLease)(target))
        val (attempt, timestamp) = operations.proposals.single()
        assertEquals(attempt.value, UUID.fromString(attempt.value).toString()); assertEquals(0, timestamp.nano)
    }

    @Test fun eachPausedOwnerGetsADistinctCanonicalProposalWithAWholeSecondTimestamp() = runBlocking {
        val operations = Operations(); operations.discoverBlock = { page(it, ref("a"), ref("b")) }
        val controller = SystemTitleUpdateController(Cursors(), Cursors(), operations, { error("No proof") })
        controller.run(target, network = true) { true }
        assertEquals(2, operations.proposals.size)
        assertEquals(2, operations.proposals.map { it.first }.toSet().size)
        operations.proposals.forEach { (id, time) ->
            assertEquals(id.value, UUID.fromString(id.value).toString()); assertEquals(0, time.nano)
        }
    }

    @Test fun deletionTraversalInspectsBeforeProcessingOnlyPausedDeletesAndClosesItsLease() = runBlocking {
        val operations = Operations(); val deletion = ref("b", MutationKind.DELETE)
        operations.discoverBlock = { page(it, ref("a"), deletion, ref("c", MutationKind.DELETE, LocalEditState.CONFLICT)) }
        var retainedLease: ((CalendarScope) -> Boolean)? = null
        operations.processBlock = { requested, _, admit, proof ->
            assertTrue(admit(requested)); retainedLease = admit; proof(); false
        }
        var wakes = 0
        val controller = SystemTitleUpdateController(Cursors(), Cursors(), operations, { wakes++ },
            mutationKind = MutationKind.DELETE)
        assertEquals(SystemTitleRecoveryProgress(), controller.run(target, network = false) { true })
        assertTrue(operations.calls.isEmpty())
        assertEquals(SystemTitleRecoveryProgress(more = true), controller.run(target, network = true) { true })
        assertEquals(listOf("inspect", "discover", "process"), operations.calls)
        assertEquals(listOf(deletion), operations.processed); assertEquals(1, wakes)
        assertFalse(checkNotNull(retainedLease)(target))
    }

    @Test fun restartedDeletionTraversalKeepsItsBoundAndNeverReopensARevokedRun() = runBlocking {
        val changes = Cursors(); val attempts = Cursors(); val operations = Operations()
        operations.discoverBlock = { query ->
            if (query.cursor == null) page(query, ref("a", MutationKind.DELETE), ref("b", MutationKind.DELETE))
            else page(query, ref("b", MutationKind.DELETE))
        }
        var allowed = true
        val first = SystemTitleUpdateController(changes, attempts, operations, { error("No proof") },
            pageSize = 1, mutationKind = MutationKind.DELETE)
        assertTrue(first.run(target, network = true) { allowed }.more)
        assertEquals(listOf(ref("a", MutationKind.DELETE)), operations.processed)
        operations.processBlock = { requested, _, admit, _ ->
            allowed = false; assertFalse(admit(requested))
            allowed = true; assertFalse(admit(requested)); false
        }
        val restarted = SystemTitleUpdateController(changes, attempts, operations, { error("No proof") },
            pageSize = 1, mutationKind = MutationKind.DELETE)
        assertFalse(restarted.run(target, network = true, fresh = false) { allowed }.more)
        assertEquals(RetainedLocalChangeCursor(target, EventId("a")), operations.discovered.last().cursor)
        assertEquals(listOf(ref("a", MutationKind.DELETE), ref("b", MutationKind.DELETE)), operations.processed)
        assertEquals(1, operations.inspected.size)
    }

    @Test fun freshProofWakesExhaustedOfflineRecoveryOnceAndForcesOnlyOneContinuation() = runBlocking {
        val changes = Cursors(); val attempts = Cursors(); val offline = Cursors(); offline.done += target
        val operations = Operations(); operations.discoverBlock = { page(it, ref("a"), ref("b")) }
        operations.inspectBlock = { _, _, proof -> proof(); proof(); TitleUpdateOfflineRecoveryProgress(completed = 1) }
        operations.processBlock = { _, _, _, proof -> proof(); false }
        val wakes = mutableListOf<CalendarScope>()
        val controller = SystemTitleUpdateController(changes, attempts, operations, { wakes += it; offline.write(it, null) })
        assertEquals(SystemTitleRecoveryProgress(more = true), controller.run(target, network = true) { true })
        assertEquals(listOf(target), wakes); assertFalse(offline.exhausted(target))
        assertTrue(changes.exhausted(target)); assertTrue(attempts.exhausted(target))
        val calls = operations.calls.toList()
        repeat(3) { assertEquals(SystemTitleRecoveryProgress(), controller.run(target, network = true, fresh = false) { true }) }
        assertEquals(calls, operations.calls); assertEquals(listOf(target), wakes)
    }

    @Test fun heldAndFailedWorkWithoutProofNeverWakeOrRequestImmediateRetry() = runBlocking {
        val operations = Operations(); operations.discoverBlock = { page(it, ref("a")) }
        operations.inspectBlock = { _, _, _ -> TitleUpdateOfflineRecoveryProgress(held = 1, failed = 1) }
        operations.processBlock = { _, _, _, _ -> true }
        var wakes = 0
        val controller = SystemTitleUpdateController(Cursors(), Cursors(), operations, { wakes++ })
        assertEquals(SystemTitleRecoveryProgress(failed = true), controller.run(target, network = true) { true })
        assertEquals(0, wakes); assertEquals(1, operations.processed.size)
        val before = operations.calls.toList()
        assertEquals(SystemTitleRecoveryProgress(), controller.run(target, network = true, fresh = false) { true })
        assertEquals(before, operations.calls)
    }

    @Test fun twoScopesAndTwoIndexesFinishWithoutAlternatingEndlessRestarts() = runBlocking {
        val other = target.copy(calendar = CalendarId("other"))
        val changes = Cursors(); val attempts = Cursors(); val operations = Operations()
        operations.inspectBlock = { query, _, _ ->
            if (query.scope == target && query.cursor == null)
                TitleUpdateOfflineRecoveryProgress(held = 1, next = TitleUpdateWorkCursor(target, EventId("old-attempt")))
            else TitleUpdateOfflineRecoveryProgress(held = 1)
        }
        operations.discoverBlock = { query ->
            if (query.scope == other && query.cursor == null) page(query, ref("a"), ref("b"))
            else if (query.scope == other) page(query, ref("b")) else page(query)
        }
        val controller = SystemTitleUpdateController(changes, attempts, operations, { error("No proof") }, pageSize = 1)
        assertTrue(controller.run(target, network = true, fresh = true) { true }.more)
        assertTrue(controller.run(other, network = true, fresh = true) { true }.more)
        val reopened = SystemTitleUpdateController(changes, attempts, operations, { error("No proof") }, pageSize = 1)
        assertFalse(reopened.run(target, network = true, fresh = false) { true }.more)
        assertFalse(reopened.run(other, network = true, fresh = false) { true }.more)
        assertEquals(listOf(target, other, target), operations.inspected.map { it.scope })
        assertEquals(listOf(target, other, other), operations.discovered.map { it.scope })
        assertEquals(RetainedLocalChangeCursor(other, EventId("a")), operations.discovered.last().cursor)
        val before = operations.calls.toList()
        repeat(3) {
            assertFalse(reopened.run(target, network = true, fresh = false) { true }.more)
            assertFalse(reopened.run(other, network = true, fresh = false) { true }.more)
        }
        assertEquals(before, operations.calls)
        assertTrue(reopened.run(other, network = true, fresh = true) { true }.more)
    }

    @Test fun revocationDuringInspectionStopsBeforeOwnerDiscovery() = runBlocking {
        val operations = Operations(); var allowed = true; var wakes = 0
        operations.inspectBlock = { _, _, _ -> allowed = false; TitleUpdateOfflineRecoveryProgress() }
        val controller = SystemTitleUpdateController(Cursors(), Cursors(), operations, { wakes++ })
        assertEquals(SystemTitleRecoveryProgress(), controller.run(target, network = true) { allowed })
        assertEquals(listOf("inspect"), operations.calls); assertEquals(0, wakes)
    }

    @Test fun observedRevocationDoesNotReopenAndPreventsTheNextOwner() = runBlocking {
        val operations = Operations(); operations.discoverBlock = { page(it, ref("a"), ref("b")) }
        var allowed = true
        operations.processBlock = { scope, _, admit, _ ->
            assertTrue(admit(scope)); allowed = false; assertFalse(admit(scope))
            allowed = true; assertFalse(admit(scope)); false
        }
        val controller = SystemTitleUpdateController(Cursors(), Cursors(), operations, { error("No proof") })
        assertEquals(SystemTitleRecoveryProgress(), controller.run(target, network = true) { allowed })
        assertEquals(listOf(ref("a")), operations.processed)
    }

    @Test fun foreignOrOversizedDiscoveryPagesAreRejectedBeforeProcessingAnyEntry() = runBlocking {
        for (foreign in listOf(true, false)) {
            val operations = Operations()
            operations.discoverBlock = { query ->
                val forged = if (foreign) query.copy(scope = target.copy(generation = 1)) else query.copy(limit = 2)
                if (foreign) page(forged, ref("a")) else page(forged, ref("a"), ref("b"))
            }
            val controller = SystemTitleUpdateController(Cursors(), Cursors(), operations, { error("No proof") }, pageSize = 1)
            assertEquals(SystemTitleRecoveryProgress(failed = true), controller.run(target, network = true) { true })
            assertTrue(operations.processed.isEmpty())
        }
    }

    @Test fun unsupportedStoredCursorDoesNotReachItsIndexOperations() = runBlocking {
        val changes = Cursors(); changes.positions[target] = TitleUpdateWorkCursor(target, EventId("partial"), protocol = 2)
        val attempts = Cursors(); attempts.done += target
        val operations = Operations()
        val controller = SystemTitleUpdateController(changes, attempts, operations, { error("No proof") })
        assertEquals(SystemTitleRecoveryProgress(failed = true), controller.run(target, network = true, fresh = false) { true })
        assertTrue(operations.calls.isEmpty())
    }

    @Test fun unavailableDiscoveryFailsBoundedlyWithoutImmediateRetry() = runBlocking {
        val operations = Operations(); operations.discoverBlock = { RetainedLocalChangeListing.Unavailable }
        val controller = SystemTitleUpdateController(Cursors(), Cursors(), operations, { error("No proof") })
        assertEquals(SystemTitleRecoveryProgress(failed = true), controller.run(target, network = true) { true })
        assertEquals(listOf("inspect", "discover"), operations.calls)
        assertEquals(SystemTitleRecoveryProgress(), controller.run(target, network = true, fresh = false) { true })
        assertEquals(listOf("inspect", "discover"), operations.calls)
    }

    @Test fun corruptOwnerDoesNotStarveTheRestOfItsPageOrItsContinuation() = runBlocking {
        val operations = Operations()
        operations.discoverBlock = { page(it, ref("a"), ref("b"), ref("c")) }
        operations.processBlock = { _, entry, _, _ ->
            if (entry.eventId == EventId("a")) error("Synthetic corrupt entry")
            false
        }
        val controller = SystemTitleUpdateController(Cursors(), Cursors(), operations, { error("No proof") }, pageSize = 2)
        assertEquals(SystemTitleRecoveryProgress(more = true, failed = true), controller.run(target, network = true) { true })
        assertEquals(listOf(ref("a"), ref("b")), operations.processed)
    }

    @Test fun swallowedCancellationAtEveryOperationAndWakeCannotReturnNormally() = runBlocking {
        for (stage in listOf("inspect", "discover", "process", "wake")) {
            val operations = Operations(); var normal = false; var wakes = 0
            operations.inspectBlock = { _, _, _ ->
                if (stage == "inspect") currentCoroutineContext().cancel()
                TitleUpdateOfflineRecoveryProgress()
            }
            operations.discoverBlock = {
                if (stage == "discover") currentCoroutineContext().cancel()
                page(it, ref("a"), ref("b"))
            }
            operations.processBlock = { _, _, _, proof ->
                if (stage == "process") currentCoroutineContext().cancel()
                if (stage == "wake") proof()
                false
            }
            val controller = SystemTitleUpdateController(Cursors(), Cursors(), operations, {
                wakes++; currentCoroutineContext().cancel()
            })
            supervisorScope {
                val task = async { controller.run(target, network = true) { true }; normal = true }
                try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            }
            assertFalse(stage, normal)
            if (stage == "inspect") assertEquals(listOf("inspect"), operations.calls)
            if (stage == "discover") assertTrue(operations.processed.isEmpty())
            if (stage == "process") assertEquals(1, operations.processed.size)
            assertEquals(if (stage == "wake") 1 else 0, wakes)
        }
    }

    @Test fun concurrentRunsSerializeBothIndexPagesAndShareTheirExhaustion() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val operations = Operations()
        operations.inspectBlock = { _, _, _ -> entered.complete(Unit); release.await(); TitleUpdateOfflineRecoveryProgress() }
        val controller = SystemTitleUpdateController(Cursors(), Cursors(), operations, { error("No proof") })
        withTimeout(5_000) {
            val first = async { controller.run(target, network = true) { true } }
            entered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { controller.run(target, network = true, fresh = false) { true } }
            try { assertEquals(listOf("inspect"), operations.calls) } finally { release.complete(Unit) }
            assertEquals(SystemTitleRecoveryProgress(), first.await()); assertEquals(SystemTitleRecoveryProgress(), second.await())
        }
        assertEquals(listOf("inspect", "discover"), operations.calls)
    }

    @Test fun resetClearsBothIndexesOnlyForItsTarget() = runBlocking {
        val changes = Cursors(); val attempts = Cursors(); val other = target.copy(generation = 1)
        changes.done += target; attempts.done += target
        changes.positions[other] = TitleUpdateWorkCursor(other, EventId("changes"))
        attempts.positions[other] = TitleUpdateWorkCursor(other, EventId("attempts"))
        val operations = Operations()
        val controller = SystemTitleUpdateController(changes, attempts, operations, { error("No proof") })
        controller.reset(target)
        assertFalse(changes.exhausted(target)); assertFalse(attempts.exhausted(target))
        assertNotNull(changes.positions[other]); assertNotNull(attempts.positions[other]); assertTrue(operations.calls.isEmpty())
    }
}
