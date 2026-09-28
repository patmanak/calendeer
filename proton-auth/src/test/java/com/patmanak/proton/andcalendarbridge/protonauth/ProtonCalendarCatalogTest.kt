// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.AccountId
import com.patmanak.proton.andcalendarbridge.sync.CalendarId
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogFailure
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarCatalogResult
import com.patmanak.proton.andcalendarbridge.sync.catalog.CalendarSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test
import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.buffer
import okio.source
import kotlin.time.Duration.Companion.seconds

class ProtonCalendarCatalogTest {
    @Test fun readsOwnMemberVisualMetadataAndIgnoresUnknownFields() {
        val parsed = CalendarCatalogParser.parse(envelope("""
            {"ID":"cal-a","Type":1,"Name":"wrong legacy name","Future":{"opaque":true},
             "Members":[{"CalendarID":"cal-a","Name":"Équipe","Color":"#A1b2C3","Permissions":127},
                        {"CalendarID":"cal-a","Name":"secondary address","Color":"#ffffff"}]}
        """))
        assertEquals(listOf(CalendarSummary(CalendarId("cal-a"), "Équipe", 0xA1B2C3)), parsed)
    }

    @Test fun genuineEmptyCatalogIsSuccess() {
        assertTrue(CalendarCatalogParser.parse(envelope()).isEmpty())
    }

    @Test fun unsupportedColorDoesNotDiscardCalendar() {
        assertEquals(null, CalendarCatalogParser.parse(envelope(row(color = "future-color"))).single().colorRgb)
    }

    @Test fun missingNullWrongTypeOrWrongCodeAreNeverEmptySuccess() {
        listOf("{}", "[]", "null", "{\"Code\":1000}", "{\"Code\":1000,\"Calendars\":null}",
            "{\"Code\":1001,\"Calendars\":[]}", "{\"Code\":\"1000\",\"Calendars\":[]}",
            "{\"Code\":1000,\"Calendars\":{}}").forEach(::assertRejected)
    }

    @Test fun missingOwnMemberAndMismatchedMembershipFailCompleteCatalog() {
        listOf(
            "{\"ID\":\"cal-a\"}",
            "{\"ID\":\"cal-a\",\"Members\":[]}",
            "{\"ID\":\"cal-a\",\"Members\":[{\"Name\":\"A\"}]}",
            "{\"ID\":\"cal-a\",\"Members\":[{\"CalendarID\":\"other\",\"Name\":\"A\"}]}",
            "{\"ID\":\"cal-a\",\"Members\":[{\"CalendarID\":\"cal-a\",\"Name\":null}]}",
        ).forEach { assertRejected(envelope(it)) }
    }

    @Test fun duplicateIdentifiersAndInvalidRowsAreRejected() {
        assertRejected(envelope(row(), row()))
        assertRejected(envelope("null"))
        assertRejected(envelope(row(id = " ")))
        assertRejected(envelope(row(id = "a".repeat(4097))))
        assertRejected(envelope(row(title = "a".repeat(1025))))
    }

    @Test fun recordAndPayloadOverflowNeverReturnPartialResult() {
        assertRejected(envelope(*(0..CalendarCatalogParser.MAX_CALENDARS).map { row("cal-$it") }.toTypedArray()))
        assertRejected(" ".repeat(CalendarCatalogParser.MAX_RESPONSE_BYTES + 1))
    }

