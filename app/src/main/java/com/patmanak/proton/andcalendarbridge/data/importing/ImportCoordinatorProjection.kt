package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionException
import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjectionFailure
import com.patmanak.proton.andcalendarbridge.data.outgoing.CreateAttemptStore
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.*

/** Incoming projection receipts and transferred CREATE ownership under the caller's import lock. */
internal class ImportCoordinatorProjection(
    private val store: ImportStore,
    private val remote: ReadOnlyCalendarRemote,
    private val projection: CalendarProjection,
    private val creates: CreateAttemptStore?,
    private val generation: () -> Long,
    private val policyRevision: () -> Long,
    private val authenticated: () -> Boolean,
    private val hasCalendarPermissions: () -> Boolean,
    private val isCurrent: suspend (Long, AccountId) -> Boolean,
    private val handoffSource: suspend (CapturedCreateHandoffFrame, () -> Boolean) -> Boolean,
) {
    class Pending : IllegalStateException("Captured creation reconciliation pending")
    suspend fun capturedRawHold(target: CalendarScope): CapturedProjectionHold {
        val provenance = try { store.capturedProvenanceEvents(target) }
        catch (_: IllegalArgumentException) { return CapturedProjectionHold(scopeBlocked = true) }
        val dispatch = creates?.let { journal ->
            val first = journal.capturedProjectionHold(target, remote.format)
            if (!first.scopeBlocked || provenance.isEmpty()) first else {
                val transfers = store.capturedOccurrenceTransfers(target)
                if (transfers.isEmpty()) first else journal.capturedProjectionHold(target, remote.format, transfers)
            }
        } ?: CapturedProjectionHold()
        return dispatch.copy(events = dispatch.events + provenance)
    }
    private suspend fun capturedProjectionHold(target: CalendarScope): CapturedProjectionHold {
        val raw = capturedRawHold(target)
        if (raw.scopeBlocked || raw.events.isEmpty()) return raw
        val expected = generation()
        val policy = policyRevision()
        val active = { authenticated() && generation() == expected && policyRevision() == policy && hasCalendarPermissions() }
        val released = mutableSetOf<EventId>()
        for (event in raw.events) {
            try {
                val role = store.capturedHandoff(target, event) ?: continue
                if (role.state == CapturedCreateHandoffState.RELEASED && !role.waiting &&
                    handoffSource(role.frame, active) && store.capturedHandoffCanProject(role.frame) && active()) released += event
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Malformed or lost provenance remains held; other events can drain. */ }
        }
        return raw.copy(events = raw.events - released)
    }

    // Keep the hold read and status publication out of the larger import coroutine.
    suspend fun publishImportProgress(target: CalendarScope, status: String, completedAt: Long? = null): Boolean {
        val hold = capturedProjectionHold(target)
        val held = hold.scopeBlocked || hold.events.isNotEmpty() || hold.occurrenceMasters.any { store.hasPendingProjection(target, it) }
        val backlog = store.projectionBacklog(target)
        val locallyHeld = backlog.locallyHeld > 0
        val visible = when {
            held -> "CREATE_PENDING"
            locallyHeld && status in setOf("IMPORTED", "PARTIAL", "SERIES_PENDING", "HISTORY_GAP") -> "INCOMPLETE"
            else -> status
        }
        store.status(target, visible, if (held || locallyHeld) null else completedAt)
        // A CREATE hold remains visible even when this receive cycle has nothing left to project.
        return status == "IMPORTED" && backlog.pending == 0
    }
    private suspend fun requireProjectionAvailable(target: CalendarScope, eventId: EventId) {
        if (store.transaction(target) { hasPendingMutation(eventId) }) throw Pending()
        if (capturedProjectionHold(target).holds(eventId)) throw Pending()
    }
    suspend fun runnableProjections(target: CalendarScope, limit: Int = 100): List<ProjectionIntent> {
        val hold = capturedProjectionHold(target)
        return if (hold.scopeBlocked) emptyList() else store.pending(target, limit, hold.events + hold.occurrenceMasters)
    }
    suspend fun nativeChangesBlock(intent: ProjectionIntent): Boolean = projection.hasOwnedLocalChanges(intent,
        if (intent.change is RemoteChange.Deleted) store.deletedSeriesUid(intent.scope, intent.change.eventId) else null)

    suspend fun captureBaseline(expected: Long, target: CalendarScope, eventId: EventId): RemoteChange.Upsert? {
        if (capturedRawHold(target).holds(eventId)) throw Pending()
        requireProjectionAvailable(target, eventId)
        val receipt = store.projectionReceipt(target, eventId)
        val recovered = receipt?.let { recoverProjection(expected, it) } ?: false
        val base = store.projectedBaseline(target, eventId) ?: return null
        // An opaque revision alone cannot choose between different prepared contents.
        if (receipt != null && !recovered && receipt.change != base && receipt.change.revision == base.revision) return null
        return base
    }

    /** A marker proves the historical write, not that the current Provider content is unchanged. */
    private suspend fun recoverProjection(expected: Long, receipt: ProjectionReceipt): Boolean {
        requireProjectionAvailable(receipt.scope, receipt.change.eventId)
        store.requireDirectProjection(receipt.scope, receipt.change.eventId)
        if (receipt.format != remote.format || receipt.protocol != projection.projectionReceiptProtocol) return false
        val witness = projection.inspectProjectionReceipt(receipt) ?: return false
        check(witness.receipt == receipt) { "Projection witness differs" }
        if (!isCurrent(expected, receipt.scope.account)) throw CancellationException("Inactive projection recovery")
        requireProjectionAvailable(receipt.scope, receipt.change.eventId)
        return store.restoreProjection(witness)
    }

    /** Kept out of the larger import coroutine; preparation precedes every tracked Provider write. */
    suspend fun projectPending(intent: ProjectionIntent, expected: Long) {
        if (capturedRawHold(intent.scope).holds(intent.change.eventId)) {
            projectCapturedHandoff(intent, expected)
            return
        }
        requireProjectionAvailable(intent.scope, intent.change.eventId)
        store.requireDirectProjection(intent.scope, intent.change.eventId)
        val outstanding = store.projectionReceipt(intent.scope, intent.change.eventId)
        var representation: ProjectedEventReference? = null
        val recovered = outstanding?.let { recoverProjection(expected, it) } ?: false
        // A historical receipt can be recovered without consuming newer native edits. Once
        // inspected, an unsupported local change holds only this event, including old receipts.
        if (nativeChangesBlock(intent))
            throw CalendarProjectionException(CalendarProjectionFailure.LOCAL_CHANGE_PENDING)
        if (recovered && outstanding?.change == intent.change) return
        if (outstanding != null) {
            if (!recovered && (outstanding.change != intent.change || outstanding.format != remote.format ||
                outstanding.protocol != projection.projectionReceiptProtocol)) {
                throw CalendarProjectionException(CalendarProjectionFailure.PROVIDER_REJECTED)
            }
            if (!recovered) representation = outstanding.representation
        }
        requireProjectionAvailable(intent.scope, intent.change.eventId)
        if (projection.projectionReceiptProtocol == 1 && ProjectionReceipt.supports(intent.change)) {
            // Preserve an existing explicit direct preparation rather than substituting NULL.
            val prepared = store.prepareProjection(intent, remote.format, representation)
            store.requireDirectProjection(intent.scope, intent.change.eventId)
            val witness = projection.applyTracked(prepared)
            check(witness.receipt == prepared) { "Projection witness differs" }
            if (!isCurrent(expected, intent.scope.account)) throw CancellationException("Inactive projection acknowledgement")
            requireProjectionAvailable(intent.scope, intent.change.eventId)
            check(store.restoreProjection(witness)) { "Projection acknowledgement remains unresolved" }
        } else {
            store.requireDirectProjection(intent.scope, intent.change.eventId)
            projection.apply(intent, if (intent.change is RemoteChange.Deleted)
                store.deletedSeriesUid(intent.scope, intent.change.eventId) else null)
            requireProjectionAvailable(intent.scope, intent.change.eventId)
            store.acknowledge(intent)
        }
    }

    /** The durable transfer authorizes only conditional specialized updates, never generic ACK. */
    private suspend fun projectCapturedHandoff(intent: ProjectionIntent, expected: Long) {
        val target = intent.scope
        val event = intent.change.eventId
        val policy = policyRevision()
        val active = { authenticated() && generation() == expected && policyRevision() == policy && hasCalendarPermissions() }
        val previousStore = store.admitTransaction
        val previousProjection = projection.admitOperation
        store.admitTransaction = { it == target && active() }
        projection.admitOperation = active
        var frame: CapturedCreateHandoffFrame? = null
        suspend fun available(): Boolean = isCurrent(expected, target.account) && active() &&
            store.selections(target.account).singleOrNull { it.scope == target }?.let { it.enabled && it.available } == true && active()
        suspend fun waitForResolution() {
            if (active()) frame?.let { store.waitCapturedHandoff(it) }
        }
        try {
            if (!available()) throw CancellationException("Inactive captured projection")
            var role = store.capturedHandoff(target, event) ?: return
            frame = role.frame
            if (role.state != CapturedCreateHandoffState.RELEASED || role.waiting || !handoffSource(role.frame, active) ||
                !store.capturedHandoffCanProject(role.frame)) return
            var prepared = store.capturedHandoffProjection(role.frame)
            if (prepared != null) {
                val proof = projection.inspectCapturedHandoffProjection(prepared)
                if (!available()) throw CancellationException("Inactive captured recovery")
                if (!handoffSource(role.frame, active)) { waitForResolution(); return }
                if (proof != null) {
                    check(proof.plan == prepared) { "Captured projection proof differs" }
                    check(store.restoreCapturedHandoffProjection(proof)) { "Captured projection recovery unresolved" }
                    role = store.capturedHandoff(target, event) ?: return
                    frame = role.frame
                    prepared = null
                }
            }
            // Recovery can advance the reference and leave a different/newer queue entry.
            val current = store.capturedHandoffPending(role.frame) ?: return
            if (!store.capturedHandoffCanProject(role.frame) || !supportsCapturedHandoffContent(role.frame.receipt.acknowledgement.baseline, current.change)) {
                waitForResolution(); return
            }
            if (prepared != null && prepared.receipt.change != current.change) { waitForResolution(); return }
            val clean = projection.inspectCapturedHandoffBase(role.frame)
            if (!available()) throw CancellationException("Inactive captured qualification")
            if (clean?.frame != role.frame || !handoffSource(role.frame, active)) { waitForResolution(); return }
            val plan = prepared ?: store.prepareCapturedHandoffProjection(role.frame, current)
            if (!available()) throw CancellationException("Inactive captured write")
            if (!handoffSource(role.frame, active)) { waitForResolution(); return }
            val proof = projection.applyCapturedHandoffProjection(plan)
            if (!available()) throw CancellationException("Inactive captured acknowledgement")
            if (proof?.plan != plan || !handoffSource(role.frame, active)) { waitForResolution(); return }
            check(store.restoreCapturedHandoffProjection(proof)) { "Captured projection acknowledgement unresolved" }
        } catch (failure: CalendarProjectionException) {
            if (failure.category in setOf(CalendarProjectionFailure.PROVIDER_REJECTED,
                    CalendarProjectionFailure.IDENTITY_CONFLICT, CalendarProjectionFailure.CALENDAR_MISSING)) waitForResolution() else throw failure
        } finally {
            store.admitTransaction = previousStore
            projection.admitOperation = previousProjection
        }
    }

}
