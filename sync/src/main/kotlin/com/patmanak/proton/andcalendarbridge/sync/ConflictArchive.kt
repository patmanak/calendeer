// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.util.Collections

/** Historical display evidence only; never permission to resolve or dispatch a mutation. */
data class ConflictArchiveRef(val scope: CalendarScope, val id: Long, val eventId: EventId,
    val mutationId: MutationId, val kind: MutationKind) {
    init {
        archiveScope(scope); require(id > 0); archiveId(eventId.value); archiveId(mutationId.value)
        require(kind == MutationKind.UPDATE || kind == MutationKind.DELETE)
    }
    override fun toString() = "ConflictArchiveRef(REDACTED)"
}
data class ConflictArchiveCursor(val scope: CalendarScope, val afterId: Long, val protocol: Int = 1) {
    init { archiveScope(scope); require(afterId > 0 && protocol == 1) }
    override fun toString() = "ConflictArchiveCursor(REDACTED)"
}
data class ConflictArchiveQuery(val scope: CalendarScope, val limit: Int = 20,
    val cursor: ConflictArchiveCursor? = null) {
    init { archiveScope(scope); require(limit in 1..50); require(cursor == null || cursor.scope == scope && cursor.protocol == 1) }
    override fun toString() = "ConflictArchiveQuery(REDACTED)"
}
/** One current keyset page, not a snapshot or a completeness proof. */
class ConflictArchivePage(val scope: CalendarScope, entries: List<ConflictArchiveRef>, val next: ConflictArchiveCursor?) {
    val entries: List<ConflictArchiveRef> = frozen(entries.also { require(it.size <= 50) })
    init {
        archiveScope(scope); require(entries.size <= 50 && entries.all { it.scope == scope })
        require(entries.zipWithNext().all { (a, b) -> a.id < b.id })
        require(next == null || next.scope == scope && next.protocol == 1 && next.afterId == entries.lastOrNull()?.id)
    }
    override fun toString() = "ConflictArchivePage(REDACTED)"
}
class ConflictArchiveDetail(val ref: ConflictArchiveRef, val format: AdapterFormat,
    base: RemoteChange.Upsert, local: EventContent, val seriesProfile: SeriesEditProfile? = null) {
    val base = base.copy(content = archiveContent(base.content))
    val local = archiveContent(local)
    init {
        archiveId(format.id); require(base.eventId == ref.eventId); archiveId(base.revision.value)
        if (seriesProfile != null) {
            require(ref.kind == MutationKind.UPDATE)
            seriesProfile.requireCompatible(this.base.content, this.local)
        }
    }
    override fun toString() = "ConflictArchiveDetail(REDACTED)"
}
data class ConflictObservationCursor(val ref: ConflictArchiveRef, val afterId: Long, val protocol: Int = 1) {
    init { require(afterId > 0 && protocol == 1) }
    override fun toString() = "ConflictObservationCursor(REDACTED)"
}
data class ConflictObservationQuery(val ref: ConflictArchiveRef, val limit: Int = 20,
    val cursor: ConflictObservationCursor? = null) {
    init { require(limit in 1..50); require(cursor == null || cursor.ref == ref && cursor.protocol == 1) }
    override fun toString() = "ConflictObservationQuery(REDACTED)"
}
enum class ConflictObservationKind { VERIFIED, DEFERRED, UNOBSERVED, UNREADABLE }
enum class ConflictObservationOrigin { CACHED, RECEIVED }
/** Received timestamps describe local collection, not the remote author's editing time.
 * Deferred opaque content remains in storage and is deliberately absent from this view.
 */
class ConflictObservation(val id: Long, val kind: ConflictObservationKind, val origin: ConflictObservationOrigin,
    val observedAt: Instant?, val format: AdapterFormat?, change: RemoteChange?, val deferredReason: DeferredEventReason?) {
    val change: RemoteChange? = when (change) {
        is RemoteChange.Upsert -> change.copy(content = archiveContent(change.content))
        else -> change
    }
    init {
        require(id > 0)
        require((origin == ConflictObservationOrigin.CACHED) == (observedAt == null))
        format?.let { archiveId(it.id) }
        change?.let { archiveId(it.eventId.value); archiveId(it.revision.value) }
        when (kind) {
            ConflictObservationKind.VERIFIED -> require(change != null && deferredReason == null)
            ConflictObservationKind.DEFERRED -> require(change == null && deferredReason != null && format != null)
            ConflictObservationKind.UNOBSERVED -> require(change == null && deferredReason == null && format == null && origin == ConflictObservationOrigin.CACHED)
            ConflictObservationKind.UNREADABLE -> require(change == null && deferredReason == null && origin == ConflictObservationOrigin.CACHED)
        }
    }
    override fun toString() = "ConflictObservation(REDACTED)"
}
/** Sequence IDs order local collection only. Restart the first page to discover newer evidence. */
class ConflictObservationPage(val ref: ConflictArchiveRef, entries: List<ConflictObservation>, val next: ConflictObservationCursor?) {
    val entries: List<ConflictObservation> = frozen(entries.also { require(it.size <= 50) })
    init {
        require(entries.size <= 50 && entries.zipWithNext().all { (a, b) -> a.id < b.id })
        require(entries.all { it.change == null || it.change.eventId == ref.eventId })
        require(next == null || next.ref == ref && next.protocol == 1 && next.afterId == entries.lastOrNull()?.id)
    }
    override fun toString() = "ConflictObservationPage(REDACTED)"
}
private fun archiveScope(scope: CalendarScope) { archiveId(scope.account.value); archiveId(scope.calendar.value) }
private fun archiveId(value: String) {
    require(value.isNotBlank() && value.length <= 4096 && value.none { it.isISOControl() } &&
        Charsets.UTF_8.newEncoder().canEncode(value) && value.toByteArray(Charsets.UTF_8).size <= 4096) { "Invalid archive identity" }
}
private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())
private fun archiveContent(value: EventContent) = value.copy(
    recurrence = value.recurrence?.let { it.copy(additionalDates = frozen(it.additionalDates), excludedDates = frozen(it.excludedDates)) },
    details = value.details?.let { it.copy(reminders = frozen(it.reminders), attendees = frozen(it.attendees)) },
)
