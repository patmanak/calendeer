// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Reuse bounded traversal only. Title, text and time cursor namespaces and all wire/proof types stay disjoint. */
class CoordinatorSystemTimeOperations(
    private val owners: SystemTimeLocalOwner,
    private val journal: TimeUpdateRecoveryJournal,
    private val submissions: CalendarTimeUpdateSubmissionPort,
    private val evidenceAdmission: (CalendarScope) -> ((CalendarScope) -> Boolean) = { target -> { it == target } },
) : SystemTitleUpdateOperations {
    constructor(imports: ImportCoordinator, journal: TimeUpdateRecoveryJournal,
        submissions: CalendarTimeUpdateSubmissionPort) :
        this(imports.localTimeOwners, journal, submissions, imports::systemTimeEvidenceAdmission)

    override suspend fun discover(query: RetainedLocalChangeQuery): RetainedLocalChangeListing {
        currentCoroutineContext().ensureActive()
        return RetainedLocalChangeListing.Available(RetainedLocalChangePage.fromOrderedMetadata(query, emptyList()))
    }

    override suspend fun process(scope: CalendarScope, reference: RetainedLocalChangeRef, attempt: MutationId,
        timestamp: Instant, admission: (CalendarScope) -> Boolean, proofRetained: () -> Unit): Boolean {
        currentCoroutineContext().ensureActive()
        // Retired writers never prepare, claim or resend. Historical inspection/ACK stays below.
        return false
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
            val page = checked { journal.page(TimeUpdateWorkQuery(query.scope, query.limit,
                query.cursor?.let { TimeUpdateWorkCursor(it.scope, it.afterEvent, it.protocol) }), lease) }
                ?: return TitleUpdateOfflineRecoveryProgress(inactive = true)
            require(page.scope == query.scope)
            var failed = 0
            var held = 0
            var completed = 0
            for (reference in page.entries) {
                if (!lease(query.scope)) break
                try {
                    val record = checked { journal.read(query.scope, reference.id, lease) } ?: error("Missing time history")
                    require(record.eventId == reference.eventId && record.state == reference.state)
                    var result = checked { journal.readVerified(query.scope, reference.id, lease) }
                    if (result == null && network && record.state == TimeUpdateAttemptState.OUTCOME_UNKNOWN) {
                        when (val observation = checked { submissions.inspect(record, journal, lease) }) {
                            TimeUpdateSubmissionObservation.Unknown -> Unit
                            is TimeUpdateSubmissionObservation.Verified -> {
                                val fresh = TimeUpdateVerifiedResult.of(record, observation)
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
