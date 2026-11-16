// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonCalendarEventColors
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated real Provider metadata and key selection; no calendar client or service claim. */
@RunWith(AndroidJUnit4::class)
class NativeEventPaletteDeviceTest {
    @Test fun anEmptyKeyKeepsTheCalendarColorDuringLocalCreation() = fixture {
        val row = create("")
        val result = projection.captureAndStampSimpleCreate(scope, row, LocalEventId(UUID.randomUUID().toString()))
        assertNotNull(result); assertNull(result!!.content.colorRgb)
        assertEquals("", field(row, Events.EVENT_COLOR_KEY)); assertEquals("1", field(row, Events.DIRTY))
    }
    @Test fun excessiveOrDuplicatePaletteNeverPublishesAPartialList() = fixture {
        val variants = listOf(
            (0..64).map { EventPaletteColor("oversized-$it", it) },
            listOf(EventPaletteColor("duplicated", 1), EventPaletteColor("duplicated", 2)))
        for (colors in variants) {
            val invalid = AndroidCalendarProjection(context, eventColors = EventColorPalette { colors })
            try { invalid.ensureCalendar(scope, CalendarSummary(scope.calendar, "Palette fixture")); fail("Invalid palette") }
            catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.IDENTITY_CONFLICT, failure.category) }
            assertTrue(palette().isEmpty())
        }
    }
    @Test fun displayMaskingOfACleanKeyedEventPreservesItsCanonicalColor() = fixture {
        publish()
        val color = offered.first()
        val base = RemoteChange.Upsert(EventId("palette-display"), Revision("palette-v1"), EventContent("Palette display fixture",
            EventTime.Timed(Instant.parse("2030-05-01T09:00:00Z"), Instant.parse("2030-05-01T10:00:00Z"), ZoneOffset.UTC),
            colorRgb = color.rgb, details = EventDetails()))
        projection.apply(ProjectionIntent(scope, base))
        val row = context.contentResolver.query(uri(Events.CONTENT_URI), arrayOf(Events._ID),
            "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use { assertTrue(it.moveToFirst()); it.getLong(0) }
        context.contentResolver.update(uri(Events.CONTENT_URI),
            ContentValues().apply { put(Events.EVENT_COLOR_KEY, color.key); put(Events.DIRTY, 0) },
            "${Events._ID}=?", arrayOf(row.toString()))
        val masked = AndroidCalendarProjection(context, eventColors = ProtonCalendarEventColors) { EventColorMode.CALENDAR }
        masked.reprojectEventColors(scope)
        assertNull(field(row, Events.EVENT_COLOR)); assertNull(field(row, Events.EVENT_COLOR_KEY)); assertEquals("0", field(row, Events.DIRTY))
        context.contentResolver.update(uri(Events.CONTENT_URI),
            ContentValues().apply { put(Events.TITLE, "Palette masked title edit"); put(Events.DIRTY, 1) },
            "${Events._ID}=?", arrayOf(row.toString()))
        assertEquals(color.rgb, masked.captureOwnedPersonalEdit(scope, base)!!.content.colorRgb)
    }
    @Test fun accountPublishesExactlyTheOfferedPaletteWithoutChangingEvents() = fixture {
        val row = create()
        val clean = create()
        context.contentResolver.update(uri(Events.CONTENT_URI), ContentValues().apply { put(Events.DIRTY, 0) },
            "${Events._ID}=?", arrayOf(clean.toString()))
        val before = fields(row)
        val cleanBefore = fields(clean)
        publish()
        assertEquals(20, palette().size)
        assertEquals(offered.associate { it.key to (it.rgb or 0xff000000.toInt()) }, palette())
        publish()
        assertEquals(20, palette().size)
        assertEquals(before, fields(row))
        assertEquals(cleanBefore, fields(clean))
    }

    @Test fun collisionsOfEitherTypeArePreservedWithoutPartialPublication() {
        for (type in listOf(Colors.TYPE_EVENT, Colors.TYPE_CALENDAR)) fixture {
            insertColor(offered.first().key, 0x123456, type)
            val row = create()
            val before = fields(row)
            try { publish(); fail("A stable key cannot be reassigned") }
            catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.IDENTITY_CONFLICT, failure.category) }
            assertEquals(if (type == Colors.TYPE_EVENT) mapOf(offered.first().key to 0xff123456.toInt()) else emptyMap<String, Int>(), palette())
            assertEquals(before, fields(row))
        }
    }

    @Test fun existingMatchingAndForeignRowsSurviveWhileOnlyMissingEntriesAreInserted() = fixture {
        insertColor(offered.first().key, offered.first().rgb)
        insertColor("foreign-fixture-key", 0x123456)
        publish()
        assertEquals(21, palette().size)
        assertEquals(0xff123456.toInt(), palette()["foreign-fixture-key"])
        publish()
        assertEquals(21, palette().size)
    }

    @Test fun equalKeysInAnotherAccountCannotBeReadOrReplaced() = fixture {
        val other = Fixture()
        try {
            other.seed()
            other.insertColor(offered.first().key, 0x123456)
            publish()
            assertEquals(20, palette().size)
            assertEquals(mapOf(offered.first().key to 0xff123456.toInt()), other.palette())
        } finally { other.close() }
    }

    @Test fun revokedAdmissionDoesNotPublishAPartialPalette() = fixture {
        projection.admitOperation = { false }
        try { publish(); fail("Revoked admission") } catch (_: kotlinx.coroutines.CancellationException) { }
        projection.admitOperation = { true }
        assertTrue(palette().isEmpty())
    }

    @Test fun ordinaryAndSeriesEditsResolveARegisteredKeyWithoutCleaningTheEvent() {
        for (series in listOf(false, true)) fixture {
            publish()
            val time = EventTime.Timed(Instant.parse("2030-05-01T09:00:00Z"), Instant.parse("2030-05-01T10:00:00Z"), ZoneOffset.UTC)
            val base = RemoteChange.Upsert(EventId("palette-event"), Revision("palette-v1"), EventContent("Palette remote fixture",
                time, details = EventDetails(), recurrence = if (series) EventRecurrence("palette-uid", "FREQ=DAILY;COUNT=3") else null))
            projection.apply(ProjectionIntent(scope, base))
            val row = context.contentResolver.query(uri(Events.CONTENT_URI), arrayOf(Events._ID),
                "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use { assertTrue(it.moveToFirst()); it.getLong(0) }
            assertEquals(1, context.contentResolver.update(Events.CONTENT_URI,
                ContentValues().apply { put(Events.EVENT_COLOR_KEY, offered.first().key) }, "${Events._ID}=?", arrayOf(row.toString())))
            val snapshot = if (series) projection.captureOwnedSeriesEdit(scope, base) else projection.captureOwnedPersonalEdit(scope, base)
            assertNotNull(snapshot); assertEquals(offered.first().rgb, snapshot!!.content.colorRgb)
            assertEquals("1", field(row, Events.DIRTY)); assertEquals(offered.first().key, field(row, Events.EVENT_COLOR_KEY))
        }
    }

    @Test fun paletteRaceDuringCaptureCannotStampOrDiscardTheNewColour() = fixture {
        val chosen = offered.first()
        val id = insertColor(chosen.key, chosen.rgb)
        val row = create(chosen.key)
        var once = true
        val guarded = beforeBatch {
            if (once) {
                once = false
                context.contentResolver.update(uri(Colors.CONTENT_URI), ContentValues().apply { put(Colors.COLOR, 0xff258723.toInt()) },
                    "${Colors._ID}=?", arrayOf(id.toString()))
            }
        }
        try {
            assertNull(guarded.captureAndStampSimpleCreate(scope, row, LocalEventId(UUID.randomUUID().toString())))
        } catch (_: CalendarProjectionException) { }
        assertNull(field(row, Events.SYNC_DATA5)); assertEquals("1", field(row, Events.DIRTY))
        assertEquals(0xff258723.toInt().toString(), field(row, Events.EVENT_COLOR))
    }

    @Test fun concurrentInsertionRollsBackAllOtherMissingPaletteEntries() = fixture {
        val collision = offered.last()
        var once = true
        val guarded = beforeBatch {
            if (once) { once = false; insertColor(collision.key, 0x123456) }
        }
        try { guarded.ensureCalendar(scope, CalendarSummary(scope.calendar, "Palette fixture")); fail("Concurrent key collision") }
        catch (_: CalendarProjectionException) { }
        assertEquals(mapOf(collision.key to 0xff123456.toInt()), palette())
    }

    @Test fun missingOrMismatchedKeyProofCannotStampOrDiscardAValidNativeRow() {
        for (mismatch in listOf(false, true)) fixture {
            val chosen = offered.first()
            val id = insertColor(chosen.key, chosen.rgb)
            val row = create(chosen.key)
            val before = fields(row)
            var batches = 0
            val guarded = beforeBatch(query = { uri, columns, actual ->
                if (uri.pathSegments.firstOrNull() != "colors") actual else {
                    actual?.close()
                    MatrixCursor(columns!!).apply {
                        if (mismatch) addRow(columns.map { if (it == Colors._ID) id else 0xff258723.toInt() }.toTypedArray<Any>())
                    }
                }
            }, change = { batches++ })
            assertNull(guarded.captureAndStampSimpleCreate(scope, row, LocalEventId(UUID.randomUUID().toString())))
            assertEquals(0, batches); assertEquals(before, fields(row))
        }
    }

    @Test fun newEventSelectedByRegisteredKeyCanBeCapturedWithoutChangingItsColour() = fixture {
        val chosen = offered.first()
        insertColor(chosen.key, chosen.rgb)
        val row = create(chosen.key)
        assertEquals((chosen.rgb or 0xff000000.toInt()).toString(), field(row, Events.EVENT_COLOR))
        val proof = nativeEventColorKeyAssertions(context.contentResolver, account,
            ContentValues().apply { put(Events.EVENT_COLOR_KEY, chosen.key); put(Events.EVENT_COLOR, chosen.rgb or 0xff000000.toInt()) },
            { base, _ -> uri(base) }, { }, { throw CalendarProjectionException(it) })
        assertNotNull("Scoped key proof must be present", proof)
        val plain = create()
        assertEquals(LocalCreateProfile.SIMPLE, projection.localCreateProfile(scope, plain))
        assertNotNull("Unkeyed native CREATE guards: " + guardSummary(plain),
            projection.captureAndStampSimpleCreate(scope, plain, LocalEventId(UUID.randomUUID().toString())))
        val snapshot = projection.captureAndStampSimpleCreate(scope, row, LocalEventId(UUID.randomUUID().toString()))
        assertNotNull("Native CREATE guards: " + guardSummary(row), snapshot)
        assertEquals(chosen.rgb, snapshot!!.content.colorRgb)
        assertEquals(chosen.key, field(row, Events.EVENT_COLOR_KEY))
        assertEquals("1", field(row, Events.DIRTY))
    }

    private class Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CalendarScope(AccountId("palette-fixture-" + UUID.randomUUID()), CalendarId("fixture"), 0)
        val offered = ProtonCalendarEventColors.colors()
        val projection = AndroidCalendarProjection(context, eventColors = ProtonCalendarEventColors) { EventColorMode.EVENT }
        val account = projection.accountFor(scope.account)
        var calendar = -1L
        suspend fun seed() {
            calendar = AndroidCalendarProjection(context) { EventColorMode.EVENT }.ensureCalendar(scope, CalendarSummary(scope.calendar, "Palette fixture"))
        }
        fun uri(base: Uri) = base.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type).build()
        suspend fun publish() { calendar = projection.ensureCalendar(scope, CalendarSummary(scope.calendar, "Palette fixture")) }
        fun palette(): Map<String, Int> = context.contentResolver.query(uri(Colors.CONTENT_URI),
            arrayOf(Colors.COLOR_KEY, Colors.COLOR), "${Colors.ACCOUNT_NAME}=? AND ${Colors.ACCOUNT_TYPE}=? AND ${Colors.COLOR_TYPE}=?",
            arrayOf(account.name, account.type, Colors.TYPE_EVENT.toString()), null)!!.use { rows ->
            buildMap { while (rows.moveToNext()) put(rows.getString(0), rows.getInt(1)) }
        }
        fun insertColor(key: String, rgb: Int, type: Int = Colors.TYPE_EVENT): Long =
            ContentUris.parseId(context.contentResolver.insert(uri(Colors.CONTENT_URI), ContentValues().apply {
                put(Colors.ACCOUNT_NAME, account.name); put(Colors.ACCOUNT_TYPE, account.type)
                put(Colors.COLOR_KEY, key); put(Colors.COLOR_TYPE, type); put(Colors.COLOR, rgb or 0xff000000.toInt())
            })!!)
        fun create(key: String? = null): Long = ContentUris.parseId(context.contentResolver.insert(uri(Events.CONTENT_URI), ContentValues().apply {
            put(Events.CALENDAR_ID, calendar); put(Events.TITLE, "Palette local fixture")
            put(Events.DTSTART, Instant.parse("2030-05-01T09:00:00Z").toEpochMilli())
            put(Events.DTEND, Instant.parse("2030-05-01T10:00:00Z").toEpochMilli())
            put(Events.EVENT_TIMEZONE, "UTC"); put(Events.EVENT_END_TIMEZONE, "UTC"); put(Events.ALL_DAY, 0)
            put(Events.DIRTY, 1); put(Events.DELETED, 0); put(Events.HAS_ALARM, 0)
            put(Events.STATUS, Events.STATUS_CONFIRMED); put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            put(Events.HAS_ATTENDEE_DATA, 0); put(Events.IS_ORGANIZER, 0)
            put(Events.GUESTS_CAN_MODIFY, 0); put(Events.GUESTS_CAN_INVITE_OTHERS, 0); put(Events.GUESTS_CAN_SEE_GUESTS, 0)
            put(Events.SELF_ATTENDEE_STATUS, 0); put(Events.ACCESS_LEVEL, 0)
            putNull(Events.ORGANIZER); put(Events.EVENT_COLOR_KEY, key)
        })!!).also { row ->
            // Establish this fixture's explicit no-participant profile, including reused Provider IDs.
            context.contentResolver.delete(uri(Attendees.CONTENT_URI), "${Attendees.EVENT_ID}=?", arrayOf(row.toString()))
        }
        fun field(row: Long, name: String): String? = context.contentResolver.query(uri(Events.CONTENT_URI),
            arrayOf(name), "${Events._ID}=?", arrayOf(row.toString()), null)!!.use {
            assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
        }
        fun guardSummary(row: Long) = listOf(Events.DIRTY, Events.DELETED, Events.HAS_ALARM, Events.HAS_ATTENDEE_DATA,
            Events.IS_ORGANIZER, Events.GUESTS_CAN_MODIFY, Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS,
            Events.SELF_ATTENDEE_STATUS, Events.ACCESS_LEVEL, Events.STATUS, Events.AVAILABILITY,
            Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.DTSTART, Events.DTEND).associateWith { field(row, it) }.toString() +
            " nonempty=" + listOf(Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DURATION,
                Events.ORIGINAL_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY,
                Events.ORGANIZER, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI, Events._SYNC_ID,
                Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA4, Events.SYNC_DATA5,
                Events.SYNC_DATA6, Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)
                .filter { field(row, it) != null }.toString() + " childCounts=" +
            listOf(Attendees.CONTENT_URI, ExtendedProperties.CONTENT_URI, Reminders.CONTENT_URI).map { table ->
                context.contentResolver.query(uri(table), arrayOf("_id"), "event_id=?", arrayOf(row.toString()), null)!!.use { it.count }
            }.toString() + " calendarMatches=" + (field(row, Events.CALENDAR_ID) == calendar.toString())
        fun fields(row: Long) = listOf(Events.TITLE, Events.DTSTART, Events.DTEND, Events.DIRTY, Events.DELETED,
            Events.EVENT_COLOR, Events.EVENT_COLOR_KEY, Events.SYNC_DATA3, Events.SYNC_DATA5).associateWith { field(row, it) }
        fun beforeBatch(query: ((Uri, Array<out String>?, Cursor?) -> Cursor?)? = null,
            change: () -> Unit): AndroidCalendarProjection {
            val real = context.contentResolver
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri): String? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Use batch")
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Use batch")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Use batch")
                override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? {
                    val actual = real.query(uri, columns, selection, args, order)
                    return if (query == null) actual else query.invoke(uri, columns, actual)
                }
                override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                    change(); return real.applyBatch(CalendarContract.AUTHORITY, operations)
                }
            }
            val wrapped = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getContentResolver() = ContentResolver.wrap(provider)
            }
            return AndroidCalendarProjection(wrapped, eventColors = ProtonCalendarEventColors) { EventColorMode.EVENT }
        }
        suspend fun close() {
            projection.admitOperation = { true }
            try { projection.removeOwnedCalendar(scope) } finally {
                context.contentResolver.delete(uri(Colors.CONTENT_URI), "${Colors.ACCOUNT_NAME}=? AND ${Colors.ACCOUNT_TYPE}=?",
                    arrayOf(account.name, account.type))
                AccountManager.get(context).removeAccountExplicitly(account)
            }
        }
    }
    private fun fixture(action: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture()
        assumeTrue(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.action() } finally { fixture.close() }
    }
}
