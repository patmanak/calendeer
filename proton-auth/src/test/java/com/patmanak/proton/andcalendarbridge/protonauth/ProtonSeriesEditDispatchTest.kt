// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ProtonSeriesEditDispatchTest {
    private fun payload(bit: JsonElement = JsonPrimitive(1)): String {
        val f = PersonalSubmissionFixture()
        val root = ProtonPersonalEventUpdate.request(f.originalRow, f.fragments.clearSharedPart,
            f.fragments.encryptedSharedPart, true, f.context, f.intent.base.content, f.intent.content)
        val operation = root.array("Events").single() as JsonObject
        return JsonObject(root + ("Events" to JsonArray(listOf(JsonObject(operation +
            ("Event" to JsonObject(operation.obj("Event") + ("IsBreakingChange" to bit)))))))).toString()
    }
    @Test fun revokingPushBeforeExchangePreventsPersonalSeriesAndDeleteRequests() = server { server ->
        val url = server.url("/calendar/v1/synthetic/events/sync")
        var automatic = true
        val allowed = { automatic }
        val requests = listOf(
            ProtonPersonalEditV2DispatchAttempt(url, payload(JsonPrimitive(0)), allowed).request(),
            ProtonSeriesEditV3DispatchAttempt(url, payload(), allowed).request(),
            ProtonDeleteDispatchAttempt(url, """{"MemberID":"member","Events":[{"ID":"event","DeletionReason":0}]}""", allowed).request(),
        )
        val client = OkHttpClient.Builder().withSingleDispatchGuards().addInterceptor { chain ->
            automatic = false
            chain.proceed(chain.request())
        }.build()
        for (request in requests) {
            automatic = true
            server.enqueue(MockResponse().setBody("unexpected"))
            try {
                client.newCall(request).execute().close()
                fail("Write reached the server after revocation")
            } catch (_: ProtonSingleDispatchDenied) { }
            assertFalse(automatic)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun acceptsBothIntegerBitsAndPreservesOriginalUtf8Bytes() {
        for (bit in 0..1) {
            val original = " \n" + payload(JsonPrimitive(bit)) + "\t "
            assertArrayEquals(original.toByteArray(Charsets.UTF_8), seriesEventUpdateOnlyBytes(original))
            val attempt = ProtonSeriesEditV3DispatchAttempt("https://calendar-api.proton.me/calendar/v1/synthetic/events/sync".toHttpUrl(), original)
            assertTrue(attempt.requestBody.isOneShot())
            val buffer = Buffer(); attempt.requestBody.writeTo(buffer)
            assertEquals(original, buffer.readUtf8())
            denied { attempt.requestBody.writeTo(Buffer()) }
        }
        denied { personalEventUpdateOnlyBytes(payload()) }
    }
    @Test fun refusesMalformedBitsDuplicateKeysBulkUnknownFieldsAndMalformedUnicode() {
        for (bit in listOf(JsonPrimitive(-1), JsonPrimitive(2), JsonPrimitive("1"), JsonPrimitive(true),
            JsonPrimitive(1.0), JsonNull, JsonArray(emptyList()))) denied { seriesEventUpdateOnlyBytes(payload(bit)) }
        val root = Json.parseToJsonElement(payload()) as JsonObject
        val operation = root.array("Events").single() as JsonObject
        denied { seriesEventUpdateOnlyBytes(JsonObject(root + ("Events" to JsonArray(listOf(operation, operation)))).toString()) }
        denied { seriesEventUpdateOnlyBytes(payload().replace("\"IsImport\":0", "\"IsImport\":0,\"IsImport\":0")) }
        denied { seriesEventUpdateOnlyBytes(payload().replace("\"IsBreakingChange\":1", "\"IsBreakingChange\":1,\"IsBreakingChange\":0")) }
        for (field in listOf("Author", "SharedKeyPacket", "FutureField")) {
            val event = JsonObject(operation.obj("Event") + (field to JsonPrimitive("unexpected")))
            denied { seriesEventUpdateOnlyBytes(JsonObject(root + ("Events" to JsonArray(listOf(JsonObject(operation + ("Event" to event)))))).toString()) }
        }
        denied { seriesEventUpdateOnlyBytes(payload().replace("synthetic", "\uD800")) }
        denied { seriesEventUpdateOnlyBytes(" ".repeat(4 * 1024 * 1024 + 1)) }
    }
    @Test fun strictEndpointAndTagAndBodyBindingRefuseConfusion() = server { server ->
        val url = server.url("/calendar/v1/synthetic/events/sync")
        for (path in listOf("/calendar/v1//events/sync", "/calendar/v1/synthetic/events/sync/", "/calendar/v2/synthetic/events/sync", "/calendar/v1/synthetic/events/other")) {
            try { ProtonSeriesEditV3DispatchAttempt(server.url(path), payload()); fail("Bad endpoint accepted") }
            catch (_: IllegalArgumentException) { } catch (_: ProtonSingleDispatchDenied) { }
        }
        val series = ProtonSeriesEditV3DispatchAttempt(url, payload())
        val personal = ProtonPersonalEditV2DispatchAttempt(url, payload(JsonPrimitive(0)))
        val create = ProtonSingleDispatchAttempt(url, singleCreateFixture)
        val client = OkHttpClient.Builder().withSingleDispatchGuards().build()
        val invalid = listOf(
            series.request().newBuilder().tag(ProtonPersonalEditV2DispatchAttempt::class.java, personal).build(),
            personal.request().newBuilder().tag(ProtonSeriesEditV3DispatchAttempt::class.java, series).build(),
            create.request().newBuilder().tag(ProtonSeriesEditV3DispatchAttempt::class.java, series).build(),
            series.request().newBuilder().tag(ProtonSingleDispatchAttempt::class.java, create).build(),
            series.request().newBuilder().tag(ProtonSeriesEditV3DispatchAttempt::class.java, null).build(),
            series.request().newBuilder().put("{}".toRequestBody()).build(),
            series.request().newBuilder().url(server.url("/other")).build(), series.request().newBuilder().get().build())
        invalid.forEach { denied { client.newCall(it).execute().close() } }
        assertEquals(0, server.requestCount)
    }
    @Test fun onePermitAllowsExactlyOneApplicationEntryAndExchange() = server { server ->
        server.enqueue(MockResponse().setBody("synthetic"))
        val attempt = ProtonSeriesEditV3DispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), payload())
        val client = OkHttpClient.Builder().withSingleDispatchGuards().build()
        client.newCall(attempt.request()).execute().close()
        denied { client.newCall(attempt.request()).execute().close() }
        assertEquals(1, server.requestCount)
    }
    @Test fun secondProceedCannotRepeatSeriesNetworkExchange() = server { server ->
        server.enqueue(MockResponse().setBody("synthetic"))
        val client = OkHttpClient.Builder().withSingleDispatchGuards().addInterceptor { chain ->
            chain.proceed(chain.request()).close(); chain.proceed(chain.request())
        }.build()
        val attempt = ProtonSeriesEditV3DispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), payload())
        denied { client.newCall(attempt.request()).execute().close() }
        assertEquals(1, server.requestCount)
    }
    private fun denied(action: () -> Unit) {
        try { action(); fail("Unsafe series dispatch accepted") } catch (_: ProtonSingleDispatchDenied) { }
    }
    private fun server(action: (MockWebServer) -> Unit) {
        val server = MockWebServer(); try { server.start(); action(server) } finally { server.shutdown() }
    }
}
