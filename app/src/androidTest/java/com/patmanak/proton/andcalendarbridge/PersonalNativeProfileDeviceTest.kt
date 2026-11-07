// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.*
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Isolated Provider fixtures; never accesses a Proton session or existing calendars. */
class PersonalNativeProfileDeviceTest {
    @Test fun combinedKindChangeCapturesAndAcknowledgesWithoutReplacingReminders() = fixture {
        assertNull(projection.captureOwnedSimpleEdit(scope, base))
        assertEquals(intent.content, projection.captureOwnedPersonalEdit(scope, base)!!.content)
        val children = reminders()
        assertEquals(AppliedPersonalUpdateAcknowledgement(receipt), projection.acknowledgePersonalUpdate(receipt))
        assertEquals(children, reminders())
        assertEquals("0", field(Events.DIRTY))
        assertEquals("Personal\nedit", field(Events.TITLE))
        assertEquals("1", field(Events.ALL_DAY))
        assertEquals("UTC", field(Events.EVENT_TIMEZONE))
        assertEquals((0xff000000.toInt() or 0x123456).toString(), field(Events.EVENT_COLOR))
        assertEquals("1", field(Events.HAS_ALARM))
        mutate(ContentValues().apply { put(Events.TITLE, "Later edit"); put(Events.DIRTY, 1) })
        assertEquals(AppliedPersonalUpdateAcknowledgement(receipt), projection.inspectPersonalUpdateAcknowledgement(receipt))
        assertEquals("Later edit", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
        assertNull(projection.acknowledgePersonalUpdate(receipt))
    }
    @Test fun changedReminderOffsetMethodAndExtraRowsBlockCaptureAndAcknowledgement() = fixture {
        for ((column, value) in listOf(Reminders.MINUTES to 11, Reminders.METHOD to Reminders.METHOD_EMAIL)) {
            context.contentResolver.update(sync(Reminders.CONTENT_URI), ContentValues().apply { put(column, value) },
                "${Reminders.EVENT_ID}=?", arrayOf(row.toString()))
            assertNull(projection.captureOwnedPersonalEdit(scope, base))
            assertNull(projection.acknowledgePersonalUpdate(receipt)); assertEquals("1", field(Events.DIRTY))
            context.contentResolver.update(sync(Reminders.CONTENT_URI), ContentValues().apply {
                put(Reminders.MINUTES, 10); put(Reminders.METHOD, Reminders.METHOD_ALERT)
            }, "${Reminders.EVENT_ID}=?", arrayOf(row.toString()))
        }
        addReminder(20)
        assertNull(projection.captureOwnedPersonalEdit(scope, base))
        assertNull(projection.acknowledgePersonalUpdate(receipt)); assertEquals("1", field(Events.DIRTY))
    }
    @Test fun unknownDetailsParticipantsAndChangedMetadataStayUnqualified() = fixture {
        assertNull(projection.captureOwnedPersonalEdit(scope, base.copy(content = original.copy(details = null))))
        val attendee = context.contentResolver.insert(sync(Attendees.CONTENT_URI), ContentValues().apply {
            put(Attendees.EVENT_ID, row); put(Attendees.ATTENDEE_EMAIL, "fixture@example.invalid")
            put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED)
            put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE)
        })!!
        assertNull(projection.captureOwnedPersonalEdit(scope, base))
        assertNull(projection.acknowledgePersonalUpdate(receipt))
        context.contentResolver.delete(attendee, null, null)
        // HAS_ALARM is Provider-maintained; reminders above exercise its real child path.
        for ((column, value) in listOf(Events.EVENT_COLOR to 0xff654321.toInt())) {
            val old = field(column)!!
            mutate(ContentValues().apply { put(column, value) })
            assertNull(projection.captureOwnedPersonalEdit(scope, base))
            assertNull(projection.acknowledgePersonalUpdate(receipt))
            assertEquals("1", field(Events.DIRTY))
            mutate(ContentValues().apply { put(column, old.toInt()) })
        }
    }
    @Test fun reminderReplacementBetweenReadAndBatchAbortsEvenWithSameCountAndOffset() = fixture {
        val real = context.contentResolver
        var replaced = false
        val provider = object : ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri): String? = null
            override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?) = real.query(uri, columns, selection, args, sort)
            override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Unexpected insert")
            override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = error("Unexpected update")
            override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = error("Unexpected delete")
            override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                if (!replaced) {
                    replaced = true
                    // Insert first, forcing a different child ID before removing the original.
                    val replacement = addReminder(10)
                    real.delete(sync(Reminders.CONTENT_URI), "${Reminders.EVENT_ID}=? AND ${Reminders._ID}!=?",
                        arrayOf(row.toString(), ContentUris.parseId(replacement).toString()))
                }
                return real.applyBatch(CalendarContract.AUTHORITY, operations)
            }
        }
        val wrapped = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getContentResolver() = ContentResolver.wrap(provider)
        }
        try { AndroidCalendarProjection(wrapped).acknowledgePersonalUpdate(receipt); fail("Changed child identity must abort") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PROVIDER_REJECTED, failure.category) }
        assertTrue(replaced); assertEquals("1", field(Events.DIRTY))
        assertEquals("Personal\r\nedit", field(Events.TITLE)); assertEquals(1, reminders().size)
    }

    private class Fixture(val context: Context) {
        val scope = CalendarScope(AccountId("personal-native-${UUID.randomUUID()}"), CalendarId("fixture"), 0)
        val projection = AndroidCalendarProjection(context)
        val format = AdapterFormat("synthetic-personal-native", 1)
        val original = EventContent("Original", EventTime.Timed(Instant.parse("2030-05-01T09:00:00Z"),
            Instant.parse("2030-05-01T10:00:00Z"), ZoneId.of("Europe/Paris")), colorRgb = 0x123456,
            details = EventDetails(reminders = listOf(EventReminder(10))))
        val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("old"), original)
        val intent = LocalEditIntent(MutationId("local"), scope, format, base, original.copy(title = "Personal\r\nedit",
            description = "New detail", location = "New place", time = EventTime.AllDay(java.time.LocalDate.of(2030, 5, 2), java.time.LocalDate.of(2030, 5, 4))))
        val observed = base.copy(revision = Revision("new"), content = intent.content.copy(title = "Personal\nedit"))
        val receipt = PersonalUpdateAcknowledgementReceipt(PersonalUpdateAttemptIdentity(scope, MutationId("attempt"), intent.id,
            base.eventId, base.revision, format, format, "a".repeat(64), "b".repeat(64)), intent,
            ProjectionReceipt(scope, format, observed, UUID.randomUUID().toString(), base))
        var row = -1L
        fun sync(uri: Uri): Uri = projection.accountFor(scope.account).let { uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, it.name).appendQueryParameter(Calendars.ACCOUNT_TYPE, it.type).build() }
        fun mutate(values: ContentValues) { assertEquals(1, context.contentResolver.update(sync(Events.CONTENT_URI), values, "${Events._ID}=?", arrayOf(row.toString()))) }
        fun field(column: String): String? = context.contentResolver.query(sync(Events.CONTENT_URI), arrayOf(column), "${Events._ID}=?", arrayOf(row.toString()), null)!!.use { check(it.moveToFirst()); it.getString(0) }
        fun reminders(): List<List<String?>> = context.contentResolver.query(sync(Reminders.CONTENT_URI), arrayOf(Reminders._ID, Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD), "${Reminders.EVENT_ID}=?", arrayOf(row.toString()), Reminders._ID)!!.use { cursor -> buildList { while(cursor.moveToNext()) add((0..3).map { cursor.getString(it) }) } }
        fun addReminder(minutes: Int): Uri = context.contentResolver.insert(sync(Reminders.CONTENT_URI), ContentValues().apply { put(Reminders.EVENT_ID, row); put(Reminders.MINUTES, minutes); put(Reminders.METHOD, Reminders.METHOD_ALERT) })!!
        suspend fun seed() {
            val calendar = projection.ensureCalendarState(scope, CalendarSummary(scope.calendar, "Synthetic personal fixture"), true).id
            projection.apply(ProjectionIntent(scope, base))
            row = context.contentResolver.query(sync(Events.CONTENT_URI), arrayOf(Events._ID), "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use { check(it.count == 1 && it.moveToFirst()); it.getLong(0) }
            mutate(ContentValues().apply {
                put(Events.TITLE, intent.content.title); put(Events.DESCRIPTION, intent.content.description)
                put(Events.EVENT_LOCATION, intent.content.location); put(Events.DIRTY, 1)
                put(Events.ALL_DAY, 1); put(Events.EVENT_TIMEZONE, "UTC"); put(Events.EVENT_END_TIMEZONE, "UTC")
                val time = intent.content.time as EventTime.AllDay
                put(Events.DTSTART, time.start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                put(Events.DTEND, time.endExclusive.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
            })
        }
        suspend fun close() {
            projection.removeOwnedCalendar(scope)
            AccountManager.get(context).removeAccountExplicitly(projection.accountFor(scope.account))
        }
    }
    private fun fixture(block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext())
        assumeTrue(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
}
