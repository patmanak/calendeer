// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import org.junit.Assert.*
import org.junit.Test

class ProtonCalendarEventColorsTest {
    @Test fun exportedPaletteIsBoundedStableAndMatchesFreshWriteAdmission() {
        val first = ProtonCalendarEventColors.colors()
        assertEquals(20, first.size)
        assertEquals(first, ProtonCalendarEventColors.colors())
        assertEquals(20, first.map { it.key }.toSet().size)
        assertEquals(20, first.map { it.rgb }.toSet().size)
        assertTrue(first.all { ProtonEventColourPolicy.accepts(it.rgb) &&
            it.key == "calendeer-proton-event-v1-" + it.rgb.toString(16).padStart(6, '0') })
    }
    @Test fun callersCannotReplaceOrRemoveTheSharedPalette() {
        val first = ProtonCalendarEventColors.colors()
        try { (first as MutableList).clear(); fail("Palette is immutable") }
        catch (_: UnsupportedOperationException) { }
        assertEquals(20, ProtonCalendarEventColors.colors().size)
        assertEquals(0x8080ff, ProtonCalendarEventColors.colors().first().rgb)
    }
}
