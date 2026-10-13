// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSharedTextProperty.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Structural recovery fixtures only; fabricated signatures are not service or crypto proof. */
class ProtonLocalTextPreparationCodecTest {
    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 2)
    private val stamp = Instant.parse("2026-10-03T12:00:00Z")
    private val base = EventContent("Original", EventTime.Timed(Instant.parse("2026-10-04T12:00:00.000000001Z"),
        Instant.parse("2026-10-04T13:00:00Z"), ZoneId.of("Europe/Paris")), "Notes", "", 0x6849D3, details = EventDetails())
    private fun intent(original: EventContent = base, replacement: EventContent = original.copy(description = "New notes", location = "Place")) =
        LocalEditIntent(MutationId("synthetic-edit"), scope, AdapterFormat("synthetic-observation", 1),
            RemoteChange.Upsert(EventId("synthetic-event"), Revision("opaque-revision"), original), replacement)
    private val selected = linkedMapOf(DESCRIPTION to ProtonSharedTextChange("Notes", "New notes"),
        LOCATION to ProtonSharedTextChange(null, "Place"))
    private fun binding(value: LocalEditIntent = intent(), changes: Map<ProtonSharedTextProperty, ProtonSharedTextChange> = selected,
        uid: String = "synthetic-uid", time: Instant = stamp) = ProtonTextPreparationBinding(value.scope,
        value.base.eventId, value.base.revision, uid, value.base.content.title, changes, time)
    private class MemoryProtector : EventPayloadProtector {
        var plain = ""; var opened = 0; var sealed = 0
        var beforeSeal: (() -> Unit)? = null
        override fun seal(plain: String): String { this.plain = plain; sealed++; beforeSeal?.invoke(); return "protected-fixture" }
        override fun open(protected: String): String { opened++; return plain }
    }
    private fun part(type: Int, data: String) = buildJsonObject {
        put("Type", type); put("Data", data); put("Signature", "synthetic-signature"); put("Author", "synthetic@example.invalid")
        put("Opaque", JsonArray(listOf(JsonNull, JsonPrimitive("kept"))))
    }
    private fun fixture(changed: Boolean = true): ProtonTimestampedSharedText {
        val clear = part(2, "original-clear"); val secret = part(3, "original-secret")
        val row = buildJsonObject {
            put("ID", "synthetic-event"); put("CalendarID", scope.calendar.value); put("UID", "synthetic-uid")
            put("SharedKeyPacket", "original-packet"); put("SharedEvents", JsonArray(listOf(clear, secret)))
            put("CalendarEvents", JsonArray(emptyList())); put("AttendeesEvents", JsonArray(emptyList()))
        }
        return ProtonTimestampedSharedText(row, if (changed) part(2, "new-clear") else clear,
            if (changed) part(3, "new-secret") else secret, changed)
    }
    private fun record(source: LocalTextEditPreparation, value: LocalEditIntent = source.intent,
        time: Instant = source.timestamp, format: AdapterFormat = source.format) =
        LocalTextEditPreparation(value, time, format, source.protectedPayload)
    private fun failure(reason: ProtonTextPreparationFailure = ProtonTextPreparationFailure.UNSUPPORTED, block: () -> Unit) {
        val actual = assertThrows(ProtonTextPreparationException::class.java) { block() }
        assertEquals(reason, actual.reason); assertNull(actual.cause); assertEquals("TEXT_PREPARATION_${reason.name}", actual.message)
    }
    private fun same(expected: ProtonTimestampedSharedText, actual: ProtonTimestampedSharedText) {
        assertEquals(expected.originalRow, actual.originalRow); assertEquals(expected.clearSharedPart, actual.clearSharedPart)
        assertEquals(expected.encryptedSharedPart, actual.encryptedSharedPart); assertEquals(expected.changed, actual.changed)
    }
    private fun withBase(value: LocalEditIntent, next: EventContent) = value.copy(base = value.base.copy(content = next),
        content = next.copy(title = value.content.title, description = value.content.description, location = value.content.location))

    @Test fun newCodecAndProtectorReopenCompleteIntentWithoutExternalUid() {
        val writer = MemoryProtector(); val value = intent(); val source = fixture()
        val saved = ProtonTextPreparationCodec(writer).sealLocal(value, binding(), source)
        assertEquals(value, saved.intent); assertEquals(stamp, saved.timestamp); assertEquals(LOCAL_TEXT_PREPARATION_FORMAT, saved.format)
        val reader = MemoryProtector().apply { plain = writer.plain }
        same(source, ProtonTextPreparationCodec(reader).openLocal(saved)); assertEquals(1, reader.opened)
        assertFalse(saved.toString().contains("protected-fixture")); assertFalse(writer.plain.contains("privateKey"))
    }
    @Test fun entireOwnerBaseReplacementAndTimeCannotBeRebound() {
        val protector = MemoryProtector(); val codec = ProtonTextPreparationCodec(protector)
        val saved = codec.sealLocal(intent(), binding(), fixture()); val value = saved.intent; val timed = base.time as EventTime.Timed
        val variants = listOf(value.copy(id = MutationId("other")), value.copy(scope = scope.copy(generation = 3)),
            value.copy(format = AdapterFormat("other", 1)), value.copy(format = value.format.copy(version = 2)),
            value.copy(base = value.base.copy(revision = Revision("other"))), value.copy(base = value.base.copy(eventId = EventId("other"))),
            value.copy(content = value.content.copy(description = "Other")), value.copy(content = value.content.copy(location = "Other")),
            value.copy(content = value.content.copy(title = "Other")), withBase(value, base.copy(title = "Other")),
            withBase(value, base.copy(description = "Other")), withBase(value, base.copy(location = "Other")),
            withBase(value, base.copy(colorRgb = null)), withBase(value, base.copy(details = null)),
            withBase(value, base.copy(time = timed.copy(start = timed.start.minusNanos(1)))),
            withBase(value, base.copy(time = timed.copy(end = timed.end.plusSeconds(1)))),
            withBase(value, base.copy(time = timed.copy(zone = ZoneId.of("UTC")))),
            withBase(value, base.copy(time = EventTime.AllDay(LocalDate.parse("2026-10-04"), LocalDate.parse("2026-10-06")))))
        variants.forEach { failure { codec.openLocal(record(saved, it)) } }
        failure { codec.openLocal(record(saved, time = stamp.plusSeconds(1))) }
    }
    @Test fun fullIntentAndBindingMismatchRefusesBeforeProtection() {
        val protector = MemoryProtector(); val codec = ProtonTextPreparationCodec(protector); val value = intent()
        val variants = listOf(binding(changes = selected - LOCATION), binding(changes = selected + (SUMMARY to ProtonSharedTextChange("Original", "Original"))),
            binding(changes = selected + (LOCATION to ProtonSharedTextChange("Other", "Place"))),
            binding(changes = selected + (DESCRIPTION to ProtonSharedTextChange(null, "New notes"))),
            binding(changes = selected + (DESCRIPTION to ProtonSharedTextChange("Notes", "Other"))),
            binding(value.copy(scope = scope.copy(generation = 3))), binding(value.copy(base = value.base.copy(revision = Revision("other")))))
        variants.forEach { failure { codec.sealLocal(value, it, fixture()) } }; assertEquals(0, protector.sealed)
    }
    @Test fun absentAndExplicitEmptyExpectationsAreDistinctButUnchangedEmptyIsUnselected() {
        val protector = MemoryProtector(); val codec = ProtonTextPreparationCodec(protector)
        val absent = codec.sealLocal(intent(), binding(), fixture()); val saved = protector.plain
        val empty = codec.sealLocal(intent(), binding(changes = selected + (LOCATION to ProtonSharedTextChange("", "Place"))), fixture())
        assertNotEquals(saved, protector.plain); same(fixture(), codec.openLocal(empty))
        protector.plain = saved; same(fixture(), codec.openLocal(absent))
        val value = intent(replacement = base.copy(description = "New notes"))
        codec.sealLocal(value, binding(value, mapOf(DESCRIPTION to selected.getValue(DESCRIPTION))), fixture())
        failure { codec.sealLocal(value, binding(value, selected + (LOCATION to ProtonSharedTextChange(null, null))), fixture()) }
    }
    @Test fun optionalDeletionAndMultilineNoOpRetainExactHistoricalIntent() {
        val protector = MemoryProtector(); val codec = ProtonTextPreparationCodec(protector)
        val removal = intent(replacement = base.copy(description = ""))
        val saved = codec.sealLocal(removal, binding(removal, mapOf(DESCRIPTION to ProtonSharedTextChange("Notes", null))), fixture())
        assertEquals(removal, saved.intent); same(fixture(), codec.openLocal(saved))
        failure { codec.sealLocal(removal, binding(removal, mapOf(DESCRIPTION to ProtonSharedTextChange("Notes", ""))), fixture()) }
        val original = base.copy(description = "Line1\r\nLine2")
        val noOp = intent(original, original.copy(description = "Line1\nLine2"))
        val noOpSaved = codec.sealLocal(noOp, binding(noOp, mapOf(DESCRIPTION to ProtonSharedTextChange("Line1\nLine2", "Line1\nLine2"))), fixture(false))
        assertEquals(noOp, noOpSaved.intent); same(fixture(false), codec.openLocal(noOpSaved))
    }
    @Test fun titleSelectionAndAllDayNullDetailsHaveIndependentCompleteBindings() {
        val protector = MemoryProtector(); val codec = ProtonTextPreparationCodec(protector)
        val original = base.copy(time = EventTime.AllDay(LocalDate.parse("2026-10-04"), LocalDate.parse("2026-10-07")), details = null)
        val value = intent(original, original.copy(title = "Changed"))
        val saved = codec.sealLocal(value, binding(value, mapOf(SUMMARY to ProtonSharedTextChange("Original", "Changed"))), fixture())
        same(fixture(), codec.openLocal(saved)); assertNull(saved.intent.base.content.details)
        failure { codec.openLocal(record(saved, withBase(value, original.copy(details = EventDetails())))) }
    }
    @Test fun formatsKindsClosedShapesAndDuplicateKeysDoNotGrantLocalOwnership() {
        val protector = MemoryProtector(); val codec = ProtonTextPreparationCodec(protector)
        val saved = codec.sealLocal(intent(), binding(), fixture()); val good = protector.plain
        listOf(TEXT_PREPARATION_FORMAT, TITLE_PREPARATION_FORMAT, LOCAL_TITLE_PREPARATION_FORMAT,
            LOCAL_TEXT_PREPARATION_FORMAT.copy(version = 2)).forEach { failure { codec.openLocal(record(saved, format = it)) } }
        assertEquals(0, protector.opened)
        val root = Json.parseToJsonElement(good) as JsonObject
        val corrupt = listOf(JsonObject(root - "localIntent").toString(), JsonObject(root + ("unknown" to JsonNull)).toString(),
            JsonObject(root + ("kind" to JsonPrimitive("offline-text-preparation"))).toString(),
            JsonObject(root + ("localIntent" to JsonObject(root.obj("localIntent") + ("unknown" to JsonNull)))).toString(),
            good.replace("\"localIntent\":", "\"localIntent\":{},\"localIntent\":"),
            good.replace("\"uid\":", "\"u\\u0069d\":\"duplicate\",\"uid\":"),
            good.replace("\"expected\":", "\"expected\":null,\"expected\":"))
        corrupt.forEach { protector.plain = it; failure { codec.openLocal(saved) } }
        protector.plain = good
        val generic = ProtonProtectedTextPreparation(scope, binding().eventId, binding().revision, TEXT_PREPARATION_FORMAT, saved.protectedPayload)
        failure { codec.open(generic, binding()) }
        codec.seal(binding(), fixture()); failure { codec.openLocal(saved) }
    }
    @Test fun payloadCannotBeSwappedAcrossOtherwiseIdenticalMutations() {
        val protector = MemoryProtector(); val codec = ProtonTextPreparationCodec(protector)
        val first = codec.sealLocal(intent(), binding(), fixture())
        val secondIntent = intent().copy(id = MutationId("second"))
        val second = codec.sealLocal(secondIntent, binding(secondIntent), fixture())
        failure { codec.openLocal(first) }; same(fixture(), codec.openLocal(second))
    }
    @Test fun freezingBeforeProtectorCallbackPreventsAliasedDetailsMutation() {
        val reminders = mutableListOf<EventReminder>(); val details = EventDetails(reminders = reminders)
        val original = base.copy(details = details); val value = intent(original)
        val protector = MemoryProtector().apply { beforeSeal = { reminders.add(EventReminder(5)) } }
        val saved = ProtonTextPreparationCodec(protector).sealLocal(value, binding(value), fixture())
        assertEquals(EventDetails(), saved.intent.base.content.details); same(fixture(), ProtonTextPreparationCodec(protector).openLocal(saved))
    }
    @Test fun ordinaryStorageErrorsAreSanitizedAndCancellationIdentityPropagates() {
        val ordinary = object : EventPayloadProtector {
            override fun seal(plain: String): String = throw java.io.IOException("synthetic-sensitive")
            override fun open(protected: String): String = throw java.io.IOException("synthetic-sensitive")
        }
        failure(ProtonTextPreparationFailure.STORAGE) { ProtonTextPreparationCodec(ordinary).sealLocal(intent(), binding(), fixture()) }
        val memory = MemoryProtector(); val saved = ProtonTextPreparationCodec(memory).sealLocal(intent(), binding(), fixture())
        failure(ProtonTextPreparationFailure.STORAGE) { ProtonTextPreparationCodec(ordinary).openLocal(saved) }
        val cancelled = CancellationException("synthetic-sensitive")
        val cancelling = object : EventPayloadProtector {
            override fun seal(plain: String): String = throw cancelled
            override fun open(protected: String): String = throw cancelled
        }
        assertSame(cancelled, assertThrows(CancellationException::class.java) { ProtonTextPreparationCodec(cancelling).sealLocal(intent(), binding(), fixture()) })
        assertSame(cancelled, assertThrows(CancellationException::class.java) { ProtonTextPreparationCodec(cancelling).openLocal(saved) })
    }
}
