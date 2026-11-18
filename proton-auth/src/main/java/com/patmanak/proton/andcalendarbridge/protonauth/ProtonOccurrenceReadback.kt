// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.ZoneOffset
import kotlinx.serialization.json.*

internal fun interface OccurrenceChildVerifier {
    suspend fun verify(session: CatalogSession, calendar: String, bootstrap: JsonObject, row: JsonObject, uid: String): RemoteChange.Upsert?
}

internal fun occurrenceChildVerifier(decoder: ProtonEventDecryptor) = OccurrenceChildVerifier { session, calendar, bootstrap, row, uid ->
    val decoded = decoder.decodePage(session.userId, calendar, bootstrap, listOf(row), uid)
    decoded.changes.singleOrNull()?.takeIf { decoded.deferred.isEmpty() }
}

/** Compare the signed packets of this specific attempt. Two edits of the same slot can have identical visible fields. */
internal fun qualifyOccurrenceChild(row: JsonObject, retained: ProtonOccurrenceSubmission) {
    val candidate = requireNotNull(retained.intent.occurrence)
    val event = retained.request.array("Events").single().jsonObject.obj("Event")
    val slot = when (val original = candidate.original) {
        is RecurrencePoint.Timed -> original.instant.epochSecond
        is RecurrencePoint.AllDay -> original.date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
    }
    require(row.number("RecurrenceID") == slot && row["IsPersonalSingleEdit"] == event.getValue("IsPersonalSingleEdit"))
    // Only these two fields differ from the closed personal GET profile.
    ProtonPersonalEventWire.qualify(JsonObject(row + mapOf("RecurrenceID" to JsonNull, "IsPersonalSingleEdit" to JsonPrimitive(false))))
    require(ProtonPersonalEventUpdate.metadata(candidate.content.time).all { (key, value) -> row[key] == value })
    require(listOf("SharedKeyPacket", "Notifications", "Color", "IsOrganizer").all { row[it] == event.getValue(it) })
    fun matchParts(requestKey: String, observedKey: String) {
        val expected = (event[requestKey] as? JsonArray).orEmpty().map { it.jsonObject }.sortedBy { it.number("Type") }
        val actual = row.array(observedKey).map { it.jsonObject }.sortedBy { it.number("Type") }
        require(expected.size == actual.size)
        for ((sent, observed) in expected.zip(actual)) {
            require(sent.string("Author").isEmpty() && observed.string("Author").isNotBlank())
            require((sent - "Author") == (observed - "Author"))
        }
    }
    matchParts("SharedEventContent", "SharedEvents")
    matchParts("CalendarEventContent", "CalendarEvents")
}
