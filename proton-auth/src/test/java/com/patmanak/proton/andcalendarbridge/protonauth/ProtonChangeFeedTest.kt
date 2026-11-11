package com.patmanak.proton.andcalendarbridge.protonauth

import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.ApiException
import me.proton.core.network.domain.ApiResult
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test

class ProtonChangeFeedTest {
    private val scope = ProtonFeedScope("fixture-account", 1, "fixture-calendar")
    private val cursor = ProtonFeedCursor(scope, "cursor-before")
    private val session = CatalogSession(UserId(scope.account), SessionId("fixture-session"))

    @Test fun calendarUrlMetadataDoesNotBlockAnOtherwiseValidEventPage() {
        // An empty collection already satisfies the official optional CalendarURL array type.
        // No account, sharing link or network request is used by this reproduction.
        val root = JsonObject(page("""[{"ID":"fixture-event","Action":0}]""", more = 1)
            .toMutableMap().apply { put("CalendarURL", JsonArray(emptyList())) })
        val parsed = ProtonChangeFeedParser.page(cursor, root)
        assertEquals("cursor-after", parsed.next.value)
        assertTrue(parsed.more)
        assertFalse(parsed.requiresReconciliation)
        assertEquals("CalendarEvents", parsed.changes.single().collection)
        assertEquals(ProtonFeedAction.DELETE, parsed.changes.single().action)
        assertEquals(root, parsed.envelope)
    }

    @Test fun calendarUrlMetadataIsOpaqueAndDoesNotChangeEventProvenance() {
        val base = page("""[{"ID":"fixture-event","Action":2,"Event":{"ID":"fixture-event","CalendarID":"fixture-calendar"}}]""")
        val metadata = JsonArray(listOf(
            obj("""{"ID":"synthetic-notice","Action":0,"FutureMetadata":{"CalendarEvents":[{"ID":"not-an-event","Action":0}]}}"""),
            obj("""{"ID":"synthetic-notice","Action":0}"""),
        ))
        val root = JsonObject(base + ("CalendarURL" to metadata))
        val parsed = ProtonChangeFeedParser.page(cursor, root)
        assertEquals(ProtonChangeFeedParser.page(cursor, base).changes, parsed.changes)
        assertEquals(root, parsed.envelope)
        assertEquals(metadata, parsed.envelope["CalendarURL"])
        assertEquals("fixture-event", parsed.changes.single().id)
    }

    @Test fun calendarUrlOnlyPagesAndNullMetadataAdvanceWithoutInventingActions() {
        for (metadata in listOf(JsonNull, JsonArray(emptyList()), JsonArray(listOf(obj("""{"ID":"synthetic-notice","Action":0}"""))))) {
            val root = JsonObject(page().toMutableMap().apply { put("CalendarURL", metadata) })
            val parsed = ProtonChangeFeedParser.page(cursor, root)
            assertEquals("cursor-after", parsed.next.value)
            assertTrue(parsed.changes.isEmpty())
            assertFalse(parsed.more)
            assertFalse(parsed.requiresReconciliation)
            assertEquals(root, parsed.envelope)
        }
    }

    @Test fun calendarUrlMetadataMustRemainABoundedArrayOfOpaqueObjects() {
        val invalid = listOf<JsonElement>(JsonObject(emptyMap()), JsonPrimitive("synthetic"),
            JsonPrimitive(1), JsonPrimitive(true), JsonArray(listOf(JsonNull)),
            JsonArray(listOf(JsonPrimitive("synthetic"))), JsonArray(listOf(JsonArray(emptyList()))))
        invalid.forEach { metadata -> unsupported {
            ProtonChangeFeedParser.page(cursor, JsonObject(page() + ("CalendarURL" to metadata)))
        } }
        val boundary = JsonArray(List(10_000) { JsonObject(emptyMap()) })
        assertTrue(ProtonChangeFeedParser.page(cursor, JsonObject(page() + ("CalendarURL" to boundary))).changes.isEmpty())
        unsupported { ProtonChangeFeedParser.page(cursor, JsonObject(page() +
            ("CalendarURL" to JsonArray(boundary + JsonObject(emptyMap()))))) }
    }

