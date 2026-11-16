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

/** Real serialization facade with synthetic ports; no network, crypto or account qualification. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtonGuardedPersonalPreparationFacadeTest {
    @Test fun explicitSeriesEntryKeepsItsProfileAndNeverEntersSimplePreparation() = runTest {
        var calls = 0
        var retainedLease: ((CalendarScope) -> Boolean)? = null
        val old = base.content.copy(recurrence = EventRecurrence("series-fixture", "FREQ=DAILY;COUNT=3"))
        val next = intent.content.copy(recurrence = EventRecurrence("series-fixture", "FREQ=DAILY;COUNT=5"))
        val recurring = intent.copy(base = base.copy(content = old), content = next, seriesProfile = SeriesEditProfile.of(old, next))
        val runtime = object : Runtime() {
            override val seriesFormat = PERSONAL_SERIES_PREPARATION_FORMAT
            override suspend fun prepareSeries(intent: LocalEditIntent, timestamp: Instant,
                admit: (CalendarScope) -> Boolean): PersonalEditPreparation? {
                calls++; retainedLease = admit; assertTrue(admit(scope))
                return PersonalEditPreparation.forSeries(intent, timestamp, seriesFormat, "synthetic-series")
            }
        }
        val port = facade(runtime, StandardTestDispatcher(testScheduler))
        port.restore()
        val result = port.prepareSeries(recurring, timestamp) { true }
        assertNotNull(result); assertTrue(result!!.isSeries); assertEquals(recurring, result.intent)
        assertEquals(1, calls); assertFalse(retainedLease!!(scope))
        failure(LocalEditPreparationFailure.UNSUPPORTED) { port.preparePersonal(recurring, timestamp) { true } }
        assertNull(port.prepareSeries(recurring, timestamp) { false }); assertEquals(1, calls)
    }

    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 2)
    private val timestamp = Instant.parse("2030-01-01T12:00:00Z")
    private val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-revision"),
        EventContent("Original", EventTime.AllDay(LocalDate.of(2030, 1, 2), LocalDate.of(2030, 1, 4)),
            "Notes", "", details = EventDetails()))
    private val intent = LocalEditIntent(MutationId("synthetic-edit"), scope, EVENT_FORMAT, base,
        base.content.copy(time = (base.content.time as EventTime.AllDay).copy(endExclusive = LocalDate.of(2030, 1, 5))))
    private fun result(edit: LocalEditIntent = intent, time: Instant = timestamp,
        format: AdapterFormat = PERSONAL_PREPARATION_FORMAT) =
        PersonalEditPreparation(edit, time, format, "synthetic-protected")

    private open class Runtime : CalendarPersonalPreparationPort {
        override var personalFormat: AdapterFormat? = PERSONAL_PREPARATION_FORMAT
        override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: Instant): PersonalEditPreparation =
            error("Unexpected unrestricted preparation")
    }
    private fun runtime(action: suspend (LocalEditIntent, Instant, ((CalendarScope) -> Boolean)?) -> PersonalEditPreparation?) =
        object : Runtime() {
            override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: Instant) =
                checkNotNull(action(intent, timestamp, null))
            override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: Instant,
                admit: (CalendarScope) -> Boolean) = action(intent, timestamp, admit)
        }
    private suspend fun failure(reason: LocalEditPreparationFailure, action: suspend () -> Unit) {
        try { action(); fail("Expected sanitized preparation failure") }
        catch (actual: LocalEditPreparationException) { assertEquals(reason, actual.reason); assertNull(actual.cause) }
    }
    private fun same(expected: PersonalEditPreparation, actual: PersonalEditPreparation?) {
        assertNotNull(actual)
        assertEquals(expected.intent, actual!!.intent); assertEquals(expected.timestamp, actual.timestamp)
        assertEquals(expected.format, actual.format); assertEquals(expected.protectedPayload, actual.protectedPayload)
    }

    @Test fun bothOverloadsUseOnlyTheIndependentTimePortAndReturnExactFrozenRecords() = runTest {
        val calls = mutableListOf<Boolean>()
        val port = facade(runtime { edit, time, lease ->
            calls += lease != null; assertEquals(intent, edit); assertEquals(timestamp, time)
            assertNotSame(intent, edit); lease?.let { assertTrue(it(scope)) }; result(edit, time)
        }, StandardTestDispatcher(testScheduler))
        port.restore()
        same(result(), port.preparePersonal(intent, timestamp))
        same(result(), port.preparePersonal(intent, timestamp) { true })
        assertEquals(listOf(false, true), calls)
        // Its separate format does not widen the historical title entry point.
        failure(LocalEditPreparationFailure.UNSUPPORTED) { port.prepareLocalTitle(intent, timestamp) }
    }

    @Test fun guardedLeaseAcceptsExactScopeAndExpiresAfterReturn() = runTest {
        var captured: ((CalendarScope) -> Boolean)? = null
        val port = facade(runtime { _, _, lease ->
            val active = checkNotNull(lease); captured = active
            assertTrue(active(scope))
            assertFalse(active(scope.copy(generation = 3)))
            assertFalse(active(scope.copy(account = AccountId("foreign"))))
            assertFalse(active(scope.copy(calendar = CalendarId("foreign"))))
            assertTrue(active(scope)); result()
        }, StandardTestDispatcher(testScheduler))
        port.restore(); same(result(), port.preparePersonal(intent, timestamp) { true })
        assertFalse(checkNotNull(captured)(scope))
    }

    @Test fun unauthenticatedAndDeniedRequestsDoNotConstructOrEnterRuntime() = runTest {
        var builds = 0
        val unopened = SerializedAuthenticationPort({ builds++; error("No graph construction") }, {},
            StandardTestDispatcher(testScheduler))
        assertNull(unopened.preparePersonal(intent, timestamp) { true })
        failure(LocalEditPreparationFailure.AUTHENTICATION) { unopened.preparePersonal(intent, timestamp) }
        assertEquals(0, builds)
        var calls = 0
        val port = facade(runtime { _, _, _ -> calls++; result() }, StandardTestDispatcher(testScheduler))
        port.restore()
        assertNull(port.preparePersonal(intent, timestamp) { false }); assertEquals(0, calls)
    }

    @Test fun guardedDefaultAndMissingOrWrongFormatsNeverFallBackToUnrestricted() = runTest {
        for (format in listOf(null, AdapterFormat("foreign", 1), PERSONAL_PREPARATION_FORMAT)) {
            val port = facade(Runtime().apply { personalFormat = format }, StandardTestDispatcher(testScheduler))
            port.restore(); assertNull(port.preparePersonal(intent, timestamp) { true })
        }
        for (format in listOf(null, AdapterFormat("foreign", 1))) {
            val port = facade(Runtime().apply { personalFormat = format }, StandardTestDispatcher(testScheduler))
            port.restore()
            failure(LocalEditPreparationFailure.UNSUPPORTED) { port.preparePersonal(intent, timestamp) }
        }
    }

    @Test fun bothOverloadsFreezeBeforeDispatcherAndMutexWait() = runTest {
        for (guarded in listOf(false, true)) {
            val reminders = mutableListOf<EventReminder>()
            val details = EventDetails(reminders = reminders)
            val source = intent.copy(base = base.copy(content = base.content.copy(details = details)),
                content = intent.content.copy(details = details))
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var calls = 0
            val port = facade(runtime { edit, time, _ ->
                calls++; assertEquals(intent, edit); result(edit, time)
            }, StandardTestDispatcher(testScheduler), holdCatalog = { entered.complete(Unit); release.await() })
            port.restore(); val busy = async { port.load() }; entered.await()
            val task = async(start = CoroutineStart.UNDISPATCHED) {
                if (guarded) port.preparePersonal(source, timestamp) { true } else port.preparePersonal(source, timestamp)
            }
            reminders += EventReminder(10); release.complete(Unit); busy.await()
            same(result(), task.await()); assertEquals(1, calls)
        }
    }

    @Test fun revocationWhileQueuedOnAuthenticationMutexPreventsEntry() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var allowed = true; var calls = 0
        val port = facade(runtime { _, _, _ -> calls++; result() }, StandardTestDispatcher(testScheduler),
            holdCatalog = { entered.complete(Unit); release.await() })
        port.restore(); val busy = async { port.load() }; entered.await()
        val work = async { port.preparePersonal(intent, timestamp) { allowed } }
        runCurrent(); allowed = false; release.complete(Unit); busy.await()
        assertNull(work.await()); assertEquals(0, calls)
    }

    @Test fun revocationDuringRuntimeAwaitDiscardsItsResult() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var allowed = true
        val port = facade(runtime { _, _, _ -> entered.complete(Unit); release.await(); result() },
            StandardTestDispatcher(testScheduler))
        port.restore()
        val work = async { port.preparePersonal(intent, timestamp) { allowed } }
        runCurrent(); assertTrue("Runtime must be reached", entered.isCompleted)
        allowed = false; release.complete(Unit); assertNull(work.await())
    }

    @Test fun observedDenialNeverReopensEvenWhenCallerOrRuntimeFormatRecovers() = runTest {
        var allowed = true
        var captured: ((CalendarScope) -> Boolean)? = null
        val target = runtime { _, _, lease ->
            val active = checkNotNull(lease); captured = active
            assertTrue(active(scope)); allowed = false; assertFalse(active(scope))
            allowed = true; assertFalse(active(scope)); result()
        }
        val port = facade(target, StandardTestDispatcher(testScheduler)); port.restore()
        assertNull(port.preparePersonal(intent, timestamp) { allowed })
        assertFalse(checkNotNull(captured)(scope))
        lateinit var changing: Runtime
        changing = runtime { _, _, lease ->
            val active = checkNotNull(lease)
            changing.personalFormat = AdapterFormat("foreign", 1); assertFalse(active(scope))
            changing.personalFormat = PERSONAL_PREPARATION_FORMAT; assertFalse(active(scope)); result()
        }
        val second = facade(changing, StandardTestDispatcher(testScheduler)); second.restore()
        assertNull(second.preparePersonal(intent, timestamp) { true })
    }

    @Test fun bothOverloadsRejectSubstitutedCompleteResults() = runTest {
        val changedBase = base.copy(content = base.content.copy(location = "Different original"))
        val substitutions = listOf(
            result(intent.copy(id = MutationId("other"))),
            result(intent.copy(scope = scope.copy(generation = 3))),
            result(intent.copy(base = changedBase, content = intent.content.copy(location = "Different original"))),
            result(intent.copy(content = intent.content.copy(time = (intent.content.time as EventTime.AllDay).copy(endExclusive = LocalDate.of(2030, 1, 6))))),
            result(time = timestamp.plusSeconds(1)), result(format = AdapterFormat("other", 1)))
        for (substitute in substitutions) {
            val port = facade(runtime { _, _, _ -> substitute }, StandardTestDispatcher(testScheduler)); port.restore()
            assertNull(port.preparePersonal(intent, timestamp) { true })
            failure(LocalEditPreparationFailure.CONTEXT) { port.preparePersonal(intent, timestamp) }
        }
    }

    @Test fun runtimeFormatChangesCannotEscapeEitherOverload() = runTest {
        for (guarded in listOf(false, true)) {
            lateinit var changing: Runtime
            changing = runtime { _, _, _ -> changing.personalFormat = AdapterFormat("other", 1); result() }
            val port = facade(changing, StandardTestDispatcher(testScheduler)); port.restore()
            if (guarded) assertNull(port.preparePersonal(intent, timestamp) { true })
            else failure(LocalEditPreparationFailure.CONTEXT) { port.preparePersonal(intent, timestamp) }
        }
    }

    @Test fun invalidInputIsRejectedBeforeDispatchOrCallingAdmission() = runTest {
        var admissions = 0; var calls = 0
        val port = facade(runtime { _, _, _ -> calls++; result() }, StandardTestDispatcher(testScheduler)); port.restore()
        val bad = listOf(intent.copy(state = LocalEditState.CONFLICT),
            intent.copy(format = EVENT_FORMAT.copy(version = EVENT_FORMAT.version + 1)),
            intent.copy(content = intent.content.copy(description = "\uD800")),
            intent.copy(content = intent.content.copy(title = "\u0000")))
        for (edit in bad) {
            failure(LocalEditPreparationFailure.UNSUPPORTED) { port.preparePersonal(edit, timestamp) }
            failure(LocalEditPreparationFailure.UNSUPPORTED) { port.preparePersonal(edit, timestamp) { admissions++; true } }
        }
        failure(LocalEditPreparationFailure.UNSUPPORTED) { port.preparePersonal(intent, timestamp.plusNanos(1)) }
        assertEquals(0, calls); assertEquals(0, admissions)
    }

    @Test fun typedFailuresPropagateUnchangedAndAlwaysCloseGuardedLeases() = runTest {
        for (reason in LocalEditPreparationFailure.entries) {
            val expected = LocalEditPreparationException(reason)
            var captured: ((CalendarScope) -> Boolean)? = null
            val port = facade(runtime { _, _, lease -> captured = lease; throw expected }, StandardTestDispatcher(testScheduler))
            port.restore()
            try { port.preparePersonal(intent, timestamp) { true }; fail("Typed failure must propagate") }
            catch (actual: LocalEditPreparationException) { assertSame(expected, actual) }
            assertFalse(checkNotNull(captured)(scope))
        }
    }

    @Test fun callbackAndRuntimeCancellationAreClassifiedInsideTheChild() = runTest {
        for (origin in listOf("ADMISSION", "GUARDED_RUNTIME", "UNGUARDED_RUNTIME")) for (ordinaryFailure in listOf(false, true)) {
            var captured: ((CalendarScope) -> Boolean)? = null
            var runtimeCalls = 0
            val port = facade(runtime { _, _, lease ->
                runtimeCalls++; captured = lease
                currentCoroutineContext().cancel()
                if (ordinaryFailure) throw java.io.IOException("Synthetic failure after cancellation")
                result()
            }, StandardTestDispatcher(testScheduler))
            port.restore()
            var returned = 0; var cancellations = 0; var failures = 0
            supervisorScope {
                val task = async {
                    val context = currentCoroutineContext()
                    try {
                        if (origin == "UNGUARDED_RUNTIME") port.preparePersonal(intent, timestamp)
                        else port.preparePersonal(intent, timestamp) {
                            if (origin == "ADMISSION") {
                                context.cancel()
                                if (ordinaryFailure) throw java.io.IOException("Synthetic admission failure")
                                false
                            } else true
                        }
                        returned++
                    } catch (_: CancellationException) { cancellations++ }
                      catch (_: Exception) { failures++ }
                }
                try { task.await() } catch (_: CancellationException) { }
            }
            assertEquals(1, cancellations); assertEquals(0, failures); assertEquals(0, returned)
            assertEquals(if (origin == "ADMISSION") 0 else 1, runtimeCalls)
            captured?.let { assertFalse(it(scope)) }
        }
    }

    @Test fun logoutWaitsForBothPreparationOverloadsAndClosesFurtherRequests() = runTest {
        for (guarded in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val auth = Auth()
            val port = facade(runtime { edit, time, _ -> entered.complete(Unit); release.await(); result(edit, time) },
                StandardTestDispatcher(testScheduler), auth)
            port.restore()
            val work = async {
                if (guarded) port.preparePersonal(intent, timestamp) { true } else port.preparePersonal(intent, timestamp)
            }
            runCurrent(); assertTrue("Runtime must be reached", entered.isCompleted)
            val logout = async { port.cancel() }; runCurrent(); assertEquals(0, auth.cancellations)
            release.complete(Unit); same(result(), work.await()); logout.await()
            assertEquals(1, auth.cancellations)
            assertNull(port.preparePersonal(intent, timestamp) { true })
            failure(LocalEditPreparationFailure.AUTHENTICATION) { port.preparePersonal(intent, timestamp) }
        }
    }

    @Test fun cancelledPreparationReleasesMutexAndFailedCleanupStillClosesAuthentication() = runTest {
        val entered = CompletableDeferred<Unit>(); val auth = Auth()
        val port = facade(runtime { _, _, _ -> entered.complete(Unit); awaitCancellation() },
            StandardTestDispatcher(testScheduler), auth)
        port.restore()
        val work = async { port.preparePersonal(intent, timestamp) { true } }
        runCurrent(); assertTrue("Runtime must be reached", entered.isCompleted)
        val logout = async { port.cancel() }; runCurrent(); assertEquals(0, auth.cancellations)
        work.cancelAndJoin(); logout.await(); assertEquals(1, auth.cancellations)
        assertNull(port.preparePersonal(intent, timestamp) { true })
        port.restore(); auth.failCleanup = true
        try { port.cancel(); fail("Cleanup failure must propagate") } catch (_: IllegalStateException) { }
        assertNull(port.preparePersonal(intent, timestamp) { true })
        failure(LocalEditPreparationFailure.AUTHENTICATION) { port.preparePersonal(intent, timestamp) }
    }

    private class Auth : AuthenticationFlowPort {
        var cancellations = 0; var failCleanup = false
        override suspend fun restore() = AuthenticationStep.Authenticated
        override suspend fun signIn(username: AuthenticationSecret, password: AuthenticationSecret) = error("No login")
        override suspend fun submitCode(code: AuthenticationSecret) = error("No code")
        override suspend fun unlock(password: AuthenticationSecret) = error("No unlock")
        override suspend fun cancel() { cancellations++; if (failCleanup) error("Synthetic cleanup failure") }
    }
    private fun facade(runtime: CalendarPersonalPreparationPort, dispatcher: CoroutineDispatcher,
        auth: Auth = Auth(), holdCatalog: suspend () -> Unit = {}) = SerializedAuthenticationPort({
        ProtonAuthenticationGraph(auth, NoEvents, personals = runtime,
            localEdits = CalendarLocalEditPreparationPort { _, _ -> error("Text must never enter title runtime") }) {
            holdCatalog(); CalendarCatalogResult.Success(scope.account, emptyList())
        }
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
