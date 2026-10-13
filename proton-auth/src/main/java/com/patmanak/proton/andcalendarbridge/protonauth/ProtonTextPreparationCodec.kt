// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.*

internal enum class ProtonTextPreparationFailure { UNSUPPORTED, STORAGE }
internal class ProtonTextPreparationException(val reason: ProtonTextPreparationFailure) :
    Exception("TEXT_PREPARATION_${reason.name}")

internal val LOCAL_TEXT_PREPARATION_FORMAT = AdapterFormat("proton-calendar-offline-local-text-preparation", 1)

/** Disjoint protected history, not a current read receipt or a title-journal migration.
 * Reopening validates retained structure; it neither verifies signatures nor qualifies
 * encrypted text against the selection. Actual preparation belongs to the decryptor.
 */
internal class ProtonTextPreparationCodec(private val protector: EventPayloadProtector) {
    /** Bind the full frozen local observation, independently of historical title consent. */
    fun sealLocal(intent: LocalEditIntent, binding: ProtonTextPreparationBinding,
        fragments: ProtonTimestampedSharedText): LocalTextEditPreparation {
        val frozen = structure { LocalTextEditPreparation.freezeIntent(intent) }
        val plain = structure {
            validateLocalBinding(frozen, binding)
            JsonObject(envelope(binding, fragments) + mapOf("kind" to JsonPrimitive(LOCAL_KIND),
                "localIntent" to frozen.json())).encoded()
        }
        val protected = storage { protector.seal(plain) }
        return structure {
            bounded(protected)
            LocalTextEditPreparation(frozen, binding.timestamp, LOCAL_TEXT_PREPARATION_FORMAT, protected)
        }
    }

