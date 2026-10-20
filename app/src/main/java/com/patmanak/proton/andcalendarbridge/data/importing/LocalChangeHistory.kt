package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Local SQL history under the coordinator's mutex and original access epoch.
 * Paused/unavailable selections remain readable; no Provider contents are read,
 * no native acknowledgement is written and no sending authority is renewed. */
internal class LocalChangeHistory(
    private val target: CalendarScope,
    private val store: ImportStore,
    private val projection: CalendarProjection,
    private val active: () -> Boolean,
    private val currentAccount: suspend () -> Boolean,
) {
    private suspend fun current(): Boolean = currentAccount() && active()

    private fun installAdmission() { store.admitTransaction = { it == target && active() } }

    private fun clearAdmission() {
        store.admitTransaction = { false }
        projection.admitOperation = { false }
    }

    suspend fun <T : Any> readArchive(read: suspend () -> T?): ConflictArchiveRead<T> {
        return try {
            currentCoroutineContext().ensureActive()
            val currentBefore = currentAccount()
            currentCoroutineContext().ensureActive()
            if (!currentBefore || !active()) return ConflictArchiveRead.Inactive
            installAdmission()
            val known = store.selections(target.account).any { it.scope == target }
            val value = if (known) read() else null
            currentCoroutineContext().ensureActive()
            val currentAfter = currentAccount()
            currentCoroutineContext().ensureActive()
            if (!currentAfter || !active()) return ConflictArchiveRead.Inactive
            if (value == null) ConflictArchiveRead.Unqualified else ConflictArchiveRead.Available(value)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
              currentCoroutineContext().ensureActive()
              if (active()) ConflictArchiveRead.Unavailable else ConflictArchiveRead.Inactive
          }
          finally {
              clearAdmission()
          }
    }

    suspend fun list(query: RetainedLocalChangeQuery): RetainedLocalChangeListing {
        return try {
            if (!current()) return RetainedLocalChangeListing.Inactive
            installAdmission()
            if (store.selections(target.account).none { it.scope == target }) return RetainedLocalChangeListing.Unqualified
            val page = store.retainedLocalChanges(query)
            currentCoroutineContext().ensureActive()
            if (!current()) return RetainedLocalChangeListing.Inactive
            RetainedLocalChangeListing.Available(page)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { RetainedLocalChangeListing.Unavailable }
          finally {
              clearAdmission()
          }
    }

    suspend fun inspectEdit(eventId: EventId, id: MutationId): LocalEditInspection {
        return try {
            if (!current()) return LocalEditInspection.Inactive
            installAdmission()
            // Historical owners remain inspectable after a calendar is paused or disappears from discovery.
            if (store.selections(target.account).none { it.scope == target }) return LocalEditInspection.Unqualified
            val review = store.inspectLocalEdit(target, eventId, id) ?: return LocalEditInspection.Unqualified
            currentCoroutineContext().ensureActive()
            if (!current()) return LocalEditInspection.Inactive
            LocalEditInspection.Available(review)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { LocalEditInspection.Unavailable }
          finally {
              clearAdmission()
          }
    }

    suspend fun inspectDelete(eventId: EventId, id: MutationId): LocalDeleteInspection {
        return try {
            if (!current()) return LocalDeleteInspection.Inactive
            installAdmission()
            if (store.selections(target.account).none { it.scope == target }) return LocalDeleteInspection.Unqualified
            val review = store.inspectLocalDelete(target, eventId, id) ?: return LocalDeleteInspection.Unqualified
            currentCoroutineContext().ensureActive()
            if (!current()) return LocalDeleteInspection.Inactive
            LocalDeleteInspection.Available(review)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { LocalDeleteInspection.Unavailable }
          finally {
              clearAdmission()
          }
    }
}
