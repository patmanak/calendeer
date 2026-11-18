// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.ZoneOffset
import kotlinx.serialization.json.*

/** Full signed finite master; the broader import decoder alone is not sufficient evidence. */
internal fun seriesFamilyVerifier(decoder: ProtonEventDecryptor) = SeriesFamilyEventVerifier { session, calendar, bootstrap, row, uid ->
    try {
        currentCoroutineContext().ensureActive()
        require(row.string("CalendarID") == calendar && row.string("UID") == uid)
        ProtonPersonalEventWire.qualify(JsonObject(row + ("RRule" to JsonNull)))
        val decoded = decoder.decodePage(session.userId, calendar, bootstrap, listOf(row), uid)
        currentCoroutineContext().ensureActive()
        require(decoded.deferred.isEmpty())
        val result = requireNotNull(decoded.changes.singleOrNull())
        val master = result.copy(content = SeriesEditProfile.freezeMaster(result.content))
        require(master.eventId.value == row.string("ID"))
        ProtonSeriesEventUpdate.qualify(row, master.content)
        master
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: IllegalArgumentException) { currentCoroutineContext().ensureActive(); null }
}

/** Verify the signed calendar data and the visible recurrence metadata before planning a removal. */
internal fun seriesRemovalVerifier(decoder: ProtonEventDecryptor) = SeriesFamilyEventVerifier { session, calendar, bootstrap, row, uid ->
    require(row.string("CalendarID") == calendar && row.string("UID") == uid)
    val result = decoder.decodePage(session.userId, calendar, bootstrap, listOf(row), uid)
    currentCoroutineContext().ensureActive()
    require(result.deferred.isEmpty())
    result.changes.singleOrNull()?.also {
        require(it.eventId.value == row.string("ID"))
        qualifySeriesRemovalRow(row, it.content)
    }
}

internal fun qualifySeriesRemovalRow(row: JsonObject, content: EventContent) {
    val recurrence = requireNotNull(content.recurrence)
    require(row.string("UID") == recurrence.uid && !recurrence.cancelled && recurrence.additionalDates.isEmpty())
    fun epoch(point: RecurrencePoint) = when (point) {
        is RecurrencePoint.AllDay -> point.date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        is RecurrencePoint.Timed -> point.instant.epochSecond.also { require(point.instant.nano == 0) }
    }
    val excluded = row.array("Exdates").map {
        val value = it as? JsonPrimitive
        require(value != null && !value.isString)
        requireNotNull(value.longOrNull)
    }
    require(excluded.sorted() == recurrence.excludedDates.map(::epoch).sorted() && excluded.distinct().size == excluded.size)
    val rawSlot = row["RecurrenceID"]
    val slot = recurrence.original
    if (slot == null) {
        require(rawSlot == null || rawSlot == JsonNull)
        require(row["IsPersonalSingleEdit"] == JsonPrimitive(false))
        require(CreateSeriesRule.parse(row.string("RRule")) == CreateSeriesRule.parse(requireNotNull(recurrence.rule)))
    } else {
        require(rawSlot == JsonPrimitive(epoch(slot)) && (row["RRule"] == null || row["RRule"] == JsonNull))
        require(row["IsPersonalSingleEdit"] in setOf(JsonPrimitive(false), JsonPrimitive(true)))
    }
    require(ProtonPersonalEventUpdate.metadata(content.time).all { (key, value) -> row[key] == value })
    ProtonPersonalEventWire.qualify(JsonObject(row + mapOf("RRule" to JsonNull, "Exdates" to JsonArray(emptyList()),
        "RecurrenceID" to JsonNull, "IsPersonalSingleEdit" to JsonPrimitive(false))))
}
