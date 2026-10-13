// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/** Fabricated parts and decoder results exercise bindings, not native crypto or server acceptance. */
class ProtonTextUpdateReadbackVerifierTest {
    @Test fun completeTimedAndAllDayReadbackReturnsActualRevisionAndKnownEmptyContent() = runTest {
        for (timed in listOf(false, true)) {
            val f = Fixture(timed); val record = f.record()
            assertEquals(f.decoded, f.verify(record)); assertEquals(1, f.verifications)
            assertNotEquals(record.preparation.intent.base.revision, f.decoded!!.revision)
        }
    }

    @Test fun onlyThreeTextCrLfPairsAreCanonicalizedWithoutRewritingRawIntent() = runTest {
        val f = Fixture(multiline = true); val record = f.record()
        assertEquals(f.decoded, f.verify(record))
        assertTrue(record.preparation.intent.content.title.contains("\r\n"))
        assertTrue(record.preparation.intent.content.description.contains("\r\n"))
        assertTrue(record.preparation.intent.content.location.contains("\r\n"))
    }

    @Test fun optionalRemovalIsAcceptedWithOtherFieldsExact() = runTest {
        val f = Fixture(removal = true); val record = f.record()
        assertEquals("", record.preparation.intent.content.description)
        assertEquals(f.decoded, f.verify(record)); assertEquals(1, f.verifications)
    }

    @Test fun partsSignaturesAuthorsAndUntouchedMetadataCannotBeSubstituted() = runTest {
        val f = Fixture(); val record = f.record()
        val variations = mutableListOf<JsonObject>()
        for (index in 0..1) for (key in listOf("Data", "Signature", "Author")) {
            val parts = f.row.array("SharedEvents").toMutableList()
            parts[index] = JsonObject((parts[index] as JsonObject) + (key to JsonPrimitive("different")))
            variations += JsonObject(f.row + ("SharedEvents" to JsonArray(parts)))
        }
        for (key in listOf("ID", "CalendarID", "UID", "SharedKeyPacket", "StartTimezone", "EndTimezone"))
            variations += JsonObject(f.row + (key to JsonPrimitive("different")))
        variations += JsonObject(f.row + ("Unknown" to JsonNull))
        variations += JsonObject(f.row + ("ModifyTime" to JsonPrimitive(1)))
        variations += JsonObject(f.row + ("Notifications" to JsonArray(listOf(JsonPrimitive(1)))))
        for (row in variations) assertNull(f.verify(record, row = row))
        assertEquals(0, f.verifications)
        assertEquals(f.decoded, f.verify(record))
    }

    @Test fun contextBootstrapAndAccountMustMatchBeforeDecoder() = runTest {
        val f = Fixture(); val record = f.record()
        for (key in f.context.keys) assertNull(f.verify(record,
            context = JsonObject(f.context + (key to JsonPrimitive("different")))))
        assertNull(f.verify(record, bootstrap = buildJsonObject { put("different", true) }))
        assertNull(f.reader.verify(f.session.copy(userId = me.proton.core.domain.entity.UserId("different")),
            record, f.context, f.bootstrap, f.row))
        assertEquals(0, f.verifications); assertEquals(f.decoded, f.verify(record))
    }

    @Test fun callbackMustProveExactIdAndComputedNewRevision() = runTest {
        val f = Fixture(); val record = f.record(); val original = f.decoded!!
        for (wrong in listOf(original.copy(eventId = EventId("different")),
            original.copy(revision = Revision("invented")), original.copy(revision = record.preparation.intent.base.revision))) {
            f.decoded = wrong; assertNull(f.verify(record))
        }
        f.decoded = original; assertEquals(original, f.verify(record))
    }

    @Test fun exactPartsNeverExcuseAnyDifferentDecodedFieldOrUnknownDetails() = runTest {
        val f = Fixture(timed = true); val record = f.record(); val good = f.decoded!!
        val content = good.content; val time = content.time as EventTime.Timed
        val variants = listOf(content.copy(title = "different"), content.copy(description = "different"),
            content.copy(location = "different"), content.copy(title = content.title + " "),
            content.copy(time = time.copy(start = time.start.plusNanos(1))),
            content.copy(time = time.copy(end = time.end.plusSeconds(1))),
            content.copy(time = time.copy(zone = ZoneId.of("UTC"))),
            content.copy(colorRgb = 42), content.copy(details = null),
            content.copy(details = EventDetails(reminders = listOf(EventReminder(15)))))
        for (wrong in variants) { f.decoded = good.copy(content = wrong); assertNull(f.verify(record)) }
        f.decoded = good; assertEquals(good, f.verify(record))
    }

    @Test fun unverifiedOrFailingDecoderCannotProduceSuccess() = runTest {
        val f = Fixture(); val record = f.record()
        f.decoded = null; assertNull(f.verify(record))
        f.onVerify = { throw IOException("synthetic-sensitive") }; assertNull(f.verify(record))
        assertEquals(2, f.verifications)
    }

