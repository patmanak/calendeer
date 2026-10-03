// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.*
import android.database.Cursor
import android.database.CursorWrapper
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.ExtendedProperties
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated actual Provider rows and synthetic immutable capture/attachment identities.
 * No server transport, Android acknowledgement or incoming-hold release is exercised.
 */
@RunWith(AndroidJUnit4::class)
class CapturedCreateCurrentProviderDeviceTest {
    @Test fun unchangedDirtyOriginalHasAnExactSupportedSnapshotWithoutAnyWrite() = fixture {
        val before = values()
        val observed = supported()
        assertEquals(baseline, observed.baseline); assertEquals(content, observed.content)
        assertTrue(observed.unchanged); assertFalse(observed.deleted)
        assertEquals(before, values()); assertEquals("1", field(Events.DIRTY))
    }

    @Test fun laterTextAndTimedDatesAreCapturedWithoutReplacingTheSentReference() = fixture {
        val start = Instant.parse("2030-10-28T12:00:00Z")
        val end = Instant.parse("2030-10-28T13:00:00Z")
        mutate(ContentValues().apply {
            put(Events.TITLE, "Captured current later title"); put(Events.DESCRIPTION, "Later description")
            put(Events.EVENT_LOCATION, "Later location"); put(Events.DTSTART, start.toEpochMilli()); put(Events.DTEND, end.toEpochMilli())
        })
        val before = values(); val observed = supported()
        assertEquals(content.copy(title = "Captured current later title", description = "Later description", location = "Later location",
            time = EventTime.Timed(start, end, ZoneId.of("Europe/Paris"))), observed.content)
        assertEquals(content, observed.baseline.change.content); assertFalse(observed.unchanged)
        assertEquals(before, values())
    }

    @Test fun allDayExclusiveDatesRemainSupportedAndUnchanged() = fixture(allDay = true) {
        val before = values(); assertTrue(supported().unchanged); assertEquals(content, supported().content)
        assertEquals(before, values())
    }

    @Test fun presentDirtyTombstoneIsCapturedWithItsCurrentContentAndFlagsIntact() = fixture {
        mutate(ContentValues().apply { put(Events.DELETED, 1); put(Events.TITLE, "Deleted current title") })
        val before = values(); val observed = supported()
        assertTrue(observed.deleted); assertFalse(observed.unchanged)
        assertEquals(content.copy(title = "Deleted current title"), observed.content)
        assertEquals(before, values()); assertEquals("1", field(Events.DIRTY)); assertEquals("1", field(Events.DELETED))
    }

    @Test fun presentTombstoneWithReminderStillRequiresExplicitUnsupportedReconciliation() = fixture {
        reminder(); mutate(ContentValues().apply { put(Events.DELETED, 1) }); val before = values()
        assertEquals(CapturedCreateCurrentObservation.Unsupported, capture()); assertEquals(before, values())
        assertEquals(1, children(Reminders.CONTENT_URI, Reminders.EVENT_ID))
    }

    @Test fun reminderChildRemainsUnsupportedWhenTheReadShapeReportsNoAlarm() = fixture {
        reminder()
        // Simulate an inconsistent read shape only; the actual Provider reminder and flag remain untouched.
        // No direct HAS_ALARM mutation is needed to exercise the independent child inspection.
        val before = values(); val adapter = intercepted(maskAlarm = true)
        assertEquals(CapturedCreateCurrentObservation.Unsupported, adapter.captureCapturedCreateCurrent(baseline))
        assertEquals(1, maskedAlarmQueries); assertEquals(0, interceptedBatches); assertEquals(0, realBatchAttempts)
        assertEquals(before, values()); assertEquals(1, children(Reminders.CONTENT_URI, Reminders.EVENT_ID))
    }

