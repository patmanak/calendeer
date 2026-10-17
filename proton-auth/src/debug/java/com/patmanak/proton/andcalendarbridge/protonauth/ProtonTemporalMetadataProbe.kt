// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Closed observations relative to an explicitly enrolled synthetic time, never raw API data. */
enum class TemporalApiZoneShape { UTC, Z, EXPECTED, EMPTY, OTHER }
data class TemporalMetadataShape(val fullDay: Long, val startDeltaSeconds: Long?, val endDeltaSeconds: Long?,
    val startZone: TemporalApiZoneShape, val endZone: TemporalApiZoneShape)

internal fun temporalMetadataShape(row: JsonObject, expected: EventTime): TemporalMetadataShape {
    val bounds = when (expected) {
        is EventTime.AllDay -> expected.start.atStartOfDay(ZoneOffset.UTC).toEpochSecond() to
            expected.endExclusive.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        is EventTime.Timed -> expected.start.epochSecond to expected.end.epochSecond
    }
    val zone = (expected as? EventTime.Timed)?.zone?.id ?: "UTC"
    fun shape(name: String): TemporalApiZoneShape = when (val value = row.string(name)) {
        "UTC" -> TemporalApiZoneShape.UTC
        "Z" -> TemporalApiZoneShape.Z
        zone -> TemporalApiZoneShape.EXPECTED
        "" -> TemporalApiZoneShape.EMPTY
        else -> TemporalApiZoneShape.OTHER
    }
    fun delta(name: String, wanted: Long): Long? = Math.subtractExact(row.number(name), wanted).takeIf { it in -86_400..86_400 }
    return TemporalMetadataShape(row.number("FullDay").also { require(it in 0..1) },
        delta("StartTime", bounds.first), delta("EndTime", bounds.second), shape("StartTimezone"), shape("EndTimezone"))
}

/** Debug-only authenticated GET. Full identity/session checks and the shared logout mutex apply.
 * Reflection drift fails closed; no readback claim, dispatch or raw payload leaves this helper.
 */
object ProtonTemporalMetadataProbe {
    suspend fun inspect(context: Context, scope: CalendarScope, event: EventId, expected: EventTime,
        expectedIdentityHash: String): TemporalMetadataShape? = withContext(Dispatchers.IO) {
        try {
            if (!expectedIdentityHash.matches(Regex("[a-f0-9]{64}"))) return@withContext null
            val port = ProtonAuthentication.localEditPreparations(context)
            val lock = field(port, "lock") as Mutex
            lock.withLock {
                if (field(port, "authenticated") != true) return@withLock null
                val graph = field(port, "graph") as ProtonAuthenticationGraph
                val adapter = graph.localEdits as? ProtonLocalTitlePreparationAdapter ?: return@withLock null
                val sessions = field(adapter, "sessions") as CatalogSessionSource
                val session = sessions.current()?.takeIf { it.userId.id == scope.account.value } ?: return@withLock null
                if (!graph.matchesIdentityHash(scope.account, expectedIdentityHash) || sessions.current() != session) return@withLock null
                val originals = field(adapter, "originals") as LocalTitleEventRemote
                val row = originals.event(session, scope, event)
                if (sessions.current() != session || row.string("ID") != event.value ||
                    row.string("CalendarID") != scope.calendar.value) return@withLock null
                temporalMetadataShape(row, expected)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
    }
    private fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(value)
}
