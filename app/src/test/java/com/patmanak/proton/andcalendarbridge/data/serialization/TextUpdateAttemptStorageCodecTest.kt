// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.data.serialization

import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TextUpdateAttemptStorageCodecTest {
    @Test fun timedNanosZoneRawCrLfAndUnicodeSurviveFreshDecode() {
        val source = record()
        val bytes = TextUpdateAttemptStorageCodec.encode(source)
        val decoded = TextUpdateAttemptStorageCodec.decode(bytes)
        assertEquals(source, decoded); assertEquals(source.hashCode(), decoded.hashCode())
        assertNotSame(source, decoded); assertNotSame(source.preparation, decoded.preparation)
        assertEquals("Old\r\nnotes; café 😀", decoded.preparation.intent.base.content.description)
        assertEquals("New\r\nnotes; 東京 😀", decoded.preparation.intent.content.description)
        assertArrayEquals(bytes, TextUpdateAttemptStorageCodec.encode(decoded))
    }

    @Test fun allDayExclusiveEndAndUnknownAdapterFormatsRemainOpaque() {
        val source = record(allDay = true)
        val decoded = TextUpdateAttemptStorageCodec.decode(TextUpdateAttemptStorageCodec.encode(source))
        assertEquals(source, decoded)
        assertEquals(EventTime.AllDay(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-27")),
            decoded.preparation.intent.content.time)
        assertEquals(AdapterFormat("future-observation", 23), decoded.preparation.intent.format)
        assertEquals(AdapterFormat("future-preparation", 47), decoded.preparation.format)
        assertEquals(AdapterFormat("future-request", 61), decoded.format)
    }

    @Test fun preparedUnknownAndEvidenceStatesRetainEveryProtectedByte() {
        for (source in listOf(record(), record().copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN),
            record().copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "opaque\r\nevidence 😀")))
            assertEquals(source, TextUpdateAttemptStorageCodec.decode(TextUpdateAttemptStorageCodec.encode(source)))
    }

    @Test fun schemaOneBytesMatchIndependentLiteralFixture() {
        for (source in listOf(record(), record(allDay = true).copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN,
            protectedEvidence = "evidence"))) {
            val golden = golden(source)
            assertArrayEquals(golden.bytes, TextUpdateAttemptStorageCodec.encode(source))
            assertEquals(source, TextUpdateAttemptStorageCodec.decode(golden.bytes))
        }
    }

    @Test fun completeMutationIdentityCannotBeLostInRoundTrip() {
        val original = record(); val intent = original.preparation.intent
        val variants = listOf(original.copy(id = MutationId("another-request")),
            replaceIntent(original, intent.copy(id = MutationId("another-owner"))),
            replaceIntent(original, intent.copy(scope = intent.scope.copy(generation = 99))),
            replaceIntent(original, intent.copy(base = intent.base.copy(revision = Revision("another-base")))),
            replaceIntent(original, intent.copy(content = intent.content.copy(location = "another-location"))),
            original.copy(protectedPayload = "another-request-payload"),
            original.copy(preparation = LocalTextEditPreparation(intent, original.preparation.timestamp.plusSeconds(1),
                original.preparation.format, "another-preparation-payload")))
        val first = TextUpdateAttemptStorageCodec.encode(original)
        for (variant in variants) {
            val bytes = TextUpdateAttemptStorageCodec.encode(variant)
            assertFalse(first.contentEquals(bytes)); assertEquals(variant, TextUpdateAttemptStorageCodec.decode(bytes))
        }
    }

    @Test fun headerSchemaStateTimeKindAndMetadataTagsAreClosed() {
        val source = golden(record())
        refused(source.bytes.copyOf().also { it[0] = 0 })
        refused(source.bytes.copyOf().also { putInt(it, source.offsets.getValue("schema"), 2) })
        for (name in listOf("intentState", "base.timeKind", "next.timeKind", "base.color", "next.color",
            "base.recurrence", "next.recurrence", "base.details", "next.details", "state", "evidence"))
            refused(source.bytes.copyOf().also { it[source.offsets.getValue(name)] = 127 })
    }

    @Test fun truncatedAndTrailingCapsulesAreNeverAccepted() {
        val bytes = golden(record()).bytes
        for (length in 0 until bytes.size) refused(bytes.copyOf(length))
        refused(bytes + byteArrayOf(0)); refused(bytes + bytes)
    }

    @Test fun negativeOverflowAndRemainingBudgetLengthsRefuseBeforeAllocation() {
        val source = golden(record())
        for (size in listOf(-1, Int.MIN_VALUE, Int.MAX_VALUE, TextUpdateAttemptStorageCodec.MAX_CAPSULE_BYTES))
            refused(source.bytes.copyOf().also { putInt(it, source.offsets.getValue("id.length"), size) })
        refused(source.bytes.copyOf().also { putInt(it, source.offsets.getValue("payload.length"), 4_194_304) })
        refused(ByteArray(TextUpdateAttemptStorageCodec.MAX_CAPSULE_BYTES + 1))
    }

    @Test fun strictStandardUtf8RejectsOverlongAndIsolatedSurrogateBytes() {
        for (bytes in listOf(byteArrayOf(0xC0.toByte(), 0x80.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), byteArrayOf(0xFF.toByte())))
            refused(golden(record(), rawStrings = mapOf("id" to bytes)).bytes)
    }

    @Test fun idsTextAndProtectedFieldsUseIndependentUtf8ByteLimits() {
        refused(golden(record(), strings = mapOf("id" to "x".repeat(4097))).bytes)
        refused(golden(record(), strings = mapOf("id" to "é".repeat(2049))).bytes)
        refused(golden(record(), strings = mapOf("next.title" to "x".repeat(65537))).bytes)
        refused(golden(record(), strings = mapOf("next.description" to "€".repeat(21846))).bytes)
        refused(golden(record(), strings = mapOf("payload" to "x".repeat(4_194_305))).bytes)
        refused(golden(record(), strings = mapOf("preparationPayload" to "x".repeat(4_194_305))).bytes)
        refused(golden(record().copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "evidence"),
            strings = mapOf("evidenceText" to "x".repeat(4_194_305))).bytes)
    }

    @Test fun numericTimeRangesAndOrderingCannotBeCanonicalizedIntoValidity() {
        val source = golden(record())
        for (name in listOf("base.start.nano", "base.end.nano", "next.start.nano", "next.end.nano", "timestamp.nano")) {
            refused(source.bytes.copyOf().also { putInt(it, source.offsets.getValue(name), -1) })
            refused(source.bytes.copyOf().also { putInt(it, source.offsets.getValue(name), 1_000_000_000) })
        }
        refused(source.bytes.copyOf().also { putLong(it, source.offsets.getValue("base.start.second"), Long.MAX_VALUE) })
        refused(source.bytes.copyOf().also { putLong(it, source.offsets.getValue("base.end.second"), 0) })
        refused(source.bytes.copyOf().also { putInt(it, source.offsets.getValue("timestamp.nano"), 1) })
        refused(source.bytes.copyOf().also { putLong(it, source.offsets.getValue("timestamp.second"), -1) })
        refused(source.bytes.copyOf().also { putLong(it, source.offsets.getValue("generation"), -1) })
        refused(source.bytes.copyOf().also { putInt(it, source.offsets.getValue("requestFormat.version"), 0) })
    }

    @Test fun dateAndZoneStringsMustBeCanonicalAndCalendarRangeValid() {
        refused(golden(record(), strings = mapOf("base.zone" to "not-a-zone")).bytes)
        refused(golden(record(), strings = mapOf("base.zone" to "+00:00")).bytes)
        refused(golden(record(allDay = true), strings = mapOf("base.startDate" to "2026-1-01")).bytes)
        refused(golden(record(allDay = true), strings = mapOf("base.endDate" to "2026-10-23")).bytes)
        refused(golden(record(allDay = true), strings = mapOf("base.endDate" to "2026-02-30")).bytes)
    }

    @Test fun semanticRecordRulesAreRecheckedAfterDecoding() {
        val original = record()
        refused(golden(original, strings = mapOf("next.title" to original.preparation.intent.base.content.title,
            "next.description" to original.preparation.intent.base.content.description,
            "next.location" to original.preparation.intent.base.content.location)).bytes)
        val evidence = golden(original.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "evidence"))
        refused(evidence.bytes.copyOf().also { it[evidence.offsets.getValue("state")] = 1 })
        refused(golden(original, strings = mapOf("next.title" to "bad\u0000text")).bytes)
        refused(golden(original, strings = mapOf("payload" to "")).bytes)
    }

    @Test fun largeCapsuleOverCursorWindowSizeRoundTripsBelowAggregateBudget() {
        val original = record()
        val source = original.copy(preparation = LocalTextEditPreparation(original.preparation.intent,
            original.preparation.timestamp, original.preparation.format, "p".repeat(3 * 1024 * 1024)),
            protectedPayload = "r".repeat(3 * 1024 * 1024), state = TextUpdateAttemptState.OUTCOME_UNKNOWN,
            protectedEvidence = "e".repeat(3 * 1024 * 1024))
        val bytes = TextUpdateAttemptStorageCodec.encode(source)
        assertTrue(bytes.size > 2 * 1024 * 1024); assertTrue(bytes.size < TextUpdateAttemptStorageCodec.MAX_CAPSULE_BYTES)
        assertEquals(source, TextUpdateAttemptStorageCodec.decode(bytes))
    }

    private fun record(allDay: Boolean = false): TextUpdateAttemptRecord {
        val time = if (allDay) EventTime.AllDay(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-27"))
            else EventTime.Timed(Instant.parse("2026-10-24T12:00:00.123456789Z"),
                Instant.parse("2026-10-24T13:00:00.987654321Z"), ZoneId.of("Europe/Paris"))
        val base = EventContent("Old title", time, "Old\r\nnotes; café 😀", "Old place", details = EventDetails())
        val intent = LocalEditIntent(MutationId("synthetic-owner"), CalendarScope(AccountId("synthetic-account"),
            CalendarId("synthetic-calendar"), 7), AdapterFormat("future-observation", 23),
            RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-revision"), base),
            base.copy(title = "New title", description = "New\r\nnotes; 東京 😀", location = "New place"))
        val preparation = LocalTextEditPreparation(intent, Instant.parse("2026-10-13T12:00:00Z"),
            AdapterFormat("future-preparation", 47), "opaque-preparation\r\n😀")
        return TextUpdateAttemptRecord(MutationId("synthetic-request"), preparation,
            AdapterFormat("future-request", 61), "opaque-request\r\n😀")
    }

    private fun replaceIntent(source: TextUpdateAttemptRecord, intent: LocalEditIntent) = source.copy(preparation =
        LocalTextEditPreparation(intent, source.preparation.timestamp, source.preparation.format, source.preparation.protectedPayload))
    private fun refused(bytes: ByteArray) {
        try { TextUpdateAttemptStorageCodec.decode(bytes); fail("Invalid capsule accepted") }
        catch (failure: IllegalArgumentException) { assertEquals("Invalid text attempt capsule", failure.message); assertNull(failure.cause) }
    }
    private fun putInt(bytes: ByteArray, offset: Int, value: Int) { ByteBuffer.wrap(bytes).putInt(offset, value) }
    private fun putLong(bytes: ByteArray, offset: Int, value: Long) { ByteBuffer.wrap(bytes).putLong(offset, value) }
    private data class Golden(val bytes: ByteArray, val offsets: Map<String, Int>)

    /** Independent schema-one writer: literal tags never derive from production enum ordinals or codec output. */
    private fun golden(record: TextUpdateAttemptRecord, strings: Map<String, String> = emptyMap(),
        rawStrings: Map<String, ByteArray> = emptyMap()): Golden {
        val bytes = ByteArrayOutputStream(); val output = DataOutputStream(bytes); val offsets = linkedMapOf<String, Int>()
        fun mark(name: String) { offsets[name] = bytes.size() }
        fun tag(name: String, value: Int) { mark(name); output.writeByte(value) }
        fun integer(name: String, value: Int) { mark(name); output.writeInt(value) }
        fun long(name: String, value: Long) { mark(name); output.writeLong(value) }
        fun string(name: String, value: String) {
            val raw = rawStrings[name] ?: (strings[name] ?: value).toByteArray(Charsets.UTF_8)
            integer("$name.length", raw.size); mark(name); output.write(raw)
        }
        fun format(name: String, value: AdapterFormat) { string("$name.id", value.id); integer("$name.version", value.version) }
        fun instant(name: String, value: Instant) { long("$name.second", value.epochSecond); integer("$name.nano", value.nano) }
        fun content(name: String, value: EventContent) {
            string("$name.title", value.title); string("$name.description", value.description); string("$name.location", value.location)
            when (val time = value.time) {
                is EventTime.Timed -> { tag("$name.timeKind", 1); instant("$name.start", time.start); instant("$name.end", time.end); string("$name.zone", time.zone.id) }
                is EventTime.AllDay -> { tag("$name.timeKind", 2); string("$name.startDate", time.start.toString()); string("$name.endDate", time.endExclusive.toString()) }
            }
            tag("$name.color", 0); tag("$name.recurrence", 0); tag("$name.details", 1)
        }
        output.write("CALENDAR_TEXT_UPDATE_ATTEMPT".toByteArray(Charsets.US_ASCII)); integer("schema", 1)
        val intent = record.preparation.intent
        string("id", record.id.value); string("account", intent.scope.account.value); string("calendar", intent.scope.calendar.value)
        long("generation", intent.scope.generation); string("owner", intent.id.value); format("observationFormat", intent.format)
        tag("intentState", 1); string("event", intent.base.eventId.value); string("revision", intent.base.revision.value)
        content("base", intent.base.content); content("next", intent.content); instant("timestamp", record.preparation.timestamp)
        format("preparationFormat", record.preparation.format); string("preparationPayload", record.preparation.protectedPayload)
        format("requestFormat", record.format); string("payload", record.protectedPayload)
        tag("state", if (record.state == TextUpdateAttemptState.PREPARED) 1 else 2)
        val evidence = record.protectedEvidence; tag("evidence", if (evidence == null) 0 else 1)
        if (evidence != null) string("evidenceText", evidence)
        return Golden(bytes.toByteArray(), offsets)
    }
}
