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

/** Published Core/Retrofit DELETE transport, synthetic loopback sessions only. */
class ProtonDeleteSubmissionCoreTest {
    internal interface FixtureApi : BaseRetrofitApi {
        @Streaming @PUT("calendar/v1/synthetic/events/sync")
        suspend fun delete(@Body body: RequestBody, @Tag attempt: ProtonDeleteDispatchAttempt): retrofit2.Response<ResponseBody>
        @Streaming @GET("calendar/v1/synthetic/events/synthetic-event")
        suspend fun event(@Tag attempt: ProtonDeleteReadAttempt): retrofit2.Response<ResponseBody>
    }
    @Test fun firstHttpErrorBodyRemainsAvailableWithoutTokenRefreshOrSecondPut() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"Code\":401}"))
            server.enqueue(MockResponse().setBody("must not reach"))
            val sessions = SyntheticSessions()
            val payload = """{"MemberID":"synthetic-member","Events":[{"ID":"synthetic-event","DeletionReason":0}]}"""
            val attempt = ProtonDeleteDispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), payload)
            val result = manager(server, sessions).invoke(forceNoRetryOnConnectionErrors = true) { api ->
                val response = api.delete(attempt.requestBody, attempt)
                (response.body() ?: response.errorBody())!!.use { it.string() }
            }
            assertEquals("{\"Code\":401}", result.valueOrThrow)
            assertEquals(1, server.requestCount); assertEquals(0, sessions.refreshes)
            assertEquals("PUT", server.takeRequest().method)
        }
    }
    @Test fun successfulCoreDeleteKeepsPreparedBytesAndAuthenticatedHeaders() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse().setBody("synthetic-response"))
            val payload = """{"MemberID":"synthetic-member","Events":[{"ID":"synthetic-event","DeletionReason":0}]}"""
            val attempt = ProtonDeleteDispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), payload)
            val result = manager(server, SyntheticSessions()).invoke(forceNoRetryOnConnectionErrors = true) { api ->
                api.delete(attempt.requestBody, attempt).body()!!.use { it.string() }
            }
            assertEquals("synthetic-response", result.valueOrThrow); assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals(payload, request.body.readUtf8())
            assertEquals("Bearer synthetic-access", request.getHeader("Authorization"))
            assertEquals("synthetic-session", request.getHeader("x-pm-uid"))
        }
    }
    @Test fun exactNotFoundIsCapturedBeforeCoreConvertsItToAnError() = runBlocking {
        for (compressed in listOf(false, true)) withServer { server ->
            val body = """{"Code":2501,"Error":"Synthetic event does not exist"}"""
            val response = MockResponse().setResponseCode(404)
            if (compressed) {
                val bytes = java.io.ByteArrayOutputStream().run {
                    java.util.zip.GZIPOutputStream(this).use { it.write(body.toByteArray(Charsets.UTF_8)) }; toByteArray()
                }
                response.setHeader("Content-Encoding", "gzip").setBody(okio.Buffer().write(bytes))
            } else response.setBody(body)
            server.enqueue(response)
            val sessions = SyntheticSessions()
            val url = server.url("/calendar/v1/synthetic/events/synthetic-event")
            val probe = ProtonDeleteReadAttempt(url, sessions.id.id)
            try {
                val result = manager(server, sessions).invoke(forceNoRetryOnConnectionErrors = true) { api -> api.event(probe) }
                // Core's ServerErrorInterceptor deliberately converts this structured response.
                // The exact raw witness must survive it; its parsed exception is not absence proof.
                assertTrue(result is ApiResult.Error.Http)
                val captured = requireNotNull(probe.finish())
                assertEquals(body, captured.body); assertEquals(404, captured.httpCode); assertEquals(url, captured.url)
                assertNull(probe.finish())
                assertEquals(1, server.requestCount); assertEquals(0, sessions.refreshes)
                val sent = server.takeRequest()
                assertEquals("GET", sent.method)
                assertEquals("Bearer synthetic-access", sent.getHeader("Authorization"))
                assertEquals("synthetic-session", sent.getHeader("x-pm-uid"))
            } finally { probe.close() }
        }
    }

    @Test fun aForeignSessionCannotDonateAnExactResponseWitness() = runBlocking {
        withServer { server ->
            val sessions = SyntheticSessions()
            val probe = ProtonDeleteReadAttempt(server.url("/calendar/v1/synthetic/events/synthetic-event"), "foreign-session")
            try {
                manager(server, sessions).invoke(forceNoRetryOnConnectionErrors = true) { api -> api.event(probe) }
                assertNull(probe.finish()); assertEquals(0, server.requestCount); assertEquals(0, sessions.refreshes)
            } finally { probe.close() }
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
