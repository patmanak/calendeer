// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.auth.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogResult
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtonGuardedTextUpdateFacadeTest {
    @Test fun preparationUsesDedicatedRuntimeAndChecksCompleteReturnedBinding() = runTest {
        for (wrong in listOf(false, true)) {
            val f = TextSubmissionFixture(); val record = f.seed(); val runtime = Runtime(record)
            if (wrong) runtime.prepared = record.copy(id = MutationId("different"))
            val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
            val actual = facade.prepareSubmission(record.id, record.preparation) { it == record.scope }
            if (wrong) assertNull(actual) else { assertEquals(record, actual); assertNotSame(record, actual) }
            assertEquals(listOf("prepare"), runtime.calls)
            assertFalse(requireNotNull(runtime.lease)(record.scope))
        }
    }

    @Test fun unauthenticatedAndDeniedOperationsNeverConstructOrEnterRuntime() = runTest {
        val f = TextSubmissionFixture(); val record = f.seed(); var builds = 0
        val unopened = SerializedAuthenticationPort({ builds++; error("No graph construction") }, {}, StandardTestDispatcher(testScheduler))
        assertNull(unopened.prepareSubmission(record.id, record.preparation) { true })
        assertEquals(TextUpdateSubmissionObservation.Unknown, unopened.submit(record, f.journal, { true }, { true }, { true }))
        assertEquals(TextUpdateSubmissionObservation.Unknown, unopened.inspect(record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN), f.journal) { true })
        assertEquals(0, builds)
        val runtime = Runtime(record); val authenticated = facade(runtime, StandardTestDispatcher(testScheduler)); authenticated.restore()
        assertNull(authenticated.prepareSubmission(record.id, record.preparation) { false })
        assertEquals(TextUpdateSubmissionObservation.Unknown, authenticated.submit(record, f.journal, { false }, { true }, { true }))
        assertTrue(runtime.calls.isEmpty())
    }

    @Test fun absentOrWrongCapabilityRefusesWithoutRuntimeFallback() = runTest {
        val f = TextSubmissionFixture(); val record = f.seed()
        for (format in listOf(null, TITLE_UPDATE_SUBMISSION_FORMAT)) {
            val runtime = Runtime(record).apply { textUpdateFormat = format }
            val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
            assertNull(facade.prepareSubmission(record.id, record.preparation) { true })
            assertEquals(TextUpdateSubmissionObservation.Unknown, facade.submit(record, f.journal, { true }, { true }, { true }))
            assertEquals(TextUpdateSubmissionObservation.Unknown, facade.inspect(record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN), f.journal) { true })
            assertTrue(runtime.calls.isEmpty())
        }
        val absent = facade(null, StandardTestDispatcher(testScheduler)); absent.restore()
        assertNull(absent.prepareSubmission(record.id, record.preparation) { true })
    }

    @Test fun submissionKeepsEvidenceLeaseIndependentAndAllCallbacksExpireOnReturn() = runTest {
        val f = TextSubmissionFixture(); val record = f.seed(); var allowed = true
        val runtime = Runtime(record)
        runtime.during = { phase -> if (phase == "submit") {
            val lease = requireNotNull(runtime.lease); val evidence = requireNotNull(runtime.evidenceLease)
            assertTrue(lease(record.scope)); assertTrue(evidence(record.scope))
            assertFalse(evidence(record.scope.copy(generation = record.scope.generation + 1)))
            allowed = false
            assertFalse(lease(record.scope)); assertTrue(evidence(record.scope))
            allowed = true; assertFalse(lease(record.scope))
        } }
        val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
        assertEquals(TextUpdateSubmissionObservation.Unknown, facade.submit(record, f.journal, { allowed }, { true }, { true }))
        assertEquals(listOf("submit"), runtime.calls)
        assertFalse(requireNotNull(runtime.lease)(record.scope)); assertFalse(requireNotNull(runtime.evidenceLease)(record.scope))
        assertFalse(requireNotNull(runtime.source)(record.scope))
    }

    @Test fun verifiedResultCannotChangeRequestOrFullPreparationBinding() = runTest {
        for (wrong in listOf(false, true)) {
            val f = TextSubmissionFixture(); val record = f.seed(); val runtime = Runtime(record)
            val returned = record.copy(id = if (wrong) MutationId("other") else record.id, state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
            runtime.observation = TextUpdateSubmissionObservation.Verified(returned, requireNotNull(f.decoded))
            val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
            val actual = facade.submit(record, f.journal, { true }, { true }, { true })
            if (wrong) assertEquals(TextUpdateSubmissionObservation.Unknown, actual)
            else assertEquals(returned, (actual as TextUpdateSubmissionObservation.Verified).record)
            assertEquals(listOf("submit"), runtime.calls)
        }
    }

    @Test fun unknownInspectionRoutesOnlyToInspectionAndCannotSubmitOrPrepare() = runTest {
        val f = TextSubmissionFixture(); val record = f.seed().copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
        val runtime = Runtime(record).apply { observation = TextUpdateSubmissionObservation.Verified(record, requireNotNull(f.decoded)) }
        val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
        val actual = facade.inspect(record, f.journal) { true }
        assertTrue(actual is TextUpdateSubmissionObservation.Verified); assertEquals(listOf("inspect"), runtime.calls)
        assertFalse(requireNotNull(runtime.lease)(record.scope))
    }

    @Test fun logoutWaitsForSubmissionAndThenPreventsAllTextCalls() = runTest {
        val f = TextSubmissionFixture(); val record = f.seed(); val runtime = Runtime(record); val auth = Auth()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        runtime.during = { if (it == "submit") { entered.complete(Unit); release.await() } }
        val facade = facade(runtime, StandardTestDispatcher(testScheduler), auth); facade.restore()
        val work = async { facade.submit(record, f.journal, { true }, { true }, { true }) }
        runCurrent(); assertTrue(entered.isCompleted)
        val logout = async { facade.cancel() }; runCurrent(); assertEquals(0, auth.cancellations)
        release.complete(Unit); work.await(); logout.await(); assertEquals(1, auth.cancellations)
        assertNull(facade.prepareSubmission(record.id, record.preparation) { true })
        assertEquals(listOf("submit"), runtime.calls)
    }

    @Test fun runtimeSwallowingCancellationCannotReturnNullOrUnknownNormally() = runTest {
        for (phase in listOf("prepare", "submit", "inspect")) for (throws in listOf(false, true)) {
            val f = TextSubmissionFixture(); val record = f.seed(); val runtime = Runtime(record)
            var reached = false; var cancelled = 0; var ordinary = 0; var returned = 0
            runtime.during = { reached = true; currentCoroutineContext().cancel(); if (throws) throw IOException("Synthetic cancellation") }
            val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
            supervisorScope {
                val child = async {
                    try {
                        when (phase) {
                            "prepare" -> facade.prepareSubmission(record.id, record.preparation) { true }
                            "submit" -> facade.submit(record, f.journal, { true }, { true }, { true })
                            else -> facade.inspect(record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN), f.journal) { true }
                        }
                        returned++
                    } catch (_: CancellationException) { cancelled++ }
                      catch (_: Exception) { ordinary++ }
                }
                try { child.await() } catch (_: CancellationException) { }
            }
            assertTrue("Runtime reached: $phase/$throws", reached)
            assertEquals("Cancellation: $phase/$throws", 1, cancelled)
            assertEquals("Ordinary failure: $phase/$throws", 0, ordinary)
            assertEquals("Normal return: $phase/$throws", 0, returned)
        }
    }

    private class Runtime(private val record: TextUpdateAttemptRecord) : CalendarTextUpdateSubmissionPort {
        override var textUpdateFormat: AdapterFormat? = TEXT_UPDATE_REQUEST_FORMAT
        val calls = mutableListOf<String>()
        var during: suspend (String) -> Unit = {}
        var prepared: TextUpdateAttemptRecord? = record
        var observation: TextUpdateSubmissionObservation = TextUpdateSubmissionObservation.Unknown
        var lease: ((CalendarScope) -> Boolean)? = null
        var evidenceLease: ((CalendarScope) -> Boolean)? = null
        var source: (suspend (CalendarScope) -> Boolean)? = null
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalTextEditPreparation,
            admit: (CalendarScope) -> Boolean): TextUpdateAttemptRecord? {
            assertEquals(record.id, id); assertEquals(record.preparation.intent, preparation.intent)
            assertNotSame(record.preparation, preparation); lease = admit; calls += "prepare"; during("prepare"); return prepared
        }
        override suspend fun submit(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation {
            assertEquals(this.record, record); assertNotSame(this.record, record)
            lease = admit; source = qualifySource; evidenceLease = admitEvidence; calls += "submit"; during("submit"); return observation
        }
        override suspend fun inspect(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation {
            lease = admit; calls += "inspect"; during("inspect"); return observation
        }
    }
    private class Auth : AuthenticationFlowPort {
        var cancellations = 0
        override suspend fun restore() = AuthenticationStep.Authenticated
        override suspend fun signIn(username: AuthenticationSecret, password: AuthenticationSecret) = error("No login")
        override suspend fun submitCode(code: AuthenticationSecret) = error("No code")
        override suspend fun unlock(password: AuthenticationSecret) = error("No unlock")
        override suspend fun cancel() { cancellations++ }
    }
    private fun facade(runtime: CalendarTextUpdateSubmissionPort?, dispatcher: CoroutineDispatcher, auth: Auth = Auth()) =
        SerializedAuthenticationPort({ ProtonAuthenticationGraph(auth, NoEvents, textUpdates = runtime) {
            CalendarCatalogResult.Success(AccountId("synthetic-account"), emptyList())
        } }, {}, dispatcher)
    private object NoEvents : CalendarDeltaRemote {
        override val format = EVENT_FORMAT
        override val deltaFormat = CALENDAR_DELTA_FORMAT
        override suspend fun currentAccount(): AccountId? = error("No auth re-entry")
        override suspend fun dispatch(mutation: Mutation): DispatchResult = error("No dispatch")
        override suspend fun fetch(request: PageRequest): RemotePage = error("No fetch")
        override suspend fun receive(request: PageRequest): ReceivedCalendarPage = error("No receive")
        override suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage = error("No decode")
        override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? = error("No retry")
        override suspend fun latestChanges(scope: CalendarScope): OpaqueCursor = error("No feed")
        override suspend fun receiveChanges(scope: CalendarScope, after: OpaqueCursor): ReceivedChangePage = error("No feed")
        override suspend fun decodeChanges(received: ReceivedChangePage): DecodedChangePage = error("No feed")
        override suspend fun receiveEvent(scope: CalendarScope, eventId: EventId): ReceivedCalendarEvent = error("No GET")
        override suspend fun decodeEvent(received: ReceivedCalendarEvent): DecodedCalendarEvent = error("No decode")
        override suspend fun refreshContext(scope: CalendarScope) = error("No context")
    }
}
