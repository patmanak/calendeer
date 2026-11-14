// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import biweekly.Biweekly
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class ProtonSeriesEventPreparationTest {
    private val uid = "synthetic-series@calendeer.invalid"
    private val stamp = Instant.parse("2030-03-01T12:00:00Z")
    private val timed = EventContent("Série 🦌", EventTime.Timed(Instant.parse("2030-03-29T11:00:00Z"),
        Instant.parse("2030-03-29T12:00:00Z"), ZoneId.of("Europe/Paris")), "Line one\nLine two", "Synthetic room")
    @Test fun ruleIsSignedInClearSharedContentAndHistoricalTextBytesRemainUnchanged() {
        val rule = CreateSeriesRule(5)
        val legacy = ProtonSimpleEventSerializer.serialize(uid, stamp, timed)
        val parts = ProtonSimpleEventSerializer.serializeSeries(uid, stamp, timed, rule)
        val clear = Biweekly.parse(parts.clearShared).first().events.single()
        assertNotNull(clear.recurrenceRule)
        assertEquals(uid, clear.uid.value); assertEquals(0, clear.sequence.value.toInt())
        assertEquals(legacy.clearShared, parts.clearShared.replace(Regex("(?m)^RRULE:[^\\r\\n]*\\r?\\n"), ""))
        assertEquals(legacy.encryptedShared, parts.encryptedShared)
        assertFalse(parts.encryptedShared.contains("RRULE:")); assertFalse(parts.clearShared.contains("SUMMARY:"))
        val restored = ProtonICalendar.parse(listOf(parts.clearShared, parts.encryptedShared))
        assertEquals(rule, CreateSeriesRule.parse(restored.recurrence!!.rule!!))
        assertEquals(uid, restored.recurrence!!.uid)
        assertEquals(timed, restored.copy(recurrence = null, description = restored.description.replace("\r\n", "\n")))
    }
    @Test fun allDayExclusiveEndsAndIntervalsRoundTripWithoutTimezoneMaterialization() {
        val content = timed.copy(time = EventTime.AllDay(LocalDate.parse("2030-03-29"), LocalDate.parse("2030-03-31")), description = "")
        val rule = CreateSeriesRule(3, 2)
        val parts = ProtonSimpleEventSerializer.serializeSeries(uid, stamp, content, rule)
        assertTrue(parts.clearShared.contains("DTSTART;VALUE=DATE:20300329"))
        assertTrue(parts.clearShared.contains("DTEND;VALUE=DATE:20300331"))
        assertFalse(parts.clearShared.contains("BEGIN:VTIMEZONE"))
        val restored = ProtonICalendar.parse(listOf(parts.clearShared, parts.encryptedShared))
        assertEquals(rule, CreateSeriesRule.parse(restored.recurrence!!.rule!!))
        assertEquals(content, restored.copy(recurrence = null))
    }
    @Test fun unsupportedFutureSlotsAndRichSharedFieldsAreRejectedBeforeEncryption() {
        val unsafe = timed.copy(time = EventTime.Timed(Instant.parse("2030-03-29T01:30:00Z"),
            Instant.parse("2030-03-29T02:30:00Z"), ZoneId.of("Europe/Paris")))
        for (content in listOf(unsafe, timed.copy(colorRgb = 1),
            timed.copy(details = EventDetails(listOf(EventReminder(10)))),
            timed.copy(recurrence = EventRecurrence(uid, "FREQ=DAILY;COUNT=5")))) {
            assertThrows(ProtonEventPreparationException::class.java) {
                ProtonSimpleEventSerializer.serializeSeries(uid, stamp, content, CreateSeriesRule(5))
            }
        }
    }
    @Test fun historicalSerializerStillRefusesCanonicalRecurringContent() {
        assertThrows(ProtonEventPreparationException::class.java) {
            ProtonSimpleEventSerializer.serialize(uid, stamp, timed.copy(recurrence = EventRecurrence(uid, "FREQ=DAILY;COUNT=5")))
        }
    }
}
