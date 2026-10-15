// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.TitleUpdateOfflineRecoveryProgress
import com.patmanak.proton.andcalendarbridge.sync.TitleUpdateWorkCursor
import com.patmanak.proton.andcalendarbridge.sync.TitleUpdateWorkQuery
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Local traversal position and exhaustion only; no ownership or submission authority.
 * Writes/reset clear exhaustion; marking exhaustion atomically removes the old cursor.
 */
interface TitleRecoveryCursorStore {
    suspend fun read(scope: CalendarScope): TitleUpdateWorkCursor?
    suspend fun write(scope: CalendarScope, cursor: TitleUpdateWorkCursor?)
    suspend fun exhausted(scope: CalendarScope): Boolean
    suspend fun markExhausted(scope: CalendarScope)
}

data class SystemTitleRecoveryProgress(val more: Boolean = false, val failed: Boolean = false)

/**
 * Bounded offline discovery. The recovery callback owns all acknowledgement admission.
 * The only continuation is a durably retained, current page position. Unresolved or corrupt
 * entries remain in the journal and are retried by a later ordinary trigger, not a busy loop.
 * This controller has no remote inspection, preparation, claim or submission capability.
 */
class SystemTitleRecoveryController(
    private val cursors: TitleRecoveryCursorStore,
    private val recover: suspend (TitleUpdateWorkQuery) -> TitleUpdateOfflineRecoveryProgress,
    private val pageSize: Int = 25,
    private val guardedRecover: (suspend (TitleUpdateWorkQuery, () -> Boolean) -> TitleUpdateOfflineRecoveryProgress)? = null,
) {
    private val mutex = Mutex()

    init { require(pageSize in 1..TitleUpdateWorkQuery.MAX_PAGE_SIZE) { "Invalid title recovery page size" } }

    suspend fun reset(target: CalendarScope) = mutex.withLock {
        currentCoroutineContext().ensureActive()
        cursors.write(target, null)
        currentCoroutineContext().ensureActive()
    }

    suspend fun run(target: CalendarScope, fresh: Boolean = true,
        admit: () -> Boolean): SystemTitleRecoveryProgress = mutex.withLock {
        val operation = currentCoroutineContext()
        val closed = AtomicBoolean(false)
        fun active(): Boolean {
            if (closed.get()) return false
            operation.ensureActive()
            val allowed = admit()
            operation.ensureActive()
            if (!allowed) closed.set(true)
            return allowed && !closed.get()
        }
        try {
            currentCoroutineContext().ensureActive()
            if (!active()) return@withLock discard(target)
            val exhausted = cursors.exhausted(target)
            currentCoroutineContext().ensureActive()
            if (!active()) return@withLock discard(target)
            if (exhausted) {
                if (!fresh) return@withLock SystemTitleRecoveryProgress()
                discard(target)
                if (!active()) return@withLock SystemTitleRecoveryProgress()
            }
            val cursor = cursors.read(target)
            currentCoroutineContext().ensureActive()
            if (!active()) return@withLock discard(target)
            val query = TitleUpdateWorkQuery(target, pageSize, cursor)
            // The optional path forwards this invocation's original authority through every
            // ACK boundary. Cursor IO must never acquire a newer worker/policy epoch.
            val progress = guardedRecover?.invoke(query, ::active) ?: recover(query)
            currentCoroutineContext().ensureActive()
            if (!active() || progress.inactive) return@withLock discard(target)
            val next = progress.next
            // Do not compare opaque identifiers using JVM ordering; SQL owns index order.
            TitleUpdateWorkQuery(target, pageSize, next)
            check(next == null || next != cursor) { "Title recovery cursor did not advance" }
            if (next == null) cursors.markExhausted(target) else cursors.write(target, next)
            currentCoroutineContext().ensureActive()
            if (!active()) return@withLock discard(target)
            SystemTitleRecoveryProgress(more = next != null, failed = progress.failed > 0)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
              currentCoroutineContext().ensureActive()
              // ACK may already be durable. A checkpoint failure grants no new work or retry.
              try {
                  cursors.markExhausted(target)
                  currentCoroutineContext().ensureActive()
              }
              catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { currentCoroutineContext().ensureActive() }
              SystemTitleRecoveryProgress(failed = true)
          } finally { closed.set(true) }
    }

    private suspend fun discard(target: CalendarScope): SystemTitleRecoveryProgress {
        currentCoroutineContext().ensureActive()
        cursors.write(target, null)
        currentCoroutineContext().ensureActive()
        return SystemTitleRecoveryProgress()
    }
}
