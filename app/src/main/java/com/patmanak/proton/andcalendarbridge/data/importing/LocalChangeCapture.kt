package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException

/** Retain local UPDATE/DELETE snapshots under the coordinator's mutex and original epoch.
 * Capture never writes or acknowledges a Provider row, submits a mutation or renews a send lease. */
internal class LocalChangeCapture(
    private val target: CalendarScope,
    private val store: ImportStore,
    private val projection: CalendarProjection,
    private val currentFormat: () -> AdapterFormat,
    private val active: () -> Boolean,
    private val currentAccount: suspend () -> Boolean,
    private val requireUnheld: suspend (EventId) -> Unit,
    private val baseline: suspend (EventId) -> RemoteChange.Upsert?,
) {
    private suspend fun current(): Boolean = currentAccount() && active()

    private suspend fun selected(): Boolean {
        val selected = store.selections(target.account).singleOrNull { it.scope == target }
        return selected?.enabled == true && selected.available
    }

    private fun installAdmission() {
        store.admitTransaction = { it == target && active() }
        projection.admitOperation = active
    }

    private fun clearAdmission() {
        store.admitTransaction = { false }
        projection.admitOperation = { false }
    }

    suspend fun edit(eventId: EventId, id: MutationId?, beforeNewOwner: suspend () -> Unit): LocalEditCaptureResult {
        installAdmission()
        return try {
            if (!selected()) return LocalEditCaptureResult.INACTIVE
            requireUnheld(eventId)
            val format = store.transaction(target) { checkpoint?.format }
            if (format != currentFormat()) return LocalEditCaptureResult.UNQUALIFIED
            if (store.localDelete(target, eventId) != null) return LocalEditCaptureResult.UNQUALIFIED
            val retained = store.localEdit(target, eventId)
            val mutation = id ?: retained?.id ?: MutationId(java.util.UUID.randomUUID().toString())
            if (retained != null) {
                if (retained.id != mutation || retained.format != format) return LocalEditCaptureResult.UNQUALIFIED
                return if (retained.state == LocalEditState.CONFLICT) LocalEditCaptureResult.CONFLICT else LocalEditCaptureResult.RETAINED
            }
            val base = baseline(eventId) ?: return LocalEditCaptureResult.UNQUALIFIED
            val snapshot = projection.captureOwnedSimpleEdit(target, base)
                ?: projection.captureOwnedPersonalEdit(target, base)
                ?: return LocalEditCaptureResult.UNQUALIFIED
            if (!current()) return LocalEditCaptureResult.INACTIVE
            beforeNewOwner()
            if (!current()) return LocalEditCaptureResult.INACTIVE
            val captured = store.captureLocalEdit(LocalEditIntent(mutation, target, format, base, snapshot.content))
            if (captured.state == LocalEditState.CONFLICT) LocalEditCaptureResult.CONFLICT else LocalEditCaptureResult.RETAINED
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { LocalEditCaptureResult.UNAVAILABLE }
          finally {
              clearAdmission()
          }
    }

    suspend fun delete(eventId: EventId, id: MutationId, beforeNewOwner: suspend () -> Unit): LocalEditCaptureResult {
        installAdmission()
        return try {
            if (!selected()) return LocalEditCaptureResult.INACTIVE
            requireUnheld(eventId)
            val format = store.transaction(target) { checkpoint?.format }
            if (format != currentFormat()) return LocalEditCaptureResult.UNQUALIFIED
            if (store.localEdit(target, eventId) != null) return LocalEditCaptureResult.UNQUALIFIED
            val retained = store.localDelete(target, eventId)
            if (retained != null) {
                if (retained.id != id || retained.format != format) return LocalEditCaptureResult.UNQUALIFIED
                return if (retained.state == LocalEditState.CONFLICT) LocalEditCaptureResult.CONFLICT else LocalEditCaptureResult.RETAINED
            }
            val base = baseline(eventId) ?: return LocalEditCaptureResult.UNQUALIFIED
            val snapshot = projection.captureOwnedSimpleDelete(target, base) ?: return LocalEditCaptureResult.UNQUALIFIED
            if (!current()) return LocalEditCaptureResult.INACTIVE
            beforeNewOwner()
            if (!current()) return LocalEditCaptureResult.INACTIVE
            val captured = store.captureLocalDelete(LocalDeleteIntent(id, target, format, base, snapshot.content))
            if (captured.state == LocalEditState.CONFLICT) LocalEditCaptureResult.CONFLICT else LocalEditCaptureResult.RETAINED
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { LocalEditCaptureResult.UNAVAILABLE }
          finally {
              clearAdmission()
          }
    }
}
