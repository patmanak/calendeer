// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

/** Loopback transport qualification only; no claim about Proton's server concurrency semantics. */
class ProtonCreateOnlyDispatchTest {
    @Test fun staleReadCannotUseCreatePermitToOverwriteANewerRemoteEvent() {
        val server = MockWebServer()
        val remoteTitle = AtomicReference("Original remote title")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "GET" -> {
                    val observed = remoteTitle.getAndSet("Newer remote title")
                    MockResponse().setBody(observed)
                }
                "PUT" -> {
                    remoteTitle.set("Stale local title")
                    MockResponse().setBody("synthetic success")
                }
                else -> MockResponse().setResponseCode(405)
            }
        }
        server.start()
        try {
            val client = OkHttpClient.Builder().withSingleDispatchGuards().build()
            client.newCall(Request.Builder().url(server.url("/calendar/v1/synthetic/events/fixture")).build())
                .execute().use { assertEquals("Original remote title", it.body!!.string()) }
            var refused = false
            try {
                val update = """{"MemberID":"synthetic-member","IsImport":0,"Events":[{"ID":"fixture","Event":{"SharedEventContent":[]}}]}"""
                val attempt = ProtonSingleDispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), update)
                client.newCall(attempt.request()).execute().close()
            } catch (_: ProtonSingleDispatchDenied) { refused = true }
            assertEquals("The simulated concurrent edit must remain intact", "Newer remote title", remoteTitle.get())
            assertEquals("Only the preflight GET may reach the server", 1, server.requestCount)
            assertTrue("The CREATE permit must refuse UPDATE locally", refused)
        } finally { server.shutdown() }
    }

    @Test fun updateDeleteMoveImportAndOverwriteCannotReceiveACreatePermit() = server { server ->
        val event = """{"Permissions":1,"IsOrganizer":1,"SharedKeyPacket":"synthetic","SharedEventContent":[]}"""
        val operations = listOf(
            """{"ID":"fixture","Event":$event}""",
            """{"ID":"fixture","DeletionReason":0}""",
            """{"ID":"fixture","Overwrite":0,"Event":$event}""",
            """{"Overwrite":1,"Event":$event}""",
            """{"Overwrite":0,"Event":$event,"LinkedEventID":"fixture"}""",
            """{"Overwrite":0,"Event":$event},{"ID":"fixture","DeletionReason":0}""",
        )
        for (operation in operations) {
            denied { attempt(server, """{"MemberID":"synthetic","IsImport":0,"Events":[$operation]}""") }
        }
        denied { attempt(server, singleCreateFixture.replace("\"IsImport\":0", "\"IsImport\":1")) }
        assertEquals(0, server.requestCount)
    }

    @Test fun malformedAndAmbiguousJsonNeverReceiveAPermit() = server { server ->
        val bad = listOf("{}", "[]", "null", "{", singleCreateFixture + "{}",
            singleCreateFixture.replace("\"MemberID\":\"synthetic-member\"", "\"MemberID\":3"),
            singleCreateFixture.replace("synthetic-member", " "),
            singleCreateFixture.replace("\"IsImport\":0", "\"IsImport\":\"0\""),
            singleCreateFixture.replace("\"Overwrite\":0", "\"Overwrite\":\"0\""),
            singleCreateFixture.replace("\"Overwrite\":0", "\"Overwrite\":null"),
            singleCreateFixture.replace("\"IsImport\":0", "\"IsImport\":0,\"IsImport\":0"),
            singleCreateFixture.replace("\"Overwrite\":0", "\"Overwrite\":1,\"Overwrite\":0"),
            singleCreateFixture.replace("\"Overwrite\":0", "\"Overwrit\\u0065\":1,\"Overwrite\":0"),
            singleCreateFixture.replace("\"SharedKeyPacket\":\"synthetic-key-packet\"",
                "\"SharedKeyPacket\":\"first\",\"SharedKeyPacket\":\"synthetic-key-packet\""))
        for (payload in bad) denied { attempt(server, payload) }
        assertEquals(0, server.requestCount)
    }

    @Test fun onlyTheTwoCurrentCreateEventProfilesAreAccepted() = server { server ->
        val bad = listOf(
            singleCreateFixture.replace("\"Permissions\":1", "\"Permissions\":\"1\""),
            singleCreateFixture.replace("\"IsOrganizer\":1", "\"IsOrganizer\":0"),
            singleCreateFixture.replace("\"SharedKeyPacket\":\"synthetic-key-packet\"", "\"SharedKeyPacket\":null"),
            singleCreateFixture.replace("synthetic-key-packet", ""),
            singleCreateFixture.replace("\"SharedEventContent\":[]", "\"SharedEventContent\":null"),
            singleCreateFixture.replace("\"SharedEventContent\":[]", "\"SharedEventContent\":[],\"ID\":\"fixture\""),
            singleCreateFixture.replace("\"SharedEventContent\":[]", "\"SharedEventContent\":[],\"Notifications\":null"),
            singleCreateFixture.replace("\"SharedEventContent\":[]", "\"SharedEventContent\":[],\"Notifications\":[{}]"),
            singleCreateFixture.replace("\"IsImport\":0", "\"IsImport\":0,\"Unexpected\":0"))
        for (payload in bad) denied { attempt(server, payload) }
        assertEquals(0, server.requestCount)
    }

    @Test fun bothSupportedProfilesPreserveExactPayloadBytes() = server { server ->
        val client = OkHttpClient.Builder().withSingleDispatchGuards().build()
        val knownEmpty = singleCreateFixture.replace("\"SharedEventContent\":[]", "\"SharedEventContent\":[],\"Notifications\":[]")
        for (payload in listOf(singleCreateFixture, " \n$knownEmpty\n ")) {
            server.enqueue(MockResponse().setBody("synthetic success"))
            client.newCall(attempt(server, payload).request()).execute().close()
            assertEquals(payload, server.takeRequest().body.readUtf8())
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun transportBudgetCountsUtf8BytesAndRetainsTheExactBoundary() = server { server ->
        val limit = 4 * 1024 * 1024
        val overhead = singleCreateFixture.length - "synthetic-key-packet".length
        val exact = singleCreateFixture.replace("synthetic-key-packet", "a".repeat(limit - overhead))
        assertEquals(limit.toLong(), attempt(server, exact).requestBody.contentLength())
        denied { attempt(server, exact + " ") }
        val multibyte = singleCreateFixture.replace("synthetic-key-packet", "é".repeat(limit / 2))
        assertTrue(multibyte.length < limit)
        denied { attempt(server, multibyte) }
        assertEquals(0, server.requestCount)
    }

    private fun attempt(server: MockWebServer, payload: String) =
        ProtonSingleDispatchAttempt(server.url("/calendar/v1/synthetic/events/sync"), payload)
    private fun denied(action: () -> Unit) {
        try { action(); fail("Unsupported mutation received a CREATE permit") }
        catch (failure: ProtonSingleDispatchDenied) {
            assertEquals("CREATE_SINGLE_DISPATCH_DENIED", failure.message)
            assertNull(failure.cause)
        }
    }
    private fun server(action: (MockWebServer) -> Unit) {
        val server = MockWebServer(); server.start()
        try { action(server) } finally { server.shutdown() }
    }
}
