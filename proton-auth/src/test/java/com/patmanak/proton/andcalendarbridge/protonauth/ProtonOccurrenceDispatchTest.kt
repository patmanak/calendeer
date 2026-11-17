// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ProtonOccurrenceDispatchTest {
    private val payload = """{"MemberID":"member","IsImport":0,"Events":[{"Overwrite":0,"Event":{"Permissions":1,"IsOrganizer":1,"SharedKeyPacket":"packet","SharedEventContent":[{"Type":2,"Data":"clear","Signature":"signature","Author":""},{"Type":3,"Data":"encrypted","Signature":"signature","Author":""}],"CalendarEventContent":[{"Type":2,"Data":"calendar","Signature":"signature","Author":""}],"Notifications":null,"Color":null,"IsPersonalSingleEdit":false}}]}"""
    private val url = "https://example.invalid/calendar/v1/calendar/events/sync".toHttpUrl()
    @Test fun explicitPermitPreservesNullDefaultsAndExactBytesWithoutRepeating503() {
        val server = MockWebServer(); server.start()
        val client = OkHttpClient.Builder().withSingleDispatchGuards().build()
        try {
            server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("must not be sent"))
            val exact = "\n  $payload  \n"
            val attempt = ProtonSingleDispatchAttempt.occurrence(server.url("/calendar/v1/calendar/events/sync"), exact)
            client.newCall(attempt.request()).execute().use { assertEquals(503, it.code) }
            assertEquals(exact, server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertThrows(ProtonSingleDispatchDenied::class.java) { client.newCall(attempt.request()).execute().close() }
            assertEquals(1, server.requestCount)
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); server.shutdown() }
    }
    @Test fun historicalConstructorStillRejectsTheNewEnvelopeAndEmailsAreRetained() {
        assertThrows(ProtonSingleDispatchDenied::class.java) { ProtonSingleDispatchAttempt(url, payload) }
        val emails = payload.replace("\"Notifications\":null", "\"Notifications\":[{\"Type\":0,\"Trigger\":\"-PT1H\"},{\"Type\":1,\"Trigger\":\"-PT10M\"}]")
        val body = okio.Buffer()
        ProtonSingleDispatchAttempt.occurrence(url, emails).requestBody.writeTo(body)
        assertEquals(emails, body.readUtf8())
    }
    @Test fun updateDeletionMissingMetadataUnknownFieldsAndMalformedUtf8NeverReceiveAPermit() {
        for (changed in listOf(payload.replace("\"Overwrite\":0", "\"ID\":\"event\""),
            payload.replace("\"Overwrite\":0", "\"Overwrite\":1"),
            payload.replace("\"Color\":null,", ""), payload.replace("\"Color\":null", "\"Color\":null,\"Unknown\":1"),
            payload.replace("\"IsPersonalSingleEdit\":false", "\"IsPersonalSingleEdit\":1"),
            payload.replace("\"Author\":\"\"", "\"Author\":\"foreign\""),
            payload.replace("\"clear\"", "\"\ud800\"")))
            assertThrows(ProtonSingleDispatchDenied::class.java) { ProtonSingleDispatchAttempt.occurrence(url, changed) }
    }
}
