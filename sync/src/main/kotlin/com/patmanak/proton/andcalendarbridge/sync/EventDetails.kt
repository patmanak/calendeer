package com.patmanak.proton.andcalendarbridge.sync

/** Local alert offset from the occurrence start. Negative values mean after start. */
data class EventReminder(val minutesBefore: Int) {
    init { require(minutesBefore in -525600..525600 && minutesBefore != -1) }
}

data class EventPerson(val email: String, val name: String = "") {
    init {
        require(email.isNotBlank() && email.length <= 320 && '@' in email)
        require(email.none { it.isWhitespace() || it == '\u0000' })
        require(name.length <= 512 && name.none { it == '\r' || it == '\n' || it == '\u0000' })
    }
}

enum class AttendanceRole { REQUIRED, OPTIONAL, RESOURCE, NONE }
enum class AttendanceStatus { NEEDS_ACTION, ACCEPTED, DECLINED, TENTATIVE, UNKNOWN }
data class EventAttendee(val person: EventPerson, val role: AttendanceRole,
    val status: AttendanceStatus)

/** Read-only display data; this grants neither invitation nor reply capability. */
data class EventDetails(
    val reminders: List<EventReminder> = emptyList(),
    val organizer: EventPerson? = null,
    val attendees: List<EventAttendee> = emptyList(),
) {
    init {
        require(reminders.size <= 5 && reminders.distinct().size == reminders.size)
        require(attendees.size <= 100)
        require(attendees.map { it.person.email.lowercase(java.util.Locale.ROOT) }.distinct().size == attendees.size)
    }
}
