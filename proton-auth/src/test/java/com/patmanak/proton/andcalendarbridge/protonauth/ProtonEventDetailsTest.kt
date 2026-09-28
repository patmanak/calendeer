// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProtonEventDetailsTest {
    @Test fun legacyParserDoesNotPretendDetailsAreKnown() {
        assertNull(ProtonICalendar.parse(listOf(ics())).details)
    }
    @Test fun emptyOverrideSuppressesDefaultsAndSharedAlarms() {
        val alarm = "BEGIN:VALARM\nACTION:DISPLAY\nTRIGGER:-PT30M\nDESCRIPTION:Alarm\nEND:VALARM"
        assertEquals(emptyList<EventReminder>(), parse(alarm, "[]", defaults()).reminders)
    }
    @Test fun missingAndNullNotificationsInheritTheCorrectDateKind() {
        for (value in listOf(null, "null")) {
            assertEquals(listOf(EventReminder(15)), parse(notifications = value, bootstrap = defaults()).reminders)
            assertEquals(listOf(EventReminder(-540)), parse(notifications = value, bootstrap = defaults(), allDay = true).reminders)
        }
    }
    @Test fun explicitNotificationsNeedNoDefaultSettingsAndEmailIsNotDuplicated() {
        assertEquals(listOf(EventReminder(10)), parse(notifications = "[{\"Type\":0,\"Trigger\":\"-PT30M\"},{\"Type\":1,\"Trigger\":\"-PT10M\"}]").reminders)
    }
    @Test fun durationsKeepExactMinutesAndDeduplicate() {
        assertEquals(listOf(EventReminder(10080), EventReminder(0), EventReminder(-120)),
            parse(notifications = "[{\"Type\":1,\"Trigger\":\"-P1W\"},{\"Type\":1,\"Trigger\":\"PT0S\"},{\"Type\":1,\"Trigger\":\"PT2H\"},{\"Type\":1,\"Trigger\":\"-P7D\"}]").reminders)
    }
    @Test fun emailIsNotConstrainedByAndroidReminderSentinelsOrPrecision() {
        assertEquals(emptyList<EventReminder>(), parse(notifications = "[{\"Type\":0,\"Trigger\":\"PT1M\"},{\"Type\":0,\"Trigger\":\"-PT30S\"}]").reminders)
    }
    @Test fun boundedRemindersAndAttendeesAreNeverTruncated() {
        unsupported { parse(notifications = (1..6).joinToString(",", "[", "]") { "{\"Type\":1,\"Trigger\":\"-PT${it}M\"}" }) }
        unsupported { parse((1..101).joinToString("\n") { "ATTENDEE:mailto:qa$it@example.test" }) }
    }
    @Test fun invalidAndUnrepresentableTriggersAreNotRoundedOrDropped() {
        for (trigger in listOf("-PT30S", "PT1M", "-P366D", "P", "PT", "20001001T120000Z", "P1M", "P1WT1H", "-PT999999999999999999999M")) {
            unsupported { parse(notifications = "[{\"Type\":1,\"Trigger\":\"$trigger\"}]") }
        }
        unsupported { parse(notifications = "[{\"Type\":9,\"Trigger\":\"-PT15M\"}]") }
        unsupported { parse(notifications = "[{\"Type\":0,\"Trigger\":\"invalid\"}]") }
    }
    @Test fun missingDefaultsAreUnknownAndCalendarScopeIsChecked() {
        unsupported { parse(notifications = null) }
        unsupported { parse(notifications = "null", bootstrap = JsonObject(defaults() + ("CalendarSettings" to buildJsonObject { put("CalendarID", "other") }))) }
        assertEquals(emptyList<EventReminder>(), parse(notifications = "[]").reminders)
    }
    @Test fun organizerAttendeesAndCurrentApiStatusAreMerged() {
        val extra = "ORGANIZER;CN=Host:mailto:host@example.test\nATTENDEE;CN=Guest;ROLE=OPT-PARTICIPANT;PARTSTAT=NEEDS-ACTION;X-PM-TOKEN=known:mailto:guest@example.test"
        val details = parse(extra, statuses = "{\"Attendees\":[{\"Token\":\"known\",\"Status\":3}],\"MoreAttendees\":0}")
        assertEquals(EventPerson("host@example.test", "Host"), details.organizer)
        assertEquals(listOf(EventAttendee(EventPerson("guest@example.test", "Guest"), AttendanceRole.OPTIONAL, AttendanceStatus.ACCEPTED)), details.attendees)
    }
    @Test fun fallbackTokenUsesProtonCanonicalizationWithoutChangingDisplayedAddress() {
        val email = "Q.A-test+tag@proton.me"
        val token = java.security.MessageDigest.getInstance("SHA-1").digest("syntheticqatest@proton.me".toByteArray()).joinToString("") { "%02x".format(it) }
        val details = parse("ATTENDEE:mailto:$email", statuses = "{\"Attendees\":[{\"Token\":\"$token\",\"Status\":2}],\"MoreAttendees\":0}")
        assertEquals(email, details.attendees.single().person.email)
        assertEquals(AttendanceStatus.DECLINED, details.attendees.single().status)
    }
    @Test fun legacyAttendeeStatusIsAlsoApplied() {
        val row = row("[]").let { JsonObject(it + ("Attendees" to Json.parseToJsonElement("[{\"Token\":\"known\",\"Status\":1}]"))) }
        val result = ProtonICalendar.parse(listOf(ics("ATTENDEE;X-PM-TOKEN=known:mailto:guest@example.test")), row).details!!
        assertEquals(AttendanceStatus.TENTATIVE, result.attendees.single().status)
    }
    @Test fun partialAndConflictingStatusListsStayDeferred() {
        unsupported { parse("ATTENDEE:mailto:guest@example.test", statuses = "{\"Attendees\":[],\"MoreAttendees\":1}") }
        unsupported { parse("ATTENDEE;X-PM-TOKEN=a:mailto:guest@example.test", statuses = "{\"Attendees\":[{\"Token\":\"a\",\"Status\":2},{\"Token\":\"a\",\"Status\":3}],\"MoreAttendees\":0}") }
        unsupported { parse("ATTENDEE;X-PM-TOKEN=a:mailto:guest@example.test", statuses = "{\"Attendees\":[{\"Token\":\"a\",\"Status\":9}],\"MoreAttendees\":0}") }
    }
    @Test fun distinctParticipantsCannotShareAnExplicitStatusToken() {
        unsupported {
            parse("ATTENDEE;X-PM-TOKEN=shared:mailto:first@example.test\nATTENDEE;X-PM-TOKEN=shared:mailto:second@example.test",
                statuses = "{\"Attendees\":[{\"Token\":\"shared\",\"Status\":3}],\"MoreAttendees\":0}")
        }
    }
    @Test fun canonicalTokenCollisionsAreRejectedEvenWithoutStatusMetadata() {
        unsupported { parse("ATTENDEE:mailto:first.last@proton.me\nATTENDEE:mailto:firstlast+alias@proton.me") }
    }
    @Test fun unsupportedParticipantSemanticsAreNotConvertedIntoPeople() {
        for (extra in listOf("ATTENDEE:urn:uuid:synthetic", "ATTENDEE;CUTYPE=GROUP:mailto:group@example.test", "ATTENDEE;ROLE=CHAIR:mailto:guest@example.test", "ATTENDEE;PARTSTAT=DELEGATED:mailto:guest@example.test", "ATTENDEE;SENT-BY=\"mailto:delegate@example.test\":mailto:guest@example.test", "ATTENDEE:mailto:guest@example.test\nATTENDEE:mailto:GUEST@example.test")) unsupported { parse(extra) }
    }
    @Test fun resourceAndNonParticipantHaveExplicitRoles() {
        val result = parse("ATTENDEE;CUTYPE=ROOM:mailto:room@example.test\nATTENDEE;ROLE=NON-PARTICIPANT:mailto:observer@example.test")
        assertEquals(listOf(AttendanceRole.RESOURCE, AttendanceRole.NONE), result.attendees.map { it.role })
    }
    @Test fun typedUnknownAndRepeatedParticipantParametersStayUnsupported() {
        for (parameter in listOf("ROLE=X-UNKNOWN", "PARTSTAT=X-UNKNOWN", "ROLE=REQ-PARTICIPANT;ROLE=OPT-PARTICIPANT", "PARTSTAT=ACCEPTED;PARTSTAT=DECLINED")) {
            unsupported { parse("ATTENDEE;$parameter:mailto:guest@example.test") }
        }
    }
    @Test fun participantsFromLaterEncryptedPartsAreIncluded() {
        val result = ProtonICalendar.parse(listOf(ics(), part("ATTENDEE;PARTSTAT=ACCEPTED:mailto:guest@example.test")), row("[]")).details!!
        assertEquals(AttendanceStatus.ACCEPTED, result.attendees.single().status)
    }

    private fun parse(extra: String = "", notifications: String? = "[]", bootstrap: JsonObject? = null, allDay: Boolean = false, statuses: String? = null): EventDetails =
        ProtonICalendar.parse(listOf(ics(extra, allDay)), row(notifications, statuses), bootstrap).details!!
    private fun row(notifications: String?, statuses: String? = null) = buildJsonObject {
        put("CalendarID", "calendar")
        if (notifications != null) put("Notifications", Json.parseToJsonElement(notifications))
        if (statuses != null) put("AttendeesInfo", Json.parseToJsonElement(statuses))
    }
    private fun defaults() = Json.parseToJsonElement("""{"CalendarSettings":{"CalendarID":"calendar","DefaultPartDayNotifications":[{"Type":1,"Trigger":"-PT15M"}],"DefaultFullDayNotifications":[{"Type":1,"Trigger":"PT9H"}]}}""").jsonObject
    private fun ics(extra: String = "", allDay: Boolean = false) = part((if (allDay) "DTSTART;VALUE=DATE:20261001\nDTEND;VALUE=DATE:20261002" else "DTSTART:20261001T100000Z\nDTEND:20261001T110000Z") + "\n" + extra)
    private fun part(properties: String) = "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Synthetic fixture//EN\nBEGIN:VEVENT\nUID:synthetic\n$properties\nEND:VEVENT\nEND:VCALENDAR\n".replace("\n", "\r\n")
    private fun unsupported(block: () -> Unit) {
        try { block(); fail("Expected unsupported") } catch (failure: RemoteImportException) { assertEquals(RemoteImportFailure.UNSUPPORTED, failure.reason) }
    }
}
