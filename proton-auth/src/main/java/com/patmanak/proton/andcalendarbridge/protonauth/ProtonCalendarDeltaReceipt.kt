// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.util.Base64
import kotlinx.serialization.json.*

/** Delta cursors/envelopes cannot be mistaken for baseline window cursors or event receipts. */
internal class CalendarDeltaReceiptCodec(private val protector: EventPayloadProtector) {
    fun cursor(cursor: ProtonFeedCursor): OpaqueCursor {
        ProtonChangeFeedParser.validateCursor(cursor)
        if (cursor.scope.calendar == null) unsupportedEvents()
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(cursorJson(cursor).toString().toByteArray(Charsets.UTF_8))
        if (encoded.length > 32_768) unsupportedEvents()
        return OpaqueCursor(CALENDAR_DELTA_FORMAT, encoded)
    }
    fun readCursor(scope: CalendarScope, cursor: OpaqueCursor): ProtonFeedCursor {
        if (cursor.format != CALENDAR_DELTA_FORMAT || cursor.value.length > 32_768) unsupportedEvents()
        val json = try { Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(cursor.value), Charsets.UTF_8)) as? JsonObject }
            catch (_: Exception) { unsupportedEvents() }
        json ?: unsupportedEvents()
        if (json.number("version") != 1L || json.string("kind") != "calendar-delta-cursor" || json.obj("scope") != scope.feedScope().json()) unsupportedEvents()
        return ProtonFeedCursor(scope.feedScope(), json.string("cursor")).also(ProtonChangeFeedParser::validateCursor)
    }
    fun seal(page: ProtonFeedPage): String {
        val plain = buildJsonObject {
            put("version", 1); put("kind", "calendar-delta-page")
            put("after", cursorJson(page.requested)); put("envelope", page.envelope)
        }.toString()
        if (plain.length > ReceivedCalendarPage.MAX_PAYLOAD_CHARS) unsupportedEvents()
        return protector.seal(plain).also { if (it.isEmpty() || it.length > ReceivedCalendarPage.MAX_PAYLOAD_CHARS) unsupportedEvents() }
    }
    fun open(received: ReceivedChangePage): ProtonFeedPage {
        val requested = readCursor(received.scope, received.after)
        val plain = protector.open(received.protectedPayload)
        if (plain.length > ReceivedCalendarPage.MAX_PAYLOAD_CHARS) unsupportedEvents()
        val json = Json.parseToJsonElement(plain) as? JsonObject ?: unsupportedEvents()
        if (json.number("version") != 1L || json.string("kind") != "calendar-delta-page" || json.obj("after") != cursorJson(requested)) unsupportedEvents()
        return ProtonChangeFeedParser.page(requested, json.obj("envelope"))
    }
    private fun cursorJson(cursor: ProtonFeedCursor) = buildJsonObject {
        put("version", 1); put("kind", "calendar-delta-cursor")
        put("scope", cursor.scope.json()); put("cursor", cursor.value)
    }
    private fun ProtonFeedScope.json() = buildJsonObject {
        put("account", account); put("calendar", calendar); put("generation", generation)
    }
}
