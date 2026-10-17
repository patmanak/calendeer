// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Fabricated fragments/decoder results prove supplied binding checks, not service acceptance. */
class ProtonTimeUpdateReadbackVerifierTest {
    @Test fun exactTemporalMetadataAndVerifiedContentsReturnActualNewRevision() = runTest {
        val f = Fixture(); val record = f.record()
        assertEquals(f.decoded, f.verify(record)); assertEquals(1, f.verifications)
        assertNotEquals(record.preparation.intent.base.revision, f.decoded!!.revision)
    }

    @Test fun eachTemporalMetadataFieldIsRequiredAndCheckedBeforeCrypto() = runTest {
        val f = Fixture(); val record = f.record()
        for (name in listOf("StartTime", "EndTime", "FullDay", "StartTimezone", "EndTimezone")) {
            assertNull(f.verify(record, row = JsonObject(f.row - name)))
            val wrong = if (name.endsWith("Timezone")) JsonPrimitive("Europe/Paris") else JsonPrimitive(1)
            assertNull(f.verify(record, row = JsonObject(f.row + (name to wrong))))
        }
        assertEquals(0, f.verifications)
        assertNull(f.verify(record, row = f.originalReadback))
    }

    @Test fun sharedPartsIdentityPacketAndOtherMetadataRemainExact() = runTest {
        val f = Fixture(); val record = f.record()
        for (index in 0..1) for (field in listOf("Data", "Signature", "Author")) {
            val parts = f.row.array("SharedEvents").toMutableList()
            parts[index] = JsonObject((parts[index] as JsonObject) + (field to JsonPrimitive("different")))
            assertNull(f.verify(record, row = JsonObject(f.row + ("SharedEvents" to JsonArray(parts)))))
        }
        for (field in listOf("ID", "CalendarID", "UID", "SharedKeyPacket"))
            assertNull(f.verify(record, row = JsonObject(f.row + (field to JsonPrimitive("different")))))
        assertNull(f.verify(record, row = JsonObject(f.row + ("Unrecognized" to JsonNull))))
        assertNull(f.verify(record, row = JsonObject(f.row + ("ModifyTime" to JsonPrimitive(1)))))
        assertEquals(0, f.verifications)
    }

    @Test fun contextBootstrapAndAccountStayBoundBeforeDecoder() = runTest {
        val f = Fixture(); val record = f.record()
        for (field in f.context.keys)
            assertNull(f.verify(record, context = JsonObject(f.context + (field to JsonPrimitive("different")))))
        assertNull(f.verify(record, bootstrap = buildJsonObject { put("different", true) }))
        assertNull(f.reader.verify(f.session.copy(userId = me.proton.core.domain.entity.UserId("different")),
            record, f.context, f.bootstrap, f.row))
        assertEquals(0, f.verifications)
    }

    @Test fun matchingApiMetadataCannotExcuseAnyDifferentDecryptedField() = runTest {
        val f = Fixture(); val record = f.record(); val good = f.decoded!!
        val content = good.content; val time = content.time as EventTime.Timed
        for (bad in listOf(content.copy(title = "different"), content.copy(description = "different"),
            content.copy(location = "different"), content.copy(time = time.copy(start = time.start.plusSeconds(1))),
            content.copy(time = time.copy(end = time.end.plusNanos(1))),
            content.copy(time = time.copy(zone = ZoneId.of("UTC"))), content.copy(colorRgb = 42),
            content.copy(details = null), content.copy(details = EventDetails(reminders = listOf(EventReminder(5)))))) {
            f.decoded = good.copy(content = bad); assertNull(f.verify(record))
        }
        for (bad in listOf(good.copy(eventId = EventId("different")), good.copy(revision = Revision("invented")),
            good.copy(revision = record.preparation.intent.base.revision))) {
            f.decoded = bad; assertNull(f.verify(record))
        }
        f.decoded = null; assertNull(f.verify(record))
        f.onVerify = { throw java.io.IOException("Synthetic private failure") }; assertNull(f.verify(record))
    }

