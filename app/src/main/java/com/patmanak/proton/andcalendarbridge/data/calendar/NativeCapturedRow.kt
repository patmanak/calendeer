// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import com.patmanak.proton.andcalendarbridge.sync.supportsCapturedHandoff
import com.patmanak.proton.andcalendarbridge.data.calendar.NativeCalendarSelection as Selection
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.database.DatabaseUtils
import android.net.Uri
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Reminders
import android.provider.CalendarContract.ExtendedProperties
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateAttachmentReceipt
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateBaseline
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateCurrent
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateAcknowledgementReceipt
import com.patmanak.proton.andcalendarbridge.sync.CapturedCreateHandoffFrame
import com.patmanak.proton.andcalendarbridge.sync.CapturedHandoffCurrent
import com.patmanak.proton.andcalendarbridge.sync.CapturedHandoffProjection
import com.patmanak.proton.andcalendarbridge.sync.EventContent
import com.patmanak.proton.andcalendarbridge.sync.EventDetails
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.ATTACHMENT_SYNC_COLUMNS
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.OWNER_MARKER
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.digest
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.fail
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.strictAttachmentDigest
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.NativeEditAcknowledgement

internal fun AndroidCalendarProjection.captureCapturedCurrent(baseline: CapturedCreateBaseline,
    expectedAck: CapturedCreateAcknowledgementReceipt? = null,
    writeAck: CapturedCreateAcknowledgementReceipt? = null,
    clean: Boolean = false,
    handoff: CapturedCreateHandoffFrame? = null,
    handoffProjection: CapturedHandoffProjection? = null,
    editAcknowledgement: NativeEditAcknowledgement? = null): CapturedNativeObservation {
    if (editAcknowledgement != null) {
        require(!clean && writeAck == null && handoffProjection == null && handoff != null &&
            editAcknowledgement.handoff == handoff && expectedAck == handoff.receipt.acknowledgement)
    }
    val personal = handoff != null || !supportsCapturedHandoff(baseline.change)
    val personalBase = handoff?.base ?: baseline.change
    val ackMarker = (expectedAck ?: writeAck)?.let(::capturedAcknowledgementMarker)
    val receipt = baseline.receipt
    val expected = capturedAttachmentValues(receipt) // Strict canonical identity before Provider IO.
    if (expectedAck != null) expected.put(Events.SYNC_DATA7, ackMarker)
    if (handoff != null) {
        expected.put(Events.SYNC_DATA2, digest("revision-v1", handoff.base.revision.value))
        expected.put(Events.SYNC_DATA3, handoff.lastProjection?.let(::receiptMarker))
    }
    val scope = baseline.scope
    val account = accountFor(scope.account)
    val calendar = try { calendarRow(scope, account) } catch (failure: CalendarProjectionException) {
        if (failure.category == CalendarProjectionFailure.IDENTITY_CONFLICT)
            return CapturedNativeObservation.Unresolved
        throw failure
    } ?: return CapturedNativeObservation.Unresolved
    val uri = syncUri(Events.CONTENT_URI, account)
    val local = capturedLocalSelection(receipt, calendar)
    val unsupported = arrayOf(Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DURATION,
        Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY,
        Events.ORGANIZER, Events.EVENT_COLOR, Events.EVENT_COLOR_KEY, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI)
    val emptyFlags = arrayOf(Events.HAS_ATTENDEE_DATA, Events.IS_ORGANIZER, Events.GUESTS_CAN_MODIFY,
        Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS, Events.HAS_ALARM,
        Events.SELF_ATTENDEE_STATUS, Events.ACCESS_LEVEL)
    val columns = arrayOf(Events._ID, Events.CALENDAR_ID, Events.DIRTY, Events.DELETED, Events.TITLE,
        Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND, Events.ALL_DAY,
        Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.STATUS, Events.AVAILABILITY) +
        ATTACHMENT_SYNC_COLUMNS + unsupported + emptyFlags
    val values = resolver.query(uri, columns, local.sql, local.args, null)?.use {
        if (it.count != 1 || !it.moveToFirst()) return CapturedNativeObservation.Unresolved
        ContentValues().apply { DatabaseUtils.cursorRowToContentValues(it, this) }
    } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    val row = values.getAsLong(Events._ID)
    val deleted = values.getAsInteger(Events.DELETED)
    if (row == null || row <= 0 || values.getAsLong(Events.CALENDAR_ID) != calendar ||
        values.getAsInteger(Events.DIRTY) != (if (clean) 0 else 1) ||
        (if (clean) deleted != 0 else deleted != 0 && deleted != 1) ||
        values.getAsString(Events.SYNC_DATA5) != local.args[1] ||
        !colorMetadataMatches(values, expected))
        return CapturedNativeObservation.Unresolved
    val remote = capturedRemoteSelection(receipt, calendar)
    val remoteMatches = resolver.query(uri, arrayOf(Events._ID), remote.sql, remote.args, null)?.use {
        it.count == 1 && it.moveToFirst() && it.getLong(0) == row
    } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    if (!remoteMatches) return CapturedNativeObservation.Unresolved
    val localOwnerPlaceholder = if (personal) personalMetadata.knownLocalPlaceholder(values, account)
        else simpleLocalOwnerPlaceholder(values, account)
    val personalOwner = personal && knownPersonalOwner(values, account, personalBase.content)
    val rawColor = values.getAsInteger(Events.EVENT_COLOR)
    val style = NativeColorStyle.read(values.getAsString(Events.SYNC_DATA3))
        ?: return CapturedNativeObservation.Unsupported
    if (!style.accepts(personalBase.content.colorRgb, rawColor)) return CapturedNativeObservation.Unsupported
    if (personal && rawColor != null && rawColor != 0 && rawColor.ushr(24) != 0xff)
        return CapturedNativeObservation.Unsupported
    if (unsupported.any { values[it] != null && !(it == Events.EVENT_COLOR && (personal || rawColor == 0)) &&
            !(it == Events.ORGANIZER && (localOwnerPlaceholder || personalOwner)) } ||
        (!localOwnerPlaceholder && emptyFlags.any { if (personal && it == Events.HAS_ALARM)
            values.getAsInteger(it) !in 0..1 else !simplePersonalFlag(values, it, personalOwner) }) ||
        values.getAsInteger(Events.STATUS) != Events.STATUS_CONFIRMED ||
        values.getAsInteger(Events.AVAILABILITY) != Events.AVAILABILITY_BUSY)
        return CapturedNativeObservation.Unsupported
    val time = readSimpleTime(values, localOwnerPlaceholder) ?: return CapturedNativeObservation.Unsupported
    val reminders = if (personal) personalMetadata.captureReminders(account, row, personalBase.content)
        ?: return CapturedNativeObservation.Unsupported else null
    if (reminders != null && values.getAsInteger(Events.HAS_ALARM) != (if (reminders.reminders.isEmpty()) 0 else 1))
        return CapturedNativeObservation.Unsupported
    val snapshot = try {
        val content = EventContent(values.getAsString(Events.TITLE) ?: "", time,
            values.getAsString(Events.DESCRIPTION) ?: "", values.getAsString(Events.EVENT_LOCATION) ?: "",
            colorRgb = if (!personal) null else style.logicalColor(rawColor),
            details = EventDetails(reminders?.reminders.orEmpty()))
        if (handoff == null) CapturedCreateCurrent(baseline, content, deleted == 1, acknowledgement = expectedAck)
        else CapturedHandoffCurrent(handoff, content, deleted == 1)
        CapturedNativeObservation.Snapshot(content, deleted == 1)
    } catch (_: IllegalArgumentException) { return CapturedNativeObservation.Unsupported }
    val unchanged = !snapshot.deleted && snapshot.content == (handoff?.base ?: baseline.change).content
    if ((writeAck != null || clean) && !unchanged) return CapturedNativeObservation.Unresolved
    if (editAcknowledgement != null && (snapshot.deleted || snapshot.content != editAcknowledgement.intent.content))
        return CapturedNativeObservation.Unresolved
    val checks = arrayListOf(assertion(syncUri(Calendars.CONTENT_URI, account), calendarSelection(scope, account), calendar),
        ContentProviderOperation.newAssertQuery(uri).withSelection(local.sql, local.args)
            .withValues(values).withExpectedCount(1).build(),
        ContentProviderOperation.newAssertQuery(uri).withSelection(remote.sql, remote.args)
            .withValue(Events._ID, row).withExpectedCount(1).build())
    val expectedCounts = mutableListOf(1, 1, 1)
    if (reminders != null) personalMetadata.assertReminders(reminders, checks, expectedCounts)
    val absentChildren = if (!personal) listOf(Reminders.CONTENT_URI to Reminders.EVENT_ID,
        Attendees.CONTENT_URI to Attendees.EVENT_ID, ExtendedProperties.CONTENT_URI to ExtendedProperties.EVENT_ID)
        else listOf(Attendees.CONTENT_URI to Attendees.EVENT_ID, ExtendedProperties.CONTENT_URI to ExtendedProperties.EVENT_ID)
    for ((child, column) in absentChildren) {
        val absent = resolver.query(syncUri(child, account), arrayOf("_id"), "$column=?", arrayOf(row.toString()), null)
            ?.use { it.count == 0 } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        if (!absent) return CapturedNativeObservation.Unsupported
        checks += ContentProviderOperation.newAssertQuery(syncUri(child, account))
            .withSelection("$column=?", arrayOf(row.toString())).withExpectedCount(0).build()
        expectedCounts += 0
    }
    if (writeAck != null) checks += ContentProviderOperation.newUpdate(uri)
        .withSelection(local.sql, local.args).withValue(Events.DIRTY, 0)
        .withValue(Events.SYNC_DATA7, ackMarker)
        .withValues(displayColor(ContentValues(), baseline.change.content.colorRgb,
            style.proof, values.getAsString(Events.SYNC_DATA3)))
        .withExpectedCount(1).build()
    if (editAcknowledgement != null) checks += ContentProviderOperation.newUpdate(uri)
        .withSelection(local.sql, local.args)
        .withValues(displayColor(editAcknowledgement.values(receiptMarker(editAcknowledgement.projection)),
            editAcknowledgement.projection.change.content.colorRgb, receiptMarker(editAcknowledgement.projection),
            values.getAsString(Events.SYNC_DATA3))).withExpectedCount(1).build()
    if (handoffProjection != null) checks += ContentProviderOperation.newUpdate(uri)
        .withSelection(local.sql, local.args).withValues(eventValues(handoffProjection.receipt.change, account).apply {
            put(Events.SYNC_DATA2, digest("revision-v1", handoffProjection.receipt.change.revision.value))
            displayColor(this, handoffProjection.receipt.change.content.colorRgb, receiptMarker(handoffProjection.receipt))
        }).withExpectedCount(1).build()
    if (writeAck != null || handoffProjection != null || editAcknowledgement != null) expectedCounts += 1
    // ACK and incoming handoff writes admit from current assertions, never a prior snapshot.
    // Every identity, content/flag value and child absence is qualified in this one batch.
    requireEditPermissions(editAcknowledgement)
    if (handoffProjection == null || handoffProjection.receipt.change.content.details == handoffProjection.frame.base.content.details)
        capturedBatch(checks, expectedCounts.toIntArray()) else {
        replaceDetails(checks, account, row, checks.lastIndex, handoffProjection.receipt.change.content)
        val results = checkedBatch(checks)
        for ((index, count) in expectedCounts.withIndex()) if (results[index].count != count)
            fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        if (results[expectedCounts.size].count != reminders!!.raw.size || results[expectedCounts.size + 1].count != 0)
            fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    }
    requireEditPermissions(editAcknowledgement)
    return CapturedNativeObservation.Supported(snapshot)
}

