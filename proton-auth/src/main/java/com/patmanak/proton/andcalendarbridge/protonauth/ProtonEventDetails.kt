// SPDX-License-Identifier: GPL-3.0-only
// Adapts Proton NotificationEntity, Event.alarms and TransformEventUseCase; see PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import biweekly.component.VEvent
import biweekly.ICalVersion
import biweekly.property.ICalProperty
import com.patmanak.proton.andcalendarbridge.sync.*
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.json.*

/** Current API personal notifications override historical VALARM components entirely. */
internal object ProtonEventDetails {
    fun parse(event: VEvent, row: JsonObject, bootstrap: JsonObject?, allDay: Boolean): EventDetails {
        val notifications = when (val explicit = row["Notifications"]) {
            null, JsonNull -> {
                val settings = bootstrap?.get("CalendarSettings") as? JsonObject ?: unsupportedEvents()
                if (settings.string("CalendarID") != row.string("CalendarID")) unsupportedEvents()
                settings.array(if (allDay) "DefaultFullDayNotifications" else "DefaultPartDayNotifications")
            }
            is JsonArray -> explicit
            else -> unsupportedEvents()
        }
        if (notifications.size > 100) unsupportedEvents()
        val reminders = notifications.mapNotNull { element ->
            val value = element as? JsonObject ?: unsupportedEvents()
            val type = value.number("Type")
            if (type !in 0L..1L) unsupportedEvents()
            // Email delivery remains Proton's responsibility; never create a local email action.
            if (type == 1L) EventReminder(minutesBefore(value.string("Trigger")))
            else { durationSeconds(value.string("Trigger")); null }
        }.distinct()
        val organizer = event.organizer?.let {
            rejectDelegation(it)
            person(it.uri, it.email, it.commonName)
        }
        val statuses = statuses(row)
        if (event.attendees.size > 100) unsupportedEvents()
        val resolvedTokens = hashSetOf<String>()
        val attendees = event.attendees.map { attendee ->
            rejectDelegation(attendee)
            val person = person(attendee.uri, attendee.email, attendee.commonName)
            // Biweekly consumes ROLE/PARTSTAT into typed fields while parsing.
            // Any raw remainder is a repeated ambiguous parameter, not the parsed value.
            if (attendee.getParameter("ROLE") != null || attendee.getParameter("PARTSTAT") != null) unsupportedEvents()
            val cutype = attendee.calendarUserType?.value?.uppercase(Locale.ROOT) ?: "INDIVIDUAL"
            val role = (attendee.role?.value ?: attendee.participationLevel?.getValue(ICalVersion.V2_0))
                ?.uppercase(Locale.ROOT) ?: "REQ-PARTICIPANT"
            val attendanceRole = when (cutype) {
                "ROOM", "RESOURCE" -> {
                    if (role != "REQ-PARTICIPANT") unsupportedEvents()
                    AttendanceRole.RESOURCE
                }
                "INDIVIDUAL" -> when (role) {
                    "REQ-PARTICIPANT" -> AttendanceRole.REQUIRED
                    "OPT-PARTICIPANT" -> AttendanceRole.OPTIONAL
                    "NON-PARTICIPANT" -> AttendanceRole.NONE
                    else -> unsupportedEvents()
                }
                else -> unsupportedEvents()
            }
            val token = attendee.getParameter("X-PM-TOKEN")?.also {
                if (it.isBlank() || it.length > 128) unsupportedEvents()
            } ?: token(event.uid.value, person.email)
            // A single response must never be attributed to two participant identities.
            if (!resolvedTokens.add(token)) unsupportedEvents()
            val status = statuses[token] ?: when (attendee.participationStatus?.value?.uppercase(Locale.ROOT)) {
                null, "NEEDS-ACTION" -> AttendanceStatus.NEEDS_ACTION
                "ACCEPTED" -> AttendanceStatus.ACCEPTED
                "DECLINED" -> AttendanceStatus.DECLINED
                "TENTATIVE" -> AttendanceStatus.TENTATIVE
                else -> unsupportedEvents()
            }
            EventAttendee(person, attendanceRole, status)
        }
        return EventDetails(reminders, organizer, attendees)
    }

