// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.util.Collections

/** Discovery metadata only. Inspect the exact owner separately before using its captured content. */
data class RetainedLocalChangeRef(val eventId: EventId, val mutationId: MutationId,
    val kind: MutationKind, val state: LocalEditState) {
    init {
        requireIndexId(eventId.value); requireIndexId(mutationId.value)
        require(kind == MutationKind.UPDATE || kind == MutationKind.DELETE) { "Invalid retained change kind" }
    }
    override fun toString() = "RetainedLocalChangeRef(REDACTED)"
}

/** Position in the local metadata index, not an import cursor or a snapshot/completeness proof. */
data class RetainedLocalChangeCursor(val scope: CalendarScope, val afterEvent: EventId, val protocol: Int = 1) {
    init { requireIndexId(afterEvent.value) }
    override fun toString() = "RetainedLocalChangeCursor(REDACTED)"
}

data class RetainedLocalChangeQuery(val scope: CalendarScope, val limit: Int = MAX_PAGE_SIZE,
    val cursor: RetainedLocalChangeCursor? = null) {
    init {
        requireIndexId(scope.account.value); requireIndexId(scope.calendar.value)
        require(limit in 1..MAX_PAGE_SIZE) { "Invalid retained change page size" }
        require(cursor == null || (cursor.scope == scope && cursor.protocol == 1)) { "Incompatible retained change cursor" }
    }
    override fun toString() = "RetainedLocalChangeQuery(REDACTED)"
    companion object { const val MAX_PAGE_SIZE = 50 }
}

/** A bounded current page. Concurrent insertions behind the cursor require a fresh first page. */
class RetainedLocalChangePage private constructor(val scope: CalendarScope,
    entries: List<RetainedLocalChangeRef>, val next: RetainedLocalChangeCursor?) {
    val entries: List<RetainedLocalChangeRef> = Collections.unmodifiableList(entries.toList())
    override fun toString() = "RetainedLocalChangePage(REDACTED)"

    companion object {
        /** SQL supplies BINARY order. Do not re-sort opaque identifiers using JVM string order. */
        fun fromOrderedMetadata(query: RetainedLocalChangeQuery, rows: List<RetainedLocalChangeRef>): RetainedLocalChangePage {
            val frozen = rows.toList()
            require(frozen.size <= query.limit + 1 && frozen.map { it.eventId }.distinct().size == frozen.size &&
                frozen.map { it.mutationId }.distinct().size == frozen.size) { "Invalid retained change page" }
            val visible = frozen.take(query.limit)
            val next = if (frozen.size > query.limit)
                RetainedLocalChangeCursor(query.scope, visible.last().eventId) else null
            return RetainedLocalChangePage(query.scope, visible, next)
        }
    }
}

private fun requireIndexId(value: String) {
    require(value.isNotBlank() && value.none { it.isISOControl() } &&
        Charsets.UTF_8.newEncoder().canEncode(value) && value.toByteArray(Charsets.UTF_8).size <= 4096) {
        "Invalid retained change identity"
    }
}
