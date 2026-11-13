// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit local removal, serialized against projection and uploads. SQL history is retained. */
internal class ImportCalendarDisconnect(
    private val store: ImportStore,
    private val projection: CalendarProjection,
    private val mutex: Mutex,
    private val disconnected: () -> Boolean,
) {
    suspend fun remove() = mutex.withLock {
        val operation = currentCoroutineContext()
        val active = { operation.ensureActive(); disconnected() }
        check(active())
        check(projection.hasPermissions()) { "Calendar permission is required to remove local calendars" }
        projection.admitOperation = active
        try {
            for (target in store.calendarScopes()) {
                check(active())
                projection.removeOwnedCalendar(target)
                check(active())
            }
        } finally { projection.admitOperation = { false } }
    }
}
