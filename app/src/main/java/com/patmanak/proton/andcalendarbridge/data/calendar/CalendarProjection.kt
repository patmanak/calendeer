package com.patmanak.proton.andcalendarbridge.data.calendar

import com.patmanak.proton.andcalendarbridge.sync.DeleteAcknowledgementReceipt
import com.patmanak.proton.andcalendarbridge.sync.AppliedDeleteAcknowledgement
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
import com.patmanak.proton.andcalendarbridge.sync.LocalCreateIntent
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
import com.patmanak.proton.andcalendarbridge.sync.TitleUpdateAcknowledgementReceipt
import com.patmanak.proton.andcalendarbridge.sync.AppliedTitleUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.sync.TextUpdateAcknowledgementReceipt
import com.patmanak.proton.andcalendarbridge.sync.AppliedTextUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.sync.PersonalUpdateAcknowledgementReceipt
import com.patmanak.proton.andcalendarbridge.sync.AppliedPersonalUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.sync.TimeUpdateAcknowledgementReceipt
import com.patmanak.proton.andcalendarbridge.sync.AppliedTimeUpdateAcknowledgement
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary

/** Android-layer boundary. A failed apply leaves its durable intent unacknowledged for replay. */
interface CalendarProjection {
    /** Local representation only; never capture/send edits. Pages include skipped rows. */
    suspend fun reprojectEventColors(scope: CalendarScope, afterRow: Long = 0, limit: Int = 100): ColorProjectionPage =
        ColorProjectionPage(afterRow, false)
    val deleteAcknowledgementProtocol: Int? get() = null
    /** Metadata-only ACK of one exact ordinary dirty tombstone. Keep DELETED and base revision.
     * Caller must first reserve this receipt durably. Never purge or insert.
     */
    suspend fun acknowledgeDelete(receipt: DeleteAcknowledgementReceipt): AppliedDeleteAcknowledgement? = null
    /** Historical marker/origin proof only; preserve later edits, flags and children. */
    suspend fun inspectDeleteAcknowledgement(receipt: DeleteAcknowledgementReceipt): AppliedDeleteAcknowledgement? = null
    val personalUpdateAcknowledgementProtocol: Int? get() = null
    /** Exact personal raw snapshot ACK; preserve time, metadata and reminder row identities. */
    suspend fun acknowledgePersonalUpdate(receipt: PersonalUpdateAcknowledgementReceipt): AppliedPersonalUpdateAcknowledgement? = null
    /** Historical proof only; preserve later edits and children. */
    suspend fun inspectPersonalUpdateAcknowledgement(receipt: PersonalUpdateAcknowledgementReceipt): AppliedPersonalUpdateAcknowledgement? = null
    /** Separate capability: inspect first, then conditionally finish an interrupted
     * pending ACK using the same nonce and exact original raw native contents.
     * It never inserts, overwrites a newer edit or enters a transport facade.
     */
    val personalUpdateAcknowledgementRecoveryProtocol: Int? get() = null
    suspend fun resumePersonalUpdateAcknowledgement(receipt: PersonalUpdateAcknowledgementReceipt): AppliedPersonalUpdateAcknowledgement? = null
    val timeUpdateAcknowledgementProtocol: Int? get() = null
    /** Assert the exact raw native temporal edit and origin before clearing DIRTY on the same row.
     * Retain the desired time; only verified CRLF/LF text normalization and metadata may change.
     */
    suspend fun acknowledgeTimeUpdate(receipt: TimeUpdateAcknowledgementReceipt): AppliedTimeUpdateAcknowledgement? = null
    /** Historical acknowledgement proof only; preserve later content, deletion and children. */
    suspend fun inspectTimeUpdateAcknowledgement(receipt: TimeUpdateAcknowledgementReceipt): AppliedTimeUpdateAcknowledgement? = null
    val textUpdateAcknowledgementProtocol: Int? get() = null
    /** Assert the full raw native edit before same-row acknowledgement. Only verified CRLF/LF
     * text differences and acknowledgement metadata may change; CREATE requires explicit origin.
     */
    suspend fun acknowledgeTextUpdate(receipt: TextUpdateAcknowledgementReceipt): AppliedTextUpdateAcknowledgement? = null
    /** Historical metadata proof only; never consume subsequent native edits or children. */
    suspend fun inspectTextUpdateAcknowledgement(receipt: TextUpdateAcknowledgementReceipt): AppliedTextUpdateAcknowledgement? = null
    val titleUpdateAcknowledgementProtocol: Int? get() = null
    /** Atomic metadata-only ACK of one exact dirty edit, with explicit CREATE handoff when present.
     * Preserve creation provenance; never rewrite content, insert or infer handoff from row markers.
     */
    suspend fun acknowledgeTitleUpdate(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? = null
    /** Historical marker proof only; preserve every subsequent local edit, tombstone and child. */
    suspend fun inspectTitleUpdateAcknowledgement(receipt: TitleUpdateAcknowledgementReceipt): AppliedTitleUpdateAcknowledgement? = null
    /** Native lookup key only; never a remote identifier or ownership/capture proof. */
    fun eventIdentityKey(scope: CalendarScope, event: EventId): String? = null
    /** Discovery key only; complete durable provenance and native assertions remain mandatory. */
    fun capturedIdentityKey(scope: CalendarScope, localId: LocalEventId): String? = null
    suspend fun inspectCapturedIdentityRow(scope: CalendarScope, localId: LocalEventId, row: Long): Boolean = false
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
    suspend fun ensureCalendarState(scope: CalendarScope, summary: CalendarSummary, systemCreates: Boolean): EnsuredCalendar {
        check(!systemCreates) { "System creation unavailable" }
        return ensureCalendarState(scope, summary)
    }
    suspend fun discoverSystemChanges(scope: CalendarScope, afterRow: Long = 0, limit: Int = 25): SystemCalendarChangePage? = null
    /** Existing owned mirror only; closing access must never create a calendar or account. */
    suspend fun makeCalendarReadOnly(scope: CalendarScope) { error("Access restriction unavailable") }
    /** Explicit confirmed sign-out only; preserve all unrelated calendars. Idempotent. */
    suspend fun removeOwnedCalendar(scope: CalendarScope) { error("Calendar removal unavailable") }
    suspend fun apply(intent: ProjectionIntent, seriesUid: String? = null)
    /** Assertion-only observation of dirty/deleted owned rows affected by this projection,
     * including recurrence dependencies. Never grants ownership, a write lease or acknowledgement. */
    suspend fun hasOwnedLocalChanges(intent: ProjectionIntent, deletedSeriesUid: String? = null): Boolean = false
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
    /** Explicit first insertion only: saved finite master, absent event/series/children,
     * and no previous base. No replacement or ordinary import capability is implied.
     */
    suspend fun insertFiniteProjection(receipt: ProjectionReceipt): AppliedProjectionWitness = error("Finite insertion unavailable")
    /** Historical first-insertion proof only. Preserve later dirty/deleted content;
     * exact canonical/reference restoration remains the durable store's responsibility.
     */
    suspend fun inspectFiniteInsertion(receipt: ProjectionReceipt): AppliedProjectionWitness? = null
    /** Current owned-row witness only. Missing/duplicate/foreign/dirty rows cannot establish association. */
    suspend fun inspectOwnedEvent(scope: CalendarScope, eventId: EventId, revision: Revision): OwnedEventWitness? = null
    /** Snapshot only; no acknowledgement or upload. Unsupported/changed rows are left untouched. */
    suspend fun captureOwnedSimpleEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? = null
    /** Known personal metadata and exact unchanged reminders only; assertion-only observation. */
    suspend fun captureOwnedPersonalEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? = null
    /** Explicit master-series observation only; no remote family proof, preparation or ACK. */
    suspend fun captureOwnedSeriesEdit(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? = null
    /** Inactive first-occurrence observation only; no durable claim, preparation, upload or ACK. */
    suspend fun captureOwnedOccurrenceEdit(scope: CalendarScope, master: RemoteChange.Upsert,
        childRow: Long): OwnedOccurrenceEditSnapshot? = null
    suspend fun captureAndStampOccurrence(intent: LocalCreateIntent, childRow: Long): OwnedOccurrenceEditSnapshot? = null
    suspend fun inspectStampedOccurrence(intent: LocalCreateIntent): OwnedOccurrenceEditSnapshot? = null
    /** Present, dirty local tombstone only; absence never establishes deletion intent. No ACK. */
    suspend fun captureOwnedSimpleDelete(scope: CalendarScope, base: RemoteChange.Upsert): OwnedLocalEditSnapshot? = null
    /** One-shot identity stamp after a durable claim; preserves DIRTY and never assigns remote IDs. */
    /** Selection only; the chosen reader still qualifies and atomically stamps the full snapshot. */
    suspend fun localCreateProfile(scope: CalendarScope, eventRow: Long): LocalCreateProfile? = null
    suspend fun captureAndStampSimpleCreate(scope: CalendarScope, eventRow: Long, localId: LocalEventId): OwnedLocalEditSnapshot? = null
    /** Reopening is marker-only inspection. Missing markers never fall back to a row locator. */
    suspend fun inspectStampedSimpleCreate(scope: CalendarScope, localId: LocalEventId): OwnedLocalEditSnapshot? = null
    /** Explicit series observation only; neither port enables preparation, sending or acknowledgement. */
    suspend fun captureAndStampSeriesCreate(scope: CalendarScope, eventRow: Long, localId: LocalEventId): OwnedLocalEditSnapshot? = null
    suspend fun inspectStampedSeriesCreate(scope: CalendarScope, localId: LocalEventId): OwnedLocalEditSnapshot? = null
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
data class OwnedLocalEditSnapshot(val calendarRow: Long, val eventRow: Long, val content: EventContent,
    val series: com.patmanak.proton.andcalendarbridge.sync.CreateSeriesRule? = null) {
    init { require(calendarRow > 0 && eventRow > 0 && series?.supports(content.time) != false) }
    override fun toString() = "OwnedLocalEditSnapshot(redacted)"
}

/** Historical Android locator, always revalidated before use; never a permanent remote ACK. */
data class OwnedEventWitness(val calendarRow: Long, val eventRow: Long, val revision: Revision) {
    init { require(calendarRow > 0 && eventRow > 0) }
    override fun toString() = "OwnedEventWitness(redacted)"
}
