// SPDX-License-Identifier: GPL-3.0-or-later
@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER") // Pinned Core backend, test-only.
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.coroutines.runBlocking
import me.proton.core.domain.entity.UserId
import me.proton.core.network.data.ProtonApiBackend
import me.proton.core.network.data.protonApi.BaseRetrofitApi
import me.proton.core.network.domain.*
import me.proton.core.network.domain.client.*
import me.proton.core.network.domain.deviceverification.*
import me.proton.core.network.domain.handlers.TokenErrorHandler
import me.proton.core.network.domain.humanverification.*
import me.proton.core.network.domain.server.ServerTimeListener
import me.proton.core.network.domain.session.*
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.http.*

/** Real published Core/Retrofit retry path with synthetic sessions and loopback HTTP only. */
class ProtonCoreSingleDispatchTest {
    internal interface FixtureApi : BaseRetrofitApi {
        @Streaming @GET("calendar/v1/{calendar}/events/{event}")
        suspend fun observe(@Path("calendar") calendar: String, @Path("event") event: String): retrofit2.Response<ResponseBody>
        @PUT("calendar/v1/synthetic/events/sync")
        suspend fun create(@Body body: RequestBody, @Tag attempt: ProtonSingleDispatchAttempt): ResponseBody
        @retrofit2.http.Headers("Content-Type: application/json") @PUT("calendar/v1/synthetic/events/sync")
        suspend fun wrapped(@Body body: RequestBody, @Tag attempt: ProtonSingleDispatchAttempt): ResponseBody
    }

