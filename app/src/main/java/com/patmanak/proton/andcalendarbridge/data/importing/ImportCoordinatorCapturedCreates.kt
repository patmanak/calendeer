package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.sync.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Captured CREATE staging, fresh dispatch leases and exact historical restoration/transfer. */
internal class ImportCoordinatorCapturedCreates(
    private val store: ImportStore,
    private val remote: ReadOnlyCalendarRemote,
    private val projection: CalendarProjection,
    private val mutex: Mutex,
    private val scheduler: ImportScheduler,
    private val creates: CreateAttemptStore?,
    private val localCreates: LocalCreateStore?,
    private val generation: () -> Long,
    private val policyRevision: () -> Long,
    private val authenticated: () -> Boolean,
    private val systemCreatesEnabled: (CalendarScope) -> Boolean,
    private val hasCalendarPermissions: () -> Boolean,
    private val isCurrent: suspend (Long, AccountId) -> Boolean,
    private val ensureCreateLifecycle: suspend () -> Unit,
    private val qualifyCreate: suspend (CalendarScope, Long, Long) -> Unit,
) {
    private data class CapturedSubmissionLease(val identity: CapturedCreateDispatchIdentity, val generation: Long, val policy: Long)
    // Only a newly retained preparation in this owner can populate this map. Cache reads never rearm it.
    private val freshCapturedSubmissions = ConcurrentHashMap<Pair<CalendarScope, MutationId>, CapturedSubmissionLease>()
    fun invalidate(target: CalendarScope) {
        freshCapturedSubmissions.entries.removeIf { it.key.first == target }
    }

    fun clearLeases() { freshCapturedSubmissions.clear() }

    /** Preparation never renews its original epoch; only fresh durable staging records a send lease. */
    private fun localCreateStaging(target: CalendarScope, journal: LocalCreateStore, expected: Long, policy: Long) =
        LocalCreateStaging(target, store, projection, journal,
            currentFormat = { remote.format },
            active = { authenticated() && generation() == expected && policyRevision() == policy && hasCalendarPermissions() },
            currentAccount = { isCurrent(expected, target.account) },
            recordFreshSubmission = { attempt, retained ->
                if (creates != null) freshCapturedSubmissions[target to attempt] =
                    CapturedSubmissionLease(CapturedCreateDispatchIdentity.of(retained), expected, policy)
            })

    /** Explicit internal capture only; no preparation, submission or Android acknowledgement. */
    suspend fun captureLocalCreate(target: CalendarScope, eventRow: Long, localId: LocalEventId,
        id: MutationId, timestamp: java.time.Instant, series: Boolean = false, autoProfile: Boolean = false): LocalEditCaptureResult = mutex.withLock {
        val journal = localCreates ?: return@withLock LocalEditCaptureResult.UNAVAILABLE
        val expected = generation()
        val policy = policyRevision()
        if (!isCurrent(expected, target.account) || !hasCalendarPermissions())
            return@withLock LocalEditCaptureResult.INACTIVE
        localCreateStaging(target, journal, expected, policy).capture(eventRow, localId, id, timestamp, series, autoProfile)
    }

    /** Explicit inactive occurrence capture; no preparation, dispatch or Provider acknowledgement. */
    suspend fun captureLocalOccurrence(target: CalendarScope, master: RemoteChange.Upsert, eventRow: Long,
        localId: LocalEventId, id: MutationId, timestamp: java.time.Instant): LocalEditCaptureResult = mutex.withLock {
        val journal = localCreates ?: return@withLock LocalEditCaptureResult.UNAVAILABLE
        val expected = generation(); val policy = policyRevision()
        if (!isCurrent(expected, target.account) || !hasCalendarPermissions()) return@withLock LocalEditCaptureResult.INACTIVE
        localCreateStaging(target, journal, expected, policy).captureOccurrence(master, eventRow, localId, id, timestamp)
    }

    /** Explicit read-only preparation; no outgoing journal, dispatch, or Provider acknowledgement. */
    suspend fun prepareLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        port: CalendarCreatePreparationPort): LocalCreatePreparationResult = mutex.withLock {
        val journal = localCreates ?: return@withLock LocalCreatePreparationResult.UNAVAILABLE
        val expected = generation()
        val policy = policyRevision()
        if (!isCurrent(expected, target.account) || !hasCalendarPermissions())
            return@withLock LocalCreatePreparationResult.INACTIVE
        localCreateStaging(target, journal, expected, policy).prepare(localId, id, port)
    }

    /** Explicit read-only staging only; no dispatch journal, upload or Provider acknowledgement. */
    suspend fun stageLocalCreateSubmission(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, port: CalendarCreateSubmissionPort): LocalCreateSubmissionResult = mutex.withLock {
        val journal = localCreates ?: return@withLock LocalCreateSubmissionResult.UNAVAILABLE
        ensureCreateLifecycle()
        val expected = generation()
        val policy = policyRevision()
        if (!isCurrent(expected, target.account) || !hasCalendarPermissions()) return@withLock LocalCreateSubmissionResult.INACTIVE
        localCreateStaging(target, journal, expected, policy).stageSubmission(localId, id, attempt, port)
    }

    /** Opt-in automatic path requalifies the same unsent payload; UNKNOWN remains inspect-only. */
    suspend fun resumePreparedLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, port: CalendarCreateSubmissionPort,
        allowPaused: Boolean = true): LocalCreateSubmissionResult = mutex.withLock {
        val captures = localCreates ?: return@withLock LocalCreateSubmissionResult.UNRESOLVED
        val journal = creates ?: return@withLock LocalCreateSubmissionResult.UNRESOLVED
        ensureCreateLifecycle()
        val expected = generation()
        val policy = policyRevision()
        val active = { authenticated() && generation() == expected && policyRevision() == policy &&
            systemCreatesEnabled(target) && hasCalendarPermissions() }
        if (!active()) return@withLock LocalCreateSubmissionResult.INACTIVE
        val key = target to attempt
        var retainedLease: CapturedSubmissionLease? = null
        try {
            qualifyCreate(target, expected, policy)
            captures.admitCapture = { it == target && active() }
            store.admitTransaction = { it == target && active() }
            projection.admitOperation = active
            val submission = captures.readSubmission(target, localId) ?: return@withLock LocalCreateSubmissionResult.UNRESOLVED
            val captured = submission.reservation.intent.reservation
            if (captured.scope != target || captured.localId != localId || captured.id != id ||
                submission.reservation.attempt != attempt || captured.format != remote.format ||
                !port.supportsCapturedSubmission(submission.reservation.format))
                return@withLock LocalCreateSubmissionResult.UNQUALIFIED
            val wanted = CapturedCreateDispatchIdentity.of(submission)
            val record = journal.read(target, attempt)
            if (record?.state == CreateAttemptState.OUTCOME_UNKNOWN) return@withLock LocalCreateSubmissionResult.UNRESOLVED
            suspend fun qualifies(): Boolean {
                if (!active() || !isCurrent(expected, target.account)) return false
                val selected = store.selections(target.account).singleOrNull { it.scope == target }
                if (selected?.enabled != true || !selected.available ||
                    store.transaction(target) { checkpoint?.format } != captured.format ||
                    captures.readSubmission(target, localId) != submission ||
                    (submission.reservation.isOccurrence && captures.readOccurrenceContext(target, localId) != submission.reservation.intent) ||
                    !port.supportsCapturedSubmission(submission.reservation.format)) return false
                val matches = projection.matchesCapturedIntent(submission.reservation.intent) && store.matchesOccurrenceSource(submission.reservation.intent)
                return matches && active() &&
                    captures.readSubmission(target, localId) == submission &&
                    store.transaction(target) { checkpoint?.format } == captured.format
            }
            if (!qualifies()) return@withLock if (active()) LocalCreateSubmissionResult.UNQUALIFIED else LocalCreateSubmissionResult.INACTIVE
            val lease = CapturedSubmissionLease(wanted, expected, policy)
            retainedLease = lease
            freshCapturedSubmissions[key] = lease
            journal.admitDispatch = { it == target && active() && freshCapturedSubmissions[key] == lease }
            if (!journal.resumeCapturedPrepared(submission, allowPaused)) {
                freshCapturedSubmissions.remove(key, lease)
                return@withLock LocalCreateSubmissionResult.UNQUALIFIED
            }
            if (!qualifies()) {
                freshCapturedSubmissions.remove(key, lease)
                return@withLock if (active()) LocalCreateSubmissionResult.UNQUALIFIED else LocalCreateSubmissionResult.INACTIVE
            }
            LocalCreateSubmissionResult.RETAINED
        } catch (cancelled: CancellationException) {
            retainedLease?.let { freshCapturedSubmissions.remove(key, it) }
            throw cancelled
        } catch (_: Exception) {
            retainedLease?.let { freshCapturedSubmissions.remove(key, it) }
            if (active()) LocalCreateSubmissionResult.UNAVAILABLE else LocalCreateSubmissionResult.INACTIVE
        } finally {
            captures.admitCapture = { false }; journal.admitDispatch = { false }
            store.admitTransaction = { false }; projection.admitOperation = { false }
        }
    }

    /** Explicit internal dispatch; original Provider acknowledgement and association remain separate. */
    suspend fun submitLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, port: CalendarCreateSubmissionPort, pushAllowed: () -> Boolean = { true }): CreateSubmissionObservation = mutex.withLock {
        val captures = localCreates ?: return@withLock CreateSubmissionObservation.Unknown
        val journal = creates ?: return@withLock CreateSubmissionObservation.Unknown
        ensureCreateLifecycle()
        val expected = generation()
        val policy = policyRevision()
        val active = { authenticated() && generation() == expected && policyRevision() == policy && hasCalendarPermissions() }
        try {
            qualifyCreate(target, expected, policy)
            captures.admitCapture = { it == target && active() }
            store.admitTransaction = { it == target && active() }
            projection.admitOperation = active
            val submission = captures.readSubmission(target, localId) ?: return@withLock CreateSubmissionObservation.Unknown
            val reserved = submission.reservation
            fun currentAdmission() = active() && pushAllowed() && (!reserved.isOccurrence || systemCreatesEnabled(target))
            val captured = reserved.intent.reservation
            if (captured.scope != target || captured.localId != localId || captured.id != id || reserved.attempt != attempt ||
                !port.supportsCapturedSubmission(reserved.format)) return@withLock CreateSubmissionObservation.Unknown
            val wanted = CapturedCreateDispatchIdentity.of(submission)
            val draft = reserved.draft
            val prepared = CreateAttemptRecord(attempt, target, reserved.format, submission.protectedPayload)
            val existing = journal.read(target, attempt)
            if (existing != null && journal.capturedIdentity(target, attempt) != wanted) return@withLock CreateSubmissionObservation.Unknown
            val canonical = capturedCreateExpectation(submission, port) ?: return@withLock CreateSubmissionObservation.Unknown
            qualifyCreate(target, expected, policy)
            if (captures.readSubmission(target, localId) != submission) return@withLock CreateSubmissionObservation.Unknown

            fun qualifiedObservation(observed: CreateSubmissionObservation): CreateSubmissionObservation {
                return if (observed is CreateSubmissionObservation.Verified &&
                    (observed.attempt != wanted.attempt || observed.event.content != canonical)) CreateSubmissionObservation.Unknown
                else observed
            }
            if (existing?.state == CreateAttemptState.OUTCOME_UNKNOWN) {
                val observed = port.inspectCaptured(existing, draft)
                qualifyCreate(target, expected, policy)
                if (captures.readSubmission(target, localId) != submission || journal.capturedIdentity(target, attempt) != wanted)
                    return@withLock CreateSubmissionObservation.Unknown
                return@withLock qualifiedObservation(observed)
            }
            val key = target to attempt
            val lease = freshCapturedSubmissions[key] ?: return@withLock CreateSubmissionObservation.Unknown
            fun hasLease() = currentAdmission() && lease.identity == wanted && lease.generation == expected && lease.policy == policy &&
                freshCapturedSubmissions[key] == lease
            // Called from inside Proton's authentication mutex at final claim. Never call
            // currentAccount/isCurrent/qualifyCreate here; the adapter owns fresh session checks.
            suspend fun qualifyLocal(): Boolean {
                if (reserved.isOccurrence && !systemCreatesEnabled(target)) return false
                if (!hasLease() || !port.supportsCapturedSubmission(reserved.format) || remote.format != captured.format) return false
                suspend fun localStateMatches(): Boolean {
                    val selected = store.selections(target.account).singleOrNull { it.scope == target }
                    return selected?.enabled == true && selected.available &&
                        store.transaction(target) { checkpoint?.format } == captured.format &&
                        captures.readSubmission(target, localId) == submission &&
                        (!reserved.isOccurrence || captures.readOccurrenceContext(target, localId) == reserved.intent)
                }
                if (!localStateMatches()) return false
                val matches = projection.matchesCapturedIntent(reserved.intent) && store.matchesOccurrenceSource(reserved.intent)
                return matches && localStateMatches() && hasLease() &&
                    port.supportsCapturedSubmission(reserved.format) && remote.format == captured.format
            }
            if (!qualifyLocal() || journal.pauseReason(target, attempt) != null) return@withLock CreateSubmissionObservation.Unknown
            journal.admitDispatch = { it == target && hasLease() }
            check(journal.rememberCaptured(submission) == wanted) { "Captured dispatch link differs" }
            val actual = checkNotNull(journal.read(target, attempt))
            if (actual != prepared || !qualifyLocal()) return@withLock CreateSubmissionObservation.Unknown
            val guarded = object : CreateAttemptJournal by journal {
                override fun allowsTransport(scope: CalendarScope) =
                    scope == target && currentAdmission() && journal.allowsTransport(scope)

                override suspend fun claim(expectedWork: CreateAttemptRecord): Boolean {
                    if (expectedWork != actual || journal.capturedIdentity(target, attempt) != wanted || !qualifyLocal()) return false
                    // Consume after the last qualification but before the independent SQLite commit.
                    // A failed/cancelled claim cannot recover this right from a cache or a new owner.
                    if (!freshCapturedSubmissions.remove(key, lease)) return false
                    journal.admitDispatch = { it == target && currentAdmission() }
                    return journal.claimCaptured(expectedWork, wanted)
                }
            }
            val observed = port.submitCaptured(actual, draft, guarded)
            qualifyCreate(target, expected, policy)
            if (journal.read(target, attempt)?.state != CreateAttemptState.OUTCOME_UNKNOWN ||
                journal.capturedIdentity(target, attempt) != wanted) return@withLock CreateSubmissionObservation.Unknown
            qualifiedObservation(observed)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { CreateSubmissionObservation.Unknown }
          finally {
              captures.admitCapture = { false }
              journal.admitDispatch = { false }
              store.admitTransaction = { false }
              projection.admitOperation = { false }
          }
    }

    /** Created under the import mutex after lifecycle admission; never renews an operation's epoch. */
    private fun capturedCreateRestoration(target: CalendarScope, captures: LocalCreateStore,
        journal: CreateAttemptStore): CapturedCreateRestoration {
        val expected = generation()
        val policy = policyRevision()
        return CapturedCreateRestoration(target, store, projection, captures, journal,
            currentFormat = { remote.format },
            active = { authenticated() && generation() == expected && policyRevision() == policy && hasCalendarPermissions() },
            currentAccount = { isCurrent(expected, target.account) }, expectations = remote as? CalendarCreateSubmissionPort)
    }

    /** Reconstruct only a fully proven historical queue association; never prepare, send or ACK. */
    suspend fun recoverHistoricalSystemCreate(target: CalendarScope, row: Long, marker: String?, after: String?):
        com.patmanak.proton.andcalendarbridge.data.outgoing.HistoricalCreateRecovery = mutex.withLock {
        val unresolved = com.patmanak.proton.andcalendarbridge.data.outgoing.HistoricalCreateRecovery(
            reason = com.patmanak.proton.andcalendarbridge.data.outgoing.HistoricalCreateIssueReason.INCOMPLETE_PROOF)
        val captures = localCreates ?: return@withLock unresolved
        val journal = creates ?: return@withLock unresolved
        ensureCreateLifecycle()
        val expected = generation()
        val policy = policyRevision()
        val active = { authenticated() && generation() == expected && policyRevision() == policy &&
            hasCalendarPermissions() && systemCreatesEnabled(target) }
        suspend fun available() = active() && isCurrent(expected, target.account) &&
            store.selections(target.account).singleOrNull { it.scope == target }?.let { it.enabled && it.available } == true && active()
        try {
            if (!available()) return@withLock unresolved
            captures.admitCapture = { it == target && active() }
            store.admitTransaction = { it == target && active() }
            projection.admitOperation = active
            val candidates = captures.historicalLocalIds(target, after)
            val recovered = com.patmanak.proton.andcalendarbridge.data.outgoing.recoverHistoricalCreatePage(
                target, row, marker, candidates, 25, { projection.capturedIdentityKey(target, it) }) qualify@ { localId ->
                val submission = captures.readSubmission(target, localId) ?: return@qualify null
                val reservation = submission.reservation.intent.reservation
                if (reservation.scope != target || reservation.localId != localId || reservation.format != remote.format ||
                    captures.readReservation(target, localId) != reservation ||
                    captures.readIntent(target, localId) != submission.reservation.intent ||
                    captures.readSubmissionReservation(target, localId) != submission.reservation) return@qualify null
                val identity = CapturedCreateDispatchIdentity.of(submission)
                val attempt = journal.read(target, identity.attempt.id) ?: return@qualify null
                if (attempt.state != CreateAttemptState.OUTCOME_UNKNOWN || CreateAttemptIdentity.of(attempt) != identity.attempt ||
                    journal.capturedIdentity(target, attempt.id) != identity) return@qualify null
                val binding = journal.capturedBinding(identity) ?: return@qualify null
                val attachment = journal.capturedAttachment(binding) ?: return@qualify null
                val canonical = capturedCreateExpectation(submission, remote as? CalendarCreateSubmissionPort) ?: return@qualify null
                if (!available() || captures.readSubmission(target, localId) != submission ||
                    journal.capturedBinding(identity) != binding || journal.capturedAttachment(binding) != attachment) return@qualify null
                val original = CapturedCreateBaseline(attachment, RemoteChange.Upsert(binding.eventId, binding.verifiedRevision, canonical))
                // This accessor validates the retained historical baseline and PROVEN ACK too.
                // The pre-handoff baseline accessor deliberately refuses every transferred event.
                val role = store.capturedHandoff(target, binding.eventId) ?: return@qualify null
                if (role.frame.receipt.acknowledgement.baseline != original ||
                    role.state != CapturedCreateHandoffState.RELEASED || !handoffSource(role.frame, active)) return@qualify null
                val clean = projection.inspectCapturedHandoffBase(role.frame)?.frame == role.frame
                val dirty = if (!clean) projection.captureCapturedHandoffCurrent(role.frame) as?
                    CapturedHandoffCurrentObservation.Supported else null
                if ((!clean && dirty?.snapshot?.frame != role.frame) ||
                    !projection.inspectCapturedIdentityRow(target, localId, row) || !available() ||
                    captures.readSubmission(target, localId) != submission ||
                    store.capturedHandoff(target, binding.eventId) != role || !handoffSource(role.frame, active)) return@qualify null
                com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateWork(target, row, localId,
                    reservation.id, submission.reservation.attempt, reservation.timestamp,
                    com.patmanak.proton.andcalendarbridge.data.outgoing.SystemCreateWorkStatus.COMPLETE)
            }
            if (available()) recovered else unresolved
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { unresolved }
        finally {
            captures.admitCapture = { false }
            store.admitTransaction = { false }
            projection.admitOperation = { false }
        }
    }

    /** Retain only the first authenticated captured identity. No original-row ACK or projection. */
    suspend fun bindLocalCreateObservation(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, port: CalendarCreateSubmissionPort? = null): CapturedCreateBindingResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateBindingResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateBindingResult.UNRESOLVED
        ensureCreateLifecycle()
        capturedCreateRestoration(target, captures, journal).bind(localId, id, attempt, port)
    }

    /** Original-row metadata only; the incoming hold is not released by this operation. */
    suspend fun attachLocalCreateBinding(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateAttachmentResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateAttachmentResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateAttachmentResult.UNRESOLVED
        ensureCreateLifecycle()
        capturedCreateRestoration(target, captures, journal).attach(localId, id, attempt)
    }

    /** Retain the verified sent baseline only. Never clean the original row or release its hold. */
    suspend fun retainLocalCreateBaseline(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateBaselineResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateBaselineResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateBaselineResult.UNRESOLVED
        ensureCreateLifecycle()
        capturedCreateRestoration(target, captures, journal).retainBaseline(localId, id, attempt)
    }

    /** Explicit one-shot clean acknowledgement of an unchanged captured original.
     * ACKNOWLEDGED proves a historical batch only; incoming holds and remote evidence remain.
     */
    suspend fun acknowledgeLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateAcknowledgementResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateAcknowledgementResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateAcknowledgementResult.UNRESOLVED
        ensureCreateLifecycle()
        capturedCreateRestoration(target, captures, journal).acknowledge(localId, id, attempt)
    }

    /** Explicit offline point-in-time qualification; no ACK, durable clean state or hold release. */
    suspend fun qualifyAcknowledgedLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateCleanResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateCleanResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateCleanResult.UNRESOLVED
        ensureCreateLifecycle()
        capturedCreateRestoration(target, captures, journal).qualifyClean(localId, id, attempt)
    }

    /** Snapshot and retain the current local intention only; no Provider ACK or hold release. */
    suspend fun reconcileLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, mutation: MutationId): CapturedCreateReconciliationResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
        ensureCreateLifecycle()
        capturedCreateRestoration(target, captures, journal).reconcile(localId, id, attempt, mutation)
    }

    /** The caller supplies its original epoch and phase-specific consent predicate. */
    private fun capturedCreateHandoff(target: CalendarScope, captures: LocalCreateStore,
        journal: CreateAttemptStore, expected: Long, active: () -> Boolean,
        denied: java.util.concurrent.atomic.AtomicBoolean? = null) =
        CapturedCreateHandoff(target, store, projection, captures, journal, scheduler,
            currentFormat = { remote.format }, active = active,
            currentAccount = { account -> isCurrent(expected, account).also { if (!it) denied?.set(true) } && denied?.get() != true },
            source = { frame -> handoffSource(frame, active) }, expectations = remote as? CalendarCreateSubmissionPort)

    /** Explicit transfer; never submit again or adopt an original through ordinary projection. */
    suspend fun handoffLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateHandoffResult = mutex.withLock {
        handoffLocalCreateLocked(target, localId, id, attempt)
    }

    private suspend fun handoffLocalCreateLocked(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId): CapturedCreateHandoffResult {
        val captures = localCreates ?: return CapturedCreateHandoffResult.UNRESOLVED
        val journal = creates ?: return CapturedCreateHandoffResult.UNRESOLVED
        ensureCreateLifecycle()
        val expected = generation()
        val policy = policyRevision()
        val active = { authenticated() && generation() == expected && policyRevision() == policy && hasCalendarPermissions() }
        return capturedCreateHandoff(target, captures, journal, expected, active).transfer(localId, id, attempt)
    }

    /** Immutable local/dispatch provenance is checked separately from the mutable import reference. */
    suspend fun handoffSource(frame: CapturedCreateHandoffFrame, active: () -> Boolean): Boolean {
        val captures = localCreates ?: return false
        val journal = creates ?: return false
        val original = frame.receipt.acknowledgement.baseline
        val attachment = original.receipt
        val identity = attachment.binding.identity
        if (!active() || remote.format != frame.format || projection.capturedHandoffProtocol != frame.receipt.protocol ||
            projection.capturedAcknowledgementProtocol != frame.receipt.acknowledgement.protocol ||
            projection.capturedAttachmentProtocol != attachment.protocol ||
            store.transaction(frame.scope) { checkpoint?.format } != frame.format) return false
        val previous = captures.admitCapture
        captures.admitCapture = { it == frame.scope && active() }
        try {
            val submission = captures.readSubmission(frame.scope, identity.localId) ?: return false
            val canonical = capturedCreateExpectation(submission, remote as? CalendarCreateSubmissionPort) ?: return false
            return CapturedCreateDispatchIdentity.of(submission) == identity &&
                canonical == original.change.content && captures.readSubmission(frame.scope, identity.localId) == submission &&
                journal.capturedBinding(identity) == attachment.binding &&
                journal.capturedAttachment(attachment.binding) == attachment && remote.format == frame.format &&
                store.transaction(frame.scope) { checkpoint?.format } == frame.format && active()
        } finally { captures.admitCapture = previous }
    }


    /** Explicit offline series capture only. It grants no preparation or sending capability. */
    suspend fun captureHandoffSeriesEdit(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, mutation: MutationId? = null,
        beforeNewOwner: suspend () -> Unit = {}): CapturedCreateReconciliationResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
        ensureCreateLifecycle()
        val expected = generation(); val policy = policyRevision()
        val denied = java.util.concurrent.atomic.AtomicBoolean(false)
        val active = {
            val admitted = authenticated() && generation() == expected && policyRevision() == policy && hasCalendarPermissions() &&
                (mutation != null || systemCreatesEnabled(target))
            if (!admitted && mutation == null) denied.set(true)
            admitted && !denied.get()
        }
        capturedCreateHandoff(target, captures, journal, expected, active, if (mutation == null) denied else null)
            .captureSeriesEdit(localId, id, attempt, mutation, beforeNewOwner)
    }

    /** Retain a later dirty intention against the current reference, never the original sent base.
     * Automatic observations reuse an existing durable owner, or allocate a distinct new UUID.
     * Explicit caller identities retain their original strict matching behavior.
     */
    suspend fun reconcileHandoffLocalCreate(target: CalendarScope, localId: LocalEventId, id: MutationId,
        attempt: MutationId, mutation: MutationId? = null,
        beforeNewOwner: suspend () -> Unit = {}): CapturedCreateReconciliationResult = mutex.withLock {
        val captures = localCreates ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
        val journal = creates ?: return@withLock CapturedCreateReconciliationResult.UNRESOLVED
        ensureCreateLifecycle()
        val expected = generation()
        val policy = policyRevision()
        val denied = java.util.concurrent.atomic.AtomicBoolean(false)
        val active = {
            val admitted = authenticated() && generation() == expected && policyRevision() == policy && hasCalendarPermissions() &&
                (mutation != null || systemCreatesEnabled(target))
            if (!admitted && mutation == null) denied.set(true)
            admitted && !denied.get()
        }
        val handoff = capturedCreateHandoff(target, captures, journal, expected, active, if (mutation == null) denied else null)
        val result = handoff.reconcile(localId, id, attempt, mutation, beforeNewOwner)
        if (mutation == null && result == CapturedCreateReconciliationResult.UNSUPPORTED)
            handoff.captureSeriesEdit(localId, id, attempt, null, beforeNewOwner) else result
    }

}
