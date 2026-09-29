// SPDX-License-Identifier: GPL-3.0-only
// Protocol adaptation from Proton Android Calendar/WebClients; see PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.RemoteImportException
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.protonApi.BaseRetrofitApi
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Streaming

/** Preparation only: the owner must stage pages durably before acknowledging their next cursor. */
internal data class ProtonFeedScope(val account: String, val generation: Long, val calendar: String? = null) {
    override fun toString() = "ProtonFeedScope(REDACTED)"
}
internal data class ProtonFeedCursor(val scope: ProtonFeedScope, val value: String) {
    override fun toString() = "ProtonFeedCursor(REDACTED)"
}
internal enum class ProtonFeedAction { DELETE, CREATE, UPDATE }
internal data class ProtonFeedChange(
    val collection: String, val id: String, val action: ProtonFeedAction,
    val payload: JsonObject?, val provenance: String,
) { override fun toString() = "ProtonFeedChange(REDACTED)" }
internal data class ProtonFeedPage(
    val requested: ProtonFeedCursor, val next: ProtonFeedCursor, val more: Boolean,
    val refresh: Long, val changes: List<ProtonFeedChange>, val envelope: JsonObject,
) {
    val requiresReconciliation: Boolean get() = refresh != 0L
    override fun toString() = "ProtonFeedPage(REDACTED)"
}
internal enum class ProtonFeedFailure { AUTHENTICATION, FORBIDDEN, NOT_FOUND, NETWORK, RETRY, UNSUPPORTED }
internal class ProtonFeedException(val reason: ProtonFeedFailure, val retryAfterMillis: Long? = null) :
    IllegalStateException("PROTON_FEED_${reason.name}")

internal interface ProtonChangeFeedTransport {
    suspend fun latest(session: CatalogSession, scope: ProtonFeedScope): JsonObject
    suspend fun changes(session: CatalogSession, cursor: ProtonFeedCursor): JsonObject
    suspend fun event(session: CatalogSession, scope: ProtonFeedScope, eventId: String): JsonObject
}

