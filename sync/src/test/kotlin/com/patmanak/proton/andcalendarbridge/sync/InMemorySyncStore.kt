package com.patmanak.proton.andcalendarbridge.sync

/** Test double only. Copy-on-write simulates atomic boundaries, NOT disk/process durability. */
internal class InMemorySyncStore : SyncStore {
    data class State(
        var checkpoint: PageCheckpoint? = null,
        var access: CalendarAccess = CalendarAccess(),
        val remote: MutableMap<EventId, RemoteChange> = mutableMapOf(),
        val projections: MutableMap<EventId, ProjectionIntent> = mutableMapOf(),
        val mutations: MutableMap<MutationId, Mutation> = mutableMapOf(),
        var received: ReceivedCalendarPage? = null,
        val deferred: MutableMap<EventId, DeferredCalendarEvent> = mutableMapOf(),
        var deltaCursor: OpaqueCursor? = null,
        var deltaReceived: ReceivedChangePage? = null,
        var deltaMore: Boolean = false,
        val jobs: MutableMap<String, CalendarChangeJob> = linkedMapOf(),
        val reconciliations: MutableList<ReceivedChangePage> = mutableListOf(),
        var coverage: CoverageLedger = CoverageLedger(),
    ) {
        fun snapshot() = copy(
            remote = remote.toMutableMap(), projections = projections.toMutableMap(),
            mutations = mutations.toMutableMap(), deferred = deferred.toMutableMap(), jobs = jobs.toMutableMap(),
            reconciliations = reconciliations.toMutableList(),
        )
    }

    private val states = mutableMapOf<CalendarScope, State>()
    var beforeCommit: ((State) -> Unit)? = null
    var afterCommit: ((State) -> Unit)? = null

    fun seed(scope: CalendarScope, state: State = State()) { states[scope] = state.snapshot() }
    fun snapshot(scope: CalendarScope) = states.getValue(scope).snapshot()
    fun setAccess(scope: CalendarScope, access: CalendarAccess) { states.getValue(scope).access = access }

    override suspend fun <T> transaction(scope: CalendarScope, block: SyncStore.Transaction.() -> T): T =
        synchronized(states) {
            if (states.keys.any { it.account == scope.account && it.calendar == scope.calendar && it != scope }) {
                throw SyncContractException("Stale store generation")
            }
            val staged = (states[scope] ?: State()).snapshot()
            val tx = object : DeltaSyncTransaction {
                override var coverage: CoverageLedger
                    get() = staged.coverage
                    set(value) { staged.coverage = value }
                override val reconciliationFormat get() = staged.reconciliations.firstOrNull()?.after?.format
                override fun retainReconciliation(receipt: ReceivedChangePage) {
                    require(receipt.scope == scope)
                    require(reconciliationFormat == null || reconciliationFormat == receipt.after.format)
                    staged.reconciliations.add(receipt)
                }
                override var deltaCursor: OpaqueCursor?
                    get() = staged.deltaCursor
                    set(value) { staged.deltaCursor = value }
                override var deltaReceived: ReceivedChangePage?
                    get() = staged.deltaReceived
                    set(value) { staged.deltaReceived = value }
                override var deltaMore: Boolean
                    get() = staged.deltaMore
                    set(value) { staged.deltaMore = value }
                override fun deltaJobs(limit: Int, blocked: Boolean) = staged.jobs.values.filter { it.blocked == blocked }.take(limit)
                override fun putDeltaJob(job: CalendarChangeJob) { staged.jobs[job.notice.id] = job }
                override fun removeDeltaJob(expected: CalendarChangeJob): Boolean =
                    if (staged.jobs[expected.notice.id] == expected) { staged.jobs.remove(expected.notice.id); true } else false
                override fun clearBlockedEvent(eventId: EventId) { staged.jobs.entries.removeAll { it.value.blocked && it.value.notice.eventId == eventId } }
                override fun retryBlockedJobs() { staged.jobs.replaceAll { _, job -> job.copy(blocked = false) } }
                override var received: ReceivedCalendarPage?
                    get() = staged.received
                    set(value) { staged.received = value }
                override fun deferred(eventId: EventId) = staged.deferred[eventId]
                override fun defer(event: DeferredCalendarEvent) { staged.deferred[event.eventId] = event }
                override fun clearDeferred(eventId: EventId) { staged.deferred.remove(eventId) }
                override var checkpoint: PageCheckpoint?
                    get() = staged.checkpoint
                    set(value) {
                        require(value == null || value.scope == scope)
                        staged.checkpoint = value
                    }
                override val access: CalendarAccess get() = staged.access
                override fun remote(eventId: EventId) = staged.remote[eventId]
                override fun putRemote(change: RemoteChange) { staged.remote[change.eventId] = change }
                override fun hasPendingMutation(eventId: EventId) =
                    staged.mutations.values.any {
                        it.target == eventId || (it.state as? MutationState.Acknowledged)?.eventId == eventId
                    }
                override fun putProjection(intent: ProjectionIntent) {
                    require(intent.scope == scope)
                    staged.projections[intent.change.eventId] = intent
                }
                override fun removeProjection(eventId: EventId) { staged.projections.remove(eventId) }
                override fun mutation(id: MutationId) = staged.mutations[id]
                override fun putMutation(mutation: Mutation) {
                    require(mutation.scope == scope)
                    staged.mutations[mutation.id] = mutation
                }
            }
            val result = tx.block()
            beforeCommit?.invoke(staged)
            states[scope] = staged
            afterCommit?.invoke(staged)
            result
        }
}
