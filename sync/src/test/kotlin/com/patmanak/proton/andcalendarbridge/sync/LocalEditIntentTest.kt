// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class LocalEditIntentTest {
    private val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("opaque-base"),
        EventContent("Synthetic", EventTime.AllDay(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)), colorRgb = 0x123456))
    private fun intent(base: RemoteChange.Upsert = this.base, local: EventContent = base.content.copy(title = "Edited")) =
        LocalEditIntent(MutationId("synthetic-intent"), CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 0),
            AdapterFormat("synthetic-edit", 1), base, local)
    private fun refused(block: () -> Unit) {
        try { block(); fail("Expected unsupported edit refusal") } catch (_: IllegalArgumentException) { }
    }
    @Test fun simpleEditRetainsExactBaseAndStartsPausedWithRedactedDiagnostics() {
        val edit = intent()
        assertEquals(base, edit.base); assertEquals(LocalEditState.PAUSED, edit.state)
        assertFalse(edit.toString().contains("synthetic")); assertFalse(edit.toString().contains("Edited"))
        assertEquals(edit.content, edit.copy(state = LocalEditState.CONFLICT).content)
    }
    @Test fun unchangedAndColorEditsAreRejected() {
        refused { intent(local = base.content) }
        refused { intent(local = base.content.copy(title = "Edited", colorRgb = null)) }
        refused { intent(local = base.content.copy(title = "Edited", colorRgb = 0xFFFFFF)) }
    }
    @Test fun unsupportedDetailsAndRecurrenceAreNotDiscarded() {
        refused { intent(base = base.copy(content = base.content.copy(details = EventDetails(reminders = listOf(EventReminder(10)))))) }
        refused { intent(local = base.content.copy(title = "Edited", details = EventDetails())) }
        val recurrence = EventRecurrence("synthetic-uid", "FREQ=DAILY")
        refused { intent(base = base.copy(content = base.content.copy(recurrence = recurrence))) }
        refused { intent(local = base.content.copy(title = "Edited", recurrence = recurrence)) }
    }
    @Test fun knownEmptyDetailsArePreservedExactlyInsteadOfBecomingUnknown() {
        val known = base.copy(content = base.content.copy(details = EventDetails()))
        assertEquals(EventDetails(), intent(base = known).content.details)
        refused { intent(base = known, local = known.content.copy(title = "Edited", details = null)) }
    }
}
