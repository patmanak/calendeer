// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import java.io.IOException
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink

/** A process-local permit for one already durably admitted CREATE, not a retry/idempotency key.
 * The shared sync endpoint also accepts UPDATE/DELETE: a permit must not authorize those shapes.
 */
internal class ProtonSingleDispatchAttempt private constructor(private val url: HttpUrl, private val body: SingleDispatchBody) {
    constructor(url: HttpUrl, payload: String) : this(url, SingleDispatchBody(createOnlyBytes(payload)))
    companion object {
        fun occurrence(url: HttpUrl, payload: String) = ProtonSingleDispatchAttempt(url, SingleDispatchBody(occurrenceCreateOnlyBytes(payload)))
    }
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

/** Distinct permit: a CREATE tag can never authorize an existing-event UPDATE (or vice versa). */
internal class ProtonTitleUpdateDispatchAttempt(private val url: HttpUrl, payload: String) {
    private val body = SingleDispatchBody(titleUpdateOnlyBytes(payload))
    private val entered = AtomicBoolean()
    private val exchanged = AtomicBoolean()
    init { require(url.isCreateEndpoint()) { "Unsupported update endpoint" } }
    val requestBody: RequestBody get() = body
    fun request(): Request = Request.Builder().url(url).put(body)
        .tag(ProtonTitleUpdateDispatchAttempt::class.java, this).build()
    internal fun enter(request: Request) = matches(request) && entered.compareAndSet(false, true)
    internal fun exchange(request: Request) = matches(request) && entered.get() && exchanged.compareAndSet(false, true)
    private fun matches(request: Request) = request.method == "PUT" && request.url == url && request.body === body && body.isOneShot()
    override fun toString() = "ProtonTitleUpdateDispatchAttempt(redacted)"
}

internal class ProtonPersonalEditV2DispatchAttempt(private val url: HttpUrl, payload: String) {
    private val body = SingleDispatchBody(personalEventUpdateOnlyBytes(payload))
    private val entered = AtomicBoolean()
    private val exchanged = AtomicBoolean()
    init { require(url.isCreateEndpoint()) { "Unsupported update endpoint" } }
    val requestBody: RequestBody get() = body
    fun request(): Request = Request.Builder().url(url).put(body)
        .tag(ProtonPersonalEditV2DispatchAttempt::class.java, this).build()
    internal fun enter(request: Request) = matches(request) && entered.compareAndSet(false, true)
    internal fun exchange(request: Request) = matches(request) && entered.get() && exchanged.compareAndSet(false, true)
    private fun matches(request: Request) = request.method == "PUT" && request.url == url && request.body === body && body.isOneShot()
    override fun toString() = "ProtonPersonalEditV2DispatchAttempt(redacted)"
}

/** Separate permit for a durably admitted series edit; never interchangeable with v2. */
internal class ProtonSeriesEditV3DispatchAttempt(private val url: HttpUrl, payload: String) {
    private val body = SingleDispatchBody(seriesEventUpdateOnlyBytes(payload))
    private val entered = AtomicBoolean()
    private val exchanged = AtomicBoolean()
    init { require(url.isCreateEndpoint()) { "Unsupported update endpoint" } }
    val requestBody: RequestBody get() = body
    fun request(): Request = Request.Builder().url(url).put(body)
        .tag(ProtonSeriesEditV3DispatchAttempt::class.java, this).build()
    internal fun enter(request: Request) = matches(request) && entered.compareAndSet(false, true)
    internal fun exchange(request: Request) = matches(request) && entered.get() && exchanged.compareAndSet(false, true)
    private fun matches(request: Request) = request.method == "PUT" && request.url == url && request.body === body && body.isOneShot()
    override fun toString() = "ProtonSeriesEditV3DispatchAttempt(redacted)"
}

/** Never include the URL, body, headers or underlying transport exception in diagnostics. */
internal class ProtonSingleDispatchDenied : IOException("CREATE_SINGLE_DISPATCH_DENIED")

/** Operation discrimination only; original signing, scope, rights and journal admission belong
 * to the existing preparation/submission path. Historical CREATE profiles stay compatible.
 * Keep the original bytes after qualification: no payload rewrite or preparation migration.
 */
private fun createOnlyBytes(payload: String): ByteArray {
    // The serializer accepts up to 1 MiB plaintext; encryption/base64 and JSON add overhead.
    // This is a local 4 MiB transport ceiling, not a claimed Proton server limit.
    val limit = 4 * 1024 * 1024
    if (payload.length > limit || !UniqueJsonKeys(payload).validate()) throw ProtonSingleDispatchDenied()
    val accepted = try {
        val root = Json.parseToJsonElement(payload) as? JsonObject
        val member = root?.get("MemberID") as? JsonPrimitive
        val events = root?.get("Events") as? JsonArray
        val operation = events?.singleOrNull() as? JsonObject
        val event = operation?.get("Event") as? JsonObject
        val fields = setOf("Permissions", "IsOrganizer", "SharedKeyPacket", "SharedEventContent")
        fun number(value: JsonElement?, expected: Long) = (value as? JsonPrimitive)?.let {
            !it.isString && it.longOrNull == expected
        } == true
        val packet = event?.get("SharedKeyPacket") as? JsonPrimitive
        root?.keys == setOf("MemberID", "IsImport", "Events") && member?.isString == true && member.content.isNotBlank() &&
            number(root["IsImport"], 0) && operation?.keys == setOf("Overwrite", "Event") && number(operation["Overwrite"], 0) &&
            event != null &&
            ((event.keys == fields || event.keys == fields + "Notifications") &&
                ("Notifications" !in event || (event["Notifications"] as? JsonArray)?.isEmpty() == true) ||
                event.keys == fields + setOf("Notifications", "Color") && CreatedEventReadbackPolicy.PERSONAL_CREATE_V3.accepts(event)) &&
            number(event["Permissions"], 1) && number(event["IsOrganizer"], 1) && packet?.isString == true && packet.content.isNotBlank() &&
            event["SharedEventContent"] is JsonArray
    } catch (_: Exception) { false }
    if (!accepted) throw ProtonSingleDispatchDenied()
    val bytes = payload.toByteArray(Charsets.UTF_8)
    if (bytes.size > limit) { bytes.fill(0); throw ProtonSingleDispatchDenied() }
    return bytes
}

/** Explicit v5 validation view only; transport always retains the original bytes and personal metadata. */
private fun occurrenceCreateOnlyBytes(payload: String): ByteArray = try {
    require(payload.length <= 4_194_304 && UniqueJsonKeys(payload).validate())
    val root = Json.parseToJsonElement(payload) as JsonObject
    val operation = root.array("Events").single() as JsonObject
    val event = operation.obj("Event")
    val fields = setOf("Permissions", "IsOrganizer", "SharedKeyPacket", "SharedEventContent", "Notifications", "Color", "IsPersonalSingleEdit")
    require(event.keys == fields || event.keys == fields + "CalendarEventContent")
    require(event["IsPersonalSingleEdit"] in listOf(JsonPrimitive(true), JsonPrimitive(false)))
    require(ProtonPersonalMetadata.valid(event["Notifications"], event["Color"]))
    event["CalendarEventContent"]?.let { value ->
        val part = (value as JsonArray).single() as JsonObject
        require(part.keys == setOf("Type", "Data", "Signature", "Author") && part["Type"] == JsonPrimitive(2) && part["Author"] == JsonPrimitive(""))
        require(part.string("Data").isNotBlank() && part.string("Signature").isNotBlank())
    }
    val parts = event.array("SharedEventContent").map { it as JsonObject }
    require(parts.map { it.number("Type") }.sorted() == listOf(2L, 3L))
    parts.forEach { require(it.keys == setOf("Type", "Data", "Signature", "Author") && it["Author"] == JsonPrimitive("") &&
        it.string("Data").isNotBlank() && it.string("Signature").isNotBlank()) }
    val view = JsonObject(event - setOf("IsPersonalSingleEdit", "CalendarEventContent") + ("Notifications" to JsonArray(emptyList())))
    createOnlyBytes(JsonObject(root + ("Events" to JsonArray(listOf(JsonObject(operation + ("Event" to view)))))).toString()).fill(0)
    val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(payload))
    require(encoded.remaining() <= 4_194_304)
    ByteArray(encoded.remaining()).also { encoded.get(it) }
} catch (_: Exception) { throw ProtonSingleDispatchDenied() }

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
        val update = request.tag(ProtonTitleUpdateDispatchAttempt::class.java)
        val personal = request.tag(ProtonPersonalEditV2DispatchAttempt::class.java)
        val series = request.tag(ProtonSeriesEditV3DispatchAttempt::class.java)
        val deletion = request.tag(ProtonDeleteDispatchAttempt::class.java)
        if (listOf(attempt, update, personal, series, deletion).count { it != null } > 1) throw ProtonSingleDispatchDenied()
        if (attempt != null) {
            if (!attempt.enter(request)) throw ProtonSingleDispatchDenied()
        } else if (update != null) {
            if (!update.enter(request)) throw ProtonSingleDispatchDenied()
        } else if (personal != null) {
            if (!personal.enter(request)) throw ProtonSingleDispatchDenied()
        } else if (series != null) {
            if (!series.enter(request)) throw ProtonSingleDispatchDenied()
        } else if (deletion != null) {
            if (!deletion.enter(request)) throw ProtonSingleDispatchDenied()
        } else if (request.method == "PUT" && request.url.isCreateEndpoint()) throw ProtonSingleDispatchDenied()
        return chain.proceed(request)
    }
}

