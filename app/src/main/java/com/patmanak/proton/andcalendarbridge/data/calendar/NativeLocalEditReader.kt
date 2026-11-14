// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import com.patmanak.proton.andcalendarbridge.data.calendar.NativeCalendarSelection as Selection
import com.patmanak.proton.andcalendarbridge.sync.DeleteAcknowledgementReceipt
import android.accounts.Account
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.database.DatabaseUtils
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Reminders
import android.provider.CalendarContract.ExtendedProperties
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.EventTime
import com.patmanak.proton.andcalendarbridge.sync.PersonalEditCandidate
import com.patmanak.proton.andcalendarbridge.sync.SeriesEditCandidate
import com.patmanak.proton.andcalendarbridge.sync.SeriesEditProfile
import com.patmanak.proton.andcalendarbridge.sync.MutationId
import com.patmanak.proton.andcalendarbridge.sync.AdapterFormat
import com.patmanak.proton.andcalendarbridge.sync.LocalEditIntent
import com.patmanak.proton.andcalendarbridge.sync.RemoteChange
import com.patmanak.proton.andcalendarbridge.sync.EventContent
import com.patmanak.proton.andcalendarbridge.sync.EventDetails
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.Instant
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.CAPTURED_ORIGIN_COLUMNS
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.ORDINARY_RESERVED_COLUMNS
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.OWNER_MARKER
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.digest
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.fail
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.ordinaryReceiptMarker
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.NativeEditAcknowledgement

