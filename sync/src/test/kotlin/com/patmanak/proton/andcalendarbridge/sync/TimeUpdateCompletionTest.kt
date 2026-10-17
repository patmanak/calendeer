// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test

class TimeUpdateCompletionTest {
    @Test fun restoredExactReceiptClosesImmutableUnknownWithoutLosingRawOrCanonicalText() {
        val f = TimeDeliveryFixture(); val result = f.result(); val receipt = f.receipt()
        val completion = TimeUpdateCompletion.of(result, TimeUpdateAcknowledgementRecord(receipt, TimeUpdateAcknowledgementState.RESTORED))
        assertEquals(result, completion.result); assertEquals(receipt, completion.receipt)
        assertEquals(TimeUpdateAttemptState.OUTCOME_UNKNOWN, completion.result.record.state)
        assertEquals(f.intent, completion.result.record.preparation.intent); assertEquals(f.event, completion.result.event)
        assertEquals(completion, completion.snapshot()); assertEquals(completion.hashCode(), completion.snapshot().hashCode())
        assertFalse(completion.toString().contains(f.intent.content.title))
    }

    @Test fun pendingReceiptOrDifferentWorkCannotReleaseTheEvent() {
        val f = TimeDeliveryFixture(); val result = f.result()
        rejects { TimeUpdateCompletion.of(result, TimeUpdateAcknowledgementRecord(f.receipt(), TimeUpdateAcknowledgementState.PENDING)) }
        val changed = f.result(f.unknown.copy(protectedPayload = "other-request"))
        rejects { TimeUpdateCompletion.of(changed, TimeUpdateAcknowledgementRecord(f.receipt(), TimeUpdateAcknowledgementState.RESTORED)) }
        val newer = f.result(actual = f.event.copy(revision = Revision("other-revision")))
        rejects { TimeUpdateCompletion.of(newer, TimeUpdateAcknowledgementRecord(f.receipt(), TimeUpdateAcknowledgementState.RESTORED)) }
    }

    @Test fun differentNonceOrExplicitHandoffRemainsDifferentCompletionHistory() {
        val f = TimeDeliveryFixture(); val result = f.result()
        fun completed(receipt: TimeUpdateAcknowledgementReceipt) = TimeUpdateCompletion.of(result,
            TimeUpdateAcknowledgementRecord(receipt, TimeUpdateAcknowledgementState.RESTORED))
        val first = completed(f.receipt())
        assertNotEquals(first, completed(TimeUpdateAcknowledgementReceipt.of(result, "00000000-0000-0000-0000-000000000009")))
        assertNotEquals(first, completed(TimeUpdateAcknowledgementReceipt.of(result, f.nonce, f.handoff())))
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("Unrestored or substituted completion accepted") } catch (_: IllegalArgumentException) { } }
}
