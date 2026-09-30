// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LocalDeleteIntentTest {
    private val scope = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 0)
    private val format = AdapterFormat("fixture", 1)
    private val base = RemoteChange.Upsert(EventId("fixture-event"), Revision("opaque-baseline"), EventContent(
        "Fixture", EventTime.AllDay(LocalDate.of(2030, 10, 1), LocalDate.of(2030, 10, 2)),
        colorRgb = 0x112233, details = EventDetails()))
    private fun intent(change: RemoteChange.Upsert = base, content: EventContent = change.content) =
        LocalDeleteIntent(MutationId("fixture-deletion"), scope, format, change, content)
    private fun refused(block: () -> Unit) {
        try { block(); fail("Unsupported deletion snapshot must be refused") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun deletionDoesNotRequireChangingTheSupportedContent() {
        val deletion = intent()
        assertEquals(base, deletion.base); assertEquals(base.content, deletion.content)
        assertEquals(LocalEditState.PAUSED, deletion.state)
    }
    @Test fun editsMadeBeforeDeletionRemainSeparateFromTheProjectedBaseline() {
        val local = base.content.copy(title = "Local variant", description = "Local description", location = "Local place")
        val deletion = intent(content = local)
        assertEquals(base, deletion.base); assertEquals(local, deletion.content)
        assertEquals(local, deletion.copy(state = LocalEditState.CONFLICT).content)
    }
    @Test fun knownEmptyAndUnknownDetailsArePreservedWithoutUpgradingTheProfile() {
        assertEquals(EventDetails(), intent().content.details)
        val unknown = base.copy(content = base.content.copy(details = null))
        assertNull(intent(unknown).content.details)
        refused { intent(unknown, unknown.content.copy(details = EventDetails())) }
        refused { intent(content = base.content.copy(details = null)) }
    }
    @Test fun recurringOrDetailedEventsCannotBecomePartialLocalDeletionIntents() {
        val recurring = base.copy(content = base.content.copy(recurrence = EventRecurrence("fixture-uid", "FREQ=DAILY")))
        refused { intent(recurring) }
        val detailed = base.copy(content = base.content.copy(details = EventDetails(reminders = listOf(EventReminder(15)))))
        refused { intent(detailed) }
        refused { intent(content = base.content.copy(recurrence = EventRecurrence("fixture-uid", "FREQ=DAILY"))) }
    }
    @Test fun changedColorOrAddedDetailsAreRefused() {
        refused { intent(content = base.content.copy(colorRgb = 0x445566)) }
        refused { intent(content = base.content.copy(details = EventDetails(reminders = listOf(EventReminder(5))))) }
    }
    @Test fun timedSnapshotsRetainAnExplicitTimezoneAndRequireSecondPrecision() {
        val time = EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"), Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris"))
        val timed = base.copy(content = base.content.copy(time = time))
        assertEquals(time, intent(timed).content.time)
        refused { intent(timed.copy(content = timed.content.copy(time = time.copy(start = time.start.plusNanos(1))))) }
        refused { intent(timed, timed.content.copy(time = time.copy(end = time.end.plusNanos(1)))) }
    }
    @Test fun diagnosticsNeverExposeTheScopedEventOrLocalContent() {
        assertEquals("LocalDeleteIntent(REDACTED)", intent().toString())
    }
}