internal fun AndroidCalendarProjection.captureSimpleSnapshot(scope: CalendarScope, base: RemoteChange.Upsert, deleted: Boolean,
    acknowledgement: NativeEditAcknowledgement? = null, deleteAcknowledgement: DeleteAcknowledgementReceipt? = null,
    checkDeleteActive: () -> Unit = {}, personal: Boolean = false, series: Boolean = false): OwnedLocalEditSnapshot? {
    require(acknowledgement == null || deleteAcknowledgement == null)
    require(!series || (personal && !deleted && acknowledgement == null && deleteAcknowledgement == null))
    val acknowledging = acknowledgement != null || deleteAcknowledgement != null
    fun checkPermissions() {
        checkDeleteActive(); requireEditPermissions(acknowledgement); checkDeleteActive()
    }
    if (series) {
        try { SeriesEditProfile.of(base.content, base.content) }
        catch (_: IllegalArgumentException) { return null }
    } else if (base.content.recurrence != null) return null
    val personalBase = if (series) base.content.copy(recurrence = null) else base.content
    if (personal) {
        if (!knownPersonalEvent(personalBase)) return null
    } else if (base.content.details != null && base.content.details != EventDetails()) return null
    val account = accountFor(scope.account)
    val calendar = calendarRow(scope, account) ?: return null
    val remoteId = digest("event-v1", calendarSyncId(scope), base.eventId.value)
    val syncId = if (series) digest("series-v1", calendarSyncId(scope), base.content.recurrence!!.uid) else remoteId
    val uri = syncUri(Events.CONTENT_URI, account)
    val identity = Selection("${Events.CALENDAR_ID}=? AND (${Events.SYNC_DATA4}=? OR " +
        "(${Events.SYNC_DATA4} IS NULL AND ${Events._SYNC_ID}=?))", arrayOf(calendar.toString(), remoteId, remoteId))
    val row = ownedRow(uri, identity, Events.SYNC_DATA1) ?: return null
    val columns = arrayOf(Events._ID, Events.CALENDAR_ID, Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2,
        Events.SYNC_DATA4, Events.SYNC_DATA5, Events.SYNC_DATA6, Events.SYNC_DATA7,
        Events.DIRTY, Events.DELETED, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION,
        Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE,
        Events.EVENT_COLOR, Events.EVENT_COLOR_KEY, Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE,
        Events.DURATION, Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME,
        Events.ORIGINAL_ALL_DAY, Events.STATUS, Events.HAS_ATTENDEE_DATA, Events.ORGANIZER, Events.IS_ORGANIZER,
        Events.GUESTS_CAN_MODIFY, Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS,
        Events.HAS_ALARM, Events.SELF_ATTENDEE_STATUS, Events.AVAILABILITY, Events.ACCESS_LEVEL,
        Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI) + if (!acknowledging && !personal) emptyArray() else
            arrayOf(Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)
    val observedColumns = columns + Events.SYNC_DATA3
    val values = resolver.query(uri, observedColumns, identity.sql, identity.args, null)?.use {
        if (it.count != 1 || !it.moveToFirst()) return null
        ContentValues().apply { DatabaseUtils.cursorRowToContentValues(it, this) }
    } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    if (values.getAsLong(Events._ID) != row || values.getAsString(Events._SYNC_ID) != syncId ||
        values.getAsString(Events.SYNC_DATA1) != OWNER_MARKER ||
        values.getAsString(Events.SYNC_DATA2) != digest("revision-v1", base.revision.value) ||
        CAPTURED_ORIGIN_COLUMNS.any { values[it] != null } ||
        values.getAsInteger(Events.DIRTY) != 1 || values.getAsInteger(Events.DELETED) != (if (deleted) 1 else 0))
        return null
    if ((acknowledging || personal) && (values.getAsString(Events.SYNC_DATA4) != remoteId ||
            ORDINARY_RESERVED_COLUMNS.any { values[it] != null } ||
            !ordinaryReceiptMarker(values.getAsString(Events.SYNC_DATA3)))) return null
    val unsupported = arrayOf(Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DURATION,
        Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY,
        Events.ORGANIZER, Events.EVENT_COLOR_KEY, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI)
    val personalOwner = if (personal) knownPersonalOwner(values, account, personalBase)
        else simplePersonalOwner(values, account, base.content)
    val seriesSnapshot = if (series) nativeCreateSeriesSnapshot(values, false) { logical, _ ->
        readSimpleTime(logical, personalAllDayUtcAlias = true)
    } ?: return null else null
    if (unsupported.any { values[it] != null && !(it == Events.ORGANIZER && personalOwner) &&
            !(seriesSnapshot != null && it in setOf(Events.RRULE, Events.DURATION)) }) return null
    val emptyFlags = arrayOf(Events.HAS_ATTENDEE_DATA, Events.IS_ORGANIZER, Events.GUESTS_CAN_MODIFY,
        Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS, Events.HAS_ALARM,
        Events.SELF_ATTENDEE_STATUS, Events.ACCESS_LEVEL)
    if (emptyFlags.any { if (personal && it == Events.HAS_ALARM)
            values.getAsInteger(it) !in 0..1
            else !simplePersonalFlag(values, it, personalOwner) } ||
        values.getAsInteger(Events.STATUS) != Events.STATUS_CONFIRMED ||
        values.getAsInteger(Events.AVAILABILITY) != Events.AVAILABILITY_BUSY) return null
    val expectedColor = base.content.colorRgb?.or(0xFF000000.toInt())
    val rawColor = values.getAsInteger(Events.EVENT_COLOR)
    val style = NativeColorStyle.read(values.getAsString(Events.SYNC_DATA3)) ?: return null
    if (!style.accepts(base.content.colorRgb, rawColor)) return null
    if (!personal && !style.masked && rawColor != expectedColor) return null
    if (personal && rawColor != null && rawColor != 0 && rawColor.ushr(24) != 0xff) return null
    val time = seriesSnapshot?.first ?: readSimpleTime(values, personalAllDayUtcAlias = personal) ?: return null
    val reminders = if (personal) personalMetadata.captureReminders(account, row, base.content) ?: return null else null
    if (reminders != null && values.getAsInteger(Events.HAS_ALARM) != (if (reminders.reminders.isEmpty()) 0 else 1)) return null
    val content = EventContent(values.getAsString(Events.TITLE) ?: "", time,
        values.getAsString(Events.DESCRIPTION) ?: "", values.getAsString(Events.EVENT_LOCATION) ?: "",
        if (personal) style.logicalColor(rawColor) else base.content.colorRgb,
        recurrence = seriesSnapshot?.let { base.content.recurrence!!.copy(rule = it.second.rule) },
        details = if (reminders == null) base.content.details else base.content.details!!.copy(reminders = reminders.reminders))
    if (personal) {
        try {
            val intent = LocalEditIntent(MutationId("native-personal-observation"), scope,
                AdapterFormat("native-personal-observation", 1), base, content,
                seriesProfile = if (series) SeriesEditProfile.of(base.content, content) else null)
            if (series) SeriesEditCandidate(intent) else PersonalEditCandidate(intent)
        } catch (_: IllegalArgumentException) { return null }
    }
    if (!deleted && content == base.content) return null
    if (acknowledgement != null && content != acknowledgement.intent.content) return null
    if (deleteAcknowledgement != null && content != deleteAcknowledgement.intent.content) return null
    // Capture stays assertion-only. ACK asserts the complete raw edit; its typed recipe
    // adds metadata and, for text/time receipts, verified CRLF/LF differences.
    val checks = arrayListOf(assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), calendar),
        assertion(uri, identity, row),
        ContentProviderOperation.newAssertQuery(uri).withSelection(identity.sql, identity.args)
            .withValues(values).withExpectedCount(1).build())
    val expected = mutableListOf(1, 1, 1)
    if (series) {
        val children = "${Events.ORIGINAL_ID}=? OR (${Events.CALENDAR_ID}=? AND ${Events.ORIGINAL_SYNC_ID}=?)"
        val args = arrayOf(row.toString(), calendar.toString(), syncId)
        val absent = resolver.query(uri, arrayOf(Events._ID), children, args, null)?.use { it.count == 0 }
            ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        if (!absent) return null
        checks += ContentProviderOperation.newAssertQuery(uri).withSelection(children, args).withExpectedCount(0).build()
        expected += 0
    }
    if (acknowledging || personal) {
        val syncIdentity = Selection("${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=?", arrayOf(calendar.toString(), syncId))
        if (ownedRow(uri, syncIdentity, Events.SYNC_DATA1) != row) return null
        checks += assertion(uri, syncIdentity, row)
        expected += 1
    }
    if (reminders != null) personalMetadata.assertReminders(reminders, checks, expected)
    val absentChildren = if (personal) listOf(Attendees.CONTENT_URI to Attendees.EVENT_ID,
        ExtendedProperties.CONTENT_URI to ExtendedProperties.EVENT_ID) else listOf(
        Reminders.CONTENT_URI to Reminders.EVENT_ID, Attendees.CONTENT_URI to Attendees.EVENT_ID,
        ExtendedProperties.CONTENT_URI to ExtendedProperties.EVENT_ID)
    for ((child, column) in absentChildren) {
        val childrenAbsent = resolver.query(syncUri(child, account), arrayOf("_id"),
            "$column=?", arrayOf(row.toString()), null)?.use { it.count == 0 }
            ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        if (!childrenAbsent) return null
        checks += ContentProviderOperation.newAssertQuery(syncUri(child, account))
            .withSelection("$column=?", arrayOf(row.toString())).withExpectedCount(0).build()
        expected += 0
    }
    if (acknowledgement != null) {
        checks += ContentProviderOperation.newUpdate(uri).withSelection(identity.sql, identity.args)
            .withValues(displayColor(acknowledgement.values(receiptMarker(acknowledgement.projection)),
                acknowledgement.projection.change.content.colorRgb, receiptMarker(acknowledgement.projection),
                values.getAsString(Events.SYNC_DATA3)))
            .withExpectedCount(1).build()
        expected += 1
    }
    if (deleteAcknowledgement != null) {
        val changes = ContentValues().apply {
            put(Events.DIRTY, 0); put(Events.SYNC_DATA3, deleteReceiptMarker(deleteAcknowledgement))
        }
        checks += ContentProviderOperation.newUpdate(uri).withSelection(identity.sql, identity.args)
            .withValues(changes).withExpectedCount(1).build()
        expected += 1
        // A Provider that purges a clean tombstone must roll back, never produce absence proof.
        val retained = ContentValues(values).apply { putAll(changes) }
        checks += ContentProviderOperation.newAssertQuery(uri).withSelection(identity.sql, identity.args)
            .withValues(retained).withExpectedCount(1).build()
        expected += 1
    }
    checkPermissions()
    capturedBatch(checks, expected.toIntArray())
    checkPermissions()
    return OwnedLocalEditSnapshot(calendar, row, content)
}

