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

    @Test fun simpleOrganizerAndParticipantImportsCanHaveHistoricalApplicationReceipts() {
        val organizer = EventPerson("organizer@example.test", "Fixture organizer")
        val guest = EventAttendee(EventPerson("guest@example.test", "Fixture guest"),
            AttendanceRole.OPTIONAL, AttendanceStatus.TENTATIVE)
        for (details in listOf(EventDetails(organizer = organizer), EventDetails(attendees = listOf(guest)),
            EventDetails(listOf(EventReminder(15)), organizer, listOf(guest)))) {
            val next = base.copy(content = base.content.copy(details = details))
            assertTrue(ProjectionReceipt.supports(next))
            assertEquals(next, ProjectionReceipt(scope, format, next, nonce, base).snapshot().change)
        }
    }

    @Test fun movedAndCancelledOccurrenceImportsCanHaveHistoricalApplicationReceipts() {
        val dates = base.content.time as EventTime.AllDay
        for (cancelled in listOf(false, true)) {
            val next = base.copy(content = base.content.copy(
                time = dates.copy(start = dates.start.plusDays(1), endExclusive = dates.endExclusive.plusDays(1)),
                recurrence = EventRecurrence("fixture-series", original = RecurrencePoint.AllDay(dates.start), cancelled = cancelled)))
            assertTrue(ProjectionReceipt.supports(next))
            assertEquals(next, ProjectionReceipt(scope, format, next, nonce, base).snapshot().change)
        }
    }

    @Test fun incompatibleProtocolForeignBaselineAndMalformedNonceAreRefused() {
        refused { ProjectionReceipt(scope, format, base, nonce, null, protocol = 2) }
        refused { ProjectionReceipt(scope, format, base, "unversioned-proof", null) }
        refused { ProjectionReceipt(scope, format, base, nonce, base.copy(eventId = EventId("foreign"))) }
    }

    @Test fun recurringMastersCanHaveHistoricalApplicationReceipts() {
        val date = (base.content.time as EventTime.AllDay).start
        for (rule in listOf("FREQ=DAILY;COUNT=3", null)) {
            val recurring = base.copy(content = base.content.copy(recurrence = EventRecurrence("fixture-uid", rule,
                listOf(RecurrencePoint.AllDay(date.plusDays(4))), listOf(RecurrencePoint.AllDay(date.plusDays(1))))))
            assertTrue(ProjectionReceipt.supports(recurring))
            assertEquals(recurring, ProjectionReceipt(scope, format, recurring, nonce, base).snapshot().change)
        }
    }

    @Test fun deletionCannotBecomeARecoverableEventWrite() {
        assertFalse(ProjectionReceipt.supports(RemoteChange.Deleted(base.eventId, Revision("gone"))))
    }

    @Test fun masterDateListsAreRevalidatedAndFrozenBeforeCrossingBoundaries() {
        val point = RecurrencePoint.AllDay(LocalDate.of(2030, 1, 3))
        val additions = mutableListOf<RecurrencePoint>(point)
        val exclusions = mutableListOf<RecurrencePoint>(point)
        val candidate = base.copy(content = base.content.copy(recurrence = EventRecurrence("fixture-uid",
            additionalDates = additions, excludedDates = exclusions)))
        val receipt = ProjectionReceipt(scope, format, candidate, nonce, base)
        val frozen = receipt.snapshot()
        additions.clear()
        assertFalse(ProjectionReceipt.supports(candidate)); refused { receipt.snapshot() }
        assertEquals(listOf(point), frozen.change.content.recurrence!!.additionalDates)
        additions.add(RecurrencePoint.Timed(Instant.parse("2030-01-03T00:00:00Z")))
        assertFalse(ProjectionReceipt.supports(candidate)); refused { receipt.snapshot() }
        additions.clear(); additions.add(point)
        exclusions.add(RecurrencePoint.Timed(Instant.parse("2030-01-04T00:00:00Z")))
        assertFalse(ProjectionReceipt.supports(candidate)); refused { receipt.snapshot() }
        exclusions.clear(); exclusions.addAll(List(513) { point })
        assertFalse(ProjectionReceipt.supports(candidate)); refused { receipt.snapshot() }
        exclusions.clear(); exclusions.add(point)
        additions.addAll(List(512) { point })
        assertFalse(ProjectionReceipt.supports(candidate)); refused { receipt.snapshot() }
        try { (frozen.change.content.recurrence!!.excludedDates as MutableList).clear(); fail("Snapshot must be immutable") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun timedMasterListsRetainWholeSecondPrecisionAfterAliasedMutation() {
        val start = Instant.parse("2030-10-27T00:30:00Z")
        val additions = mutableListOf<RecurrencePoint>(RecurrencePoint.Timed(start.plusSeconds(86400)))
        val candidate = base.copy(content = base.content.copy(time = EventTime.Timed(start, start.plusSeconds(3600), ZoneId.of("Europe/Paris")),
            recurrence = EventRecurrence("fixture-uid", "FREQ=DAILY;COUNT=3", additionalDates = additions)))
        val receipt = ProjectionReceipt(scope, format, candidate, nonce, null)
        assertTrue(ProjectionReceipt.supports(candidate))
        additions.add(RecurrencePoint.Timed(start.plusNanos(1)))
        assertFalse(ProjectionReceipt.supports(candidate)); refused { receipt.snapshot() }
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

    @Test fun participantSnapshotOwnsTheAppliedListAndRetainsTheOrganizerExactlyOnce() {
        val organizer = EventPerson("organizer@example.test", "Fixture organizer")
        val people = mutableListOf(EventAttendee(organizer, AttendanceRole.REQUIRED, AttendanceStatus.ACCEPTED))
        val next = base.copy(content = base.content.copy(details = EventDetails(organizer = organizer, attendees = people)))
        val frozen = ProjectionReceipt(scope, format, next, nonce, base).snapshot()
        people.clear()
        assertEquals(organizer, frozen.change.content.details!!.organizer)
        assertEquals(listOf(EventAttendee(organizer, AttendanceRole.REQUIRED, AttendanceStatus.ACCEPTED)),
            frozen.change.content.details!!.attendees)
        try { (frozen.change.content.details!!.attendees as MutableList).clear(); fail("Snapshot must be immutable") }
        catch (_: UnsupportedOperationException) { }
    }

    @Test fun mutatedParticipantListsAreBoundedAndCaseInsensitiveDuplicatesAreRefused() {
        fun guest(index: Int) = EventAttendee(EventPerson("guest$index@example.test"),
            AttendanceRole.NONE, AttendanceStatus.UNKNOWN)
        val people = mutableListOf(guest(0))
        val next = base.copy(content = base.content.copy(details = EventDetails(attendees = people)))
        val receipt = ProjectionReceipt(scope, format, next, nonce, null)
        people.add(guest(0).copy(person = EventPerson("GUEST0@EXAMPLE.TEST")))
        assertFalse(ProjectionReceipt.supports(next)); refused { receipt.snapshot() }
        people.clear(); people.addAll((0..100).map(::guest))
        assertFalse(ProjectionReceipt.supports(next)); refused { receipt.snapshot() }
        people.removeAt(100)
        assertTrue(ProjectionReceipt.supports(next)); assertEquals(100, receipt.snapshot().change.content.details!!.attendees.size)
    }

    @Test fun exceptionOriginalDateTypeAndSecondPrecisionMustMatchTheProjectionProfile() {
        val start = Instant.parse("2030-10-27T00:30:00Z")
        val timed = base.copy(content = base.content.copy(time = EventTime.Timed(start, start.plusSeconds(3600), ZoneId.of("Europe/Paris")),
            recurrence = EventRecurrence("fixture-series", original = RecurrencePoint.Timed(start))))
        assertTrue(ProjectionReceipt.supports(timed))
        refused { timed.copy(content = timed.content.copy(recurrence = timed.content.recurrence!!.copy(
            original = RecurrencePoint.Timed(start.plusNanos(1))))) }
        assertFalse(ProjectionReceipt.supports(timed.copy(content = timed.content.copy(recurrence = timed.content.recurrence!!.copy(
            original = RecurrencePoint.AllDay(LocalDate.of(2030, 10, 27)))))))
        assertFalse(ProjectionReceipt.supports(base.copy(content = base.content.copy(recurrence = timed.content.recurrence))))
    }

    @Test fun exceptionListsMutatedAfterConstructionCannotTurnIntoPartialTrackedWrites() {
        val dates = mutableListOf<RecurrencePoint>()
        val recurrence = EventRecurrence("fixture-series", additionalDates = dates,
            original = RecurrencePoint.AllDay(LocalDate.of(2030, 1, 1)))
        val next = base.copy(content = base.content.copy(recurrence = recurrence))
        val receipt = ProjectionReceipt(scope, format, next, nonce, null)
        dates.add(RecurrencePoint.AllDay(LocalDate.of(2030, 1, 2)))
        assertFalse(ProjectionReceipt.supports(next)); refused { receipt.snapshot() }
        dates.clear()
        val exclusions = mutableListOf<RecurrencePoint>()
        val excluded = next.copy(content = next.content.copy(recurrence = recurrence.copy(excludedDates = exclusions)))
        val second = ProjectionReceipt(scope, format, excluded, nonce, null)
        exclusions.add(RecurrencePoint.AllDay(LocalDate.of(2030, 1, 2)))
        assertFalse(ProjectionReceipt.supports(excluded)); refused { second.snapshot() }
    }
}
