// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.util.Collections

/** Current journal metadata only; no preparation, dispatch, verification or ACK authority. */
data class TimeUpdateWorkRef(val id: MutationId, val eventId: EventId, val state: TimeUpdateAttemptState) {
    init { requireTimeIndexId(id.value); requireTimeIndexId(eventId.value) }
    override fun toString() = "TimeUpdateWorkRef(REDACTED)"
}

/** Position in the local work index, unrelated to remote pagination or import adapter cursors. */
data class TimeUpdateWorkCursor(val scope: CalendarScope, val afterEvent: EventId, val protocol: Int = 1) {
    init { requireTimeIndexId(afterEvent.value) }
    override fun toString() = "TimeUpdateWorkCursor(REDACTED)"
}

data class TimeUpdateWorkQuery(val scope: CalendarScope, val limit: Int = MAX_PAGE_SIZE,
    val cursor: TimeUpdateWorkCursor? = null) {
    init {
        requireTimeIndexId(scope.account.value); requireTimeIndexId(scope.calendar.value)
        require(limit in 1..MAX_PAGE_SIZE) { "Invalid time work page size" }
        require(cursor == null || (cursor.scope == scope && cursor.protocol == 1)) { "Incompatible time work cursor" }
    }
    override fun toString() = "TimeUpdateWorkQuery(REDACTED)"
    companion object { const val MAX_PAGE_SIZE = 50 }
}

/** A bounded current page, not a snapshot or a completeness guarantee. Insertions behind the
 * cursor require restarting at the first page. Closed rows are absent even if their completion
 * is corrupt: this metadata query never qualifies a completion or releases any event hold.
 */
class TimeUpdateWorkPage private constructor(val scope: CalendarScope, entries: List<TimeUpdateWorkRef>,
    val next: TimeUpdateWorkCursor?) {
    val entries: List<TimeUpdateWorkRef> = Collections.unmodifiableList(entries.toList())
    override fun toString() = "TimeUpdateWorkPage(REDACTED)"
    companion object {
        /** Trust SQL BINARY ordering, never re-sort opaque identifiers using JVM UTF-16 order. */
        fun fromOrderedMetadata(query: TimeUpdateWorkQuery, rows: List<TimeUpdateWorkRef>): TimeUpdateWorkPage {
            require(rows.size <= query.limit + 1) { "Invalid time work page" }
            val frozen = rows.toList()
            require(frozen.size <= query.limit + 1 && frozen.map { it.eventId }.distinct().size == frozen.size &&
                frozen.map { it.id }.distinct().size == frozen.size) { "Invalid time work page" }
            val visible = frozen.take(query.limit)
            return TimeUpdateWorkPage(query.scope, visible, if (frozen.size > query.limit)
                TimeUpdateWorkCursor(query.scope, visible.last().eventId) else null)
        }
    }
}

/** Local discovery only. Read and validate each complete attempt separately before recovery. */
interface TimeUpdateWorkJournal {
    suspend fun page(query: TimeUpdateWorkQuery, admit: (CalendarScope) -> Boolean): TimeUpdateWorkPage?
}

private fun requireTimeIndexId(value: String) {
    require(value.length <= 4096 && value.isNotBlank() && value.none { it.isISOControl() } &&
        Charsets.UTF_8.newEncoder().canEncode(value) && value.toByteArray(Charsets.UTF_8).size <= 4096) {
        "Invalid time work identity"
    }
}
