package com.patmanak.proton.andcalendarbridge.sync

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

/** A bounded risk detector, not a recurrence validator or a guarantee of native compatibility. */
object RecurringLocalTimeRisk {
    const val MAX_DAILY_COUNT = 4096

    /** Detect ambiguous starts for every rule, plus gap/fold slots in simple finite daily rules.
     * Other profiles are intentionally not expanded. Exclusions cannot establish safety after a gap.
     */
    fun detected(start: Instant, zone: ZoneId, rule: String?): Boolean {
        if (rule == null || zone.rules.isFixedOffset) return false
        val local = try { start.atZone(zone).toLocalDateTime() } catch (_: DateTimeException) { return true }
        if (zone.rules.getValidOffsets(local).size != 1) return true
        if (rule.length > 4096) return false // Oversized legacy rules are outside the bounded scan.
        val fields = rule.uppercase().split(';').map { it.split('=') }
        if (fields.any { it.size != 2 } || fields.map { it[0] }.distinct().size != fields.size) return false
        val values = fields.associate { it[0] to it[1] }
        if (values.keys.any { it !in setOf("FREQ", "COUNT", "INTERVAL") } || values["FREQ"] != "DAILY") return false
        val count = values["COUNT"]?.toIntOrNull()?.takeIf { it in 1..MAX_DAILY_COUNT } ?: return false
        val interval = (values["INTERVAL"] ?: "1").toIntOrNull()?.takeIf { it in 1..1_000_000 } ?: return false
        return try {
            (1 until count).any { index ->
                // Always derive from the original local time, never from a normalized gap occurrence.
                zone.rules.getValidOffsets(local.plusDays(index.toLong() * interval)).size != 1
            }
        } catch (_: DateTimeException) { true }
    }
}
