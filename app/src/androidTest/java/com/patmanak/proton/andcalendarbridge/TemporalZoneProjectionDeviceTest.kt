// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in, isolated native fixtures. Does not use a Proton session or existing user calendar. */
class TemporalZoneProjectionDeviceTest {
    @Test fun allDayExclusiveUtcDatesSurviveSpringAndAutumnAcknowledgement() {
        fixture(day("2026-03-28", "2026-03-30"), day("2026-03-29", "2026-03-31"),
            "2026-03-28T00:00:00Z", "2026-03-30T00:00:00Z", "2026-03-29T00:00:00Z", "2026-03-31T00:00:00Z", "UTC", true)
        fixture(day("2026-10-24", "2026-10-26"), day("2026-10-25", "2026-10-28"),
            "2026-10-24T00:00:00Z", "2026-10-26T00:00:00Z", "2026-10-25T00:00:00Z", "2026-10-28T00:00:00Z", "UTC", true)
    }

    @Test fun timedZoneAndDurationSurviveNativeAcknowledgementAndNewerEdits() {
        val cases = listOf(
            listOf("Europe/Paris", "2026-03-28T11:00:00Z", "2026-03-29T10:00:00Z", "2026-03-28T12:00:00Z", "2026-03-29T12:00:00Z"),
            listOf("Europe/Paris", "2026-10-24T10:00:00Z", "2026-10-25T11:00:00Z", "2026-10-24T11:00:00Z", "2026-10-25T13:00:00Z"),
            listOf("America/New_York", "2026-03-07T17:00:00Z", "2026-03-08T16:00:00Z", "2026-03-07T18:00:00Z", "2026-03-08T18:00:00Z"),
            listOf("America/New_York", "2026-10-31T16:00:00Z", "2026-11-01T17:00:00Z", "2026-10-31T17:00:00Z", "2026-11-01T19:00:00Z"),
            listOf("Australia/Lord_Howe", "2026-10-03T01:30:00Z", "2026-10-04T01:00:00Z", "2026-10-03T02:30:00Z", "2026-10-04T03:00:00Z"),
            listOf("Australia/Lord_Howe", "2026-04-04T01:00:00Z", "2026-04-05T01:30:00Z", "2026-04-04T02:00:00Z", "2026-04-05T03:30:00Z"),
            listOf("Asia/Kathmandu", "2026-01-01T18:30:00Z", "2026-01-01T19:30:00Z", "2026-01-01T19:30:00Z", "2026-01-01T21:30:00Z"),
            listOf("Pacific/Kiritimati", "2026-01-01T09:30:00Z", "2026-01-01T10:30:00Z", "2026-01-01T10:30:00Z", "2026-01-01T12:30:00Z"),
            listOf("Pacific/Pago_Pago", "2026-01-02T10:30:00Z", "2026-01-02T11:30:00Z", "2026-01-02T11:30:00Z", "2026-01-02T13:30:00Z"),
        )
        for (case in cases) fixture(timed(case[0], case[1], case[2]), timed(case[0], case[3], case[4]),
            case[1], case[2], case[3], case[4], case[0], false)
    }

