// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import android.content.ContentProviderOperation
import android.content.ContentValues
import android.database.DatabaseUtils
import android.provider.CalendarContract.*
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.ATTACHMENT_SYNC_COLUMNS
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.ORDINARY_RESERVED_COLUMNS
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.OWNER_MARKER
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.digest
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.fail
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.ordinaryReceiptMarker
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneOffset

/** Android locators never enter the candidate's domain identity. No stamping or write admission. */
data class OwnedOccurrenceEditSnapshot(val masterRow: Long, val eventRow: Long, val candidate: OccurrenceEditCandidate) {
    init { require(masterRow > 0 && eventRow > 0 && masterRow != eventRow) }
    override fun toString() = "OwnedOccurrenceEditSnapshot(REDACTED)"
}

internal suspend fun AndroidCalendarProjection.readOccurrenceEdit(scope: CalendarScope,
    master: RemoteChange.Upsert, childRow: Long): OwnedOccurrenceEditSnapshot? {
    // Freeze caller-owned lists before the first suspension. This grants no remote authority.
    val frozen = try { OccurrenceEditCandidate.freezeMaster(master) }
        catch (_: IllegalArgumentException) { return null }
        catch (_: java.nio.charset.CharacterCodingException) { return null }
    return operation { observeOccurrence(scope, frozen, childRow) }
}

