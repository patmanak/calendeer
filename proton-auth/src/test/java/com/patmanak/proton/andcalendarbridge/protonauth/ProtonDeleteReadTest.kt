// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.DeleteAttemptRecord
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ProtonDeleteReadTest {
    private val url = "https://calendar-api.proton.me/calendar/v1/calendar/events/event".toHttpUrl()
    private fun request(attempt: ProtonDeleteReadAttempt) = Request.Builder().url(url).get()
        .header("x-pm-uid", "synthetic-session").header("Authorization", "Bearer synthetic-access")
        .tag(ProtonDeleteReadAttempt::class.java, attempt).build()
    private fun response(request: Request, body: String) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(404).message("Synthetic missing").body(body.toResponseBody()).build()

    @Test fun capturedRawBodyPreservesDuplicateKeysAndDoesNotConsumeCoreResponse() {
        val attempt = ProtonDeleteReadAttempt(url, "synthetic-session"); val request = request(attempt)
        val raw = "{\"Code\":2501,\"Code\":1000}"
        val response = response(request, raw)
        assertTrue(attempt.begin(request)); attempt.capture(response)
        val captured = requireNotNull(attempt.finish())
        assertEquals(url, captured.url); assertEquals(404, captured.httpCode); assertEquals(raw, captured.body)
        assertEquals(raw, response.body!!.string())
        assertEquals(ProtonDeleteEventRead.Unknown, ProtonDeleteEventResponseParser.parse(captured.url, "calendar", "event", captured.httpCode, captured.body))
        assertNull(attempt.finish()); assertFalse(captured.toString().contains("2501"))
    }
    @Test fun secondTaggedEntryInvalidatesEvenAnAlreadyCapturedAbsence() {
        val attempt = ProtonDeleteReadAttempt(url, "synthetic-session"); val request = request(attempt)
        assertTrue(attempt.begin(request)); response(request, "{\"Code\":2501}").use { attempt.capture(it) }
        assertFalse(attempt.begin(request)); assertNull(attempt.finish())
    }
    @Test fun wrongMethodUrlSessionOrAmbiguousAuthNeverEnters() {
        val variants: List<(Request) -> Request> = listOf(
            { it.newBuilder().post("".toRequestBody()).build() },
            { it.newBuilder().url(url.newBuilder().addPathSegment("other").build()).build() },
            { it.newBuilder().header("x-pm-uid", "foreign-session").build() },
            { it.newBuilder().removeHeader("Authorization").build() },
            { it.newBuilder().addHeader("Authorization", "Bearer other").build() },
            { it.newBuilder().addHeader("x-pm-uid", "foreign-session").build() })
        for (variant in variants) {
            val attempt = ProtonDeleteReadAttempt(url, "synthetic-session")
            assertFalse(attempt.begin(variant(request(attempt)))); assertNull(attempt.finish())
        }
    }
    @Test fun mismatchingResponseCannotDonateAProofAndClosedProbeCannotReenter() {
        val attempt = ProtonDeleteReadAttempt(url, "synthetic-session"); val request = request(attempt)
        assertTrue(attempt.begin(request))
        response(request.newBuilder().url(url.newBuilder().addPathSegment("foreign").build()).build(), "{\"Code\":2501}")
            .use { attempt.capture(it) }
        assertNull(attempt.finish()); assertFalse(attempt.begin(request))
        val closed = ProtonDeleteReadAttempt(url, "synthetic-session"); closed.close()
        assertFalse(closed.begin(request(closed))); assertNull(closed.finish())
    }
    @Test fun oversizedRawResponseIsRejectedBeforeItCanBeInterpreted() {
        val attempt = ProtonDeleteReadAttempt(url, "synthetic-session"); val request = request(attempt)
        assertTrue(attempt.begin(request))
        response(request, "x".repeat(DeleteAttemptRecord.MAX_PAYLOAD_BYTES + 1)).use { attempt.capture(it) }
        assertNull(attempt.finish())
    }
    @Test fun gzipIsDecodedBeforeParsingWithoutConsumingTheCompressedResponse() {
        val attempt = ProtonDeleteReadAttempt(url, "synthetic-session"); val request = request(attempt)
        val plain = "{\"Code\":2501,\"Error\":\"Synthetic missing\"}"
        val wire = gzip(plain)
        val response = response(request, "").newBuilder().header("Content-Encoding", "gzip").body(wire.toResponseBody()).build()
        assertTrue(attempt.begin(request)); attempt.capture(response)
        val captured = requireNotNull(attempt.finish()); assertEquals(plain, captured.body)
        assertTrue(ProtonDeleteEventResponseParser.parse(captured.url, "calendar", "event", captured.httpCode, captured.body) is ProtonDeleteEventRead.Absent)
        assertArrayEquals(wire, response.body!!.bytes())
    }
    @Test fun compressedBombMalformedGzipAndUnknownEncodingCannotDonateProof() {
        val cases = listOf("gzip" to gzip("x".repeat(DeleteAttemptRecord.MAX_PAYLOAD_BYTES + 1)),
            "gzip" to "{\"Code\":2501}".toByteArray(), "gzip" to (gzip("{\"Code\":2501}") + byteArrayOf(1)),
            "br" to "{\"Code\":2501}".toByteArray())
        for ((encoding, wire) in cases) {
            val attempt = ProtonDeleteReadAttempt(url, "synthetic-session"); val request = request(attempt)
            assertTrue(attempt.begin(request))
            response(request, "").newBuilder().header("Content-Encoding", encoding).body(wire.toResponseBody()).build().use { attempt.capture(it) }
            assertNull(attempt.finish())
        }
    }
    private fun gzip(value: String): ByteArray = ByteArrayOutputStream().run {
        GZIPOutputStream(this).use { it.write(value.toByteArray(Charsets.UTF_8)) }; toByteArray()
    }
}
