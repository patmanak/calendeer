package com.patmanak.proton.andcalendarbridge

import android.accounts.AccountManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionFailure
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated synthetic Provider fixtures only. No Proton transport or existing calendar access. */
@RunWith(AndroidJUnit4::class)
class RecurrenceProviderDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val projection = AndroidCalendarProjection(context)
    private val resolver get() = context.contentResolver

    @Before fun optIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("providerFixture") == "true")
        assumeTrue(projection.hasPermissions())
    }

    @Test fun timedOriginalBecomesAllDayAcrossDstWithoutDuplicateNativeOccurrences() = runBlocking {
        qualifyConversion(originalAllDay = false)
    }

    @Test fun allDayOriginalBecomesTimedAcrossDstWithoutDuplicateNativeOccurrences() = runBlocking {
        qualifyConversion(originalAllDay = true)
    }

    /** Characterizes a known Provider limitation, not correct RFC fold handling. No guard is active yet. */
    @Test fun ambiguousDstMasterStartCurrentlyLeavesShiftedOriginalInstance() = runBlocking {
        for (converted in listOf(false, true)) for (cancelled in listOf(false, true)) fixture { scope, _, calendar, _ ->
            val master = timed("fold-master", "2030-10-27T00:30:00Z", EventRecurrence("fold-series", "FREQ=DAILY;COUNT=3"), "Europe/Paris")
            projection.apply(ProjectionIntent(scope, master)); val masterId = row(calendar, master.content.title).id
            val nativeSlots = listOf(
                Instance(masterId, Instant.parse("2030-10-27T01:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), false),
                Instance(masterId, Instant.parse("2030-10-28T01:30:00Z"), Instant.parse("2030-10-28T02:30:00Z"), false),
                Instance(masterId, Instant.parse("2030-10-29T01:30:00Z"), Instant.parse("2030-10-29T02:30:00Z"), false),
            )
            val storedStart = resolver.query(Events.CONTENT_URI, arrayOf(Events.DTSTART), "${Events.CALENDAR_ID}=? AND ${Events._ID}=?",
                arrayOf(calendar.toString(), masterId.toString()), null)!!.use {
                assertTrue(it.moveToFirst()); it.getLong(0)
            }
            assertEquals(Instant.parse("2030-10-27T00:30:00Z").toEpochMilli(), storedStart)
            assertEquals(nativeSlots, conversionWindow(calendar))
            val child = RemoteChange.Upsert(EventId("fold-child"), Revision("v1"), EventContent("fold-child",
                if (converted) EventTime.AllDay(LocalDate.parse("2030-10-30"), LocalDate.parse("2030-10-31")) else
                    EventTime.Timed(Instant.parse("2030-10-30T09:00:00Z"), Instant.parse("2030-10-30T10:00:00Z"), ZoneId.of("Europe/Paris")),
                recurrence = EventRecurrence("fold-series", original = point("2030-10-27T00:30:00Z"), cancelled = cancelled)))
            tracked(scope, child); val childId = row(calendar, child.content.title).id
            val moved = if (cancelled) emptyList() else listOf(if (converted)
                Instance(childId, Instant.parse("2030-10-30T00:00:00Z"), Instant.parse("2030-10-31T00:00:00Z"), true) else
                Instance(childId, Instant.parse("2030-10-30T09:00:00Z"), Instant.parse("2030-10-30T10:00:00Z"), false))
            // The shifted first slot incorrectly survives both movement and cancellation.
            assertEquals(nativeSlots + moved, conversionWindow(calendar))
        }
    }

    private suspend fun qualifyConversion(originalAllDay: Boolean) = fixture { scope, _, calendar, _ ->
        val master = conversionMaster(originalAllDay)
        projection.apply(ProjectionIntent(scope, master)); val masterId = row(calendar, master.content.title).id
        assertEquals(originalSlots(masterId, originalAllDay), conversionWindow(calendar)) // Prime native expansion before the exception.
        val child = conversionChild(originalAllDay); val receipt = tracked(scope, child)
        val childId = row(calendar, child.content.title).id
        val remaining = unchangedSlots(masterId, originalAllDay)
        val moved = convertedSlot(childId, originalAllDay)
        assertEquals(remaining, instances(calendar, "2030-10-25T00:00:00Z", "2030-10-29T00:00:00Z"))
        assertEquals(listOf(moved), instances(calendar, "2030-10-30T00:00:00Z", "2030-11-01T00:00:00Z"))
        assertEquals(remaining + moved, conversionWindow(calendar)); assertEquals(2, count(calendar))
        assertEquals(masterId, row(calendar, child.content.title).parent)
        assertEquals(AppliedProjectionWitness(receipt), AndroidCalendarProjection(context).inspectProjectionReceipt(receipt))
        assertEquals(remaining + moved, conversionWindow(calendar)); assertEquals(childId, row(calendar, child.content.title).id)
    }

    @Test fun cancelledConvertedOccurrencesStayAbsentAndChildDeletionRestoresOriginalSlots() = runBlocking {
        for (originalAllDay in listOf(false, true)) fixture { scope, _, calendar, _ ->
            val master = conversionMaster(originalAllDay)
            projection.apply(ProjectionIntent(scope, master)); val masterId = row(calendar, master.content.title).id
            assertEquals(originalSlots(masterId, originalAllDay), conversionWindow(calendar))
            val child = conversionChild(originalAllDay).let { it.copy(content = it.content.copy(recurrence = it.content.recurrence!!.copy(cancelled = true))) }
            val receipt = tracked(scope, child)
            val remaining = unchangedSlots(masterId, originalAllDay)
            assertEquals(remaining, conversionWindow(calendar))
            assertEquals(remaining, instances(calendar, "2030-10-25T00:00:00Z", "2030-10-29T00:00:00Z"))
            assertTrue(instances(calendar, "2030-10-30T00:00:00Z", "2030-11-01T00:00:00Z").isEmpty())
            assertEquals(AppliedProjectionWitness(receipt), AndroidCalendarProjection(context).inspectProjectionReceipt(receipt))
            projection.apply(ProjectionIntent(scope, RemoteChange.Deleted(child.eventId, Revision("child-deleted"))))
            assertEquals(originalSlots(masterId, originalAllDay), conversionWindow(calendar))
            assertEquals(1, count(calendar)); assertNull(projection.inspectProjectionReceipt(receipt))
        }
    }

    @Test fun convertedOccurrenceCanReturnToItsOriginalDateTypeWithoutStaleInstances() = runBlocking {
        for (originalAllDay in listOf(false, true)) fixture { scope, _, calendar, _ ->
            val master = conversionMaster(originalAllDay)
            projection.apply(ProjectionIntent(scope, master)); val masterId = row(calendar, master.content.title).id
            val child = conversionChild(originalAllDay); val old = tracked(scope, child)
            val childId = row(calendar, child.content.title).id
            assertEquals(unchangedSlots(masterId, originalAllDay) + convertedSlot(childId, originalAllDay), conversionWindow(calendar))
            val reverted = child.copy(revision = Revision("reconverted"), content = child.content.copy(time = if (originalAllDay)
                EventTime.AllDay(LocalDate.parse("2030-10-31"), LocalDate.parse("2030-11-01")) else
                EventTime.Timed(Instant.parse("2030-10-31T16:00:00Z"), Instant.parse("2030-10-31T17:00:00Z"), ZoneId.of("Europe/Paris"))))
            val receipt = tracked(scope, reverted, child)
            val replacement = if (originalAllDay) Instance(childId, Instant.parse("2030-10-31T00:00:00Z"), Instant.parse("2030-11-01T00:00:00Z"), true)
                else Instance(childId, Instant.parse("2030-10-31T16:00:00Z"), Instant.parse("2030-10-31T17:00:00Z"), false)
            assertEquals(unchangedSlots(masterId, originalAllDay) + replacement, conversionWindow(calendar))
            assertTrue(instances(calendar, "2030-10-30T00:00:00Z", "2030-10-30T23:59:59Z").isEmpty())
            assertEquals(2, count(calendar)); assertEquals(childId, row(calendar, reverted.content.title).id)
            assertNull(projection.inspectProjectionReceipt(old))
            assertEquals(AppliedProjectionWitness(receipt), AndroidCalendarProjection(context).inspectProjectionReceipt(receipt))
        }
    }

    private fun conversionMaster(allDay: Boolean) = if (allDay) RemoteChange.Upsert(EventId("converted-master"), Revision("v1"),
        EventContent("converted-master", EventTime.AllDay(LocalDate.parse("2030-10-26"), LocalDate.parse("2030-10-27")),
            recurrence = EventRecurrence("converted-series", "FREQ=DAILY;COUNT=3")))
        else timed("converted-master", "2030-10-26T08:00:00Z", EventRecurrence("converted-series", "FREQ=DAILY;COUNT=3"), "Europe/Paris")

    private fun conversionChild(originalAllDay: Boolean) = RemoteChange.Upsert(EventId("converted-child"), Revision("v1"),
        EventContent("converted-child", if (originalAllDay) EventTime.Timed(Instant.parse("2030-10-30T09:00:00Z"),
            Instant.parse("2030-10-30T10:00:00Z"), ZoneId.of("Europe/Paris")) else
            EventTime.AllDay(LocalDate.parse("2030-10-30"), LocalDate.parse("2030-10-31")), recurrence =
            EventRecurrence("converted-series", original = if (originalAllDay) RecurrencePoint.AllDay(LocalDate.parse("2030-10-27")) else point("2030-10-27T09:00:00Z"))))

    private suspend fun tracked(scope: CalendarScope, child: RemoteChange.Upsert, previous: RemoteChange.Upsert? = null): ProjectionReceipt {
        val receipt = ProjectionReceipt(scope, AdapterFormat("converted-instances-fixture", 1), child, UUID.randomUUID().toString(), previous)
        assertEquals(AppliedProjectionWitness(receipt), projection.applyTracked(receipt))
        return receipt
    }

    private fun unchangedSlots(masterId: Long, allDay: Boolean): List<Instance> = if (allDay) listOf(
        Instance(masterId, Instant.parse("2030-10-26T00:00:00Z"), Instant.parse("2030-10-27T00:00:00Z"), true),
        Instance(masterId, Instant.parse("2030-10-28T00:00:00Z"), Instant.parse("2030-10-29T00:00:00Z"), true),
    ) else listOf(
        Instance(masterId, Instant.parse("2030-10-26T08:00:00Z"), Instant.parse("2030-10-26T09:00:00Z"), false),
        Instance(masterId, Instant.parse("2030-10-28T09:00:00Z"), Instant.parse("2030-10-28T10:00:00Z"), false),
    )

    private fun originalSlots(masterId: Long, allDay: Boolean): List<Instance> {
        val remaining = unchangedSlots(masterId, allDay)
        val middle = if (allDay) Instance(masterId, Instant.parse("2030-10-27T00:00:00Z"), Instant.parse("2030-10-28T00:00:00Z"), true)
            else Instance(masterId, Instant.parse("2030-10-27T09:00:00Z"), Instant.parse("2030-10-27T10:00:00Z"), false)
        return listOf(remaining[0], middle, remaining[1])
    }

    private fun convertedSlot(childId: Long, originalAllDay: Boolean) = if (originalAllDay)
        Instance(childId, Instant.parse("2030-10-30T09:00:00Z"), Instant.parse("2030-10-30T10:00:00Z"), false)
        else Instance(childId, Instant.parse("2030-10-30T00:00:00Z"), Instant.parse("2030-10-31T00:00:00Z"), true)

    private fun conversionWindow(calendar: Long) = instances(calendar, "2030-10-25T00:00:00Z", "2030-11-02T00:00:00Z")

    @Test fun ensureReportsRecreationEvenWhenProviderMayReuseCalendarId() = runBlocking {
        fixture { first, _, _, _ ->
            val summary = CalendarSummary(first.calendar, "Recreated recurrence fixture", 0x167D8D)
            projection.removeOwnedCalendar(first)
            val created = projection.ensureCalendarState(first, summary)
            assertTrue(created.created)
            val existing = projection.ensureCalendarState(first, summary)
            assertFalse(existing.created)
            assertEquals(created.id, existing.id)
            projection.removeOwnedCalendar(first)
            val recreated = AndroidCalendarProjection(context).ensureCalendarState(first, summary)
            // The Provider may return the same numeric ID. Creation must not be inferred from it.
            assertTrue(recreated.created)
            assertEquals(recreated.id, projection.ensureCalendar(first, summary))
            assertFalse(projection.ensureCalendarState(first, summary).created)
        }
    }

    @Test fun weeklyKeepsLocalHourAcrossDstAndMonthlySkipsMissingDay() = runBlocking {
        fixture { scope, _, calendar, _ ->
            val weekly = timed("weekly", "2030-03-24T09:00:00Z", EventRecurrence("weekly-uid", "FREQ=WEEKLY;COUNT=3"), "Europe/Paris")
            projection.apply(ProjectionIntent(scope, weekly))
            assertEquals(listOf("2030-03-24T09:00:00Z", "2030-03-31T08:00:00Z", "2030-04-07T08:00:00Z").map(Instant::parse),
                instances(calendar, "2030-03-23T00:00:00Z", "2030-04-08T00:00:00Z").map { it.start })
            val monthly = timed("monthly", "2030-01-31T10:00:00Z", EventRecurrence("monthly-uid", "FREQ=MONTHLY;COUNT=3"))
            projection.apply(ProjectionIntent(scope, monthly))
            val id = row(calendar, "monthly").id
            assertEquals(listOf("2030-01-31T10:00:00Z", "2030-03-31T10:00:00Z", "2030-05-31T10:00:00Z").map(Instant::parse),
                instances(calendar, "2030-01-01T00:00:00Z", "2030-06-01T00:00:00Z").filter { it.event == id }.map { it.start })
        }
    }

    @Test fun dailyExcludedAndAdditionalDatesProduceExactInstances() = runBlocking {
        fixture { scope, _, calendar, _ ->
            val recurrence = EventRecurrence("dates-uid", "FREQ=DAILY;COUNT=3",
                additionalDates = listOf(point("2030-01-05T10:00:00Z")),
                excludedDates = listOf(point("2030-01-02T10:00:00Z")))
            val event = timed("dates", "2030-01-01T10:00:00Z", recurrence)
            projection.apply(ProjectionIntent(scope, event))
            AndroidCalendarProjection(context).apply(ProjectionIntent(scope, event))
            assertEquals(1, count(calendar))
            assertEquals(listOf("2030-01-01T10:00:00Z", "2030-01-03T10:00:00Z", "2030-01-05T10:00:00Z").map(Instant::parse),
                instances(calendar, "2030-01-01T00:00:00Z", "2030-01-06T00:00:00Z").map { it.start })
            val row = row(calendar, "dates")
            assertNull(row.end)
            assertEquals("PT3600S", row.duration)
            assertEquals("FREQ=DAILY;COUNT=3", row.rule)
        }
    }

    @Test fun yearlyLeapDayAndOrdinalWeekdayProduceExactInstances() = runBlocking {
        fixture { scope, _, calendar, _ ->
            projection.apply(ProjectionIntent(scope, timed("leap-day", "2028-02-29T10:00:00Z",
                EventRecurrence("leap-day-uid", "FREQ=YEARLY;COUNT=3"))))
            val leapId = row(calendar, "leap-day").id
            assertEquals(listOf("2028-02-29T10:00:00Z", "2032-02-29T10:00:00Z", "2036-02-29T10:00:00Z").map(Instant::parse),
                instances(calendar, "2028-02-28T00:00:00Z", "2036-03-01T00:00:00Z")
                    .filter { it.event == leapId }.map { it.start })
            projection.apply(ProjectionIntent(scope, timed("third-monday", "2030-05-20T10:00:00Z",
                EventRecurrence("third-monday-uid", "FREQ=YEARLY;BYMONTH=5;BYDAY=3MO;COUNT=3"))))
            val mondayId = row(calendar, "third-monday").id
            assertEquals(listOf("2030-05-20T10:00:00Z", "2031-05-19T10:00:00Z", "2032-05-17T10:00:00Z").map(Instant::parse),
                instances(calendar, "2030-05-01T00:00:00Z", "2032-06-01T00:00:00Z")
                    .filter { it.event == mondayId }.map { it.start })
        }
    }

    @Test fun allDaySeriesUsesWholeDaysWithUtcExclusionsAndAdditionalDates() = runBlocking {
        fixture { scope, _, calendar, _ ->
            val event = RemoteChange.Upsert(EventId("days"), Revision("v1"), EventContent("days",
                EventTime.AllDay(LocalDate.parse("2030-03-30"), LocalDate.parse("2030-04-01")),
                recurrence = EventRecurrence("days-uid", "FREQ=DAILY;COUNT=3",
                    additionalDates = listOf(RecurrencePoint.AllDay(LocalDate.parse("2030-04-04"))),
                    excludedDates = listOf(RecurrencePoint.AllDay(LocalDate.parse("2030-03-31"))))))
            projection.apply(ProjectionIntent(scope, event))
            val actual = instances(calendar, "2030-03-29T00:00:00Z", "2030-04-07T00:00:00Z")
            assertEquals(listOf("2030-03-30T00:00:00Z", "2030-04-01T00:00:00Z", "2030-04-04T00:00:00Z").map(Instant::parse), actual.map { it.start })
            assertTrue(actual.all { it.end.epochSecond - it.start.epochSecond == 172800L })
            assertEquals("P2D", row(calendar, "days").duration)
        }
    }

    @Test fun movedAndCancelledExceptionsReplaceOriginalAndDeleteRestoresIt() = runBlocking {
        fixture { scope, _, calendar, _ ->
            val master = timed("master", "2030-07-01T10:00:00Z", EventRecurrence("series", "FREQ=DAILY;COUNT=3"))
            projection.apply(ProjectionIntent(scope, master))
            val moved = timed("moved", "2030-07-02T16:00:00Z", EventRecurrence("series", original = point("2030-07-02T10:00:00Z")))
            val cancelled = timed("cancelled", "2030-07-03T10:00:00Z", EventRecurrence("series", original = point("2030-07-03T10:00:00Z"), cancelled = true))
            projection.apply(ProjectionIntent(scope, moved))
            val movedId = row(calendar, "moved").id
            projection.apply(ProjectionIntent(scope, cancelled))
            AndroidCalendarProjection(context).apply(ProjectionIntent(scope, moved))
            assertEquals(movedId, row(calendar, "moved").id)
            assertEquals(row(calendar, "master").id, row(calendar, "moved").parent)
            assertEquals(Instant.parse("2030-07-02T10:00:00Z").toEpochMilli(), row(calendar, "moved").original)
            assertEquals(listOf("2030-07-01T10:00:00Z", "2030-07-02T16:00:00Z").map(Instant::parse), july(calendar).map { it.start })
            projection.apply(ProjectionIntent(scope, RemoteChange.Deleted(cancelled.eventId, Revision("v2"))))
            projection.apply(ProjectionIntent(scope, RemoteChange.Deleted(moved.eventId, Revision("v2"))))
            assertEquals(listOf("2030-07-01T10:00:00Z", "2030-07-02T10:00:00Z", "2030-07-03T10:00:00Z").map(Instant::parse), july(calendar).map { it.start })
        }
    }

    @Test fun allDayOriginalCanBecomeTimedWithoutLeavingOriginalOccurrence() = runBlocking {
        fixture { scope, _, calendar, _ ->
            val master = RemoteChange.Upsert(EventId("days"), Revision("v1"), EventContent("days",
                EventTime.AllDay(LocalDate.parse("2030-07-01"), LocalDate.parse("2030-07-02")),
                recurrence = EventRecurrence("days-uid", "FREQ=DAILY;COUNT=3")))
            projection.apply(ProjectionIntent(scope, master))
            val moved = timed("timed-child", "2030-07-02T14:00:00Z", EventRecurrence("days-uid",
                original = RecurrencePoint.AllDay(LocalDate.parse("2030-07-02"))))
            projection.apply(ProjectionIntent(scope, moved))
            val child = row(calendar, "timed-child")
            resolver.query(Events.CONTENT_URI, arrayOf(Events.ALL_DAY, Events.ORIGINAL_ALL_DAY),
                "${Events._ID}=?", arrayOf(child.id.toString()), null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)); assertEquals(1, it.getInt(1))
            }
            assertEquals(listOf("2030-07-01T00:00:00Z", "2030-07-02T14:00:00Z", "2030-07-03T00:00:00Z").map(Instant::parse),
                july(calendar).map { it.start })
        }
    }

    @Test fun explicitRecurrenceDatesWithoutRuleAreExpanded() = runBlocking {
        fixture { scope, _, calendar, _ ->
            projection.apply(ProjectionIntent(scope, timed("date-list", "2030-07-01T10:00:00Z",
                EventRecurrence("dates-only", additionalDates = listOf(point("2030-07-03T10:00:00Z"))))))
            assertEquals(listOf("2030-07-01T10:00:00Z", "2030-07-03T10:00:00Z").map(Instant::parse), july(calendar).map { it.start })
        }
    }

    @Test fun originalSlotTypeMustMatchMasterRegardlessOfMovedStartType() = runBlocking {
        fixture { scope, _, calendar, _ ->
            val allDayMaster = RemoteChange.Upsert(EventId("days"), Revision("v1"), EventContent("days",
                EventTime.AllDay(LocalDate.parse("2030-07-01"), LocalDate.parse("2030-07-02")),
                recurrence = EventRecurrence("days-uid", "FREQ=DAILY;COUNT=3")))
            val timedMaster = timed("hours", "2030-07-01T10:00:00Z", EventRecurrence("hours-uid", "FREQ=DAILY;COUNT=3"))
            projection.apply(ProjectionIntent(scope, allDayMaster))
            projection.apply(ProjectionIntent(scope, timedMaster))
            val incompatible = listOf(
                timed("wrong-timed-slot", "2030-07-02T14:00:00Z", EventRecurrence("days-uid",
                    original = point("2030-07-02T00:00:00Z"))),
                timed("wrong-day-slot", "2030-07-02T14:00:00Z", EventRecurrence("hours-uid",
                    original = RecurrencePoint.AllDay(LocalDate.parse("2030-07-02")))),
            )
            val before = july(calendar)
            for (child in incompatible) {
                try { projection.apply(ProjectionIntent(scope, child)); fail("Incompatible original slot was admitted") }
                catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.IDENTITY_CONFLICT, failure.category) }
            }
            assertEquals(2, count(calendar))
            assertEquals(before, july(calendar))
        }
    }

    @Test fun exceptionMovedOutsideQueriedWindowStillRemovesItsOriginal() = runBlocking {
        fixture { scope, _, calendar, _ ->
            projection.apply(ProjectionIntent(scope, timed("master", "2030-07-01T10:00:00Z", EventRecurrence("series", "FREQ=DAILY;COUNT=3"))))
            projection.apply(ProjectionIntent(scope, timed("moved", "2030-07-20T16:00:00Z", EventRecurrence("series", original = point("2030-07-02T10:00:00Z")))))
            assertEquals(listOf("2030-07-01T10:00:00Z", "2030-07-03T10:00:00Z").map(Instant::parse), july(calendar).map { it.start })
            assertEquals(listOf(Instant.parse("2030-07-20T16:00:00Z")), instances(calendar, "2030-07-19T00:00:00Z", "2030-07-21T00:00:00Z").map { it.start })
        }
    }

    @Test fun deletionIsScopedAndReplayCleansChildrenAfterMasterAlreadyDisappeared() = runBlocking {
        fixture { first, second, firstCalendar, secondCalendar ->
            val master = timed("master", "2030-07-01T10:00:00Z", EventRecurrence("series", "FREQ=DAILY;COUNT=3"))
            val child = timed("child", "2030-07-02T16:00:00Z", EventRecurrence("series", original = point("2030-07-02T10:00:00Z")))
            for (scope in listOf(first, second)) {
                projection.apply(ProjectionIntent(scope, master)); projection.apply(ProjectionIntent(scope, child))
            }
            val delete = ProjectionIntent(first, RemoteChange.Deleted(master.eventId, Revision("deleted")))
            projection.apply(delete, "series")
            projection.apply(delete, "series")
            assertEquals(0, count(firstCalendar))
            assertEquals(2, count(secondCalendar))
            // Simulate an interrupted legacy cleanup: only the master row was removed.
            val masterId = row(secondCalendar, "master").id
            val account = projection.accountFor(second.account)
            val uri = Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Events.ACCOUNT_NAME, account.name).appendQueryParameter(Events.ACCOUNT_TYPE, account.type).build()
            resolver.delete(uri, "${Events._ID}=?", arrayOf(masterId.toString()))
            projection.apply(ProjectionIntent(second, RemoteChange.Deleted(master.eventId, Revision("deleted"))), "series")
            assertEquals(0, count(secondCalendar))
            assertTrue(july(firstCalendar).isEmpty()); assertTrue(july(secondCalendar).isEmpty())
        }
    }

    @Test fun simpleMasterSimpleTransitionsPreserveIdentityAndClearRecurrence() = runBlocking {
        fixture { scope, _, calendar, _ ->
            val simple = timed("transition", "2030-07-01T10:00:00Z", null)
            projection.apply(ProjectionIntent(scope, simple))
            val id = row(calendar, "transition").id
            // Simulate the old provider representation, before SYNC_DATA4 existed.
            val account = projection.accountFor(scope.account)
            val uri = Events.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(Events.ACCOUNT_NAME, account.name).appendQueryParameter(Events.ACCOUNT_TYPE, account.type).build()
            resolver.update(uri, ContentValues().apply { putNull(Events.SYNC_DATA4) }, "${Events._ID}=?", arrayOf(id.toString()))
            val master = simple.copy(revision = Revision("v2"), content = simple.content.copy(recurrence = EventRecurrence("series", "FREQ=DAILY;COUNT=3")))
            projection.apply(ProjectionIntent(scope, master))
            assertEquals(id, row(calendar, "transition").id)
            projection.apply(ProjectionIntent(scope, timed("child", "2030-07-02T16:00:00Z", EventRecurrence("series", original = point("2030-07-02T10:00:00Z")))))
            projection.apply(ProjectionIntent(scope, simple.copy(revision = Revision("v3"))))
            val actual = row(calendar, "transition")
            assertEquals(id, actual.id); assertEquals(1, count(calendar))
            assertNull(actual.rule); assertNull(actual.duration); assertNull(actual.parent); assertNull(actual.original)
            assertNotNull(actual.end)
        }
    }

    @Test fun missingMasterAndDuplicateSeriesFailWithoutCreatingRows() = runBlocking {
        fixture { scope, _, calendar, _ ->
            val child = timed("child", "2030-07-02T16:00:00Z", EventRecurrence("series", original = point("2030-07-02T10:00:00Z")))
            try { projection.apply(ProjectionIntent(scope, child)); fail("Missing parent was admitted") }
            catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.IDENTITY_CONFLICT, failure.category) }
            assertEquals(0, count(calendar))
            val master = timed("master", "2030-07-01T10:00:00Z", EventRecurrence("series", "FREQ=DAILY;COUNT=3"))
            projection.apply(ProjectionIntent(scope, master))
            val id = row(calendar, "master").id
            try { projection.apply(ProjectionIntent(scope, master.copy(eventId = EventId("duplicate")))); fail("Duplicate series was admitted") }
            catch (failure: CalendarProjectionException) { assertEquals(CalendarProjectionFailure.IDENTITY_CONFLICT, failure.category) }
            assertEquals(1, count(calendar)); assertEquals(id, row(calendar, "master").id)
            projection.apply(ProjectionIntent(scope, child))
            assertEquals(2, count(calendar))
        }
    }

    private fun timed(id: String, start: String, recurrence: EventRecurrence?, zone: String = "UTC") =
        RemoteChange.Upsert(EventId(id), Revision("v1"), EventContent(id,
            EventTime.Timed(Instant.parse(start), Instant.parse(start).plusSeconds(3600), ZoneId.of(zone)), recurrence = recurrence))
    private fun point(value: String) = RecurrencePoint.Timed(Instant.parse(value))
    private fun july(calendar: Long) = instances(calendar, "2030-07-01T00:00:00Z", "2030-07-05T00:00:00Z")
    private data class Instance(val event: Long, val start: Instant, val end: Instant, val allDay: Boolean)
    private fun instances(calendar: Long, start: String, end: String): List<Instance> {
        val builder = Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, Instant.parse(start).toEpochMilli())
        ContentUris.appendId(builder, Instant.parse(end).toEpochMilli())
        return resolver.query(builder.build(), arrayOf(Instances.EVENT_ID, Instances.BEGIN, Instances.END, Events.ALL_DAY),
            "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), "${Instances.BEGIN} ASC")!!.use {
            buildList { while (it.moveToNext()) add(Instance(it.getLong(0), Instant.ofEpochMilli(it.getLong(1)), Instant.ofEpochMilli(it.getLong(2)), it.getInt(3) == 1)) }
        }
    }
    private data class Row(val id: Long, val end: Long?, val duration: String?, val rule: String?, val parent: Long?, val original: Long?)
    private fun row(calendar: Long, title: String): Row = resolver.query(Events.CONTENT_URI,
        arrayOf(Events._ID, Events.DTEND, Events.DURATION, Events.RRULE, Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME),
        "${Events.CALENDAR_ID}=? AND ${Events.TITLE}=?", arrayOf(calendar.toString(), title), null)!!.use {
        assertTrue(it.moveToFirst()); assertEquals(1, it.count)
        Row(it.getLong(0), if (it.isNull(1)) null else it.getLong(1), it.getString(2), it.getString(3),
            if (it.isNull(4)) null else it.getLong(4), if (it.isNull(5)) null else it.getLong(5))
    }
    private fun count(calendar: Long) = resolver.query(Events.CONTENT_URI, arrayOf(Events._ID),
        "${Events.CALENDAR_ID}=?", arrayOf(calendar.toString()), null)!!.use { it.count }
    private suspend fun fixture(block: suspend (CalendarScope, CalendarScope, Long, Long) -> Unit) {
        val account = AccountId("recurrence-fixture-" + UUID.randomUUID())
        val first = CalendarScope(account, CalendarId("first"), 0)
        val second = CalendarScope(account, CalendarId("second"), 0)
        try {
            val firstId = projection.ensureCalendar(first, CalendarSummary(first.calendar, "Recurrence fixture", 0x167D8D))
            val secondId = projection.ensureCalendar(second, CalendarSummary(second.calendar, "Recurrence fixture second", 0x167D8D))
            block(first, second, firstId, secondId)
        } finally {
            projection.removeOwnedCalendar(first); projection.removeOwnedCalendar(second)
            val manager = AccountManager.get(context)
            val fixtureAccount = projection.accountFor(account)
            if (manager.getAccountsByType(AndroidCalendarProjection.ACCOUNT_TYPE).any { it == fixtureAccount }) {
                assertTrue(manager.removeAccountExplicitly(fixtureAccount))
            }
        }
    }
}
