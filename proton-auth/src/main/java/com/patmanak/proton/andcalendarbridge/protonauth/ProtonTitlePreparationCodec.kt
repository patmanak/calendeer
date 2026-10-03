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
    fun seal(binding: ProtonTitlePreparationBinding, fragments: ProtonTimestampedSharedTitle): ProtonProtectedTitlePreparation {
        val plain = structure {
            validateBinding(binding)
            val original = fragments.originalRow
            val clear = fragments.clearSharedPart
            val encrypted = fragments.encryptedSharedPart
            validateFragments(binding, original, clear, encrypted, fragments.changed)
            buildJsonObject {
                put("kind", KIND); put("version", 1); put("binding", binding.json())
                put("originalRow", original); put("clearSharedPart", clear); put("encryptedSharedPart", encrypted)
                put("changed", fragments.changed)
            }.toString().also { bounded(it); require(UniqueJsonKeys(it).validate()) }
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
            bounded(plain)
            require(UniqueJsonKeys(plain).validate())
            val root = Json.parseToJsonElement(plain) as? JsonObject ?: refuse()
            require(root.keys == ROOT_KEYS && root.strictString("kind") == KIND && root.strictNumber("version") == 1L)
            require(root.strictObject("binding") == expected.json())
            val changed = (root["changed"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: refuse()
            val original = root.strictObject("originalRow")
            val clear = root.strictObject("clearSharedPart")
            val encrypted = root.strictObject("encryptedSharedPart")
            validateFragments(expected, original, clear, encrypted, changed)
            ProtonTimestampedSharedTitle(original, clear, encrypted, changed)
        }
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
        const val MAX_PAYLOAD_BYTES = 4_194_304
        const val MAX_ID_BYTES = 4096
        const val MAX_TITLE_BYTES = 65_536
        val RENEWED_KEYS = setOf("Data", "Signature")
        val ROOT_KEYS = setOf("kind", "version", "binding", "originalRow", "clearSharedPart", "encryptedSharedPart", "changed")
    }
}
