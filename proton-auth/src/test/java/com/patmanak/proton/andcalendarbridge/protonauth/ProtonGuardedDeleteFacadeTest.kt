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
class ProtonGuardedDeleteFacadeTest {
    @Test fun transientReadsCrossTheAuthenticatedFacade() = runTest {
        val f = DeleteFacadeFixture(); val record = f.seed(); val runtime = Runtime(record)
        runtime.during = { throw OutgoingReadUnavailableException() }
        val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
        for (stage in 0..2) try {
            when (stage) {
                0 -> facade.prepareSubmission(record.id, record.intent) { true }
                1 -> facade.submit(record, f.journal, { true }, { true }, { true })
                else -> facade.inspect(record.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN), f.journal) { true }
            }
            fail("Read failure was hidden at stage $stage")
        } catch (failure: OutgoingReadUnavailableException) { assertNull(failure.cause) }
    }
    private class DeleteFacadeFixture {
        private val source = TitleSubmissionFixture()
        private val intent = LocalDeleteIntent(source.intent.id, source.intent.scope, source.intent.format,
            source.intent.base, source.intent.base.content)
        fun seed() = DeleteAttemptRecord(MutationId("synthetic-delete"), intent, DELETE_SUBMISSION_FORMAT, "synthetic-protected")
        val journal = object : DeleteAttemptJournal {
            override suspend fun remember(record: DeleteAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean = error("No journal access in facade")
            override suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): DeleteAttemptRecord? = error("No journal access in facade")
            override suspend fun claim(expected: DeleteAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean = error("No journal access in facade")
            override suspend fun retainEvidence(expectedUnknown: DeleteAttemptRecord, protectedEvidence: String,
                admit: (CalendarScope) -> Boolean): Boolean = error("No journal access in facade")
        }
    }

    @Test fun preparationUsesDedicatedRuntimeAndChecksCompleteReturnedBinding() = runTest {
        for (wrong in listOf(false, true)) {
            val f = DeleteFacadeFixture(); val record = f.seed(); val runtime = Runtime(record)
            if (wrong) runtime.prepared = record.copy(id = MutationId("different"))
            val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
            val actual = facade.prepareSubmission(record.id, record.intent) { it == record.scope }
            if (wrong) assertNull(actual) else { assertEquals(record, actual); assertNotSame(record, actual) }
            assertEquals(listOf("prepare"), runtime.calls)
            assertFalse(requireNotNull(runtime.lease)(record.scope))
        }
    }

    @Test fun unauthenticatedAndDeniedOperationsNeverConstructOrEnterRuntime() = runTest {
        val f = DeleteFacadeFixture(); val record = f.seed(); var builds = 0
        val unopened = SerializedAuthenticationPort({ builds++; error("No graph construction") }, {}, StandardTestDispatcher(testScheduler))
        assertNull(unopened.prepareSubmission(record.id, record.intent) { true })
        assertEquals(DeleteSubmissionObservation.Unknown, unopened.submit(record, f.journal, { true }, { true }, { true }))
        assertEquals(DeleteSubmissionObservation.Unknown, unopened.inspect(record.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN), f.journal) { true })
        assertEquals(0, builds)
        val runtime = Runtime(record); val authenticated = facade(runtime, StandardTestDispatcher(testScheduler)); authenticated.restore()
        assertNull(authenticated.prepareSubmission(record.id, record.intent) { false })
        assertEquals(DeleteSubmissionObservation.Unknown, authenticated.submit(record, f.journal, { false }, { true }, { true }))
        assertTrue(runtime.calls.isEmpty())
    }

    @Test fun absentOrWrongCapabilityRefusesWithoutRuntimeFallback() = runTest {
        val f = DeleteFacadeFixture(); val record = f.seed()
        for (format in listOf(null, TITLE_UPDATE_SUBMISSION_FORMAT)) {
            val runtime = Runtime(record).apply { deleteFormat = format }
            val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
            assertNull(facade.prepareSubmission(record.id, record.intent) { true })
            assertEquals(DeleteSubmissionObservation.Unknown, facade.submit(record, f.journal, { true }, { true }, { true }))
            assertEquals(DeleteSubmissionObservation.Unknown, facade.inspect(record.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN), f.journal) { true })
            assertTrue(runtime.calls.isEmpty())
        }
        val absent = facade(null, StandardTestDispatcher(testScheduler)); absent.restore()
        assertNull(absent.prepareSubmission(record.id, record.intent) { true })
    }

    @Test fun submissionKeepsEvidenceLeaseIndependentAndAllCallbacksExpireOnReturn() = runTest {
        val f = DeleteFacadeFixture(); val record = f.seed(); var allowed = true
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
        assertEquals(DeleteSubmissionObservation.Unknown, facade.submit(record, f.journal, { allowed }, { true }, { true }))
        assertEquals(listOf("submit"), runtime.calls)
        assertFalse(requireNotNull(runtime.lease)(record.scope)); assertFalse(requireNotNull(runtime.evidenceLease)(record.scope))
        assertFalse(requireNotNull(runtime.source)(record.scope))
    }

    @Test fun verifiedResultCannotChangeRequestOrFullDeletionBinding() = runTest {
        for (wrong in listOf(false, true)) {
            val f = DeleteFacadeFixture(); val record = f.seed(); val runtime = Runtime(record)
            val returned = record.copy(id = if (wrong) MutationId("other") else record.id, state = DeleteAttemptState.OUTCOME_UNKNOWN)
            runtime.observation = DeleteSubmissionObservation.Verified(returned)
            val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
            val actual = facade.submit(record, f.journal, { true }, { true }, { true })
            if (wrong) assertEquals(DeleteSubmissionObservation.Unknown, actual)
            else assertEquals(returned, (actual as DeleteSubmissionObservation.Verified).record)
            assertEquals(listOf("submit"), runtime.calls)
        }
    }

    @Test fun unknownInspectionRoutesOnlyToInspectionAndCannotSubmitOrPrepare() = runTest {
        val f = DeleteFacadeFixture(); val record = f.seed().copy(state = DeleteAttemptState.OUTCOME_UNKNOWN)
        val runtime = Runtime(record).apply { observation = DeleteSubmissionObservation.Verified(record) }
        val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
        val actual = facade.inspect(record, f.journal) { true }
        assertTrue(actual is DeleteSubmissionObservation.Verified); assertEquals(listOf("inspect"), runtime.calls)
        assertFalse(requireNotNull(runtime.lease)(record.scope))
    }

    @Test fun conflictPreservesVerifiedRemoteVariantAndCannotChangeTheDeleteIdentity() = runTest {
        for (unknown in listOf(false, true)) for (wrong in listOf(false, true)) {
            val f = DeleteFacadeFixture()
            val record = f.seed().copy(state = if (unknown) DeleteAttemptState.OUTCOME_UNKNOWN else DeleteAttemptState.PREPARED)
            val returned = record.copy(id = if (wrong) MutationId("unrelated-delete") else record.id)
            val remote = record.intent.base.copy(revision = Revision("synthetic-later-revision"),
                content = record.intent.base.content.copy(title = "Synthetic remote change"))
            val runtime = Runtime(record).apply { observation = DeleteSubmissionObservation.Conflict(returned, remote) }
            val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
            val result = if (unknown) facade.inspect(record, f.journal) { true }
                else facade.submit(record, f.journal, { true }, { true }, { true })
            if (wrong) assertEquals(DeleteSubmissionObservation.Unknown, result)
            else { result as DeleteSubmissionObservation.Conflict; assertEquals(record, result.record); assertEquals(remote, result.event) }
            assertEquals(listOf(if (unknown) "inspect" else "submit"), runtime.calls)
        }
    }

    @Test fun logoutWaitsForSubmissionAndThenPreventsAllDeleteCalls() = runTest {
        val f = DeleteFacadeFixture(); val record = f.seed(); val runtime = Runtime(record); val auth = Auth()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        runtime.during = { if (it == "submit") { entered.complete(Unit); release.await() } }
        val facade = facade(runtime, StandardTestDispatcher(testScheduler), auth); facade.restore()
        val work = async { facade.submit(record, f.journal, { true }, { true }, { true }) }
        runCurrent(); assertTrue(entered.isCompleted)
        val logout = async { facade.cancel() }; runCurrent(); assertEquals(0, auth.cancellations)
        release.complete(Unit); work.await(); logout.await(); assertEquals(1, auth.cancellations)
        assertNull(facade.prepareSubmission(record.id, record.intent) { true })
        assertEquals(listOf("submit"), runtime.calls)
    }

    @Test fun runtimeSwallowingCancellationCannotReturnNullOrUnknownNormally() = runTest {
        for (phase in listOf("prepare", "submit", "inspect")) for (throws in listOf(false, true)) {
            val f = DeleteFacadeFixture(); val record = f.seed(); val runtime = Runtime(record)
            var reached = false; var cancelled = 0; var ordinary = 0; var returned = 0
            runtime.during = { reached = true; currentCoroutineContext().cancel(); if (throws) throw IOException("Synthetic cancellation") }
            val facade = facade(runtime, StandardTestDispatcher(testScheduler)); facade.restore()
            supervisorScope {
                val child = async {
                    try {
                        when (phase) {
                            "prepare" -> facade.prepareSubmission(record.id, record.intent) { true }
                            "submit" -> facade.submit(record, f.journal, { true }, { true }, { true })
                            else -> facade.inspect(record.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN), f.journal) { true }
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

    private class Runtime(private val record: DeleteAttemptRecord) : CalendarDeleteSubmissionPort {
        override var deleteFormat: AdapterFormat? = DELETE_SUBMISSION_FORMAT
        val calls = mutableListOf<String>()
        var during: suspend (String) -> Unit = {}
        var prepared: DeleteAttemptRecord? = record
        var observation: DeleteSubmissionObservation = DeleteSubmissionObservation.Unknown
        var lease: ((CalendarScope) -> Boolean)? = null
        var evidenceLease: ((CalendarScope) -> Boolean)? = null
        var source: (suspend (CalendarScope) -> Boolean)? = null
        override suspend fun prepareSubmission(id: MutationId, intent: LocalDeleteIntent,
            admit: (CalendarScope) -> Boolean): DeleteAttemptRecord? {
            assertEquals(record.id, id); assertEquals(record.intent, intent)
            assertNotSame(record.intent, intent); lease = admit; calls += "prepare"; during("prepare"); return prepared
        }
        override suspend fun submit(record: DeleteAttemptRecord, journal: DeleteAttemptJournal,
            admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
            admitEvidence: (CalendarScope) -> Boolean): DeleteSubmissionObservation {
            assertEquals(this.record, record); assertNotSame(this.record, record)
            lease = admit; source = qualifySource; evidenceLease = admitEvidence; calls += "submit"; during("submit"); return observation
        }
        override suspend fun inspect(record: DeleteAttemptRecord, journal: DeleteAttemptJournal,
            admit: (CalendarScope) -> Boolean): DeleteSubmissionObservation {
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
    private fun facade(runtime: CalendarDeleteSubmissionPort?, dispatcher: CoroutineDispatcher, auth: Auth = Auth()) =
        SerializedAuthenticationPort({ ProtonAuthenticationGraph(auth, NoEvents, deletions = runtime) {
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
