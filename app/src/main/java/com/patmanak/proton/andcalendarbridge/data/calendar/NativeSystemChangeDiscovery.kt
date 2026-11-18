// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import android.provider.CalendarContract.Events
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.OWNER_MARKER
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.fail

/** Locator only. Capture must assert the identified canonical master and exact native family in the stamping batch. */
internal suspend fun AndroidCalendarProjection.nativeOccurrenceParentKey(scope: CalendarScope, childRow: Long): String? = operation {
    val account = accountFor(scope.account)
    val calendar = calendarRow(scope, account) ?: return@operation null
    val uri = syncUri(Events.CONTENT_URI, account)
    val row = "${Events.CALENDAR_ID}=? AND ${Events._ID}=?"
    val parent = resolver.query(uri, arrayOf(Events.ORIGINAL_ID), row, arrayOf(calendar.toString(), childRow.toString()), null)?.use {
        if (it.count != 1 || !it.moveToFirst() || it.isNull(0)) return@operation null
        it.getLong(0)
    } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    if (parent == childRow) return@operation null
    resolver.query(uri, arrayOf(Events.SYNC_DATA1, Events.SYNC_DATA4), row, arrayOf(calendar.toString(), parent.toString()), null)?.use {
        if (it.count != 1 || !it.moveToFirst() || it.getString(0) != OWNER_MARKER) return@operation null
        it.getString(1)?.takeIf { key -> key.matches(Regex("[a-f0-9]{64}")) }
    }
}

/** Metadata-only discovery under the caller's existing operation lease. */
internal fun AndroidCalendarProjection.discoverNativeSystemChanges(scope: CalendarScope, afterRow: Long,
    limit: Int): SystemCalendarChangePage = run {
    require(afterRow >= 0 && limit in 1..100)
    val account = accountFor(scope.account)
    val calendar = calendarRow(scope, account) ?: fail(CalendarProjectionFailure.CALENDAR_MISSING)
    val sync = arrayOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3,
        Events.SYNC_DATA4, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7, Events.SYNC_DATA8,
        Events.SYNC_DATA9, Events.SYNC_DATA10)
    val columns = arrayOf(Events._ID, Events.DELETED, Events.DIRTY) + sync
    val changes = mutableListOf<SystemCalendarChange>()
    var more = false
    resolver.query(syncUri(Events.CONTENT_URI, account), columns,
        "${Events.CALENDAR_ID}=? AND ${Events._ID}>? AND (${Events.DIRTY}=1 OR ${Events.DELETED}=1)",
        arrayOf(calendar.toString(), afterRow.toString()), "${Events._ID} ASC")?.use { cursor ->
        while (cursor.moveToNext()) {
            if (changes.size == limit) { more = true; break }
            val row = cursor.getLong(0)
            val deleted = cursor.getInt(1) != 0
            fun value(column: String): String? = cursor.getString(cursor.getColumnIndexOrThrow(column))
            val remote = value(Events.SYNC_DATA4) ?: value(Events._SYNC_ID)
            val kind = when {
                value(Events.SYNC_DATA5) != null -> SystemCalendarChangeKind.CAPTURED
                value(Events.SYNC_DATA1) == OWNER_MARKER && !remote.isNullOrBlank() -> SystemCalendarChangeKind.IMPORTED
                sync.all { value(it) == null } && !deleted -> SystemCalendarChangeKind.NEW
                else -> SystemCalendarChangeKind.UNSUPPORTED
            }
            // Provider keys are one-way hashes (and master sync IDs can identify a series).
            // Only the owner can resolve them against scoped, acknowledged canonical IDs.
            changes += SystemCalendarChange(row, kind, deleted = deleted,
                nativeKey = if (kind == SystemCalendarChangeKind.IMPORTED) remote else null,
                dirty = cursor.getInt(cursor.getColumnIndexOrThrow(Events.DIRTY)) != 0,
                capturedKey = if (kind == SystemCalendarChangeKind.CAPTURED)
                    value(Events.SYNC_DATA5)?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } else null)
        }
    } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    SystemCalendarChangePage(changes.toList(), changes.lastOrNull()?.row ?: afterRow, more)
}
