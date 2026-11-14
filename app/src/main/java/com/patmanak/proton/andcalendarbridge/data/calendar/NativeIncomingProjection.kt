// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import com.patmanak.proton.andcalendarbridge.data.calendar.NativeCalendarSelection as Selection
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.database.DatabaseUtils
import android.net.Uri
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import com.patmanak.proton.andcalendarbridge.sync.RecurringLocalTimeRisk
import com.patmanak.proton.andcalendarbridge.sync.ProjectionIntent
import com.patmanak.proton.andcalendarbridge.sync.ProjectionReceipt
import com.patmanak.proton.andcalendarbridge.sync.ProjectionRepresentation
import com.patmanak.proton.andcalendarbridge.sync.AppliedProjectionWitness
import com.patmanak.proton.andcalendarbridge.sync.RecurrencePoint
import com.patmanak.proton.andcalendarbridge.sync.RemoteChange
import java.time.ZoneId
import java.time.Instant
import kotlinx.coroutines.CancellationException
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.CAPTURED_ORIGIN_COLUMNS
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.FINITE_RECEIPT_PREFIX
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.OWNER_MARKER
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.digest
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.fail
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.ordinaryReceiptMarker

internal fun AndroidCalendarProjection.applyInternal(intent: ProjectionIntent, seriesUid: String?, receipt: ProjectionReceipt?, firstFiniteInsertion: Boolean = false) {
    val account = accountFor(intent.scope.account)
    val calendar = calendarRow(intent.scope, account) ?: fail(CalendarProjectionFailure.CALENDAR_MISSING)
    val uri = syncUri(Events.CONTENT_URI, account)
    val remoteId = digest("event-v1", calendarSyncId(intent.scope), intent.change.eventId.value)
    val identity = Selection("${Events.CALENDAR_ID}=? AND (${Events.SYNC_DATA4}=? OR " +
        "(${Events.SYNC_DATA4} IS NULL AND ${Events._SYNC_ID}=?))", arrayOf(calendar.toString(), remoteId, remoteId))
    val existing = ownedRow(uri, identity, Events.SYNC_DATA1)
    if (firstFiniteInsertion && existing != null) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
    val observedMarker = existing?.let { ordinaryReceiptMarker(uri, identity) }
    val previousMaster = existing?.let { masterSyncId(uri, it) }
    val operations = arrayListOf<ContentProviderOperation>()
    // Recheck calendar ownership inside the same provider transaction as the event mutation.
    operations += assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(intent.scope, account), calendar)
    operations += assertion(uri, identity, existing)
    if (existing != null) operations += ContentProviderOperation.newAssertQuery(uri)
        .withSelection(identity.sql, identity.args).withExpectedCount(1)
        .withValue(Events.DIRTY, 0).withValue(Events.DELETED, 0)
        .withValue(Events.SYNC_DATA3, observedMarker)
        .withValue(Events.SYNC_DATA5, null).withValue(Events.SYNC_DATA6, null).withValue(Events.SYNC_DATA7, null).build()
    when (val change = intent.change) {
        is RemoteChange.Upsert -> {
            val recurrence = change.content.recurrence
            if (unsupportedRuleTime(change.content.time, recurrence?.rule)) fail(CalendarProjectionFailure.PROVIDER_REJECTED)
            val seriesId = recurrence?.let { digest("series-v1", calendarSyncId(intent.scope), it.uid) }
            val ownSyncId = if (recurrence?.isMaster == true) seriesId!! else remoteId
            val syncIdentity = Selection("${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=?",
                arrayOf(calendar.toString(), ownSyncId))
            val sameSync = ownedRow(uri, syncIdentity, Events.SYNC_DATA1)
            if (sameSync != null && sameSync != existing) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
            operations += assertion(uri, syncIdentity, sameSync)
            if (firstFiniteInsertion) {
                // An orphan exception can otherwise alter the new master's geometry.
                val children = Selection("${Events.CALENDAR_ID}=? AND ${Events.ORIGINAL_SYNC_ID}=?",
                    arrayOf(calendar.toString(), ownSyncId))
                val absent = resolver.query(uri, arrayOf(Events._ID), children.sql, children.args, null)?.use { it.count == 0 }
                    ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
                if (!absent) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
                operations += ContentProviderOperation.newAssertQuery(uri).withSelection(children.sql, children.args).withExpectedCount(0).build()
            }
            val parent = if (recurrence != null && !recurrence.isMaster) {
                val parentIdentity = Selection("${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=?",
                    arrayOf(calendar.toString(), seriesId!!))
                val parentId = ownedRow(uri, parentIdentity, Events.SYNC_DATA1)
                    ?: fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
                val parentMarker = ordinaryReceiptMarker(uri, parentIdentity)
                if (masterSyncId(uri, parentId) != seriesId) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
                val originalAllDay = if (recurrence.original is RecurrencePoint.AllDay) 1 else 0
                val parentValues = resolver.query(uri, arrayOf(Events.ALL_DAY, Events.DTSTART, Events.EVENT_TIMEZONE, Events.RRULE),
                    "${Events._ID}=?", arrayOf(parentId.toString()), null)?.use {
                    if (!it.moveToFirst()) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
                    ContentValues().apply {
                        put(Events.ALL_DAY, it.getInt(0))
                        if (it.isNull(1)) putNull(Events.DTSTART) else put(Events.DTSTART, it.getLong(1))
                        if (it.isNull(2)) putNull(Events.EVENT_TIMEZONE) else put(Events.EVENT_TIMEZONE, it.getString(2))
                        if (it.isNull(3)) putNull(Events.RRULE) else put(Events.RRULE, it.getString(3))
                    }
                } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
                if (parentValues.getAsInteger(Events.ALL_DAY) != originalAllDay) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
                if (originalAllDay == 0 && parentValues.getAsString(Events.RRULE) != null) {
                    val start = parentValues.getAsLong(Events.DTSTART) ?: fail(CalendarProjectionFailure.PROVIDER_REJECTED)
                    val zone = try { ZoneId.of(parentValues.getAsString(Events.EVENT_TIMEZONE)) }
                    catch (_: Exception) { fail(CalendarProjectionFailure.PROVIDER_REJECTED) }
                    if (RecurringLocalTimeRisk.detected(Instant.ofEpochMilli(start), zone, parentValues.getAsString(Events.RRULE)))
                        fail(CalendarProjectionFailure.PROVIDER_REJECTED)
                }
                operations += assertion(uri, parentIdentity, parentId)
                // Recheck the exact parent time/rule admitted above in the exception transaction.
                operations += ContentProviderOperation.newAssertQuery(uri)
                    .withSelection(parentIdentity.sql, parentIdentity.args).withExpectedCount(1)
                    .withValues(parentValues)
                    .withValue(Events.SYNC_DATA3, parentMarker)
                    .withValue(Events.SYNC_DATA5, null).withValue(Events.SYNC_DATA6, null).withValue(Events.SYNC_DATA7, null).build()
                parentId
            } else null
            if (previousMaster != null && previousMaster != ownSyncId) {
                removeChildren(operations, uri, calendar, previousMaster)
            }
            val values = eventValues(change, account).apply {
                put(Events.CALENDAR_ID, calendar)
                put(Events._SYNC_ID, ownSyncId)
                put(Events.SYNC_DATA1, OWNER_MARKER)
                put(Events.SYNC_DATA2, digest("revision-v1", change.revision.value))
                put(Events.SYNC_DATA4, remoteId)
                if (receipt != null) put(Events.SYNC_DATA3, receiptMarker(receipt))
                if (parent != null) {
                    put(Events.ORIGINAL_ID, parent)
                    put(Events.ORIGINAL_SYNC_ID, seriesId)
                }
            }
            val eventOperation = operations.size
            operations += if (existing == null) ContentProviderOperation.newInsert(uri).withValues(values).build()
            else ContentProviderOperation.newUpdate(uri).withSelection(identity.sql, identity.args)
                .withValues(values).withExpectedCount(1).build()
            replaceDetails(operations, account, existing, eventOperation, change.content)
        }
        is RemoteChange.Deleted -> {
            // The store retains the master association after its Provider row disappears.
            val deletedSeries = seriesUid?.let { digest("series-v1", calendarSyncId(intent.scope), it) }
                ?: previousMaster
            if (deletedSeries != null) removeChildren(operations, uri, calendar, deletedSeries)
            if (existing != null) operations += ContentProviderOperation.newDelete(uri)
                .withSelection(identity.sql, identity.args).withExpectedCount(1).build()
        }
    }
    requirePermissions()
    checkedBatch(operations)
    Unit
}

