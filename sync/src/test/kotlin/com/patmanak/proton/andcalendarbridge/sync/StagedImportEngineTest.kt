package com.patmanak.proton.andcalendarbridge.sync

import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StagedImportEngineTest {
    private val scope = CalendarScope(AccountId("fixture"), CalendarId("fixture"), 0)
    private val format = AdapterFormat("sealed-fixture", 1)
    private fun event(id: String) = RemoteChange.Upsert(EventId(id), Revision("one"), EventContent("Fixture",
        EventTime.AllDay(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2))))
    private fun issue(id: String) = DeferredCalendarEvent(EventId(id), Revision("one"), format,
        DeferredEventReason.UNSUPPORTED_CONTENT, "sealed-fixture")
    private fun pageCoverage() = PageCoverage(listOf(CoverageInterval(LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 2, 1), java.time.ZoneOffset.UTC)), 0, true, true)

    private inner class Remote : StagedCalendarRemote {
        override val format = this@StagedImportEngineTest.format
        var receives = 0
        var failDecode = false
        var continuePages = false
        var deferred = listOf(issue("incompatible"))
        var changes = listOf(event("before"), event("after"))
        var retryResult: RemoteChange.Upsert? = null
        var onRetry: () -> Unit = {}
        var onReceive: () -> Unit = {}
        var onDecode: () -> Unit = {}
        var coverage: PageCoverage? = null
        override suspend fun currentAccount() = scope.account
        override suspend fun dispatch(mutation: Mutation) = DispatchResult.Rejected
        override suspend fun fetch(request: PageRequest): RemotePage = error("Legacy fetch must not run")
        override suspend fun receive(request: PageRequest): ReceivedCalendarPage {
            receives++
            onReceive()
            return ReceivedCalendarPage(request, "sealed-page", coverage)
        }
        override suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage {
            onDecode()
            if (failDecode) error("simulated termination before decode")
            val next = if (continuePages) OpaqueCursor(format,
                ((received.request.checkpoint.cursor?.value?.toInt() ?: 0) + 1).toString()) else null
            return DecodedCalendarPage(RemotePage(scope, format, changes, next, !continuePages), deferred, coverage)
        }
        override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? {
            onRetry(); return retryResult
        }
    }

    @Test fun receivedPageSurvivesInterruptedDecodeAndResumesWithoutNetwork() = runBlocking {
        val store = InMemorySyncStore(); val remote = Remote()
        remote.failDecode = true
        try { StagedImportEngine(store, remote, AccountSerialExecutor()).run(scope, true); fail() }
        catch (_: IllegalStateException) { }
        assertNotNull(store.snapshot(scope).received)
        assertNull(store.snapshot(scope).checkpoint)
        remote.failDecode = false
        val result = StagedImportEngine(store, remote, AccountSerialExecutor()).run(scope, false)
        assertTrue(result.traversalComplete)
        assertEquals(1, remote.receives)
        assertEquals(setOf(EventId("before"), EventId("after")), store.snapshot(scope).remote.keys)
        assertEquals(setOf(EventId("incompatible")), store.snapshot(scope).deferred.keys)
        assertNull(store.snapshot(scope).received)
    }

    @Test fun receivedCoverageSurvivesDecodeFailureAndOnlyOfflineApplyCompletesTreatment() = runBlocking {
        val store = InMemorySyncStore()
        val remote = Remote().apply {
            failDecode = true
            coverage = PageCoverage(listOf(CoverageInterval(LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 2, 1), java.time.ZoneOffset.UTC)), 0, true, true)
        }
        try { StagedImportEngine(store, remote, AccountSerialExecutor()).run(scope, true); fail() }
        catch (_: IllegalStateException) { }
        val received = store.snapshot(scope).coverage.active
        assertNotNull("Received progress must survive before decryption", received)
        assertEquals(1, received!!.receivedWindows)
        assertEquals(0, received.appliedWindows)
        assertFalse(received.finished)
        remote.failDecode = false
        assertTrue(StagedImportEngine(store, remote, AccountSerialExecutor()).run(scope, false).traversalComplete)
        val applied = store.snapshot(scope).coverage.active!!
        assertEquals(1, applied.appliedWindows)
        assertTrue(applied.finished)
        assertEquals(1L, applied.windows.single().receivedPages)
        assertEquals(1, remote.receives)
        // Applied envelopes may still include unsupported records and unprojected intents.
        assertEquals(1, store.snapshot(scope).deferred.size)
        assertEquals(2, store.snapshot(scope).projections.size)
    }

    @Test fun decodingCommitFailureKeepsInboxAndCheckpointTogether() = runBlocking {
        val store = InMemorySyncStore(); val remote = Remote().apply { coverage = pageCoverage() }
        store.beforeCommit = { if (it.checkpoint != null) error("storage failure") }
        try { StagedImportEngine(store, remote, AccountSerialExecutor()).run(scope, true); fail() }
        catch (_: IllegalStateException) { }
        val actual = store.snapshot(scope)
        assertNotNull(actual.received); assertNull(actual.checkpoint)
        assertTrue(actual.remote.isEmpty()); assertTrue(actual.deferred.isEmpty())
        assertEquals(1, actual.coverage.active!!.receivedWindows)
        assertEquals(0, actual.coverage.active!!.appliedWindows)
        assertFalse(actual.coverage.active!!.finished)
    }

    @Test fun lateReceiptCannotJoinANewEpochWithTheSameCheckpoint() = runBlocking {
        val store = InMemorySyncStore(); val remote = Remote().apply { coverage = pageCoverage() }
        remote.onReceive = {
            store.seed(scope, store.snapshot(scope).let { it.copy(coverage = it.coverage.restart()) })
        }
        try { StagedImportEngine(store, remote, AccountSerialExecutor()).run(scope, true); fail() }
        catch (_: SyncContractException) { }
        assertEquals(1L, store.snapshot(scope).coverage.epoch)
        assertNull(store.snapshot(scope).received)
        assertNull(store.snapshot(scope).coverage.active)
        assertTrue(store.snapshot(scope).remote.isEmpty())
    }

    @Test fun lateDecodeCannotApplyToANewEpochEvenIfReceiptIsIdentical() = runBlocking {
        val store = InMemorySyncStore(); val remote = Remote().apply { coverage = pageCoverage() }
        remote.onDecode = {
            store.seed(scope, store.snapshot(scope).let { it.copy(coverage = it.coverage.restart()) })
        }
        try { StagedImportEngine(store, remote, AccountSerialExecutor()).run(scope, true); fail() }
        catch (_: SyncContractException) { }
        assertEquals(1L, store.snapshot(scope).coverage.epoch)
        assertNotNull(store.snapshot(scope).received)
        assertNull(store.snapshot(scope).coverage.active)
        assertNull(store.snapshot(scope).checkpoint)
        assertTrue(store.snapshot(scope).remote.isEmpty())
    }

    @Test fun inconsistentDecodedMetadataRetainsReceiptAndDoesNotAdvanceProgress() = runBlocking {
        val store = InMemorySyncStore(); val remote = Remote().apply { coverage = pageCoverage() }
        remote.onDecode = { remote.coverage = null }
        try { StagedImportEngine(store, remote, AccountSerialExecutor()).run(scope, true); fail() }
        catch (_: SyncContractException) { }
        assertNotNull(store.snapshot(scope).received)
        assertEquals(0, store.snapshot(scope).coverage.active!!.appliedWindows)
        assertNull(store.snapshot(scope).checkpoint)
    }

    @Test fun offlineResumeRequiresMigrationOfForeignCoverageFormat() = runBlocking {
        val store = InMemorySyncStore(); val remote = Remote().apply { coverage = pageCoverage(); failDecode = true }
        try { StagedImportEngine(store, remote, AccountSerialExecutor()).run(scope, true); fail() }
        catch (_: IllegalStateException) { }
        val before = store.snapshot(scope)
        store.seed(scope, before.copy(coverage = before.coverage.copy(active =
            before.coverage.active!!.copy(format = AdapterFormat("old-adapter", 1)))))
        remote.failDecode = false
        try { StagedImportEngine(store, remote, AccountSerialExecutor()).run(scope, false); fail("Foreign coverage must not be resumed") }
        catch (_: SyncContractException) { }
        assertNotNull(store.snapshot(scope).received)
        assertNull(store.snapshot(scope).checkpoint)
        assertEquals(0, store.snapshot(scope).coverage.active!!.appliedWindows)
        assertEquals(1, remote.receives)
    }

    @Test fun malformedEnvelopeDoesNotBecomeACompletedPartialImport() = runBlocking {
        val store = InMemorySyncStore(); val remote = Remote()
        remote.changes = listOf(event("duplicate"), event("duplicate"))
        try { StagedImportEngine(store, remote, AccountSerialExecutor()).run(scope, true); fail() }
        catch (_: SyncContractException) { }
        assertNotNull(store.snapshot(scope).received)
        assertNull(store.snapshot(scope).checkpoint)
    }

    @Test fun offlineWithNoReceiptDoesNotFetchOrClaimCompletion() = runBlocking {
        val store = InMemorySyncStore(); val remote = Remote()
        val result = StagedImportEngine(store, remote, AccountSerialExecutor()).run(scope, false)
        assertFalse(result.traversalComplete); assertEquals(0, remote.receives)
    }

    @Test fun successfulRetryClearsOnlyTheExactRetainedVersion() = runBlocking {
        val store = InMemorySyncStore(); val remote = Remote()
        val engine = StagedImportEngine(store, remote, AccountSerialExecutor())
        engine.run(scope, true)
        val original = issue("incompatible")
        remote.retryResult = event("incompatible")
        assertTrue(engine.retryDeferred(scope, original))
        assertTrue(store.snapshot(scope).deferred.isEmpty())
        assertEquals(event("incompatible"), store.snapshot(scope).remote[original.eventId])
        assertFalse(engine.retryDeferred(scope, original))
    }

    @Test fun incomingUnsupportedRevisionDoesNotApplyAnOlderQueuedProjection() = runBlocking {
        val store = InMemorySyncStore(); val remote = Remote()
        val old = event("incompatible")
        store.seed(scope, InMemorySyncStore.State(remote = mutableMapOf(old.eventId to old),
            projections = mutableMapOf(old.eventId to ProjectionIntent(scope, old))))
        StagedImportEngine(store, remote, AccountSerialExecutor()).run(scope, true)
        assertEquals(old, store.snapshot(scope).remote[old.eventId])
        assertFalse(store.snapshot(scope).projections.containsKey(old.eventId))
        assertTrue(store.snapshot(scope).deferred.containsKey(old.eventId))
    }

    @Test fun longTraversalStopsAtFourCommittedPagesAndResumesFromExactCursor() = runBlocking {
        val store = InMemorySyncStore(); val remote = Remote().apply { continuePages = true }
        val engine = StagedImportEngine(store, remote, AccountSerialExecutor())
        val first = engine.run(scope, true)
        assertEquals(4, first.pagesCommitted); assertFalse(first.traversalComplete)
        assertEquals("4", store.snapshot(scope).checkpoint?.cursor?.value)
        assertEquals(4, remote.receives)
        remote.continuePages = false
        assertTrue(engine.run(scope, true).traversalComplete)
        assertEquals(5, remote.receives)
        assertEquals(2, store.snapshot(scope).remote.size)
    }

    @Test fun staleRetryResultCannotEraseNewerDeferredEvidence() = runBlocking {
        val store = InMemorySyncStore(); val remote = Remote()
        val engine = StagedImportEngine(store, remote, AccountSerialExecutor())
        engine.run(scope, true)
        val original = issue("incompatible")
        val newer = original.copy(revision = Revision("two"), protectedPayload = "sealed-new")
        remote.retryResult = event("incompatible")
        remote.onRetry = { store.seed(scope, store.snapshot(scope).also { it.deferred[original.eventId] = newer }) }
        assertFalse(engine.retryDeferred(scope, original))
        assertEquals(newer, store.snapshot(scope).deferred[original.eventId])
        assertNull(store.snapshot(scope).remote[original.eventId])
    }
    @Test fun windowScanCannotResurrectAuthoritativeTombstone() = runBlocking {
        val store = InMemorySyncStore(); val remote = Remote()
        val deleted = RemoteChange.Deleted(EventId("before"), Revision("tombstone"))
        store.seed(scope, InMemorySyncStore.State(remote = mutableMapOf(deleted.eventId to deleted)))
        StagedImportEngine(store, remote, AccountSerialExecutor()).run(scope, true)
        assertEquals(deleted, store.snapshot(scope).remote[deleted.eventId])
        assertFalse(store.snapshot(scope).projections.containsKey(deleted.eventId))
    }
}
