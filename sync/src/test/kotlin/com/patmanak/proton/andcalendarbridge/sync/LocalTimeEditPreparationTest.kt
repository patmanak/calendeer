// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.charset.CharacterCodingException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LocalTimeEditPreparationTest {
    private val stamp = Instant.parse("2026-10-03T09:30:00Z")
    private val day = EventTime.AllDay(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 5))
    private val base = RemoteChange.Upsert(EventId("fixture-event"), Revision("fixture-revision"),
        EventContent("Fixture", day, details = EventDetails()))
    private val intent = LocalEditIntent(MutationId("fixture-owner"),
        CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 1),
        AdapterFormat("fixture-observation", 1), base, base.content.copy(time = day.copy(endExclusive = day.endExclusive.plusDays(1))))
    private val format = AdapterFormat("fixture-time-preparation", 1)
    private fun prepared(timestamp: Instant = stamp, adapter: AdapterFormat = format, payload: String = "protected") =
        LocalTimeEditPreparation(intent, timestamp, adapter, payload)
    private fun refused(action: () -> Unit) {
        try { action(); fail("Expected bounded preparation refusal") }
        catch (_: IllegalArgumentException) { }
        catch (_: CharacterCodingException) { }
    }

    @Test fun recordRetainsExactFrozenIntentAndRedactsDiagnostics() {
        val actual = prepared()
        assertEquals(intent, actual.intent); assertNotSame(intent, actual.intent)
        assertEquals(stamp, actual.timestamp); assertEquals(format, actual.format)
        assertEquals("protected", actual.protectedPayload)
        assertEquals("LocalTimeEditPreparation(REDACTED)", actual.toString())
    }

    @Test fun timestampRequiresWholeSecondsInClosedUtcRange() {
        for (bad in listOf(stamp.plusNanos(1), Instant.parse("1969-12-31T23:59:59Z"),
            Instant.parse("+10000-01-01T00:00:00Z"), Instant.MIN, Instant.MAX)) refused { prepared(timestamp = bad) }
        assertEquals(Instant.EPOCH, prepared(timestamp = Instant.EPOCH).timestamp)
        val last = Instant.parse("9999-12-31T23:59:59Z")
        assertEquals(last, prepared(timestamp = last).timestamp)
    }

    @Test fun rawContextAndPayloadHaveStrictUtf8Bounds() {
        assertEquals(2048, prepared(adapter = AdapterFormat("é".repeat(2048), 1)).format.id.length)
        for (bad in listOf("é".repeat(2049), "\uD800")) refused { prepared(adapter = AdapterFormat(bad, 1)) }
        assertEquals(2097152, prepared(payload = "é".repeat(2097152)).protectedPayload.length)
        for (bad in listOf("", " \t\r\n", "\uD800", "é".repeat(2097153))) refused { prepared(payload = bad) }
    }

    @Test fun guardedDefaultDeniesWithoutBorrowingUnrestrictedPreparation() = runTest {
        var unrestricted = 0
        val port = CalendarLocalTimePreparationPort { _, _ -> unrestricted++; prepared() }
        assertNull(port.localTimeFormat)
        assertNull(port.prepareLocalTime(intent, stamp) { true })
        assertEquals(0, unrestricted)
    }
}
