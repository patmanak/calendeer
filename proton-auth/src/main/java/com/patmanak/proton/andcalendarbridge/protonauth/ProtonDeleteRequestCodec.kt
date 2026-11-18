// SPDX-License-Identifier: GPL-3.0-only
// Same-calendar DELETE shape adapted from pinned Proton sources; see PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal val DELETE_SUBMISSION_FORMAT = AdapterFormat("proton-calendar-delete-request", 1)

/** Historical request only; opening does not grant access or establish signatures or absence. */
internal class ProtonDeleteRequest(context: JsonObject, originalRow: JsonObject, request: JsonObject) {
    private val contextText = context.toString()
    private val originalText = originalRow.toString()
    private val requestText = request.toString()
    val context get() = Json.parseToJsonElement(contextText) as JsonObject
    val originalRow get() = Json.parseToJsonElement(originalText) as JsonObject
    val request get() = Json.parseToJsonElement(requestText) as JsonObject
    override fun toString() = "ProtonDeleteRequest(redacted)"
}

internal class ProtonDeleteRequestCodec(private val protector: EventPayloadProtector) {
    suspend fun seal(id: MutationId, intent: LocalDeleteIntent, context: JsonObject, original: JsonObject): DeleteAttemptRecord {
        val frozen = DeleteSubmissionPolicy.freezeIntent(intent)
        require(frozen.format == EVENT_FORMAT)
        val qualified = deleteSnapshot(context).also(::validateDeleteContext)
        val row = deleteSnapshot(original).also { validateRow(frozen, it) }
        val plain = envelope(id, frozen, qualified, row).toString().also { deleteBounded(it) }
        currentCoroutineContext().ensureActive()
        val protected = protector.seal(plain)
        currentCoroutineContext().ensureActive()
        return DeleteAttemptRecord(id, frozen, DELETE_SUBMISSION_FORMAT, protected)
    }
    suspend fun open(record: DeleteAttemptRecord): ProtonDeleteRequest {
        val frozen = record.frozen()
        require(frozen.format == DELETE_SUBMISSION_FORMAT && frozen.intent.format == EVENT_FORMAT)
        currentCoroutineContext().ensureActive()
        val plain = protector.open(frozen.protectedPayload)
        currentCoroutineContext().ensureActive()
        deleteBounded(plain); require(UniqueJsonKeys(plain).validate())
        val root = Json.parseToJsonElement(plain) as JsonObject
        deleteValidateJson(root)
        val context = root.obj("context").also(::validateDeleteContext)
        val original = root.obj("original").also { validateRow(frozen.intent, it) }
        require(root == envelope(frozen.id, frozen.intent, context, original))
        return ProtonDeleteRequest(context, original, request(frozen.eventId, context))
    }
    private fun validateRow(intent: LocalDeleteIntent, row: JsonObject) {
        ProtonSimpleEventUpdate.qualify(row)
        require(row.string("ID") == intent.base.eventId.value && row.string("CalendarID") == intent.scope.calendar.value)
    }
    private fun envelope(id: MutationId, intent: LocalDeleteIntent, context: JsonObject, original: JsonObject) = buildJsonObject {
        put("kind", "delete-request"); put("version", 1); put("requestId", id.value)
        put("intent", DeleteAttemptIdentity.intentFingerprint(intent))
        put("context", context); put("original", original); put("request", request(intent.base.eventId, context))
    }
    private fun request(eventId: EventId, context: JsonObject) =
        deleteRequest(listOf(eventId), context).also { deleteOnlyBytes(it.toString()).fill(0) }
}

internal fun validateDeleteContext(context: JsonObject) {
    require(context.keys == ProtonSimpleEventUpdate.CONTEXT_KEYS)
    listOf("member", "address", "key", "passphrase", "author").forEach { deleteBounded(context.string(it), 4096) }
    require(context.number("permissions") in 0L..127L && context.number("permissions") and 16L != 0L && context.number("flags") == 1L)
    listOf("calendarKey", "signingKey", "calendar", "bootstrap").forEach {
        require(context.string(it).matches(Regex("[a-f0-9]{64}")))
    }
}

internal fun deleteRequest(ids: List<EventId>, context: JsonObject) = buildJsonObject {
    put("MemberID", context.string("member"))
    putJsonArray("Events") { for (id in ids) add(buildJsonObject { put("ID", id.value); put("DeletionReason", 0) }) }
}

internal fun deleteBounded(value: String, limit: Int = DeleteAttemptRecord.MAX_PAYLOAD_BYTES) {
    require(value.length <= limit)
    require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <= limit)
}
internal fun deleteValidateJson(value: JsonElement, depth: Int = 0) {
    require(depth <= 64)
    when (value) {
        is JsonObject -> value.forEach { (key, child) -> deleteBounded(key); deleteValidateJson(child, depth + 1) }
        is JsonArray -> value.forEach { deleteValidateJson(it, depth + 1) }
        is JsonPrimitive -> if (value.isString) deleteBounded(value.content)
    }
}
internal fun deleteSnapshot(value: JsonObject): JsonObject {
    val text = value.toString(); deleteBounded(text)
    return (Json.parseToJsonElement(text) as JsonObject).also { deleteValidateJson(it) }
}
