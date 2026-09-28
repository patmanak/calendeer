package com.patmanak.proton.andcalendarbridge.sync

data class ReceivedChangePage(val scope: CalendarScope, val after: OpaqueCursor, val protectedPayload: String) {
    init { require(protectedPayload.isNotEmpty() && protectedPayload.length <= ReceivedCalendarPage.MAX_PAYLOAD_CHARS) }
    override fun toString() = "ReceivedChangePage(REDACTED)"
}

enum class CalendarNoticeKind { EVENT_CHANGED, EVENT_DELETED, CONTEXT_CHANGED, CONTENT_CHANGED }

/** Notice identity/revision are adapter-owned; only an authoritative notice may request deletion. */
data class CalendarChangeNotice(val id: String, val kind: CalendarNoticeKind,
    val eventId: EventId? = null, val revision: Revision? = null) {
    init {
        require(id.isNotBlank())
        require((kind in setOf(CalendarNoticeKind.CONTEXT_CHANGED, CalendarNoticeKind.CONTENT_CHANGED)) == (eventId == null))
        require((kind == CalendarNoticeKind.EVENT_DELETED) == (revision != null))
    }
    override fun toString() = "CalendarChangeNotice(REDACTED)"
}

/** A reset's next cursor is a usable pre-rescan anchor. Its notices are not authoritative actions;
 * the complete received envelope must be retained as history-gap evidence before continuing.
 */
data class DecodedChangePage(val scope: CalendarScope, val after: OpaqueCursor, val next: OpaqueCursor,
    val more: Boolean, val reconciliationRequired: Boolean, val notices: List<CalendarChangeNotice>) {
    override fun toString() = "DecodedChangePage(REDACTED)"
}

data class ReceivedCalendarEvent(val scope: CalendarScope, val eventId: EventId, val revision: Revision,
    val format: AdapterFormat, val protectedPayload: String) {
    init { require(protectedPayload.isNotEmpty() && protectedPayload.length <= ReceivedCalendarPage.MAX_PAYLOAD_CHARS) }
    override fun toString() = "ReceivedCalendarEvent(REDACTED)"
}

data class DecodedCalendarEvent(val change: RemoteChange.Upsert? = null, val deferred: DeferredCalendarEvent? = null) {
    init { require((change == null) != (deferred == null)) }
    override fun toString() = "DecodedCalendarEvent(REDACTED)"
}

/** Persisted obligations permit cursor advancement without losing an unresolved per-ID read. */
data class CalendarChangeJob(val notice: CalendarChangeNotice, val received: ReceivedCalendarEvent? = null,
    val blocked: Boolean = false) {
    override fun toString() = "CalendarChangeJob(REDACTED)"
}

interface CalendarDeltaRemote : StagedCalendarRemote {
    val deltaFormat: AdapterFormat
    suspend fun latestChanges(scope: CalendarScope): OpaqueCursor
    suspend fun receiveChanges(scope: CalendarScope, after: OpaqueCursor): ReceivedChangePage
    /** Local only. Nonzero reset signals require reconciliation, never deletion inferred from absence. */
    suspend fun decodeChanges(received: ReceivedChangePage): DecodedChangePage
    suspend fun receiveEvent(scope: CalendarScope, eventId: EventId): ReceivedCalendarEvent
    /** Local only, using already available protected keys. */
    suspend fun decodeEvent(received: ReceivedCalendarEvent): DecodedCalendarEvent
    /** Explicit network/key refresh; never called by local decode. No account attributes are changed. */
    suspend fun refreshContext(scope: CalendarScope)
}

interface DeltaSyncTransaction : StagedSyncTransaction {
    /** Sticky history gap, owned by this delta format; no implicit clearing on a fresh scan. */
    val reconciliationFormat: AdapterFormat?
    /** Archive the whole reset receipt under the staging quota in the cursor/scan transaction.
     * Reset-page notices are not authoritative actions. Keep previously acknowledged jobs intact.
     * Reject foreign scope/format; retained evidence requires explicit migration to another adapter.
     */
    fun retainReconciliation(receipt: ReceivedChangePage)
    var deltaCursor: OpaqueCursor?
    var deltaReceived: ReceivedChangePage?
    var deltaMore: Boolean
    fun deltaJobs(limit: Int = 20, blocked: Boolean = false): List<CalendarChangeJob>
    fun putDeltaJob(job: CalendarChangeJob)
    /** Must compare the exact persisted job; a stale completion cannot consume new evidence. */
    fun removeDeltaJob(expected: CalendarChangeJob): Boolean
    fun clearBlockedEvent(eventId: EventId)
    fun retryBlockedJobs()
}