internal suspend fun AndroidCalendarProjection.inspectReceipt(frozen: ProjectionReceipt): AppliedProjectionWitness? {
    return operation {
        val scope = frozen.scope
        val account = accountFor(scope.account)
        val calendar = calendarRow(scope, account) ?: return@operation null
        val remoteId = digest("event-v1", calendarSyncId(scope), frozen.change.eventId.value)
        val recurrence = frozen.change.content.recurrence
        val ownSyncId = if (recurrence?.isMaster == true) digest("series-v1", calendarSyncId(scope), recurrence.uid) else remoteId
        val uri = syncUri(Events.CONTENT_URI, account)
        val identity = Selection("${Events.CALENDAR_ID}=? AND (${Events.SYNC_DATA4}=? OR " +
            "(${Events.SYNC_DATA4} IS NULL AND ${Events._SYNC_ID}=?))", arrayOf(calendar.toString(), remoteId, remoteId))
        val row = ownedRow(uri, identity, Events.SYNC_DATA1) ?: return@operation null
        val metadata = resolver.query(uri, arrayOf(Events._ID, Events.CALENDAR_ID, Events._SYNC_ID,
            Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA4),
            identity.sql, identity.args, null)?.use {
            if (it.count != 1 || !it.moveToFirst()) return@operation null
            ContentValues().apply { DatabaseUtils.cursorRowToContentValues(it, this) }
        } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        if (metadata.getAsLong(Events._ID) != row || metadata.getAsString(Events._SYNC_ID) != ownSyncId ||
            metadata.getAsString(Events.SYNC_DATA1) != OWNER_MARKER || metadata.getAsString(Events.SYNC_DATA4) != remoteId ||
            metadata.getAsString(Events.SYNC_DATA2) != digest("revision-v1", frozen.change.revision.value) ||
            metadata.getAsString(Events.SYNC_DATA3) != receiptMarker(frozen)) return@operation null
        // A master has distinct remote-event and series identities. Bind both uniquely,
        // without treating today's recurrence fields or child rows as historical proof.
        val syncIdentity = Selection("${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=?",
            arrayOf(calendar.toString(), ownSyncId))
        if (ownedRow(uri, syncIdentity, Events.SYNC_DATA1) != row) return@operation null
        requirePermissions()
        capturedBatch(arrayListOf(
            assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), calendar),
            ContentProviderOperation.newAssertQuery(uri).withSelection(identity.sql, identity.args)
                .withValues(metadata).withExpectedCount(1).build(),
            assertion(uri, syncIdentity, row),
        ), intArrayOf(1, 1, 1))
        requirePermissions()
        AppliedProjectionWitness(frozen)
    }
}

