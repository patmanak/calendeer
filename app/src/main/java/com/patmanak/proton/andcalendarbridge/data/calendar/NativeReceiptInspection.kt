// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import com.patmanak.proton.andcalendarbridge.data.calendar.NativeCalendarSelection as Selection
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.database.DatabaseUtils
import com.patmanak.proton.andcalendarbridge.sync.RemoteChange
import android.net.Uri
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Reminders
import android.provider.CalendarContract.ExtendedProperties
import com.patmanak.proton.andcalendarbridge.sync.EventId
import com.patmanak.proton.andcalendarbridge.sync.Revision
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.ProjectionReceipt
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateAttachmentReceipt
import com.patmanak.proton.andcalendarbridge.sync.AppliedCapturedCreateAttachment
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateAcknowledgementReceipt
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.ATTACHMENT_SYNC_COLUMNS
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.ORDINARY_RESERVED_COLUMNS
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.OWNER_MARKER
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.digest
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.fail

internal fun AndroidCalendarProjection.inspectCapturedHistory(receipt: CapturedCreateAcknowledgementReceipt, projectionReceipt: ProjectionReceipt? = null,
    checkActive: () -> Unit = {}, master: RemoteChange.Upsert? = null): Boolean {
    val checks = capturedHistoryChecks(receipt, projectionReceipt, checkActive, master) ?: return false
    checkActive(); requirePermissions()
    capturedBatch(checks, IntArray(checks.size) { 1 })
    checkActive(); requirePermissions()
    return true
}

internal fun AndroidCalendarProjection.capturedHistoryChecks(receipt: CapturedCreateAcknowledgementReceipt,
    projectionReceipt: ProjectionReceipt?, checkActive: () -> Unit,
    master: RemoteChange.Upsert?): ArrayList<ContentProviderOperation>? {
    val baseline = receipt.baseline
    val expected = capturedAttachmentValues(baseline.receipt)
    expected.put(Events.SYNC_DATA7, capturedAcknowledgementMarker(receipt))
    if (projectionReceipt != null) {
        expected.put(Events.SYNC_DATA2, digest("revision-v1", projectionReceipt.change.revision.value))
        expected.put(Events.SYNC_DATA3, receiptMarker(projectionReceipt))
    }
    val scope = baseline.scope
    val account = accountFor(scope.account)
    val calendar = try { calendarRow(scope, account) } catch (failure: CalendarProjectionException) {
        if (failure.category == CalendarProjectionFailure.IDENTITY_CONFLICT) return null
        throw failure
    } ?: return null
    val uri = syncUri(Events.CONTENT_URI, account)
    val intent = baseline.occurrenceIntent
    val local = if (intent == null) capturedLocalSelection(baseline.receipt, calendar) else
        Selection("${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA5}=?", arrayOf(calendar.toString(), occurrenceMarker(intent)))
    val values = attachmentMetadata(uri, local) ?: return null
    if (!colorMetadataMatches(values, expected) || values.getAsString(Events.SYNC_DATA5) != local.args[1])
        return null
    val remote = capturedRemoteSelection(baseline.receipt, calendar)
    val remoteMatches = resolver.query(uri, arrayOf(Events._ID), remote.sql, remote.args, null)?.use {
        it.count == 1 && it.moveToFirst() && it.getLong(0) == values.getAsLong(Events._ID)
    } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    if (!remoteMatches) return null
    // Historical proof ignores later user data, children and dirty/deleted flags.
    val metadata = ContentValues(values).apply { remove(Events.DIRTY); remove(Events.DELETED) }
    checkActive()
    requirePermissions()
    checkActive()
    val checks = arrayListOf(
        assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), calendar),
        ContentProviderOperation.newAssertQuery(uri).withSelection(local.sql, local.args)
            .withValues(metadata).withExpectedCount(1).build(),
        ContentProviderOperation.newAssertQuery(uri).withSelection(remote.sql, remote.args)
            .withValue(Events._ID, values.getAsLong(Events._ID)).withExpectedCount(1).build(),
    )
    if (intent != null) {
        val family = occurrenceHistoryAssertions(intent, calendar, values.getAsLong(Events._ID), master ?: requireNotNull(intent.occurrence).master) ?: return null
        checks.addAll(family)
    }
    return checks
}

