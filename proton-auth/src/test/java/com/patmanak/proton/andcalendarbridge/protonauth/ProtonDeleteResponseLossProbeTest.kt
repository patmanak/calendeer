// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import java.io.IOException
import java.security.MessageDigest
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test

class ProtonDeleteResponseLossProbeTest {
    private val url = "https://calendar-api.proton.me/calendar/v1/synthetic-calendar/events/sync".toHttpUrl()
    private val payload = """{"MemberID":"synthetic-member","Events":[{"ID":"synthetic-event","DeletionReason":0}]}"""
    private val client = OkHttpClient()
    private fun session(url: HttpUrl = this.url) = DeleteResponseLossSession(url, "synthetic-session",
        MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()))
    private fun request(wire: String = payload, url: HttpUrl = this.url) =
        ProtonDeleteDispatchAttempt(url, wire).request().newBuilder()
            .header("x-pm-uid", "synthetic-session").header("Authorization", "Bearer synthetic-fixture").build()
    private fun response(request: Request, body: ResponseBody, code: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic").body(body).build()
    private class TrackedBody : ResponseBody() {
        var reads = 0
        var closes = 0
        private val stream = object : ForwardingSource(Buffer().writeUtf8("synthetic-unconsumed-response")) {
            override fun read(sink: Buffer, byteCount: Long): Long { reads++; return super.read(sink, byteCount) }
            override fun close() { closes++; super.close() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = -1L
        override fun source(): BufferedSource = stream
    }
    private fun lost(block: () -> Unit) {
        try { block(); fail("Expected explicit injected response loss") }
        catch (failure: ProtonDeleteResponseLost) { assertEquals("DELETE_RESPONSE_LOSS_INJECTED", failure.message) }
    }

    @Test fun genuineResponseIsClosedWithoutReadingAndObservationNeverClaimsAcceptance() {
        for (code in listOf(200, 400, 500)) {
            val session = session(); val request = request(); val call = client.newCall(request)
            val body = TrackedBody(); var exchanges = 0
            lost { session.intercept(call, request) { exchanges++; response(it, body, code) } }
            assertEquals(1, exchanges); assertEquals(0, body.reads); assertEquals(1, body.closes)
            assertEquals(DeleteResponseLossObservation(1, true, true), session.observation())
            assertTrue(session.observation().measuredLoss)
            assertFalse(session.observation().toString().contains("synthetic"))
        }
    }

    @Test fun actualNetworkResponseIsLostAfterOneRealBodyWriteWithoutRetry() {
        MockWebServer().use { server ->
            server.start(); server.enqueue(MockResponse().setBody("synthetic-reply"))
            val url = server.url("/calendar/v1/synthetic-calendar/events/sync")
            val session = session(url)
            val guarded = OkHttpClient.Builder().withSingleDispatchGuards()
                .addNetworkInterceptor { chain -> session.intercept(chain.call(), chain.request(), chain::proceed) }.build()
            val request = request(url = url)
            lost { guarded.newCall(request).execute().close() }
            assertEquals(1, server.requestCount)
            assertEquals(payload, server.takeRequest().body.readUtf8())
            assertTrue(session.observation().measuredLoss)
            try { guarded.newCall(request).execute().close(); fail("Consumed permit must remain closed") }
            catch (_: ProtonSingleDispatchDenied) { }
            assertEquals(1, server.requestCount)
            session.close()
        }
    }

    @Test fun foreignRouteEventMemberBodyTagAndSessionNeverDonateLoss() {
        val original = request()
        val invalid = listOf(
            request(payload.replace("synthetic-event", "foreign-event")),
            request(payload.replace("synthetic-member", "foreign-member")),
            original.newBuilder().url(url.newBuilder().addQueryParameter("foreign", "1").build()).build(),
            original.newBuilder().get().build(),
            original.newBuilder().put(payload.toRequestBody()).build(),
            original.newBuilder().tag(ProtonDeleteDispatchAttempt::class.java, null).build(),
            original.newBuilder().header("x-pm-uid", "foreign-session").build(),
            original.newBuilder().addHeader("x-pm-uid", "synthetic-session").build(),
            original.newBuilder().removeHeader("Authorization").build(),
            original.newBuilder().addHeader("Authorization", "Bearer synthetic-fixture").build(),
        )
        for (request in invalid) {
            val session = session(); val body = TrackedBody(); val expected = response(request, body)
            assertSame(expected, session.intercept(client.newCall(request), request) { expected })
            assertFalse(session.observation().measuredLoss); assertEquals(0, body.closes)
            expected.close()
        }
    }

    @Test fun originalCallBodyAndTagCannotBeReboundByNetworkInterceptors() {
        for (foreignCall in listOf(client.newCall(request()), client.newCall(Request.Builder().url(url).build()))) {
            val session = session(); val request = request(); val body = TrackedBody()
            val expected = response(request, body)
            assertSame(expected, session.intercept(foreignCall, request) { expected })
            assertFalse(session.observation().measuredLoss); expected.close()
        }
    }

    @Test fun foreignResponseOrCancellationCannotDonateLoss() {
        for (scenario in 0..4) {
            val session = session(); val request = request(); val call = client.newCall(request); val body = TrackedBody()
            val foreign = when (scenario) {
                0 -> request().newBuilder().headers(request.headers).build() // Distinct same-wire tag/body.
                1 -> request.newBuilder().header("x-pm-uid", "foreign-session").build()
                2 -> request.newBuilder().url(url.newBuilder().addPathSegment("foreign").build()).build()
                else -> request
            }
            val expected = response(foreign, body)
            val actual = session.intercept(call, request) {
                if (scenario == 3) call.cancel()
                if (scenario == 4) session.close()
                expected
            }
            assertSame(expected, actual); assertFalse(session.observation().measuredLoss)
            assertEquals(0, body.closes); expected.close()
        }
    }

    @Test fun repeatedEntryInvalidatesMeasurementAndCannotDropTwice() {
        val session = session(); val request = request(); val call = client.newCall(request)
        val first = response(request, TrackedBody()); val nested = response(request, TrackedBody())
        assertSame(first, session.intercept(call, request) {
            assertSame(nested, session.intercept(call, request) { nested })
            first
        })
        assertEquals(2, session.observation().calls); assertFalse(session.observation().measuredLoss)
        first.close(); nested.close()
        val once = session(); lost { once.intercept(call, request) { response(it, TrackedBody()) } }
        val later = response(request, TrackedBody())
        assertSame(later, once.intercept(call, request) { later })
        assertEquals(2, once.observation().calls); assertFalse(once.observation().measuredLoss); later.close()
    }

    @Test fun closedPrecancelledAndNaturallyFailedCallsNeverInjectAResponse() {
        val closed = session(); closed.close(); val request = request(); val call = client.newCall(request)
        val body = TrackedBody(); val expected = response(request, body)
        assertSame(expected, closed.intercept(call, request) { expected }); assertEquals(0, closed.observation().calls)
        expected.close()
        val cancelled = session(); call.cancel(); val cancelledReply = response(request, TrackedBody())
        assertSame(cancelledReply, cancelled.intercept(call, request) { cancelledReply })
        assertFalse(cancelled.observation().measuredLoss); cancelledReply.close()
        val failure = IOException("synthetic-natural-failure"); val failed = session()
        try { failed.intercept(client.newCall(request), request) { throw failure }; fail("Expected original error") }
        catch (actual: IOException) { assertSame(failure, actual) }
        assertFalse(failed.observation().measuredLoss)
    }
}
