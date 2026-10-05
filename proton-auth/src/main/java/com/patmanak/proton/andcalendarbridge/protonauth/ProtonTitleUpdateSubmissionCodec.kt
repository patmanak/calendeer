// SPDX-License-Identifier: GPL-3.0-only
// Same-calendar UPDATE input adaptation from Proton WebClients; see PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.serialization.json.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal val TITLE_UPDATE_SUBMISSION_FORMAT = AdapterFormat("proton-calendar-title-update-submission", 1)
internal class ProtonTitleUpdateSubmission(val fragments: ProtonTimestampedSharedTitle, val context: JsonObject,
    val request: JsonObject) { override fun toString() = "ProtonTitleUpdateSubmission(redacted)" }

internal fun titleUpdateOnlyBytes(payload: String): ByteArray {
    try {
        require(payload.length <= LocalEditPreparation.MAX_PAYLOAD_BYTES && UniqueJsonKeys(payload).validate())
        val root = Json.parseToJsonElement(payload) as JsonObject
        require(root.keys == setOf("MemberID", "IsImport", "Events") && root.string("MemberID").isNotBlank() && root.number("IsImport") == 0L)
        val operation = root.array("Events").single() as JsonObject
        require(operation.keys == setOf("ID", "Event") && operation.string("ID").isNotBlank())
        val event = operation.obj("Event")
        require(event.keys == setOf("Permissions", "IsOrganizer", "IsBreakingChange", "IsPersonalSingleEdit", "Notifications", "Color", "VideoConferencingData", "SharedEventContent"))
        require(event.number("Permissions") == 1L && event.number("IsOrganizer") == 1L && event.number("IsBreakingChange") == 0L &&
            event["IsPersonalSingleEdit"] == JsonPrimitive(false) && event.array("Notifications").isEmpty() &&
            event["Color"] == JsonNull && event["VideoConferencingData"] == JsonNull)
        val parts = event.array("SharedEventContent").map { it as JsonObject }
        require(parts.map { it.number("Type") }.sorted() == listOf(2L, 3L))
        parts.forEach { require(it.keys == setOf("Type", "Data", "Signature") && it.string("Data").isNotBlank() && it.string("Signature").isNotBlank()) }
        val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(payload))
        require(bytes.remaining() <= LocalEditPreparation.MAX_PAYLOAD_BYTES)
        return ByteArray(bytes.remaining()).also(bytes::get)
    } catch (_: Exception) { throw ProtonSingleDispatchDenied() }
}

/** Closed first experiment: future GET fields or nonempty personal/invitation contexts cannot be dropped. */
internal object ProtonTitleUpdateProfile {
    private val fields = setOf("ID", "CalendarID", "SharedEventID", "CalendarKeyPacket", "CreateTime", "ModifyTime", "Permissions",
        "AddressKeyPacket", "AddressID", "SharedKeyPacket", "SharedEvents", "CalendarEvents", "AttendeesEvents", "Attendees",
        "AttendeesInfo", "IsProtonProtonInvite", "Notifications", "Color", "StartTime", "StartTimezone", "EndTime", "EndTimezone",
        "FullDay", "UID", "RecurrenceID", "Exdates", "RRule", "IsOrganizer", "IsPersonalSingleEdit", "VideoConferencingData",
        "LastEditTime", "NotificationOccurrences")
    fun qualify(row: JsonObject) {
        require(row.keys.all { it in fields })
        listOf("ID", "CalendarID", "UID", "SharedKeyPacket").forEach { require(row.string(it).isNotBlank()) }
        // GET attendee bitmap SEE or SEE_AND_INVITE, unrelated to calendar WRITE=16.
        // Upstream UPDATE still writes its default SEE=1. Exact GET bitmap must survive readback.
        require(row.number("Permissions") in setOf(1L, 3L) && row.number("IsOrganizer") == 1L && row["IsPersonalSingleEdit"] == JsonPrimitive(false))
        listOf("Notifications", "CalendarEvents", "AttendeesEvents", "Exdates").forEach { require(row.array(it).isEmpty()) }
        // Observed GET-only extensions, not a claimed upstream write contract. Compare their explicit readback rules below.
        if ("LastEditTime" in row) require(row.number("LastEditTime") >= 0)
        if ("NotificationOccurrences" in row) require(row.array("NotificationOccurrences").isEmpty())
        listOf("Color", "VideoConferencingData", "RRule", "RecurrenceID", "AddressKeyPacket", "CalendarKeyPacket").forEach { require(row[it] == null || row[it] == JsonNull) }
        require(row["IsProtonProtonInvite"] == null || row["IsProtonProtonInvite"] == JsonNull || row.number("IsProtonProtonInvite") == 0L)
        if (row["Attendees"] != null && row["Attendees"] != JsonNull) require(row.array("Attendees").isEmpty())
        if (row["AttendeesInfo"] != null && row["AttendeesInfo"] != JsonNull) {
            val info = row.obj("AttendeesInfo")
            require(info.keys == setOf("Attendees", "MoreAttendees") || info.keys == setOf("Attendees", "MoreAttendees", "Code"))
            require(info.array("Attendees").isEmpty() && info.number("MoreAttendees") == 0L)
            if ("Code" in info) require(info.number("Code") == 1000L)
        }
        require(row["Attendees"] is JsonArray || row["AttendeesInfo"] is JsonObject)
        require(row.number("CreateTime") >= 0 && row.number("ModifyTime") >= row.number("CreateTime"))
        require(row.number("FullDay") in 0L..1L && row.number("EndTime") >= row.number("StartTime"))
        row.string("StartTimezone"); row.string("EndTimezone")
        val parts = row.array("SharedEvents").map { it as JsonObject }
        require(parts.map { it.number("Type") }.sorted() == listOf(2L, 3L))
        parts.forEach { require(it.keys == setOf("Type", "Data", "Signature", "Author")); listOf("Data", "Signature", "Author").forEach { name -> require(it.string(name).isNotBlank()) } }
    }
    fun matchesReadback(original: JsonObject, fragments: ProtonTimestampedSharedTitle, actual: JsonObject): Boolean = try {
        qualify(actual)
        val expectedParts = listOf(fragments.clearSharedPart, fragments.encryptedSharedPart).associateBy { it.number("Type") }
        val actualParts = actual.array("SharedEvents").map { it as JsonObject }.associateBy { it.number("Type") }
        actualParts == expectedParts && actual.number("ModifyTime") >= original.number("ModifyTime") &&
            serverEditClockMatches(original, actual) &&
            JsonObject(actual - setOf("ModifyTime", "LastEditTime", "SharedEvents")) ==
                JsonObject(original - setOf("ModifyTime", "LastEditTime", "SharedEvents"))
    } catch (_: Exception) { false }

