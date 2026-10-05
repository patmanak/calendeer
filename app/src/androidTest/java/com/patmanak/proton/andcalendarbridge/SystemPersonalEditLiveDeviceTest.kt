// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract.Events
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in isolated native client journey. No Proton service access or direct local edit write. */
class SystemPersonalEditLiveDeviceTest {
    @Test fun editProjectedPersonalEventInSystemClient() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("systemPersonalEdit") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("system-personal-edit-qualification-v1", Context.MODE_PRIVATE)
        val projection = AndroidCalendarProjection(context)
        val phase = args.getString("personalEditPhase")
        assertTrue(phase in setOf("LAUNCH", "VERIFY", "CLEANUP"))
        if (phase == "LAUNCH") {
            assertFalse("Never replace an existing journey", prefs.contains("account"))
            assertTrue(prefs.edit().putString("account", "system-personal-edit-${UUID.randomUUID()}").commit())
        }
        val scope = CalendarScope(AccountId(checkNotNull(prefs.getString("account", null))), CalendarId("fixture"), 0)
        if (phase == "CLEANUP") {
            projection.removeOwnedCalendar(scope)
            AccountManager.get(context).removeAccountExplicitly(projection.accountFor(scope.account))
            assertTrue(prefs.edit().clear().commit()); return@runBlocking
        }
        val base = RemoteChange.Upsert(EventId("fixture"), Revision("second"), EventContent(
            "Calendeer QA personal incoming", EventTime.Timed(Instant.parse("2030-05-02T10:00:00Z"),
                Instant.parse("2030-05-02T11:00:00Z"), ZoneId.of("UTC")), details = EventDetails()))
        if (phase == "LAUNCH") {
            val calendar = projection.ensureCalendarState(scope, CalendarSummary(scope.calendar, "Calendeer QA personal editing"), true).id
            projection.apply(ProjectionIntent(scope, base.copy(revision = Revision("first"))))
            val row = context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID),
                "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); it.getLong(0)
            }
            assertTrue(prefs.edit().putLong("row", row).commit())
            AndroidCalendarProjection(context).apply(ProjectionIntent(scope, base))
            context.startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(Events.CONTENT_URI, row)).apply {
                setPackage(args.getString("editorPackage") ?: "foundation.e.calendar")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, (base.content.time as EventTime.Timed).start.toEpochMilli())
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, (base.content.time as EventTime.Timed).end.toEpochMilli())
            })
            return@runBlocking
        }
        val row = prefs.getLong("row", -1)
        val captured = checkNotNull(projection.captureOwnedSimpleEdit(scope, base)) {
            "The actual client's saved title must be capturable after incoming projection"
        }
        assertEquals(row, captured.eventRow)
        assertEquals("Calendeer QA personal edited", captured.content.title)
        assertEquals(base.content.copy(title = captured.content.title), captured.content)
        context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events.ORGANIZER, Events.IS_ORGANIZER,
            Events.GUESTS_CAN_MODIFY, Events.HAS_ATTENDEE_DATA, Events.DIRTY), "_id=?", arrayOf(row.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst())
            assertEquals(projection.accountFor(scope.account).name, it.getString(0))
            assertEquals(1, it.getInt(1)); assertEquals(0, it.getInt(2)); assertEquals(0, it.getInt(3)); assertEquals(1, it.getInt(4))
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putBoolean("system_personal_edit_saved_and_captured", true)
            putBoolean("system_personal_edit_same_row_no_invitation", true)
        })
    }
}
