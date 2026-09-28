// SPDX-License-Identifier: GPL-3.0-only
// Protocol adaptation from Proton Android Calendar; see PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.protonApi.BaseRetrofitApi
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import okhttp3.ResponseBody
import retrofit2.http.*

internal val EVENT_FORMAT = AdapterFormat("proton-calendar-window", 1)
internal val EVENT_CONTENT_MAPPING = AdapterFormat("proton-calendar-content", 3)
internal fun unsupportedEvents(): Nothing = throw RemoteImportException(RemoteImportFailure.UNSUPPORTED)

internal enum class EventWindowLayout { LEGACY, NEAR_FIRST, EXPLICIT }

/** Frozen, scoped windows; page positions are observations, never a server snapshot or delta token. */
internal data class EventWindow(val start: LocalDate, val end: LocalDate, val offset: Int,
    val type: Int, val page: Int, val size: Int, val zone: ZoneId,
    val layout: EventWindowLayout = EventWindowLayout.LEGACY,
    val explicitPlan: List<CoverageInterval> = emptyList()) {
    val from: LocalDate get() = if (layout == EventWindowLayout.EXPLICIT) explicitPlan[offset].start
        else start.plusDays(offset.toLong())
    val until: LocalDate get() = when (layout) {
        EventWindowLayout.EXPLICIT -> explicitPlan[offset].endExclusive
        EventWindowLayout.NEAR_FIRST -> start.plusDays(NEAR_SEGMENTS.first { it.first == offset }.second.toLong())
        EventWindowLayout.LEGACY -> minOf(from.plusDays(42), end)
    }
    val isFirst: Boolean get() = offset == (if (layout == EventWindowLayout.NEAR_FIRST) 60 else 0) && type == 0 && page == 0
    /** Query coverage uses the cursor's frozen calendar dates and order, never the current clock. */
    fun coverage(more: Boolean): PageCoverage {
        if (layout == EventWindowLayout.EXPLICIT)
            return PageCoverage(explicitPlan, offset, type == 0 && page == 0, type == 3 && !more)
        val segments = if (layout == EventWindowLayout.NEAR_FIRST) NEAR_SEGMENTS
            else (0 until 456 step 42).map { it to minOf(it + 42, 456) }
        return PageCoverage(segments.map { (from, until) ->
            CoverageInterval(start.plusDays(from.toLong()), start.plusDays(until.toLong()), zone)
        }, segments.indexOfFirst { it.first == offset }, type == 0 && page == 0, type == 3 && !more)
    }
    fun next(more: Boolean): EventWindow? = when {
        more -> if (page >= 10_000) unsupportedEvents() else copy(page = page + 1)
        type < 3 -> copy(type = type + 1, page = 0)
        layout == EventWindowLayout.EXPLICIT -> if (offset < explicitPlan.lastIndex)
            copy(offset = offset + 1, type = 0, page = 0) else null
        layout == EventWindowLayout.NEAR_FIRST -> NEAR_SEGMENTS.getOrNull(
            NEAR_SEGMENTS.indexOfFirst { it.first == offset } + 1)?.let { copy(offset = it.first, type = 0, page = 0) }
        until < end -> copy(offset = offset + 42, type = 0, page = 0)
        else -> null
    }
    fun encode(scope: CalendarScope): OpaqueCursor {
        val fields = listOf(scope.account.value, scope.calendar.value, scope.generation.toString(),
            start.toString(), end.toString(), offset.toString(), type.toString(), page.toString(), size.toString(), zone.id)
        // Preserve the exact ten-field encoding for already persisted cursors and sealed receipts.
        val payload = when (layout) {
            EventWindowLayout.LEGACY -> fields
            EventWindowLayout.NEAR_FIRST -> fields + NEAR_LAYOUT_MARKER
            EventWindowLayout.EXPLICIT -> fields + EXPLICIT_LAYOUT_MARKER + explicitPlan.size.toString() +
                explicitPlan.flatMap { listOf(it.start.toString(), it.endExclusive.toString()) }
        }.joinToString("\n")
        return OpaqueCursor(EVENT_FORMAT, Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()))
    }
    companion object {
        private const val NEAR_LAYOUT_MARKER = "near-first-v1"
        private const val EXPLICIT_LAYOUT_MARKER = "explicit-windows-v1"
        // Half-open, disjoint intervals: today +/-30 days, older dates, then the remaining future.
        // Each interval is at most 42 calendar days; finish every page and Type before advancing.
        private val NEAR_SEGMENTS = listOf(60 to 102, 102 to 121, 0 to 42, 42 to 60,
            121 to 163, 163 to 205, 205 to 247, 247 to 289, 289 to 331,
            331 to 373, 373 to 415, 415 to 456)

        fun planned(windows: List<CoverageInterval>, limit: Int): EventWindow {
            if (limit !in 1..100) unsupportedEvents()
            try {
                PageCoverage(windows, 0, true, false)
                if (windows.any { java.time.temporal.ChronoUnit.DAYS.between(it.start, it.endExclusive) > 42 })
                    unsupportedEvents()
                return EventWindow(windows.minOf { it.start }, windows.maxOf { it.endExclusive },
                    0, 0, 0, limit, windows.first().zone, EventWindowLayout.EXPLICIT, windows.toList())
            } catch (failure: RemoteImportException) { throw failure }
            catch (_: Exception) { unsupportedEvents() }
        }

        fun read(request: PageRequest, clock: Clock, zone: ZoneId): EventWindow {
            if (request.limit !in 1..100 || request.checkpoint.format != EVENT_FORMAT || request.checkpoint.traversalComplete) unsupportedEvents()
            val cursor = request.checkpoint.cursor ?: return LocalDate.now(clock.withZone(zone)).let {
                EventWindow(it.minusDays(90), it.plusDays(366), 60, 0, 0, request.limit, zone, EventWindowLayout.NEAR_FIRST)
            }
            if (cursor.format != EVENT_FORMAT || cursor.value.length > 20_000) unsupportedEvents()
            try {
                val values = String(Base64.getUrlDecoder().decode(cursor.value), Charsets.UTF_8).split('\n')
                val layout = when {
                    values.size == 10 -> EventWindowLayout.LEGACY
                    values.size == 11 && values[10] == NEAR_LAYOUT_MARKER -> EventWindowLayout.NEAR_FIRST
                    values.size >= 14 && values[10] == EXPLICIT_LAYOUT_MARKER -> EventWindowLayout.EXPLICIT
                    else -> unsupportedEvents()
                }
                if (values[0] != request.checkpoint.scope.account.value ||
                    values[1] != request.checkpoint.scope.calendar.value || values[2].toLong() != request.checkpoint.scope.generation) unsupportedEvents()
                val frozenZone = ZoneId.of(values[9])
                val explicit = if (layout == EventWindowLayout.EXPLICIT) {
                    val count = values[11].toInt()
                    if (count !in 1..128 || values.size != 12 + count * 2) unsupportedEvents()
                    List(count) { index -> CoverageInterval(LocalDate.parse(values[12 + index * 2]),
                        LocalDate.parse(values[13 + index * 2]), frozenZone) }.also { planned(it, request.limit) }
                } else emptyList()
                return EventWindow(LocalDate.parse(values[3]), LocalDate.parse(values[4]), values[5].toInt(),
                    values[6].toInt(), values[7].toInt(), values[8].toInt(), frozenZone, layout, explicit).also {
                    val validOffset = when (layout) {
                        EventWindowLayout.EXPLICIT -> it.offset in explicit.indices
                        EventWindowLayout.NEAR_FIRST -> NEAR_SEGMENTS.any { segment -> segment.first == it.offset }
                        EventWindowLayout.LEGACY -> it.offset in 0..455 && it.offset % 42 == 0
                    }
                    val validBounds = if (layout == EventWindowLayout.EXPLICIT)
                        it.start == explicit.minOf { interval -> interval.start } && it.end == explicit.maxOf { interval -> interval.endExclusive }
                        else it.end == it.start.plusDays(456)
                    if (!validBounds || !validOffset ||
                        it.type !in 0..3 || it.page !in 0..10_000 || it.size != request.limit) unsupportedEvents()
                }
            } catch (failure: RemoteImportException) { throw failure }
            catch (_: Exception) { unsupportedEvents() }
        }
    }
}

