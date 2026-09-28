// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Patmanak
// Adapted from Contako; see auth/PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.auth

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AuthenticationDestination {
    RESTORING,
    SIGN_IN,
    CODE,
    PASSWORD,
    SECURITY_KEY_UNSUPPORTED,
    HUMAN_VERIFICATION_UNAVAILABLE,
    AUTHENTICATED,
}

enum class AuthenticationMessage {
    INPUT_REQUIRED,
    REJECTED,
    ACCOUNT_ALREADY_CONNECTED,
    OFFLINE,
    TIMED_OUT,
    RATE_LIMITED,
    CRYPTOGRAPHIC_FAILURE,
    CLIENT_UNSUPPORTED,
    CLEANUP_FAILED,
    TRY_AGAIN,
}

data class AuthenticationState(
    val destination: AuthenticationDestination = AuthenticationDestination.RESTORING,
    val passwordPurpose: PasswordPurpose? = null,
    val isSubmitting: Boolean = false,
    val isCleanupInProgress: Boolean = false,
    /** New credentials are forbidden until cancelAuthentication completes successfully. */
    val isCleanupRequired: Boolean = false,
    val isRetryBlocked: Boolean = false,
    val retryAfterMillis: Long? = null,
    val retryBlockedUntilElapsedRealtimeMillis: Long? = null,
    val message: AuthenticationMessage? = null,
)

fun interface AuthenticationMonotonicClock {
    fun elapsedRealtimeMillis(): Long
}


/**
 * Authentication state holder extracted from Contako's ViewModel without Android dependencies.
 * Call methods and run the injected scope on one serialized dispatcher (for Android, Main).
 * The owner MUST call dispose before cancelling that scope. The clock MUST be monotonic,
 * nonnegative milliseconds. Mutable arrays crossing this boundary are always cleared, including
 * duplicate and rejected input. Secret values never enter observable or saved state.
 * Authenticated only describes identity/key unlock, not a usable Calendar synchronization session.
 */
