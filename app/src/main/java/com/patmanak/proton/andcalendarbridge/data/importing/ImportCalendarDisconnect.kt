// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit local removal, serialized against projection and uploads. SQL history is retained. */
internal class ImportCalendarDisconnect(
    private val store: ImportStore,
    private val projection: CalendarProjection,
    private val mutex: Mutex,
    private val disconnected: () -> Boolean,
    private val pendingChanged: (Boolean) -> Unit = {},
) {
    suspend fun remove(): Boolean = mutex.withLock {
        val operation = currentCoroutineContext()
        val active = { operation.ensureActive(); disconnected() }
        check(active())
        // Commit the obligation before any permission-dependent native operation.
        store.queueNativeRemovals()
        pendingChanged(true)
        drainLocked(active)
    }

    /** Caller already holds the coordinator mutex. Retrying a removed mirror is idempotent. */
    suspend fun drainLocked(admitted: () -> Boolean): Boolean {
        val operation = currentCoroutineContext()
        val active = { operation.ensureActive(); admitted() }
        check(active())
        val pending = store.pendingNativeRemovals()
        check(active())
        pendingChanged(pending.isNotEmpty())
        if (pending.isEmpty()) return true
        if (!projection.hasPermissions()) return false
        projection.admitOperation = { active() && projection.hasPermissions() }
        try {
            for (target in pending) {
                check(active())
                if (!projection.hasPermissions()) return false
                projection.removeOwnedCalendar(target)
                check(active())
                // Never acknowledge a native failure or a superseded epoch.
                store.completeNativeRemoval(target)
                check(active())
            }
            val remains = store.pendingNativeRemovals().isNotEmpty()
            check(active())
            pendingChanged(remains)
            return !remains
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { pendingChanged(true); return false }
          finally { projection.admitOperation = { false } }
    }
}