internal data class EncryptedEventPage(val rows: List<JsonObject>, val more: Boolean)
internal interface EventImportRemote {
    suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage
    suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject
}

internal class ProtonEventImportAdapter(private val sessions: CatalogSessionSource, private val remote: EventImportRemote,
    private val decoder: EventImportDecoder, protector: EventPayloadProtector,
    private val clock: Clock = Clock.systemUTC(), private val zone: () -> ZoneId = ZoneId::systemDefault) : RollingCalendarRemote, ContentMappingRemote {
    override val contentMapping = EVENT_CONTENT_MAPPING
    private val receipts = EventReceiptCodec(protector)
    override val format = EVENT_FORMAT
    override suspend fun currentAccount(): AccountId? = sessions.current()?.let { AccountId(it.userId.id) }
    override suspend fun dispatch(mutation: Mutation): DispatchResult = DispatchResult.Rejected
    override suspend fun queryHorizon(scope: CalendarScope): CoverageInterval = guard {
        session(scope)
        val frozenZone = zone()
        val today = LocalDate.now(clock.withZone(frozenZone))
        CoverageInterval(today.minusDays(90), today.plusDays(366), frozenZone)
    }
    override suspend fun planWindows(scope: CalendarScope, windows: List<CoverageInterval>, limit: Int): PageCheckpoint = guard {
        session(scope)
        // Cursor framing uses one line per field, so reject identities that cannot round-trip.
        if (listOf(scope.account.value, scope.calendar.value).any { it.length > 1024 || it.any { char -> char == '\n' || char == '\r' } })
            unsupportedEvents()
        val window = EventWindow.planned(windows, limit)
        PageCheckpoint(scope, format, window.encode(scope))
    }
    override suspend fun fetch(request: PageRequest): RemotePage {
        val decoded = decode(receive(request))
        // Legacy consumers cannot persist retry evidence and must not report partial success.
        if (decoded.deferred.isNotEmpty()) unsupportedEvents()
        return decoded.page
    }
    override suspend fun receive(request: PageRequest): ReceivedCalendarPage = guard {
        val initial = session(request.checkpoint.scope)
        val window = EventWindow.read(request, clock, zone())
        val calendar = request.checkpoint.scope.calendar.value
        val page = remote.page(initial, calendar, window)
        validatePage(request, page)
        checkSession(initial)
        val bootstrap = if (page.rows.isEmpty() && request.checkpoint.cursor != null) null
            else remote.bootstrap(initial, calendar)
        checkSession(initial)
        ReceivedCalendarPage(request, receipts.sealPage(request, page, bootstrap, window), window.coverage(page.more))
    }
    override suspend fun decode(received: ReceivedCalendarPage): DecodedCalendarPage = guard {
        val request = received.request
        val scope = request.checkpoint.scope
        val initial = session(scope)
        // Validate metadata without rebasing a first-page horizon after process death/date change.
        EventWindow.read(request, clock, zone())
        val evidence = receipts.openPage(received)
        val window = EventWindow.read(request.copy(checkpoint = request.checkpoint.copy(
            cursor = OpaqueCursor(format, evidence.window))), clock, zone())
        request.checkpoint.cursor?.let { if (it.value != evidence.window) unsupportedEvents() }
        if (request.checkpoint.cursor == null && !window.isFirst) unsupportedEvents()
        validatePage(request, evidence.page)
        // The sealed receipt is authoritative; old receipts keep unknown coverage after migration.
        received.coverage?.let { if (it != window.coverage(evidence.page.more)) unsupportedEvents() }
        val decoded = if (evidence.page.rows.isEmpty() && request.checkpoint.cursor != null) EventDecodeResult(emptyList(), emptyList())
            else decoder.decode(initial, scope.calendar.value, evidence.bootstrap ?: unsupportedEvents(), evidence.page.rows)
        validateDecoded(evidence.page.rows, decoded, evidence.bootstrap)
        val deferred = decoded.deferred.map { issue ->
            val row = evidence.page.rows.single { it.string("ID") == issue.eventId.value }
            DeferredCalendarEvent(issue.eventId, issue.revision, format, issue.reason,
                receipts.sealEvent(scope, row, evidence.bootstrap ?: unsupportedEvents()))
        }
        checkSession(initial)
        val next = window.next(evidence.page.more)
        DecodedCalendarPage(RemotePage(scope, format, decoded.changes, next?.encode(scope), next == null), deferred, received.coverage)
    }
    override suspend fun retryDeferred(scope: CalendarScope, event: DeferredCalendarEvent): RemoteChange.Upsert? = guard {
        val initial = session(scope)
        val (row, bootstrap) = receipts.openEvent(scope, event)
        validateRow(scope.calendar.value, row)
        val decoded = decoder.decode(initial, scope.calendar.value, bootstrap, listOf(row))
        validateDecoded(listOf(row), decoded, bootstrap)
        checkSession(initial)
        decoded.changes.singleOrNull()
    }
    private suspend fun session(scope: CalendarScope): CatalogSession = sessions.current()?.takeIf {
        it.userId.id == scope.account.value
    } ?: throw RemoteImportException(RemoteImportFailure.AUTHENTICATION)
    private suspend fun checkSession(expected: CatalogSession) {
        if (sessions.current() != expected) throw RemoteImportException(RemoteImportFailure.AUTHENTICATION)
    }
    private fun validatePage(request: PageRequest, page: EncryptedEventPage) {
        if (page.rows.size > request.limit || (page.more && page.rows.isEmpty())) unsupportedEvents()
        page.rows.forEach { validateRow(request.checkpoint.scope.calendar.value, it) }
        if (page.rows.map { it.string("ID") }.distinct().size != page.rows.size) unsupportedEvents()
    }
    private fun validateRow(calendar: String, row: JsonObject) {
        if (row.string("CalendarID") != calendar || row.string("ID").isBlank() || row.string("ID").length > 1024) unsupportedEvents()
        row.revision()
    }
    private fun validateDecoded(rows: List<JsonObject>, decoded: EventDecodeResult, bootstrap: JsonObject?) {
        val expected = rows.associate { it.string("ID") to it.revision(bootstrap) }
        val actual = decoded.changes.map { it.eventId.value to it.revision } + decoded.deferred.map { it.eventId.value to it.revision }
        if (actual.size != rows.size || actual.map { it.first }.distinct().size != actual.size || actual.toMap() != expected) unsupportedEvents()
    }
    private suspend fun <T> guard(block: suspend () -> T): T = try { block() }
      catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: RemoteImportException) { throw failure }
      catch (failure: Exception) { throw failure.importFailure() }
}

