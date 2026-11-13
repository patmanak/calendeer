// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native mirror lifecycle under the coordinator's original mutex and dynamic epoch guards. */
internal class ImportCoordinatorNativeCalendars(
    private val store: ImportStore,
    private val projection: CalendarProjection,
    private val mutex: Mutex,
    private val generation: () -> Long,
    private val policyRevision: () -> Long,
    private val authenticated: () -> Boolean,
    private val hasCalendarPermissions: () -> Boolean,
    private val systemCreatesEnabled: (CalendarScope) -> Boolean,
    private val isCurrent: suspend (Long, AccountId) -> Boolean,
    private val invalidateAuthentication: () -> Unit,
) {
    suspend fun configure(target: CalendarScope): Boolean = mutex.withLock {
        val expected = generation()
        if (!hasCalendarPermissions()) return@withLock false
        val selected = store.selections(target.account).singleOrNull { it.scope == target }
        val mode = systemCreatesEnabled(target) && selected?.enabled == true && selected.available &&
            isCurrent(expected, target.account)
        val policy = policyRevision()
        projection.admitOperation = { generation() == expected && hasCalendarPermissions() &&
            policyRevision() == policy && (!mode || (authenticated() && systemCreatesEnabled(target))) }
        try {
            if (mode) projection.ensureCalendarState(target, selected!!.summary, true)
            else projection.makeCalendarReadOnly(target)
            true
        }
        finally { projection.admitOperation = { false } }
    }
    suspend fun reconcile() {
        for (target in store.calendarScopes()) configure(target)
    }
    suspend fun disconnect() {
        invalidateAuthentication()
        val expected = generation()
        ImportCalendarDisconnect(store, projection, mutex) { !authenticated() && generation() == expected }.remove()
    }
}
