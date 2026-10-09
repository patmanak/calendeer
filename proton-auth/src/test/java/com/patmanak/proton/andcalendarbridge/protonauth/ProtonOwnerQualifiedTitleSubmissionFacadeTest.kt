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
class ProtonOwnerQualifiedTitleSubmissionFacadeTest {
    private fun verified(f: TitleSubmissionFixture) =
        TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(f.record), checkNotNull(f.verified))

    @Test fun runtimeReceivesOnlyOwnerQualifiedOverloadWithBothScopeBoundedCallbacks() = runTest {
        val f = TitleSubmissionFixture(); var calls = 0; val sourceScopes = mutableListOf<CalendarScope>()
        val runtime = object : SubmissionPort() {
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                calls++; assertEquals(f.record, record); assertSame(f.journal, journal)
                for (foreign in listOf(f.scope.copy(generation = f.scope.generation + 1),
                    f.scope.copy(account = AccountId("synthetic-other-account")),
                    f.scope.copy(calendar = CalendarId("synthetic-other-calendar")))) {
                    assertFalse(admit(foreign)); assertFalse(qualifySource(foreign))
                }
                assertTrue("Foreign scope probes must not close the correct scope", admit(f.scope))
                assertTrue(qualifySource(f.scope))
                return verified(f)
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        val result = port.submit(f.record, f.journal, { true }) { yield(); sourceScopes += it; true }
            as TitleUpdateSubmissionObservation.Verified
        assertEquals(TitleUpdateAttemptIdentity.of(f.record), result.attempt); assertEquals(f.verified, result.event)
        assertEquals(1, calls); assertTrue(sourceScopes.isNotEmpty()); assertTrue(sourceScopes.all { it == f.scope })
    }

    @Test fun missingOwnerQualifiedCapabilityNeverFallsBackToThreeArgumentOrLegacySubmission() = runTest {
        val f = TitleSubmissionFixture()
        for (runtime in listOf(SubmissionPort(), null)) {
            val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
            assertEquals(TitleUpdateSubmissionObservation.Unknown, port.submit(f.record, f.journal, { true }) { true })
        }
    }

    @Test fun deniedSourceAndUnauthenticatedFacadeCannotInvokeRuntime() = runTest {
        val f = TitleSubmissionFixture(); var calls = 0; var builds = 0
        val runtime = object : SubmissionPort() {
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                calls++; return verified(f)
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        assertEquals(TitleUpdateSubmissionObservation.Unknown, port.submit(f.record, f.journal, { true }) { false })
        assertEquals(0, calls)
        val unopened = SerializedAuthenticationPort({ builds++; error("No graph") }, {}, StandardTestDispatcher(testScheduler))
        assertEquals(TitleUpdateSubmissionObservation.Unknown, unopened.submit(f.record, f.journal, { true }) { true })
        assertEquals(0, builds)
    }

    @Test fun sourceRevocationWhileWaitingForMutexPreventsRuntime() = runTest {
        val f = TitleSubmissionFixture(); var current = true; var calls = 0
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val runtime = object : SubmissionPort() {
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                calls++; return verified(f)
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)) { entered.complete(Unit); release.await() }
        port.restore(); val busy = async { port.load() }; entered.await()
        val task = async { port.submit(f.record, f.journal, { true }) { current } }
        runCurrent(); current = false; release.complete(Unit); busy.await()
        assertEquals(TitleUpdateSubmissionObservation.Unknown, task.await()); assertEquals(0, calls)
    }

    @Test fun sourceRevocationIsMonotoneEvenWhenCallerLaterReturnsTrue() = runTest {
        val f = TitleSubmissionFixture(); var current = true
        val runtime = object : SubmissionPort() {
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                assertTrue(qualifySource(f.scope))
                current = false; assertFalse(qualifySource(f.scope))
                current = true; assertFalse("The source lease cannot reopen", qualifySource(f.scope))
                assertFalse("Source revocation must close immediate admission", admit(f.scope))
                return verified(f)
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        assertEquals(TitleUpdateSubmissionObservation.Unknown, port.submit(f.record, f.journal, { true }) { current })
    }

    @Test fun sourceOrFormatChangeDuringRuntimeSuppressesVerifiedReturn() = runTest {
        for (formatChange in listOf(false, true)) {
            val f = TitleSubmissionFixture(); var current = true
            val runtime = object : SubmissionPort() {
                override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                    admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                    if (formatChange) titleUpdateFormat = TITLE_UPDATE_SUBMISSION_FORMAT.copy(version = 2)
                    else current = false
                    return verified(f)
                }
            }
            val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
            assertEquals(TitleUpdateSubmissionObservation.Unknown, port.submit(f.record, f.journal, { true }) { current })
        }
    }

    @Test fun recordIsFrozenBeforeMutexAndBeforeSuspendingSource() = runTest {
        val f = TitleSubmissionFixture(); val reminders = mutableListOf<EventReminder>(); var calls = 0
        val incoming = f.record.copy(preparation = f.preparation.copy(intent = f.intent.copy(
            content = f.intent.content.copy(details = EventDetails(reminders = reminders)))))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val runtime = object : SubmissionPort() {
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                calls++; assertEquals(f.record, record); return verified(f)
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        var held = false
        val task = async(start = CoroutineStart.UNDISPATCHED) { port.submit(incoming, f.journal, { true }) {
            if (!held) { held = true; entered.complete(Unit); release.await() }; true
        } }
        runCurrent(); assertTrue("Suspending source was not reached", entered.isCompleted)
        reminders += EventReminder(10); release.complete(Unit)
        assertTrue(task.await() is TitleUpdateSubmissionObservation.Verified); assertEquals(1, calls)
    }

    @Test fun wrongFullIdentityOrUntouchedContentCannotVerifyThroughNewOverload() = runTest {
        val f = TitleSubmissionFixture(); val identity = TitleUpdateAttemptIdentity.of(f.record)
        val invalid = listOf(
            TitleUpdateSubmissionObservation.Verified(identity.copy(payloadFingerprint = "2".repeat(64)), checkNotNull(f.verified)),
            TitleUpdateSubmissionObservation.Verified(identity.copy(preparationFingerprint = "1".repeat(64)), checkNotNull(f.verified)),
            TitleUpdateSubmissionObservation.Verified(identity.copy(scope = f.scope.copy(generation = 2)), checkNotNull(f.verified)),
            TitleUpdateSubmissionObservation.Verified(identity, checkNotNull(f.verified).copy(content = f.intent.content.copy(description = "Other description"))),
        )
        for (result in invalid) {
            val runtime = object : SubmissionPort() {
                override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                    admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean) = result
            }
            val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
            assertEquals(TitleUpdateSubmissionObservation.Unknown, port.submit(f.record, f.journal, { true }) { true })
        }
    }

    @Test fun sourceCancellationReturningFalseOrThrowingNeverCompletesNormally() = runTest {
        for (throwAfterCancel in listOf(false, true)) {
            val f = TitleSubmissionFixture(); var returned = false
            val port = facade(SubmissionPort(), StandardTestDispatcher(testScheduler)); port.restore()
            supervisorScope {
                val task = async {
                    port.submit(f.record, f.journal, { true }) {
                        currentCoroutineContext().cancel(CancellationException("Synthetic cancellation"))
                        if (throwAfterCancel) throw IllegalStateException("Synthetic failure")
                        false
                    }
                    returned = true
                }
                try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            }
            assertFalse(returned)
        }
    }

    @Test fun runtimeReturningUnknownAfterCancellationNeverCompletesNormally() = runTest {
        val f = TitleSubmissionFixture(); var returned = false; var called = false
        val runtime = object : SubmissionPort() {
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                called = true; currentCoroutineContext().cancel(CancellationException("Synthetic cancellation"))
                return TitleUpdateSubmissionObservation.Unknown
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        supervisorScope {
            val task = async { port.submit(f.record, f.journal, { true }) { true }; returned = true }
            try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        }
        assertTrue(called); assertFalse(returned)
    }
    @Test fun cancelledSourceRecheckAfterRuntimeUnknownCannotCompleteNormally() = runTest {
        val f = TitleSubmissionFixture(); var runtimeReturned = false; var sourceRechecked = false; var returned = false
        val runtime = object : SubmissionPort() {
            override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
                admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                runtimeReturned = true
                return TitleUpdateSubmissionObservation.Unknown
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        supervisorScope {
            val task = async {
                port.submit(f.record, f.journal, { true }) {
                    if (runtimeReturned) {
                        sourceRechecked = true
                        currentCoroutineContext().cancel(CancellationException("Synthetic cancellation"))
                        false
                    } else true
                }
                returned = true
            }
            try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        }
        assertTrue(runtimeReturned); assertTrue(sourceRechecked); assertFalse(returned)
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
        override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
            admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation = throw AssertionError("No three-argument fallback")
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
