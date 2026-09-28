package com.patmanak.proton.andcalendarbridge.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePolicyTest {
    @Test fun systemModeFollowsBothSystemAppearances() {
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, false))
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, true))
    }

    @Test fun explicitChoiceOverridesSystemAppearance() {
        for (systemDark in listOf(false, true)) {
            assertFalse(resolveDarkTheme(ThemeMode.LIGHT, systemDark))
            assertTrue(resolveDarkTheme(ThemeMode.DARK, systemDark))
        }
    }
}
