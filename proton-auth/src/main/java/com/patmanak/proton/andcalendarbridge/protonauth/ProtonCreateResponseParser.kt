// SPDX-License-Identifier: GPL-3.0-only
// Protocol adaptation from Proton SyncMultipleApiResponse/SyncResponse; see PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.serialization.json.*

internal sealed interface ProtonCreateResponse {
    /** Reported success is a candidate for authenticated refetch/decode, never final acknowledgement. */
    class Candidate(val event: JsonObject) : ProtonCreateResponse {
        override fun toString() = "ProtonCreateResponse.Candidate(redacted)"
    }
    class ReportedFailure(val code: Long) : ProtonCreateResponse {
        override fun toString() = "ProtonCreateResponse.ReportedFailure(code=$code, outcome=unknown)"
    }
    data object Unknown : ProtonCreateResponse
}

/** Exactly one CREATE operation; malformed, foreign and incomplete acknowledgements remain unknown. */
internal object ProtonCreateResponseParser {
    fun parse(body: String, calendar: String, uid: String): ProtonCreateResponse { return try {
        if (body.length > 1_048_576 || body.toByteArray(Charsets.UTF_8).size > 1_048_576) return ProtonCreateResponse.Unknown
        if (!UniqueJsonKeys(body).validate()) return ProtonCreateResponse.Unknown
        val root = Json.parseToJsonElement(body) as? JsonObject ?: return ProtonCreateResponse.Unknown
        if (root.number("Code") !in listOf(1000L, 1001L)) return ProtonCreateResponse.Unknown
        val entry = root.array("Responses").singleOrNull() as? JsonObject ?: return ProtonCreateResponse.Unknown
        if (entry.number("Index") != 0L) return ProtonCreateResponse.Unknown
        val response = entry.obj("Response")
        val code = response.number("Code")
        if (code != 1000L) return if (code > 0 && response["Event"] in listOf(null, JsonNull))
            ProtonCreateResponse.ReportedFailure(code) else ProtonCreateResponse.Unknown
        val event = response.obj("Event")
        if (event.string("ID").isBlank() || event.string("ID").length > 4096 ||
            event.string("CalendarID") != calendar || event.string("UID") != uid || event.number("ModifyTime") < 0)
            return ProtonCreateResponse.Unknown
        ProtonCreateResponse.Candidate(event)
    } catch (_: Exception) { ProtonCreateResponse.Unknown } }
}

/** Reject duplicate names (including escaped equivalents) before Json can silently keep the last.
 * This bounded preflight checks containers and names; Json remains responsible for value syntax.
 */
internal class UniqueJsonKeys(private val source: String) {
    private var offset = 0
    fun validate(): Boolean = try { value(0); space(); offset == source.length } catch (_: Exception) { false }
    private fun space() { while (offset < source.length && source[offset] in " \t\r\n") offset++ }
    private fun take(expected: Char) { space(); require(offset < source.length && source[offset++] == expected) }
    private fun string(): String {
        space(); val start = offset; take('"')
        while (offset < source.length) {
            when (source[offset++]) {
                '\\' -> { require(offset < source.length); offset++ }
                '"' -> return source.substring(start, offset)
            }
        }
        error("Unterminated string")
    }
    private fun value(depth: Int) {
        require(depth <= 64); space(); require(offset < source.length)
        when (source[offset]) {
            '{' -> {
                offset++; space(); val names = mutableSetOf<String>()
                if (source.getOrNull(offset) == '}') { offset++; return }
                while (true) {
                    val name = (Json.parseToJsonElement(string()) as JsonPrimitive).content
                    require(names.add(name)); take(':'); value(depth + 1); space()
                    if (source.getOrNull(offset) == '}') { offset++; return }; take(',')
                }
            }
            '[' -> {
                offset++; space()
                if (source.getOrNull(offset) == ']') { offset++; return }
                while (true) {
                    value(depth + 1); space()
                    if (source.getOrNull(offset) == ']') { offset++; return }; take(',')
                }
            }
            '"' -> string()
            else -> {
                val start = offset
                while (offset < source.length && source[offset] !in ",]} \t\r\n") offset++
                require(offset > start)
            }
        }
    }
}
