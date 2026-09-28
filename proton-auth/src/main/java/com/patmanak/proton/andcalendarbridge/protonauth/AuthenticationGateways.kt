// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Patmanak
// Adapted from Contako; see proton-auth/PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth
import java.util.concurrent.atomic.AtomicBoolean
internal abstract class RedactedOpaqueValue internal constructor(
    internal val value: String,
    private val label: String,
) {
    init {
        require(value.isNotBlank())
        require(value.length <= MAX_OPAQUE_VALUE_LENGTH)
    }

    final override fun equals(other: Any?): Boolean =
        other != null && other::class == this::class && (other as RedactedOpaqueValue).value == value

    final override fun hashCode(): Int = 31 * this::class.hashCode() + value.hashCode()
    final override fun toString(): String = "$label(REDACTED)"

    private companion object {
        const val MAX_OPAQUE_VALUE_LENGTH = 4_096
    }
}

internal class AccountScope(value: String) : RedactedOpaqueValue(value, "AccountScope")
internal enum class GatewayFailureCategory {
    AUTHENTICATION_REQUIRED,
    ACCOUNT_ALREADY_CONNECTED,
    PERMISSION_OR_PLAN_DENIED,
    RATE_LIMITED,
    LOCAL_REQUEST_BUDGET_EXHAUSTED,
    NETWORK_UNAVAILABLE,
    TIMEOUT,
    CANCELLED,
    VALIDATION_REJECTED,
    CONFLICT,
    NOT_FOUND,
    CRYPTOGRAPHIC_VERIFICATION_FAILED,
    MALFORMED_RESPONSE,
    UNSUPPORTED_AUTHENTICATION,
    CLIENT_IDENTITY_REJECTED,
    REMOTE_SERVICE_FAILURE,
    HUMAN_VERIFICATION_REQUIRED,
    UNKNOWN,
}

internal sealed interface GatewayOutcome<out T> {
 data class Success<T>(val value:T): GatewayOutcome<T> { override fun toString()="GatewayOutcome.Success(REDACTED)" }
 data class Failure(val category:GatewayFailureCategory,val retryAfterMillis:Long?=null): GatewayOutcome<Nothing> {
  init { require(retryAfterMillis==null || retryAfterMillis>=0) }
 }
}
internal class OperationSecret private constructor(private val value: CharArray) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    internal fun <T> consume(block: (CharArray) -> T): T {
        check(closed.compareAndSet(false, true))
        return try {
            block(value)
        } finally {
            value.fill('\u0000')
        }
    }

    internal suspend fun <T> consumeSuspend(block: suspend (CharArray) -> T): T {
        check(closed.compareAndSet(false, true))
        return try {
            block(value)
        } finally {
            value.fill('\u0000')
        }
    }

    override fun close() {
        closed.set(true)
        value.fill('\u0000')
    }

    override fun toString(): String = "OperationSecret(REDACTED)"

    companion object {
        fun takeAndClear(source: CharArray): OperationSecret {
            val owned = try {
                require(source.isNotEmpty())
                require(source.size <= MAX_SECRET_LENGTH)
                source.copyOf()
            } finally {
                source.fill('\u0000')
            }
            return OperationSecret(owned)
        }

        private const val MAX_SECRET_LENGTH = 16_384
    }
}

internal enum class SecondFactorMethod { CODE, SECURITY_KEY }

internal sealed interface AuthenticationState {
    data object Ready : AuthenticationState
    class SecondFactorRequired private constructor(
        val methods: Set<SecondFactorMethod>,
    ) : AuthenticationState {
        override fun toString(): String = "SecondFactorRequired(methods=$methods)"

        companion object {
            fun fromOfferedMethods(methods: Set<SecondFactorMethod>): AuthenticationState {
                require(methods.isNotEmpty())
                return if (SecondFactorMethod.CODE in methods) {
                    SecondFactorRequired(methods.toSet())
                } else {
                    SecurityKeyOnlyUnsupported
                }
            }
        }
    }
    data object MailboxPasswordRequired : AuthenticationState
    data object KeyUnlockRequired : AuthenticationState
    data object SecurityKeyOnlyUnsupported : AuthenticationState
}

/** SRP login and code-capable second factor; implementations MUST consume secrets in-call only. */
internal interface ProtonAuthenticationGateway {
    suspend fun signIn(
        account: AccountScope,
        username: OperationSecret,
        password: OperationSecret,
    ): GatewayOutcome<AuthenticationState>

    suspend fun submitSecondFactor(
        account: AccountScope,
        code: OperationSecret,
    ): GatewayOutcome<AuthenticationState>

    suspend fun cancel(account: AccountScope)
}

internal enum class SessionState {
    READY,
    AUTHENTICATION_REQUIRED,
    INTERACTIVE_MAILBOX_PASSWORD_REQUIRED,
    INTERACTIVE_KEY_UNLOCK_REQUIRED,
    REVOKED,
}

/** Session material stays inside Proton Core's encrypted account state and never crosses this API. */
internal interface ProtonSessionGateway {
    suspend fun restore(account: AccountScope): GatewayOutcome<SessionState>
    suspend fun refresh(account: AccountScope): GatewayOutcome<SessionState>
    suspend fun unlockKeys(account: AccountScope, password: OperationSecret): GatewayOutcome<SessionState>
    suspend fun lockKeys(account: AccountScope): GatewayOutcome<Unit>
    suspend fun revokeAndClear(account: AccountScope): GatewayOutcome<Unit>
}

internal fun interface ProtonLocalSessionCleanupGateway {
    suspend fun clearLocal(account: AccountScope): GatewayOutcome<Unit>
}