/** Network-only operations. This class neither decrypts nor acknowledges a durable checkpoint. */
internal class ProtonChangeFeed(
    private val sessions: CatalogSessionSource,
    private val transport: ProtonChangeFeedTransport,
) {
    suspend fun latest(scope: ProtonFeedScope): ProtonFeedCursor = guarded(scope) { session ->
        ProtonChangeFeedParser.latest(scope, transport.latest(session, scope))
    }
    suspend fun changes(cursor: ProtonFeedCursor): ProtonFeedPage = guarded(cursor.scope) { session ->
        ProtonChangeFeedParser.validateCursor(cursor)
        ProtonChangeFeedParser.page(cursor, transport.changes(session, cursor))
    }
    suspend fun event(scope: ProtonFeedScope, eventId: String): JsonObject = guarded(scope) { session ->
        if (scope.calendar == null) feedUnsupported()
        ProtonChangeFeedParser.validateId(eventId)
        ProtonChangeFeedParser.event(scope, eventId, transport.event(session, scope, eventId))
    }
    private suspend fun <T> guarded(scope: ProtonFeedScope, block: suspend (CatalogSession) -> T): T = try {
        ProtonChangeFeedParser.validateScope(scope)
        val session = sessions.current()?.takeIf { it.userId.id == scope.account }
            ?: throw ProtonFeedException(ProtonFeedFailure.AUTHENTICATION)
        val result = block(session)
        if (sessions.current() != session) throw ProtonFeedException(ProtonFeedFailure.AUTHENTICATION)
        result
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (failure: ProtonFeedException) { throw failure }
      catch (failure: Exception) { throw failure.feedFailure() }
}

internal object ProtonChangeFeedParser {
    private val calendarCollections = mapOf("CalendarEvents" to "Event", "CalendarMembers" to "Member",
        "CalendarKeys" to "Key",
        "CalendarPassphrases" to "Passphrase", "CalendarSettings" to "CalendarSettings",
        "CalendarAlarms" to "Alarm", "CalendarSubscriptions" to "CalendarSubscription")
    private val globalCollections = mapOf("Calendars" to "Calendar", "CalendarMembers" to "Member",
        "Addresses" to "Address")

    fun latest(scope: ProtonFeedScope, root: JsonObject): ProtonFeedCursor = parsed {
        validateScope(scope)
        validateRoot(root)
        ProtonFeedCursor(scope, root.string(cursorField(scope))).also(::validateCursor)
    }

    fun page(cursor: ProtonFeedCursor, root: JsonObject): ProtonFeedPage = parsed {
        validateCursor(cursor)
        validateRoot(root)
        val next = ProtonFeedCursor(cursor.scope, root.string(cursorField(cursor.scope))).also(::validateCursor)
        val more = root.number("More")
        val refresh = root.number("Refresh")
        if (more !in 0L..1L || refresh !in 0L..Int.MAX_VALUE.toLong() ||
            (more == 1L && next == cursor)) feedUnsupported()
        // Refresh invalidates the whole observed delta. Do not publish its individual changes.
        if (refresh != 0L) return@parsed ProtonFeedPage(cursor, next, more == 1L, refresh, emptyList(), root)
        val collections = if (cursor.scope.calendar == null) globalCollections else calendarCollections
        if (cursor.scope.calendar != null && root.keys.any {
            it !in collections && it !in setOf("Code", "CalendarModelEventID", "More", "Refresh")
        }) feedUnsupported()
        val changes = collections.flatMap { (collection, payloadName) ->
            val value = root[collection]
            if (value == null || value == JsonNull) return@flatMap emptyList()
            val rows = value as? JsonArray ?: feedUnsupported()
            if (rows.size > 10_000) feedUnsupported()
            val seen = mutableSetOf<String>()
            rows.mapIndexed { index, item ->
                val row = item as? JsonObject ?: feedUnsupported()
                val id = row.string("ID").also(::validateId)
                // Source does not establish ordering of duplicate entity actions in a batch.
                if (!seen.add(id)) feedUnsupported()
                val action = when (row.number("Action")) {
                    0L -> ProtonFeedAction.DELETE
                    1L -> ProtonFeedAction.CREATE
                    2L -> ProtonFeedAction.UPDATE
                    else -> feedUnsupported()
                }
                val payload = if (action == ProtonFeedAction.DELETE) {
                    if (row[payloadName] != null && row[payloadName] != JsonNull) feedUnsupported()
                    null
                } else row.obj(payloadName).also { payload ->
                    // Subscription payloads are keyed by CalendarID and do not expose an ID.
                    // Their outer notification ID remains opaque context provenance only.
                    if (collection != "CalendarSubscriptions" && payload.string("ID") != id) feedUnsupported()
                    val calendar = cursor.scope.calendar
                    if (calendar != null) {
                        if (collection in setOf("CalendarEvents", "CalendarMembers", "CalendarSubscriptions") &&
                            payload.string("CalendarID") != calendar) feedUnsupported()
                        if (payload["CalendarID"] != null && payload.string("CalendarID") != calendar) feedUnsupported()
                    }
                }
                val provenance = digest(buildJsonObject {
                    put("format", "proton-feed-v1")
                    put("account", cursor.scope.account); put("generation", cursor.scope.generation)
                    put("calendar", cursor.scope.calendar); put("from", cursor.value); put("to", next.value)
                    put("collection", collection); put("index", index); put("entry", row)
                })
                ProtonFeedChange(collection, id, action, payload, "proton-feed-v1:$provenance")
            }
        }
        // The global envelope also contains other Core product data: retain it without interpreting it.
        ProtonFeedPage(cursor, next, more == 1L, refresh, changes, root)
    }

    fun event(scope: ProtonFeedScope, eventId: String, root: JsonObject): JsonObject = parsed {
        validateScope(scope); validateId(eventId); validateRoot(root)
        val calendar = scope.calendar ?: feedUnsupported()
        root.obj("Event").also {
            if (it.string("ID") != eventId || it.string("CalendarID") != calendar) feedUnsupported()
            it.revision()
        }
    }
    fun validateScope(scope: ProtonFeedScope) {
        validateId(scope.account)
        if (scope.generation < 0) feedUnsupported()
        scope.calendar?.let(::validateId)
    }
    fun validateCursor(cursor: ProtonFeedCursor) { validateScope(cursor.scope); validateId(cursor.value) }
    fun validateId(value: String) {
        if (value.isBlank() || value.length > 4096 || value.any { it.isISOControl() }) feedUnsupported()
    }
    private fun validateRoot(root: JsonObject) {
        if (root.toString().toByteArray(Charsets.UTF_8).size > CalendarCatalogParser.MAX_RESPONSE_BYTES ||
            root.number("Code") != 1000L) feedUnsupported()
    }
    private fun cursorField(scope: ProtonFeedScope) = if (scope.calendar == null) "EventID" else "CalendarModelEventID"
    private fun digest(value: JsonObject) = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray(Charsets.UTF_8)))
    private inline fun <T> parsed(block: () -> T): T = try { block() }
        catch (failure: RemoteImportException) { feedUnsupported() }
}

