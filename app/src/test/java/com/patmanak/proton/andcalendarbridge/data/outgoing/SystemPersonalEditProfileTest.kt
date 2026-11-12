// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.sync.*
import org.junit.Assert.*
import org.junit.Test

class SystemPersonalEditProfileTest {
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
        for (invalid in listOf(fixture.base, fixture.changed.copy(colorRgb = 0x123456))) {
            try { fixture.intent.copy(content = invalid); fail("Invalid intent must be rejected before routing") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
