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
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateAttachmentReceipt
import com.patmanak.proton.andcalendarbridge.sync.AppliedCapturedCreateAttachment
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateBaseline
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateCurrentObservation
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateAcknowledgementReceipt
import com.patmanak.proton.andcalendarbridge.sync.AppliedCapturedCreateAcknowledgement
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateCleanWitness
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateHandoffFrame
import com.patmanak.proton.andcalendarbridge.sync.CapturedHandoffCleanWitness
import com.patmanak.proton.andcalendarbridge.sync.CapturedHandoffCurrentObservation
import com.patmanak.proton.andcalendarbridge.sync.CapturedHandoffProjection
import com.patmanak.proton.andcalendarbridge.sync.AppliedCapturedHandoffProjection
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary

/** Android-layer boundary. A failed apply leaves its durable intent unacknowledged for replay. */
interface CalendarProjection {
    val capturedHandoffProtocol: Int? get() = null
    /** Assert the current clean reference and full origin in one batch; no future write lease. */
    suspend fun inspectCapturedHandoffBase(frame: CapturedCreateHandoffFrame): CapturedHandoffCleanWitness? = null
    /** Exact current dirty snapshot, including a present tombstone; no deletion from absence. */
    suspend fun captureCapturedHandoffCurrent(frame: CapturedCreateHandoffFrame): CapturedHandoffCurrentObservation =
        CapturedHandoffCurrentObservation.Unresolved
    /** Conditional atomic update of the existing unique row. Never insert or erase origin markers. */
    suspend fun applyCapturedHandoffProjection(plan: CapturedHandoffProjection): AppliedCapturedHandoffProjection? = null
    /** Historical marker proof only. Ignore and preserve subsequent local content and dirty flags. */
    suspend fun inspectCapturedHandoffProjection(plan: CapturedHandoffProjection): AppliedCapturedHandoffProjection? = null
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
    val capturedAttachmentProtocol: Int? get() = null
    /** Metadata-only attachment to the unique scoped local marker. Preserve content, children and
     * dirty/deleted flags. No row-ID fallback, insertion, acknowledgement or import release.
     * After an uncertain write callers must inspect the same durable receipt before retrying.
     */
    suspend fun attachCapturedCreate(receipt: CapturedCreateAttachmentReceipt): AppliedCapturedCreateAttachment? = null
    /** Historical exact metadata proof only, even if the row was subsequently edited or deleted. */
    suspend fun inspectCapturedAttachment(receipt: CapturedCreateAttachmentReceipt): AppliedCapturedCreateAttachment? = null
    /** Exact attached dirty-row snapshot, including a present local tombstone. Assertion-only;
     * never acknowledge Android, infer deletion from absence or release incoming projections.
     */
    suspend fun captureCapturedCreateCurrent(baseline: CapturedCreateBaseline): CapturedCreateCurrentObservation =
        CapturedCreateCurrentObservation.Unresolved
    val capturedAcknowledgementProtocol: Int? get() = null
    /** Conditional atomic acknowledgement of the exact unchanged attached original. Only DIRTY
     * and the acknowledgement marker can change. A lost response must be recovered by inspection.
     */
    suspend fun acknowledgeCapturedCreate(receipt: CapturedCreateAcknowledgementReceipt): AppliedCapturedCreateAcknowledgement? = null
    /** Historical atomic acknowledgement proof. Later local edits/deletions/children do not
     * invalidate that history and are never consumed by inspection; this is not current-clean proof.
     */
    suspend fun inspectCapturedAcknowledgement(receipt: CapturedCreateAcknowledgementReceipt): AppliedCapturedCreateAcknowledgement? = null
    /** Assertion-only current clean original: full acknowledgement provenance, unique identity,
     * unchanged simple content, clean/non-deleted flags and absent children in one atomic batch.
     * Historical ACK alone is insufficient. This observation is not a lease or import release.
     */
    suspend fun inspectAcknowledgedCreateClean(receipt: CapturedCreateAcknowledgementReceipt): CapturedCreateCleanWitness? = null
    /** Capture subsequent dirty content through the exact historical acknowledgement marker.
     * No new acknowledgement, incoming release, or deletion inference from an absent row.
     */
    suspend fun captureAcknowledgedCreateCurrent(receipt: CapturedCreateAcknowledgementReceipt): CapturedCreateCurrentObservation =
        CapturedCreateCurrentObservation.Unresolved
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