    @Test fun observationsAreSnapshottedBeforeProtectionCallbackCanMutateThem() = runTest {
        val f = Fixture(); val record = f.record()
        val context = LinkedHashMap(f.context); val bootstrap = LinkedHashMap(f.bootstrap); val row = LinkedHashMap(f.row)
        f.beforeOpen = { context.clear(); bootstrap.clear(); row.clear() }
        assertEquals(f.decoded, f.verify(record, JsonObject(context), JsonObject(bootstrap), JsonObject(row)))
        assertEquals(1, f.verifications); assertTrue(row.isEmpty())
    }

    @Test fun malformedProtectedHistoryAndStorageFailuresKeepSanitizedCategories() = runTest {
        val f = Fixture(); val record = f.record()
        val malformed = ProtonProtectedTextUpdateRequest(record.id, record.preparation,
            record.format.copy(version = 99), record.protectedPayload)
        try { f.verify(malformed); fail("Malformed history accepted") }
        catch (failure: ProtonTextUpdateRequestException) {
            assertEquals(ProtonTextUpdateRequestFailure.UNSUPPORTED, failure.reason)
            assertEquals("TEXT_UPDATE_REQUEST_UNSUPPORTED", failure.message); assertNull(failure.cause)
        }
        f.beforeOpen = { throw IOException("synthetic-sensitive") }
        try { f.verify(record); fail("Storage error swallowed") }
        catch (failure: ProtonTextUpdateRequestException) {
            assertEquals(ProtonTextUpdateRequestFailure.STORAGE, failure.reason)
            assertEquals("TEXT_UPDATE_REQUEST_STORAGE", failure.message); assertNull(failure.cause)
        }
        assertEquals(0, f.verifications)
    }

    @Test fun returnedKnownEmptyDetailsDoNotRetainDecoderMutableListAliases() = runTest {
        val f = Fixture(); val record = f.record(); val reminders = mutableListOf<EventReminder>()
        f.decoded = f.decoded!!.copy(content = f.decoded!!.content.copy(details = EventDetails(reminders = reminders)))
        val result = requireNotNull(f.verify(record)); reminders += EventReminder(15)
        assertEquals(EventDetails(), result.content.details)
    }

    @Test fun cancellationDominatesCallbackReturnAndOrdinaryFailureInsideChild() = runTest {
        for (fromProtection in listOf(false, true)) for (throwOrdinary in listOf(false, true)) {
            val f = Fixture(); val record = f.record()
            var cancelled = 0; var ordinary = 0; var returned = 0
            supervisorScope {
                val child = async {
                    val job = currentCoroutineContext().job
                    val cancel: () -> Unit = { job.cancel(); if (throwOrdinary) throw IOException("synthetic-sensitive") }
                    if (fromProtection) f.beforeOpen = cancel else f.onVerify = cancel
                    try { f.verify(record); returned++ }
                    catch (_: CancellationException) { cancelled++ }
                    catch (_: Exception) { ordinary++ }
                }
                try { child.await() } catch (_: CancellationException) { }
            }
            assertEquals(1, cancelled); assertEquals(0, ordinary); assertEquals(0, returned)
            assertEquals(if (fromProtection) 0 else 1, f.verifications)
        }
    }

    @Test fun malformedUtf8ObservationsAreRefusedBeforeOpeningProtectedHistory() = runTest {
        for (target in 0..2) {
            val f = Fixture(); val record = f.record(); f.protectedOpens = 0
            val malformed = buildJsonObject { put("synthetic", "\uD800") }
            assertNull(f.verify(record, context = if (target == 0) malformed else f.context,
                bootstrap = if (target == 1) malformed else f.bootstrap, row = if (target == 2) malformed else f.row))
            assertEquals(0, f.protectedOpens); assertEquals(0, f.verifications)
        }
    }

    @Test fun oversizedObservationsAreRefusedBeforeOpeningProtectedHistory() = runTest {
        for (text in listOf("x".repeat(LocalTextEditPreparation.MAX_PAYLOAD_BYTES),
            "\u20AC".repeat(LocalTextEditPreparation.MAX_PAYLOAD_BYTES / 2))) for (target in 0..2) {
            val oversized = buildJsonObject { put("synthetic", text) }
            val f = Fixture(); val record = f.record(); f.protectedOpens = 0
            assertNull(f.verify(record, context = if (target == 0) oversized else f.context,
                bootstrap = if (target == 1) oversized else f.bootstrap, row = if (target == 2) oversized else f.row))
            assertEquals(0, f.protectedOpens); assertEquals(0, f.verifications)
        }
    }