internal fun AndroidCalendarProjection.capturedAcknowledgementMarker(receipt: CapturedCreateAcknowledgementReceipt) =
    "calendeer-captured-ack-v1:" + receipt.fingerprint()

internal fun AndroidCalendarProjection.capturedAttachmentValues(receipt: CapturedCreateAttachmentReceipt) = ContentValues().apply {
    val identity = receipt.binding.identity
    val attempt = identity.attempt
    val remote = digest("event-v1", calendarSyncId(attempt.scope), receipt.binding.eventId.value)
    put(Events._SYNC_ID, remote); put(Events.SYNC_DATA1, OWNER_MARKER)
    put(Events.SYNC_DATA2, digest("revision-v1", receipt.binding.verifiedRevision.value)); put(Events.SYNC_DATA4, remote)
    // Bind the full immutable receipt, not just a nonce or historical row locator.
    put(Events.SYNC_DATA6, "calendeer-captured-attachment-v1:" + strictAttachmentDigest("captured-attachment-v1",
        attempt.scope.account.value, attempt.scope.calendar.value, attempt.scope.generation.toString(),
        attempt.id.value, attempt.format.id, attempt.format.version.toString(),
        attempt.payloadFingerprint, identity.localId.value, identity.captureId.value, identity.captureFormat.id,
        identity.captureFormat.version.toString(), identity.timestamp.toString(), identity.contentFingerprint,
        identity.fingerprintVersion.toString(), receipt.binding.eventId.value, receipt.binding.verifiedRevision.value,
        receipt.protocol.toString(), receipt.nonce))
}

