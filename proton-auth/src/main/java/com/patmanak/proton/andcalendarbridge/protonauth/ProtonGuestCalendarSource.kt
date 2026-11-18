package com.patmanak.proton.andcalendarbridge.protonauth

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private val guestUtc = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withResolverStyle(ResolverStyle.STRICT)
private val calendarFields = setOf("PRODID", "VERSION", "CALSCALE")
private val eventFields = setOf("UID", "DTSTAMP", "SEQUENCE", "DTSTART", "DTEND", "ORGANIZER",
    "ATTENDEE", "SUMMARY", "DESCRIPTION", "LOCATION", "STATUS", "TRANSP", "CREATED", "LAST-MODIFIED")

/** Gate the raw structure before biweekly can supply defaults, normalize duplicates or retain private extensions. */
internal fun guestCalendarWithoutAlarms(source: String): String? {
    if (source.length !in 1..262144 || !Charsets.UTF_8.newEncoder().canEncode(source)) return null
    val normalized = source.replace("\r\n", "\n")
    if (normalized.any { it.isISOControl() && it !in "\n\t" }) return null
    val lines = normalized.replace(Regex("\n[ \t]"), "").removeSuffix("\n").split('\n')
    if (lines.size !in 8..2048 || lines.first() != "BEGIN:VCALENDAR" || lines.last() != "END:VCALENDAR") return null
    val calendar = mutableMapOf<String, String>()
    val event = mutableMapOf<String, String>()
    var insideEvent = false
    var sawEvent = false
    var insideAlarm = false
    var attendees = 0
    val kept = StringBuilder("BEGIN:VCALENDAR\r\n")

    for (line in lines.subList(1, lines.lastIndex)) {
        if (line.isEmpty() || line.length > 65536 || '\t' in line || ':' !in line) return null
        when {
            line == "BEGIN:VEVENT" && !sawEvent -> { insideEvent = true; sawEvent = true }
            line == "END:VEVENT" && insideEvent && !insideAlarm -> insideEvent = false
            line == "BEGIN:VALARM" && insideEvent && !insideAlarm -> { insideAlarm = true; continue }
            line == "END:VALARM" && insideAlarm -> { insideAlarm = false; continue }
            line.startsWith("BEGIN:", true) || line.startsWith("END:", true) -> return null
            insideAlarm -> continue
            else -> {
                val parts = line.substringBefore(':').split(';')
                val name = parts.first()
                val value = line.substringAfter(':')
                if (insideEvent) {
                    if (name !in eventFields || !guestParameters(name, parts.drop(1), value)) return null
                    if (name == "ATTENDEE") {
                        if (++attendees > 100) return null
                    } else if (event.put(name, line) != null) return null
                } else if (name !in calendarFields || parts.size != 1 || calendar.put(name, value) != null) return null
            }
        }
        kept.append(line).append("\r\n")
    }
    if (insideEvent || insideAlarm || calendar["VERSION"] != "2.0" || calendar["PRODID"].isNullOrBlank() ||
        calendar["CALSCALE"]?.let { it != "GREGORIAN" } == true ||
        !event.keys.containsAll(setOf("UID", "DTSTAMP", "SEQUENCE", "DTSTART", "DTEND", "ORGANIZER"))) return null
    val start = event.getValue("DTSTART").substringAfter("DTSTART")
    val end = event.getValue("DTEND").substringAfter("DTEND")
    if (start.substringBefore(':') != end.substringBefore(':') || end < start) return null
    return kept.append("END:VCALENDAR\r\n").toString()
}

private fun guestParameters(name: String, params: List<String>, value: String): Boolean {
    if (name == "ORGANIZER" || name == "ATTENDEE") {
        if (!value.startsWith("mailto:", ignoreCase = true) || value.drop(7).any { it in "%?#\"<>,;" }) return false
        val seen = mutableSetOf<String>()
        return params.all { parameter ->
            val key = parameter.substringBefore('=')
            val raw = parameter.substringAfter('=', "")
            seen.add(key) && when {
                key == "CN" -> raw.length in 1..512 && raw.none { it in "\"\\^" || it.isISOControl() }
                name == "ORGANIZER" -> false
                key == "ROLE" -> raw in setOf("REQ-PARTICIPANT", "OPT-PARTICIPANT")
                key == "PARTSTAT" -> raw in setOf("NEEDS-ACTION", "ACCEPTED", "TENTATIVE", "DECLINED")
                key == "CUTYPE" -> raw == "INDIVIDUAL"
                key == "RSVP" -> raw == "TRUE" || raw == "FALSE"
                key == "X-PM-TOKEN" -> raw.matches(Regex("[a-fA-F0-9]{40}"))
                else -> false
            }
        }
    }
    if (name in setOf("DTSTART", "DTEND", "DTSTAMP", "CREATED", "LAST-MODIFIED")) {
        if (params == listOf("VALUE=DATE") && name in setOf("DTSTART", "DTEND")) {
            return value.length == 8 && LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).year in 1..9999
        }
        return params.isEmpty() && value.length == 16 && LocalDateTime.parse(value, guestUtc).year in 1..9999
    }
    if (params.isNotEmpty()) return false
    return when (name) {
        "UID" -> value.isNotBlank() && value.length <= 512
        "SEQUENCE" -> value.toIntOrNull()?.let { it >= 0 && it.toString() == value } == true
        "STATUS" -> value in setOf("CONFIRMED", "TENTATIVE")
        "TRANSP" -> value in setOf("OPAQUE", "TRANSPARENT")
        else -> true
    }
}
