// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProviderWakeRegistrationTest {
    private class Store : ProviderWakeStateStore {
        var state = ProviderWakeState()
        var failWrite = false
        override fun read() = state
        override fun write(state: ProviderWakeState) {
            if (failWrite) error("fixture commit failure")
            this.state = state
        }
    }

    private class Backend(private val store: Store) : ProviderWakeBackend {
        val arms = mutableListOf<String>()
        val registrations = mutableSetOf<String>()
        var failArm = false
        var failCancel = false
        var entered: CompletableDeferred<Unit>? = null
        var release: CompletableDeferred<Unit>? = null
        var cancels = 0
        override suspend fun arm(token: String) {
            // Enqueue must never precede the durable handoff.
            assertTrue(store.state.enabled)
            assertEquals(token, store.state.watchToken)
            arms += token
            entered?.complete(Unit)
            release?.await()
            if (failArm) error("fixture arm failure")
            registrations += token
        }
        override suspend fun cancelAll() {
            assertEquals(ProviderWakeState(), store.state)
            cancels++
            if (failCancel) error("fixture cancel failure")
            registrations.clear()
        }
    }

    private class Fixture {
        val store = Store()
        val backend = Backend(store)
        var sequence = 0
        fun registration() = ProviderWakeRegistration(store, backend) { "token-${++sequence}" }
        val subject = registration()
    }

    @Test fun failedInitialEnqueueRecoversSameDurableSignalAfterRecreation() = runBlocking {
        val f = Fixture()
        f.backend.failArm = true
        assertTrue(runCatching { f.subject.setEnabled(true) }.isFailure)
        val pending = f.store.state
        assertNotNull(pending.pendingToken)
        assertEquals(pending.watchToken, pending.pendingToken)
        f.backend.failArm = false
        assertEquals(pending, f.registration().ensureArmed())
        assertEquals(1, f.sequence)
        assertEquals(setOf(pending.watchToken), f.backend.registrations)
    }

    @Test fun failedCommitNeverArmsAnUndurableSuccessor() = runBlocking {
        val f = Fixture()
        f.subject.setEnabled(true)
        val original = f.store.state
        f.store.failWrite = true
        assertTrue(runCatching { f.subject.onTrigger(original.watchToken!!) }.isFailure)
        assertEquals(original, f.store.state)
        assertEquals(listOf(original.watchToken), f.backend.arms)
        f.store.failWrite = false
        val recovered = f.registration().onTrigger(original.watchToken!!)
        assertNotEquals(original.watchToken, recovered.watchToken)
        assertEquals(recovered.watchToken, recovered.pendingToken)
    }

    @Test fun failedSuccessorArmAndStaleRetriesDoNotRotateAgain() = runBlocking {
        val f = Fixture()
        f.subject.setEnabled(true)
        val first = f.store.state.watchToken!!
        f.backend.failArm = true
        assertTrue(runCatching { f.subject.onTrigger(first) }.isFailure)
        val successor = f.store.state
        f.backend.failArm = false
        val recreated = f.registration()
        repeat(20) { assertEquals(successor, recreated.onTrigger(first)) }
        assertEquals(2, f.sequence)
        assertEquals(successor, recreated.ensureArmed())
        assertEquals(2, f.backend.registrations.size)
    }

    @Test fun laterSignalSurvivesAcknowledgementFromAnOlderRunningPass() = runBlocking {
        val f = Fixture()
        f.subject.setEnabled(true)
        val running = f.store.state.pendingToken!!
        val later = f.subject.onTrigger(f.store.state.watchToken!!)
        assertFalse(f.subject.acknowledge(running, "obsolete-fingerprint"))
        assertEquals(later, f.subject.snapshot())
        assertTrue(f.subject.acknowledge(later.pendingToken!!, "current-fingerprint"))
        assertNull(f.store.state.pendingToken)
        assertEquals("current-fingerprint", f.store.state.fingerprint)
        assertFalse(f.subject.acknowledge(later.pendingToken, "duplicate"))
        assertEquals("current-fingerprint", f.store.state.fingerprint)
    }

    @Test fun aBurstCoalescesToLatestSignalWithoutLettingAnyOlderPassClearIt() = runBlocking {
        val f = Fixture()
        f.subject.setEnabled(true)
        val superseded = mutableListOf<String>()
        repeat(20) {
            superseded += f.store.state.pendingToken!!
            f.subject.onTrigger(f.store.state.watchToken!!)
        }
        val latest = f.store.state
        for (token in superseded) {
            assertFalse(f.subject.acknowledge(token, "outdated"))
            assertEquals(latest, f.subject.onTrigger(token))
        }
        assertEquals(21, f.sequence)
        assertTrue(f.subject.acknowledge(latest.pendingToken!!, "latest"))
        assertNull(f.store.state.pendingToken)
        assertEquals(latest.watchToken, f.store.state.watchToken)
    }

    @Test fun disableWaitsForSuspendedArmAndReenableUsesFreshIdentity() = runBlocking {
        val f = Fixture()
        f.backend.entered = CompletableDeferred()
        f.backend.release = CompletableDeferred()
        val enable = async { f.subject.setEnabled(true) }
        f.backend.entered!!.await()
        val original = f.store.state.watchToken!!
        val disable = async(start = CoroutineStart.UNDISPATCHED) { f.subject.setEnabled(false) }
        assertFalse(disable.isCompleted)
        assertTrue(f.store.state.enabled)
        assertEquals(0, f.backend.cancels)
        f.backend.release!!.complete(Unit)
        enable.await()
        disable.await()
        assertEquals(ProviderWakeState(), f.subject.snapshot())
        assertTrue(f.backend.registrations.isEmpty())
        f.backend.entered = null
        f.backend.release = null
        f.subject.setEnabled(true)
        assertNotEquals(original, f.store.state.watchToken)
        val latest = f.store.state
        assertEquals(latest, f.subject.onTrigger(original))
        assertEquals(latest, f.subject.snapshot())
    }

    @Test fun disabledStateSurvivesFailedCancellationAndCannotBeRevivedByTrigger() = runBlocking {
        val f = Fixture()
        f.subject.setEnabled(true)
        val original = f.store.state.watchToken!!
        f.backend.failCancel = true
        assertTrue(runCatching { f.subject.setEnabled(false) }.isFailure)
        val armCount = f.backend.arms.size
        val recreated = f.registration()
        assertEquals(ProviderWakeState(), recreated.onTrigger(original))
        assertEquals(ProviderWakeState(), recreated.ensureArmed())
        assertEquals(armCount, f.backend.arms.size)
        assertFalse(recreated.acknowledge(original, "stale"))
        f.backend.failCancel = false
        recreated.setEnabled(false)
        assertTrue(f.backend.registrations.isEmpty())
    }

    @Test fun failedAcknowledgementRetainsSignalAndRepeatedEnableDoesNotCreateAnother() = runBlocking {
        val f = Fixture()
        f.subject.setEnabled(true)
        val pending = f.store.state
        f.store.failWrite = true
        assertTrue(runCatching { f.subject.acknowledge(pending.pendingToken!!, "new") }.isFailure)
        assertEquals(pending, f.store.state)
        f.store.failWrite = false
        f.subject.setEnabled(true)
        assertEquals(1, f.sequence)
        assertTrue(f.subject.acknowledge(pending.pendingToken!!, "new"))
        f.subject.setEnabled(false)
        f.subject.setEnabled(true)
        assertNull(f.store.state.fingerprint)
        assertNotEquals(pending.watchToken, f.store.state.watchToken)
    }

    @Test fun diagnosticRepresentationNeverIncludesPersistedIdentifiers() {
        val state = ProviderWakeState(true, "private-watch", "private-pending", "private-digest")
        assertEquals("ProviderWakeState(REDACTED, enabled=true)", state.toString())
    }
}
