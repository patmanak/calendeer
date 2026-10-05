// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class TitleUpdateAcknowledgementTest {
    private val scope = CalendarScope(AccountId("ack-fixture-account"), CalendarId("ack-fixture-calendar"), 2)
    private val base = RemoteChange.Upsert(EventId("ack-fixture-event"), Revision("original-revision"),
        EventContent("Original synthetic title", EventTime.AllDay(LocalDate.of(2030, 5, 2), LocalDate.of(2030, 5, 3)),
            "Preserved description", "Preserved location", details = EventDetails()))
    private val intent = LocalEditIntent(MutationId("ack-local-intent"), scope, AdapterFormat("ack-import", 1),
        base, base.content.copy(title = "Submitted synthetic title"))
    private val preparation = LocalEditPreparation(intent, Instant.parse("2030-05-01T10:00:00Z"),
        AdapterFormat("ack-preparation", 1), "sealed-original-preparation")
    private val unknown = TitleUpdateAttemptRecord(MutationId("ack-attempt"), preparation,
        AdapterFormat("ack-wire", 1), "sealed-original-wire", TitleUpdateAttemptState.OUTCOME_UNKNOWN)
    private val event = base.copy(revision = Revision("verified-revision"), content = intent.content)
    private val result = TitleUpdateVerifiedResult.of(unknown,
        TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(unknown), event))
    private val nonce = "00000000-0000-0000-0000-000000000001"
    private fun receipt() = TitleUpdateAcknowledgementReceipt.of(result, nonce)

    @Test fun receiptBindsOriginalIntentAndCompleteVerifiedProjection() {
        val receipt = receipt()
        assertEquals(result.attempt, receipt.attempt)
        assertEquals(intent, receipt.intent)
        assertEquals(ProjectionReceipt(scope, intent.format, event, nonce, base), receipt.projection)
        assertEquals(receipt, receipt.snapshot())
        assertTrue(receipt.matches(result))
        assertFalse(receipt.toString().contains(intent.content.title))
        assertFalse(AppliedTitleUpdateAcknowledgement(receipt).toString().contains(scope.account.value))
    }

    @Test fun preparationBindingIncludesEveryHistoricalFieldAndProtectedPayload() {
        val identity = result.attempt
        assertTrue(identity.matchesPreparation(preparation))
        val altered = listOf(preparation.copy(timestamp = preparation.timestamp.plusSeconds(1)),
            preparation.copy(protectedPayload = "different-protected-preparation"),
            preparation.copy(format = AdapterFormat("different-preparation", 1)),
            preparation.copy(intent = intent.copy(id = MutationId("different-intent"))),
            preparation.copy(intent = intent.copy(scope = scope.copy(generation = 3))),
            preparation.copy(intent = intent.copy(format = AdapterFormat("different-import", 2))),
            preparation.copy(intent = intent.copy(base = base.copy(revision = Revision("different-base")))),
            preparation.copy(intent = intent.copy(content = intent.content.copy(title = "Different local title"))))
        altered.forEach { assertFalse(identity.matchesPreparation(it)) }
    }

    @Test fun substitutionOfOriginalBaseContentOrVerifiedRevisionIsRefused() {
        val receipt = receipt()
        val altered = listOf(receipt.projection.copy(previousBase = base.copy(revision = Revision("other-base"))),
            receipt.projection.copy(change = event.copy(revision = base.revision)),
            receipt.projection.copy(change = event.copy(content = event.content.copy(description = "different-description"))),
            receipt.projection.copy(scope = scope.copy(generation = 3)),
            receipt.projection.copy(format = AdapterFormat("different-import", 1)))
        altered.forEach { projection -> rejects { receipt.copy(projection = projection) } }
        rejects { receipt.copy(intent = intent.copy(id = MutationId("other-owner"))) }
        rejects { receipt.copy(intent = intent.copy(state = LocalEditState.CONFLICT)) }
    }

    @Test fun anotherAttemptOrAnotherVerifiedRevisionDoesNotMatchTheReceipt() {
        val otherWork = unknown.copy(protectedPayload = "other-wire")
        val other = TitleUpdateVerifiedResult.of(otherWork,
            TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(otherWork), event))
        assertFalse(receipt().matches(other))
        val newer = TitleUpdateVerifiedResult.of(unknown, TitleUpdateSubmissionObservation.Verified(result.attempt,
            event.copy(revision = Revision("another-observed-revision"))))
        assertFalse(receipt().matches(newer))
    }

    @Test fun preparationFingerprintCannotTreatUnknownDetailsOrColorAsTheKnownEmptyProfile() {
        for (content in listOf(base.content.copy(details = null), base.content.copy(colorRgb = 0x123456))) {
            val substituted = preparation.copy(intent = intent.copy(base = base.copy(content = content),
                content = content.copy(title = intent.content.title)))
            assertFalse(result.attempt.matchesPreparation(substituted))
        }
    }

    private fun rejects(action: () -> Unit) {
        try { action(); fail("Expected incompatible title acknowledgement") }
        catch (_: IllegalArgumentException) { }
    }
}
