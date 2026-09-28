package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@JvmInline value class AccountId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class CalendarId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class EventId(val value: String) { init { require(value.isNotBlank()) } }
@JvmInline value class MutationId(val value: String) { init { require(value.isNotBlank()) } }

/** Generation changes require explicit store migration/reconciliation, never a silent reset. */
data class CalendarScope(val account: AccountId, val calendar: CalendarId, val generation: Long) {
    init { require(generation >= 0) }
}

data class AdapterFormat(val id: String, val version: Int) {
    init { require(id.isNotBlank()); require(version > 0) }
}

/** Values are opaque: the engine neither parses nor orders cursors or revisions. */
data class OpaqueCursor(val format: AdapterFormat, val value: String)
@JvmInline value class Revision(val value: String) { init { require(value.isNotBlank()) } }

sealed interface EventTime {
    data class Timed(val start: Instant, val end: Instant, val zone: ZoneId) : EventTime {
        init { require(end > start) }
    }
    data class AllDay(val start: LocalDate, val endExclusive: LocalDate) : EventTime {
        init { require(endExclusive > start) }
    }
}

/** Supported display fields only; not a replacement for a remote adapter's full payload. */
data class EventContent(
    val title: String,
    val time: EventTime,
    val description: String = "",
    val location: String = "",
    /** Optional per-event RGB override. Null inherits the calendar color. */
    val colorRgb: Int? = null,
    val recurrence: EventRecurrence? = null,
    /** Null is legacy/unknown, never a request to invent default reminders. */
    val details: EventDetails? = null,
) {
    init {
        require(colorRgb == null || colorRgb in 0..0xFFFFFF)
        recurrence?.let { value ->
            val points = value.additionalDates + value.excludedDates
            require(points.all { (it is RecurrencePoint.AllDay) == (time is EventTime.AllDay) })
            // An exception can change its display type; original retains the master's slot type.
            require((points + listOfNotNull(value.original)).filterIsInstance<RecurrencePoint.Timed>()
                .all { it.instant.nano == 0 })
            if (time is EventTime.Timed) {
                require(time.start.nano == 0 && time.end.nano == 0)
            }
        }
    }
}

enum class CalendarMode { READ_ONLY, EDITABLE }
enum class MutationKind { CREATE, UPDATE, DELETE }
data class CalendarAccess(
    val mode: CalendarMode = CalendarMode.READ_ONLY,
    val remoteCanWrite: Boolean = false,
    val supportedOperations: Set<MutationKind> = emptySet(),
)

sealed interface RemoteChange {
    val eventId: EventId
    val revision: Revision

    data class Upsert(
        override val eventId: EventId,
        override val revision: Revision,
        val content: EventContent,
    ) : RemoteChange

    /** Only an explicit authoritative tombstone can request a provider deletion. */
    data class Deleted(override val eventId: EventId, override val revision: Revision) : RemoteChange
}

/** Traversal completion does not assert date coverage or incremental-sync completeness. */
data class PageCheckpoint(
    val scope: CalendarScope,
    val format: AdapterFormat,
    val cursor: OpaqueCursor? = null,
    val traversalComplete: Boolean = false,
)

data class PageRequest(val checkpoint: PageCheckpoint, val limit: Int)
data class RemotePage(
    val scope: CalendarScope,
    val format: AdapterFormat,
    val changes: List<RemoteChange>,
    val nextCursor: OpaqueCursor?,
    val traversalComplete: Boolean,
)

/** Stable identity makes projection replayable; production projection must recover provider mappings. */
data class ProjectionIntent(val scope: CalendarScope, val change: RemoteChange)

enum class BlockReason { READ_ONLY, REMOTE_PERMISSION, UNSUPPORTED_OPERATION, CONFLICT, REJECTED, NOT_APPLIED }
sealed interface MutationState {
    data object Queued : MutationState
    data class Blocked(val reason: BlockReason) : MutationState
    /** Persisted before dispatch, including when dispatch has not actually started before a crash. */
    data object OutcomeUnknown : MutationState
    data class Acknowledged(val eventId: EventId, val revision: Revision) : MutationState
}

data class Mutation(
    val id: MutationId,
    val scope: CalendarScope,
    val format: AdapterFormat,
    val kind: MutationKind,
    val target: EventId? = null,
    val baseRevision: Revision? = null,
    val content: EventContent? = null,
    val state: MutationState = MutationState.Queued,
) {
    init {
        require((kind == MutationKind.CREATE) == (target == null))
        require((kind == MutationKind.CREATE) == (baseRevision == null))
        require((kind == MutationKind.DELETE) == (content == null))
    }
}

sealed interface DispatchResult {
    data class Accepted(val eventId: EventId, val revision: Revision) : DispatchResult
    data object Rejected : DispatchResult
    /** Adapter has definitive evidence the server did not apply this request. */
    data object NotApplied : DispatchResult
    data object Unknown : DispatchResult
}

class SyncContractException(message: String) : IllegalStateException(message)