    @Test fun inputSnapshotsAndReturnedDetailsCannotBeMutatedByCallbacks() = runTest {
        val f = Fixture(); val record = f.record()
        val context = f.context.toMutableMap(); val bootstrap = f.bootstrap.toMutableMap(); val row = f.row.toMutableMap()
        f.beforeOpen = { context.clear(); bootstrap.clear(); row.clear() }
        val reminders = mutableListOf<EventReminder>()
        f.decoded = f.decoded!!.copy(content = f.decoded!!.content.copy(details = EventDetails(reminders = reminders)))
        val actual = checkNotNull(f.verify(record, JsonObject(context), JsonObject(bootstrap), JsonObject(row)))
        reminders += EventReminder(5)
        assertEquals(EventDetails(), actual.content.details)
        assertTrue(row.isEmpty()); assertEquals(1, f.verifications)
    }

    @Test fun malformedHistoryStorageAndCancellationKeepTheirDistinctOutcomes() = runTest {
        val f = Fixture(); val record = f.record()
        try { f.verify(ProtonProtectedTimeUpdateRequest(record.id, record.preparation, record.format.copy(version = 2), record.protectedPayload)); fail("Malformed accepted") }
        catch (failure: ProtonTimeUpdateRequestException) { assertEquals(ProtonTimeUpdateRequestFailure.UNSUPPORTED, failure.reason); assertNull(failure.cause) }
        f.beforeOpen = { throw java.io.IOException("Synthetic storage failure") }
        try { f.verify(record); fail("Storage error swallowed") }
        catch (failure: ProtonTimeUpdateRequestException) { assertEquals(ProtonTimeUpdateRequestFailure.STORAGE, failure.reason); assertNull(failure.cause) }
        for (protection in listOf(false, true)) for (ordinaryError in listOf(false, true)) {
            val fresh = Fixture(); val saved = fresh.record()
            var cancelled = 0; var ordinary = 0; var returned = 0
            supervisorScope {
                val child = async {
                    val job = currentCoroutineContext().job
                    val stop: () -> Unit = { job.cancel(); if (ordinaryError) throw java.io.IOException("Synthetic callback failure") }
                    if (protection) fresh.beforeOpen = stop else fresh.onVerify = stop
                    try { fresh.verify(saved); returned++ }
                    catch (_: CancellationException) { cancelled++ }
                    catch (_: Exception) { ordinary++ }
                }
                try { child.await() } catch (_: CancellationException) { }
            }
            assertEquals(1, cancelled); assertEquals(0, ordinary); assertEquals(0, returned)
        }
    }