internal fun AndroidCalendarProjection.snapshotReceipt(receipt: ProjectionReceipt): ProjectionReceipt = try {
    // Ordinary projection remains direct; finite insertion has its own admission.
    require(receipt.representation?.representation != ProjectionRepresentation.FINITE_DAILY_V1 &&
        receipt.previousRepresentation?.representation != ProjectionRepresentation.FINITE_DAILY_V1)
    receipt.snapshot()
}
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { fail(CalendarProjectionFailure.PROVIDER_REJECTED) }

internal fun AndroidCalendarProjection.snapshotFiniteInsertion(receipt: ProjectionReceipt): ProjectionReceipt = try {
    val frozen = receipt.snapshot()
    require(frozen.representation?.representation == ProjectionRepresentation.FINITE_DAILY_V1 &&
        frozen.previousBase == null && frozen.previousRepresentation == null)
    frozen
}
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { fail(CalendarProjectionFailure.PROVIDER_REJECTED) }

internal fun AndroidCalendarProjection.receiptMarker(receipt: ProjectionReceipt) =
    if (receipt.representation?.representation == ProjectionRepresentation.FINITE_DAILY_V1)
        "${FINITE_RECEIPT_PREFIX}daily-dates-v1-receipt-v${receipt.protocol}:${receipt.nonce}"
    else "calendeer-projection-receipt-v${receipt.protocol}:${receipt.nonce}"

/** Specialized rows cannot enter ordinary writes, even when currently clean.
 * Return the observed ordinary marker for an atomic write/parent assertion.
 */
internal fun AndroidCalendarProjection.ordinaryReceiptMarker(uri: Uri, selection: Selection): String? {
    val cursor = resolver.query(uri, arrayOf(Events.SYNC_DATA3) + CAPTURED_ORIGIN_COLUMNS, selection.sql, selection.args, null)
        ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    return cursor.use {
        if (it.count != 1 || !it.moveToFirst()) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
        if ((1..CAPTURED_ORIGIN_COLUMNS.size).any { column -> !it.isNull(column) }) fail(CalendarProjectionFailure.IDENTITY_CONFLICT)
        val marker = it.getString(0)
        if (finiteMarker(marker)) fail(CalendarProjectionFailure.PROVIDER_REJECTED)
        marker
    }
}

internal fun AndroidCalendarProjection.finiteMarker(marker: String?) = marker?.startsWith(FINITE_RECEIPT_PREFIX, ignoreCase = true) == true
