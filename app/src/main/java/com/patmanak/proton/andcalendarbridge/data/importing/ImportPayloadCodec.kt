package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.importing.ImportSqlValues.COVERAGE_MAX_BYTES
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

    // This schema stores project-owned observations, never adapter cursor internals.
    private fun intervalJson(value: CoverageInterval) = JSONObject().apply {
        put("start", value.start.toString()); put("end", value.endExclusive.toString()); put("zone", value.zone.id)
    }
    private fun readInterval(value: JSONObject) = CoverageInterval(LocalDate.parse(value.getString("start")),
        LocalDate.parse(value.getString("end")), ZoneId.of(value.getString("zone")))
    private fun boundedCoverage(payload: String): String {
        require(payload.toByteArray(Charsets.UTF_8).size <= COVERAGE_MAX_BYTES) { "Coverage payload exceeds limit" }
        return payload
    }
    private fun coverageJson(payload: String, maximumSchema: Int = 1): JSONObject = JSONObject(boundedCoverage(payload)).also {
        require(it.getInt("schema") in 1..maximumSchema) { "Unknown coverage schema" }
    }
    fun encodePageCoverage(value: PageCoverage): String = boundedCoverage(JSONObject().apply {
        put("schema", 1); put("plan", JSONArray(value.plan.map(::intervalJson)))
        put("index", value.windowIndex); put("starts", value.startsWindow); put("finishes", value.finishesWindow)
    }.toString())
    fun decodePageCoverage(payload: String): PageCoverage = coverageJson(payload).let {
        val plan = it.getJSONArray("plan")
        require(plan.length() in 1..128)
        PageCoverage((0 until plan.length()).map { index -> readInterval(plan.getJSONObject(index)) },
            it.getInt("index"), it.getBoolean("starts"), it.getBoolean("finishes"))
    }
    private fun validateRun(value: CoverageRun) {
        PageCoverage(value.windows.map { it.interval }, 0, true, false) // Validate disjoint dates and zone.
        value.windows.forEach {
            require((it.receivedAt ?: 0) >= 0)
            require((it.appliedAt ?: 0) >= 0)
            require(!it.prefixKnown || it.receivedPages > 0)
            require(!it.terminalReceived || it.receivedPages > 0)
            require(!it.terminalApplied || it.appliedPages == it.receivedPages)
            require((it.receivedPages == 0L) == (it.receivedAt == null))
            require((it.appliedPages == 0L) == (it.appliedAt == null))
        }
        require(value.lastIndex >= 0 || value.windows.all { it.receivedPages == 0L })
        require(!value.finished || value.lastIndex == value.windows.lastIndex && value.windows.last().terminalApplied)
    }
    private fun runJson(value: CoverageRun): JSONObject {
        validateRun(value)
        return JSONObject().apply {
            put("adapter", value.format.id); put("version", value.format.version)
            put("lastIndex", value.lastIndex); put("finished", value.finished)
            put("windows", JSONArray(value.windows.map { window -> JSONObject().apply {
                put("interval", intervalJson(window.interval)); put("prefixKnown", window.prefixKnown)
                put("receivedPages", window.receivedPages); put("appliedPages", window.appliedPages)
                put("terminalReceived", window.terminalReceived); put("terminalApplied", window.terminalApplied)
                put("receivedAt", window.receivedAt ?: JSONObject.NULL); put("appliedAt", window.appliedAt ?: JSONObject.NULL)
            } }))
        }
    }
    private fun readRun(value: JSONObject): CoverageRun {
        val windows = value.getJSONArray("windows")
        require(windows.length() in 1..128)
        return CoverageRun(AdapterFormat(value.getString("adapter"), value.getInt("version")),
            (0 until windows.length()).map { index -> windows.getJSONObject(index).let {
                WindowCoverage(readInterval(it.getJSONObject("interval")), it.getBoolean("prefixKnown"),
                    it.getLong("receivedPages"), it.getLong("appliedPages"), it.getBoolean("terminalReceived"),
                    it.getBoolean("terminalApplied"), if (it.isNull("receivedAt")) null else it.getLong("receivedAt"),
                    if (it.isNull("appliedAt")) null else it.getLong("appliedAt"))
            } }, value.getInt("lastIndex"), value.getBoolean("finished")).also(::validateRun)
    }
    fun encodeCoverage(value: CoverageLedger): String {
        require(value.previous?.finished != false)
        return boundedCoverage(JSONObject().apply {
            put("schema", 2); put("epoch", value.epoch)
            put("active", value.active?.let(::runJson) ?: JSONObject.NULL)
            put("previous", value.previous?.let(::runJson) ?: JSONObject.NULL)
            put("observed", value.observed?.let { observation -> JSONObject().apply {
                put("adapter", observation.format.id); put("version", observation.format.version)
                put("interval", intervalJson(observation.interval))
            } } ?: JSONObject.NULL)
        }.toString())
    }
    fun decodeCoverage(payload: String): CoverageLedger = coverageJson(payload, maximumSchema = 2).let {
        CoverageLedger(it.getLong("epoch"), if (it.isNull("active")) null else readRun(it.getJSONObject("active")),
            if (it.isNull("previous")) null else readRun(it.getJSONObject("previous")),
            if (it.getInt("schema") == 1 || it.isNull("observed")) null else it.getJSONObject("observed").let { observation ->
                CoverageObservation(AdapterFormat(observation.getString("adapter"), observation.getInt("version")),
                    readInterval(observation.getJSONObject("interval")))
            }).also { ledger ->
            require(ledger.previous?.finished != false)
        }
    }
}
