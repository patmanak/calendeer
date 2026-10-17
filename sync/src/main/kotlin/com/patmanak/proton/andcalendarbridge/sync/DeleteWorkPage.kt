// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.util.Collections

/** Current journal metadata only; no preparation, dispatch, verification or ACK authority. */
data class DeleteWorkRef(val id: MutationId, val eventId: EventId, val state: DeleteAttemptState) {
    init { requireDeleteIndexId(id.value); requireDeleteIndexId(eventId.value) }
    override fun toString() = "DeleteWorkRef(REDACTED)"
}

/** Position in the local work index, unrelated to remote pagination or import adapter cursors. */
data class DeleteWorkCursor(val scope: CalendarScope, val afterEvent: EventId, val protocol: Int = 1) {
    init { requireDeleteIndexId(afterEvent.value) }
    override fun toString() = "DeleteWorkCursor(REDACTED)"
}

data class DeleteWorkQuery(val scope: CalendarScope, val limit: Int = MAX_PAGE_SIZE,
    val cursor: DeleteWorkCursor? = null) {
    init {
        requireDeleteIndexId(scope.account.value); requireDeleteIndexId(scope.calendar.value)
        require(limit in 1..MAX_PAGE_SIZE) { "Invalid deletion work page size" }
        require(cursor == null || (cursor.scope == scope && cursor.protocol == 1)) { "Incompatible deletion work cursor" }
    }
    override fun toString() = "DeleteWorkQuery(REDACTED)"
    companion object { const val MAX_PAGE_SIZE = 50 }
}

/** A bounded current page, not a snapshot or a completeness guarantee. Insertions behind the
 * cursor require restarting at the first page. Closed rows are absent even if their completion
 * is corrupt: this metadata query never qualifies a completion or releases any event hold.
 */
class DeleteWorkPage private constructor(val scope: CalendarScope, entries: List<DeleteWorkRef>,
    val next: DeleteWorkCursor?) {
    val entries: List<DeleteWorkRef> = Collections.unmodifiableList(entries.toList())
    override fun toString() = "DeleteWorkPage(REDACTED)"
    companion object {
        /** Trust SQL BINARY ordering, never re-sort opaque identifiers using JVM UTF-16 order. */
        fun fromOrderedMetadata(query: DeleteWorkQuery, rows: List<DeleteWorkRef>): DeleteWorkPage {
            require(rows.size <= query.limit + 1) { "Invalid deletion work page" }
            val frozen = rows.toList()
            require(frozen.size <= query.limit + 1 && frozen.map { it.eventId }.distinct().size == frozen.size &&
                frozen.map { it.id }.distinct().size == frozen.size) { "Invalid deletion work page" }
            val visible = frozen.take(query.limit)
            return DeleteWorkPage(query.scope, visible, if (frozen.size > query.limit)
                DeleteWorkCursor(query.scope, visible.last().eventId) else null)
        }
    }
}

private fun requireDeleteIndexId(value: String) {
    require(value.length <= 4096 && value.isNotBlank() && value.none { it.isISOControl() } &&
        Charsets.UTF_8.newEncoder().canEncode(value) && value.toByteArray(Charsets.UTF_8).size <= 4096) {
        "Invalid deletion work identity"
    }
}