    @Test fun calendarUrlMetadataCannotHideInvalidEventsOrUnknownRootCollections() {
        val invalidEvents = listOf("{}", """[{"ID":"same","Action":0},{"ID":"same","Action":0}]""",
            """[{"ID":"fixture-event","Action":3}]""",
            """[{"ID":"fixture-event","Action":2}]""",
            """[{"ID":"fixture-event","Action":2,"Event":{"ID":"other","CalendarID":"fixture-calendar"}}]""",
            """[{"ID":"fixture-event","Action":2,"Event":{"ID":"fixture-event","CalendarID":"foreign"}}]""")
        for (events in invalidEvents) unsupported {
            ProtonChangeFeedParser.page(cursor, JsonObject(page().toMutableMap().apply {
                put("CalendarURL", JsonArray(emptyList())); put("CalendarEvents", Json.parseToJsonElement(events))
            }))
        }
        unsupported { ProtonChangeFeedParser.page(cursor, JsonObject(page().toMutableMap().apply {
            put("CalendarURL", JsonArray(emptyList())); put("FutureCollection", JsonArray(emptyList()))
        })) }
        unsupported { ProtonChangeFeedParser.page(cursor, JsonObject(page(next = cursor.value, more = 1) +
            ("CalendarURL" to JsonArray(emptyList())))) }
    }

    @Test fun calendarUrlMetadataSharesTheExistingWholeResponseByteLimit() {
        val metadata = buildJsonArray { add(buildJsonObject {
            put("Opaque", "x".repeat(CalendarCatalogParser.MAX_RESPONSE_BYTES))
        }) }
        unsupported { ProtonChangeFeedParser.page(cursor, JsonObject(page() + ("CalendarURL" to metadata))) }
    }

    @Test fun calendarMemberNoticeIsAcceptedOnlyForItsCalendar() {
        val root = obj("""{"Code":1000,"CalendarModelEventID":"next","More":0,"Refresh":0,
            "CalendarMembers":[{"ID":"member","Action":2,
            "Member":{"ID":"member","CalendarID":"fixture-calendar"}}]}""")
        val change = ProtonChangeFeedParser.page(cursor, root).changes.single()
        assertEquals("CalendarMembers", change.collection)
        assertEquals(ProtonFeedAction.UPDATE, change.action)
        unsupported { ProtonChangeFeedParser.page(cursor.copy(scope = scope.copy(calendar = "other-calendar")), root) }
        unsupported { ProtonChangeFeedParser.page(cursor, obj("""{"Code":1000,"CalendarModelEventID":"next","More":0,"Refresh":0,
            "CalendarMembers":[{"ID":"member","Action":1,"Member":{"ID":"member"}}]}""")) }
        val deletion = ProtonChangeFeedParser.page(cursor, obj("""{"Code":1000,"CalendarModelEventID":"next","More":0,"Refresh":0,
            "CalendarMembers":[{"ID":"member","Action":0}]}""")).changes.single()
        assertEquals(ProtonFeedAction.DELETE, deletion.action)
        assertNull(deletion.payload)
    }

    @Test fun subscriptionContextPayloadUsesItsCalendarIdentityWithoutAnInventedIdField() {
        // Pinned CalendarSubscriptionEntity has CalendarID but no ID property.
        val root = obj("""{"Code":1000,"CalendarModelEventID":"next","More":0,"Refresh":0,
            "CalendarSubscriptions":[{"ID":"subscription-notice","Action":2,
                "CalendarSubscription":{"CalendarID":"fixture-calendar","CreateTime":1,"LastUpdateTime":2,"Status":0,"URL":"https://example.invalid/feed"}}]}""")
        val change = ProtonChangeFeedParser.page(cursor, root).changes.single()
        assertEquals("CalendarSubscriptions", change.collection)
        assertEquals("fixture-calendar", change.payload?.string("CalendarID"))
        unsupported { ProtonChangeFeedParser.page(cursor.copy(scope = scope.copy(calendar = "other")), root) }
    }

