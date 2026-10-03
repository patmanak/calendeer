// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import biweekly.Biweekly
import org.junit.Assert.*
import org.junit.Test

class ProtonSharedTitlePatcherTest {
    private val uid = "synthetic-preservation@calendeer.invalid"
    private val stamp = "20261003T100000Z"
    private fun calendar(body: String, extra: String = "") = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
        "PRODID:-//Synthetic fixture//EN\r\n$extra" +
        "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:$stamp\r\n$body" +
        "END:VEVENT\r\nEND:VCALENDAR\r\n"
    private fun parts(secret: String = "SUMMARY:Original title\r\n") = ProtonEventTextParts(
        calendar("DTSTART:20261005T100000Z\r\nDTEND:20261005T110000Z\r\nSEQUENCE:7\r\n"), calendar(secret))
    private fun patch(original: ProtonEventTextParts, expected: String = "Original title", next: String = "New title") =
        ProtonSharedTitlePatcher.patch(original, expected, next)
    private fun refused(original: ProtonEventTextParts, expected: String = "Original title", next: String = "New title") {
        val clear = original.clearShared
        val secret = original.encryptedShared
        try { patch(original, expected, next); fail("Unsupported input must be refused") }
        catch (failure: ProtonTitlePatchException) {
            assertEquals("Shared title patch unavailable: UNSUPPORTED", failure.message)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains(uid))
            assertFalse(failure.toString().contains(expected))
        }
        assertEquals(clear, original.clearShared)
        assertEquals(secret, original.encryptedShared)
    }

    @Test fun noOpRetainsOriginalFoldingCaseAndBothPartitionsExactly() {
        val original = parts("summary:Original \r\n\ttitle\r\nX-OPAQUE:retained\r\n")
        val result = patch(original, next = "Original title")
        assertSame(original, result)
        assertEquals(original.clearShared, result.clearShared)
        assertEquals(original.encryptedShared, result.encryptedShared)
    }

    @Test fun onlyTheEventSummarySpanChangesWhileOpaqueDataAndNestedTitlesRemainExact() {
        val before = "X-FUTURE;P=\"https://example.invalid/a:b\":first\r\n" +
            "X-FUTURE:duplicate\r\nDESCRIPTION:Keep, raw\\ntext\r\n\t and folding\r\n"
        val after = "LOCATION;LANGUAGE=fr:Lieu\r\n" +
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT15M\r\nSUMMARY:Alarm title\r\nEND:VALARM\r\n" +
            "BEGIN:X-FUTURE-COMPONENT\r\nX-OPAQUE:Untouched\r\nEND:X-FUTURE-COMPONENT\r\n"
        val original = parts(before + "SUMMARY:Original \r\n title\r\n" + after)
        val result = patch(original)
        assertEquals(original.clearShared, result.clearShared)
        assertEquals(calendar(before + "SUMMARY:New title\r\n" + after), result.encryptedShared)
        assertEquals("New title", Biweekly.parse(result.encryptedShared).first().events.single().summary.value)
        assertEquals(1, Biweekly.parse(result.encryptedShared).first().events.single().alarms.size)
        assertFalse(result.toString().contains("New title"))
    }

    @Test fun unrelatedRootComponentsAndClearMetadataRemainByteIdentical() {
        val zone = "BEGIN:VTIMEZONE\r\nTZID:Fixture/Zone\r\nBEGIN:STANDARD\r\n" +
            "DTSTART:19700101T000000\r\nTZOFFSETFROM:+0100\r\nTZOFFSETTO:+0100\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n"
        val original = parts().let { ProtonEventTextParts(calendar(
            "DTSTART:20261005T100000Z\r\nDTEND:20261005T110000Z\r\nX-UNKNOWN:opaque\r\n", zone), it.encryptedShared) }
        assertEquals(original.clearShared, patch(original).clearShared)
    }

    @Test fun libraryEscapingAndFoldingRetainUnicodeTextWithoutComponentInjection() {
        val next = "Deer 🦌 été,;\\\nBEGIN:VEVENT\nEND:VCALENDAR\t" + "é🦌".repeat(80)
        val result = patch(parts(), next = next)
        val event = Biweekly.parse(result.encryptedShared).first().events.single()
        assertEquals(next, event.summary.value.replace("\r\n", "\n"))
        assertTrue(result.encryptedShared.contains("\r\n "))
        assertTrue(result.encryptedShared.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertEquals(1, result.encryptedShared.split("\r\n").count { it == "BEGIN:VEVENT" })
        assertEquals(1, result.encryptedShared.split("\r\n").count { it == "END:VCALENDAR" })
        assertTrue(result.encryptedShared.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == result.encryptedShared)
    }

    @Test fun emptyTitleAndNormalizedNewlinesRemainSupported() {
        val empty = patch(parts(), next = "")
        assertEquals("", Biweekly.parse(empty.encryptedShared).first().events.single().summary.value)
        val original = parts("SUMMARY:line1\\nline2\r\n")
        assertSame(original, patch(original, "line1\r\nline2", "line1\nline2"))
        assertEquals("line1\r\nline2", Biweekly.parse(patch(parts(), next = "line1\r\nline2").encryptedShared)
            .first().events.single().summary.value)
    }

    @Test fun staleMissingDuplicateOrParameterizedEventTitlesAreRefused() {
        refused(parts(), expected = "Stale title")
        for (body in listOf("DESCRIPTION:No title\r\n", "SUMMARY:Original title\r\nSUMMARY:Other\r\n",
            "SUMMARY;LANGUAGE=en:Original title\r\n", "SUMMARY;ALTREP=\"https://example.invalid/title\":Original title\r\n",
            "SUMMARY;X-FUTURE=value:Original title\r\n", "group.SUMMARY:Original title\r\n")) refused(parts(body))
        val original = parts()
        refused(ProtonEventTextParts(original.clearShared.replace("SEQUENCE:7", "SUMMARY:Original title"), original.encryptedShared))
    }

    @Test fun identityAndStampMustBeUnambiguousTypedAndMatchBothPartitions() {
        val original = parts()
        for (secret in listOf(
            original.encryptedShared.replace("UID:$uid\r\n", ""),
            original.encryptedShared.replace("UID:$uid", "UID:$uid\r\nUID:$uid"),
            original.encryptedShared.replace("UID:$uid", "UID:other@calendeer.invalid"),
            original.encryptedShared.replace("UID:$uid", "UID;X-OPAQUE=1:$uid"),
            original.encryptedShared.replace("DTSTAMP:$stamp\r\n", ""),
            original.encryptedShared.replace("DTSTAMP:$stamp", "DTSTAMP:$stamp\r\nDTSTAMP:$stamp"),
            original.encryptedShared.replace(stamp, "20261003T100001Z"),
            original.encryptedShared.replace(stamp, "20260230T100000Z"),
            original.encryptedShared.replace(stamp, "20261003T100000"),
            original.encryptedShared.replace("DTSTAMP:", "DTSTAMP;VALUE=DATE:"))) {
            refused(ProtonEventTextParts(original.clearShared, secret))
        }
    }

    @Test fun malformedFramingVersionOrComponentStructuresCannotBePatched() {
        val original = parts()
        val secret = original.encryptedShared
        for (invalid in listOf(secret.replace("\r\n", "\n"), secret.replaceFirst("\r\n", "\r"),
            "\uFEFF$secret", " $secret", secret + "X-OUTSIDE:value\r\n", secret + secret,
            secret.replace("VERSION:2.0", "VERSION:2.0\r\nVERSION:2.0"), secret.replace("VERSION:2.0\r\n", ""),
            secret.replace("VERSION:2.0", "VERSION:1.0"), secret.replace("BEGIN:VEVENT", "BEGIN;P=1:VEVENT"),
            secret.replace("BEGIN:VEVENT", "BEGIN:VE\r\n VENT"), secret.replace("END:VEVENT", "END:VALARM"),
            secret.replace("SUMMARY:Original title", "BEGIN:VEVENT\r\nEND:VEVENT\r\nSUMMARY:Original title"),
            secret.replace("SUMMARY:Original title", "X-BAD;P=\"broken:value\r\nSUMMARY:Original title"),
            secret.replace("SUMMARY:Original title", "SUMMARY:Original title\u0000"),
            secret.replace("SUMMARY:Original title", "SUMMARY:Original title\uD800"))) {
            refused(ProtonEventTextParts(original.clearShared, invalid))
        }
    }

    @Test fun titleEncodingControlCharactersAndInputBudgetsAreBounded() {
        for (title in listOf("bare\rreturn", "nul\u0000", "control\u0001", "bad\uD800", "x".repeat(65_537)))
            refused(parts(), next = title)
        val original = parts()
        refused(ProtonEventTextParts(original.clearShared, original.encryptedShared.replace(
            "SUMMARY:Original title", "X-LARGE:" + "é".repeat(524_288) + "\r\nSUMMARY:Original title")))
        refused(parts("X-LINE:opaque\r\n".repeat(20_001) + "SUMMARY:Original title\r\n"))
        refused(parts("BEGIN:X-NESTED\r\n".repeat(17) + "END:X-NESTED\r\n".repeat(17) + "SUMMARY:Original title\r\n"))
    }

    @Test fun refusedClearPartitionCannotLeakOrBeNormalized() {
        val original = parts()
        for (clear in listOf(original.clearShared.replace("VERSION:2.0", "VERSION:2.0\r\nVERSION:2.0"),
            original.clearShared.replace(uid, "other@calendeer.invalid"), original.clearShared.replace(stamp, "invalid"),
            original.clearShared.replace("\r\n", "\n"))) refused(ProtonEventTextParts(clear, original.encryptedShared))
    }

    @Test fun preservationDoesNotCertifyOrRepairUnrelatedUnsupportedProperties() {
        val original = parts().let { ProtonEventTextParts(it.clearShared.replace(
            "DTSTART:20261005T100000Z", "DTSTART:not-a-valid-date"), it.encryptedShared) }
        val first = patch(original)
        val second = patch(original)
        assertEquals(original.clearShared, first.clearShared)
        assertEquals(first.clearShared, second.clearShared)
        assertEquals(first.encryptedShared, second.encryptedShared)
    }

    @Test fun acceptedInputNearTheByteLimitRefusesAnOversizedPatchedOutput() {
        val small = parts("X-LARGE:\r\nSUMMARY:Original title\r\n")
        val padding = 1_048_575 - small.clearShared.toByteArray(Charsets.UTF_8).size -
            small.encryptedShared.toByteArray(Charsets.UTF_8).size
        val original = ProtonEventTextParts(small.clearShared, small.encryptedShared.replace("X-LARGE:\r\n",
            "X-LARGE:" + "x".repeat(padding) + "\r\n"))
        assertEquals(1_048_575, original.clearShared.toByteArray(Charsets.UTF_8).size +
            original.encryptedShared.toByteArray(Charsets.UTF_8).size)
        assertSame(original, patch(original, next = "Original title"))
        refused(original, next = "A larger new title".repeat(20))
    }

    @Test fun unicodeFoldingAtBoundariesPreservesCodePointsAndEnforcesOctetLimit() {
        for (next in listOf("界".repeat(130), "🦌".repeat(130), "x".repeat(74) + "é🦌界".repeat(30))) {
            val result = patch(parts(), next = next)
            assertEquals(next, Biweekly.parse(result.encryptedShared).first().events.single().summary.value)
            assertTrue(result.encryptedShared.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        }
        // Vinnie deliberately keeps long whitespace runs together; do not advertise these as safe.
        refused(parts(), next = "x".repeat(80) + " ".repeat(100) + "end")
    }
}
