// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Patmanak
// Adapted from Contako; see auth/PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthenticationControllerTest {
    private val dispatcher = StandardTestDispatcher()

    @Test
    fun `double submit is ignored and cancellation cannot be overwritten by stale success`() =
        runTest(dispatcher) {
            val release = CompletableDeferred<AuthenticationStep>()
            val port = FakeAuthenticationFlowPort(signInResult = {
                withContext(NonCancellable) { release.await() }
            })
            val controller = controller(port)
            advanceUntilIdle()

            val firstUsername = "first-user".toCharArray()
            val firstPassword = "first-password".toCharArray()
            controller.submitSignIn(firstUsername, firstPassword)
            val duplicateUsername = "duplicate-user".toCharArray()
            val duplicatePassword = "duplicate-password".toCharArray()
            controller.submitSignIn(duplicateUsername, duplicatePassword)

            assertTrue(firstUsername.isCleared())
            assertTrue(firstPassword.isCleared())
            assertTrue(duplicateUsername.isCleared())
            assertTrue(duplicatePassword.isCleared())
            advanceUntilIdle()
            assertEquals(1, port.signInCalls)
            assertTrue(controller.state.value.isSubmitting)

            controller.cancelAuthentication()
            release.complete(AuthenticationStep.Authenticated)
            advanceUntilIdle()

            assertEquals(1, port.cancelCalls)
            assertEquals(AuthenticationDestination.SIGN_IN, controller.state.value.destination)
            assertFalse(controller.state.value.isSubmitting)
        }

    @Test
    fun `recreated state restores fail closed without carrying code or password`() = runTest(dispatcher) {
        val firstPort = FakeAuthenticationFlowPort(signInResult = { AuthenticationStep.CodeRequired })
        val first = controller(firstPort)
        advanceUntilIdle()
        val username = "account".toCharArray()
        val password = "operation-secret".toCharArray()
        first.submitSignIn(username, password)
        advanceUntilIdle()
        assertEquals(AuthenticationDestination.CODE, first.state.value.destination)

        val recreated = controller(FakeAuthenticationFlowPort())
        advanceUntilIdle()

        assertEquals(AuthenticationDestination.SIGN_IN, recreated.state.value.destination)
        assertFalse(recreated.state.value.toString().contains("operation-secret"))
        assertFalse(recreated.state.value.toString().contains("account"))
        assertTrue(username.isCleared())
        assertTrue(password.isCleared())
    }

    @Test
    fun `TOTP and recovery input is submitted opaquely then cleared`() = runTest(dispatcher) {
        val observed = mutableListOf<String>()
        val port = FakeAuthenticationFlowPort(
            restoreResult = AuthenticationStep.CodeRequired,
            codeResult = { secret ->
                secret.consumeSuspend { chars -> observed += String(chars) }
                AuthenticationStep.PasswordRequired(PasswordPurpose.KEY_UNLOCK)
            },
        )
        val controller = controller(port)
        advanceUntilIdle()
        val code = " Ab- 09_Z ".toCharArray()

        controller.submitCode(code)
        advanceUntilIdle()

        assertEquals(listOf(" Ab- 09_Z "), observed)
        assertTrue(code.isCleared())
        assertEquals(AuthenticationDestination.PASSWORD, controller.state.value.destination)
    }

    @Test
    fun `FIDO only state fails closed and exposes no secure input state`() = runTest(dispatcher) {
        val port = FakeAuthenticationFlowPort(
            signInResult = { AuthenticationStep.SecurityKeyOnlyUnsupported },
        )
        val controller = controller(port)
        advanceUntilIdle()

        controller.submitSignIn("account".toCharArray(), "password".toCharArray())
        advanceUntilIdle()

        assertEquals(AuthenticationDestination.SECURITY_KEY_UNSUPPORTED, controller.state.value.destination)
        controller.cancelAuthentication()
        advanceUntilIdle()
        assertEquals(AuthenticationDestination.SIGN_IN, controller.state.value.destination)
    }

    @Test
    fun `invalid and duplicate inputs are always zeroed before returning`() = runTest(dispatcher) {
        val port = FakeAuthenticationFlowPort()
        val controller = controller(port)
        advanceUntilIdle()
        val username = "   ".toCharArray()
        val password = CharArray(0)

        controller.submitSignIn(username, password)

        assertTrue(username.isCleared())
        assertEquals(AuthenticationMessage.INPUT_REQUIRED, controller.state.value.message)
        assertEquals(0, port.signInCalls)
    }

    @Test
    fun `explicit sign in cannot return silently when the gateway yields signed out state`() =
        runTest(dispatcher) {
            val controller = controller(
                FakeAuthenticationFlowPort(
                    restoreResult = AuthenticationStep.SignIn,
                    signInResult = { AuthenticationStep.SignIn },
                ),
            )
            advanceUntilIdle()
            assertEquals(AuthenticationDestination.SIGN_IN, controller.state.value.destination)
            assertEquals(null, controller.state.value.message)

            controller.submitSignIn("account".toCharArray(), "wrong-password".toCharArray())
            advanceUntilIdle()

            assertEquals(AuthenticationDestination.SIGN_IN, controller.state.value.destination)
            assertEquals(AuthenticationMessage.REJECTED, controller.state.value.message)
            assertFalse(controller.state.value.isSubmitting)
        }

    @Test
    fun `second account attempt shows the actionable single account limitation`() = runTest(dispatcher) {
        val controller = controller(
            FakeAuthenticationFlowPort(
                signInResult = {
                    AuthenticationStep.Failure(AuthenticationFailure.ACCOUNT_ALREADY_CONNECTED)
                },
            ),
        )
        advanceUntilIdle()

        controller.submitSignIn("other-account".toCharArray(), "password".toCharArray())
        advanceUntilIdle()

        assertEquals(AuthenticationDestination.SIGN_IN, controller.state.value.destination)
        assertEquals(AuthenticationMessage.ACCOUNT_ALREADY_CONNECTED, controller.state.value.message)
        assertFalse(controller.state.value.isSubmitting)
    }

    @Test
    fun `rate limit disables retry until bounded monotonic deadline and rejects double retry`() =
        runTest(dispatcher) {
            var attempts = 0
            val port = FakeAuthenticationFlowPort(
                signInResult = {
                    attempts += 1
                    if (attempts == 1) {
                        AuthenticationStep.Failure(AuthenticationFailure.RATE_LIMITED, 5_000)
                    } else {
                        AuthenticationStep.Authenticated
                    }
                },
            )
            val controller = controller(
                port = port,
                monotonicClock = AuthenticationMonotonicClock { testScheduler.currentTime },
            )
            advanceUntilIdle()

            controller.submitSignIn("account".toCharArray(), "password".toCharArray())
            runCurrent()

            assertTrue(controller.state.value.isRetryBlocked)
            assertEquals(5_000L, controller.state.value.retryAfterMillis)
            assertEquals(5_000L, controller.state.value.retryBlockedUntilElapsedRealtimeMillis)

            val duplicateUsername = "duplicate".toCharArray()
            val duplicatePassword = "duplicate-password".toCharArray()
            controller.submitSignIn(duplicateUsername, duplicatePassword)
            runCurrent()
            assertTrue(duplicateUsername.isCleared())
            assertTrue(duplicatePassword.isCleared())
            assertEquals(1, port.signInCalls)

            advanceTimeBy(4_999)
            runCurrent()
            assertTrue(controller.state.value.isRetryBlocked)

            advanceTimeBy(1)
            runCurrent()
            assertFalse(controller.state.value.isRetryBlocked)

            controller.submitSignIn("account".toCharArray(), "password".toCharArray())
            advanceUntilIdle()
            assertEquals(2, port.signInCalls)
            assertEquals(AuthenticationDestination.AUTHENTICATED, controller.state.value.destination)
        }

    @Test
    fun `rate limit without server budget uses safe default delay`() = runTest(dispatcher) {
        val port = FakeAuthenticationFlowPort(
            signInResult = {
                AuthenticationStep.Failure(AuthenticationFailure.RATE_LIMITED)
            },
        )
        val controller = controller(
            port = port,
            monotonicClock = AuthenticationMonotonicClock { testScheduler.currentTime },
        )
        advanceUntilIdle()

        controller.submitSignIn("account".toCharArray(), "password".toCharArray())
        runCurrent()

        assertTrue(controller.state.value.isRetryBlocked)
        assertEquals(30_000L, controller.state.value.retryAfterMillis)
        advanceTimeBy(29_999)
        runCurrent()
        assertTrue(controller.state.value.isRetryBlocked)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(controller.state.value.isRetryBlocked)
    }

    @Test
    fun `server retry budget longer than timer slice is never shortened`() = runTest(dispatcher) {
        val serverBudget = 60 * 60_000L
        val controller = controller(
            port = FakeAuthenticationFlowPort(
                signInResult = { AuthenticationStep.Failure(AuthenticationFailure.RATE_LIMITED, serverBudget) },
            ),
            monotonicClock = AuthenticationMonotonicClock { testScheduler.currentTime },
        )
        advanceUntilIdle()

        controller.submitSignIn("account".toCharArray(), "password".toCharArray())
        runCurrent()
        assertEquals(serverBudget, controller.state.value.retryAfterMillis)
        assertEquals(serverBudget, controller.state.value.retryBlockedUntilElapsedRealtimeMillis)

        advanceTimeBy(serverBudget - 1)
        runCurrent()
        assertTrue(controller.state.value.isRetryBlocked)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(controller.state.value.isRetryBlocked)
    }

    @Test
    fun `retry deadline addition saturates at Long MAX without an overflowing early release`() = runTest(dispatcher) {
        var elapsed = Long.MAX_VALUE - 500
        val controller = controller(
            port = FakeAuthenticationFlowPort(
                signInResult = { AuthenticationStep.Failure(AuthenticationFailure.RATE_LIMITED, 1_000) },
            ),
            monotonicClock = AuthenticationMonotonicClock { elapsed },
        )
        advanceUntilIdle()

        controller.submitSignIn("account".toCharArray(), "password".toCharArray())
        runCurrent()
        assertTrue(controller.state.value.isRetryBlocked)
        assertEquals(Long.MAX_VALUE, controller.state.value.retryBlockedUntilElapsedRealtimeMillis)

        advanceTimeBy(499)
        runCurrent()
        assertTrue(controller.state.value.isRetryBlocked)
        elapsed = Long.MAX_VALUE
        advanceTimeBy(1)
        runCurrent()
        assertFalse(controller.state.value.isRetryBlocked)
    }

    @Test
    fun `challenge cancellation cannot bypass the monotonic retry deadline on sign in`() = runTest(dispatcher) {
        val port = FakeAuthenticationFlowPort(
            restoreResult = AuthenticationStep.CodeRequired,
            codeResult = {
                it.close()
                AuthenticationStep.Failure(AuthenticationFailure.RATE_LIMITED, 5_000)
            },
        )
        val controller = controller(
            port = port,
            monotonicClock = AuthenticationMonotonicClock { testScheduler.currentTime },
        )
        advanceUntilIdle()

        controller.submitCode("fixture-code".toCharArray())
        runCurrent()
        assertTrue(controller.state.value.isRetryBlocked)
        controller.cancelAuthentication()
        runCurrent()
        assertEquals(AuthenticationDestination.SIGN_IN, controller.state.value.destination)
        assertTrue(controller.state.value.isRetryBlocked)

        val username = "account".toCharArray()
        val password = "password".toCharArray()
        controller.submitSignIn(username, password)
        runCurrent()
        assertTrue(username.isCleared())
        assertTrue(password.isCleared())
        assertEquals(0, port.signInCalls)

        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(controller.state.value.isRetryBlocked)
    }

    @Test
    fun `repeated back cannot cancel and restart cleanup already in progress`() = runTest(dispatcher) {
        val releaseCleanup = CompletableDeferred<Unit>()
        val port = FakeAuthenticationFlowPort(
            restoreResult = AuthenticationStep.CodeRequired,
            cancelResult = { releaseCleanup.await() },
        )
        val controller = controller(port)
        advanceUntilIdle()

        controller.cancelAuthentication()
        controller.cancelAuthentication()
        runCurrent()

        assertEquals(1, port.cancelCalls)
        assertTrue(controller.state.value.isCleanupInProgress)

        releaseCleanup.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, port.cancelCalls)
        assertFalse(controller.state.value.isCleanupInProgress)
    }
}

