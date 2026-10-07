// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.auth.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogResult
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test

/** Synthetic records qualify admission and suspension control flow, not Proton cryptography. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtonGuardedTitleInspectionTest {
    private val stages = listOf("user", "addresses", "metadata", "context-bootstrap", "local-addresses")
    private fun fixture() = TitleSubmissionFixture().also {
        it.contextStages = stages
        it.protectedOpens = 0
    }
    private fun TitleSubmissionFixture.unknown() = record.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)

    @Test fun admittedUnknownUsesReadbackWithoutPreparationClaimSendOrEvidenceWrites() = runTest {
        val f = fixture()
        val scopes = mutableListOf<CalendarScope>()
        assertTrue(f.adapter.inspect(f.unknown()) { scopes += it; it == f.scope } is TitleUpdateSubmissionObservation.Verified)
        assertTrue(scopes.isNotEmpty()); assertTrue(scopes.all { it == f.scope })
        assertEquals(listOf("context") + stages + listOf("get", "bootstrap", "verify"), f.steps)
        assertEquals(0, f.sends); assertEquals(0, f.prepares)
        assertEquals(f.record, f.journal.stored)
    }

    @Test fun deniedAndThrowingAdmissionNeverOpenProtectedFragmentsOrReadSession() = runTest {
        for (throwing in listOf(false, true)) {
            val f = fixture()
            assertEquals(TitleUpdateSubmissionObservation.Unknown, f.adapter.inspect(f.unknown()) {
                if (throwing) error("synthetic revoked lease") else false
            })
            assertEquals(0, f.protectedOpens); assertEquals(0, f.sessionReads); assertTrue(f.steps.isEmpty())
        }
    }

    @Test fun preparedGuardedInspectionDoesNotOpenOrRead() = runTest {
        val f = fixture()
        assertEquals(TitleUpdateSubmissionObservation.Unknown, f.adapter.inspect(f.record) { true })
        assertEquals(0, f.protectedOpens); assertEquals(0, f.sessionReads); assertTrue(f.steps.isEmpty())
    }

    @Test fun revocationDuringSessionReadIsRecheckedBeforeProtectedPayloadOpening() = runTest {
        val f = fixture(); var admitted = true
        f.afterSession = { admitted = false }
        assertEquals(TitleUpdateSubmissionObservation.Unknown, f.adapter.inspect(f.unknown()) { admitted })
        assertEquals(1, f.sessionReads); assertEquals(0, f.protectedOpens); assertTrue(f.steps.isEmpty())
    }

    @Test fun everyContextReadAndReadbackBoundaryStopsBeforeItsSuccessorOnRevocation() = runTest {
        val all = listOf("context") + stages + listOf("get", "bootstrap", "verify")
        for (boundary in all) {
            val f = fixture(); var admitted = true
            f.after = { if (it == boundary) admitted = false }
            assertEquals(boundary, TitleUpdateSubmissionObservation.Unknown, f.adapter.inspect(f.unknown()) { admitted })
            assertEquals(boundary, all.take(all.indexOf(boundary) + 1), f.steps)
            assertEquals(0, f.sends); assertEquals(0, f.prepares); assertEquals(f.record, f.journal.stored)
        }
    }

    @Test fun disableThenReenableDoesNotRestoreTheOriginalCallerEpoch() = runTest {
        val f = fixture(); var revision = 0L; val expected = revision
        f.after = { if (it == "addresses") { revision++; revision++ } }
        assertEquals(TitleUpdateSubmissionObservation.Unknown, f.adapter.inspect(f.unknown()) { revision == expected })
        assertEquals(listOf("context", "user", "addresses"), f.steps)
    }

    @Test fun sessionReplacementAtEveryContextAndReadbackBoundaryStopsBeforeItsSuccessor() = runTest {
        val all = listOf("context") + stages + listOf("get", "bootstrap", "verify")
        for (boundary in all) {
            val f = fixture()
            f.after = { if (it == boundary) f.current = f.initial.copy(sessionId = SessionId("synthetic-replacement")) }
            assertEquals(boundary, TitleUpdateSubmissionObservation.Unknown, f.adapter.inspect(f.unknown()) { true })
            assertEquals(boundary, all.take(all.indexOf(boundary) + 1), f.steps)
        }
    }

    @Test fun cancellationAtContextAndReadbackBoundariesPropagatesWithoutLaterReads() = runTest {
        val all = listOf("context") + stages + listOf("get", "bootstrap", "verify")
        for (boundary in all) {
            val f = fixture(); f.after = { if (it == boundary) throw CancellationException("synthetic cancellation") }
            try { f.adapter.inspect(f.unknown()) { true }; fail("Cancellation must propagate") }
            catch (_: CancellationException) { }
            assertEquals(boundary, all.take(all.indexOf(boundary) + 1), f.steps)
            assertEquals(0, f.sends)
        }
    }

    @Test fun cancellationWithoutAThrowingTransportStopsAfterGetBeforeBootstrap() = runTest {
        val f = fixture()
        val request = async(start = CoroutineStart.LAZY) { f.adapter.inspect(f.unknown()) { true } }
        f.after = { if (it == "get") request.cancel() }
        request.start()
        try { request.await(); fail("Cancelled inspection must not return verification") }
        catch (_: CancellationException) { }
        assertEquals(listOf("context") + stages + "get", f.steps)
        assertEquals(0, f.sends)
    }

    @Test fun cancellationAfterVerifierReturnsNullStillPropagatesFromAdapter() = runTest {
        val f = fixture(); f.verified = null; var swallowed = false
        val request = async(start = CoroutineStart.LAZY) {
            f.adapter.inspect(f.unknown()) { true }
            swallowed = true
        }
        f.after = { if (it == "verify") request.cancel() }
        request.start()
        try { request.await(); fail("Cancellation must propagate even when verification is absent") }
        catch (_: CancellationException) { }
        assertFalse("The adapter must not return Unknown while cancelled", swallowed)
        assertEquals(listOf("context") + stages + listOf("get", "bootstrap", "verify"), f.steps)
    }

    @Test fun cancelledTransportThatThrowsNonCancellationFailureCannotBecomeUnknown() = runTest {
        val f = fixture(); var swallowed = false
        val request = async(start = CoroutineStart.LAZY) {
            f.adapter.inspect(f.unknown()) { true }
            swallowed = true
        }
        f.after = { if (it == "get") { request.cancel(); throw java.io.IOException("synthetic read failure") } }
        request.start()
        try { request.await(); fail("A cancelled job must propagate cancellation") }
        catch (_: CancellationException) { }
        assertFalse("A transport failure must not hide cancellation", swallowed)
        assertEquals(listOf("context") + stages + "get", f.steps)
    }

    @Test fun facadeForwardsOnlyGuardedInspectionAndOriginalScopeCallback() = runTest {
        val f = fixture(); var calls = 0
        val result = TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(f.unknown()), checkNotNull(f.verified))
        val runtime = object : InspectionPort() {
            override suspend fun inspect(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                calls++; assertEquals(f.unknown(), record); assertTrue(admit(f.scope)); assertFalse(admit(f.scope.copy(generation = 2)))
                return result
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler))
        port.restore()
        assertEquals(result, port.inspect(f.unknown()) { it == f.scope })
        assertEquals(1, calls)
    }

    @Test fun facadeCannotFallbackToLegacyWhenRuntimeHasNoGuardedCapability() = runTest {
        val f = fixture(); val port = facade(InspectionPort(), StandardTestDispatcher(testScheduler))
        port.restore()
        assertEquals(TitleUpdateSubmissionObservation.Unknown, port.inspect(f.unknown()) { true })
    }

    @Test fun facadeDeniedBeforeAuthenticationCannotBuildOrCallRuntime() = runTest {
        val f = fixture(); var builds = 0
        val port = SerializedAuthenticationPort({ builds++; error("No runtime may be built") }, {}, StandardTestDispatcher(testScheduler))
        assertEquals(TitleUpdateSubmissionObservation.Unknown, port.inspect(f.unknown()) { false })
        assertEquals(0, builds)
    }

    @Test fun facadeRevocationWhileWaitingForMutexStopsBeforeRuntime() = runTest {
        val f = fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        val runtime = object : InspectionPort() {
            override suspend fun inspect(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                calls++; return TitleUpdateSubmissionObservation.Unknown
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)) { entered.complete(Unit); release.await() }
        port.restore()
        val busy = async { port.load() }; entered.await(); var admitted = true
        val inspection = async { port.inspect(f.unknown()) { admitted } }
        runCurrent(); admitted = false; release.complete(Unit); busy.await()
        assertEquals(TitleUpdateSubmissionObservation.Unknown, inspection.await()); assertEquals(0, calls)
    }

    @Test fun facadeRevocationOnRuntimeReturnDiscardsVerification() = runTest {
        val f = fixture(); var admitted = true; var calls = 0
        val runtime = object : InspectionPort() {
            override suspend fun inspect(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                calls++; admitted = false
                return TitleUpdateSubmissionObservation.Verified(TitleUpdateAttemptIdentity.of(record), checkNotNull(f.verified))
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        assertEquals(TitleUpdateSubmissionObservation.Unknown, port.inspect(f.unknown()) { admitted }); assertEquals(1, calls)
    }

    @Test fun facadeFreezesAttemptBeforeDispatcherAndSharedMutexSuspension() = runTest {
        val f = fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val reminders = mutableListOf<EventReminder>()
        val intent = f.intent.copy(content = f.intent.content.copy(details = EventDetails(reminders = reminders)))
        val record = f.unknown().copy(preparation = f.preparation.copy(intent = intent))
        var calls = 0
        val runtime = object : InspectionPort() {
            override suspend fun inspect(record: TitleUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): TitleUpdateSubmissionObservation {
                calls++; assertTrue(checkNotNull(record.preparation.intent.content.details).reminders.isEmpty())
                return TitleUpdateSubmissionObservation.Unknown
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)) { entered.complete(Unit); release.await() }
        port.restore(); val busy = async { port.load() }; entered.await()
        val inspection = async(start = CoroutineStart.UNDISPATCHED) { port.inspect(record) { true } }
        reminders += EventReminder(10); release.complete(Unit); busy.await(); inspection.await(); assertEquals(1, calls)
    }

    private fun facade(runtime: CalendarTitleUpdateSubmissionPort, dispatcher: CoroutineDispatcher,
        holdCatalog: suspend () -> Unit = {}): SerializedAuthenticationPort = SerializedAuthenticationPort({
        ProtonAuthenticationGraph(object : AuthenticationFlowPort {
            override suspend fun restore() = AuthenticationStep.Authenticated
            override suspend fun signIn(username: AuthenticationSecret, password: AuthenticationSecret) = error("No sign in")
            override suspend fun submitCode(code: AuthenticationSecret) = error("No code")
            override suspend fun unlock(password: AuthenticationSecret) = error("No unlock")
            override suspend fun cancel() = Unit
        }, NoEvents, titleUpdates = runtime) { holdCatalog(); CalendarCatalogResult.Success(AccountId("synthetic-account"), emptyList()) }
    }, {}, dispatcher)

    private open class InspectionPort : CalendarTitleUpdateSubmissionPort {
        override val titleUpdateFormat = TITLE_UPDATE_SUBMISSION_FORMAT
        override suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord = error("No prepare")
        override suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation = error("No send")
        override suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation = error("Legacy inspection must not be used")
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
