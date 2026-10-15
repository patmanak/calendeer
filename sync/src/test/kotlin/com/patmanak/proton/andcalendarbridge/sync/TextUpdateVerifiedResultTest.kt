// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test

class TextUpdateVerifiedResultTest {
    @Test fun resultRetainsRawIntentAndRealEventAndIgnoresOnlyResponseEvidence() {
        for (allDay in listOf(false, true)) {
            val f = TextDeliveryFixture(allDay); val result = f.result()
            assertEquals(f.unknown, result.record); assertEquals(f.event, result.event)
            assertNotEquals(result.record.preparation.intent.content, result.event.content)
            assertEquals(TextUpdateAttemptIdentity.of(f.unknown), result.attempt)
            assertEquals(result, TextUpdateVerifiedResult.of(f.unknown.copy(protectedEvidence = "evidence-a"),
                f.observed(f.unknown.copy(protectedEvidence = "evidence-b"))))
            assertEquals(result, result.snapshot()); assertEquals(result.hashCode(), result.snapshot().hashCode())
        }
    }

    @Test fun anyDifferentUnknownWorkRefusesAnOtherwiseMatchingObservation() {
        val f = TextDeliveryFixture(); val observation = f.observed()
        val variants = listOf(f.unknown.copy(state = TextUpdateAttemptState.PREPARED), f.unknown.copy(id = MutationId("other-id")),
            f.unknown.copy(format = f.unknown.format.copy(version = 2)), f.unknown.copy(protectedPayload = "other-payload")) +
            f.preparationVariants().map { f.unknown.copy(preparation = it) }
        for (variant in variants) rejects { TextUpdateVerifiedResult.of(variant, observation) }
    }

    @Test fun twoLogicalEquivalentButRawDifferentObservationsRemainDifferentHistoricalProofs() {
        val f = TextDeliveryFixture(); val canonical = f.result()
        val raw = f.result(actual = f.event.copy(content = f.intent.content))
        assertNotEquals(canonical, raw); assertEquals(f.intent.content, raw.event.content)
    }

    @Test fun resultOwnsDecoderEmptyCollectionsAndRedactsSensitiveValues() {
        val f = TextDeliveryFixture(); val reminders = mutableListOf<EventReminder>()
        val result = f.result(actual = f.event.copy(content = f.event.content.copy(details = EventDetails(reminders))))
        reminders += EventReminder(10)
        assertEquals(EventDetails(), result.event.content.details); assertEquals(f.result(), result)
        for (secret in listOf(f.scope.account.value, f.intent.content.title, f.preparation.protectedPayload, f.unknown.protectedPayload))
            assertFalse(result.toString().contains(secret))
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("Substituted proof accepted") } catch (_: IllegalArgumentException) { } }
}
