// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.util.Collections

/** Current journal metadata only; no preparation, dispatch, verification or ACK authority. */
data class TextUpdateWorkRef(val id: MutationId, val eventId: EventId, val state: TextUpdateAttemptState) {
    init { requireTextIndexId(id.value); requireTextIndexId(eventId.value) }
    override fun toString() = "TextUpdateWorkRef(REDACTED)"
}

/** Position in the local work index, unrelated to remote pagination or import adapter cursors. */
data class TextUpdateWorkCursor(val scope: CalendarScope, val afterEvent: EventId, val protocol: Int = 1) {
    init { requireTextIndexId(afterEvent.value) }
    override fun toString() = "TextUpdateWorkCursor(REDACTED)"
}

data class TextUpdateWorkQuery(val scope: CalendarScope, val limit: Int = MAX_PAGE_SIZE,
    val cursor: TextUpdateWorkCursor? = null) {
    init {
        requireTextIndexId(scope.account.value); requireTextIndexId(scope.calendar.value)
        require(limit in 1..MAX_PAGE_SIZE) { "Invalid text work page size" }
        require(cursor == null || (cursor.scope == scope && cursor.protocol == 1)) { "Incompatible text work cursor" }
    }
    override fun toString() = "TextUpdateWorkQuery(REDACTED)"
    companion object { const val MAX_PAGE_SIZE = 50 }
}

/** A bounded current page, not a snapshot or a completeness guarantee. Insertions behind the
 * cursor require restarting at the first page. Closed rows are absent even if their completion
 * is corrupt: this metadata query never qualifies a completion or releases any event hold.
 */
class TextUpdateWorkPage private constructor(val scope: CalendarScope, entries: List<TextUpdateWorkRef>,
    val next: TextUpdateWorkCursor?) {
    val entries: List<TextUpdateWorkRef> = Collections.unmodifiableList(entries.toList())
    override fun toString() = "TextUpdateWorkPage(REDACTED)"
    companion object {
        /** Trust SQL BINARY ordering, never re-sort opaque identifiers using JVM UTF-16 order. */
        fun fromOrderedMetadata(query: TextUpdateWorkQuery, rows: List<TextUpdateWorkRef>): TextUpdateWorkPage {
            require(rows.size <= query.limit + 1) { "Invalid text work page" }
            val frozen = rows.toList()
            require(frozen.size <= query.limit + 1 && frozen.map { it.eventId }.distinct().size == frozen.size &&
                frozen.map { it.id }.distinct().size == frozen.size) { "Invalid text work page" }
            val visible = frozen.take(query.limit)
            return TextUpdateWorkPage(query.scope, visible, if (frozen.size > query.limit)
                TextUpdateWorkCursor(query.scope, visible.last().eventId) else null)
        }
    }
}

/** Local discovery only. Read and validate each complete attempt separately before recovery. */
interface TextUpdateWorkJournal {
    suspend fun page(query: TextUpdateWorkQuery, admit: (CalendarScope) -> Boolean): TextUpdateWorkPage?
}

private fun requireTextIndexId(value: String) {
    require(value.length <= 4096 && value.isNotBlank() && value.none { it.isISOControl() } &&
        Charsets.UTF_8.newEncoder().canEncode(value) && value.toByteArray(Charsets.UTF_8).size <= 4096) {
        "Invalid text work identity"
    }
}
