// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.database.DatabaseUtils
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.ATTACHMENT_SYNC_COLUMNS
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.CAPTURED_ORIGIN_COLUMNS
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.OWNER_MARKER
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.fail
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope

data class ColorProjectionPage(val afterRow: Long, val hasMore: Boolean)

/** Bounded, restartable native-only pass. No event intent, request or SQL receipt is changed. */
internal suspend fun AndroidCalendarProjection.reprojectColorPage(scope: CalendarScope, afterRow: Long,
    limit: Int): ColorProjectionPage = operation {
    require(afterRow >= 0 && limit in 1..100)
    val account = accountFor(scope.account)
    val calendar = calendarRow(scope, account) ?: return@operation ColorProjectionPage(afterRow, false)
    val uri = syncUri(Events.CONTENT_URI, account)
    val columns = arrayOf(Events._ID, Events.CALENDAR_ID, Events.DIRTY, Events.DELETED,
        Events.EVENT_COLOR, Events.EVENT_COLOR_KEY) + ATTACHMENT_SYNC_COLUMNS
    val rows = mutableListOf<ContentValues>()
    var more = false
    resolver.query(uri, columns, "${Events.CALENDAR_ID}=? AND ${Events._ID}>?",
        arrayOf(calendar.toString(), afterRow.toString()), "${Events._ID} ASC")?.use { cursor ->
        while (cursor.moveToNext()) {
            if (rows.size == limit) { more = true; break }
            rows += ContentValues().apply { DatabaseUtils.cursorRowToContentValues(cursor, this) }
        }
    } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    val mode = colorMode()
    for (row in rows) {
        requirePermissions()
        if (!knownColorOwner(row) || row.getAsInteger(Events.DIRTY) != 0 ||
            row.getAsInteger(Events.DELETED) != 0) continue
        val colorKeyChecks = nativeEventColorKeyAssertions(resolver, account, row,
            ::syncUri, ::requirePermissions, ::fail) ?: continue
        val rawMarker = row.getAsString(Events.SYNC_DATA3)
        val style = NativeColorStyle.read(rawMarker) ?: continue
        val next = style.forDisplay(mode, row.getAsInteger(Events.EVENT_COLOR)) ?: continue
        if (next.masked == style.masked) continue
        val id = row.getAsLong(Events._ID)
        val identity = NativeCalendarSelection("${Events.CALENDAR_ID}=? AND ${Events._ID}=?",
            arrayOf(calendar.toString(), id.toString()))
        val unique = NativeCalendarSelection("${Events.CALENDAR_ID}=? AND (${Events._SYNC_ID}=? OR ${Events.SYNC_DATA4}=?)",
            arrayOf(calendar.toString(), row.getAsString(Events._SYNC_ID), row.getAsString(Events.SYNC_DATA4)))
        val operations = arrayListOf(
            assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), calendar),
            ContentProviderOperation.newAssertQuery(uri).withSelection(identity.sql, identity.args)
                .withValues(row).withExpectedCount(1).build(),
            ContentProviderOperation.newAssertQuery(uri).withSelection(unique.sql, unique.args)
                .withValue(Events._ID, id).withExpectedCount(1).build())
        if (row[Events.SYNC_DATA5] != null) operations += ContentProviderOperation.newAssertQuery(uri)
            .withSelection("${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA5}=?",
                arrayOf(calendar.toString(), row.getAsString(Events.SYNC_DATA5)))
            .withValue(Events._ID, id).withExpectedCount(1).build()
        val values = ContentValues().apply {
            put(Events.SYNC_DATA3, next.marker())
            putNull(Events.EVENT_COLOR_KEY)
            if (next.masked || next.colorRgb == null) putNull(Events.EVENT_COLOR)
            else put(Events.EVENT_COLOR, next.colorRgb or 0xff000000.toInt())
        }
        operations.addAll(colorKeyChecks)
        operations += ContentProviderOperation.newUpdate(uri).withSelection(identity.sql, identity.args)
            .withValues(values).withExpectedCount(1).build()
        requirePermissions()
        capturedBatch(operations, IntArray(operations.size) { 1 })
    }
    ColorProjectionPage(rows.lastOrNull()?.getAsLong(Events._ID) ?: afterRow, more)
}

private fun knownColorOwner(row: ContentValues): Boolean {
    fun matches(column: String, pattern: String) = row.getAsString(column)?.matches(Regex(pattern)) == true
    if (row.getAsString(Events.SYNC_DATA1) != OWNER_MARKER ||
        listOf(Events._SYNC_ID, Events.SYNC_DATA2, Events.SYNC_DATA4).any { !matches(it, "[a-f0-9]{64}") } ||
        listOf(Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10).any { row[it] != null }) return false
    val style = NativeColorStyle.read(row.getAsString(Events.SYNC_DATA3)) ?: return false
    val ordinary = AndroidCalendarProjection.ordinaryReceiptMarker(row.getAsString(Events.SYNC_DATA3))
    val captured = CAPTURED_ORIGIN_COLUMNS.any { row[it] != null }
    if (captured) return ordinary && row.getAsString(Events._SYNC_ID) == row.getAsString(Events.SYNC_DATA4) &&
        matches(Events.SYNC_DATA5, "[a-f0-9]{64}") &&
        matches(Events.SYNC_DATA6, "calendeer-captured-attachment-v1:[a-f0-9]{64}") &&
        matches(Events.SYNC_DATA7, "calendeer-captured-ack-v1:[a-f0-9]{64}")
    return ordinary || style.proof?.matches(Regex(
        "calendeer-finite-daily-dates-v1-receipt-v1:[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) == true
}
