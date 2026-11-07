// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PersonalAcknowledgementRecoveryTest {
    private val scope = CalendarScope(AccountId("fixture"), CalendarId("calendar"), 0)
    private val format = AdapterFormat("synthetic", 2)
    private val base = RemoteChange.Upsert(EventId("event"), Revision("base"), EventContent("Before",
        EventTime.AllDay(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 2)), details = EventDetails()))
    private val intent = LocalEditIntent(MutationId("owner"), scope, format, base,
        base.content.copy(title = "After", description = "Changed"))
    private val preparation = PersonalEditPreparation(intent, Instant.parse("2030-01-01T00:00:00Z"), format, "synthetic-capsule")
    private val record = PersonalUpdateAttemptRecord(MutationId("attempt"), preparation, format, "synthetic-wire",
        PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
    private val result = PersonalUpdateVerifiedResult.of(record, PersonalUpdateSubmissionObservation.Verified(record,
        base.copy(revision = Revision("after"), content = intent.content)))
    private val applied = AppliedPersonalUpdateAcknowledgement(PersonalUpdateAcknowledgementReceipt.of(result,
        "a7b390b1-01db-45c9-a149-35a34611c353"))

    private class Calls { val values = mutableListOf<String>() }
    private suspend fun run(calls: Calls, fresh: Boolean = false, protocol: Int? = 1,
        historical: Boolean = false, qualified: Boolean = true,
        inspectHook: suspend () -> Unit = {}, qualifyHook: suspend () -> Unit = {},
        resumeHook: suspend () -> Unit = {}): AppliedPersonalUpdateAcknowledgement? = recoverPersonalAcknowledgement(
        fresh, protocol,
        inspect = { calls.values += "inspect"; inspectHook(); if (historical) applied else null },
        qualifyCurrentOwner = { calls.values += "qualify"; qualifyHook(); qualified },
        applyFresh = { calls.values += "fresh"; applied },
        resume = { calls.values += "resume"; resumeHook(); applied },
    )

    @Test fun pendingBeforeNativeWriteInspectsThenRequalifiesAndFinishesSameReceipt() = runBlocking {
        val calls = Calls()
        assertEquals(applied, run(calls))
        assertEquals(listOf("inspect", "qualify", "resume"), calls.values)
    }
    @Test fun historicalProofPreservesLaterDirtyContentsWithoutAWrite() = runBlocking {
        val calls = Calls()
        assertEquals(applied, run(calls, historical = true, qualified = false))
        assertEquals(listOf("inspect"), calls.values)
    }
    @Test fun unknownCapabilityVersionsRemainInspectionOnly() = runBlocking {
        for (protocol in listOf(null, 0, 2, Int.MAX_VALUE)) {
            val calls = Calls()
            assertNull(run(calls, protocol = protocol))
            assertEquals(listOf("inspect"), calls.values)
        }
    }
    @Test fun changedOwnerOrSourceCannotBeOverwritten() = runBlocking {
        val calls = Calls()
        assertNull(run(calls, qualified = false))
        assertEquals(listOf("inspect", "qualify"), calls.values)
    }
    @Test fun freshReservationKeepsItsOriginalOneShotOperation() = runBlocking {
        val calls = Calls()
        assertEquals(applied, run(calls, fresh = true, protocol = null))
        assertEquals(listOf("fresh"), calls.values)
    }
    @Test fun cancellationSwallowedByAnyBoundaryCannotAuthorizeTheNextStep() = runBlocking {
        for (boundary in listOf("inspect", "qualify", "resume")) {
            val calls = Calls()
            val attempt = async {
                val operation = currentCoroutineContext()
                val cancel: suspend () -> Unit = { operation.cancel(); Unit }
                run(calls, inspectHook = if (boundary == "inspect") cancel else ({}),
                    qualifyHook = if (boundary == "qualify") cancel else ({}),
                    resumeHook = if (boundary == "resume") cancel else ({}))
            }
            try { attempt.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            assertEquals(listOf("inspect", "qualify", "resume").takeWhile { it != boundary } + boundary, calls.values)
        }
    }
}
