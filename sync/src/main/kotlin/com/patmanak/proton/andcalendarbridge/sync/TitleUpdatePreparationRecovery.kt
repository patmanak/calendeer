// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Internal preparation composition only. The caller supplies current owner qualification
 * and an epoch-bound lease; this helper cannot inspect Android or grant dispatch authority.
 */
class TitleUpdatePreparationRecovery(private val journal: TitleUpdatePreparationJournal,
    private val port: CalendarTitleUpdateSubmissionPort) {
    suspend fun prepare(candidate: TitleUpdatePreparationReservation, allowNetwork: Boolean = false,
        admit: (CalendarScope) -> Boolean): TitleUpdateAttemptRecord? {
        val frozen = candidate.frozen()
        val lease = { scope: CalendarScope -> scope == frozen.scope &&
            port.titleUpdateFormat == frozen.format && admit(scope) }
        suspend fun active(): Boolean {
            currentCoroutineContext().ensureActive()
            val permitted = lease(frozen.scope)
            currentCoroutineContext().ensureActive()
            return permitted
        }
        fun qualified(record: TitleUpdateAttemptRecord?, reservation: TitleUpdatePreparationReservation): TitleUpdateAttemptRecord? =
            record?.frozen()?.takeIf { it.state == TitleUpdateAttemptState.PREPARED &&
                it.protectedEvidence == null && reservation.matches(it) }
        try {
            if (!active()) return null
            val reserved = journal.reservePreparation(frozen, lease)
            if (!active()) return null
            val canonical = reserved?.frozen() ?: return null
            if (canonical.scope != frozen.scope || canonical.preparation != frozen.preparation ||
                canonical.format != frozen.format) return null
            val existing = journal.read(canonical.scope, canonical.id)
            if (!active()) return null
            if (existing != null) return qualified(existing, canonical)
            if (!allowNetwork) return null
            val prepared = port.prepareSubmission(canonical, lease)
            if (!active()) return null
            val wire = qualified(prepared, canonical) ?: return null
            val retained = journal.remember(wire, lease)
            if (!active()) return null
            // A false retention can mean another helper won with different randomized
            // ciphertext. Only explicit durable readback may supply that winning wire.
            val durable = journal.read(canonical.scope, canonical.id)
            if (!active()) return null
            val observed = qualified(durable, canonical)
            if (retained && observed != wire) return null
            return observed
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) { currentCoroutineContext().ensureActive(); throw failure }
    }
}
