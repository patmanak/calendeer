// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Fabricated parts exercise the codec contract, not cryptographic compatibility. */
class ProtonTitlePreparationCodecTest {
    private val binding = ProtonTitlePreparationBinding(
        CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 2),
        EventId("synthetic-event"), Revision("opaque-revision"), "synthetic-uid", "Original", "Changed",
        Instant.parse("2026-10-03T12:00:00Z"))
    private class MemoryProtector : EventPayloadProtector {
        var plain = ""; var opened = 0; var sealed = 0
        override fun seal(plain: String): String { this.plain = plain; sealed++; return "protected-fixture" }
        override fun open(protected: String): String { opened++; return plain }
    }
    private fun part(type: Int, data: String, signature: String) = buildJsonObject {
        put("Type", type); put("Data", data); put("Signature", signature); put("Author", "synthetic@example.invalid")
        put("Opaque", buildJsonObject { put("nested", JsonArray(listOf(JsonPrimitive(1), JsonNull))) })
    }
    private fun fixture(changed: Boolean = true): ProtonTimestampedSharedTitle {
        val clear = part(2, "original-clear", "original-clear-signature")
        val secret = part(3, "original-secret", "original-secret-signature")
        val row = buildJsonObject {
            put("ID", binding.eventId.value); put("CalendarID", binding.scope.calendar.value); put("UID", binding.uid)
            put("SharedKeyPacket", "original-key-packet"); put("SharedEvents", JsonArray(listOf(clear, secret)))
            put("CalendarEvents", JsonArray(emptyList())); put("AttendeesEvents", JsonArray(emptyList()))
            put("Opaque", "original-metadata")
        }
        return ProtonTimestampedSharedTitle(row, if (changed) part(2, "new-clear", "new-clear-signature") else clear,
            if (changed) part(3, "new-secret", "new-secret-signature") else secret, changed)
    }
    private fun alternate(scope: CalendarScope = binding.scope, event: EventId = binding.eventId,
        revision: Revision = binding.revision, uid: String = binding.uid, old: String = binding.expectedTitle,
        next: String = binding.newTitle, time: Instant = binding.timestamp) =
        ProtonTitlePreparationBinding(scope, event, revision, uid, old, next, time)
    private fun failure(reason: ProtonTitlePreparationFailure = ProtonTitlePreparationFailure.UNSUPPORTED, block: () -> Unit) {
        try { block(); fail("Expected closed refusal") } catch (e: ProtonTitlePreparationException) {
            assertEquals(reason, e.reason); assertNull(e.cause); assertEquals("TITLE_PREPARATION_${reason.name}", e.message)
        }
    }
    private fun mutate(value: JsonObject, key: String, replacement: JsonElement) = JsonObject(value + (key to replacement))

    @Test fun protectedRoundTripRetainsSeparateFragmentsAndFrozenOriginal() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector); val input = fixture()
        val record = codec.seal(binding, input)
        assertEquals(TITLE_PREPARATION_FORMAT, record.format); assertEquals(binding.scope, record.scope)
        assertEquals(binding.eventId, record.eventId); assertEquals(binding.revision, record.revision)
        assertEquals("protected-fixture", record.protectedPayload)
        val opened = codec.open(record, binding)
        assertEquals(input.originalRow, opened.originalRow); assertEquals(input.clearSharedPart, opened.clearSharedPart)
        assertEquals(input.encryptedSharedPart, opened.encryptedSharedPart); assertTrue(opened.changed)
        assertNotSame(opened.originalRow, opened.originalRow)
        assertEquals("original-key-packet", opened.originalRow.string("SharedKeyPacket"))
    }
    @Test fun noOpPreservesAllOriginalPartsExactly() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector); val input = fixture(false)
        val actual = codec.open(codec.seal(binding, input), binding)
        assertFalse(actual.changed); assertEquals(input.originalRow, actual.originalRow)
        assertEquals(input.clearSharedPart, actual.clearSharedPart); assertEquals(input.encryptedSharedPart, actual.encryptedSharedPart)
    }
    @Test fun everyExpectedBindingFieldIsRequired() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector); val record = codec.seal(binding, fixture())
        val wrong = listOf(
            alternate(scope = binding.scope.copy(account = AccountId("other"))),
            alternate(scope = binding.scope.copy(calendar = CalendarId("other"))),
            alternate(scope = binding.scope.copy(generation = 3)), alternate(event = EventId("other")),
            alternate(revision = Revision("other")), alternate(uid = "other"), alternate(old = "other"),
            alternate(next = "other"), alternate(time = binding.timestamp.plusSeconds(1)))
        wrong.forEach { failure { codec.open(record, it) } }
    }
    @Test fun publicHeadersAndFormatAreBoundBeforeDecrypting() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector); val record = codec.seal(binding, fixture())
        val variants = listOf(
            ProtonProtectedTitlePreparation(record.scope.copy(generation = 4), record.eventId, record.revision, record.format, record.protectedPayload),
            ProtonProtectedTitlePreparation(record.scope, EventId("other"), record.revision, record.format, record.protectedPayload),
            ProtonProtectedTitlePreparation(record.scope, record.eventId, Revision("other"), record.format, record.protectedPayload),
            ProtonProtectedTitlePreparation(record.scope, record.eventId, record.revision, AdapterFormat(record.format.id, 2), record.protectedPayload),
            ProtonProtectedTitlePreparation(record.scope, record.eventId, record.revision, AdapterFormat("other", 1), record.protectedPayload))
        variants.forEach { failure { codec.open(it, binding) } }; assertEquals(0, protector.opened)
    }
    @Test fun duplicateEscapedAndNestedNamesAreRejectedBeforeJsonDropsThem() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector); val record = codec.seal(binding, fixture())
        val good = protector.plain
        listOf(good.replace("\"kind\":", "\"k\\u0069nd\":\"duplicate\",\"kind\":"),
            good.replace("\"nested\":", "\"nested\":[],\"nested\":"),
            good.replace("\"uid\":", "\"uid\":\"duplicate\",\"uid\":"))
            .forEach { protector.plain = it; failure { codec.open(record, binding) } }
    }
    @Test fun envelopeBindingAndBooleanShapesAreStrict() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector); val record = codec.seal(binding, fixture())
        val good = Json.parseToJsonElement(protector.plain) as JsonObject
        val wrong = listOf(JsonObject(good + ("unknown" to JsonNull)), mutate(good, "version", JsonPrimitive("1")),
            mutate(good, "kind", JsonPrimitive("other")), mutate(good, "changed", JsonPrimitive("true")),
            mutate(good, "binding", JsonObject(good.obj("binding") + ("unknown" to JsonNull))),
            mutate(good, "originalRow", JsonNull), mutate(good, "clearSharedPart", JsonArray(emptyList())))
        wrong.forEach { protector.plain = it.toString(); failure { codec.open(record, binding) } }
    }
    @Test fun identityPersonalPartitionsAndSharedPartLayoutAreRequired() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector); val input = fixture()
        val original = input.originalRow
        val wrong = listOf(mutate(original, "ID", JsonPrimitive("other")), mutate(original, "CalendarID", JsonPrimitive("other")),
            mutate(original, "UID", JsonPrimitive("other")), mutate(original, "CalendarEvents", JsonArray(listOf(JsonObject(emptyMap())))),
            JsonObject(original - "AttendeesEvents"), mutate(original, "SharedEvents", JsonArray(listOf(input.clearSharedPart))),
            mutate(original, "SharedEvents", JsonArray(listOf(input.clearSharedPart, input.clearSharedPart))))
        wrong.forEach { failure { codec.seal(binding, ProtonTimestampedSharedTitle(it, input.clearSharedPart, input.encryptedSharedPart, true)) } }
        assertEquals(0, protector.sealed)
    }
    @Test fun originalSharedKeyProfileIsRequiredOnSealAndOpen() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector); val input = fixture()
        val record = codec.seal(binding, input); val good = Json.parseToJsonElement(protector.plain) as JsonObject
        val original = input.originalRow
        val wrong = listOf(JsonObject(original - "SharedKeyPacket"),
            mutate(original, "SharedKeyPacket", JsonNull), mutate(original, "SharedKeyPacket", JsonPrimitive(1)),
            mutate(original, "SharedKeyPacket", JsonPrimitive(" ")),
            mutate(original, "AddressKeyPacket", JsonPrimitive("alternate-packet")),
            mutate(original, "AddressKeyPacket", JsonPrimitive("")),
            mutate(original, "AddressKeyPacket", JsonObject(emptyMap())),
            mutate(original, "AddressKeyPacket", JsonPrimitive(1)))
        wrong.forEach { row ->
            failure { codec.seal(binding, ProtonTimestampedSharedTitle(row, input.clearSharedPart, input.encryptedSharedPart, true)) }
            protector.plain = mutate(good, "originalRow", row).toString()
            failure { codec.open(record, binding) }
        }
        val explicitNull = ProtonTimestampedSharedTitle(mutate(original, "AddressKeyPacket", JsonNull),
            input.clearSharedPart, input.encryptedSharedPart, true)
        assertEquals(explicitNull.originalRow, codec.open(codec.seal(binding, explicitNull), binding).originalRow)
    }
    @Test fun fragmentsCannotChangeOpaqueMetadataOrResignUnchangedData() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector); val input = fixture()
        val originalClear = input.originalRow.array("SharedEvents")[0] as JsonObject
        val wrong = listOf(mutate(input.clearSharedPart, "Author", JsonPrimitive("other@example.invalid")),
            JsonObject(input.clearSharedPart + ("extra" to JsonNull)), JsonObject(input.clearSharedPart - "Opaque"),
            mutate(input.clearSharedPart, "Opaque", JsonNull), mutate(input.clearSharedPart, "Data", JsonPrimitive(" ")),
            mutate(input.clearSharedPart, "Signature", JsonPrimitive(1)),
            mutate(originalClear, "Signature", JsonPrimitive("gratuitous-signature")))
        wrong.forEach { failure { codec.seal(binding, ProtonTimestampedSharedTitle(input.originalRow, it, input.encryptedSharedPart, true)) } }
        assertEquals(0, protector.sealed)
    }
    @Test fun changedFlagMatchesActualPartDifferencesOnSealAndReopen() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector); val changed = fixture(); val noop = fixture(false)
        failure { codec.seal(binding, ProtonTimestampedSharedTitle(changed.originalRow, changed.clearSharedPart, changed.encryptedSharedPart, false)) }
        failure { codec.seal(binding, ProtonTimestampedSharedTitle(noop.originalRow, noop.clearSharedPart, noop.encryptedSharedPart, true)) }
        val record = codec.seal(binding, changed); val root = Json.parseToJsonElement(protector.plain) as JsonObject
        protector.plain = mutate(root, "changed", JsonPrimitive(false)).toString(); failure { codec.open(record, binding) }
    }
    @Test fun reopenChecksFragmentsAndOriginalWithoutTrustingProtectedJsonShape() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector); val input = fixture()
        val record = codec.seal(binding, input); val good = Json.parseToJsonElement(protector.plain) as JsonObject
        val wrong = listOf(
            mutate(good, "originalRow", mutate(input.originalRow, "UID", JsonPrimitive("other"))),
            mutate(good, "originalRow", mutate(input.originalRow, "AttendeesEvents", JsonArray(listOf(JsonNull)))),
            mutate(good, "clearSharedPart", mutate(input.clearSharedPart, "Opaque", JsonNull)),
            mutate(good, "encryptedSharedPart", mutate(input.encryptedSharedPart, "Signature", JsonPrimitive(" "))))
        wrong.forEach { protector.plain = it.toString(); failure { codec.open(record, binding) } }
    }
    @Test fun unchangedClearPartCanRetainItsOriginalSignatureAndOpaqueFields() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector); val input = fixture()
        val clear = input.originalRow.array("SharedEvents")[0] as JsonObject
        val mixed = ProtonTimestampedSharedTitle(input.originalRow, clear, input.encryptedSharedPart, true)
        val actual = codec.open(codec.seal(binding, mixed), binding)
        assertEquals(clear, actual.clearSharedPart); assertEquals(input.encryptedSharedPart, actual.encryptedSharedPart)
        assertTrue(actual.changed)
    }
    @Test fun changedDataDoesNotRequireDistinctCanonicalTextSignatureBytes() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector); val input = fixture()
        val originalClear = input.originalRow.array("SharedEvents")[0] as JsonObject
        val clear = mutate(input.clearSharedPart, "Signature", originalClear.getValue("Signature"))
        val actual = codec.open(codec.seal(binding,
            ProtonTimestampedSharedTitle(input.originalRow, clear, input.encryptedSharedPart, true)), binding)
        assertEquals(clear, actual.clearSharedPart); assertTrue(actual.changed)
    }
    @Test fun invalidTimesAndOversizedBindingValuesAreRejected() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector)
        listOf(alternate(time = binding.timestamp.plusNanos(1)), alternate(time = Instant.parse("1969-12-31T23:59:59Z")),
            alternate(time = Instant.parse("+10000-01-01T00:00:00Z")), alternate(uid = " "),
            alternate(uid = "x".repeat(4097)), alternate(next = "é".repeat(32769)))
            .forEach { failure { codec.seal(it, fixture()) } }
        assertEquals(0, protector.sealed)
    }
    @Test fun plaintextAndProtectedPayloadBudgetsAreCheckedOnBothSides() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector); val input = fixture()
        val huge = mutate(input.originalRow, "Huge", JsonPrimitive("é".repeat(2_097_152)))
        failure { codec.seal(binding, ProtonTimestampedSharedTitle(huge, input.clearSharedPart, input.encryptedSharedPart, true)) }
        val record = codec.seal(binding, input)
        protector.plain = " ".repeat(4_194_305); failure { codec.open(record, binding) }
        protector.plain = "é".repeat(2_097_153); failure { codec.open(record, binding) }
        listOf("", "x".repeat(4_194_305), "é".repeat(2_097_153)).forEach {
            failure { codec.open(ProtonProtectedTitlePreparation(record.scope, record.eventId, record.revision, record.format, it), binding) }
        }
        listOf("", "x".repeat(4_194_305), "é".repeat(2_097_153)).forEach { protected ->
            val oversized = object : EventPayloadProtector { override fun seal(plain: String) = protected; override fun open(protected: String) = error("unused") }
            failure { ProtonTitlePreparationCodec(oversized).seal(binding, input) }
        }
    }
    @Test fun storageFailuresAreClosedAndCancellationPropagates() {
        val storage = object : EventPayloadProtector {
            override fun seal(plain: String): String = error("synthetic-sensitive-message")
            override fun open(protected: String): String = error("synthetic-sensitive-message")
        }
        failure(ProtonTitlePreparationFailure.STORAGE) { ProtonTitlePreparationCodec(storage).seal(binding, fixture()) }
        val record = ProtonProtectedTitlePreparation(binding.scope, binding.eventId, binding.revision, TITLE_PREPARATION_FORMAT, "protected")
        failure(ProtonTitlePreparationFailure.STORAGE) { ProtonTitlePreparationCodec(storage).open(record, binding) }
        val cancellation = CancellationException("synthetic-cancellation")
        val cancelling = object : EventPayloadProtector {
            override fun seal(plain: String): String = throw cancellation
            override fun open(protected: String): String = throw cancellation
        }
        try { ProtonTitlePreparationCodec(cancelling).seal(binding, fixture()); fail() } catch (actual: CancellationException) { assertSame(cancellation, actual) }
        try { ProtonTitlePreparationCodec(cancelling).open(record, binding); fail() } catch (actual: CancellationException) { assertSame(cancellation, actual) }
    }
    @Test fun diagnosticsContainNoBindingContentsOrPayload() {
        val record = ProtonProtectedTitlePreparation(binding.scope, binding.eventId, binding.revision, TITLE_PREPARATION_FORMAT, "private-payload")
        assertEquals("ProtonTitlePreparationBinding(redacted)", binding.toString())
        assertEquals("ProtonProtectedTitlePreparation(redacted)", record.toString())
    }
    @Test fun malformedUnicodeAndOverdeepJsonAreRefusedWithoutInputDiagnostics() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector)
        failure { codec.seal(alternate(next = "\uD800"), fixture()) }
        val record = codec.seal(binding, fixture())
        protector.plain = "[".repeat(66) + "null" + "]".repeat(66)
        failure { codec.open(record, binding) }
        protector.plain = "{broken-json"; failure { codec.open(record, binding) }
    }
}
