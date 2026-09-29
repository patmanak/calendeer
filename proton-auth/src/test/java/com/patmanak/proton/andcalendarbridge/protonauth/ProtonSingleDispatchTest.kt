// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test

class ProtonSingleDispatchTest {
    @Test fun noConnectionRetryFlagAloneStillRepeats503() = server { server ->
        server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setBody("synthetic response"))
        val unsafe = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).build()
        val request = Request.Builder().url(server.url("/calendar/v1/synthetic/events/sync"))
            .put("{}".toRequestBody("application/json".toMediaType())).build()
        unsafe.newCall(request).execute().use { assertEquals(200, it.code) }
        assertEquals(2, server.requestCount)
    }

    @Test fun create503IsNeverRepeated() = server { server ->
        server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setBody("must not be reached"))
        val client = OkHttpClient.Builder().withSingleDispatchGuards().build()
        val request = ProtonSingleDispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), "{}").request()
        client.newCall(request).execute().use { assertEquals(503, it.code) }
        assertEquals("One admitted attempt must make at most one request", 1, server.requestCount)
    }

    @Test fun repeatInvocationOfTheSameAttemptStopsBeforeTheSecondRequest() = server { server ->
        server.enqueue(MockResponse().setResponseCode(401)); server.enqueue(MockResponse().setBody("must not be reached"))
        val client = guarded()
        val attempt = ProtonSingleDispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), "{}")
        client.newCall(attempt.request()).execute().use { assertEquals(401, it.code) }
        denied { client.newCall(attempt.request()).execute().close() }
        assertEquals(1, server.requestCount)
        assertFalse(attempt.toString().contains("synthetic"))
    }

    @Test fun unmarkedWritesAndReboundAttemptsFailClosedButReadsContinue() = server { server ->
        val client = guarded()
        val url = server.url("/calendar/v1/synthetic/events/sync")
        val attempt = ProtonSingleDispatchAttempt(url, "{}")
        val unmarked = Request.Builder().url(url).put("{}".toRequestBody()).build()
        for (request in listOf(unmarked, attempt.request().newBuilder().url(server.url("/foreign")).build(),
            attempt.request().newBuilder().get().build(), attempt.request().newBuilder().put("{}".toRequestBody()).build())) {
            denied { client.newCall(request).execute().close() }
        }
        assertEquals(0, server.requestCount)
        server.enqueue(MockResponse().setBody("synthetic"))
        client.newCall(Request.Builder().url(server.url("/calendar/v1/synthetic")).build()).execute().close()
        assertEquals(1, server.requestCount)
    }

    @Test fun redirectsLostResponsesAndTimeoutsNeverStartAnotherRequest() = server { server ->
        val client = guarded().newBuilder().callTimeout(300, TimeUnit.MILLISECONDS).build()
        for (response in listOf(MockResponse().setResponseCode(307).addHeader("Location", server.url("/elsewhere")),
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))) {
            server.enqueue(response)
            val before = server.requestCount
            val attempt = ProtonSingleDispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), "{}")
            try { client.newCall(attempt.request()).execute().close() } catch (_: java.io.IOException) { }
            assertEquals(before + 1, server.requestCount)
            denied { client.newCall(attempt.request()).execute().close() }
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test fun networkGuardStopsAnInterceptorProceedingTwice() = server { server ->
        server.enqueue(MockResponse().setBody("synthetic")); server.enqueue(MockResponse().setBody("must not be reached"))
        val client = guarded().newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request()).close()
            chain.proceed(chain.request())
        }.build()
        val attempt = ProtonSingleDispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), "{}")
        denied { client.newCall(attempt.request()).execute().close() }
        assertEquals(1, server.requestCount)
    }

    @Test fun cancellationAfterReceiptCannotReuseTheAttempt() = server { server ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = guarded()
        val attempt = ProtonSingleDispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), "{}")
        val call = client.newCall(attempt.request())
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val result = pool.submit<Boolean> { try { call.execute().close(); false } catch (_: java.io.IOException) { true } }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS)); call.cancel()
            assertTrue(result.get(3, TimeUnit.SECONDS))
            denied { client.newCall(attempt.request()).execute().close() }
            assertEquals(1, server.requestCount)
        } finally { call.cancel(); pool.shutdownNow(); pool.awaitTermination(3, TimeUnit.SECONDS) }
    }

    @Test fun concurrentCallsShareOnePermit() = server { server ->
        server.enqueue(MockResponse().setBody("synthetic")); server.enqueue(MockResponse().setBody("must not be reached"))
        val client = guarded()
        val attempt = ProtonSingleDispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), "{}")
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val calls = (1..2).map {
                pool.submit<Boolean> {
                    start.await()
                    try { client.newCall(attempt.request()).execute().close(); true }
                    catch (_: ProtonSingleDispatchDenied) { false }
                }
            }
            start.countDown()
            assertEquals(1, calls.map { it.get(3, TimeUnit.SECONDS) }.count { it })
            assertEquals(1, server.requestCount)
        } finally { pool.shutdownNow(); pool.awaitTermination(3, TimeUnit.SECONDS) }
    }

    private fun guarded() = OkHttpClient.Builder().withSingleDispatchGuards().build()
    private fun denied(action: () -> Unit) {
        try { action(); fail("Unsafe dispatch accepted") }
        catch (failure: ProtonSingleDispatchDenied) { assertEquals("CREATE_SINGLE_DISPATCH_DENIED", failure.message); assertNull(failure.cause) }
    }

    private fun server(action: (MockWebServer) -> Unit) {
        val server = MockWebServer(); server.start()
        try { action(server) } finally { server.shutdown() }
    }
}
