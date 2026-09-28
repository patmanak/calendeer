package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.auth.*
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogResult
import java.time.*
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.*
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test

class ProtonRollingWindowTest {
    private val scope = CalendarScope(AccountId("account"), CalendarId("calendar"), 1)
    private val session = CatalogSession(UserId("account"), SessionId("session"))
    private val clock = Clock.fixed(Instant.parse("2026-10-24T23:30:00Z"), ZoneOffset.UTC)
    private val paris = ZoneId.of("Europe/Paris")
    private val protector = TestEventProtector()
    private val day = CoverageInterval(LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-26"), paris)

    @Test fun horizonUsesLocalCalendarDaysAndPlanningPerformsNoNetworkOrDecode() = runTest {
        val remote = Network()
        val adapter = adapter(remote)
        assertEquals(CoverageInterval(LocalDate.parse("2026-07-27"), LocalDate.parse("2027-10-26"), paris),
            adapter.queryHorizon(scope))
        val planned = adapter.planWindows(scope, listOf(day), 20)
        assertFalse(planned.traversalComplete)
        val window = read(planned)
        assertEquals(day.start, window.from)
        assertEquals(day.endExclusive, window.until)
        assertEquals(25L, Duration.between(window.from.atStartOfDay(window.zone), window.until.atStartOfDay(window.zone)).toHours())
        assertEquals(PageCoverage(listOf(day), 0, true, false), window.coverage(false))
        assertEquals(0, remote.calls)
    }

    @Test fun singleNewDayQueriesEveryPartitionAndCompletesWithoutScanningOldDates() = runTest {
        val remote = Network()
        val adapter = adapter(remote)
        var checkpoint = adapter.planWindows(scope, listOf(day), 20)
        for (type in 0..3) {
            val received = adapter.receive(PageRequest(checkpoint, 20))
            assertEquals(PageCoverage(listOf(day), 0, type == 0, type == 3), received.coverage)
            val decoded = adapter.decode(received)
            assertEquals(type == 3, decoded.page.traversalComplete)
            checkpoint = PageCheckpoint(scope, EVENT_FORMAT, decoded.page.nextCursor, decoded.page.traversalComplete)
        }
        assertEquals(listOf(0, 1, 2, 3), remote.windows.map { it.type })
        assertTrue(remote.windows.all { it.from == day.start && it.until == day.endExclusive })
        assertEquals(4, remote.calls)
    }

    @Test fun multiplePagesAndUnsortedDisjointPlanRemainFrozenAcrossOfflineRestart() = runTest {
        val later = day.copy(start = day.start.plusDays(30), endExclusive = day.endExclusive.plusDays(30))
        val plan = listOf(later, day)
        val remote = Network().apply { more = true; rows = listOf(row("synthetic")) }
        val adapter = adapter(remote)
        val checkpoint = adapter.planWindows(scope, plan, 20)
        val received = adapter.receive(PageRequest(checkpoint, 20))
        remote.offline = true
        val restart = adapter(remote, Clock.offset(clock, Duration.ofDays(300)), ZoneId.of("America/New_York"))
        val decoded = restart.decode(received)
        assertEquals(PageCoverage(plan, 0, true, false), decoded.coverage)
        // A server-returned event may span both sides of a new query period; do not clip it.
        assertEquals(EventTime.AllDay(day.start.minusDays(100), day.endExclusive.plusDays(100)),
            (decoded.page.changes.single() as RemoteChange.Upsert).content.time)
        val next = read(PageCheckpoint(scope, EVENT_FORMAT, decoded.page.nextCursor))
        assertEquals(plan, next.explicitPlan)
        assertEquals(1, next.page)
        assertEquals(0, next.type)
        assertEquals(paris, next.zone)
        assertEquals(later.start, next.from)
        assertEquals(1, remote.calls)
        assertEquals(next.copy(type = 1, page = 0), next.next(false))
        val second = next.copy(type = 3).next(false)!!
        assertEquals(day.start, second.from)
        assertEquals(PageCoverage(plan, 1, true, false), second.coverage(false))
        assertNull(second.copy(type = 3).next(false))
    }

    @Test fun explicitWindowMetadataCannotBeChangedIndependentlyOfSealedEvidence() = runTest {
        val adapter = adapter(Network())
        val checkpoint = adapter.planWindows(scope, listOf(day), 20)
        val received = adapter.receive(PageRequest(checkpoint, 20))
        reject { adapter.decode(received.copy(coverage = requireNotNull(received.coverage).copy(finishesWindow = true))) }
        val changed = adapter.planWindows(scope, listOf(day.copy(endExclusive = day.endExclusive.plusDays(1))), 20)
        reject { adapter.decode(received.copy(request = PageRequest(changed, 20))) }
    }

    @Test fun plansRejectEmptyOverlappingOversizedMixedZoneAndInvalidLimits() = runTest {
        val adapter = adapter(Network())
        val tooMany = (0..128).map { day.copy(start = day.start.plusDays(it.toLong()), endExclusive = day.endExclusive.plusDays(it.toLong())) }
        for (plan in listOf(emptyList(), listOf(day, day),
            listOf(day.copy(endExclusive = day.start.plusDays(43))), tooMany,
            listOf(day, day.copy(start = day.start.plusDays(3), endExclusive = day.endExclusive.plusDays(3), zone = ZoneOffset.UTC)))) {
            reject { adapter.planWindows(scope, plan, 20) }
        }
        for (limit in listOf(0, 101)) reject { adapter.planWindows(scope, listOf(day), limit) }
        val maxPlan = tooMany.take(128)
        assertEquals(maxPlan, read(adapter.planWindows(scope, maxPlan, 20)).explicitPlan)
    }

    @Test fun malformedIndicesCountsBoundsAndScopeNeverReachNetwork() = runTest {
        val remote = Network()
        val adapter = adapter(remote)
        val checkpoint = adapter.planWindows(scope, listOf(day), 20)
        val fields = String(Base64.getUrlDecoder().decode(checkpoint.cursor!!.value)).split('\n')
        assertEquals("explicit-windows-v1", fields[10])
        suspend fun malformed(index: Int, value: String) {
            val changed = fields.toMutableList().also { it[index] = value }
            reject { adapter.receive(PageRequest(checkpoint.copy(cursor = raw(changed)), 20)) }
        }
        for ((index, value) in listOf(5 to "-1", 5 to "1", 6 to "4", 7 to "10001", 8 to "19",
            11 to "0", 11 to "129", 11 to "2", 10 to "explicit-windows-v2", 3 to "2026-10-24", 4 to "2026-10-27",
            12 to "2026-10-26", 13 to "2026-12-31")) malformed(index, value)
        reject { adapter.receive(PageRequest(checkpoint.copy(cursor = raw(fields + "extra")), 20)) }
        reject { adapter.receive(PageRequest(checkpoint.copy(scope = scope.copy(calendar = CalendarId("other"))), 20)) }
        reject { adapter.receive(PageRequest(checkpoint.copy(scope = scope.copy(generation = 2)), 20)) }
        reject { adapter.receive(PageRequest(checkpoint, 10)) }
        assertEquals(0, remote.calls)
    }

    @Test fun planningRejectsUnauthenticatedAccountAndUnframeableIdentity() = runTest {
        val adapter = adapter(Network())
        try { adapter.queryHorizon(scope.copy(account = AccountId("other"))); fail("Expected authentication failure") }
        catch (failure: RemoteImportException) { assertEquals(RemoteImportFailure.AUTHENTICATION, failure.reason) }
        reject { adapter.planWindows(scope.copy(calendar = CalendarId("calendar\nother")), listOf(day), 20) }
    }

    @Test fun productionWrappersForwardCapabilityWithinAuthenticationGateAndFailClosedForOlderBaseline() = runTest {
        val remote = Network()
        val baseline = adapter(remote)
        val delta = delta(baseline, remote)
        val auth = object : AuthenticationFlowPort {
            override suspend fun restore() = AuthenticationStep.Authenticated
            override suspend fun signIn(username: AuthenticationSecret, password: AuthenticationSecret) = error("Unexpected login")
            override suspend fun submitCode(code: AuthenticationSecret) = error("Unexpected code")
            override suspend fun unlock(password: AuthenticationSecret) = error("Unexpected unlock")
            override suspend fun cancel() = Unit
        }
        val port = SerializedAuthenticationPort({
            ProtonAuthenticationGraph(auth, delta) { CalendarCatalogResult.Success(scope.account, emptyList()) }
        }, {}, StandardTestDispatcher(testScheduler))
        port.restore()
        assertEquals(baseline.queryHorizon(scope), port.queryHorizon(scope))
        assertEquals(baseline.planWindows(scope, listOf(day), 20), port.planWindows(scope, listOf(day), 20))
        assertEquals(0, remote.calls)
        port.cancel()
        for (operation in listOf<suspend () -> Unit>(
            { port.queryHorizon(scope) }, { port.planWindows(scope, listOf(day), 20) })) {
            try { operation(); fail("Expected authentication gate") }
            catch (failure: RemoteImportException) { assertEquals(RemoteImportFailure.AUTHENTICATION, failure.reason) }
        }
        val legacyOnly = object : StagedCalendarRemote by baseline {}
        val unsupported = delta(legacyOnly, remote)
        reject { unsupported.queryHorizon(scope) }
        reject { unsupported.planWindows(scope, listOf(day), 20) }
    }

    private fun delta(baseline: StagedCalendarRemote, remote: Network): ProtonCalendarDeltaAdapter {
        val source = CatalogSessionSource { session }
        val feed = ProtonChangeFeed(source, object : ProtonChangeFeedTransport {
            override suspend fun latest(session: CatalogSession, scope: ProtonFeedScope): JsonObject = error("Unexpected feed")
            override suspend fun changes(session: CatalogSession, cursor: ProtonFeedCursor): JsonObject = error("Unexpected feed")
            override suspend fun event(session: CatalogSession, scope: ProtonFeedScope, eventId: String): JsonObject = error("Unexpected feed")
        })
        return ProtonCalendarDeltaAdapter(baseline, source, feed, remote,
            EventImportDecoder { _, _, _, _ -> error("Unexpected decode") }, protector, EventContextRefresher { error("Unexpected refresh") })
    }

    private fun read(checkpoint: PageCheckpoint) = EventWindow.read(PageRequest(checkpoint, 20), clock, ZoneOffset.UTC)
    private fun raw(fields: List<String>) = OpaqueCursor(EVENT_FORMAT,
        Base64.getUrlEncoder().withoutPadding().encodeToString(fields.joinToString("\n").toByteArray()))
    private fun adapter(remote: Network, time: Clock = clock, zone: ZoneId = paris) =
        ProtonEventImportAdapter(CatalogSessionSource { session }, remote, EventImportDecoder { _, _, _, rows ->
            EventDecodeResult(rows.map { RemoteChange.Upsert(EventId(it.string("ID")), it.revision(),
                EventContent("Synthetic", EventTime.AllDay(day.start.minusDays(100), day.endExclusive.plusDays(100)))) }, emptyList())
        }, protector, time) { zone }
    private fun row(id: String) = buildJsonObject { put("ID", id); put("CalendarID", "calendar"); put("ModifyTime", 10) }
    private class Network : EventImportRemote {
        var calls = 0
        var more = false
        var offline = false
        var rows = emptyList<JsonObject>()
        val windows = mutableListOf<EventWindow>()
        override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage {
            check(!offline); calls++; windows += window
            return EncryptedEventPage(rows, more)
        }
        override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject {
            check(!offline); return JsonObject(emptyMap())
        }
    }
    private suspend fun reject(block: suspend () -> Unit) {
        try { block(); fail("Expected unsupported response") }
        catch (failure: RemoteImportException) { assertEquals(RemoteImportFailure.UNSUPPORTED, failure.reason) }
    }
}