    /** Narrow measured response transformation, not a revision precondition or a generic ignored field. */
    private fun serverEditClockMatches(original: JsonObject, actual: JsonObject): Boolean {
        if (("LastEditTime" in original) != ("LastEditTime" in actual)) return false
        if ("LastEditTime" !in original) return true
        val before = original.number("LastEditTime")
        val after = actual.number("LastEditTime")
        if (before < 0 || after < 0) return false
        return after == before || (after >= before && after == actual.number("ModifyTime"))
    }
}

internal class ProtonTitleUpdateSubmissionCodec(private val protector: EventPayloadProtector) {
    fun seal(id: MutationId, preparation: LocalEditPreparation, context: JsonObject): TitleUpdateAttemptRecord {
        val frozen = preparation.copy(intent = LocalEditPreparation.freezeIntent(preparation.intent))
        val fragments = ProtonTitlePreparationCodec(protector).openLocal(frozen)
        val request = request(fragments, context)
        val root = envelope(id, frozen, context, request)
        return TitleUpdateAttemptRecord(id, frozen, TITLE_UPDATE_SUBMISSION_FORMAT, protector.seal(root.toString()))
    }
    fun open(record: TitleUpdateAttemptRecord): ProtonTitleUpdateSubmission {
        val frozen = record.frozen()
        require(frozen.format == TITLE_UPDATE_SUBMISSION_FORMAT)
        val fragments = ProtonTitlePreparationCodec(protector).openLocal(frozen.preparation)
        val plain = protector.open(frozen.protectedPayload)
        require(plain.length <= LocalEditPreparation.MAX_PAYLOAD_BYTES && UniqueJsonKeys(plain).validate())
        val root = Json.parseToJsonElement(plain) as JsonObject
        val context = root.obj("context")
        val request = request(fragments, context)
        require(root == envelope(frozen.id, frozen.preparation, context, request))
        return ProtonTitleUpdateSubmission(fragments, context, request)
    }
    private fun request(fragments: ProtonTimestampedSharedTitle, context: JsonObject): JsonObject {
        require(fragments.changed)
        ProtonTitleUpdateProfile.qualify(fragments.originalRow)
        require(context.keys == CONTEXT_KEYS && context.string("member").isNotBlank())
        listOf(fragments.clearSharedPart, fragments.encryptedSharedPart).forEach {
            require(it.keys == setOf("Type", "Data", "Signature", "Author") && it.string("Author").equals(context.string("author"), ignoreCase = true))
        }
        return buildJsonObject {
            put("MemberID", context.string("member")); put("IsImport", 0)
            putJsonArray("Events") { add(buildJsonObject {
                put("ID", fragments.originalRow.string("ID")); putJsonObject("Event") {
                    put("Permissions", 1); put("IsOrganizer", 1); put("IsBreakingChange", 0); put("IsPersonalSingleEdit", false)
                    put("Notifications", JsonArray(emptyList())); put("Color", JsonNull); put("VideoConferencingData", JsonNull)
                    put("SharedEventContent", JsonArray(listOf(fragments.clearSharedPart, fragments.encryptedSharedPart).map { JsonObject(it - "Author") }))
                }
            }) }
        }.also { titleUpdateOnlyBytes(it.toString()).fill(0) }
    }
    private fun envelope(id: MutationId, preparation: LocalEditPreparation, context: JsonObject, request: JsonObject) = buildJsonObject {
        put("kind", "title-update-submission"); put("version", 1); put("attempt", id.value)
        put("preparation", hash(preparation.protectedPayload)); put("context", context); put("request", request)
    }
    companion object {
        val CONTEXT_KEYS = setOf("member", "address", "permissions", "flags", "key", "passphrase", "calendarKey", "signingKey", "author", "calendar", "bootstrap")
        fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun context(selection: ProtonCreateSelection, calendar: JsonObject, bootstrap: JsonObject) = JsonObject(
            ProtonCreateSubmissionCodec.context(selection) + mapOf("author" to JsonPrimitive(selection.address.email),
                "calendar" to JsonPrimitive(hash(calendar.toString())), "bootstrap" to JsonPrimitive(hash(bootstrap.toString()))))
    }
}
