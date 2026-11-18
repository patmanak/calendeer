// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import android.content.ContentValues
import android.content.ContentProviderOperation
import android.provider.CalendarContract.Events
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.OWNER_MARKER
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.digest

/** All operations use the same stamped-row, parent, slot, reminder and family assertion batch. */
enum class OccurrenceReceiptAction { ATTACH, INSPECT_ATTACHMENT, ACKNOWLEDGE, INSPECT_ACKNOWLEDGEMENT, INSPECT_CLEAN, PROJECT }

class OccurrenceNativeReceipt(val action: OccurrenceReceiptAction, val intent: LocalCreateIntent,
    val attachment: CapturedCreateAttachmentReceipt, val acknowledgement: CapturedCreateAcknowledgementReceipt? = null,
    val frame: CapturedCreateHandoffFrame? = null, val projection: CapturedHandoffProjection? = null) {
    init {
        require(attachment.binding.identity.matchesOccurrence(intent))
        require(attachment.binding.eventId != intent.occurrence!!.master.eventId)
        require((action !in setOf(OccurrenceReceiptAction.ATTACH, OccurrenceReceiptAction.INSPECT_ATTACHMENT)) == (acknowledgement != null))
        if (acknowledgement != null) require(acknowledgement.baseline.receipt == attachment &&
            acknowledgement.baseline.occurrenceIntent == intent)
        if (frame != null) require(frame.receipt.acknowledgement == acknowledgement &&
            (action == OccurrenceReceiptAction.INSPECT_CLEAN || action == OccurrenceReceiptAction.PROJECT))
        require((action == OccurrenceReceiptAction.PROJECT) == (projection != null))
        if (projection != null) require(projection.frame == frame)
    }
    override fun toString() = "OccurrenceNativeReceipt(REDACTED)"
}

internal fun AndroidCalendarProjection.occurrenceExpectedMetadata(step: OccurrenceNativeReceipt): ContentValues =
    if (step.action == OccurrenceReceiptAction.ATTACH) ContentValues() else capturedAttachmentValues(step.attachment).apply {
        if (step.acknowledgement != null && step.action != OccurrenceReceiptAction.ACKNOWLEDGE)
            put(Events.SYNC_DATA7, capturedAcknowledgementMarker(requireNotNull(step.acknowledgement)))
        step.frame?.let { frame ->
            put(Events.SYNC_DATA2, digest("revision-v1", frame.base.revision.value))
            put(Events.SYNC_DATA3, frame.lastProjection?.let(::receiptMarker))
        }
    }

internal fun AndroidCalendarProjection.occurrenceMetadataWrite(step: OccurrenceNativeReceipt): ContentValues? = when (step.action) {
    OccurrenceReceiptAction.ATTACH -> capturedAttachmentValues(step.attachment)
    OccurrenceReceiptAction.ACKNOWLEDGE -> ContentValues().apply {
        put(Events.SYNC_DATA7, capturedAcknowledgementMarker(requireNotNull(step.acknowledgement)))
        put(Events.DIRTY, 0)
    }
    else -> null
}

/** A historical ACK survives later local edits. Qualify original family/slot, even on a dirty tombstone. */
internal fun AndroidCalendarProjection.occurrenceHistoryAssertions(intent: LocalCreateIntent, calendar: Long, child: Long,
    master: RemoteChange.Upsert = requireNotNull(intent.occurrence).master):
    List<ContentProviderOperation>? {
    val occurrence = requireNotNull(intent.occurrence)
    val scope = intent.reservation.scope
    if (!compatibleOccurrenceMaster(occurrence.master, master)) return null
    val series = digest("series-v1", calendarSyncId(scope), master.content.recurrence!!.uid)
    val remote = digest("event-v1", calendarSyncId(scope), master.eventId.value)
    val uri = syncUri(Events.CONTENT_URI, accountFor(scope.account))
    val parentWhere = "${Events.CALENDAR_ID}=? AND (${Events._SYNC_ID}=? OR ${Events.SYNC_DATA4}=?)"
    val parentArgs = arrayOf(calendar.toString(), series, remote)
    requirePermissions()
    val parent = resolver.query(uri, arrayOf(Events._ID), parentWhere, parentArgs, null)?.use {
        if (it.count != 1 || !it.moveToFirst()) return null
        it.getLong(0)
    } ?: return null
    if (parent == child) return null
    return listOf(
        ContentProviderOperation.newAssertQuery(uri).withSelection(parentWhere, parentArgs)
            .withValue(Events._ID, parent).withValue(Events._SYNC_ID, series).withValue(Events.SYNC_DATA4, remote)
            .withValue(Events.SYNC_DATA1, OWNER_MARKER).withValue(Events.SYNC_DATA2, digest("revision-v1", master.revision.value))
            .withExpectedCount(1).build(),
        ContentProviderOperation.newAssertQuery(uri).withSelection("${Events.ORIGINAL_ID}=? OR (${Events.CALENDAR_ID}=? AND ${Events.ORIGINAL_SYNC_ID}=?)",
            arrayOf(parent.toString(), calendar.toString(), series)).withValue(Events._ID, child).withValue(Events.CALENDAR_ID, calendar)
            .withValue(Events.ORIGINAL_ID, parent).withValue(Events.ORIGINAL_INSTANCE_TIME, recurrenceInstant(occurrence.original))
            .withValue(Events.ORIGINAL_ALL_DAY, if (occurrence.original is RecurrencePoint.AllDay) 1 else 0).withExpectedCount(1).build(),
        ContentProviderOperation.newAssertQuery(uri).withSelection("${Events._ID}=? AND (${Events.ORIGINAL_SYNC_ID} IS NULL OR ${Events.ORIGINAL_SYNC_ID}=?)",
            arrayOf(child.toString(), series)).withExpectedCount(1).build()
    )
}
