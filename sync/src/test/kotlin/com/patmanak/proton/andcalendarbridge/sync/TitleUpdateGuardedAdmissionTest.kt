// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TitleUpdateGuardedAdmissionTest {
    private val target = CalendarScope(AccountId("guarded-account"), CalendarId("guarded-calendar"), 2)
    private val content = EventContent("Synthetic original", EventTime.AllDay(LocalDate.of(2030, 1, 1),
        LocalDate.of(2030, 1, 2)), details = EventDetails())
    private val intent = LocalEditIntent(MutationId("synthetic-local-intent"), target, AdapterFormat("synthetic-import", 1),
        RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-base"), content), content.copy(title = "Synthetic changed"))
    private val wire = TitleUpdateAttemptRecord(MutationId("synthetic-attempt"), LocalEditPreparation(intent,
        Instant.parse("2030-01-01T12:00:00Z"), AdapterFormat("synthetic-preparation", 1), "synthetic-capsule"),
        AdapterFormat("synthetic-wire", 1), "synthetic-wire-payload")
    private open class LegacyJournal : TitleUpdateAttemptJournal {
        var legacyClaims = 0
        override suspend fun remember(record: TitleUpdateAttemptRecord): Unit = error("No retention")
        override suspend fun read(scope: CalendarScope, id: MutationId): TitleUpdateAttemptRecord? = error("No discovery")
        override suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean { legacyClaims++; error("No legacy admission") }
        override suspend fun retainEvidence(expectedUnknown: TitleUpdateAttemptRecord, protectedEvidence: String): Unit = error("No evidence")
    }
    private class Journal(val prepared: TitleUpdateAttemptRecord) : LegacyJournal() {
        var claims = 0
        var stored = prepared
        var afterCommit: suspend () -> Unit = {}
        var failure: Exception? = null
        var commit = true
        override suspend fun claim(expected: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            claims++; assertEquals(prepared, expected); assertTrue(admit(expected.scope))
            assertFalse(admit(expected.scope.copy(generation = expected.scope.generation + 1)))
            failure?.let { throw it }
            if (commit) stored = prepared.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
            afterCommit(); return commit
        }
    }
    @Test fun exactDurableUnknownPrecedesTheSingleSend() = runTest {
        val journal = Journal(wire); var sends = 0
        assertTrue(TitleUpdateAttemptAdmission(journal).dispatch(wire, { true }) { sent ->
            sends++; assertEquals(wire.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), sent)
            assertEquals(sent, journal.stored)
        })
        assertEquals(1, sends); assertEquals(1, journal.claims); assertEquals(0, journal.legacyClaims)
    }
    @Test fun deniedLeaseAndUnsupportedGuardedJournalNeverFallBackToLegacyAdmission() = runTest {
        val journal = Journal(wire)
        assertFalse(TitleUpdateAttemptAdmission(journal).dispatch(wire, { false }) { error("No send") })
        assertEquals(0, journal.claims); assertEquals(wire, journal.stored)
        val legacy = LegacyJournal()
        assertFalse(TitleUpdateAttemptAdmission(legacy).dispatch(wire, { true }) { error("No send") })
        assertEquals(0, legacy.legacyClaims)
    }
    @Test fun unknownAndEvidenceBearingInputsNeverClaimOrSend() = runTest {
        for (candidate in listOf(wire.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN),
            wire.copy(protectedEvidence = "synthetic-evidence"))) {
            val journal = Journal(wire)
            assertFalse(TitleUpdateAttemptAdmission(journal).dispatch(candidate, { true }) { error("No send") })
            assertEquals(0, journal.claims); assertEquals(wire, journal.stored)
        }
    }
    @Test fun failedOrFalseClaimCannotReachTransport() = runTest {
        val noCommit = Journal(wire).apply { commit = false }
        assertFalse(TitleUpdateAttemptAdmission(noCommit).dispatch(wire, { true }) { error("No send") })
        assertEquals(1, noCommit.claims); assertEquals(wire, noCommit.stored)
        val failure = IllegalStateException("Synthetic persistence failure")
        val failed = Journal(wire).apply { this.failure = failure }
        try {
            TitleUpdateAttemptAdmission(failed).dispatch(wire, { true }) { error("No send") }
            fail("Persistence failure must propagate")
        } catch (observed: IllegalStateException) { assertSame(failure, observed) }
        assertEquals(wire, failed.stored)
    }
    @Test fun revocationAfterCommitHoldsUnknownWithoutSendingOrResetting() = runTest {
        var allowed = true
        val journal = Journal(wire).apply { afterCommit = { allowed = false } }
        assertFalse(TitleUpdateAttemptAdmission(journal).dispatch(wire, { allowed }) { error("No send") })
        assertEquals(1, journal.claims); assertEquals(wire.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), journal.stored)
    }
    @Test fun cancellationAfterClaimReturningNormallyNeverReachesSendOrNormalReturn() = runTest {
        for (committed in listOf(false, true)) {
            val normalReturns = AtomicInteger()
            val journal = Journal(wire).apply {
                commit = committed
                afterCommit = { currentCoroutineContext().cancel(CancellationException("Synthetic cancellation")) }
            }
            supervisorScope {
                val task = async {
                    TitleUpdateAttemptAdmission(journal).dispatch(wire, { true }) { error("No send") }
                    normalReturns.incrementAndGet()
                }
                try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            }
            assertEquals(1, journal.claims); assertEquals(0, normalReturns.get())
            assertEquals(if (committed) wire.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN) else wire, journal.stored)
        }
    }
    @Test fun cancelledLeaseReturningFalseCannotBecomeAnOrdinaryDeniedResult() = runTest {
        val journal = Journal(wire); val normalReturns = AtomicInteger()
        supervisorScope {
            val task = async {
                val requestContext = currentCoroutineContext()
                TitleUpdateAttemptAdmission(journal).dispatch(wire, {
                    requestContext.cancel(CancellationException("Synthetic cancelled lease")); false
                }) { error("No send") }
                normalReturns.incrementAndGet()
            }
            try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        }
        assertEquals(0, normalReturns.get()); assertEquals(0, journal.claims)
    }
    @Test fun transportFailurePreservesCommittedUnknownAndPropagates() = runTest {
        val failure = IllegalStateException("Synthetic lost response"); val journal = Journal(wire)
        try {
            TitleUpdateAttemptAdmission(journal).dispatch(wire, { true }) { throw failure }
            fail("Transport failure must propagate")
        } catch (observed: IllegalStateException) { assertSame(failure, observed) }
        assertEquals(wire.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), journal.stored)
    }
}
