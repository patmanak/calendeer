// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink

/** Distinct one-exchange permit, never an idempotency key or a CREATE/UPDATE permit. */
internal class ProtonDeleteDispatchAttempt(private val url: HttpUrl, payload: String) {
    private val body = DeleteBody(deleteOnlyBytes(payload))
    private val entered = AtomicBoolean()
    private val exchanged = AtomicBoolean()
    init {
        require(url.query == null && url.fragment == null && url.pathSegments.let {
            it.size == 5 && it[0] == "calendar" && it[1] == "v1" && it[2].isNotBlank() && it[3] == "events" && it[4] == "sync"
        })
    }
    val requestBody: RequestBody get() = body
    fun request(): Request = Request.Builder().url(url).put(body).tag(ProtonDeleteDispatchAttempt::class.java, this).build()
    internal fun enter(request: Request) = matches(request) && entered.compareAndSet(false, true)
    internal fun exchange(request: Request) = matches(request) && entered.get() && exchanged.compareAndSet(false, true)
    private fun matches(request: Request) = request.method == "PUT" && request.url == url && request.body === body && body.isOneShot()
    override fun toString() = "ProtonDeleteDispatchAttempt(redacted)"
}

internal fun deleteOnlyBytes(payload: String): ByteArray = try {
    deleteBounded(payload); require(UniqueJsonKeys(payload).validate())
    val root = (Json.parseToJsonElement(payload) as JsonObject).also { deleteValidateJson(it) }
    require(root.keys == setOf("MemberID", "Events") && root.string("MemberID").isNotBlank())
    val operation = root.array("Events").single() as JsonObject
    require(operation.keys == setOf("ID", "DeletionReason") && operation.string("ID").isNotBlank() && operation.number("DeletionReason") == 0L)
    payload.toByteArray(Charsets.UTF_8)
} catch (_: Exception) { throw ProtonSingleDispatchDenied() }

private class DeleteBody(private val bytes: ByteArray) : RequestBody() {
    private val written = AtomicBoolean()
    override fun contentType() = "application/json; charset=utf-8".toMediaType()
    override fun contentLength() = bytes.size.toLong()
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) {
        if (!written.compareAndSet(false, true)) throw ProtonSingleDispatchDenied()
        try { sink.write(bytes) } finally { bytes.fill(0) }
    }
    override fun toString() = "DeleteBody(redacted)"
}
