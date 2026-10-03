// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.SessionId
import okhttp3.*
import okhttp3.mockwebserver.*
import okio.*
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit

class ProtonEventVersionProbeTest {
    private val scope = ProtonFeedScope("synthetic-account", 0, "synthetic-calendar")
    private val session = CatalogSession(UserId(scope.account), SessionId("synthetic-session"))
    private val payload = """{"Code":1000,"Event":{"ID":"synthetic-event","CalendarID":"synthetic-calendar","ModifyTime":2,"CreateTime":1}}"""

    @Test fun descriptiveProfileRecognizesHeadersAndIntegerFields() {
        val profile = eventVersionProfile(Headers.Builder().add("ETag", "synthetic-tag")
            .add("Last-Modified", "synthetic-date").build(),
            Json.parseToJsonElement("""{"ModifyTime":2,"CreateTime":0}""").jsonObject)
        assertEquals(EventVersionProfile(EventHeaderShape.SINGLE, EventHeaderShape.SINGLE,
            EventNumberShape.NON_NEGATIVE_INTEGER, EventNumberShape.NON_NEGATIVE_INTEGER), profile)
    }

    @Test fun headerShapeDoesNotClaimSyntaxOrExposeValues() {
        val profile = eventVersionProfile(Headers.Builder().add("ETag", "not-a-valid-tag")
            .add("Last-Modified", "private-first").add("last-modified", "private-second").build(), JsonObject(emptyMap()))
        assertEquals(EventHeaderShape.SINGLE, profile.etag)
        assertEquals(EventHeaderShape.MULTIPLE, profile.lastModified)
        assertFalse(profile.toString().contains("private"))
        assertFalse(profile.toString().contains("valid-tag"))
        assertEquals(EventHeaderShape.EMPTY, eventVersionProfile(Headers.Builder().add("ETag", " ").build(), JsonObject(emptyMap())).etag)
        assertEquals(EventHeaderShape.ABSENT, eventVersionProfile(Headers.Builder().build(), JsonObject(emptyMap())).etag)
    }

    @Test fun onlyBoundedLexicalNonNegativeIntegersAreClassifiedAsSuch() {
        for (value in listOf("-1", "-0", "1.0", "1e2", "9223372036854775808", "\"1\"", "true", "[]", "{}")) {
            assertEquals(value, EventNumberShape.OTHER, profile(value).createTime)
        }
        for (value in listOf("0", "2", "9223372036854775807")) assertEquals(EventNumberShape.NON_NEGATIVE_INTEGER, profile(value).createTime)
        assertEquals(EventNumberShape.NULL, profile("null").createTime)
        assertEquals(EventNumberShape.ABSENT, eventVersionProfile(Headers.Builder().build(), JsonObject(emptyMap())).createTime)
    }

    @Test fun successfulReadChecksEventEnvelopeAndClosesBody() {
        val body = TrackingBody(payload.toByteArray())
        val profile = readEventVersion(Response.success(body), scope, "synthetic-event")
        assertEquals(EventNumberShape.NON_NEGATIVE_INTEGER, profile.modifyTime)
        assertTrue(body.closed)
    }

    @Test fun nonSuccessClosesErrorBodyWithoutReturningItsContents() {
        val body = TrackingBody("private-error".toByteArray())
        refused(Response.error(403, body))
        assertTrue(body.closed)
    }

    @Test fun foreignEventOrCalendarOrInvalidEnvelopeIsRefusedAndClosed() {
        for (invalid in listOf(payload.replace("synthetic-event", "foreign-event"),
            payload.replace("synthetic-calendar", "foreign-calendar"), payload.replace("1000", "1001"),
            payload.replace("\"ModifyTime\":2", "\"ModifyTime\":null"), "private-malformed-json")) {
            val body = TrackingBody(invalid.toByteArray()); refused(Response.success(body)); assertTrue(body.closed)
        }
    }

    @Test fun boundedStrictUtf8ReaderRejectsOversizeAndMalformedUtf8() {
        for (bytes in listOf(ByteArray(1_048_577) { 32 }, byteArrayOf(0xc3.toByte(), 0x28))) {
            val body = TrackingBody(bytes); refused(Response.success(body)); assertTrue(body.closed)
        }
    }

