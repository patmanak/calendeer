// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.data.importing.RetainedLocalChangeListing
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Coordinator-owned admission. Discovery never grants permission to prepare or send. */
interface SystemTitleUpdateOperations {
    suspend fun discover(query: RetainedLocalChangeQuery): RetainedLocalChangeListing
    suspend fun process(scope: CalendarScope, reference: RetainedLocalChangeRef, attempt: MutationId,
        timestamp: Instant, admission: (CalendarScope) -> Boolean, proofRetained: () -> Unit): Boolean
    suspend fun inspect(query: TitleUpdateWorkQuery, admission: (CalendarScope) -> Boolean,
        proofRetained: () -> Unit): TitleUpdateOfflineRecoveryProgress
}

/** Two bounded local indexes. Network work requires explicit opt-in and the original worker epoch. */
class SystemUpdateController(
    private val changes: TitleRecoveryCursorStore,
    private val attempts: TitleRecoveryCursorStore,
    private val operations: SystemTitleUpdateOperations,
    private val wakeProof: suspend (CalendarScope) -> Unit,
    private val pageSize: Int = 25,
    private val mutationKind: MutationKind = MutationKind.UPDATE,
) {
    private val mutex = Mutex()
    init {
        require(pageSize in 1..TitleUpdateWorkQuery.MAX_PAGE_SIZE)
        require(mutationKind in setOf(MutationKind.UPDATE, MutationKind.DELETE))
    }

    suspend fun reset(target: CalendarScope) = mutex.withLock {
        currentCoroutineContext().ensureActive()
        changes.write(target, null)
        currentCoroutineContext().ensureActive()
        attempts.write(target, null)
        currentCoroutineContext().ensureActive()
    }

    /** Rearm only local discovery before insertion, including identities preceding its cursor.
     * Existing held owners must not call this: their native DIRTY rows are scanned again.
     * Inspection exhaustion and durable request ownership remain unchanged.
     */
    suspend fun localOwnerWillBeCaptured(target: CalendarScope) = mutex.withLock {
        currentCoroutineContext().ensureActive()
        changes.write(target, null)
        currentCoroutineContext().ensureActive()
    }

    suspend fun run(target: CalendarScope, network: Boolean, fresh: Boolean = true,
        admission: () -> Boolean): SystemTitleRecoveryProgress = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!network) return@withLock SystemTitleRecoveryProgress()
        // One invocation owns this lease. Observing revocation is irreversible, and a
        // callback retained by an adapter cannot outlive this run or borrow another scope.
        var open = true
        var proof = false
        fun active(): Boolean {
            if (open && !admission()) open = false
            return open
        }
        val admit: (CalendarScope) -> Boolean = { it == target && active() }
        val proved: () -> Unit = { proof = true }
        try {
            if (!active()) {
                changes.write(target, null)
                currentCoroutineContext().ensureActive()
                attempts.write(target, null)
                currentCoroutineContext().ensureActive()
                return@withLock SystemTitleRecoveryProgress()
            }
            val inspection = SystemTitleRecoveryController(attempts, { query ->
                operations.inspect(query, admit, proved)
            }, pageSize).run(target, fresh, ::active)
            currentCoroutineContext().ensureActive()
            val discovery = if (active()) SystemTitleRecoveryController(changes, { query ->
                val localQuery = RetainedLocalChangeQuery(target, query.limit,
                    query.cursor?.let { RetainedLocalChangeCursor(it.scope, it.afterEvent, it.protocol) })
                val listing = operations.discover(localQuery)
                currentCoroutineContext().ensureActive()
                if (!active()) TitleUpdateOfflineRecoveryProgress(inactive = true)
                else when (listing) {
                    RetainedLocalChangeListing.Inactive -> TitleUpdateOfflineRecoveryProgress(inactive = true)
                    RetainedLocalChangeListing.Unavailable, RetainedLocalChangeListing.Unqualified ->
                        TitleUpdateOfflineRecoveryProgress(failed = 1)
                    is RetainedLocalChangeListing.Available -> {
                        val page = listing.page
                        val next = page.next
                        check(page.scope == target && page.entries.size <= query.limit)
                        RetainedLocalChangeQuery(target, query.limit, next)
                        check(next == null || (page.entries.isNotEmpty() &&
                            next.afterEvent == page.entries.last().eventId))
                        var failed = 0
                        for (reference in page.entries) {
                            if (!active()) break
                            if (reference.kind != mutationKind || reference.state != LocalEditState.PAUSED) continue
                            try {
                                if (operations.process(target, reference, MutationId(UUID.randomUUID().toString()),
                                        Instant.now().truncatedTo(ChronoUnit.SECONDS), admit, proved)) failed++
                            } catch (cancelled: CancellationException) { throw cancelled }
                              catch (_: Exception) {
                                  currentCoroutineContext().ensureActive()
                                  failed++
                              }
                            currentCoroutineContext().ensureActive()
                        }
                        TitleUpdateOfflineRecoveryProgress(failed = failed, inactive = !active(),
                            next = next?.let { TitleUpdateWorkCursor(it.scope, it.afterEvent, it.protocol) })
                    }
                }
            }, pageSize).run(target, fresh, ::active) else SystemTitleRecoveryProgress()
            currentCoroutineContext().ensureActive()
            // A new durable result may arrive after the offline traversal has exhausted.
            // Wake once for proof, never for a held/uncertain owner or a failed inspection.
            if (proof) {
                wakeProof(target)
                currentCoroutineContext().ensureActive()
            }
            SystemTitleRecoveryProgress(more = proof || (active() && (inspection.more || discovery.more)),
                failed = inspection.failed || discovery.failed)
        } finally { open = false }
    }
}
