// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.util.Collections

/** Current journal metadata only; no preparation, dispatch, verification or ACK authority. */
data class PersonalUpdateWorkRef(val id: MutationId, val eventId: EventId, val state: PersonalUpdateAttemptState) {
    init { requirePersonalIndexId(id.value); requirePersonalIndexId(eventId.value) }
    override fun toString() = "PersonalUpdateWorkRef(REDACTED)"
}

/** Position in the local work index, unrelated to remote pagination or import adapter cursors. */
data class PersonalUpdateWorkCursor(val scope: CalendarScope, val afterEvent: EventId, val protocol: Int = 1) {
    init { requirePersonalIndexId(afterEvent.value) }
    override fun toString() = "PersonalUpdateWorkCursor(REDACTED)"
}

data class PersonalUpdateWorkQuery(val scope: CalendarScope, val limit: Int = MAX_PAGE_SIZE,
    val cursor: PersonalUpdateWorkCursor? = null) {
    init {
        requirePersonalIndexId(scope.account.value); requirePersonalIndexId(scope.calendar.value)
        require(limit in 1..MAX_PAGE_SIZE) { "Invalid personal edit v2 work page size" }
        require(cursor == null || (cursor.scope == scope && cursor.protocol == 1)) { "Incompatible time work cursor" }
    }
    override fun toString() = "PersonalUpdateWorkQuery(REDACTED)"
    companion object { const val MAX_PAGE_SIZE = 50 }
}

/** A bounded current page, not a snapshot or a completeness guarantee. Insertions behind the
 * cursor require restarting at the first page. Closed rows are absent even if their completion
 * is corrupt: this metadata query never qualifies a completion or releases any event hold.
 */
class PersonalUpdateWorkPage private constructor(val scope: CalendarScope, entries: List<PersonalUpdateWorkRef>,
    val next: PersonalUpdateWorkCursor?) {
    val entries: List<PersonalUpdateWorkRef> = Collections.unmodifiableList(entries.toList())
    override fun toString() = "PersonalUpdateWorkPage(REDACTED)"
    companion object {
        /** Trust SQL BINARY ordering, never re-sort opaque identifiers using JVM UTF-16 order. */
        fun fromOrderedMetadata(query: PersonalUpdateWorkQuery, rows: List<PersonalUpdateWorkRef>): PersonalUpdateWorkPage {
            require(rows.size <= query.limit + 1) { "Invalid personal edit v2 work page" }
            val frozen = rows.toList()
            require(frozen.size <= query.limit + 1 && frozen.map { it.eventId }.distinct().size == frozen.size &&
                frozen.map { it.id }.distinct().size == frozen.size) { "Invalid personal edit v2 work page" }
            val visible = frozen.take(query.limit)
            return PersonalUpdateWorkPage(query.scope, visible, if (frozen.size > query.limit)
                PersonalUpdateWorkCursor(query.scope, visible.last().eventId) else null)
        }
    }
}

/** Local discovery only. Read and validate each complete attempt separately before recovery. */
interface PersonalUpdateWorkJournal {
    suspend fun page(query: PersonalUpdateWorkQuery, admit: (CalendarScope) -> Boolean): PersonalUpdateWorkPage?
}

private fun requirePersonalIndexId(value: String) {
    require(value.length <= 4096 && value.isNotBlank() && value.none { it.isISOControl() } &&
        Charsets.UTF_8.newEncoder().canEncode(value) && value.toByteArray(Charsets.UTF_8).size <= 4096) {
        "Invalid personal edit v2 work identity"
    }
}
