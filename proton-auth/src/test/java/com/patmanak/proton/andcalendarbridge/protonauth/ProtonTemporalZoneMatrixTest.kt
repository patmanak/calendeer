// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.EventContent
import com.patmanak.proton.andcalendarbridge.sync.EventTime
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

/** Literal UTC and wall-clock expectations: no expected value is derived by the serializer under test. */
class ProtonTemporalZoneMatrixTest {
    private val uid = "synthetic-zone-matrix@calendeer.invalid"
    private val stamp = Instant.parse("2026-01-01T00:00:00Z")
    private val opaque = "X-SYNTHETIC;X-NOTE=\"a:b\":Keep  bytes\r\n\tand folding\r\n"

    private data class Case(
        val zone: String,
        val oldStart: String, val oldEnd: String, val oldLocalStart: String, val oldLocalEnd: String,
        val nextStart: String, val nextEnd: String, val nextLocalStart: String, val nextLocalEnd: String,
        val oldMinutes: Long, val nextMinutes: Long,
    ) {
        fun old() = EventTime.Timed(Instant.parse(oldStart), Instant.parse(oldEnd), ZoneId.of(zone))
        fun next() = EventTime.Timed(Instant.parse(nextStart), Instant.parse(nextEnd), ZoneId.of(zone))
    }

    private val cases = listOf(
        Case("Europe/Paris", "2026-03-28T11:00:00Z", "2026-03-29T10:00:00Z", "20260328T120000", "20260329T120000",
            "2026-03-28T12:00:00Z", "2026-03-29T12:00:00Z", "20260328T130000", "20260329T140000", 1380, 1440),
        Case("Europe/Paris", "2026-10-24T10:00:00Z", "2026-10-25T11:00:00Z", "20261024T120000", "20261025T120000",
            "2026-10-24T11:00:00Z", "2026-10-25T13:00:00Z", "20261024T130000", "20261025T140000", 1500, 1560),
        Case("America/New_York", "2026-03-07T17:00:00Z", "2026-03-08T16:00:00Z", "20260307T120000", "20260308T120000",
            "2026-03-07T18:00:00Z", "2026-03-08T18:00:00Z", "20260307T130000", "20260308T140000", 1380, 1440),
        Case("America/New_York", "2026-10-31T16:00:00Z", "2026-11-01T17:00:00Z", "20261031T120000", "20261101T120000",
            "2026-10-31T17:00:00Z", "2026-11-01T19:00:00Z", "20261031T130000", "20261101T140000", 1500, 1560),
        // The US transition date must not be applied to Europe, or vice versa.
        Case("Europe/Paris", "2026-03-07T11:00:00Z", "2026-03-08T11:00:00Z", "20260307T120000", "20260308T120000",
            "2026-03-07T12:00:00Z", "2026-03-08T13:00:00Z", "20260307T130000", "20260308T140000", 1440, 1500),
        Case("America/New_York", "2026-03-28T16:00:00Z", "2026-03-29T16:00:00Z", "20260328T120000", "20260329T120000",
            "2026-03-28T17:00:00Z", "2026-03-29T18:00:00Z", "20260328T130000", "20260329T140000", 1440, 1500),
        Case("Australia/Lord_Howe", "2026-10-03T01:30:00Z", "2026-10-04T01:00:00Z", "20261003T120000", "20261004T120000",
            "2026-10-03T02:30:00Z", "2026-10-04T03:00:00Z", "20261003T130000", "20261004T140000", 1410, 1470),
        Case("Australia/Lord_Howe", "2026-04-04T01:00:00Z", "2026-04-05T01:30:00Z", "20260404T120000", "20260405T120000",
            "2026-04-04T02:00:00Z", "2026-04-05T03:30:00Z", "20260404T130000", "20260405T140000", 1470, 1530),
        Case("Asia/Kathmandu", "2026-01-01T18:30:00Z", "2026-01-01T19:30:00Z", "20260102T001500", "20260102T011500",
            "2026-01-01T19:30:00Z", "2026-01-01T21:30:00Z", "20260102T011500", "20260102T031500", 60, 120),
        Case("Pacific/Kiritimati", "2026-01-01T09:30:00Z", "2026-01-01T10:30:00Z", "20260101T233000", "20260102T003000",
            "2026-01-01T10:30:00Z", "2026-01-01T12:30:00Z", "20260102T003000", "20260102T023000", 60, 120),
        Case("Pacific/Pago_Pago", "2026-01-02T10:30:00Z", "2026-01-02T11:30:00Z", "20260101T233000", "20260102T003000",
            "2026-01-02T11:30:00Z", "2026-01-02T13:30:00Z", "20260102T003000", "20260102T023000", 60, 120),
    )

