// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.serialization.json.*
import me.proton.core.crypto.common.keystore.KeyStoreCrypto

/** Production implementations must authenticate and encrypt; plaintext is allowed only in tests. */
internal interface EventPayloadProtector {
    fun seal(plain: String): String
    fun open(protected: String): String
}

internal class ProtonEventPayloadProtector(private val keyStore: KeyStoreCrypto) : EventPayloadProtector {
    override fun seal(plain: String): String {
        GateCProtectedStorageGuard.requireAvailable(keyStore)
        return keyStore.encrypt(plain)
    }
    override fun open(protected: String): String {
        GateCProtectedStorageGuard.requireAvailable(keyStore)
        return keyStore.decrypt(protected)
    }
}

/** Version is independent of network cursors and mapper revisions. Old evidence fails closed. */
internal class EventReceiptCodec(private val protector: EventPayloadProtector) {
    fun sealPage(request: PageRequest, page: EncryptedEventPage, bootstrap: JsonObject?, window: EventWindow): String =
        seal(buildJsonObject {
            header("page", request.checkpoint.scope)
            put("request", request.json())
            put("window", window.encode(request.checkpoint.scope).value)
            put("more", page.more)
            put("rows", JsonArray(page.rows))
            put("bootstrap", bootstrap ?: JsonNull)
        })

    fun openPage(received: ReceivedCalendarPage): PageEvidence {
        val json = open(received.protectedPayload, "page", received.request.checkpoint.scope)
        if (json.obj("request") != received.request.json()) unsupportedEvents()
        val rows = json.array("rows").map { it as? JsonObject ?: unsupportedEvents() }
        val more = (json["more"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: unsupportedEvents()
        val bootstrap = if (json["bootstrap"] == JsonNull) null else json.obj("bootstrap")
        return PageEvidence(EncryptedEventPage(rows, more), bootstrap, json.string("window"))
    }

    fun sealEvent(scope: CalendarScope, row: JsonObject, bootstrap: JsonObject): String = seal(buildJsonObject {
        header("event", scope)
        put("row", row)
        put("bootstrap", bootstrap)
    })

    fun openEvent(scope: CalendarScope, event: DeferredCalendarEvent): Pair<JsonObject, JsonObject> {
        if (event.format != EVENT_FORMAT) unsupportedEvents()
        val json = open(event.protectedPayload, "event", scope)
        val row = json.obj("row")
        val bootstrap = json.obj("bootstrap")
        if (row.string("ID") != event.eventId.value ||
            event.revision !in listOf(row.revision(bootstrap), row.legacyRevision())) unsupportedEvents()
        return row to bootstrap
    }

    private fun JsonObjectBuilder.header(kind: String, scope: CalendarScope) {
        put("version", 1)
        put("kind", kind)
        put("scope", scope.json())
        put("adapter", "proton-calendar-window")
        put("adapterVersion", 1)
    }
    private fun seal(json: JsonObject): String {
        val plain = json.toString()
        if (plain.length > ReceivedCalendarPage.MAX_PAYLOAD_CHARS) unsupportedEvents()
        return protector.seal(plain).also {
            if (it.isEmpty() || it.length > ReceivedCalendarPage.MAX_PAYLOAD_CHARS) unsupportedEvents()
        }
    }
    private fun open(payload: String, kind: String, scope: CalendarScope): JsonObject {
        if (payload.isEmpty() || payload.length > ReceivedCalendarPage.MAX_PAYLOAD_CHARS) unsupportedEvents()
        val plain = protector.open(payload)
        if (plain.length > ReceivedCalendarPage.MAX_PAYLOAD_CHARS) unsupportedEvents()
        val json = Json.parseToJsonElement(plain) as? JsonObject ?: unsupportedEvents()
        if (json.number("version") != 1L || json.string("kind") != kind || json.obj("scope") != scope.json() ||
            json.string("adapter") != "proton-calendar-window" || json.number("adapterVersion") != 1L) unsupportedEvents()
        return json
    }
    private fun CalendarScope.json() = buildJsonObject {
        put("account", account.value); put("calendar", calendar.value); put("generation", generation)
    }
    private fun PageRequest.json() = buildJsonObject {
        put("scope", checkpoint.scope.json())
        put("limit", limit)
        put("cursor", checkpoint.cursor?.value?.let(::JsonPrimitive) ?: JsonNull)
        put("complete", checkpoint.traversalComplete)
    }
}

internal data class PageEvidence(val page: EncryptedEventPage, val bootstrap: JsonObject?, val window: String) {
    override fun toString() = "PageEvidence(REDACTED)"
}

internal data class DeferredEventContent(val eventId: EventId, val revision: Revision, val reason: DeferredEventReason)
internal data class EventDecodeResult(val changes: List<RemoteChange.Upsert>, val deferred: List<DeferredEventContent>)
internal fun interface EventImportDecoder {
    /** Must not have a network fallback. Missing protected local keys block the entire page. */
    suspend fun decode(session: CatalogSession, calendar: String, bootstrap: JsonObject, rows: List<JsonObject>): EventDecodeResult
}

internal class UnverifiedEventContent : Exception("UNVERIFIED_CALENDAR_CONTENT")
