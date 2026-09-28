package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test

/** Opaque in-memory handles, not cryptography. Native device tests qualify the real protector. */
internal class TestEventProtector : EventPayloadProtector {
    private val values = mutableMapOf<String, String>()
    override fun seal(plain: String): String = "sealed-${values.size}".also { values[it] = plain }
    override fun open(protected: String): String = values[protected] ?: error("Unknown fixture receipt")
}

class ProtonStagedEventImportTest {
    private val scope = CalendarScope(AccountId("account"), CalendarId("calendar"), 1)
    private val request = PageRequest(PageCheckpoint(scope, EVENT_FORMAT), 20)
    private val session = CatalogSession(UserId("account"), SessionId("session"))
    private val clock = Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC)
    private val protector = TestEventProtector()

    @Test fun receiptExposesFrozenCoverageBeforeDecodeAndKeepsItAfterOfflineRestart() = runTest {
        val remote = Network(listOf(row("first")))
        val decoder = Decoder()
        val original = ProtonEventImportAdapter(CatalogSessionSource { session }, remote, decoder, protector,
            clock) { ZoneId.of("Europe/Paris") }
        val received = original.receive(request)
        val coverage = requireNotNull(received.coverage)
        assertEquals(0, decoder.calls)
        assertEquals(12, coverage.plan.size)
        assertEquals(CoverageInterval(LocalDate.parse("2026-08-27"), LocalDate.parse("2026-10-08"),
            ZoneId.of("Europe/Paris")), coverage.plan.first())
        assertEquals(LocalDate.parse("2026-06-28"), coverage.plan.minOf { it.start })
        assertEquals(LocalDate.parse("2027-09-27"), coverage.plan.maxOf { it.endExclusive })
        assertEquals(0, coverage.windowIndex)
        assertTrue(coverage.startsWindow)
        assertFalse(coverage.finishesWindow)
        remote.forbidNetwork = true
        val restarted = ProtonEventImportAdapter(CatalogSessionSource { session }, remote, decoder, protector,
            Clock.offset(clock, Duration.ofDays(200))) { ZoneId.of("America/New_York") }
        assertEquals(coverage, restarted.decode(received).coverage)
        assertEquals(1, remote.pageCalls)
    }

    @Test fun alteredCoverageCannotOverrideSealedWindowAndIsRejectedBeforeDecoder() = runTest {
        val remote = Network(listOf(row("first")))
        val decoder = Decoder()
        val adapter = adapter(remote, decoder)
        val received = adapter.receive(request)
        val metadata = requireNotNull(received.coverage)
        val changedPlan = metadata.plan.map { it.copy(start = it.start.plusDays(1), endExclusive = it.endExclusive.plusDays(1)) }
        val changedZone = metadata.plan.map { it.copy(zone = ZoneId.of("Europe/Paris")) }
        for (changed in listOf(metadata.copy(plan = changedPlan), metadata.copy(plan = changedZone),
            metadata.copy(windowIndex = 1), metadata.copy(startsWindow = false), metadata.copy(finishesWindow = true))) {
            reason(RemoteImportFailure.UNSUPPORTED) { adapter.decode(received.copy(coverage = changed)) }
        }
        assertEquals(0, decoder.calls)
    }

    @Test fun coverageEndsOnlyAfterFinalPageOfFourthPartitionAndThenAdvancesWindow() = runTest {
        val remote = Network(listOf(row("first")))
        val decoder = Decoder()
        val adapter = adapter(remote, decoder)
        var current = request
        val origin = requireNotNull(adapter.receive(current).coverage).plan
        for (type in 0..3) {
            remote.more = true
            val first = adapter.receive(current)
            assertEquals(PageCoverage(origin, 0, type == 0, false), first.coverage)
            val firstDecoded = adapter.decode(first)
            assertEquals(first.coverage, firstDecoded.coverage)
            current = current.copy(checkpoint = current.checkpoint.copy(cursor = firstDecoded.page.nextCursor))
            remote.more = false
            val last = adapter.receive(current)
            assertEquals(PageCoverage(origin, 0, false, type == 3), last.coverage)
            val lastDecoded = adapter.decode(last)
            assertEquals(last.coverage, lastDecoded.coverage)
            current = current.copy(checkpoint = current.checkpoint.copy(cursor = lastDecoded.page.nextCursor))
        }
        assertEquals(PageCoverage(origin, 1, true, false), adapter.receive(current).coverage)
    }

    @Test fun emptyFirstPartitionDoesNotFinishWindowAndLegacyReceiptRemainsUnknown() = runTest {
        val remote = Network(emptyList())
        val adapter = adapter(remote, Decoder())
        val received = adapter.receive(request)
        assertFalse(requireNotNull(received.coverage).finishesWindow)
        val legacyReceipt = received.copy(coverage = null)
        remote.forbidNetwork = true
        assertNull(adapter.decode(legacyReceipt).coverage)
    }

    @Test fun receiptSurvivesNewAdapterAndDateChangeWithoutAnyNetworkDuringDecode() = runTest {
        val remote = Network(listOf(row("first")))
        val decoder = Decoder()
        val received = adapter(remote, decoder).receive(request)
        assertEquals(1, remote.pageCalls)
        assertEquals(1, remote.bootstrapCalls)
        assertEquals(0, decoder.calls)
        assertFalse(received.protectedPayload.contains("first"))
        remote.forbidNetwork = true
        val restarted = adapter(remote, decoder, Clock.offset(clock, Duration.ofDays(70)))
        val decoded = restarted.decode(received)
        assertEquals(listOf(EventId("first")), decoded.page.changes.map { it.eventId })
        val next = EventWindow.read(request.copy(checkpoint = request.checkpoint.copy(cursor = decoded.page.nextCursor)), clock, ZoneOffset.UTC)
        assertEquals(LocalDate.parse("2026-06-28"), next.start)
        assertEquals(1, decoder.calls)
        assertEquals(1, remote.pageCalls)
    }

    @Test fun legacyFirstReceiptStillDecodesOfflineWithItsOriginalOrderAfterUpgrade() = runTest {
        val legacy = EventWindow(LocalDate.parse("2026-06-28"), LocalDate.parse("2027-09-27"),
            0, 0, 0, request.limit, ZoneId.of("Europe/Paris"))
        val page = EncryptedEventPage(listOf(row("legacy")), false)
        val received = ReceivedCalendarPage(request, EventReceiptCodec(protector).sealPage(request, page,
            buildJsonObject { put("synthetic", true) }, legacy))
        val remote = Network(emptyList()).apply { forbidNetwork = true }
        val restarted = ProtonEventImportAdapter(CatalogSessionSource { session }, remote, Decoder(), protector,
            Clock.offset(clock, Duration.ofDays(200))) { ZoneId.of("America/New_York") }
        val decoded = restarted.decode(received)
        assertEquals(listOf(EventId("legacy")), decoded.page.changes.map { it.eventId })
        assertEquals(legacy.copy(type = 1).encode(scope), decoded.page.nextCursor)
        assertNull(decoded.coverage)
        assertEquals(0, remote.pageCalls)
        assertEquals(0, remote.bootstrapCalls)
    }

    @Test fun paginatedTenThousandEventsResumeAcrossAdaptersWithoutSkippingOrGrowingPages() = runTest {
        val today = LocalDate.parse("2026-09-26")
        val firstRequest = request.copy(limit = 100)
        var pageCalls = 0
        var maxRows = 0
        val observed = mutableSetOf<String>()
        val requestedRanges = mutableListOf<Pair<LocalDate, LocalDate>>()
        val remote = object : EventImportRemote {
            override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage {
                pageCalls++
                assertEquals(100, window.size)
                requestedRanges += window.from to window.until
                val containsToday = today >= window.from && today < window.until
                if (!containsToday || window.type != 0) return EncryptedEventPage(emptyList(), false)
                val first = window.page * window.size
                val last = minOf(first + window.size, 10_000)
                val rows = (first until last).map { row("event-$it") }
                maxRows = maxOf(maxRows, rows.size)
                return EncryptedEventPage(rows, last < 10_000)
            }
            override suspend fun bootstrap(session: CatalogSession, calendar: String) = buildJsonObject { put("synthetic", true) }
        }
        var checkpoint = firstRequest.checkpoint
        var completed = false
        while (!completed && pageCalls < 200) {
            // Recreate adapter on every page and change wall clock/zone after the first page.
            val currentClock = if (pageCalls == 0) clock else Clock.offset(clock, Duration.ofDays(80))
            val currentZone = if (pageCalls == 0) ZoneOffset.UTC else ZoneId.of("Pacific/Auckland")
            val current = ProtonEventImportAdapter(CatalogSessionSource { session }, remote, Decoder(), protector,
                currentClock) { currentZone }
            val decoded = current.decode(current.receive(PageRequest(checkpoint, 100)))
            decoded.page.changes.forEach { assertTrue("Duplicated fixture identity", observed.add(it.eventId.value)) }
            completed = decoded.page.traversalComplete
            checkpoint = PageCheckpoint(scope, EVENT_FORMAT, decoded.page.nextCursor, completed)
        }
        assertTrue(completed)
        assertEquals((0 until 10_000).map { "event-$it" }.toSet(), observed)
        assertEquals(100, maxRows)
        assertEquals(today.minusDays(30), requestedRanges.first().first)
        // 100 populated pages replace one empty page in the 48-page priority traversal.
        assertEquals(147, pageCalls)
        assertEquals(today.minusDays(90), requestedRanges.minOf { it.first })
        assertEquals(today.plusDays(366), requestedRanges.maxOf { it.second })
    }

    @Test fun validUnsupportedValidRetainsEvidenceAndCanRetryAfterMapperImprovesOffline() = runTest {
        val remote = Network(listOf(row("first"), row("unsupported"), row("last")))
        val decoder = Decoder()
        val adapter = adapter(remote, decoder)
        val decoded = adapter.decode(adapter.receive(request))
        assertEquals(listOf("first", "last"), decoded.page.changes.map { it.eventId.value })
        val issue = decoded.deferred.single()
        assertEquals(EventId("unsupported"), issue.eventId)
        assertEquals(DeferredEventReason.UNSUPPORTED_CONTENT, issue.reason)
        assertNotNull(decoded.page.nextCursor)
        remote.forbidNetwork = true
        assertNull(adapter.retryDeferred(scope, issue))
        decoder.supportEverything = true
        assertEquals(EventId("unsupported"), adapter.retryDeferred(scope, issue)?.eventId)
        assertEquals(1, remote.pageCalls)
        assertEquals(1, remote.bootstrapCalls)
    }

    @Test fun legacyFetchNeverHidesDeferredContent() = runTest {
        reason(RemoteImportFailure.UNSUPPORTED) { adapter(Network(listOf(row("unsupported"))), Decoder()).fetch(request) }
    }

    @Test fun ambiguousOrForeignIdentityBlocksWholeReceiptBeforeBootstrap() = runTest {
        for (rows in listOf(listOf(row("same"), row("same")), listOf(row("", "calendar")),
            listOf(row("first", "foreign")), listOf(JsonObject(emptyMap())))) {
            val remote = Network(rows)
            reason(RemoteImportFailure.UNSUPPORTED) { adapter(remote, Decoder()).receive(request) }
            assertEquals(0, remote.bootstrapCalls)
        }
    }

    @Test fun sealedRequestVersionScopeAndDeferredIdentityAreCheckedBeforeDecode() = runTest {
        val remote = Network(listOf(row("unsupported")))
        val decoder = Decoder()
        val adapter = adapter(remote, decoder)
        val receipt = adapter.receive(request)
        reason(RemoteImportFailure.UNSUPPORTED) { adapter.decode(receipt.copy(request = request.copy(limit = 19))) }
        reason(RemoteImportFailure.UNSUPPORTED) { adapter.decode(receipt.copy(request = request.copy(checkpoint = request.checkpoint.copy(scope = scope.copy(generation = 2))))) }
        val json = Json.parseToJsonElement(protector.open(receipt.protectedPayload)) as JsonObject
        val future = protector.seal(JsonObject(json + ("version" to JsonPrimitive(2))).toString())
        reason(RemoteImportFailure.UNSUPPORTED) { adapter.decode(receipt.copy(protectedPayload = future)) }
        assertEquals(0, decoder.calls)
        val issue = adapter.decode(receipt).deferred.single()
        reason(RemoteImportFailure.UNSUPPORTED) { adapter.retryDeferred(scope.copy(calendar = CalendarId("other")), issue) }
        reason(RemoteImportFailure.UNSUPPORTED) { adapter.retryDeferred(scope, issue.copy(revision = Revision("other"))) }
        reason(RemoteImportFailure.UNSUPPORTED) { adapter.retryDeferred(scope, issue.copy(format = AdapterFormat("future", 2))) }
    }

    @Test fun sessionSwapAndCancellationDuringDecodeNeverPublishResults() = runTest {
        var current: CatalogSession? = session
        val decoder = Decoder()
        val adapter = adapter(Network(listOf(row("first"))), decoder, source = { current })
        val receipt = adapter.receive(request)
        decoder.duringDecode = { current = session.copy(sessionId = SessionId("new-session")) }
        reason(RemoteImportFailure.AUTHENTICATION) { adapter.decode(receipt) }
        decoder.duringDecode = { throw CancellationException("synthetic") }
        try { adapter.decode(receipt); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        decoder.duringDecode = { throw RemoteImportException(RemoteImportFailure.AUTHENTICATION) }
        reason(RemoteImportFailure.AUTHENTICATION) { adapter.decode(receipt) }
        decoder.duringDecode = { throw RemoteImportException(RemoteImportFailure.LOCAL_KEYS) }
        reason(RemoteImportFailure.LOCAL_KEYS) { adapter.decode(receipt) }
        current = session.copy(userId = UserId("another-account"))
        reason(RemoteImportFailure.AUTHENTICATION) { adapter.decode(receipt) }
    }

    @Test fun missingBootstrapBlocksOfflineWithoutRefetchAndDecoderCannotLoseRows() = runTest {
        val decoder = Decoder()
        val remote = Network(listOf(row("first")))
        val adapter = adapter(remote, decoder)
        val receipt = adapter.receive(request)
        val json = Json.parseToJsonElement(protector.open(receipt.protectedPayload)) as JsonObject
        val missing = protector.seal(JsonObject(json + ("bootstrap" to JsonNull)).toString())
        remote.forbidNetwork = true
        reason(RemoteImportFailure.UNSUPPORTED) { adapter.decode(receipt.copy(protectedPayload = missing)) }
        assertEquals(0, decoder.calls)
        decoder.dropRows = true
        reason(RemoteImportFailure.UNSUPPORTED) { adapter.decode(receipt) }
    }

    private fun adapter(remote: Network, decoder: Decoder, time: Clock = clock,
        source: CatalogSessionSource = CatalogSessionSource { session }) =
        ProtonEventImportAdapter(source, remote, decoder, protector, time) { ZoneOffset.UTC }

    private fun row(id: String, calendar: String = "calendar") = buildJsonObject {
        put("ID", id); put("CalendarID", calendar); put("ModifyTime", 10)
    }
    private class Network(private val rows: List<JsonObject>) : EventImportRemote {
        var more = false
        var forbidNetwork = false
        var pageCalls = 0
        var bootstrapCalls = 0
        override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage {
            check(!forbidNetwork) { "Network forbidden" }; pageCalls++
            return EncryptedEventPage(rows, more)
        }
        override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject {
            check(!forbidNetwork) { "Network forbidden" }; bootstrapCalls++
            return buildJsonObject { put("synthetic", true) }
        }
    }
    private class Decoder : EventImportDecoder {
        var calls = 0
        var supportEverything = false
        var dropRows = false
        var duringDecode: () -> Unit = {}
        override suspend fun decode(session: CatalogSession, calendar: String, bootstrap: JsonObject, rows: List<JsonObject>): EventDecodeResult {
            calls++; duringDecode()
            if (dropRows) return EventDecodeResult(emptyList(), emptyList())
            val (unsupported, valid) = rows.partition { !supportEverything && it.string("ID") == "unsupported" }
            return EventDecodeResult(valid.map { RemoteChange.Upsert(EventId(it.string("ID")), it.revision(),
                EventContent("Synthetic", EventTime.AllDay(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02")))) },
                unsupported.map { DeferredEventContent(EventId(it.string("ID")), it.revision(), DeferredEventReason.UNSUPPORTED_CONTENT) })
        }
    }
    private suspend fun reason(expected: RemoteImportFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected closed failure") } catch (failure: RemoteImportException) { assertEquals(expected, failure.reason) }
    }
}
