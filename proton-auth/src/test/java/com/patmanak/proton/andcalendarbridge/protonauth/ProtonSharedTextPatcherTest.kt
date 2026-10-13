// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import biweekly.Biweekly
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSharedTextProperty.*
import org.junit.Assert.*
import org.junit.Test

class ProtonSharedTextPatcherTest {
    private val uid = "shared-text-fixture@calendeer.invalid"
    private fun calendar(body: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:$uid\r\n" +
        "DTSTAMP:20261003T100000Z\r\n${body}END:VEVENT\r\nEND:VCALENDAR\r\n"
    private fun parts(body: String = "SUMMARY:Original\r\n", clear: String = "X-OPAQUE:clear\r\n") =
        ProtonEventTextParts(calendar(clear), calendar(body))
    private fun change(expected: String?, replacement: String?) = ProtonSharedTextChange(expected, replacement)
    private fun patch(original: ProtonEventTextParts, vararg changes: Pair<ProtonSharedTextProperty, ProtonSharedTextChange>) =
        ProtonSharedTextPatcher.patch(original, linkedMapOf(*changes), uid)
    private fun refused(original: ProtonEventTextParts, changes: Map<ProtonSharedTextProperty, ProtonSharedTextChange>,
        expectedUid: String = uid) {
        val clear = original.clearShared; val secret = original.encryptedShared
        try { ProtonSharedTextPatcher.patch(original, changes, expectedUid); fail("Ambiguous text must be refused") }
        catch (failure: ProtonTextPatchException) {
            assertEquals("Shared text patch unavailable: UNSUPPORTED", failure.message); assertNull(failure.cause)
            assertFalse(failure.toString().contains(uid))
        }
        assertEquals(clear, original.clearShared); assertEquals(secret, original.encryptedShared)
    }

    @Test fun absentAndEmptyAreDistinctAndOptionalPropertiesCanBeInsertedOrRemoved() {
        val original = parts()
        val empty = patch(original, DESCRIPTION to change(null, ""))
        assertEquals(calendar("SUMMARY:Original\r\nDESCRIPTION:\r\n"), empty.encryptedShared)
        assertEquals("", Biweekly.parse(empty.encryptedShared).first().events.single().description.value)
        val located = patch(empty, LOCATION to change(null, "Room"), DESCRIPTION to change("", null))
        assertEquals(calendar("SUMMARY:Original\r\nLOCATION:Room\r\n"), located.encryptedShared)
        val removed = patch(located, LOCATION to change("Room", null))
        assertEquals(original.clearShared, removed.clearShared); assertEquals(original.encryptedShared, removed.encryptedShared)
        refused(original, mapOf(DESCRIPTION to change("", "x")))
        refused(empty, mapOf(DESCRIPTION to change(null, "x")))
    }
    @Test fun multichangeInsertionOrderDoesNotDependOnMapOrder() {
        val original = parts("SUMMARY:Original\r\nX-TAIL:raw\r\n")
        val changes = listOf(LOCATION to change(null, "Place"), SUMMARY to change("Original", "New"), DESCRIPTION to change(null, "Notes"))
        val expected = calendar("SUMMARY:New\r\nX-TAIL:raw\r\nDESCRIPTION:Notes\r\nLOCATION:Place\r\n")
        assertEquals(expected, patch(original, *changes.toTypedArray()).encryptedShared)
        assertEquals(expected, patch(original, *changes.reversed().toTypedArray()).encryptedShared)
        assertEquals(original.clearShared, patch(original, *changes.toTypedArray()).clearShared)
    }
    @Test fun selectedNoOpSpanStaysRawWhileAnotherSelectedSpanChanges() {
        val original = parts("summary:Orig\r\n\tinal\r\ndescription:Line\\n\r\n Text\r\nlocation:Old\r\n")
        val result = patch(original, SUMMARY to change("Original", "Original"), DESCRIPTION to change("Line\r\nText", "Line\nText"),
            LOCATION to change("Old", "New"))
        assertEquals(original.encryptedShared.replace("location:Old\r\n", "LOCATION:New\r\n"), result.encryptedShared)
        assertSame(original, patch(original, DESCRIPTION to change("Line\nText", "Line\r\nText"), LOCATION to change("Old", "Old")))
        val absent = parts()
        assertSame(absent, patch(absent, DESCRIPTION to change(null, null)))
    }
    @Test fun untargetedParametersDuplicatesNestedComponentsAndOpaqueBytesRemainExact() {
        val raw = "DESCRIPTION;LANGUAGE=fr:Premier\r\nDESCRIPTION:Second\r\nLOCATION;ALTREP=\"https://example.invalid/a:b\":Place\r\n" +
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT5M\r\nSUMMARY:Nested\r\nDESCRIPTION:Nested notes\r\nEND:VALARM\r\n" +
            "BEGIN:X-OPAQUE\r\nLOCATION:Nested place\r\nX-RAW:Keep\\,escaped\r\nEND:X-OPAQUE\r\n"
        val original = parts("SUMMARY:Original\r\n$raw")
        val common = patch(original, SUMMARY to change("Original", "New"))
        val legacy = ProtonSharedTitlePatcher.patch(original, "Original", "New", uid)
        assertEquals(legacy.clearShared, common.clearShared); assertEquals(legacy.encryptedShared, common.encryptedShared)
        assertEquals(calendar("SUMMARY:New\r\n$raw"), common.encryptedShared)
        assertSame(original, patch(original, SUMMARY to change("Original", "Original")))
    }
    @Test fun selectedDuplicatesParametersAndClearPartAmbiguitiesFailEvenOnNoOp() {
        for (field in listOf(DESCRIPTION, LOCATION)) {
            for (body in listOf("${field.name}:Value\r\n${field.name}:Value\r\n", "${field.name};LANGUAGE=fr:Value\r\n")) {
                refused(parts("SUMMARY:Original\r\n$body"), mapOf(field to change("Value", "Value")))
            }
            refused(parts("SUMMARY:Original\r\n", "${field.name}:Value\r\n"), mapOf(field to change(null, null)))
            refused(parts("SUMMARY:Original\r\n${field.name}:Value\r\n", "${field.name}:Value\r\n"), mapOf(field to change("Value", "Value")))
            refused(parts("SUMMARY:Original\r\n${field.name}:Value\r\n"), mapOf(field to change("Stale", "Stale")))
        }
    }
    @Test fun selectedAbsentFieldsIgnoreNestedNamesWithoutRemovingThem() {
        val nested = "BEGIN:X-OPAQUE\r\nDESCRIPTION:Nested\r\nLOCATION:Nested\r\nEND:X-OPAQUE\r\n"
        val original = parts("SUMMARY:Original\r\n$nested")
        assertSame(original, patch(original, DESCRIPTION to change(null, null)))
        assertEquals(calendar("SUMMARY:Original\r\n${nested}LOCATION:New\r\n"), patch(original, LOCATION to change(null, "New")).encryptedShared)
    }
    @Test fun summaryCannotBeAbsentAndSignedIdentityStillGatesOptionalOnlyEdits() {
        refused(parts(), emptyMap())
        for (changes in listOf(mapOf(SUMMARY to change("Original", null)), mapOf(SUMMARY to change(null, "New")))) refused(parts(), changes)
        for (secret in listOf("", "SUMMARY:Original\r\nSUMMARY:Original\r\n", "SUMMARY;LANGUAGE=en:Original\r\n"))
            refused(parts(secret), mapOf(DESCRIPTION to change(null, "x")))
        val original = parts()
        refused(original, mapOf(LOCATION to change(null, null)), "other-fixture")
        for (secret in listOf(original.encryptedShared.replace(uid, "other-fixture"),
            original.encryptedShared.replace("20261003T100000Z", "20261003T100001Z"),
            original.encryptedShared.replace("UID:$uid\r\n", ""))) {
            refused(ProtonEventTextParts(original.clearShared, secret), mapOf(LOCATION to change(null, null)))
        }
    }
    @Test fun generatedOptionalTextUsesLibraryEscapingAndUtf8OctetFolding() {
        val text = "é🦌界,;\\\nBEGIN:VEVENT\nEND:VCALENDAR\t" + "🦌界é".repeat(80)
        val result = patch(parts(), DESCRIPTION to change(null, text), LOCATION to change(null, text))
        val event = Biweekly.parse(result.encryptedShared).first().events.single()
        assertEquals(text, event.description.value.replace("\r\n", "\n")); assertEquals(text, event.location.value.replace("\r\n", "\n"))
        assertTrue(result.encryptedShared.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertEquals(1, result.encryptedShared.split("\r\n").count { it == "BEGIN:VEVENT" })
    }
    @Test fun controlsSurrogatesTextInputAndGeneratedFoldingBudgetsRefuseWithoutLeaks() {
        for (value in listOf("private\u0000", "private\rline", "bad\uD800", "x".repeat(65_537), "x".repeat(80) + " ".repeat(100) + "end")) {
            for (field in listOf(DESCRIPTION, LOCATION)) refused(parts(), mapOf(field to change(null, value)))
        }
        assertFalse(change("private-expected", "private-replacement").toString().contains("private"))
        val huge = parts("SUMMARY:Original\r\nX-OPAQUE:" + "x".repeat(1_048_576) + "\r\n")
        refused(huge, mapOf(LOCATION to change(null, null)))
        refused(parts("SUMMARY:Original\r\n" + "X-RAW:a\r\n".repeat(20_001)), mapOf(LOCATION to change(null, null)))
    }
    @Test fun outputBudgetIsCheckedAfterInsertionWhileTrueNoOpKeepsOriginal() {
        val small = parts("SUMMARY:Original\r\nX-PAD:\r\n")
        val count = 1_048_575 - small.clearShared.toByteArray().size - small.encryptedShared.toByteArray().size
        val original = ProtonEventTextParts(small.clearShared, small.encryptedShared.replace("X-PAD:", "X-PAD:" + "x".repeat(count)))
        assertSame(original, patch(original, LOCATION to change(null, null)))
        refused(original, mapOf(LOCATION to change(null, "An added place")))
    }
    @Test fun legacyFailureTypeAndCauseStayUnchangedAfterDelegation() {
        val failure = assertThrows(ProtonTitlePatchException::class.java) { ProtonSharedTitlePatcher.patch(parts(), "Stale", "Next", uid) }
        assertEquals("Shared title patch unavailable: UNSUPPORTED", failure.message); assertNull(failure.cause)
    }
    @Test fun oneValidEditCannotEscapeAnotherStaleAmbiguousOrClearSelectedField() {
        val normal = parts("SUMMARY:Original\r\nDESCRIPTION:Notes\r\nLOCATION:Place\r\n")
        val candidates = listOf(normal,
            parts("SUMMARY:Original\r\nDESCRIPTION:Notes\r\nLOCATION:Place\r\nLOCATION:Place\r\n"),
            parts("SUMMARY:Original\r\nDESCRIPTION:Notes\r\nLOCATION;LANGUAGE=en:Place\r\n"),
            parts("SUMMARY:Original\r\nDESCRIPTION:Notes\r\nLOCATION:Place\r\n", "LOCATION:Place\r\n"))
        for ((index, original) in candidates.withIndex()) {
            val edits = linkedMapOf(DESCRIPTION to change("Notes", "Changed valid notes"),
                LOCATION to change(if (index == 0) "Stale" else "Place", "Changed place"))
            refused(original, edits)
            refused(original, edits.entries.reversed().associate { it.toPair() })
        }
    }
}
