package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemPersonalEditProfile
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Historical title preparation/dispatch composition retained for explicit internal callers. */
internal class ImportCoordinatorTitleWrites(
    private val store: ImportStore,
    private val remote: ReadOnlyCalendarRemote,
    private val projection: CalendarProjection,
    private val mutex: Mutex,
    private val generation: () -> Long,
    private val policyRevision: () -> Long,
    private val authenticated: () -> Boolean,
    private val currentAccount: () -> AccountId?,
    private val systemCreatesEnabled: (CalendarScope) -> Boolean,
    private val systemTitlesEnabled: (CalendarScope) -> Boolean,
    private val hasCalendarPermissions: () -> Boolean,
    private val isCurrent: suspend (Long, AccountId) -> Boolean,
    private val capturedRawHold: suspend (CalendarScope) -> CapturedProjectionHold,
    private val handoffSource: suspend (CapturedCreateHandoffFrame, () -> Boolean) -> Boolean,
) {
    /** Internal submission followed by guarded result retention under the same original epoch. */
    suspend fun submitAndRetainLocalTitleAttempt(record: TitleUpdateAttemptRecord,
        attempts: TitleUpdateAttemptJournal, results: TitleUpdateResultJournal,
        port: CalendarTitleUpdateSubmissionPort, allowNetwork: Boolean = false): TitleUpdateVerifiedResult? =
        submitLocalTitleAttemptGuarded(record, attempts, port, allowNetwork, results).verifiedResult

    /** Internal submission of already durable wire; offline by default. The local callback
     * observes ownership without re-entering authentication while its submission lock is held.
     * A verified observation does not acknowledge Android or durably store a verified result.
     */
    suspend fun submitLocalTitleAttempt(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
        port: CalendarTitleUpdateSubmissionPort, allowNetwork: Boolean = false): TitleUpdateSubmissionObservation =
        submitLocalTitleAttemptGuarded(record, journal, port, allowNetwork, null).observation

    private class TitleSubmissionOutcome(
        val observation: TitleUpdateSubmissionObservation = TitleUpdateSubmissionObservation.Unknown,
        val verifiedResult: TitleUpdateVerifiedResult? = null,
    )

    private suspend fun submitLocalTitleAttemptGuarded(record: TitleUpdateAttemptRecord,
        journal: TitleUpdateAttemptJournal, port: CalendarTitleUpdateSubmissionPort,
        allowNetwork: Boolean, results: TitleUpdateResultJournal?, systemTitle: Boolean = false,
        admission: (CalendarScope) -> Boolean = { true }): TitleSubmissionOutcome {
        val unresolved = TitleSubmissionOutcome()
        val frozen = record.frozen()
        currentCoroutineContext().ensureActive()
        if (!allowNetwork || frozen.state != TitleUpdateAttemptState.PREPARED || frozen.protectedEvidence != null)
            return unresolved
        return mutex.withLock {
            val target = frozen.scope
            val qualification = localTitleOwnerQualification(frozen.preparation, frozen.format, port,
                generation(), policyRevision(), systemTitle, admission)
            var closed = false
            val lease = { scope: CalendarScope ->
                if (scope != target || closed) false else qualification.active().also { if (!it) closed = true }
            }
            suspend fun active(): Boolean {
                currentCoroutineContext().ensureActive()
                val allowed = lease(target)
                currentCoroutineContext().ensureActive()
                return allowed
            }
            val source: suspend (CalendarScope) -> Boolean = { scope ->
                currentCoroutineContext().ensureActive()
                if (scope != target || !active()) false else {
                    val qualified = try { qualification.localSourceQualification() == null }
                    catch (failure: Exception) { closed = true; currentCoroutineContext().ensureActive(); throw failure }
                    if (!qualified) closed = true
                    currentCoroutineContext().ensureActive()
                    val stillActive = active()
                    qualified && stillActive
                }
            }
            suspend fun currentSource(): Boolean {
                if (!active()) return false
                // port.submit has returned and released its authentication mutex. Fresh account
                // observations are safe here and cannot replace this invocation's original epoch.
                val qualified = qualification.available() && source(target)
                if (!qualified) closed = true
                val stillActive = active()
                return qualified && stillActive
            }
            try {
                // This account lookup is deliberately outside port.submit's authentication lock.
                if (!qualification.available() || !active()) return@withLock unresolved
                store.admitTransaction = lease
                projection.admitOperation = { lease(target) }
                val stored = journal.read(target, frozen.id)?.frozen()
                if (!active() || stored != frozen || !source(target)) return@withLock unresolved
                val result = port.submit(frozen, journal, lease, source)
                currentCoroutineContext().ensureActive()
                if (!active() || result !is TitleUpdateSubmissionObservation.Verified ||
                    result.attempt != TitleUpdateAttemptIdentity.of(frozen) ||
                    result.event.eventId != frozen.preparation.intent.base.eventId ||
                    result.event.content != frozen.preparation.intent.content)
                    return@withLock unresolved
                val durable = journal.read(target, frozen.id)?.frozen()
                if (!active() || durable == null || durable.state != TitleUpdateAttemptState.OUTCOME_UNKNOWN ||
                    durable.protectedEvidence == null ||
                    durable.copy(state = TitleUpdateAttemptState.PREPARED, protectedEvidence = null) != frozen || !source(target))
                    return@withLock unresolved
                if (results == null) return@withLock TitleSubmissionOutcome(result)
                if (!currentSource()) return@withLock unresolved
                val verified = TitleUpdateVerifiedResult.of(durable, result)
                val committed = results.retainVerified(verified, lease)
                // Recheck even false/null results: a dependency may have swallowed cancellation.
                if (!currentSource() || !committed) return@withLock unresolved
                val retained = results.readVerified(target, frozen.id)
                if (!currentSource() || retained != verified) return@withLock unresolved
                TitleSubmissionOutcome(result, retained)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { currentCoroutineContext().ensureActive(); unresolved }
              finally {
                  closed = true
                  store.admitTransaction = { false }
                  projection.admitOperation = { false }
              }
        }
    }

    /** Current source, with complete provenance required for a transferred CREATE. */
    private suspend fun systemTitleSource(intent: LocalEditIntent, active: () -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        if (!active() || !LocalTitleOwnerQualification.systemTitleProfile(intent)) return false
        val target = intent.scope
        val event = intent.base.eventId
        if (store.localEdit(target, event) != intent || !active()) return false
        val role = store.capturedHandoff(target, event)
        if (!active()) return false
        val hold = capturedRawHold(target)
        if (!active() || hold.scopeBlocked || (role == null && hold.holds(event))) return false
        val content = if (role == null) (if (SystemPersonalEditProfile.requiresExtendedSnapshot(intent))
            projection.captureOwnedPersonalEdit(target, intent.base)
            else projection.captureOwnedSimpleEdit(target, intent.base))?.content else {
            if (!currentSystemTitleHandoff(role, intent, active)) return false
            val observed = projection.captureCapturedHandoffCurrent(role.frame) as? CapturedHandoffCurrentObservation.Supported
            observed?.snapshot?.takeIf { it.frame == role.frame && !it.deleted }?.content
        }
        currentCoroutineContext().ensureActive()
        if (!active() || content != intent.content) return false
        if (store.capturedHandoff(target, event) != role || !active()) return false
        if (role != null && !currentSystemTitleHandoff(role, intent, active)) return false
        val after = capturedRawHold(target)
        currentCoroutineContext().ensureActive()
        return active() && !after.scopeBlocked && (role != null || !after.holds(event)) &&
            store.capturedHandoff(target, event) == role && store.localEdit(target, event) == intent && active()
    }

    suspend fun currentSystemTitleHandoff(role: CapturedCreateHandoffRecord, intent: LocalEditIntent,
        active: () -> Boolean): Boolean {
        if (!active() || !LocalTitleOwnerQualification.matchesReleasedHandoff(role, intent)) return false
        val pending = store.capturedHandoffProjection(role.frame)
        currentCoroutineContext().ensureActive()
        if (!active() || pending != null) return false
        val qualified = handoffSource(role.frame, active)
        currentCoroutineContext().ensureActive()
        return qualified && active()
    }

    /** Production-only opt-in preparation. Capsule and wire keep the original epoch and timestamp. */
    suspend fun prepareSystemTitle(target: CalendarScope, event: EventId, owner: MutationId,
        attempt: MutationId, timestamp: java.time.Instant, journal: TitleUpdatePreparationJournal,
        localPort: CalendarLocalEditPreparationPort, titlePort: CalendarTitleUpdateSubmissionPort,
        admission: (CalendarScope) -> Boolean, existingOnly: Boolean = false): LocalTitleAttemptPreparation {
        var expected = -1L
        var policy = -1L
        var capsule: LocalEditPreparation? = null
        val refusal = mutex.withLock {
            expected = generation(); policy = policyRevision()
            val active = { authenticated() && generation() == expected && policyRevision() == policy &&
                systemCreatesEnabled(target) && systemTitlesEnabled(target) && hasCalendarPermissions() && admission(target) }
            try {
                if (!active() || !isCurrent(expected, target.account)) return@withLock LocalTitleAttemptPreparation.Inactive
                store.admitTransaction = { it == target && active() }
                projection.admitOperation = active
                val intent = store.localEdit(target, event) ?: return@withLock LocalTitleAttemptPreparation.Unqualified
                if (intent.id != owner || !systemTitleSource(intent, active)) return@withLock LocalTitleAttemptPreparation.Unsupported
                val retained = store.localEditPreparation(target, event)
                if (!active()) return@withLock LocalTitleAttemptPreparation.Inactive
                if (existingOnly && retained == null) return@withLock LocalTitleAttemptPreparation.Unsupported
                val actualTime = retained?.timestamp ?: timestamp
                val result = prepareLocalTitleLocked(target, event, owner, actualTime, localPort, expected, policy,
                    systemTitle = true, admission = admission)
                if (!active()) return@withLock LocalTitleAttemptPreparation.Inactive
                when (result) {
                    LocalTitlePreparationResult.RETAINED, LocalTitlePreparationResult.HISTORICAL -> Unit
                    LocalTitlePreparationResult.INACTIVE -> return@withLock LocalTitleAttemptPreparation.Inactive
                    LocalTitlePreparationResult.CONFLICT -> return@withLock LocalTitleAttemptPreparation.Conflict
                    LocalTitlePreparationResult.UNSUPPORTED -> return@withLock LocalTitleAttemptPreparation.Unsupported
                    LocalTitlePreparationResult.UNAVAILABLE -> return@withLock LocalTitleAttemptPreparation.Unavailable
                    else -> return@withLock LocalTitleAttemptPreparation.Unqualified
                }
                store.admitTransaction = { it == target && active() }
                capsule = store.localEditPreparation(target, event)
                if (!active() || capsule?.intent != intent || capsule?.timestamp != actualTime)
                    return@withLock LocalTitleAttemptPreparation.Unqualified
                null
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { currentCoroutineContext().ensureActive(); LocalTitleAttemptPreparation.Unavailable }
              finally { store.admitTransaction = { false }; projection.admitOperation = { false } }
        }
        if (refusal != null) return refusal
        val prepared = capsule ?: return LocalTitleAttemptPreparation.Unresolved
        val format = titlePort.titleUpdateFormat ?: return LocalTitleAttemptPreparation.Unsupported
        val lease = { scope: CalendarScope -> scope == target && generation() == expected &&
            policyRevision() == policy && admission(scope) }
        return prepareLocalTitleAttempt(TitleUpdatePreparationReservation(attempt, prepared, format),
            journal, titlePort, allowNetwork = true, systemTitle = true, admission = lease)
    }

    /** Production title submission; the original worker admission participates through commit. */
    suspend fun submitSystemTitle(record: TitleUpdateAttemptRecord, journal: TitleUpdateRecoveryJournal,
        port: CalendarTitleUpdateSubmissionPort, admission: (CalendarScope) -> Boolean): TitleUpdateVerifiedResult? =
        submitLocalTitleAttemptGuarded(record, journal, port, true, journal, systemTitle = true,
            admission = admission).verifiedResult

    private fun localTitleOwnerQualification(preparation: LocalEditPreparation, format: AdapterFormat,
        port: CalendarTitleUpdateSubmissionPort, expected: Long, policy: Long,
        systemTitle: Boolean = false, admission: (CalendarScope) -> Boolean = { true }): LocalTitleOwnerQualification {
        val target = preparation.intent.scope
        return LocalTitleOwnerQualification(preparation, format, port, store, projection,
            currentFormat = { remote.format },
            baseActive = { authenticated() && generation() == expected && policyRevision() == policy &&
                systemCreatesEnabled(target) && hasCalendarPermissions() && admission(target) &&
                (!systemTitle || systemTitlesEnabled(target)) },
            currentAccount = { isCurrent(expected, target.account) },
            cachedAccount = { currentAccount() == target.account },
            systemTitle = systemTitle,
            capturedHold = { capturedRawHold(target) },
            handoffSource = { frame, active -> handoffSource(frame, active) })
    }

    /** Prepare or recover reserved wire for the exact current native edit. Offline by default;
     * read-only preparation eligibility never grants claim, submission or acknowledgement.
     */
    suspend fun prepareLocalTitleAttempt(candidate: TitleUpdatePreparationReservation,
        journal: TitleUpdatePreparationJournal, port: CalendarTitleUpdateSubmissionPort,
        allowNetwork: Boolean = false, systemTitle: Boolean = false,
        admission: (CalendarScope) -> Boolean = { true }): LocalTitleAttemptPreparation {
        val frozen = candidate.frozen()
        return mutex.withLock {
            val target = frozen.scope
            val qualification = localTitleOwnerQualification(frozen.preparation, frozen.format, port,
                generation(), policyRevision(), systemTitle, admission)
            var refusal: LocalTitleAttemptRetentionResult? = null
            val lease = { scope: CalendarScope -> scope == target && refusal == null && qualification.active() }
            try {
                if (!qualification.available(formats = false)) return@withLock LocalTitleAttemptPreparation.Inactive
                val format = port.titleUpdateFormat ?: return@withLock LocalTitleAttemptPreparation.Unsupported
                if (format != frozen.format || remote.format != frozen.preparation.intent.format)
                    return@withLock LocalTitleAttemptPreparation.Unqualified
                store.admitTransaction = lease
                projection.admitOperation = { lease(target) }
                val durable = TitleUpdatePreparationRecovery(journal, port).prepare(frozen, allowNetwork, lease) {
                    // Once denied, this invocation cannot borrow a restored source or a new epoch.
                    if (refusal == null) refusal = qualification.sourceQualification()
                    refusal == null
                }
                currentCoroutineContext().ensureActive()
                refusal?.let { return@withLock titleAttemptPreparationRefusal(it) }
                if (!qualification.active()) return@withLock LocalTitleAttemptPreparation.Inactive
                // The helper has just requalified the source and read the canonical durable wire.
                // No new asynchronous observation may substitute an owner after that boundary.
                if (durable == null) LocalTitleAttemptPreparation.Unresolved
                else LocalTitleAttemptPreparation.Prepared(durable)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: LocalEditPreparationException) {
                  currentCoroutineContext().ensureActive()
                  if (!qualification.active()) LocalTitleAttemptPreparation.Inactive else when (failure.reason) {
                      LocalEditPreparationFailure.AUTHENTICATION, LocalEditPreparationFailure.PERMISSION -> LocalTitleAttemptPreparation.Inactive
                      LocalEditPreparationFailure.CONFLICT -> LocalTitleAttemptPreparation.Conflict
                      LocalEditPreparationFailure.UNSUPPORTED -> LocalTitleAttemptPreparation.Unsupported
                      else -> LocalTitleAttemptPreparation.Unavailable
                  }
              } catch (_: Exception) {
                  currentCoroutineContext().ensureActive()
                  if (!qualification.active()) LocalTitleAttemptPreparation.Inactive else LocalTitleAttemptPreparation.Unavailable
              } finally {
                  store.admitTransaction = { false }
                  projection.admitOperation = { false }
              }
        }
    }

    private fun titleAttemptPreparationRefusal(reason: LocalTitleAttemptRetentionResult) = when (reason) {
        LocalTitleAttemptRetentionResult.INACTIVE -> LocalTitleAttemptPreparation.Inactive
        LocalTitleAttemptRetentionResult.UNQUALIFIED -> LocalTitleAttemptPreparation.Unqualified
        LocalTitleAttemptRetentionResult.UNSUPPORTED -> LocalTitleAttemptPreparation.Unsupported
        LocalTitleAttemptRetentionResult.UNRESOLVED -> LocalTitleAttemptPreparation.Unresolved
        LocalTitleAttemptRetentionResult.CONFLICT -> LocalTitleAttemptPreparation.Conflict
        else -> LocalTitleAttemptPreparation.Unavailable
    }

    /** Bind already prepared wire to the exact current owner under one access epoch.
     * This local composition never prepares, claims, submits, inspects the service or writes
     * the Provider. Durable PREPARED work is not authority for a subsequent dispatch.
     */
    suspend fun retainLocalTitleAttempt(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal,
        port: CalendarTitleUpdateSubmissionPort): LocalTitleAttemptRetentionResult {
        val frozen = record.frozen()
        return mutex.withLock {
            val target = frozen.scope
            val intent = frozen.preparation.intent
            val qualification = localTitleOwnerQualification(frozen.preparation, frozen.format, port,
                generation(), policyRevision())
            val active = qualification.active
            suspend fun available(formats: Boolean = true) = qualification.available(formats)
            suspend fun sourceQualification() = qualification.sourceQualification()
            try {
                if (!available(formats = false)) return@withLock LocalTitleAttemptRetentionResult.INACTIVE
                if (frozen.state != TitleUpdateAttemptState.PREPARED || frozen.protectedEvidence != null)
                    return@withLock LocalTitleAttemptRetentionResult.UNQUALIFIED
                val format = port.titleUpdateFormat ?: return@withLock LocalTitleAttemptRetentionResult.UNSUPPORTED
                if (format != frozen.format || remote.format != intent.format)
                    return@withLock LocalTitleAttemptRetentionResult.UNQUALIFIED
                if (!available()) return@withLock LocalTitleAttemptRetentionResult.INACTIVE
                store.admitTransaction = { it == target && active() }
                projection.admitOperation = active
                val existing = journal.read(target, frozen.id)
                if (!available()) return@withLock LocalTitleAttemptRetentionResult.INACTIVE
                if (existing != null && existing.state != TitleUpdateAttemptState.PREPARED)
                    return@withLock LocalTitleAttemptRetentionResult.UNRESOLVED
                if (existing != null && existing != frozen) return@withLock LocalTitleAttemptRetentionResult.UNQUALIFIED
                sourceQualification()?.let { return@withLock it }
                // Exact repetitions still validate complete journal history inside this
                // guarded transaction; read()==record alone never qualifies HISTORICAL.
                val retained = journal.remember(frozen) { it == target && active() }
                if (!available()) return@withLock LocalTitleAttemptRetentionResult.INACTIVE
                if (!retained) return@withLock LocalTitleAttemptRetentionResult.UNRESOLVED
                val durable = journal.read(target, frozen.id)
                if (!available()) return@withLock LocalTitleAttemptRetentionResult.INACTIVE
                if (durable != frozen) return@withLock LocalTitleAttemptRetentionResult.UNRESOLVED
                // Independent databases and Provider operations have no distributed commit.
                // Preserve a committed wire but do not report current qualification if its
                // owner or native contents changed during retention/readback.
                sourceQualification()?.let { return@withLock it }
                if (!available()) return@withLock LocalTitleAttemptRetentionResult.INACTIVE
                if (existing == null) LocalTitleAttemptRetentionResult.RETAINED else LocalTitleAttemptRetentionResult.HISTORICAL
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) {
                  currentCoroutineContext().ensureActive()
                  if (!active()) LocalTitleAttemptRetentionResult.INACTIVE else LocalTitleAttemptRetentionResult.UNAVAILABLE
              } finally {
                  store.admitTransaction = { false }
                  projection.admitOperation = { false }
              }
        }
    }

    /** Explicit preparation of a captured title edit; never dispatches or acknowledges Android. */
    suspend fun prepareLocalTitle(target: CalendarScope, eventId: EventId, id: MutationId,
        timestamp: java.time.Instant, port: CalendarLocalEditPreparationPort): LocalTitlePreparationResult =
        mutex.withLock { prepareLocalTitleLocked(target, eventId, id, timestamp, port, generation(), policyRevision()) }

    suspend fun prepareLocalTitleLocked(target: CalendarScope, eventId: EventId, id: MutationId,
        timestamp: java.time.Instant, port: CalendarLocalEditPreparationPort,
        expected: Long, policy: Long, systemTitle: Boolean = false,
        admission: (CalendarScope) -> Boolean = { true }): LocalTitlePreparationResult {
        val active = { authenticated() && generation() == expected && policyRevision() == policy && hasCalendarPermissions() &&
            admission(target) && (!systemTitle || (systemCreatesEnabled(target) && systemTitlesEnabled(target))) }
        if (!isCurrent(expected, target.account) || !active()) return LocalTitlePreparationResult.INACTIVE
        store.admitTransaction = { it == target && active() }
        var captured: LocalEditIntent? = null
        return try {
            val selected = store.selections(target.account).singleOrNull { it.scope == target }
            if (selected?.enabled != true || !selected.available) return LocalTitlePreparationResult.INACTIVE
            val format = store.transaction(target) { checkpoint?.format }
            if (format != remote.format) return LocalTitlePreparationResult.UNQUALIFIED
            val owner = store.localEdit(target, eventId) ?: return LocalTitlePreparationResult.UNQUALIFIED
            if (owner.id != id || owner.format != format) return LocalTitlePreparationResult.UNQUALIFIED
            if (owner.state == LocalEditState.CONFLICT) return LocalTitlePreparationResult.CONFLICT
            val frozen = try {
                require(timestamp.nano == 0 && timestamp.atOffset(java.time.ZoneOffset.UTC).year in 1970..9999)
                LocalEditPreparation.freezeIntent(owner)
            } catch (_: Exception) { return LocalTitlePreparationResult.UNQUALIFIED }
            captured = frozen
            if (systemTitle) {
                projection.admitOperation = active
                if (!systemTitleSource(frozen, active)) return LocalTitlePreparationResult.UNSUPPORTED
            }
            val preparationFormat = port.localTitleFormat ?: return LocalTitlePreparationResult.UNSUPPORTED
            val admission = store.assessLocalTitlePreparation(frozen)
            if (admission != LocalTitlePreparationAdmission.ELIGIBLE) {
                if (!isCurrent(expected, target.account) || !active()) return LocalTitlePreparationResult.INACTIVE
                return titlePreparationResult(admission)
            }
            // No store/auth mutex is held while the adapter runs its serialized authenticated reads.
            val prepared = if (systemTitle) port.prepareLocalTitle(frozen, timestamp, { it == target && active() })
                else port.prepareLocalTitle(frozen, timestamp)
            currentCoroutineContext().ensureActive()
            if (prepared == null) return if (active()) LocalTitlePreparationResult.UNAVAILABLE else LocalTitlePreparationResult.INACTIVE
            if (prepared.intent != frozen || prepared.timestamp != timestamp || prepared.format != preparationFormat ||
                port.localTitleFormat != preparationFormat || remote.format != format)
                return LocalTitlePreparationResult.UNQUALIFIED
            if (!isCurrent(expected, target.account) || !active()) return LocalTitlePreparationResult.INACTIVE
            // SQL and lease checks run again in the same transaction as first insertion.
            store.admitTransaction = { it == target && active() && port.localTitleFormat == preparationFormat && remote.format == format }
            titlePreparationResult(store.retainCurrentLocalTitlePreparation(prepared))
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: LocalEditPreparationException) {
              when (failure.reason) {
                  LocalEditPreparationFailure.CONFLICT -> {
                      try {
                          val owner = captured
                          if (owner == null || !isCurrent(expected, target.account) || !active()) LocalTitlePreparationResult.INACTIVE
                          else titlePreparationResult(store.conflictLocalTitlePreparation(owner))
                      } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { LocalTitlePreparationResult.UNAVAILABLE }
                  }
                  LocalEditPreparationFailure.AUTHENTICATION, LocalEditPreparationFailure.PERMISSION -> LocalTitlePreparationResult.INACTIVE
                  LocalEditPreparationFailure.UNSUPPORTED -> LocalTitlePreparationResult.UNSUPPORTED
                  else -> LocalTitlePreparationResult.UNAVAILABLE
              }
          } catch (_: Exception) { LocalTitlePreparationResult.UNAVAILABLE }
          finally { store.admitTransaction = { false } }
    }

    private fun titlePreparationResult(admission: LocalTitlePreparationAdmission) = when (admission) {
        LocalTitlePreparationAdmission.STORED -> LocalTitlePreparationResult.RETAINED
        LocalTitlePreparationAdmission.HISTORICAL -> LocalTitlePreparationResult.HISTORICAL
        LocalTitlePreparationAdmission.CONFLICT -> LocalTitlePreparationResult.CONFLICT
        LocalTitlePreparationAdmission.INACTIVE -> LocalTitlePreparationResult.INACTIVE
        else -> LocalTitlePreparationResult.UNQUALIFIED
    }

}
