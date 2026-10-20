// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class UpdateRequestLeaseTest {
    private val target = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 0)

    @Test fun foreignScopeNeverConsultsAdmissionAndCannotCloseTheOriginalNamespace() {
        var reads = 0
        val lease = UpdateRequestLease(target, { reads++; true }, Job())
        assertFalse(lease.allows(target.copy(calendar = CalendarId("other"))))
        assertEquals(0, reads)
        assertTrue(lease.allows(target))
        assertEquals(1, reads)
    }

    @Test fun deniedAdmissionCannotBeRestoredWithinTheSameInvocation() {
        var allowed = true
        val lease = UpdateRequestLease(target, { allowed }, Job())
        lease.check()
        allowed = false
        assertFalse(lease.allows(target))
        allowed = true
        assertFalse(lease.allows(target))
        try { lease.check(); fail("Closed lease must be refused") } catch (_: UpdateRequestLease.Closed) { }
    }

    @Test fun admissionExceptionClosesTheLeaseWithoutMaskingItsFailure() {
        var failing = true
        val expected = IllegalStateException("Synthetic admission failure")
        val lease = UpdateRequestLease(target, { if (failing) throw expected else true }, Job())
        try { lease.check(); fail("Admission failure must propagate") }
        catch (actual: IllegalStateException) { assertSame(expected, actual) }
        failing = false
        assertFalse(lease.allows(target))
    }

    @Test fun cancellationSwallowedOrReplacedDuringAdmissionStillPropagates() {
        for (throwsFailure in listOf(false, true)) {
            val operation = Job()
            val lease = UpdateRequestLease(target, {
                operation.cancel()
                if (throwsFailure) throw IllegalStateException("Synthetic cancelled dependency") else true
            }, operation)
            try { lease.check(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            finally { lease.close() }
            assertFalse(lease.allows(target))
        }
    }
}
