// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reuse bounded traversal only. Title/text cursor namespaces and all wire/proof types stay disjoint. */
class CoordinatorSystemTextOperations(
    private val owners: SystemTextLocalOwner,
    private val journal: TextUpdateRecoveryJournal,
    private val capsules: CalendarLocalTextPreparationPort,
    private val submissions: CalendarTextUpdateSubmissionPort,
    private val discoverLocal: suspend (RetainedLocalChangeQuery) -> RetainedLocalChangeListing,
    private val evidenceAdmission: (CalendarScope) -> ((CalendarScope) -> Boolean) = { target -> { it == target } },
    private val existingOnly: Boolean = false,
) : SystemTitleUpdateOperations {
    constructor(imports: ImportCoordinator, journal: TextUpdateRecoveryJournal,
        capsules: CalendarLocalTextPreparationPort, submissions: CalendarTextUpdateSubmissionPort, existingOnly: Boolean = false) :
        this(imports.localTextOwners, journal, capsules, submissions, imports::retainedLocalChanges,
            imports::systemTextEvidenceAdmission, existingOnly)

    override suspend fun discover(query: RetainedLocalChangeQuery) = checked { discoverLocal(query) }

    override suspend fun process(scope: CalendarScope, reference: RetainedLocalChangeRef, attempt: MutationId,
        timestamp: Instant, admission: (CalendarScope) -> Boolean, proofRetained: () -> Unit): Boolean {
        currentCoroutineContext().ensureActive()
        if (reference.kind != MutationKind.UPDATE || !admission(scope)) return false
        // The generic traversal's random candidate is deliberately not a durable text request ID.
        // A text ID derives from the first locally retained complete capsule and target format.
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
                source.intent.base.eventId != reference.eventId || !SystemTextEditProfile.accepts(source.intent)) return false
            val prepared = source.preparation ?: run {
                if (existingOnly) return false
                val fresh = checked { capsules.prepareLocalText(source.intent, timestamp, lease) } ?: return false
                if (!active() || fresh.intent != source.intent || fresh.timestamp != timestamp) return false
                checked { owners.retain(fresh, lease) } ?: return false
            }
            if (!active() || prepared.intent != source.intent || !checked { owners.qualify(prepared, lease) }) return false
            val format = submissions.textUpdateFormat ?: return false
            val id = TextUpdateAttemptIdentity.requestId(prepared, format)
            val existing = checked { journal.read(scope, id, lease) }
            if (!active()) return false
            val record = if (existing != null) {
                if (!samePreparation(existing.preparation, prepared) || existing.format != format) return false
                existing
            } else {
                val candidate = checked { submissions.prepareSubmission(id, prepared, lease) } ?: return false
                if (!active() || candidate.id != id || candidate.scope != scope || candidate.format != format ||
                    candidate.state != TextUpdateAttemptState.PREPARED || candidate.protectedEvidence != null ||
                    !samePreparation(candidate.preparation, prepared) || !checked { owners.qualify(prepared, lease) }) return false
                checked { journal.remember(candidate, lease) }
                if (!active()) return false
                // A concurrent call can retain the first wire. Never replace it with our new ciphertext.
                checked { journal.read(scope, id, lease) }?.takeIf { it.id == id && it.format == format &&
                    samePreparation(it.preparation, prepared) } ?: return false
            }
            if (!active()) return false
            if (record.state == TextUpdateAttemptState.OUTCOME_UNKNOWN) return false // inspection has its own traversal
            val observed = checked { owners.submit(record, journal, submissions, lease, evidence) }
            currentCoroutineContext().ensureActive()
            if (observed is TextUpdateSubmissionObservation.Verified && evidence(scope)) {
                val result = TextUpdateVerifiedResult.of(record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN), observed)
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
        proofRetained: () -> Unit): TitleUpdateOfflineRecoveryProgress = recover(query, admission, true, proofRetained)

    /** Offline entry point has no preparation, claim or transport operation. */
    suspend fun recoverOffline(query: TitleUpdateWorkQuery, admission: (CalendarScope) -> Boolean): TitleUpdateOfflineRecoveryProgress =
        recover(query, admission, false) { }

    private suspend fun recover(query: TitleUpdateWorkQuery, admission: (CalendarScope) -> Boolean, network: Boolean,
        proofRetained: () -> Unit): TitleUpdateOfflineRecoveryProgress {
        currentCoroutineContext().ensureActive()
        var open = true
        var closed = false
        val lease: (CalendarScope) -> Boolean = { scope ->
            if (!open || closed || scope != query.scope) false else admission(scope).also { if (!it) closed = true }
        }
        val retainedEvidence = evidenceAdmission(query.scope)
        val evidence: (CalendarScope) -> Boolean = { scope -> open && scope == query.scope && retainedEvidence(scope) }
        try {
            if (!lease(query.scope)) return TitleUpdateOfflineRecoveryProgress(inactive = true)
            val page = checked { journal.page(TextUpdateWorkQuery(query.scope, query.limit,
                query.cursor?.let { TextUpdateWorkCursor(it.scope, it.afterEvent, it.protocol) }), lease) }
                ?: return TitleUpdateOfflineRecoveryProgress(inactive = true)
            require(page.scope == query.scope)
            var failed = 0
            var held = 0
            var completed = 0
            for (reference in page.entries) {
                if (!lease(query.scope)) break
                try {
                    val record = checked { journal.read(query.scope, reference.id, lease) } ?: error("Missing text history")
                    require(record.eventId == reference.eventId && record.state == reference.state)
                    var result = checked { journal.readVerified(query.scope, reference.id, lease) }
                    if (result == null && network && record.state == TextUpdateAttemptState.OUTCOME_UNKNOWN) {
                        when (val observation = checked { submissions.inspect(record, journal, lease) }) {
                            TextUpdateSubmissionObservation.Unknown -> Unit
                            is TextUpdateSubmissionObservation.Verified -> {
                                val fresh = TextUpdateVerifiedResult.of(record, observation)
                                if (checked { journal.retainVerified(fresh, evidence) }) { result = fresh; proofRetained() }
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    if (!lease(query.scope)) break
                    if (result == null) held++
                    else {
                        require(result.record == record.copy(protectedEvidence = null))
                        if (checked { owners.acknowledge(result, journal, lease) } &&
                            checked { owners.complete(result, journal, lease) }) completed++ else held++
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { currentCoroutineContext().ensureActive(); failed++ }
            }
            currentCoroutineContext().ensureActive()
            val active = lease(query.scope)
            return TitleUpdateOfflineRecoveryProgress(completed = completed, held = held, failed = failed,
                inactive = !active, next = if (active) page.next?.let { TitleUpdateWorkCursor(it.scope, it.afterEvent, it.protocol) } else null)
        } finally { open = false; closed = true }
    }
    private fun samePreparation(a: LocalTextEditPreparation, b: LocalTextEditPreparation) =
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
