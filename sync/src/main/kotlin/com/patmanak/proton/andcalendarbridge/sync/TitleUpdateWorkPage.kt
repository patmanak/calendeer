// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.util.Collections

/** Current journal metadata only; no preparation, dispatch, verification or ACK authority. */
data class TitleUpdateWorkRef(val id: MutationId, val eventId: EventId, val state: TitleUpdateAttemptState) {
    init { requireTitleIndexId(id.value); requireTitleIndexId(eventId.value) }
    override fun toString() = "TitleUpdateWorkRef(REDACTED)"
}

/** Position in the local work index, unrelated to remote pagination or import adapter cursors. */
data class TitleUpdateWorkCursor(val scope: CalendarScope, val afterEvent: EventId, val protocol: Int = 1) {
    init { requireTitleIndexId(afterEvent.value) }
    override fun toString() = "TitleUpdateWorkCursor(REDACTED)"
}

data class TitleUpdateWorkQuery(val scope: CalendarScope, val limit: Int = MAX_PAGE_SIZE,
    val cursor: TitleUpdateWorkCursor? = null) {
    init {
        requireTitleIndexId(scope.account.value); requireTitleIndexId(scope.calendar.value)
        require(limit in 1..MAX_PAGE_SIZE) { "Invalid title work page size" }
        require(cursor == null || (cursor.scope == scope && cursor.protocol == 1)) { "Incompatible title work cursor" }
    }
    override fun toString() = "TitleUpdateWorkQuery(REDACTED)"
    companion object { const val MAX_PAGE_SIZE = 50 }
}

/** A bounded current page, not a snapshot or a completeness guarantee. Insertions behind the
 * cursor require restarting at the first page. Closed rows are absent even if their completion
 * is corrupt: this metadata query never qualifies a completion or releases any event hold.
 */
class TitleUpdateWorkPage private constructor(val scope: CalendarScope, entries: List<TitleUpdateWorkRef>,
    val next: TitleUpdateWorkCursor?) {
    val entries: List<TitleUpdateWorkRef> = Collections.unmodifiableList(entries.toList())
    override fun toString() = "TitleUpdateWorkPage(REDACTED)"
    companion object {
        /** Trust SQL BINARY ordering, never re-sort opaque identifiers using JVM UTF-16 order. */
        fun fromOrderedMetadata(query: TitleUpdateWorkQuery, rows: List<TitleUpdateWorkRef>): TitleUpdateWorkPage {
            require(rows.size <= query.limit + 1) { "Invalid title work page" }
            val frozen = rows.toList()
            require(frozen.size <= query.limit + 1 && frozen.map { it.eventId }.distinct().size == frozen.size &&
                frozen.map { it.id }.distinct().size == frozen.size) { "Invalid title work page" }
            val visible = frozen.take(query.limit)
            return TitleUpdateWorkPage(query.scope, visible, if (frozen.size > query.limit)
                TitleUpdateWorkCursor(query.scope, visible.last().eventId) else null)
        }
    }
}

/** Local discovery only. Read and validate each complete attempt separately before recovery. */
interface TitleUpdateWorkJournal {
    suspend fun page(query: TitleUpdateWorkQuery): TitleUpdateWorkPage
}

private fun requireTitleIndexId(value: String) {
    require(value.length <= 4096 && value.isNotBlank() && value.none { it.isISOControl() } &&
        Charsets.UTF_8.newEncoder().canEncode(value) && value.toByteArray(Charsets.UTF_8).size <= 4096) {
        "Invalid title work identity"
    }
}
