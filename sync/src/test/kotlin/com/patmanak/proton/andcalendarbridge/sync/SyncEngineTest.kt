package com.patmanak.proton.andcalendarbridge.sync

import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SyncEngineTest {
    private val scope = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 1)
    private val format = AdapterFormat("fixture-adapter", 1)
    private val writable = CalendarAccess(CalendarMode.EDITABLE, true, MutationKind.entries.toSet())
    private val store = InMemorySyncStore()
    private val adapter = FixtureAdapter(format)
    private val serial = AccountSerialExecutor()
    private fun engine() = SyncEngine(store, adapter, serial)
    private fun cursor(value: String) = OpaqueCursor(format, value)
    private fun content(title: String) = EventContent(
        title, EventTime.AllDay(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)),
    )
    private fun event(id: String, revision: String = "r1", title: String = id) =
        RemoteChange.Upsert(EventId(id), Revision(revision), content(title))
    private fun page(vararg changes: RemoteChange, next: String? = null) = RemotePage(
        scope, format, changes.toList(), next?.let(::cursor), next == null,
    )
    private fun create(state: MutationState = MutationState.Queued) = Mutation(
        MutationId("local-create"), scope, format, MutationKind.CREATE, content = content("local draft"), state = state,
    )
    private fun update() = Mutation(
        MutationId("local-update"), scope, format, MutationKind.UPDATE,
        EventId("a"), Revision("r1"), content("local edited title"),
    )
    private fun seedMutation(mutation: Mutation = create(), access: CalendarAccess = writable) {
        store.seed(scope, InMemorySyncStore.State(access = access, mutations = mutableMapOf(mutation.id to mutation)))
    }

    @Test fun `bounded import commits progressive pages and resumes from checkpoint`() = runTest {
        adapter.fetcher = { request ->
            when (request.checkpoint.cursor?.value) {
                null -> page(event("a"), next = "page-2")
                "page-2" -> page(event("b"), next = "page-3")
                "page-3" -> page(event("c"), next = "page-4")
                "page-4" -> page(event("d"), next = "page-5")
                "page-5" -> page(event("e"))
                else -> error("Unexpected cursor")
            }
        }
        assertEquals(ImportProgress(4, false), engine().importPages(scope))
        assertEquals(setOf("a", "b", "c", "d"), store.snapshot(scope).projections.keys.map { it.value }.toSet())
        assertEquals("page-5", store.snapshot(scope).checkpoint?.cursor?.value)
        assertTrue(adapter.requests.all { it.limit == 100 })
        assertEquals(ImportProgress(1, true), engine().importPages(scope))
        assertEquals(5, store.snapshot(scope).remote.size)
        assertEquals(ImportProgress(0, true), engine().importPages(scope))
        assertEquals(5, adapter.requests.size)
    }

    @Test fun `overlapping pages deduplicate and an empty final page never deletes records`() = runTest {
        adapter.fetcher = { request -> when (request.checkpoint.cursor?.value) {
            null -> page(event("a"), next = "overlap")
            "overlap" -> page(event("a"), event("b"), next = "empty")
            else -> page()
        } }
        engine().importPages(scope)
        assertEquals(setOf(EventId("a"), EventId("b")), store.snapshot(scope).remote.keys)
        assertEquals(2, store.snapshot(scope).projections.size)
    }

    @Test fun `failure before page commit rolls back records intents and checkpoint together`() = runTest {
        store.seed(scope)
        adapter.fetcher = { page(event("a")) }
        store.beforeCommit = { if (it.checkpoint != null) throw SimulatedCrash() }
        failure<SimulatedCrash> { engine().importPages(scope) }
        with(store.snapshot(scope)) {
            assertNull(checkpoint); assertTrue(remote.isEmpty()); assertTrue(projections.isEmpty())
        }
        store.beforeCommit = null
        engine().importPages(scope)
        assertEquals(2, adapter.requests.size)
        assertEquals(1, store.snapshot(scope).projections.size)
    }

    @Test fun `lost commit acknowledgement resumes after durable page without downloading it twice`() = runTest {
        adapter.fetcher = { request -> if (request.checkpoint.cursor == null) page(event("a"), next = "second") else page(event("b")) }
        store.afterCommit = { if (it.checkpoint?.cursor == cursor("second")) throw SimulatedCrash() }
        failure<SimulatedCrash> { engine().importPages(scope) }
        assertEquals(setOf(EventId("a")), store.snapshot(scope).projections.keys)
        store.afterCommit = null
        engine().importPages(scope)
        assertEquals(listOf(null, cursor("second")), adapter.requests.map { it.checkpoint.cursor })
        assertEquals(2, store.snapshot(scope).projections.size)
    }

    @Test fun `remote refresh preserves pending local content and its base revision`() = runTest {
        val local = update()
        store.seed(scope, InMemorySyncStore.State(
            remote = mutableMapOf(EventId("a") to event("a")),
            mutations = mutableMapOf(local.id to local),
            projections = mutableMapOf(EventId("a") to ProjectionIntent(scope, event("a"))),
        ))
        adapter.fetcher = { page(event("a", "r2", "remote edited title")) }
        engine().importPages(scope)
        assertEquals(local, store.snapshot(scope).mutations[local.id])
        assertEquals(event("a", "r2", "remote edited title"), store.snapshot(scope).remote[EventId("a")])
        assertTrue(store.snapshot(scope).projections.isEmpty())
    }

    @Test fun `only explicit tombstone produces delete intent and pending edits suppress it`() = runTest {
        val deletion = RemoteChange.Deleted(EventId("a"), Revision("r2"))
        adapter.fetcher = { page(deletion) }
        engine().importPages(scope)
        assertEquals(deletion, store.snapshot(scope).projections[EventId("a")]?.change)
        val local = update()
        store.seed(scope, InMemorySyncStore.State(mutations = mutableMapOf(local.id to local)))
        engine().importPages(scope)
        assertEquals(local, store.snapshot(scope).mutations[local.id])
        assertTrue(store.snapshot(scope).projections.isEmpty())
    }

    @Test fun `wrong account calendar generation and format pages commit nothing`() = runTest {
        val invalidPages = listOf(
            page(event("a")).copy(scope = scope.copy(account = AccountId("other-account"))),
            page(event("a")).copy(scope = scope.copy(calendar = CalendarId("other-calendar"))),
            page(event("a")).copy(scope = scope.copy(generation = 2)),
            page(event("a")).copy(format = format.copy(version = 2)),
            page(event("a"), next = "next").copy(nextCursor = OpaqueCursor(format.copy(version = 2), "next")),
        )
        for (invalid in invalidPages) {
            store.seed(scope)
            adapter.fetcher = { invalid }
            failure<SyncContractException> { engine().importPages(scope) }
            assertNull(store.snapshot(scope).checkpoint)
            assertTrue(store.snapshot(scope).remote.isEmpty())
        }
    }

    @Test fun `incompatible saved checkpoint fails before network and preserves pending mutation`() = runTest {
        val local = create()
        store.seed(scope, InMemorySyncStore.State(
            checkpoint = PageCheckpoint(scope, format.copy(version = 2)),
            mutations = mutableMapOf(local.id to local),
        ))
        failure<SyncContractException> { engine().importPages(scope) }
        assertTrue(adapter.requests.isEmpty())
        assertEquals(local, store.snapshot(scope).mutations[local.id])
    }

    @Test fun `stale storage generation is rejected before fetch`() = runTest {
        store.seed(scope)
        failure<SyncContractException> { engine().importPages(scope.copy(generation = 2)) }
        assertTrue(adapter.requests.isEmpty())
    }

    @Test fun `oversized repeated cursor duplicate identity and inconsistent revisions reject entire page`() = runTest {
        val invalidPages = listOf(
            page(event("a"), event("b")) to 1,
            page(event("a"), event("a")) to 100,
            page(event("a")).copy(traversalComplete = false) to 100,
        )
        for ((invalid, pageSize) in invalidPages) {
            store.seed(scope)
            adapter.fetcher = { invalid }
            failure<SyncContractException> { engine().importPages(scope, pageSize = pageSize) }
            assertTrue(store.snapshot(scope).remote.isEmpty())
        }
        store.seed(scope, InMemorySyncStore.State(checkpoint = PageCheckpoint(scope, format, cursor("same"))))
        adapter.fetcher = { page(next = "same") }
        failure<SyncContractException> { engine().importPages(scope) }
        store.seed(scope, InMemorySyncStore.State(remote = mutableMapOf(EventId("a") to event("a"))))
        adapter.fetcher = { page(event("b"), event("a", title = "same revision different content")) }
        failure<SyncContractException> { engine().importPages(scope) }
        assertEquals(setOf(EventId("a")), store.snapshot(scope).remote.keys)
        assertNull(store.snapshot(scope).checkpoint)
    }

    @Test fun `read-only rights and operation capability each independently block upload`() = runTest {
        val cases = listOf(
            writable.copy(mode = CalendarMode.READ_ONLY) to BlockReason.READ_ONLY,
            writable.copy(remoteCanWrite = false) to BlockReason.REMOTE_PERMISSION,
            writable.copy(supportedOperations = emptySet()) to BlockReason.UNSUPPORTED_OPERATION,
        )
        for ((access, expected) in cases) {
            val local = create()
            seedMutation(local, access)
            assertEquals(MutationState.Blocked(expected), engine().dispatchMutation(scope, local.id))
            assertEquals(content("local draft"), store.snapshot(scope).mutations[local.id]?.content)
        }
        assertEquals(0, adapter.dispatches)
    }

    @Test fun `adapter without qualified mutation capabilities never claims or dispatches work`() = runTest {
        var calls = 0
        val unqualified = object : RemoteCalendarAdapter {
            override val format = this@SyncEngineTest.format
            override suspend fun fetch(request: PageRequest): RemotePage = error("No read expected")
            override suspend fun dispatch(mutation: Mutation): DispatchResult {
                calls++
                return DispatchResult.Accepted(mutation.target ?: EventId("created"), Revision("r2"))
            }
        }
        for (local in listOf(create(), update(), update().copy(kind = MutationKind.DELETE, content = null))) {
            store.seed(scope, InMemorySyncStore.State(access = writable,
                remote = mutableMapOf(EventId("a") to event("a")), mutations = mutableMapOf(local.id to local)))
            val expected = local.copy(state = MutationState.Blocked(BlockReason.UNSUPPORTED_OPERATION))
            assertEquals(expected.state, SyncEngine(store, unqualified, serial).dispatchMutation(scope, local.id))
            assertEquals(expected, store.snapshot(scope).mutations[local.id])
            assertEquals(0, calls)
        }
    }

    @Test fun `create capability cannot admit update or deletion against a matching cached base`() = runTest {
        adapter.supportedMutations = setOf(MutationKind.CREATE)
        for (local in listOf(update(), update().copy(kind = MutationKind.DELETE, content = null))) {
            store.seed(scope, InMemorySyncStore.State(access = writable,
                remote = mutableMapOf(EventId("a") to event("a")), mutations = mutableMapOf(local.id to local)))
            store.beforeCommit = { state -> assertFalse(state.mutations.values.any { it.state == MutationState.OutcomeUnknown }) }
            assertEquals(MutationState.Blocked(BlockReason.UNSUPPORTED_OPERATION), engine().dispatchMutation(scope, local.id))
            assertEquals(local.copy(state = MutationState.Blocked(BlockReason.UNSUPPORTED_OPERATION)),
                store.snapshot(scope).mutations[local.id])
            assertEquals(event("a"), store.snapshot(scope).remote[EventId("a")])
        }
        store.beforeCommit = null
        val local = create()
        seedMutation(local)
        adapter.dispatcher = { DispatchResult.Accepted(EventId("created"), Revision("r1")) }
        assertEquals(MutationState.Acknowledged(EventId("created"), Revision("r1")), engine().dispatchMutation(scope, local.id))
        assertEquals(1, adapter.dispatches)
    }

    @Test fun `withdrawn adapter capability blocks admission and restoring it does not replay`() = runTest {
        val local = create()
        seedMutation(local)
        adapter.supportedMutations = emptySet()
        val expected = MutationState.Blocked(BlockReason.UNSUPPORTED_OPERATION)
        assertEquals(expected, engine().dispatchMutation(scope, local.id))
        adapter.supportedMutations = MutationKind.entries.toSet()
        assertEquals(expected, SyncEngine(store, adapter, AccountSerialExecutor()).dispatchMutation(scope, local.id))
        assertEquals(local.copy(state = expected), store.snapshot(scope).mutations[local.id])
        assertEquals(0, adapter.dispatches)
    }

    @Test fun `capability loss cannot reclassify retained unknown or acknowledged work`() = runTest {
        adapter.supportedMutations = emptySet()
        for (state in listOf(MutationState.OutcomeUnknown, MutationState.Acknowledged(EventId("created"), Revision("r1")))) {
            val local = create(state)
            seedMutation(local)
            assertEquals(state, engine().dispatchMutation(scope, local.id))
            assertEquals(local, store.snapshot(scope).mutations[local.id])
        }
        assertEquals(0, adapter.dispatches)
    }

    @Test fun `capability loss after admission cannot make a lost response resendable`() = runTest {
        val local = create()
        seedMutation(local)
        adapter.dispatcher = {
            assertEquals(MutationState.OutcomeUnknown, store.snapshot(scope).mutations[local.id]?.state)
            adapter.supportedMutations = emptySet()
            throw SimulatedCrash()
        }
        failure<SimulatedCrash> { engine().dispatchMutation(scope, local.id) }
        assertEquals(MutationState.OutcomeUnknown, engine().dispatchMutation(scope, local.id))
        adapter.supportedMutations = MutationKind.entries.toSet()
        assertEquals(MutationState.OutcomeUnknown, engine().dispatchMutation(scope, local.id))
        assertEquals(local.copy(state = MutationState.OutcomeUnknown), store.snapshot(scope).mutations[local.id])
        assertEquals(1, adapter.dispatches)
    }

    @Test fun `read-only still imports and re-enabling writes does not auto resume blocked intent`() = runTest {
        val local = create()
        seedMutation(local, CalendarAccess())
        engine().dispatchMutation(scope, local.id)
        adapter.fetcher = { page(event("a")) }
        engine().importPages(scope)
        store.setAccess(scope, writable)
        assertEquals(MutationState.Blocked(BlockReason.READ_ONLY), engine().dispatchMutation(scope, local.id))
        assertEquals(1, store.snapshot(scope).projections.size)
        assertEquals(0, adapter.dispatches)
    }

    @Test fun `remote revision conflict retains both variants without dispatch`() = runTest {
        val local = update()
        seedMutation(local)
        adapter.fetcher = { page(event("a", "r2", "remote variant")) }
        engine().importPages(scope)
        assertEquals(MutationState.Blocked(BlockReason.CONFLICT), engine().dispatchMutation(scope, local.id))
        assertEquals(content("local edited title"), store.snapshot(scope).mutations[local.id]?.content)
        assertEquals(content("remote variant"), (store.snapshot(scope).remote[EventId("a")] as RemoteChange.Upsert).content)
        assertEquals(0, adapter.dispatches)
    }

    @Test fun `permission revoked during fetch is checked freshly before mutation admission`() = runTest {
        val local = create()
        seedMutation(local)
        adapter.fetcher = {
            store.setAccess(scope, writable.copy(remoteCanWrite = false))
            page(event("a"))
        }
        engine().importPages(scope)
        assertEquals(MutationState.Blocked(BlockReason.REMOTE_PERMISSION), engine().dispatchMutation(scope, local.id))
        assertEquals(0, adapter.dispatches)
    }

    @Test fun `update and delete carry base revision to adapter conditional mutation boundary`() = runTest {
        for (kind in listOf(MutationKind.UPDATE, MutationKind.DELETE)) {
            val local = update().copy(kind = kind, content = if (kind == MutationKind.DELETE) null else content("edit"))
            store.seed(scope, InMemorySyncStore.State(
                access = writable,
                remote = mutableMapOf(EventId("a") to event("a")),
                mutations = mutableMapOf(local.id to local),
            ))
            adapter.dispatcher = { dispatched ->
                assertEquals(EventId("a"), dispatched.target)
                assertEquals(Revision("r1"), dispatched.baseRevision)
                DispatchResult.Accepted(EventId("a"), Revision("r2"))
            }
            assertEquals(MutationState.Acknowledged(EventId("a"), Revision("r2")), engine().dispatchMutation(scope, local.id))
        }
        assertEquals(2, adapter.dispatches)
    }

    @Test fun `mismatched acknowledgement leaves target intent unknown`() = runTest {
        val local = update()
        store.seed(scope, InMemorySyncStore.State(
            access = writable, remote = mutableMapOf(EventId("a") to event("a")),
            mutations = mutableMapOf(local.id to local),
        ))
        adapter.dispatcher = { DispatchResult.Accepted(EventId("wrong-target"), Revision("r2")) }
        failure<SyncContractException> { engine().dispatchMutation(scope, local.id) }
        assertEquals(MutationState.OutcomeUnknown, engine().dispatchMutation(scope, local.id))
        assertEquals(1, adapter.dispatches)
    }

    @Test fun `outcome unknown is committed before dispatch and accepted create is not sent again`() = runTest {
        val local = create()
        seedMutation(local)
        adapter.dispatcher = {
            assertEquals(MutationState.OutcomeUnknown, store.snapshot(scope).mutations[local.id]?.state)
            DispatchResult.Accepted(EventId("created-remote-id"), Revision("server-revision"))
        }
        val expected = MutationState.Acknowledged(EventId("created-remote-id"), Revision("server-revision"))
        assertEquals(expected, engine().dispatchMutation(scope, local.id))
        assertEquals(expected, engine().dispatchMutation(scope, local.id))
        assertEquals(1, adapter.dispatches)
    }

    @Test fun `acknowledged create remains pending during import until explicit reconciliation`() = runTest {
        val local = create()
        seedMutation(local)
        adapter.dispatcher = { DispatchResult.Accepted(EventId("created-remote-id"), Revision("r1")) }
        val acknowledged = MutationState.Acknowledged(EventId("created-remote-id"), Revision("r1"))
        assertEquals(acknowledged, engine().dispatchMutation(scope, local.id))
        adapter.fetcher = { page(event("created-remote-id", "r2", "remote changed after creation")) }

        engine().importPages(scope)

        assertEquals(local.copy(state = acknowledged), store.snapshot(scope).mutations[local.id])
        assertEquals(event("created-remote-id", "r2", "remote changed after creation"), store.snapshot(scope).remote[EventId("created-remote-id")])
        assertTrue(store.snapshot(scope).projections.isEmpty())
        assertEquals(1, adapter.dispatches)
    }

    @Test fun `crash between unknown commit and dispatch conservatively blocks replay`() = runTest {
        val local = create()
        seedMutation(local)
        store.afterCommit = { if (it.mutations[local.id]?.state == MutationState.OutcomeUnknown) throw SimulatedCrash() }
        failure<SimulatedCrash> { engine().dispatchMutation(scope, local.id) }
        store.afterCommit = null
        assertEquals(MutationState.OutcomeUnknown, engine().dispatchMutation(scope, local.id))
        assertEquals(0, adapter.dispatches)
    }

    @Test fun `accepted create with lost acknowledgement is never blindly retried`() = runTest {
        val local = create()
        seedMutation(local)
        var serverCreates = 0
        adapter.dispatcher = { serverCreates++; throw SimulatedCrash() }
        failure<SimulatedCrash> { engine().dispatchMutation(scope, local.id) }
        assertEquals(MutationState.OutcomeUnknown, engine().dispatchMutation(scope, local.id))
        assertEquals(1, serverCreates)
        assertEquals(1, adapter.dispatches)
    }

    @Test fun `cancellation during dispatch preserves unknown state`() = runTest {
        val local = create()
        seedMutation(local)
        adapter.dispatcher = { throw CancellationException("fixture cancellation") }
        failure<CancellationException> { engine().dispatchMutation(scope, local.id) }
        assertEquals(MutationState.OutcomeUnknown, engine().dispatchMutation(scope, local.id))
        assertEquals(1, adapter.dispatches)
    }

    @Test fun `commit failure before claim permits later dispatch but never dispatches unpersisted work`() = runTest {
        val local = create()
        seedMutation(local)
        store.beforeCommit = { if (it.mutations[local.id]?.state == MutationState.OutcomeUnknown) throw SimulatedCrash() }
        failure<SimulatedCrash> { engine().dispatchMutation(scope, local.id) }
        assertEquals(0, adapter.dispatches)
        assertEquals(MutationState.Queued, store.snapshot(scope).mutations[local.id]?.state)
        store.beforeCommit = null
        engine().dispatchMutation(scope, local.id)
        assertEquals(1, adapter.dispatches)
    }

    @Test fun `rejected and proven not applied outcomes are retained without immediate retry`() = runTest {
        for ((result, reason) in listOf(DispatchResult.Rejected to BlockReason.REJECTED, DispatchResult.NotApplied to BlockReason.NOT_APPLIED)) {
            val local = create()
            seedMutation(local)
            adapter.dispatcher = { result }
            assertEquals(MutationState.Blocked(reason), engine().dispatchMutation(scope, local.id))
            assertEquals(MutationState.Blocked(reason), engine().dispatchMutation(scope, local.id))
        }
        assertEquals(2, adapter.dispatches)
    }

    @Test fun `mutation format and scope faults stop before dispatch`() = runTest {
        val local = create()
        for (invalid in listOf(local.copy(format = format.copy(version = 2)), local.copy(scope = scope.copy(calendar = CalendarId("other"))))) {
            seedMutation(invalid)
            failure<SyncContractException> { engine().dispatchMutation(scope, invalid.id) }
        }
        assertEquals(0, adapter.dispatches)
    }

    @Test fun `same account serializes different engines while other accounts can progress`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val secondScope = scope.copy(calendar = CalendarId("second-calendar"))
        val otherAccount = scope.copy(account = AccountId("second-account"))
        val fetched = mutableListOf<CalendarScope>()
        adapter.fetcher = { request ->
            val requested = request.checkpoint.scope
            fetched += requested
            if (requested == scope) { entered.complete(Unit); release.await() }
            RemotePage(requested, format, emptyList(), null, true)
        }
        val first = async { engine().importPages(scope) }
        entered.await()
        val second = async { engine().importPages(secondScope) }
        val independent = async { engine().importPages(otherAccount) }
        runCurrent()
        assertEquals(listOf(scope, otherAccount), fetched)
        assertTrue(independent.isCompleted)
        assertFalse(second.isCompleted)
        release.complete(Unit)
        first.await(); second.await(); independent.await()
        assertEquals(listOf(scope, otherAccount, secondScope), fetched)
    }

    private class SimulatedCrash : RuntimeException()

    private suspend inline fun <reified T : Throwable> failure(noinline action: suspend () -> Unit) {
        try { action(); fail("Expected ${T::class.simpleName}") }
        catch (failure: Throwable) { if (failure !is T) throw failure }
    }

    private class FixtureAdapter(override val format: AdapterFormat) : RemoteCalendarAdapter {
        override var supportedMutations: Set<MutationKind> = MutationKind.entries.toSet()
        val requests = mutableListOf<PageRequest>()
        var dispatches = 0
        var fetcher: suspend (PageRequest) -> RemotePage = { error("No fixture page") }
        var dispatcher: suspend (Mutation) -> DispatchResult = { DispatchResult.Unknown }
        override suspend fun fetch(request: PageRequest): RemotePage { requests += request; return fetcher(request) }
        override suspend fun dispatch(mutation: Mutation): DispatchResult { dispatches++; return dispatcher(mutation) }
    }
}
