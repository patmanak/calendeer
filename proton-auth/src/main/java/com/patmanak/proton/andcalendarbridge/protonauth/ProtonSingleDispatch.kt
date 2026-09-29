// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink

/** A process-local permit for one already durably admitted attempt, not a retry/idempotency key. */
internal class ProtonSingleDispatchAttempt(private val url: HttpUrl, payload: String) {
    private val body = SingleDispatchBody(payload.toByteArray(Charsets.UTF_8))
    private val entered = AtomicBoolean()
    private val exchanged = AtomicBoolean()

    init { require(url.isCreateEndpoint()) { "Unsupported create endpoint" } }
    val requestBody: RequestBody get() = body
    fun request(): Request = Request.Builder().url(url).put(body)
        .tag(ProtonSingleDispatchAttempt::class.java, this).build()
    internal fun enter(request: Request): Boolean = matches(request) && entered.compareAndSet(false, true)
    internal fun exchange(request: Request): Boolean = matches(request) && entered.get() && exchanged.compareAndSet(false, true)
    private fun matches(request: Request) = request.method == "PUT" && request.url == url && request.body === body && body.isOneShot()
    override fun toString() = "ProtonSingleDispatchAttempt(redacted)"
}

/** Never include the URL, body, headers or underlying transport exception in diagnostics. */
internal class ProtonSingleDispatchDenied : IOException("CREATE_SINGLE_DISPATCH_DENIED")

private class SingleDispatchBody(private val bytes: ByteArray) : RequestBody() {
    private val written = AtomicBoolean()
    override fun contentType() = "application/json; charset=utf-8".toMediaType()
    override fun contentLength() = bytes.size.toLong()
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) {
        if (!written.compareAndSet(false, true)) throw ProtonSingleDispatchDenied()
        try { sink.write(bytes) } finally { bytes.fill(0) }
    }
    override fun toString() = "SingleDispatchBody(redacted)"
}

/** Guards Core re-entry before its header preparation or any network exchange. */
internal class ProtonSingleDispatchApplicationGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val attempt = request.tag(ProtonSingleDispatchAttempt::class.java)
        if (attempt != null) {
            if (!attempt.enter(request)) throw ProtonSingleDispatchDenied()
        } else if (request.method == "PUT" && request.url.isCreateEndpoint()) throw ProtonSingleDispatchDenied()
        return chain.proceed(request)
    }
}

/** Independently guards every exchange, including internal follow-ups or interceptor re-entry. */
internal class ProtonSingleDispatchNetworkGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val attempt = request.tag(ProtonSingleDispatchAttempt::class.java)
        if (attempt != null) {
            if (!attempt.exchange(request)) throw ProtonSingleDispatchDenied()
        } else if (request.method == "PUT" && request.url.isCreateEndpoint()) throw ProtonSingleDispatchDenied()
        return chain.proceed(request)
    }
}

internal fun OkHttpClient.Builder.withSingleDispatchGuards(): OkHttpClient.Builder =
    followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .addInterceptor(ProtonSingleDispatchApplicationGuard())
        .addNetworkInterceptor(ProtonSingleDispatchNetworkGuard())

private fun HttpUrl.isCreateEndpoint(): Boolean = pathSegments.let {
    it.size == 5 && it[0] == "calendar" && it[1] == "v1" && it[2].isNotEmpty() && it[3] == "events" && it[4] == "sync"
}
