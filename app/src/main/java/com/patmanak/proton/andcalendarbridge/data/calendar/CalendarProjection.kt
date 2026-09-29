package com.patmanak.proton.andcalendarbridge.data.calendar

import android.accounts.Account
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.ProjectionIntent
import com.patmanak.proton.andcalendarbridge.sync.EventId
import com.patmanak.proton.andcalendarbridge.sync.Revision
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary

/** Android-layer boundary. A failed apply leaves its durable intent unacknowledged for replay. */
interface CalendarProjection {
    var admitOperation: () -> Boolean
    fun hasPermissions(): Boolean
    fun accountFor(accountId: AccountId): Account
    suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar
    suspend fun apply(intent: ProjectionIntent, seriesUid: String? = null)
    /** Current owned-row witness only. Missing/duplicate/foreign/dirty rows cannot establish association. */
    suspend fun inspectOwnedEvent(scope: CalendarScope, eventId: EventId, revision: Revision): OwnedEventWitness? = null
}

/** Historical Android locator, always revalidated before use; never a permanent remote ACK. */
data class OwnedEventWitness(val calendarRow: Long, val eventRow: Long, val revision: Revision) {
    init { require(calendarRow > 0 && eventRow > 0) }
    override fun toString() = "OwnedEventWitness(redacted)"
}
