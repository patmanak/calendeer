package com.patmanak.proton.andcalendarbridge.data.calendar

import android.accounts.Account
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.ProjectionIntent
import com.patmanak.proton.andcalendarbridge.sync.EventId
import com.patmanak.proton.andcalendarbridge.sync.Revision
import com.patmanak.proton.andcalendarbridge.sync.RemoteChange
import com.patmanak.proton.andcalendarbridge.sync.EventContent
import com.patmanak.proton.andcalendarbridge.sync.ProjectionReceipt
import com.patmanak.proton.andcalendarbridge.sync.AppliedProjectionWitness
import com.patmanak.proton.andcalendarbridge.sync.LocalEventId
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary

/** Android-layer boundary. A failed apply leaves its durable intent unacknowledged for replay. */
interface CalendarProjection {
    var admitOperation: () -> Boolean
    fun hasPermissions(): Boolean
    fun accountFor(accountId: AccountId): Account
    suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary): EnsuredCalendar
    suspend fun apply(intent: ProjectionIntent, seriesUid: String? = null)
    /** Null ports use ordinary projection; they cannot qualify recovery of a retained receipt. */
    val projectionReceiptProtocol: Int? get() = null
    /** Write the exact receipt marker and event atomically. Failure leaves preparation unresolved,
     * even when the write might have committed; callers must inspect before acknowledging it.
     */
    suspend fun applyTracked(receipt: ProjectionReceipt): AppliedProjectionWitness = error("Tracked projection unavailable")
    /** Historical atomic write proof, qualified with assertion-only ownership/identity/nonce checks.
     * Null means no proof. Dirty/deleted flags are never consumed by this inspection.
     */
    suspend fun inspectProjectionReceipt(receipt: ProjectionReceipt): AppliedProjectionWitness? = null
    /** Current owned-row witness only. Missing/duplicate/foreign/dirty rows cannot establish association. */
    suspend fun inspectOwnedEvent(scope: CalendarScope, eventId: EventId, revision: Revision): OwnedEventWitness? = null
    /** Snapshot only; no acknowledgement or upload. Unsupported/changed rows are left untouched. */
    suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? = null
    /** Present, dirty local tombstone only; absence never establishes deletion intent. No ACK. */
    suspend fun captureOwnedSimpleDelete(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? = null
    /** One-shot identity stamp after a durable claim; preserves DIRTY and never assigns remote IDs. */
    suspend fun captureAndStampSimpleCreate(scope: CalendarScope, eventRow: Long, localId: LocalEventId): OwnedLocalEditSnapshot? = null
    /** Reopening is marker-only inspection. Missing markers never fall back to a row locator. */
    suspend fun inspectStampedSimpleCreate(scope: CalendarScope, localId: LocalEventId): OwnedLocalEditSnapshot? = null
}

/** Android locator is diagnostic context only and never enters the durable domain intent. */
data class OwnedLocalEditSnapshot(val calendarRow: Long, val eventRow: Long, val content: EventContent) {
    init { require(calendarRow > 0 && eventRow > 0) }
    override fun toString() = "OwnedLocalEditSnapshot(redacted)"
}

/** Historical Android locator, always revalidated before use; never a permanent remote ACK. */
data class OwnedEventWitness(val calendarRow: Long, val eventRow: Long, val revision: Revision) {
    init { require(calendarRow > 0 && eventRow > 0) }
    override fun toString() = "OwnedEventWitness(redacted)"
}
