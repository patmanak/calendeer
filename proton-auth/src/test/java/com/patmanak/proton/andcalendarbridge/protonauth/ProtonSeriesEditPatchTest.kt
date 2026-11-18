// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class ProtonSeriesEditPatchTest {
    @Test fun signedCalendarStampBindsBothSeriesBodiesWithoutDroppingTheRule() {
        val source = parts()
        val after = before.copy(title = "Edited", recurrence = recurrence.copy(rule = "FREQ=DAILY;COUNT=8"))
        val changed = patch(after)
        val calendar = wrap("TRANSP:OPAQUE\r\nSTATUS:CONFIRMED\r\n")
        assertEquals(calendar.replace("20261016T120000Z", "20261017T120000Z"),
            ProtonSharedTextPatcher.calendarSeriesPart(calendar, "fixture-uid", source, changed, before, after))
        refused { ProtonSharedTextPatcher.calendarSeriesPart(calendar, "fixture-uid", source, changed,
            before.copy(description = "Foreign notes"), after) }
        refused { ProtonSharedTextPatcher.calendarSeriesPart(calendar, "fixture-uid", source, changed, before,
            after.copy(recurrence = recurrence)) }
    }

    private val timestamp = Instant.parse("2026-10-17T12:00:00Z")
    private val time = EventTime.Timed(Instant.parse("2026-10-17T09:00:00Z"), Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC)
    private val recurrence = EventRecurrence("fixture-uid", "FREQ=DAILY;COUNT=5")
    private val before = EventContent("Original", time, "Notes", "Place", details = EventDetails(), recurrence = recurrence)
    private val opaque = "X-OPAQUE;X-NOTE=\"a:b\":Keep  bytes\r\n\tand folding\r\n"
    private fun wrap(body: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:fixture\r\nBEGIN:VEVENT\r\nUID:fixture-uid\r\nDTSTAMP:20261016T120000Z\r\n" + body + "END:VEVENT\r\nEND:VCALENDAR\r\n"
    private fun parts(rule: String = "RRULE:FREQ=DAILY;COUNT=5\r\n",
        dates: String = "DTSTART:20261017T090000Z\r\nDTEND:20261017T100000Z\r\n") = ProtonEventTextParts(
        wrap("CREATED:20261015T120000Z\r\nLAST-MODIFIED:20261016T120000Z\r\nSEQUENCE:7\r\n" + dates + rule + opaque),
        wrap("SUMMARY:Original\r\nDESCRIPTION:Notes\r\nLOCATION:Place\r\n" + opaque))
    private fun patch(after: EventContent, source: ProtonEventTextParts = parts(), base: EventContent = before) =
        ProtonSharedTextPatcher.patchSeriesAt(source, "fixture-uid", base, after, timestamp)
    private fun refused(block: () -> Unit) {
        try { block(); fail("Expected refusal") } catch (_: ProtonPersonalPatchException) { }
    }
    @Test fun exclusionUsesTheOriginalSlotAndPreservesPreviouslySignedBytes() {
        val first = RecurrencePoint.Timed(time.start)
        val second = RecurrencePoint.Timed(time.start.plusSeconds(86400))
        val old = RemoteChange.Upsert(EventId("master"), Revision("revision"),
            before.copy(recurrence = recurrence.copy(excludedDates = listOf(first))))
        val plan = requireNotNull(seriesRemovalPlan(old, listOf(old.eventId), listOf(old), SeriesRemovalTarget.Single(second)))
        val input = parts(rule = "RRULE:FREQ=DAILY;COUNT=5\r\nEXDATE:20261017T090000Z\r\n")
        val patched = ProtonSharedTextPatcher.addExcludedSlot(input, old, plan)
        assertEquals(input.clearShared, patched.clearShared.replace("EXDATE:20261018T090000Z\r\n", ""))
        assertEquals(input.encryptedShared, patched.encryptedShared)
        refused { ProtonSharedTextPatcher.addExcludedSlot(parts(), old, plan) }
        refused { ProtonSharedTextPatcher.addExcludedSlot(input, old.copy(revision = Revision("later")), plan) }
    }

    @Test fun exclusionRetainsDateTypeAndLocalTimeZone() {
        val allDay = java.time.LocalDate.of(2026, 10, 17)
        val local = java.time.ZoneId.of("Europe/Paris")
        val cases = listOf(
            Triple(EventTime.AllDay(allDay, allDay.plusDays(1)),
                "DTSTART;VALUE=DATE:20261017\r\nDTEND;VALUE=DATE:20261018\r\n",
                RecurrencePoint.AllDay(allDay.plusDays(1)) to "EXDATE;VALUE=DATE:20261018"),
            Triple(EventTime.Timed(time.start, time.end, local),
                "DTSTART;TZID=Europe/Paris:20261017T110000\r\nDTEND;TZID=Europe/Paris:20261017T120000\r\n",
                RecurrencePoint.Timed(time.start.plusSeconds(86400)) to "EXDATE;TZID=Europe/Paris:20261018T110000")
        )
        for ((sourceTime, dates, expected) in cases) {
            val master = RemoteChange.Upsert(EventId("master"), Revision("revision"), before.copy(time = sourceTime))
            val plan = requireNotNull(seriesRemovalPlan(master, listOf(master.eventId), listOf(master),
                SeriesRemovalTarget.Single(expected.first)))
            val input = parts(dates = dates)
            val result = ProtonSharedTextPatcher.addExcludedSlot(input, master, plan)
            assertEquals(input.clearShared, result.clearShared.replace(expected.second + "\r\n", ""))
        }
    }

    @Test fun ruleOnlyEditAdvancesSequenceOnceAndPreservesOpaqueProperties() {
        ProtonSharedTextPatcher.assertSeriesBase(parts(), "fixture-uid", before)
        val after = before.copy(recurrence = recurrence.copy(rule = "FREQ=DAILY;COUNT=8;INTERVAL=2"))
        val actual = patch(after)
        assertTrue(actual.clearShared.contains("RRULE:FREQ=DAILY;COUNT=8;INTERVAL=2\r\n"))
        assertTrue(actual.clearShared.contains("SEQUENCE:8\r\n")); assertFalse(actual.clearShared.contains("SEQUENCE:9"))
        for (part in listOf(actual.clearShared, actual.encryptedShared)) {
            assertTrue(part.contains(opaque)); assertTrue(part.contains("DTSTAMP:20261017T120000Z\r\n"))
        }
        assertFalse(actual.encryptedShared.contains("RRULE")); assertTrue(actual.encryptedShared.contains("SUMMARY:Original\r\n"))
    }
    @Test fun combinedTextDatesAndRuleEditsKeepTheCanonicalSeriesUid() {
        val after = before.copy(title = "Edited", description = "New notes", location = "New place",
            time = time.copy(end = time.end.plusSeconds(3600)), recurrence = recurrence.copy(rule = "FREQ=DAILY;COUNT=8"))
        val actual = patch(after)
        assertTrue(actual.clearShared.contains("DTEND:20261017T110000Z\r\n"))
        assertTrue(actual.clearShared.contains("RRULE:FREQ=DAILY;COUNT=8\r\n"))
        assertTrue(actual.encryptedShared.contains("SUMMARY:Edited\r\n"))
        assertTrue(actual.encryptedShared.contains("DESCRIPTION:New notes\r\nLOCATION:New place\r\n"))
        for (part in listOf(actual.clearShared, actual.encryptedShared)) assertTrue(part.contains("UID:fixture-uid\r\n"))
    }
    @Test fun unchangedRuleKeepsItsRawFoldedBytesAndOptionalWebFraming() {
        val rule = "RRULE:COUNT=5;\r\n FREQ=DAILY\r\n"
        val source = parts(rule).let { ProtonEventTextParts(it.clearShared.removeSuffix("\r\n"), it.encryptedShared.removeSuffix("\r\n")) }
        val actual = patch(before.copy(title = "Edited"), source)
        assertTrue(actual.clearShared.contains(rule)); assertFalse(actual.clearShared.endsWith("\r\n"))
        assertFalse(actual.encryptedShared.endsWith("\r\n")); assertTrue(actual.clearShared.contains(opaque))
    }
    @Test fun allDayAndTimeKindTransitionsKeepExclusiveEndAndTheRule() {
        val day = EventTime.AllDay(LocalDate.parse("2026-10-17"), LocalDate.parse("2026-10-20"))
        val dates = "DTSTART;VALUE=DATE:20261017\r\nDTEND;VALUE=DATE:20261020\r\n"
        assertTrue(patch(before.copy(time = day)).clearShared.contains(dates))
        assertTrue(patch(before, parts(dates = dates), before.copy(time = day)).clearShared.contains("DTEND:20261017T100000Z\r\n"))
    }
    @Test fun duplicateMismatchedOrUnsupportedRecurrenceAndParticipantsRemainClosed() {
        for (rule in listOf("", "RRULE:FREQ=DAILY;COUNT=6\r\n", "RRULE:FREQ=WEEKLY;COUNT=5\r\n",
            "RRULE:FREQ=DAILY;COUNT=5\r\nRRULE:FREQ=DAILY;COUNT=5\r\n", "RRULE;X-UNKNOWN=1:FREQ=DAILY;COUNT=5\r\n",
            "RRULE:FREQ=DAILY;COUNT=5\r\nEXDATE:20261018T090000Z\r\n",
            "RRULE:FREQ=DAILY;COUNT=5\r\nATTENDEE:mailto:fixture@example.invalid\r\n")) refused { patch(before.copy(title = "Edited"), parts(rule)) }
        refused { patch(before.copy(recurrence = recurrence.copy(uid = "foreign"))) }
        val source = parts()
        refused { ProtonSharedTextPatcher.assertSeriesBase(source, "foreign", before) }
        refused { ProtonSharedTextPatcher.assertSeriesBase(source, "fixture-uid", before.copy(title = "Foreign")) }
        refused { ProtonSharedTextPatcher.assertSeriesBase(source, "fixture-uid", before.copy(description = "Foreign notes")) }
        refused { ProtonSharedTextPatcher.assertSeriesBase(source, "fixture-uid", before.copy(location = "Foreign place")) }
        refused { ProtonSharedTextPatcher.assertSeriesBase(source, "fixture-uid", before.copy(time = time.copy(end = time.end.plusSeconds(60)))) }
        val moved = ProtonEventTextParts(source.clearShared.replace("RRULE:FREQ=DAILY;COUNT=5\r\n", "")
            .replace("BEGIN:VEVENT\r\n", "RRULE:FREQ=DAILY;COUNT=5\r\nBEGIN:VEVENT\r\n"), source.encryptedShared)
        refused { patch(before.copy(title = "Edited"), moved) }
        refused { patch(before.copy(title = "Edited"), ProtonEventTextParts(source.clearShared, source.encryptedShared.replace(opaque, "RRULE:FREQ=DAILY;COUNT=5\r\n" + opaque))) }
        refused { ProtonSharedTextPatcher.patchPersonalAt(source, "fixture-uid", before.copy(recurrence = null), before.copy(title = "Edited", recurrence = null), timestamp) }
    }
}
