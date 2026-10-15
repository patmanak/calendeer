// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test

class TextUpdateCompletionTest {
    @Test fun restoredExactReceiptClosesImmutableUnknownWithoutLosingRawOrCanonicalText() {
        val f = TextDeliveryFixture(); val result = f.result(); val receipt = f.receipt()
        val completion = TextUpdateCompletion.of(result, TextUpdateAcknowledgementRecord(receipt, TextUpdateAcknowledgementState.RESTORED))
        assertEquals(result, completion.result); assertEquals(receipt, completion.receipt)
        assertEquals(TextUpdateAttemptState.OUTCOME_UNKNOWN, completion.result.record.state)
        assertEquals(f.intent, completion.result.record.preparation.intent); assertEquals(f.event, completion.result.event)
        assertEquals(completion, completion.snapshot()); assertEquals(completion.hashCode(), completion.snapshot().hashCode())
        assertFalse(completion.toString().contains(f.intent.content.title))
    }

    @Test fun pendingReceiptOrDifferentWorkCannotReleaseTheEvent() {
        val f = TextDeliveryFixture(); val result = f.result()
        rejects { TextUpdateCompletion.of(result, TextUpdateAcknowledgementRecord(f.receipt(), TextUpdateAcknowledgementState.PENDING)) }
        val changed = f.result(f.unknown.copy(protectedPayload = "other-request"))
        rejects { TextUpdateCompletion.of(changed, TextUpdateAcknowledgementRecord(f.receipt(), TextUpdateAcknowledgementState.RESTORED)) }
        val newer = f.result(actual = f.event.copy(revision = Revision("other-revision")))
        rejects { TextUpdateCompletion.of(newer, TextUpdateAcknowledgementRecord(f.receipt(), TextUpdateAcknowledgementState.RESTORED)) }
    }

    @Test fun differentNonceOrExplicitHandoffRemainsDifferentCompletionHistory() {
        val f = TextDeliveryFixture(); val result = f.result()
        fun completed(receipt: TextUpdateAcknowledgementReceipt) = TextUpdateCompletion.of(result,
            TextUpdateAcknowledgementRecord(receipt, TextUpdateAcknowledgementState.RESTORED))
        val first = completed(f.receipt())
        assertNotEquals(first, completed(TextUpdateAcknowledgementReceipt.of(result, "00000000-0000-0000-0000-000000000009")))
        assertNotEquals(first, completed(TextUpdateAcknowledgementReceipt.of(result, f.nonce, f.handoff())))
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("Unrestored or substituted completion accepted") } catch (_: IllegalArgumentException) { } }
}
