// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.*
import kotlinx.serialization.json.*

internal val CREATE_SUBMISSION_FORMAT = AdapterFormat("proton-calendar-create-submission", 1)
internal val KNOWN_EMPTY_SUBMISSION_FORMAT = AdapterFormat("proton-calendar-create-submission", 2)
internal val PERSONAL_CREATE_SUBMISSION_FORMAT = AdapterFormat("proton-calendar-create-submission", 3)
internal class ProtonCreateSubmission(val draft: CalendarCreateDraft, val uid: String,
    val context: JsonObject, val request: JsonObject, val policy: CreatedEventReadbackPolicy) {
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
        if (personal(content)) {
            val frozen = freezePersonal(content)
            put("personalDetails", true)
            put("reminders", JsonArray(frozen.details!!.reminders.map { JsonPrimitive(it.minutesBefore) }))
            put("color", frozen.colorRgb?.let(::JsonPrimitive) ?: JsonNull)
        } else if (content.details == EventDetails()) put("knownEmptyDetails", true)
        when (val time = content.time) {
            is EventTime.AllDay -> { put("type", "date"); put("start", time.start.toString()); put("end", time.endExclusive.toString()) }
            is EventTime.Timed -> { put("type", "timed"); put("start", time.start.toString()); put("end", time.end.toString()); put("zone", time.zone.id) }
        }
    }
    fun open(record: CreateAttemptRecord, protector: EventPayloadProtector): ProtonCreateSubmission {
        val policy = when (record.format) {
            CREATE_SUBMISSION_FORMAT -> CreatedEventReadbackPolicy.SHARED_ONLY_V1
            KNOWN_EMPTY_SUBMISSION_FORMAT -> CreatedEventReadbackPolicy.KNOWN_EMPTY_V2
            PERSONAL_CREATE_SUBMISSION_FORMAT -> CreatedEventReadbackPolicy.PERSONAL_CREATE_V3
            else -> createUnavailable(CreatePreparationFailure.UNSUPPORTED)
        }
        val source = protector.open(record.protectedPayload)
        if (!UniqueJsonKeys(source).validate()) createUnavailable(CreatePreparationFailure.CONTEXT)
        val root = Json.parseToJsonElement(source) as JsonObject
        if (root.number("version") != record.format.version.toLong() || root.string("account") != record.scope.account.value ||
            root.string("calendar") != record.scope.calendar.value || root.number("generation") != record.scope.generation ||
            root.string("correlation") != record.id.value) createUnavailable(CreatePreparationFailure.CONTEXT)
        val content = root.obj("expected")
        val knownEmpty = policy == CreatedEventReadbackPolicy.KNOWN_EMPTY_V2
        val personal = policy == CreatedEventReadbackPolicy.PERSONAL_CREATE_V3
        if (knownEmpty && (root.string("notificationPolicy") != "EXPLICIT_EMPTY" ||
            content["knownEmptyDetails"] != JsonPrimitive(true))) createUnavailable(CreatePreparationFailure.CONTEXT)
        if (personal && (root.string("notificationPolicy") != "EXPLICIT_PERSONAL" ||
            content["personalDetails"] != JsonPrimitive(true))) createUnavailable(CreatePreparationFailure.CONTEXT)
        val time = when (content.string("type")) {
            "date" -> EventTime.AllDay(LocalDate.parse(content.string("start")), LocalDate.parse(content.string("end")))
            "timed" -> EventTime.Timed(Instant.parse(content.string("start")), Instant.parse(content.string("end")), ZoneId.of(content.string("zone")))
            else -> createUnavailable(CreatePreparationFailure.UNSUPPORTED)
        }
        val draft = CalendarCreateDraft(record.scope, record.id, Instant.parse(root.string("timestamp")),
            EventContent(content.string("title"), time, content.string("description"), content.string("location"),
                details = if (personal) EventDetails(content.array("reminders").map {
                    val value = it as? JsonPrimitive ?: createUnavailable(CreatePreparationFailure.CONTEXT)
                    if (value.isString) createUnavailable(CreatePreparationFailure.CONTEXT)
                    EventReminder(value.int)
                }) else if (knownEmpty) EventDetails() else null,
                colorRgb = if (personal && content["color"] != JsonNull) content.number("color").let {
                    if (it !in 0L..0xffffffL) createUnavailable(CreatePreparationFailure.CONTEXT)
                    it.toInt()
                } else null))
        val uid = root.string("uid")
        if (personal) freezePersonal(draft.content)
        ProtonSimpleEventSerializer.serialize(uid, draft.timestamp, if (personal) sharedContent(draft.content) else draft.content)
        val context = root.obj("context"); val request = root.obj("request")
        val event = request.array("Events").single() as JsonObject
        val rootKeys = setOf("version", "account", "calendar", "generation", "correlation", "uid", "timestamp", "context", "request", "expected") +
            if (knownEmpty || personal) setOf("notificationPolicy") else emptySet()
        val eventKeys = setOf("Permissions", "IsOrganizer", "SharedKeyPacket", "SharedEventContent") +
            if (personal) setOf("Notifications", "Color") else if (knownEmpty) setOf("Notifications") else emptySet()
        if (root.keys != rootKeys || content != expected(draft.content) || !policy.acceptsExpected(draft.content) ||
            request.keys != setOf("MemberID", "IsImport", "Events") || request.string("MemberID") != context.string("member") ||
            request.number("IsImport") != 0L || event.number("Overwrite") != 0L || event.keys != setOf("Overwrite", "Event") ||
            event.obj("Event").keys != eventKeys || !policy.accepts(event.obj("Event")))
            createUnavailable(CreatePreparationFailure.CONTEXT)
        if (personal) {
            val metadata = personalEvent(buildJsonObject {}, draft.content)
            if (event.obj("Event")["Notifications"] != metadata["Notifications"] ||
                event.obj("Event")["Color"] != metadata["Color"]) createUnavailable(CreatePreparationFailure.CONTEXT)
        }
        return ProtonCreateSubmission(draft, uid, context, request, policy)
    }

    fun personal(content: EventContent): Boolean = content.details != null &&
        (content.details != EventDetails() || content.colorRgb != null)

    /** Freeze and revalidate caller-owned lists before stripping personal siblings for shared crypto. */
    fun freezePersonal(content: EventContent): EventContent {
        val details = requireNotNull(content.details)
        val frozen = details.copy(reminders = java.util.Collections.unmodifiableList(details.reminders.map { EventReminder(it.minutesBefore) }),
            attendees = java.util.Collections.unmodifiableList(details.attendees.toList()))
        require(frozen.organizer == null && frozen.attendees.isEmpty() && content.recurrence == null)
        require(content.colorRgb?.let { it in 0..0xffffff } != false)
        val time = content.time
        require(time !is EventTime.Timed || (time.start.nano == 0 && time.end.nano == 0))
        return content.copy(details = frozen)
    }

    fun sharedContent(content: EventContent): EventContent = content.copy(details = EventDetails(), colorRgb = null)

    /** New CREATE profile only: no inherited notifications, email delivery or implicit calendar defaults. */
    fun personalEvent(shared: JsonObject, content: EventContent): JsonObject {
        val frozen = freezePersonal(content)
        val notifications = shared["Notifications"]
        if ((notifications != null && (notifications !is JsonArray || notifications.isNotEmpty())) ||
            (shared["Color"] != null && shared["Color"] != JsonNull)) createUnavailable(CreatePreparationFailure.CONTEXT)
        val empty = JsonObject(shared + mapOf("Notifications" to JsonArray(emptyList()), "Color" to JsonNull))
        return ProtonPersonalMetadataEdit.apply(empty, sharedContent(frozen), frozen)
    }
}
