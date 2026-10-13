// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSharedTextProperty.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Fabricated signatures test protected structure only, never service or cryptographic validity. */
class ProtonTextUpdateRequestCodecTest {
    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 2)
    private val timestamp = Instant.parse("2026-10-03T12:00:00Z")
    private val id = MutationId("synthetic-request")
    private val base = EventContent("Original", EventTime.AllDay(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 27)),
        description = "Notes", location = "Place", details = EventDetails())
    private fun part(type: Int, data: String) = buildJsonObject {
        put("Type", type); put("Data", data); put("Signature", "signature-$data"); put("Author", "synthetic@example.invalid")
    }
    private val original = buildJsonObject {
        put("ID", "synthetic-event"); put("CalendarID", scope.calendar.value); put("UID", "synthetic-uid")
        put("SharedKeyPacket", "original-packet"); put("Permissions", 3); put("IsOrganizer", 1); put("IsPersonalSingleEdit", false)
        put("CreateTime", 1); put("ModifyTime", 2); put("LastEditTime", 2)
        put("StartTime", 3); put("EndTime", 4); put("FullDay", 1); put("StartTimezone", "UTC"); put("EndTimezone", "UTC")
        listOf("CalendarEvents", "AttendeesEvents", "Notifications", "Attendees", "Exdates", "NotificationOccurrences")
            .forEach { put(it, JsonArray(emptyList())) }
        put("SharedEvents", JsonArray(listOf(part(2, "old-clear"), part(3, "old-secret"))))
    }
    private val context = buildJsonObject {
        put("member", "synthetic-member"); put("address", "synthetic-address"); put("permissions", 16); put("flags", 1)
        put("key", "synthetic-key"); put("passphrase", "synthetic-passphrase"); put("author", "synthetic@example.invalid")
        for (name in listOf("calendarKey", "signingKey", "calendar", "bootstrap")) put(name, "a".repeat(64))
    }
    private class MemoryProtector : EventPayloadProtector {
        val values = linkedMapOf<String, String>()
        var opens = 0; var seals = 0
        var onOpen: (() -> Unit)? = null; var onSeal: (() -> Unit)? = null
        override fun seal(plain: String): String {
            seals++; onSeal?.invoke()
            return "synthetic-protected-${values.size}".also { values[it] = plain }
        }
        override fun open(protected: String): String {
            opens++; onOpen?.invoke()
            return values[protected] ?: error("private missing storage")
        }
    }
    private fun intent(before: EventContent = base, after: EventContent = before.copy(title = "New", description = "New notes", location = "")) =
        LocalEditIntent(MutationId("synthetic-owner"), scope, EVENT_FORMAT,
            RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-revision"), before), after)
    private fun prepared(protector: MemoryProtector, value: LocalEditIntent = intent(), row: JsonObject = original,
        clear: JsonObject = part(2, "new-clear"), encrypted: JsonObject = part(3, "new-secret"), changed: Boolean = true): LocalTextEditPreparation {
        fun texts(content: EventContent) = mapOf(SUMMARY to content.title, DESCRIPTION to content.description, LOCATION to content.location)
        val before = texts(value.base.content); val after = texts(value.content)
        val changes = before.keys.filter { before[it] != after[it] }.associateWith { field ->
            ProtonSharedTextChange(before.getValue(field), after.getValue(field).let { if (field != SUMMARY && it.isEmpty()) null else it })
        }
        val binding = ProtonTextPreparationBinding(value.scope, value.base.eventId, value.base.revision,
            "synthetic-uid", value.base.content.title, changes, timestamp)
        return ProtonTextPreparationCodec(protector).sealLocal(value, binding, ProtonTimestampedSharedText(row, clear, encrypted, changed))
    }
    private fun replace(record: ProtonProtectedTextUpdateRequest, nextId: MutationId = record.id,
        preparation: LocalTextEditPreparation = record.preparation, format: AdapterFormat = record.format,
        payload: String = record.protectedPayload) = ProtonProtectedTextUpdateRequest(nextId, preparation, format, payload)
    private suspend fun refused(reason: ProtonTextUpdateRequestFailure = ProtonTextUpdateRequestFailure.UNSUPPORTED, block: suspend () -> Unit) {
        try { block(); fail("Unsupported request must be refused") }
        catch (failure: ProtonTextUpdateRequestException) {
            assertEquals(reason, failure.reason); assertEquals("TEXT_UPDATE_REQUEST_${reason.name}", failure.message)
            assertNull(failure.cause); assertFalse(failure.toString().contains("synthetic")); assertFalse(failure.toString().contains("private"))
        }
    }

    @Test fun multiTextRequestReopensWithOnlyTheLiteralSameCalendarUpdateShape() = runTest {
        val storage = MemoryProtector(); val preparation = prepared(storage)
        val saved = ProtonTextUpdateRequestCodec(storage).seal(id, preparation, context)
        val reader = MemoryProtector().apply { values.putAll(storage.values) }
        val opened = ProtonTextUpdateRequestCodec(reader).open(saved)
        val expected = Json.parseToJsonElement("""{"MemberID":"synthetic-member","IsImport":0,"Events":[{"ID":"synthetic-event","Event":{
          "Permissions":1,"IsOrganizer":1,"IsBreakingChange":0,"IsPersonalSingleEdit":false,"Notifications":[],"Color":null,
          "VideoConferencingData":null,"SharedEventContent":[{"Type":2,"Data":"new-clear","Signature":"signature-new-clear"},
          {"Type":3,"Data":"new-secret","Signature":"signature-new-secret"}]}}]}""")
        assertEquals(expected, opened.request); assertEquals(context, opened.context)
        assertEquals(original, opened.fragments.originalRow); assertTrue(opened.fragments.changed)
        assertEquals(preparation.intent, saved.preparation.intent); assertEquals(TEXT_UPDATE_REQUEST_FORMAT, saved.format)
        assertEquals("ProtonProtectedTextUpdateRequest(redacted)", saved.toString())
        assertEquals("ProtonTextUpdateRequest(redacted)", opened.toString())
    }
    @Test fun descriptionLocationRemovalAndTimedProfilesKeepTheWholeLocalObservation() = runTest {
        val timed = base.copy(time = EventTime.Timed(Instant.parse("2026-10-24T12:00:00Z"),
            Instant.parse("2026-10-24T13:00:00Z"), ZoneId.of("Europe/Paris")))
        for ((before, after) in listOf(base to base.copy(description = "Only notes"), base to base.copy(location = ""),
            base.copy(description = "") to base.copy(description = "Inserted"), timed to timed.copy(location = "New room"))) {
            val storage = MemoryProtector(); val value = intent(before, after); val source = prepared(storage, value)
            val codec = ProtonTextUpdateRequestCodec(storage); val record = codec.seal(id, source, context)
            assertEquals(value, record.preparation.intent); assertEquals(original, codec.open(record).fragments.originalRow)
        }
    }
    @Test fun unchangedPartStaysExactButNormalizedNoOpCannotBecomeARequest() = runTest {
        val storage = MemoryProtector(); val clear = original.array("SharedEvents")[0] as JsonObject
        val secret = original.array("SharedEvents")[1] as JsonObject
        val codec = ProtonTextUpdateRequestCodec(storage)
        val onePart = codec.open(codec.seal(id, prepared(storage, clear = clear), context))
        assertEquals(clear, onePart.fragments.clearSharedPart)
        val before = base.copy(description = "Line\nText")
        val source = prepared(storage, intent(before, before.copy(description = "Line\r\nText")), clear = clear, encrypted = secret, changed = false)
        val seals = storage.seals
        refused { codec.seal(id, source, context) }; assertEquals(seals, storage.seals)
    }
    @Test fun legacyUnknownDetailsAndColoredPreparationsCannotWidenTheWireProfile() = runTest {
        val storage = MemoryProtector(); val codec = ProtonTextUpdateRequestCodec(storage)
        for (before in listOf(base.copy(details = null), base.copy(colorRgb = 0x112233))) {
            val source = prepared(storage, intent(before, before.copy(description = "New")))
            val seals = storage.seals
            refused { codec.seal(id, source, context) }; assertEquals(seals, storage.seals)
        }
    }
    @Test fun unknownRowSemanticsAndAuthorContextCannotBeDropped() = runTest {
        val storage = MemoryProtector(); val codec = ProtonTextUpdateRequestCodec(storage)
        for ((key, value) in listOf("Unknown" to JsonPrimitive(1), "Notifications" to JsonArray(listOf(JsonPrimitive(1))),
            "Permissions" to JsonPrimitive(7), "IsOrganizer" to JsonPrimitive(0), "Color" to JsonPrimitive("#112233"))) {
            val source = prepared(storage, row = JsonObject(original + (key to value)))
            refused { codec.seal(id, source, context) }
        }
        val source = prepared(storage)
        refused { codec.seal(id, source, JsonObject(context + ("author" to JsonPrimitive("other@example.invalid")))) }
        refused { codec.seal(id, source, JsonObject(context + ("extra" to JsonPrimitive(1)))) }
        refused { codec.seal(id, source, JsonObject(context - "bootstrap")) }
        for ((name, value) in listOf("permissions" to JsonPrimitive("16"), "flags" to JsonPrimitive(-1),
            "calendar" to JsonPrimitive("not-a-fingerprint"), "member" to JsonPrimitive(" "),
            "address" to JsonPrimitive("é".repeat(2049))))
            refused { codec.seal(id, source, JsonObject(context + (name to value))) }
    }
    @Test fun everyOuterLocatorAndCompletePreparationRemainBoundToTheProtectedPayload() = runTest {
        val storage = MemoryProtector(); val codec = ProtonTextUpdateRequestCodec(storage)
        val source = prepared(storage); val saved = codec.seal(id, source, context)
        val variants = listOf(source.intent.copy(id = MutationId("other")), source.intent.copy(scope = scope.copy(generation = 3)),
            source.intent.copy(scope = scope.copy(account = AccountId("other"))), source.intent.copy(scope = scope.copy(calendar = CalendarId("other"))),
            source.intent.copy(base = source.intent.base.copy(revision = Revision("other"))), source.intent.copy(content = source.intent.content.copy(location = "other")))
        for (value in variants) {
            val rebound = LocalTextEditPreparation(value, source.timestamp, source.format, source.protectedPayload)
            refused { codec.open(replace(saved, preparation = rebound)) }
        }
        refused { codec.open(replace(saved, nextId = MutationId("other"))) }
        refused { codec.open(replace(saved, preparation = LocalTextEditPreparation(source.intent, timestamp.plusSeconds(1), source.format, source.protectedPayload))) }
        for (format in listOf(TITLE_UPDATE_SUBMISSION_FORMAT, TEXT_UPDATE_REQUEST_FORMAT.copy(version = 2), TEXT_PREPARATION_FORMAT))
            refused { codec.open(replace(saved, format = format)) }
    }
    @Test fun separatelyValidPreparationAndRequestPayloadsCannotBeSwapped() = runTest {
        val storage = MemoryProtector(); val codec = ProtonTextUpdateRequestCodec(storage)
        val first = codec.seal(id, prepared(storage), context)
        val second = codec.seal(MutationId("other-request"), prepared(storage, intent(after = base.copy(location = "Other"))), context)
        refused { codec.open(replace(first, payload = second.protectedPayload)) }
        refused { codec.open(replace(first, preparation = second.preparation)) }
    }
    @Test fun protectedEnvelopeRejectsChangedContextRequestUnknownKeysAndDuplicateKeys() = runTest {
        val storage = MemoryProtector(); val codec = ProtonTextUpdateRequestCodec(storage)
        val saved = codec.seal(id, prepared(storage), context)
        val plain = storage.values.getValue(saved.protectedPayload)
        val root = Json.parseToJsonElement(plain) as JsonObject
        val changes = listOf(JsonObject(root + ("extra" to JsonPrimitive(1))).toString(),
            JsonObject(root + ("kind" to JsonPrimitive("title-update-submission"))).toString(),
            JsonObject(root + ("version" to JsonPrimitive("1"))).toString(),
            JsonObject(root + ("context" to JsonObject(root.obj("context") + ("member" to JsonPrimitive("other"))))).toString(),
            JsonObject(root + ("request" to JsonObject(root.obj("request") + ("IsImport" to JsonPrimitive(1))))).toString(),
            plain.replaceFirst("{", "{\"version\":1,"))
        for (changed in changes) { storage.values[saved.protectedPayload] = changed; refused { codec.open(saved) } }
    }
    @Test fun payloadAndIdentityBudgetsRejectMalformedUtf8AndOversizedInputs() = runTest {
        val storage = MemoryProtector(); val codec = ProtonTextUpdateRequestCodec(storage); val source = prepared(storage)
        for (requestId in listOf("x".repeat(4097), "é".repeat(2049), "bad\uD800"))
            refused { codec.seal(MutationId(requestId), source, context) }
        val saved = codec.seal(id, source, context)
        for (plain in listOf("x".repeat(4_194_305), "\uD800", "[]")) {
            storage.values[saved.protectedPayload] = plain
            refused { codec.open(saved) }
        }
    }
    @Test fun snapshotsSurviveProtectorCallbacksAndGetterAccess() = runTest {
        val storage = MemoryProtector(); val source = prepared(storage); val fields = context.toMutableMap()
        storage.onOpen = { fields["member"] = JsonPrimitive("changed-during-open") }
        val saved = ProtonTextUpdateRequestCodec(storage).seal(id, source, JsonObject(fields))
        storage.onOpen = null
        val opened = ProtonTextUpdateRequestCodec(storage).open(saved)
        assertEquals(context, opened.context)
        assertNotSame(saved.preparation, saved.preparation)
        assertNotSame(opened.context, opened.context); assertNotSame(opened.request, opened.request)
        assertNotSame(opened.fragments, opened.fragments); assertNotSame(opened.fragments.originalRow, opened.fragments.originalRow)
        val mutableNested = mutableListOf<JsonElement>(JsonPrimitive("kept"))
        val mutable = mutableMapOf<String, JsonElement>("nested" to JsonArray(mutableNested))
        val standalone = ProtonTextUpdateRequest(opened.fragments, JsonObject(mutable), JsonObject(mutable))
        mutableNested.clear(); mutable.clear()
        assertEquals(JsonArray(listOf(JsonPrimitive("kept"))), standalone.context["nested"])
        assertEquals(standalone.context, standalone.request)
    }
    @Test fun storageErrorsAreDistinctAndNeverCarryTheirOriginalCause() = runTest {
        val storage = MemoryProtector(); val source = prepared(storage); val codec = ProtonTextUpdateRequestCodec(storage)
        storage.onOpen = { throw java.io.IOException("private open failure") }
        refused(ProtonTextUpdateRequestFailure.STORAGE) { codec.seal(id, source, context) }
        storage.onOpen = null; storage.onSeal = { throw java.io.IOException("private seal failure") }
        refused(ProtonTextUpdateRequestFailure.STORAGE) { codec.seal(id, source, context) }
        storage.onSeal = null; val saved = codec.seal(id, source, context)
        storage.onOpen = { throw java.io.IOException("private reopen failure") }
        refused(ProtonTextUpdateRequestFailure.STORAGE) { codec.open(saved) }
    }
    @Test fun cancelledProtectorNormalAndExceptionalReturnsPrioritizeCancellationInsideCaller() = runTest {
        for (operation in listOf("seal-open", "seal-seal", "open")) for (throws in listOf(false, true)) {
            val storage = MemoryProtector(); val source = prepared(storage); val codec = ProtonTextUpdateRequestCodec(storage)
            val saved = if (operation == "open") codec.seal(id, source, context) else null
            var cancellations = 0; var ordinary = 0; var returns = 0
            supervisorScope {
                val child = async {
                    val jobContext = currentCoroutineContext()
                    val hook = { jobContext.cancel(); if (throws) throw java.io.IOException("private after cancel") }
                    if (operation == "seal-seal") storage.onSeal = hook else storage.onOpen = hook
                    try {
                        if (saved == null) codec.seal(id, source, context) else codec.open(saved)
                        returns++
                    } catch (failure: CancellationException) { cancellations++; throw failure }
                      catch (failure: Exception) { ordinary++; throw failure }
                }
                try { child.await(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
            }
            assertEquals(1, cancellations); assertEquals(0, ordinary); assertEquals(0, returns)
        }
    }
    @Test fun titleWrapperAndCommonHelperKeepIdenticalWireAndReadbackBehavior() {
        val title = TitleSubmissionFixture()
        val opened = title.codec.open(title.record)
        assertEquals(opened.request, ProtonSimpleEventUpdate.request(title.fragments.originalRow,
            title.fragments.clearSharedPart, title.fragments.encryptedSharedPart, title.fragments.changed, title.context))
        assertTrue(ProtonSimpleEventUpdate.matchesReadback(title.fragments.originalRow,
            title.fragments.clearSharedPart, title.fragments.encryptedSharedPart, title.readRow))
        assertFalse(ProtonSimpleEventUpdate.matchesReadback(title.fragments.originalRow,
            title.fragments.clearSharedPart, title.fragments.encryptedSharedPart,
            JsonObject(title.readRow + ("SharedKeyPacket" to JsonPrimitive("other")))))
    }
}
