// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.serialization.json.*

internal val OCCURRENCE_CREATE_SUBMISSION_FORMAT = AdapterFormat("proton-calendar-create-submission", 5)

/** Protected snapshot inspection only. It grants neither preparation nor dispatch authority. */
internal class ProtonOccurrenceSubmission(val intent: LocalCreateIntent, val context: JsonObject,
    val source: JsonObject, val request: JsonObject, val notificationDefaults: JsonElement) {
    override fun toString() = "ProtonOccurrenceSubmission(REDACTED)"
}

internal class ProtonOccurrenceSubmissionCodec(private val protector: EventPayloadProtector) {
    fun seal(id: MutationId, intent: LocalCreateIntent, context: JsonObject, source: JsonObject,
        request: JsonObject, notificationDefaults: JsonElement): String {
        val record = CreateAttemptRecord(id, intent.reservation.scope, OCCURRENCE_CREATE_SUBMISSION_FORMAT, "unsealed")
        val root = buildJsonObject {
            put("version", 5); put("kind", "first-personal-occurrence")
            put("binding", binding(record, intent)); put("context", context); put("source", source)
            put("request", request); put("notificationDefaults", notificationDefaults)
        }
        val plain = root.toString()
        validate(parse(plain), record, intent)
        return protector.seal(plain).also { bounded(it) }
    }

    /** Requires the independently retained capture; a bare child body cannot qualify this capsule.
     * UNKNOWN inspection is allowed, but never claims or sends work or changes its state. */
    fun open(record: CreateAttemptRecord, intent: LocalCreateIntent): ProtonOccurrenceSubmission {
        require(record.format == OCCURRENCE_CREATE_SUBMISSION_FORMAT)
        bounded(record.protectedPayload)
        return validate(parse(protector.open(record.protectedPayload)), record, intent)
    }
    private fun validate(root: JsonObject, record: CreateAttemptRecord, intent: LocalCreateIntent): ProtonOccurrenceSubmission {
        require(root.keys == setOf("version", "kind", "binding", "context", "source", "request", "notificationDefaults"))
        require(root["version"] == JsonPrimitive(5) && root.string("kind") == "first-personal-occurrence")
        require(root.obj("binding") == binding(record, intent))
        val candidate = requireNotNull(intent.occurrence)
        val context = root.obj("context"); val source = root.obj("source"); val request = root.obj("request")
        require(source.string("ID") == candidate.master.eventId.value && source.string("CalendarID") == record.scope.calendar.value &&
            source.string("UID") == candidate.master.content.recurrence!!.uid)
        require(context.string("member").isNotBlank() && request.string("MemberID") == context.string("member"))
        require(request.keys == setOf("MemberID", "IsImport", "Events") && request["IsImport"] == JsonPrimitive(0))
        val operation = request.array("Events").single() as JsonObject
        require(operation.keys == setOf("Overwrite", "Event") && operation["Overwrite"] == JsonPrimitive(0))
        val event = operation.obj("Event")
        val required = setOf("SharedKeyPacket", "SharedEventContent", "Notifications", "Color", "IsPersonalSingleEdit")
        require(event.keys.containsAll(required) && event.keys.all { it in required || it in
            setOf("Permissions", "IsOrganizer", "CalendarEventContent", "CalendarKeyPacket") })
        require(event.string("SharedKeyPacket").isNotBlank() && event.array("SharedEventContent").isNotEmpty())
        require(event["IsPersonalSingleEdit"] in listOf(JsonPrimitive(true), JsonPrimitive(false)))
        val defaults = requireNotNull(root["notificationDefaults"])
        require(defaults == JsonNull || defaults is JsonArray)
        return ProtonOccurrenceSubmission(intent.copy(), context, source, request, defaults)
    }
    private fun binding(record: CreateAttemptRecord, intent: LocalCreateIntent): JsonObject {
        val link = CapturedCreateDispatchIdentity.ofOccurrence(record, intent)
        return buildJsonObject {
            put("account", record.scope.account.value); put("calendar", record.scope.calendar.value)
            put("generation", record.scope.generation); put("attempt", record.id.value)
            put("capture", link.captureId.value); put("local", link.localId.value)
            put("adapter", link.captureFormat.id); put("adapterVersion", link.captureFormat.version)
            put("timestamp", link.timestamp.toString()); put("fingerprintVersion", 3); put("fingerprint", link.contentFingerprint)
        }
    }
    private fun parse(plain: String): JsonObject {
        bounded(plain); require(UniqueJsonKeys(plain).validate())
        return Json.parseToJsonElement(plain) as JsonObject
    }
    private fun bounded(value: String) {
        require(value.isNotBlank() && value.length <= 4_194_304 && value.toByteArray(Charsets.UTF_8).size <= 4_194_304)
    }
}
