// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal val PERSONAL_UPDATE_REQUEST_FORMAT = AdapterFormat("proton-calendar-personal-edit-request", 2)
internal enum class ProtonPersonalUpdateRequestFailure { UNSUPPORTED, STORAGE }
internal class ProtonPersonalUpdateRequestException(val reason: ProtonPersonalUpdateRequestFailure) :
    Exception("PERSONAL_UPDATE_REQUEST_${reason.name}")

/** Protected offline personal-v2 request only; no journal state, transport or sending authority. */
internal class ProtonProtectedPersonalUpdateRequest(val id: MutationId, preparation: PersonalEditPreparation,
    val format: AdapterFormat, val protectedPayload: String) {
    private val frozen = PersonalEditPreparation(preparation.intent, preparation.timestamp, preparation.format, preparation.protectedPayload)
    val preparation: PersonalEditPreparation get() =
        PersonalEditPreparation(frozen.intent, frozen.timestamp, frozen.format, frozen.protectedPayload)
    override fun toString() = "ProtonProtectedPersonalUpdateRequest(redacted)"
}

/** Reopened historical structure only; does not establish current access or verify signatures. */
internal class ProtonPersonalUpdateRequest(fragments: ProtonTimestampedSharedPersonal, context: JsonObject, request: JsonObject) {
    private val frozen = ProtonTimestampedSharedPersonal(fragments.originalRow, fragments.clearSharedPart,
        fragments.encryptedSharedPart, fragments.changed, fragments.defaults)
    private val contextJson = context.toString()
    private val requestJson = request.toString()
    val fragments: ProtonTimestampedSharedPersonal get() = ProtonTimestampedSharedPersonal(frozen.originalRow,
        frozen.clearSharedPart, frozen.encryptedSharedPart, frozen.changed, frozen.defaults)
    val context: JsonObject get() = Json.parseToJsonElement(contextJson) as JsonObject
    val request: JsonObject get() = Json.parseToJsonElement(requestJson) as JsonObject
    override fun toString() = "ProtonPersonalUpdateRequest(redacted)"
}

internal class ProtonPersonalUpdateRequestCodec(private val protector: EventPayloadProtector) {
    suspend fun seal(id: MutationId, preparation: PersonalEditPreparation, context: JsonObject): ProtonProtectedPersonalUpdateRequest {
        val frozen = structure { bounded(id.value, ID_BYTES); freeze(preparation) }
        // Snapshot before opening the capsule: even that protector callback can mutate caller JSON.
        val frozenContext = structure { snapshot(context).also(::validateContext) }
        val fragments = structure { ProtonPersonalPreparationCodec(protector).openLocal(frozen) }
        val request = structure { request(frozen, fragments, frozenContext) }
        val plain = structure { envelope(id, frozen, frozenContext, request).encoded() }
        val protected = storage { protector.seal(plain) }
        return structure {
            bounded(protected)
            ProtonProtectedPersonalUpdateRequest(id, frozen, PERSONAL_UPDATE_REQUEST_FORMAT, protected)
        }
    }

    suspend fun open(record: ProtonProtectedPersonalUpdateRequest): ProtonPersonalUpdateRequest {
        val frozen = structure {
            require(record.format == PERSONAL_UPDATE_REQUEST_FORMAT)
            bounded(record.id.value, ID_BYTES); bounded(record.protectedPayload)
            freeze(record.preparation)
        }
        val fragments = structure { ProtonPersonalPreparationCodec(protector).openLocal(frozen) }
        val plain = storage { protector.open(record.protectedPayload) }
        return structure {
            bounded(plain); require(UniqueJsonKeys(plain).validate())
            val root = Json.parseToJsonElement(plain) as? JsonObject ?: refuse()
            validateJson(root)
            require(root.keys == ROOT_KEYS)
            val context = root.strictObject("context").also(::validateContext)
            val request = request(frozen, fragments, context)
            require(root == envelope(record.id, frozen, context, request))
            ProtonPersonalUpdateRequest(fragments, context, request)
        }
    }

    private fun freeze(preparation: PersonalEditPreparation): PersonalEditPreparation {
        require(preparation.format == PERSONAL_PREPARATION_FORMAT)
        val frozen = PersonalEditPreparation(preparation.intent, preparation.timestamp, preparation.format, preparation.protectedPayload)
        require(frozen.intent.format == EVENT_FORMAT)
        return frozen
    }

    private fun request(preparation: PersonalEditPreparation, fragments: ProtonTimestampedSharedPersonal,
        context: JsonObject): JsonObject = ProtonPersonalEventUpdate.request(fragments.originalRow, fragments.clearSharedPart,
        fragments.encryptedSharedPart, fragments.changed, context, preparation.intent.base.content, preparation.intent.content, fragments.defaults)

    private fun snapshot(value: JsonObject): JsonObject = Json.parseToJsonElement(value.encoded()) as JsonObject

    private fun validateContext(context: JsonObject) {
        require(context.keys == CONTEXT_KEYS)
        for (name in listOf("member", "address", "key", "passphrase", "author")) bounded(context.strictString(name), ID_BYTES)
        require(context.strictNumber("permissions") >= 0 && context.strictNumber("flags") >= 0)
        for (name in listOf("calendarKey", "signingKey", "calendar", "bootstrap"))
            require(context.strictString(name).matches(Regex("[a-f0-9]{64}")))
    }

