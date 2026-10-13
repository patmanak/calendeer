// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSharedTextProperty.*
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Fabricated fragments verify protected structure only, not Proton cryptography. */
class ProtonTextPreparationCodecTest {
    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 2)
    private val changes = linkedMapOf(DESCRIPTION to ProtonSharedTextChange("Notes", "New notes"),
        LOCATION to ProtonSharedTextChange(null, ""))
    private fun binding(scope: CalendarScope = this.scope, event: EventId = EventId("synthetic-event"),
        revision: Revision = Revision("opaque-revision"), uid: String = "synthetic-uid", title: String = "Original",
        changes: Map<ProtonSharedTextProperty, ProtonSharedTextChange> = this.changes,
        time: Instant = Instant.parse("2026-10-03T12:00:00Z")) =
        ProtonTextPreparationBinding(scope, event, revision, uid, title, changes, time)
    private class MemoryProtector : EventPayloadProtector {
        var plain = ""; var sealed = 0; var opened = 0
        override fun seal(plain: String): String { this.plain = plain; sealed++; return "protected-fixture" }
        override fun open(protected: String): String { opened++; return plain }
    }
    private fun part(type: Int, data: String, signature: String) = buildJsonObject {
        put("Type", type); put("Data", data); put("Signature", signature); put("Author", "synthetic@example.invalid")
        putJsonObject("Opaque") { put("nested", JsonArray(listOf(JsonPrimitive(1), JsonNull))) }
    }
    private fun fixture(changed: Boolean = true): ProtonTimestampedSharedText {
        val clear = part(2, "original-clear", "original-clear-signature")
        val secret = part(3, "original-secret", "original-secret-signature")
        val row = buildJsonObject {
            put("ID", "synthetic-event"); put("CalendarID", scope.calendar.value); put("UID", "synthetic-uid")
            put("SharedKeyPacket", "original-key-packet"); put("SharedEvents", JsonArray(listOf(clear, secret)))
            put("CalendarEvents", JsonArray(emptyList())); put("AttendeesEvents", JsonArray(emptyList()))
            putJsonObject("UnknownRowData") { put("future", JsonArray(listOf(JsonNull, JsonPrimitive("kept")))) }
        }
        return ProtonTimestampedSharedText(row, if (changed) part(2, "new-clear", "new-clear-signature") else clear,
            if (changed) part(3, "new-secret", "new-secret-signature") else secret, changed)
    }
    private fun failure(reason: ProtonTextPreparationFailure = ProtonTextPreparationFailure.UNSUPPORTED, action: () -> Unit) {
        val failure = assertThrows(ProtonTextPreparationException::class.java) { action() }
        assertEquals(reason, failure.reason); assertEquals("TEXT_PREPARATION_${reason.name}", failure.message)
        assertNull(failure.cause); assertFalse(failure.toString().contains("synthetic-sensitive"))
    }
    private fun mutate(root: JsonObject, key: String, value: JsonElement) = JsonObject(root + (key to value))
    private fun assertFragments(expected: ProtonTimestampedSharedText, actual: ProtonTimestampedSharedText) {
        assertEquals(expected.originalRow, actual.originalRow); assertEquals(expected.clearSharedPart, actual.clearSharedPart)
        assertEquals(expected.encryptedSharedPart, actual.encryptedSharedPart); assertEquals(expected.changed, actual.changed)
    }

    @Test fun newCodecAndProtectorInstancesReopenExactFragmentsAndNullableBindings() {
        val writer = MemoryProtector(); val input = fixture(); val expected = binding()
        val record = ProtonTextPreparationCodec(writer).seal(expected, input)
        assertEquals(TEXT_PREPARATION_FORMAT, record.format); assertEquals(expected.scope, record.scope)
        assertEquals(expected.eventId, record.eventId); assertEquals(expected.revision, record.revision)
        val reader = MemoryProtector().apply { plain = writer.plain }
        val opened = ProtonTextPreparationCodec(reader).open(record, expected)
        assertFragments(input, opened); assertNotSame(opened.originalRow, opened.originalRow)
        assertEquals("original-key-packet", opened.originalRow.string("SharedKeyPacket"))
        assertFalse(record.toString().contains(record.protectedPayload))
        assertFalse(writer.plain.contains("bootstrap"))
    }
    @Test fun noOpAndSingleChangedFragmentPreserveOriginalPartsExactly() {
        val protector = MemoryProtector(); val codec = ProtonTextPreparationCodec(protector)
        val noOp = fixture(false)
        assertFragments(noOp, codec.open(codec.seal(binding(), noOp), binding()))
        val changed = fixture(); val clear = changed.originalRow.array("SharedEvents")[0] as JsonObject
        val mixed = ProtonTimestampedSharedText(changed.originalRow, clear, changed.encryptedSharedPart, true)
        assertFragments(mixed, codec.open(codec.seal(binding(), mixed), binding()))
        val sameSignature = mutate(changed.clearSharedPart, "Signature", clear.getValue("Signature"))
        val canonical = ProtonTimestampedSharedText(changed.originalRow, sameSignature, changed.encryptedSharedPart, true)
        assertFragments(canonical, codec.open(codec.seal(binding(), canonical), binding()))
    }
    @Test fun everyBindingAndSelectedPropertyExpectationIsRequired() {
        val protector = MemoryProtector(); val codec = ProtonTextPreparationCodec(protector)
        val record = codec.seal(binding(), fixture())
        val variants = listOf(binding(scope = scope.copy(account = AccountId("other"))),
            binding(scope = scope.copy(calendar = CalendarId("other"))), binding(scope = scope.copy(generation = 3)),
            binding(event = EventId("other")), binding(revision = Revision("other")), binding(uid = "other"),
            binding(title = "other"), binding(time = binding().timestamp.plusSeconds(1)),
            binding(changes = mapOf(DESCRIPTION to changes.getValue(DESCRIPTION))),
            binding(changes = changes + (LOCATION to ProtonSharedTextChange("", ""))),
            binding(changes = changes + (LOCATION to ProtonSharedTextChange(null, null))),
            binding(changes = changes + (DESCRIPTION to ProtonSharedTextChange("Other", "New notes"))),
            binding(changes = changes + (DESCRIPTION to ProtonSharedTextChange("Notes", "Other"))))
        variants.forEach { failure { codec.open(record, it) } }
        // Map order has no meaning; exact values and property presence do.
        assertFragments(fixture(), codec.open(record, binding(changes = changes.entries.reversed().associate { it.toPair() })))
    }
    @Test fun wrongHeadersAndHistoricalFormatsRefuseBeforeProtectorAccess() {
        val protector = MemoryProtector(); val codec = ProtonTextPreparationCodec(protector)
        val record = codec.seal(binding(), fixture())
        val formats = listOf(TITLE_PREPARATION_FORMAT, LOCAL_TITLE_PREPARATION_FORMAT,
            AdapterFormat(TEXT_PREPARATION_FORMAT.id, 2), AdapterFormat("other", 1))
        val variants = formats.map { ProtonProtectedTextPreparation(record.scope, record.eventId, record.revision, it, record.protectedPayload) } +
            listOf(ProtonProtectedTextPreparation(scope.copy(generation = 9), record.eventId, record.revision, record.format, record.protectedPayload),
                ProtonProtectedTextPreparation(scope, EventId("other"), record.revision, record.format, record.protectedPayload),
                ProtonProtectedTextPreparation(scope, record.eventId, Revision("other"), record.format, record.protectedPayload))
        variants.forEach { failure { codec.open(it, binding()) } }; assertEquals(0, protector.opened)
    }
    @Test fun duplicateEscapedKeysAndNestedDuplicateFieldsAreRejected() {
        val protector = MemoryProtector(); val codec = ProtonTextPreparationCodec(protector)
        val record = codec.seal(binding(), fixture()); val good = protector.plain
        listOf(good.replace("\"kind\":", "\"k\\u0069nd\":\"duplicate\",\"kind\":"),
            good.replace("\"nested\":", "\"nested\":[],\"nested\":"),
            good.replace("\"expected\":", "\"expected\":null,\"expected\":"))
            .forEach { protector.plain = it; failure { codec.open(record, binding()) } }
    }
    @Test fun envelopeKindVersionAndTypesAreClosedIncludingNestedChanges() {
        val protector = MemoryProtector(); val codec = ProtonTextPreparationCodec(protector)
        val record = codec.seal(binding(), fixture()); val good = Json.parseToJsonElement(protector.plain) as JsonObject
        val bound = good.obj("binding"); val selected = bound.obj("changes")
        val wrong = listOf(JsonObject(good + ("unknown" to JsonNull)), mutate(good, "version", JsonPrimitive("1")),
            mutate(good, "kind", JsonPrimitive("offline-title-preparation")), mutate(good, "kind", JsonPrimitive("offline-local-title-preparation")),
            mutate(good, "changed", JsonPrimitive("true")), mutate(good, "originalRow", JsonNull),
            mutate(good, "binding", JsonObject(bound + ("unknown" to JsonNull))),
            mutate(good, "binding", mutate(bound, "changes", JsonObject(selected + ("X-FUTURE" to JsonNull)))),
            mutate(good, "binding", mutate(bound, "changes", mutate(selected, "LOCATION", buildJsonObject { put("replacement", "") }))),
            mutate(good, "binding", mutate(bound, "changes", mutate(selected, "LOCATION", buildJsonObject { put("expected", 1); put("replacement", "") }))))
        wrong.forEach { protector.plain = it.toString(); failure { codec.open(record, binding()) } }
        listOf("", "[]", "null", "{broken}").forEach { protector.plain = it; failure { codec.open(record, binding()) } }
    }
    @Test fun selectedSummaryAndEncodingBudgetsAreValidatedBeforeSealing() {
        val protector = MemoryProtector(); val codec = ProtonTextPreparationCodec(protector)
        val bad = listOf(binding(changes = emptyMap()), binding(changes = mapOf(SUMMARY to ProtonSharedTextChange(null, "New"))),
            binding(changes = mapOf(SUMMARY to ProtonSharedTextChange("Original", null))),
            binding(changes = mapOf(SUMMARY to ProtonSharedTextChange("Stale", "New"))),
            binding(uid = " "), binding(uid = "x".repeat(4097)), binding(title = "bad\uD800"),
            binding(changes = mapOf(LOCATION to ProtonSharedTextChange(null, "bad\u0000"))),
            binding(changes = mapOf(DESCRIPTION to ProtonSharedTextChange("Notes", "é".repeat(32769)))),
            binding(time = binding().timestamp.plusNanos(1)), binding(time = Instant.parse("1969-12-31T23:59:59Z")),
            binding(time = Instant.parse("+10000-01-01T00:00:00Z")))
        bad.forEach { failure { codec.seal(it, fixture()) } }; assertEquals(0, protector.sealed)
        val normalized = binding(title = "Line\r\nTwo", changes = mapOf(SUMMARY to ProtonSharedTextChange("Line\nTwo", "New")))
        codec.seal(normalized, fixture()) // Equality is logical here; sealed binding remains exact.
    }
    @Test fun originalProfileAndOpaqueFragmentsAreCheckedOnSealAndReopen() {
        val protector = MemoryProtector(); val codec = ProtonTextPreparationCodec(protector); val input = fixture()
        val record = codec.seal(binding(), input); val good = Json.parseToJsonElement(protector.plain) as JsonObject
        val row = input.originalRow
        val rows = listOf(mutate(row, "ID", JsonPrimitive("other")), mutate(row, "UID", JsonPrimitive("other")),
            mutate(row, "CalendarID", JsonPrimitive("other")), JsonObject(row - "SharedKeyPacket"),
            mutate(row, "SharedKeyPacket", JsonPrimitive(" ")), mutate(row, "AddressKeyPacket", JsonPrimitive("")),
            mutate(row, "CalendarEvents", JsonArray(listOf(JsonObject(emptyMap())))), JsonObject(row - "AttendeesEvents"),
            mutate(row, "SharedEvents", JsonArray(listOf(input.clearSharedPart, input.clearSharedPart))))
        rows.forEach {
            failure { codec.seal(binding(), ProtonTimestampedSharedText(it, input.clearSharedPart, input.encryptedSharedPart, true)) }
            protector.plain = mutate(good, "originalRow", it).toString(); failure { codec.open(record, binding()) }
        }
        val wrongParts = listOf(mutate(input.clearSharedPart, "Author", JsonPrimitive("other@example.invalid")),
            JsonObject(input.clearSharedPart + ("unknown" to JsonNull)), JsonObject(input.clearSharedPart - "Opaque"),
            mutate(input.clearSharedPart, "Signature", JsonPrimitive(1)), mutate(input.clearSharedPart, "Data", JsonPrimitive(" ")))
        wrongParts.forEach {
            failure { codec.seal(binding(), ProtonTimestampedSharedText(row, it, input.encryptedSharedPart, true)) }
            protector.plain = mutate(good, "clearSharedPart", it).toString(); failure { codec.open(record, binding()) }
        }
    }
    @Test fun inconsistentChangeFlagsAndResignedUnchangedDataRefuse() {
        val protector = MemoryProtector(); val codec = ProtonTextPreparationCodec(protector); val input = fixture()
        val noOp = fixture(false)
        failure { codec.seal(binding(), ProtonTimestampedSharedText(input.originalRow, input.clearSharedPart, input.encryptedSharedPart, false)) }
        failure { codec.seal(binding(), ProtonTimestampedSharedText(noOp.originalRow, noOp.clearSharedPart, noOp.encryptedSharedPart, true)) }
        val altered = mutate(noOp.clearSharedPart, "Signature", JsonPrimitive("gratuitous-signature"))
        failure { codec.seal(binding(), ProtonTimestampedSharedText(noOp.originalRow, altered, noOp.encryptedSharedPart, true)) }
        val record = codec.seal(binding(), input); val good = Json.parseToJsonElement(protector.plain) as JsonObject
        protector.plain = mutate(good, "changed", JsonPrimitive(false)).toString(); failure { codec.open(record, binding()) }
    }
    @Test fun plaintextAndProtectedBudgetsBoundBothDirections() {
        val protector = MemoryProtector(); val codec = ProtonTextPreparationCodec(protector); val input = fixture()
        val huge = mutate(input.originalRow, "Huge", JsonPrimitive("é".repeat(2_097_152)))
        failure { codec.seal(binding(), ProtonTimestampedSharedText(huge, input.clearSharedPart, input.encryptedSharedPart, true)) }
        val record = codec.seal(binding(), input)
        listOf("", "x".repeat(4_194_305), "é".repeat(2_097_153), "bad\uD800").forEach { bad ->
            protector.plain = bad; failure { codec.open(record, binding()) }
            failure { codec.open(ProtonProtectedTextPreparation(scope, record.eventId, record.revision, record.format, bad), binding()) }
            val invalid = object : EventPayloadProtector {
                override fun seal(plain: String) = bad
                override fun open(protected: String) = error("unused")
            }
            failure { ProtonTextPreparationCodec(invalid).seal(binding(), input) }
        }
    }
    @Test fun storageFailuresAreSanitizedAndCancellationIsNotConverted() {
        val storage = object : EventPayloadProtector {
            override fun seal(plain: String): String = error("synthetic-sensitive")
            override fun open(protected: String): String = error("synthetic-sensitive")
        }
        failure(ProtonTextPreparationFailure.STORAGE) { ProtonTextPreparationCodec(storage).seal(binding(), fixture()) }
        val record = ProtonProtectedTextPreparation(scope, binding().eventId, binding().revision, TEXT_PREPARATION_FORMAT, "protected-fixture")
        failure(ProtonTextPreparationFailure.STORAGE) { ProtonTextPreparationCodec(storage).open(record, binding()) }
        val cancelled = CancellationException("synthetic-cancelled")
        val cancelling = object : EventPayloadProtector {
            override fun seal(plain: String): String = throw cancelled
            override fun open(protected: String): String = throw cancelled
        }
        assertSame(cancelled, assertThrows(CancellationException::class.java) { ProtonTextPreparationCodec(cancelling).seal(binding(), fixture()) })
        assertSame(cancelled, assertThrows(CancellationException::class.java) { ProtonTextPreparationCodec(cancelling).open(record, binding()) })
    }
}
