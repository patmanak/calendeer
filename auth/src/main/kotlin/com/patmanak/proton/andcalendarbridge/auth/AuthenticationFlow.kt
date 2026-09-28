// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Patmanak
// Adapted from Contako; see auth/PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.auth

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mutable one-shot authentication input owned by the active operation.
 *
 * Ownership transfer clears the caller's array immediately. Consumption and close both clear the
 * owned array, and the value is never exposed through equality, hashing, or toString.
 */
class AuthenticationSecret private constructor(private val value: CharArray) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    fun <T> consume(block: (CharArray) -> T): T {
        check(closed.compareAndSet(false, true))
        return try {
            block(value)
        } finally {
            value.fill('\u0000')
        }
    }

    suspend fun <T> consumeSuspend(block: suspend (CharArray) -> T): T {
        check(closed.compareAndSet(false, true))
        return try {
            block(value)
        } finally {
            value.fill('\u0000')
        }
    }

    override fun close() {
        // Consumption reserves the secret before a suspending adapter returns. Cancellation must
        // still clear that borrowed array immediately, including a non-cooperative late callback.
        closed.set(true)
        value.fill('\u0000')
    }

    override fun toString(): String = "AuthenticationSecret(REDACTED)"

    companion object {
        fun takeAndClear(source: CharArray): AuthenticationSecret {
            val owned = try {
                require(source.isNotEmpty())
                require(source.size <= MAX_SECRET_LENGTH)
                source.copyOf()
            } finally {
                source.fill('\u0000')
            }
            return AuthenticationSecret(owned)
        }

        private const val MAX_SECRET_LENGTH = 16_384
    }
}

enum class AuthenticationFailure {
    REJECTED,
    ACCOUNT_ALREADY_CONNECTED,
    OFFLINE,
    TIMED_OUT,
    RATE_LIMITED,
    CRYPTOGRAPHIC_FAILURE,
    CLIENT_UNSUPPORTED,
    CANCELLED,
    TRY_AGAIN,
}

enum class PasswordPurpose {
    MAILBOX,
    KEY_UNLOCK,
}

sealed interface AuthenticationStep {
    data object SignIn : AuthenticationStep
    data object CodeRequired : AuthenticationStep
    data class PasswordRequired(val purpose: PasswordPurpose) : AuthenticationStep
    data object SecurityKeyOnlyUnsupported : AuthenticationStep

    /**
     * Fail-closed UI boundary for a future maintained HV presentation contract.
     * No implementation may infer this state from an unknown transport or server failure.
     */
    data object HumanVerificationUnavailable : AuthenticationStep

    /** Identity and account keys are usable; Calendar access/key bootstrap is still separate. */
    data object Authenticated : AuthenticationStep
    data class Failure(
        val category: AuthenticationFailure,
        val retryAfterMillis: Long? = null,
    ) : AuthenticationStep {
        init {
            require(retryAfterMillis == null || retryAfterMillis >= 0)
        }
    }
}

/**
 * Application-owned authentication boundary; implementations consume secrets in-call and MUST NOT
 * retain passwords/codes after returning. An adapter that needs a bounded challenge-lifetime secret
 * owns that explicit lifetime and clears it on cancellation. Inputs never belong in logs/saved state.
 *
 * restore MUST clear unusable partial second-factor sessions before returning SignIn, and MUST
 * distinguish mailbox/key unlock from authenticated identity. Authenticated does not authorize
 * Calendar synchronization: Calendar-specific keys, access and capabilities need another boundary.
 *
 * cancel MUST invalidate the active attempt before clearing protected session/challenge state;
 * late callbacks MUST NOT recreate an invalidated session. It MUST preserve queued calendar work.
 * CancellationException propagates. Other failures should use the closed Failure categories and
 * retain any server retry delay. Human verification remains interactive inside the adapter; an
 * unavailable presentation uses HumanVerificationUnavailable, never a forged successful proof.
 */
interface AuthenticationFlowPort {
    suspend fun restore(): AuthenticationStep
    suspend fun signIn(username: AuthenticationSecret, password: AuthenticationSecret): AuthenticationStep
    suspend fun submitCode(code: AuthenticationSecret): AuthenticationStep
    suspend fun unlock(password: AuthenticationSecret): AuthenticationStep
    suspend fun cancel()
}
