// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.database.DatabaseUtils
import android.provider.CalendarContract.*
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.digest
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.fail
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The parent and authenticated exception are asserted together. Only parent columns and reminders are written. */
internal suspend fun AndroidCalendarProjection.projectTransferredMaster(frame: CapturedCreateHandoffFrame,
    prepared: ProjectionReceipt, inspect: Boolean): AppliedProjectionWitness? {
    val receipt = prepared.snapshot()
    val base = frame.occurrenceMaster ?: return null
    val next = receipt.change
    if (receipt.previousBase != base || !compatibleOccurrenceMaster(base, next) ||
        receipt.scope != frame.scope || receipt.format != frame.format ||
        listOf(receipt.representation, receipt.previousRepresentation).any {
            it != null && it.representation != ProjectionRepresentation.DIRECT_V1
        }) return null
    val caller = currentCoroutineContext()
    return operation {
        val account = accountFor(frame.scope.account)
        val calendar = calendarRow(frame.scope, account) ?: return@operation null
        val expected = if (inspect) next else base
        val checks = capturedHistoryChecks(frame.receipt.acknowledgement, frame.lastProjection,
            caller::ensureActive, expected) ?: return@operation null
        val uri = syncUri(Events.CONTENT_URI, account)
        val series = digest("series-v1", calendarSyncId(frame.scope), expected.content.recurrence!!.uid)
        val remote = digest("event-v1", calendarSyncId(frame.scope), expected.eventId.value)
        val where = "${Events.CALENDAR_ID}=? AND (${Events._SYNC_ID}=? OR ${Events.SYNC_DATA4}=?)"
        val args = arrayOf(calendar.toString(), series, remote)
        requirePermissions()
        val parent = resolver.query(uri, OCCURRENCE_COLUMNS, where, args, null)?.use {
            if (it.count != 1 || !it.moveToFirst()) return@operation null
            ContentValues().apply { DatabaseUtils.cursorRowToContentValues(it, this) }
        } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        val id = parent.getAsLong(Events._ID) ?: return@operation null
        val marker = parent.getAsString(Events.SYNC_DATA3)
        if (inspect) {
            // Later local changes may survive recovery of this exact historical nonce.
            if (NativeColorStyle.read(marker)?.proof != receiptMarker(receipt)) return@operation null
            checks += ContentProviderOperation.newAssertQuery(uri).withSelection(where, args)
                .withValue(Events._ID, id).withValue(Events.SYNC_DATA3, marker).withExpectedCount(1).build()
            caller.ensureActive(); requirePermissions()
            capturedBatch(checks, IntArray(checks.size) { 1 })
        } else {
            if (!matchesOccurrenceParent(parent, id, series, remote, base, account)) return@operation null
            checks += ContentProviderOperation.newAssertQuery(uri).withSelection(where, args)
                .withValues(parent).withExpectedCount(1).build()
            val colors = nativeEventColorKeyAssertions(resolver, account, parent, ::syncUri, ::requirePermissions, ::fail)
                ?: return@operation null
            checks.addAll(colors)
            val counts = MutableList(checks.size) { 1 }
            val alerts = personalMetadata.captureReminders(account, id, base.content) ?: return@operation null
            if (alerts.reminders != base.content.details!!.reminders ||
                parent.getAsInteger(Events.HAS_ALARM) != if (alerts.reminders.isEmpty()) 0 else 1) return@operation null
            personalMetadata.assertReminders(alerts, checks, counts)
            for ((table, column) in listOf(Attendees.CONTENT_URI to Attendees.EVENT_ID,
                ExtendedProperties.CONTENT_URI to ExtendedProperties.EVENT_ID)) {
                checks += ContentProviderOperation.newAssertQuery(syncUri(table, account)).withSelection("$column=?",
                    arrayOf(id.toString())).withExpectedCount(0).build()
                counts += 0
            }
            val update = eventValues(next, account).apply {
                val allowed = setOf(Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION,
                    Events.EVENT_COLOR_KEY, Events.EVENT_COLOR, Events.HAS_ALARM)
                keySet().toList().filterNot { it in allowed }.forEach(::remove)
                put(Events.SYNC_DATA2, digest("revision-v1", next.revision.value))
                displayColor(this, next.content.colorRgb, receiptMarker(receipt), marker)
            }
            checks += ContentProviderOperation.newUpdate(uri).withSelection(where, args).withValues(update).withExpectedCount(1).build()
            counts += 1
            if (next.content.details != base.content.details) {
                replaceDetails(checks, account, id, checks.lastIndex, next.content)
                caller.ensureActive(); requirePermissions()
                val results = checkedBatch(checks)
                if (counts.indices.any { results[it].count != counts[it] } ||
                    results[counts.size].count != alerts.raw.size || results[counts.size + 1].count != 0)
                    fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
            } else {
                caller.ensureActive(); requirePermissions()
                capturedBatch(checks, counts.toIntArray())
            }
        }
        caller.ensureActive(); requirePermissions()
        AppliedProjectionWitness(receipt)
    }
}