    /** Recover protected history without an external UID. No fresh crypto/access certification. */
    fun openLocal(record: LocalTextEditPreparation): ProtonTimestampedSharedText {
        val frozen = structure {
            require(record.format == LOCAL_TEXT_PREPARATION_FORMAT)
            bounded(record.protectedPayload)
            LocalTextEditPreparation.freezeIntent(record.intent)
        }
        val plain = storage { protector.open(record.protectedPayload) }
        return structure {
            bounded(plain); require(UniqueJsonKeys(plain).validate())
            val root = Json.parseToJsonElement(plain) as? JsonObject ?: refuse()
            require(root.keys == LOCAL_ROOT_KEYS && root.strictString("kind") == LOCAL_KIND && root.strictNumber("version") == 1L)
            require(root.strictObject("localIntent") == frozen.json())
            val bound = root.strictObject("binding")
            val selected = bound.strictObject("changes").map { (name, value) ->
                val change = value as? JsonObject ?: refuse()
                require(change.keys == setOf("expected", "replacement"))
                ProtonSharedTextProperty.valueOf(name) to ProtonSharedTextChange(
                    change.nullableString("expected"), change.nullableString("replacement"))
            }.toMap()
            val binding = ProtonTextPreparationBinding(frozen.scope, frozen.base.eventId, frozen.base.revision,
                bound.strictString("uid"), frozen.base.content.title, selected, record.timestamp)
            validateBinding(binding); validateLocalBinding(frozen, binding)
            require(bound == binding.json())
            val changed = (root["changed"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: refuse()
            val original = root.strictObject("originalRow")
            val clear = root.strictObject("clearSharedPart")
            val encrypted = root.strictObject("encryptedSharedPart")
            validateFragments(binding, original, clear, encrypted, changed)
            ProtonTimestampedSharedText(original, clear, encrypted, changed)
        }
    }

    fun seal(binding: ProtonTextPreparationBinding, fragments: ProtonTimestampedSharedText): ProtonProtectedTextPreparation {
        val plain = structure { envelope(binding, fragments).encoded() }
        val protected = storage { protector.seal(plain) }
        structure { bounded(protected) }
        return ProtonProtectedTextPreparation(binding.scope, binding.eventId, binding.revision, TEXT_PREPARATION_FORMAT, protected)
    }

    fun open(record: ProtonProtectedTextPreparation, expected: ProtonTextPreparationBinding): ProtonTimestampedSharedText {
        structure {
            validateBinding(expected)
            require(record.format == TEXT_PREPARATION_FORMAT && record.scope == expected.scope &&
                record.eventId == expected.eventId && record.revision == expected.revision)
            bounded(record.protectedPayload)
        }
        val plain = storage { protector.open(record.protectedPayload) }
        return structure {
            bounded(plain); require(UniqueJsonKeys(plain).validate())
            val root = Json.parseToJsonElement(plain) as? JsonObject ?: refuse()
            require(root.keys == ROOT_KEYS && root.strictString("kind") == KIND && root.strictNumber("version") == 1L)
            // Exact equality also binds property presence, explicit nulls, types and closed nested keys.
            require(root.strictObject("binding") == expected.json())
            val changed = (root["changed"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: refuse()
            val original = root.strictObject("originalRow")
            val clear = root.strictObject("clearSharedPart")
            val encrypted = root.strictObject("encryptedSharedPart")
            validateFragments(expected, original, clear, encrypted, changed)
            ProtonTimestampedSharedText(original, clear, encrypted, changed)
        }
    }

    private fun validateBinding(binding: ProtonTextPreparationBinding) {
        with(binding) {
            listOf(scope.account.value, scope.calendar.value, eventId.value, revision.value, uid).forEach {
                require(it.isNotBlank()); bounded(it, MAX_ID_BYTES)
            }
            text(expectedTitle)
            val selected = changes
            require(selected.isNotEmpty())
            selected.forEach { (field, change) ->
                change.expected?.let(::text); change.replacement?.let(::text)
                if (field == ProtonSharedTextProperty.SUMMARY) {
                    require(change.expected != null && change.replacement != null)
                    require(change.expected.replace("\r\n", "\n") == expectedTitle.replace("\r\n", "\n"))
                }
            }
            require(timestamp.nano == 0 && timestamp.atOffset(ZoneOffset.UTC).year in 1970..9999)
        }
    }

    private fun envelope(binding: ProtonTextPreparationBinding, fragments: ProtonTimestampedSharedText): JsonObject {
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

    private fun validateLocalBinding(intent: LocalEditIntent, binding: ProtonTextPreparationBinding) {
        require(binding.scope == intent.scope && binding.eventId == intent.base.eventId &&
            binding.revision == intent.base.revision && binding.expectedTitle == intent.base.content.title)
        fun EventContent.texts() = mapOf(ProtonSharedTextProperty.SUMMARY to title,
            ProtonSharedTextProperty.DESCRIPTION to description, ProtonSharedTextProperty.LOCATION to location)
        val before = intent.base.content.texts(); val after = intent.content.texts()
        val changed = before.keys.filter { before.getValue(it) != after.getValue(it) }.toSet()
        val selected = binding.changes
        require(selected.keys == changed)
        selected.forEach { (field, change) ->
            val old = before.getValue(field); val next = after.getValue(field)
            if (change.expected == null) require(field != ProtonSharedTextProperty.SUMMARY && old.isEmpty())
            else require(change.expected.replace("\r\n", "\n") == old.replace("\r\n", "\n"))
            require(change.replacement == if (field != ProtonSharedTextProperty.SUMMARY && next.isEmpty()) null else next)
        }
    }

    /** Exact historical projection, including null details, color and time precision/zone. */
    private fun LocalEditIntent.json() = buildJsonObject {
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
    }

    private fun EventContent.json() = buildJsonObject {
        put("title", title); put("description", description); put("location", location)
        put("color", colorRgb?.let(::JsonPrimitive) ?: JsonNull)
        put("details", if (details == null) JsonNull else buildJsonObject {})
        put("recurrence", JsonNull)
        put("time", buildJsonObject {
            when (val value = time) {
                is EventTime.AllDay -> { put("type", "ALL_DAY"); put("start", value.start.toString()); put("end", value.endExclusive.toString()) }
                is EventTime.Timed -> {
                    put("type", "TIMED"); put("start", value.start.toString()); put("end", value.end.toString()); put("zone", value.zone.id)
                }
            }
        })
    }

    private fun JsonObject.encoded() = toString().also { bounded(it); require(UniqueJsonKeys(it).validate()) }

    /** Historical shapes remain independent of the existing title codec and its two formats. */
    private fun validateFragments(binding: ProtonTextPreparationBinding, original: JsonObject,
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
        validatePart(originalClear, clear); validatePart(originalEncrypted, encrypted)
        require(changed == (originalClear != clear || originalEncrypted != encrypted))
    }

    private fun validatePart(original: JsonObject, replacement: JsonObject) {
        listOf(original, replacement).forEach { part ->
            listOf("Data", "Signature", "Author").forEach { require(part.strictString(it).isNotBlank()) }
        }
        require(original.keys == replacement.keys)
        require(JsonObject(original - RENEWED_KEYS) == JsonObject(replacement - RENEWED_KEYS))
        // Canonically equivalent text may retain signature bytes. Unchanged Data must retain everything.
        if (original["Data"] == replacement["Data"]) require(original == replacement)
    }

    private fun ProtonTextPreparationBinding.json() = buildJsonObject {
        put("scope", buildJsonObject {
            put("account", scope.account.value); put("calendar", scope.calendar.value); put("generation", scope.generation)
        })
        put("eventId", eventId.value); put("revision", revision.value); put("uid", uid)
        put("expectedTitle", expectedTitle); put("timestamp", timestamp.toString())
        val selected = changes
        put("changes", buildJsonObject {
            for (field in ProtonSharedTextProperty.entries) {
                val change = selected[field] ?: continue
                put(field.name, buildJsonObject {
                    put("expected", change.expected?.let(::JsonPrimitive) ?: JsonNull)
                    put("replacement", change.replacement?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        })
    }

    private fun JsonObject.strictString(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: refuse()
    private fun JsonObject.strictNumber(key: String): Long =
        (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: refuse()
    private fun JsonObject.strictObject(key: String): JsonObject = this[key] as? JsonObject ?: refuse()
    private fun JsonObject.strictArray(key: String): JsonArray = this[key] as? JsonArray ?: refuse()
    private fun JsonObject.nullableString(key: String): String? {
        require(containsKey(key))
        return if (this[key] == JsonNull) null else strictString(key)
    }

    private fun text(value: String) {
        bounded(value, MAX_TEXT_BYTES, allowEmpty = true)
        require(value.replace("\r\n", "\n").none { it.isISOControl() && it != '\n' && it != '\t' })
    }
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
        catch (_: Exception) { throw ProtonTextPreparationException(ProtonTextPreparationFailure.STORAGE) }
    private fun refuse(): Nothing = throw ProtonTextPreparationException(ProtonTextPreparationFailure.UNSUPPORTED)

    private companion object {
        const val KIND = "offline-text-preparation"
        const val LOCAL_KIND = "offline-local-text-preparation"
        const val MAX_PAYLOAD_BYTES = 4_194_304
        const val MAX_ID_BYTES = 4096
        const val MAX_TEXT_BYTES = 65_536
        val RENEWED_KEYS = setOf("Data", "Signature")
        val ROOT_KEYS = setOf("kind", "version", "binding", "originalRow", "clearSharedPart", "encryptedSharedPart", "changed")
        val LOCAL_ROOT_KEYS = ROOT_KEYS + "localIntent"
    }
}
