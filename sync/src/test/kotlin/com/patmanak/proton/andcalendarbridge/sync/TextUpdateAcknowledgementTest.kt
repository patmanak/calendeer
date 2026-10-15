// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TextUpdateAcknowledgementTest {
    @Test fun receiptSeparatesRawNativeContentFromActualVerifiedProjection() {
        for (allDay in listOf(false, true)) {
            val f = TextDeliveryFixture(allDay); val receipt = f.receipt()
            assertEquals(f.intent, receipt.intent); assertEquals(f.event, receipt.projection.change)
            assertEquals(f.intent.base, receipt.projection.previousBase)
            assertNotEquals(receipt.intent.content, receipt.projection.change.content)
            assertTrue(receipt.matches(f.result())); assertEquals(receipt, receipt.snapshot())
            assertEquals(receipt.hashCode(), receipt.snapshot().hashCode())
        }
    }

    @Test fun receiptRejectsSubstitutedBaseFormatScopeOwnerAndRemoteFields() {
        val f = TextDeliveryFixture(); val receipt = f.receipt()
        val variants = listOf(receipt.projection.copy(previousBase = f.intent.base.copy(revision = Revision("other-base"))),
            receipt.projection.copy(scope = f.scope.copy(generation = 4)), receipt.projection.copy(format = AdapterFormat("other", 1)),
            receipt.projection.copy(change = f.event.copy(revision = f.intent.base.revision)),
            receipt.projection.copy(change = f.event.copy(content = f.event.content.copy(description = "Other"))),
            receipt.projection.copy(change = f.event.copy(content = f.event.content.copy(location = "Other"))))
        for (projection in variants) rejects { receipt.copy(projection = projection) }
        rejects { receipt.copy(intent = f.intent.copy(id = MutationId("other-owner"))) }
        rejects { receipt.copy(intent = f.intent.copy(state = LocalEditState.CONFLICT)) }
        rejects { receipt.copy(attempt = receipt.attempt.copy(baseRevision = Revision("other-base"))) }
    }

    @Test fun differentWireOrVerifiedRevisionNeverMatchesHistoricalReceipt() {
        val f = TextDeliveryFixture(); val receipt = f.receipt()
        assertFalse(receipt.matches(f.result(f.unknown.copy(protectedPayload = "other-wire"))))
        assertFalse(receipt.matches(f.result(actual = f.event.copy(revision = Revision("other-new-revision")))))
        assertFalse(receipt.matches(f.result(actual = f.event.copy(content = f.intent.content))))
    }

    @Test fun handoffBindsCompleteOriginCurrentFrameAndUniqueNonce() {
        val f = TextDeliveryFixture(); val frame = f.handoff()
        val receipt = TextUpdateAcknowledgementReceipt.of(f.result(), f.nonce, frame)
        assertEquals(frame, receipt.handoff); assertTrue(receipt.matches(f.result())); assertNotEquals(f.receipt(), receipt)
        for (reserved in listOf(frame.receipt.nonce, frame.receipt.acknowledgement.nonce,
            frame.receipt.acknowledgement.baseline.receipt.nonce))
            rejects { TextUpdateAcknowledgementReceipt.of(f.result(), reserved, frame) }
        val different = f.intent.base.copy(revision = Revision("other-current"))
        val projection = ProjectionReceipt(f.scope, f.intent.format, different, UUID(0, 7).toString(), f.intent.base)
        rejects { receipt.copy(handoff = frame.copy(base = different, lastProjection = projection)) }
    }

    @Test fun constructorOwnsAllEmptyDetailsIncludingHandoffBeforeCallerMutation() {
        val f = TextDeliveryFixture(); val details = mutableListOf<EventReminder>()
        val base = f.intent.base.copy(content = f.baseContent.copy(details = EventDetails(details)))
        val intent = f.intent.copy(base = base, content = f.intent.content.copy(details = EventDetails(details)))
        val original = f.handoff()
        val frame = original.copy(base = base, receipt = original.receipt.copy(acknowledgement = original.receipt.acknowledgement.copy(
            baseline = original.receipt.acknowledgement.baseline.copy(change = base))))
        val projected = f.event.copy(content = f.event.content.copy(details = EventDetails(details)))
        val receipt = TextUpdateAcknowledgementReceipt(f.result().attempt, intent,
            ProjectionReceipt(f.scope, f.intent.format, projected, f.nonce, base), frame)
        details += EventReminder(10)
        assertEquals(EventDetails(), receipt.intent.content.details); assertEquals(EventDetails(), receipt.projection.change.content.details)
        assertEquals(EventDetails(), receipt.handoff!!.base.content.details)
        assertEquals(EventDetails(), receipt.handoff!!.receipt.acknowledgement.baseline.change.content.details)
        assertTrue(receipt.matches(f.result()))
    }

    @Test fun receiptAndWitnessTypesNeverPrintRawIntentOrProtectedIdentity() {
        val f = TextDeliveryFixture(); val receipt = f.receipt()
        for (value in listOf(receipt, AppliedTextUpdateAcknowledgement(receipt),
            TextUpdateAcknowledgementRecord(receipt, TextUpdateAcknowledgementState.PENDING),
            TextUpdateAcknowledgementReservation(TextUpdateAcknowledgementRecord(receipt, TextUpdateAcknowledgementState.PENDING), true)))
            for (secret in listOf(f.scope.account.value, f.intent.content.title, f.result().attempt.payloadFingerprint))
                assertFalse(value.toString().contains(secret))
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("Invalid text acknowledgement accepted") } catch (_: IllegalArgumentException) { } }
}
