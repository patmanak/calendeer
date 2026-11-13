// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.*

internal enum class ProtonPersonalPreparationFailure { UNSUPPORTED, STORAGE }
internal class ProtonPersonalPreparationException(val reason: ProtonPersonalPreparationFailure) :
    Exception("PERSONAL_PREPARATION_${reason.name}")
internal val PERSONAL_PREPARATION_FORMAT = AdapterFormat("proton-calendar-offline-personal-edit-preparation", 2)

/** Disjoint offline personal-v2 history. Reopening checks retained structure; it does not
 * verify signatures, decrypt replacement data or establish current access/send authority.
 */
internal class ProtonPersonalPreparationCodec(private val protector: EventPayloadProtector) {
    fun sealLocal(intent: LocalEditIntent, uid: String, timestamp: Instant,
        fragments: ProtonTimestampedSharedPersonal): PersonalEditPreparation {
        val frozen = structure { PersonalEditCandidate(intent).intent }
        val plain = structure {
            validateBinding(uid, timestamp)
            val original = fragments.originalRow
            val clear = fragments.clearSharedPart
            val encrypted = fragments.encryptedSharedPart
            validateFragments(frozen, uid, original, clear, encrypted, fragments.changed)
            validateDefaults(frozen, original, fragments.defaults)
            buildJsonObject {
                put("kind", KIND); put("version", 2); put("format", PERSONAL_PREPARATION_FORMAT.json())
                put("localIntent", frozen.json()); put("binding", binding(uid, timestamp))
                put("originalRow", original); put("clearSharedPart", clear); put("encryptedSharedPart", encrypted)
                put("changed", fragments.changed)
                fragments.defaults?.let { put("notificationDefaults", it) }
            }.encoded()
        }
        val protected = storage { protector.seal(plain) }
        return structure {
            bounded(protected); require(protected.isNotBlank())
            PersonalEditPreparation(frozen, timestamp, PERSONAL_PREPARATION_FORMAT, protected)
        }
    }

