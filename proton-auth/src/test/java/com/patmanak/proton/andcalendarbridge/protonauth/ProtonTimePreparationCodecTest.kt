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

/** Independent synthetic recovery fixtures; opaque signatures provide no cryptographic evidence. */
class ProtonTimePreparationCodecTest {
    private val scope = CalendarScope(AccountId("time-account"), CalendarId("time-calendar"), 4)
    private val stamp = Instant.parse("2026-10-17T08:00:00Z")
    private val start = Instant.parse("2026-10-25T00:00:00Z")
    private val time = EventTime.Timed(start, start.plusSeconds(3600), ZoneId.of("Europe/Paris"))
    private val base = EventContent("Title", time, "Notes\r\nNext", "Place", details = EventDetails())
    private fun intent(original: EventContent = base,
        replacement: EventContent = original.copy(time = time.copy(end = start.plusSeconds(7200)))) =
        LocalEditIntent(MutationId("time-edit"), scope, AdapterFormat("time-observation", 3),
            RemoteChange.Upsert(EventId("time-event"), Revision("time-revision"), original), replacement)
    private class MemoryProtector : EventPayloadProtector {
        var plain = ""; var opened = 0; var sealed = 0
        var protectedValue = "protected-time-fixture"
        var beforeSeal: (() -> Unit)? = null
        override fun seal(plain: String): String { this.plain = plain; sealed++; beforeSeal?.invoke(); return protectedValue }
        override fun open(protected: String): String { opened++; return plain }
    }
    private fun part(type: Int, data: String) = buildJsonObject {
        put("Type", type); put("Data", data); put("Signature", "fixture-signature-$data")
        put("Author", "fixture@example.invalid")
        put("Opaque", buildJsonObject { put("retained", JsonArray(listOf(JsonNull, JsonPrimitive(7)))) })
    }
    private fun fixture(changed: Boolean = true): ProtonTimestampedSharedTime {
        val clear = part(2, "old-clear"); val encrypted = part(3, "old-encrypted")
        val row = buildJsonObject {
            put("ID", "time-event"); put("CalendarID", "time-calendar"); put("UID", "time-uid")
            put("SharedKeyPacket", "retained-packet"); put("AddressKeyPacket", JsonNull)
            put("SharedEvents", JsonArray(listOf(encrypted, clear)))
            put("CalendarEvents", JsonArray(emptyList())); put("AttendeesEvents", JsonArray(emptyList()))
            put("OpaqueRow", JsonArray(listOf(JsonPrimitive("keep"), JsonNull)))
        }
        return ProtonTimestampedSharedTime(row, if (changed) part(2, "next-clear") else clear,
            if (changed) part(3, "next-encrypted") else encrypted, changed)
    }
    private fun record(source: LocalTimeEditPreparation, value: LocalEditIntent = source.intent,
        timestamp: Instant = source.timestamp, format: AdapterFormat = source.format) =
        LocalTimeEditPreparation(value, timestamp, format, source.protectedPayload)
    private fun failure(reason: ProtonTimePreparationFailure = ProtonTimePreparationFailure.UNSUPPORTED, action: () -> Unit) {
        val error = assertThrows(ProtonTimePreparationException::class.java) { action() }
        assertEquals(reason, error.reason); assertEquals("TIME_PREPARATION_${reason.name}", error.message); assertNull(error.cause)
    }
    private fun same(expected: ProtonTimestampedSharedTime, actual: ProtonTimestampedSharedTime) {
        assertEquals(expected.originalRow, actual.originalRow); assertEquals(expected.clearSharedPart, actual.clearSharedPart)
        assertEquals(expected.encryptedSharedPart, actual.encryptedSharedPart); assertEquals(expected.changed, actual.changed)
    }
    private fun JsonObject.replace(name: String, value: JsonElement) = JsonObject(this + (name to value))
    private fun JsonObject.obj(name: String) = getValue(name) as JsonObject
    private fun withBase(value: LocalEditIntent, content: EventContent) = value.copy(
        base = value.base.copy(content = content), content = content.copy(time = value.content.time))
    private fun altered(source: ProtonTimestampedSharedTime = fixture(), original: JsonObject = source.originalRow,
        clear: JsonObject = source.clearSharedPart, encrypted: JsonObject = source.encryptedSharedPart,
        changed: Boolean = source.changed) = ProtonTimestampedSharedTime(original, clear, encrypted, changed)

