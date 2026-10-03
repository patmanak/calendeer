// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.*

internal val TITLE_PREPARATION_FORMAT = AdapterFormat("proton-calendar-offline-title-preparation", 1)
internal val LOCAL_TITLE_PREPARATION_FORMAT = AdapterFormat("proton-calendar-offline-local-title-preparation", 1)

/** Frozen caller observation, not proof of current access or event edit eligibility. */
internal class ProtonTitlePreparationBinding(
    val scope: CalendarScope, val eventId: EventId, val revision: Revision,
    val uid: String, val expectedTitle: String, val newTitle: String, val timestamp: Instant
) {
    override fun toString() = "ProtonTitlePreparationBinding(redacted)"
}

/** UID, titles and edit time remain inside the protected payload. */
internal class ProtonProtectedTitlePreparation(
    val scope: CalendarScope, val eventId: EventId, val revision: Revision,
    val format: AdapterFormat, val protectedPayload: String
) {
    override fun toString() = "ProtonProtectedTitlePreparation(redacted)"
}

internal enum class ProtonTitlePreparationFailure { UNSUPPORTED, STORAGE }
internal class ProtonTitlePreparationException(val reason: ProtonTitlePreparationFailure) :
    Exception("TITLE_PREPARATION_${reason.name}")

/** Local protected fragments only. No persistence, crypto re-verification, access proof or UPDATE assembly.
 * Sealing authenticates the frozen structure with the supplied storage protector, not the unsigned
 * Proton row metadata. Opaque fields and revision are retained without asserting their semantics.
 */
internal class ProtonTitlePreparationCodec(private val protector: EventPayloadProtector) {
    /** Seal a complete historical local observation; this is not remote content/access qualification. */
    fun sealLocal(intent: LocalEditIntent, uid: String, timestamp: Instant,
        fragments: ProtonTimestampedSharedTitle): LocalEditPreparation {
        val frozen = structure { LocalEditPreparation.freezeIntent(intent) }
        val binding = frozen.binding(uid, timestamp)
        val plain = structure {
            val owner = frozen.json()
            JsonObject(envelope(binding, fragments) + mapOf("kind" to JsonPrimitive(LOCAL_KIND), "localIntent" to owner))
                .encoded()
        }
        val protected = storage { protector.seal(plain) }
        return structure {
            bounded(protected)
            LocalEditPreparation(frozen, timestamp, LOCAL_TITLE_PREPARATION_FORMAT, protected)
        }
    }

    /** Read only the sealed historical preparation. UID comes from authenticated local storage,
     * never from a separate caller expectation or unprotected database field. No dispatch admission.
     */
    fun openLocal(record: LocalEditPreparation): ProtonTimestampedSharedTitle {
        val (frozen, owner) = structure {
            require(record.format == LOCAL_TITLE_PREPARATION_FORMAT)
            val frozen = record.copy(intent = LocalEditPreparation.freezeIntent(record.intent))
            frozen to frozen.intent.json()
        }
        val plain = storage { protector.open(frozen.protectedPayload) }
        return structure {
            val root = parse(plain)
            require(root.keys == LOCAL_ROOT_KEYS && root.strictString("kind") == LOCAL_KIND && root.strictNumber("version") == 1L)
            require(root.strictObject("localIntent") == owner)
            // Compare the expected full intent before using the authenticated UID.
            val binding = frozen.intent.binding(root.strictObject("binding").strictString("uid"), frozen.timestamp)
            validateBinding(binding)
            require(root.strictObject("binding") == binding.json())
            fragments(root, binding)
        }
    }

    fun seal(binding: ProtonTitlePreparationBinding, fragments: ProtonTimestampedSharedTitle): ProtonProtectedTitlePreparation {
        val plain = structure {
            envelope(binding, fragments).encoded()
        }
        val protected = storage { protector.seal(plain) }
        structure { bounded(protected) }
        return ProtonProtectedTitlePreparation(binding.scope, binding.eventId, binding.revision, TITLE_PREPARATION_FORMAT, protected)
    }

