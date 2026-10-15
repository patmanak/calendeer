// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SystemTextEditProfileTest {
    @Test fun descriptionLocationRemovalAndCombinedTitleAreTextConsentWork() {
        val f = TextOperationsFixture()
        for (content in listOf(f.base.copy(description = "Changed notes"), f.base.copy(location = "Changed room"),
            f.base.copy(description = ""), f.base.copy(location = ""), f.changed))
            assertTrue(SystemTextEditProfile.accepts(f.intent.copy(content = content)))
    }

    @Test fun titleOnlyAndLineEndingOnlyDifferencesNeverBorrowTextConsent() {
        val f = TextOperationsFixture()
        for (content in listOf(f.base.copy(title = "Changed title"),
            f.base.copy(description = f.base.description.replace("\r\n", "\n")),
            f.base.copy(title = "Changed title", location = f.base.location.replace("\r\n", "\n"))))
            assertFalse(SystemTextEditProfile.accepts(f.intent.copy(content = content)))
    }

    @Test fun timedAndAllDayKeepExactDatesButUnknownDetailsAndCustomColorsAreExcluded() {
        val f = TextOperationsFixture()
        val allDay = EventTime.AllDay(LocalDate.parse("2033-03-26"), LocalDate.parse("2033-03-29"))
        val intent = f.intent.copy(base = f.intent.base.copy(content = f.base.copy(time = allDay)),
            content = f.changed.copy(time = allDay))
        assertTrue(SystemTextEditProfile.accepts(intent))
        assertFalse(SystemTextEditProfile.accepts(intent.copy(content = intent.content.copy(
            time = allDay.copy(endExclusive = allDay.endExclusive.plusDays(1))))))
        for (base in listOf(f.base.copy(details = null), f.base.copy(colorRgb = 0x334455))) {
            assertFalse(SystemTextEditProfile.accepts(f.intent.copy(base = f.intent.base.copy(content = base),
                content = base.copy(description = "Changed notes"))))
        }
        val reminders = mutableListOf<EventReminder>()
        val aliased = f.intent.copy(base = f.intent.base.copy(content = f.base.copy(details = EventDetails(reminders))),
            content = f.changed.copy(details = EventDetails(reminders)))
        reminders += EventReminder(15)
        assertFalse(SystemTextEditProfile.accepts(aliased))
        assertFalse(SystemTextEditProfile.accepts(f.intent.copy(state = LocalEditState.CONFLICT)))
    }

    @Test fun ownershipSnapshotFreezesEmptyCollectionsAndRejectsDifferentCapsuleOwner() {
        val f = TextOperationsFixture(); val reminders = mutableListOf<EventReminder>()
        val intent = f.intent.copy(base = f.intent.base.copy(content = f.base.copy(details = EventDetails(reminders))),
            content = f.changed.copy(details = EventDetails(reminders)))
        val snapshot = TextLocalOwnerSnapshot(intent, null)
        reminders += EventReminder(5)
        assertEquals(EventDetails(), snapshot.intent.content.details)
        assertEquals(EventDetails(), snapshot.intent.base.content.details)
        try { TextLocalOwnerSnapshot(f.intent.copy(id = MutationId("different-owner")), f.preparation); fail("Owner substitution") }
        catch (_: IllegalArgumentException) { }
    }
}
