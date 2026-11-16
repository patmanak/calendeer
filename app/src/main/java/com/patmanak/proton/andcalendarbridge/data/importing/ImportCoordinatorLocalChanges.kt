package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Offline native-change capture and local SQL conflict history under the shared mutex. */
internal class ImportCoordinatorLocalChanges(
    private val store: ImportStore,
    private val remote: ReadOnlyCalendarRemote,
    private val projection: CalendarProjection,
    private val mutex: Mutex,
    private val projectionWork: ImportCoordinatorProjection,
    private val titleWrites: ImportCoordinatorTitleWrites,
    private val generation: () -> Long,
    private val policyRevision: () -> Long,
    private val authenticated: () -> Boolean,
    private val systemCreatesEnabled: (CalendarScope) -> Boolean,
    private val hasCalendarPermissions: () -> Boolean,
    private val isCurrent: suspend (Long, AccountId) -> Boolean,
    private val capturedRawHold: suspend (CalendarScope) -> CapturedProjectionHold,
) {
    /** Inspection never renews server rights or grants mutation/acknowledgement authority. */
    val conflictArchiveReader: ConflictArchiveReader = object : ConflictArchiveReader {
        override suspend fun list(query: ConflictArchiveQuery): ConflictArchiveRead<ConflictArchivePage> =
            readConflictArchive(query.scope) { store.conflictArchives(query) }
        override suspend fun detail(ref: ConflictArchiveRef): ConflictArchiveRead<ConflictArchiveDetail> =
            readConflictArchive(ref.scope) { store.conflictArchive(ref) }
        override suspend fun observations(query: ConflictObservationQuery): ConflictArchiveRead<ConflictObservationPage> =
            readConflictArchive(query.ref.scope) {
                if (store.conflictArchive(query.ref) == null) null else store.conflictObservations(query)
            }
    }
    /** Explicit capture followed by title-only preparation under one session/policy observation.
     * Capture is durable before preparation. Failure or cancellation keeps that intent; a retry
     * never substitutes later Provider content. No acknowledgement, dispatch or observer is enabled.
     */
    suspend fun captureAndPrepareLocalTitle(target: CalendarScope, eventId: EventId, id: MutationId,
        timestamp: java.time.Instant, port: CalendarLocalEditPreparationPort): LocalTitlePreparationResult =
        mutex.withLock {
            val expected = generation()
            val policy = policyRevision()
            try {
                currentCoroutineContext().ensureActive()
                when (captureLocalEditLocked(target, eventId, id, expected, policy)) {
                    LocalEditCaptureResult.RETAINED -> {
                        currentCoroutineContext().ensureActive()
                        titleWrites.prepareLocalTitleLocked(target, eventId, id, timestamp, port, expected, policy)
                    }
                    LocalEditCaptureResult.INACTIVE -> LocalTitlePreparationResult.INACTIVE
                    LocalEditCaptureResult.UNQUALIFIED -> LocalTitlePreparationResult.UNQUALIFIED
                    LocalEditCaptureResult.CONFLICT -> LocalTitlePreparationResult.CONFLICT
                    LocalEditCaptureResult.UNAVAILABLE -> LocalTitlePreparationResult.UNAVAILABLE
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { LocalTitlePreparationResult.UNAVAILABLE }
              finally {
                store.admitTransaction = { false }
                projection.admitOperation = { false }
            }
        }

    private fun localChangeCapture(target: CalendarScope, expected: Long, active: () -> Boolean,
        denied: java.util.concurrent.atomic.AtomicBoolean? = null) =
        LocalChangeCapture(target, store, projection,
            currentFormat = { remote.format }, active = active,
            currentAccount = { isCurrent(expected, target.account).also { if (!it) denied?.set(true) } && denied?.get() != true },
            requireUnheld = { event -> if (capturedRawHold(target).holds(event)) throw ImportCoordinatorProjection.Pending() },
            baseline = { event -> projectionWork.captureBaseline(expected, target, event) })

    /** Explicit internal UPDATE snapshot only. The dirty Provider row is never acknowledged. */
    suspend fun captureLocalEdit(target: CalendarScope, eventId: EventId, id: MutationId): LocalEditCaptureResult = mutex.withLock {
        captureLocalEditLocked(target, eventId, id, generation(), policyRevision())
    }
    /** Explicit offline series observation. Remote family qualification and upload remain separate. */
    suspend fun captureLocalSeriesEdit(target: CalendarScope, eventId: EventId, id: MutationId): LocalEditCaptureResult = mutex.withLock {
        val expected = generation(); val policy = policyRevision()
        val active = { authenticated() && generation() == expected && policyRevision() == policy && hasCalendarPermissions() }
        if (!isCurrent(expected, target.account) || !active()) return@withLock LocalEditCaptureResult.INACTIVE
        localChangeCapture(target, expected, active).edit(eventId, id, {}, series = true)
    }

    /** Offline observer capture. Reuse the durable owner until it is closed; a later cycle
     * receives a new identity, never the event's fixed identity from a previous upload.
     */
    suspend fun captureSystemLocalEdit(target: CalendarScope, eventId: EventId,
        beforeNewOwner: suspend () -> Unit = {}): LocalEditCaptureResult = mutex.withLock {
        captureLocalEditLocked(target, eventId, null, generation(), policyRevision(), requireSystemMode = true,
            beforeNewOwner = beforeNewOwner)
    }

    private suspend fun captureLocalEditLocked(target: CalendarScope, eventId: EventId, id: MutationId?,
        expected: Long, policy: Long, requireSystemMode: Boolean = false,
        beforeNewOwner: suspend () -> Unit = {}): LocalEditCaptureResult {
        val denied = java.util.concurrent.atomic.AtomicBoolean(false)
        val active = {
            val admitted = authenticated() && generation() == expected && policyRevision() == policy && hasCalendarPermissions() &&
                (!requireSystemMode || systemCreatesEnabled(target))
            if (!admitted && requireSystemMode) denied.set(true)
            admitted && !denied.get()
        }
        if (!isCurrent(expected, target.account) || !active()) return LocalEditCaptureResult.INACTIVE
        val capture = localChangeCapture(target, expected, active, if (requireSystemMode) denied else null)
        val result = capture.edit(eventId, id, beforeNewOwner)
        if (requireSystemMode && id == null && result == LocalEditCaptureResult.UNQUALIFIED) {
            if (!isCurrent(expected, target.account) || !active()) return LocalEditCaptureResult.INACTIVE
            return capture.edit(eventId, null, beforeNewOwner, series = true)
        }
        return result
    }

    private fun localChangeHistory(target: CalendarScope, expected: Long, policy: Long) =
        LocalChangeHistory(target, store, projection,
            active = { authenticated() && generation() == expected && policyRevision() == policy && hasCalendarPermissions() },
            currentAccount = { isCurrent(expected, target.account) })

    /** Local SQL history only. A missing dossier still passes the final session/cancellation gate. */
    private suspend fun <T : Any> readConflictArchive(target: CalendarScope,
        read: suspend () -> T?): ConflictArchiveRead<T> = mutex.withLock {
        val expected = generation()
        val policy = policyRevision()
        localChangeHistory(target, expected, policy).readArchive(read)
    }

    /** Explicit metadata discovery. Each page requires a fresh historical account/permission gate. */
    suspend fun retainedLocalChanges(query: RetainedLocalChangeQuery): RetainedLocalChangeListing = mutex.withLock {
        val target = query.scope
        val expected = generation()
        val policy = policyRevision()
        localChangeHistory(target, expected, policy).list(query)
    }

    /** Explicit local history inspection. Never reads Provider contents or renews remote rights. */
    suspend fun inspectLocalEdit(target: CalendarScope, eventId: EventId, id: MutationId): LocalEditInspection = mutex.withLock {
        val expected = generation()
        val policy = policyRevision()
        localChangeHistory(target, expected, policy).inspectEdit(eventId, id)
    }

    /** Explicit DELETE history only; never acknowledges a deletion or supplies an undo image. */
    suspend fun inspectLocalDelete(target: CalendarScope, eventId: EventId, id: MutationId): LocalDeleteInspection = mutex.withLock {
        val expected = generation()
        val policy = policyRevision()
        localChangeHistory(target, expected, policy).inspectDelete(eventId, id)
    }

    /** Explicit internal DELETE snapshot. No observation, upload, or Android acknowledgement. */
    suspend fun captureLocalDelete(target: CalendarScope, eventId: EventId, id: MutationId,
        beforeNewOwner: suspend () -> Unit = {}): LocalEditCaptureResult = mutex.withLock {
        val expected = generation()
        val policy = policyRevision()
        if (!isCurrent(expected, target.account) || !hasCalendarPermissions())
            return@withLock LocalEditCaptureResult.INACTIVE
        val active = { authenticated() && generation() == expected && policyRevision() == policy && hasCalendarPermissions() }
        localChangeCapture(target, expected, active).delete(eventId, id, beforeNewOwner)
    }

}