    fun open(record: ProtonProtectedTitlePreparation, expected: ProtonTitlePreparationBinding): ProtonTimestampedSharedTitle {
        structure {
            validateBinding(expected)
            require(record.format == TITLE_PREPARATION_FORMAT && record.scope == expected.scope &&
                record.eventId == expected.eventId && record.revision == expected.revision)
            bounded(record.protectedPayload)
        }
        val plain = storage { protector.open(record.protectedPayload) }
        return structure {
            val root = parse(plain)
            require(root.keys == ROOT_KEYS && root.strictString("kind") == KIND && root.strictNumber("version") == 1L)
            require(root.strictObject("binding") == expected.json())
            fragments(root, expected)
        }
    }

    private fun LocalEditIntent.binding(uid: String, timestamp: Instant) = ProtonTitlePreparationBinding(
        scope, base.eventId, base.revision, uid, base.content.title, content.title, timestamp)

    /** Closed typed representation. It pins the caller's complete observation, not remote semantics. */
    private fun LocalEditIntent.json(): JsonObject = buildJsonObject {
        put("id", id.value)
        put("scope", buildJsonObject {
            put("account", scope.account.value); put("calendar", scope.calendar.value); put("generation", scope.generation)
        })
        put("format", buildJsonObject { put("id", format.id); put("version", format.version) })
        put("state", state.name)
        put("base", buildJsonObject {
            put("eventId", base.eventId.value); put("revision", base.revision.value); put("content", base.content.json())
        })
        put("content", content.json())
    }.also { bounded(it.toString()) }

    private fun EventContent.json(): JsonObject {
        bounded(title, MAX_TITLE_BYTES, allowEmpty = true)
        bounded(description, allowEmpty = true); bounded(location, allowEmpty = true)
        return buildJsonObject {
            put("title", title); put("description", description); put("location", location)
            put("color", colorRgb?.let(::JsonPrimitive) ?: JsonNull)
            put("details", if (details == null) JsonNull else buildJsonObject {})
            put("recurrence", JsonNull) // The frozen title-only profile already refuses recurrence.
            put("time", buildJsonObject {
                when (val value = time) {
                    is EventTime.AllDay -> {
                        put("type", "ALL_DAY"); put("start", value.start.toString()); put("end", value.endExclusive.toString())
                    }
                    is EventTime.Timed -> {
                        put("type", "TIMED"); put("start", value.start.toString()); put("end", value.end.toString())
                        put("zone", value.zone.id)
                    }
                }
            })
        }
    }

