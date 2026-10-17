// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic structural request evidence; no service sending or cryptographic certification. */
class ProtonTimeUpdateRequestCodecTest {
    private val scope = CalendarScope(AccountId("time-account"), CalendarId("time-calendar"), 4)
    private val stamp = Instant.parse("2026-10-17T08:00:00Z")
    private val id = MutationId("time-request")
    private val start = Instant.parse("2026-10-17T09:00:00Z")
    private val time = EventTime.Timed(start, start.plusSeconds(3600), ZoneId.of("UTC"))
    private val base = EventContent("Title", time, "Notes\r\nNext", "Place", details = EventDetails())
    private fun part(type: Int, data: String) = buildJsonObject {
        put("Type", type); put("Data", data); put("Signature", "signature-$data"); put("Author", "fixture@example.invalid")
    }
    private val original = buildJsonObject {
        put("ID", "time-event"); put("CalendarID", scope.calendar.value); put("UID", "time-uid")
        put("SharedKeyPacket", "retained-packet"); put("Permissions", 3); put("IsOrganizer", 1); put("IsPersonalSingleEdit", false)
        put("CreateTime", 1); put("ModifyTime", 2); put("LastEditTime", 2)
        put("StartTime", start.epochSecond); put("EndTime", start.plusSeconds(3600).epochSecond)
        put("FullDay", 0); put("StartTimezone", "UTC"); put("EndTimezone", "UTC")
        for (name in listOf("CalendarEvents", "AttendeesEvents", "Notifications", "Attendees", "Exdates", "NotificationOccurrences"))
            put(name, JsonArray(emptyList()))
        put("SharedEvents", JsonArray(listOf(part(2, "old-clear"), part(3, "old-secret"))))
    }
    private val context = buildJsonObject {
        put("member", "time-member"); put("address", "time-address"); put("permissions", 16); put("flags", 1)
        put("key", "time-key"); put("passphrase", "time-passphrase"); put("author", "fixture@example.invalid")
        for (name in listOf("calendarKey", "signingKey", "calendar", "bootstrap")) put(name, "a".repeat(64))
    }
    private class MemoryProtector : EventPayloadProtector {
        val values = linkedMapOf<String, String>(); var opens = 0; var seals = 0
        var onOpen: (() -> Unit)? = null; var onSeal: (() -> Unit)? = null
        var sealResult: String? = null
        override fun seal(plain: String): String {
            seals++; onSeal?.invoke()
            return (sealResult ?: "time-protected-${values.size}").also { values[it] = plain }
        }
        override fun open(protected: String): String { opens++; onOpen?.invoke(); return values[protected] ?: error("private missing storage") }
    }
    private fun intent(before: EventContent = base,
        after: EventContent = before.copy(time = time.copy(end = start.plusSeconds(7200)))) =
        LocalEditIntent(MutationId("time-owner"), scope, EVENT_FORMAT,
            RemoteChange.Upsert(EventId("time-event"), Revision("time-revision"), before), after)
    private fun prepared(storage: MemoryProtector, value: LocalEditIntent = intent(), row: JsonObject = original) =
        ProtonTimePreparationCodec(storage).sealLocal(value, "time-uid", stamp,
            ProtonTimestampedSharedTime(row, part(2, "new-clear"), part(3, "new-secret"), true))
    private fun replace(record: ProtonProtectedTimeUpdateRequest, nextId: MutationId = record.id,
        preparation: LocalTimeEditPreparation = record.preparation, format: AdapterFormat = record.format,
        payload: String = record.protectedPayload) = ProtonProtectedTimeUpdateRequest(nextId, preparation, format, payload)
    private fun JsonObject.changed(name: String, value: JsonElement) = JsonObject(this + (name to value))
    private fun JsonObject.obj(name: String) = getValue(name) as JsonObject
    private suspend fun refused(reason: ProtonTimeUpdateRequestFailure = ProtonTimeUpdateRequestFailure.UNSUPPORTED,
        action: suspend () -> Unit) {
        try { action(); fail("Expected temporal request refusal") }
        catch (error: ProtonTimeUpdateRequestException) {
            assertEquals(reason, error.reason); assertEquals("TIME_UPDATE_REQUEST_${reason.name}", error.message)
            assertNull(error.cause); assertFalse(error.toString().contains("private")); assertFalse(error.toString().contains("fixture"))
        }
    }
    private fun rebound(source: LocalTimeEditPreparation, value: LocalEditIntent = source.intent,
        timestamp: Instant = source.timestamp, format: AdapterFormat = source.format, payload: String = source.protectedPayload) =
        LocalTimeEditPreparation(value, timestamp, format, payload)
    private fun withBase(value: LocalEditIntent, content: EventContent) = value.copy(
        base = value.base.copy(content = content), content = content.copy(time = value.content.time))

