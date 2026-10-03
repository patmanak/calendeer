// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import android.content.Context
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.EventId
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.protonApi.BaseRetrofitApi
import okhttp3.Headers
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Streaming

enum class EventHeaderShape { ABSENT, EMPTY, SINGLE, MULTIPLE }
enum class EventNumberShape { ABSENT, NULL, NON_NEGATIVE_INTEGER, OTHER }

/** Descriptive metadata only. No values and no authority to admit concurrent writes. */
data class EventVersionProfile(
    val etag: EventHeaderShape,
    val lastModified: EventHeaderShape,
    val modifyTime: EventNumberShape,
    val createTime: EventNumberShape,
)

internal fun eventVersionProfile(headers: Headers, row: JsonObject): EventVersionProfile {
    fun header(name: String): EventHeaderShape {
        val values = headers.values(name)
        return when {
            values.isEmpty() -> EventHeaderShape.ABSENT
            values.size > 1 -> EventHeaderShape.MULTIPLE
            values.single().isBlank() -> EventHeaderShape.EMPTY
            else -> EventHeaderShape.SINGLE
        }
    }
    fun number(name: String): EventNumberShape {
        val value = row[name] ?: return EventNumberShape.ABSENT
        if (value == JsonNull) return EventNumberShape.NULL
        val primitive = value as? JsonPrimitive ?: return EventNumberShape.OTHER
        return if (!primitive.isString && primitive.content.matches(Regex("0|[1-9][0-9]*")) &&
            primitive.content.toLongOrNull() != null) EventNumberShape.NON_NEGATIVE_INTEGER
        else EventNumberShape.OTHER
    }
    // Cardinality/emptiness only: SINGLE does not validate HTTP tag/date syntax.
    return EventVersionProfile(header("ETag"), header("Last-Modified"), number("ModifyTime"), number("CreateTime"))
}

internal interface EventVersionApi : BaseRetrofitApi {
    @Streaming @GET("calendar/v1/{calendar}/events/{event}")
    suspend fun event(@Path("calendar") calendar: String, @Path("event") event: String): Response<ResponseBody>
}

/** Uses the same bounded strict UTF-8/envelope/identity checks as import. */
internal fun readEventVersion(response: Response<ResponseBody>, scope: ProtonFeedScope, eventId: String): EventVersionProfile =
    try {
        try {
            if (!response.isSuccessful) throw IOException()
            val root = eventResponse(response.body() ?: throw IOException())
            val row = ProtonChangeFeedParser.event(scope, eventId, root)
            eventVersionProfile(response.headers(), row)
        } finally {
            try { response.body()?.close() } finally { response.errorBody()?.close() }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { throw IOException("EVENT_VERSION_OBSERVATION_UNAVAILABLE") }

/** Shared testable session gate. No request or follow-up after an identity/session refusal. */
internal suspend fun inspectEventVersion(
    sessions: CatalogSessionSource, scope: ProtonFeedScope, eventId: String,
    authorize: suspend () -> Boolean,
    request: suspend (CatalogSession) -> EventVersionProfile,
): EventVersionProfile? = try {
    ProtonChangeFeedParser.validateScope(scope); ProtonChangeFeedParser.validateId(eventId)
    if (scope.calendar == null) null else {
        val session = sessions.current()?.takeIf { it.userId.id == scope.account }
        if (session == null || !authorize() || sessions.current() != session) null
        else request(session).takeIf { sessions.current() == session }
    }
} catch (cancelled: CancellationException) { throw cancelled }
  catch (_: Exception) { null }

/** Debug-only authenticated GET. Reflection drift fails closed; never exposes raw metadata. */
object ProtonEventVersionProbe {
    suspend fun inspect(context: Context, scope: CalendarScope, eventId: EventId,
        expectedIdentityHash: String): EventVersionProfile? = withContext(Dispatchers.IO) {
        try {
            if (!expectedIdentityHash.matches(Regex("[a-f0-9]{64}"))) return@withContext null
            val port = ProtonAuthentication.localEditPreparations(context)
            val lock = field(port, "lock") as Mutex
            lock.withLock {
                if (field(port, "authenticated") != true) return@withLock null
                val graph = field(port, "graph") as ProtonAuthenticationGraph
                val adapter = graph.localEdits as? ProtonLocalTitlePreparationAdapter ?: return@withLock null
                val sessions = field(adapter, "sessions") as CatalogSessionSource
                inspectEventVersion(sessions, scope.feedScope(), eventId.value,
                    authorize = { graph.matchesIdentityHash(scope.account, expectedIdentityHash) },
                    request = { session ->
                        val remote = field(adapter, "events") as EventImportRemote
                        val provider = field(remote, "provider") as ApiProvider
                        provider.get<EventVersionApi>(sessionId = session.sessionId)
                            .invoke(forceNoRetryOnConnectionErrors = true) {
                                readEventVersion(event(scope.calendar.value, eventId.value), scope.feedScope(), eventId.value)
                            }.valueOrThrow
                    })
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { null }
    }

    private fun field(value: Any, name: String): Any? = value.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(value)
}
