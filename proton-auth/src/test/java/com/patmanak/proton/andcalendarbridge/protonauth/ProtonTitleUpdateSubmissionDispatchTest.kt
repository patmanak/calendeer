// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProtonTitleUpdateSubmissionDispatchTest {
    private fun payload() = TitleSubmissionFixture().let { it.codec.open(it.record).request.toString() }
    @Test fun updatePermitRefusesCreateDeleteBulkUnknownAndAuthorKeyPackets() {
        val server = MockWebServer()
        try {
            server.start(); val url = server.url("/calendar/v1/synthetic/events/sync")
            denied { ProtonTitleUpdateDispatchAttempt(url, singleCreateFixture) }
            denied { ProtonSingleDispatchAttempt(url, payload()) }
            val root = Json.parseToJsonElement(payload()) as JsonObject
            val operation = root.array("Events").single() as JsonObject
            val event = operation.obj("Event")
            for ((field, value) in listOf("SharedKeyPacket" to JsonPrimitive("packet"), "Author" to JsonPrimitive("author"), "Unknown" to JsonNull)) {
                val changed = JsonObject(root + ("Events" to JsonArray(listOf(JsonObject(operation + ("Event" to JsonObject(event + (field to value))))))))
                denied { ProtonTitleUpdateDispatchAttempt(url, changed.toString()) }
            }
            denied { ProtonTitleUpdateDispatchAttempt(url, JsonObject(root + ("Events" to JsonArray(listOf(operation, operation)))).toString()) }
            denied { ProtonTitleUpdateDispatchAttempt(url, payload().replace("\"IsImport\":0", "\"IsImport\":0,\"IsImport\":0")) }
        } finally { server.shutdown() }
    }
    @Test fun tagConfusionAndRebindingNeverReachNetwork() = server { server ->
        val url = server.url("/calendar/v1/synthetic/events/sync")
        val update = ProtonTitleUpdateDispatchAttempt(url, payload())
        val create = ProtonSingleDispatchAttempt(url, singleCreateFixture)
        val client = OkHttpClient.Builder().withSingleDispatchGuards().build()
        val requests = listOf(update.request().newBuilder().tag(ProtonSingleDispatchAttempt::class.java, create).build(),
            create.request().newBuilder().tag(ProtonTitleUpdateDispatchAttempt::class.java, update).build(),
            update.request().newBuilder().url(server.url("/other")).build(),
            update.request().newBuilder().put("{}".toRequestBody()).build(), update.request().newBuilder().get().build(),
            update.request().newBuilder().tag(ProtonTitleUpdateDispatchAttempt::class.java, null).build())
        requests.forEach { denied { client.newCall(it).execute().close() } }
        assertEquals(0, server.requestCount)
    }
    @Test fun updateResponseRetryRedirectAndLostResponseNeverRepeatExchange() = server { server ->
        val client = OkHttpClient.Builder().withSingleDispatchGuards().callTimeout(500, TimeUnit.MILLISECONDS).build()
        for (response in listOf(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"),
            MockResponse().setResponseCode(307).addHeader("Location", server.url("/redirect")),
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))) {
            server.enqueue(response)
            val before = server.requestCount
            val attempt = ProtonTitleUpdateDispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), payload())
            try { client.newCall(attempt.request()).execute().close() } catch (_: java.io.IOException) { }
            assertEquals(before + 1, server.requestCount)
            denied { client.newCall(attempt.request()).execute().close() }
            assertEquals(before + 1, server.requestCount)
        }
    }
    @Test fun interceptorSecondProceedCannotReenterUpdateExchange() = server { server ->
        server.enqueue(MockResponse().setBody("synthetic")); server.enqueue(MockResponse().setBody("must not reach"))
        val client = OkHttpClient.Builder().withSingleDispatchGuards().addInterceptor { chain ->
            chain.proceed(chain.request()).close(); chain.proceed(chain.request())
        }.build()
        denied { client.newCall(ProtonTitleUpdateDispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), payload()).request()).execute().close() }
        assertEquals(1, server.requestCount)
    }
    private fun denied(action: () -> Unit) { try { action(); fail("Unsafe permit accepted") } catch (_: ProtonSingleDispatchDenied) { } }
    private fun server(action: (MockWebServer) -> Unit) {
        val server = MockWebServer(); try { server.start(); action(server) } finally { server.shutdown() }
    }
}
