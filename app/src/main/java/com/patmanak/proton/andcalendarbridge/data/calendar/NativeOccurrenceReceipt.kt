// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import android.content.ContentValues
import android.content.ContentProviderOperation
import android.provider.CalendarContract.Events
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.OWNER_MARKER
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.digest

/** All operations use the same stamped-row, parent, slot, reminder and family assertion batch. */
enum class OccurrenceReceiptAction { ATTACH, INSPECT_ATTACHMENT, ACKNOWLEDGE, INSPECT_ACKNOWLEDGEMENT }

class OccurrenceNativeReceipt(val action: OccurrenceReceiptAction, val intent: LocalCreateIntent,
    val attachment: CapturedCreateAttachmentReceipt, val acknowledgement: CapturedCreateAcknowledgementReceipt? = null) {
    init {
        require(attachment.binding.identity.matchesOccurrence(intent))
        require(attachment.binding.eventId != intent.occurrence!!.master.eventId)
        require((action == OccurrenceReceiptAction.ACKNOWLEDGE || action == OccurrenceReceiptAction.INSPECT_ACKNOWLEDGEMENT) ==
            (acknowledgement != null))
        if (acknowledgement != null) require(acknowledgement.baseline.receipt == attachment &&
            acknowledgement.baseline.occurrenceIntent == intent)
    }
    override fun toString() = "OccurrenceNativeReceipt(REDACTED)"
}

internal fun AndroidCalendarProjection.occurrenceExpectedMetadata(step: OccurrenceNativeReceipt): ContentValues =
    if (step.action == OccurrenceReceiptAction.ATTACH) ContentValues() else capturedAttachmentValues(step.attachment).apply {
        if (step.action == OccurrenceReceiptAction.INSPECT_ACKNOWLEDGEMENT)
            put(Events.SYNC_DATA7, capturedAcknowledgementMarker(requireNotNull(step.acknowledgement)))
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
internal fun AndroidCalendarProjection.occurrenceHistoryAssertions(intent: LocalCreateIntent, calendar: Long, child: Long):
    List<ContentProviderOperation>? {
    val occurrence = requireNotNull(intent.occurrence)
    val scope = intent.reservation.scope
    val master = occurrence.master
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
