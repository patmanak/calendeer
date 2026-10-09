// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.auth.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogResult
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Production facade, synthetic runtime; no service traffic, crypto claim or mutable account. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtonGuardedLocalTitlePreparationFacadeTest {
    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 0)
    private val timestamp = Instant.parse("2030-01-01T12:00:00Z")
    private val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("original"), EventContent("Original",
        EventTime.AllDay(LocalDate.of(2030, 1, 2), LocalDate.of(2030, 1, 3)), details = EventDetails()))
    private val intent = LocalEditIntent(MutationId("synthetic-intent"), scope, EVENT_FORMAT, base, base.content.copy(title = "Changed"))
    private val result = LocalEditPreparation(intent, timestamp, LOCAL_TITLE_PREPARATION_FORMAT, "synthetic-protected-capsule")

    private open class Runtime : CalendarLocalEditPreparationPort {
        override var localTitleFormat: AdapterFormat? = LOCAL_TITLE_PREPARATION_FORMAT
        override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant): LocalEditPreparation =
            error("Never use unrestricted preparation")
    }

    @Test fun forwardsOnlyTheGuardedRuntimeWithExactScopeAndClosesItsLeaseOnReturn() = runTest {
        var calls = 0
        var retainedLease: ((CalendarScope) -> Boolean)? = null
        val runtime = object : Runtime() {
            override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant,
                admit: (CalendarScope) -> Boolean): LocalEditPreparation {
                calls++; assertEquals(this@ProtonGuardedLocalTitlePreparationFacadeTest.intent, intent)
                assertEquals(this@ProtonGuardedLocalTitlePreparationFacadeTest.timestamp, timestamp)
                assertTrue(admit(scope)); assertFalse(admit(scope.copy(generation = 1)))
                assertFalse(admit(scope.copy(account = AccountId("foreign"))))
                assertTrue(admit(scope)); retainedLease = admit
                return result
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        assertEquals(result, port.prepareLocalTitle(intent, timestamp) { true })
        assertEquals(1, calls); assertFalse(checkNotNull(retainedLease)(scope))
    }

    @Test fun deniedAndUnauthenticatedRequestsNeverBuildOrEnterRuntime() = runTest {
        var builds = 0
        val unopened = SerializedAuthenticationPort({ builds++; error("No graph construction") }, {}, StandardTestDispatcher(testScheduler))
        assertNull(unopened.prepareLocalTitle(intent, timestamp) { true })
        assertNull(unopened.prepareLocalTitle(intent, timestamp) { false }); assertEquals(0, builds)
        var calls = 0
        val runtime = object : Runtime() {
            override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant,
                admit: (CalendarScope) -> Boolean): LocalEditPreparation { calls++; return result }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        assertNull(port.prepareLocalTitle(intent, timestamp) { false }); assertEquals(0, calls)
    }

    @Test fun unsupportedGuardedRuntimeAndWrongDeclaredFormatNeverFallBack() = runTest {
        for (format in listOf(null, AdapterFormat("other", 1), LOCAL_TITLE_PREPARATION_FORMAT)) {
            val runtime = Runtime().apply { localTitleFormat = format }
            val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
            assertNull(port.prepareLocalTitle(intent, timestamp) { true })
        }
    }

    @Test fun revocationWhileWaitingForSharedAuthenticationMutexPreventsRuntimeEntry() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0; var allowed = true
        val runtime = object : Runtime() {
            override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant,
                admit: (CalendarScope) -> Boolean): LocalEditPreparation { calls++; return result }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)) { entered.complete(Unit); release.await() }
        port.restore(); val busy = async { port.load() }; entered.await()
        val work = async { port.prepareLocalTitle(intent, timestamp) { allowed } }
        runCurrent(); allowed = false; release.complete(Unit); busy.await()
        assertNull(work.await()); assertEquals(0, calls)
    }

    @Test fun inputIsFrozenBeforeDispatcherAndMutexSuspension() = runTest {
        val reminders = mutableListOf<EventReminder>()
        val candidate = intent.copy(content = intent.content.copy(details = EventDetails(reminders = reminders)))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var calls = 0
        val runtime = object : Runtime() {
            override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant,
                admit: (CalendarScope) -> Boolean): LocalEditPreparation {
                calls++; assertEquals(this@ProtonGuardedLocalTitlePreparationFacadeTest.intent, intent)
                return result
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)) { entered.complete(Unit); release.await() }
        port.restore(); val busy = async { port.load() }; entered.await()
        val work = async(start = CoroutineStart.UNDISPATCHED) { port.prepareLocalTitle(candidate, timestamp) { true } }
        reminders += EventReminder(10); release.complete(Unit); busy.await()
        assertEquals(result, work.await()); assertEquals(1, calls)
    }

    @Test fun revocationDuringRuntimeAwaitDiscardsTheReturnedCapsule() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var allowed = true
        val runtime = object : Runtime() {
            override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant,
                admit: (CalendarScope) -> Boolean): LocalEditPreparation {
                entered.complete(Unit); release.await(); return result
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        val work = async { port.prepareLocalTitle(intent, timestamp) { allowed } }
        runCurrent(); assertTrue("Guarded runtime was not reached", entered.isCompleted)
        allowed = false; release.complete(Unit); assertNull(work.await())
    }

    @Test fun observedRevocationCannotReopenWithinTheSameRuntimeCall() = runTest {
        var allowed = true; var calls = 0
        val runtime = object : Runtime() {
            override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant,
                admit: (CalendarScope) -> Boolean): LocalEditPreparation {
                calls++; assertTrue(admit(scope)); allowed = false; assertFalse(admit(scope))
                allowed = true; assertFalse(admit(scope)); return result
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        assertNull(port.prepareLocalTitle(intent, timestamp) { allowed }); assertEquals(1, calls)
    }

    @Test fun changedRuntimeFormatAndSubstitutedCapsuleCannotEscapeTheFacade() = runTest {
        val substitutions = listOf(result.copy(intent = intent.copy(id = MutationId("other"))),
            result.copy(timestamp = timestamp.plusSeconds(1)), result.copy(format = AdapterFormat("other", 1)))
        for (substitute in substitutions) {
            var calls = 0
            val runtime = object : Runtime() {
                override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant,
                    admit: (CalendarScope) -> Boolean): LocalEditPreparation { calls++; return substitute }
            }
            val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
            assertNull(port.prepareLocalTitle(intent, timestamp) { true }); assertEquals(1, calls)
        }
        val runtime = object : Runtime() {
            override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant,
                admit: (CalendarScope) -> Boolean): LocalEditPreparation {
                localTitleFormat = AdapterFormat("other", 1); return result
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        assertNull(port.prepareLocalTitle(intent, timestamp) { true })
    }

    @Test fun typedRuntimeFailuresPropagateAndCloseTheCapturedLease() = runTest {
        for (reason in LocalEditPreparationFailure.entries) {
            val expected = LocalEditPreparationException(reason)
            var retainedLease: ((CalendarScope) -> Boolean)? = null
            val runtime = object : Runtime() {
                override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant,
                    admit: (CalendarScope) -> Boolean): LocalEditPreparation {
                    retainedLease = admit; throw expected
                }
            }
            val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
            try { port.prepareLocalTitle(intent, timestamp) { true }; fail("Typed failure must propagate") }
            catch (actual: LocalEditPreparationException) { assertSame(expected, actual) }
            assertFalse(checkNotNull(retainedLease)(scope))
        }
    }

    @Test fun runtimeCancellingThenReturningNullCannotReturnNormally() = runTest {
        var calls = 0; var normal = false
        val runtime = object : Runtime() {
            override suspend fun prepareLocalTitle(intent: LocalEditIntent, timestamp: Instant,
                admit: (CalendarScope) -> Boolean): LocalEditPreparation? {
                calls++; currentCoroutineContext().cancel(); return null
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler)); port.restore()
        supervisorScope {
            val task = async { port.prepareLocalTitle(intent, timestamp) { true }; normal = true }
            try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        }
        assertEquals(1, calls); assertFalse(normal)
    }

    @Test fun cancelledAdmissionReturningFalseCannotReturnNormally() = runTest {
        val port = facade(Runtime(), StandardTestDispatcher(testScheduler)); port.restore()
        var normal = false
        supervisorScope {
            val task = async {
                val context = currentCoroutineContext()
                port.prepareLocalTitle(intent, timestamp) { context.cancel(); false }
                normal = true
            }
            try { task.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        }
        assertFalse(normal)
    }

    private fun facade(runtime: CalendarLocalEditPreparationPort, dispatcher: CoroutineDispatcher,
        holdCatalog: suspend () -> Unit = {}) = SerializedAuthenticationPort({
        ProtonAuthenticationGraph(object : AuthenticationFlowPort {
            override suspend fun restore() = AuthenticationStep.Authenticated
            override suspend fun signIn(username: AuthenticationSecret, password: AuthenticationSecret) = error("No sign in")
            override suspend fun submitCode(code: AuthenticationSecret) = error("No code")
            override suspend fun unlock(password: AuthenticationSecret) = error("No unlock")
            override suspend fun cancel() = Unit
        }, NoEvents, localEdits = runtime) { holdCatalog(); CalendarCatalogResult.Success(scope.account, emptyList()) }
    }, {}, dispatcher)

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
