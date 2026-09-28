// SPDX-License-Identifier: GPL-3.0-only
// Protocol field/query adaptation from Proton WebClients; see PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarId
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogFailure
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogPort
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogResult
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.protonApi.BaseRetrofitApi
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import me.proton.core.network.domain.session.SessionId
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Streaming

internal data class CatalogSession(val userId: UserId, val sessionId: SessionId) {
    override fun toString(): String = "CatalogSession(REDACTED)"
}

internal fun interface CatalogSessionSource {
    suspend fun current(): CatalogSession?
}

internal fun interface CalendarCatalogRemote {
    suspend fun fetch(session: CatalogSession): List<CalendarSummary>
}

/** The shared owner serializes calls with authentication/logout. Recheck identity after every read. */
internal class ProtonCalendarCatalogAdapter(
    private val sessions: CatalogSessionSource,
    private val remote: CalendarCatalogRemote,
) : CalendarCatalogPort {
    override suspend fun load(): CalendarCatalogResult = try {
        val initial = sessions.current()
        if (initial == null) {
            CalendarCatalogResult.Failure(CalendarCatalogFailure.AUTHENTICATION_REQUIRED)
        } else {
            val calendars = remote.fetch(initial)
            if (sessions.current() != initial) {
                CalendarCatalogResult.Failure(CalendarCatalogFailure.AUTHENTICATION_REQUIRED)
            } else {
                CalendarCatalogResult.Success(AccountId(initial.userId.id), calendars)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        failure.catalogFailure()
    }
}

internal interface CalendarCatalogApi : BaseRetrofitApi {
    // Mirrors WebClients CalendarModel: complete catalog with the current user's members inline.
    // Do not add Page/PageSize and then incorrectly treat the first page as a complete catalog.
    @Streaming
    @GET("calendar/v1?OrderBy=0&ReturnFlags=1")
    suspend fun calendars(): ResponseBody
}

internal class ProtonCalendarCatalogRemote(private val provider: ApiProvider) : CalendarCatalogRemote {
    override suspend fun fetch(session: CatalogSession): List<CalendarSummary> =
        provider.get<CalendarCatalogApi>(sessionId = session.sessionId)
            .invoke(forceNoRetryOnConnectionErrors = true) {
                calendars().use { body -> CalendarCatalogParser.parse(CalendarCatalogBodyReader.read(body)) }
            }.valueOrThrow
}

internal object CalendarCatalogBodyReader {
    fun read(body: ResponseBody): String {
        if (body.contentLength() > CalendarCatalogParser.MAX_RESPONSE_BYTES) throw UnsupportedCatalogResponse()
        val bytes = ByteArrayOutputStream()
        body.byteStream().use { source ->
            val buffer = ByteArray(8_192)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read > CalendarCatalogParser.MAX_RESPONSE_BYTES - bytes.size()) throw UnsupportedCatalogResponse()
                bytes.write(buffer, 0, read)
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw UnsupportedCatalogResponse()
        }
    }
}

/** No item is silently dropped; unsupported response shapes fail the entire metadata snapshot. */
internal object CalendarCatalogParser {
    const val MAX_RESPONSE_BYTES = 1_048_576
    const val MAX_CALENDARS = 1_024

    fun parse(response: String): List<CalendarSummary> {
        if (response.length > MAX_RESPONSE_BYTES || response.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            throw UnsupportedCatalogResponse()
        }
        val root = Json.parseToJsonElement(response) as? JsonObject ?: throw UnsupportedCatalogResponse()
        val code = root["Code"] as? JsonPrimitive ?: throw UnsupportedCatalogResponse()
        if (code.isString || code.intOrNull != 1_000) throw UnsupportedCatalogResponse()
        val rows = root["Calendars"] as? JsonArray ?: throw UnsupportedCatalogResponse()
        if (rows.size > MAX_CALENDARS) throw UnsupportedCatalogResponse()
        val seen = mutableSetOf<String>()
        return rows.map { row ->
            val calendar = row as? JsonObject ?: throw UnsupportedCatalogResponse()
            val id = calendar.requiredString("ID", 4_096)
            if (id.isBlank() || !seen.add(id)) throw UnsupportedCatalogResponse()
            val members = calendar["Members"] as? JsonArray ?: throw UnsupportedCatalogResponse()
            // Mirrors WebClients getVisualCalendar: the list endpoint supplies own members only.
            // The first one supplies display metadata, even when multiple own addresses exist.
            val member = members.firstOrNull() as? JsonObject ?: throw UnsupportedCatalogResponse()
            if (member.requiredString("CalendarID", 4_096) != id) throw UnsupportedCatalogResponse()
            val title = member.requiredString("Name", 1_024)
            val color = (member["Color"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }?.drop(1)?.toInt(16)
            CalendarSummary(CalendarId(id), title, color)
        }
    }

    private fun JsonObject.requiredString(field: String, maxLength: Int): String {
        val value = this[field] as? JsonPrimitive ?: throw UnsupportedCatalogResponse()
        if (!value.isString || value.content.length > maxLength) throw UnsupportedCatalogResponse()
        return value.content
    }
}

internal class UnsupportedCatalogResponse : IllegalStateException("UNSUPPORTED_CALENDAR_CATALOG")

private fun Exception.catalogFailure(): CalendarCatalogResult.Failure {
    val apiError = (this as? ApiException)?.error
    val category = when {
        this is UnsupportedCatalogResponse || this is SerializationException -> CalendarCatalogFailure.UNSUPPORTED_RESPONSE
        apiError is ApiResult.Error.NoInternet -> CalendarCatalogFailure.UNREACHABLE
        apiError is ApiResult.Error.Timeout -> CalendarCatalogFailure.TIMED_OUT
        apiError is ApiResult.Error.Connection -> CalendarCatalogFailure.UNREACHABLE
        apiError is ApiResult.Error.Parse -> CalendarCatalogFailure.UNSUPPORTED_RESPONSE
        apiError is ApiResult.Error.Http -> when (apiError.httpCode) {
            401 -> CalendarCatalogFailure.AUTHENTICATION_REQUIRED
            403 -> CalendarCatalogFailure.FORBIDDEN
            429 -> CalendarCatalogFailure.RATE_LIMITED
            else -> CalendarCatalogFailure.TRY_AGAIN
        }
        this is IOException -> CalendarCatalogFailure.UNREACHABLE
        else -> CalendarCatalogFailure.TRY_AGAIN
    }
    val retryAfter = (apiError as? ApiResult.Error.Http)?.retryAfter?.inWholeMilliseconds?.coerceAtLeast(0)
    return CalendarCatalogResult.Failure(category, retryAfter)
}
