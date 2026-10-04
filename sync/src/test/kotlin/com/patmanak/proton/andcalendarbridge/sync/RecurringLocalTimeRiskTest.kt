package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class RecurringLocalTimeRiskTest {
    private val paris = ZoneId.of("Europe/Paris")
    private fun risk(start: String, rule: String?) = RecurringLocalTimeRisk.detected(Instant.parse(start), paris, rule)

    @Test fun laterFoldAndGapAreDetectedFromTheOriginalLocalStart() {
        assertTrue(risk("2030-10-26T00:30:00Z", "FREQ=DAILY;COUNT=3"))
        assertTrue(risk("2030-03-30T01:30:00Z", "COUNT=3;FREQ=DAILY"))
    }

    @Test fun EndingBeforeOrSkippingTheTransitionRemainsOutsideTheDetectedRisk() {
        for (start in listOf("2030-10-26T00:30:00Z", "2030-03-30T01:30:00Z")) {
            assertFalse(risk(start, "FREQ=DAILY;COUNT=1"))
            assertFalse(risk(start, "FREQ=DAILY;COUNT=2;INTERVAL=2"))
        }
        assertFalse(risk("2030-10-25T00:30:00Z", "FREQ=DAILY;COUNT=2"))
        assertTrue(risk("2030-10-25T00:30:00Z", "FREQ=DAILY;COUNT=3"))
    }

    @Test fun OrdinaryHoursAndFixedOffsetsDoNotBecomeGloballyDeferred() {
        assertFalse(risk("2030-03-30T09:00:00Z", "FREQ=DAILY;COUNT=4096"))
        assertFalse(RecurringLocalTimeRisk.detected(Instant.parse("2030-03-30T01:30:00Z"), ZoneId.of("UTC"), "FREQ=DAILY;COUNT=4096"))
        assertFalse(risk("2030-10-27T00:30:00Z", null))
    }

    @Test fun InitialAmbiguityRemainsDetectedForComplexAndUnboundedRules() {
        for (rule in listOf("FREQ=MONTHLY;BYDAY=SU", "FREQ=DAILY", "FREQ=DAILY;COUNT=4097")) {
            assertTrue(risk("2030-10-27T00:30:00Z", rule))
            assertTrue(risk("2030-10-27T01:30:00Z", rule))
        }
    }

    @Test fun BoundedScanDoesNotPretendToQualifyOtherProfiles() {
        val start = "2030-10-26T00:30:00Z"
        assertTrue(risk(start, "FREQ=DAILY;COUNT=4096"))
        for (rule in listOf("FREQ=DAILY;COUNT=4097", "FREQ=DAILY", "FREQ=DAILY;UNTIL=20301028T013000Z",
            "FREQ=DAILY;COUNT=3;BYHOUR=2", "FREQ=WEEKLY;COUNT=3", "FREQ=DAILY;COUNT=3;COUNT=1")) assertFalse(risk(start, rule))
        val oversized = "FREQ=DAILY;COUNT=" + "0".repeat(4096) + "3"
        assertFalse(risk(start, oversized))
        assertTrue(risk("2030-10-27T00:30:00Z", oversized))
    }

    @Test fun LargeIntervalsUseLongArithmeticAndOverflowRefusesRecognizedProfiles() {
        assertFalse(risk("2030-10-26T08:00:00Z", "FREQ=DAILY;COUNT=4096;INTERVAL=1000000"))
        val nearLimit = LocalDateTime.of(999999999, 12, 30, 10, 0).atZone(paris).toInstant()
        assertTrue(RecurringLocalTimeRisk.detected(nearLimit, paris, "FREQ=DAILY;COUNT=3"))
    }
}