    @Test fun allDayActualDecryptedReadbackRetainsExclusiveCivilDates() = runTest {
        val f = Fixture(true); val record = f.record()
        val result = f.verify(record)
        assertEquals(f.decoded, result)
        assertEquals(EventTime.AllDay(LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-29")), result!!.content.time)
        assertEquals(1, f.verifications)
    }

    @Test fun allDayInclusiveApiEndIsRejectedBeforeDecryption() = runTest {
        val f = Fixture(true); val record = f.record()
        for (delta in listOf(1L, 86_400L)) {
            val end = Instant.parse("2026-10-29T00:00:00Z").epochSecond - delta
            assertNull(f.verify(record, row = JsonObject(f.row + ("EndTime" to JsonPrimitive(end)))))
        }
        assertEquals(0, f.verifications)
    }

    @Test fun allDayDecoderCannotCollapseExclusiveDateOrChangeKind() = runTest {
        for (replacement in listOf<EventTime>(EventTime.AllDay(LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-28")),
            EventTime.Timed(Instant.parse("2026-10-25T00:00:00Z"), Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC))) {
            val f = Fixture(true); val record = f.record()
            f.decoded = f.decoded!!.copy(content = f.decoded!!.content.copy(time = replacement))
            assertNull(f.verify(record)); assertEquals(1, f.verifications)
        }
    }

    private class Fixture(allDay: Boolean = false) {
        private val seed = TitleSubmissionFixture()
        val session = seed.initial
        val bootstrap = seed.bootstrap
        val context = JsonObject(seed.context + buildJsonObject {
            put("permissions", 16); put("flags", 1)
            for (name in listOf("calendarKey", "signingKey", "calendar")) put(name, ProtonSimpleEventUpdate.hash("synthetic-$name"))
            put("bootstrap", ProtonSimpleEventUpdate.hash(bootstrap.toString()))
        })
        private val before: EventTime = if (allDay) EventTime.AllDay(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-27"))
            else EventTime.Timed(Instant.parse("2026-10-04T12:00:00Z"), Instant.parse("2026-10-04T13:00:00Z"), ZoneOffset.UTC)
        private val after: EventTime = when (before) {
            is EventTime.Timed -> before.copy(start = before.start.plusSeconds(3600), end = before.end.plusSeconds(7200))
            is EventTime.AllDay -> EventTime.AllDay(LocalDate.parse("2026-10-25"), LocalDate.parse("2026-10-29"))
        }
        private fun metadata(time: EventTime): Map<String, JsonElement> {
            val bounds = when (time) {
                is EventTime.Timed -> time.start.epochSecond to time.end.epochSecond
                is EventTime.AllDay -> time.start.atStartOfDay(ZoneOffset.UTC).toEpochSecond() to time.endExclusive.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            }
            return mapOf("StartTime" to JsonPrimitive(bounds.first), "EndTime" to JsonPrimitive(bounds.second),
                "FullDay" to JsonPrimitive(if (time is EventTime.AllDay) 1 else 0),
                "StartTimezone" to JsonPrimitive("UTC"), "EndTimezone" to JsonPrimitive("UTC"))
        }
        private val original = JsonObject(seed.row + metadata(before))
        private val content = seed.content.copy(time = before, description = "Notes", location = "Place", details = EventDetails())
        private val intent = seed.intent.copy(base = seed.intent.base.copy(revision = original.revision(bootstrap), content = content),
            content = content.copy(time = after))
        private val fragments = ProtonTimestampedSharedTime(original, seed.fragments.clearSharedPart, seed.fragments.encryptedSharedPart, true)
        val row = JsonObject(original + metadata(after) + mapOf("SharedEvents" to JsonArray(listOf(fragments.clearSharedPart,
            fragments.encryptedSharedPart)), "ModifyTime" to JsonPrimitive(5)))
        val originalReadback = JsonObject(row + metadata(before))
        var decoded: RemoteChange.Upsert? = intent.base.copy(revision = row.revision(bootstrap), content = intent.content)
        var beforeOpen: () -> Unit = {}
        var onVerify: () -> Unit = {}
        var verifications = 0
        private val values = mutableMapOf<String, String>()
        private val protector = object : EventPayloadProtector {
            override fun seal(plain: String) = "synthetic-${values.size}".also { values[it] = plain }
            override fun open(protected: String): String { beforeOpen(); return values.getValue(protected) }
        }
        private val codec = ProtonTimeUpdateRequestCodec(protector)
        val reader = ProtonTimeUpdateReadbackVerifier(codec, CreateEventVerifier { actualSession, calendar, observedBootstrap, observedRow, uid, policy ->
            verifications++; assertEquals(session, actualSession); assertEquals(intent.scope.calendar.value, calendar)
            assertEquals(bootstrap, observedBootstrap); assertEquals(row, observedRow); assertEquals("synthetic-uid", uid)
            assertEquals(CreatedEventReadbackPolicy.KNOWN_EMPTY_V2, policy); onVerify(); decoded
        })
        suspend fun record(): ProtonProtectedTimeUpdateRequest {
            val preparation = ProtonTimePreparationCodec(protector).sealLocal(intent, "synthetic-uid", seed.timestamp, fragments)
            return codec.seal(MutationId("synthetic-time-request"), preparation, context)
        }
        suspend fun verify(record: ProtonProtectedTimeUpdateRequest, context: JsonObject = this.context,
            bootstrap: JsonObject = this.bootstrap, row: JsonObject = this.row) = reader.verify(session, record, context, bootstrap, row)
    }
}
