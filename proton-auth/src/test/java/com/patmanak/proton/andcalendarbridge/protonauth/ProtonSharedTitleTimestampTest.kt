// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ProtonSharedTitleTimestampTest {
    private val uid = "synthetic-timestamp@calendeer.invalid"
    private val old = "20261003T100000Z"
    private val created = "20261001T090000Z"
    private val modified = "20261003T095900Z"
    private val next = Instant.parse("2026-10-03T10:01:00Z")
    private fun calendar(body: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
        "PRODID:-//Synthetic fixture//EN\r\nBEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:$old\r\n" +
        body + "END:VEVENT\r\nEND:VCALENDAR\r\n"
    private fun parts() = ProtonEventTextParts(calendar("CREATED:$created\r\nLAST-MODIFIED:$modified\r\n" +
        "SEQUENCE:0007\r\nDTSTART:20261005T100000Z\r\nDTEND:20261005T110000Z\r\nX-OPAQUE:kept\r\n"),
        calendar("SUMMARY:Original title\r\nDESCRIPTION:Keep raw\\ntext\r\n\t and folding\r\n"))
    private fun patch(original: ProtonEventTextParts = parts(), title: String = "New title", time: Instant = next,
                      expectedUid: String = uid, expectedTitle: String = "Original title") =
        ProtonSharedTitlePatcher.patchAt(original, expectedTitle, title, expectedUid, time)
    private fun refused(original: ProtonEventTextParts = parts(), time: Instant = next, title: String = "New title",
                        expectedUid: String = uid, expectedTitle: String = "Original title") {
        val clear = original.clearShared
        val secret = original.encryptedShared
        try { patch(original, title, time, expectedUid, expectedTitle); fail("Unsupported metadata must be refused") }
        catch (failure: ProtonTitlePatchException) {
            assertEquals("Shared title patch unavailable: UNSUPPORTED", failure.message)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains(uid))
        }
        assertEquals(clear, original.clearShared)
        assertEquals(secret, original.encryptedShared)
    }
    private fun clear(value: String) = parts().let { ProtonEventTextParts(value, it.encryptedShared) }
    private fun secret(value: String) = parts().let { ProtonEventTextParts(it.clearShared, value) }

    @Test fun changedTitleReplacesOnlySummaryAndThreeTimestampSpans() {
        val original = parts()
        val result = patch(original)
        assertEquals(original.clearShared.replace("DTSTAMP:$old", "DTSTAMP:20261003T100100Z")
            .replace("LAST-MODIFIED:$modified", "LAST-MODIFIED:20261003T100100Z"), result.clearShared)
        assertEquals(original.encryptedShared.replace("DTSTAMP:$old", "DTSTAMP:20261003T100100Z")
            .replace("SUMMARY:Original title", "SUMMARY:New title"), result.encryptedShared)
        assertTrue(result.clearShared.contains("CREATED:$created\r\n"))
        assertTrue(result.clearShared.contains("SEQUENCE:0007\r\n"))
        assertFalse(result.toString().contains("New title"))
    }

    @Test fun noOpPreservesRawBytesWithoutCheckingAdditionalMetadataEligibility() {
        val original = ProtonEventTextParts(calendar("SEQUENCE:invalid\r\nRRULE:unsupported\r\n"),
            calendar("summary:Original \r\n\ttitle\r\nCREATED:invalid\r\n"))
        assertSame(original, patch(original, title = "Original title", time = Instant.EPOCH))
        // A no-op still requires its observed title and signed UID binding.
        refused(original, title = "Original title", expectedUid = "other@calendeer.invalid")
        refused(original, title = "Original title", expectedTitle = "Stale title")
    }

    @Test fun explicitTimestampMustBeWholeSecondsAndInRangeEvenOnNoOp() {
        for (time in listOf(next.plusNanos(1), Instant.parse("1969-12-31T23:59:59Z"),
            Instant.parse("+10000-01-01T00:00:00Z"), Instant.MIN, Instant.MAX)) {
            refused(time = time)
            refused(time = time, title = "Original title")
        }
        val original = parts()
        assertSame(original, patch(original, "Original title", Instant.EPOCH))
    }

    @Test fun unchangedLegacyApiStillIgnoresAdditionalMetadata() {
        val original = ProtonEventTextParts(calendar("CREATED:invalid\r\nSEQUENCE:-1\r\n"),
            calendar("SUMMARY:Original title\r\nLAST-MODIFIED:invalid\r\n"))
        val result = ProtonSharedTitlePatcher.patch(original, "Original title", "New title", uid)
        assertEquals(original.clearShared, result.clearShared)
        assertEquals(original.encryptedShared.replace("SUMMARY:Original title", "SUMMARY:New title"), result.encryptedShared)
    }

    @Test fun backdatedTimestampCannotPrecedeEitherObservedModificationDate() {
        refused(time = Instant.parse("2026-10-03T09:59:59Z"))
        val laterLast = clear(parts().clearShared.replace(modified, "20261003T100200Z"))
        refused(laterLast)
        val equal = patch(time = Instant.parse("2026-10-03T10:00:00Z"))
        assertTrue(equal.clearShared.contains("LAST-MODIFIED:$old\r\n"))
    }

    @Test fun requiredClearMetadataIsSinglePlainAndPresent() {
        for (line in listOf("DTSTAMP:$old", "CREATED:$created", "LAST-MODIFIED:$modified", "SEQUENCE:0007")) {
            val text = parts().clearShared
            refused(clear(text.replace("$line\r\n", "")))
            refused(clear(text.replace(line, "$line\r\n$line")))
            val name = line.substringBefore(':')
            refused(clear(text.replace(line, "$name;X-FUTURE=value:${line.substringAfter(':')}")))
        }
    }

    @Test fun secretPartitionRejectsMisplacedClearMetadata() {
        for (line in listOf("CREATED:$created", "LAST-MODIFIED:$modified", "SEQUENCE:0007",
            "CREATED;X-FUTURE=1:$created", "LAST-MODIFIED;X-FUTURE=1:$modified", "SEQUENCE;X-FUTURE=1:0007")) {
            refused(secret(parts().encryptedShared.replace("SUMMARY:", "$line\r\nSUMMARY:")))
        }
    }

    @Test fun malformedCreatedAndLastModifiedDatesAreRefused() {
        for (date in listOf("20260230T100000Z", "20261003T100060Z", "20261003T100000", "20261003",
            "19691231T235959Z", "20261003T100000+0000", "20261003t100000z", "invalid")) {
            refused(clear(parts().clearShared.replace(created, date)))
            refused(clear(parts().clearShared.replace(modified, date)))
        }
    }

    @Test fun creationCannotBeAfterEitherPreviousTimestamp() {
        refused(clear(parts().clearShared.replace(created, "20261003T095930Z")))
        refused(clear(parts().clearShared.replace(created, "20261003T100001Z")
            .replace(modified, "20261003T100200Z")), time = Instant.parse("2026-10-03T10:03:00Z"))
    }

    @Test fun sequenceMustBeNonnegativeDecimalWithoutOverflow() {
        for (sequence in listOf("-1", "+1", "1.0", "", " 1", "1 ", "2147483648", "abc", "١"))
            refused(clear(parts().clearShared.replace("SEQUENCE:0007", "SEQUENCE:$sequence")))
        for (sequence in listOf("0", "2147483647", "000000000000000000000000000000000000007")) {
            val original = clear(parts().clearShared.replace("SEQUENCE:0007", "SEQUENCE:$sequence"))
            assertTrue(patch(original).clearShared.contains("SEQUENCE:$sequence\r\n"))
        }
    }

    @Test fun foldedSequenceAndCaseRetainOriginalRawSpan() {
        val original = clear(parts().clearShared.replace("SEQUENCE:0007", "sequence:00\r\n\t07")
            .replace("CREATED:", "created:").replace("LAST-MODIFIED:", "last-modified:")
            .replace("DTSTAMP:", "dtstamp:"))
        val result = patch(original)
        assertEquals(original.clearShared.replace("dtstamp:$old", "dtstamp:20261003T100100Z")
            .replace("last-modified:$modified", "last-modified:20261003T100100Z"), result.clearShared)
        assertTrue(result.clearShared.contains("sequence:00\r\n\t07\r\n"))
    }

    @Test fun recurrenceAndInvitationMarkersRefuseChangesInEitherPartition() {
        for (marker in listOf("RRULE:FREQ=DAILY", "EXRULE:FREQ=DAILY", "RDATE:20261005T100000Z", "EXDATE:20261005T100000Z",
            "RECURRENCE-ID:20261005T100000Z", "ATTENDEE:mailto:synthetic@example.invalid",
            "ORGANIZER:mailto:synthetic@example.invalid", "STATUS:CONFIRMED")) {
            refused(clear(parts().clearShared.replace("X-OPAQUE:", "$marker\r\nX-OPAQUE:")))
            refused(secret(parts().encryptedShared.replace("SUMMARY:", "$marker\r\nSUMMARY:")))
        }
    }

    @Test fun nestedAlarmMetadataAndUnrelatedComponentsRemainUntouched() {
        val alarm = "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT15M\r\nSUMMARY:Alarm title\r\n" +
            "CREATED:opaque\r\nLAST-MODIFIED:opaque\r\nSEQUENCE:opaque\r\nSTATUS:opaque\r\n" +
            "ATTENDEE:mailto:synthetic@example.invalid\r\nMETHOD:opaque\r\nEND:VALARM\r\n"
        val original = secret(parts().encryptedShared.replace("END:VEVENT", alarm + "END:VEVENT"))
        assertEquals(original.encryptedShared.replace("DTSTAMP:$old", "DTSTAMP:20261003T100100Z")
            .replace("SUMMARY:Original title", "SUMMARY:New title"), patch(original).encryptedShared)
    }

    @Test fun rootAndEventMethodPreventInvitationLikeChanges() {
        for (original in listOf(
            clear(parts().clearShared.replace("VERSION:2.0", "VERSION:2.0\r\nMETHOD:PUBLISH")),
            secret(parts().encryptedShared.replace("VERSION:2.0", "VERSION:2.0\r\nMETHOD;X=1:REQUEST")),
            clear(parts().clearShared.replace("X-OPAQUE:", "METHOD:PUBLISH\r\nX-OPAQUE:")),
            secret(parts().encryptedShared.replace("SUMMARY:", "METHOD:REQUEST\r\nSUMMARY:")))) refused(original)
    }

    @Test fun acceptedInputBudgetStillRefusesOversizedOutputWithoutChangingOriginal() {
        val small = parts()
        val padding = 1_048_575 - small.clearShared.toByteArray(Charsets.UTF_8).size -
            small.encryptedShared.toByteArray(Charsets.UTF_8).size
        val original = clear(small.clearShared.replace("X-OPAQUE:kept", "X-OPAQUE:kept" + "x".repeat(padding)))
        assertSame(original, patch(original, title = "Original title"))
        refused(original, title = "A substantially longer replacement title")
    }
}
