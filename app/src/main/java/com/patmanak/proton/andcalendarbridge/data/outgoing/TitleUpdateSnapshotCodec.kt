// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import android.database.Cursor
import com.patmanak.proton.andcalendarbridge.data.serialization.TitleUpdateAcknowledgementCodec
import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject

/** Strict historical domain snapshots; opaque protected payload bytes are never reinterpreted. */
internal object TitleUpdateSnapshotCodec {
    /** This is a domain snapshot codec, never a Proton wire serializer or a protected-payload decoder. */
    fun encodePreparation(value: LocalEditPreparation): String = bounded(JSONObject().apply {
        val intent = value.intent
        put("schema", 1); put("id", intent.id.value)
        put("account", intent.scope.account.value); put("calendar", intent.scope.calendar.value); put("generation", intent.scope.generation)
        put("intentAdapter", intent.format.id); put("intentVersion", intent.format.version); put("intentState", intent.state.name)
        put("event", intent.base.eventId.value); put("revision", intent.base.revision.value)
        put("base", contentJson(intent.base.content)); put("newTitle", intent.content.title)
        put("timestamp", value.timestamp.toString()); put("preparationAdapter", value.format.id)
        put("preparationVersion", value.format.version); put("preparationPayload", value.protectedPayload)
    }.toString())

    fun contentJson(value: EventContent) = JSONObject().apply {
        require(value.details == EventDetails() && value.recurrence == null && value.colorRgb == null)
        put("title", value.title); put("description", value.description); put("location", value.location)
        put("knownEmptyDetails", true)
        when (val time = value.time) {
            is EventTime.AllDay -> { put("allDay", true); put("start", time.start.toString()); put("end", time.endExclusive.toString()) }
            is EventTime.Timed -> { put("allDay", false); put("start", time.start.toString()); put("end", time.end.toString()); put("zone", time.zone.id) }
        }
    }

    fun decodePreparation(payload: String): LocalEditPreparation {
        val json = JSONObject(bounded(payload))
        require(json.keys().asSequence().toSet() == PREPARATION_FIELDS && integer(json, "schema") == 1L)
        val scope = CalendarScope(AccountId(text(json, "account")), CalendarId(text(json, "calendar")), integer(json, "generation"))
        val base = RemoteChange.Upsert(EventId(text(json, "event")), Revision(text(json, "revision")),
            decodeContent(json.getJSONObject("base")))
        val intent = LocalEditIntent(MutationId(text(json, "id")), scope,
            AdapterFormat(text(json, "intentAdapter"), version(json, "intentVersion")), base,
            base.content.copy(title = text(json, "newTitle")), LocalEditState.valueOf(text(json, "intentState")))
        val timeText = text(json, "timestamp")
        val timestamp = Instant.parse(timeText)
        require(timestamp.toString() == timeText)
        return LocalEditPreparation(intent, timestamp, AdapterFormat(text(json, "preparationAdapter"),
            version(json, "preparationVersion")), text(json, "preparationPayload"))
    }

    fun decodeContent(json: JSONObject): EventContent {
        require(json.get("allDay") is Boolean && json.get("knownEmptyDetails") == true)
        val allDay = json.getBoolean("allDay")
        require(json.keys().asSequence().toSet() == CONTENT_FIELDS + if (allDay) emptySet() else setOf("zone"))
        val time = if (allDay) EventTime.AllDay(LocalDate.parse(text(json, "start")), LocalDate.parse(text(json, "end")))
            else EventTime.Timed(Instant.parse(text(json, "start")), Instant.parse(text(json, "end")), ZoneId.of(text(json, "zone")))
        return EventContent(text(json, "title"), time, text(json, "description"), text(json, "location"), details = EventDetails())
    }

    fun text(json: JSONObject, key: String): String = json.get(key).let { require(it is String); it }
    fun integer(json: JSONObject, key: String): Long = json.get(key).let {
        require(it is Int || it is Long); (it as Number).toLong()
    }
    fun version(json: JSONObject, key: String): Int = integer(json, key).let {
        require(it in 1..Int.MAX_VALUE.toLong()); it.toInt()
    }
    fun version(row: Cursor, column: Int): Int {
        require(row.getType(column) == Cursor.FIELD_TYPE_INTEGER)
        return row.getLong(column).let { require(it in 1..Int.MAX_VALUE.toLong()); it.toInt() }
    }
    fun bounded(value: String): String {
        require(value.length <= LocalEditPreparation.MAX_PAYLOAD_BYTES)
        require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <= LocalEditPreparation.MAX_PAYLOAD_BYTES)
        return value
    }
    private val PREPARATION_FIELDS = setOf("schema", "id", "account", "calendar", "generation", "intentAdapter", "intentVersion",
            "intentState", "event", "revision", "base", "newTitle", "timestamp", "preparationAdapter", "preparationVersion", "preparationPayload")
    private val CONTENT_FIELDS = setOf("title", "description", "location", "knownEmptyDetails", "allDay", "start", "end")
    fun encodeCompletion(value: TitleUpdateCompletion): String = bounded(JSONObject().apply {
        put("schema", 1)
        put("result", JSONObject(encodeResult(value.result)))
        put("receipt", TitleUpdateAcknowledgementCodec.encode(value.receipt))
    }.toString())

    fun encodeResult(value: TitleUpdateVerifiedResult): String = bounded(JSONObject().apply {
        put("schema", 1); put("attempt", attemptJson(value.attempt))
        put("event", JSONObject().apply {
            put("id", value.event.eventId.value); put("revision", value.event.revision.value)
            put("content", contentJson(value.event.content))
        })
    }.toString())

    fun attemptJson(value: TitleUpdateAttemptIdentity) = JSONObject().apply {
        put("account", value.scope.account.value); put("calendar", value.scope.calendar.value)
        put("generation", value.scope.generation); put("id", value.id.value); put("intent", value.localIntentId.value)
        put("event", value.eventId.value); put("baseRevision", value.baseRevision.value)
        put("adapter", value.format.id); put("version", value.format.version)
        put("preparationAdapter", value.preparationFormat.id); put("preparationVersion", value.preparationFormat.version)
        put("preparationFingerprint", value.preparationFingerprint); put("payloadFingerprint", value.payloadFingerprint)
    }


}