internal fun AndroidCalendarProjection.capturedLocalSelection(receipt: CapturedCreateAttachmentReceipt, calendar: Long): Selection {
    val identity = receipt.binding.identity
    return Selection("${Events.CALENDAR_ID}=? AND ${Events.SYNC_DATA5}=?", arrayOf(calendar.toString(),
        digest("local-create-v1", calendarSyncId(identity.attempt.scope), identity.localId.value)))
}

internal fun AndroidCalendarProjection.capturedRemoteSelection(receipt: CapturedCreateAttachmentReceipt, calendar: Long): Selection {
    val remote = digest("event-v1", calendarSyncId(receipt.binding.identity.attempt.scope), receipt.binding.eventId.value)
    return Selection("${Events.CALENDAR_ID}=? AND (${Events._SYNC_ID}=? OR ${Events.SYNC_DATA4}=?)",
        arrayOf(calendar.toString(), remote, remote))
}

internal fun AndroidCalendarProjection.attachmentMetadata(uri: Uri, selection: Selection): ContentValues? {
    val cursor = resolver.query(uri, arrayOf(Events._ID, Events.CALENDAR_ID, Events.DIRTY, Events.DELETED) + ATTACHMENT_SYNC_COLUMNS,
        selection.sql, selection.args, null) ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
    return cursor.use {
        if (it.count != 1 || !it.moveToFirst()) null
        else ContentValues().apply { DatabaseUtils.cursorRowToContentValues(it, this) }
    }
}
