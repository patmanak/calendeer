package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Current title owner and native-source qualification under the coordinator's mutex.
 * Account callbacks retain the original epoch. Guarded submission uses only the cached
 * account path, without re-entering the adapter's authentication lock. */
internal class LocalTitleOwnerQualification(
    private val preparation: LocalEditPreparation,
    private val format: AdapterFormat,
    private val port: CalendarTitleUpdateSubmissionPort,
    private val store: ImportStore,
    private val projection: CalendarProjection,
    private val currentFormat: () -> AdapterFormat,
    private val baseActive: () -> Boolean,
    private val currentAccount: suspend () -> Boolean,
    private val cachedAccount: () -> Boolean,
    private val systemTitle: Boolean,
    private val capturedHold: suspend () -> CapturedProjectionHold,
    private val handoffSource: suspend (CapturedCreateHandoffFrame, () -> Boolean) -> Boolean,
) {
    private val target = preparation.intent.scope
    private val intent = preparation.intent
    private val event = intent.base.eventId
    val active = { baseActive() && currentFormat() == intent.format && port.titleUpdateFormat == format }
    suspend fun available(formats: Boolean = true): Boolean = availableWithAccount(formats) {
        currentAccount()
    }
    /** Only cached epoch/account and local selection reads; safe inside guarded submit. */
    private suspend fun availableLocal(): Boolean = availableWithAccount(true) {
        cachedAccount()
    }
    private suspend fun availableWithAccount(formats: Boolean, accountMatches: suspend () -> Boolean): Boolean {
        currentCoroutineContext().ensureActive()
        fun admitted() = if (formats) active() else baseActive()
        if (!admitted()) return false
        val current = accountMatches()
        currentCoroutineContext().ensureActive()
        if (!current || !admitted()) return false
        val selected = store.selections(target.account).singleOrNull { it.scope == target }
        currentCoroutineContext().ensureActive()
        return selected?.enabled == true && selected.available && admitted()
    }
    private suspend fun sqlQualification(available: suspend () -> Boolean): LocalTitleAttemptRetentionResult? {
        if (!available()) return LocalTitleAttemptRetentionResult.INACTIVE
        val owner = store.localEdit(target, event)
        if (!available()) return LocalTitleAttemptRetentionResult.INACTIVE
        if (owner?.state == LocalEditState.CONFLICT && owner.copy(state = LocalEditState.PAUSED) == intent)
            return LocalTitleAttemptRetentionResult.CONFLICT
        if (owner != intent) return LocalTitleAttemptRetentionResult.UNQUALIFIED
        val admission = store.assessLocalTitlePreparation(intent)
        if (!available()) return LocalTitleAttemptRetentionResult.INACTIVE
        when (admission) {
            LocalTitlePreparationAdmission.CONFLICT -> return LocalTitleAttemptRetentionResult.CONFLICT
            LocalTitlePreparationAdmission.INACTIVE -> return LocalTitleAttemptRetentionResult.INACTIVE
            LocalTitlePreparationAdmission.HISTORICAL -> Unit
            else -> return LocalTitleAttemptRetentionResult.UNQUALIFIED
        }
        val retainedPreparation = store.localEditPreparation(target, event)
        if (!available()) return LocalTitleAttemptRetentionResult.INACTIVE
        return if (retainedPreparation == preparation) null else LocalTitleAttemptRetentionResult.UNQUALIFIED
    }
    suspend fun sourceQualification(): LocalTitleAttemptRetentionResult? = qualifySource { available() }
    suspend fun localSourceQualification(): LocalTitleAttemptRetentionResult? = qualifySource { availableLocal() }
    private suspend fun qualifySource(available: suspend () -> Boolean): LocalTitleAttemptRetentionResult? {
        sqlQualification(available)?.let { return it }
        val hold = capturedHold()
        if (!available()) return LocalTitleAttemptRetentionResult.INACTIVE
        if (hold.scopeBlocked) return LocalTitleAttemptRetentionResult.UNRESOLVED
        val role = store.capturedHandoff(target, event)
        if (!available()) return LocalTitleAttemptRetentionResult.INACTIVE
        if (systemTitle && !systemTitleProfile(intent))
            return LocalTitleAttemptRetentionResult.UNSUPPORTED
        val content = if (role == null) {
            if (hold.holds(event)) return LocalTitleAttemptRetentionResult.UNRESOLVED
            projection.captureOwnedSimpleEdit(target, intent.base)?.content
        } else {
            if (!matchesReleasedHandoff(role, intent))
                return LocalTitleAttemptRetentionResult.UNRESOLVED
            val pending = store.capturedHandoffProjection(role.frame)
            if (!available()) return LocalTitleAttemptRetentionResult.INACTIVE
            if (pending != null) return LocalTitleAttemptRetentionResult.UNRESOLVED
            val qualified = handoffSource(role.frame, active)
            if (!available()) return LocalTitleAttemptRetentionResult.INACTIVE
            if (!qualified) return LocalTitleAttemptRetentionResult.UNRESOLVED
            val observed = projection.captureCapturedHandoffCurrent(role.frame) as? CapturedHandoffCurrentObservation.Supported
            observed?.snapshot?.takeIf { it.frame == role.frame && !it.deleted }?.content
        }
        // Check access/cancellation even when a dependency returned null.
        if (!available()) return LocalTitleAttemptRetentionResult.INACTIVE
        if (content != intent.content) return LocalTitleAttemptRetentionResult.UNRESOLVED
        val currentRole = store.capturedHandoff(target, event)
        if (!available()) return LocalTitleAttemptRetentionResult.INACTIVE
        if (currentRole != role) return LocalTitleAttemptRetentionResult.UNRESOLVED
        if (role != null) {
            val qualified = handoffSource(role.frame, active)
            if (!available()) return LocalTitleAttemptRetentionResult.INACTIVE
            if (!qualified) return LocalTitleAttemptRetentionResult.UNRESOLVED
            val pending = store.capturedHandoffProjection(role.frame)
            if (!available()) return LocalTitleAttemptRetentionResult.INACTIVE
            if (pending != null) return LocalTitleAttemptRetentionResult.UNRESOLVED
        }
        val currentHold = capturedHold()
        if (!available()) return LocalTitleAttemptRetentionResult.INACTIVE
        if (currentHold.scopeBlocked || (role == null && currentHold.holds(event)))
            return LocalTitleAttemptRetentionResult.UNRESOLVED
        val finalRole = store.capturedHandoff(target, event)
        if (!available()) return LocalTitleAttemptRetentionResult.INACTIVE
        if (finalRole != role) return LocalTitleAttemptRetentionResult.UNRESOLVED
        return sqlQualification(available)
    }

    companion object {
        fun matchesReleasedHandoff(role: CapturedCreateHandoffRecord, intent: LocalEditIntent): Boolean =
            role.state == CapturedCreateHandoffState.RELEASED && role.waiting &&
                role.frame.base == intent.base && role.frame.format == intent.format

        fun systemTitleProfile(intent: LocalEditIntent): Boolean =
            intent.state == LocalEditState.PAUSED &&
                intent.base.content.details == EventDetails() && intent.base.content.recurrence == null &&
                intent.base.content.colorRgb == null && intent.content != intent.base.content &&
                intent.content == intent.base.content.copy(title = intent.content.title)
    }
}
