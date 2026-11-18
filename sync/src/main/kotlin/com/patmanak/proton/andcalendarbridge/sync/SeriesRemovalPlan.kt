// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.util.Collections

sealed interface SeriesRemovalTarget {
    data object Entire : SeriesRemovalTarget
    class Single(val original: RecurrencePoint) : SeriesRemovalTarget
}

/** Offline description of personal finite-series operations. Carries no network or admission capability. */
class SeriesRemovalPlan internal constructor(
    deletions: List<Pair<EventId, Revision>>,
    val updatedMaster: RemoteChange.Upsert?,
) {
    val deletions: List<Pair<EventId, Revision>> = Collections.unmodifiableList(deletions.toList())
    override fun toString() = "SeriesRemovalPlan(REDACTED)"
}

/**
 * The adapter must provide a complete, authenticated UID enumeration for the same calendar, and
 * independently verified rows. Recheck the frozen revisions and membership before any submission.
 * A master update holds the revision to replace. An already excluded slot needs no master update.
 */
fun seriesRemovalPlan(expectedMaster: RemoteChange.Upsert, enumeratedIds: List<EventId>,
    verifiedRows: List<RemoteChange.Upsert>, target: SeriesRemovalTarget): SeriesRemovalPlan? = try {
    val ids = enumeratedIds.toList()
    val rows = verifiedRows.toList()
    require(ids.size in 1..367 && ids.size == rows.size && ids.toSet().size == ids.size)
    require(rows.map { it.eventId }.toSet() == ids.toSet())
    val master = requireNotNull(rows.singleOrNull { it.eventId == expectedMaster.eventId })
    require(master == expectedMaster)
    val content = freezePersonalEditContent(master.content)
    val recurrence = requireNotNull(content.recurrence)
    require(recurrence.isMaster && recurrence.additionalDates.isEmpty())
    val rule = CreateSeriesRule.parse(requireNotNull(recurrence.rule))
    require(rule.supports(content.time))
    val slots = (0 until rule.count).map { index ->
        val days = index.toLong() * rule.interval
        when (val time = content.time) {
            is EventTime.AllDay -> RecurrencePoint.AllDay(time.start.plusDays(days))
            is EventTime.Timed -> RecurrencePoint.Timed(time.start.atZone(time.zone).plusDays(days).toInstant())
        }
    }
    val excluded = recurrence.excludedDates.toList()
    require(excluded.size == excluded.distinct().size && slots.containsAll(excluded))
    val children = rows.filter { it.eventId != master.eventId }
    val bySlot = children.associateBy { child ->
        val exception = requireNotNull(freezePersonalEditContent(child.content).recurrence)
        require(exception.uid == recurrence.uid && !exception.cancelled && exception.rule == null &&
            exception.additionalDates.isEmpty() && exception.excludedDates.isEmpty())
        requireNotNull(exception.original).also { require(it in slots) }
    }
    require(bySlot.size == children.size)
    fun RemoteChange.Upsert.identity() = eventId to revision
    when (target) {
        SeriesRemovalTarget.Entire -> SeriesRemovalPlan(
            slots.mapNotNull { bySlot[it]?.identity() } + master.identity(), null)
        is SeriesRemovalTarget.Single -> {
            val slot = target.original
            require(slot in slots)
            val updated = if (slot in excluded) null else {
                val dates = Collections.unmodifiableList(slots.filter { it in excluded || it == slot })
                master.copy(content = content.copy(recurrence = EventRecurrence(recurrence.uid,
                    recurrence.rule, excludedDates = dates)))
            }
            SeriesRemovalPlan(listOfNotNull(bySlot[slot]?.identity()), updated)
        }
    }
} catch (_: IllegalArgumentException) {
    null
} catch (_: java.nio.charset.CharacterCodingException) {
    null
}
