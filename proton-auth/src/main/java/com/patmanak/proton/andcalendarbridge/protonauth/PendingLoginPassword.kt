// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Patmanak
// Adapted from Contako; see proton-auth/PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.protonauth.OperationSecret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.proton.core.network.domain.session.SessionId

/** One login attempt, memory only. Neither the password nor the session is diagnostic data. */
internal class PendingLoginPassword(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private var value: Pair<SessionId, OperationSecret>? = null
    private var expiry: Job? = null

    @Synchronized
    fun retain(session: SessionId, password: CharArray) {
        clear()
        val retained = OperationSecret.takeAndClear(password.copyOf())
        value = session to retained
        expiry = scope.launch {
            delay(5 * 60 * 1_000L)
            synchronized(this@PendingLoginPassword) {
                if (value?.second === retained) clear()
            }
        }
    }

    @Synchronized
    fun take(session: SessionId): OperationSecret? {
        val retained = value
        value = null
        expiry?.cancel()
        expiry = null
        return if (retained?.first == session) retained.second else {
            retained?.second?.close()
            null
        }
    }

    @Synchronized
    fun clear() {
        value?.second?.close()
        value = null
        expiry?.cancel()
        expiry = null
    }
}
