// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Inspect-only recovery with explicit network eligibility and a per-invocation access lease.
 * Default denial permits no reads or transport. A historical result needs no network; a new
 * observation is returned only after guarded durable retention and exact journal readback.
 * This path never prepares, claims, submits, acknowledges or completes native work.
 * The caller supplies a lease bound to its original session/policy epoch. Exceptions and
 * cancellation propagate; revocation after a commit may hide a proof that remains durable.
 */
class TitleUpdateGuardedResultRecovery(
    private val attempts: TitleUpdateAttemptJournal,
    private val results: TitleUpdateResultJournal,
    private val submission: CalendarTitleUpdateSubmissionPort,
) {
    /** The owner may additionally check asynchronous account and selection observations.
     * This check never replaces the synchronous epoch lease passed to transport and storage.
     */
    suspend fun recover(scope: CalendarScope, id: MutationId, allowNetwork: Boolean,
        admit: (CalendarScope) -> Boolean, checkAccess: suspend () -> Boolean): TitleUpdateVerifiedResult? {
        val lease = { target: CalendarScope -> target == scope && admit(scope) }
        suspend fun available(): Boolean {
            currentCoroutineContext().ensureActive()
            if (!lease(scope)) return false
            val observed = checkAccess()
            currentCoroutineContext().ensureActive()
            return observed && lease(scope)
        }
        if (!available()) return null
        val record = attempts.read(scope, id)?.frozen()
        if (!available() || record == null) return null
        check(record.scope == scope && record.id == id) { "Invalid title recovery work" }
        if (record.state != TitleUpdateAttemptState.OUTCOME_UNKNOWN) return null
        val historical = results.readVerified(scope, id)
        if (!available()) return null
        if (historical != null) {
            check(historical.record == record.copy(protectedEvidence = null)) { "Invalid title recovery history" }
            return historical
        }
        if (!allowNetwork || submission.titleUpdateFormat != record.format || !available()) return null
        val observation = submission.inspect(record, lease)
        if (!available() || observation !is TitleUpdateSubmissionObservation.Verified) return null
        val verified = TitleUpdateVerifiedResult.of(record, observation)
        if (!available()) return null
        val committed = results.retainVerified(verified, lease)
        if (!available() || !committed) return null
        val durable = results.readVerified(scope, id)
        if (!available()) return null
        check(durable == verified) { "Title recovery result is not durable" }
        return durable
    }

    suspend fun recover(scope: CalendarScope, id: MutationId, allowNetwork: Boolean = false,
        admit: (CalendarScope) -> Boolean = { false }): TitleUpdateVerifiedResult? =
        recover(scope, id, allowNetwork, admit) { true }
}
