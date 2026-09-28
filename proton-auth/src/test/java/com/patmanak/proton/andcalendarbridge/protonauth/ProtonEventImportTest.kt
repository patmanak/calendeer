package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test

class ProtonEventImportTest {
    @Test fun importSupportsDurableReceiveBeforeOfflineDecode() {
        val adapter: Any = adapter(FakeRemote())
        assertTrue("Import must separate network receipt from offline decoding", adapter is StagedCalendarRemote)
    }
    @Test fun eventColorsPreserveRgbAndDistinguishInheritanceFromAnExplicitColor() {
        fun color(json: String) = (Json.parseToJsonElement(json) as JsonObject).eventColorRgb()
        assertEquals(0xAABBCC, color("""{"Color":"#AaBbCc"}"""))
        assertEquals(0, color("""{"Color":"#000000"}"""))
        assertEquals(0xFFFFFF, color("""{"Color":"#FFFFFF"}"""))
        assertNull(color("{}"))
        assertNull(color("""{"Color":null}"""))
        unsupported { color("""{"Color":"not-a-color"}""") }
        unsupported { color("""{"Color":42}""") }
    }
    @Test fun eventMappingRevisionDoesNotCollideWithPreviouslyImportedColorlessContent() {
        val row = Json.parseToJsonElement("""{"ModifyTime":10,"Color":"#AABBCC"}""") as JsonObject
        assertTrue(row.revision().value.startsWith("event-map3:"))
    }
    private val scope = CalendarScope(AccountId("account"), CalendarId("calendar"), 1)
    private val request = PageRequest(PageCheckpoint(scope, EVENT_FORMAT), 20)
    private val session = CatalogSession(UserId("account"), SessionId("session"))
    private val clock = Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneOffset.UTC)

    @Test fun cursorFreezesHorizonAndRequestSizeAndRejectsForeignScope() {
        val first = EventWindow.read(request, clock, ZoneId.of("Europe/Paris"))
        assertEquals(LocalDate.parse("2026-08-27"), first.from)
        assertEquals(42L, java.time.temporal.ChronoUnit.DAYS.between(first.from, first.until))
        val continued = request.copy(checkpoint = request.checkpoint.copy(cursor = first.next(true)!!.encode(scope)))
        val recovered = EventWindow.read(continued, Clock.offset(clock, java.time.Duration.ofDays(90)), ZoneOffset.UTC)
        assertEquals(first.copy(page = 1), recovered)
        unsupported { EventWindow.read(continued.copy(limit = 10), clock, ZoneOffset.UTC) }
        unsupported { EventWindow.read(continued.copy(checkpoint = continued.checkpoint.copy(scope = scope.copy(generation = 2))), clock, ZoneOffset.UTC) }
        unsupported { EventWindow.read(continued.copy(checkpoint = continued.checkpoint.copy(scope = scope.copy(account = AccountId("other")))), clock, ZoneOffset.UTC) }
    }

    @Test fun advancesEveryExplicitMorePageThenAllTypesAndChunks() {
        var window: EventWindow? = EventWindow.read(request, clock, ZoneOffset.UTC)
        var pages = 0
        while (window != null) { pages++; window = window.next(false) }
        assertEquals(48, pages)
        val first = EventWindow.read(request, clock, ZoneOffset.UTC)
        assertEquals(0, first.next(true)!!.type)
        assertEquals(1, first.next(false)!!.type)
        assertEquals(102, first.copy(type = 3).next(false)!!.offset)
    }

    @Test fun prioritizesNearDatesThenCoversTheWholeFrozenHorizonExactlyOnce() {
        var window: EventWindow? = EventWindow.read(request, clock, ZoneOffset.UTC)
        val segments = mutableListOf<Pair<Int, Int>>()
        while (window != null) {
            val current = window
            if (current.type == 0) segments += current.offset to
                java.time.temporal.ChronoUnit.DAYS.between(current.start, current.until).toInt()
            assertEquals(current.copy(page = 1), current.next(true))
            window = current.next(false)
        }
        assertEquals(listOf(60 to 102, 102 to 121, 0 to 42, 42 to 60,
            121 to 163, 163 to 205, 205 to 247, 247 to 289, 289 to 331,
            331 to 373, 373 to 415, 415 to 456), segments)
        val covered = segments.flatMap { (start, end) -> (start until end).toList() }
        assertEquals((0 until 456).toList(), covered.sorted())
        assertEquals(covered.size, covered.distinct().size)
        assertTrue(segments.all { (start, end) -> end - start in 1..42 })
    }

    @Test fun priorityCursorPersistsAtEveryTypeAndChunkBoundaryAcrossDateAndZoneChanges() {
        var window: EventWindow? = EventWindow.read(request, clock, ZoneId.of("Europe/Paris"))
        while (window != null) {
            val current = window
            val cursor = current.encode(scope)
            assertEquals(11, cursorFields(cursor).size)
            assertEquals("near-first-v1", cursorFields(cursor).last())
            val restarted = EventWindow.read(request.copy(checkpoint = request.checkpoint.copy(cursor = cursor)),
                Clock.offset(clock, java.time.Duration.ofDays(120)), ZoneId.of("America/New_York"))
            assertEquals(current, restarted)
            window = current.next(false)
        }
    }

    @Test fun newHorizonUsesLocalDateAndCalendarDaysAcrossDaylightSaving() {
        val time = Clock.fixed(Instant.parse("2026-10-24T23:30:00Z"), ZoneOffset.UTC)
        val first = EventWindow.read(request, time, ZoneId.of("Europe/Paris"))
        assertEquals(LocalDate.parse("2026-09-25"), first.from)
        assertEquals(LocalDate.parse("2026-11-06"), first.until)
        assertEquals(42L * 24 + 1, java.time.Duration.between(
            first.from.atStartOfDay(first.zone), first.until.atStartOfDay(first.zone)).toHours())
        assertEquals(ZoneId.of("Europe/Paris"), first.zone)
    }

    @Test fun legacyCursorEncodingAndTraversalRemainByteCompatible() {
        val fields = listOf("account", "calendar", "1", "2026-06-28", "2027-09-27", "0", "0", "0", "20", "Europe/Paris")
        val cursor = rawCursor(fields)
        var window: EventWindow? = EventWindow.read(request.copy(checkpoint = request.checkpoint.copy(cursor = cursor)), clock, ZoneOffset.UTC)
        assertEquals(cursor, window!!.encode(scope))
        var pages = 0
        while (window != null) {
            assertEquals(10, cursorFields(window.encode(scope)).size)
            if (window.type == 0) assertEquals((pages / 4) * 42, window.offset)
            pages++
            window = window.next(false)
        }
        assertEquals(44, pages)
    }

    @Test fun legacyCoverageUsesElevenChronologicalFrozenWindowsAndHonorsIntermediateResume() {
        val cursor = rawCursor(listOf("account", "calendar", "1", "2026-06-28", "2027-09-27",
            "420", "3", "7", "20", "Europe/Paris"))
        val window = EventWindow.read(request.copy(checkpoint = request.checkpoint.copy(cursor = cursor)),
            Clock.offset(clock, java.time.Duration.ofDays(300)), ZoneId.of("America/New_York"))
        val coverage = window.coverage(false)
        assertEquals(11, coverage.plan.size)
        assertEquals(10, coverage.windowIndex)
        assertFalse(coverage.startsWindow)
        assertTrue(coverage.finishesWindow)
        assertEquals(CoverageInterval(LocalDate.parse("2026-06-28"), LocalDate.parse("2026-08-09"),
            ZoneId.of("Europe/Paris")), coverage.plan.first())
        assertEquals(CoverageInterval(LocalDate.parse("2027-08-22"), LocalDate.parse("2027-09-27"),
            ZoneId.of("Europe/Paris")), coverage.plan.last())
        assertEquals((0 until 456).toList(), coverage.plan.flatMap { interval ->
            java.time.temporal.ChronoUnit.DAYS.between(window.start, interval.start).toInt() until
                java.time.temporal.ChronoUnit.DAYS.between(window.start, interval.endExclusive).toInt()
        })
        assertFalse(window.coverage(true).finishesWindow)
    }

    @Test fun rejectsUnknownLayoutsOffsetsAndMalformedCursorShapes() {
        val fields = listOf("account", "calendar", "1", "2026-06-28", "2027-09-27", "60", "0", "0", "20", "UTC", "near-first-v1")
        fun reject(values: List<String>) = unsupported {
            EventWindow.read(request.copy(checkpoint = request.checkpoint.copy(cursor = rawCursor(values))), clock, ZoneOffset.UTC)
        }
        for (offset in listOf(-1, 1, 59, 61, 84, 120, 122, 420, 455, 456))
            reject(fields.toMutableList().also { it[5] = offset.toString() })
        reject(fields.dropLast(1)) // Offset 60 is never valid in the legacy layout.
        reject(fields.dropLast(1) + "near-first-v2")
        reject(fields + "extra")
        reject(fields.toMutableList().also { it[4] = "2027-09-28" })
        for (index in listOf(6, 7)) reject(fields.toMutableList().also { it[index] = "-1" })
        reject(fields.toMutableList().also { it[6] = "4" })
        reject(fields.toMutableList().also { it[7] = "10001" })
    }

    @Test fun legacyFirstReceiptResumesOfflineAndKeepsLegacyCursor() = runTest {
        val protector = TestEventProtector()
        val oldWindow = EventWindow(LocalDate.parse("2026-06-28"), LocalDate.parse("2027-09-27"),
            0, 0, 0, 20, ZoneId.of("Europe/Paris"))
        val receipt = ReceivedCalendarPage(request, EventReceiptCodec(protector).sealPage(request,
            EncryptedEventPage(emptyList(), false), JsonObject(emptyMap()), oldWindow))
        val remote = FakeRemote().apply { duringPage = { error("Offline decoding must not fetch") } }
        val restarted = ProtonEventImportAdapter(CatalogSessionSource { session }, remote,
            EventImportDecoder { _, _, _, _ -> EventDecodeResult(emptyList(), emptyList()) }, protector,
            Clock.offset(clock, java.time.Duration.ofDays(120))) { ZoneOffset.UTC }
        val next = restarted.decode(receipt).page.nextCursor!!
        assertEquals(oldWindow.copy(type = 1).encode(scope), next)
        assertEquals(10, cursorFields(next).size)
        assertEquals(0, remote.bootstrapCalls)
    }

    @Test fun firstReceiptRejectsLaterPositionsInBothLayouts() = runTest {
        val protector = TestEventProtector()
        val remote = FakeRemote()
        val adapter = ProtonEventImportAdapter(CatalogSessionSource { session }, remote,
            EventImportDecoder { _, _, _, _ -> EventDecodeResult(emptyList(), emptyList()) }, protector, clock) { ZoneOffset.UTC }
        val near = EventWindow.read(request, clock, ZoneOffset.UTC)
        val legacy = EventWindow(LocalDate.parse("2026-06-28"), LocalDate.parse("2027-09-27"), 0, 0, 0, 20, ZoneOffset.UTC)
        for (window in listOf(near.copy(offset = 0), near.copy(offset = 102), near.copy(type = 1),
            near.copy(page = 1), legacy.copy(offset = 42), legacy.copy(type = 1), legacy.copy(page = 1))) {
            val receipt = ReceivedCalendarPage(request, EventReceiptCodec(protector).sealPage(request,
                EncryptedEventPage(emptyList(), false), JsonObject(emptyMap()), window))
            assertReason(RemoteImportFailure.UNSUPPORTED) { adapter.decode(receipt) }
        }
    }

    private fun rawCursor(fields: List<String>) = OpaqueCursor(EVENT_FORMAT,
        Base64.getUrlEncoder().withoutPadding().encodeToString(fields.joinToString("\n").toByteArray()))
    private fun cursorFields(cursor: OpaqueCursor) = String(Base64.getUrlDecoder().decode(cursor.value), Charsets.UTF_8).split('\n')

    @Test fun emptyFirstPageQualifiesKeysAndLaterEmptyPagesSkipBootstrap() = runTest {
        val remote = FakeRemote()
        val adapter = adapter(remote)
        val page = adapter.fetch(request)
        assertTrue(page.changes.isEmpty())
        assertFalse(page.traversalComplete)
        assertNotNull(page.nextCursor)
        assertEquals(1, remote.bootstrapCalls)
        adapter.fetch(request.copy(checkpoint = request.checkpoint.copy(cursor = page.nextCursor)))
        assertEquals(1, remote.bootstrapCalls)
        assertEquals(AccountId("account"), adapter.currentAccount())
    }

    @Test fun sessionChangeAndForeignAccountNeverPublishFetchedEvents() = runTest {
        var current: CatalogSession? = session
        val remote = FakeRemote().apply { duringPage = { current = session.copy(sessionId = SessionId("replacement")) } }
        val adapter = adapter(remote) { current }
        assertReason(RemoteImportFailure.AUTHENTICATION) { adapter.fetch(request) }
        assertReason(RemoteImportFailure.AUTHENTICATION) { adapter.fetch(request.copy(checkpoint = request.checkpoint.copy(scope = scope.copy(account = AccountId("other"))))) }
        current = null
        assertNull(adapter.currentAccount())
    }

    @Test fun malformedMoreAndOversizedPagesFailWithoutCheckpoint() = runTest {
        val remote = FakeRemote().apply { result = EncryptedEventPage(emptyList(), true) }
        val adapter = adapter(remote)
        assertReason(RemoteImportFailure.UNSUPPORTED) { adapter.fetch(request) }
        remote.result = EncryptedEventPage(List(21) { JsonObject(emptyMap()) }, false)
        assertReason(RemoteImportFailure.UNSUPPORTED) { adapter.fetch(request) }
        assertEquals(0, remote.bootstrapCalls)
    }

    @Test fun cancellationPropagatesWithoutClosedErrorWrapping() = runTest {
        val remote = FakeRemote().apply { duringPage = { throw CancellationException("synthetic") } }
        try { adapter(remote).fetch(request); fail() } catch (_: CancellationException) { }
    }

    @Test fun revisionDistinguishesChangesWithinSameSecond() {
        fun row(name: String) = Json.parseToJsonElement("""{"ModifyTime":42,"Data":"$name"}""") as JsonObject
        assertEquals(row("one").revision(), row("one").revision())
        assertNotEquals(row("one").revision(), row("two").revision())
        unsupported { (Json.parseToJsonElement("""{"ModifyTime":-1}""") as JsonObject).revision() }
    }

    private fun adapter(remote: FakeRemote, sessions: CatalogSessionSource = CatalogSessionSource { session }) =
        ProtonEventImportAdapter(sessions, remote, EventImportDecoder { _, _, _, _ -> EventDecodeResult(emptyList(), emptyList()) },
            TestEventProtector(), clock) { ZoneOffset.UTC }

    private class FakeRemote : EventImportRemote {
        var result = EncryptedEventPage(emptyList(), false)
        var duringPage: () -> Unit = {}
        var bootstrapCalls = 0
        override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage { duringPage(); return result }
        override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject { bootstrapCalls++; return JsonObject(emptyMap()) }
    }
    private fun unsupported(block: () -> Unit) {
        try { block(); fail("Expected closed unsupported failure") } catch (failure: RemoteImportException) { assertEquals(RemoteImportFailure.UNSUPPORTED, failure.reason) }
    }
    private suspend fun assertReason(reason: RemoteImportFailure, block: suspend () -> Unit) {
        try { block(); fail("Expected closed failure") } catch (failure: RemoteImportException) { assertEquals(reason, failure.reason) }
    }
}