    @Test fun alreadyCancelledEntryDoesNotReadJsonOrOpenProtectedHistory() = runTest {
        val f = Fixture(); val record = f.record(); f.protectedOpens = 0
        var reads = 0; var cancelled = 0; var ordinary = 0; var returned = 0
        val watched = JsonObject(object : AbstractMap<String, JsonElement>() {
            override val entries: Set<Map.Entry<String, JsonElement>>
                get() { reads++; return f.context.entries }
        })
        supervisorScope {
            val child = async {
                currentCoroutineContext().cancel()
                try { f.verify(record, context = watched); returned++ }
                catch (_: CancellationException) { cancelled++ }
                catch (_: Exception) { ordinary++ }
            }
            try { child.await() } catch (_: CancellationException) { }
        }
        assertEquals(1, cancelled); assertEquals(0, ordinary); assertEquals(0, returned)
        assertEquals(0, reads); assertEquals(0, f.protectedOpens); assertEquals(0, f.verifications)
    }

    private class Fixture(timed: Boolean = false, multiline: Boolean = false, removal: Boolean = false) {
        private val seed = TitleSubmissionFixture()
        val session = seed.initial
        val bootstrap = seed.bootstrap
        val context = JsonObject(seed.context + buildJsonObject {
            put("permissions", 16); put("flags", 1)
            for (name in listOf("calendarKey", "signingKey", "calendar"))
                put(name, ProtonSimpleEventUpdate.hash("synthetic-$name"))
            put("bootstrap", ProtonSimpleEventUpdate.hash(bootstrap.toString()))
        })
        private val originalRow = seed.row
        private val time = if (timed) EventTime.Timed(Instant.parse("2026-10-04T12:00:00Z"),
            Instant.parse("2026-10-04T13:00:00Z"), ZoneId.of("Europe/Paris")) else seed.content.time
        private val baseContent = seed.content.copy(time = time, description = "Old notes", location = "Old place")
        private val next = baseContent.copy(title = if (multiline) "New\r\ntitle" else "New title",
            description = if (removal) "" else if (multiline) "New\r\nnotes" else "New notes",
            location = if (multiline) "New\r\nplace" else "New place")
        private val intent = seed.intent.copy(base = seed.intent.base.copy(revision = originalRow.revision(bootstrap),
            content = baseContent), content = next)
        private val fragments = ProtonTimestampedSharedText(originalRow, seed.fragments.clearSharedPart,
            seed.fragments.encryptedSharedPart, true)
        val row = JsonObject(originalRow + mapOf("SharedEvents" to JsonArray(listOf(fragments.clearSharedPart,
            fragments.encryptedSharedPart)), "ModifyTime" to JsonPrimitive(5)))
        var decoded: RemoteChange.Upsert? = intent.base.copy(revision = row.revision(bootstrap), content = next.copy(
            title = next.title.replace("\r\n", "\n"), description = next.description.replace("\r\n", "\n"),
            location = next.location.replace("\r\n", "\n")))
        var beforeOpen: () -> Unit = {}
        var onVerify: () -> Unit = {}
        var verifications = 0
        var protectedOpens = 0
        private val protected = mutableMapOf<String, String>()
        private val protector = object : EventPayloadProtector {
            override fun seal(plain: String) = "synthetic-${protected.size}".also { protected[it] = plain }
            override fun open(protected: String): String { protectedOpens++; beforeOpen(); return requireNotNull(this@Fixture.protected[protected]) }
        }
        private val codec = ProtonTextUpdateRequestCodec(protector)
        val reader = ProtonTextUpdateReadbackVerifier(codec, CreateEventVerifier { actualSession, calendar, actualBootstrap, actualRow, uid, policy ->
            verifications++
            assertEquals(session, actualSession); assertEquals(intent.scope.calendar.value, calendar)
            assertEquals(bootstrap, actualBootstrap); assertEquals(row, actualRow); assertEquals("synthetic-uid", uid)
            assertEquals(CreatedEventReadbackPolicy.KNOWN_EMPTY_V2, policy)
            onVerify(); decoded
        })
        suspend fun record(): ProtonProtectedTextUpdateRequest {
            val changes = linkedMapOf(
                ProtonSharedTextProperty.SUMMARY to ProtonSharedTextChange(baseContent.title, next.title),
                ProtonSharedTextProperty.DESCRIPTION to ProtonSharedTextChange(baseContent.description, next.description.takeUnless { it.isEmpty() }),
                ProtonSharedTextProperty.LOCATION to ProtonSharedTextChange(baseContent.location, next.location))
            val binding = ProtonTextPreparationBinding(intent.scope, intent.base.eventId, intent.base.revision,
                "synthetic-uid", baseContent.title, changes, seed.timestamp)
            val preparation = ProtonTextPreparationCodec(protector).sealLocal(intent, binding, fragments)
            return codec.seal(MutationId("synthetic-text-request"), preparation, context)
        }
        suspend fun verify(record: ProtonProtectedTextUpdateRequest, context: JsonObject = this.context,
            bootstrap: JsonObject = this.bootstrap, row: JsonObject = this.row) = reader.verify(session, record, context, bootstrap, row)
    }
}
