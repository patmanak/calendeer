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
class ProtonGuardedTitleSubmissionFacadeTest {
    private fun verified(f: TitleSubmissionFixture) =
        TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(f.record), checkNotNull(f.verified))

    @Test fun guardedRuntimeReceivesFrozenRecordAndScopeBoundedLease() = runTest {
        val f = TitleSubmissionFixture(); var calls = 0
        val expected = verified(f)
        val runtime = object : SubmissionPort() {
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                calls++; assertEquals(f.record, record); assertSame(f.journal, journal)
                assertTrue(admit(f.scope))
                assertFalse(admit(f.scope.copy(generation = f.scope.generation + 1)))
                assertFalse(admit(f.scope.copy(account = AccountId("synthetic-other-account"))))
                assertFalse(admit(f.scope.copy(calendar = CalendarId("synthetic-other-calendar"))))
                return expected
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        val actual = port.submit(f.record, f.journal) { true } as TitleUpdateSubmissionObservation.Verified
        assertEquals(expected.attempt, actual.attempt); assertEquals(expected.event, actual.event); assertEquals(1, calls)
    }

    @Test fun missingRuntimeOrGuardedCapabilityNeverCallsLegacySubmission() = runTest {
        val f = TitleSubmissionFixture()
        for (runtime in listOf(SubmissionPort(), null)) {
            val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
            assertEquals(TitleUpdateSubmissionObservation.Unknown, port.submit(f.record, f.journal) { true })
        }
    }

    @Test fun unauthenticatedOrDeniedCallsNeverBuildRuntime() = runTest {
        val f = TitleSubmissionFixture(); var builds = 0
        val port = SerializedAuthenticationPort({ builds++; error("No graph") }, {}, StandardTestDispatcher(testScheduler))
        assertEquals(TitleUpdateSubmissionObservation.Unknown, port.submit(f.record, f.journal) { true })
        assertEquals(TitleUpdateSubmissionObservation.Unknown, port.submit(f.record, f.journal) { false })
        assertEquals(0, builds)
    }

    @Test fun unknownEvidenceAndIncompatibleFormatNeverReachRuntime() = runTest {
        val f = TitleSubmissionFixture(); var calls = 0
        val runtime = object : SubmissionPort() {
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation { calls++; return verified(f) }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        for (invalid in listOf(f.record.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN),
            f.record.copy(protectedEvidence = "invented-evidence"),
            f.record.copy(format = f.record.format.copy(version = 2)))) {
            assertEquals(TitleUpdateSubmissionObservation.Unknown, port.submit(invalid, f.journal) { true })
        }
        runtime.titleUpdateFormat = null
        assertEquals(TitleUpdateSubmissionObservation.Unknown, port.submit(f.record, f.journal) { true })
        assertEquals(0, calls)
    }

    @Test fun revocationWhileWaitingForAuthenticationMutexStopsBeforeRuntime() = runTest {
        val f = TitleSubmissionFixture(); var admitted = true; var calls = 0
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val runtime = object : SubmissionPort() {
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation { calls++; return verified(f) }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)) { entered.complete(Unit); release.await() }
        port.restore(); val busy = async { port.load() }; entered.await()
        val work = async { port.submit(f.record, f.journal) { admitted } }
        runCurrent(); admitted = false; release.complete(Unit); busy.await()
        assertEquals(TitleUpdateSubmissionObservation.Unknown, work.await()); assertEquals(0, calls)
    }

    @Test fun freezesRecordBeforeDispatcherAndSharedMutexSuspension() = runTest {
        val f = TitleSubmissionFixture(); val reminders = mutableListOf<EventReminder>(); var calls = 0
        val incoming = f.record.copy(preparation = f.preparation.copy(intent = f.intent.copy(
            content = f.intent.content.copy(details = EventDetails(reminders = reminders)))))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val runtime = object : SubmissionPort() {
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                calls++; assertEquals(f.record, record)
                assertTrue(checkNotNull(record.preparation.intent.content.details).reminders.isEmpty())
                return verified(f)
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)) { entered.complete(Unit); release.await() }
        port.restore(); val busy = async { port.load() }; entered.await()
        val work = async(start = CoroutineStart.UNDISPATCHED) { port.submit(incoming, f.journal) { true } }
        reminders += EventReminder(10); release.complete(Unit); busy.await()
        assertTrue(work.await() is TitleUpdateSubmissionObservation.Verified); assertEquals(1, calls)
    }

    @Test fun revocationDuringRuntimeAwaitSuppressesVerifiedResult() = runTest {
        val f = TitleSubmissionFixture(); var admitted = true
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val runtime = object : SubmissionPort() {
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                entered.complete(Unit); release.await(); return verified(f)
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        val work = async { port.submit(f.record, f.journal) { admitted } }
        runCurrent(); assertTrue("Guarded runtime was not invoked", entered.isCompleted)
        admitted = false; release.complete(Unit)
        assertEquals(TitleUpdateSubmissionObservation.Unknown, work.await())
    }

    @Test fun revokedLeaseCannotBeReenabledDuringRuntimeCall() = runTest {
        val f = TitleSubmissionFixture(); var admitted = true
        val runtime = object : SubmissionPort() {
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                assertTrue(admit(f.scope))
                admitted = false; assertFalse(admit(f.scope))
                admitted = true; assertFalse("Revocation is monotone for this call", admit(f.scope))
                return verified(f)
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        assertEquals(TitleUpdateSubmissionObservation.Unknown, port.submit(f.record, f.journal) { admitted })
    }

    @Test fun runtimeFormatChangeClosesLeaseAndSuppressesReturnedVerification() = runTest {
        val f = TitleSubmissionFixture()
        val runtime = object : SubmissionPort() {
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                titleUpdateFormat = TITLE_UPDATE_SUBMISSION_FORMAT.copy(version = 2)
                assertFalse(admit(f.scope))
                return verified(f)
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        assertEquals(TitleUpdateSubmissionObservation.Unknown, port.submit(f.record, f.journal) { true })
    }

    @Test fun verifiedResultRequiresEveryAttemptIdentityField() = runTest {
        val f = TitleSubmissionFixture(); val identity = TitleUpdateAttemptIdentity.of(f.record)
        val invalid = listOf(
            identity.copy(id = MutationId("different-attempt")),
            identity.copy(scope = f.scope.copy(generation = f.scope.generation + 1)),
            identity.copy(localIntentId = MutationId("different-intent")),
            identity.copy(baseRevision = Revision("different-base")),
            identity.copy(format = identity.format.copy(version = 2)),
            identity.copy(preparationFormat = identity.preparationFormat.copy(version = 2)),
            identity.copy(preparationFingerprint = "1".repeat(64)),
            identity.copy(payloadFingerprint = "2".repeat(64)),
        )
        for (other in invalid) {
            val runtime = object : SubmissionPort() {
                override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                    admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation =
                    TitleUpdateSubmissionObservation.Verified(other, checkNotNull(f.verified))
            }
            val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
            assertEquals(TitleUpdateSubmissionObservation.Unknown, port.submit(f.record, f.journal) { true })
        }
    }

    @Test fun matchingTitleWithDifferentFullContentCannotVerify() = runTest {
        val f = TitleSubmissionFixture()
        for (content in listOf(f.intent.content.copy(description = "Different description"),
            f.intent.content.copy(location = "Different location"), f.intent.content.copy(title = "Different title"))) {
            val runtime = object : SubmissionPort() {
                override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                    admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation =
                    TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(f.record),
                        checkNotNull(f.verified).copy(content = content))
            }
            val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
            assertEquals(TitleUpdateSubmissionObservation.Unknown, port.submit(f.record, f.journal) { true })
        }
    }

    @Test fun cancellationReturningUnknownCannotCompleteNormally() = runTest {
        val f = TitleSubmissionFixture(); var returned = false; var calls = 0
        val runtime = object : SubmissionPort() {
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                calls++; currentCoroutineContext().cancel(CancellationException("Synthetic cancellation"))
                return TitleUpdateSubmissionObservation.Unknown
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        supervisorScope {
            val work = async { port.submit(f.record, f.journal) { true }; returned = true }
            try { work.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        }
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

    private open class SubmissionPort : CalendarTitleUpdateSubmissionPort {
        override var titleUpdateFormat: AdapterFormat? = TITLE_UPDATE_SUBMISSION_FORMAT
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord = error("Legacy preparation must not be used")
        override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation = throw AssertionError("No legacy send")
        override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation = throw AssertionError("No inspection")
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
