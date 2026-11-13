// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import android.accounts.Account
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Reminders
import com.patmanak.proton.andcalendarbridge.sync.EventTime
import com.patmanak.proton.andcalendarbridge.sync.RecurringLocalTimeRisk
import com.patmanak.proton.andcalendarbridge.sync.RecurrencePoint
import com.patmanak.proton.andcalendarbridge.sync.RemoteChange
import com.patmanak.proton.andcalendarbridge.sync.EventContent
import com.patmanak.proton.andcalendarbridge.sync.EventPerson
import com.patmanak.proton.andcalendarbridge.sync.AttendanceRole
import com.patmanak.proton.andcalendarbridge.sync.AttendanceStatus
import java.time.ZoneOffset
import java.time.Instant
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter



internal fun AndroidCalendarProjection.eventValues(change: RemoteChange.Upsert, account: Account): ContentValues = ContentValues().apply {
    put(Events.TITLE, change.content.title)
    put(Events.DESCRIPTION, change.content.description)
    put(Events.EVENT_LOCATION, change.content.location)
    putNull(Events.EVENT_COLOR_KEY)
    val color = change.content.colorRgb
    if (color == null) putNull(Events.EVENT_COLOR) else put(Events.EVENT_COLOR, color or 0xFF000000.toInt())
    put(Events.DIRTY, 0)
    put(Events.DELETED, 0)
    // A later untracked write must not leave an old receipt as proof of new content.
    putNull(Events.SYNC_DATA3)
    put(Events.STATUS, Events.STATUS_CONFIRMED)
    val details = change.content.details
    put(Events.HAS_ATTENDEE_DATA, if (details?.organizer != null || !details?.attendees.isNullOrEmpty()) 1 else 0)
    val organizer = details?.organizer
    val personalOwner = knownPersonalEvent(change.content)
    when {
        organizer != null -> put(Events.ORGANIZER, organizer.email)
        personalOwner -> put(Events.ORGANIZER, account.name)
        else -> putNull(Events.ORGANIZER)
    }
    // Provider defaults SELF_ATTENDEE_STATUS to NONE. Supplying it would create a
    // synthetic self-attendee on insert and is forbidden on event updates. Our owned
    // calendar's opaque owner name cannot match an attendee email, so no RSVP is inferred.
    // AOSP clients also check this event-level permission before opening their editor.
    // Use only the local calendar's opaque label; no participant is synthesized.
    put(Events.IS_ORGANIZER, if (personalOwner) 1 else 0)
    put(Events.GUESTS_CAN_MODIFY, 0)
    put(Events.GUESTS_CAN_INVITE_OTHERS, 0)
    put(Events.GUESTS_CAN_SEE_GUESTS, 0)
    putNull(Events.RRULE)
    putNull(Events.RDATE)
    putNull(Events.EXRULE)
    putNull(Events.EXDATE)
    putNull(Events.DURATION)
    putNull(Events.ORIGINAL_ID)
    putNull(Events.ORIGINAL_SYNC_ID)
    putNull(Events.ORIGINAL_INSTANCE_TIME)
    putNull(Events.ORIGINAL_ALL_DAY)
    when (val time = change.content.time) {
        is EventTime.Timed -> {
            put(Events.DTSTART, time.start.toEpochMilli())
            put(Events.DTEND, time.end.toEpochMilli())
            put(Events.ALL_DAY, 0)
            put(Events.EVENT_TIMEZONE, time.zone.id)
            put(Events.EVENT_END_TIMEZONE, time.zone.id)
        }
        is EventTime.AllDay -> {
            put(Events.DTSTART, time.start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            put(Events.DTEND, time.endExclusive.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            put(Events.ALL_DAY, 1)
            put(Events.EVENT_TIMEZONE, "UTC")
            put(Events.EVENT_END_TIMEZONE, "UTC")
        }
    }
    change.content.recurrence?.let { recurrence ->
        if (recurrence.isMaster) {
            putNull(Events.DTEND)
            // Explicit timed dates are absolute instants. Expanding them in a named
            // zone can merge the two occurrences of a repeated hour. Keep the domain
            // timezone in the import store/receipt, but expand this finite set in UTC.
            // RRULEs retain their zone because they describe recurring local times.
            if (recurrence.rule == null && change.content.time is EventTime.Timed) {
                put(Events.EVENT_TIMEZONE, "UTC")
                put(Events.EVENT_END_TIMEZONE, "UTC")
            }
            put(Events.DURATION, when (val time = change.content.time) {
                is EventTime.AllDay -> "P${ChronoUnit.DAYS.between(time.start, time.endExclusive)}D"
                is EventTime.Timed -> "PT${Duration.between(time.start, time.end).seconds}S"
            })
            recurrence.rule?.let { put(Events.RRULE, it) }
            val additionalDates = if (recurrence.rule == null) {
                // Android expands an RDATE-only set literally; RFC 5545 also includes DTSTART.
                val first = when (val time = change.content.time) {
                    is EventTime.AllDay -> RecurrencePoint.AllDay(time.start)
                    is EventTime.Timed -> RecurrencePoint.Timed(time.start)
                }
                (listOf(first) + recurrence.additionalDates).distinct()
            } else recurrence.additionalDates
            if (additionalDates.isNotEmpty()) put(Events.RDATE, recurrenceDates(additionalDates))
            if (recurrence.excludedDates.isNotEmpty()) put(Events.EXDATE, recurrenceDates(recurrence.excludedDates))
        } else {
            val original = recurrence.original!!
            put(Events.ORIGINAL_INSTANCE_TIME, recurrenceInstant(original))
            put(Events.ORIGINAL_ALL_DAY, if (original is RecurrencePoint.AllDay) 1 else 0)
            if (recurrence.cancelled) put(Events.STATUS, Events.STATUS_CANCELED)
        }
    }
}

/** Child rows have no ownership marker; the parent assertions above guard this whole batch. */
internal fun AndroidCalendarProjection.replaceDetails(operations: ArrayList<ContentProviderOperation>, account: Account,
    eventId: Long?, eventOperation: Int, content: EventContent) {
    val remindersUri = syncUri(Reminders.CONTENT_URI, account)
    val attendeesUri = syncUri(Attendees.CONTENT_URI, account)
    if (eventId != null) {
        operations += ContentProviderOperation.newDelete(remindersUri)
            .withSelection("${Reminders.EVENT_ID}=?", arrayOf(eventId.toString())).build()
        operations += ContentProviderOperation.newDelete(attendeesUri)
            .withSelection("${Attendees.EVENT_ID}=?", arrayOf(eventId.toString())).build()
    }
    val details = content.details ?: return
    fun ContentProviderOperation.Builder.forEvent(column: String): ContentProviderOperation.Builder =
        if (eventId != null) withValue(column, eventId) else withValueBackReference(column, eventOperation)
    // Cancelled exceptions must suppress the original instance without scheduling an alert.
    if (content.recurrence?.cancelled != true) details.reminders.forEach { reminder ->
        operations += ContentProviderOperation.newInsert(remindersUri).forEvent(Reminders.EVENT_ID)
            .withValue(Reminders.MINUTES, reminder.minutesBefore)
            .withValue(Reminders.METHOD, Reminders.METHOD_ALERT).build()
    }
    fun addPerson(person: EventPerson, role: Int, status: Int, relationship: Int) {
        operations += ContentProviderOperation.newInsert(attendeesUri).forEvent(Attendees.EVENT_ID)
            .withValue(Attendees.ATTENDEE_NAME, person.name)
            .withValue(Attendees.ATTENDEE_EMAIL, person.email)
            .withValue(Attendees.ATTENDEE_TYPE, role)
            .withValue(Attendees.ATTENDEE_STATUS, status)
            .withValue(Attendees.ATTENDEE_RELATIONSHIP, relationship).build()
    }
    details.attendees.forEach { attendee ->
        addPerson(attendee.person, when (attendee.role) {
            AttendanceRole.REQUIRED -> Attendees.TYPE_REQUIRED
            AttendanceRole.OPTIONAL -> Attendees.TYPE_OPTIONAL
            AttendanceRole.RESOURCE -> Attendees.TYPE_RESOURCE
            AttendanceRole.NONE -> Attendees.TYPE_NONE
        }, when (attendee.status) {
            AttendanceStatus.NEEDS_ACTION -> Attendees.ATTENDEE_STATUS_INVITED
            AttendanceStatus.ACCEPTED -> Attendees.ATTENDEE_STATUS_ACCEPTED
            AttendanceStatus.DECLINED -> Attendees.ATTENDEE_STATUS_DECLINED
            AttendanceStatus.TENTATIVE -> Attendees.ATTENDEE_STATUS_TENTATIVE
            AttendanceStatus.UNKNOWN -> Attendees.ATTENDEE_STATUS_NONE
        }, if (attendee.person.email.equals(details.organizer?.email, ignoreCase = true))
            Attendees.RELATIONSHIP_ORGANIZER else Attendees.RELATIONSHIP_ATTENDEE)
    }
    details.organizer?.takeIf { organizer -> details.attendees.none {
        it.person.email.equals(organizer.email, ignoreCase = true)
    } }?.let { addPerson(it, Attendees.TYPE_NONE, Attendees.ATTENDEE_STATUS_NONE, Attendees.RELATIONSHIP_ORGANIZER) }
}

internal fun AndroidCalendarProjection.recurrenceInstant(point: RecurrencePoint): Long = when (point) {
    is RecurrencePoint.AllDay -> point.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    is RecurrencePoint.Timed -> point.instant.toEpochMilli()
}

internal fun AndroidCalendarProjection.recurrenceDates(points: List<RecurrencePoint>): String = points.joinToString(",") {
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        .format(java.time.Instant.ofEpochMilli(recurrenceInstant(it)))
}

internal fun AndroidCalendarProjection.unsupportedRuleTime(time: EventTime, rule: String?): Boolean = time is EventTime.Timed &&
    RecurringLocalTimeRisk.detected(time.start, time.zone, rule)