    @Test fun serializerAndParserRetainLiteralUtcWallTimesAndExactZoneIds() {
        for (case in cases) for ((time, start, end, minutes) in listOf(
            Endpoint(case.old(), case.oldLocalStart, case.oldLocalEnd, case.oldMinutes),
            Endpoint(case.next(), case.nextLocalStart, case.nextLocalEnd, case.nextMinutes),
        )) {
            val content = EventContent("Synthetic zone matrix", time)
            val parts = ProtonSimpleEventSerializer.serialize(uid, stamp, content)
            assertTrue(parts.clearShared.contains(dates(case.zone, start, end)))
            assertFalse(parts.clearShared.contains("BEGIN:VTIMEZONE"))
            assertFalse(parts.encryptedShared.contains("DTSTART"))
            val parsed = ProtonICalendar.parse(listOf(parts.clearShared, parts.encryptedShared), expectedUid = uid)
            assertEquals(content, parsed)
            assertEquals(case.zone, (parsed.time as EventTime.Timed).zone.id)
            assertEquals(minutes * 60, Duration.between(time.start, time.end).seconds)
        }
    }

    private data class Endpoint(val time: EventTime.Timed, val start: String, val end: String, val minutes: Long)

    @Test fun protectedPatchMovesAndResizesAcrossEachZoneBoundaryWithoutRebuildingRawContent() {
        for (case in cases) {
            val original = source(dates(case.zone, case.oldLocalStart, case.oldLocalEnd))
            val result = ProtonSharedTextPatcher.patchTimeAt(original, uid, case.old(), case.next(), stamp.plusSeconds(60))
            val expectedClear = original.clearShared
                .replace(dates(case.zone, case.oldLocalStart, case.oldLocalEnd), dates(case.zone, case.nextLocalStart, case.nextLocalEnd))
                .replace("DTSTAMP:20260101T000000Z", "DTSTAMP:20260101T000100Z")
                .replace("LAST-MODIFIED:20260101T000000Z", "LAST-MODIFIED:20260101T000100Z")
                .replace("SEQUENCE:7", "SEQUENCE:8")
            assertEquals(expectedClear, result.clearShared)
            assertEquals(original.encryptedShared.replace("DTSTAMP:20260101T000000Z", "DTSTAMP:20260101T000100Z"), result.encryptedShared)
            val time = ProtonICalendar.parse(listOf(result.clearShared, result.encryptedShared), expectedUid = uid).time
            assertEquals(case.next(), time)
            assertEquals(case.zone, (time as EventTime.Timed).zone.id)
            assertEquals(case.nextMinutes * 60, Duration.between(time.start, time.end).seconds)
        }
    }

