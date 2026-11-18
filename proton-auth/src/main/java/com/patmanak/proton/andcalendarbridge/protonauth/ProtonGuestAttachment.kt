// SPDX-License-Identifier: GPL-3.0-only
// iTIP transforms based on Proton Calendar's getBaseIcs/getInviteIcs/getCancelIcs; see GUEST_WRITES.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import biweekly.Biweekly
import biweekly.io.ParseWarning
import biweekly.parameter.ParticipationLevel
import biweekly.parameter.ParticipationStatus
import biweekly.property.Attendee
import biweekly.property.CalendarScale
import biweekly.property.Method
import com.patmanak.proton.andcalendarbridge.sync.*
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Date
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Plaintext input to the Mail package encryptor. The caller owns and wipes any copy it requests. */
internal class ProtonGuestAttachment internal constructor(
    val organizerEmail: String,
    val recipient: String,
    val contentType: String,
    private var content: ByteArray?,
) : AutoCloseable {
    val fileName = "invite.ics"
    @Synchronized fun bytesForEncryption(): ByteArray? = content?.copyOf()
    @Synchronized override fun close() {
        content?.fill(0)
        content = null
    }
    override fun toString() = "ProtonGuestAttachment(redacted)"
}

/** Input must be the complete authenticated organizer event, matched by account, event and revision. */
internal fun prepareGuestAttachment(
    sourceCalendar: String,
    expectedUid: String,
    organizer: EventPerson,
    base: EventDetails,
    edited: EventDetails,
    sharedId: String,
    sharedKey: ByteArray?,
    timestamp: Instant,
    canonicalRecipients: Map<String, String>,
): ProtonGuestAttachment? {
    val change = guestRecipientChange(organizer, base, edited) ?: return null
    if (!sharedId.matches(Regex("[A-Za-z0-9_+/=-]{1,512}")) ||
        timestamp.epochSecond !in 0..253402300799L || timestamp.nano != 0) return null
    if (change is GuestRecipientChange.Request && sharedKey?.size != 32 ||
        change is GuestRecipientChange.Cancel && sharedKey != null) return null
    return try {
        val bounded = guestCalendarWithoutAlarms(sourceCalendar) ?: return null
        val warnings = mutableListOf<List<ParseWarning>>()
        val calendar = Biweekly.parse(bounded).warnings(warnings).all().single()
        if (warnings.flatten().isNotEmpty()) return null
        val event = calendar.events.single()
        if (event.uid.value != expectedUid || event.dateTimeStamp.value.toInstant() > timestamp ||
            event.sequence.value < 0) return null
        val details = ProtonEventDetails.parse(event, JsonObject(mapOf("Notifications" to JsonArray(emptyList()))),
            null, !event.dateStart.value.hasTime())
        if (details.organizer != organizer || details.attendees.toSet() != base.attendees.toSet()) return null
        if (event.dateStart.value == event.dateEnd.value) event.dateEnd = null
        if (calendar.calendarScale == null) calendar.calendarScale = CalendarScale.gregorian()
        val recipient = when (change) {
            is GuestRecipientChange.Request -> {
                val guest = change.guest
                if ('%' in guest.person.email) return null
                // The verified recipient lookup maps the exact addressed mailbox to Proton's canonical one.
                val canonical = canonicalRecipients[guest.person.email] ?: return null
                if (canonical.length !in 3..254 || canonical.count { it == '@' } != 1 ||
                    canonical.first() == '@' || canonical.last() == '@' ||
                    canonical.any { it.code !in 33..126 || it in "%?#\"<>,;:\\" }) return null
                if (canonical.equals(organizer.email, ignoreCase = true) ||
                    base.attendees.any { it.person.email.equals(canonical, ignoreCase = true) }) return null
                // Proton's participant identifier is SHA-1(UID + canonical email), unrelated to key security.
                val token = MessageDigest.getInstance("SHA-1").digest((expectedUid + canonical).toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
                if (event.attendees.any { it.getParameter("X-PM-TOKEN").equals(token, ignoreCase = true) }) return null
                event.addAttendee(Attendee(guest.person.email, guest.person.email).apply {
                    rsvp = true
                    addParameter("X-PM-TOKEN", token)
                    participationLevel = if (guest.role == AttendanceRole.OPTIONAL)
                        ParticipationLevel.OPTIONAL else ParticipationLevel.REQUIRED
                    participationStatus = ParticipationStatus.NEEDS_ACTION
                })
                calendar.setMethod(Method.REQUEST)
                event.setExperimentalProperty("X-PM-SESSION-KEY", Base64.getEncoder().encodeToString(sharedKey))
                guest.person.email
            }
            is GuestRecipientChange.Cancel -> {
                calendar.setMethod(Method.CANCEL)
                event.status = null
                change.guest.person.email
            }
        }
        event.attendees.forEach { it.commonName = it.email ?: return null }
        event.setExperimentalProperty("X-PM-SHARED-EVENT-ID", sharedId)
        event.setDateTimeStamp(Date.from(timestamp))
        val bytes = Biweekly.write(calendar).go().toByteArray(Charsets.UTF_8)
        ProtonGuestAttachment(organizer.email, recipient, "text/calendar; method=" + calendar.method.value, bytes)
    } catch (_: Exception) {
        // Parser failures may contain the source text. Expose only unsupported.
        null
    }
}
