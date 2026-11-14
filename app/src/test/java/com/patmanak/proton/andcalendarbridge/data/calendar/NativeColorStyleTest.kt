// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import org.junit.Assert.*
import org.junit.Test

class NativeColorStyleTest {
    private val proof = "calendeer-projection-receipt-v1:11111111-2222-3333-4444-555555555555"
    @Test fun legacyProofsRemainByteIdenticalIncludingNull() {
        for (raw in listOf(null, proof, "calendeer-finite-daily-dates-v1-receipt-v1:fixture")) {
            val parsed = NativeColorStyle.read(raw)!!
            assertFalse(parsed.masked); assertEquals(raw, parsed.proof); assertEquals(raw, parsed.marker())
        }
    }
    @Test fun maskedColorIsReversibleWithoutNetworkAndKeepsTheOriginalProof() {
        for (color in listOf(null, 0, 0x123456, 0xffffff)) for (raw in listOf(null, proof)) {
            val style = NativeColorStyle(raw, true, color)
            assertEquals(style, NativeColorStyle.read(style.marker()))
            assertEquals(raw, NativeColorStyle.read(style.marker())!!.copy(masked = false).marker())
        }
    }
    @Test fun finiteProofRemainsAtTheBeginningForTransactionalChildBarriers() {
        val finite = "calendeer-finite-daily-dates-v1-receipt-v1:fixture"
        assertTrue(NativeColorStyle(finite, true, 0x654321).marker()!!.startsWith("calendeer-finite-"))
    }
    @Test fun unknownMalformedAndNestedStylesFailClosed() {
        for (raw in listOf("$proof|calendeer-color-v2:123456", "$proof|calendeer-color-v1:12345",
            "$proof|calendeer-color-v1:ABCDEF", "$proof|calendeer-color-v1:-1",
            "$proof|calendeer-color-v1:123456|calendeer-color-v1:none")) assertNull(NativeColorStyle.read(raw))
    }
    @Test fun maskedNullPreservesTheBaseAndExplicitNativeColorsAreUnsupported() {
        val style = NativeColorStyle(proof, true, 0x123456)
        assertTrue(style.accepts(0x123456, null)); assertEquals(0x123456, style.logicalColor(null))
        assertFalse(style.accepts(0x654321, null))
        assertFalse(style.accepts(null, null))
        assertFalse(style.accepts(0x123456, 0)); assertFalse(style.accepts(0x123456, 0xff654321.toInt()))
    }
    @Test fun unmaskedNativeFallbackAndBlackKeepTheirExistingMeaning() {
        val style = NativeColorStyle(proof, false, null)
        assertNull(style.logicalColor(null)); assertNull(style.logicalColor(0))
        assertEquals(0, style.logicalColor(0xff000000.toInt()))
        assertEquals(0x123456, style.logicalColor(0xff123456.toInt()))
    }
    @Test fun displayChangeRoundTripKeepsBlackFallbackAndProofWithoutAccumulatingSuffixes() {
        for (native in listOf(null, 0, 0xff000000.toInt(), 0xff123456.toInt())) {
            val original = NativeColorStyle(proof, false, null)
            val hidden = original.forDisplay(EventColorMode.CALENDAR, native)!!
            assertTrue(hidden.masked); assertEquals(original.logicalColor(native), hidden.colorRgb)
            val restored = hidden.forDisplay(EventColorMode.EVENT, null)!!
            assertFalse(restored.masked); assertEquals(proof, restored.marker())
            assertEquals(hidden, hidden.forDisplay(EventColorMode.CALENDAR, null))
            assertEquals(hidden, NativeColorStyle.read(hidden.marker()))
        }
    }
    @Test fun displayRewriteNeverConsumesAnExplicitColorOnAMaskedRowOrUnknownAlpha() {
        for (mode in EventColorMode.entries) {
            assertNull(NativeColorStyle(proof, true, 0x123456).forDisplay(mode, 0xff654321.toInt()))
            assertNull(NativeColorStyle(proof, true, null).forDisplay(mode, 0))
            assertNull(NativeColorStyle(proof, false, null).forDisplay(mode, 0x123456))
        }
    }
}
