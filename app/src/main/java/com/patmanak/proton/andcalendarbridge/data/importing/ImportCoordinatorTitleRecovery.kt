package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bounded historical title inspection and exact durable acknowledgement/completion. */
internal class ImportCoordinatorTitleRecovery(
    private val store: ImportStore,
    private val remote: ReadOnlyCalendarRemote,
    private val projection: CalendarProjection,
    private val mutex: Mutex,
    private val generation: () -> Long,
    private val policyRevision: () -> Long,
    private val authenticated: () -> Boolean,
    private val systemCreatesEnabled: (CalendarScope) -> Boolean,
    private val systemTitlesEnabled: (CalendarScope) -> Boolean,
    private val hasCalendarPermissions: () -> Boolean,
    private val isCurrent: suspend (Long, AccountId) -> Boolean,
    private val capturedRawHold: suspend (CalendarScope) -> CapturedProjectionHold,
    private val handoffSource: suspend (CapturedCreateHandoffFrame, () -> Boolean) -> Boolean,
) {
    /** Internal inspection/recovery only. Network eligibility never grants submission admission. */
    suspend fun inspectAndRecoverLocalTitles(query: TitleUpdateWorkQuery, journal: TitleUpdateRecoveryJournal,
        port: CalendarTitleUpdateSubmissionPort, allowNetwork: Boolean = false): TitleUpdateRecoveryProgress = mutex.withLock {
        recoverLocalTitlesLocked(query, journal, port, allowNetwork)
    }

    /** One internal offline page. No inspection, preparation or submission capability. */
    suspend fun recoverLocalTitles(query: TitleUpdateWorkQuery,
        journal: TitleUpdateOfflineRecoveryJournal): TitleUpdateOfflineRecoveryProgress = mutex.withLock {
        recoverLocalTitlesLocked(query, journal)
    }

    /** Opt-in network inspection only; durable historical proofs keep their offline admission. */
    suspend fun inspectSystemTitles(query: TitleUpdateWorkQuery, journal: TitleUpdateRecoveryJournal,
        port: CalendarTitleUpdateSubmissionPort, admission: (CalendarScope) -> Boolean,
        proofRetained: () -> Unit): TitleUpdateRecoveryProgress = mutex.withLock {
        recoverLocalTitlesLocked(query, journal, port, allowNetwork = true,
            networkAdmission = admission, proofRetained = proofRetained)
    }

    private suspend fun recoverLocalTitlesLocked(query: TitleUpdateWorkQuery,
        journal: TitleUpdateOfflineRecoveryJournal, inspection: CalendarTitleUpdateSubmissionPort? = null,
        allowNetwork: Boolean = false, networkAdmission: ((CalendarScope) -> Boolean)? = null,
        proofRetained: () -> Unit = {}): TitleUpdateRecoveryProgress {
        val target = query.scope
        val expected = generation()
        val policy = policyRevision()
        val active = { authenticated() && generation() == expected && policyRevision() == policy &&
            systemCreatesEnabled(target) && hasCalendarPermissions() }
        var completed = 0
        var held = 0
        var failed = 0
        fun progress(next: TitleUpdateWorkCursor? = null, inactive: Boolean = false) =
            TitleUpdateOfflineRecoveryProgress(completed, held, failed, next, inactive)
        suspend fun available(): Boolean {
            currentCoroutineContext().ensureActive()
            if (!active() || !isCurrent(expected, target.account)) return false
            val selected = store.selections(target.account).singleOrNull { it.scope == target }
            return selected?.enabled == true && selected.available && active()
        }
        return try {
            if (!available()) return progress(inactive = true)
            check(journal.completionProtocol == 1) { "Incompatible title recovery journal" }
            val recovery = if (inspection != null) {
                check(journal is TitleUpdateRecoveryJournal) { "Incompatible title inspection journal" }
                TitleUpdateGuardedResultRecovery(journal, journal, inspection)
            } else null
            val page = journal.page(query)
            if (!available()) return progress(inactive = true)
            val next = page.next
            check(page.scope == target && page.entries.size <= query.limit &&
                (next == null || (next.scope == target && next.protocol == 1 &&
                    next.afterEvent == page.entries.lastOrNull()?.eventId))) { "Invalid title recovery page" }
            for (reference in page.entries) {
                if (!available()) return progress(inactive = true)
                // The helpers close their leases; install this invocation's same epoch again.
                store.admitTransaction = { it == target && active() }
                try {
                    val record = journal.read(target, reference.id)?.frozen()
                    if (!available()) return progress(inactive = true)
                    if (record == null) { held++; continue }
                    check(record.scope == target && record.id == reference.id &&
                        record.preparation.intent.base.eventId == reference.eventId) { "Invalid title recovery binding" }
                    // Metadata may have changed state since discovery. Only the complete record counts.
                    if (record.state != TitleUpdateAttemptState.OUTCOME_UNKNOWN ||
                        record.preparation.intent.format != remote.format ||
                        store.transaction(target) { checkpoint?.format } != record.preparation.intent.format) {
                        if (!available()) return progress(inactive = true)
                        held++; continue
                    }
                    if (!available()) return progress(inactive = true)
                    val historical = journal.readVerified(target, reference.id)
                    var inspectionSourceFrozen = false
                    var inspectionRole: CapturedCreateHandoffRecord? = null
                    suspend fun networkAvailable(): Boolean {
                        if (!available()) return false
                        if (networkAdmission == null) return true
                        if (!systemTitlesEnabled(target) || !networkAdmission(target) ||
                            !LocalTitleOwnerQualification.systemTitleProfile(record.preparation.intent)) return false
                        val role = store.capturedHandoff(target, reference.eventId)
                        if (!available()) return false
                        val hold = capturedRawHold(target)
                        currentCoroutineContext().ensureActive()
                        if (!available() || hold.scopeBlocked || (inspectionSourceFrozen && role != inspectionRole)) return false
                        if (role == null) {
                            if (hold.holds(reference.eventId)) return false
                        } else {
                            // Historical inspection must survive a later native edit. Sending and
                            // acknowledgement separately require their exact current owner/frame.
                            if (role.state != CapturedCreateHandoffState.RELEASED ||
                                role.frame.format != record.preparation.intent.format ||
                                !handoffSource(role.frame, active)) return false
                            if (!available()) return false
                        }
                        val currentRole = store.capturedHandoff(target, reference.eventId)
                        if (!available() || currentRole != role) return false
                        val currentHold = capturedRawHold(target)
                        currentCoroutineContext().ensureActive()
                        if (!available() || currentHold.scopeBlocked ||
                            (role == null && currentHold.holds(reference.eventId)) ||
                            !systemTitlesEnabled(target) || !networkAdmission(target)) return false
                        inspectionRole = role
                        inspectionSourceFrozen = true
                        return true
                    }
                    val result = historical ?: if (recovery != null && networkAvailable()) recovery.recover(
                        target, reference.id, allowNetwork, admit = { it == target && active() &&
                            (networkAdmission == null || (systemTitlesEnabled(target) && networkAdmission(target))) },
                        checkAccess = { networkAvailable() }) else null
                    if (historical == null && result != null) proofRetained()
                    if (!available()) return progress(inactive = true)
                    if (result == null) { held++; continue }
                    check(result.record == record.copy(protectedEvidence = null)) { "Invalid title recovery result" }
                    when (acknowledgeLocalTitleLocked(target, reference.eventId, reference.id, journal, expected, policy)) {
                        TitleUpdateAcknowledgementResult.INACTIVE -> return progress(inactive = true)
                        TitleUpdateAcknowledgementResult.UNRESOLVED -> { held++; continue }
                        TitleUpdateAcknowledgementResult.ACKNOWLEDGED -> Unit
                    }
                    if (!available()) return progress(inactive = true)
                    val completion = completeLocalTitleLocked(target, reference.eventId, reference.id, journal, expected, policy)
                    if (!available()) return progress(inactive = true)
                    when (completion) {
                        TitleUpdateCompletionResult.INACTIVE -> return progress(inactive = true)
                        TitleUpdateCompletionResult.UNRESOLVED -> held++
                        TitleUpdateCompletionResult.COMPLETED -> completed++
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) {
                      if (!available()) return progress(inactive = true)
                      failed++
                  }
            }
            progress(next = next)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
              if (!active()) progress(inactive = true)
              else {
                  // A failed page/access read gives no safe continuation. Restart at the first page.
                  if (completed + held + failed < TitleUpdateWorkQuery.MAX_PAGE_SIZE) failed++
                  progress()
              }
          } finally {
              store.admitTransaction = { false }
              projection.admitOperation = { false }
          }
    }

    /** Recovery passes its original epoch; constructing a helper must never renew admission. */
    private fun localTitleCompletion(target: CalendarScope, expected: Long, policy: Long) =
        LocalTitleCompletion(target, store, projection,
            currentFormat = { remote.format },
            active = { authenticated() && generation() == expected && policyRevision() == policy &&
                systemCreatesEnabled(target) && hasCalendarPermissions() },
            currentAccount = { isCurrent(expected, target.account) })

    /** Internal simple title acknowledgement only. Reads an already durable verified result;
     * never prepares, inspects the service or dispatches UPDATE. A fresh reservation permits one
     * conditional native metadata write. Reopening only inspects the retained nonce.
     */
    suspend fun acknowledgeLocalTitle(target: CalendarScope, eventId: EventId, attempt: MutationId,
        journal: TitleUpdateResultJournal): TitleUpdateAcknowledgementResult = mutex.withLock {
        acknowledgeLocalTitleLocked(target, eventId, attempt, journal, generation(), policyRevision())
    }

    private suspend fun acknowledgeLocalTitleLocked(target: CalendarScope, eventId: EventId, attempt: MutationId,
        journal: TitleUpdateResultJournal, expected: Long, policy: Long): TitleUpdateAcknowledgementResult {
        return localTitleCompletion(target, expected, policy).acknowledge(eventId, attempt, journal)
    }

    /** Internal durable closure only; no Provider write or service request. The two journals
     * commit independently: a restored ACK without completion keeps the attempt's hold and
     * can be resumed by reading its exact historical proof, including after a later edit.
     */
    suspend fun completeLocalTitle(target: CalendarScope, eventId: EventId, attempt: MutationId,
        journal: TitleUpdateCompletionJournal): TitleUpdateCompletionResult = mutex.withLock {
        completeLocalTitleLocked(target, eventId, attempt, journal, generation(), policyRevision())
    }

    private suspend fun completeLocalTitleLocked(target: CalendarScope, eventId: EventId, attempt: MutationId,
        journal: TitleUpdateCompletionJournal, expected: Long, policy: Long): TitleUpdateCompletionResult {
        return localTitleCompletion(target, expected, policy).complete(eventId, attempt, journal)
    }

}
