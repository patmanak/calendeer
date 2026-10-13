// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal val TEXT_UPDATE_REQUEST_FORMAT = AdapterFormat("proton-calendar-text-update-request", 1)
internal enum class ProtonTextUpdateRequestFailure { UNSUPPORTED, STORAGE }
internal class ProtonTextUpdateRequestException(val reason: ProtonTextUpdateRequestFailure) :
    Exception("TEXT_UPDATE_REQUEST_${reason.name}")

/** Protected offline request description only; no journal state, transport or sending authority. */
internal class ProtonProtectedTextUpdateRequest(val id: MutationId, preparation: LocalTextEditPreparation,
    val format: AdapterFormat, val protectedPayload: String) {
    private val frozen = LocalTextEditPreparation(preparation.intent, preparation.timestamp, preparation.format, preparation.protectedPayload)
    val preparation: LocalTextEditPreparation get() =
        LocalTextEditPreparation(frozen.intent, frozen.timestamp, frozen.format, frozen.protectedPayload)
    override fun toString() = "ProtonProtectedTextUpdateRequest(redacted)"
}

/** Reopened historical structure only; does not establish current access or verify signatures. */
internal class ProtonTextUpdateRequest(fragments: ProtonTimestampedSharedText, context: JsonObject, request: JsonObject) {
    private val frozen = ProtonTimestampedSharedText(fragments.originalRow, fragments.clearSharedPart,
        fragments.encryptedSharedPart, fragments.changed)
    private val contextJson = context.toString()
    private val requestJson = request.toString()
    val fragments: ProtonTimestampedSharedText get() = ProtonTimestampedSharedText(frozen.originalRow,
        frozen.clearSharedPart, frozen.encryptedSharedPart, frozen.changed)
    val context: JsonObject get() = Json.parseToJsonElement(contextJson) as JsonObject
    val request: JsonObject get() = Json.parseToJsonElement(requestJson) as JsonObject
    override fun toString() = "ProtonTextUpdateRequest(redacted)"
}

internal class ProtonTextUpdateRequestCodec(private val protector: EventPayloadProtector) {
    suspend fun seal(id: MutationId, preparation: LocalTextEditPreparation, context: JsonObject): ProtonProtectedTextUpdateRequest {
        val frozen = structure { bounded(id.value, ID_BYTES); freeze(preparation) }
        // Freeze caller-owned JSON before the first protector callback can mutate it.
        val frozenContext = structure { snapshot(context).also(::validateContext) }
        val fragments = structure { ProtonTextPreparationCodec(protector).openLocal(frozen) }
        val request = structure { request(fragments, frozenContext) }
        val plain = structure { envelope(id, frozen, frozenContext, request).encoded() }
        val protected = storage { protector.seal(plain) }
        return structure {
            bounded(protected)
            ProtonProtectedTextUpdateRequest(id, frozen, TEXT_UPDATE_REQUEST_FORMAT, protected)
        }
    }

    suspend fun open(record: ProtonProtectedTextUpdateRequest): ProtonTextUpdateRequest {
        val frozen = structure {
            require(record.format == TEXT_UPDATE_REQUEST_FORMAT)
            bounded(record.id.value, ID_BYTES); bounded(record.protectedPayload)
            freeze(record.preparation)
        }
        val fragments = structure { ProtonTextPreparationCodec(protector).openLocal(frozen) }
        val plain = storage { protector.open(record.protectedPayload) }
        return structure {
            bounded(plain); require(UniqueJsonKeys(plain).validate())
            val root = Json.parseToJsonElement(plain) as? JsonObject ?: refuse()
            require(root.keys == ROOT_KEYS)
            val context = root.obj("context").also(::validateContext)
            val request = request(fragments, context)
            require(root == envelope(record.id, frozen, context, request))
            ProtonTextUpdateRequest(fragments, context, request)
        }
    }

    private fun freeze(preparation: LocalTextEditPreparation): LocalTextEditPreparation {
        require(preparation.format == LOCAL_TEXT_PREPARATION_FORMAT)
        val frozen = LocalTextEditPreparation(preparation.intent, preparation.timestamp, preparation.format, preparation.protectedPayload)
        require(frozen.intent.format == EVENT_FORMAT)
        require(frozen.intent.base.content.details == EventDetails() && frozen.intent.base.content.colorRgb == null &&
            frozen.intent.base.content.recurrence == null)
        return frozen
    }

    private fun request(fragments: ProtonTimestampedSharedText, context: JsonObject): JsonObject =
        ProtonSimpleEventUpdate.request(fragments.originalRow, fragments.clearSharedPart,
            fragments.encryptedSharedPart, fragments.changed, context)

    private fun snapshot(value: JsonObject): JsonObject = Json.parseToJsonElement(value.encoded()) as JsonObject
    private fun validateContext(context: JsonObject) {
        require(context.keys == ProtonSimpleEventUpdate.CONTEXT_KEYS)
        for (name in listOf("member", "address", "key", "passphrase", "author")) bounded(context.string(name), ID_BYTES)
        require(context.number("permissions") >= 0 && context.number("flags") >= 0)
        for (name in listOf("calendarKey", "signingKey", "calendar", "bootstrap"))
            require(context.string(name).matches(Regex("[a-f0-9]{64}")))
    }

    private fun envelope(id: MutationId, preparation: LocalTextEditPreparation, context: JsonObject, request: JsonObject) =
        buildJsonObject {
            put("kind", "text-update-request"); put("version", 1); put("requestId", id.value)
            put("preparation", preparationIdentity(preparation)); put("context", context); put("request", request)
        }

    /** Complete canonical binding, not merely an unframed concatenation or ciphertext locator. */
    private fun preparationIdentity(preparation: LocalTextEditPreparation) = buildJsonObject {
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
        put("color", JsonNull); put("recurrence", JsonNull); put("details", buildJsonObject {})
        putJsonObject("time") {
            when (val time = content.time) {
                is EventTime.AllDay -> { put("type", "ALL_DAY"); put("start", time.start.toString()); put("end", time.endExclusive.toString()) }
                is EventTime.Timed -> {
                    put("type", "TIMED"); put("start", time.start.toString()); put("end", time.end.toString()); put("zone", time.zone.id)
                }
            }
        }
    }

    private fun JsonObject.encoded() = toString().also { bounded(it); require(UniqueJsonKeys(it).validate()) }
    private fun bounded(value: String, maximum: Int = PAYLOAD_BYTES) {
        require(value.isNotBlank() && value.length <= maximum)
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
          catch (failure: ProtonTextPreparationException) {
            currentCoroutineContext().ensureActive()
            throw ProtonTextUpdateRequestException(if (failure.reason == ProtonTextPreparationFailure.STORAGE)
                ProtonTextUpdateRequestFailure.STORAGE else ProtonTextUpdateRequestFailure.UNSUPPORTED)
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
            throw ProtonTextUpdateRequestException(ProtonTextUpdateRequestFailure.STORAGE)
        }
    }
    private fun refuse(): Nothing = throw ProtonTextUpdateRequestException(ProtonTextUpdateRequestFailure.UNSUPPORTED)
    private companion object {
        const val ID_BYTES = 4_096
        const val PAYLOAD_BYTES = 4_194_304
        val ROOT_KEYS = setOf("kind", "version", "requestId", "preparation", "context", "request")
    }
}
