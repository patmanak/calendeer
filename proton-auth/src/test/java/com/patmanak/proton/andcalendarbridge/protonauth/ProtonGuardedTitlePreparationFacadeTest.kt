// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.auth.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogResult
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** The production facade must retain the guarded overload across dispatch and its shared lock. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtonGuardedTitlePreparationFacadeTest {
    private fun TitleSubmissionFixture.reservation() = TitleUpdatePreparationReservation.of(record)

    @Test fun forwardsFrozenReservationAndOriginalScopeGuardOnlyToGuardedRuntime() = runTest {
        val f = TitleSubmissionFixture(); var calls = 0
        val runtime = object : PreparationPort() {
            override suspend fun prepareSubmission(reservation: TitleUpdatePreparationReservation,
                admit: (CalendarScope) -> Boolean): TitleUpdateAttemptRecord? {
                calls++; assertEquals(f.reservation(), reservation)
                assertTrue(admit(f.scope)); assertFalse(admit(f.scope.copy(generation = 2)))
                return f.record
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        assertEquals(f.record, port.prepareSubmission(f.reservation()) { it == f.scope }); assertEquals(1, calls)
    }

    @Test fun broadCallerAdmissionIsNarrowedToReservedScopeAtRuntime() = runTest {
        val f = TitleSubmissionFixture(); var calls = 0
        val runtime = object : PreparationPort() {
            override suspend fun prepareSubmission(reservation: TitleUpdatePreparationReservation,
                admit: (CalendarScope) -> Boolean): TitleUpdateAttemptRecord? {
                calls++; assertEquals(f.reservation(), reservation)
                assertTrue(admit(f.scope))
                assertFalse(admit(f.scope.copy(generation = f.scope.generation + 1)))
                assertFalse(admit(f.scope.copy(account = AccountId("synthetic-other-account"))))
                assertFalse(admit(f.scope.copy(calendar = CalendarId("synthetic-other-calendar"))))
                return f.record
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        assertEquals(f.record, port.prepareSubmission(f.reservation()) { true })
        assertEquals(1, calls)
    }
    @Test fun defaultGuardedCapabilityAndMissingRuntimeDenyWithoutLegacyFallback() = runTest {
        val f = TitleSubmissionFixture()
        for (runtime in listOf(PreparationPort(), null)) {
            val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
            assertNull(port.prepareSubmission(f.reservation()) { true })
        }
    }

    @Test fun unauthenticatedCallsNeverBuildRuntimeEvenWhenAdmissionIsTrue() = runTest {
        val f = TitleSubmissionFixture(); var builds = 0
        val port = SerializedAuthenticationPort({ builds++; error("No graph may be built") }, {}, StandardTestDispatcher(testScheduler))
        assertNull(port.prepareSubmission(f.reservation()) { true })
        assertNull(port.prepareSubmission(f.reservation()) { false })
        assertEquals(0, builds)
    }

    @Test fun revocationWhileWaitingForAuthenticationMutexStopsBeforeRuntime() = runTest {
        val f = TitleSubmissionFixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0; var admitted = true
        val runtime = object : PreparationPort() {
            override suspend fun prepareSubmission(reservation: TitleUpdatePreparationReservation,
                admit: (CalendarScope) -> Boolean): TitleUpdateAttemptRecord? { calls++; return f.record }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)) { entered.complete(Unit); release.await() }
        port.restore(); val busy = async { port.load() }; entered.await()
        val preparation = async { port.prepareSubmission(f.reservation()) { admitted } }
        runCurrent(); admitted = false; release.complete(Unit); busy.await()
        assertNull(preparation.await()); assertEquals(0, calls)
    }

    @Test fun revocationDuringRuntimeAwaitDiscardsReturnedPreparation() = runTest {
        val f = TitleSubmissionFixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var admitted = true
        val runtime = object : PreparationPort() {
            override suspend fun prepareSubmission(reservation: TitleUpdatePreparationReservation,
                admit: (CalendarScope) -> Boolean): TitleUpdateAttemptRecord? {
                entered.complete(Unit); release.await(); return f.record
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        val work = async { port.prepareSubmission(f.reservation()) { admitted } }
        runCurrent(); assertTrue("Guarded runtime was not invoked", entered.isCompleted)
        admitted = false; release.complete(Unit)
        assertNull(work.await())
    }

    @Test fun freezesReservationBeforeDispatcherAndSharedMutexSuspension() = runTest {
        val f = TitleSubmissionFixture(); val reminders = mutableListOf<EventReminder>()
        val intent = f.intent.copy(content = f.intent.content.copy(details = EventDetails(reminders = reminders)))
        val reservation = f.reservation().copy(preparation = f.preparation.copy(intent = intent))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val runtime = object : PreparationPort() {
            override suspend fun prepareSubmission(reservation: TitleUpdatePreparationReservation,
                admit: (CalendarScope) -> Boolean): TitleUpdateAttemptRecord? {
                calls++; assertEquals(f.reservation(), reservation)
                assertTrue(checkNotNull(reservation.preparation.intent.content.details).reminders.isEmpty())
                return f.record
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)) { entered.complete(Unit); release.await() }
        port.restore(); val busy = async { port.load() }; entered.await()
        val work = async(start = CoroutineStart.UNDISPATCHED) { port.prepareSubmission(reservation) { true } }
        reminders += EventReminder(10); release.complete(Unit); busy.await()
        assertEquals(f.record, work.await()); assertEquals(1, calls)
    }

    @Test fun runtimeTypedFailurePropagatesWithoutBeingConvertedToNull() = runTest {
        val f = TitleSubmissionFixture()
        for (reason in LocalEditPreparationFailure.entries) {
            val failure = LocalEditPreparationException(reason)
            val runtime = object : PreparationPort() {
                override suspend fun prepareSubmission(reservation: TitleUpdatePreparationReservation,
                    admit: (CalendarScope) -> Boolean): TitleUpdateAttemptRecord? = throw failure
            }
            val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
            try { port.prepareSubmission(f.reservation()) { true }; fail("Expected typed failure") }
            catch (actual: LocalEditPreparationException) { assertSame(failure, actual) }
        }
    }

    @Test fun cancelledRuntimeReturningNullDoesNotCompleteFacadeNormally() = runTest {
        val f = TitleSubmissionFixture(); var returned = false; var calls = 0
        lateinit var work: Deferred<Unit>
        val runtime = object : PreparationPort() {
            override suspend fun prepareSubmission(reservation: TitleUpdatePreparationReservation,
                admit: (CalendarScope) -> Boolean): TitleUpdateAttemptRecord? { calls++; work.cancel(); return null }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        work = async(start = CoroutineStart.LAZY) { port.prepareSubmission(f.reservation()) { true }; returned = true }
        work.start()
        try { work.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(1, calls); assertFalse(returned)
    }

    private fun facade(runtime: CalendarTitleUpdateSubmissionPort?, dispatcher: CoroutineDispatcher,
        holdCatalog: suspend () -> Unit = {}): SerializedAuthenticationPort = SerializedAuthenticationPort({
        ProtonAuthenticationGraph(object : AuthenticationFlowPort {
            override suspend fun restore() = AuthenticationStep.Authenticated
            override suspend fun signIn(username: AuthenticationSecret, password: AuthenticationSecret) = error("No sign in")
            override suspend fun submitCode(code: AuthenticationSecret) = error("No code")
            override suspend fun unlock(password: AuthenticationSecret) = error("No unlock")
            override suspend fun cancel() = Unit
        }, NoEvents, titleUpdates = runtime) { holdCatalog(); CalendarCatalogResult.Success(AccountId("synthetic-account"), emptyList()) }
    }, {}, dispatcher)

    private open class PreparationPort : CalendarTitleUpdateSubmissionPort {
        override val titleUpdateFormat = TITLE_UPDATE_SUBMISSION_FORMAT
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord = error("Legacy preparation must not be used")
        override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation = error("No send")
        override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation = error("No inspection")
    }

    private object NoEvents : CalendarDeltaRemote {
        override val format = EVENT_FORMAT
        override val deltaFormat = CALENDAR_DELTA_FORMAT
        override suspend fun currentAccount(): AccountId? = error("No current account")
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
