// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ProtonDeleteNetworkInterruptionProbeTest {
    private val url = "https://calendar-api.proton.me/calendar/v1/synthetic-calendar/events/sync".toHttpUrl()
    private val client = OkHttpClient()
    private val payload = """{"MemberID":"synthetic-member","Events":[{"ID":"synthetic-event","DeletionReason":0}]}"""
    private val fingerprint get() = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
    private fun taggedCall() = client.newCall(ProtonDeleteDispatchAttempt(url, payload).request())
    private fun authenticated(call: Call) = call.request().newBuilder()
        .header("x-pm-uid", "synthetic-session").header("Authorization", "Bearer synthetic-fixture").build()

    @Test fun onlyAuthenticatedSameCallSocketFailureAfterHookQualifies() {
        var hooks = 0
        val session = DeleteNetworkObservationSession(url, "synthetic-session", fingerprint) { hooks++ }
        val call = taggedCall(); val listener = session.listener(call)
        listener.requestHeadersEnd(call, authenticated(call)); listener.requestBodyStart(call)
        assertFalse(session.observation().measuredFailure)
        listener.callFailed(call, SocketException("synthetic disconnect"))
        assertEquals(1, hooks); assertTrue(session.observation().measuredFailure)
        assertFalse(session.observation().toString().contains("synthetic"))
    }

    @Test fun foreignRouteAndUntaggedRequestsNeverArmInterruption() {
        var hooks = 0
        val session = DeleteNetworkObservationSession(url, "synthetic-session", fingerprint) { hooks++ }
        for (request in listOf(Request.Builder().url(url).build(),
            taggedCall().request().newBuilder().url(url.newBuilder().addQueryParameter("foreign", "1").build()).build())) {
            val call = client.newCall(request); val listener = session.listener(call)
            listener.requestHeadersEnd(call, authenticated(call)); listener.requestBodyStart(call)
            listener.callFailed(call, SocketException())
        }
        assertEquals(0, hooks); assertEquals(0, session.observation().calls); assertFalse(session.observation().measuredFailure)
    }

    @Test fun anotherDeletionInTheSameCalendarAndSessionCannotDonateTheMeasurement() {
        var hooks = 0
        val session = DeleteNetworkObservationSession(url, "synthetic-session", fingerprint) { hooks++ }
        for (wire in listOf(payload.replace("synthetic-event", "other-event"), payload.replace("synthetic-member", "other-member"))) {
            val call = client.newCall(ProtonDeleteDispatchAttempt(url, wire).request())
            val listener = session.listener(call)
            listener.requestHeadersEnd(call, authenticated(call)); listener.requestBodyStart(call)
            listener.callFailed(call, SocketException())
            assertSame(EventListener.NONE, listener)
        }
        assertEquals(0, hooks); assertEquals(0, session.observation().calls); assertFalse(session.observation().measuredFailure)
    }

    @Test fun missingForeignOrAmbiguousSessionAndAuthorizationCannotTriggerHook() {
        for (headers in listOf(Headers.Builder().build(),
            Headers.Builder().add("x-pm-uid", "foreign").add("Authorization", "Bearer synthetic").build(),
            Headers.Builder().add("x-pm-uid", "synthetic-session").add("x-pm-uid", "synthetic-session")
                .add("Authorization", "Bearer synthetic").build(),
            Headers.Builder().add("x-pm-uid", "synthetic-session").add("Authorization", "Bearer synthetic")
                .add("Authorization", "Bearer synthetic").build())) {
            var hooks = 0
            val session = DeleteNetworkObservationSession(url, "synthetic-session", fingerprint) { hooks++ }
            val call = taggedCall(); val listener = session.listener(call)
            listener.requestHeadersEnd(call, call.request().newBuilder().headers(headers).build())
            listener.requestBodyStart(call); listener.callFailed(call, SocketException())
            assertEquals(0, hooks); assertFalse(session.observation().measuredFailure)
        }
    }

    @Test fun cancellationTimeoutDispatchDenialAndGenericIoNeverDonateSocketEvidence() {
        for (failure in listOf(IOException(), SocketTimeoutException(), ProtonSingleDispatchDenied())) {
            val session = DeleteNetworkObservationSession(url, "synthetic-session", fingerprint) {}
            val call = taggedCall(); val listener = session.listener(call)
            listener.requestHeadersEnd(call, authenticated(call)); listener.requestBodyStart(call)
            listener.callFailed(call, failure)
            assertFalse(session.observation().measuredFailure)
        }
        val session = DeleteNetworkObservationSession(url, "synthetic-session", fingerprint) {}
        val call = taggedCall(); val listener = session.listener(call)
        listener.requestHeadersEnd(call, authenticated(call)); listener.requestBodyStart(call)
        call.cancel(); listener.callFailed(call, SocketException())
        assertFalse(session.observation().measuredFailure)
    }

    @Test fun foreignCallAndSuccessfulReplyCannotBecomeFailureEvidence() {
        val session = DeleteNetworkObservationSession(url, "synthetic-session", fingerprint) {}
        val call = taggedCall(); val other = taggedCall(); val listener = session.listener(call)
        listener.requestHeadersEnd(other, authenticated(call)); listener.requestBodyStart(other)
        assertEquals(0, session.observation().bodyStarts)
        listener.requestHeadersEnd(call, authenticated(call)); listener.requestBodyStart(call)
        listener.callFailed(other, SocketException()); assertFalse(session.observation().measuredFailure)
        listener.callEnd(call); listener.callFailed(call, SocketException())
        assertFalse(session.observation().measuredFailure)
    }

    @Test fun repeatedCallOrBodyStartCannotQualifyAndHookRunsOnce() {
        var hooks = 0
        val session = DeleteNetworkObservationSession(url, "synthetic-session", fingerprint) { hooks++ }
        val call = taggedCall(); val listener = session.listener(call)
        listener.requestHeadersEnd(call, authenticated(call)); listener.requestBodyStart(call); listener.requestBodyStart(call)
        listener.callFailed(call, SocketException())
        assertEquals(1, hooks); assertFalse(session.observation().measuredFailure)
        session.listener(taggedCall()); assertEquals(2, session.observation().calls)
    }

    @Test fun hookErrorAndClosedSessionCannotQualify() {
        val session = DeleteNetworkObservationSession(url, "synthetic-session", fingerprint) { throw IllegalStateException("synthetic hook failure") }
        val call = taggedCall(); val listener = session.listener(call)
        listener.requestHeadersEnd(call, authenticated(call))
        try { listener.requestBodyStart(call); fail("Hook must fail") } catch (_: IllegalStateException) {}
        listener.callFailed(call, SocketException()); assertFalse(session.observation().measuredFailure)
        session.close(); assertSame(EventListener.NONE, session.listener(taggedCall()))
    }
}
