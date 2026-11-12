package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.outgoing.SystemPersonalEditProfile
import com.patmanak.proton.andcalendarbridge.sync.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared, revocable local-owner access under the coordinator's existing mutex. */
internal class ImportCoordinatorEditAccess(
    private val store: ImportStore,
    private val remote: ReadOnlyCalendarRemote,
    private val projection: CalendarProjection,
    private val mutex: Mutex,
    private val generation: () -> Long,
    private val policyRevision: () -> Long,
    private val currentAccount: () -> AccountId?,
    private val authenticated: () -> Boolean,
    private val hasCalendarPermissions: () -> Boolean,
    private val systemCreatesEnabled: (CalendarScope) -> Boolean,
    private val selected: (CalendarScope) -> Boolean,
    private val capturedRawHold: suspend (CalendarScope) -> CapturedProjectionHold,
    private val currentSystemTitleHandoff: suspend (CapturedCreateHandoffRecord, LocalEditIntent, () -> Boolean) -> Boolean,
) {
    /** Current native contents only. The caller retains the observed role and typed SQL proof. */
    suspend fun currentEditNativeSource(intent: LocalEditIntent, role: CapturedCreateHandoffRecord?,
        access: Access, personal: Boolean = false): Boolean {
        val target = access.target
        val event = intent.base.eventId
        val hold = access.read { capturedRawHold(target) }
        if (hold.scopeBlocked || (role == null && hold.holds(event))) return false
        val content = if (role == null) access.read { if (personal || SystemPersonalEditProfile.requiresExtendedSnapshot(intent)) projection.captureOwnedPersonalEdit(target, intent.base) else projection.captureOwnedSimpleEdit(target, intent.base) }?.content else {
            if (!access.read { currentSystemTitleHandoff(role, intent, access::active) }) return false
            val observed = access.read { projection.captureCapturedHandoffCurrent(role.frame) }
                as? CapturedHandoffCurrentObservation.Supported
            observed?.snapshot?.takeIf { it.frame == role.frame && !it.deleted }?.content
        }
        return content == intent.content && access.read { store.capturedHandoff(target, event) } == role
    }

    /** Recheck the same typed SQL owner/capsule before and after the shared native observations. */
    suspend fun currentEditSource(intent: LocalEditIntent, access: Access,
        sql: suspend () -> Boolean, personal: Boolean = false): Boolean {
        val target = access.target
        val event = intent.base.eventId
        if (!sql()) return false
        val role = access.read { store.capturedHandoff(target, event) }
        if (!currentEditNativeSource(intent, role, access, personal)) return false
        if (role != null && !access.read { currentSystemTitleHandoff(role, intent, access::active) }) return false
        val after = access.read { capturedRawHold(target) }
        if (after.scopeBlocked || (role == null && after.holds(event)) ||
            access.read { store.capturedHandoff(target, event) } != role) return false
        return sql()
    }

    /** A fresh native ACK still requires the exact current ordinary or transferred owner role. */
    suspend fun currentEditAcknowledgementRole(intent: LocalEditIntent, frame: CapturedCreateHandoffFrame?,
        access: Access): Boolean {
        val target = access.target
        val event = intent.base.eventId
        val role = access.read { store.capturedHandoff(target, event) }
        if (frame == null) {
            val hold = access.read { capturedRawHold(target) }
            if (role != null || hold.scopeBlocked || hold.holds(event)) return false
        } else if (role == null || role != CapturedCreateHandoffRecord(frame, CapturedCreateHandoffState.RELEASED, waiting = true) ||
            !access.read { currentSystemTitleHandoff(role, intent, access::active) }) return false
        return true
    }

    private class ScopedEditOwnerDenied : IllegalStateException("Edit owner access is inactive")

    inner class Access(val target: CalendarScope, private val expected: Long,
        private val policy: Long, private val consent: Boolean, private val admission: (CalendarScope) -> Boolean,
        private val operation: kotlin.coroutines.CoroutineContext,
        private val sendingEnabled: (CalendarScope) -> Boolean) {
        private val closed = AtomicBoolean(false)
        fun close() { closed.set(true) }
        fun active(): Boolean {
            operation.ensureActive()
            if (closed.get()) return false
            val allowed = authenticated() && generation() == expected && policyRevision() == policy &&
                currentAccount() == target.account && hasCalendarPermissions() && systemCreatesEnabled(target) &&
                (!consent || sendingEnabled(target)) &&
                selected(target) && admission(target)
            operation.ensureActive()
            if (!allowed || generation() != expected || policyRevision() != policy ||
                currentAccount() != target.account) closed.set(true)
            return !closed.get()
        }
        val admit: (CalendarScope) -> Boolean = { requested -> requested == target && active() }
        suspend fun requireAvailable() {
            operation.ensureActive()
            if (!active()) throw ScopedEditOwnerDenied()
            val selected = store.selections(target.account).singleOrNull { it.scope == target }
            operation.ensureActive()
            if (!active() || selected == null || !selected.enabled || !selected.available) {
                close(); throw ScopedEditOwnerDenied()
            }
        }
        suspend fun <T> read(action: suspend () -> T): T {
            requireAvailable()
            val value = action()
            operation.ensureActive()
            requireAvailable()
            return value
        }
    }

    suspend fun <T> withScopedEditOwner(target: CalendarScope, admit: (CalendarScope) -> Boolean,
        consent: Boolean, denied: T, finalAccessRequired: Boolean = true,
        sendingEnabled: (CalendarScope) -> Boolean,
        action: suspend (Access) -> T): T {
        currentCoroutineContext().ensureActive()
        val expected = generation()
        val policy = policyRevision()
        return mutex.withLock {
            val access = Access(target, expected, policy, consent, admit, currentCoroutineContext(), sendingEnabled)
            try {
                access.requireAvailable()
                store.admitTransaction = access.admit
                projection.admitOperation = access::active
                val result = action(access)
                currentCoroutineContext().ensureActive()
                if (finalAccessRequired) access.requireAvailable()
                result
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: ScopedEditOwnerDenied) { currentCoroutineContext().ensureActive(); denied }
              catch (_: Exception) { currentCoroutineContext().ensureActive(); denied }
              finally {
                  access.close()
                  store.admitTransaction = { false }
                  projection.admitOperation = { false }
              }
        }
    }

}