/** Independently guards every exchange, including internal follow-ups or interceptor re-entry. */
internal class ProtonSingleDispatchNetworkGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val attempt = request.tag(ProtonSingleDispatchAttempt::class.java)
        val update = request.tag(ProtonTitleUpdateDispatchAttempt::class.java)
        val personal = request.tag(ProtonPersonalEditV2DispatchAttempt::class.java)
        val series = request.tag(ProtonSeriesEditV3DispatchAttempt::class.java)
        val deletion = request.tag(ProtonDeleteDispatchAttempt::class.java)
        if (listOf(attempt, update, personal, series, deletion).count { it != null } > 1) throw ProtonSingleDispatchDenied()
        if (attempt != null) {
            if (!attempt.exchange(request)) throw ProtonSingleDispatchDenied()
        } else if (update != null) {
            if (!update.exchange(request)) throw ProtonSingleDispatchDenied()
        } else if (personal != null) {
            if (!personal.exchange(request)) throw ProtonSingleDispatchDenied()
        } else if (series != null) {
            if (!series.exchange(request)) throw ProtonSingleDispatchDenied()
        } else if (deletion != null) {
            if (!deletion.exchange(request)) throw ProtonSingleDispatchDenied()
        } else if (request.method == "PUT" && request.url.isCreateEndpoint()) throw ProtonSingleDispatchDenied()
        return chain.proceed(request)
    }
}

internal fun OkHttpClient.Builder.withSingleDispatchGuards(): OkHttpClient.Builder =
    followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .addInterceptor(ProtonSingleDispatchApplicationGuard())
        .addNetworkInterceptor(ProtonSingleDispatchNetworkGuard())
        .addNetworkInterceptor(ProtonDeleteReadInterceptor())

private fun HttpUrl.isCreateEndpoint(): Boolean = pathSegments.let {
    it.size == 5 && it[0] == "calendar" && it[1] == "v1" && it[2].isNotEmpty() && it[3] == "events" && it[4] == "sync"
}
