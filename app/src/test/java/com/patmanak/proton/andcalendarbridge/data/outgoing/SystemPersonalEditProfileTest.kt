// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.sync.*
import org.junit.Assert.*
import org.junit.Test

class SystemPersonalEditProfileTest {
    @Test fun canonicalLineEndingOnlyIntentCannotUseThePersonalRoute() {
        val f = TextOperationsFixture()
        val base = f.base.copy(title = "Before\nTitle")
        val intent = f.intent.copy(base = f.intent.base.copy(content = base), content = base.copy(title = "Before\r\nTitle"))
        PersonalEditCandidate(intent)
        assertFalse("Canonical no-ops need a visible held outcome, not a route which will never accept them",
            SystemPersonalEditProfile.accepts(intent))
    }
    @Test fun retiringCanonicalNoopsDoesNotChangeTheHistoricalNativeReader() {
        val f = TextOperationsFixture()
        val base = f.base.copy(description = "Before\nNotes")
        val intent = f.intent.copy(base = f.intent.base.copy(content = base), content = base.copy(description = "Before\r\nNotes"))
        assertFalse(SystemPersonalEditProfile.accepts(intent))
        assertTrue(SystemPersonalEditProfile.requiresExtendedSnapshot(intent))
    }
    @Test fun simpleTitleTextAndTimeChangesCanUsePersonalV2() {
        val text = TextOperationsFixture()
        val time = TimeOperationsFixture()
        val title = text.intent.copy(content = text.base.copy(title = "Edited title"))
        for (intent in listOf(title, text.intent, time.intent)) {
            PersonalEditCandidate(intent) // Independent domain contract already accepts these changes.
            assertTrue("Eligible simple changes must not be excluded by their old route", SystemPersonalEditProfile.accepts(intent))
            assertFalse("Routing must not replace the legacy native source reader", SystemPersonalEditProfile.requiresExtendedSnapshot(intent))
        }
    }

    @Test fun conflictingAndUnchangedObservationsRemainUnsupported() {
        val fixture = TextOperationsFixture()
        assertFalse(SystemPersonalEditProfile.accepts(fixture.intent.copy(state = LocalEditState.CONFLICT)))
        for (invalid in listOf(fixture.base)) {
            try { fixture.intent.copy(content = invalid); fail("Invalid intent must be rejected before routing") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun metadataOnlyChangesRequireEditableModeAndKeepTheirCompletePersonalRoute() {
        val f = TextOperationsFixture()
        val variants = listOf(f.base.copy(colorRgb = 0x123456),
            f.base.copy(details = EventDetails(listOf(EventReminder(10)))))
        for (content in variants) {
            val edit = f.intent.copy(content = content)
            assertTrue(SystemPersonalEditProfile.accepts(edit))
            assertFalse(com.patmanak.proton.andcalendarbridge.data.importing.PersonalEditConsent.allowed(edit,
                { true }, { true }, { true }, { false }))
            assertTrue(com.patmanak.proton.andcalendarbridge.data.importing.PersonalEditConsent.allowed(edit,
                { false }, { false }, { false }, { true }))
        }
    }
}