internal fun AndroidCalendarProjection.readSimpleTime(values: ContentValues, localOwnerPlaceholder: Boolean = false,
    personalAllDayUtcAlias: Boolean = false): EventTime? {
    val start = values.getAsLong(Events.DTSTART) ?: return null
    val end = values.getAsLong(Events.DTEND) ?: return null
    if (end <= start || start % 1000L != 0L || end % 1000L != 0L) return null
    val zone = values.getAsString(Events.EVENT_TIMEZONE) ?: return null
    val endZone = values.getAsString(Events.EVENT_END_TIMEZONE)
    return try {
        // An editor can retain the timed endpoint's explicit UTC alias (for
        // example Z) when converting to date-only UTC. Keep its raw token
        // in the Provider assertion; only this personal all-day reader opts in.
        val fixedUtcEnd = personalAllDayUtcAlias && values.getAsInteger(Events.ALL_DAY) == 1 &&
            zone == "UTC" && endZone != null && ZoneId.of(endZone).normalized() == ZoneOffset.UTC
        if (endZone != zone && !(localOwnerPlaceholder && endZone == null) && !fixedUtcEnd) return null
        when (values.getAsInteger(Events.ALL_DAY)) {
            0 -> EventTime.Timed(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end), ZoneId.of(zone))
            1 -> {
                if (zone != "UTC" || start % 86_400_000L != 0L || end % 86_400_000L != 0L) return null
                EventTime.AllDay(Instant.ofEpochMilli(start).atOffset(ZoneOffset.UTC).toLocalDate(),
                    Instant.ofEpochMilli(end).atOffset(ZoneOffset.UTC).toLocalDate())
            }
            else -> null
        }
    } catch (_: IllegalArgumentException) { null }
      catch (_: java.time.DateTimeException) { null }
}