    private fun envelope(binding: ProtonTitlePreparationBinding, fragments: ProtonTimestampedSharedTitle): JsonObject {
        validateBinding(binding)
        val original = fragments.originalRow
        val clear = fragments.clearSharedPart
        val encrypted = fragments.encryptedSharedPart
        validateFragments(binding, original, clear, encrypted, fragments.changed)
        return buildJsonObject {
            put("kind", KIND); put("version", 1); put("binding", binding.json())
            put("originalRow", original); put("clearSharedPart", clear); put("encryptedSharedPart", encrypted)
            put("changed", fragments.changed)
        }
    }
    private fun JsonObject.encoded() = toString().also { bounded(it); require(UniqueJsonKeys(it).validate()) }
    private fun parse(plain: String): JsonObject {
        bounded(plain); require(UniqueJsonKeys(plain).validate())
        return Json.parseToJsonElement(plain) as? JsonObject ?: refuse()
    }
    private fun fragments(root: JsonObject, binding: ProtonTitlePreparationBinding): ProtonTimestampedSharedTitle {
        val changed = (root["changed"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: refuse()
        val original = root.strictObject("originalRow")
        val clear = root.strictObject("clearSharedPart")
        val encrypted = root.strictObject("encryptedSharedPart")
        validateFragments(binding, original, clear, encrypted, changed)
        return ProtonTimestampedSharedTitle(original, clear, encrypted, changed)
    }

    private fun validateBinding(binding: ProtonTitlePreparationBinding) {
        with(binding) {
            listOf(scope.account.value, scope.calendar.value, eventId.value, revision.value, uid).forEach {
                require(it.isNotBlank()); bounded(it, MAX_ID_BYTES)
            }
            listOf(expectedTitle, newTitle).forEach { title ->
                bounded(title, MAX_TITLE_BYTES, allowEmpty = true)
                require(title.replace("\r\n", "\n").none { it.isISOControl() && it != '\n' && it != '\t' })
            }
            require(timestamp.nano == 0 && timestamp.atOffset(ZoneOffset.UTC).year in 1970..9999)
        }
    }

    private fun validateFragments(binding: ProtonTitlePreparationBinding, original: JsonObject,
        clear: JsonObject, encrypted: JsonObject, changed: Boolean) {
        require(original.strictString("ID") == binding.eventId.value &&
            original.strictString("CalendarID") == binding.scope.calendar.value && original.strictString("UID") == binding.uid)
        require(original.strictString("SharedKeyPacket").isNotBlank())
        require(original["AddressKeyPacket"] == null || original["AddressKeyPacket"] == JsonNull)
        require(original.strictArray("CalendarEvents").isEmpty() && original.strictArray("AttendeesEvents").isEmpty())
        val parts = original.strictArray("SharedEvents").map { it as? JsonObject ?: refuse() }
        require(parts.size == 2 && parts.map { it.strictNumber("Type") }.sorted() == listOf(2L, 3L))
        val originalClear = parts.single { it.strictNumber("Type") == 2L }
        val originalEncrypted = parts.single { it.strictNumber("Type") == 3L }
        validatePart(originalClear, clear)
        validatePart(originalEncrypted, encrypted)
        require(changed == (originalClear != clear || originalEncrypted != encrypted))
    }

    private fun validatePart(original: JsonObject, replacement: JsonObject) {
        listOf(original, replacement).forEach { part ->
            listOf("Data", "Signature", "Author").forEach { require(part.strictString(it).isNotBlank()) }
        }
        require(original.keys == replacement.keys)
        require(JsonObject(original - RENEWED_KEYS) == JsonObject(replacement - RENEWED_KEYS))
        // Core text signatures authenticate canonical text. Different received spans can
        // legitimately retain the same signature bytes; this codec cannot verify crypto.
        if (original["Data"] == replacement["Data"]) require(original == replacement)
    }

    private fun ProtonTitlePreparationBinding.json() = buildJsonObject {
        put("scope", buildJsonObject {
            put("account", scope.account.value); put("calendar", scope.calendar.value); put("generation", scope.generation)
        })
        put("eventId", eventId.value); put("revision", revision.value); put("uid", uid)
        put("expectedTitle", expectedTitle); put("newTitle", newTitle); put("timestamp", timestamp.toString())
    }

    private fun JsonObject.strictString(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: refuse()
    private fun JsonObject.strictNumber(key: String): Long =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: refuse()
    private fun JsonObject.strictObject(key: String): JsonObject = this[key] as? JsonObject ?: refuse()
    private fun JsonObject.strictArray(key: String): JsonArray = this[key] as? JsonArray ?: refuse()

    private fun bounded(value: String, maximum: Int = MAX_PAYLOAD_BYTES, allowEmpty: Boolean = false) {
        require((allowEmpty || value.isNotEmpty()) && value.length <= maximum)
        val size = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining()
        require(size <= maximum)
    }

    private fun <T> structure(action: () -> T): T = try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { refuse() }
    private fun <T> storage(action: () -> T): T = try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw ProtonTitlePreparationException(ProtonTitlePreparationFailure.STORAGE) }
    private fun refuse(): Nothing = throw ProtonTitlePreparationException(ProtonTitlePreparationFailure.UNSUPPORTED)

    private companion object {
        const val KIND = "offline-title-preparation"
        const val LOCAL_KIND = "offline-local-title-preparation"
        const val MAX_PAYLOAD_BYTES = 4_194_304
        const val MAX_ID_BYTES = 4096
        const val MAX_TITLE_BYTES = 65_536
        val RENEWED_KEYS = setOf("Data", "Signature")
        val ROOT_KEYS = setOf("kind", "version", "binding", "originalRow", "clearSharedPart", "encryptedSharedPart", "changed")
        val LOCAL_ROOT_KEYS = ROOT_KEYS + "localIntent"
    }
}
