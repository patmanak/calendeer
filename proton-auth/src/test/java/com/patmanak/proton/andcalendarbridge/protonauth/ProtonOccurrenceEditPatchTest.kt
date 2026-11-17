// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class ProtonOccurrenceEditPatchTest {
    private val timestamp = Instant.parse("2030-04-01T12:00:00Z")
    private val time = EventTime.AllDay(LocalDate.parse("2030-04-01"), LocalDate.parse("2030-04-02"))
    private val master = RemoteChange.Upsert(EventId("master"), Revision("revision"), EventContent("Original", time,
        "Notes", "Place", details = EventDetails(), recurrence = EventRecurrence("fixture-uid", "FREQ=DAILY;COUNT=5")))
    private val opaque = "X-OPAQUE;X-NOTE=\"a:b\":Keep bytes\r\n\tand folding\r\n"
    private fun wrap(body: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:fixture-uid\r\nDTSTAMP:20300330T120000Z\r\n" + body + "END:VEVENT\r\nEND:VCALENDAR\r\n"
    private fun parts(dates: String = "DTSTART;VALUE=DATE:20300401\r\nDTEND;VALUE=DATE:20300402\r\n") =
        ProtonEventTextParts(wrap("SEQUENCE:7\r\n" + dates + "RRULE:FREQ=DAILY;COUNT=5\r\n" + opaque),
            wrap("SUMMARY:Original\r\nDESCRIPTION:Notes\r\nLOCATION:Place\r\n" + opaque))
    private val original = RecurrencePoint.AllDay(LocalDate.parse("2030-04-02"))
    private val generated = master.content.copy(time = EventTime.AllDay(LocalDate.parse("2030-04-02"), LocalDate.parse("2030-04-03")), recurrence = null)
    private fun patch(content: EventContent, source: ProtonEventTextParts = parts()) = ProtonSharedTextPatcher.patchOccurrenceAt(
        source, OccurrenceEditCandidate(master, original, content), timestamp)

    @Test fun titleOnlyChildKeepsMasterUidSequenceAndUnknownBytesAndStripsRule() {
        val source = parts()
        val changed = patch(generated.copy(title = "Edited"), source)
        assertTrue(changed.clearShared.contains("RECURRENCE-ID;VALUE=DATE:20300402\r\n"))
        assertTrue(changed.clearShared.contains("SEQUENCE:7\r\n")); assertFalse(changed.clearShared.contains("RRULE"))
        assertFalse(changed.encryptedShared.contains("RECURRENCE-ID"))
        for (text in listOf(changed.clearShared, changed.encryptedShared)) {
            assertTrue(text.contains(opaque)); assertTrue(text.contains("UID:fixture-uid\r\n"))
            assertTrue(text.contains("DTSTAMP:20300401T120000Z\r\n"))
        }
        assertTrue(changed.encryptedShared.contains("SUMMARY:Edited\r\n"))
        assertTrue(source.clearShared.contains("RRULE:FREQ=DAILY;COUNT=5\r\n"))
    }
    @Test fun movedAndKindChangedChildIncrementsOnceButOriginalDateRemainsUnchanged() {
        val moved = patch(generated.copy(time = EventTime.Timed(Instant.parse("2030-04-04T10:00:00Z"),
            Instant.parse("2030-04-04T11:00:00Z"), ZoneOffset.UTC)))
        assertTrue(moved.clearShared.contains("SEQUENCE:8\r\n"))
        assertTrue(moved.clearShared.contains("RECURRENCE-ID;VALUE=DATE:20300402\r\n"))
        assertTrue(moved.clearShared.contains("DTSTART:20300404T100000Z\r\n"))
    }
    @Test fun missingOrInvalidSequenceForeignUidRuleAndStaleBaseAreRefused() {
        val source = parts()
        for (clear in listOf(source.clearShared.replace("SEQUENCE:7\r\n", ""),
            source.clearShared.replace("SEQUENCE:7", "SEQUENCE:-1"),
            source.clearShared.replace("SEQUENCE:7", "SEQUENCE:2147483647"),
            source.clearShared.replace("UID:fixture-uid", "UID:foreign"),
            source.clearShared.replace("COUNT=5", "COUNT=4"),
            source.clearShared.replace("20300401\r\nDTEND", "20300403\r\nDTEND")))
            refused { patch(generated.copy(title = "Edited"), ProtonEventTextParts(clear, source.encryptedShared)) }
        refused { patch(generated.copy(title = "Edited"), ProtonEventTextParts(source.clearShared,
            source.encryptedShared.replace("SUMMARY:Original", "SUMMARY:Foreign"))) }
    }
    @Test fun timedOriginalUsesMasterTimezoneAcrossDstEvenWhenChildBecomesAllDay() {
        val timed = EventTime.Timed(Instant.parse("2030-03-30T08:00:00Z"), Instant.parse("2030-03-30T09:00:00Z"), ZoneId.of("Europe/Paris"))
        val parent = master.copy(content = master.content.copy(time = timed))
        val candidate = OccurrenceEditCandidate(parent, RecurrencePoint.Timed(Instant.parse("2030-03-31T07:00:00Z")),
            generated.copy(title = "Edited"))
        val changed = ProtonSharedTextPatcher.patchOccurrenceAt(parts("DTSTART;TZID=Europe/Paris:20300330T090000\r\nDTEND;TZID=Europe/Paris:20300330T100000\r\n"), candidate, timestamp)
        assertTrue(changed.clearShared.contains("RECURRENCE-ID;TZID=Europe/Paris:20300331T090000\r\n"))
        assertTrue(changed.clearShared.contains("DTSTART;VALUE=DATE:20300402\r\n"))
        assertTrue(changed.clearShared.contains("SEQUENCE:8\r\n"))
    }
    private fun refused(block: () -> Unit) { assertThrows(ProtonPersonalPatchException::class.java, block) }
    @Test fun personalFlagUsesProtonPlainTextTrimmingAndIgnoresPersonalSiblings() {
        fun flag(content: EventContent) = ProtonSharedTextPatcher.isPersonalSingleOccurrence(OccurrenceEditCandidate(master, original, content))
        assertTrue(flag(generated.copy(title = "\ufeff Original\u00a0")))
        assertTrue(flag(generated.copy(details = EventDetails(listOf(EventReminder(10))), colorRgb = 0x415df0)))
        assertFalse(flag(generated.copy(title = "New title")))
        assertFalse(flag(generated.copy(time = time)))
    }
    @Test fun changedHtmlOrEntityTitlesAreHeldInsteadOfGuessingBrowserSanitization() {
        for (title in listOf("<b>Original</b>", "Original &amp; title", "A > B", "A & B"))
            refused { patch(generated.copy(title = title)) }
    }
    @Test fun equivalentUtcBoundsKeepSequenceAndPersonalFlagAcrossAZoneChange() {
        val time = EventTime.Timed(Instant.parse("2030-03-30T08:00:00Z"), Instant.parse("2030-03-30T09:00:00Z"), ZoneId.of("Europe/Paris"))
        val parent = master.copy(content = master.content.copy(time = time))
        val child = master.content.copy(time = EventTime.Timed(Instant.parse("2030-03-31T07:00:00Z"), Instant.parse("2030-03-31T08:00:00Z"), ZoneOffset.UTC), recurrence = null)
        val candidate = OccurrenceEditCandidate(parent, RecurrencePoint.Timed(Instant.parse("2030-03-31T07:00:00Z")), child)
        assertTrue(ProtonSharedTextPatcher.isPersonalSingleOccurrence(candidate))
        val changed = ProtonSharedTextPatcher.patchOccurrenceAt(parts("DTSTART;TZID=Europe/Paris:20300330T090000\r\nDTEND;TZID=Europe/Paris:20300330T100000\r\n"), candidate, timestamp)
        assertTrue(changed.clearShared.contains("SEQUENCE:7\r\n"))
        assertTrue(changed.clearShared.contains("RECURRENCE-ID;TZID=Europe/Paris:20300331T090000\r\n"))
    }
}
