// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.*
import kotlinx.serialization.json.*

internal val CREATE_SUBMISSION_FORMAT = AdapterFormat("proton-calendar-create-submission", 1)
internal class ProtonCreateSubmission(val draft: CalendarCreateDraft, val uid: String,
    val context: JsonObject, val request: JsonObject) {
    override fun toString() = "ProtonCreateSubmission(redacted)"
}

internal object ProtonCreateSubmissionCodec {
    fun context(selection: ProtonCreateSelection) = buildJsonObject {
        put("member", selection.member.string("ID")); put("address", selection.address.addressId.id)
        put("permissions", selection.member.number("Permissions")); put("flags", selection.member.number("Flags"))
        put("key", selection.calendarKey.string("ID")); put("passphrase", selection.calendarKey.string("PassphraseID"))
        // Fingerprints qualify the frozen request's keys without retaining another private key copy.
        fun digest(value: String) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        put("calendarKey", digest(selection.calendarKey.string("PrivateKey")))
        put("signingKey", digest(selection.signingKey.key))
    }
    fun request(member: String, event: JsonObject) = buildJsonObject {
        put("MemberID", member); put("IsImport", 0)
        putJsonArray("Events") { add(buildJsonObject { put("Overwrite", 0); put("Event", event) }) }
    }
    fun expected(content: EventContent) = buildJsonObject {
        put("title", content.title.replace("\r\n", "\n")); put("description", content.description.replace("\r\n", "\n"))
        put("location", content.location.replace("\r\n", "\n"))
        when (val time = content.time) {
            is EventTime.AllDay -> { put("type", "date"); put("start", time.start.toString()); put("end", time.endExclusive.toString()) }
            is EventTime.Timed -> { put("type", "timed"); put("start", time.start.toString()); put("end", time.end.toString()); put("zone", time.zone.id) }
        }
    }
    fun open(record: CreateAttemptRecord, protector: EventPayloadProtector): ProtonCreateSubmission {
        if (record.format != CREATE_SUBMISSION_FORMAT) createUnavailable(CreatePreparationFailure.UNSUPPORTED)
        val root = Json.parseToJsonElement(protector.open(record.protectedPayload)) as JsonObject
        if (root.number("version") != 1L || root.string("account") != record.scope.account.value ||
            root.string("calendar") != record.scope.calendar.value || root.number("generation") != record.scope.generation ||
            root.string("correlation") != record.id.value) createUnavailable(CreatePreparationFailure.CONTEXT)
        val content = root.obj("expected")
        val time = when (content.string("type")) {
            "date" -> EventTime.AllDay(LocalDate.parse(content.string("start")), LocalDate.parse(content.string("end")))
            "timed" -> EventTime.Timed(Instant.parse(content.string("start")), Instant.parse(content.string("end")), ZoneId.of(content.string("zone")))
            else -> createUnavailable(CreatePreparationFailure.UNSUPPORTED)
        }
        val draft = CalendarCreateDraft(record.scope, record.id, Instant.parse(root.string("timestamp")),
            EventContent(content.string("title"), time, content.string("description"), content.string("location")))
        val uid = root.string("uid")
        ProtonSimpleEventSerializer.serialize(uid, draft.timestamp, draft.content)
        val context = root.obj("context"); val request = root.obj("request")
        val event = request.array("Events").single() as JsonObject
        if (root.keys != setOf("version", "account", "calendar", "generation", "correlation", "uid", "timestamp", "context", "request", "expected") ||
            request.keys != setOf("MemberID", "IsImport", "Events") || request.string("MemberID") != context.string("member") ||
            request.number("IsImport") != 0L || event.number("Overwrite") != 0L || event.keys != setOf("Overwrite", "Event") ||
            event.obj("Event").keys != setOf("Permissions", "IsOrganizer", "SharedKeyPacket", "SharedEventContent"))
            createUnavailable(CreatePreparationFailure.CONTEXT)
        return ProtonCreateSubmission(draft, uid, context, request)
    }
}
