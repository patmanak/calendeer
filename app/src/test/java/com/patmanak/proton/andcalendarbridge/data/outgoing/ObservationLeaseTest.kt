// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class ObservationLeaseTest {
    @Test fun sessionHydrationAfterSuspendedOldLeaseStillReachesObservationRegistration() = runBlocking {
        val states = MutableStateFlow(false)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val armed = CompletableDeferred<Unit>()
        val passes = mutableListOf<Boolean>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            states.collect { hydrated -> observeCurrentCalendarSignal {
                passes += hydrated
                if (!hydrated) {
                    entered.complete(Unit); release.await()
                    throw CancellationException("Fixture old session lease")
                }
                armed.complete(Unit)
            } }
        }
        try {
            entered.await(); states.value = true; release.complete(Unit)
            withTimeout(2000) { armed.await() }
            assertEquals(listOf(false, true), passes)
            assertTrue(collector.isActive)
        } finally { collector.cancelAndJoin() }
    }

    @Test fun genuineCollectorCancellationNeverArmsAnotherSignal() = runBlocking {
        val states = MutableStateFlow(false)
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        var armed = false
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            states.collect { hydrated -> observeCurrentCalendarSignal {
                if (!hydrated) { entered.complete(Unit); never.await() }
                armed = true
            } }
        }
        entered.await(); collector.cancelAndJoin(); states.value = true
        assertTrue(collector.isCancelled)
        assertFalse(armed)
    }
}