    @Test fun bodyCancellationIsPropagatedAndClosed() {
        val cancellation = CancellationException("synthetic-cancellation")
        val body = TrackingBody(payload.toByteArray(), cancellation)
        try { readEventVersion(Response.success(body), scope, "synthetic-event"); fail("Must cancel") }
        catch (actual: CancellationException) { assertSame(cancellation, actual) }
        assertTrue(body.closed)
    }

    @Test fun retrofitMakesOnlyUnconditionalGet() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setBody(payload).addHeader("ETag", "synthetic-tag"))
            val api = Retrofit.Builder().baseUrl(server.url("/")).build().create(EventVersionApi::class.java)
            assertEquals(EventHeaderShape.SINGLE, readEventVersion(api.event("synthetic-calendar", "synthetic-event"), scope, "synthetic-event").etag)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/calendar/v1/synthetic-calendar/events/synthetic-event", request.path)
            for (name in listOf("If-Match", "If-None-Match", "If-Unmodified-Since", "If-Modified-Since", "Range")) assertNull(request.getHeader(name))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun wrongAccountAndDeniedIdentityNeverRequest() = runBlocking {
        var requests = 0; var authorizations = 0
        val other = session.copy(userId = UserId("foreign-account"))
        for (current in listOf(null, other, session)) {
            val result = inspectEventVersion(CatalogSessionSource { current }, scope, "synthetic-event",
                { authorizations++; false }, { requests++; profile("0") })
            assertNull(result)
        }
        assertEquals(0, requests); assertEquals(1, authorizations)
    }

    @Test fun sessionChangeDuringAuthorizationNeverRequests() = runBlocking {
        var current: CatalogSession? = session; var requests = 0
        assertNull(inspectEventVersion(CatalogSessionSource { current }, scope, "synthetic-event",
            { current = null; true }, { requests++; profile("0") }))
        assertEquals(0, requests)
    }

    @Test fun sessionChangeAfterGetDiscardsProfileWithoutFollowup() = runBlocking {
        var current: CatalogSession? = session; var requests = 0
        assertNull(inspectEventVersion(CatalogSessionSource { current }, scope, "synthetic-event",
            { true }, { requests++; current = null; profile("0") }))
        assertEquals(1, requests)
    }

    @Test fun stableExactSessionReturnsProfileAndCancellationPropagates() = runBlocking {
        val sessions = CatalogSessionSource { session }
        val expected = profile("0")
        assertEquals(expected, inspectEventVersion(sessions, scope, "synthetic-event", { true }, { expected }))
        val cancelled = CancellationException("synthetic")
        try { inspectEventVersion(sessions, scope, "synthetic-event", { true }, { throw cancelled }); fail("Must cancel") }
        catch (actual: CancellationException) { assertSame(cancelled, actual) }
        assertNull(inspectEventVersion(sessions, scope, "synthetic-event", { true }, { error("private-failure") }))
    }

    @Test fun malformedScopeOrEventNeverRequests() = runBlocking {
        var requests = 0
        for ((target, id) in listOf(scope.copy(generation = -1) to "synthetic-event", scope.copy(calendar = null) to "synthetic-event", scope to "")) {
            assertNull(inspectEventVersion(CatalogSessionSource { session }, target, id, { true }, { requests++; profile("0") }))
        }
        assertEquals(0, requests)
    }

    private fun profile(value: String) = eventVersionProfile(Headers.Builder().build(),
        Json.parseToJsonElement("""{"CreateTime":$value}""").jsonObject)

    private fun refused(response: Response<ResponseBody>) {
        try { readEventVersion(response, scope, "synthetic-event"); fail("Must refuse") }
        catch (failure: java.io.IOException) {
            assertEquals("EVENT_VERSION_OBSERVATION_UNAVAILABLE", failure.message); assertNull(failure.cause)
        }
    }

    private class TrackingBody(bytes: ByteArray, failure: RuntimeException? = null) : ResponseBody() {
        var closed = false
        private val buffered = object : ForwardingSource(Buffer().write(bytes)) {
            override fun read(sink: Buffer, byteCount: Long): Long { if (failure != null) throw failure; return super.read(sink, byteCount) }
            override fun close() { closed = true; super.close() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = -1L
        override fun source() = buffered
    }
}
