package com.patmanak.proton.andcalendarbridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Attendees
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in native editor profile; synthetic isolated account, no Proton or account settings. */
class SystemCalendarEditorDeviceTest {
    @Test fun inspectSystemEditorCreationWithoutDiscardingItsFields() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("systemEditor") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("system-editor-qualification", Context.MODE_PRIVATE)
        val adapter = AndroidCalendarProjection(context)
        val phase = args.getString("editorPhase")
        assertTrue(phase in setOf("LAUNCH", "INSPECT", "CLEANUP"))
        if (phase == "LAUNCH") {
            assertFalse("Never replace a retained editor fixture", prefs.contains("account"))
            val target = CalendarScope(AccountId("system-editor-${UUID.randomUUID()}"), CalendarId("fixture"), 0)
            val calendar = adapter.ensureCalendarState(target, CalendarSummary(target.calendar, "Calendeer QA — création système"), true).id
            assertTrue(prefs.edit().putString("account", target.account.value).putLong("calendar", calendar).commit())
            val start = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).plusSeconds(86400).toEpochMilli()
            context.startActivity(Intent(Intent.ACTION_INSERT).apply {
                setDataAndType(Events.CONTENT_URI, "vnd.android.cursor.dir/event")
                setPackage(args.getString("editorPackage") ?: "foundation.e.calendar")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Events.CALENDAR_ID, calendar)
                putExtra(Events.TITLE, "Calendeer QA — création depuis Android")
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 3600000)
            })
            return@runBlocking
        }
        val target = CalendarScope(AccountId(checkNotNull(prefs.getString("account", null))), CalendarId("fixture"), 0)
        if (phase == "CLEANUP") {
            adapter.removeOwnedCalendar(target)
            android.accounts.AccountManager.get(context).removeAccountExplicitly(adapter.accountFor(target.account))
            assertTrue(prefs.edit().clear().commit())
            return@runBlocking
        }
        val calendar = prefs.getLong("calendar", -1)
        val account = adapter.accountFor(target.account)
        val uri = Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
        val flags = arrayOf(Events.HAS_ATTENDEE_DATA, Events.IS_ORGANIZER, Events.GUESTS_CAN_MODIFY,
            Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS, Events.HAS_ALARM,
            Events.SELF_ATTENDEE_STATUS, Events.ACCESS_LEVEL, Events.STATUS, Events.AVAILABILITY)
        val unexpected = arrayOf(Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DURATION,
            Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY,
            Events.EVENT_COLOR, Events.EVENT_COLOR_KEY, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI,
            Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA4,
            Events.SYNC_DATA6, Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)
        val summary = Bundle()
        val row = context.contentResolver.query(uri, arrayOf(Events._ID, Events.ORGANIZER, Events.EVENT_END_TIMEZONE,
            Events.DTSTART, Events.DTEND, Events.EVENT_TIMEZONE, Events.DIRTY, Events.DELETED, Events.ALL_DAY) + flags + unexpected,
            "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use { cursor ->
            assertEquals("Save one synthetic event in the isolated calendar", 1, cursor.count)
            assertTrue(cursor.moveToFirst())
            summary.putBoolean("has_organizer", !cursor.isNull(1))
            summary.putBoolean("organizer_is_local_owner", cursor.getString(1) == account.name)
            summary.putBoolean("has_end_zone", !cursor.isNull(2))
            summary.putBoolean("start_whole_seconds", cursor.getLong(3) % 1000L == 0L)
            summary.putBoolean("end_whole_seconds", cursor.getLong(4) % 1000L == 0L)
            summary.putBoolean("positive_duration", cursor.getLong(4) > cursor.getLong(3))
            summary.putBoolean("has_start_zone", !cursor.isNull(5))
            summary.putInt("dirty", cursor.getInt(6)); summary.putInt("deleted", cursor.getInt(7))
            summary.putInt("all_day", cursor.getInt(8))
            unexpected.forEach { summary.putBoolean("nonempty_$it", !cursor.isNull(cursor.getColumnIndexOrThrow(it))) }
            flags.forEach { column ->
                val index = cursor.getColumnIndexOrThrow(column)
                summary.putString("flag_$column", if (cursor.isNull(index)) "NULL" else cursor.getInt(index).toString())
            }
            cursor.getLong(0)
        }
        val localId = LocalEventId(prefs.getString("local_id", null) ?: UUID.randomUUID().toString().also {
            assertTrue(prefs.edit().putString("local_id", it).commit())
        })
        val candidate = adapter.inspectStampedSimpleCreate(target, localId)
            ?: adapter.captureAndStampSimpleCreate(target, row, localId)
        summary.putBoolean("capturable", candidate != null)
        for ((name, child, column) in listOf(Triple("reminders", CalendarContract.Reminders.CONTENT_URI, CalendarContract.Reminders.EVENT_ID),
            Triple("properties", CalendarContract.ExtendedProperties.CONTENT_URI, CalendarContract.ExtendedProperties.EVENT_ID))) {
            context.contentResolver.query(child, arrayOf("_id"), "$column=?", arrayOf(row.toString()), null)!!.use {
                summary.putInt("child_$name", it.count)
            }
        }
        context.contentResolver.query(Attendees.CONTENT_URI, arrayOf(Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_NAME,
            Attendees.ATTENDEE_RELATIONSHIP, Attendees.ATTENDEE_TYPE, Attendees.ATTENDEE_STATUS),
            "${Attendees.EVENT_ID}=?", arrayOf(row.toString()), null)!!.use { cursor ->
            summary.putInt("child_attendees", cursor.count)
            if (cursor.count == 1 && cursor.moveToFirst()) {
                summary.putBoolean("attendee_is_local_owner", cursor.getString(0) == account.name)
                summary.putBoolean("attendee_has_name", !cursor.isNull(1) && cursor.getString(1).isNotEmpty())
                for (index in 2..4) summary.putString("attendee_${cursor.getColumnName(index)}",
                    if (cursor.isNull(index)) "NULL" else cursor.getInt(index).toString())
            }
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, summary)
        if (args.getString("editorExpectedCapture") == "true") assertNotNull("Native editor profile must be captured", candidate)
    }
}
