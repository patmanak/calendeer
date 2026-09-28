package com.patmanak.proton.andcalendarbridge.sync

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeltaImportEngineTest {
    private val scope = CalendarScope(AccountId("fixture"), CalendarId("fixture"), 0)
    private val format = AdapterFormat("fixture", 1)
    private val delta = AdapterFormat("fixture-delta", 1)
    private val start = OpaqueCursor(delta, "start")
    private val end = OpaqueCursor(delta, "end")
    private val id = EventId("event")
    private val event = RemoteChange.Upsert(id, Revision("new"), EventContent("Fixture",
        EventTime.AllDay(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2))))
    private val changed = CalendarChangeNotice("change", CalendarNoticeKind.EVENT_CHANGED, id)
    private val deleted = CalendarChangeNotice("delete", CalendarNoticeKind.EVENT_DELETED, id, Revision("deleted"))
    private fun finishedCoverage(): CoverageLedger {
        val metadata = PageCoverage(listOf(CoverageInterval(LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 2, 1), java.time.ZoneOffset.UTC)), 0, true, true)
        val received = CoverageTracker.received(CoverageLedger(epoch = 7),
            PageRequest(PageCheckpoint(scope, format), 100), metadata, 10)
        return CoverageTracker.applied(received, metadata, true, 20)
    }
    private inner class Remote : CalendarDeltaRemote {
        override val format = this@DeltaImportEngineTest.format
        override val deltaFormat = delta
        var notices = listOf(changed)
        var reset = false
        var missing = false
        var stopDecode = false
        var receives = 0
        var contexts = 0
        var next = end
        var beforeChangesDecode: () -> Unit = {}
        var beforeDecode: () -> Unit = {}
        override suspend fun currentAccount() = scope.account
        override suspend fun dispatch(mutation: Mutation) = DispatchResult.Rejected
        override suspend fun fetch(request: PageRequest): RemotePage = error("Unused")
        override suspend fun receive(request: PageRequest): ReceivedCalendarPage = error("Unused")
        override suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage = error("Unused")
        override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? = null
        override suspend fun latestChanges(scope: CalendarScope) = start
        override suspend fun receiveChanges(scope: CalendarScope, after: OpaqueCursor) = ReceivedChangePage(scope, after, "sealed")
        override suspend fun decodeChanges(received: ReceivedChangePage): DecodedChangePage {
            beforeChangesDecode()
            return DecodedChangePage(scope, received.after, next, false, reset, notices)
        }
        override suspend fun receiveEvent(scope: CalendarScope, eventId: EventId): ReceivedCalendarEvent {
            receives++
            if (missing) throw RemoteImportException(RemoteImportFailure.NOT_FOUND)
            return ReceivedCalendarEvent(scope, eventId, event.revision, format, "sealed")
        }
        override suspend fun decodeEvent(received: ReceivedCalendarEvent): DecodedCalendarEvent {
            beforeDecode()
            if (stopDecode) error("Simulated interrupted local decode")
            return DecodedCalendarEvent(event)
        }
        override suspend fun refreshContext(scope: CalendarScope) { contexts++ }
    }
    private fun store() = InMemorySyncStore().apply {
        seed(scope, InMemorySyncStore.State(checkpoint = PageCheckpoint(scope, format, traversalComplete = true),
            deltaCursor = start, deltaMore = true))
    }
    @Test fun inheritedContentChangeStartsDurableRescanBeforeEventObligations() = runBlocking {
        val store = store(); val remote = Remote().apply {
            notices = listOf(changed, CalendarChangeNotice("settings", CalendarNoticeKind.CONTENT_CHANGED))
        }
        store.seed(scope, store.snapshot(scope).copy(coverage = finishedCoverage()))
        val engine = DeltaImportEngine(store, remote, AccountSerialExecutor())
        val progress = engine.run(scope, true)
        assertTrue(progress.workRemaining)
        assertFalse(progress.historyGap)
        assertNull(store.snapshot(scope).checkpoint)
        assertNull(store.snapshot(scope).coverage.observed)
        assertEquals(8L, store.snapshot(scope).coverage.epoch)
        assertEquals(end, store.snapshot(scope).deltaCursor)
        assertTrue("A settings rescan must check changes acquired during the scan", store.snapshot(scope).deltaMore)
        assertEquals(listOf(changed), store.snapshot(scope).jobs.values.map { it.notice })
        assertEquals(1, remote.contexts); assertEquals(0, remote.receives)
        assertTrue(DeltaImportEngine(store, remote, AccountSerialExecutor()).run(scope, false).needsNetwork)
        assertEquals(0, remote.receives)
        store.seed(scope, store.snapshot(scope).copy(checkpoint = PageCheckpoint(scope, format, traversalComplete = true)))
        remote.notices = emptyList()
        assertFalse(engine.run(scope, true).workRemaining)
        assertEquals(event, store.snapshot(scope).remote[id])
    }

    @Test fun inheritedContentRefreshRollbackKeepsObligationAndCoverage() = runBlocking {
        val store = store(); val remote = Remote().apply {
            notices = listOf(CalendarChangeNotice("settings", CalendarNoticeKind.CONTENT_CHANGED))
        }
        val coverage = finishedCoverage()
        store.seed(scope, store.snapshot(scope).copy(coverage = coverage))
        store.beforeCommit = { if (it.checkpoint == null) error("Storage full") }
        try { DeltaImportEngine(store, remote, AccountSerialExecutor()).run(scope, true); fail() }
        catch (_: IllegalStateException) { }
        assertTrue(store.snapshot(scope).checkpoint!!.traversalComplete)
        assertEquals(coverage, store.snapshot(scope).coverage)
        assertEquals(1, store.snapshot(scope).jobs.size)
    }

    @Test fun capturePrecedesBaselineAndPreservesExistingRecords() = runBlocking {
        val store = InMemorySyncStore(); val remote = Remote()
        val oldCoverage = finishedCoverage()
        store.seed(scope, InMemorySyncStore.State(remote = mutableMapOf(id to event), coverage = oldCoverage))
        val engine = DeltaImportEngine(store, remote, AccountSerialExecutor())
        assertFalse(engine.prepare(scope, false, true))
        assertEquals(oldCoverage, store.snapshot(scope).coverage)
        assertTrue(engine.prepare(scope, true, true))
        assertEquals(8L, store.snapshot(scope).coverage.epoch)
        assertEquals(oldCoverage.active, store.snapshot(scope).coverage.previous)
        assertNull(store.snapshot(scope).coverage.active)
        assertTrue(engine.prepare(scope, true, true))
        assertEquals(8L, store.snapshot(scope).coverage.epoch)
        assertEquals(start, store.snapshot(scope).deltaCursor)
        engine.run(scope, true)
        assertEquals(0, remote.receives)
        assertEquals(event, store.snapshot(scope).remote[id])
    }
    @Test fun resetArchivesEvidenceAndIgnoresItsNoticesEvenOffline() = runBlocking {
        val store = store(); val remote = Remote().apply { reset = true; notices = listOf(deleted) }
        val receipt = ReceivedChangePage(scope, start, "sealed-reset")
        val oldBlocked = CalendarChangeJob(changed, blocked = true)
        store.seed(scope, store.snapshot(scope).also {
            it.remote[id] = event; it.deltaReceived = receipt; it.jobs[changed.id] = oldBlocked
            it.coverage = finishedCoverage()
        })
        val result = DeltaImportEngine(store, remote, AccountSerialExecutor()).run(scope, false)
        assertTrue(result.needsNetwork); assertTrue(result.historyGap); assertTrue(result.workRemaining)
        assertEquals(listOf(receipt), store.snapshot(scope).reconciliations)
        assertNull(store.snapshot(scope).deltaReceived)
        assertNull(store.snapshot(scope).checkpoint)
        assertEquals(oldBlocked, store.snapshot(scope).jobs[changed.id])
        assertEquals(event, store.snapshot(scope).remote[id])
        assertTrue(store.snapshot(scope).projections.isEmpty())
        assertEquals(0, remote.receives)
        assertEquals(8L, store.snapshot(scope).coverage.epoch)
        assertNull(store.snapshot(scope).coverage.active)
        assertEquals(finishedCoverage().active, store.snapshot(scope).coverage.previous)
    }
    @Test fun resetTransactionFailurePreservesCursorReceiptAndBaseline() = runBlocking {
        val store = store(); val remote = Remote().apply { reset = true; notices = emptyList() }
        val oldCoverage = finishedCoverage()
        store.seed(scope, store.snapshot(scope).copy(coverage = oldCoverage))
        store.beforeCommit = { if (it.reconciliations.isNotEmpty()) error("Storage full") }
        try { DeltaImportEngine(store, remote, AccountSerialExecutor()).run(scope, true); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(start, store.snapshot(scope).deltaCursor)
        assertTrue(store.snapshot(scope).checkpoint!!.traversalComplete)
        assertNotNull(store.snapshot(scope).deltaReceived)
        assertTrue(store.snapshot(scope).reconciliations.isEmpty())
        assertEquals(oldCoverage, store.snapshot(scope).coverage)
    }
    @Test fun restartAfterResetMustWaitForBaselineAndKeepGapAfterFutureChanges() = runBlocking {
        val store = store(); val remote = Remote().apply { reset = true; notices = listOf(deleted) }
        DeltaImportEngine(store, remote, AccountSerialExecutor()).run(scope, true)
        val restarted = DeltaImportEngine(store, remote, AccountSerialExecutor())
        assertTrue(restarted.run(scope, false).needsNetwork)
        assertTrue(store.snapshot(scope).jobs.isEmpty())
        assertEquals(0, remote.receives)
        store.seed(scope, store.snapshot(scope).copy(checkpoint = PageCheckpoint(scope, format, traversalComplete = true)))
        remote.reset = false; remote.notices = listOf(changed); remote.next = OpaqueCursor(delta, "future")
        val result = restarted.run(scope, true)
        assertTrue(result.historyGap); assertFalse(result.workRemaining)
        assertEquals(event, store.snapshot(scope).remote[id])
        assertEquals(1, store.snapshot(scope).reconciliations.size)
    }
    @Test fun repeatedResetRetainsEveryEvidenceAndDoesNotLoopWithinRun() = runBlocking {
        val store = store(); val remote = Remote().apply { reset = true; notices = emptyList() }
        val engine = DeltaImportEngine(store, remote, AccountSerialExecutor())
        engine.run(scope, true)
        store.seed(scope, store.snapshot(scope).copy(checkpoint = PageCheckpoint(scope, format, traversalComplete = true)))
        remote.next = OpaqueCursor(delta, "later-reset")
        assertTrue(engine.run(scope, true).workRemaining)
        assertEquals(listOf(start, end), store.snapshot(scope).reconciliations.map { it.after })
        assertNull(store.snapshot(scope).checkpoint)
    }
    @Test fun nonAdvancingResetKeepsItsReceiptInsteadOfRescanningForever() = runBlocking {
        val store = store(); val remote = Remote().apply { reset = true; next = start; notices = emptyList() }
        try { DeltaImportEngine(store, remote, AccountSerialExecutor()).run(scope, true); fail() }
        catch (_: SyncContractException) { }
        assertEquals(start, store.snapshot(scope).deltaCursor)
        assertTrue(store.snapshot(scope).checkpoint!!.traversalComplete)
        assertNotNull(store.snapshot(scope).deltaReceived)
        assertTrue(store.snapshot(scope).reconciliations.isEmpty())
    }
    @Test fun foreignArchivedFormatBlocksEvenWithCompatibleCurrentCursor() = runBlocking {
        val store = store(); val remote = Remote()
        store.seed(scope, store.snapshot(scope).also { it.reconciliations.add(
            ReceivedChangePage(scope, OpaqueCursor(AdapterFormat("old", 1), "old"), "sealed")) })
        val engine = DeltaImportEngine(store, remote, AccountSerialExecutor())
        try { engine.prepare(scope, true, true); fail() } catch (_: SyncContractException) { }
        try { engine.run(scope, true); fail() } catch (_: SyncContractException) { }
        assertEquals(0, remote.receives)
        assertEquals(start, store.snapshot(scope).deltaCursor)
    }
    @Test fun staleResetCannotArchiveOrAdvanceChangedReceipt() = runBlocking {
        val store = store(); val remote = Remote().apply { reset = true }
        remote.beforeChangesDecode = { store.seed(scope, store.snapshot(scope).copy(
            deltaReceived = ReceivedChangePage(scope, start, "new-evidence"))) }
        try { DeltaImportEngine(store, remote, AccountSerialExecutor()).run(scope, true); fail() }
        catch (_: SyncContractException) { }
        assertEquals("new-evidence", store.snapshot(scope).deltaReceived!!.protectedPayload)
        assertEquals(start, store.snapshot(scope).deltaCursor)
        assertTrue(store.snapshot(scope).reconciliations.isEmpty())
    }
    @Test fun receivedEventAndAcknowledgedCursorSurviveOfflineRestart() = runBlocking {
        val store = store(); val remote = Remote().apply { stopDecode = true }
        try { DeltaImportEngine(store, remote, AccountSerialExecutor()).run(scope, true); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(end, store.snapshot(scope).deltaCursor)
        assertNotNull(store.snapshot(scope).jobs[changed.id]?.received)
        remote.stopDecode = false
        val result = DeltaImportEngine(store, remote, AccountSerialExecutor()).run(scope, false)
        assertFalse(result.workRemaining); assertEquals(1, remote.receives)
        assertEquals(event, store.snapshot(scope).remote[id])
    }
    @Test fun missingEventRemainsBlockedUntilAuthoritativeTombstone() = runBlocking {
        val store = store(); val remote = Remote().apply { missing = true }
        store.seed(scope, store.snapshot(scope).also { it.remote[id] = event })
        val engine = DeltaImportEngine(store, remote, AccountSerialExecutor())
        assertTrue(engine.run(scope, true).blocked)
        assertEquals(event, store.snapshot(scope).remote[id])
        remote.notices = listOf(deleted)
        engine.prepare(scope, true, true)
        assertFalse(engine.run(scope, true).blocked)
        assertTrue(store.snapshot(scope).remote[id] is RemoteChange.Deleted)
        assertTrue(store.snapshot(scope).jobs.isEmpty())
    }
    @Test fun resetRestartsBaselineWithoutDeletingKnownData() = runBlocking {
        val store = store(); val remote = Remote().apply { reset = true; notices = emptyList() }
        store.seed(scope, store.snapshot(scope).also { it.remote[id] = event })
        val result = DeltaImportEngine(store, remote, AccountSerialExecutor()).run(scope, true)
        assertTrue(result.workRemaining)
        assertNull(store.snapshot(scope).checkpoint)
        assertEquals(end, store.snapshot(scope).deltaCursor)
        assertEquals(event, store.snapshot(scope).remote[id])
    }
    @Test fun contextBarrierPrecedesEventFetchAndWaitsForNetwork() = runBlocking {
        val store = store(); val remote = Remote().apply {
            notices = listOf(changed, CalendarChangeNotice("context", CalendarNoticeKind.CONTEXT_CHANGED))
        }
        store.seed(scope, store.snapshot(scope).copy(deltaReceived = ReceivedChangePage(scope, start, "sealed")))
        val engine = DeltaImportEngine(store, remote, AccountSerialExecutor())
        assertTrue(engine.run(scope, false).needsNetwork)
        assertEquals(0, remote.receives)
        remote.beforeDecode = { assertEquals(1, remote.contexts) }
        assertFalse(engine.run(scope, true).workRemaining)
    }
    @Test fun cursorAndObligationsCommitAtomically() = runBlocking {
        val store = store(); val remote = Remote()
        store.beforeCommit = { if (it.deltaCursor == end) error("Storage failure") }
        try { DeltaImportEngine(store, remote, AccountSerialExecutor()).run(scope, true); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(start, store.snapshot(scope).deltaCursor)
        assertNotNull(store.snapshot(scope).deltaReceived)
        assertTrue(store.snapshot(scope).jobs.isEmpty())
    }
    @Test fun staleCompletionCannotConsumeNewEvidence() = runBlocking {
        val store = store(); val remote = Remote()
        remote.beforeDecode = {
            store.seed(scope, store.snapshot(scope).also { it.jobs[changed.id] = CalendarChangeJob(changed, blocked = true) })
        }
        try { DeltaImportEngine(store, remote, AccountSerialExecutor()).run(scope, true); fail() }
        catch (_: SyncContractException) { }
        assertTrue(store.snapshot(scope).jobs.getValue(changed.id).blocked)
        assertTrue(store.snapshot(scope).remote.isEmpty())
    }
    @Test fun completedBaselineWithWrongFormatCannotAuthorizeDelta() = runBlocking {
        val store = store(); val remote = Remote()
        store.seed(scope, store.snapshot(scope).copy(checkpoint = PageCheckpoint(scope, AdapterFormat("other", 1), traversalComplete = true)))
        try { DeltaImportEngine(store, remote, AccountSerialExecutor()).run(scope, true); fail() }
        catch (_: SyncContractException) { }
        assertEquals(0, remote.receives)
        assertEquals(start, store.snapshot(scope).deltaCursor)
    }
    @Test fun duplicateEventActionsDoNotAdvanceCursor() = runBlocking {
        val store = store(); val remote = Remote().apply { notices = listOf(changed, deleted) }
        try { DeltaImportEngine(store, remote, AccountSerialExecutor()).run(scope, true); fail() }
        catch (_: SyncContractException) { }
        assertEquals(start, store.snapshot(scope).deltaCursor)
        assertTrue(store.snapshot(scope).jobs.isEmpty())
    }
    @Test fun changedAndDeletedNeverOverwritePendingLocalMutationProjection() = runBlocking {
        val store = store(); val remote = Remote().apply { notices = listOf(deleted) }
        store.seed(scope, store.snapshot(scope).also { it.mutations[MutationId("local")] =
            Mutation(MutationId("local"), scope, format, MutationKind.UPDATE, id, event.revision, event.content) })
        DeltaImportEngine(store, remote, AccountSerialExecutor()).run(scope, true)
        assertTrue(store.snapshot(scope).remote[id] is RemoteChange.Deleted)
        assertTrue(store.snapshot(scope).projections.isEmpty())
        assertEquals(1, store.snapshot(scope).mutations.size)
    }
    @Test fun boundedJobDrainResumesWithoutRefetchingPage() = runBlocking {
        val store = store(); val remote = Remote().apply {
            notices = (0..24).map { CalendarChangeNotice("delete-$it", CalendarNoticeKind.EVENT_DELETED, EventId("event-$it"), Revision("deleted")) }
        }
        val engine = DeltaImportEngine(store, remote, AccountSerialExecutor())
        assertTrue(engine.run(scope, true).workRemaining)
        assertEquals(20, store.snapshot(scope).remote.size)
        assertFalse(engine.run(scope, false).workRemaining)
        assertEquals(25, store.snapshot(scope).remote.size)
    }
}