private class FakeAuthenticationFlowPort(
    private val restoreResult: AuthenticationStep = AuthenticationStep.SignIn,
    private val signInResult: suspend () -> AuthenticationStep = { AuthenticationStep.Authenticated },
    private val codeResult: suspend (AuthenticationSecret) -> AuthenticationStep = {
        it.close()
        AuthenticationStep.Authenticated
    },
    private val cancelResult: suspend () -> Unit = {},
) : AuthenticationFlowPort {
    var signInCalls: Int = 0
    var cancelCalls: Int = 0

    override suspend fun restore(): AuthenticationStep = restoreResult

    override suspend fun signIn(
        username: AuthenticationSecret,
        password: AuthenticationSecret,
    ): AuthenticationStep {
        signInCalls += 1
        return username.consumeSuspend {
            password.consumeSuspend { signInResult() }
        }
    }

    override suspend fun submitCode(code: AuthenticationSecret): AuthenticationStep = codeResult(code)

    override suspend fun unlock(password: AuthenticationSecret): AuthenticationStep {
        password.close()
        return AuthenticationStep.Authenticated
    }

    override suspend fun cancel() {
        cancelCalls += 1
        cancelResult()
    }
}

private fun CharArray.isCleared(): Boolean = all { it == '\u0000' }

@OptIn(ExperimentalCoroutinesApi::class)
private fun kotlinx.coroutines.test.TestScope.controller(
    port: AuthenticationFlowPort,
    monotonicClock: AuthenticationMonotonicClock = AuthenticationMonotonicClock { testScheduler.currentTime },
): AuthenticationController = AuthenticationController(port, monotonicClock, this)
