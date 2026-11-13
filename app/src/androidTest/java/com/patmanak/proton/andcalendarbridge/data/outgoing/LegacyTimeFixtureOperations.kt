// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Frozen old-version state construction for synthetic compatibility fixtures, not a production writer. */
internal class LegacyTimeFixtureOperations(
    imports: ImportCoordinator,
    private val journal: TimeUpdateRecoveryJournal,
    private val capsules: CalendarLocalTimePreparationPort,
    private val submissions: CalendarTimeUpdateSubmissionPort,
) : SystemTitleUpdateOperations {
    private val owners = imports.localTimeOwners
    private val evidenceAdmission = imports::systemTimeEvidenceAdmission
    private val current = CoordinatorSystemTimeOperations(imports, journal, submissions)
    private val existingOnly = false
    private val discoverLocal = imports::retainedLocalChanges
    override suspend fun discover(query: RetainedLocalChangeQuery) = discoverLocal(query)
    override suspend fun process(scope: CalendarScope, reference: RetainedLocalChangeRef, attempt: MutationId,
        timestamp: Instant, admission: (CalendarScope) -> Boolean, proofRetained: () -> Unit): Boolean {
        currentCoroutineContext().ensureActive()
        if (reference.kind != MutationKind.UPDATE || !admission(scope)) return false
        // The generic traversal's random candidate is deliberately not a durable time request ID.
        // A time ID derives from the first locally retained complete capsule and target format.
        var open = true
        var closed = false
        val lease: (CalendarScope) -> Boolean = { requested ->
            if (!open || closed || requested != scope) false else admission(requested).also { if (!it) closed = true }
        }
        val retainedEvidence = evidenceAdmission(scope)
        val evidence: (CalendarScope) -> Boolean = { requested -> open && requested == scope && retainedEvidence(requested) }
        suspend fun active(): Boolean {
            currentCoroutineContext().ensureActive()
            val allowed = lease(scope)
            currentCoroutineContext().ensureActive()
            return allowed
        }
        try {
            if (!active()) return false
            val source = checked { owners.observe(scope, reference.eventId, reference.mutationId, lease) } ?: return false
            if (!active() || source.intent.scope != scope || source.intent.id != reference.mutationId ||
                source.intent.base.eventId != reference.eventId || !SystemTimeEditProfile.accepts(source.intent)) return false
            val prepared = source.preparation ?: run {
                if (existingOnly) return false
                val fresh = checked { capsules.prepareLocalTime(source.intent, timestamp, lease) } ?: return false
                if (!active() || fresh.intent != source.intent || fresh.timestamp != timestamp) return false
                checked { owners.retain(fresh, lease) } ?: return false
            }
            if (!active() || prepared.intent != source.intent || !checked { owners.qualify(prepared, lease) }) return false
            val format = submissions.timeUpdateFormat ?: return false
            val id = TimeUpdateAttemptIdentity.requestId(prepared, format)
            val existing = checked { journal.read(scope, id, lease) }
            if (!active()) return false
            val record = if (existing != null) {
                if (!samePreparation(existing.preparation, prepared) || existing.format != format) return false
                existing
            } else {
                val candidate = checked { submissions.prepareSubmission(id, prepared, lease) } ?: return false
                if (!active() || candidate.id != id || candidate.scope != scope || candidate.format != format ||
                    candidate.state != TimeUpdateAttemptState.PREPARED || candidate.protectedEvidence != null ||
                    !samePreparation(candidate.preparation, prepared) || !checked { owners.qualify(prepared, lease) }) return false
                checked { journal.remember(candidate, lease) }
                if (!active()) return false
                // A concurrent call can retain the first wire. Never replace it with our new ciphertext.
                checked { journal.read(scope, id, lease) }?.takeIf { it.id == id && it.format == format &&
                    samePreparation(it.preparation, prepared) } ?: return false
            }
            if (!active()) return false
            if (record.state == TimeUpdateAttemptState.OUTCOME_UNKNOWN) return false // inspection has its own traversal
            val observed = checked { owners.submit(record, journal, submissions, lease, evidence) }
            currentCoroutineContext().ensureActive()
            if (observed is TimeUpdateSubmissionObservation.Verified && evidence(scope)) {
                val result = TimeUpdateVerifiedResult.of(record.copy(state = TimeUpdateAttemptState.OUTCOME_UNKNOWN), observed)
                if (checked { journal.retainVerified(result, evidence) }) {
                    proofRetained()
                    if (active() && checked { owners.acknowledge(result, journal, lease) })
                        checked { owners.complete(result, journal, lease) }
                }
            }
            return false
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { currentCoroutineContext().ensureActive(); return true }
          finally { open = false; closed = true }
    }

    override suspend fun inspect(query: TitleUpdateWorkQuery, admission: (CalendarScope) -> Boolean,
        proofRetained: () -> Unit) = current.inspect(query, admission, proofRetained)
    private fun samePreparation(a: LocalTimeEditPreparation, b: LocalTimeEditPreparation) =
        a.intent == b.intent && a.timestamp == b.timestamp && a.format == b.format && a.protectedPayload == b.protectedPayload

    /** A dependency returning null/false after swallowing cancellation cannot authorize
     * a normal return, wakeup or another side effect. Exceptions are classified by callers.
     */
    private suspend fun <T> checked(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val result = try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { currentCoroutineContext().ensureActive(); throw failure }
        currentCoroutineContext().ensureActive()
        return result
    }
}
