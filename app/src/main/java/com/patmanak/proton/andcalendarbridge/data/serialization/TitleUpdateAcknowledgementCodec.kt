// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.serialization

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONObject
import org.json.JSONArray

/** Canonical historical title receipts; schemas are shared by imports and attempt completion. */
internal object TitleUpdateAcknowledgementCodec {
    /** Canonical immutable metadata only. Sealed preparation/wire/evidence payloads are not copied. */
    fun encode(value: TitleUpdateAcknowledgementReceipt): String = JSONObject().apply {
        val receipt = value.snapshot()
        val attempt = receipt.attempt
        put("schema", if (receipt.handoff == null) 1 else 2)
        put("attempt", JSONObject().apply {
            put("account", attempt.scope.account.value); put("calendar", attempt.scope.calendar.value); put("generation", attempt.scope.generation)
            put("id", attempt.id.value); put("intent", attempt.localIntentId.value); put("event", attempt.eventId.value)
            put("revision", attempt.baseRevision.value); put("adapter", attempt.format.id); put("version", attempt.format.version)
            put("preparationAdapter", attempt.preparationFormat.id); put("preparationVersion", attempt.preparationFormat.version)
            put("preparationFingerprint", attempt.preparationFingerprint); put("payloadFingerprint", attempt.payloadFingerprint)
        })
        put("adapter", receipt.intent.format.id); put("version", receipt.intent.format.version)
        put("base", JSONObject(encodeChange(receipt.intent.base)))
        put("local", JSONObject(encodeChange(receipt.intent.base.copy(content = receipt.intent.content))))
        put("change", JSONObject(encodeChange(receipt.projection.change)))
        put("nonce", receipt.projection.nonce); put("protocol", receipt.projection.protocol)
        receipt.handoff?.let { put("handoff", encodeTitleHandoffFrame(it)) }
    }.toString().also(::requirePreparationSnapshotBudget)

    fun decode(payload: String): TitleUpdateAcknowledgementReceipt {
        requirePreparationSnapshotBudget(payload)
        val json = JSONObject(payload)
        fun text(source: JSONObject, field: String): String = source.get(field).let { require(it is String); it }
        fun integer(source: JSONObject, field: String): Long = source.get(field).let {
            require(it is Int || it is Long); (it as Number).toLong()
        }
        fun version(source: JSONObject, field: String): Int = integer(source, field).let {
            require(it in 1..Int.MAX_VALUE.toLong()); it.toInt()
        }
        val schema = integer(json, "schema")
        require(schema in 1L..2L && integer(json, "protocol") == 1L)
        require(json.keys().asSequence().toSet() == setOf("schema", "attempt", "adapter", "version", "base", "local", "change", "nonce", "protocol") +
            if (schema == 2L) setOf("handoff") else emptySet())
        val attempt = json.getJSONObject("attempt")
        require(attempt.keys().asSequence().toSet() == setOf("account", "calendar", "generation", "id", "intent", "event", "revision",
            "adapter", "version", "preparationAdapter", "preparationVersion", "preparationFingerprint", "payloadFingerprint"))
        val scope = CalendarScope(AccountId(text(attempt, "account")), CalendarId(text(attempt, "calendar")), integer(attempt, "generation"))
        val identity = TitleUpdateAttemptIdentity(scope, MutationId(text(attempt, "id")), MutationId(text(attempt, "intent")),
            EventId(text(attempt, "event")), Revision(text(attempt, "revision")), AdapterFormat(text(attempt, "adapter"), version(attempt, "version")),
            AdapterFormat(text(attempt, "preparationAdapter"), version(attempt, "preparationVersion")),
            text(attempt, "preparationFingerprint"), text(attempt, "payloadFingerprint"))
        val base = decodeChange(json.getJSONObject("base").toString()) as RemoteChange.Upsert
        val local = decodeChange(json.getJSONObject("local").toString()) as RemoteChange.Upsert
        require(local.eventId == base.eventId && local.revision == base.revision)
        val intent = LocalEditIntent(identity.localIntentId, scope, AdapterFormat(text(json, "adapter"), version(json, "version")), base, local.content)
        val change = decodeChange(json.getJSONObject("change").toString()) as RemoteChange.Upsert
        val handoff = if (schema == 1L) null else decodeTitleHandoffFrame(json.getJSONObject("handoff"))
        val receipt = TitleUpdateAcknowledgementReceipt(identity, intent,
            ProjectionReceipt(scope, intent.format, change, text(json, "nonce"), base), handoff).snapshot()
        // Re-encoding rejects extra event/detail fields, coercions and noncanonical metadata.
        require(encode(receipt) == payload)
        return receipt
    }

