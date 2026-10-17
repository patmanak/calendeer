// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ProtonDeleteTransportTest {
    private val eventUrl = "https://calendar-api.proton.me/calendar/v1/synthetic-calendar/events/synthetic-event".toHttpUrl()
    private val syncUrl = "https://calendar-api.proton.me/calendar/v1/synthetic-calendar/events/sync".toHttpUrl()
    private val body = "{\"MemberID\":\"synthetic-member\",\"Events\":[{\"ID\":\"synthetic-event\",\"DeletionReason\":0}]}"

    @Test fun exactStructured2501IsScopedButGeneric404OrWrongEndpointIsUnknown() {
        fun parse(url: okhttp3.HttpUrl = eventUrl, status: Int = 404, body: String = "{\"Code\":2501,\"Error\":\"Does not exist\"}") =
            ProtonDeleteEventResponseParser.parse(url, "synthetic-calendar", "synthetic-event", status, body)
        val absent = parse() as ProtonDeleteEventRead.Absent
        assertEquals("synthetic-calendar", absent.calendar); assertEquals("synthetic-event", absent.eventId)
        for (result in listOf(parse(body = "Not found"), parse(body = "{\"Code\":404}"), parse(status = 403), parse(url = syncUrl),
            parse(url = eventUrl.newBuilder().addQueryParameter("Page", "0").build()),
            parse(url = eventUrl.newBuilder().host("other.invalid").build()),
            parse(body = "{\"Code\":2501,\"Code\":2501}"), parse(body = "{\"Code\":2501,\"Event\":{}}")))
            assertEquals(ProtonDeleteEventRead.Unknown, result)
    }
    @Test fun indexedSuccessDoesNotRequireAnEventAnd2501MutationResponseIsOnlyFailure() {
        assertEquals(ProtonDeleteResponse.ReportedSuccess,
            ProtonDeleteResponseParser.parse("{\"Code\":1001,\"Responses\":[{\"Index\":0,\"Response\":{\"Code\":1000}}]}"))
        assertEquals(ProtonDeleteResponse.ReportedFailure(2501),
            ProtonDeleteResponseParser.parse("{\"Code\":1001,\"Responses\":[{\"Index\":0,\"Response\":{\"Code\":2501}}]}"))
        for (value in listOf("{\"Code\":1000}", "{\"Code\":1001,\"Responses\":[{\"Index\":1,\"Response\":{\"Code\":1000}}]}",
            "{\"Code\":1001,\"Responses\":[{\"Index\":0,\"Response\":{\"Code\":1000,\"Error\":\"failed\"}}]}"))
            assertEquals(ProtonDeleteResponse.Unknown, ProtonDeleteResponseParser.parse(value))
    }
    @Test fun typedPermitIsExactOneShotAndRejectsCreateUpdateBulkAndUnknownShapes() {
        val permit = ProtonDeleteDispatchAttempt(syncUrl, body); val request = permit.request()
        assertFalse(permit.exchange(request)); assertTrue(permit.enter(request)); assertFalse(permit.enter(request))
        assertTrue(permit.exchange(request)); assertFalse(permit.exchange(request))
        val buffer = Buffer(); permit.requestBody.writeTo(buffer); assertEquals(body, buffer.readUtf8())
        denied { permit.requestBody.writeTo(Buffer()) }
        for (value in listOf(body.replace("\"DeletionReason\":0", "\"DeletionReason\":1"),
            body.replace("\"DeletionReason\":0", "\"Event\":{}"), body.replace("\"DeletionReason\":0", "\"Overwrite\":0"),
            body.replace("\"MemberID\":", "\"IsImport\":0,\"MemberID\":"), body.replace("\"ID\":", "\"ID\":\"duplicate\",\"ID\":")))
            denied { ProtonDeleteDispatchAttempt(syncUrl, value) }
        assertFalse(ProtonDeleteDispatchAttempt(syncUrl, body).enter(request))
    }
    private fun denied(action: () -> Unit) {
        try { action(); fail("Invalid permit accepted") } catch (_: IOException) { }
    }
}