internal interface CalendarEventsApi : BaseRetrofitApi {
    @Streaming @GET("calendar/v1/{calendarId}/bootstrap")
    suspend fun bootstrap(@Path("calendarId") calendar: String): ResponseBody
    @Streaming @GET("calendar/v1/{calendarId}/events")
    suspend fun events(@Path("calendarId") calendar: String, @Query("Start") start: Long,
        @Query("End") end: Long, @Query("Timezone") zone: String, @Query("Type") type: Int,
        @Query("Page") page: Int, @Query("PageSize") size: Int): ResponseBody
}

internal class ProtonEventImportRemote(private val provider: ApiProvider) : EventImportRemote {
    override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage =
        provider.get<CalendarEventsApi>(sessionId = session.sessionId).invoke(forceNoRetryOnConnectionErrors = true) {
            events(calendar, window.from.atStartOfDay(window.zone).toEpochSecond(), window.until.atStartOfDay(window.zone).toEpochSecond(),
                window.zone.id, window.type, window.page, window.size).use { body ->
                val json = eventResponse(body)
                val more = json.number("More")
                if (more !in 0..1) unsupportedEvents()
                val rows = json.array("Events").map { it as? JsonObject ?: unsupportedEvents() }
                if (rows.size > window.size) unsupportedEvents()
                EncryptedEventPage(rows, more == 1L)
            }
        }.valueOrThrow
    override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject =
        provider.get<CalendarEventsApi>(sessionId = session.sessionId).invoke(forceNoRetryOnConnectionErrors = true) {
            bootstrap(calendar).use(::eventResponse)
        }.valueOrThrow
}

