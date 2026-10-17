// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

/** DELETE needs its own one-exchange authority, including when Core rebuilds a request. */
class ProtonDeleteDispatchTest {
    private val payload = """{"MemberID":"synthetic-member","Events":[{"ID":"synthetic-event","DeletionReason":0}]}"""

    @Test fun qualifiedDeleteMakesOneExchangeAndCannotBeInvokedAgain() = server { server ->
        server.enqueue(MockResponse().setBody("{}"))
        val permit = ProtonDeleteDispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), payload)
        val client = OkHttpClient.Builder().withSingleDispatchGuards().build()
        client.newCall(permit.request()).execute().use { assertEquals(200, it.code) }
        denied { client.newCall(permit.request()).execute().close() }
        assertEquals(1, server.requestCount)
        assertFalse(permit.toString().contains("synthetic"))
    }

    @Test fun unavailableServerRedirectAndLostReplyNeverStartASecondExchange() = server { server ->
        val client = OkHttpClient.Builder().withSingleDispatchGuards().callTimeout(500, TimeUnit.MILLISECONDS).build()
        for (response in listOf(
            MockResponse().setResponseCode(503).addHeader("Retry-After", "0"),
            MockResponse().setResponseCode(307).addHeader("Location", server.url("/elsewhere")),
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE),
        )) {
            server.enqueue(response)
            val before = server.requestCount
            val permit = ProtonDeleteDispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), payload)
            try { client.newCall(permit.request()).execute().close() } catch (_: java.io.IOException) { }
            assertEquals(before + 1, server.requestCount)
            denied { client.newCall(permit.request()).execute().close() }
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test fun foreignBindingsAndMixedFamilyTagsAreRejectedBeforeNetwork() = server { server ->
        val client = OkHttpClient.Builder().withSingleDispatchGuards().build()
        val url = server.url("/calendar/v1/synthetic/events/sync")
        val permit = ProtonDeleteDispatchAttempt(url, payload)
        val create = ProtonSingleDispatchAttempt(url, singleCreateFixture)
        val update = ProtonTitleUpdateDispatchAttempt(url, TitleSubmissionFixture().let { it.codec.open(it.record).request.toString() })
        for (request in listOf(
            permit.request().newBuilder().url(server.url("/calendar/v1/foreign/events/sync")).build(),
            permit.request().newBuilder().get().build(),
            permit.request().newBuilder().put(payload.toRequestBody()).build(),
            permit.request().newBuilder().tag(ProtonSingleDispatchAttempt::class.java, create).build(),
            permit.request().newBuilder().tag(ProtonTitleUpdateDispatchAttempt::class.java, update).build(),
            create.request().newBuilder().tag(ProtonDeleteDispatchAttempt::class.java, permit).build(),
            update.request().newBuilder().tag(ProtonDeleteDispatchAttempt::class.java, permit).build(),
        )) denied { client.newCall(request).execute().close() }
        assertEquals(0, server.requestCount)
    }

    @Test fun networkGuardStopsInterceptorReentryForDelete() = server { server ->
        server.enqueue(MockResponse().setBody("{}"))
        server.enqueue(MockResponse().setBody("must not be reached"))
        val client = OkHttpClient.Builder().withSingleDispatchGuards().addInterceptor { chain ->
            chain.proceed(chain.request()).close()
            chain.proceed(chain.request())
        }.build()
        val permit = ProtonDeleteDispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), payload)
        denied { client.newCall(permit.request()).execute().close() }
        assertEquals(1, server.requestCount)
    }

    private fun denied(action: () -> Unit) {
        try { action(); fail("Unqualified deletion accepted") } catch (_: ProtonSingleDispatchDenied) { }
    }

    private fun server(action: (MockWebServer) -> Unit) {
        val server = MockWebServer(); server.start()
        try { action(server) } finally { server.shutdown() }
    }
}