class AuthenticationController(
    private val port: AuthenticationFlowPort,
    private val monotonicClock: AuthenticationMonotonicClock,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    private val mutableState = MutableStateFlow(AuthenticationState())
    val state: StateFlow<AuthenticationState> = mutableState.asStateFlow()

    private val operationAdmitted = AtomicBoolean(false)
    private var operationRevision = 0L
    private var activeOperation: Job? = null
    private var retryReleaseJob: Job? = null
    private var activeSecrets: List<AuthenticationSecret> = emptyList()
    private var disposed = false

    init {
        launchOperation(AuthenticationDestination.RESTORING) { port.restore() }
    }

    fun submitSignIn(username: CharArray, password: CharArray) {
        val revision = admitForDestinationOrClear(AuthenticationDestination.SIGN_IN, username, password) ?: return
        if (username.none { !it.isWhitespace() } || password.isEmpty()) {
            username.clearSecret()
            password.clearSecret()
            rejectLocal(revision, AuthenticationDestination.SIGN_IN)
            return
        }

        val ownedUsername = try {
            AuthenticationSecret.takeAndClear(username)
        } catch (_: IllegalArgumentException) {
            password.clearSecret()
            rejectLocal(revision, AuthenticationDestination.SIGN_IN)
            return
        }
        val ownedPassword = try {
            AuthenticationSecret.takeAndClear(password)
        } catch (_: IllegalArgumentException) {
            ownedUsername.close()
            rejectLocal(revision, AuthenticationDestination.SIGN_IN)
            return
        }
        launchAdmittedOperation(
            revision = revision,
            destination = AuthenticationDestination.SIGN_IN,
            secrets = listOf(ownedUsername, ownedPassword),
            rejectSilentSignInResult = true,
        ) {
            port.signIn(ownedUsername, ownedPassword)
        }
    }

    fun submitCode(code: CharArray) {
        val revision = admitForDestinationOrClear(AuthenticationDestination.CODE, code) ?: return
        if (code.isEmpty() || code.size > MAX_CODE_LENGTH) {
            code.clearSecret()
            rejectLocal(revision, AuthenticationDestination.CODE)
            return
        }
        val owned = try {
            AuthenticationSecret.takeAndClear(code)
        } catch (_: IllegalArgumentException) {
            rejectLocal(revision, AuthenticationDestination.CODE)
            return
        }
        launchAdmittedOperation(
            revision = revision,
            destination = AuthenticationDestination.CODE,
            secrets = listOf(owned),
        ) {
            port.submitCode(owned)
        }
    }

    fun submitPassword(password: CharArray) {
        val revision = admitForDestinationOrClear(AuthenticationDestination.PASSWORD, password) ?: return
        val purpose = mutableState.value.passwordPurpose
        if (password.isEmpty() || purpose == null) {
            password.clearSecret()
            rejectLocal(revision, AuthenticationDestination.PASSWORD, purpose)
            return
        }
        val owned = try {
            AuthenticationSecret.takeAndClear(password)
        } catch (_: IllegalArgumentException) {
            rejectLocal(revision, AuthenticationDestination.PASSWORD, purpose)
            return
        }
        launchAdmittedOperation(
            revision = revision,
            destination = AuthenticationDestination.PASSWORD,
            passwordPurpose = purpose,
            secrets = listOf(owned),
        ) {
            port.unlock(owned)
        }
    }

    fun cancelAuthentication() {
        if (disposed || mutableState.value.isCleanupInProgress) return
        val preservedRetryDeadline = mutableState.value
            .takeIf(AuthenticationState::isRetryBlocked)
            ?.retryBlockedUntilElapsedRealtimeMillis
        operationRevision += 1
        val revision = operationRevision
        activeSecrets.forEach(AuthenticationSecret::close)
        activeSecrets = emptyList()
        activeOperation?.cancel()
        if (preservedRetryDeadline == null) {
            retryReleaseJob?.cancel()
            retryReleaseJob = null
        } else {
            scheduleRetryRelease(preservedRetryDeadline)
        }
        operationAdmitted.set(true)
        mutableState.value = mutableState.value.copy(
            destination = AuthenticationDestination.SIGN_IN,
            passwordPurpose = null,
            isSubmitting = true,
            isCleanupInProgress = true,
            isCleanupRequired = true,
            message = null,
        )
        activeOperation = scope.launch {
            var cleanupSucceeded = false
            try {
                port.cancel()
                cleanupSucceeded = true
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Keep credential admission blocked until an explicit cleanup retry succeeds.
            } finally {
                if (revision == operationRevision) {
                    operationAdmitted.set(false)
                    mutableState.value = mutableState.value.copy(
                        destination = AuthenticationDestination.SIGN_IN,
                        passwordPurpose = null,
                        isSubmitting = false,
                        isCleanupInProgress = false,
                        isCleanupRequired = !cleanupSucceeded,
                        message = if (cleanupSucceeded) null else AuthenticationMessage.CLEANUP_FAILED,
                    )
                }
            }
        }
    }

    fun signOut() = cancelAuthentication()

    fun dismissMessage() {
        if (disposed) return
        mutableState.value = mutableState.value.copy(message = null)
    }

    /** Clears transient inputs and invalidates the adapter attempt before the owning scope stops. */
    suspend fun dispose() {
        if (disposed) return
        disposed = true
        operationRevision += 1
        activeSecrets.forEach(AuthenticationSecret::close)
        activeSecrets = emptyList()
        activeOperation?.cancel()
        retryReleaseJob?.cancel()
        mutableState.value = AuthenticationState(AuthenticationDestination.SIGN_IN)
        // The caller must observe cleanup failures; no success state is published on failure.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { port.cancel() }
    }

    private fun launchOperation(
        destination: AuthenticationDestination,
        block: suspend () -> AuthenticationStep,
    ) {
        val revision = admitOrClear() ?: return
        launchAdmittedOperation(revision, destination, block = block)
    }

    private fun launchAdmittedOperation(
        revision: Long,
        destination: AuthenticationDestination,
        passwordPurpose: PasswordPurpose? = null,
        secrets: List<AuthenticationSecret> = emptyList(),
        rejectSilentSignInResult: Boolean = false,
        block: suspend () -> AuthenticationStep,
    ) {
        mutableState.value = AuthenticationState(
            destination = destination,
            passwordPurpose = passwordPurpose,
            isSubmitting = true,
        )
        activeSecrets = secrets
        activeOperation = scope.launch {
            try {
                val returnedStep = block()
                val step = if (rejectSilentSignInResult && returnedStep == AuthenticationStep.SignIn) {
                    // An explicit credential submission MUST never return silently to the welcome
                    // screen. Restore legitimately uses SignIn to represent "no retained session",
                    // but the same result after submit is a closed authentication rejection.
                    AuthenticationStep.Failure(AuthenticationFailure.REJECTED)
                } else {
                    returnedStep
                }
                if (revision == operationRevision) applyStep(step)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (revision == operationRevision) {
                    applyStep(AuthenticationStep.Failure(AuthenticationFailure.TRY_AGAIN))
                }
            } finally {
                secrets.forEach(AuthenticationSecret::close)
                if (revision == operationRevision) {
                    activeSecrets = emptyList()
                    operationAdmitted.set(false)
                    mutableState.value = mutableState.value.copy(isSubmitting = false)
                }
            }
        }
    }

    private fun applyStep(step: AuthenticationStep) {
        if (step !is AuthenticationStep.Failure) clearRetryBlock()
        mutableState.value = when (step) {
            AuthenticationStep.SignIn -> AuthenticationState(AuthenticationDestination.SIGN_IN)
            AuthenticationStep.CodeRequired -> AuthenticationState(AuthenticationDestination.CODE)
            is AuthenticationStep.PasswordRequired -> AuthenticationState(
                destination = AuthenticationDestination.PASSWORD,
                passwordPurpose = step.purpose,
            )
            AuthenticationStep.SecurityKeyOnlyUnsupported ->
                AuthenticationState(AuthenticationDestination.SECURITY_KEY_UNSUPPORTED)
            AuthenticationStep.HumanVerificationUnavailable ->
                AuthenticationState(AuthenticationDestination.HUMAN_VERIFICATION_UNAVAILABLE)
            AuthenticationStep.Authenticated -> AuthenticationState(AuthenticationDestination.AUTHENTICATED)
            is AuthenticationStep.Failure -> failureState(step)
        }
    }

    private fun failureState(step: AuthenticationStep.Failure): AuthenticationState {
        val current = mutableState.value
        if (step.category != AuthenticationFailure.RATE_LIMITED && step.retryAfterMillis == null) {
            clearRetryBlock()
            return AuthenticationState(
                destination = failureDestination(current.destination),
                passwordPurpose = current.passwordPurpose,
                message = step.category.toMessage(),
            )
        }

        val retryAfterMillis = (step.retryAfterMillis ?: DEFAULT_RATE_LIMIT_DELAY_MILLIS)
            .coerceAtLeast(MIN_RATE_LIMIT_DELAY_MILLIS)
        val now = monotonicClock.elapsedRealtimeMillis()
        val deadline = if (Long.MAX_VALUE - now < retryAfterMillis) {
            Long.MAX_VALUE
        } else {
            now + retryAfterMillis
        }
        scheduleRetryRelease(deadline)
        return AuthenticationState(
            destination = failureDestination(current.destination),
            passwordPurpose = current.passwordPurpose,
            isRetryBlocked = true,
            retryAfterMillis = retryAfterMillis,
            retryBlockedUntilElapsedRealtimeMillis = deadline,
            message = step.category.toMessage(),
        )
    }

    private fun scheduleRetryRelease(deadline: Long) {
        retryReleaseJob?.cancel()
        val revision = operationRevision
        retryReleaseJob = scope.launch {
            var remaining = (deadline - monotonicClock.elapsedRealtimeMillis()).coerceAtLeast(0)
            while (remaining > 0) {
                delay(minOf(remaining, MAX_RATE_LIMIT_TIMER_SLICE_MILLIS))
                remaining = (deadline - monotonicClock.elapsedRealtimeMillis()).coerceAtLeast(0)
            }
            if (revision == operationRevision) {
                mutableState.value = mutableState.value.copy(
                    isRetryBlocked = false,
                    retryAfterMillis = null,
                    retryBlockedUntilElapsedRealtimeMillis = null,
                )
            }
        }
    }

    private fun clearRetryBlock() {
        retryReleaseJob?.cancel()
        retryReleaseJob = null
    }

    private fun failureDestination(current: AuthenticationDestination): AuthenticationDestination = when (current) {
        AuthenticationDestination.CODE -> AuthenticationDestination.CODE
        AuthenticationDestination.PASSWORD -> AuthenticationDestination.PASSWORD
        else -> AuthenticationDestination.SIGN_IN
    }

    private fun AuthenticationFailure.toMessage(): AuthenticationMessage = when (this) {
        AuthenticationFailure.REJECTED -> AuthenticationMessage.REJECTED
        AuthenticationFailure.ACCOUNT_ALREADY_CONNECTED -> AuthenticationMessage.ACCOUNT_ALREADY_CONNECTED
        AuthenticationFailure.OFFLINE -> AuthenticationMessage.OFFLINE
        AuthenticationFailure.TIMED_OUT -> AuthenticationMessage.TIMED_OUT
        AuthenticationFailure.RATE_LIMITED -> AuthenticationMessage.RATE_LIMITED
        AuthenticationFailure.CRYPTOGRAPHIC_FAILURE -> AuthenticationMessage.CRYPTOGRAPHIC_FAILURE
        AuthenticationFailure.CLIENT_UNSUPPORTED -> AuthenticationMessage.CLIENT_UNSUPPORTED
        AuthenticationFailure.CANCELLED,
        AuthenticationFailure.TRY_AGAIN,
        -> AuthenticationMessage.TRY_AGAIN
    }

    private fun admitForDestinationOrClear(
        destination: AuthenticationDestination,
        vararg sources: CharArray,
    ): Long? {
        if (mutableState.value.destination != destination) {
            sources.forEach { it.clearSecret() }
            return null
        }
        return admitOrClear(*sources)
    }

    private fun admitOrClear(vararg sources: CharArray): Long? {
        if (disposed || mutableState.value.isCleanupRequired) {
            sources.forEach { it.clearSecret() }
            return null
        }
        if (mutableState.value.isRetryBlocked) {
            sources.forEach { it.clearSecret() }
            return null
        }
        if (!operationAdmitted.compareAndSet(false, true)) {
            sources.forEach { it.clearSecret() }
            return null
        }
        operationRevision += 1
        return operationRevision
    }

    private fun rejectLocal(
        revision: Long,
        destination: AuthenticationDestination,
        purpose: PasswordPurpose? = null,
    ) {
        if (revision == operationRevision) {
            operationAdmitted.set(false)
            mutableState.value = AuthenticationState(
                destination = destination,
                passwordPurpose = purpose,
                message = AuthenticationMessage.INPUT_REQUIRED,
            )
        }
    }

    private fun CharArray.clearSecret() = fill('\u0000')

    companion object {
        private const val MAX_CODE_LENGTH = 128
        private const val MIN_RATE_LIMIT_DELAY_MILLIS = 1_000L
        private const val DEFAULT_RATE_LIMIT_DELAY_MILLIS = 30_000L
        private const val MAX_RATE_LIMIT_TIMER_SLICE_MILLIS = 15 * 60_000L

    }
}
