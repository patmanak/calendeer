// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSharedTextProperty.*
import org.junit.Assert.*
import org.junit.Test

class ProtonSharedTextObservationTest {
    private val uid = "observation-fixture@calendeer.invalid"
    private fun calendar(body: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:$uid\r\n" +
        "DTSTAMP:20261003T100000Z\r\n${body}END:VEVENT\r\nEND:VCALENDAR\r\n"
    private fun parts(body: String = "SUMMARY:Original\r\n", clear: String = "X-OPAQUE:clear\r\n") =
        ProtonEventTextParts(calendar(clear), calendar(body))
    private fun observe(original: ProtonEventTextParts, vararg fields: ProtonSharedTextProperty) =
        ProtonSharedTextPatcher.observe(original, fields.toSet(), uid)
    private fun refused(original: ProtonEventTextParts, selected: Set<ProtonSharedTextProperty>, expectedUid: String = uid) {
        val clear = original.clearShared; val secret = original.encryptedShared
        val failure = assertThrows(ProtonTextPatchException::class.java) {
            ProtonSharedTextPatcher.observe(original, selected, expectedUid)
        }
        assertEquals("Shared text patch unavailable: UNSUPPORTED", failure.message)
        assertNull(failure.cause)
        assertFalse(failure.toString().contains(uid))
        assertEquals(clear, original.clearShared); assertEquals(secret, original.encryptedShared)
    }

    @Test fun absentAndPresentEmptyRemainExplicitDistinctMapEntries() {
        val original = parts("SUMMARY:Original\r\nDESCRIPTION:\r\n")
        val result = observe(original, SUMMARY, DESCRIPTION, LOCATION)
        assertEquals(setOf(SUMMARY, DESCRIPTION, LOCATION), result.keys)
        assertEquals("Original", result[SUMMARY]); assertEquals("", result[DESCRIPTION])
        assertTrue(result.containsKey(LOCATION)); assertNull(result[LOCATION])
        assertEquals(setOf(LOCATION), observe(original, LOCATION).keys)
    }
    @Test fun foldedEscapedUnicodeTextIsDecodedWithoutRewritingRawPartitions() {
        val body = "summary:Orig\r\n\tinal\r\nDESCRIPTION:Ligne\\n\r\n Suite\\,é\\;😀\r\nLOCATION:Room\\\\Back\r\n"
        val original = parts(body)
        val result = observe(original, DESCRIPTION, LOCATION)
        assertEquals("Ligne\nSuite,é;😀", result[DESCRIPTION])
        assertEquals("Room\\Back", result[LOCATION])
        assertEquals(calendar(body), original.encryptedShared)
        assertEquals(calendar("X-OPAQUE:clear\r\n"), original.clearShared)
    }
    @Test fun unselectedOpaqueDuplicatesParametersAndNestedNamesStayUninterpreted() {
        val nested = "DESCRIPTION;LANGUAGE=fr:First\r\nDESCRIPTION:Second\r\n" +
            "BEGIN:X-OPAQUE\r\nLOCATION:Nested\r\nSUMMARY:Nested\r\nEND:X-OPAQUE\r\n"
        val original = parts("SUMMARY:Original\r\n$nested", "DESCRIPTION:clear-only\r\n")
        assertEquals(mapOf(SUMMARY to "Original", LOCATION to null), observe(original, SUMMARY, LOCATION))
        assertEquals(calendar("SUMMARY:Original\r\n$nested"), original.encryptedShared)
    }
    @Test fun selectedDuplicatesParametersAndClearAmbiguityAreRejected() {
        for (field in listOf(DESCRIPTION, LOCATION)) {
            for (body in listOf("${field.name}:Same\r\n${field.name}:Same\r\n", "${field.name};LANGUAGE=en:Same\r\n"))
                refused(parts("SUMMARY:Original\r\n$body"), setOf(field))
            refused(parts(clear = "${field.name}:\r\n"), setOf(field))
            refused(parts("SUMMARY:Original\r\n${field.name}:Same\r\n", "${field.name}:Same\r\n"), setOf(field))
        }
    }
    @Test fun summaryAndBothSignedIdentitiesAreMandatoryForOptionalObservation() {
        for (body in listOf("", "SUMMARY:One\r\nSUMMARY:One\r\n", "SUMMARY;LANGUAGE=en:One\r\n"))
            refused(parts(body), setOf(DESCRIPTION))
        val original = parts()
        refused(original, setOf(LOCATION), "other-fixture")
        for (secret in listOf(original.encryptedShared.replace(uid, "other-fixture"),
            original.encryptedShared.replace("20261003T100000Z", "20261003T100001Z"),
            original.encryptedShared.replace("UID:$uid\r\n", ""),
            original.encryptedShared.replace("DTSTAMP:20261003T100000Z\r\n", "")))
            refused(ProtonEventTextParts(original.clearShared, secret), setOf(LOCATION))
        refused(original, emptySet())
    }
    @Test fun selectedTextBudgetControlsAndInvalidUnicodeAreRejected() {
        val limit = "é".repeat(32_768)
        assertEquals(limit, observe(parts("SUMMARY:Original\r\nDESCRIPTION:$limit\r\n"), DESCRIPTION)[DESCRIPTION])
        refused(parts("SUMMARY:Original\r\nDESCRIPTION:${limit}x\r\n"), setOf(DESCRIPTION))
        refused(parts("SUMMARY:Original\r\nLOCATION:bad\u0000value\r\n"), setOf(LOCATION))
        refused(parts("SUMMARY:Original\r\nDESCRIPTION:bad\uD800value\r\n"), setOf(DESCRIPTION))
    }
    @Test fun framingAndCombinedPartitionBudgetsRemainBounded() {
        val original = parts()
        refused(ProtonEventTextParts(original.clearShared, original.encryptedShared.replace("\r\n", "\n")), setOf(LOCATION))
        refused(parts("SUMMARY:Original\r\nX-RAW:${"x".repeat(1_048_576)}\r\n"), setOf(LOCATION))
        refused(parts("SUMMARY:Original\r\n" + "X-RAW:x\r\n".repeat(20_000)), setOf(LOCATION))
        refused(parts("SUMMARY:Original\r\n" + "BEGIN:X-OPAQUE\r\n".repeat(16) + "END:X-OPAQUE\r\n".repeat(16)), setOf(LOCATION))
    }
    @Test fun observationDoesNotCertifyChangedTextMetadataPolicy() {
        val original = parts("SUMMARY:Original\r\nLOCATION:Old\r\nRRULE:FREQ=DAILY\r\n")
        assertEquals("Old", observe(original, LOCATION)[LOCATION])
        assertThrows(ProtonTextPatchException::class.java) {
            ProtonSharedTextPatcher.patchAt(original, mapOf(LOCATION to ProtonSharedTextChange("Old", "New")),
                uid, java.time.Instant.parse("2026-10-03T11:00:00Z"))
        }
    }
    @Test fun resultMutationAndSelectionMutationCannotAffectLaterObservations() {
        val original = parts("SUMMARY:Original\r\nDESCRIPTION:Notes\r\n")
        val selected = mutableSetOf(DESCRIPTION)
        val first = ProtonSharedTextPatcher.observe(original, selected, uid)
        selected.clear()
        assertEquals(mapOf(DESCRIPTION to "Notes"), first)
        @Suppress("UNCHECKED_CAST")
        (first as? MutableMap<ProtonSharedTextProperty, String?>)?.let {
            try { it[DESCRIPTION] = "Changed" } catch (_: UnsupportedOperationException) { }
        }
        assertEquals(mapOf(DESCRIPTION to "Notes"), observe(original, DESCRIPTION))
    }
}
