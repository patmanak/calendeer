package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/** Lifetime and irreversible admission for one text/time submission invocation.
 * The caller supplies the original operation and captured account-evidence admission.
 * Source rejection does not revoke factual evidence; neither channel can outlive close(). */
internal class EditSubmissionLease(
    private val target: CalendarScope,
    private val operation: CoroutineContext,
    private val accountEvidence: (CalendarScope) -> Boolean,
    private val admitEvidence: (CalendarScope) -> Boolean,
) {
    private val open = AtomicBoolean(true)
    private val sourceClosed = AtomicBoolean(false)
    private val evidenceClosed = AtomicBoolean(false)

    val evidence: (CalendarScope) -> Boolean = { requested ->
        operation.ensureActive()
        val allowed = open.get() && !evidenceClosed.get() && requested == target &&
            accountEvidence(requested) && admitEvidence(requested)
        operation.ensureActive()
        if (!allowed) evidenceClosed.set(true)
        allowed && !evidenceClosed.get() && open.get()
    }

    fun sending(active: () -> Boolean, formatMatches: () -> Boolean): (CalendarScope) -> Boolean = { requested ->
        operation.ensureActive()
        val allowed = open.get() && !sourceClosed.get() && requested == target && active() && formatMatches()
        operation.ensureActive()
        if (!allowed) sourceClosed.set(true)
        allowed && !sourceClosed.get() && open.get()
    }

    suspend fun source(requested: CalendarScope, sending: (CalendarScope) -> Boolean,
        qualify: suspend () -> Boolean): Boolean {
        operation.ensureActive()
        return try {
            val valid = sending(requested) && qualify() && sending(requested)
            if (!valid) sourceClosed.set(true)
            valid
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { operation.ensureActive(); sourceClosed.set(true); false }
    }

    fun close() {
        open.set(false); sourceClosed.set(true); evidenceClosed.set(true)
    }
}
