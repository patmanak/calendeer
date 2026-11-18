package com.patmanak.proton.andcalendarbridge.protonauth

import biweekly.Biweekly
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class ProtonGuestAttachmentTest {
    private val owner = EventPerson("owner@example.test")
    private val guest = EventAttendee(EventPerson("new+guest@example.test"), AttendanceRole.OPTIONAL,
        AttendanceStatus.NEEDS_ACTION)
    private val old = EventAttendee(EventPerson("old@example.test", "Private nickname"),
        AttendanceRole.REQUIRED, AttendanceStatus.ACCEPTED)
    private val initial = EventDetails(organizer = owner, attendees = listOf(old))
    private val clock = Instant.parse("2026-11-18T09:10:11Z")
    private val key = ByteArray(32) { (it + 1).toByte() }
    private val original = """
        BEGIN:VCALENDAR
        VERSION:2.0
        PRODID:-//Fixture//EN
        BEGIN:VEVENT
        UID:fixture-uid
        DTSTAMP:20261101T010203Z
        SEQUENCE:4
        DTSTART:20261120T120000Z
        DTEND:20261120T130000Z
        SUMMARY:Réunion
        STATUS:CONFIRMED
        ORGANIZER:mailto:owner@example.test
        ATTENDEE;CN=Private nickname;PARTSTAT=ACCEPTED:mailto:old@example.test
        BEGIN:VALARM
        ACTION:DISPLAY
        DESCRIPTION:Private alarm
        TRIGGER:-PT10M
        END:VALARM
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n") + "\r\n"

    private fun prepare(source: String = original, edited: EventDetails = initial.copy(attendees = listOf(old, guest)),
        secret: ByteArray? = key, uid: String = "fixture-uid", shared: String = "shared-fixture") =
        prepareGuestAttachment(source, uid, owner, initial, edited, shared, secret, clock,
            mapOf(guest.person.email to "canonical@example.test"))

    @Test fun requestUsesEditedGuestsAndSealedMailInputOnly() {
        val attachment = requireNotNull(prepare())
        assertEquals("text/calendar; method=REQUEST", attachment.contentType)
        assertEquals("invite.ics", attachment.fileName)
        assertEquals(guest.person.email, attachment.recipient)
        assertFalse(attachment.toString().contains(guest.person.email))
        val contents = requireNotNull(attachment.bytesForEncryption()).toString(Charsets.UTF_8)
        assertFalse(contents.contains("Private"))
        assertFalse(contents.contains("VALARM"))
        val calendar = Biweekly.parse(contents).first()
        val event = calendar.events.single()
        assertEquals("REQUEST", calendar.method.value)
        assertEquals("fixture-uid", event.uid.value)
        assertEquals("Réunion", event.summary.value)
        assertEquals(4, event.sequence.value)
        assertEquals(clock, event.dateTimeStamp.value.toInstant())
        assertEquals(setOf(old.person.email, guest.person.email), event.attendees.map { it.commonName }.toSet())
        val added = event.attendees.single { it.commonName == guest.person.email }
        assertEquals(true, added.rsvp)
        assertEquals("OPT-PARTICIPANT", added.participationLevel.getValue(biweekly.ICalVersion.V2_0))
        assertEquals(java.security.MessageDigest.getInstance("SHA-1")
            .digest("fixture-uidcanonical@example.test".toByteArray()).joinToString("") { "%02x".format(it) },
            added.getParameter("X-PM-TOKEN"))
        assertEquals(Base64.getEncoder().encodeToString(key), event.getExperimentalProperty("X-PM-SESSION-KEY").value)
        assertEquals("shared-fixture", event.getExperimentalProperty("X-PM-SHARED-EVENT-ID").value)
        attachment.close()
        assertNull(attachment.bytesForEncryption())
    }

    @Test fun cancellationUsesOriginalGuestAndOmitsEventStatusAndKey() {
        val allDay = original.replace("DTSTART:20261120T120000Z", "DTSTART;VALUE=DATE:20261120")
            .replace("DTEND:20261120T130000Z", "DTEND;VALUE=DATE:20261121")
        val attachment = requireNotNull(prepare(allDay, initial.copy(attendees = emptyList()), null))
        attachment.use {
            assertEquals("text/calendar; method=CANCEL", it.contentType)
            assertEquals(old.person.email, it.recipient)
            val calendar = Biweekly.parse(requireNotNull(it.bytesForEncryption()).toString(Charsets.UTF_8)).first()
            val event = calendar.events.single()
            assertEquals("CANCEL", calendar.method.value)
            assertEquals(old.person.email, event.attendees.single().commonName)
            assertFalse(event.dateStart.value.hasTime())
            assertNull(event.getExperimentalProperty("X-PM-SESSION-KEY"))
            assertNull(event.status)
        }
    }

    @Test fun unsupportedOrMismatchedSourcesProduceNoAttachment() {
        for (extra in listOf("RRULE:FREQ=DAILY", "X-PM-SESSION-KEY:stale", "ATTACH:https://example.test",
            "UID:second", "ORGANIZER:mailto:other@example.test", "RECURRENCE-ID:20261120T120000Z")) {
            assertNull(prepare(original.replace("SEQUENCE:4", "SEQUENCE:4\r\n" + extra)))
        }
        assertNull(prepare(original.replace("PARTSTAT=ACCEPTED", "PARTSTAT=DECLINED")))
        assertNull(prepare(original.replace("CN=Private nickname", "SENT-BY=someone")))
        assertNull(prepare(original.replace("DTEND:20261120T130000Z", "DTEND:20261119T130000Z")))
        assertNull(prepare(original.replace("DTSTART:", "DTSTART;TZID=Invalid:")))
        assertNull(prepare(edited = initial))
        assertNull(prepare(secret = null))
        assertNull(prepare(secret = ByteArray(8)))
        assertNull(prepare(edited = initial.copy(attendees = emptyList())))
        assertNull(prepare(shared = "shared\r\nINJECT:bad"))
        assertNull(prepare(uid = "other"))
        for (canonical in listOf(emptyMap(), mapOf(guest.person.email to owner.email),
            mapOf(guest.person.email to old.person.email))) {
            assertNull(prepareGuestAttachment(original, "fixture-uid", owner, initial,
                initial.copy(attendees = listOf(old, guest)), "shared-fixture", key, clock, canonical))
        }
    }
}
