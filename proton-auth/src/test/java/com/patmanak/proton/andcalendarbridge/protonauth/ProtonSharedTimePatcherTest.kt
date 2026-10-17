// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.EventTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class ProtonSharedTimePatcherTest {
    private val now = Instant.parse("2026-10-17T12:00:00Z")
    private val old = EventTime.Timed(Instant.parse("2026-10-17T09:00:00Z"), Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC)
    private val next = old.copy(start = old.start.plusSeconds(3600), end = old.end.plusSeconds(7200))
    private val opaque = "X-OPAQUE;X-NOTE=\"a:b\":Keep  bytes\r\n\tand folding\r\n"
    private fun wrap(body: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:fixture\r\nBEGIN:VEVENT\r\n" +
        "UID:fixture-uid\r\nDTSTAMP:20261016T120000Z\r\n" + body + "END:VEVENT\r\nEND:VCALENDAR\r\n"
    private fun parts(dates: String = "DTSTART:20261017T090000Z\r\nDTEND:20261017T100000Z\r\n") =
        ProtonEventTextParts(wrap("CREATED:20261015T120000Z\r\nLAST-MODIFIED:20261016T120000Z\r\nSEQUENCE:7\r\n" + dates + opaque),
            wrap("SUMMARY:Fixture\r\nDESCRIPTION:Notes\\nNext\r\n" + opaque))
    private fun patch(source: ProtonEventTextParts = parts(), expected: EventTime = old, replacement: EventTime = next,
        stamp: Instant = now, uid: String = "fixture-uid") =
        ProtonSharedTextPatcher.patchTimeAt(source, uid, expected, replacement, stamp)
    private fun refused(action: () -> Unit) {
        try { action(); fail("Expected time patch refusal") }
        catch (failure: ProtonTimePatchException) {
            assertNull(failure.cause)
            assertEquals("Shared time patch unavailable: UNSUPPORTED", failure.message)
        }
    }

    @Test fun movementAndDurationPatchOnlyDatesSequenceAndModificationStamps() {
        val original = parts()
        val result = patch(original)
        assertEquals(original.clearShared.replace("20261016T120000Z", "20261017T120000Z")
            .replace("SEQUENCE:7", "SEQUENCE:8").replace("DTSTART:20261017T090000Z", "DTSTART:20261017T100000Z")
            .replace("DTEND:20261017T100000Z", "DTEND:20261017T120000Z"), result.clearShared)
        assertEquals(original.encryptedShared.replace("20261016T120000Z", "20261017T120000Z"), result.encryptedShared)
    }

    @Test fun exactNoOpReturnsSameObjectEvenWithOldExplicitTimestamp() {
        val original = parts()
        assertSame(original, patch(original, replacement = old, stamp = Instant.EPOCH))
    }

    @Test fun allDayEndRemainsExclusiveWithoutTimezone() {
        val day = EventTime.AllDay(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 18))
        val result = patch(parts("DTSTART;VALUE=DATE:20261017\r\nDTEND;VALUE=DATE:20261018\r\n"), day,
            day.copy(start = day.start.plusDays(1), endExclusive = day.start.plusDays(4)))
        assertTrue(result.clearShared.contains("DTSTART;VALUE=DATE:20261018\r\nDTEND;VALUE=DATE:20261021\r\n"))
        assertFalse(result.clearShared.contains("TZID"))
    }

    @Test fun namedZoneCanMoveAcrossSpringAndAutumnDstWithExactInstants() {
        val zone = ZoneId.of("Europe/Paris")
        for ((date, hours) in listOf(LocalDate.of(2026, 3, 28) to 23L, LocalDate.of(2026, 10, 24) to 25L)) {
            val start = date.atTime(12, 0).atZone(zone)
            val end = start.plusDays(1)
            val expected = EventTime.Timed(start.toInstant(), end.toInstant(), zone)
            assertEquals(hours * 3600, java.time.Duration.between(expected.start, expected.end).seconds)
            val digits = java.time.format.DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")
            val dates = "DTSTART;TZID=Europe/Paris:${digits.format(start)}\r\nDTEND;TZID=Europe/Paris:${digits.format(end)}\r\n"
            val changed = expected.copy(end = expected.end.plusSeconds(3600))
            val result = patch(parts(dates), expected, changed)
            assertTrue(result.clearShared.contains("DTEND;TZID=Europe/Paris:${digits.format(end.plusHours(1))}\r\n"))
            assertTrue(result.clearShared.contains(opaque))
        }
    }

    @Test fun identityTimePrecisionZoneAndBackdatedChangesAreRefused() {
        refused { patch(uid = "other") }
        refused { patch(expected = old.copy(end = old.end.plusSeconds(1))) }
        refused { patch(replacement = next.copy(end = next.end.plusNanos(1))) }
        refused { patch(replacement = next.copy(zone = ZoneId.of("Europe/Paris"))) }
        refused { patch(replacement = EventTime.AllDay(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 18))) }
        refused { patch(stamp = now.plusNanos(1)) }
        refused { patch(stamp = Instant.EPOCH) }
    }

    @Test fun missingDuplicateFloatingDurationParametersAndNestedComponentsAreRefused() {
        for (dates in listOf("DTSTART:20261017T090000Z\r\n",
            "DTSTART:20261017T090000Z\r\nDTSTART:20261017T090000Z\r\nDTEND:20261017T100000Z\r\n",
            "DTSTART:20261017T090000\r\nDTEND:20261017T100000\r\n",
            "DTSTART;X-UNSAFE=1:20261017T090000Z\r\nDTEND:20261017T100000Z\r\n",
            "DTSTART:20261017T090000Z\r\nDTEND:20261017T100000Z\r\nDURATION:PT1H\r\n",
            "DTSTART:20261017T090000Z\r\nDTEND:20261017T100000Z\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\nEND:VALARM\r\n"))
            refused { patch(parts(dates)) }
        val original = parts()
        refused { patch(ProtonEventTextParts(original.clearShared, original.encryptedShared.replace("SUMMARY:", "DTSTART:20261017T090000Z\r\nSUMMARY:"))) }
        refused { patch(ProtonEventTextParts(original.clearShared.replace("BEGIN:VEVENT", "BEGIN:VTIMEZONE\r\nTZID:Europe/Paris\r\nEND:VTIMEZONE\r\nBEGIN:VEVENT"), original.encryptedShared)) }
    }

    @Test fun mismatchedStampsInvalidMetadataAndSequenceOverflowAreRefused() {
        val original = parts()
        refused { patch(ProtonEventTextParts(original.clearShared, original.encryptedShared.replace("20261016T120000Z", "20261016T120001Z"))) }
        for (sequence in listOf("2147483647", "2147483648", "-1", "abc"))
            refused { patch(ProtonEventTextParts(original.clearShared.replace("SEQUENCE:7", "SEQUENCE:$sequence"), original.encryptedShared)) }
        refused { patch(ProtonEventTextParts(original.clearShared.replace("CREATED:20261015T120000Z", "CREATED:20261018T120000Z"), original.encryptedShared)) }
    }

    @Test fun ambiguousOriginalAndReplacementOffsetsAreRefused() {
        val zone = ZoneId.of("Europe/Paris")
        val ambiguous = EventTime.Timed(Instant.parse("2026-10-25T00:30:00Z"), Instant.parse("2026-10-25T02:30:00Z"), zone)
        refused { patch(parts("DTSTART;TZID=Europe/Paris:20261025T023000\r\nDTEND;TZID=Europe/Paris:20261025T033000\r\n"), ambiguous, ambiguous.copy(end = ambiguous.end.plusSeconds(60))) }
        val normal = old.copy(zone = zone)
        refused { patch(parts("DTSTART;TZID=Europe/Paris:20261017T110000\r\nDTEND;TZID=Europe/Paris:20261017T120000\r\n"), normal, ambiguous) }
    }

    @Test fun historicalTextPatchStillRetainsSequence() {
        val result = ProtonSharedTextPatcher.patchAt(parts(), mapOf(ProtonSharedTextProperty.SUMMARY to ProtonSharedTextChange("Fixture", "Next")), "fixture-uid", now)
        assertTrue(result.clearShared.contains("SEQUENCE:7\r\n"))
    }

    @Test fun foldedLowercaseDatesAndSequenceAreReplacedWithoutTouchingOtherSpans() {
        val original = parts("dtstart:20261017T09\r\n 0000Z\r\ndtend:20261017T100000Z\r\n")
        val folded = ProtonEventTextParts(original.clearShared.replace("SEQUENCE:7", "sequence:000\r\n 7"), original.encryptedShared)
        val result = patch(folded)
        assertTrue(result.clearShared.contains("sequence:8\r\n"))
        assertTrue(result.clearShared.contains("DTSTART:20261017T100000Z\r\nDTEND:20261017T120000Z\r\n"))
        assertTrue(result.clearShared.contains(opaque))
        assertEquals(original.encryptedShared.replace("20261016T120000Z", "20261017T120000Z"), result.encryptedShared)
    }

    @Test fun unsupportedSharedProfilesAndMalformedFramingAreRefusedWithoutInputInDiagnostics() {
        val original = parts()
        for (marker in listOf("RRULE:FREQ=DAILY", "ATTENDEE:mailto:fixture@example.invalid", "RECURRENCE-ID:20261017T090000Z", "METHOD:PUBLISH")) {
            refused { patch(ProtonEventTextParts(original.clearShared.replace(opaque, "$marker\r\n$opaque"), original.encryptedShared)) }
            refused { patch(ProtonEventTextParts(original.clearShared, original.encryptedShared.replace(opaque, "$marker\r\n$opaque"))) }
        }
        for (clear in listOf(original.clearShared.replace("\r\n", "\n"), original.clearShared + "\r\n",
            original.clearShared.replace("VERSION:2.0", "VERSION:2.0\r\nVERSION:2.0"),
            original.clearShared.replace("UID:fixture-uid", "UID:fixture-uid\r\nUID:fixture-uid"),
            original.clearShared.replace("DTSTAMP:20261016T120000Z", "DTSTAMP:20260230T120000Z")))
            refused { patch(ProtonEventTextParts(clear, original.encryptedShared)) }
    }

    @Test fun sourceAndReplacementDateBoundsAndMismatchedTimezoneParametersAreRefused() {
        refused { patch(parts("DTSTART;TZID=UTC:20261017T090000\r\nDTEND;TZID=Europe/Paris:20261017T120000\r\n")) }
        refused { patch(parts("DTSTART;VALUE=DATE:20260230\r\nDTEND;VALUE=DATE:20260302\r\n")) }
        refused { patch(replacement = next.copy(end = Instant.parse("+10000-01-01T00:00:00Z"))) }
        refused { patch(replacement = next.copy(start = Instant.parse("1969-12-31T23:59:59Z"))) }
        val day = EventTime.AllDay(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 18))
        refused { patch(parts("DTSTART;VALUE=DATE:20261017\r\nDTEND;VALUE=DATE:20261018\r\n"), day,
            day.copy(endExclusive = LocalDate.of(10000, 1, 1))) }
        val sameInstantDifferentZone = old.copy(zone = ZoneId.of("UTC"))
        refused { patch(expected = sameInstantDifferentZone, replacement = sameInstantDifferentZone.copy(end = next.end)) }
    }
}