internal fun AndroidCalendarProjection.inspectCapturedAttachmentInternal(receipt: CapturedCreateAttachmentReceipt): AppliedCapturedCreateAttachment? {
    val expected = capturedAttachmentValues(receipt)
    val scope = receipt.binding.identity.attempt.scope
    val account = accountFor(scope.account)
    val calendar = calendarRow(scope, account) ?: return null
    val uri = syncUri(Events.CONTENT_URI, account)
    val local = capturedLocalSelection(receipt, calendar)
    val values = attachmentMetadata(uri, local) ?: return null
    if (expected.keySet().any { values.getAsString(it) != expected.getAsString(it) } ||
        values.getAsString(Events.SYNC_DATA5) != local.args[1]) return null
    val remote = capturedRemoteSelection(receipt, calendar)
    val remoteValues = resolver.query(uri, arrayOf(Events._ID), remote.sql, remote.args, null)?.use {
        it.count == 1 && it.moveToFirst() && it.getLong(0) == values.getAsLong(Events._ID)
    } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    if (!remoteValues) return null
    val checks = ContentValues(values).apply { remove(Events.DIRTY); remove(Events.DELETED) }
    requirePermissions()
    capturedBatch(arrayListOf(
        assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), calendar),
        ContentProviderOperation.newAssertQuery(uri).withSelection(local.sql, local.args)
            .withValues(checks).withExpectedCount(1).build(),
        ContentProviderOperation.newAssertQuery(uri).withSelection(remote.sql, remote.args)
            .withValue(Events._ID, values.getAsLong(Events._ID)).withExpectedCount(1).build(),
    ), intArrayOf(1, 1, 1))
    requirePermissions()
    return AppliedCapturedCreateAttachment(receipt)
}

internal fun AndroidCalendarProjection.inspectOrdinaryAcknowledgement(scope: CalendarScope, eventId: EventId, revision: Revision,
    marker: String, checkActive: () -> Unit, seriesUid: String? = null): Boolean {
    val account = accountFor(scope.account)
    val calendar = calendarRow(scope, account) ?: return false
    val remoteId = digest("event-v1", calendarSyncId(scope), eventId.value)
    val syncId = if (seriesUid == null) remoteId else digest("series-v1", calendarSyncId(scope), seriesUid)
    val uri = syncUri(Events.CONTENT_URI, account)
    val identity = Selection("${Events.CALENDAR_ID}=? AND (${Events.SYNC_DATA4}=? OR " +
        "(${Events.SYNC_DATA4} IS NULL AND ${Events._SYNC_ID}=?))", arrayOf(calendar.toString(), remoteId, remoteId))
    val row = ownedRow(uri, identity, Events.SYNC_DATA1) ?: return false
    // Historical proof excludes current flags/content/children; all ownership metadata
    // remains exact and is asserted atomically, including reserved and CREATE markers.
    val metadata = resolver.query(uri, arrayOf(Events._ID, Events.CALENDAR_ID) + ATTACHMENT_SYNC_COLUMNS,
        identity.sql, identity.args, null)?.use {
        if (it.count != 1 || !it.moveToFirst()) return false
        ContentValues().apply { DatabaseUtils.cursorRowToContentValues(it, this) }
    } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    if (metadata.getAsLong(Events._ID) != row || metadata.getAsLong(Events.CALENDAR_ID) != calendar ||
        metadata.getAsString(Events._SYNC_ID) != syncId || metadata.getAsString(Events.SYNC_DATA4) != remoteId ||
        metadata.getAsString(Events.SYNC_DATA1) != OWNER_MARKER ||
        metadata.getAsString(Events.SYNC_DATA2) != digest("revision-v1", revision.value) ||
        NativeColorStyle.read(metadata.getAsString(Events.SYNC_DATA3))?.proof != marker ||
        ORDINARY_RESERVED_COLUMNS.any { metadata[it] != null }) return false
    val syncIdentity = Selection("${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=?", arrayOf(calendar.toString(), syncId))
    if (ownedRow(uri, syncIdentity, Events.SYNC_DATA1) != row) return false
    checkActive(); requirePermissions(); checkActive()
    capturedBatch(arrayListOf(
        assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), calendar),
        ContentProviderOperation.newAssertQuery(uri).withSelection(identity.sql, identity.args)
            .withValues(metadata).withExpectedCount(1).build(),
        assertion(uri, syncIdentity, row),
    ), intArrayOf(1, 1, 1))
    checkActive(); requirePermissions(); checkActive()
    return true
}