    @Test fun streamedBodyLimitWorksWithoutContentLengthAndClosesSource() {
        var closed = false
        val source = object : java.io.ByteArrayInputStream(ByteArray(CalendarCatalogParser.MAX_RESPONSE_BYTES + 1)) {
            override fun close() { closed = true; super.close() }
        }
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source() = source.source().buffer()
        }
        try { CalendarCatalogBodyReader.read(body); fail("Unbounded stream must be rejected") }
        catch (_: UnsupportedCatalogResponse) { }
        assertTrue(closed)
    }

    @Test fun malformedUtf8CannotBecomeDifferentCalendarMetadata() {
        val body = object : ResponseBody() {
            private val bytes = Buffer().write(byteArrayOf(0xC3.toByte(), 0x28))
            override fun contentType(): MediaType? = null
            override fun contentLength() = 2L
            override fun source() = bytes
        }
        try { CalendarCatalogBodyReader.read(body); fail("Invalid UTF-8 must be rejected") }
        catch (_: UnsupportedCatalogResponse) { }
    }

    @Test fun unauthenticatedDoesNotCallRemote() = runTest {
        var calls = 0
        val result = ProtonCalendarCatalogAdapter({ null }, { calls++; emptyList() }).load()
        assertEquals(CalendarCatalogResult.Failure(CalendarCatalogFailure.AUTHENTICATION_REQUIRED), result)
        assertEquals(0, calls)
    }

    @Test fun resultBindsExactlyToRequestSessionAndAccount() = runTest {
        val session = session("account-a", "session-a")
        val rows = listOf(CalendarSummary(CalendarId("calendar-a"), "A"))
        val result = ProtonCalendarCatalogAdapter({ session }, {
            assertEquals(session, it)
            rows
        }).load()
        assertEquals(CalendarCatalogResult.Success(AccountId("account-a"), rows), result)
    }

    @Test fun logoutOrReplacementWhileAwaitingRemoteDiscardsOldResult() = runTest {
        listOf(null, session("account-b", "session-b"), session("account-a", "replacement")).forEach { replaced ->
            var active: CatalogSession? = session("account-a", "session-a")
            val result = ProtonCalendarCatalogAdapter({ active }, {
                active = replaced
                listOf(CalendarSummary(CalendarId("calendar-a"), "A"))
            }).load()
            assertEquals(CalendarCatalogResult.Failure(CalendarCatalogFailure.AUTHENTICATION_REQUIRED), result)
        }
    }

    @Test fun malformedAndRateLimitedRemainFailuresWithRetryDelay() = runTest {
        val session = session("account-a", "session-a")
        val errors = listOf(
            UnsupportedCatalogResponse() to CalendarCatalogResult.Failure(CalendarCatalogFailure.UNSUPPORTED_RESPONSE),
            ApiException(ApiResult.Error.Http(429, "synthetic private error", retryAfter = 7.seconds)) to
                CalendarCatalogResult.Failure(CalendarCatalogFailure.RATE_LIMITED, 7_000),
            ApiException(ApiResult.Error.Http(403, "synthetic")) to
                CalendarCatalogResult.Failure(CalendarCatalogFailure.FORBIDDEN),
            ApiException(ApiResult.Error.Http(401, "synthetic")) to
                CalendarCatalogResult.Failure(CalendarCatalogFailure.AUTHENTICATION_REQUIRED),
            ApiException(ApiResult.Error.Timeout(true)) to
                CalendarCatalogResult.Failure(CalendarCatalogFailure.TIMED_OUT),
        )
        errors.forEach { (failure, expected) ->
            assertEquals(expected, ProtonCalendarCatalogAdapter({ session }, { throw failure }).load())
        }
    }

    @Test fun cancellationPropagates() = runTest {
        val cancellation = CancellationException("synthetic")
        try {
            ProtonCalendarCatalogAdapter({ session("a", "s") }, { throw cancellation }).load()
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) { assertSame(cancellation, actual) }
    }

    private fun assertRejected(value: String) {
        try { CalendarCatalogParser.parse(value); fail("Expected unsupported response") }
        catch (_: UnsupportedCatalogResponse) { }
    }
    private fun envelope(vararg rows: String) = "{\"Code\":1000,\"Calendars\":[${rows.joinToString()}]}"
    private fun row(id: String = "cal-a", title: String = "A", color: String = "#123456") =
        "{\"ID\":\"$id\",\"Members\":[{\"CalendarID\":\"$id\",\"Name\":\"$title\",\"Color\":\"$color\"}]}"
    private fun session(user: String, session: String) = CatalogSession(UserId(user), SessionId(session))
}
