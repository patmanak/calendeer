package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/** Historical import-cache encoding. Preserve exact bytes/null omission for saved identities
 * and acknowledgement comparisons; this is separate from the outgoing receipt protocols. */
internal object ImportPayloadCodec {
    fun key(scope: CalendarScope) = org.json.JSONArray()
        .put(scope.account.value).put(scope.calendar.value).put(scope.generation).toString()

    fun encodeCheckpoint(value: PageCheckpoint) = JSONObject().apply {
        put("adapter", value.format.id); put("version", value.format.version)
        put("cursor", value.cursor?.value ?: JSONObject.NULL); put("complete", value.traversalComplete)
    }.toString()
    fun decodeCheckpoint(scope: CalendarScope, payload: String): PageCheckpoint = JSONObject(payload).let {
        val format = AdapterFormat(it.getString("adapter"), it.getInt("version"))
        PageCheckpoint(scope, format, if (it.isNull("cursor")) null else OpaqueCursor(format, it.getString("cursor")), it.getBoolean("complete"))
    }
    fun encodeChange(value: RemoteChange) = JSONObject().apply {
        put("id", value.eventId.value); put("revision", value.revision.value)
        put("deleted", value is RemoteChange.Deleted)
        if (value is RemoteChange.Upsert) {
            val content = value.content
            put("title", content.title); put("description", content.description); put("location", content.location)
            // Omit null to preserve legacy payload encoding and pending acknowledgement equality.
            content.colorRgb?.let { put("colorRgb", it) }
            content.recurrence?.let { put("recurrence", encodeRecurrence(it)) }
            content.details?.let { put("details", encodeDetails(it)) }
            when (val time = content.time) {
                is EventTime.AllDay -> { put("allDay", true); put("start", time.start.toString()); put("end", time.endExclusive.toString()) }
                is EventTime.Timed -> { put("allDay", false); put("start", time.start.toString()); put("end", time.end.toString()); put("zone", time.zone.id) }
            }
        }
    }.toString()
    fun decodeChange(payload: String): RemoteChange = JSONObject(payload).let {
        val id = EventId(it.getString("id")); val revision = Revision(it.getString("revision"))
        if (it.getBoolean("deleted")) RemoteChange.Deleted(id, revision) else {
            val time = if (it.getBoolean("allDay")) EventTime.AllDay(LocalDate.parse(it.getString("start")), LocalDate.parse(it.getString("end")))
            else EventTime.Timed(Instant.parse(it.getString("start")), Instant.parse(it.getString("end")), ZoneId.of(it.getString("zone")))
            RemoteChange.Upsert(id, revision, EventContent(it.getString("title"), time, it.getString("description"),
                it.getString("location"), if (it.isNull("colorRgb")) null else it.getInt("colorRgb"),
                if (it.isNull("recurrence")) null else decodeRecurrence(it.getJSONObject("recurrence")),
                if (it.isNull("details")) null else decodeDetails(it.getJSONObject("details"))))
        }
    }
    private fun encodePerson(value: EventPerson) = JSONObject().apply {
        put("email", value.email); put("name", value.name)
    }
    private fun decodePerson(value: JSONObject) = EventPerson(value.getString("email"), value.getString("name"))
    private fun encodeDetails(value: EventDetails) = JSONObject().apply {
        put("schema", 1)
        put("reminders", JSONArray().apply { value.reminders.forEach { put(it.minutesBefore) } })
        value.organizer?.let { put("organizer", encodePerson(it)) }
        put("attendees", JSONArray().apply { value.attendees.forEach { attendee ->
            put(JSONObject().apply {
                put("person", encodePerson(attendee.person)); put("role", attendee.role.name); put("status", attendee.status.name)
            })
        } })
    }
    private fun decodeDetails(value: JSONObject): EventDetails {
        require(value.getInt("schema") == 1) { "Unsupported event details schema" }
        val reminders = value.getJSONArray("reminders")
        val attendees = value.getJSONArray("attendees")
        require(reminders.length() <= 5 && attendees.length() <= 100)
        return EventDetails(List(reminders.length()) { EventReminder(reminders.getInt(it)) },
            if (value.isNull("organizer")) null else decodePerson(value.getJSONObject("organizer")),
            List(attendees.length()) { index -> attendees.getJSONObject(index).let {
                EventAttendee(decodePerson(it.getJSONObject("person")), AttendanceRole.valueOf(it.getString("role")),
                    AttendanceStatus.valueOf(it.getString("status")))
            } })
    }
    private fun encodeRecurrence(value: EventRecurrence) = JSONObject().apply {
        put("schema", 1); put("uid", value.uid)
        value.rule?.let { put("rule", it) }
        put("additional", JSONArray().apply { value.additionalDates.forEach { put(encodePoint(it)) } })
        put("excluded", JSONArray().apply { value.excludedDates.forEach { put(encodePoint(it)) } })
        value.original?.let { put("original", encodePoint(it)) }
        put("cancelled", value.cancelled)
    }
    private fun encodePoint(value: RecurrencePoint) = JSONObject().apply {
        when (value) {
            is RecurrencePoint.AllDay -> { put("type", "date"); put("value", value.date.toString()) }
            is RecurrencePoint.Timed -> { put("type", "instant"); put("value", value.instant.toString()) }
        }
    }
    private fun decodePoint(value: JSONObject): RecurrencePoint = when (value.getString("type")) {
        "date" -> RecurrencePoint.AllDay(LocalDate.parse(value.getString("value")))
        "instant" -> RecurrencePoint.Timed(Instant.parse(value.getString("value")))
        else -> error("Unsupported recurrence date type")
    }
    private fun decodeRecurrence(value: JSONObject): EventRecurrence {
        require(value.getInt("schema") == 1) { "Unsupported recurrence schema" }
        fun points(name: String): List<RecurrencePoint> = value.getJSONArray(name).let { array ->
            require(array.length() <= 512)
            List(array.length()) { decodePoint(array.getJSONObject(it)) }
        }
        return EventRecurrence(value.getString("uid"), if (value.isNull("rule")) null else value.getString("rule"),
            points("additional"), points("excluded"), if (value.isNull("original")) null else decodePoint(value.getJSONObject("original")),
            value.getBoolean("cancelled"))
    }
    fun requirePreparationSnapshotBudget(value: String) {
        require(value.length <= LocalEditPreparation.MAX_PAYLOAD_BYTES) { "Local preparation observation is too large" }
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining()
        require(bytes <= LocalEditPreparation.MAX_PAYLOAD_BYTES) { "Local preparation observation is too large" }
    }
}
