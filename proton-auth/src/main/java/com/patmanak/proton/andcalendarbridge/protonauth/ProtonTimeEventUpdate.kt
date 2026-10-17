// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.EventTime
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Offline temporal wire/readback rules, without dispatch authority.
 * Timed metadata retains its original v1 interpretation. All-day dates use measured
 * UTC-midnight bounds with an exclusive end and FullDay=1; no local-zone conversion.
 * Other kind/zone transitions remain refused. Service scope is recorded in TEMPORAL_UPDATE.md.
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
        require(before != after)
        require(when (before) {
            is EventTime.Timed -> after is EventTime.Timed && before.zone == after.zone
            is EventTime.AllDay -> after is EventTime.AllDay
        })
        val oldMetadata = metadata(before)
        val newMetadata = metadata(after)
        ProtonSimpleEventUpdate.qualify(original)
        require(oldMetadata.all { (name, value) -> original[name] == value })
        require(clear.number("Type") == 2L && encrypted.number("Type") == 3L)
        return JsonObject(original + newMetadata)
    }

    private fun metadata(time: EventTime): JsonObject = when (time) {
        is EventTime.Timed -> timedMetadata(time)
        is EventTime.AllDay -> {
            require(time.start.year in 1970..9999 && time.endExclusive.year in 1970..9999)
            buildJsonObject {
                put("StartTime", time.start.atStartOfDay(ZoneOffset.UTC).toEpochSecond())
                put("EndTime", time.endExclusive.atStartOfDay(ZoneOffset.UTC).toEpochSecond())
                put("StartTimezone", "UTC"); put("EndTimezone", "UTC"); put("FullDay", 1)
            }
        }
    }

    private fun timedMetadata(time: EventTime.Timed): JsonObject {
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
