package com.patmanak.proton.andcalendarbridge.sync

/** A single adapter-sealed page. Receipt is not application or traversal completion. */
data class ReceivedCalendarPage(val request: PageRequest, val protectedPayload: String, val coverage: PageCoverage? = null) {
    init { require(protectedPayload.isNotEmpty() && protectedPayload.length <= MAX_PAYLOAD_CHARS) }
    override fun toString() = "ReceivedCalendarPage(REDACTED)"
    companion object { const val MAX_PAYLOAD_CHARS = 4 * 1024 * 1024 }
}

enum class DeferredEventReason { UNSUPPORTED_CONTENT, UNVERIFIED_CONTENT }

/** Adapter-owned encrypted retry evidence; no keys or plain event data cross this boundary. */
data class DeferredCalendarEvent(
    val eventId: EventId,
    val revision: Revision,
    val format: AdapterFormat,
    val reason: DeferredEventReason,
    val protectedPayload: String,
) {
    init { require(protectedPayload.isNotEmpty() && protectedPayload.length <= ReceivedCalendarPage.MAX_PAYLOAD_CHARS) }
    override fun toString() = "DeferredCalendarEvent(REDACTED)"
}

data class DecodedCalendarPage(val page: RemotePage, val deferred: List<DeferredCalendarEvent> = emptyList(),
    val coverage: PageCoverage? = null) {
    override fun toString() = "DecodedCalendarPage(REDACTED)"
}

/**
 * receive may use the network. decode and retryDeferred MUST use only the sealed receipt and
 * already available protected local keys/session state; missing keys are an explicit block.
 * Scope, format, request and envelope identity must be validated before isolating a bad record.
 * Unsupported individual content may be deferred, but malformed/untrusted page identity blocks
 * the whole page. Cancellation and authentication errors must never be swallowed as bad content.
 */
interface StagedCalendarRemote : ReadOnlyCalendarRemote {
    suspend fun receive(request: PageRequest): ReceivedCalendarPage
    suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage
    /** Null means the retained content remains unsupported; exceptions leave it unchanged. */
    suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert?
}

/** Transaction extension: inbox, records, issues and applied checkpoint share one commit. */
interface StagedSyncTransaction : SyncStore.Transaction {
    var coverage: CoverageLedger
    var received: ReceivedCalendarPage?
    fun deferred(eventId: EventId): DeferredCalendarEvent?
    fun defer(event: DeferredCalendarEvent)
    fun clearDeferred(eventId: EventId)
}

/** Closed condition: preserve the inbox and stop downloading rather than silently drop retry data. */
class ImportCapacityException : IllegalStateException("Calendar import storage budget reached")
