// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class SeriesRemovalPlanTest {
    private val date = LocalDate.of(2030, 4, 1)
    private val time = EventTime.AllDay(date, date.plusDays(1))
    private fun day(offset: Long) = RecurrencePoint.AllDay(date.plusDays(offset))
    private val master = RemoteChange.Upsert(EventId("master"), Revision("revision"),
        EventContent("Family", time, details = EventDetails(),
            recurrence = EventRecurrence("family", "FREQ=DAILY;COUNT=5", excludedDates = listOf(day(0)))))
    private val child = RemoteChange.Upsert(EventId("child"), Revision("child-revision"),
        master.content.copy(time = EventTime.AllDay(date.plusDays(7), date.plusDays(8)),
            recurrence = EventRecurrence("family", original = day(2))))
    private val rows = listOf(child, master)
    private val ids = rows.map { it.eventId }
    private fun plan(slot: RecurrencePoint? = day(2), family: List<RemoteChange.Upsert> = rows,
        observedIds: List<EventId> = ids) =
        seriesRemovalPlan(master, observedIds, family, slot?.let(SeriesRemovalTarget::Single) ?: SeriesRemovalTarget.Entire)

    @Test fun movedExceptionIsDeletedAndItsOriginalSlotIsExcludedWithoutLosingEarlierExclusions() {
        val plan = requireNotNull(plan())
        assertEquals(listOf(child.eventId to child.revision, master.eventId to master.revision), plan.family)
        assertEquals(listOf(child.eventId to child.revision), plan.deletions)
        assertEquals(listOf(day(0), day(2)), plan.updatedMaster?.content?.recurrence?.excludedDates)
        assertEquals(master.revision, plan.updatedMaster?.revision)
        val excluded = requireNotNull(plan(day(0)))
        assertTrue(excluded.deletions.isEmpty())
        assertNull(excluded.updatedMaster)
    }

    @Test fun entireSeriesRequiresEveryUniqueIdRevisionAndPersonalRow() {
        assertEquals(listOf(child.eventId to child.revision, master.eventId to master.revision),
            requireNotNull(plan(null)).deletions)
        assertNull(plan(null, listOf(master)))
        assertNull(plan(null, rows, listOf(master.eventId, master.eventId)))
        assertNull(plan(null, listOf(child, master.copy(revision = Revision("new")))))
        assertNull(plan(null, listOf(child.copy(content = child.content.copy(details = null)), master)))
        assertNull(plan(day(7)))
        val duplicate = child.copy(eventId = EventId("duplicate"))
        assertNull(plan(day(2), rows + duplicate, ids + duplicate.eventId))
    }

    @Test fun malformedSignedTextAndMutatedDetachedRecurrenceAreRefused() {
        val malformed = child.copy(content = child.content.copy(title = "\uD800"))
        assertNull(plan(family = listOf(master, malformed)))
        val additional = mutableListOf<RecurrencePoint>()
        val recurrence = EventRecurrence("family", original = day(2), additionalDates = additional)
        additional += day(4)
        val changed = child.copy(content = child.content.copy(recurrence = recurrence))
        assertNull(plan(family = listOf(master, changed)))
    }

    @Test fun originalTimedSlotFollowsLocalTimeOverSpringClockChange() {
        val zone = ZoneId.of("Europe/Paris")
        val start = Instant.parse("2030-03-30T09:00:00Z")
        val timed = master.copy(content = master.content.copy(
            time = EventTime.Timed(start, start.plusSeconds(1800), zone),
            recurrence = EventRecurrence("family", "FREQ=DAILY;COUNT=3")))
        fun timedPlan(slot: Instant) = seriesRemovalPlan(timed, listOf(timed.eventId), listOf(timed),
            SeriesRemovalTarget.Single(RecurrencePoint.Timed(slot)))
        val next = Instant.parse("2030-03-31T08:00:00Z")
        assertEquals(listOf(RecurrencePoint.Timed(next)), timedPlan(next)?.updatedMaster?.content?.recurrence?.excludedDates)
        assertNull(timedPlan(start.plusSeconds(86400)))
    }
}
