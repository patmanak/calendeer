// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.serialization.json.*
import me.proton.core.network.data.ApiProvider
import me.proton.core.network.data.protonApi.BaseRetrofitApi
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.*

/** Typed series transport; an ordinary personal-v2 sender cannot admit series requests. */
internal interface SeriesUpdateSubmissionRemote : TitleUpdateSubmissionRemote {
    suspend fun sendSeries(session: CatalogSession, calendar: String, request: JsonObject): String
}

internal interface ProtonPersonalUpdateSubmissionApi : BaseRetrofitApi {
    @Streaming @PUT("calendar/v1/{calendar}/events/sync")
    suspend fun update(@Path("calendar") calendar: String, @Body body: RequestBody, @Tag attempt: ProtonPersonalEditV2DispatchAttempt): retrofit2.Response<ResponseBody>
    @Streaming @PUT("calendar/v1/{calendar}/events/sync")
    suspend fun updateSeries(@Path("calendar") calendar: String, @Body body: RequestBody, @Tag attempt: ProtonSeriesEditV3DispatchAttempt): retrofit2.Response<ResponseBody>
    @Streaming @GET("calendar/v1/{calendar}/events/{event}")
    suspend fun event(@Path("calendar") calendar: String, @Path("event") event: String): ResponseBody
}
internal class ProtonPersonalUpdateSubmissionRemote(private val provider: ApiProvider) : SeriesUpdateSubmissionRemote {
    override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject): String {
        val url = "https://calendar-api.proton.me/".toHttpUrl().newBuilder().addPathSegment("calendar").addPathSegment("v1")
            .addPathSegment(calendar).addPathSegment("events").addPathSegment("sync").build()
        val attempt = ProtonPersonalEditV2DispatchAttempt(url, request.toString())
        return provider.get<ProtonPersonalUpdateSubmissionApi>(sessionId = session.sessionId).invoke(forceNoRetryOnConnectionErrors = true) {
            val response = update(calendar, attempt.requestBody, attempt)
            (response.body() ?: response.errorBody())?.use(CalendarCatalogBodyReader::read) ?: ""
        }.valueOrThrow
    }
    override suspend fun sendSeries(session: CatalogSession, calendar: String, request: JsonObject): String {
        val url = "https://calendar-api.proton.me/".toHttpUrl().newBuilder().addPathSegment("calendar").addPathSegment("v1")
            .addPathSegment(calendar).addPathSegment("events").addPathSegment("sync").build()
        val attempt = ProtonSeriesEditV3DispatchAttempt(url, request.toString())
        return provider.get<ProtonPersonalUpdateSubmissionApi>(sessionId = session.sessionId).invoke(forceNoRetryOnConnectionErrors = true) {
            val response = updateSeries(calendar, attempt.requestBody, attempt)
            (response.body() ?: response.errorBody())?.use(CalendarCatalogBodyReader::read) ?: ""
        }.valueOrThrow
    }
    override suspend fun event(session: CatalogSession, calendar: String, id: String): JsonObject =
        provider.get<ProtonPersonalUpdateSubmissionApi>(sessionId = session.sessionId).invoke(forceNoRetryOnConnectionErrors = true) {
            event(calendar, id).use { createdEventResponse(it).obj("Event") }
        }.valueOrThrow
}