    @Test fun gapsAndFoldsInEitherOriginalEndpointAreRefusedWithRawPartitionsIntact() {
        val unsafe = listOf(
            Triple("Europe/Paris", "20260329T023000", "2026-03-29T01:30:00Z"),
            Triple("Europe/Paris", "20261025T023000", "2026-10-25T00:30:00Z"),
            Triple("America/New_York", "20260308T023000", "2026-03-08T07:30:00Z"),
            Triple("America/New_York", "20261101T013000", "2026-11-01T05:30:00Z"),
            Triple("Australia/Lord_Howe", "20261004T021500", "2026-10-03T15:45:00Z"),
            Triple("Australia/Lord_Howe", "20260405T014500", "2026-04-04T14:45:00Z"),
        )
        for ((zone, local, coercedInstant) in unsafe) for (endpoint in listOf("DTSTART", "DTEND")) {
            val safeLocals = when (zone) {
                "Europe/Paris" -> "20260102T010000" to "20261231T130000"
                "America/New_York" -> "20260101T190000" to "20261231T070000"
                else -> "20260102T110000" to "20261231T230000"
            }
            val expected = EventTime.Timed(
                Instant.parse(if (endpoint == "DTSTART") coercedInstant else "2026-01-02T00:00:00Z"),
                Instant.parse(if (endpoint == "DTEND") coercedInstant else "2026-12-31T12:00:00Z"), ZoneId.of(zone))
            // Construct valid domain values first. Only the protected source contains the impossible/ambiguous local value.
            val original = source(dates(zone, if (endpoint == "DTSTART") local else safeLocals.first,
                if (endpoint == "DTEND") local else safeLocals.second))
            // The candidate uses the instant a lenient gap/fold resolver could choose; rejection must be at the raw boundary.
            val replacement = if (endpoint == "DTSTART") expected.copy(start = expected.start.minusSeconds(7200))
                else expected.copy(end = expected.end.plusSeconds(7200))
            refusedUnchanged(original, expected, replacement)
        }
    }

    @Test fun bothFoldOccurrencesInEitherReplacementEndpointAreRefused() {
        val folds = listOf(
            Triple("Europe/Paris", "2026-10-25T00:30:00Z", "2026-10-25T01:30:00Z"),
            Triple("America/New_York", "2026-11-01T05:30:00Z", "2026-11-01T06:30:00Z"),
            Triple("Australia/Lord_Howe", "2026-04-04T14:45:00Z", "2026-04-04T15:15:00Z"),
        )
        for ((zone, first, second) in folds) {
            val case = cases.first { it.zone == zone }
            val original = source(dates(zone, case.oldLocalStart, case.oldLocalEnd))
            for (instant in listOf(first, second).map(Instant::parse)) {
                refusedUnchanged(original, case.old(), EventTime.Timed(instant, instant.plusSeconds(7200), ZoneId.of(zone)))
                refusedUnchanged(original, case.old(), EventTime.Timed(instant.minusSeconds(7200), instant, ZoneId.of(zone)))
            }
        }
    }

    @Test fun sameInstantsWithDifferentZoneIdAndKindChangesRemainRefused() {
        val case = cases.first()
        val original = source(dates(case.zone, case.oldLocalStart, case.oldLocalEnd))
        refusedUnchanged(original, case.old(), case.next().copy(zone = ZoneId.of("Europe/Brussels")))
        refusedUnchanged(original, case.old(), EventTime.AllDay(LocalDate.parse("2026-03-28"), LocalDate.parse("2026-03-30")))
    }

    private fun dates(zone: String, start: String, end: String) =
        "DTSTART;TZID=$zone:$start\r\nDTEND;TZID=$zone:$end\r\n"

    private fun source(dates: String): ProtonEventTextParts {
        fun wrap(body: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:synthetic\r\nBEGIN:VEVENT\r\n" +
            "UID:$uid\r\nDTSTAMP:20260101T000000Z\r\n$body" + "END:VEVENT\r\nEND:VCALENDAR\r\n"
        return ProtonEventTextParts(wrap("CREATED:20260101T000000Z\r\nLAST-MODIFIED:20260101T000000Z\r\nSEQUENCE:7\r\n$dates$opaque"),
            wrap("SUMMARY:Synthetic zone matrix\r\n$opaque"))
    }

    private fun refusedUnchanged(original: ProtonEventTextParts, expected: EventTime, replacement: EventTime) {
        val clear = original.clearShared
        val encrypted = original.encryptedShared
        try {
            ProtonSharedTextPatcher.patchTimeAt(original, uid, expected, replacement, stamp.plusSeconds(60))
            fail("Unsafe temporal patch must be refused")
        } catch (failure: ProtonTimePatchException) {
            assertEquals("Shared time patch unavailable: UNSUPPORTED", failure.message)
            assertNull(failure.cause)
        }
        assertEquals(clear, original.clearShared)
        assertEquals(encrypted, original.encryptedShared)
    }
}
