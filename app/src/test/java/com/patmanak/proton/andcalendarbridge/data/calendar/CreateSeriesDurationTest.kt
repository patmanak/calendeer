// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import org.junit.Assert.*
import org.junit.Test

class CreateSeriesDurationTest {
    @Test fun qualifiedDurationsKeepTheExclusiveEndWithNoNativeRewrite() {
        assertEquals(3_601_000L, seriesCreateEnd(1_000, "PT3600S", false))
        assertEquals(5_401_000L, seriesCreateEnd(1_000, "PT1H30M", false))
        assertEquals(172_800_000L, seriesCreateEnd(0, "P2D", true))
        assertEquals(31_622_400_000L, seriesCreateEnd(0, "P366D", true))
    }
    @Test fun unknownNegativeFractionalOversizedAndOverflowingDurationsStayUnsupported() {
        for (raw in listOf("PT0S", "P0D", "-PT1H", "PT0.5S", "P1W", "P1M", "P367D", "PT1Hgarbage", "P", "P1DT", "P999999999999999999999D"))
            assertNull(raw, seriesCreateEnd(0, raw, false))
        assertNull(seriesCreateEnd(0, "PT86400S", true))
        assertNull(seriesCreateEnd(0, "P1D", false)) // Nominal days are not elapsed hours across DST.
        assertNull(seriesCreateEnd(0, "P1DT1H", false))
        assertNull(seriesCreateEnd(Long.MAX_VALUE, "PT1S", false))
    }
}
