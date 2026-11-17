// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class OccurrenceEditCandidateTest {
    private val start = Instant.parse("2030-04-01T10:00:00Z")
    private val time = EventTime.Timed(start, start.plusSeconds(3600), ZoneId.of("Europe/Paris"))
    private val base = RemoteChange.Upsert(EventId("master"), Revision("revision"), EventContent("Master", time,
        details = EventDetails(listOf(EventReminder(5))), recurrence = EventRecurrence("fixture@calendeer.invalid", "FREQ=DAILY;COUNT=5")))
    private val original = RecurrencePoint.Timed(start.plusSeconds(86400))
    private fun replacement() = base.content.copy(title = "Moved occurrence", recurrence = null)

    @Test fun exactOriginalIsIndependentOfMovedDatesAndChangedType() {
        val moved = EventTime.AllDay(LocalDate.of(2030, 5, 1), LocalDate.of(2030, 5, 2))
        val candidate = OccurrenceEditCandidate(base, original, replacement().copy(time = moved))
        assertEquals(base, candidate.master); assertEquals(original, candidate.original)
        assertEquals(moved, candidate.content.time)
        assertEquals(EventRecurrence(base.content.recurrence!!.uid, original = original), candidate.content.recurrence)
        assertEquals(AdapterFormat("calendeer-native-occurrence", 1), candidate.profile)
    }

    @Test fun callerListsAreOwnedAndCannotChangeEitherSnapshot() {
        val reminders = mutableListOf(EventReminder(10))
        val source = base.copy(content = base.content.copy(details = EventDetails(reminders)))
        val candidate = OccurrenceEditCandidate(source, original, replacement().copy(details = EventDetails(reminders)))
        reminders.clear()
        assertEquals(listOf(EventReminder(10)), candidate.master.content.details!!.reminders)
        assertEquals(listOf(EventReminder(10)), candidate.content.details!!.reminders)
        assertThrows(UnsupportedOperationException::class.java) {
            (candidate.content.details!!.reminders as MutableList<EventReminder>).clear()
        }
    }

    @Test fun wrongOriginalKindOutsideRuleAndUnchangedOccurrenceAreRejected() {
        for (slot in listOf(RecurrencePoint.AllDay(LocalDate.of(2030, 4, 2)),
            RecurrencePoint.Timed(start.minusSeconds(86400)), RecurrencePoint.Timed(start.plusSeconds(86400 * 5)),
            RecurrencePoint.Timed(start.plusSeconds(86400 + 1)))) {
            assertThrows(IllegalArgumentException::class.java) { OccurrenceEditCandidate(base, slot, replacement()) }
        }
        val unchanged = base.content.copy(time = time.copy(start = start.plusSeconds(86400), end = time.end.plusSeconds(86400)), recurrence = null)
        assertThrows(IllegalArgumentException::class.java) { OccurrenceEditCandidate(base, original, unchanged) }
    }

    @Test fun boundsApplyToOpaqueMasterIdentifiersInBytes() {
        for (source in listOf(base.copy(eventId = EventId("x".repeat(4097))),
            base.copy(revision = Revision("x".repeat(4097))), base.copy(revision = Revision("é".repeat(2049))))) {
            assertThrows(IllegalArgumentException::class.java) { OccurrenceEditCandidate(source, original, replacement()) }
        }
    }

    @Test fun unknownMasterRulesExclusionsAndChildCancellationCannotEnterTheProfile() {
        val recurrence = base.content.recurrence!!
        for (unsupported in listOf(recurrence.copy(rule = "FREQ=WEEKLY;COUNT=5"),
            recurrence.copy(excludedDates = listOf(original)),
            recurrence.copy(rule = null, original = original))) {
            assertThrows(IllegalArgumentException::class.java) {
                OccurrenceEditCandidate(base.copy(content = base.content.copy(recurrence = unsupported)), original, replacement())
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            OccurrenceEditCandidate(base, original, replacement().copy(recurrence = recurrence.copy(rule = null, original = original, cancelled = true)))
        }
    }
}