    private fun statuses(row: JsonObject): Map<String, AttendanceStatus> {
        val rows = when (val info = row["AttendeesInfo"]) {
            null, JsonNull -> when (val legacy = row["Attendees"]) {
                null, JsonNull -> JsonArray(emptyList())
                is JsonArray -> legacy
                else -> unsupportedEvents()
            }
            is JsonObject -> {
                // This slice does not fetch additional pages of participation metadata.
                if (info.number("MoreAttendees") != 0L) unsupportedEvents()
                info.array("Attendees")
            }
            else -> unsupportedEvents()
        }
        if (rows.size > 1000) unsupportedEvents()
        val result = linkedMapOf<String, AttendanceStatus>()
        rows.forEach { element ->
            val value = element as? JsonObject ?: unsupportedEvents()
            val token = value.string("Token")
            if (token.isBlank() || token.length > 128 || token in result) unsupportedEvents()
            result[token] = when (value.number("Status")) {
                0L -> AttendanceStatus.NEEDS_ACTION
                1L -> AttendanceStatus.TENTATIVE
                2L -> AttendanceStatus.DECLINED
                3L -> AttendanceStatus.ACCEPTED
                else -> unsupportedEvents()
            }
        }
        return result
    }

    private fun rejectDelegation(property: ICalProperty) {
        if (listOf("SENT-BY", "DELEGATED-TO", "DELEGATED-FROM", "MEMBER").any { property.getParameter(it) != null }) unsupportedEvents()
    }

    private fun person(uri: String?, email: String?, name: String?): EventPerson {
        if (uri != null && !uri.startsWith("mailto:", true)) unsupportedEvents()
        val address = email ?: uri?.substringAfter(':') ?: unsupportedEvents()
        if (address.count { it == '@' } != 1 || address.startsWith('@') || address.endsWith('@') ||
            address.any { it in "<>;,?%\\\"" || it.isISOControl() }) unsupportedEvents()
        return EventPerson(address, name ?: "")
    }

    /** Identifier compatibility only, not a cryptographic authentication primitive. */
    private fun token(uid: String, email: String): String {
        val lower = email.lowercase(Locale.ROOT)
        val domain = lower.substringAfterLast('@')
        val canonical = if (domain in setOf("protonmail.ch", "protonmail.com", "pm.me", "proton.me")) {
            lower.substringBefore('@').substringBefore('+').replace(Regex("[.\\-_]"), "") + "@" + domain
        } else lower
        return MessageDigest.getInstance("SHA-1").digest((uid + canonical).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun minutesBefore(value: String): Int {
        val signedSeconds = durationSeconds(value)
        val seconds = kotlin.math.abs(signedSeconds)
        if (seconds % 60L != 0L || seconds / 60L > 525600L) unsupportedEvents()
        val minutes = (-signedSeconds / 60L).toInt()
        if (minutes == -1) unsupportedEvents() // Android reserves -1 for its default reminder sentinel.
        return minutes
    }

    private fun durationSeconds(value: String): Long {
        if (value.length > 64) unsupportedEvents()
        val match = Regex("^([+-]?)P(?:(\\d+)W|(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?)$").matchEntire(value)
            ?: unsupportedEvents()
        val pieces = match.groupValues.drop(2)
        if (pieces.all { it.isEmpty() } || value.endsWith('T')) unsupportedEvents()
        val units = listOf(604800L, 86400L, 3600L, 60L, 1L)
        var seconds = 0L
        pieces.forEachIndexed { index, text ->
            if (text.isNotEmpty()) seconds = Math.addExact(seconds, Math.multiplyExact(text.toLongOrNull() ?: unsupportedEvents(), units[index]))
        }
        return seconds * if (match.groupValues[1] == "-") -1 else 1
    }
}
