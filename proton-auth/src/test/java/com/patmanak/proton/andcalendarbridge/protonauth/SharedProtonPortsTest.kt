// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.auth.AuthenticationFailure
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationFlowPort
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationSecret
import com.patmanak.proton.andcalendarbridge.auth.AuthenticationStep
import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogFailure
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SharedProtonPortsTest {
    @Test fun createPreparationSharesLogoutMutexAndAuthenticationGate() = runTest {
        val auth = FakeAuth()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = CalendarScope(AccountId("a"), CalendarId("c"), 0)
        val draft = CalendarCreateDraft(scope, MutationId("synthetic"), java.time.Instant.EPOCH,
            EventContent("Synthetic", EventTime.AllDay(java.time.LocalDate.of(2026, 1, 1), java.time.LocalDate.of(2026, 1, 2))))
        val preparation = CalendarCreatePreparationPort {
            entered.complete(Unit); release.await(); PreparedCalendarCreate(scope, AdapterFormat("synthetic", 1), "sealed")
        }
        val port = SerializedAuthenticationPort({
            ProtonAuthenticationGraph(auth, NoEvents, preparation) { CalendarCatalogResult.Success(AccountId("a"), emptyList()) }
        }, {}, StandardTestDispatcher(testScheduler))
        suspend fun denied() {
            try { port.prepareCreate(draft); fail("Preparation must require authentication") }
            catch (failure: CreatePreparationException) { assertEquals(CreatePreparationFailure.AUTHENTICATION, failure.reason) }
        }
        denied(); port.restore()
        val work = async { port.prepareCreate(draft) }; entered.await()
        val logout = async { port.cancel() }; runCurrent()
        assertEquals(0, auth.cancellations)
        release.complete(Unit); work.await(); logout.await(); denied()
    }
    @Test fun sharedProductionBoundarySupportsDurableCalendarDeltas() {
        val port: Any = SerializedAuthenticationPort({ error("No runtime needed") }, {})
        assertTrue("The shared production boundary must expose calendar deltas", port is CalendarDeltaRemote)
    }
    @Test fun deltaOperationsShareLogoutMutexAndAllCloseAfterCleanup() = runTest {
        val auth = FakeAuth()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scope = CalendarScope(AccountId("a"), CalendarId("c"), 0)
        val cursor = OpaqueCursor(CALENDAR_DELTA_FORMAT, "synthetic-cursor")
        val events = object : CalendarDeltaRemote by NoEvents {
            override suspend fun latestChanges(scope: CalendarScope): OpaqueCursor {
                entered.complete(Unit); release.await(); return cursor
            }
        }
        val port = SerializedAuthenticationPort({
            ProtonAuthenticationGraph(auth, events) { CalendarCatalogResult.Success(AccountId("a"), emptyList()) }
        }, {}, StandardTestDispatcher(testScheduler))
        port.restore()
        val latest = async { port.latestChanges(scope) }
        entered.await()
        val logout = async { port.cancel() }
        runCurrent()
        assertEquals(0, auth.cancellations)
        release.complete(Unit)
        latest.await(); logout.await()
        val page = ReceivedChangePage(scope, cursor, "synthetic-sealed")
        val event = ReceivedCalendarEvent(scope, EventId("e"), Revision("r"), EVENT_FORMAT, "synthetic-sealed")
        val calls = listOf<suspend () -> Unit>(
            { port.latestChanges(scope) }, { port.receiveChanges(scope, cursor) }, { port.decodeChanges(page) },
            { port.receiveEvent(scope, EventId("e")) }, { port.decodeEvent(event) }, { port.refreshContext(scope) })
        for (call in calls) {
            try { call(); fail("Delta operation must stop after logout") }
            catch (failure: RemoteImportException) { assertEquals(RemoteImportFailure.AUTHENTICATION, failure.reason) }
        }
    }
    @Test fun receiptDecodeAndDeferredRetryAreDeniedAfterLogoutAndSerializedWithCleanup() = runTest {
        val auth = FakeAuth()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var receivedCalls = 0
        var retryCalls = 0
        val events = object : TestDeltaRemote {
            override val format = EVENT_FORMAT
            override suspend fun currentAccount() = AccountId("a")
            override suspend fun dispatch(mutation: Mutation) = DispatchResult.Rejected
            override suspend fun fetch(request: PageRequest): RemotePage = error("Unexpected fetch")
            override suspend fun receive(request: PageRequest): ReceivedCalendarPage {
                receivedCalls++
                return ReceivedCalendarPage(request, "synthetic-sealed")
            }
            override suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage {
                entered.complete(Unit); release.await()
                return DecodedCalendarPage(RemotePage(received.request.checkpoint.scope, format, emptyList(), null, true))
            }
            override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? {
                retryCalls++; return null
            }
        }
        val port = SerializedAuthenticationPort({
            ProtonAuthenticationGraph(auth, events) { CalendarCatalogResult.Success(AccountId("a"), emptyList()) }
        }, {}, StandardTestDispatcher(testScheduler))
        val scope = CalendarScope(AccountId("a"), CalendarId("c"), 0)
        val request = PageRequest(PageCheckpoint(scope, EVENT_FORMAT), 10)
        port.restore()
        val receipt = port.receive(request)
        val issue = DeferredCalendarEvent(EventId("e"), Revision("r"), EVENT_FORMAT, DeferredEventReason.UNSUPPORTED_CONTENT, "synthetic-sealed")
        assertNull(port.retryDeferred(scope, issue))
        val decode = async { port.decode(receipt) }
        entered.await()
        val logout = async { port.cancel() }
        runCurrent()
        assertEquals(0, auth.cancellations)
        release.complete(Unit)
        decode.await(); logout.await()
        for (operation in listOf<suspend () -> Unit>({ port.receive(request) }, { port.decode(receipt) }, { port.retryDeferred(scope, issue) })) {
            try { operation(); fail("Authenticated gate must be closed") }
            catch (failure: RemoteImportException) { assertEquals(RemoteImportFailure.AUTHENTICATION, failure.reason) }
        }
        assertEquals(1, receivedCalls)
        assertEquals(1, retryCalls)
    }

    @Test fun eventImportAndOfflineIdentityShareLogoutGateAndMutex() = runTest {
        val auth = FakeAuth()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = object : TestDeltaRemote {
            override suspend fun receive(request: PageRequest): ReceivedCalendarPage = error("Unexpected receive")
        override suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage = error("Unexpected decode")
        override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? = error("Unexpected retry")
        override val format = EVENT_FORMAT
            override suspend fun currentAccount() = AccountId("a")
            override suspend fun dispatch(mutation: Mutation) = DispatchResult.Rejected
            override suspend fun fetch(request: PageRequest): RemotePage {
                entered.complete(Unit)
                release.await()
                return RemotePage(request.checkpoint.scope, format, emptyList(), null, true)
            }
        }
        val port = SerializedAuthenticationPort({
            ProtonAuthenticationGraph(auth, events) { CalendarCatalogResult.Success(AccountId("a"), emptyList()) }
        }, {}, StandardTestDispatcher(testScheduler))
        val request = PageRequest(PageCheckpoint(CalendarScope(AccountId("a"), CalendarId("c"), 0), EVENT_FORMAT), 10)
        assertNull(port.currentAccount())
        try { port.fetch(request); fail() } catch (failure: RemoteImportException) { assertEquals(RemoteImportFailure.AUTHENTICATION, failure.reason) }
        port.restore()
        assertEquals(AccountId("a"), port.currentAccount())
        val fetch = async { port.fetch(request) }
        entered.await()
        val logout = async { port.cancel() }
        runCurrent()
        assertEquals(0, auth.cancellations)
        release.complete(Unit)
        fetch.await(); logout.await()
        assertNull(port.currentAccount())
        try { port.fetch(request); fail() } catch (failure: RemoteImportException) { assertEquals(RemoteImportFailure.AUTHENTICATION, failure.reason) }
    }

    @Test fun catalogIsDeniedBeforeSuccessfulAuthAndGraphIsBuiltOnlyOnce() = runTest {
        var builds = 0
        val auth = FakeAuth()
        val port = SerializedAuthenticationPort({
            builds++
            ProtonAuthenticationGraph(auth, NoEvents) { CalendarCatalogResult.Success(AccountId("a"), emptyList()) }
        }, {}, StandardTestDispatcher(testScheduler))
        assertEquals(denied, port.load())
        assertEquals(0, builds)
        auth.next = AuthenticationStep.CodeRequired
        port.restore()
        assertEquals(denied, port.load())
        auth.next = AuthenticationStep.Authenticated
        port.restore()
        assertTrue(port.load() is CalendarCatalogResult.Success)
        assertEquals(1, builds)
        port.cancel()
        assertEquals(denied, port.load())
        assertEquals(1, auth.cancellations)
    }

    @Test fun logoutWaitsForCatalogAndDoesNotAllowCatalogAfterCleanup() = runTest {
        val auth = FakeAuth()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val port = SerializedAuthenticationPort({
            ProtonAuthenticationGraph(auth, NoEvents) {
                entered.complete(Unit)
                release.await()
                CalendarCatalogResult.Success(AccountId("a"), emptyList())
            }
        }, {}, StandardTestDispatcher(testScheduler))
        port.restore()
        val fetch = async { port.load() }
        entered.await()
        val logout = async { port.cancel() }
        runCurrent()
        assertEquals(0, auth.cancellations)
        release.complete(Unit)
        fetch.await()
        logout.await()
        assertEquals(1, auth.cancellations)
        assertEquals(denied, port.load())
    }

    @Test fun failedCleanupStillClosesCatalogGateAndTransientAuthFailureDoesNotEraseValidGate() = runTest {
        val auth = FakeAuth()
        val port = SerializedAuthenticationPort({
            ProtonAuthenticationGraph(auth, NoEvents) { CalendarCatalogResult.Success(AccountId("a"), emptyList()) }
        }, {}, StandardTestDispatcher(testScheduler))
        port.restore()
        auth.next = AuthenticationStep.Failure(AuthenticationFailure.OFFLINE)
        port.restore()
        assertTrue(port.load() is CalendarCatalogResult.Success)
        auth.failCleanup = true
        try { port.cancel(); fail("Cleanup error must propagate") } catch (_: IllegalStateException) { }
        assertEquals(denied, port.load())
    }

    private class FakeAuth : AuthenticationFlowPort {
        var next: AuthenticationStep = AuthenticationStep.Authenticated
        var cancellations = 0
        var failCleanup = false
        override suspend fun restore() = next
        override suspend fun signIn(username: AuthenticationSecret, password: AuthenticationSecret) = next
        override suspend fun submitCode(code: AuthenticationSecret) = next
        override suspend fun unlock(password: AuthenticationSecret) = next
        override suspend fun cancel() {
            cancellations++
            if (failCleanup) error("synthetic cleanup failure")
        }
    }
    private object NoEvents : TestDeltaRemote {
        override suspend fun receive(request: PageRequest): ReceivedCalendarPage = error("Unexpected receive")
        override suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage = error("Unexpected decode")
        override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? = error("Unexpected retry")
        override val format = EVENT_FORMAT
        override suspend fun currentAccount() = AccountId("a")
        override suspend fun fetch(request: com.patmanak.proton.andcalendarbridge.sync.PageRequest): com.patmanak.proton.andcalendarbridge.sync.RemotePage = error("Unexpected fetch")
        override suspend fun dispatch(mutation: com.patmanak.proton.andcalendarbridge.sync.Mutation) = com.patmanak.proton.andcalendarbridge.sync.DispatchResult.Rejected
    }
    private interface TestDeltaRemote : CalendarDeltaRemote {
        override val deltaFormat get() = CALENDAR_DELTA_FORMAT
        override suspend fun latestChanges(scope: CalendarScope): OpaqueCursor = error("Unexpected delta operation")
        override suspend fun receiveChanges(scope: CalendarScope, after: OpaqueCursor): ReceivedChangePage = error("Unexpected delta operation")
        override suspend fun decodeChanges(received: ReceivedChangePage): DecodedChangePage = error("Unexpected delta operation")
        override suspend fun receiveEvent(scope: CalendarScope, eventId: EventId): ReceivedCalendarEvent = error("Unexpected delta operation")
        override suspend fun decodeEvent(received: ReceivedCalendarEvent): DecodedCalendarEvent = error("Unexpected delta operation")
        override suspend fun refreshContext(scope: CalendarScope): Unit = error("Unexpected delta operation")
    }
    private companion object {
        val denied = CalendarCatalogResult.Failure(CalendarCatalogFailure.AUTHENTICATION_REQUIRED)
    }
}