/** Native local owner label only, not an invited participant. Used solely for new/captured
 * CREATE with empty child tables, preserving the exact row in every capture/ACK assertion.
 */
internal fun AndroidCalendarProjection.simpleLocalOwnerPlaceholder(values: ContentValues, account: Account): Boolean =
    values.getAsString(Events.ORGANIZER) == account.name &&
        listOf(Events.HAS_ATTENDEE_DATA, Events.IS_ORGANIZER, Events.GUESTS_CAN_INVITE_OTHERS,
            Events.GUESTS_CAN_SEE_GUESTS).all { values.getAsInteger(it) == 1 } &&
        listOf(Events.GUESTS_CAN_MODIFY, Events.HAS_ALARM, Events.SELF_ATTENDEE_STATUS,
            Events.ACCESS_LEVEL).all { values.getAsInteger(it) == 0 }

internal fun AndroidCalendarProjection.knownPersonalEvent(content: EventContent): Boolean = personalMetadata.knownPersonalEvent(content)

internal fun AndroidCalendarProjection.knownPersonalOwner(values: ContentValues, account: Account, content: EventContent): Boolean =
    personalMetadata.knownPersonalOwner(values, account, content)

/** The original empty-metadata profile remains strict for legacy capture and ACK families. */
internal fun AndroidCalendarProjection.simplePersonalEvent(content: EventContent): Boolean =
    content.recurrence == null && content.details == EventDetails()

internal fun AndroidCalendarProjection.simplePersonalOwner(values: ContentValues, account: Account, content: EventContent): Boolean =
    simplePersonalEvent(content) && values.getAsString(Events.ORGANIZER) == account.name &&
        values.getAsInteger(Events.IS_ORGANIZER) == 1 &&
        listOf(Events.HAS_ATTENDEE_DATA, Events.GUESTS_CAN_MODIFY, Events.GUESTS_CAN_INVITE_OTHERS,
            Events.GUESTS_CAN_SEE_GUESTS, Events.HAS_ALARM, Events.SELF_ATTENDEE_STATUS,
            Events.ACCESS_LEVEL).all { values.getAsInteger(it) == 0 }

internal fun AndroidCalendarProjection.simplePersonalFlag(values: ContentValues, column: String, personalOwner: Boolean): Boolean {
    val flag = values.getAsInteger(column)
    return flag == 0 || (column == Events.IS_ORGANIZER && flag == 1 && personalOwner)
}
