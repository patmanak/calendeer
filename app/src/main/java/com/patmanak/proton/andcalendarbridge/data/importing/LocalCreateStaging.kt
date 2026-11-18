package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.LocalCreateProfile
import com.patmanak.proton.andcalendarbridge.data.outgoing.LocalCreateStore
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Capture and protected preparation under the coordinator's existing mutex and original epoch.
 * This collaborator does not dispatch or acknowledge CREATE. Only a newly retained submission
 * calls recordFreshSubmission; reading historical capsules cannot restore a send lease. */
internal class LocalCreateStaging(
    private val target: CalendarScope,
    private val store: ImportStore,
    private val projection: CalendarProjection,
    private val journal: LocalCreateStore,
    private val currentFormat: () -> AdapterFormat,
    private val active: () -> Boolean,
    private val currentAccount: suspend () -> Boolean,
    private val recordFreshSubmission: (MutationId, LocalCreateSubmission) -> Unit,
) {
    private fun installAdmission(admitted: () -> Boolean = active) {
        store.admitTransaction = { it == target && admitted() }
        projection.admitOperation = admitted
        journal.admitCapture = { it == target && admitted() }
    }

    private fun clearAdmission() {
        store.admitTransaction = { false }
        projection.admitOperation = { false }
        journal.admitCapture = { false }
    }
    suspend fun captureOccurrence(master: RemoteChange.Upsert, eventRow: Long, localId: LocalEventId,
        id: MutationId, timestamp: Instant): LocalEditCaptureResult {
        val frozen = try { OccurrenceEditCandidate.freezeMaster(master) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { currentCoroutineContext().ensureActive(); return LocalEditCaptureResult.UNQUALIFIED }
        installAdmission()
        return try {
            val format = currentFormat()
            suspend fun qualified(): Boolean {
                if (!currentAccount() || !active() || currentFormat() != format) return false
                val selected = store.selections(target.account).singleOrNull { it.scope == target }
                return selected?.enabled == true && selected.available &&
                    store.transaction(target) { checkpoint?.format } == format &&
                    store.projectedBaseline(target, frozen.eventId) == frozen && store.localEdit(target, frozen.eventId) == null && active()
            }
            if (!qualified()) return LocalEditCaptureResult.UNQUALIFIED
            val previous = journal.readReservation(target, localId)
            val retainedContext = journal.readOccurrenceContext(target, localId)
            if (previous != null && (retainedContext == null || previous.id != id || previous.format != format ||
                    retainedContext.occurrence?.master != frozen)) return LocalEditCaptureResult.UNQUALIFIED
            val retained = journal.readIntent(target, localId)
            if (retained != null) return if (retained == retainedContext && qualified()) LocalEditCaptureResult.RETAINED else LocalEditCaptureResult.UNQUALIFIED
            val intent = retainedContext ?: run {
                if (eventRow <= 0 || timestamp.nano != 0) return LocalEditCaptureResult.UNQUALIFIED
                val snapshot = projection.captureOwnedOccurrenceEdit(target, frozen, eventRow) ?: return LocalEditCaptureResult.UNQUALIFIED
                LocalCreateIntent(LocalCreateReservation(id, localId, target, format, timestamp),
                    snapshot.candidate.content.copy(recurrence = null), occurrence = snapshot.candidate)
            }
            if (!qualified()) return LocalEditCaptureResult.UNQUALIFIED
            val first = journal.reserveOccurrence(intent)
            if (!qualified()) return LocalEditCaptureResult.UNQUALIFIED
            val snapshot = if (first) projection.captureAndStampOccurrence(intent, eventRow) else projection.inspectStampedOccurrence(intent)
            if (snapshot?.candidate != intent.occurrence || !qualified()) return LocalEditCaptureResult.UNQUALIFIED
            journal.retain(intent)
            LocalEditCaptureResult.RETAINED
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { LocalEditCaptureResult.UNAVAILABLE }
          finally { clearAdmission() }
    }

    suspend fun capture(eventRow: Long, localId: LocalEventId, id: MutationId, timestamp: Instant,
        series: Boolean = false, autoProfile: Boolean = false): LocalEditCaptureResult {
        installAdmission()
        return try {
            val selected = store.selections(target.account).singleOrNull { it.scope == target }
            if (selected?.enabled != true || !selected.available) return LocalEditCaptureResult.INACTIVE
            val format = store.transaction(target) { checkpoint?.format }
            if (format != currentFormat()) return LocalEditCaptureResult.UNQUALIFIED
            val previous = journal.readReservation(target, localId)
            if (previous != null && (previous.id != id || previous.format != format || previous.markerProtocol != 1))
                return LocalEditCaptureResult.UNQUALIFIED
            // A retained intention is historical work, not a witness of the current Android row.
            val retained = journal.readIntent(target, localId)
            if (retained != null) {
                if (retained.occurrence != null) return LocalEditCaptureResult.UNQUALIFIED
                val identity = retained.reservation
                if (identity.id != id || identity.localId != localId || identity.scope != target ||
                    identity.format != format || identity.markerProtocol != 1 ||
                    (previous != null && identity != previous)) return LocalEditCaptureResult.UNQUALIFIED
                return LocalEditCaptureResult.RETAINED
            }
            if (previous == null && (eventRow <= 0 || timestamp.nano != 0)) return LocalEditCaptureResult.UNQUALIFIED
            val captureSeries = if (autoProfile) when (projection.localCreateProfile(target, eventRow)) {
                LocalCreateProfile.SIMPLE -> false
                LocalCreateProfile.SERIES -> true
                null -> return LocalEditCaptureResult.UNQUALIFIED
            } else series
            // Classification is an await, not permission to reserve against an obsolete owner.
            if (!currentAccount() || !active()) return LocalEditCaptureResult.INACTIVE
            val admitted = store.selections(target.account).singleOrNull { it.scope == target }
            if (admitted?.enabled != true || !admitted.available) return LocalEditCaptureResult.INACTIVE
            if (store.transaction(target) { checkpoint?.format } != format) return LocalEditCaptureResult.UNQUALIFIED
            val reservation = previous ?: LocalCreateReservation(id, localId, target, format, timestamp)
            val firstClaim = journal.reserve(reservation)
            val snapshot = if (captureSeries) {
                if (firstClaim) projection.captureAndStampSeriesCreate(target, eventRow, localId)
                else projection.inspectStampedSeriesCreate(target, localId)
            } else if (firstClaim) projection.captureAndStampSimpleCreate(target, eventRow, localId)
                else projection.inspectStampedSimpleCreate(target, localId)
            if (snapshot == null || (snapshot.series != null) != captureSeries) return LocalEditCaptureResult.UNQUALIFIED
            if (!currentAccount() || !active()) return LocalEditCaptureResult.INACTIVE
            val currentSelection = store.selections(target.account).singleOrNull { it.scope == target }
            if (currentSelection?.enabled != true || !currentSelection.available) return LocalEditCaptureResult.INACTIVE
            if (store.transaction(target) { checkpoint?.format } != format) return LocalEditCaptureResult.UNQUALIFIED
            journal.retain(LocalCreateIntent(reservation, snapshot.content, snapshot.series))
            LocalEditCaptureResult.RETAINED
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { LocalEditCaptureResult.UNAVAILABLE }
          finally {
              clearAdmission()
          }
    }

    suspend fun prepare(localId: LocalEventId, id: MutationId, port: CalendarCreatePreparationPort): LocalCreatePreparationResult {
        installAdmission()
        return try {
            val selected = store.selections(target.account).singleOrNull { it.scope == target }
            if (selected?.enabled != true || !selected.available) return LocalCreatePreparationResult.INACTIVE
            val format = store.transaction(target) { checkpoint?.format }
            if (format != currentFormat()) return LocalCreatePreparationResult.UNQUALIFIED
            val intent = journal.readIntent(target, localId) ?: return LocalCreatePreparationResult.UNQUALIFIED
            if (intent.occurrence != null) return LocalCreatePreparationResult.UNSUPPORTED
            val reservation = intent.reservation
            if (reservation.scope != target || reservation.localId != localId || reservation.id != id ||
                reservation.format != format || reservation.markerProtocol != 1) return LocalCreatePreparationResult.UNQUALIFIED
            val preparationFormat = port.capturedPreparationFormat(intent.content, intent.series) ?: return LocalCreatePreparationResult.UNSUPPORTED
            if (!projection.inspectCapturedIntent(intent).matches(intent)) return LocalCreatePreparationResult.UNQUALIFIED
            val retained = journal.readPreparation(target, localId)
            if (retained != null) {
                if (retained.intent != intent || retained.format != preparationFormat)
                    return LocalCreatePreparationResult.UNQUALIFIED
                if (!currentAccount() || !active()) return LocalCreatePreparationResult.INACTIVE
                return LocalCreatePreparationResult.RETAINED
            }
            val prepared = port.prepareCreate(CalendarCreateDraft(target, reservation.id, reservation.timestamp, intent.content, intent.series))
            if (prepared.scope != target || prepared.format != preparationFormat || port.capturedPreparationFormat(intent.content, intent.series) != preparationFormat ||
                prepared.protectedPayload.isBlank()) return LocalCreatePreparationResult.UNQUALIFIED
            if (!currentAccount() || !active()) return LocalCreatePreparationResult.INACTIVE
            val currentSelection = store.selections(target.account).singleOrNull { it.scope == target }
            if (currentSelection?.enabled != true || !currentSelection.available) return LocalCreatePreparationResult.INACTIVE
            if (store.transaction(target) { checkpoint?.format } != format) return LocalCreatePreparationResult.UNQUALIFIED
            if (!projection.inspectCapturedIntent(intent).matches(intent)) return LocalCreatePreparationResult.UNQUALIFIED
            journal.retainPreparation(LocalCreatePreparation(intent, preparationFormat, prepared.protectedPayload))
            LocalCreatePreparationResult.RETAINED
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: CreatePreparationException) {
              when (failure.reason) {
                  CreatePreparationFailure.UNSUPPORTED -> LocalCreatePreparationResult.UNSUPPORTED
                  CreatePreparationFailure.AUTHENTICATION, CreatePreparationFailure.PERMISSION -> LocalCreatePreparationResult.INACTIVE
                  else -> LocalCreatePreparationResult.UNAVAILABLE
              }
          } catch (_: Exception) { LocalCreatePreparationResult.UNAVAILABLE }
          finally {
              clearAdmission()
          }
    }

    suspend fun stageSubmission(localId: LocalEventId, id: MutationId, attempt: MutationId,
        port: CalendarCreateSubmissionPort, occurrenceEditable: () -> Boolean): LocalCreateSubmissionResult {
        installAdmission()
        return try {
            suspend fun admission(admitted: () -> Boolean = active): LocalCreateSubmissionResult? {
                if (!currentAccount() || !admitted()) return LocalCreateSubmissionResult.INACTIVE
                val selection = store.selections(target.account).singleOrNull { it.scope == target }
                if (selection?.enabled != true || !selection.available) return LocalCreateSubmissionResult.INACTIVE
                return null
            }
            admission()?.let { return it }
            val importFormat = store.transaction(target) { checkpoint?.format }
            if (importFormat != currentFormat()) return LocalCreateSubmissionResult.UNQUALIFIED
            val intent = journal.readIntent(target, localId) ?: return LocalCreateSubmissionResult.UNQUALIFIED
            val admitted = { active() && (intent.occurrence == null || occurrenceEditable()) }
            if (!admitted()) return LocalCreateSubmissionResult.INACTIVE
            installAdmission(admitted)
            val captured = intent.reservation
            if (captured.scope != target || captured.localId != localId || captured.id != id ||
                captured.format != importFormat || captured.markerProtocol != 1) return LocalCreateSubmissionResult.UNQUALIFIED
            val submissionFormat = port.capturedSubmissionFormat(intent) ?: return LocalCreateSubmissionResult.UNSUPPORTED
            val reservation = try { if (intent.occurrence == null) LocalCreateSubmissionReservation(intent, attempt, submissionFormat)
                else LocalCreateSubmissionReservation.forOccurrence(intent, attempt, submissionFormat) }
                catch (_: IllegalArgumentException) { return LocalCreateSubmissionResult.UNQUALIFIED }

            suspend fun qualify(): LocalCreateSubmissionResult? {
                admission(admitted)?.let { return it }
                if (port.capturedSubmissionFormat(intent) != submissionFormat || currentFormat() != importFormat ||
                    store.transaction(target) { checkpoint?.format } != importFormat) return LocalCreateSubmissionResult.UNQUALIFIED
                if (intent.occurrence != null && journal.readOccurrenceContext(target, localId) != intent) return LocalCreateSubmissionResult.UNQUALIFIED
                if (!projection.matchesCapturedIntent(intent) || !store.matchesOccurrenceSource(intent)) return LocalCreateSubmissionResult.UNQUALIFIED
                // Provider inspection and SQLite are separate. Recheck app state after every external await.
                admission(admitted)?.let { return it }
                if (port.capturedSubmissionFormat(intent) != submissionFormat || currentFormat() != importFormat ||
                    store.transaction(target) { checkpoint?.format } != importFormat) return LocalCreateSubmissionResult.UNQUALIFIED
                if (!admitted()) return LocalCreateSubmissionResult.INACTIVE
                return null
            }
            qualify()?.let { return it }
            val existing = journal.readSubmissionReservation(target, localId)
            if (existing != null && existing != reservation) return LocalCreateSubmissionResult.UNQUALIFIED
            if (existing != null) {
                val retained = journal.readSubmission(target, localId)
                qualify()?.let { return it }
                return if (retained != null) LocalCreateSubmissionResult.RETAINED else LocalCreateSubmissionResult.UNRESOLVED
            }
            val permit = journal.reserveSubmission(reservation)
            if (permit == null) {
                // Another helper won. Never acquire its preparation right or regenerate a blob.
                val retained = journal.readSubmission(target, localId)
                qualify()?.let { return it }
                return if (retained != null) LocalCreateSubmissionResult.RETAINED else LocalCreateSubmissionResult.UNRESOLVED
            }
            qualify()?.let { return it }
            val prepared = port.prepareSubmission(reservation.draft)
            if (prepared.scope != target || prepared.format != submissionFormat || prepared.protectedPayload.isBlank())
                return LocalCreateSubmissionResult.UNQUALIFIED
            qualify()?.let { return it }
            val retained = journal.retainSubmission(permit, LocalCreateSubmission(reservation, prepared.protectedPayload))
            qualify()?.let { return it }
            currentCoroutineContext().ensureActive()
            recordFreshSubmission(attempt, retained)
            LocalCreateSubmissionResult.RETAINED
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: CreatePreparationException) {
              when (failure.reason) {
                  CreatePreparationFailure.UNSUPPORTED -> LocalCreateSubmissionResult.UNSUPPORTED
                  CreatePreparationFailure.AUTHENTICATION, CreatePreparationFailure.PERMISSION -> LocalCreateSubmissionResult.INACTIVE
                  else -> LocalCreateSubmissionResult.UNAVAILABLE
              }
          } catch (_: Exception) { LocalCreateSubmissionResult.UNAVAILABLE }
          finally {
              clearAdmission()
          }
    }
}