    @Test fun bareTombstoneIsScopedAndReplayHasStableProvenance() {
        val root = page("""[{"ID":"fixture-event","Action":0}]""")
        val parsed = ProtonChangeFeedParser.page(cursor, root)
        val deletion = parsed.changes.single()
        assertEquals(ProtonFeedAction.DELETE, deletion.action)
        assertNull(deletion.payload)
        assertEquals(deletion.provenance, ProtonChangeFeedParser.page(cursor, root).changes.single().provenance)
        assertNotEquals(deletion.provenance, ProtonChangeFeedParser.page(
            cursor.copy(scope = scope.copy(calendar = "other-calendar")), root).changes.single().provenance)
        assertNotEquals(deletion.provenance, ProtonChangeFeedParser.page(
            cursor.copy(scope = scope.copy(generation = 2)), root).changes.single().provenance)
        assertEquals("cursor-after", parsed.next.value)
    }
    @Test fun metadataKeepsRawEnvelopeAndRequiresCorrectIdentityAndCalendar() {
        val root = page("""[{"ID":"fixture-event","Action":2,"Event":{"ID":"fixture-event","CalendarID":"fixture-calendar","StartTime":0}}]""")
        val parsed = ProtonChangeFeedParser.page(cursor, root)
        assertEquals(root, parsed.envelope)
        assertEquals(ProtonFeedAction.UPDATE, parsed.changes.single().action)
        unsupported { ProtonChangeFeedParser.page(cursor.copy(scope = scope.copy(calendar = "foreign")), root) }
        unsupported { ProtonChangeFeedParser.page(cursor, page("""[{"ID":"fixture-event","Action":1,"Event":{"ID":"wrong","CalendarID":"fixture-calendar"}}]""")) }
    }
    @Test fun duplicateActionsAndUnknownActionFailClosedRatherThanGuessOrdering() {
        listOf("""[{"ID":"same","Action":0},{"ID":"same","Action":0}]""",
            """[{"ID":"fixture","Action":3}]""", """[{"ID":"fixture","Action":"0"}]""",
            """[{"ID":"fixture","Action":0,"Event":{"ID":"fixture"}}]""",
            """[{"ID":"fixture","Action":2}]""").forEach { rows ->
            unsupported { ProtonChangeFeedParser.page(cursor, page(rows)) }
        }
    }
    @Test fun refreshVariantsRequireReconciliationWithoutPublishingPartialDeletions() {
        listOf(1, 2, 4, 255, 256).forEach { refresh ->
            val parsed = ProtonChangeFeedParser.page(cursor, page("""[{"ID":"fixture","Action":0}]""", refresh = refresh))
            assertTrue(parsed.requiresReconciliation)
            assertTrue(parsed.changes.isEmpty())
            assertEquals(refresh.toLong(), parsed.refresh)
        }
    }
    @Test fun emptyPollingMayKeepCursorButMoreMustAdvance() {
        assertFalse(ProtonChangeFeedParser.page(cursor, page(next = cursor.value)).more)
        unsupported { ProtonChangeFeedParser.page(cursor, page(next = cursor.value, more = 1)) }
        unsupported { ProtonChangeFeedParser.page(cursor, page(more = 2)) }
        unsupported { ProtonChangeFeedParser.page(cursor, page(refresh = -1)) }
        unsupported { ProtonChangeFeedParser.page(cursor, obj("""{"Code":1000,"CalendarModelEventID":"next","More":false,"Refresh":0}""")) }
    }
    @Test fun globalAndCalendarCursorShapesRemainSeparateAndGlobalCoreDataIsPreserved() {
        val global = scope.copy(calendar = null)
        val root = obj("""{"Code":1000,"EventID":"global-next","More":0,"Refresh":0,"User":{"ID":"fixture-account"},"CalendarMembers":[{"ID":"member","Action":0}]}""")
        val result = ProtonChangeFeedParser.page(ProtonFeedCursor(global, "global-before"), root)
        assertEquals("CalendarMembers", result.changes.single().collection)
        assertNotNull(result.envelope["User"])
        unsupported { ProtonChangeFeedParser.page(cursor, root) }
        unsupported { ProtonChangeFeedParser.latest(global, obj("""{"Code":1000,"CalendarModelEventID":"wrong-kind"}""")) }
        unsupported { ProtonChangeFeedParser.page(cursor, JsonObject(page().toMutableMap().apply { put("FutureCollection", JsonArray(emptyList())) })) }
    }
    @Test fun malformedCollectionsIdentifiersAndCodeNeverBecomeEmptySuccess() {
        val malformed = listOf(
            """{"Code":1000,"CalendarModelEventID":"next","More":0,"Refresh":0,"CalendarEvents":{}}""",
            """{"Code":1000,"CalendarModelEventID":"","More":0,"Refresh":0}""",
            """{"Code":1000,"CalendarModelEventID":"next","More":0}""",
            """{"Code":1001,"CalendarModelEventID":"next","More":0,"Refresh":0}""",
        )
        malformed.forEach { unsupported { ProtonChangeFeedParser.page(cursor, obj(it)) } }
        unsupported { ProtonChangeFeedParser.latest(scope, obj("""{"Code":1000,"CalendarModelEventID":"line\nother"}""")) }
    }
    @Test fun fullEventFetchValidatesScopeAndRevisionAndPreservesEncryptedParts() {
        val root = obj("""{"Code":1000,"Event":{"ID":"fixture-event","CalendarID":"fixture-calendar","ModifyTime":42,"SharedEvents":[{"Data":"synthetic"}]}}""")
        assertNotNull(ProtonChangeFeedParser.event(scope, "fixture-event", root)["SharedEvents"])
        unsupported { ProtonChangeFeedParser.event(scope, "other", root) }
        unsupported { ProtonChangeFeedParser.event(scope.copy(calendar = "other"), "fixture-event", root) }
        unsupported { ProtonChangeFeedParser.event(scope.copy(calendar = null), "fixture-event", root) }
    }
    @Test fun sessionReplacementNeverPublishesFetchedFeedAndForeignAccountDoesNotFetch() = runTest {
        var current = session
        val transport = FakeTransport().apply { duringRequest = { current = session.copy(sessionId = SessionId("replacement")) } }
        val feed = ProtonChangeFeed(CatalogSessionSource { current }, transport)
        failure(ProtonFeedFailure.AUTHENTICATION) { feed.changes(cursor) }
        assertEquals(1, transport.calls)
        failure(ProtonFeedFailure.AUTHENTICATION) { feed.latest(scope.copy(account = "foreign")) }
        assertEquals(1, transport.calls)
    }
    @Test fun failuresRemainSanitizedAnd404IsNotATombstone() = runTest {
        val cases = listOf(
            IOException("private") to ProtonFeedFailure.NETWORK,
            ApiException(ApiResult.Error.Http(401, "private")) to ProtonFeedFailure.AUTHENTICATION,
            ApiException(ApiResult.Error.Http(403, "private")) to ProtonFeedFailure.FORBIDDEN,
            ApiException(ApiResult.Error.Http(404, "private")) to ProtonFeedFailure.NOT_FOUND,
            ApiException(ApiResult.Error.Http(429, "private", retryAfter = 7.seconds)) to ProtonFeedFailure.RETRY,
        )
        for ((error, expected) in cases) {
            val feed = ProtonChangeFeed(CatalogSessionSource { session }, FakeTransport().apply { duringRequest = { throw error } })
            val caught = failure(expected) { feed.event(scope, "fixture-event") }
            assertFalse(caught.toString().contains("private"))
            assertNull(caught.cause)
            if (expected == ProtonFeedFailure.RETRY) assertEquals(7000L, caught.retryAfterMillis)
        }
    }
    @Test fun cancellationPropagatesAndDebugStringsHideCursorScopeAndPayload() = runTest {
        val feed = ProtonChangeFeed(CatalogSessionSource { session }, FakeTransport().apply {
            duringRequest = { throw CancellationException("synthetic") }
        })
        try { feed.latest(scope); fail() } catch (_: CancellationException) { }
        val parsed = ProtonChangeFeedParser.page(cursor, page("""[{"ID":"fixture-event","Action":0}]"""))
        listOf(scope, cursor, parsed, parsed.changes.single()).forEach {
            assertFalse(it.toString().contains("fixture")); assertFalse(it.toString().contains("cursor-before"))
        }
    }
    private inner class FakeTransport : ProtonChangeFeedTransport {
        var calls = 0
        var duringRequest: () -> Unit = {}
        private fun before() { calls++; duringRequest() }
        override suspend fun latest(session: CatalogSession, scope: ProtonFeedScope): JsonObject { before(); return obj("""{"Code":1000,"CalendarModelEventID":"latest"}""") }
        override suspend fun changes(session: CatalogSession, cursor: ProtonFeedCursor): JsonObject { before(); return page() }
        override suspend fun event(session: CatalogSession, scope: ProtonFeedScope, eventId: String): JsonObject { before(); return obj("{}") }
    }
    private fun page(rows: String = "[]", next: String = "cursor-after", more: Int = 0, refresh: Int = 0) =
        obj("""{"Code":1000,"CalendarModelEventID":"$next","More":$more,"Refresh":$refresh,"CalendarEvents":$rows}""")
    private fun obj(value: String) = Json.parseToJsonElement(value) as JsonObject
    private fun unsupported(block: () -> Unit) {
        try { block(); fail("Expected unsupported feed") } catch (failure: ProtonFeedException) {
            assertEquals(ProtonFeedFailure.UNSUPPORTED, failure.reason)
        }
    }
    private suspend fun failure(expected: ProtonFeedFailure, block: suspend () -> Unit): ProtonFeedException {
        try { block(); fail("Expected feed failure") } catch (failure: ProtonFeedException) {
            assertEquals(expected, failure.reason); return failure
        }
        error("Unreachable")
    }
}
