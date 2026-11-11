// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.ReadOnlyCalendarRemote
import com.patmanak.proton.andcalendarbridge.sync.catalog.*

/** Revalidate catalogue access without discarding retained work on a failed observation. */
internal suspend fun refreshImportAccess(remote: ReadOnlyCalendarRemote, store: ImportStore,
    account: AccountId, current: suspend () -> Boolean,
    remember: suspend (AccountId, List<CalendarSummary>) -> Unit): ImportRunResult? {
    val catalogPort = remote as? CalendarCatalogPort ?: return null
    val enabled = store.selections(account).filter { it.enabled && it.available }
    if (enabled.isNotEmpty() && enabled.all { it.nextAttempt > System.currentTimeMillis() })
        return ImportRunResult.RETRY
    when (val catalog = catalogPort.load()) {
        is CalendarCatalogResult.Success -> {
            if (catalog.account != account || !current()) return ImportRunResult.COMPLETE
            remember(account, catalog.calendars)
        }
        is CalendarCatalogResult.Failure -> {
            val status = when (catalog.category) {
                CalendarCatalogFailure.AUTHENTICATION_REQUIRED -> "AUTH"
                CalendarCatalogFailure.UNSUPPORTED_RESPONSE, CalendarCatalogFailure.FORBIDDEN -> "UNSUPPORTED"
                else -> "NETWORK"
            }
            for (item in store.selections(account).filter { it.enabled && it.available }) {
                store.status(item.scope, status)
                catalog.retryAfterMillis?.let { store.deferNetwork(item.scope, it) }
            }
            return ImportRunResult.RETRY
        }
    }
    return null
}