    private fun envelope(id: MutationId, preparation: PersonalEditPreparation, context: JsonObject, request: JsonObject) =
        buildJsonObject {
            put("kind", "personal-edit-v2-request"); put("version", 2); put("requestId", id.value)
            put("preparation", preparationIdentity(preparation)); put("context", context); put("request", request)
        }

    /** Bind the full framed observation and capsule identity, including exact temporal values. */
    private fun preparationIdentity(preparation: PersonalEditPreparation) = buildJsonObject {
        val intent = preparation.intent
        put("id", intent.id.value)
        putJsonObject("scope") {
            put("account", intent.scope.account.value); put("calendar", intent.scope.calendar.value); put("generation", intent.scope.generation)
        }
        putJsonObject("observationFormat") { put("id", intent.format.id); put("version", intent.format.version) }
        put("state", intent.state.name)
        put("event", intent.base.eventId.value); put("revision", intent.base.revision.value)
        put("base", contentIdentity(intent.base.content)); put("replacement", contentIdentity(intent.content))
        put("timestamp", preparation.timestamp.toString())
        putJsonObject("format") { put("id", preparation.format.id); put("version", preparation.format.version) }
        put("payload", ProtonSimpleEventUpdate.hash(preparation.protectedPayload))
    }

    private fun contentIdentity(content: EventContent) = buildJsonObject {
        put("title", content.title); put("description", content.description); put("location", content.location)
        // The domain candidate freezes personal details/color and forbids recurrence.
        put("color", content.colorRgb?.let(::JsonPrimitive) ?: JsonNull); put("recurrence", JsonNull); put("details", buildJsonObject { put("reminders", JsonArray(content.details!!.reminders.map { JsonPrimitive(it.minutesBefore) })); put("organizer", JsonNull); put("attendees", JsonArray(emptyList())) })
        putJsonObject("time") {
            when (val time = content.time) {
                is EventTime.AllDay -> {
                    put("type", "ALL_DAY"); put("start", time.start.toString()); put("end", time.endExclusive.toString())
                }
                is EventTime.Timed -> {
                    put("type", "TIMED"); put("start", time.start.toString()); put("end", time.end.toString()); put("zone", time.zone.id)
                }
            }
        }
    }

    private fun JsonObject.encoded(): String {
        validateJson(this)
        return toString().also { bounded(it); require(UniqueJsonKeys(it).validate()) }
    }

    /** Decoded escaped strings receive the same strict UTF-8 budget as literal strings. */
    private fun validateJson(value: JsonElement, depth: Int = 0) {
        require(depth <= 64)
        when (value) {
            is JsonObject -> value.forEach { (key, item) ->
                bounded(key, allowBlank = true); validateJson(item, depth + 1)
            }
            is JsonArray -> value.forEach { validateJson(it, depth + 1) }
            is JsonPrimitive -> if (value.isString) bounded(value.content, allowBlank = true)
        }
    }

    private fun JsonObject.strictString(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: refuse()
    private fun JsonObject.strictNumber(key: String): Long =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: refuse()
    private fun JsonObject.strictObject(key: String): JsonObject = this[key] as? JsonObject ?: refuse()

    private fun bounded(value: String, maximum: Int = PAYLOAD_BYTES, allowBlank: Boolean = false) {
        require((allowBlank || value.isNotBlank()) && value.length <= maximum)
        require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <= maximum)
    }

    private suspend fun <T> structure(action: () -> T): T {
        currentCoroutineContext().ensureActive()
        return try {
            val result = action()
            currentCoroutineContext().ensureActive()
            result
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: ProtonPersonalPreparationException) {
            currentCoroutineContext().ensureActive()
            throw ProtonPersonalUpdateRequestException(if (failure.reason == ProtonPersonalPreparationFailure.STORAGE)
                ProtonPersonalUpdateRequestFailure.STORAGE else ProtonPersonalUpdateRequestFailure.UNSUPPORTED)
        } catch (_: Exception) { currentCoroutineContext().ensureActive(); refuse() }
    }

    private suspend fun <T> storage(action: () -> T): T {
        currentCoroutineContext().ensureActive()
        return try {
            val result = action()
            currentCoroutineContext().ensureActive()
            result
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            throw ProtonPersonalUpdateRequestException(ProtonPersonalUpdateRequestFailure.STORAGE)
        }
    }

    private fun refuse(): Nothing = throw ProtonPersonalUpdateRequestException(ProtonPersonalUpdateRequestFailure.UNSUPPORTED)

    private companion object {
        const val ID_BYTES = 4_096
        const val PAYLOAD_BYTES = 4_194_304
        val ROOT_KEYS = setOf("kind", "version", "requestId", "preparation", "context", "request")
        val CONTEXT_KEYS = setOf("member", "address", "permissions", "flags", "key", "passphrase", "calendarKey",
            "signingKey", "author", "calendar", "bootstrap")
    }
}
