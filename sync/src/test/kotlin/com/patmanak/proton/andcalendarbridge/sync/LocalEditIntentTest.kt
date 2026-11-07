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
    @Test fun unchangedPersonalRemindersAndColorArePreservedWithoutWideningLegacyPreparations() {
        val details = EventDetails(reminders = listOf(EventReminder(10), EventReminder(30)))
        val personal = base.copy(content = base.content.copy(details = details))
        val edit = intent(base = personal)
        assertEquals(personal, edit.base)
        assertEquals(details, edit.content.details)
        assertEquals(0x123456, edit.content.colorRgb)
        assertEquals(edit, PersonalEditCandidate(edit).intent)
        val changedTime = (personal.content.time as EventTime.AllDay).copy(endExclusive = LocalDate.of(2026, 10, 3))
        val timeEdit = intent(personal, personal.content.copy(time = changedTime))
        refused { LocalTimeEditCandidate(timeEdit) }
        val textEdit = intent(personal, personal.content.copy(description = "New notes"))
        refused { LocalTextEditPreparation(textEdit, java.time.Instant.parse("2026-10-01T00:00:00Z"), AdapterFormat("legacy-text", 1), "opaque") }
        refused { LocalEditPreparation.freezeIntent(edit) }
        refused { intent(personal, personal.content.copy(title = "Edited", details = EventDetails())) }
        refused { intent(personal, personal.content.copy(title = "Edited", details = details.copy(reminders = listOf(EventReminder(20))))) }
        refused { intent(personal, personal.content.copy(title = "Edited", details = null)) }
    }
    @Test fun organizersParticipantsMetadataChangesAndRecurrenceAreNotDiscarded() {
        val person = EventPerson("fixture@example.invalid")
        val organizer = EventDetails(organizer = person)
        val attendees = EventDetails(attendees = listOf(EventAttendee(person, AttendanceRole.REQUIRED, AttendanceStatus.ACCEPTED)))
        for (details in listOf(organizer, attendees)) {
            refused { intent(base = base.copy(content = base.content.copy(details = details))) }
            refused { intent(local = base.content.copy(title = "Edited", details = details)) }
        }
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