/** Scheduling hint, not ownership evidence. Use a dedicated reader instance, never the
 * coordinator's shared mutable admission guard. Include child tables and full dirty values:
 * DIRTY alone cannot distinguish a new edit from an unchanged held event.
 */
internal suspend fun AndroidCalendarProjection.readProviderDirtyFingerprint(scopes: List<CalendarScope>): ProviderDirtyFingerprint = operation {
    try {
        val hash = ProviderWakeDigest()
        hash.text("provider-dirty-v1")
        var dirty = 0
        fun appendRows(uri: Uri, selection: String, args: Array<String>, eventRows: Boolean) {
            requirePermissions()
            resolver.query(uri, null, selection, args, "_id ASC")?.use { cursor ->
                val columns = cursor.columnNames.sorted()
                while (cursor.moveToNext()) {
                    requirePermissions(); hash.row()
                    if (eventRows) dirty++
                    hash.text("ROW")
                    for (column in columns) {
                        val index = cursor.getColumnIndexOrThrow(column)
                        hash.text(column)
                        val type = cursor.getType(index)
                        hash.text(type.toString())
                        when (type) {
                            android.database.Cursor.FIELD_TYPE_NULL -> Unit
                            android.database.Cursor.FIELD_TYPE_BLOB -> hash.value(cursor.getBlob(index))
                            android.database.Cursor.FIELD_TYPE_INTEGER -> hash.text(cursor.getLong(index).toString())
                            android.database.Cursor.FIELD_TYPE_FLOAT -> hash.text(java.lang.Double.toHexString(cursor.getDouble(index)))
                            else -> hash.text(cursor.getString(index))
                        }
                    }
                }
                hash.text("END")
            } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        }
        for (target in scopes.sortedWith(compareBy({ it.account.value }, { it.calendar.value }, { it.generation }))) {
            requirePermissions()
            hash.text(target.account.value); hash.text(target.calendar.value); hash.text(target.generation.toString())
            val account = accountFor(target.account)
            val calendar = calendarRow(target, account)
            if (calendar == null) { hash.text("MISSING"); continue }
            val selection = "${Events.CALENDAR_ID}=? AND (${Events.DIRTY}=1 OR ${Events.DELETED}=1)"
            val args = arrayOf(calendar.toString())
            appendRows(syncUri(Events.CONTENT_URI, account), selection, args, true)
            // Read only children of this calendar's dirty events; no unrelated account content.
            // Query each dirty parent's children through the public Provider API.
            resolver.query(syncUri(Events.CONTENT_URI, account), arrayOf(Events._ID), selection, args,
                "${Events._ID} ASC")?.use { events ->
                while (events.moveToNext()) {
                    val id = events.getLong(0).toString()
                    hash.text("CHILDREN"); hash.text(id)
                    for (table in listOf(Reminders.CONTENT_URI, Attendees.CONTENT_URI, ExtendedProperties.CONTENT_URI)) {
                        hash.text(table.lastPathSegment.orEmpty())
                        appendRows(syncUri(table, account), "event_id=?", arrayOf(id), false)
                    }
                }
            } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        }
        requirePermissions()
        ProviderDirtyFingerprint(hash.finish(), dirty)
    } catch (_: ProviderWakeSnapshotLimit) {
        // A large held row must not starve ordinary dirty events. Fall back to bounded
        // paginated capture without caching a partial/constant content fingerprint.
        ProviderDirtyFingerprint(null, 1)
    }
}
