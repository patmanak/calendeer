// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Local history only. Implementations must recheck their session/permission lease after reads.
 * None of these operations grants synchronization, dispatch or conflict-resolution authority.
 */
interface ConflictArchiveReader {
    suspend fun list(query: ConflictArchiveQuery): ConflictArchiveRead<ConflictArchivePage>
    suspend fun detail(ref: ConflictArchiveRef): ConflictArchiveRead<ConflictArchiveDetail>
    suspend fun observations(query: ConflictObservationQuery): ConflictArchiveRead<ConflictObservationPage>
}

sealed interface ConflictArchiveRead<out T> {
    data class Available<T>(val value: T) : ConflictArchiveRead<T> {
        override fun toString() = "ConflictArchiveRead.Available(REDACTED)"
    }
    data object Inactive : ConflictArchiveRead<Nothing>
    data object Unqualified : ConflictArchiveRead<Nothing>
    data object Unavailable : ConflictArchiveRead<Nothing>
}

internal enum class ConflictArchiveStatus { LOADING, READY, EMPTY, INACTIVE, MISSING, UNQUALIFIED, UNAVAILABLE }

/** One archive page and one observation page at most, held only for the open view. */
internal data class ConflictArchiveViewState(
    val status: ConflictArchiveStatus = ConflictArchiveStatus.LOADING,
    val page: ConflictArchivePage? = null,
    val selected: ConflictArchiveRef? = null,
    val detail: ConflictArchiveDetail? = null,
    val observationStatus: ConflictArchiveStatus = ConflictArchiveStatus.LOADING,
    val observations: ConflictObservationPage? = null,
)

/** Main-thread UI owner. A canceled or superseded read cannot repopulate a closed/new view. */
internal class ConflictArchiveController(
    private val scope: CalendarScope,
    private val reader: ConflictArchiveReader,
    private val jobs: CoroutineScope,
) {
    private val mutable = MutableStateFlow(ConflictArchiveViewState())
    val state: StateFlow<ConflictArchiveViewState> = mutable.asStateFlow()
    private var job: Job? = null
    private var revision = 0L
    private var disposed = false

    fun refresh() { state.value.selected?.let(::open) ?: list() }

    fun list(cursor: ConflictArchiveCursor? = null) {
        if (disposed) return
        val query = ConflictArchiveQuery(scope, PAGE_SIZE, cursor)
        begin(ConflictArchiveViewState()) { ticket ->
            val result = read { reader.list(query) }
            when (result) {
                is ConflictArchiveRead.Available -> {
                    val page = result.value
                    val next = page.next
                    val valid = page.scope == scope && page.entries.all { it.scope == scope } &&
                        validIds(page.entries.map { it.id }, cursor?.afterId ?: 0, next?.afterId) &&
                        (next == null || next.scope == scope && next.protocol == 1)
                    publish(ticket, if (valid) ConflictArchiveViewState(
                        status = if (page.entries.isEmpty()) ConflictArchiveStatus.EMPTY else ConflictArchiveStatus.READY, page = page)
                    else ConflictArchiveViewState(status = ConflictArchiveStatus.UNAVAILABLE))
                }
                else -> publish(ticket, ConflictArchiveViewState(status = status(result)))
            }
        }
    }

    fun open(ref: ConflictArchiveRef) {
        if (disposed || ref.scope != scope) return
        begin(ConflictArchiveViewState(selected = ref)) { ticket ->
            val result = read { reader.detail(ref) }
            if (result is ConflictArchiveRead.Available) {
                val detail = result.value
                if (detail.ref != ref || detail.base.eventId != ref.eventId) {
                    publish(ticket, ConflictArchiveViewState(status = ConflictArchiveStatus.UNAVAILABLE, selected = ref))
                } else if (publish(ticket, ConflictArchiveViewState(status = ConflictArchiveStatus.READY,
                        selected = ref, detail = detail))) {
                    loadObservations(ticket, detail, null)
                }
            } else publish(ticket, ConflictArchiveViewState(status = status(result, detail = true),
                selected = if (result == ConflictArchiveRead.Inactive) null else ref))
        }
    }

    fun observations(cursor: ConflictObservationCursor? = null) {
        val detail = state.value.detail ?: return
        if (disposed || cursor != null && cursor.ref != detail.ref) return
        begin(ConflictArchiveViewState(status = ConflictArchiveStatus.READY, selected = detail.ref, detail = detail)) {
            loadObservations(it, detail, cursor)
        }
    }

    fun dispose() {
        disposed = true; revision++; job?.cancel(); job = null
        mutable.value = ConflictArchiveViewState(status = ConflictArchiveStatus.INACTIVE)
    }

    private suspend fun loadObservations(ticket: Long, detail: ConflictArchiveDetail, cursor: ConflictObservationCursor?) {
        val result = read { reader.observations(ConflictObservationQuery(detail.ref, PAGE_SIZE, cursor)) }
        if (result == ConflictArchiveRead.Inactive) {
            publish(ticket, ConflictArchiveViewState(status = ConflictArchiveStatus.INACTIVE))
            return
        }
        if (result == ConflictArchiveRead.Unqualified) {
            publish(ticket, ConflictArchiveViewState(status = ConflictArchiveStatus.MISSING, selected = detail.ref))
            return
        }
        val retained = ConflictArchiveViewState(status = ConflictArchiveStatus.READY, selected = detail.ref, detail = detail)
        if (result is ConflictArchiveRead.Available) {
            val page = result.value
            val next = page.next
            val valid = page.ref == detail.ref && validIds(page.entries.map { it.id }, cursor?.afterId ?: 0, next?.afterId) &&
                (next == null || next.ref == detail.ref && next.protocol == 1) &&
                page.entries.all { entry -> entry.change?.let { it.eventId == detail.ref.eventId } ?: true }
            publish(ticket, if (valid) retained.copy(observations = page,
                observationStatus = if (page.entries.isEmpty()) ConflictArchiveStatus.EMPTY else ConflictArchiveStatus.READY)
            else retained.copy(observationStatus = ConflictArchiveStatus.UNAVAILABLE))
        } else publish(ticket, retained.copy(observationStatus = status(result, detail = true)))
    }

    private fun begin(initial: ConflictArchiveViewState, action: suspend (Long) -> Unit) {
        val ticket = ++revision
        job?.cancel()
        mutable.value = initial
        job = jobs.launch { action(ticket) }
    }

    private suspend fun <T> read(action: suspend () -> ConflictArchiveRead<T>): ConflictArchiveRead<T> {
        val result = try { action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ConflictArchiveRead.Unavailable }
        currentCoroutineContext().ensureActive()
        return result
    }

    private fun publish(ticket: Long, value: ConflictArchiveViewState): Boolean {
        if (disposed || revision != ticket) return false
        mutable.value = value
        return true
    }

    private fun validIds(ids: List<Long>, after: Long, next: Long?) = ids.size <= PAGE_SIZE &&
        ids.all { it > after } && ids.zipWithNext().all { (left, right) -> left < right } &&
        (next == null || ids.isNotEmpty() && next == ids.last())

    private fun status(result: ConflictArchiveRead<*>, detail: Boolean = false) = when (result) {
        ConflictArchiveRead.Inactive -> ConflictArchiveStatus.INACTIVE
        ConflictArchiveRead.Unqualified -> if (detail) ConflictArchiveStatus.MISSING else ConflictArchiveStatus.UNQUALIFIED
        else -> ConflictArchiveStatus.UNAVAILABLE
    }

    private companion object { const val PAGE_SIZE = 20 }
}
