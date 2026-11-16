// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import android.accounts.Account
import android.content.*
import android.database.DatabaseUtils
import android.net.Uri
import android.provider.CalendarContract.*
import com.patmanak.proton.andcalendarbridge.sync.*

internal data class NativeCalendarSelection(val sql: String, val args: Array<String>)

/** Native CREATE snapshot/stamp under the projection owner's original operation guard. */
internal class NativeLocalCreateReader(
    private val resolver: ContentResolver,
    private val accountFor: (AccountId) -> Account,
    private val calendarRow: (CalendarScope, Account) -> Long?,
    private val syncUri: (Uri, Account) -> Uri,
    private val calendarSyncId: (CalendarScope) -> String,
    private val calendarSelection: (CalendarScope, Account) -> NativeCalendarSelection,
    private val assertion: (Uri, NativeCalendarSelection, Long) -> ContentProviderOperation,
    private val readSimpleTime: (ContentValues, Boolean) -> EventTime?,
    private val requirePermissions: () -> Unit,
    private val capturedBatch: (ArrayList<ContentProviderOperation>, IntArray) -> Unit,
    private val fail: (CalendarProjectionFailure) -> Nothing,
    private val hash: (Array<out String>) -> String,
) {
    private val personal = NativePersonalMetadata(resolver, syncUri, fail)
    private fun digest(vararg parts: String) = hash(parts)
    fun profile(scope: CalendarScope, eventRow: Long): LocalCreateProfile? {
        if (eventRow <= 0) return null
        val account = accountFor(scope.account)
        val calendar = calendarRow(scope, account) ?: return null
        val result = resolver.query(syncUri(Events.CONTENT_URI, account), arrayOf(Events.RRULE),
            "${Events.CALENDAR_ID}=? AND ${Events._ID}=?", arrayOf(calendar.toString(), eventRow.toString()), null)?.use {
            if (it.count != 1 || !it.moveToFirst()) return null
            // Even an empty or unsupported rule must never fall back to the simple reader.
            if (it.isNull(0)) LocalCreateProfile.SIMPLE else LocalCreateProfile.SERIES
        } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        requirePermissions()
        return result
    }
    fun readLocalCreate(scope: CalendarScope, localId: LocalEventId, firstRow: Long?, seriesOnly: Boolean = false): OwnedLocalEditSnapshot? {
        if (firstRow != null && firstRow <= 0) return null
        val account = accountFor(scope.account)
        val calendar = calendarRow(scope, account) ?: return null
        val uri = syncUri(Events.CONTENT_URI, account)
        val marker = digest("local-create-v1", calendarSyncId(scope), localId.value)
        val markerSelection = NativeCalendarSelection("${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA5}=?", arrayOf(calendar.toString(), marker))
        val selection = if (firstRow == null) markerSelection else NativeCalendarSelection(
            "${Events.CALENDAR_ID}=? AND ${Events._ID}=?", arrayOf(calendar.toString(), firstRow.toString()))
        val syncColumns = arrayOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3,
            Events.SYNC_DATA4, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7, Events.SYNC_DATA8,
            Events.SYNC_DATA9, Events.SYNC_DATA10)
        val unsupported = arrayOf(Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DURATION,
            Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY,
            Events.ORGANIZER, Events.EVENT_COLOR_KEY, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI)
        val emptyFlags = arrayOf(Events.HAS_ATTENDEE_DATA, Events.IS_ORGANIZER, Events.GUESTS_CAN_MODIFY,
            Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS,
            Events.SELF_ATTENDEE_STATUS, Events.ACCESS_LEVEL)
        val columns = arrayOf(Events._ID, Events.CALENDAR_ID, Events.DIRTY, Events.DELETED, Events.TITLE,
            Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND, Events.ALL_DAY,
            Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.STATUS, Events.AVAILABILITY, Events.EVENT_COLOR, Events.HAS_ALARM) +
            syncColumns + unsupported + emptyFlags
        val values = resolver.query(uri, columns, selection.sql, selection.args, null)?.use {
            if (it.count != 1 || !it.moveToFirst()) return null
            ContentValues().apply { DatabaseUtils.cursorRowToContentValues(it, this) }
        } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        val row = values.getAsLong(Events._ID) ?: return null
        if (row <= 0 || values.getAsLong(Events.CALENDAR_ID) != calendar ||
            values.getAsInteger(Events.DIRTY) != 1 || values.getAsInteger(Events.DELETED) != 0) return null
        val localOwnerPlaceholder = personal.knownLocalPlaceholder(values, account)
        val seriesSnapshot = if (seriesOnly) nativeCreateSeriesSnapshot(values, localOwnerPlaceholder, readSimpleTime) ?: return null else null
        val rawColor = values.getAsInteger(Events.EVENT_COLOR)
        val colorKeyChecks = nativeEventColorKeyAssertions(resolver, account, values, syncUri, requirePermissions, fail) ?: return null
        if (rawColor != null && rawColor != 0 && rawColor.ushr(24) != 0xff) return null
        if (syncColumns.any { column ->
                if (firstRow == null && column == Events.SYNC_DATA5) values.getAsString(column) != marker
                else values[column] != null
            } || unsupported.any { values[it] != null && it != Events.EVENT_COLOR_KEY && !(it == Events.ORGANIZER && localOwnerPlaceholder) &&
                !(seriesSnapshot != null && it in setOf(Events.RRULE, Events.DURATION)) } ||
            (!localOwnerPlaceholder && emptyFlags.any { values.getAsInteger(it) != 0 }) ||
            values.getAsInteger(Events.STATUS) != Events.STATUS_CONFIRMED ||
            values.getAsInteger(Events.AVAILABILITY) != Events.AVAILABILITY_BUSY) return null
        val time = seriesSnapshot?.first ?: readSimpleTime(values, localOwnerPlaceholder) ?: return null
        val shared = EventContent(values.getAsString(Events.TITLE) ?: "", time,
            values.getAsString(Events.DESCRIPTION) ?: "", values.getAsString(Events.EVENT_LOCATION) ?: "",
            details = EventDetails())
        val reminders = personal.captureReminders(account, row, shared) ?: return null
        if (values.getAsInteger(Events.HAS_ALARM) != (if (reminders.reminders.isEmpty()) 0 else 1)) return null
        val content = shared.copy(details = EventDetails(reminders.reminders),
            colorRgb = rawColor?.takeUnless { it == 0 }?.and(0xffffff))
        val checks = arrayListOf(assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), calendar),
            ContentProviderOperation.newAssertQuery(uri).withSelection(selection.sql, selection.args)
                .withValues(values).withExpectedCount(1).build(),
            ContentProviderOperation.newAssertQuery(uri).withSelection(markerSelection.sql, markerSelection.args)
                .withExpectedCount(if (firstRow == null) 1 else 0).build())
        val expected = mutableListOf(1, 1, if (firstRow == null) 1 else 0)
        checks.addAll(colorKeyChecks); expected.addAll(colorKeyChecks.map { 1 })
        personal.assertReminders(reminders, checks, expected)
        if (seriesSnapshot != null) {
            val children = "${Events.ORIGINAL_ID}=?"
            val args = arrayOf(row.toString())
            val absent = resolver.query(uri, arrayOf(Events._ID), children, args, null)?.use { it.count == 0 }
                ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
            if (!absent) return null
            checks += ContentProviderOperation.newAssertQuery(uri).withSelection(children, args).withExpectedCount(0).build()
            expected += 0
        }
        for ((child, column) in listOf(Attendees.CONTENT_URI to Attendees.EVENT_ID,
            ExtendedProperties.CONTENT_URI to ExtendedProperties.EVENT_ID)) {
            val absent = resolver.query(syncUri(child, account), arrayOf("_id"), "$column=?", arrayOf(row.toString()), null)
                ?.use { it.count == 0 } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
            if (!absent) return null
            checks += ContentProviderOperation.newAssertQuery(syncUri(child, account))
                .withSelection("$column=?", arrayOf(row.toString())).withExpectedCount(0).build()
            expected += 0
        }
        if (firstRow != null) checks += ContentProviderOperation.newUpdate(uri)
            .withSelection(selection.sql, selection.args).withValue(Events.SYNC_DATA5, marker).withExpectedCount(1).build()
        requirePermissions()
        if (firstRow != null) expected += 1
        capturedBatch(checks, expected.toIntArray())
        requirePermissions()
        return OwnedLocalEditSnapshot(calendar, row, content, seriesSnapshot?.second)
    }

}
