package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test

class ProtonCalendarDeltaAdapterTest {
    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 1)
    private val session = CatalogSession(UserId(scope.account.value), SessionId("synthetic-session"))
    private val protector = TestEventProtector()
    private var current: CatalogSession? = session
    private val source = CatalogSessionSource { current }

    @Test fun calendarMemberNoticeRefreshesContextWithoutTreatingItAsAnEvent() = runTest {
        val network = Network().apply { changes = obj("""{"Code":1000,"CalendarModelEventID":"next","More":0,"Refresh":0,
            "CalendarMembers":[{"ID":"member","Action":2,
            "Member":{"ID":"member","CalendarID":"synthetic-calendar"}}]}""") }
        val adapter = adapter(network, Decoder())
        val receipt = adapter.receiveChanges(scope, adapter.latestChanges(scope))
        network.forbidNetwork = true
        val notices = adapter.decodeChanges(receipt).notices
        assertEquals(listOf(CalendarNoticeKind.CONTEXT_CHANGED), notices.map { it.kind })
        assertNull(notices.single().eventId)
        assertEquals(0, network.bootstrapCalls)
    }

    @Test fun bareCalendarMemberDeletionDoesNotBecomeAnEventDeletion() = runTest {
        val network = Network().apply { changes = obj("""{"Code":1000,"CalendarModelEventID":"next","More":0,"Refresh":0,
            "CalendarMembers":[{"ID":"member","Action":0}]}""") }
        val adapter = adapter(network, Decoder())
        val receipt = adapter.receiveChanges(scope, adapter.latestChanges(scope))
        network.forbidNetwork = true
        val notice = adapter.decodeChanges(receipt).notices.single()
        assertEquals(CalendarNoticeKind.CONTEXT_CHANGED, notice.kind)
        assertNull(notice.eventId)
    }

    @Test fun stagedFeedDecodesAfterRestartWithoutNetworkAndPlacesContextBeforeEvents() = runTest {
        val network = Network()
        val decoder = Decoder()
        val adapter = adapter(network, decoder)
        val before = adapter.latestChanges(scope)
        assertNotEquals(EVENT_FORMAT, before.format)
        network.changes = obj("""{"Code":1000,"CalendarModelEventID":"next","More":1,"Refresh":0,
            "CalendarEvents":[{"ID":"deleted","Action":0},{"ID":"changed","Action":2,"Event":{"ID":"changed","CalendarID":"synthetic-calendar"}}],
            "CalendarKeys":[{"ID":"key","Action":2,"Key":{"ID":"key","CalendarID":"synthetic-calendar"}}]}""")
        val receipt = adapter.receiveChanges(scope, before)
        network.forbidNetwork = true
        val decoded = adapter(network, decoder).decodeChanges(receipt)
        assertEquals(listOf(CalendarNoticeKind.CONTEXT_CHANGED, CalendarNoticeKind.EVENT_DELETED, CalendarNoticeKind.EVENT_CHANGED), decoded.notices.map { it.kind })
        assertEquals(EventId("deleted"), decoded.notices[1].eventId)
        assertNotNull(decoded.notices[1].revision)
        assertTrue(decoded.more)
        assertFalse(decoded.reconciliationRequired)
        assertEquals(decoded, adapter.decodeChanges(receipt))
        assertEquals(0, decoder.calls)
        assertEquals(0, network.bootstrapCalls)
    }

    @Test fun refreshDiscardsDeletionInterpretationAndDemandsReconciliation() = runTest {
        val network = Network().apply { changes = obj("""{"Code":1000,"CalendarModelEventID":"next","More":0,"Refresh":255,"CalendarEvents":[{"ID":"event","Action":0}]}""") }
        val adapter = adapter(network, Decoder())
        val receipt = adapter.receiveChanges(scope, adapter.latestChanges(scope))
        val page = adapter.decodeChanges(receipt)
        assertTrue(page.reconciliationRequired)
        assertTrue(page.notices.isEmpty())
    }

    @Test fun settingsNoticeInvalidatesContentRatherThanOnlyRefreshingKeys() = runTest {
        val network = Network().apply { changes = obj("""{"Code":1000,"CalendarModelEventID":"next","More":0,"Refresh":0,
            "CalendarEvents":[{"ID":"event","Action":2,"Event":{"ID":"event","CalendarID":"synthetic-calendar"}}],
            "CalendarSettings":[{"ID":"settings","Action":2,"CalendarSettings":{"ID":"settings","CalendarID":"synthetic-calendar"}}]}""") }
        val decoder = Decoder()
        val adapter = adapter(network, decoder)
        val receipt = adapter.receiveChanges(scope, adapter.latestChanges(scope))
        network.forbidNetwork = true
        val notices = adapter(network, decoder).decodeChanges(receipt).notices
        assertEquals(listOf(CalendarNoticeKind.CONTENT_CHANGED, CalendarNoticeKind.EVENT_CHANGED), notices.map { it.kind })
        assertNull(notices.first().eventId)
        assertEquals(0, network.bootstrapCalls)
        assertEquals(0, decoder.calls)
    }

    @Test fun receivedEventUsesOfflineDecoderAndRetainsUnsupportedEvidenceWithoutRefetch() = runTest {
        val network = Network()
        val decoder = Decoder()
        val adapter = adapter(network, decoder)
        val receipt = adapter.receiveEvent(scope, EventId("event"))
        assertEquals(0, decoder.calls)
        assertEquals(1, network.bootstrapCalls)
        network.forbidNetwork = true
        val fresh = adapter(network, decoder)
        val change = fresh.decodeEvent(receipt).change!!
        assertEquals(EventId("event"), change.eventId)
        assertEquals(EventDetails(listOf(EventReminder(15))), change.content.details)
        decoder.unsupported = true
        val issue = fresh.decodeEvent(receipt).deferred!!
        assertEquals(receipt.protectedPayload, issue.protectedPayload)
        assertEquals(receipt.revision, issue.revision)
        assertEquals(DeferredEventReason.UNSUPPORTED_CONTENT, issue.reason)
    }

    @Test fun unchangedRemoteRowAcquiresANewRevisionWhenDefaultSettingsChange() = runTest {
        val network = Network()
        val adapter = adapter(network, Decoder())
        val first = adapter.receiveEvent(scope, EventId("event"))
        network.bootstrapData = obj("""{"CalendarSettings":{"CalendarID":"synthetic-calendar","DefaultPartDayNotifications":[{"Type":1,"Trigger":"-PT30M"}],"DefaultFullDayNotifications":[]}}""")
        val second = adapter.receiveEvent(scope, EventId("event"))
        assertNotEquals(first.revision, second.revision)
        assertEquals(obj(protector.open(first.protectedPayload)).obj("row"), obj(protector.open(second.protectedPayload)).obj("row"))
        network.forbidNetwork = true
        assertEquals(first.revision, adapter.decodeEvent(first).change!!.revision)
        assertEquals(second.revision, adapter.decodeEvent(second).change!!.revision)
    }

    @Test fun legacySingleEventReceiptKeepsItsOriginalRevisionAndUnknownDetailsOffline() = runTest {
        val network = Network()
        val decoder = Decoder()
        val acquired = adapter(network, decoder).receiveEvent(scope, EventId("event"))
        val envelope = obj(protector.open(acquired.protectedPayload))
        assertEquals(1L, envelope.number("version"))
        val legacyRevision = envelope.obj("row").legacyRevision()
        val legacy = acquired.copy(revision = legacyRevision)
        network.forbidNetwork = true
        val restarted = adapter(network, decoder)
        val change = restarted.decodeEvent(legacy).change!!
        assertEquals(legacyRevision, change.revision)
        assertNull(change.content.details)
        assertEquals(change, restarted.decodeEvent(legacy).change)
        assertEquals(1, network.bootstrapCalls)
        assertEquals(2, decoder.calls)
    }

    @Test fun forgedLegacyHashAndMismatchedMappingContextAreRejectedBeforeDecoding() = runTest {
        val network = Network()
        val decoder = Decoder()
        val adapter = adapter(network, decoder)
        val acquired = adapter.receiveEvent(scope, EventId("event"))
        val row = obj(protector.open(acquired.protectedPayload)).obj("row")
        network.forbidNetwork = true
        reason(RemoteImportFailure.UNSUPPORTED) {
            adapter.decodeEvent(acquired.copy(revision = Revision("event-map2:12:forged")))
        }
        val differentContext = obj("""{"CalendarSettings":{"CalendarID":"synthetic-calendar","DefaultPartDayNotifications":[],"DefaultFullDayNotifications":[]}}""")
        val resealed = EventReceiptCodec(protector).sealEvent(scope, row, differentContext)
        reason(RemoteImportFailure.UNSUPPORTED) {
            adapter.decodeEvent(acquired.copy(protectedPayload = resealed))
        }
        assertEquals(0, decoder.calls)
    }

    @Test fun missingRemoteEventIsNotADeletionAndNeverFetchesBootstrap() = runTest {
        val network = Network().apply { eventFailure = ApiException(ApiResult.Error.Http(404, "synthetic")) }
        reason(RemoteImportFailure.NOT_FOUND) { adapter(network, Decoder()).receiveEvent(scope, EventId("event")) }
        assertEquals(0, network.bootstrapCalls)
    }

    @Test fun scopedCursorAndSealedVersionAfterAndEventIdentityRejectForeignEvidence() = runTest {
        val network = Network()
        val decoder = Decoder()
        val adapter = adapter(network, decoder)
        val before = adapter.latestChanges(scope)
        reason(RemoteImportFailure.UNSUPPORTED) { adapter.receiveChanges(scope.copy(generation = 2), before) }
        reason(RemoteImportFailure.UNSUPPORTED) { adapter.receiveChanges(scope, OpaqueCursor(EVENT_FORMAT, before.value)) }
        val receipt = adapter.receiveChanges(scope, before)
        reason(RemoteImportFailure.UNSUPPORTED) { adapter.decodeChanges(receipt.copy(scope = scope.copy(calendar = CalendarId("other")))) }
        val json = Json.parseToJsonElement(protector.open(receipt.protectedPayload)) as JsonObject
        val future = protector.seal(JsonObject(json + ("version" to JsonPrimitive(2))).toString())
        reason(RemoteImportFailure.UNSUPPORTED) { adapter.decodeChanges(receipt.copy(protectedPayload = future)) }
        val next = adapter.decodeChanges(receipt).next
        reason(RemoteImportFailure.UNSUPPORTED) { adapter.decodeChanges(receipt.copy(after = next)) }
        val event = adapter.receiveEvent(scope, EventId("event"))
        reason(RemoteImportFailure.UNSUPPORTED) { adapter.decodeEvent(event.copy(eventId = EventId("other"))) }
        reason(RemoteImportFailure.UNSUPPORTED) { adapter.decodeEvent(event.copy(format = CALENDAR_DELTA_FORMAT)) }
        assertEquals(0, decoder.calls)
    }

    @Test fun refreshContextIsExplicitAndMissingKeysCannotBecomeEmptySuccess() = runTest {
        val network = Network()
        val decoder = Decoder()
        val adapter = adapter(network, decoder)
        adapter.refreshContext(scope)
        assertEquals(1, network.contextCalls)
        assertEquals(1, network.bootstrapCalls)
        assertEquals(1, decoder.calls)
        decoder.failure = RemoteImportException(RemoteImportFailure.LOCAL_KEYS)
        reason(RemoteImportFailure.LOCAL_KEYS) { adapter.refreshContext(scope) }
        assertEquals(2, network.contextCalls)
    }

    @Test fun sessionReplacementDuringBootstrapAndCancellationNeverPublishResults() = runTest {
        val network = Network().apply { duringBootstrap = { current = session.copy(sessionId = SessionId("replacement")) } }
        val adapter = adapter(network, Decoder())
        reason(RemoteImportFailure.AUTHENTICATION) { adapter.receiveEvent(scope, EventId("event")) }
        current = session
        network.duringBootstrap = { throw CancellationException("synthetic") }
        try { adapter.receiveEvent(scope, EventId("event")); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        current = session.copy(userId = UserId("foreign"))
        reason(RemoteImportFailure.AUTHENTICATION) { adapter.latestChanges(scope) }
    }

    private fun adapter(network: Network, decoder: Decoder) = ProtonCalendarDeltaAdapter(
        Baseline, source, ProtonChangeFeed(source, network), network, decoder, protector,
        EventContextRefresher { network.checkNetwork(); network.contextCalls++ })

    private class Network : ProtonChangeFeedTransport, EventImportRemote {
        var forbidNetwork = false
        var bootstrapCalls = 0
        var contextCalls = 0
        var duringBootstrap: () -> Unit = {}
        var eventFailure: Exception? = null
        var bootstrapData = Json.parseToJsonElement("""{"CalendarSettings":{"CalendarID":"synthetic-calendar","DefaultPartDayNotifications":[{"Type":1,"Trigger":"-PT15M"}],"DefaultFullDayNotifications":[]}}""") as JsonObject
        var changes = Json.parseToJsonElement("""{"Code":1000,"CalendarModelEventID":"next","More":0,"Refresh":0}""") as JsonObject
        fun checkNetwork() { check(!forbidNetwork) { "NETWORK_FORBIDDEN" } }
        override suspend fun latest(session: CatalogSession, scope: ProtonFeedScope): JsonObject {
            checkNetwork(); return Json.parseToJsonElement("""{"Code":1000,"CalendarModelEventID":"before"}""") as JsonObject
        }
        override suspend fun changes(session: CatalogSession, cursor: ProtonFeedCursor): JsonObject { checkNetwork(); return changes }
        override suspend fun event(session: CatalogSession, scope: ProtonFeedScope, eventId: String): JsonObject {
            checkNetwork(); eventFailure?.let { throw it }
            return buildJsonObject { put("Code", 1000); putJsonObject("Event") {
                put("ID", eventId); put("CalendarID", scope.calendar); put("ModifyTime", 12)
            } }
        }
        override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject {
            checkNetwork(); bootstrapCalls++; duringBootstrap(); return bootstrapData
        }
        override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage = error("Baseline not requested")
    }
    private class Decoder : EventImportDecoder {
        var calls = 0
        var unsupported = false
        var failure: Exception? = null
        override suspend fun decode(session: CatalogSession, calendar: String, bootstrap: JsonObject, rows: List<JsonObject>): EventDecodeResult {
            calls++; failure?.let { throw it }
            return if (unsupported) EventDecodeResult(emptyList(), rows.map { DeferredEventContent(EventId(it.string("ID")), it.revision(bootstrap), DeferredEventReason.UNSUPPORTED_CONTENT) })
            else EventDecodeResult(rows.map { RemoteChange.Upsert(EventId(it.string("ID")), it.revision(bootstrap),
                EventContent("Synthetic", EventTime.AllDay(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)),
                    details = EventDetails(listOf(EventReminder(15))))) }, emptyList())
        }
    }
    private object Baseline : StagedCalendarRemote {
        override val format = EVENT_FORMAT
        override suspend fun currentAccount(): AccountId? = error("Unexpected baseline operation")
        override suspend fun fetch(request: PageRequest): RemotePage = error("Unexpected baseline operation")
        override suspend fun receive(request: PageRequest): ReceivedCalendarPage = error("Unexpected baseline operation")
        override suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage = error("Unexpected baseline operation")
        override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? = error("Unexpected baseline operation")
        override suspend fun dispatch(mutation: Mutation) = DispatchResult.Rejected
    }
    private fun obj(value: String) = Json.parseToJsonElement(value) as JsonObject
    private suspend fun reason(expected: RemoteImportFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected closed failure") } catch (failure: RemoteImportException) { assertEquals(expected, failure.reason) }
    }
}
