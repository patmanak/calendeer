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
class ProtonGuardedLocalTextPreparationFacadeTest {
    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 2)
    private val timestamp = Instant.parse("2030-01-01T12:00:00Z")
    private val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-revision"),
        EventContent("Original", EventTime.AllDay(LocalDate.of(2030, 1, 2), LocalDate.of(2030, 1, 4)),
            "Notes", "", details = EventDetails()))
    private val intent = LocalEditIntent(MutationId("synthetic-edit"), scope, EVENT_FORMAT, base,
        base.content.copy(title = "New title", description = "New notes", location = "New place"))
    private fun result(edit: LocalEditIntent = intent, time: Instant = timestamp,
        format: AdapterFormat = LOCAL_TEXT_PREPARATION_FORMAT) =
        LocalTextEditPreparation(edit, time, format, "synthetic-protected")

    private open class Runtime : CalendarLocalTextPreparationPort {
        override var localTextFormat: AdapterFormat? = LOCAL_TEXT_PREPARATION_FORMAT
        override suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: Instant): LocalTextEditPreparation =
            error("Unexpected unrestricted preparation")
    }
    private fun runtime(action: suspend (LocalEditIntent, Instant, ((CalendarScope) -> Boolean)?) -> LocalTextEditPreparation?) =
        object : Runtime() {
            override suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: Instant) =
                checkNotNull(action(intent, timestamp, null))
            override suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: Instant,
                admit: (CalendarScope) -> Boolean) = action(intent, timestamp, admit)
        }
    private suspend fun failure(reason: LocalEditPreparationFailure, action: suspend () -> Unit) {
        try { action(); fail("Expected sanitized preparation failure") }
        catch (actual: LocalEditPreparationException) { assertEquals(reason, actual.reason); assertNull(actual.cause) }
    }
    private fun same(expected: LocalTextEditPreparation, actual: LocalTextEditPreparation?) {
        assertNotNull(actual)
        assertEquals(expected.intent, actual!!.intent); assertEquals(expected.timestamp, actual.timestamp)
        assertEquals(expected.format, actual.format); assertEquals(expected.protectedPayload, actual.protectedPayload)
    }

    @Test fun bothOverloadsUseOnlyTheIndependentTextPortAndReturnExactFrozenRecords() = runTest {
        val calls = mutableListOf<Boolean>()
        val port = facade(runtime { edit, time, lease ->
            calls += lease != null; assertEquals(intent, edit); assertEquals(timestamp, time)
            assertNotSame(intent, edit); lease?.let { assertTrue(it(scope)) }; result(edit, time)
        }, StandardTestDispatcher(testScheduler))
        port.restore()
        same(result(), port.prepareLocalText(intent, timestamp))
        same(result(), port.prepareLocalText(intent, timestamp) { true })
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
        port.restore(); same(result(), port.prepareLocalText(intent, timestamp) { true })
        assertFalse(checkNotNull(captured)(scope))
    }

    @Test fun unauthenticatedAndDeniedRequestsDoNotConstructOrEnterRuntime() = runTest {
        var builds = 0
        val unopened = SerializedAuthenticationPort({ builds++; error("No graph construction") }, {},
            StandardTestDispatcher(testScheduler))
        assertNull(unopened.prepareLocalText(intent, timestamp) { true })
        failure(LocalEditPreparationFailure.AUTHENTICATION) { unopened.prepareLocalText(intent, timestamp) }
        assertEquals(0, builds)
        var calls = 0
        val port = facade(runtime { _, _, _ -> calls++; result() }, StandardTestDispatcher(testScheduler))
        port.restore()
        assertNull(port.prepareLocalText(intent, timestamp) { false }); assertEquals(0, calls)
    }

    @Test fun guardedDefaultAndMissingOrWrongFormatsNeverFallBackToUnrestricted() = runTest {
        for (format in listOf(null, AdapterFormat("foreign", 1), LOCAL_TEXT_PREPARATION_FORMAT)) {
            val port = facade(Runtime().apply { localTextFormat = format }, StandardTestDispatcher(testScheduler))
            port.restore(); assertNull(port.prepareLocalText(intent, timestamp) { true })
        }
        for (format in listOf(null, AdapterFormat("foreign", 1))) {
            val port = facade(Runtime().apply { localTextFormat = format }, StandardTestDispatcher(testScheduler))
            port.restore()
            failure(LocalEditPreparationFailure.UNSUPPORTED) { port.prepareLocalText(intent, timestamp) }
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
                if (guarded) port.prepareLocalText(source, timestamp) { true } else port.prepareLocalText(source, timestamp)
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
        val work = async { port.prepareLocalText(intent, timestamp) { allowed } }
        runCurrent(); allowed = false; release.complete(Unit); busy.await()
        assertNull(work.await()); assertEquals(0, calls)
    }

    @Test fun revocationDuringRuntimeAwaitDiscardsItsResult() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var allowed = true
        val port = facade(runtime { _, _, _ -> entered.complete(Unit); release.await(); result() },
            StandardTestDispatcher(testScheduler))
        port.restore()
        val work = async { port.prepareLocalText(intent, timestamp) { allowed } }
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
        assertNull(port.prepareLocalText(intent, timestamp) { allowed })
        assertFalse(checkNotNull(captured)(scope))
        lateinit var changing: Runtime
        changing = runtime { _, _, lease ->
            val active = checkNotNull(lease)
            changing.localTextFormat = AdapterFormat("foreign", 1); assertFalse(active(scope))
            changing.localTextFormat = LOCAL_TEXT_PREPARATION_FORMAT; assertFalse(active(scope)); result()
        }
        val second = facade(changing, StandardTestDispatcher(testScheduler)); second.restore()
        assertNull(second.prepareLocalText(intent, timestamp) { true })
    }

    @Test fun bothOverloadsRejectSubstitutedCompleteResults() = runTest {
        val changedBase = base.copy(content = base.content.copy(location = "Different original"))
        val substitutions = listOf(
            result(intent.copy(id = MutationId("other"))),
            result(intent.copy(scope = scope.copy(generation = 3))),
            result(intent.copy(base = changedBase)),
            result(intent.copy(content = intent.content.copy(description = "Other target"))),
            result(time = timestamp.plusSeconds(1)), result(format = AdapterFormat("other", 1)))
        for (substitute in substitutions) {
            val port = facade(runtime { _, _, _ -> substitute }, StandardTestDispatcher(testScheduler)); port.restore()
            assertNull(port.prepareLocalText(intent, timestamp) { true })
            failure(LocalEditPreparationFailure.CONTEXT) { port.prepareLocalText(intent, timestamp) }
        }
    }

    @Test fun runtimeFormatChangesCannotEscapeEitherOverload() = runTest {
        for (guarded in listOf(false, true)) {
            lateinit var changing: Runtime
            changing = runtime { _, _, _ -> changing.localTextFormat = AdapterFormat("other", 1); result() }
            val port = facade(changing, StandardTestDispatcher(testScheduler)); port.restore()
            if (guarded) assertNull(port.prepareLocalText(intent, timestamp) { true })
            else failure(LocalEditPreparationFailure.CONTEXT) { port.prepareLocalText(intent, timestamp) }
        }
    }

    @Test fun invalidInputIsRejectedBeforeDispatchOrCallingAdmission() = runTest {
        var admissions = 0; var calls = 0
        val port = facade(runtime { _, _, _ -> calls++; result() }, StandardTestDispatcher(testScheduler)); port.restore()
        val bad = listOf(intent.copy(state = LocalEditState.CONFLICT),
            intent.copy(format = EVENT_FORMAT.copy(version = EVENT_FORMAT.version + 1)),
            intent.copy(content = intent.content.copy(description = "\uD800")),
            intent.copy(content = intent.content.copy(time = EventTime.AllDay(LocalDate.of(2030, 1, 2), LocalDate.of(2030, 1, 5)))))
        for (edit in bad) {
            failure(LocalEditPreparationFailure.UNSUPPORTED) { port.prepareLocalText(edit, timestamp) }
            failure(LocalEditPreparationFailure.UNSUPPORTED) { port.prepareLocalText(edit, timestamp) { admissions++; true } }
        }
        failure(LocalEditPreparationFailure.UNSUPPORTED) { port.prepareLocalText(intent, timestamp.plusNanos(1)) }
        assertEquals(0, calls); assertEquals(0, admissions)
    }

    @Test fun typedFailuresPropagateUnchangedAndAlwaysCloseGuardedLeases() = runTest {
        for (reason in LocalEditPreparationFailure.entries) {
            val expected = LocalEditPreparationException(reason)
            var captured: ((CalendarScope) -> Boolean)? = null
            val port = facade(runtime { _, _, lease -> captured = lease; throw expected }, StandardTestDispatcher(testScheduler))
            port.restore()
            try { port.prepareLocalText(intent, timestamp) { true }; fail("Typed failure must propagate") }
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
                        if (origin == "UNGUARDED_RUNTIME") port.prepareLocalText(intent, timestamp)
                        else port.prepareLocalText(intent, timestamp) {
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
                if (guarded) port.prepareLocalText(intent, timestamp) { true } else port.prepareLocalText(intent, timestamp)
            }
            runCurrent(); assertTrue("Runtime must be reached", entered.isCompleted)
            val logout = async { port.cancel() }; runCurrent(); assertEquals(0, auth.cancellations)
            release.complete(Unit); same(result(), work.await()); logout.await()
            assertEquals(1, auth.cancellations)
            assertNull(port.prepareLocalText(intent, timestamp) { true })
            failure(LocalEditPreparationFailure.AUTHENTICATION) { port.prepareLocalText(intent, timestamp) }
        }
    }

    @Test fun cancelledPreparationReleasesMutexAndFailedCleanupStillClosesAuthentication() = runTest {
        val entered = CompletableDeferred<Unit>(); val auth = Auth()
        val port = facade(runtime { _, _, _ -> entered.complete(Unit); awaitCancellation() },
            StandardTestDispatcher(testScheduler), auth)
        port.restore()
        val work = async { port.prepareLocalText(intent, timestamp) { true } }
        runCurrent(); assertTrue("Runtime must be reached", entered.isCompleted)
        val logout = async { port.cancel() }; runCurrent(); assertEquals(0, auth.cancellations)
        work.cancelAndJoin(); logout.await(); assertEquals(1, auth.cancellations)
        assertNull(port.prepareLocalText(intent, timestamp) { true })
        port.restore(); auth.failCleanup = true
        try { port.cancel(); fail("Cleanup failure must propagate") } catch (_: IllegalStateException) { }
        assertNull(port.prepareLocalText(intent, timestamp) { true })
        failure(LocalEditPreparationFailure.AUTHENTICATION) { port.prepareLocalText(intent, timestamp) }
    }

    private class Auth : AuthenticationFlowPort {
        var cancellations = 0; var failCleanup = false
        override suspend fun restore() = AuthenticationStep.Authenticated
        override suspend fun signIn(username: AuthenticationSecret, password: AuthenticationSecret) = error("No login")
        override suspend fun submitCode(code: AuthenticationSecret) = error("No code")
        override suspend fun unlock(password: AuthenticationSecret) = error("No unlock")
        override suspend fun cancel() { cancellations++; if (failCleanup) error("Synthetic cleanup failure") }
    }
    private fun facade(runtime: CalendarLocalTextPreparationPort, dispatcher: CoroutineDispatcher,
        auth: Auth = Auth(), holdCatalog: suspend () -> Unit = {}) = SerializedAuthenticationPort({
        ProtonAuthenticationGraph(auth, NoEvents, localTexts = runtime,
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