    /** Full historical frame, independent of mutable projection_bases and live handoff rows. */
    private fun encodeTitleHandoffFrame(frame: CapturedCreateHandoffFrame): JSONObject = JSONObject().apply {
        val baseline = frame.receipt.acknowledgement.baseline
        val attachment = baseline.receipt
        val binding = attachment.binding
        val capture = binding.identity
        val attempt = capture.attempt
        put("origin", JSONObject().apply {
            put("account", attempt.scope.account.value); put("calendar", attempt.scope.calendar.value); put("generation", attempt.scope.generation)
            put("attempt", attempt.id.value); put("adapter", attempt.format.id); put("version", attempt.format.version)
            put("payloadFingerprint", attempt.payloadFingerprint); put("local", capture.localId.value); put("capture", capture.captureId.value)
            put("captureAdapter", capture.captureFormat.id); put("captureVersion", capture.captureFormat.version)
            put("timestamp", capture.timestamp.toString()); put("contentFingerprint", capture.contentFingerprint); put("fingerprintVersion", capture.fingerprintVersion)
            put("event", binding.eventId.value); put("revision", binding.verifiedRevision.value)
            put("attachmentNonce", attachment.nonce); put("attachmentProtocol", attachment.protocol)
            put("change", JSONObject(encodeChange(baseline.change)))
        })
        put("ackNonce", frame.receipt.acknowledgement.nonce); put("ackProtocol", frame.receipt.acknowledgement.protocol)
        put("nonce", frame.receipt.nonce); put("protocol", frame.receipt.protocol)
        put("base", JSONObject(encodeChange(frame.base)))
        put("lastProjection", frame.lastProjection?.let { JSONObject(encodeHandoffProjection(it)) } ?: JSONObject.NULL)
    }

    private fun decodeTitleHandoffFrame(json: JSONObject): CapturedCreateHandoffFrame {
        fun text(source: JSONObject, field: String): String = source.get(field).let { require(it is String); it }
        fun integer(source: JSONObject, field: String): Long = source.get(field).let {
            require(it is Int || it is Long); (it as Number).toLong()
        }
        fun version(source: JSONObject, field: String): Int = integer(source, field).let {
            require(it in 1..Int.MAX_VALUE.toLong()); it.toInt()
        }
        require(json.keys().asSequence().toSet() == setOf("origin", "ackNonce", "ackProtocol", "nonce", "protocol", "base", "lastProjection"))
        val origin = json.getJSONObject("origin")
        require(origin.keys().asSequence().toSet() == setOf("account", "calendar", "generation", "attempt", "adapter", "version",
            "payloadFingerprint", "local", "capture", "captureAdapter", "captureVersion", "timestamp", "contentFingerprint", "fingerprintVersion",
            "event", "revision", "attachmentNonce", "attachmentProtocol", "change"))
        val scope = CalendarScope(AccountId(text(origin, "account")), CalendarId(text(origin, "calendar")), integer(origin, "generation"))
        val attempt = CreateAttemptIdentity(scope, MutationId(text(origin, "attempt")),
            AdapterFormat(text(origin, "adapter"), version(origin, "version")), text(origin, "payloadFingerprint"))
        val timestamp = Instant.parse(text(origin, "timestamp"))
        require(timestamp.toString() == text(origin, "timestamp"))
        val capture = CapturedCreateDispatchIdentity(attempt, LocalEventId(text(origin, "local")), MutationId(text(origin, "capture")),
            AdapterFormat(text(origin, "captureAdapter"), version(origin, "captureVersion")), timestamp,
            text(origin, "contentFingerprint"), version(origin, "fingerprintVersion"))
        val binding = CapturedCreateBinding(capture, EventId(text(origin, "event")), Revision(text(origin, "revision")))
        val baseline = CapturedCreateBaseline(CapturedCreateAttachmentReceipt(binding, text(origin, "attachmentNonce"),
            version(origin, "attachmentProtocol")), decodeChange(origin.getJSONObject("change").toString()) as RemoteChange.Upsert)
        val acknowledgement = CapturedCreateAcknowledgementReceipt(baseline, text(json, "ackNonce"), version(json, "ackProtocol"))
        val receipt = CapturedCreateHandoffReceipt(acknowledgement, text(json, "nonce"), version(json, "protocol"))
        val base = decodeChange(json.getJSONObject("base").toString()) as RemoteChange.Upsert
        val last = if (json.isNull("lastProjection")) null else decodeHandoffProjection(scope, json.getJSONObject("lastProjection").toString())
        return CapturedCreateHandoffFrame(receipt, base, last)
    }

    private fun encodeHandoffProjection(receipt: ProjectionReceipt): String = JSONObject().apply {
        check(receipt.representation == null && receipt.previousRepresentation == null) { "Represented handoff is unavailable" }
        put("adapter", receipt.format.id); put("version", receipt.format.version); put("nonce", receipt.nonce)
        put("protocol", receipt.protocol); put("change", encodeChange(receipt.change))
        put("previous", receipt.previousBase?.let(::encodeChange) ?: JSONObject.NULL)
    }.toString()
    private fun decodeHandoffProjection(scope: CalendarScope, payload: String): ProjectionReceipt {
        val json = JSONObject(payload)
        check(json.length() == 6) { "Handoff projection proof is malformed" }
        val receipt = ProjectionReceipt(scope, AdapterFormat(json.getString("adapter"), json.getInt("version")),
            decodeChange(json.getString("change")) as RemoteChange.Upsert, json.getString("nonce"),
            if (json.isNull("previous")) null else decodeChange(json.getString("previous")) as RemoteChange.Upsert, json.getInt("protocol"))
        check(encodeHandoffProjection(receipt) == payload) { "Handoff projection proof is not canonical" }; return receipt
    }

    private fun requirePreparationSnapshotBudget(value: String) {
        require(value.length <= LocalEditPreparation.MAX_PAYLOAD_BYTES) { "Local preparation observation is too large" }
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining()
        require(bytes <= LocalEditPreparation.MAX_PAYLOAD_BYTES) { "Local preparation observation is too large" }
    }
    private fun encodeChange(value: RemoteChange) = JSONObject().apply {
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
    private fun decodeChange(payload: String): RemoteChange = JSONObject(payload).let {
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
}
