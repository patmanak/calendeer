// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class RichCapturedCreateTest {
    private val reservation = LocalCreateReservation(MutationId("capture"), LocalEventId(UUID(0, 1).toString()),
        CalendarScope(AccountId("fixture"), CalendarId("calendar"), 0), AdapterFormat("capture", 1),
        Instant.parse("2030-10-01T00:00:00Z"))
    private val empty = EventContent("Fixture", EventTime.AllDay(LocalDate.parse("2030-10-01"),
        LocalDate.parse("2030-10-02")), details = EventDetails())
    private fun identity(content: EventContent) = CapturedCreateDispatchIdentity.of(LocalCreateSubmission(
        LocalCreateSubmissionReservation(LocalCreateIntent(reservation, content), MutationId(UUID(0, 2).toString()),
            AdapterFormat("submission", 3)), "protected-fixture"))
    private fun baseline(content: EventContent): CapturedCreateBaseline {
        val change = RemoteChange.Upsert(EventId("remote"), Revision("sent"), content)
        return CapturedCreateBaseline(CapturedCreateAttachmentReceipt(CapturedCreateBinding(identity(content),
            change.eventId, change.revision), UUID(0, 3).toString()), change)
    }

    @Test fun personalCaptureOwnsConstructorAndCopyLists() {
        val reminders = mutableListOf(EventReminder(10))
        val copiedReminders = mutableListOf(EventReminder(20))
        val rich = empty.copy(details = EventDetails(reminders), colorRgb = 0x123456)
        val intent = LocalCreateIntent(reservation, rich)
        val copied = intent.copy(content = rich.copy(details = EventDetails(copiedReminders)))
        val hash = intent.hashCode()
        reminders.clear(); copiedReminders.clear()
        assertEquals(listOf(EventReminder(10)), intent.content.details!!.reminders)
        assertEquals(listOf(EventReminder(20)), copied.content.details!!.reminders)
        assertEquals(hash, intent.hashCode())
        assertEquals(intent, intent.copy())
        val (retainedReservation, retainedContent) = copied
        assertEquals(reservation, retainedReservation)
        assertEquals(copied.content, retainedContent)
        assertThrows(UnsupportedOperationException::class.java) {
            (intent.content.details!!.reminders as MutableList<EventReminder>).clear()
        }
    }

    @Test fun baselineAndCurrentCopiesOwnReplacementListsAndKeepValueSemantics() {
        val originalReminders = mutableListOf(EventReminder(10))
        val rich = empty.copy(details = EventDetails(originalReminders), colorRgb = 0x123456)
        val baseline = baseline(rich)
        val copiedReminders = mutableListOf(EventReminder(10))
        val copied = baseline.copy(change = baseline.change.copy(content = rich.copy(details = EventDetails(copiedReminders))))
        val currentReminders = mutableListOf(EventReminder(20))
        val current = CapturedCreateCurrent(copied, rich, false).copy(content = rich.copy(details = EventDetails(currentReminders)))
        val baselineHash = copied.hashCode()
        val currentHash = current.hashCode()
        originalReminders.clear(); copiedReminders.clear(); currentReminders.clear()
        assertEquals(baseline, copied)
        assertEquals(baselineHash, copied.hashCode())
        assertEquals(currentHash, current.hashCode())
        assertEquals(listOf(EventReminder(10)), copied.change.content.details!!.reminders)
        assertEquals(listOf(EventReminder(20)), current.content.details!!.reminders)
        assertFalse(current.unchanged)
        assertTrue(supportsPersonalCapturedOrigin(copied))
        assertThrows(UnsupportedOperationException::class.java) {
            (copied.change.content.details!!.reminders as MutableList<EventReminder>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (current.content.details!!.reminders as MutableList<EventReminder>).clear()
        }
    }

    @Test fun completePersonalMetadataIsPartOfTheFrozenFingerprint() {
        val rich = empty.copy(details = EventDetails(listOf(EventReminder(10), EventReminder(-10))), colorRgb = 0)
        val captured = identity(rich)
        assertTrue(captured.matchesContent(rich))
        assertEquals(1, captured.fingerprintVersion)
        for (changed in listOf(empty, rich.copy(colorRgb = null), rich.copy(colorRgb = 1),
            rich.copy(details = EventDetails(listOf(EventReminder(10)))),
            rich.copy(details = EventDetails(listOf(EventReminder(-10), EventReminder(10)))))) {
            assertFalse(captured.matchesContent(changed))
            assertNotEquals(captured.contentFingerprint, identity(changed).contentFingerprint)
        }
    }

    @Test fun richOriginRetainsItsMetadataThroughCurrentHandoffAndPersonalAcknowledgement() {
        val reminders = mutableListOf(EventReminder(10))
        val rich = empty.copy(details = EventDetails(reminders), colorRgb = 0x123456)
        val baseline = baseline(rich)
        val current = CapturedCreateCurrent(baseline, rich, false)
        val ack = CapturedCreateAcknowledgementReceipt(baseline, UUID(0, 4).toString())
        val frame = CapturedCreateHandoffFrame(CapturedCreateHandoffReceipt(ack, UUID(0, 5).toString()), baseline.change)
        val edited = rich.copy(title = "Edited")
        val edit = LocalEditIntent(MutationId("edit"), reservation.scope, baseline.format, frame.base, edited)
        val attempt = PersonalUpdateAttemptIdentity(reservation.scope, MutationId("attempt"), edit.id,
            frame.eventId, frame.base.revision, AdapterFormat("request", 2), AdapterFormat("preparation", 2),
            "a".repeat(64), "b".repeat(64))
        val personal = PersonalUpdateAcknowledgementReceipt(attempt, edit, ProjectionReceipt(reservation.scope,
            baseline.format, frame.base.copy(revision = Revision("updated"), content = edited),
            UUID(0, 6).toString(), frame.base), frame)
        reminders.clear()
        assertEquals(listOf(EventReminder(10)), baseline.change.content.details!!.reminders)
        assertEquals(listOf(EventReminder(10)), current.content.details!!.reminders)
        assertTrue(current.unchanged)
        assertEquals(0x123456, personal.handoff!!.receipt.acknowledgement.baseline.change.content.colorRgb)
        assertEquals(listOf(EventReminder(10)), personal.handoff!!.receipt.acknowledgement.baseline.change.content.details!!.reminders)
        assertFalse(supportsCapturedHandoff(baseline.change))
        assertThrows(IllegalArgumentException::class.java) { baseline.copy(change = baseline.change.copy(content = empty)) }
    }

    @Test fun unknownAndInvitationFieldsCannotBecomePersonalCreates() {
        for (invalid in listOf(empty.copy(details = null), empty.copy(details = EventDetails(organizer = EventPerson("a@example.test"))),
            empty.copy(details = EventDetails(attendees = listOf(EventAttendee(EventPerson("b@example.test"),
                AttendanceRole.REQUIRED, AttendanceStatus.NEEDS_ACTION)))))) {
            assertThrows(IllegalArgumentException::class.java) { LocalCreateIntent(reservation, invalid) }
        }
        val reminders = mutableListOf(EventReminder(10))
        val content = empty.copy(details = EventDetails(reminders))
        reminders += EventReminder(10)
        assertThrows(IllegalArgumentException::class.java) { LocalCreateIntent(reservation, content) }
    }
}
