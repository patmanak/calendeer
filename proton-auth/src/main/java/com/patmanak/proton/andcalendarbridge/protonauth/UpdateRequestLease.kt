// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** Local admission lifetime shared by the typed text/time adapters, without session or IO access. */
internal class UpdateRequestLease(val scope: CalendarScope, private val admit: (CalendarScope) -> Boolean,
    private val operation: CoroutineContext) {
    class Closed : RuntimeException()
    private val closed = AtomicBoolean()
    fun allows(requested: CalendarScope): Boolean {
        if (requested != scope || closed.get()) return false
        operation.ensureActive()
        val allowed = try { admit(requested) }
        catch (failure: Exception) { closed.set(true); operation.ensureActive(); throw failure }
        if (!allowed) closed.set(true)
        operation.ensureActive()
        return allowed && !closed.get()
    }
    fun check() { operation.ensureActive(); if (!allows(scope)) throw Closed() }
    fun close() { closed.set(true) }
}
