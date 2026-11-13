// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared authentication mutex and original operation leases; no network or ownership policy. */
internal class SerializedLocalPreparations(
    private val dispatcher: CoroutineDispatcher,
    private val lock: Mutex,
    private val isAuthenticated: () -> Boolean,
    private val currentGraph: () -> ProtonAuthenticationGraph?,
) : CalendarLocalTimePreparationPort, CalendarPersonalPreparationPort, CalendarLocalTextPreparationPort {
    override val localTimeFormat = LOCAL_TIME_PREPARATION_FORMAT
    override val personalFormat = PERSONAL_PREPARATION_FORMAT
    override val localTextFormat = LOCAL_TEXT_PREPARATION_FORMAT
    private val authenticated get() = isAuthenticated()
    private val graph get() = currentGraph()

    override suspend fun prepareLocalTime(intent: LocalEditIntent, timestamp: java.time.Instant): LocalTimeEditPreparation {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        return try {
            // Freeze before dispatcher switching or waiting for the shared logout mutex.
            val frozen = freezeLocalTimeRequest(intent, timestamp)
            val expectedFormat = localTimeFormat
            withContext(dispatcher) {
                val operation = currentCoroutineContext()
                try {
                    lock.withLock {
                        operation.ensureActive()
                        if (!authenticated) localEditUnavailable(LocalEditPreparationFailure.AUTHENTICATION)
                        val runtime = graph?.localTimes ?: localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED)
                        if (runtime.localTimeFormat != expectedFormat)
                            localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED)
                        val result = runtime.prepareLocalTime(frozen, timestamp)
                        operation.ensureActive()
                        if (!authenticated || runtime.localTimeFormat != expectedFormat || result.intent != frozen ||
                            result.timestamp != timestamp || result.format != expectedFormat)
                            localEditUnavailable(LocalEditPreparationFailure.CONTEXT)
                        LocalTimeEditPreparation(frozen, timestamp, expectedFormat, result.protectedPayload)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (failure: Exception) { operation.ensureActive(); throw failure }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { caller.ensureActive(); throw failure }
    }

    override suspend fun prepareLocalTime(intent: LocalEditIntent, timestamp: java.time.Instant,
        admit: (CalendarScope) -> Boolean): LocalTimeEditPreparation? {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val closed = AtomicBoolean(false)
        return try {
            val frozen = freezeLocalTimeRequest(intent, timestamp)
            val expectedFormat = localTimeFormat
            val lease = { scope: CalendarScope ->
                if (scope != frozen.scope || closed.get()) false else {
                    val allowed = localTimeFormat == expectedFormat && admit(scope)
                    if (!allowed) closed.set(true)
                    allowed && !closed.get()
                }
            }
            suspend fun active(): Boolean {
                currentCoroutineContext().ensureActive()
                val allowed = lease(frozen.scope)
                currentCoroutineContext().ensureActive()
                return allowed
            }
            if (!active()) return null
            withContext(dispatcher) {
                val operation = currentCoroutineContext()
                try {
                    if (!active()) return@withContext null
                    lock.withLock {
                        operation.ensureActive()
                        if (!authenticated || !active()) return@withLock null
                        val runtime = graph?.localTimes ?: return@withLock null
                        if (runtime.localTimeFormat != expectedFormat) return@withLock null
                        val runtimeLease = { scope: CalendarScope ->
                            if (scope != frozen.scope || closed.get()) false else {
                                operation.ensureActive()
                                val allowed = authenticated && runtime.localTimeFormat == expectedFormat && lease(scope)
                                if (!allowed) closed.set(true)
                                operation.ensureActive()
                                allowed && !closed.get()
                            }
                        }
                        // Guarded entry never falls back to the unrestricted preparation overload.
                        val result = runtime.prepareLocalTime(frozen, timestamp, runtimeLease)
                        operation.ensureActive()
                        if (!active() || !authenticated || runtime.localTimeFormat != expectedFormat) return@withLock null
                        result?.takeIf { it.intent == frozen && it.timestamp == timestamp && it.format == expectedFormat }
                            ?.let { LocalTimeEditPreparation(frozen, timestamp, expectedFormat, it.protectedPayload) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (failure: Exception) { operation.ensureActive(); throw failure }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { caller.ensureActive(); throw failure }
          finally { closed.set(true) }
    }

    override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: java.time.Instant): PersonalEditPreparation {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        return try {
            // Freeze before dispatcher switching or waiting for the shared logout mutex.
            val frozen = freezePersonalRequest(intent, timestamp)
            val expectedFormat = personalFormat
            withContext(dispatcher) {
                val operation = currentCoroutineContext()
                try {
                    lock.withLock {
                        operation.ensureActive()
                        if (!authenticated) localEditUnavailable(LocalEditPreparationFailure.AUTHENTICATION)
                        val runtime = graph?.personals ?: localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED)
                        if (runtime.personalFormat != expectedFormat)
                            localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED)
                        val result = runtime.preparePersonal(frozen, timestamp)
                        operation.ensureActive()
                        if (!authenticated || runtime.personalFormat != expectedFormat || result.intent != frozen ||
                            result.timestamp != timestamp || result.format != expectedFormat)
                            localEditUnavailable(LocalEditPreparationFailure.CONTEXT)
                        PersonalEditPreparation(frozen, timestamp, expectedFormat, result.protectedPayload)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (failure: Exception) { operation.ensureActive(); throw failure }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { caller.ensureActive(); throw failure }
    }

    override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: java.time.Instant,
        admit: (CalendarScope) -> Boolean): PersonalEditPreparation? {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val closed = AtomicBoolean(false)
        return try {
            val frozen = freezePersonalRequest(intent, timestamp)
            val expectedFormat = personalFormat
            val lease = { scope: CalendarScope ->
                if (scope != frozen.scope || closed.get()) false else {
                    val allowed = personalFormat == expectedFormat && admit(scope)
                    if (!allowed) closed.set(true)
                    allowed && !closed.get()
                }
            }
            suspend fun active(): Boolean {
                currentCoroutineContext().ensureActive()
                val allowed = lease(frozen.scope)
                currentCoroutineContext().ensureActive()
                return allowed
            }
            if (!active()) return null
            withContext(dispatcher) {
                val operation = currentCoroutineContext()
                try {
                    if (!active()) return@withContext null
                    lock.withLock {
                        operation.ensureActive()
                        if (!authenticated || !active()) return@withLock null
                        val runtime = graph?.personals ?: return@withLock null
                        if (runtime.personalFormat != expectedFormat) return@withLock null
                        val runtimeLease = { scope: CalendarScope ->
                            if (scope != frozen.scope || closed.get()) false else {
                                operation.ensureActive()
                                val allowed = authenticated && runtime.personalFormat == expectedFormat && lease(scope)
                                if (!allowed) closed.set(true)
                                operation.ensureActive()
                                allowed && !closed.get()
                            }
                        }
                        // Guarded entry never falls back to the unrestricted preparation overload.
                        val result = runtime.preparePersonal(frozen, timestamp, runtimeLease)
                        operation.ensureActive()
                        if (!active() || !authenticated || runtime.personalFormat != expectedFormat) return@withLock null
                        result?.takeIf { it.intent == frozen && it.timestamp == timestamp && it.format == expectedFormat }
                            ?.let { PersonalEditPreparation(frozen, timestamp, expectedFormat, it.protectedPayload) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (failure: Exception) { operation.ensureActive(); throw failure }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { caller.ensureActive(); throw failure }
          finally { closed.set(true) }
    }

    override suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: java.time.Instant): LocalTextEditPreparation {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        return try {
            // Freeze before dispatcher switching or waiting for the shared logout mutex.
            val frozen = freezeLocalTextRequest(intent, timestamp)
            val expectedFormat = localTextFormat
            withContext(dispatcher) {
                val operation = currentCoroutineContext()
                try {
                    lock.withLock {
                        operation.ensureActive()
                        if (!authenticated) localEditUnavailable(LocalEditPreparationFailure.AUTHENTICATION)
                        val runtime = graph?.localTexts ?: localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED)
                        if (runtime.localTextFormat != expectedFormat)
                            localEditUnavailable(LocalEditPreparationFailure.UNSUPPORTED)
                        val result = runtime.prepareLocalText(frozen, timestamp)
                        operation.ensureActive()
                        if (!authenticated || runtime.localTextFormat != expectedFormat || result.intent != frozen ||
                            result.timestamp != timestamp || result.format != expectedFormat)
                            localEditUnavailable(LocalEditPreparationFailure.CONTEXT)
                        LocalTextEditPreparation(frozen, timestamp, expectedFormat, result.protectedPayload)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (failure: Exception) { operation.ensureActive(); throw failure }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { caller.ensureActive(); throw failure }
    }

    override suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: java.time.Instant,
        admit: (CalendarScope) -> Boolean): LocalTextEditPreparation? {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val closed = AtomicBoolean(false)
        return try {
            val frozen = freezeLocalTextRequest(intent, timestamp)
            val expectedFormat = localTextFormat
            val lease = { scope: CalendarScope ->
                if (scope != frozen.scope || closed.get()) false else {
                    val allowed = localTextFormat == expectedFormat && admit(scope)
                    if (!allowed) closed.set(true)
                    allowed && !closed.get()
                }
            }
            suspend fun active(): Boolean {
                currentCoroutineContext().ensureActive()
                val allowed = lease(frozen.scope)
                currentCoroutineContext().ensureActive()
                return allowed
            }
            if (!active()) return null
            withContext(dispatcher) {
                val operation = currentCoroutineContext()
                try {
                    if (!active()) return@withContext null
                    lock.withLock {
                        operation.ensureActive()
                        if (!authenticated || !active()) return@withLock null
                        val runtime = graph?.localTexts ?: return@withLock null
                        if (runtime.localTextFormat != expectedFormat) return@withLock null
                        val runtimeLease = { scope: CalendarScope ->
                            if (scope != frozen.scope || closed.get()) false else {
                                operation.ensureActive()
                                val allowed = authenticated && runtime.localTextFormat == expectedFormat && lease(scope)
                                if (!allowed) closed.set(true)
                                operation.ensureActive()
                                allowed && !closed.get()
                            }
                        }
                        // Guarded entry never falls back to the unrestricted preparation overload.
                        val result = runtime.prepareLocalText(frozen, timestamp, runtimeLease)
                        operation.ensureActive()
                        if (!active() || !authenticated || runtime.localTextFormat != expectedFormat) return@withLock null
                        result?.takeIf { it.intent == frozen && it.timestamp == timestamp && it.format == expectedFormat }
                            ?.let { LocalTextEditPreparation(frozen, timestamp, expectedFormat, it.protectedPayload) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (failure: Exception) { operation.ensureActive(); throw failure }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { caller.ensureActive(); throw failure }
          finally { closed.set(true) }
    }

}