    private fun fixture(old: EventTime, next: EventTime, oldStart: String, oldEnd: String,
        nextStart: String, nextEnd: String, zone: String, allDay: Boolean) = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val projection = AndroidCalendarProjection(context)
        assumeTrue(projection.hasPermissions())
        val scope = CalendarScope(AccountId("synthetic-zone-provider-${UUID.randomUUID()}"), CalendarId("fixture"), 0)
        val format = AdapterFormat("synthetic-zone-import", 1)
        val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-before"),
            EventContent("Synthetic zone fixture", old, details = EventDetails()))
        val intent = LocalEditIntent(MutationId("synthetic-intent"), scope, format, base, base.content.copy(time = next))
        val record = TimeUpdateAttemptRecord(MutationId("synthetic-attempt"), LocalTimeEditPreparation(intent,
            Instant.parse("2026-01-01T00:00:00Z"), AdapterFormat("synthetic-zone-preparation", 1), "opaque-preparation"),
            AdapterFormat("synthetic-zone-submission", 1), "opaque-submission", TimeUpdateAttemptState.OUTCOME_UNKNOWN)
        val observed = base.copy(revision = Revision("synthetic-after"), content = intent.content)
        val receipt = TimeUpdateAcknowledgementReceipt.of(TimeUpdateVerifiedResult.of(record,
            TimeUpdateSubmissionObservation.Verified(record, observed)), UUID.randomUUID().toString())
        fun syncUri(uri: Uri): Uri = projection.accountFor(scope.account).let { account -> uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type).build() }
        val events = syncUri(Events.CONTENT_URI)
        try {
            val calendar = projection.ensureCalendarState(scope, CalendarSummary(scope.calendar, "Synthetic zone fixture"), true).id
            projection.apply(ProjectionIntent(scope, base))
            val row = context.contentResolver.query(events, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?",
                arrayOf(calendar.toString()), null)!!.use { cursor ->
                assertEquals(1, cursor.count); assertTrue(cursor.moveToFirst()); cursor.getLong(0)
            }
            val rowUri = syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, row))
            fun snapshot(): Map<String, String?> = context.contentResolver.query(rowUri, null, null, null, null)!!.use { cursor ->
                assertEquals(1, cursor.count); assertTrue(cursor.moveToFirst())
                cursor.columnNames.indices.associate { index -> cursor.getColumnName(index) to
                    if (cursor.isNull(index)) null else cursor.getString(index) }
            }
            fun mutate(values: ContentValues) { assertEquals(1, context.contentResolver.update(rowUri, values, null, null)) }
            fun assertTime(start: String, end: String) {
                val values = snapshot()
                assertEquals(Instant.parse(start).toEpochMilli().toString(), values[Events.DTSTART])
                assertEquals(Instant.parse(end).toEpochMilli().toString(), values[Events.DTEND])
                assertEquals(zone, values[Events.EVENT_TIMEZONE]); assertEquals(zone, values[Events.EVENT_END_TIMEZONE])
                assertEquals(if (allDay) "1" else "0", values[Events.ALL_DAY])
            }
            assertTime(oldStart, oldEnd)
            mutate(ContentValues().apply {
                put(Events.DTSTART, Instant.parse(nextStart).toEpochMilli()); put(Events.DTEND, Instant.parse(nextEnd).toEpochMilli())
                put(Events.DIRTY, 1)
            })
            assertTime(nextStart, nextEnd)
            val newerEditStep = if (allDay) 86_400_000L else 60_000L
            // A newer native duration edit must survive refusal of the older receipt.
            mutate(ContentValues().apply { put(Events.DTEND, Instant.parse(nextEnd).toEpochMilli() + newerEditStep) })
            val newerBeforeAck = snapshot()
            assertNull(projection.acknowledgeTimeUpdate(receipt)); assertEquals(newerBeforeAck, snapshot())
            mutate(ContentValues().apply { put(Events.DTEND, Instant.parse(nextEnd).toEpochMilli()) })
            val before = snapshot()
            assertEquals(AppliedTimeUpdateAcknowledgement(receipt), projection.acknowledgeTimeUpdate(receipt))
            val after = snapshot()
            assertEquals("0", after[Events.DIRTY])
            assertEquals(before.filterKeys { it !in setOf(Events.DIRTY, Events.SYNC_DATA2, Events.SYNC_DATA3) },
                after.filterKeys { it !in setOf(Events.DIRTY, Events.SYNC_DATA2, Events.SYNC_DATA3) })
            assertTime(nextStart, nextEnd)
            context.contentResolver.query(events, arrayOf(Events._ID), "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); assertEquals(row, it.getLong(0))
            }
            // Reopening historical proof must preserve later edits on the same native row.
            mutate(ContentValues().apply { put(Events.DTSTART, Instant.parse(nextStart).toEpochMilli() + newerEditStep); put(Events.DIRTY, 1) })
            val newerAfterAck = snapshot()
            assertEquals(AppliedTimeUpdateAcknowledgement(receipt), AndroidCalendarProjection(context).inspectTimeUpdateAcknowledgement(receipt))
            assertEquals(newerAfterAck, snapshot()); assertNull(projection.acknowledgeTimeUpdate(receipt))
        } finally {
            projection.removeOwnedCalendar(scope)
            AccountManager.get(context).removeAccountExplicitly(projection.accountFor(scope.account))
        }
    }

    private fun day(start: String, end: String) = EventTime.AllDay(LocalDate.parse(start), LocalDate.parse(end))
    private fun timed(zone: String, start: String, end: String) = EventTime.Timed(Instant.parse(start), Instant.parse(end), ZoneId.of(zone))
}
