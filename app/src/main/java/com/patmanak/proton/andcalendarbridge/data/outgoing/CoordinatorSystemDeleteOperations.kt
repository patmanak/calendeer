// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.data.importing.*
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded traversal is shared; deletion wire, proof, consent and cursor namespaces are separate. */
class CoordinatorSystemDeleteOperations(
    private val owners: SystemDeleteLocalOwner,
    private val journal: DeleteRecoveryJournal,
    private val submissions: CalendarDeleteSubmissionPort,
    private val discoverLocal: suspend (RetainedLocalChangeQuery) -> RetainedLocalChangeListing,
    private val evidenceAdmission: (CalendarScope) -> ((CalendarScope) -> Boolean) = { target -> { it == target } },
) : SystemTitleUpdateOperations {
    constructor(imports: ImportCoordinator, journal: DeleteRecoveryJournal, submissions: CalendarDeleteSubmissionPort) :
        this(imports.localDeleteOwners, journal, submissions, imports::retainedLocalChanges,
            imports::systemDeleteEvidenceAdmission)

    override suspend fun discover(query: RetainedLocalChangeQuery) = checked { discoverLocal(query) }

    override suspend fun process(scope: CalendarScope, reference: RetainedLocalChangeRef, attempt: MutationId,
        timestamp: Instant, admission: (CalendarScope) -> Boolean, proofRetained: () -> Unit): Boolean {
        currentCoroutineContext().ensureActive()
        if (reference.kind != MutationKind.DELETE) return false
        val lease = Lease(scope, admission)
        val evidence = Lease(scope, evidenceAdmission(scope))
        try {
            if (!lease.active()) return false
            val intent = checked { owners.observe(scope, reference.eventId, reference.mutationId, lease::allows) } ?: return false
            if (!lease.active() || intent.scope != scope || intent.id != reference.mutationId ||
                intent.base.eventId != reference.eventId) return false
            val frozen = DeleteSubmissionPolicy.freezeIntent(intent)
            val format = submissions.deleteFormat ?: return false
            val id = DeleteAttemptIdentity.requestId(frozen, format)
            val existing = checked { journal.read(scope, id, lease::allows) }
            if (!lease.active()) return false
            val record = existing ?: run {
                val prepared = checked { submissions.prepareSubmission(id, frozen, lease::allows) } ?: return false
                if (!lease.active() || prepared.id != id || prepared.intent != frozen || prepared.format != format ||
                    prepared.state != DeleteAttemptState.PREPARED || prepared.protectedEvidence != null) return false
                checked { journal.remember(prepared, lease::allows) }
                if (!lease.active()) return false
                checked { journal.read(scope, id, lease::allows) } ?: return false
            }
            if (record.id != id || record.intent != frozen || record.format != format || !lease.active()) return false
            if (record.state == DeleteAttemptState.OUTCOME_UNKNOWN) return false
            if (!checked { owners.reserve(record, lease::allows) } || !lease.active()) return false
            when (val observed = checked { owners.submit(record, journal, submissions, lease::allows, evidence::allows) }) {
                DeleteSubmissionObservation.Unknown -> Unit
                is DeleteSubmissionObservation.Conflict ->
                    if (lease.active() && observed.record.copy(state = DeleteAttemptState.PREPARED, protectedEvidence = null) == record)
                        checked { owners.conflict(frozen, observed.event, lease::allows) }
                is DeleteSubmissionObservation.Verified -> {
                    val result = DeleteVerifiedResult.of(record.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN), observed)
                    if (evidence.active() && checked { journal.retainVerified(result, evidence::allows) }) {
                        proofRetained()
                        if (lease.active() && checked { owners.acknowledge(result, journal, lease::allows) })
                            checked { owners.complete(result, journal, lease::allows) }
                    }
                }
            }
            return false
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { currentCoroutineContext().ensureActive(); return true }
          finally { lease.close(); evidence.close() }
    }

    override suspend fun inspect(query: TitleUpdateWorkQuery, admission: (CalendarScope) -> Boolean,
        proofRetained: () -> Unit) = recover(query, admission, true, proofRetained)

    /** No preparation, claim or HTTP operation is reachable through this entry point. */
    suspend fun recoverOffline(query: TitleUpdateWorkQuery, admission: (CalendarScope) -> Boolean) =
        recover(query, admission, false) { }

    private suspend fun recover(query: TitleUpdateWorkQuery, admission: (CalendarScope) -> Boolean,
        network: Boolean, proofRetained: () -> Unit): TitleUpdateOfflineRecoveryProgress {
        val lease = Lease(query.scope, admission)
        val evidence = Lease(query.scope, evidenceAdmission(query.scope))
        try {
            if (!lease.active()) return TitleUpdateOfflineRecoveryProgress(inactive = true)
            val page = checked { journal.page(DeleteWorkQuery(query.scope, query.limit,
                query.cursor?.let { DeleteWorkCursor(it.scope, it.afterEvent, it.protocol) }), lease::allows) }
                ?: return TitleUpdateOfflineRecoveryProgress(inactive = true)
            require(page.scope == query.scope)
            var completed = 0; var failed = 0; var held = 0
            for (reference in page.entries) {
                if (!lease.active()) break
                try {
                    val record = checked { journal.read(query.scope, reference.id, lease::allows) } ?: error("Missing deletion history")
                    require(record.eventId == reference.eventId && record.state == reference.state)
                    var result = checked { journal.readVerified(query.scope, reference.id, lease::allows) }
                    if (result == null && network && record.state == DeleteAttemptState.OUTCOME_UNKNOWN) {
                        when (val observed = checked { submissions.inspect(record, journal, lease::allows) }) {
                            DeleteSubmissionObservation.Unknown -> Unit
                            is DeleteSubmissionObservation.Conflict -> if (lease.active() &&
                                observed.record.copy(protectedEvidence = null) == record.copy(protectedEvidence = null))
                                checked { owners.conflict(record.intent, observed.event, lease::allows) }
                            is DeleteSubmissionObservation.Verified -> {
                                val fresh = DeleteVerifiedResult.of(record, observed)
                                if (evidence.active() && checked { journal.retainVerified(fresh, evidence::allows) }) {
                                    result = fresh; proofRetained()
                                }
                            }
                        }
                    }
                    if (!lease.active()) break
                    if (result == null) held++ else {
                        require(result.record == record.copy(protectedEvidence = null))
                        if (checked { owners.acknowledge(result, journal, lease::allows) } &&
                            checked { owners.complete(result, journal, lease::allows) }) completed++ else held++
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { currentCoroutineContext().ensureActive(); failed++ }
            }
            val active = lease.active()
            return TitleUpdateOfflineRecoveryProgress(completed = completed, held = held, failed = failed, inactive = !active,
                next = if (active) page.next?.let { TitleUpdateWorkCursor(it.scope, it.afterEvent, it.protocol) } else null)
        } finally { lease.close(); evidence.close() }
    }

    private class Lease(private val scope: CalendarScope, private val admit: (CalendarScope) -> Boolean) {
        private val closed = AtomicBoolean(false)
        fun allows(requested: CalendarScope): Boolean {
            if (closed.get() || requested != scope) return false
            return try {
                val allowed = admit(requested)
                if (!allowed) closed.set(true)
                allowed && !closed.get()
            }
                catch (failure: Exception) { closed.set(true); throw failure }
        }
        suspend fun active(): Boolean { currentCoroutineContext().ensureActive(); return allows(scope).also { currentCoroutineContext().ensureActive() } }
        fun close() { closed.set(true) }
    }
    private suspend fun <T> checked(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val value = try { action() } catch (failure: Exception) { currentCoroutineContext().ensureActive(); throw failure }
        currentCoroutineContext().ensureActive(); return value
    }
}
