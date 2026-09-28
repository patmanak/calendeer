// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Patmanak
package com.patmanak.proton.andcalendarbridge.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthenticationContractTest {
    @Test fun `generic failure honors explicit server retry delay`() = runTest {
        val port = object : ScriptedPort() {
            override suspend fun signIn(username: AuthenticationSecret, password: AuthenticationSecret): AuthenticationStep {
                super.signIn(username, password)
                return if (signInCalls == 1) {
                    AuthenticationStep.Failure(AuthenticationFailure.TRY_AGAIN, 5_000)
                } else AuthenticationStep.Authenticated
            }
        }
        val controller = AuthenticationController(port, { testScheduler.currentTime }, this)
        runCurrent()
        controller.submitSignIn("fixture-user".toCharArray(), "fixture-password".toCharArray())
        runCurrent()
        assertTrue(controller.state.value.isRetryBlocked)
        assertEquals(AuthenticationMessage.TRY_AGAIN, controller.state.value.message)
        assertEquals(5_000L, controller.state.value.retryAfterMillis)
        val blockedUser = "blocked-user".toCharArray()
        val blockedPassword = "blocked-password".toCharArray()
        controller.submitSignIn(blockedUser, blockedPassword)
        assertTrue(blockedUser.all { it == '\u0000' })
        assertTrue(blockedPassword.all { it == '\u0000' })
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, port.signInCalls)
        assertTrue(controller.state.value.isRetryBlocked)
        advanceTimeBy(1)
        runCurrent()
        assertFalse(controller.state.value.isRetryBlocked)
        controller.submitSignIn("fixture-user".toCharArray(), "fixture-password".toCharArray())
        runCurrent()
        assertEquals(2, port.signInCalls)
        assertEquals(AuthenticationDestination.AUTHENTICATED, controller.state.value.destination)
        controller.dispose()
    }

    @Test fun `owned secret clears on throwing consumption and cannot be consumed twice`() {
        val input = "synthetic-secret".toCharArray()
        val secret = AuthenticationSecret.takeAndClear(input)
        assertTrue(input.all { it == '\u0000' })
        var borrowed: CharArray? = null
        assertThrows(IllegalArgumentException::class.java) {
            secret.consume { borrowed = it; throw IllegalArgumentException("fixture") }
        }
        assertTrue(borrowed!!.all { it == '\u0000' })
        assertThrows(IllegalStateException::class.java) { secret.consume { } }
        assertEquals("AuthenticationSecret(REDACTED)", secret.toString())
        secret.close()
    }

    @Test fun `oversized secret is rejected and caller input is still cleared`() {
        val input = CharArray(16_385) { 'x' }
        assertThrows(IllegalArgumentException::class.java) { AuthenticationSecret.takeAndClear(input) }
        assertTrue(input.all { it == '\u0000' })
    }

    @Test fun `second factor then mailbox unlock is authenticated but never Calendar ready`() = runTest {
        val port = ScriptedPort()
        val controller = AuthenticationController(port, { testScheduler.currentTime }, this)
        runCurrent()
        controller.submitSignIn("fixture-user".toCharArray(), "fixture-password".toCharArray())
        runCurrent()
        assertEquals(AuthenticationDestination.CODE, controller.state.value.destination)
        val code = "fixture-recovery".toCharArray()
        controller.submitCode(code)
        runCurrent()
        assertTrue(code.all { it == '\u0000' })
        assertEquals(AuthenticationDestination.PASSWORD, controller.state.value.destination)
        assertEquals(PasswordPurpose.MAILBOX, controller.state.value.passwordPurpose)
        val password = "fixture-mailbox".toCharArray()
        controller.submitPassword(password)
        runCurrent()
        assertTrue(password.all { it == '\u0000' })
        assertEquals(AuthenticationDestination.AUTHENTICATED, controller.state.value.destination)
        assertEquals(1, port.unlockCalls)
        controller.dispose()
    }

    @Test fun `disposal rejects new input clears retained buffers and ignores late success`() = runTest {
        val release = CompletableDeferred<Unit>()
        var borrowed: CharArray? = null
        val port = object : ScriptedPort() {
            override suspend fun signIn(username: AuthenticationSecret, password: AuthenticationSecret): AuthenticationStep {
                username.close()
                return password.consumeSuspend {
                    borrowed = it
                    withContext(NonCancellable) { release.await() }
                    AuthenticationStep.Authenticated
                }
            }
        }
        val controller = AuthenticationController(port, { testScheduler.currentTime }, this)
        runCurrent()
        controller.submitSignIn("fixture-user".toCharArray(), "fixture-password".toCharArray())
        runCurrent()
        controller.dispose()
        assertTrue(borrowed!!.all { it == '\u0000' })
        val input = "unused".toCharArray()
        controller.submitCode(input)
        assertTrue(input.all { it == '\u0000' })
        release.complete(Unit)
        runCurrent()
        assertTrue(borrowed!!.all { it == '\u0000' })
        assertEquals(AuthenticationDestination.SIGN_IN, controller.state.value.destination)
        assertEquals(1, port.cancelCalls)
    }

    @Test fun `failed cleanup blocks login until explicit cleanup retry succeeds`() = runTest {
        val port = object : ScriptedPort() {
            override suspend fun cancel() {
                super.cancel()
                if (cancelCalls == 1) throw IllegalStateException("synthetic cleanup failure")
            }
        }
        val controller = AuthenticationController(port, { testScheduler.currentTime }, this)
        runCurrent()
        controller.cancelAuthentication()
        runCurrent()
        assertTrue(controller.state.value.isCleanupRequired)
        assertFalse(controller.state.value.isCleanupInProgress)
        assertEquals(AuthenticationMessage.CLEANUP_FAILED, controller.state.value.message)

        val username = "blocked-user".toCharArray()
        val password = "blocked-password".toCharArray()
        controller.submitSignIn(username, password)
        runCurrent()
        assertTrue(username.all { it == '\u0000' })
        assertTrue(password.all { it == '\u0000' })
        assertEquals(0, port.signInCalls)
        controller.dismissMessage()
        assertTrue(controller.state.value.isCleanupRequired)

        controller.cancelAuthentication()
        runCurrent()
        assertEquals(2, port.cancelCalls)
        assertFalse(controller.state.value.isCleanupRequired)
        assertNull(controller.state.value.message)
        controller.submitSignIn("allowed-user".toCharArray(), "allowed-password".toCharArray())
        runCurrent()
        assertEquals(1, port.signInCalls)
        assertEquals(AuthenticationDestination.CODE, controller.state.value.destination)
        controller.dispose()
    }

    @Test fun `out of stage input clears without changing state or calling adapter`() = runTest {
        val port = ScriptedPort()
        val controller = AuthenticationController(port, { testScheduler.currentTime }, this)
        runCurrent()
        val unexpectedCode = "out-of-stage-code".toCharArray()
        val unexpectedPassword = "out-of-stage-password".toCharArray()
        controller.submitCode(unexpectedCode)
        controller.submitPassword(unexpectedPassword)
        runCurrent()
        assertTrue(unexpectedCode.all { it == '\u0000' })
        assertTrue(unexpectedPassword.all { it == '\u0000' })
        assertEquals(0, port.codeCalls)
        assertEquals(0, port.unlockCalls)
        assertEquals(AuthenticationDestination.SIGN_IN, controller.state.value.destination)

        controller.submitSignIn("first-user".toCharArray(), "first-password".toCharArray())
        runCurrent()
        val replacementUser = "replacement-user".toCharArray()
        val replacementPassword = "replacement-password".toCharArray()
        controller.submitSignIn(replacementUser, replacementPassword)
        runCurrent()
        assertTrue(replacementUser.all { it == '\u0000' })
        assertTrue(replacementPassword.all { it == '\u0000' })
        assertEquals(1, port.signInCalls)
        assertEquals(AuthenticationDestination.CODE, controller.state.value.destination)
        controller.dispose()
    }

    private open class ScriptedPort : AuthenticationFlowPort {
        var signInCalls = 0
        var codeCalls = 0
        var unlockCalls = 0
        var cancelCalls = 0
        override suspend fun restore(): AuthenticationStep = AuthenticationStep.SignIn
        override suspend fun signIn(username: AuthenticationSecret, password: AuthenticationSecret): AuthenticationStep {
            signInCalls += 1
            username.close()
            password.close()
            return AuthenticationStep.CodeRequired
        }
        override suspend fun submitCode(code: AuthenticationSecret): AuthenticationStep {
            codeCalls += 1
            code.close()
            return AuthenticationStep.PasswordRequired(PasswordPurpose.MAILBOX)
        }
        override suspend fun unlock(password: AuthenticationSecret): AuthenticationStep {
            password.close()
            unlockCalls += 1
            return AuthenticationStep.Authenticated
        }
        override suspend fun cancel() { cancelCalls += 1 }
    }
}
