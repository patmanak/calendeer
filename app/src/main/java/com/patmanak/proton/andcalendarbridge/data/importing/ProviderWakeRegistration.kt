// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ProviderWakeState(
    val enabled: Boolean = false,
    val watchToken: String? = null,
    val pendingToken: String? = null,
    val fingerprint: String? = null,
) {
    override fun toString(): String = "ProviderWakeState(REDACTED, enabled=$enabled)"
}

internal interface ProviderWakeStateStore {
    fun read(): ProviderWakeState

    /** Commit durably before returning; throw if the commit fails. */
    fun write(state: ProviderWakeState)
}

internal interface ProviderWakeBackend {
    /** Persist an independent registration, idempotently, under this unique token. */
    suspend fun arm(token: String)
    suspend fun cancelAll()
}

/**
 * Durable registration and pending-signal handoff, independent of calendar admissions.
 * Use one instance per store in a process. The token supplier must return fresh tokens.
 * Backend calls stay inside the lock so a suspended arm cannot outlive a disable.
 */
internal class ProviderWakeRegistration(
    private val store: ProviderWakeStateStore,
    private val backend: ProviderWakeBackend,
    private val newToken: () -> String,
) {
    private val mutex = Mutex()

    suspend fun setEnabled(enabled: Boolean): Unit = mutex.withLock {
        if (!enabled) {
            store.write(ProviderWakeState())
            backend.cancelAll()
        } else {
            armCurrent()
        }
    }

    suspend fun onTrigger(token: String): ProviderWakeState = mutex.withLock {
        var current = store.read()
        if (current.enabled && current.watchToken == token) {
            val successor = newToken()
            current = current.copy(watchToken = successor, pendingToken = successor)
            store.write(current)
        }
        if (current.enabled) armCurrent() else current
    }

    suspend fun ensureArmed(): ProviderWakeState = mutex.withLock {
        val current = store.read()
        if (current.enabled) armCurrent() else current
    }

    suspend fun snapshot(): ProviderWakeState = mutex.withLock { store.read() }

    suspend fun acknowledge(pendingToken: String, fingerprint: String?): Boolean = mutex.withLock {
        val current = store.read()
        if (!current.enabled || current.pendingToken != pendingToken) return@withLock false
        store.write(current.copy(pendingToken = null, fingerprint = fingerprint))
        true
    }

    /** Caller holds [mutex]. Persistence precedes every newly allocated registration. */
    private suspend fun armCurrent(): ProviderWakeState {
        var current = store.read()
        if (!current.enabled || current.watchToken == null) {
            val token = newToken()
            current = current.copy(enabled = true, watchToken = token, pendingToken = token)
            store.write(current)
        }
        backend.arm(checkNotNull(current.watchToken))
        return current
    }
}
