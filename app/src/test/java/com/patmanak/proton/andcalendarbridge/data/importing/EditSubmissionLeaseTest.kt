package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarId
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EditSubmissionLeaseTest {
    private class Fixture {
        val target = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 0)
        val operation = Job()
        var active = true
        var formatMatches = true
        var accountAllowed = true
        var callerEvidenceAllowed = true
        var observations = 0
        var duringAdmission: () -> Unit = {}
        val lease = EditSubmissionLease(target, operation,
            accountEvidence = { observations++; duringAdmission(); accountAllowed },
            admitEvidence = { callerEvidenceAllowed })
        fun sending() = lease.sending(
            active = { observations++; duringAdmission(); active },
            formatMatches = { formatMatches })
    }

    @Test fun constructingCallbacksDoesNotObserveAccessOrFreezeTheCurrentFormat() {
        val f = Fixture()
        val sending = f.sending()
        assertEquals(0, f.observations)
        f.formatMatches = false
        assertFalse(sending(f.target))
        assertEquals(1, f.observations)
        assertTrue(f.lease.evidence(f.target))
    }

    @Test fun sourceRejectionCannotBorrowRestoredContentsAndStillAllowsFactualEvidence() = runBlocking {
        val f = Fixture()
        val sending = f.sending()
        var observations = 0
        assertFalse(f.lease.source(f.target, sending) { observations++; false })
        assertFalse(f.lease.source(f.target, sending) { observations++; true })
        assertEquals(1, observations)
        assertFalse(sending(f.target))
        assertTrue(f.lease.evidence(f.target))
    }

    @Test fun formatRevocationClosesAllSendingCallbacksAfterFormatRestoration() {
        val f = Fixture()
        val first = f.sending()
        val retained = f.sending()
        assertTrue(first(f.target))
        f.formatMatches = false
        assertFalse(first(f.target))
        f.formatMatches = true
        assertFalse(retained(f.target))
        assertFalse(f.sending()(f.target))
        assertTrue(f.lease.evidence(f.target))
    }

    @Test fun foreignAccountCalendarOrGenerationClosesOnlyTheInvokedChannel() {
        val target = Fixture().target
        for (other in listOf(target.copy(account = AccountId("other-account")),
            target.copy(calendar = CalendarId("other-calendar")), target.copy(generation = 1))) {
            val source = Fixture()
            val sending = source.sending()
            assertFalse(sending(other))
            assertFalse(sending(source.target))
            assertTrue(source.lease.evidence(source.target))
            val evidence = Fixture()
            assertFalse(evidence.lease.evidence(other))
            assertFalse(evidence.lease.evidence(evidence.target))
            assertTrue(evidence.sending()(evidence.target))
        }
    }

    @Test fun observedAccountOrCallerEvidenceDenialCannotBeRestoredWithinTheInvocation() {
        for (accountDenial in listOf(false, true)) {
            val f = Fixture()
            assertTrue(f.lease.evidence(f.target))
            if (accountDenial) f.accountAllowed = false else f.callerEvidenceAllowed = false
            assertFalse(f.lease.evidence(f.target))
            f.accountAllowed = true; f.callerEvidenceAllowed = true
            assertFalse(f.lease.evidence(f.target))
            assertTrue(f.sending()(f.target))
        }
    }

    @Test fun closeExpiresRetainedAndLaterCallbacksWithoutObservingTheSource() = runBlocking {
        val f = Fixture()
        val sending = f.sending()
        val evidence = f.lease.evidence
        var sourceReads = 0
        f.lease.close()
        assertFalse(sending(f.target))
        assertFalse(f.sending()(f.target))
        assertFalse(evidence(f.target))
        assertFalse(f.lease.source(f.target, sending) { sourceReads++; true })
        assertEquals(0, sourceReads)
        assertEquals(0, f.observations)
    }

    @Test fun sourceExceptionPermanentlyRefusesSendingWithoutDiscardingFactualEvidence() = runBlocking {
        val f = Fixture()
        val sending = f.sending()
        var sourceReads = 0
        assertFalse(f.lease.source(f.target, sending) { sourceReads++; throw IllegalStateException("Synthetic source failure") })
        assertFalse(f.lease.source(f.target, sending) { sourceReads++; true })
        assertEquals(1, sourceReads)
        assertTrue(f.lease.evidence(f.target))
    }

    @Test fun sourceCannotHideCancellationByReturningNormally() = runBlocking {
        val f = Fixture()
        try {
            f.lease.source(f.target, f.sending()) { f.operation.cancel(); true }
            fail("Cancellation must propagate after source observation")
        } catch (_: CancellationException) { }
        finally { f.lease.close() }
    }

    @Test fun cancellationDuringEitherAdmissionCannotReturnSuccess() {
        for (evidence in listOf(false, true)) {
            val f = Fixture()
            f.duringAdmission = { f.operation.cancel() }
            try {
                if (evidence) f.lease.evidence(f.target) else f.sending()(f.target)
                fail("Cancellation must propagate after admission observation")
            } catch (_: CancellationException) { }
            finally { f.lease.close() }
        }
    }

    @Test fun closingDuringEitherAdmissionCannotReturnSuccess() {
        for (evidence in listOf(false, true)) {
            val f = Fixture()
            f.duringAdmission = { f.lease.close() }
            assertFalse(if (evidence) f.lease.evidence(f.target) else f.sending()(f.target))
        }
    }
}
