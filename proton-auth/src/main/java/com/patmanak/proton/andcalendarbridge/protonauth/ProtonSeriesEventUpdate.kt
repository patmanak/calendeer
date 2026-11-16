// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.serialization.json.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.time.ZoneOffset

/** Separate profile. The null-rule view is validation-only; source rows stay intact.
 * Temporal API metadata deliberately retains the existing conservative interpretation.
 */
internal object ProtonSeriesEventUpdate {
    fun qualify(original: JsonObject, expected: EventContent): JsonObject {
        val rule = rule(expected)
        require(original.string("UID") == expected.recurrence!!.uid)
        val raw = original["RRule"] as? JsonPrimitive
        require(raw != null && raw.isString && CreateSeriesRule.parse(raw.content) == rule)
        return JsonObject(original + ("RRule" to JsonNull)).also(ProtonPersonalEventWire::qualify)
    }
    fun request(original: JsonObject, clear: JsonObject, encrypted: JsonObject, changed: Boolean,
        context: JsonObject, before: EventContent, after: EventContent, defaults: JsonObject? = null,
        calendarPart: JsonObject? = null): JsonObject {
        val oldRule = rule(before); val newRule = rule(after)
        require(before.recurrence!!.uid == after.recurrence!!.uid)
        val view = qualify(original, before)
        val request = ProtonPersonalEventUpdate.request(view, clear, encrypted, changed, context,
            before.copy(recurrence = null), after.copy(recurrence = null), defaults, calendarPart)
        val operation = request.array("Events").single() as JsonObject
        val breaking = oldRule != newRule || bounds(before.time) != bounds(after.time)
        val event = JsonObject(operation.obj("Event") + ("IsBreakingChange" to JsonPrimitive(if (breaking) 1 else 0)))
        return JsonObject(request + ("Events" to JsonArray(listOf(JsonObject(operation + ("Event" to event))))))
            .also { seriesEventUpdateOnlyBytes(it.toString()).fill(0) }
    }

    fun matchesReadback(original: JsonObject, clear: JsonObject, encrypted: JsonObject,
        actual: JsonObject, before: EventContent, after: EventContent, defaults: JsonObject? = null,
        calendarPart: JsonObject? = null): Boolean = try {
        require(before.recurrence!!.uid == after.recurrence!!.uid)
        val source = qualify(original, before)
        val observed = qualify(actual, after)
        ProtonPersonalEventUpdate.matchesReadback(source, clear, encrypted, observed,
            before.copy(recurrence = null), after.copy(recurrence = null), defaults, calendarPart)
    } catch (_: Exception) { false }

    private fun rule(content: EventContent): CreateSeriesRule {
        val recurrence = requireNotNull(content.recurrence)
        require(recurrence.isMaster && !recurrence.cancelled && recurrence.additionalDates.isEmpty() && recurrence.excludedDates.isEmpty())
        val details = requireNotNull(content.details)
        require(details.organizer == null && details.attendees.isEmpty())
        return CreateSeriesRule.parse(requireNotNull(recurrence.rule)).also { require(it.supports(content.time)) }
    }

    private fun bounds(time: EventTime): Pair<Long, Long> = when (time) {
        is EventTime.Timed -> time.start.epochSecond to time.end.epochSecond
        is EventTime.AllDay -> time.start.atStartOfDay(ZoneOffset.UTC).toEpochSecond() to
            time.endExclusive.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    }
}

/** Validate using a bit-zero view, but send the exact retained strict UTF-8 bytes. */
internal fun seriesEventUpdateOnlyBytes(payload: String): ByteArray {
    try {
        require(payload.length <= LocalEditPreparation.MAX_PAYLOAD_BYTES && UniqueJsonKeys(payload).validate())
        val root = Json.parseToJsonElement(payload) as JsonObject
        val operation = root.array("Events").single() as JsonObject
        val event = operation.obj("Event")
        val bit = event["IsBreakingChange"] as? JsonPrimitive
        require(bit != null && !bit.isString && bit.content in setOf("0", "1"))
        val eventView = JsonObject(event + ("IsBreakingChange" to JsonPrimitive(0)))
        val operationView = JsonObject(operation + ("Event" to eventView))
        val view = JsonObject(root + ("Events" to JsonArray(listOf(operationView))))
        personalEventUpdateOnlyBytes(view.toString()).fill(0)
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(payload))
        require(bytes.remaining() <= LocalEditPreparation.MAX_PAYLOAD_BYTES)
        return ByteArray(bytes.remaining()).also(bytes::get)
    } catch (_: Exception) { throw ProtonSingleDispatchDenied() }
}
