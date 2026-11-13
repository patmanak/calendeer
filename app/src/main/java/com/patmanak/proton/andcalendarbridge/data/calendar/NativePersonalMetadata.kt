package com.patmanak.proton.andcalendarbridge.data.calendar

import android.accounts.Account
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.database.DatabaseUtils
import android.net.Uri
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import com.patmanak.proton.andcalendarbridge.sync.EventContent

/** Native personal profile checks and exact reminder assertions for the existing event CAS batch. */
internal class NativePersonalMetadata(
    private val resolver: ContentResolver,
    private val syncUri: (Uri, Account) -> Uri,
    private val fail: (CalendarProjectionFailure) -> Nothing,
) {
    /** Personal owner profile v2: known unchanged reminders, no remote participants or recurrence.
     * The opaque local owner label never represents a remote organizer. */
    fun knownPersonalEvent(content: EventContent): Boolean = content.recurrence == null &&
        content.details?.let { it.organizer == null && it.attendees.isEmpty() } == true &&
        (content.colorRgb == null || content.colorRgb in 0..0xffffff)

    fun knownPersonalOwner(values: ContentValues, account: Account, content: EventContent): Boolean =
        knownPersonalEvent(content) && values.getAsString(Events.ORGANIZER) == account.name &&
            values.getAsInteger(Events.IS_ORGANIZER) == 1 &&
            values.getAsInteger(Events.HAS_ALARM) == (if (content.details!!.reminders.isEmpty()) 0 else 1) &&
            listOf(Events.HAS_ATTENDEE_DATA, Events.GUESTS_CAN_MODIFY, Events.GUESTS_CAN_INVITE_OTHERS,
                Events.GUESTS_CAN_SEE_GUESTS, Events.SELF_ATTENDEE_STATUS,
                Events.ACCESS_LEVEL).all { values.getAsInteger(it) == 0 }

    /** Child IDs, values and total count are asserted in the event CAS batch. Never replace rows. */
    fun assertPersonalReminders(account: Account, row: Long, content: EventContent,
        checks: MutableList<ContentProviderOperation>, expected: MutableList<Int>): Boolean {
        val uri = syncUri(Reminders.CONTENT_URI, account)
        val selection = "${Reminders.EVENT_ID}=?"
        val args = arrayOf(row.toString())
        val reminders: List<ContentValues> = resolver.query(uri, arrayOf(Reminders._ID, Reminders.EVENT_ID, Reminders.MINUTES,
            Reminders.METHOD), selection, args, null)?.use { cursor ->
            if (cursor.count > 5) return false
            buildList {
                while (cursor.moveToNext()) add(ContentValues().apply {
                    DatabaseUtils.cursorRowToContentValues(cursor, this)
                })
            }
        } ?: fail(CalendarProjectionFailure.PROVIDER_UNAVAILABLE)
        if (reminders.any { it.getAsLong(Reminders._ID)?.let { id -> id <= 0 } != false ||
                it.getAsLong(Reminders.EVENT_ID) != row || it.getAsInteger(Reminders.METHOD) != Reminders.METHOD_ALERT ||
                it.getAsInteger(Reminders.MINUTES) == null } ||
            reminders.map { it.getAsInteger(Reminders.MINUTES)!! }.sorted() !=
                content.details!!.reminders.map { it.minutesBefore }.sorted()) return false
        checks += ContentProviderOperation.newAssertQuery(uri).withSelection(selection, args)
            .withExpectedCount(reminders.size).build()
        expected += reminders.size
        for (reminder in reminders) {
            checks += ContentProviderOperation.newAssertQuery(uri)
                .withSelection("${Reminders._ID}=? AND ${Reminders.EVENT_ID}=?",
                    arrayOf(reminder.getAsLong(Reminders._ID).toString(), row.toString()))
                .withValues(reminder).withExpectedCount(1).build()
            expected += 1
        }
        return true
    }
}