    @Test fun literalSameCalendarTemporalRequestIndependentlyReopensWithoutTransport() = runTest {
        val storage = MemoryProtector(); val source = prepared(storage)
        val saved = ProtonTimeUpdateRequestCodec(storage).seal(id, source, context)
        val reader = MemoryProtector().apply { values.putAll(storage.values) }
        val opened = ProtonTimeUpdateRequestCodec(reader).open(saved)
        val expected = Json.parseToJsonElement("""{"MemberID":"time-member","IsImport":0,"Events":[{"ID":"time-event","Event":{
          "Permissions":1,"IsOrganizer":1,"IsBreakingChange":0,"IsPersonalSingleEdit":false,"Notifications":[],"Color":null,
          "VideoConferencingData":null,"SharedEventContent":[{"Type":2,"Data":"new-clear","Signature":"signature-new-clear"},
          {"Type":3,"Data":"new-secret","Signature":"signature-new-secret"}]}}]}""")
        assertEquals(expected, opened.request); assertEquals(context, opened.context)
        assertEquals(original, opened.fragments.originalRow); assertTrue(opened.fragments.changed)
        assertEquals(source.intent, saved.preparation.intent); assertEquals(TIME_UPDATE_REQUEST_FORMAT, saved.format)
        assertEquals("ProtonProtectedTimeUpdateRequest(redacted)", saved.toString())
        assertEquals("ProtonTimeUpdateRequest(redacted)", opened.toString())
    }
    @Test fun entireOwnerOriginalReplacementDateZoneAndTimestampCannotBeRebound() = runTest {
        val storage = MemoryProtector(); val codec = ProtonTimeUpdateRequestCodec(storage)
        val source = prepared(storage); val saved = codec.seal(id, source, context); val value = source.intent
        val otherZoneBase = base.copy(time = time.copy(zone = ZoneId.of("Etc/UTC")))
        val alias = intent(otherZoneBase, otherZoneBase.copy(time = time.copy(end = start.plusSeconds(7200), zone = ZoneId.of("Etc/UTC"))))
        val variants = listOf(value.copy(id = MutationId("other")), value.copy(scope = scope.copy(generation = 5)),
            value.copy(scope = scope.copy(account = AccountId("other"))), value.copy(scope = scope.copy(calendar = CalendarId("other"))),
            value.copy(base = value.base.copy(eventId = EventId("other"))), value.copy(base = value.base.copy(revision = Revision("other"))),
            value.copy(format = EVENT_FORMAT.copy(version = EVENT_FORMAT.version + 1)),
            withBase(value, base.copy(title = "Other")), withBase(value, base.copy(description = "Other")), withBase(value, base.copy(location = "Other")),
            withBase(value, base.copy(time = time.copy(start = start.minusSeconds(1)))),
            withBase(value, base.copy(time = time.copy(end = time.end.plusSeconds(1)))),
            value.copy(content = value.content.copy(time = time.copy(end = start.plusSeconds(7201)))),
            value.copy(content = value.content.copy(time = time.copy(start = start.plusSeconds(86400), end = start.plusSeconds(90000)))), alias)
        for (next in variants)
            refused { codec.open(replace(saved, preparation = rebound(source, next))) }
        refused { codec.open(replace(saved, nextId = MutationId("other"))) }
        refused { codec.open(replace(saved, preparation = rebound(source, timestamp = stamp.plusSeconds(1)))) }
    }
    @Test fun separateValidPreparationCiphertextsAndRequestPayloadsCannotBeSubstituted() = runTest {
        val storage = MemoryProtector(); val codec = ProtonTimeUpdateRequestCodec(storage)
        val first = codec.seal(id, prepared(storage), context)
        val equivalentCapsule = prepared(storage)
        refused { codec.open(replace(first, preparation = equivalentCapsule)) }
        val second = codec.seal(MutationId("other-request"), prepared(storage,
            intent(after = base.copy(time = time.copy(end = start.plusSeconds(10800))))), context)
        refused { codec.open(replace(first, payload = second.protectedPayload)) }
        refused { codec.open(replace(first, preparation = second.preparation)) }
    }
    @Test fun oldTextTitleFamiliesAndObservationVersionsRefuseBeforeStorageAccess() = runTest {
        val storage = MemoryProtector(); val source = prepared(storage); val codec = ProtonTimeUpdateRequestCodec(storage)
        val beforeSeals = storage.seals; val beforeOpens = storage.opens
        for (format in listOf(LOCAL_TEXT_PREPARATION_FORMAT, LOCAL_TITLE_PREPARATION_FORMAT, LOCAL_TIME_PREPARATION_FORMAT.copy(version = 2)))
            refused { codec.seal(id, rebound(source, format = format), context) }
        val otherObservation = source.intent.copy(format = EVENT_FORMAT.copy(version = EVENT_FORMAT.version + 1))
        refused { codec.seal(id, rebound(source, otherObservation), context) }
        assertEquals(beforeSeals, storage.seals); assertEquals(beforeOpens, storage.opens)
        val saved = codec.seal(id, source, context); val opens = storage.opens
        for (format in listOf(TEXT_UPDATE_REQUEST_FORMAT, TEXT_PREPARATION_FORMAT, TITLE_UPDATE_SUBMISSION_FORMAT,
            TIME_UPDATE_REQUEST_FORMAT.copy(version = 2))) refused { codec.open(replace(saved, format = format)) }
        assertEquals(opens, storage.opens)
    }
    @Test fun scopeRowAndTemporalMetadataProfilesCannotWidenOrDropData() = runTest {
        val storage = MemoryProtector(); val codec = ProtonTimeUpdateRequestCodec(storage)
        for ((key, next) in listOf("Unknown" to JsonPrimitive(1), "Notifications" to JsonArray(listOf(JsonPrimitive(1))),
            "Permissions" to JsonPrimitive(7), "IsOrganizer" to JsonPrimitive(0), "Color" to JsonPrimitive("#112233"),
            "StartTime" to JsonPrimitive(start.epochSecond + 1), "EndTime" to JsonPrimitive(time.end.epochSecond + 1),
            "StartTimezone" to JsonPrimitive("Europe/Paris"), "EndTimezone" to JsonPrimitive("Etc/UTC"), "FullDay" to JsonPrimitive(1))) {
            val source = prepared(storage, row = original.changed(key, next)); val seals = storage.seals
            refused { codec.seal(id, source, context) }; assertEquals(seals, storage.seals)
        }
        val source = prepared(storage)
        val otherScope = source.intent.copy(scope = scope.copy(generation = 5))
        val seals = storage.seals
        refused { codec.seal(id, rebound(source, otherScope), context) }; assertEquals(seals, storage.seals)
    }
    @Test fun closedContextRequiresTypedBoundedIdentitiesAndMatchingAuthorBeforeProtection() = runTest {
        val storage = MemoryProtector(); val source = prepared(storage); val codec = ProtonTimeUpdateRequestCodec(storage)
        val variants = listOf(context.changed("extra", JsonNull), JsonObject(context - "bootstrap"),
            context.changed("author", JsonPrimitive("other@example.invalid")), context.changed("permissions", JsonPrimitive("16")),
            context.changed("flags", JsonPrimitive(-1)), context.changed("calendar", JsonPrimitive("not-a-fingerprint")),
            context.changed("member", JsonPrimitive(" ")), context.changed("address", JsonPrimitive("é".repeat(2049))),
            context.changed("key", JsonPrimitive("bad\uD800")))
        val seals = storage.seals
        for (next in variants) refused { codec.seal(id, source, next) }
        assertEquals(seals, storage.seals)
    }
    @Test fun protectedEnvelopeKindVersionIdentityContextRequestAndDuplicateKeysRemainClosed() = runTest {
        val storage = MemoryProtector(); val codec = ProtonTimeUpdateRequestCodec(storage)
        val saved = codec.seal(id, prepared(storage), context); val good = storage.values.getValue(saved.protectedPayload)
        val root = Json.parseToJsonElement(good) as JsonObject
        val variants = listOf(root.changed("extra", JsonNull).toString(), JsonObject(root - "preparation").toString(),
            root.changed("kind", JsonPrimitive("text-update-request")).toString(), root.changed("version", JsonPrimitive("1")).toString(),
            root.changed("version", JsonPrimitive(2)).toString(), root.changed("requestId", JsonPrimitive("other")).toString(),
            root.changed("preparation", root.obj("preparation").changed("payload", JsonPrimitive("0".repeat(64)))).toString(),
            root.changed("context", root.obj("context").changed("member", JsonPrimitive("other"))).toString(),
            root.changed("request", root.obj("request").changed("IsImport", JsonPrimitive(1))).toString(),
            good.replaceFirst("{", "{\"version\":1,"), good.replace("\"member\":", "\"m\\u0065mber\":\"other\",\"member\":"))
        for (next in variants) { storage.values[saved.protectedPayload] = next; refused { codec.open(saved) } }
    }
    @Test fun strictUtf8ByteLimitsCoverRequestIdsPlainAndProtectedPayloads() = runTest {
        val storage = MemoryProtector(); val codec = ProtonTimeUpdateRequestCodec(storage); val source = prepared(storage)
        val seals = storage.seals
        for (next in listOf("x".repeat(4097), "é".repeat(2049), "bad\uD800"))
            refused { codec.seal(MutationId(next), source, context) }
        assertEquals(seals, storage.seals)
        val boundary = codec.seal(MutationId("é".repeat(2048)), source, context); codec.open(boundary)
        val saved = codec.seal(id, source, context)
        for (next in listOf("x".repeat(4_194_305), "é".repeat(2_097_153), "\uD800", "[]")) {
            storage.values[saved.protectedPayload] = next; refused { codec.open(saved) }
        }
        for (next in listOf(" ", "é".repeat(2_097_153), "\uD800")) {
            storage.sealResult = next; refused { codec.seal(id, source, context) }
            refused { codec.open(replace(saved, payload = next)) }
        }
    }
    @Test fun snapshotsPrecedeFirstProtectorCallbackAndGettersNeverExposeSharedJson() = runTest {
        val storage = MemoryProtector(); val source = prepared(storage); val fields = context.toMutableMap()
        storage.onOpen = { fields["member"] = JsonPrimitive("changed-during-open") }
        val saved = ProtonTimeUpdateRequestCodec(storage).seal(id, source, JsonObject(fields))
        storage.onOpen = null
        val opened = ProtonTimeUpdateRequestCodec(storage).open(saved)
        assertEquals(context, opened.context); assertNotSame(saved.preparation, saved.preparation)
        assertNotSame(opened.context, opened.context); assertNotSame(opened.request, opened.request)
        assertNotSame(opened.fragments, opened.fragments); assertNotSame(opened.fragments.originalRow, opened.fragments.originalRow)
        val nested = mutableListOf<JsonElement>(JsonPrimitive("retained")); val map = mutableMapOf<String, JsonElement>("nested" to JsonArray(nested))
        val standalone = ProtonTimeUpdateRequest(opened.fragments, JsonObject(map), JsonObject(map))
        nested.clear(); map.clear()
        assertEquals(JsonArray(listOf(JsonPrimitive("retained"))), standalone.context["nested"])
        assertEquals(standalone.context, standalone.request)
    }
    @Test fun nestedPreparationAndRequestStorageFailuresRemainDistinctAndSanitized() = runTest {
        val storage = MemoryProtector(); val source = prepared(storage); val codec = ProtonTimeUpdateRequestCodec(storage)
        storage.onOpen = { throw java.io.IOException("private capsule failure") }
        refused(ProtonTimeUpdateRequestFailure.STORAGE) { codec.seal(id, source, context) }
        storage.onOpen = null; storage.onSeal = { throw java.io.IOException("private request seal failure") }
        refused(ProtonTimeUpdateRequestFailure.STORAGE) { codec.seal(id, source, context) }
        storage.onSeal = null; val saved = codec.seal(id, source, context)
        var openCount = 0
        storage.onOpen = { if (++openCount == 2) throw java.io.IOException("private request reopen failure") }
        refused(ProtonTimeUpdateRequestFailure.STORAGE) { codec.open(saved) }
        storage.onOpen = { throw java.io.IOException("private capsule reopen failure") }
        refused(ProtonTimeUpdateRequestFailure.STORAGE) { codec.open(saved) }
    }
    @Test fun cancellationWinsAtEveryNormalOrExceptionalProtectorReturn() = runTest {
        for (operation in listOf("seal-open", "seal-seal", "open-capsule", "open-request")) for (throws in listOf(false, true)) {
            val storage = MemoryProtector(); val source = prepared(storage); val codec = ProtonTimeUpdateRequestCodec(storage)
            val saved = if (operation.startsWith("open")) codec.seal(id, source, context) else null
            var cancellations = 0; var ordinary = 0; var returns = 0; var callbackCount = 0
            supervisorScope {
                val child = async {
                    val jobContext = currentCoroutineContext()
                    val hook = {
                        callbackCount++
                        if (operation != "open-request" || callbackCount == 2) {
                            jobContext.cancel(); if (throws) throw java.io.IOException("private after cancellation")
                        }
                    }
                    if (operation == "seal-seal") storage.onSeal = hook else storage.onOpen = hook
                    try {
                        if (saved == null) codec.seal(id, source, context) else codec.open(saved)
                        returns++
                    } catch (cancelled: CancellationException) { cancellations++; throw cancelled }
                      catch (failure: Exception) { ordinary++; throw failure }
                }
                try { child.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
            }
            assertEquals(1, cancellations); assertEquals(0, ordinary); assertEquals(0, returns)
        }
    }
}
