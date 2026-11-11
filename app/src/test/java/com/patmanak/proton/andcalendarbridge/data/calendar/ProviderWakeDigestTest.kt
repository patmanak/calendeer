// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import org.junit.Assert.*
import org.junit.Test

class ProviderWakeDigestTest {
    private fun hash(vararg values: String) = ProviderWakeDigest().also { digest -> values.forEach(digest::text) }.finish()
    @Test fun contentChangesAndFieldBoundariesAreDistinguished() {
        assertNotEquals(hash("ab", "c"), hash("a", "bc"))
        assertNotEquals(hash("same", "before"), hash("same", "after"))
        assertNotEquals(hash("null"), hash(""))
        assertEquals(hash("stable", "contents"), hash("stable", "contents"))
        assertTrue(hash("private fixture").matches(Regex("[0-9a-f]{64}")))
    }
    @Test fun oversizedOrTooManyRowsFailInsteadOfCachingTruncatedContents() {
        val rows = ProviderWakeDigest(maxRows = 1); rows.row()
        assertTrue(runCatching { rows.row() }.isFailure)
        val bytes = ProviderWakeDigest(maxBytes = 5); bytes.text("a")
        assertTrue(runCatching { bytes.text("b") }.isFailure)
    }
}
