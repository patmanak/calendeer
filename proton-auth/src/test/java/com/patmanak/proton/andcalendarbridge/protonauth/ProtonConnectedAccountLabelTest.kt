// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.auth.*
import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogResult
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import me.proton.core.account.domain.entity.Account
import me.proton.core.account.domain.entity.AccountDetails
import me.proton.core.account.domain.entity.AccountMetadataDetails
import me.proton.core.account.domain.entity.AccountState
import me.proton.core.account.domain.entity.AccountType
import me.proton.core.account.domain.entity.SessionDetails
import me.proton.core.account.domain.entity.SessionState as CoreSessionState
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtonConnectedAccountLabelTest {
    @Test fun localReadyAccountPrefersUsernameAndFallsBackToEmail() = runTest {
        assertEquals("Synthetic User", readConnectedAccountLabel { listOf(account("Synthetic User")) })
        assertEquals("qa@example.invalid", readConnectedAccountLabel { listOf(account("   ")) })
        assertEquals("qa@example.invalid", readConnectedAccountLabel { listOf(account(null)) })
        assertNull(readConnectedAccountLabel { listOf(account(null, null)) })
    }

    @Test fun onlyUniqueReadyAuthenticatedSessionMayExposeMetadata() = runTest {
        val valid = account()
        for (accounts in listOf(emptyList(), listOf(valid, valid),
            listOf(valid.copy(state = AccountState.NotReady)),
            listOf(valid.copy(state = AccountState.TwoPassModeNeeded)),
            listOf(valid.copy(sessionState = CoreSessionState.SecondFactorNeeded)),
            listOf(valid.copy(sessionId = null)))) {
            assertNull(readConnectedAccountLabel { accounts })
        }
    }

    @Test fun displayValuesAreBoundedAndRejectControlsAndMalformedUnicode() = runTest {
        for (invalid in listOf("a\nb", "a\u0000b", "a\u202Eb", "x".repeat(257), "\uD800")) {
            assertNull(readConnectedAccountLabel { listOf(account(invalid, null)) })
            assertEquals("qa@example.invalid", readConnectedAccountLabel { listOf(account(invalid)) })
        }
        assertEquals("Équipe 🌿", readConnectedAccountLabel { listOf(account("  Équipe 🌿  ")) })
    }

    @Test fun localReadFailuresAreUnavailableAndNeverExposeIdentifiers() = runTest {
        assertNull(readConnectedAccountLabel { throw IOException("Synthetic private metadata") })
        assertNull(readConnectedAccountLabel { listOf(account(null, "")) })
    }

    @Test fun signedOutReadDoesNotConstructRuntimeOrReadMetadata() = runTest {
        var builds = 0
        val facade = SerializedAuthenticationPort({ builds++; error("No runtime construction") }, {}, StandardTestDispatcher(testScheduler))
        assertNull(facade.connectedAccountLabel())
        assertEquals(0, builds)
    }

    @Test fun restoredMetadataIsReadFreshAndLogoutDisablesFurtherReads() = runTest {
        var calls = 0; var label = "First synthetic name"
        val facade = facade(StandardTestDispatcher(testScheduler)) { calls++; label }
        facade.restore()
        assertEquals(label, facade.connectedAccountLabel())
        label = "Second synthetic name"
        assertEquals(label, facade.connectedAccountLabel())
        facade.cancel()
        assertNull(facade.connectedAccountLabel())
        assertEquals(2, calls)
    }

    @Test fun metadataReadSerializesWithLogoutAndDoesNotCallEventServices() = runTest {
        val auth = Auth(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val facade = facade(StandardTestDispatcher(testScheduler), auth) {
            entered.complete(Unit); release.await(); "Synthetic name"
        }
        facade.restore()
        val reading = async { facade.connectedAccountLabel() }
        try {
            runCurrent(); assertTrue("Local callback reached", entered.isCompleted)
            val logout = async { facade.cancel() }
            runCurrent(); assertEquals(0, auth.cancellations)
            release.complete(Unit)
            assertEquals("Synthetic name", reading.await()); logout.await()
            assertNull(facade.connectedAccountLabel()); assertEquals(1, auth.cancellations)
        } finally { release.complete(Unit); reading.cancelAndJoin() }
    }

    @Test fun facadeMetadataFailureReturnsNullWithoutResettingAuthentication() = runTest {
        var broken = true
        val facade = facade(StandardTestDispatcher(testScheduler)) {
            if (broken) throw IOException("Synthetic private metadata") else "Recovered synthetic name"
        }
        facade.restore(); assertNull(facade.connectedAccountLabel())
        broken = false; assertEquals("Recovered synthetic name", facade.connectedAccountLabel())
    }

    @Test fun localAndFacadeReadsPrioritizeSwallowedCancellationOverReturnOrFailure() = runTest {
        for (local in listOf(false, true)) for (fail in listOf(false, true)) {
            var reached = false; var cancellations = 0; var failures = 0; var returned = 0
            val cancel: suspend () -> Unit = {
                reached = true; currentCoroutineContext().cancel()
                if (fail) throw IOException("Synthetic cancellation")
            }
            val facade = facade(StandardTestDispatcher(testScheduler)) { cancel(); "Synthetic name" }
            facade.restore()
            supervisorScope {
                val child = async {
                    try {
                        if (local) readConnectedAccountLabel { cancel(); listOf(account()) }
                        else facade.connectedAccountLabel()
                        returned++
                    } catch (_: CancellationException) { cancellations++ }
                    catch (_: Exception) { failures++ }
                }
                try { child.await() } catch (_: CancellationException) { }
            }
            assertTrue("Local metadata callback reached", reached)
            assertEquals(1, cancellations); assertEquals(0, failures); assertEquals(0, returned)
        }
    }

    @Test fun graphWithoutMetadataCapabilityRemainsCompatible() = runTest {
        val facade = SerializedAuthenticationPort({ ProtonAuthenticationGraph(Auth(), NoEvents) {
            CalendarCatalogResult.Success(AccountId("synthetic-account"), emptyList())
        } }, {}, StandardTestDispatcher(testScheduler))
        facade.restore(); assertNull(facade.connectedAccountLabel())
    }

    private fun account(username: String? = "Synthetic User", email: String? = "qa@example.invalid") = Account(
        userId = UserId("synthetic-user"), username = username, email = email,
        state = AccountState.Ready, sessionId = SessionId("synthetic-session"), sessionState = CoreSessionState.Authenticated,
        details = AccountDetails(
            account = AccountMetadataDetails(0L, emptyList()),
            session = SessionDetails(initialEventId = null, requiredAccountType = AccountType.Internal,
                secondFactorEnabled = false, twoPassModeEnabled = false, passphrase = null,
                password = null, fido2AuthenticationOptionsJson = null),
        ),
    )
    private class Auth : AuthenticationFlowPort {
        var cancellations = 0
        override suspend fun restore() = AuthenticationStep.Authenticated
        override suspend fun signIn(username: AuthenticationSecret, password: AuthenticationSecret) = error("No login")
        override suspend fun submitCode(code: AuthenticationSecret) = error("No code")
        override suspend fun unlock(password: AuthenticationSecret) = error("No unlock")
        override suspend fun cancel() { cancellations++ }
    }
    private fun facade(dispatcher: CoroutineDispatcher, auth: Auth = Auth(), read: suspend () -> String?) =
        SerializedAuthenticationPort({ ProtonAuthenticationGraph(auth, NoEvents, connectedAccountLabel = read) {
            CalendarCatalogResult.Success(AccountId("synthetic-account"), emptyList())
        } }, {}, dispatcher)

    private object NoEvents : CalendarDeltaRemote {
        override val format = EVENT_FORMAT
        override val deltaFormat = CALENDAR_DELTA_FORMAT
        override suspend fun currentAccount(): AccountId? = error("No event identity lookup")
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