    fun openLocal(record: PersonalEditPreparation): ProtonTimestampedSharedPersonal {
        val frozen = structure {
            require(record.format == PERSONAL_PREPARATION_FORMAT)
            bounded(record.protectedPayload); require(record.protectedPayload.isNotBlank())
            PersonalEditCandidate(record.intent).intent
        }
        val plain = storage { protector.open(record.protectedPayload) }
        return structure {
            bounded(plain); require(UniqueJsonKeys(plain).validate())
            val root = Json.parseToJsonElement(plain) as? JsonObject ?: refuse()
            validateJson(root)
            require(root.keys in setOf(ROOT_KEYS, ROOT_KEYS + "notificationDefaults") && root.strictString("kind") == KIND && root.strictNumber("version") == 2L)
            require(root.strictObject("format") == record.format.json())
            require(root.strictObject("localIntent") == frozen.json())
            val bound = root.strictObject("binding")
            val uid = bound.strictString("uid")
            validateBinding(uid, record.timestamp)
            require(bound == binding(uid, record.timestamp))
            val changed = (root["changed"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: refuse()
            val original = root.strictObject("originalRow")
            val clear = root.strictObject("clearSharedPart")
            val encrypted = root.strictObject("encryptedSharedPart")
            validateFragments(frozen, uid, original, clear, encrypted, changed)
            val defaults = if ("notificationDefaults" in root) root.strictObject("notificationDefaults") else null
            validateDefaults(frozen, original, defaults)
            ProtonTimestampedSharedPersonal(original, clear, encrypted, changed, defaults)
        }
    }

    private fun validateDefaults(intent: LocalEditIntent, original: JsonObject, defaults: JsonObject?) {
        val needsDefaults = original["Notifications"] == JsonNull && intent.base.content.details?.reminders != intent.content.details?.reminders
        require((defaults != null) == needsDefaults)
        if (defaults != null) ProtonPersonalMetadataEdit.validateDefaults(original, intent.base.content, defaults)
    }

    private fun validateBinding(uid: String, timestamp: Instant) {
        require(uid.isNotBlank()); bounded(uid, PersonalEditPreparation.MAX_CONTEXT_BYTES)
        require(timestamp.nano == 0 && timestamp >= Instant.EPOCH && timestamp <= LAST_TIMESTAMP)
    }

    private fun binding(uid: String, timestamp: Instant) = buildJsonObject {
        put("uid", uid); put("timestamp", timestamp.toString())
    }

    /** Full exact historical observation, including all mapped content and exclusive dates. */
    private fun LocalEditIntent.json() = buildJsonObject {
        put("id", id.value)
        put("scope", buildJsonObject {
            put("account", scope.account.value); put("calendar", scope.calendar.value); put("generation", scope.generation)
        })
        put("format", format.json()); put("state", state.name)
        put("base", buildJsonObject {
            put("eventId", base.eventId.value); put("revision", base.revision.value); put("content", base.content.json())
        })
        put("content", content.json())
    }

    private fun AdapterFormat.json() = buildJsonObject { put("id", id); put("version", version) }

    private fun EventContent.json() = buildJsonObject {
        put("title", title); put("description", description); put("location", location)
        put("color", colorRgb?.let(::JsonPrimitive) ?: JsonNull)
        // PersonalEditCandidate has already required unchanged known personal details and absent recurrence.
        put("details", buildJsonObject { put("reminders", JsonArray(details!!.reminders.map { JsonPrimitive(it.minutesBefore) })); put("organizer", JsonNull); put("attendees", JsonArray(emptyList())) }); put("recurrence", JsonNull)
        put("time", buildJsonObject {
            when (val value = time) {
                is EventTime.AllDay -> {
                    put("type", "ALL_DAY"); put("start", value.start.toString()); put("end", value.endExclusive.toString())
                }
                is EventTime.Timed -> {
                    put("type", "TIMED"); put("start", value.start.toString()); put("end", value.end.toString()); put("zone", value.zone.id)
                }
            }
        })
    }

    private fun validateFragments(intent: LocalEditIntent, uid: String, original: JsonObject,
        clear: JsonObject, encrypted: JsonObject, changed: Boolean) {
        require(original.strictString("ID") == intent.base.eventId.value &&
            original.strictString("CalendarID") == intent.scope.calendar.value && original.strictString("UID") == uid)
        require(original.strictString("SharedKeyPacket").isNotBlank())
        require(original["AddressKeyPacket"] == null || original["AddressKeyPacket"] == JsonNull)
        require(original.strictArray("CalendarEvents").isEmpty() && original.strictArray("AttendeesEvents").isEmpty())
        val parts = original.strictArray("SharedEvents").map { it as? JsonObject ?: refuse() }
        require(parts.size == 2 && parts.map { it.strictNumber("Type") }.sorted() == listOf(2L, 3L))
        val originalClear = parts.single { it.strictNumber("Type") == 2L }
        val originalEncrypted = parts.single { it.strictNumber("Type") == 3L }
        validatePart(originalClear, clear); validatePart(originalEncrypted, encrypted)
        require(changed && (originalClear != clear || originalEncrypted != encrypted))
    }

    private fun validatePart(original: JsonObject, replacement: JsonObject) {
        listOf(original, replacement).forEach { part ->
            listOf("Data", "Signature", "Author").forEach { require(part.strictString(it).isNotBlank()) }
        }
        require(original.keys == replacement.keys)
        require(JsonObject(original - RENEWED_KEYS) == JsonObject(replacement - RENEWED_KEYS))
        if (original["Data"] == replacement["Data"]) require(original == replacement)
    }

    private fun JsonObject.encoded(): String {
        validateJson(this)
        return toString().also { bounded(it); require(UniqueJsonKeys(it).validate()) }
    }

    /** Json escapes must not bypass strict UTF-8 checks on decoded retained strings. */
    private fun validateJson(value: JsonElement, depth: Int = 0) {
        require(depth <= 64)
        when (value) {
            is JsonObject -> value.forEach { (key, item) ->
                bounded(key, allowEmpty = true); validateJson(item, depth + 1)
            }
            is JsonArray -> value.forEach { validateJson(it, depth + 1) }
            is JsonPrimitive -> if (value.isString) bounded(value.content, allowEmpty = true)
        }
    }

    private fun JsonObject.strictString(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: refuse()
    private fun JsonObject.strictNumber(key: String): Long =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: refuse()
    private fun JsonObject.strictObject(key: String): JsonObject = this[key] as? JsonObject ?: refuse()
    private fun JsonObject.strictArray(key: String): JsonArray = this[key] as? JsonArray ?: refuse()

    private fun bounded(value: String, maximum: Int = PersonalEditPreparation.MAX_PAYLOAD_BYTES,
        allowEmpty: Boolean = false) {
        require((allowEmpty || value.isNotEmpty()) && value.length <= maximum)
        require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <= maximum)
    }

    private fun <T> structure(action: () -> T): T = try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { refuse() }
    private fun <T> storage(action: () -> T): T = try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw ProtonPersonalPreparationException(ProtonPersonalPreparationFailure.STORAGE) }
    private fun refuse(): Nothing = throw ProtonPersonalPreparationException(ProtonPersonalPreparationFailure.UNSUPPORTED)

    private companion object {
        const val KIND = "offline-personal-edit-v2-preparation"
        val LAST_TIMESTAMP: Instant = Instant.parse("9999-12-31T23:59:59Z")
        val RENEWED_KEYS = setOf("Data", "Signature")
        val ROOT_KEYS = setOf("kind", "version", "format", "localIntent", "binding", "originalRow",
            "clearSharedPart", "encryptedSharedPart", "changed")
    }
}
