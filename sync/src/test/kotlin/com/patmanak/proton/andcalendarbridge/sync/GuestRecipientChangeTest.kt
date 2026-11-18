package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestRecipientChangeTest {
    private val organizer = EventPerson("owner@example.test")
    private val held = attendee("held@example.test").copy(status = AttendanceStatus.ACCEPTED)
    private val guest = attendee("new+tag@example.test")
    private val base = EventDetails(organizer = organizer, attendees = listOf(held))

    @Test fun addAndRemoveTargetOnlyTheChangedPerson() {
        val edited = base.copy(attendees = listOf(guest, held))
        assertEquals(GuestRecipientChange.Request(guest), guestRecipientChange(organizer, base, edited))
        assertEquals(GuestRecipientChange.Cancel(guest), guestRecipientChange(organizer, edited, base))
        assertEquals(GuestRecipientChange.Cancel(held),
            guestRecipientChange(organizer, base, base.copy(attendees = emptyList())))
    }

    @Test fun organizerAndRetainedParticipantsMustBeUnchanged() {
        val edited = base.copy(attendees = listOf(held, guest))
        assertNull(guestRecipientChange(guest.person, base, edited))
        assertNull(guestRecipientChange(organizer, base, edited.copy(organizer = guest.person)))
        assertNull(guestRecipientChange(organizer, base.copy(organizer = null), edited))
        for (changed in listOf(held.copy(role = AttendanceRole.OPTIONAL),
            held.copy(status = AttendanceStatus.TENTATIVE), held.copy(person = held.person.copy(name = "Renamed")),
            held.copy(person = EventPerson("HELD@example.test")))) {
            assertNull(guestRecipientChange(organizer, base, edited.copy(attendees = listOf(changed, guest))))
        }
    }

    @Test fun ambiguousReplacementsBulkEditsAndUnsupportedRecipientsAreHeld() {
        assertNull(guestRecipientChange(organizer, base, base))
        assertNull(guestRecipientChange(organizer, base, base.copy(attendees = listOf(guest))))
        assertNull(guestRecipientChange(organizer, base,
            base.copy(attendees = listOf(held, guest, attendee("third@example.test")))))
        for (unsupported in listOf(guest.copy(role = AttendanceRole.RESOURCE),
            guest.copy(status = AttendanceStatus.ACCEPTED), attendee("OWNER@example.test"),
            attendee("bad@@example.test"), attendee("first,last@example.test"),
            attendee("bad..dots@example.test"))) {
            assertNull(guestRecipientChange(organizer, base, base.copy(attendees = listOf(held, unsupported))))
        }
    }

    @Test fun duplicatedMutableRecipientsDoNotAuthorizeDelivery() {
        val recipients = mutableListOf(held, guest)
        val edited = base.copy(attendees = recipients)
        recipients += guest
        assertNull(guestRecipientChange(organizer, base, edited))
        recipients[2] = guest.copy(person = EventPerson("NEW+tag@example.test"))
        assertNull(guestRecipientChange(organizer, base, edited))
    }

    private fun attendee(email: String) =
        EventAttendee(EventPerson(email), AttendanceRole.REQUIRED, AttendanceStatus.NEEDS_ACTION)
}