    @Test fun coreExposesSanitizedGetMetadataWithoutConditionalHeaders() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse().setBody("""{"Code":1000,"Event":{"ID":"synthetic-event","CalendarID":"synthetic-calendar","ModifyTime":2}}""")
                .addHeader("ETag", "synthetic-tag"))
            val result = manager(server, SyntheticSessions()).invoke(forceNoRetryOnConnectionErrors = true) { api ->
                readEventVersion(api.observe("synthetic-calendar", "synthetic-event"),
                    ProtonFeedScope("synthetic-user", 0, "synthetic-calendar"), "synthetic-event")
            }
            assertEquals(EventHeaderShape.SINGLE, result.valueOrThrow.etag)
            assertEquals(EventNumberShape.NON_NEGATIVE_INTEGER, result.valueOrThrow.modifyTime)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("Bearer synthetic-access", request.getHeader("Authorization"))
            assertEquals("synthetic-session", request.getHeader("x-pm-uid"))
            for (name in listOf("If-Match", "If-None-Match", "If-Unmodified-Since", "If-Modified-Since", "Range")) assertNull(request.getHeader(name))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun tokenRefreshReentersCoreButMakesOnlyOnePut() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"Code\":401}"))
            server.enqueue(MockResponse().setBody("must not be reached"))
            val sessions = SyntheticSessions()
            val manager = manager(server, sessions)
            val attempt = ProtonSingleDispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), singleCreateFixture)
            var invocations = 0
            val result = manager.invoke(forceNoRetryOnConnectionErrors = true) { api ->
                invocations++; api.create(attempt.requestBody, attempt).use { it.string() }
            }
            assertTrue("Second invocation must remain a failure", result is ApiResult.Error)
            val cause = (result as? ApiResult.Error)?.cause
            assertEquals("Core error category=${result.javaClass.simpleName}, requests=${server.requestCount}, refreshes=${sessions.refreshes}, cause=${cause?.javaClass?.simpleName}, origin=${cause?.stackTrace?.firstOrNull()}", 2, invocations)
            assertEquals(1, sessions.refreshes); assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("Bearer synthetic-access", request.getHeader("Authorization"))
            assertEquals("synthetic-session", request.getHeader("x-pm-uid"))
        }
    }

    @Test fun retrofitContentTypeWrapperIsDeniedBeforeExchange() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse().setBody("must not be reached"))
            val attempt = ProtonSingleDispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), singleCreateFixture)
            val result = manager(server, SyntheticSessions()).invoke(forceNoRetryOnConnectionErrors = true) { api ->
                api.wrapped(attempt.requestBody, attempt).use { it.string() }
            }
            assertTrue(result is ApiResult.Error); assertEquals(0, server.requestCount)
        }
    }

    @Test fun successfulCoreRequestPreservesPayloadAndClientHeaders() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse().setBody("synthetic-response"))
            val attempt = ProtonSingleDispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), singleCreateFixture)
            val result = manager(server, SyntheticSessions()).invoke(forceNoRetryOnConnectionErrors = true) { api ->
                api.create(attempt.requestBody, attempt).use { it.string() }
            }
            assertEquals("synthetic-response", result.valueOrThrow)
            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals(singleCreateFixture, request.body.readUtf8())
            assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
            assertEquals(CalendarBridgeApiClient.forReleaseVersion("0.1.0").appVersionHeader, request.getHeader("x-pm-appversion"))
        }
    }

    private fun manager(server: MockWebServer, sessions: SyntheticSessions): ApiManagerImpl<FixtureApi> {
        val client = CalendarBridgeApiClient.forReleaseVersion("0.1.0")
        val backend = ProtonApiBackend(
            baseUrl = server.url("/").toString(), client = client,
            clientIdProvider = object : ClientIdProvider {
                override suspend fun getClientId(sessionId: SessionId?) = ClientId.AccountSession(sessions.id)
            }, serverTimeListener = object : ServerTimeListener {
                override fun onServerTimeMillisUpdated(epochMillis: Long) = Unit
            }, sessionId = sessions.id, sessionProvider = sessions,
            humanVerificationProvider = object : HumanVerificationProvider {
                override suspend fun getHumanVerificationDetails(clientId: ClientId): HumanVerificationDetails? = null
            }, deviceVerificationProvider = object : DeviceVerificationProvider {
                override suspend fun getSolvedChallenge(sessionId: SessionId?): String? = null
                override suspend fun getSolvedChallenge(challengePayload: String): String? = null
                override suspend fun setSolvedChallenge(sessionId: SessionId, challengePayload: String, solved: String) = Unit
            }, baseOkHttpClient = OkHttpClient.Builder().withSingleDispatchGuards().build(),
            converters = emptyList(), interfaceClass = FixtureApi::class,
            networkManager = object : NetworkManager() {
                override val networkStatus = NetworkStatus.Unmetered
                override fun register() = Unit
                override fun unregister() = Unit
            }, securityStrategy = {}, networkPrefs = object : NetworkPrefs {
                override var activeAltBaseUrl: String? = null
                override var lastPrimaryApiFail: Long = 0
                override var alternativeBaseUrls: List<String>? = null
                override var successfulSecondaryDohServiceUrl: String? = null
            }, cookieStore = null, extraHeaderProvider = null, interceptors = emptySet(), isPrimary = true)
        return ApiManagerImpl(client, backend, listOf(TokenErrorHandler<FixtureApi>(sessions.id, sessions, sessions)),
            monoClockMs = { System.nanoTime() / 1_000_000 })
    }

    private class SyntheticSessions : SessionProvider, SessionListener {
        val id = SessionId("synthetic-session")
        private var session: Session = Session.Authenticated(UserId("synthetic-user"), id, "synthetic-access", "synthetic-refresh", listOf("full"))
        var refreshes = 0
        override suspend fun getSession(sessionId: SessionId?) = session.takeIf { sessionId == id }
        override suspend fun getSessions() = listOf(session)
        override suspend fun getSessionId(userId: UserId?) = id.takeIf { userId == UserId("synthetic-user") }
        override suspend fun getUserId(sessionId: SessionId) = UserId("synthetic-user").takeIf { sessionId == id }
        override suspend fun <T> withLock(sessionId: SessionId?, action: suspend () -> T): T = action()
        override suspend fun requestSession() = false
        override suspend fun refreshSession(session: Session): Boolean { refreshes++; return true }
        override suspend fun onSessionTokenCreated(userId: UserId?, session: Session) = Unit
        override suspend fun onSessionTokenRefreshed(session: Session) = Unit
        override suspend fun onSessionScopesRefreshed(sessionId: SessionId, scopes: List<String>) = Unit
        override suspend fun onSessionForceLogout(session: Session, httpCode: Int) = Unit
    }
    private suspend fun withServer(action: suspend (MockWebServer) -> Unit) {
        val server = MockWebServer(); server.start()
        try { action(server) } finally { server.shutdown() }
    }
}
