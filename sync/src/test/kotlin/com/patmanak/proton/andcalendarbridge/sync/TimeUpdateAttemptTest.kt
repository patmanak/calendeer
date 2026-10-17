// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TimeUpdateAttemptTest {
    @Test fun frozenAttemptsOwnPreparationAndUseFullStructuralEquality() {
        val f = TimeDeliveryFixture(); val first = f.unknown
        val second = f.unknown.copy(preparation = f.preparation())
        assertNotSame(first.preparation, second.preparation)
        assertEquals(first, second); assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first, first.frozen()); assertEquals(f.intent, first.preparation.intent)
        assertEquals("Original\r\nnotes", first.preparation.intent.content.description)
        for (variant in f.preparationVariants()) assertNotEquals(first, first.copy(preparation = variant))
        for (variant in listOf(first.copy(id = MutationId("other")), first.copy(format = first.format.copy(version = 2)),
            first.copy(protectedPayload = "other"), first.copy(protectedEvidence = "evidence"),
            first.copy(state = TimeUpdateAttemptState.PREPARED))) assertNotEquals(first, variant)
    }

    @Test fun preparedHasNoEvidenceUnknownRetainsItAndDoesNotBecomePrepared() {
        val f = TimeDeliveryFixture(); val prepared = f.unknown.copy(state = TimeUpdateAttemptState.PREPARED)
        assertNull(prepared.protectedEvidence)
        refused { prepared.copy(protectedEvidence = "evidence") }
        val unknown = prepared.copy(state = TimeUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "evidence")
        assertEquals("evidence", unknown.frozen().protectedEvidence)
        refused { unknown.copy(state = TimeUpdateAttemptState.PREPARED) }
        assertEquals(listOf(TimeUpdateAttemptState.PREPARED, TimeUpdateAttemptState.OUTCOME_UNKNOWN), TimeUpdateAttemptState.entries)
        assertFalse(TimeUpdateAttemptJournal::class.java.methods.any { it.name.contains("reset", true) || it.name.contains("resend", true) })
    }

    @Test fun sameKindZoneKnownEmptyAndTimeOnlyAreMandatoryBeforeOwnership() {
        val f = TimeDeliveryFixture(); val time = f.intent.content.time as EventTime.Timed
        val candidates = listOf(f.baseContent, f.intent.content.copy(title = "Other"), f.intent.content.copy(description = "Other"),
            f.intent.content.copy(location = "Other"), f.intent.content.copy(time = time.copy(zone = ZoneId.of("UTC"))),
            f.intent.content.copy(time = EventTime.AllDay(LocalDate.of(2030, 10, 26), LocalDate.of(2030, 10, 27))))
        for (content in candidates) refused { f.preparation(f.intent.copy(content = content)) }
        for (base in listOf(f.baseContent.copy(details = null), f.baseContent.copy(colorRgb = 12),
            f.baseContent.copy(details = EventDetails(listOf(EventReminder(5)))),
            f.baseContent.copy(recurrence = EventRecurrence("synthetic-uid", "FREQ=DAILY"))))
            refused { f.preparation(f.intent.copy(base = f.intent.base.copy(content = base), content = base.copy(time = time))) }
    }

    @Test fun invalidTemporalBoundsAndFractionalSecondsNeverBecomeWork() {
        val f = TimeDeliveryFixture(); val time = f.intent.content.time as EventTime.Timed
        for (bad in listOf(time.copy(start = time.start.plusNanos(1)), time.copy(end = time.end.plusNanos(1)),
            time.copy(start = Instant.EPOCH.minusSeconds(1)), time.copy(end = Instant.parse("+10000-01-01T00:00:00Z"))))
            refused { f.preparation(f.intent.copy(content = f.intent.content.copy(time = bad))) }
        val day = TimeDeliveryFixture(true)
        refused { day.preparation(day.intent.copy(content = day.intent.content.copy(time =
            EventTime.AllDay(LocalDate.of(1969, 12, 31), LocalDate.of(2030, 10, 30))))) }
    }

    @Test fun requestAndEvidenceUseStrictUtf8Budgets() {
        val f = TimeDeliveryFixture(); val exact = "é".repeat(2048)
        assertEquals(exact, f.unknown.copy(id = MutationId(exact)).id.value)
        assertEquals(exact, f.unknown.copy(format = AdapterFormat(exact, 1)).format.id)
        for (bad in listOf("é".repeat(2049), "\uD800", " ")) {
            refused { f.unknown.copy(id = MutationId(bad)) }; refused { f.unknown.copy(format = AdapterFormat(bad, 1)) }
        }
        val payload = "é".repeat(2097152)
        assertEquals(payload, f.unknown.copy(protectedPayload = payload).protectedPayload)
        assertEquals(payload, f.unknown.copy(protectedEvidence = payload).protectedEvidence)
        for (bad in listOf(payload + "é", "\uD800", "", " ")) {
            refused { f.unknown.copy(protectedPayload = bad) }; refused { f.unknown.copy(protectedEvidence = bad) }
        }
    }

    @Test fun mutableEmptyDetailsDoNotAliasFrozenWorkAndDiagnosticsAreRedacted() {
        val f = TimeDeliveryFixture(); val reminders = mutableListOf<EventReminder>()
        val base = f.baseContent.copy(details = EventDetails(reminders))
        val work = f.unknown.copy(preparation = f.preparation(f.intent.copy(base = f.intent.base.copy(content = base),
            content = base.copy(time = f.replacementTime))))
        reminders += EventReminder(5)
        assertEquals(EventDetails(), work.preparation.intent.base.content.details)
        assertEquals(EventDetails(), work.preparation.intent.content.details)
        for (secret in listOf(f.scope.account.value, f.intent.content.title, f.preparation.protectedPayload, f.unknown.protectedPayload))
            assertFalse(work.toString().contains(secret))
    }
}