// Reuse the bounded streaming reader (1 MiB, strict UTF-8); oversized pages fail without advancing.
internal fun eventResponse(body: ResponseBody): JsonObject {
    val json = Json.parseToJsonElement(CalendarCatalogBodyReader.read(body)) as? JsonObject ?: unsupportedEvents()
    if (json.number("Code") != 1000L) unsupportedEvents()
    return json
}
internal fun JsonObject.string(name: String): String = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: unsupportedEvents()
internal fun JsonObject.optionalString(name: String): String? = if (this[name] == null || this[name] == JsonNull) null else string(name)
internal fun JsonObject.number(name: String): Long = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: unsupportedEvents()
internal fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: unsupportedEvents()
internal fun JsonObject.obj(name: String): JsonObject = this[name] as? JsonObject ?: unsupportedEvents()
/** Official event Color is an optional RGB override, independent of encrypted iCalendar parts. */
internal fun JsonObject.eventColorRgb(): Int? {
    val color = optionalString("Color") ?: return null
    if (!Regex("#[0-9a-fA-F]{6}").matches(color)) unsupportedEvents()
    return color.substring(1).toInt(16)
}
internal fun JsonObject.revision(bootstrap: JsonObject? = null): Revision {
    val modified = number("ModifyTime")
    if (modified < 0) unsupportedEvents()
    // Includes encrypted packets and metadata: equal second-resolution timestamps can still differ.
    val payload = buildJsonArray { add(this@revision); add(bootstrap?.get("CalendarSettings") ?: JsonNull) }
    val digest = MessageDigest.getInstance("SHA-256").digest(payload.toString().toByteArray(Charsets.UTF_8))
    // Inherited reminders can change while the encrypted event and ModifyTime stay unchanged.
    return Revision("event-map3:$modified:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
}
internal fun JsonObject.legacyRevision(): Revision {
    val modified = number("ModifyTime")
    if (modified < 0) unsupportedEvents()
    val digest = MessageDigest.getInstance("SHA-256").digest(toString().toByteArray(Charsets.UTF_8))
    return Revision("event-map2:$modified:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
}
internal fun Exception.importFailure(): RemoteImportException {
    val api = (this as? ApiException)?.error
    val reason = when {
        this is UnsupportedCatalogResponse || this is kotlinx.serialization.SerializationException -> RemoteImportFailure.UNSUPPORTED
        this is IOException || api is ApiResult.Error.NoInternet || api is ApiResult.Error.Connection || api is ApiResult.Error.Timeout -> RemoteImportFailure.NETWORK
        api is ApiResult.Error.Parse -> RemoteImportFailure.UNSUPPORTED
        api is ApiResult.Error.Http && api.httpCode in listOf(401, 403) -> RemoteImportFailure.AUTHENTICATION
        api is ApiResult.Error.Http && api.httpCode == 404 -> RemoteImportFailure.NOT_FOUND
        else -> RemoteImportFailure.RETRY
    }
    return RemoteImportException(reason, (api as? ApiResult.Error.Http)?.retryAfter?.inWholeMilliseconds?.coerceAtLeast(0))
}