internal interface ProtonChangeFeedApi : BaseRetrofitApi {
    @Streaming @GET("core/v4/events/latest") suspend fun globalLatest(): ResponseBody
    @Streaming @GET("core/v5/events/{cursor}") suspend fun globalChanges(@Path("cursor") cursor: String): ResponseBody
    @Streaming @GET("calendar/v1/{calendar}/modelevents/latest")
    suspend fun calendarLatest(@Path("calendar") calendar: String): ResponseBody
    @Streaming @GET("calendar/v1/{calendar}/modelevents/{cursor}")
    suspend fun calendarChanges(@Path("calendar") calendar: String, @Path("cursor") cursor: String): ResponseBody
    @Streaming @GET("calendar/v1/{calendar}/events/{event}")
    suspend fun event(@Path("calendar") calendar: String, @Path("event") event: String): ResponseBody
}
internal class ProtonChangeFeedRemote(private val provider: ApiProvider) : ProtonChangeFeedTransport {
    override suspend fun latest(session: CatalogSession, scope: ProtonFeedScope): JsonObject =
        request(session) { if (scope.calendar == null) globalLatest() else calendarLatest(scope.calendar) }
    override suspend fun changes(session: CatalogSession, cursor: ProtonFeedCursor): JsonObject =
        request(session) {
            val calendar = cursor.scope.calendar
            if (calendar == null) globalChanges(cursor.value) else calendarChanges(calendar, cursor.value)
        }
    override suspend fun event(session: CatalogSession, scope: ProtonFeedScope, eventId: String): JsonObject =
        request(session) { event(scope.calendar ?: feedUnsupported(), eventId) }
    private suspend fun request(session: CatalogSession, block: suspend ProtonChangeFeedApi.() -> ResponseBody): JsonObject =
        provider.get<ProtonChangeFeedApi>(sessionId = session.sessionId)
            .invoke(forceNoRetryOnConnectionErrors = true) { block().use(::eventResponse) }.valueOrThrow
}
private fun feedUnsupported(): Nothing = throw ProtonFeedException(ProtonFeedFailure.UNSUPPORTED)
private fun Exception.feedFailure(): ProtonFeedException {
    val api = (this as? ApiException)?.error
    val reason = when {
        this is UnsupportedCatalogResponse || this is SerializationException || this is RemoteImportException ||
            api is ApiResult.Error.Parse -> ProtonFeedFailure.UNSUPPORTED
        api is ApiResult.Error.Http && api.httpCode == 401 -> ProtonFeedFailure.AUTHENTICATION
        api is ApiResult.Error.Http && api.httpCode == 403 -> ProtonFeedFailure.FORBIDDEN
        api is ApiResult.Error.Http && api.httpCode == 404 -> ProtonFeedFailure.NOT_FOUND
        this is IOException || api is ApiResult.Error.NoInternet || api is ApiResult.Error.Connection ||
            api is ApiResult.Error.Timeout -> ProtonFeedFailure.NETWORK
        else -> ProtonFeedFailure.RETRY
    }
    return ProtonFeedException(reason, (api as? ApiResult.Error.Http)?.retryAfter?.inWholeMilliseconds?.coerceAtLeast(0))
}
