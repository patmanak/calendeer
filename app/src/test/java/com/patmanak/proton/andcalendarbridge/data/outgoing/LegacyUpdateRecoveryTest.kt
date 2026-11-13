// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Frozen old formats stay readable; no compatibility operation can create or resend a wire. */
class LegacyUpdateRecoveryTest {
    @Test fun retiredLegacyPreparationNeverCreatesOrSendsAnotherWire() = runBlocking {
        val text = TextOperationsFixture(); text.retained = text.preparation
        val time = TimeOperationsFixture(); time.retained = time.preparation
        assertFalse(text.process()); assertFalse(time.process())
        assertTrue(text.calls.isEmpty()); assertTrue(time.calls.isEmpty())
        assertNull(text.saved); assertNull(time.saved)
    }

    @Test fun preparedLegacyWiresAreHeldWithoutInspectionClaimOrReplacement() = runBlocking {
        val text = TextOperationsFixture(); text.seedUnknown()
        text.saved = text.saved!!.copy(state = TextUpdateAttemptState.PREPARED)
        val time = TimeOperationsFixture(); time.seedUnknown()
        time.saved = time.saved!!.copy(state = TimeUpdateAttemptState.PREPARED)
        val beforeText = text.saved; val beforeTime = time.saved
        text.process(); time.process()
        assertEquals(1, text.operations.inspect(text.query, { true }) { fail("No new proof") }.held)
        assertEquals(1, time.operations.inspect(time.query, { true }) { fail("No new proof") }.held)
        assertTrue(text.calls.isEmpty()); assertTrue(time.calls.isEmpty())
        assertSame(beforeText, text.saved); assertSame(beforeTime, time.saved)
    }

    @Test fun unknownTextKeepsItsOriginalIdentityAndOnlyInspectionCanRecoverIt() = runBlocking {
        val f = TextOperationsFixture(); f.seedUnknown(); val original = f.saved!!
        f.process(); assertTrue(f.calls.isEmpty())
        val progress = f.operations.inspect(f.query, { true }) { f.calls += "wake" }
        assertEquals(1, progress.completed)
        assertEquals(listOf("inspect", "proof", "wake", "ack", "complete"), f.calls)
        assertSame(original, f.saved)
        assertNotNull(f.completion)
    }

    @Test fun unknownTimeKeepsItsOriginalIdentityAndOnlyInspectionCanRecoverIt() = runBlocking {
        val f = TimeOperationsFixture(); f.seedUnknown(); val original = f.saved!!
        f.process(); assertTrue(f.calls.isEmpty())
        val progress = f.operations.inspect(f.query, { true }) { f.calls += "wake" }
        assertEquals(1, progress.completed)
        assertEquals(listOf("inspect", "proof", "wake", "ack", "complete"), f.calls)
        assertSame(original, f.saved)
        assertNotNull(f.completion)
    }

    @Test fun offlineTextRequiresItsExactRetainedProofAndNeverUsesTransport() = runBlocking {
        val f = TextOperationsFixture(); f.seedUnknown()
        assertEquals(1, f.operations.recoverOffline(f.query) { true }.held)
        assertTrue(f.calls.isEmpty())
        f.result = f.verified(f.saved!!)
        assertEquals(1, f.operations.recoverOffline(f.query) { true }.completed)
        assertEquals(listOf("ack", "complete"), f.calls)
        val changed = TextOperationsFixture(); changed.seedUnknown()
        changed.result = changed.verified(changed.saved!!.copy(protectedPayload = "different-wire"))
        assertEquals(1, changed.operations.recoverOffline(changed.query) { true }.failed)
        assertFalse("ack" in changed.calls)
    }

    @Test fun offlineTimeRequiresItsExactRetainedProofAndNeverUsesTransport() = runBlocking {
        val f = TimeOperationsFixture(); f.seedUnknown()
        assertEquals(1, f.operations.recoverOffline(f.query) { true }.held)
        assertTrue(f.calls.isEmpty())
        f.result = f.verified(f.saved!!)
        assertEquals(1, f.operations.recoverOffline(f.query) { true }.completed)
        assertEquals(listOf("ack", "complete"), f.calls)
        val changed = TimeOperationsFixture(); changed.seedUnknown()
        changed.result = changed.verified(changed.saved!!.copy(protectedPayload = "different-wire"))
        assertEquals(1, changed.operations.recoverOffline(changed.query) { true }.failed)
        assertFalse("ack" in changed.calls)
    }

    @Test fun revocationCannotReopenEitherLegacyInspectionLease() = runBlocking {
        val text = TextOperationsFixture(); text.seedUnknown()
        text.inspectHook = { lease ->
            text.allowed = false; assertFalse(lease(text.scope))
            text.allowed = true; assertFalse(lease(text.scope)); TextUpdateSubmissionObservation.Unknown
        }
        assertTrue(text.operations.inspect(text.query, { text.allowed }) { fail("Revoked") }.inactive)
        assertFalse(text.retainedLease!!(text.scope)); assertNull(text.completion)
        val time = TimeOperationsFixture(); time.seedUnknown()
        time.inspectHook = { lease ->
            time.allowed = false; assertFalse(lease(time.scope))
            time.allowed = true; assertFalse(lease(time.scope)); TimeUpdateSubmissionObservation.Unknown
        }
        assertTrue(time.operations.inspect(time.query, { time.allowed }) { fail("Revoked") }.inactive)
        assertFalse(time.retainedLease!!(time.scope)); assertNull(time.completion)
    }

    @Test fun cancellationStillPropagatesThroughTextCompatibilityReadsProofAndAck() = runBlocking {
        for (stage in listOf("page", "read", "proof", "ack")) supervisorScope {
            val f = TextOperationsFixture(); f.seedUnknown(); f.cancelAt = stage; f.throwAfterCancellation = true
            if (stage == "ack") f.result = f.verified(f.saved!!)
            var cancelled = 0
            val task = async {
                try {
                    if (stage == "proof") f.operations.inspect(f.query, { true }) { fail("Cancelled proof") }
                    else f.operations.recoverOffline(f.query) { true }
                    fail("Cancellation must not return success")
                } catch (_: CancellationException) { cancelled++ }
            }
            try { task.await() } catch (_: CancellationException) { }
            assertEquals(stage, 1, f.cancellationHits); assertEquals(stage, 1, cancelled)
            assertNull(f.completion); assertFalse("submit" in f.calls)
        }
    }
}
