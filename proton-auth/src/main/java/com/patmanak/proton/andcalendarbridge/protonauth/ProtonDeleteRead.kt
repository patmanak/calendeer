// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.DeleteAttemptRecord
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import okio.GzipSource
import okio.buffer

/** Raw transport evidence only. Classification still requires the exact canonical event parser. */
internal class ProtonDeleteCapturedRead(val url: HttpUrl, val httpCode: Int, val body: String) {
    override fun toString() = "ProtonDeleteCapturedRead(redacted)"
}

/** Per-call evidence, bound to one exact authenticated GET and consumed once after Core finishes.
 * Reentry invalidates even a previously captured body; it can never donate an earlier absence.
 * This is a GET proof mechanism, not remote write authority or a retry/idempotency key.
 */
internal class ProtonDeleteReadAttempt(private val url: HttpUrl, private val sessionId: String) {
    private var entered = false
    private var closed = false
    private var invalid = false
    private var captured: ProtonDeleteCapturedRead? = null
    init { require(sessionId.isNotBlank() && url.query == null && url.fragment == null) }

    @Synchronized internal fun begin(request: Request): Boolean {
        if (closed || invalid || entered || !matches(request)) {
            invalidate(); return false
        }
        entered = true; return true
    }
    @Synchronized internal fun capture(response: Response) {
        if (closed || invalid || !entered || captured != null || !matches(response.request)) {
            invalidate(); return
        }
        try {
            // Peek leaves the exact body intact for Core's ServerErrorInterceptor and Retrofit.
            val wire = response.peekBody(DeleteAttemptRecord.MAX_PAYLOAD_BYTES.toLong() + 1).use { it.bytes() }
            if (wire.size > DeleteAttemptRecord.MAX_PAYLOAD_BYTES) { invalidate(); return }
            val encodings = response.headers.values("Content-Encoding")
            if (encodings.size > 1) { invalidate(); return }
            val bytes = decodedBytes(wire, encodings.singleOrNull())
            val body = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            captured = ProtonDeleteCapturedRead(response.request.url, response.code, body)
        } catch (_: Exception) { invalidate() }
    }
    @Synchronized fun finish(): ProtonDeleteCapturedRead? {
        val result = captured.takeIf { entered && !closed && !invalid }
        captured = null; closed = true
        return result
    }
    @Synchronized fun close() { captured = null; closed = true }
    private fun invalidate() { captured = null; invalid = true }
    /** Network interceptors run before BridgeInterceptor's transparent gzip decoding.
     * Bound both the wire and inflated JSON; never let a compressed error bypass the budget.
     */
    private fun decodedBytes(wire: ByteArray, encoding: String?): ByteArray = when (encoding?.trim()?.lowercase()) {
        null, "identity" -> wire
        "gzip" -> GzipSource(Buffer().write(wire)).buffer().use { input ->
            val output = Buffer()
            while (true) {
                val count = input.read(output, minOf(8192L, DeleteAttemptRecord.MAX_PAYLOAD_BYTES - output.size + 1))
                if (count < 0) break
                require(output.size <= DeleteAttemptRecord.MAX_PAYLOAD_BYTES)
            }
            output.readByteArray()
        }
        else -> throw IOException("DELETE_READ_ENCODING_UNSUPPORTED")
    }
    private fun matches(request: Request): Boolean {
        val authorization = request.headers.values("Authorization").singleOrNull() ?: return false
        return request.method == "GET" && request.url == url && request.tag(ProtonDeleteReadAttempt::class.java) === this &&
            request.headers.values("x-pm-uid").singleOrNull() == sessionId && authorization.startsWith("Bearer ") && authorization.removePrefix("Bearer ").isNotBlank()
    }
    override fun toString() = "ProtonDeleteReadAttempt(redacted)"
}

/** Registered as a network interceptor: it sees prepared auth headers and the raw response before
 * Core's application ServerErrorInterceptor turns structured 2501 into an ApiException.
 */
internal class ProtonDeleteReadInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val attempt = request.tag(ProtonDeleteReadAttempt::class.java)
        if (attempt != null && !attempt.begin(request)) throw IOException("DELETE_READ_PROOF_DENIED")
        val response = chain.proceed(request)
        attempt?.capture(response)
        return response
    }
}
