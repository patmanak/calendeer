// SPDX-License-Identifier: GPL-3.0-only
// Calendar feed protocol adaptation; see PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException

internal val CALENDAR_DELTA_FORMAT = AdapterFormat("proton-calendar-delta", 1)

internal fun interface EventContextRefresher {
    /** Explicit authenticated GETs; must not change account attributes or set up new keys. */
    suspend fun refresh(session: CatalogSession)
}

/** Calendar-only delta integration. The separate baseline adapter retains its existing format. */
internal class ProtonCalendarDeltaAdapter(
    private val baseline: StagedCalendarRemote,
    private val sessions: CatalogSessionSource,
    private val feed: ProtonChangeFeed,
    private val events: EventImportRemote,
    private val decoder: EventImportDecoder,
    protector: EventPayloadProtector,
    private val context: EventContextRefresher,
) : CalendarDeltaRemote, RollingCalendarRemote, ContentMappingRemote {
    override val contentMapping = EVENT_CONTENT_MAPPING
    private val changes = CalendarDeltaReceiptCodec(protector)
    private val receipts = EventReceiptCodec(protector)
    override val format get() = baseline.format
    override val deltaFormat = CALENDAR_DELTA_FORMAT
    override suspend fun currentAccount() = baseline.currentAccount()
    override suspend fun dispatch(mutation: Mutation) = DispatchResult.Rejected
    override suspend fun fetch(request: PageRequest) = baseline.fetch(request)
    override suspend fun receive(request: PageRequest) = baseline.receive(request)
    override suspend fun decode(received: ReceivedCalendarPage) = baseline.decode(received)
    override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent) = baseline.retryDeferred(scope, event)
    override suspend fun queryHorizon(scope: CalendarScope): CoverageInterval =
        (baseline as? RollingCalendarRemote ?: unsupportedEvents()).queryHorizon(scope)
    override suspend fun planWindows(scope: CalendarScope, windows: List<CoverageInterval>, limit: Int): PageCheckpoint =
        (baseline as? RollingCalendarRemote ?: unsupportedEvents()).planWindows(scope, windows, limit)

    override suspend fun latestChanges(scope: CalendarScope): OpaqueCursor = operation(scope) {
        changes.cursor(feed.latest(scope.feedScope()))
    }
    override suspend fun receiveChanges(scope: CalendarScope, after: OpaqueCursor): ReceivedChangePage = operation(scope) {
        val requested = changes.readCursor(scope, after)
        val page = feed.changes(requested)
        ReceivedChangePage(scope, after, changes.seal(page))
    }
    override suspend fun decodeChanges(received: ReceivedChangePage): DecodedChangePage = operation(received.scope) {
        val page = changes.open(received)
        val ordered = page.changes.sortedBy { if (it.collection == "CalendarEvents") 1 else 0 }
        val notices = ordered.map { change ->
            if (change.collection == "CalendarSettings") CalendarChangeNotice(change.provenance, CalendarNoticeKind.CONTENT_CHANGED)
            else if (change.collection != "CalendarEvents") CalendarChangeNotice(change.provenance, CalendarNoticeKind.CONTEXT_CHANGED)
            else if (change.action == ProtonFeedAction.DELETE) CalendarChangeNotice(change.provenance,
                CalendarNoticeKind.EVENT_DELETED, EventId(change.id), Revision(change.provenance))
            else CalendarChangeNotice(change.provenance, CalendarNoticeKind.EVENT_CHANGED, EventId(change.id))
        }
        DecodedChangePage(received.scope, received.after, changes.cursor(page.next), page.more,
            page.requiresReconciliation, notices)
    }
    override suspend fun receiveEvent(scope: CalendarScope, eventId: EventId): ReceivedCalendarEvent = operation(scope) { session ->
        val row = feed.event(scope.feedScope(), eventId.value)
        checkSession(session)
        val bootstrap = events.bootstrap(session, scope.calendar.value)
        ReceivedCalendarEvent(scope, eventId, row.revision(bootstrap), EVENT_FORMAT, receipts.sealEvent(scope, row, bootstrap))
    }
    override suspend fun decodeEvent(received: ReceivedCalendarEvent): DecodedCalendarEvent = operation(received.scope) { session ->
        // Single-event receipts deliberately share the versioned deferred-content evidence format.
        val evidence = DeferredCalendarEvent(received.eventId, received.revision, received.format,
            DeferredEventReason.UNSUPPORTED_CONTENT, received.protectedPayload)
        val (row, bootstrap) = receipts.openEvent(received.scope, evidence)
        if (row.string("CalendarID") != received.scope.calendar.value) unsupportedEvents()
        val decoded = decoder.decode(session, received.scope.calendar.value, bootstrap, listOf(row))
        val resultIdentities = decoded.changes.map { it.eventId to it.revision } + decoded.deferred.map { it.eventId to it.revision }
        if (resultIdentities != listOf(received.eventId to row.revision(bootstrap))) unsupportedEvents()
        decoded.changes.singleOrNull()?.let {
            // Finish old acquired work with its original display contract and revision.
            // Profile admission invalidates reusable coverage so a fresh scan upgrades it.
            val change = if (received.revision == row.legacyRevision())
                it.copy(revision = received.revision, content = it.content.copy(details = null)) else it
            DecodedCalendarEvent(change = change)
        }
            ?: DecodedCalendarEvent(deferred = evidence.copy(reason = decoded.deferred.single().reason))
    }
    override suspend fun refreshContext(scope: CalendarScope): Unit = operation(scope) { session ->
        context.refresh(session)
        checkSession(session)
        val bootstrap = events.bootstrap(session, scope.calendar.value)
        checkSession(session)
        val decoded = decoder.decode(session, scope.calendar.value, bootstrap, emptyList())
        if (decoded.changes.isNotEmpty() || decoded.deferred.isNotEmpty()) unsupportedEvents()
    }

    private suspend fun <T> operation(scope: CalendarScope, block: suspend (CatalogSession) -> T): T = try {
        ProtonChangeFeedParser.validateScope(scope.feedScope())
        val initial = sessions.current()?.takeIf { it.userId.id == scope.account.value }
            ?: throw RemoteImportException(RemoteImportFailure.AUTHENTICATION)
        val result = block(initial)
        checkSession(initial)
        result
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: RemoteImportException) { throw failure }
      catch (failure: ProtonFeedException) {
        val reason = when (failure.reason) {
            ProtonFeedFailure.AUTHENTICATION, ProtonFeedFailure.FORBIDDEN -> RemoteImportFailure.AUTHENTICATION
            ProtonFeedFailure.NOT_FOUND -> RemoteImportFailure.NOT_FOUND
            ProtonFeedFailure.NETWORK -> RemoteImportFailure.NETWORK
            ProtonFeedFailure.UNSUPPORTED -> RemoteImportFailure.UNSUPPORTED
            ProtonFeedFailure.RETRY -> RemoteImportFailure.RETRY
        }
        throw RemoteImportException(reason, failure.retryAfterMillis)
    } catch (failure: Exception) { throw failure.importFailure() }

    private suspend fun checkSession(expected: CatalogSession) {
        if (sessions.current() != expected) throw RemoteImportException(RemoteImportFailure.AUTHENTICATION)
    }
}

internal fun CalendarScope.feedScope() = ProtonFeedScope(account.value, generation, calendar.value)
