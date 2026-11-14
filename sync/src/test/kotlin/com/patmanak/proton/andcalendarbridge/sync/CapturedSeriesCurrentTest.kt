// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CapturedSeriesCurrentTest {
    private val scope = CalendarScope(AccountId("fixture"), CalendarId("calendar"), 0)
    private val local = EventContent("Synthetic series", EventTime.AllDay(LocalDate.parse("2030-03-29"),
        LocalDate.parse("2030-03-31")), details = EventDetails(listOf(EventReminder(10))), colorRgb = 0x123456)
    private val rule = CreateSeriesRule(5)
    private val intent = LocalCreateIntent(LocalCreateReservation(MutationId("capture"), LocalEventId(UUID(0, 1).toString()),
        scope, AdapterFormat("capture", 1), Instant.parse("2030-01-01T00:00:00Z")), local, rule)
    private val record = CreateAttemptRecord(MutationId(UUID(0, 2).toString()), scope, AdapterFormat("submission", 4), "synthetic-opaque-payload")
    private val content = local.copy(recurrence = EventRecurrence("synthetic@calendeer.invalid", rule.rule))
    private val change = RemoteChange.Upsert(EventId("event"), Revision("verified"), content)
    private val baseline = CapturedCreateBaseline(CapturedCreateAttachmentReceipt(CapturedCreateBinding(
        CapturedCreateDispatchIdentity.of(record, intent), change.eventId, change.revision), UUID(0, 3).toString()), change)

    @Test fun exactSeriesIsCurrentWhileLaterEditsAndTombstonesAreNeverUnchanged() {
        val current = CapturedCreateCurrent(baseline, content, false)
        assertTrue(current.unchanged)
        for (later in listOf(content.copy(title = "Later title"), content.copy(recurrence = content.recurrence!!.copy(rule = "FREQ=DAILY;COUNT=4")),
            content.copy(colorRgb = null), content.copy(details = EventDetails()))) {
            assertEquals(later, current.copy(content = later).content)
            assertFalse(current.copy(content = later).unchanged)
        }
        assertTrue(current.copy(deleted = true).deleted); assertFalse(current.copy(deleted = true).unchanged)
        assertThrows(IllegalArgumentException::class.java) { CapturedCreateHandoffReceipt(
            CapturedCreateAcknowledgementReceipt(baseline, UUID(0, 4).toString()), UUID(0, 5).toString()) }
    }
    @Test fun missingForeignOrUnsupportedRecurrenceCannotBecomeCurrentSeriesContent() {
        for (later in listOf(local, content.copy(recurrence = content.recurrence!!.copy(uid = "foreign@calendeer.invalid")),
            content.copy(recurrence = content.recurrence!!.copy(rule = "FREQ=WEEKLY;COUNT=5")),
            content.copy(recurrence = content.recurrence!!.copy(excludedDates = listOf(RecurrencePoint.AllDay(LocalDate.parse("2030-03-30")))))))
            assertThrows(IllegalArgumentException::class.java) { CapturedCreateCurrent(baseline, later, false) }
    }
    @Test fun mutableMetadataAndEmptyDateListsAreOwnedByTheSnapshot() {
        val reminders = mutableListOf(EventReminder(10)); val dates = mutableListOf<RecurrencePoint>()
        val current = CapturedCreateCurrent(baseline, content.copy(details = EventDetails(reminders),
            recurrence = content.recurrence!!.copy(excludedDates = dates)), false)
        reminders.clear(); dates += RecurrencePoint.AllDay(LocalDate.parse("2030-03-30"))
        assertEquals(content, current.content); assertTrue(current.unchanged)
    }
}