    @Test fun freshCodecReopensEntireFrozenTimedHistoryAndOpaqueFields() {
        val writer = MemoryProtector(); val source = fixture(); val value = intent()
        val saved = ProtonTimePreparationCodec(writer).sealLocal(value, "time-uid", stamp, source)
        assertEquals(value, saved.intent); assertEquals(stamp, saved.timestamp); assertEquals(LOCAL_TIME_PREPARATION_FORMAT, saved.format)
        val reader = MemoryProtector().apply { plain = writer.plain }
        same(source, ProtonTimePreparationCodec(reader).openLocal(saved)); assertEquals(1, reader.opened)
        assertFalse(saved.toString().contains("protected-time-fixture")); assertFalse(source.toString().contains("next-clear"))
    }
    @Test fun allDayExclusiveEndAndExactZoneAliasesAreBound() {
        val day = EventTime.AllDay(LocalDate.parse("2026-10-17"), LocalDate.parse("2026-10-18"))
        val original = base.copy(time = day)
        val value = intent(original, original.copy(time = day.copy(endExclusive = day.start.plusDays(4))))
        val memory = MemoryProtector(); val codec = ProtonTimePreparationCodec(memory)
        val saved = codec.sealLocal(value, "time-uid", stamp, fixture()); same(fixture(), codec.openLocal(saved))
        failure { codec.openLocal(record(saved, value.copy(content = value.content.copy(time = day.copy(endExclusive = day.start.plusDays(5)))))) }
        val utc = time.copy(zone = ZoneId.of("UTC")); val utcBase = base.copy(time = utc)
        val timed = intent(utcBase, utcBase.copy(time = utc.copy(end = start.plusSeconds(7200))))
        val timedRecord = codec.sealLocal(timed, "time-uid", stamp, fixture())
        val aliasBase = utcBase.copy(time = utc.copy(zone = ZoneId.of("Etc/UTC")))
        val alias = intent(aliasBase, aliasBase.copy(time = utc.copy(end = start.plusSeconds(7200), zone = ZoneId.of("Etc/UTC"))))
        failure { codec.openLocal(record(timedRecord, alias)) }
    }
    @Test fun fullOwnerOriginalReplacementAndTimestampCannotBeRebound() {
        val memory = MemoryProtector(); val codec = ProtonTimePreparationCodec(memory)
        val saved = codec.sealLocal(intent(), "time-uid", stamp, fixture()); val value = saved.intent
        val variants = listOf(value.copy(id = MutationId("other")), value.copy(scope = scope.copy(account = AccountId("other"))),
            value.copy(scope = scope.copy(calendar = CalendarId("other"))), value.copy(scope = scope.copy(generation = 5)),
            value.copy(format = AdapterFormat("other", 3)), value.copy(format = value.format.copy(version = 4)),
            value.copy(base = value.base.copy(eventId = EventId("other"))), value.copy(base = value.base.copy(revision = Revision("other"))),
            withBase(value, base.copy(title = "Other")), withBase(value, base.copy(description = "Other")),
            withBase(value, base.copy(location = "Other")),
            withBase(value, base.copy(time = time.copy(start = start.minusSeconds(1)))),
            withBase(value, base.copy(time = time.copy(end = time.end.plusSeconds(1)))),
            value.copy(content = value.content.copy(time = time.copy(end = start.plusSeconds(7201)))))
        variants.forEach { failure { codec.openLocal(record(saved, it)) } }
        failure { codec.openLocal(record(saved, timestamp = stamp.plusSeconds(1))) }
    }
    @Test fun onlyTimeCandidateCanReachProtectorAndNeitherCandidateNorFragmentsMayBeNoOp() {
        val memory = MemoryProtector(); val codec = ProtonTimePreparationCodec(memory); val value = intent()
        // A truly unchanged intent cannot exist: the domain refuses it before the adapter.
        assertThrows(IllegalArgumentException::class.java) { value.copy(content = base) }
        val invalid = listOf(value.copy(state = LocalEditState.CONFLICT),
            value.copy(content = value.content.copy(title = "Other")), withBase(value, base.copy(details = null)),
            withBase(value, base.copy(colorRgb = 42)),
            value.copy(content = value.content.copy(time = time.copy(zone = ZoneId.of("UTC")))))
        invalid.forEach { failure { codec.sealLocal(it, "time-uid", stamp, fixture()) } }
        failure { codec.sealLocal(value, "time-uid", stamp, fixture(false)) }
        failure { codec.sealLocal(value, "time-uid", stamp, altered(changed = false)) }
        assertEquals(0, memory.sealed)
    }
    @Test fun formatsKindsClosedEnvelopeAndDuplicateKeysAreDisjointFromTextAndTitle() {
        val memory = MemoryProtector(); val codec = ProtonTimePreparationCodec(memory)
        val saved = codec.sealLocal(intent(), "time-uid", stamp, fixture()); val good = memory.plain
        listOf(TEXT_PREPARATION_FORMAT, LOCAL_TEXT_PREPARATION_FORMAT, TITLE_PREPARATION_FORMAT,
            LOCAL_TITLE_PREPARATION_FORMAT, LOCAL_TIME_PREPARATION_FORMAT.copy(version = 2)).forEach {
            failure { codec.openLocal(record(saved, format = it)) }
        }
        assertEquals(0, memory.opened)
        val root = Json.parseToJsonElement(good) as JsonObject
        val corrupt = listOf(JsonObject(root - "localIntent").toString(), root.replace("extra", JsonNull).toString(),
            root.replace("kind", JsonPrimitive("offline-local-text-preparation")).toString(),
            root.replace("version", JsonPrimitive("1")).toString(), root.replace("version", JsonPrimitive(2)).toString(),
            root.replace("changed", JsonPrimitive(false)).toString(), root.replace("changed", JsonPrimitive("true")).toString(),
            root.replace("localIntent", root.obj("localIntent").replace("extra", JsonNull)).toString(),
            good.replace("\"localIntent\":", "\"localIntent\":{},\"localIntent\":"),
            good.replace("\"UID\":", "\"U\\u0049D\":\"other\",\"UID\":"),
            good.replace("\"Data\":", "\"Data\":\"other\",\"Data\":"))
        corrupt.forEach { memory.plain = it; failure { codec.openLocal(saved) } }
    }
    @Test fun bindingUidTimestampAndFormatAreStrictlyRetained() {
        val memory = MemoryProtector(); val codec = ProtonTimePreparationCodec(memory)
        val saved = codec.sealLocal(intent(), "time-uid", stamp, fixture()); val good = memory.plain
        val root = Json.parseToJsonElement(good) as JsonObject
        val binding = root.obj("binding")
        for (changed in listOf(binding.replace("uid", JsonPrimitive("other")),
            binding.replace("timestamp", JsonPrimitive(stamp.plusSeconds(1).toString())),
            binding.replace("extra", JsonNull), JsonObject(binding - "uid"))) {
            memory.plain = root.replace("binding", changed).toString(); failure { codec.openLocal(saved) }
        }
        memory.plain = root.replace("format", root.obj("format").replace("id", JsonPrimitive("other"))).toString()
        failure { codec.openLocal(saved) }
    }
    @Test fun originalMustBeSharedOnlyAndBoundToEventCalendarAndUid() {
        val memory = MemoryProtector(); val codec = ProtonTimePreparationCodec(memory); val row = fixture().originalRow
        val variants = listOf(row.replace("ID", JsonPrimitive("other")), row.replace("CalendarID", JsonPrimitive("other")),
            row.replace("UID", JsonPrimitive("other")), row.replace("SharedKeyPacket", JsonPrimitive(" ")),
            row.replace("AddressKeyPacket", JsonPrimitive("address-packet")),
            row.replace("CalendarEvents", JsonArray(listOf(JsonObject(emptyMap())))),
            row.replace("AttendeesEvents", JsonArray(listOf(JsonObject(emptyMap())))),
            row.replace("SharedEvents", JsonArray(listOf(part(2, "old-clear")))),
            row.replace("SharedEvents", JsonArray(listOf(part(2, "old-clear"), part(2, "old-clear")))),
            row.replace("SharedEvents", JsonArray(listOf(part(2, "old-clear"), part(3, "old-encrypted"), part(4, "other")))),
            row.replace("SharedEvents", JsonArray(listOf(part(2, "old-clear"), part(3, "old-encrypted").replace("Type", JsonPrimitive("3"))))))
        variants.forEach { failure { codec.sealLocal(intent(), "time-uid", stamp, altered(original = it)) } }
        assertEquals(0, memory.sealed)
        val saved = codec.sealLocal(intent(), "time-uid", stamp, fixture()); val root = Json.parseToJsonElement(memory.plain) as JsonObject
        variants.forEach { memory.plain = root.replace("originalRow", it).toString(); failure { codec.openLocal(saved) } }
    }
    @Test fun replacementPreservesAllOpaqueKeysAndCannotResignUnchangedData() {
        val memory = MemoryProtector(); val codec = ProtonTimePreparationCodec(memory); val source = fixture()
        val originalClear = (source.originalRow["SharedEvents"] as JsonArray).filterIsInstance<JsonObject>().single { it["Type"] == JsonPrimitive(2) }
        val invalid = listOf(source.clearSharedPart.replace("Author", JsonPrimitive("other")),
            source.clearSharedPart.replace("Opaque", JsonNull), source.clearSharedPart.replace("new", JsonNull),
            JsonObject(source.clearSharedPart - "Opaque"), source.clearSharedPart.replace("Signature", JsonPrimitive(" ")),
            originalClear.replace("Signature", JsonPrimitive("resigned-with-identical-data")))
        invalid.forEach { failure { codec.sealLocal(intent(), "time-uid", stamp, altered(clear = it)) } }
        val retainedClear = altered(clear = originalClear)
        val saved = codec.sealLocal(intent(), "time-uid", stamp, retainedClear); same(retainedClear, codec.openLocal(saved))
        val root = Json.parseToJsonElement(memory.plain) as JsonObject
        invalid.forEach { memory.plain = root.replace("clearSharedPart", it).toString(); failure { codec.openLocal(saved) } }
    }
    @Test fun strictUtf8ContextTextPayloadAndTimestampBudgetsApplyBeforeProtection() {
        val memory = MemoryProtector(); val codec = ProtonTimePreparationCodec(memory)
        for (bad in listOf("", " ", "é".repeat(2049), "\uD800"))
            failure { codec.sealLocal(intent(), bad, stamp, fixture()) }
        for (bad in listOf("é".repeat(32769), "\uD800", "\u0000"))
            failure { codec.sealLocal(withBase(intent(), base.copy(title = bad)), "time-uid", stamp, fixture()) }
        for (bad in listOf(stamp.plusNanos(1), Instant.EPOCH.minusSeconds(1), Instant.parse("+10000-01-01T00:00:00Z")))
            failure { codec.sealLocal(intent(), "time-uid", bad, fixture()) }
        failure { codec.sealLocal(intent(), "time-uid", stamp, altered(clear = fixture().clearSharedPart.replace("Data", JsonPrimitive("é".repeat(2_097_153))))) }
        failure { codec.sealLocal(intent(), "time-uid", stamp, altered(clear = fixture().clearSharedPart.replace("Data", JsonPrimitive("\uD800")))) }
        assertEquals(0, memory.sealed)
        val saved = codec.sealLocal(intent(), "time-uid", stamp, fixture())
        for (bad in listOf("x".repeat(4_194_305), "é".repeat(2_097_153), "\uD800")) {
            memory.plain = bad; failure { codec.openLocal(saved) }
            memory.protectedValue = bad; failure { codec.sealLocal(intent(), "time-uid", stamp, fixture()) }
        }
    }
    @Test fun mutationSwapAndAliasedDetailsMutationDoNotChangeFrozenHistory() {
        val reminders = mutableListOf<EventReminder>(); val original = base.copy(details = EventDetails(reminders = reminders))
        val value = intent(original); val memory = MemoryProtector().apply { beforeSeal = { reminders.add(EventReminder(5)) } }
        val codec = ProtonTimePreparationCodec(memory); val saved = codec.sealLocal(value, "time-uid", stamp, fixture())
        assertEquals(EventDetails(), saved.intent.base.content.details); same(fixture(), codec.openLocal(saved))
        memory.beforeSeal = null
        codec.sealLocal(intent().copy(id = MutationId("other")), "time-uid", stamp, fixture())
        failure { codec.openLocal(saved) }
    }
    @Test fun exactContextAndTextByteBoundsWorkAndEscapedMalformedUnicodeIsRefused() {
        val memory = MemoryProtector(); val codec = ProtonTimePreparationCodec(memory)
        val uid = "é".repeat(2048)
        val source = altered(original = fixture().originalRow.replace("UID", JsonPrimitive(uid)))
        val value = withBase(intent(), base.copy(description = "é".repeat(32768)))
        val saved = codec.sealLocal(value, uid, stamp, source); same(source, codec.openLocal(saved))
        val good = memory.plain
        memory.plain = good.replace("next-clear", "\\ud800")
        failure { codec.openLocal(saved) }
        memory.plain = good.replace("OpaqueRow", "\\ud800")
        failure { codec.openLocal(saved) }
    }
    @Test fun storageFailuresAreSanitizedAndCancellationIdentityPropagates() {
        val memory = MemoryProtector(); val saved = ProtonTimePreparationCodec(memory).sealLocal(intent(), "time-uid", stamp, fixture())
        val broken = object : EventPayloadProtector {
            override fun seal(plain: String): String = throw java.io.IOException("sensitive-fixture")
            override fun open(protected: String): String = throw java.io.IOException("sensitive-fixture")
        }
        failure(ProtonTimePreparationFailure.STORAGE) { ProtonTimePreparationCodec(broken).sealLocal(intent(), "time-uid", stamp, fixture()) }
        failure(ProtonTimePreparationFailure.STORAGE) { ProtonTimePreparationCodec(broken).openLocal(saved) }
        val cancelled = CancellationException("sensitive-fixture")
        val cancelling = object : EventPayloadProtector {
            override fun seal(plain: String): String = throw cancelled
            override fun open(protected: String): String = throw cancelled
        }
        assertSame(cancelled, assertThrows(CancellationException::class.java) { ProtonTimePreparationCodec(cancelling).sealLocal(intent(), "time-uid", stamp, fixture()) })
        assertSame(cancelled, assertThrows(CancellationException::class.java) { ProtonTimePreparationCodec(cancelling).openLocal(saved) })
    }
}
