// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.EventTime
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Offline temporal request/readback experiment. Supplies no dispatch authority.
 * The pinned Android EventEntity DTO documents timed StartTime/EndTime as epoch seconds.
 * Its all-day iCal adjustment does not establish the five API metadata conventions, so
 * all-day request/readback remains refused even though the plaintext patcher supports it.
 * IsBreakingChange=0 is inherited as an unqualified hypothesis for this no-attendee,
 * same-kind/same-zone experiment. No temporal service write or readback is qualified here.
 */
internal object ProtonTimeEventUpdate {
    fun request(original: JsonObject, clear: JsonObject, encrypted: JsonObject, changed: Boolean,
        context: JsonObject, before: EventTime, after: EventTime): JsonObject {
        require(changed)
        temporalBaseline(original, clear, encrypted, before, after)
        // Keep the established literal wire builder and validator; this is a separate
        // metadata/readback profile, never an expansion of the text or title format.
        return ProtonSimpleEventUpdate.request(original, clear, encrypted, changed, context)
    }

    fun matchesReadback(original: JsonObject, clear: JsonObject, encrypted: JsonObject,
        actual: JsonObject, before: EventTime, after: EventTime): Boolean = try {
        val expected = temporalBaseline(original, clear, encrypted, before, after)
        ProtonSimpleEventUpdate.matchesReadback(expected, clear, encrypted, actual)
    } catch (_: Exception) { false }

    /** Patch exactly the five temporal fields; preserve original clocks and every other field. */
    private fun temporalBaseline(original: JsonObject, clear: JsonObject, encrypted: JsonObject,
        before: EventTime, after: EventTime): JsonObject {
        require(before is EventTime.Timed && after is EventTime.Timed)
        require(before != after && before.zone == after.zone)
        val oldMetadata = metadata(before)
        val newMetadata = metadata(after)
        ProtonSimpleEventUpdate.qualify(original)
        require(oldMetadata.all { (name, value) -> original[name] == value })
        require(clear.number("Type") == 2L && encrypted.number("Type") == 3L)
        return JsonObject(original + newMetadata)
    }

    private fun metadata(time: EventTime.Timed): JsonObject {
        val zone = time.zone
        require(zone == ZoneOffset.UTC || zone.id in ZoneId.getAvailableZoneIds())
        // A named nonzero fixed-offset zone is no stronger than a literal offset.
        require(!zone.rules.isFixedOffset || zone.rules.getOffset(Instant.EPOCH) == ZoneOffset.UTC)
        for (instant in listOf(time.start, time.end)) {
            require(instant.nano == 0 && instant >= Instant.EPOCH && instant <= LAST_TIMESTAMP)
            val local = instant.atZone(zone)
            require(local.year in 1970..9999)
            require(zone.rules.getValidOffsets(local.toLocalDateTime()).single() == local.offset)
        }
        // Observed API UTC spelling is distinct from the domain ZoneOffset.UTC ID (Z).
        // Other zone identifiers are retained exactly; aliases are not interchangeable.
        val apiZone = if (zone == ZoneOffset.UTC) "UTC" else zone.id
        return buildJsonObject {
            put("StartTime", time.start.epochSecond); put("EndTime", time.end.epochSecond)
            put("StartTimezone", apiZone); put("EndTimezone", apiZone); put("FullDay", 0)
        }
    }

    private val LAST_TIMESTAMP = Instant.parse("9999-12-31T23:59:59Z")
}
