// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Fabricated fragments test local recovery bindings, not signatures or Proton service acceptance. */
class ProtonLocalTitlePreparationCodecTest {
    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 2)
    private val event = EventId("synthetic-event")
    private val revision = Revision("opaque-revision")
    private val uid = "synthetic-uid"
    private val timestamp = Instant.parse("2026-10-03T12:00:00Z")
    private val original = EventContent("Original", EventTime.Timed(
        Instant.parse("2026-10-04T12:00:00Z"), Instant.parse("2026-10-04T13:00:00Z"), ZoneId.of("Europe/Paris")),
        "Synthetic description", "Synthetic location", 0x6849D3, details = EventDetails())
    private fun intent(base: EventContent = original, title: String = "Changed") = LocalEditIntent(
        MutationId("synthetic-edit"), scope, AdapterFormat("synthetic-observation", 1),
        RemoteChange.Upsert(event, revision, base), base.copy(title = title))
    private class MemoryProtector : EventPayloadProtector {
        var plain = ""; var opened = 0; var sealed = 0
        var beforeSeal: (() -> Unit)? = null
        override fun seal(plain: String): String {
            this.plain = plain; sealed++; beforeSeal?.invoke(); return "protected-local-fixture"
        }
        override fun open(protected: String): String { opened++; return plain }
    }
    private fun part(type: Int, data: String, signature: String) = buildJsonObject {
        put("Type", type); put("Data", data); put("Signature", signature); put("Author", "synthetic@example.invalid")
        put("Opaque", buildJsonObject { put("nested", JsonArray(listOf(JsonPrimitive(1), JsonNull))) })
    }
    private fun fixture(): ProtonTimestampedSharedTitle {
        val clear = part(2, "original-clear", "original-clear-signature")
        val secret = part(3, "original-secret", "original-secret-signature")
        val row = buildJsonObject {
            put("ID", event.value); put("CalendarID", scope.calendar.value); put("UID", uid)
            put("SharedKeyPacket", "original-key-packet"); put("SharedEvents", JsonArray(listOf(clear, secret)))
            put("CalendarEvents", JsonArray(emptyList())); put("AttendeesEvents", JsonArray(emptyList()))
            put("Opaque", "original-metadata")
        }
        return ProtonTimestampedSharedTitle(row, part(2, "new-clear", "new-clear-signature"),
            part(3, "new-secret", "new-secret-signature"), true)
    }
    private fun failure(reason: ProtonTitlePreparationFailure = ProtonTitlePreparationFailure.UNSUPPORTED, block: () -> Unit) {
        try { block(); fail("Expected closed refusal") } catch (actual: ProtonTitlePreparationException) {
            assertEquals(reason, actual.reason); assertNull(actual.cause)
            assertEquals("TITLE_PREPARATION_${reason.name}", actual.message)
        }
    }
    private fun mutate(value: JsonObject, key: String, replacement: JsonElement) = JsonObject(value + (key to replacement))
    private fun withBase(value: LocalEditIntent, content: EventContent) = value.copy(
        base = value.base.copy(content = content), content = content.copy(title = value.content.title))
    private fun sameFragments(expected: ProtonTimestampedSharedTitle, actual: ProtonTimestampedSharedTitle) {
        assertEquals(expected.originalRow, actual.originalRow); assertEquals(expected.clearSharedPart, actual.clearSharedPart)
        assertEquals(expected.encryptedSharedPart, actual.encryptedSharedPart); assertEquals(expected.changed, actual.changed)
    }

    @Test fun protectedLocalPreparationOpensWithoutExternalUidOrBinding() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector); val source = fixture()
        val value = intent(); val record = codec.sealLocal(value, uid, timestamp, source)
        assertEquals(value, record.intent); assertEquals(timestamp, record.timestamp)
        assertEquals(LOCAL_TITLE_PREPARATION_FORMAT, record.format)
        val recovered = ProtonTitlePreparationCodec(protector).openLocal(record)
        sameFragments(source, recovered); assertEquals(uid, recovered.originalRow.string("UID"))
        assertNotSame(recovered.originalRow, recovered.originalRow)
    }

    @Test fun allDayAndLegacyUnknownDetailsRemainDistinctRecoverableObservations() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector)
        val day = original.copy(time = EventTime.AllDay(LocalDate.parse("2026-10-04"), LocalDate.parse("2026-10-05")), details = null)
        val record = codec.sealLocal(intent(day), uid, timestamp, fixture())
        assertNull(record.intent.base.content.details); sameFragments(fixture(), codec.openLocal(record))
        failure { codec.openLocal(record.copy(intent = withBase(record.intent, day.copy(details = EventDetails())))) }
    }

    @Test fun frozenOwnerIdentityAndObservationFormatCannotBeRebound() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector)
        val record = codec.sealLocal(intent(), uid, timestamp, fixture()); val value = record.intent
        val alternatives = listOf(value.copy(id = MutationId("other-edit")),
            value.copy(scope = scope.copy(account = AccountId("other-account"))),
            value.copy(scope = scope.copy(calendar = CalendarId("other-calendar"))),
            value.copy(scope = scope.copy(generation = 3)),
            value.copy(format = AdapterFormat(value.format.id, 2)), value.copy(format = AdapterFormat("other-format", 1)),
            value.copy(base = value.base.copy(eventId = EventId("other-event"))),
            value.copy(base = value.base.copy(revision = Revision("other-revision"))))
        alternatives.forEach { failure { codec.openLocal(record.copy(intent = it)) } }
    }

    @Test fun allDisplayFieldsAndNullVersusEmptyDetailsBelongToTheFrozenOwner() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector)
        val record = codec.sealLocal(intent(), uid, timestamp, fixture()); val value = record.intent
        val alternatives = listOf(value.copy(content = value.content.copy(title = "Another title")),
            withBase(value, original.copy(title = "Different original")),
            withBase(value, original.copy(description = "Different description")),
            withBase(value, original.copy(location = "Different location")),
            withBase(value, original.copy(colorRgb = 0x123456)), withBase(value, original.copy(colorRgb = null)),
            withBase(value, original.copy(details = null)))
        alternatives.forEach { failure { codec.openLocal(record.copy(intent = it)) } }
    }

    @Test fun frozenTimeIncludesTypeEndpointsZoneAndPrecision() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector)
        val record = codec.sealLocal(intent(), uid, timestamp, fixture()); val time = original.time as EventTime.Timed
        val alternatives = listOf(time.copy(start = time.start.minusSeconds(1)), time.copy(end = time.end.plusSeconds(1)),
            time.copy(zone = ZoneId.of("UTC")), time.copy(start = time.start.plusNanos(1)),
            EventTime.AllDay(LocalDate.parse("2026-10-04"), LocalDate.parse("2026-10-05")))
        alternatives.forEach { failure { codec.openLocal(record.copy(intent = withBase(record.intent, original.copy(time = it)))) } }
        failure { codec.openLocal(record.copy(timestamp = timestamp.plusSeconds(1))) }
    }

    @Test fun identicalTitlesUidAndRemoteObservationDoNotPermitCrossMutationPayloadSwap() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector)
        val first = codec.sealLocal(intent(), uid, timestamp, fixture())
        val second = codec.sealLocal(intent().copy(id = MutationId("second-edit")), uid, timestamp, fixture())
        // The protector deliberately returns the second plaintext for either opaque payload.
        failure { codec.openLocal(first) }; sameFragments(fixture(), codec.openLocal(second))
    }

    @Test fun futureForeignAndLegacyPreparationFormatsAreRefusedBeforeOpeningStorage() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector)
        val record = codec.sealLocal(intent(), uid, timestamp, fixture())
        listOf(AdapterFormat(record.format.id, 2), AdapterFormat("other-format", 1), TITLE_PREPARATION_FORMAT)
            .forEach { failure { codec.openLocal(record.copy(format = it)) } }
        assertEquals(0, protector.opened)
    }

    @Test fun pausedTitleOnlyOwnerAdmissionIsCheckedBeforeSealing() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector); val value = intent()
        listOf(value.copy(state = LocalEditState.CONFLICT), value.copy(content = value.content.copy(description = "Changed too")),
            value.copy(content = value.content.copy(location = "Changed too")),
            value.copy(content = value.content.copy(time = (value.content.time as EventTime.Timed).let { it.copy(end = it.end.plusSeconds(1)) })))
            .forEach { failure { codec.sealLocal(it, uid, timestamp, fixture()) } }
        assertEquals(0, protector.sealed)
    }

    @Test fun rootIntentAndBindingHaveStrictTypedEnvelopeShapes() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector)
        val record = codec.sealLocal(intent(), uid, timestamp, fixture()); val good = Json.parseToJsonElement(protector.plain) as JsonObject
        val owner = good.obj("localIntent"); val binding = good.obj("binding")
        val alternatives = listOf(JsonObject(good + ("unknown" to JsonNull)), JsonObject(good - "localIntent"),
            mutate(good, "version", JsonPrimitive("1")), mutate(good, "version", JsonPrimitive(2)),
            mutate(good, "kind", JsonPrimitive("offline-title-preparation")), mutate(good, "changed", JsonPrimitive("true")),
            mutate(good, "localIntent", JsonNull), mutate(good, "localIntent", JsonObject(owner + ("unknown" to JsonNull))),
            mutate(good, "localIntent", mutate(owner, "state", JsonPrimitive("CONFLICT"))),
            mutate(good, "binding", JsonObject(binding + ("unknown" to JsonNull))))
        alternatives.forEach { protector.plain = it.toString(); failure { codec.openLocal(record) } }
    }

    @Test fun duplicateEscapedAndNestedJsonNamesAreRefusedBeforeParsingDropsThem() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector)
        val record = codec.sealLocal(intent(), uid, timestamp, fixture()); val good = protector.plain
        listOf(good.replace("\"kind\":", "\"k\\u0069nd\":\"duplicate\",\"kind\":"),
            good.replace("\"localIntent\":", "\"localIntent\":{},\"localIntent\":"),
            good.replace("\"nested\":", "\"nested\":[],\"nested\":"),
            good.replace("\"uid\":", "\"uid\":\"duplicate\",\"uid\":"))
            .forEach { protector.plain = it; failure { codec.openLocal(record) } }
    }

    @Test fun recoveredUidMustBeBoundAndMatchTheFrozenOriginalBeforeFragmentsEscape() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector)
        val record = codec.sealLocal(intent(), uid, timestamp, fixture()); val good = Json.parseToJsonElement(protector.plain) as JsonObject
        val binding = good.obj("binding"); val row = good.obj("originalRow")
        listOf(mutate(good, "binding", mutate(binding, "uid", JsonPrimitive("other-uid"))),
            mutate(good, "binding", mutate(binding, "uid", JsonNull)),
            mutate(good, "binding", mutate(binding, "uid", JsonPrimitive(1))),
            mutate(good, "originalRow", mutate(row, "UID", JsonPrimitive("other-uid"))))
            .forEach { protector.plain = it.toString(); failure { codec.openLocal(record) } }
    }

    @Test fun fragmentLayoutAndPreservedFieldsRemainValidatedOnRecovery() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector)
        val record = codec.sealLocal(intent(), uid, timestamp, fixture()); val good = Json.parseToJsonElement(protector.plain) as JsonObject
        val row = good.obj("originalRow"); val clear = good.obj("clearSharedPart"); val encrypted = good.obj("encryptedSharedPart")
        listOf(mutate(good, "originalRow", mutate(row, "CalendarEvents", JsonArray(listOf(JsonNull)))),
            mutate(good, "originalRow", mutate(row, "AttendeesEvents", JsonNull)),
            mutate(good, "clearSharedPart", mutate(clear, "Opaque", JsonNull)),
            mutate(good, "encryptedSharedPart", mutate(encrypted, "Signature", JsonPrimitive(" "))),
            mutate(good, "changed", JsonPrimitive(false)))
            .forEach { protector.plain = it.toString(); failure { codec.openLocal(record) } }
    }

    @Test fun uidAndTitleLimitsUseStrictUtf8BeforeStorageAdmission() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector)
        listOf(" ", "x".repeat(4097), "é".repeat(2049), "\uD800")
            .forEach { failure { codec.sealLocal(intent(), it, timestamp, fixture()) } }
        listOf("é".repeat(32769), "\uD800", "bad\u0000title").forEach {
            failure { codec.sealLocal(intent(title = it), uid, timestamp, fixture()) }
        }
        listOf(timestamp.plusNanos(1), Instant.parse("1969-12-31T23:59:59Z"), Instant.parse("+10000-01-01T00:00:00Z"))
            .forEach { failure { codec.sealLocal(intent(), uid, it, fixture()) } }
        assertEquals(0, protector.sealed)
    }

    @Test fun plaintextAndProtectedPayloadBudgetsCoverOwnerContentsAndFragments() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector); val input = fixture()
        failure { codec.sealLocal(intent(original.copy(description = "é".repeat(2_097_152))), uid, timestamp, input) }
        failure { codec.sealLocal(intent(original.copy(location = "é".repeat(2_097_152))), uid, timestamp, input) }
        val huge = mutate(input.originalRow, "Huge", JsonPrimitive("é".repeat(2_097_152)))
        failure { codec.sealLocal(intent(), uid, timestamp, ProtonTimestampedSharedTitle(huge, input.clearSharedPart, input.encryptedSharedPart, true)) }
        val record = codec.sealLocal(intent(), uid, timestamp, input)
        listOf(" ".repeat(4_194_305), "é".repeat(2_097_153)).forEach {
            protector.plain = it; failure { codec.openLocal(record) }
        }
        listOf("", "x".repeat(4_194_305), "é".repeat(2_097_153)).forEach { protected ->
            val oversized = object : EventPayloadProtector {
                override fun seal(plain: String) = protected
                override fun open(protected: String) = error("unused")
            }
            failure { ProtonTitlePreparationCodec(oversized).sealLocal(intent(), uid, timestamp, input) }
        }
    }

    @Test fun malformedUnicodeAndOverdeepPayloadsFailWithSanitizedDiagnostics() {
        val protector = MemoryProtector(); val codec = ProtonTitlePreparationCodec(protector)
        failure { codec.sealLocal(intent(original.copy(description = "\uD800")), uid, timestamp, fixture()) }
        failure { codec.sealLocal(intent(original.copy(location = "\uD800")), uid, timestamp, fixture()) }
        val record = codec.sealLocal(intent(), uid, timestamp, fixture())
        listOf("[".repeat(66) + "null" + "]".repeat(66), "{broken-json", "\uD800").forEach {
            protector.plain = it; failure { codec.openLocal(record) }
        }
    }

    @Test fun storageFailuresAreClosedAndCancellationPropagates() {
        val memory = MemoryProtector(); val record = ProtonTitlePreparationCodec(memory).sealLocal(intent(), uid, timestamp, fixture())
        val failed = object : EventPayloadProtector {
            override fun seal(plain: String): String = error("synthetic-sensitive-message")
            override fun open(protected: String): String = error("synthetic-sensitive-message")
        }
        failure(ProtonTitlePreparationFailure.STORAGE) { ProtonTitlePreparationCodec(failed).sealLocal(intent(), uid, timestamp, fixture()) }
        failure(ProtonTitlePreparationFailure.STORAGE) { ProtonTitlePreparationCodec(failed).openLocal(record) }
        val cancellation = CancellationException("synthetic-cancellation")
        val cancelling = object : EventPayloadProtector {
            override fun seal(plain: String): String = throw cancellation
            override fun open(protected: String): String = throw cancellation
        }
        try { ProtonTitlePreparationCodec(cancelling).sealLocal(intent(), uid, timestamp, fixture()); fail() }
        catch (actual: CancellationException) { assertSame(cancellation, actual) }
        try { ProtonTitlePreparationCodec(cancelling).openLocal(record); fail() }
        catch (actual: CancellationException) { assertSame(cancellation, actual) }
    }

    @Test fun emptyDetailListsAreFrozenBeforeTheStorageProtectorRuns() {
        val reminders = mutableListOf<EventReminder>(); val attendees = mutableListOf<EventAttendee>()
        val source = intent(original.copy(details = EventDetails(reminders = reminders, attendees = attendees)))
        val protector = MemoryProtector().apply { beforeSeal = { reminders += EventReminder(10) } }
        val codec = ProtonTitlePreparationCodec(protector); val record = codec.sealLocal(source, uid, timestamp, fixture())
        assertEquals(EventDetails(), record.intent.base.content.details); assertEquals(EventDetails(), record.intent.content.details)
        assertTrue(source.base.content.details!!.reminders.isNotEmpty())
        sameFragments(fixture(), codec.openLocal(record))
    }

    @Test fun diagnosticsAndPublicPayloadContainNoUidTitlesOrOriginalFragments() {
        val protector = MemoryProtector(); val record = ProtonTitlePreparationCodec(protector).sealLocal(intent(), uid, timestamp, fixture())
        assertEquals("LocalEditPreparation(REDACTED)", record.toString())
        assertEquals("LocalEditIntent(REDACTED)", record.intent.toString())
        assertEquals("ProtonTimestampedSharedTitle(redacted)", fixture().toString())
        listOf(uid, original.title, record.intent.content.title, "original-secret", "original-key-packet")
            .forEach { assertFalse(record.protectedPayload.contains(it)) }
    }
}
