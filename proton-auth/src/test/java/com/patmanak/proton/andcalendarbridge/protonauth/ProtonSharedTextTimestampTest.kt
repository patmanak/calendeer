// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSharedTextProperty.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ProtonSharedTextTimestampTest {
    private val uid = "shared-text-clock@calendeer.invalid"
    private val old = "20261003T100000Z"
    private val modified = "20261003T095900Z"
    private val next = Instant.parse("2026-10-03T10:01:00Z")
    private fun calendar(body: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:$old\r\n" + body + "END:VEVENT\r\nEND:VCALENDAR\r\n"
    private fun parts() = ProtonEventTextParts(calendar("created:20261001T090000Z\r\nLAST-MODIFIED:$modified\r\nsequence:00\r\n\t07\r\nX-OPAQUE:clear\r\n"),
        calendar("summary:Original\r\nDESCRIPTION:Notes\r\nLOCATION:Place\r\nX-OPAQUE:secret\r\n"))
    private val edits = linkedMapOf(LOCATION to ProtonSharedTextChange("Place", null), DESCRIPTION to ProtonSharedTextChange("Notes", "New notes"))
    private fun patch(original: ProtonEventTextParts = parts(), changes: Map<ProtonSharedTextProperty, ProtonSharedTextChange> = edits,
        time: Instant = next) = ProtonSharedTextPatcher.patchAt(original, changes, uid, time)
    private fun refused(original: ProtonEventTextParts = parts(), changes: Map<ProtonSharedTextProperty, ProtonSharedTextChange> = edits,
        time: Instant = next) {
        val failure = assertThrows(ProtonTextPatchException::class.java) { patch(original, changes, time) }
        assertEquals("Shared text patch unavailable: UNSUPPORTED", failure.message); assertNull(failure.cause)
        assertFalse(failure.toString().contains(uid))
    }
    @Test fun optionalEditsChangeOnlySelectedSpansAndThreeExistingClockSpans() {
        val original = parts(); val result = patch(original)
        assertEquals(original.clearShared.replace("DTSTAMP:$old", "DTSTAMP:20261003T100100Z")
            .replace("LAST-MODIFIED:$modified", "LAST-MODIFIED:20261003T100100Z"), result.clearShared)
        assertEquals(original.encryptedShared.replace("DTSTAMP:$old", "DTSTAMP:20261003T100100Z")
            .replace("DESCRIPTION:Notes\r\n", "DESCRIPTION:New notes\r\n").replace("LOCATION:Place\r\n", ""), result.encryptedShared)
        assertTrue(result.clearShared.contains("created:20261001T090000Z\r\n"))
        assertTrue(result.clearShared.contains("sequence:00\r\n\t07\r\n"))
    }
    @Test fun absentToExplicitEmptyIsAnActualChangeAndUpdatesMetadata() {
        val original = parts().let { ProtonEventTextParts(it.clearShared, it.encryptedShared.replace("LOCATION:Place\r\n", "")) }
        val result = patch(original, mapOf(LOCATION to ProtonSharedTextChange(null, "")))
        assertTrue(result.encryptedShared.contains("LOCATION:\r\n"))
        assertTrue(result.clearShared.contains("DTSTAMP:20261003T100100Z\r\n"))
    }
    @Test fun trueNoOpValidatesTextBindingButDoesNotQualifyMetadataLayout() {
        val original = ProtonEventTextParts(calendar("SEQUENCE:invalid\r\nRRULE:unsupported\r\n"),
            calendar("SUMMARY:Original\r\nDESCRIPTION:Notes\r\nCREATED:invalid\r\n"))
        val noOp = mapOf(DESCRIPTION to ProtonSharedTextChange("Notes", "Notes"), LOCATION to ProtonSharedTextChange(null, null))
        assertSame(original, patch(original, noOp, Instant.EPOCH))
        refused(original, mapOf(DESCRIPTION to ProtonSharedTextChange("Stale", "Stale")), Instant.EPOCH)
        refused(original, mapOf(DESCRIPTION to ProtonSharedTextChange("Notes", "Different")))
    }
    @Test fun timestampsRemainWholeSecondsBoundedAndNotBackdated() {
        for (time in listOf(next.plusNanos(1), Instant.parse("1969-12-31T23:59:59Z"), Instant.MIN, Instant.MAX)) {
            refused(time = time)
            refused(changes = mapOf(DESCRIPTION to ProtonSharedTextChange("Notes", "Notes")), time = time)
        }
        refused(time = Instant.parse("2026-10-03T09:59:59Z"))
        assertTrue(patch(time = Instant.parse("2026-10-03T10:00:00Z")).clearShared.contains("LAST-MODIFIED:$old\r\n"))
    }
    @Test fun missingDuplicateParameterizedOrMisplacedMetadataStillRefusesChanges() {
        val original = parts()
        for (line in listOf("DTSTAMP:$old", "created:20261001T090000Z", "LAST-MODIFIED:$modified")) {
            for (clear in listOf(original.clearShared.replace("$line\r\n", ""), original.clearShared.replace(line, "$line\r\n$line"),
                original.clearShared.replace(line, line.replaceFirst(":", ";X-OPAQUE=1:")))) {
                refused(ProtonEventTextParts(clear, original.encryptedShared))
            }
        }
        for (name in listOf("CREATED", "LAST-MODIFIED", "SEQUENCE"))
            refused(ProtonEventTextParts(original.clearShared, original.encryptedShared.replace("END:VEVENT", "$name:invalid\r\nEND:VEVENT")))
    }
    @Test fun recurrenceAndInvitationMarkersStayRejectedOnlyAtRootOrEvent() {
        val original = parts()
        for (line in listOf("RRULE:FREQ=DAILY", "ATTENDEE:mailto:fixture@example.invalid", "ORGANIZER:mailto:fixture@example.invalid", "STATUS:CONFIRMED", "METHOD:PUBLISH"))
            refused(ProtonEventTextParts(original.clearShared, original.encryptedShared.replace("END:VEVENT", "$line\r\nEND:VEVENT")))
        val nested = "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT5M\r\nDESCRIPTION:Alarm\r\nSEQUENCE:opaque\r\nEND:VALARM\r\n"
        val withNested = ProtonEventTextParts(original.clearShared, original.encryptedShared.replace("END:VEVENT", nested + "END:VEVENT"))
        assertTrue(patch(withNested).encryptedShared.contains(nested))
    }
    @Test fun summaryOnlyTimestampBytesMatchTheHistoricalFacade() {
        val original = parts()
        val changes = mapOf(SUMMARY to ProtonSharedTextChange("Original", "Next"))
        val legacy = ProtonSharedTitlePatcher.patchAt(original, "Original", "Next", uid, next)
        val common = patch(original, changes)
        assertEquals(legacy.clearShared, common.clearShared); assertEquals(legacy.encryptedShared, common.encryptedShared)
    }
    @Test fun deletingAnOptionalValueOrExplicitEmptyAloneUpdatesAllThreeClockSpans() {
        for ((field, expected) in listOf(DESCRIPTION to "Notes", LOCATION to "")) {
            val original = parts().let { if (field == LOCATION) ProtonEventTextParts(it.clearShared,
                it.encryptedShared.replace("LOCATION:Place\r\n", "LOCATION:\r\n")) else it }
            val result = patch(original, mapOf(field to ProtonSharedTextChange(expected, null)))
            assertEquals(original.clearShared.replace("DTSTAMP:$old", "DTSTAMP:20261003T100100Z")
                .replace("LAST-MODIFIED:$modified", "LAST-MODIFIED:20261003T100100Z"), result.clearShared)
            assertEquals(original.encryptedShared.replace("DTSTAMP:$old", "DTSTAMP:20261003T100100Z")
                .replace("${field.name}:$expected\r\n", ""), result.encryptedShared)
        }
    }
}