private fun AndroidCalendarProjection.observeOccurrence(scope: CalendarScope, master: RemoteChange.Upsert,
    childRow: Long): OwnedOccurrenceEditSnapshot? {
    if (childRow <= 0) return null
    val account = accountFor(scope.account)
    val calendar = calendarRow(scope, account) ?: return null
    val uri = syncUri(Events.CONTENT_URI, account)
    val series = digest("series-v1", calendarSyncId(scope), master.content.recurrence!!.uid)
    val remote = digest("event-v1", calendarSyncId(scope), master.eventId.value)
    val masterWhere = "${Events.CALENDAR_ID}=? AND (${Events._SYNC_ID}=? OR ${Events.SYNC_DATA4}=?)"
    val masterArgs = arrayOf(calendar.toString(), series, remote)
    fun row(where: String, args: Array<String>): ContentValues? {
        requirePermissions()
        return resolver.query(uri, OCCURRENCE_COLUMNS, where, args, null)?.use { cursor ->
            if (cursor.count != 1 || !cursor.moveToFirst()) return null
            ContentValues().apply { DatabaseUtils.cursorRowToContentValues(cursor, this) }
        } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    }
    val parent = row(masterWhere, masterArgs) ?: return null
    val parentId = parent.getAsLong(Events._ID) ?: return null
    if (parentId <= 0 || parentId == childRow || parent.getAsString(Events._SYNC_ID) != series ||
        parent.getAsString(Events.SYNC_DATA4) != remote || parent.getAsString(Events.SYNC_DATA1) != OWNER_MARKER ||
        parent.getAsString(Events.SYNC_DATA2) != digest("revision-v1", master.revision.value) ||
        ORDINARY_RESERVED_COLUMNS.any { parent[it] != null } ||
        !ordinaryReceiptMarker(parent.getAsString(Events.SYNC_DATA3))) return null
    val style = NativeColorStyle.read(parent.getAsString(Events.SYNC_DATA3)) ?: return null
    val parentColor = parent.getAsInteger(Events.EVENT_COLOR)
    if (parentColor != null && parentColor != 0 && parentColor.ushr(24) != 0xff) return null
    if (parent.getAsInteger(Events.SELF_ATTENDEE_STATUS) != 0 || parent.getAsInteger(Events.ACCESS_LEVEL) != 0) return null
    if (!style.accepts(master.content.colorRgb, parent.getAsInteger(Events.EVENT_COLOR)) ||
        style.logicalColor(parent.getAsInteger(Events.EVENT_COLOR)) != master.content.colorRgb) return null
    val expectedParent = eventValues(master, account)
    if (expectedParent.keySet().any { it !in setOf(Events.SYNC_DATA3, Events.EVENT_COLOR, Events.EVENT_COLOR_KEY) &&
            parent.getAsString(it) != expectedParent.getAsString(it) }) return null
    val childWhere = "${Events.CALENDAR_ID}=? AND ${Events._ID}=?"
    val childArgs = arrayOf(calendar.toString(), childRow.toString())
    val child = row(childWhere, childArgs) ?: return null
    if (child.getAsLong(Events.ORIGINAL_ID) != parentId ||
        (child[Events.ORIGINAL_SYNC_ID] != null && child.getAsString(Events.ORIGINAL_SYNC_ID) != series) ||
        child.getAsInteger(Events.DIRTY) != 1 || child.getAsInteger(Events.DELETED) != 0 ||
        child.getAsInteger(Events.STATUS) != Events.STATUS_CONFIRMED ||
        ATTACHMENT_SYNC_COLUMNS.any { child[it] != null } ||
        listOf(Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DURATION,
            Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI).any { child[it] != null }) return null
    val rawOriginal = child.getAsLong(Events.ORIGINAL_INSTANCE_TIME) ?: return null
    if (rawOriginal % 1000L != 0L) return null
    val original = when (master.content.time) {
        is EventTime.AllDay -> {
            if (child.getAsInteger(Events.ORIGINAL_ALL_DAY) != 1 || rawOriginal % 86_400_000L != 0L) return null
            RecurrencePoint.AllDay(Instant.ofEpochMilli(rawOriginal).atOffset(ZoneOffset.UTC).toLocalDate())
        }
        is EventTime.Timed -> {
            if (child.getAsInteger(Events.ORIGINAL_ALL_DAY) != 0) return null
            RecurrencePoint.Timed(Instant.ofEpochMilli(rawOriginal))
        }
    }
    val placeholder = personalMetadata.knownLocalPlaceholder(child, account)
    val time = readSimpleTime(child, placeholder, personalAllDayUtcAlias = true) ?: return null
    val rawColor = child.getAsInteger(Events.EVENT_COLOR)
    if (rawColor != null && rawColor != 0 && rawColor.ushr(24) != 0xff) return null
    // An unstamped child has no saved mask witness. Null cannot prove a user's color choice.
    if (colorMode() == EventColorMode.CALENDAR && rawColor == null && master.content.colorRgb != null) return null
    val personal = EventContent(child.getAsString(Events.TITLE).orEmpty(), time,
        child.getAsString(Events.DESCRIPTION).orEmpty(), child.getAsString(Events.EVENT_LOCATION).orEmpty(),
        colorRgb = rawColor?.takeUnless { it == 0 }?.and(0xffffff), details = EventDetails())
    val personalOwner = knownPersonalOwner(child, account, personal)
    if (child[Events.ORGANIZER] != null && !personalOwner && !placeholder) return null
    if (!personalOwner && !placeholder && OCCURRENCE_FLAGS.any { child.getAsInteger(it) != 0 }) return null
    if (listOf(parent, child).any { values -> values.getAsInteger(Events.AVAILABILITY) != Events.AVAILABILITY_BUSY ||
            values[Events.CUSTOM_APP_PACKAGE] != null || values[Events.CUSTOM_APP_URI] != null }) return null
    val parentAlerts = personalMetadata.captureReminders(account, parentId, master.content) ?: return null
    if (parentAlerts.reminders != master.content.details!!.reminders ||
        parent.getAsInteger(Events.HAS_ALARM) != if (parentAlerts.reminders.isEmpty()) 0 else 1) return null
    val childAlerts = personalMetadata.captureReminders(account, childRow, personal) ?: return null
    if (child.getAsInteger(Events.HAS_ALARM) != if (childAlerts.reminders.isEmpty()) 0 else 1) return null
    val candidate = try { OccurrenceEditCandidate(master, original, personal.copy(details = EventDetails(childAlerts.reminders))) }
        catch (_: IllegalArgumentException) { return null }
    val familyWhere = "${Events.ORIGINAL_ID}=? OR (${Events.CALENDAR_ID}=? AND ${Events.ORIGINAL_SYNC_ID}=?)"
    val familyArgs = arrayOf(parentId.toString(), calendar.toString(), series)
    val family = row(familyWhere, familyArgs) ?: return null
    if (family.getAsLong(Events._ID) != childRow || family.getAsLong(Events.CALENDAR_ID) != calendar) return null
    val checks = arrayListOf(assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), calendar),
        ContentProviderOperation.newAssertQuery(uri).withSelection(masterWhere, masterArgs).withValues(parent).withExpectedCount(1).build(),
        ContentProviderOperation.newAssertQuery(uri).withSelection(childWhere, childArgs).withValues(child).withExpectedCount(1).build(),
        ContentProviderOperation.newAssertQuery(uri).withSelection(familyWhere, familyArgs).withValue(Events._ID, childRow).withExpectedCount(1).build())
    val expected = mutableListOf(1, 1, 1, 1)
    for (values in listOf(parent, child)) {
        val color = nativeEventColorKeyAssertions(resolver, account, values, ::syncUri, ::requirePermissions, ::fail) ?: return null
        checks.addAll(color); expected.addAll(color.map { 1 })
    }
    personalMetadata.assertReminders(parentAlerts, checks, expected)
    personalMetadata.assertReminders(childAlerts, checks, expected)
    for (id in listOf(parentId, childRow)) for ((source, column) in listOf(Attendees.CONTENT_URI to Attendees.EVENT_ID,
        ExtendedProperties.CONTENT_URI to ExtendedProperties.EVENT_ID)) {
        val childUri = syncUri(source, account)
        val args = arrayOf(id.toString())
        requirePermissions()
        val absent = resolver.query(childUri, arrayOf("_id"), "$column=?", args, null)?.use { it.count == 0 }
            ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        if (!absent) return null
        checks += ContentProviderOperation.newAssertQuery(childUri).withSelection("$column=?", args).withExpectedCount(0).build()
        expected += 0
    }
    requirePermissions(); capturedBatch(checks, expected.toIntArray()); requirePermissions()
    return OwnedOccurrenceEditSnapshot(parentId, childRow, candidate)
}

private val OCCURRENCE_FLAGS = arrayOf(Events.HAS_ATTENDEE_DATA, Events.IS_ORGANIZER, Events.GUESTS_CAN_MODIFY,
    Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS, Events.SELF_ATTENDEE_STATUS, Events.ACCESS_LEVEL)
private val OCCURRENCE_COLUMNS = arrayOf(Events._ID, Events.CALENDAR_ID, Events.DIRTY, Events.DELETED, Events.TITLE,
    Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND, Events.ALL_DAY,
    Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.EVENT_COLOR, Events.EVENT_COLOR_KEY,
    Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DURATION,
    Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY,
    Events.ORGANIZER, Events.STATUS, Events.AVAILABILITY, Events.HAS_ALARM, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI) +
    ATTACHMENT_SYNC_COLUMNS + OCCURRENCE_FLAGS