    @Test fun attendeeChildIsNeverDiscardedWhenItsFlagSaysEmpty() = fixture {
        context.contentResolver.insert(syncUri(Attendees.CONTENT_URI), ContentValues().apply {
            put(Attendees.EVENT_ID, row); put(Attendees.ATTENDEE_EMAIL, "fixture@example.invalid")
            put(Attendees.ATTENDEE_NAME, "Fixture"); put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE)
            put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED); put(Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_ACCEPTED)
        })!!
        mutate(ContentValues().apply { put(Events.HAS_ATTENDEE_DATA, 0) })
        val before = values(); assertEquals(CapturedCreateCurrentObservation.Unsupported, capture())
        assertEquals(before, values()); assertEquals(1, children(Attendees.CONTENT_URI, Attendees.EVENT_ID))
    }

    @Test fun extendedPropertyChildIsNeverSilentlyDropped() = fixture {
        context.contentResolver.insert(syncUri(ExtendedProperties.CONTENT_URI), ContentValues().apply {
            put(ExtendedProperties.EVENT_ID, row); put(ExtendedProperties.NAME, "fixture"); put(ExtendedProperties.VALUE, "extra")
        })!!
        val before = values(); assertEquals(CapturedCreateCurrentObservation.Unsupported, capture())
        assertEquals(before, values()); assertEquals(1, children(ExtendedProperties.CONTENT_URI, ExtendedProperties.EVENT_ID))
    }

    @Test fun unsupportedColorFlagsAndCustomDataRemainUntouched() = fixture {
        for ((column, value) in listOf(Events.EVENT_COLOR to 0xFFAA1122.toInt().toString(),
            Events.ORGANIZER to "fixture@example.invalid",
            Events.ACCESS_LEVEL to Events.ACCESS_PRIVATE.toString(), Events.AVAILABILITY to Events.AVAILABILITY_FREE.toString(),
            Events.CUSTOM_APP_PACKAGE to "example.fixture", Events.CUSTOM_APP_URI to "content://example.fixture/item",
            Events.STATUS to Events.STATUS_TENTATIVE.toString(), Events.GUESTS_CAN_MODIFY to "1")) {
            val original = field(column); mutate(ContentValues().apply { put(column, value) })
            val before = values(); assertEquals("Unsupported $column", CapturedCreateCurrentObservation.Unsupported, capture())
            assertEquals(before, values()); mutate(ContentValues().apply { if (original == null) putNull(column) else put(column, original) })
        }
    }

    @Test fun recurrenceRemainsUnsupportedAndIntact() = fixture {
        mutate(ContentValues().apply { put(Events.RRULE, "FREQ=DAILY;COUNT=3"); put(Events.DURATION, "PT7200S"); putNull(Events.DTEND) })
        val before = values(); assertEquals(CapturedCreateCurrentObservation.Unsupported, capture())
        assertEquals(before, values()); assertEquals("FREQ=DAILY;COUNT=3", field(Events.RRULE))
    }

    @Test fun fractionalPrecisionAndInconsistentTimezoneRemainUnsupported() = fixture {
        val start = (content.time as EventTime.Timed).start.toEpochMilli()
        mutate(ContentValues().apply { put(Events.DTSTART, start + 1) })
        var before = values(); assertEquals(CapturedCreateCurrentObservation.Unsupported, capture()); assertEquals(before, values())
        mutate(ContentValues().apply { put(Events.DTSTART, start); put(Events.EVENT_END_TIMEZONE, "UTC") })
        before = values(); assertEquals(CapturedCreateCurrentObservation.Unsupported, capture()); assertEquals(before, values())
    }

    @Test fun substitutedAttachmentMetadataNeverQualifiesAndIsNotRepaired() = fixture {
        for (column in listOf(Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA4, Events.SYNC_DATA5,
            Events.SYNC_DATA6, Events.SYNC_DATA3, Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10)) {
            val original = field(column); mutate(ContentValues().apply { put(column, "substituted") })
            val before = values(); assertEquals("Metadata $column", CapturedCreateCurrentObservation.Unresolved, capture())
            assertEquals(before, values()); mutate(ContentValues().apply { if (original == null) putNull(column) else put(column, original) })
        }
    }

    @Test fun aDifferentNonceOrFullCaptureIdentityCannotAdoptTheRow() = fixture {
        val before = values()
        assertEquals(CapturedCreateCurrentObservation.Unresolved, capture(baseline.copy(receipt = receipt.copy(nonce = UUID.randomUUID().toString()))))
        val different = receipt.copy(binding = receipt.binding.copy(identity = receipt.binding.identity.copy(captureId = MutationId("other-capture"))))
        assertEquals(CapturedCreateCurrentObservation.Unresolved, capture(baseline.copy(receipt = different)))
        assertEquals(before, values()); assertTrue(supported().unchanged)
    }

    @Test fun malformedOpaqueIdentityIsRefusedBeforeProviderQueries() = fixture {
        val adapter = intercepted()
        val malformed = baseline.copy(receipt = receipt.copy(binding = receipt.binding.copy(verifiedRevision = Revision("\uD800"))),
            change = baseline.change.copy(revision = Revision("\uD800")))
        try { adapter.captureCapturedCreateCurrent(malformed); fail("Malformed identity must be rejected") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PROVIDER_REJECTED, failure.category) }
        assertEquals(0, interceptedQueries); assertEquals(0, interceptedBatches)
    }

    @Test fun duplicateLocalMarkerLeavesBothOriginalsUnresolved() = fixture {
        context.contentResolver.insert(uri, candidateValues().apply { put(Events.SYNC_DATA5, field(Events.SYNC_DATA5)) })!!
        val before = values(); assertEquals(CapturedCreateCurrentObservation.Unresolved, capture()); assertEquals(before, values())
        assertEquals(2, rows())
    }

    @Test fun aDuplicateRemoteIdentityWithAnotherLocalMarkerIsNotMerged() = fixture {
        context.contentResolver.insert(uri, candidateValues().apply { put(Events._SYNC_ID, field(Events._SYNC_ID)) })!!
        val before = values(); assertEquals(CapturedCreateCurrentObservation.Unresolved, capture()); assertEquals(before, values())
        assertEquals(2, rows())
    }

    @Test fun purgedOriginalAndUnmarkedReplacementNeverBecomeDeletionOrFallback() = fixture {
        assertEquals(1, context.contentResolver.delete(uri, "${Events._ID}=?", arrayOf(row.toString())))
        assertEquals(CapturedCreateCurrentObservation.Unresolved, capture())
        val replacement = ContentUris.parseId(context.contentResolver.insert(uri, candidateValues())!!)
        assertEquals(CapturedCreateCurrentObservation.Unresolved, capture()); assertEquals(1, rows())
        context.contentResolver.query(uri, arrayOf(Events.SYNC_DATA5, Events._SYNC_ID), "${Events._ID}=?", arrayOf(replacement.toString()), null)!!.use {
            assertTrue(it.moveToFirst()); assertTrue(it.isNull(0)); assertTrue(it.isNull(1))
        }
    }

    @Test fun cleanOriginalCannotBeCapturedOrAcknowledged() = fixture {
        mutate(ContentValues().apply { put(Events.DIRTY, 0) }); val before = values()
        assertEquals(CapturedCreateCurrentObservation.Unresolved, capture()); assertEquals(before, values())
    }

    @Test fun movedOriginalCannotBeCapturedThroughAnotherCalendarOrRowLocator() = fixture {
        val other = target.copy(calendar = CalendarId("other"))
        try {
            val otherCalendar = projection.ensureCalendar(other, CalendarSummary(other.calendar, "Current other fixture"))
            mutate(ContentValues().apply { put(Events.CALENDAR_ID, otherCalendar) })
            val before = values(); assertEquals(CapturedCreateCurrentObservation.Unresolved, capture()); assertEquals(before, values())
        } finally { projection.removeOwnedCalendar(other) }
    }

    @Test fun foreignCalendarOwnerCannotQualifyTheOriginalRow() = fixture {
        val calendars = syncUri(CalendarContract.Calendars.CONTENT_URI)
        val selection = "${CalendarContract.Calendars._ID}=?"
        val args = arrayOf(calendar.toString())
        val owner = context.contentResolver.query(calendars, arrayOf(CalendarContract.Calendars.CAL_SYNC1), selection, args, null)!!.use {
            assertTrue(it.moveToFirst()); it.getString(0)
        }
        val before = values()
        try {
            assertEquals(1, context.contentResolver.update(calendars,
                ContentValues().apply { put(CalendarContract.Calendars.CAL_SYNC1, "foreign-owner") }, selection, args))
            assertEquals(CapturedCreateCurrentObservation.Unresolved, capture()); assertEquals(before, values())
        } finally {
            assertEquals(1, context.contentResolver.update(calendars,
                ContentValues().apply { put(CalendarContract.Calendars.CAL_SYNC1, owner) }, selection, args))
        }
    }

    @Test fun concurrentContentEditAbortsTheSingleAtomicSnapshotAndPreservesTheEdit() = fixture {
        val adapter = intercepted { mutate(ContentValues().apply { put(Events.TITLE, "Concurrent current title") }) }
        assertRejected { adapter.captureCapturedCreateCurrent(baseline) }
        assertEquals(1, interceptedBatches); assertEquals(1, realBatchAttempts)
        assertEquals("Concurrent current title", field(Events.TITLE)); assertEquals("1", field(Events.DIRTY))
    }

    @Test fun concurrentRemoteDuplicateAbortsTheSameSnapshot() = fixture {
        val adapter = intercepted { context.contentResolver.insert(uri, candidateValues().apply { put(Events.SYNC_DATA4, field(Events.SYNC_DATA4)) })!! }
        assertRejected { adapter.captureCapturedCreateCurrent(baseline) }
        assertEquals(1, interceptedBatches); assertEquals(1, realBatchAttempts); assertEquals(2, rows()); assertEquals("1", field(Events.DIRTY))
    }

    @Test fun concurrentLocalMarkerDuplicateAbortsTheSameSnapshot() = fixture {
        val adapter = intercepted { context.contentResolver.insert(uri, candidateValues().apply { put(Events.SYNC_DATA5, field(Events.SYNC_DATA5)) })!! }
        assertRejected { adapter.captureCapturedCreateCurrent(baseline) }
        assertEquals(1, interceptedBatches); assertEquals(1, realBatchAttempts); assertEquals(2, rows()); assertEquals("1", field(Events.DIRTY))
    }

    @Test fun concurrentReminderCreationAbortsAndRetainsTheReminder() = fixture {
        val adapter = intercepted { reminder() }
        assertRejected { adapter.captureCapturedCreateCurrent(baseline) }
        assertEquals(1, interceptedBatches); assertEquals(1, realBatchAttempts)
        assertEquals(1, children(Reminders.CONTENT_URI, Reminders.EVENT_ID)); assertEquals("1", field(Events.DIRTY))
    }

    @Test fun concurrentLocalDeletionDoesNotReturnAStaleLiveSnapshot() = fixture {
        val adapter = intercepted { mutate(ContentValues().apply { put(Events.DELETED, 1) }) }
        assertRejected { adapter.captureCapturedCreateCurrent(baseline) }
        assertEquals(1, interceptedBatches); assertEquals(1, realBatchAttempts)
        assertEquals("1", field(Events.DELETED)); assertTrue(supported().deleted)
    }

    @Test fun inactiveOperationLeasePreventsQueriesAndLeavesOriginalIntact() = fixture {
        val before = values(); val adapter = intercepted(); adapter.admitOperation = { false }
        try { adapter.captureCapturedCreateCurrent(baseline); fail("Inactive lease must cancel") }
        catch (_: CancellationException) {}
        assertEquals(0, interceptedQueries); assertEquals(0, interceptedBatches); assertEquals(before, values())
    }

    @Test fun accessLossAfterBatchCannotReturnAQualifiedSnapshot() = fixture {
        var admitted = true
        val before = values(); val adapter = intercepted(afterBatch = { admitted = false }); adapter.admitOperation = { admitted }
        try { adapter.captureCapturedCreateCurrent(baseline); fail("Lost lease must cancel") }
        catch (_: CancellationException) {}
        assertEquals(1, interceptedBatches); assertEquals(before, values())
    }

    private class Fixture(val context: Context, val allDay: Boolean) {
        val target = CalendarScope(AccountId("captured-current-provider-${UUID.randomUUID()}"), CalendarId("fixture"), 0)
        val localId = LocalEventId(UUID.randomUUID().toString())
        val projection = AndroidCalendarProjection(context)
        val content = EventContent("Captured current provider fixture", if (allDay)
            EventTime.AllDay(LocalDate.of(2030, 3, 30), LocalDate.of(2030, 4, 1)) else
            EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")),
            details = EventDetails())
        private val captured = LocalCreateIntent(LocalCreateReservation(MutationId("capture"), localId, target,
            AdapterFormat("current-provider-import", 1), Instant.parse("2030-10-01T12:00:00Z")), content)
        private val stage = LocalCreateSubmission(LocalCreateSubmissionReservation(captured,
            MutationId(UUID.randomUUID().toString()), AdapterFormat("current-provider-submission", 2)), "opaque-fixture")
        val receipt = CapturedCreateAttachmentReceipt(CapturedCreateBinding(CapturedCreateDispatchIdentity.of(stage),
            EventId("fixture-remote"), Revision("fixture-revision")), UUID.randomUUID().toString())
        val baseline = CapturedCreateBaseline(receipt, RemoteChange.Upsert(receipt.binding.eventId, receipt.binding.verifiedRevision, content))
        var calendar = -1L
        var row = -1L
        var interceptedQueries = 0
        var interceptedBatches = 0
        var realBatchAttempts = 0
        var maskedAlarmQueries = 0
        val uri get() = syncUri(Events.CONTENT_URI)
        fun syncUri(uri: Uri): Uri {
            val account = projection.accountFor(target.account)
            return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type).build()
        }
        fun candidateValues() = ContentValues().apply {
            put(Events.CALENDAR_ID, calendar); put(Events.TITLE, content.title); put(Events.DESCRIPTION, content.description)
            put(Events.EVENT_LOCATION, content.location); put(Events.DIRTY, 1); put(Events.DELETED, 0)
            put(Events.STATUS, Events.STATUS_CONFIRMED); put(Events.HAS_ATTENDEE_DATA, 0); put(Events.HAS_ALARM, 0)
            putNull(Events.ORGANIZER); put(Events.IS_ORGANIZER, 0); put(Events.GUESTS_CAN_MODIFY, 0)
            put(Events.GUESTS_CAN_INVITE_OTHERS, 0); put(Events.GUESTS_CAN_SEE_GUESTS, 0)
            put(Events.ACCESS_LEVEL, Events.ACCESS_DEFAULT); put(Events.AVAILABILITY, Events.AVAILABILITY_BUSY)
            putNull(Events.EVENT_COLOR); putNull(Events.EVENT_COLOR_KEY)
            when (val time = content.time) {
                is EventTime.Timed -> {
                    put(Events.DTSTART, time.start.toEpochMilli()); put(Events.DTEND, time.end.toEpochMilli())
                    put(Events.ALL_DAY, 0); put(Events.EVENT_TIMEZONE, time.zone.id); put(Events.EVENT_END_TIMEZONE, time.zone.id)
                }
                is EventTime.AllDay -> {
                    put(Events.DTSTART, time.start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                    put(Events.DTEND, time.endExclusive.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                    put(Events.ALL_DAY, 1); put(Events.EVENT_TIMEZONE, "UTC"); put(Events.EVENT_END_TIMEZONE, "UTC")
                }
            }
        }
        suspend fun seed() {
            calendar = projection.ensureCalendar(target, CalendarSummary(target.calendar, "Calendeer captured current provider fixture"))
            row = ContentUris.parseId(context.contentResolver.insert(uri, candidateValues())!!)
            assertEquals(content, projection.captureAndStampSimpleCreate(target, row, localId)!!.content)
            assertEquals(AppliedCapturedCreateAttachment(receipt), projection.attachCapturedCreate(receipt))
        }
        suspend fun capture(candidate: CapturedCreateBaseline = baseline) = projection.captureCapturedCreateCurrent(candidate)
        suspend fun supported() = (capture() as CapturedCreateCurrentObservation.Supported).snapshot
        fun mutate(values: ContentValues) {
            assertEquals(1, context.contentResolver.update(uri, values, "${Events._ID}=?", arrayOf(row.toString())))
        }
        fun reminder() {
            context.contentResolver.insert(syncUri(Reminders.CONTENT_URI), ContentValues().apply {
                put(Reminders.EVENT_ID, row); put(Reminders.MINUTES, 10); put(Reminders.METHOD, Reminders.METHOD_ALERT)
            })!!
        }
        fun children(child: Uri, column: String) = context.contentResolver.query(syncUri(child), arrayOf("_id"),
            "$column=?", arrayOf(row.toString()), null)!!.use { it.count }
        fun rows() = context.contentResolver.query(uri, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?",
            arrayOf(calendar.toString()), null)!!.use { it.count }
        fun field(column: String): String? = context.contentResolver.query(uri, arrayOf(column),
            "${Events._ID}=?", arrayOf(row.toString()), null)!!.use {
            assertEquals(1, it.count); assertTrue(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
        }
        fun values(): Map<String, String?> {
            val columns = listOf(Events.CALENDAR_ID, Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART,
                Events.DTEND, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE, Events.DIRTY, Events.DELETED,
                Events._SYNC_ID, Events.SYNC_DATA1, Events.SYNC_DATA2, Events.SYNC_DATA3, Events.SYNC_DATA4, Events.SYNC_DATA5,
                Events.SYNC_DATA6, Events.SYNC_DATA7, Events.SYNC_DATA8, Events.SYNC_DATA9, Events.SYNC_DATA10,
                Events.STATUS, Events.HAS_ALARM, Events.HAS_ATTENDEE_DATA, Events.EVENT_COLOR, Events.EVENT_COLOR_KEY,
                Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.DURATION, Events.ORGANIZER,
                Events.ACCESS_LEVEL, Events.AVAILABILITY, Events.CUSTOM_APP_PACKAGE, Events.CUSTOM_APP_URI)
            return columns.associateWith(::field)
        }
        fun intercepted(afterBatch: () -> Unit = {}, maskAlarm: Boolean = false, beforeBatch: () -> Unit = {}): AndroidCalendarProjection {
            val real = context.contentResolver
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri): String? = null
                override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Snapshot must not insert")
                override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = error("Snapshot must not delete")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = error("Snapshot must not update")
                override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor? {
                    interceptedQueries++
                    val actual = real.query(uri, columns, selection, args, order) ?: return null
                    if (!maskAlarm || actual.getColumnIndex(Events.HAS_ALARM) < 0) return actual
                    maskedAlarmQueries++
                    // Mask the returned scalar, never the real data or the child queries.
                    return object : CursorWrapper(actual) {
                        private fun masked(index: Int) = getColumnName(index) == Events.HAS_ALARM
                        override fun getType(columnIndex: Int) = if (masked(columnIndex)) Cursor.FIELD_TYPE_INTEGER else super.getType(columnIndex)
                        override fun isNull(columnIndex: Int) = if (masked(columnIndex)) false else super.isNull(columnIndex)
                        override fun getInt(columnIndex: Int) = if (masked(columnIndex)) 0 else super.getInt(columnIndex)
                        override fun getLong(columnIndex: Int) = if (masked(columnIndex)) 0L else super.getLong(columnIndex)
                        override fun getString(columnIndex: Int): String? = if (masked(columnIndex)) "0" else super.getString(columnIndex)
                    }
                }
                override fun applyBatch(operations: ArrayList<ContentProviderOperation>): Array<ContentProviderResult> {
                    assertEquals(6, operations.size); assertTrue(operations.all { it.isAssertQuery })
                    interceptedBatches++; beforeBatch()
                    // A setup exception must not masquerade as a rejection by the atomic Provider assertions.
                    realBatchAttempts++
                    val result = real.applyBatch(CalendarContract.AUTHORITY, operations); afterBatch(); return result
                }
            }
            val wrappedResolver = ContentResolver.wrap(provider)
            val wrapped = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getContentResolver() = wrappedResolver
            }
            return AndroidCalendarProjection(wrapped)
        }
        suspend fun close() {
            projection.admitOperation = { true }
            projection.removeOwnedCalendar(target); AccountManager.get(context).removeAccountExplicitly(projection.accountFor(target.account))
        }
    }
    private fun fixture(allDay: Boolean = false, block: suspend Fixture.() -> Unit) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val fixture = Fixture(ApplicationProvider.getApplicationContext(), allDay)
        assumeTrue(fixture.projection.hasPermissions())
        try { fixture.seed(); fixture.block() } finally { fixture.close() }
    }
    private suspend fun assertRejected(block: suspend () -> CapturedCreateCurrentObservation) {
        try { block(); fail("Changed atomic snapshot must be refused") }
        catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.PROVIDER_REJECTED, failure.category) }
    }
}
