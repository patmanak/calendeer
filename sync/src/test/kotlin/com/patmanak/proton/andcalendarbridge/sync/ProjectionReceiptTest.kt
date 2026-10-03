// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ProjectionReceiptTest {
    private val scope = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 0)
    private val format = AdapterFormat("fixture-adapter", 1)
    private val nonce = "12345678-1234-1234-1234-123456789abc"
    private val base = RemoteChange.Upsert(EventId("fixture-event"), Revision("opaque-one"), EventContent(
        "Fixture event", EventTime.AllDay(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 2)), details = EventDetails()))
    private fun refused(block: () -> Unit) {
        try { block(); fail("Unsupported receipt must be refused") } catch (_: IllegalArgumentException) { }
    }

    @Test fun preparationPreservesTheExactPreviousBaseAndNeverExposesItsNonce() {
        val next = base.copy(revision = Revision("opaque-two"))
        val receipt = ProjectionReceipt(scope, format, next, nonce, base)
        assertEquals(base, receipt.previousBase); assertEquals(next, receipt.change)
        assertEquals("ProjectionReceipt(redacted)", receipt.toString())
        assertEquals("AppliedProjectionWitness(redacted)", AppliedProjectionWitness(receipt).toString())
    }

    @Test fun simpleReminderOnlyImportsCanHaveHistoricalApplicationReceipts() {
        val reminders = base.copy(content = base.content.copy(details = EventDetails(reminders = listOf(EventReminder(10), EventReminder(30)))))
        assertTrue(ProjectionReceipt.supports(reminders))
        assertEquals(reminders, ProjectionReceipt(scope, format, reminders, nonce, base).change)
    }

    @Test fun incompatibleProtocolForeignBaselineAndMalformedNonceAreRefused() {
        refused { ProjectionReceipt(scope, format, base, nonce, null, protocol = 2) }
        refused { ProjectionReceipt(scope, format, base, "unversioned-proof", null) }
        refused { ProjectionReceipt(scope, format, base, nonce, base.copy(eventId = EventId("foreign"))) }
    }

    @Test fun unsupportedEventDataCannotBecomeARecoverableSimpleWrite() {
        val recurring = base.copy(content = base.content.copy(recurrence = EventRecurrence("fixture-uid", "FREQ=DAILY")))
        val organized = base.copy(content = base.content.copy(details = EventDetails(
            reminders = listOf(EventReminder(10)), organizer = EventPerson("organizer@example.test"))))
        val attended = base.copy(content = base.content.copy(details = EventDetails(
            attendees = listOf(EventAttendee(EventPerson("attendee@example.test"), AttendanceRole.REQUIRED,
                AttendanceStatus.NEEDS_ACTION)))))
        assertFalse(ProjectionReceipt.supports(recurring)); assertFalse(ProjectionReceipt.supports(organized))
        assertFalse(ProjectionReceipt.supports(attended))
        refused { ProjectionReceipt(scope, format, recurring, nonce, null) }
        refused { ProjectionReceipt(scope, format, organized, nonce, null) }
        assertFalse(ProjectionReceipt.supports(RemoteChange.Deleted(base.eventId, Revision("gone"))))
    }

    @Test fun secondPrecisionIsRequiredAndUnknownDetailsRemainUnknown() {
        val timed = base.copy(content = base.content.copy(time = EventTime.Timed(
            Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris")), details = null))
        assertTrue(ProjectionReceipt.supports(timed))
        assertNull(ProjectionReceipt(scope, format, timed, nonce, null).change.content.details)
        val time = timed.content.time as EventTime.Timed
        assertFalse(ProjectionReceipt.supports(timed.copy(content = timed.content.copy(time = time.copy(start = time.start.plusNanos(1))))))
    }

    @Test fun reminderReceiptSnapshotOwnsBothAppliedAndPreviousCollectionData() {
        val reminders = mutableListOf(EventReminder(10), EventReminder(30))
        val previousReminders = mutableListOf(EventReminder(60))
        val previousAttendees = mutableListOf(EventAttendee(EventPerson("attendee@example.test"),
            AttendanceRole.REQUIRED, AttendanceStatus.NEEDS_ACTION))
        val additions = mutableListOf<RecurrencePoint>(RecurrencePoint.AllDay(LocalDate.of(2030, 1, 2)))
        val exclusions = mutableListOf<RecurrencePoint>(RecurrencePoint.AllDay(LocalDate.of(2030, 1, 3)))
        val previous = base.copy(content = base.content.copy(
            details = EventDetails(previousReminders, attendees = previousAttendees),
            recurrence = EventRecurrence("fixture-uid", "FREQ=DAILY", additions, exclusions)))
        val next = base.copy(content = base.content.copy(details = EventDetails(reminders)))
        val frozen = ProjectionReceipt(scope, format, next, nonce, previous).snapshot()
        reminders.clear(); previousReminders.clear(); previousAttendees.clear(); additions.clear(); exclusions.clear()
        assertEquals(listOf(EventReminder(10), EventReminder(30)), frozen.change.content.details!!.reminders)
        assertEquals(listOf(EventReminder(60)), frozen.previousBase!!.content.details!!.reminders)
        assertEquals(1, frozen.previousBase.content.details!!.attendees.size)
        assertEquals(listOf(RecurrencePoint.AllDay(LocalDate.of(2030, 1, 2))), frozen.previousBase.content.recurrence!!.additionalDates)
        assertEquals(listOf(RecurrencePoint.AllDay(LocalDate.of(2030, 1, 3))), frozen.previousBase.content.recurrence!!.excludedDates)
        try { (frozen.change.content.details!!.reminders as MutableList).clear(); fail("Snapshot must be immutable") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun mutatedReminderListsAreRevalidatedBeforeCreatingOrSnapshottingAReceipt() {
        val reminders = mutableListOf(EventReminder(10))
        val next = base.copy(content = base.content.copy(details = EventDetails(reminders)))
        val receipt = ProjectionReceipt(scope, format, next, nonce, null)
        reminders.add(EventReminder(10))
        assertFalse(ProjectionReceipt.supports(next)); refused { receipt.snapshot() }
        reminders.clear(); reminders.addAll((0..5).map { EventReminder(it) })
        assertFalse(ProjectionReceipt.supports(next)); refused { receipt.snapshot() }
        reminders.removeAt(5)
        assertTrue(ProjectionReceipt.supports(next)); assertEquals(5, receipt.snapshot().change.content.details!!.reminders.size)
    }
}
