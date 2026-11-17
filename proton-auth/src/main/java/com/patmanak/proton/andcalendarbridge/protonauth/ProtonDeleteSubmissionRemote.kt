// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.protonApi.BaseRetrofitApi
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.*

/** Only this exact authenticated single-event request may produce absence. */
internal sealed interface ProtonDeleteEventRead {
    class Present(row: JsonObject) : ProtonDeleteEventRead {
        private val text = deleteSnapshot(row).toString()
        val row get() = Json.parseToJsonElement(text) as JsonObject
        override fun toString() = "ProtonDeleteEventRead.Present(redacted)"
    }
    class Absent(val calendar: String, val eventId: String) : ProtonDeleteEventRead {
        override fun toString() = "ProtonDeleteEventRead.Absent(redacted)"
    }
    data object Unknown : ProtonDeleteEventRead
}

internal interface DeleteSubmissionRemote {
    suspend fun send(session: CatalogSession, calendar: String, request: JsonObject, allowsTransport: () -> Boolean): String
    suspend fun event(session: CatalogSession, calendar: String, id: String): ProtonDeleteEventRead
}

/** Code 2501 is evidence only for an exact event GET, never for a search or a mutation response.
 * Its service meaning under revoked access remains unqualified; fresh context checks surround it.
 */
internal object ProtonDeleteEventResponseParser {
    fun parse(url: HttpUrl, calendar: String, eventId: String, httpCode: Int, body: String): ProtonDeleteEventRead = try {
        require(url.scheme == "https" && url.host == "calendar-api.proton.me" && url.port == 443 && url.query == null && url.fragment == null)
        require(url.pathSegments == listOf("calendar", "v1", calendar, "events", eventId))
        require(calendar.isNotBlank() && eventId.isNotBlank())
        deleteBounded(body); require(UniqueJsonKeys(body).validate())
        val root = (Json.parseToJsonElement(body) as JsonObject).also { deleteValidateJson(it) }
        when {
            httpCode == 200 && root.number("Code") == 1000L -> {
                val row = root.obj("Event")
                require(row.string("ID") == eventId && row.string("CalendarID") == calendar)
                ProtonDeleteEventRead.Present(row)
            }
            httpCode in setOf(200, 400, 404, 422) && root.number("Code") == 2501L -> {
                require(root.keys.all { it in setOf("Code", "Error", "Details") } && root["Event"] == null)
                // The measured exact-event 422 envelope includes an empty Details object.
                // Nonempty, null or differently typed details remain ambiguous and held.
                root["Details"]?.let { require(it is JsonObject && it.isEmpty()) }
                root["Error"]?.let { require(it is JsonPrimitive && it.isString) }
                ProtonDeleteEventRead.Absent(calendar, eventId)
            }
            else -> ProtonDeleteEventRead.Unknown
        }
    } catch (_: Exception) { ProtonDeleteEventRead.Unknown }
}

internal sealed interface ProtonDeleteResponse {
    data object ReportedSuccess : ProtonDeleteResponse
    data class ReportedFailure(val code: Long) : ProtonDeleteResponse
    data object Unknown : ProtonDeleteResponse
}

/** Indexed operation success needs no Event. None of these results establishes remote absence. */
internal object ProtonDeleteResponseParser {
    fun parse(body: String): ProtonDeleteResponse = try {
        deleteBounded(body); require(UniqueJsonKeys(body).validate())
        val root = (Json.parseToJsonElement(body) as JsonObject).also { deleteValidateJson(it) }
        require(root.number("Code") in setOf(1000L, 1001L))
        val entry = root.array("Responses").single() as JsonObject
        require(entry.number("Index") == 0L)
        val response = entry.obj("Response")
        require(response["Event"] == null || response["Event"] == JsonNull)
        val code = response.number("Code")
        if (code == 1000L && (response["Error"] == null || response["Error"] == JsonNull)) ProtonDeleteResponse.ReportedSuccess
        else if (code > 0 && code != 1000L) ProtonDeleteResponse.ReportedFailure(code)
        else ProtonDeleteResponse.Unknown
    } catch (_: Exception) { ProtonDeleteResponse.Unknown }
}

internal interface ProtonDeleteSubmissionApi : BaseRetrofitApi {
    @Streaming @PUT("calendar/v1/{calendar}/events/sync")
    suspend fun delete(@Path("calendar") calendar: String, @Body body: RequestBody,
        @Tag attempt: ProtonDeleteDispatchAttempt): retrofit2.Response<ResponseBody>
    @Streaming @GET("calendar/v1/{calendar}/events/{event}")
    suspend fun event(@Path("calendar") calendar: String, @Path("event") id: String,
        @Tag attempt: ProtonDeleteReadAttempt): retrofit2.Response<ResponseBody>
}

internal class ProtonDeleteSubmissionRemote(private val provider: ApiProvider) : DeleteSubmissionRemote {
    override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject, allowsTransport: () -> Boolean): String {
        val url = endpoint(calendar, "sync")
        val attempt = ProtonDeleteDispatchAttempt(url, request.toString(), allowsTransport)
        return provider.get<ProtonDeleteSubmissionApi>(sessionId = session.sessionId).invoke(forceNoRetryOnConnectionErrors = true) {
            val response = delete(calendar, attempt.requestBody, attempt)
            (response.body() ?: response.errorBody())?.use(CalendarCatalogBodyReader::read) ?: ""
        }.valueOrThrow
    }
    override suspend fun event(session: CatalogSession, calendar: String, id: String): ProtonDeleteEventRead {
        currentCoroutineContext().ensureActive()
        val attempt = ProtonDeleteReadAttempt(endpoint(calendar, id), session.sessionId.id)
        return try {
            val transportFailure = try {
                provider.get<ProtonDeleteSubmissionApi>(sessionId = session.sessionId).invoke(forceNoRetryOnConnectionErrors = true) {
                    val response = event(calendar, id, attempt)
                    response.body()?.close(); response.errorBody()?.close()
                }.valueOrThrow
                null
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) {
                  currentCoroutineContext().ensureActive()
                  val reason = (failure as? ApiException)?.error
                  when {
                      failure is SocketTimeoutException || failure is UnknownHostException -> failure
                      reason is ApiResult.Error.NoInternet || reason is ApiResult.Error.Timeout ->
                          IOException("Delete event read unavailable", failure)
                      else -> null
                  }
              }
            currentCoroutineContext().ensureActive()
            val captured = attempt.finish()
            if (captured == null) {
                if (transportFailure != null) throw transportFailure
                return ProtonDeleteEventRead.Unknown
            }
            currentCoroutineContext().ensureActive()
            // Core exception codes/bodies are deliberately ignored. Only this GET's raw captured
            // route, authenticated session and duplicate-key-preserving body may establish evidence.
            ProtonDeleteEventResponseParser.parse(captured.url, calendar, id, captured.httpCode, captured.body)
        } finally { attempt.close() }
    }

    private fun endpoint(calendar: String, id: String): HttpUrl = "https://calendar-api.proton.me/".toHttpUrl().newBuilder()
        .addPathSegment("calendar").addPathSegment("v1").addPathSegment(calendar).addPathSegment("events").addPathSegment(id).build()
}
