package com.patmanak.proton.andcalendarbridge.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Share one instance across all engines/triggers in a process. Production needs one process owner. */
class AccountSerialExecutor {
    private val locks = mutableMapOf<AccountId, Mutex>()

    suspend fun <T> run(account: AccountId, action: suspend () -> T): T {
        val lock = synchronized(locks) { locks.getOrPut(account) { Mutex() } }
        return lock.withLock { action() }
    }
}

data class ImportProgress(val pagesCommitted: Int, val traversalComplete: Boolean)

class SyncEngine(
    private val store: SyncStore,
    private val adapter: RemoteCalendarAdapter,
    private val serial: AccountSerialExecutor,
) {
    suspend fun importPages(
        scope: CalendarScope,
        maxPages: Int = 4,
        pageSize: Int = 100,
    ): ImportProgress {
        require(maxPages in 1..4)
        require(pageSize in 1..100)
        return serial.run(scope.account) {
            var committed = 0
            var current = store.transaction(scope) { checkpoint ?: PageCheckpoint(scope, adapter.format) }
            validateCheckpoint(scope, current)
            while (committed < maxPages && !current.traversalComplete) {
                val page = adapter.fetch(PageRequest(current, pageSize))
                validatePage(scope, current, page, pageSize)
                val next = PageCheckpoint(scope, adapter.format, page.nextCursor, page.traversalComplete)
                store.transaction(scope) {
                    val actual = checkpoint ?: PageCheckpoint(scope, adapter.format)
                    if (actual != current) fail("Checkpoint changed during fetch")
                    for (change in page.changes) {
                        val previous = remote(change.eventId)
                        if (previous?.revision == change.revision && previous != change) {
                            fail("Revision was reused for different content")
                        }
                        if (previous != change) {
                            putRemote(change)
                            if (hasPendingMutation(change.eventId)) {
                                removeProjection(change.eventId)
                            } else {
                                putProjection(ProjectionIntent(scope, change))
                            }
                        }
                    }
                    checkpoint = next
                }
                committed++
                current = next
            }
            ImportProgress(committed, current.traversalComplete)
        }
    }

    /**
     * Only Queued is dispatchable. Re-enabling access cannot resume retained blocked work.
     * Reconciliation/retry policy must explicitly resolve blocked/unknown states elsewhere.
     * Exceptions and cancellation after the claim preserve OutcomeUnknown and propagate.
     */
    suspend fun dispatchMutation(scope: CalendarScope, id: MutationId): MutationState =
        serial.run(scope.account) {
            val (claimed, newlyClaimed) = store.transaction(scope) {
                val mutation = mutation(id) ?: fail("Unknown mutation")
                if (mutation.scope != scope) fail("Foreign mutation scope")
                if (mutation.format != adapter.format) fail("Incompatible mutation adapter format")
                if (mutation.state != MutationState.Queued) return@transaction mutation to false
                val reason = when {
                    access.mode != CalendarMode.EDITABLE -> BlockReason.READ_ONLY
                    !access.remoteCanWrite -> BlockReason.REMOTE_PERMISSION
                    mutation.kind !in access.supportedOperations ||
                        mutation.kind !in adapter.supportedMutations -> BlockReason.UNSUPPORTED_OPERATION
                    mutation.target != null && (
                        remote(mutation.target) !is RemoteChange.Upsert ||
                            remote(mutation.target)?.revision != mutation.baseRevision
                        ) -> BlockReason.CONFLICT
                    else -> null
                }
                mutation.copy(
                    state = reason?.let { MutationState.Blocked(it) } ?: MutationState.OutcomeUnknown,
                ).also(::putMutation) to (reason == null)
            }
            // Existing unknown work must never be dispatched. Claim status alone cannot distinguish
            // a newly claimed intent from one left unknown by a previous process.
            if (!newlyClaimed) return@run claimed.state
            dispatchClaimed(scope, claimed)
        }

    private suspend fun dispatchClaimed(scope: CalendarScope, claimed: Mutation): MutationState {
        val result = adapter.dispatch(claimed)
        val resolved = when (result) {
            is DispatchResult.Accepted -> {
                if (claimed.target != null && claimed.target != result.eventId) {
                    fail("Acknowledgement changed mutation target")
                }
                MutationState.Acknowledged(result.eventId, result.revision)
            }
            DispatchResult.Rejected -> MutationState.Blocked(BlockReason.REJECTED)
            DispatchResult.NotApplied -> MutationState.Blocked(BlockReason.NOT_APPLIED)
            DispatchResult.Unknown -> MutationState.OutcomeUnknown
        }
        return store.transaction(scope) {
            if (mutation(claimed.id) != claimed) fail("Mutation changed during dispatch")
            putMutation(claimed.copy(state = resolved))
            resolved
        }
    }

    private fun validateCheckpoint(scope: CalendarScope, checkpoint: PageCheckpoint) {
        if (checkpoint.scope != scope || checkpoint.format != adapter.format ||
            checkpoint.cursor?.format?.let { it != adapter.format } == true
        ) fail("Incompatible checkpoint; explicit migration or reconciliation required")
    }

    private fun validatePage(scope: CalendarScope, current: PageCheckpoint, page: RemotePage, limit: Int) {
        if (page.scope != scope || page.format != adapter.format ||
            page.nextCursor?.format?.let { it != adapter.format } == true
        ) fail("Page scope or adapter format mismatch")
        if (page.changes.size > limit) fail("Adapter exceeded page limit")
        if (page.traversalComplete != (page.nextCursor == null)) fail("Inconsistent page continuation")
        if (!page.traversalComplete && page.nextCursor == current.cursor) fail("Cursor did not advance")
        if (page.changes.map { it.eventId }.distinct().size != page.changes.size) {
            fail("Adapter must fold duplicate event identities within one page")
        }
    }

    private fun fail(message: String): Nothing = throw SyncContractException(message)
}
