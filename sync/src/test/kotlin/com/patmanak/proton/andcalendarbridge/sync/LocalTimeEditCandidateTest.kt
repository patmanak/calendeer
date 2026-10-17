// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LocalTimeEditCandidateTest {
    private val start = Instant.parse("2026-10-17T09:00:00Z")
    private val time = EventTime.Timed(start, start.plusSeconds(3600), ZoneId.of("Europe/Paris"))
    private val base = RemoteChange.Upsert(EventId("fixture-event"), Revision("fixture-revision"),
        EventContent("Fixture", time, description = "Notes\r\nNext", details = EventDetails()))
    private fun edit(next: EventTime = time.copy(end = start.plusSeconds(7200))) = LocalEditIntent(
        MutationId("fixture-edit"), CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 1),
        AdapterFormat("fixture-observation", 1), base, base.content.copy(time = next))
    private fun refused(action: () -> Unit) {
        try { action(); fail("Expected candidate refusal") } catch (_: IllegalArgumentException) { }
        catch (_: java.nio.charset.CharacterCodingException) { }
    }

    @Test fun movementAndDurationAreFrozenWithoutChangingOtherFields() {
        for (next in listOf(time.copy(end = start.plusSeconds(7200)),
            time.copy(start = start.plusSeconds(60), end = start.plusSeconds(3660)))) {
            val original = edit(next)
            val candidate = LocalTimeEditCandidate(original)
            assertEquals(original, candidate.intent)
            assertNotSame(original, candidate.intent)
            assertEquals("Notes\r\nNext", candidate.intent.content.description)
            assertEquals("LocalTimeEditCandidate(REDACTED)", candidate.toString())
        }
    }

    @Test fun allDayExclusiveEndIsRetainedAndMutableDetailsAreNotAliased() {
        val details = EventDetails(reminders = mutableListOf(), attendees = mutableListOf())
        val day = EventTime.AllDay(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 18))
        val source = base.copy(content = base.content.copy(time = day, details = details))
        val original = edit().copy(base = source, content = source.content.copy(time = day.copy(endExclusive = day.start.plusDays(3))))
        val frozen = LocalTimeEditCandidate(original).intent
        (details.reminders as MutableList<EventReminder>).add(EventReminder(5))
        assertEquals(EventDetails(), frozen.base.content.details)
        assertEquals(EventDetails(), frozen.content.details)
        assertEquals(day.start.plusDays(3), (frozen.content.time as EventTime.AllDay).endExclusive)
        refused { LocalTimeEditCandidate(original) }
    }

    @Test fun combinedTextKindZoneConflictUnknownDetailsAndColorAreRefused() {
        val valid = edit()
        for (invalid in listOf(valid.copy(content = valid.content.copy(title = "Changed")),
            valid.copy(content = valid.content.copy(description = "Changed")),
            valid.copy(content = valid.content.copy(location = "Changed")),
            valid.copy(state = LocalEditState.CONFLICT),
            edit(time.copy(zone = ZoneId.of("UTC"))),
            edit(EventTime.AllDay(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 18))),
            valid.copy(base = base.copy(content = base.content.copy(details = null)), content = valid.content.copy(details = null)),
            valid.copy(base = base.copy(content = base.content.copy(colorRgb = 42)), content = valid.content.copy(colorRgb = 42)))) {
            refused { LocalTimeEditCandidate(invalid) }
        }
        refused { LocalTimeEditCandidate(valid.copy(content = base.content.copy(title = "Only text"))) }
        refused { LocalTextEditPreparation(valid, start, AdapterFormat("fixture-text", 1), "protected") }
    }

    @Test fun precisionBoundsIdentifiersAndTextAreCheckedBeforeRetention() {
        for (next in listOf(time.copy(end = time.end.plusNanos(1)),
            time.copy(start = Instant.parse("1969-12-31T23:59:59Z")),
            time.copy(end = Instant.parse("+10000-01-01T00:00:00Z")))) refused { LocalTimeEditCandidate(edit(next)) }
        for (bad in listOf("é".repeat(2049), "\uD800"))
            refused { LocalTimeEditCandidate(edit().copy(id = MutationId(bad))) }
        for (bad in listOf("\u0000", "\uD800", "é".repeat(32769))) {
            val original = base.copy(content = base.content.copy(title = bad))
            refused { LocalTimeEditCandidate(edit().copy(base = original, content = original.content.copy(time = edit().content.time))) }
        }
    }
}
