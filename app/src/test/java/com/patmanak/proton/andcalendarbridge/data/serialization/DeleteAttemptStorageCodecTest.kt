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

class DeleteAttemptStorageCodecTest {
    @Test fun preparedUnknownAndEvidenceStatesRetainEveryProtectedByte() {
        for (source in listOf(record(), record().copy(state = DeleteAttemptState.OUTCOME_UNKNOWN),
            record().copy(state = DeleteAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "opaque\r\nevidence 😀")))
            assertEquals(source, DeleteAttemptStorageCodec.decode(DeleteAttemptStorageCodec.encode(source)))
    }

    @Test fun schemaOneBytesMatchIndependentLiteralFixture() {
        for (source in listOf(record(), record(allDay = true).copy(state = DeleteAttemptState.OUTCOME_UNKNOWN,
            protectedEvidence = "evidence"))) {
            val golden = golden(source)
            assertArrayEquals(golden.bytes, DeleteAttemptStorageCodec.encode(source))
            assertEquals(source, DeleteAttemptStorageCodec.decode(golden.bytes))
        }
    }

    @Test fun headerSchemaStateTimeKindAndMetadataTagsAreClosed() {
        val source = golden(record())
        refused(source.bytes.copyOf().also { it[0] = 0 })
        refused(source.bytes.copyOf().also { putInt(it, source.offsets.getValue("schema"), 2) })
        for (name in listOf("intentState", "base.timeKind", "base.color",
            "base.recurrence", "base.details", "state", "evidence"))
            refused(source.bytes.copyOf().also { it[source.offsets.getValue(name)] = 127 })
    }

    @Test fun truncatedAndTrailingCapsulesAreNeverAccepted() {
        val bytes = golden(record()).bytes
        for (length in 0 until bytes.size) refused(bytes.copyOf(length))
        refused(bytes + byteArrayOf(0)); refused(bytes + bytes)
    }

    @Test fun negativeOverflowAndRemainingBudgetLengthsRefuseBeforeAllocation() {
        val source = golden(record())
        for (size in listOf(-1, Int.MIN_VALUE, Int.MAX_VALUE, DeleteAttemptStorageCodec.MAX_CAPSULE_BYTES))
            refused(source.bytes.copyOf().also { putInt(it, source.offsets.getValue("id.length"), size) })
        refused(source.bytes.copyOf().also { putInt(it, source.offsets.getValue("payload.length"), 4_194_304) })
        refused(ByteArray(DeleteAttemptStorageCodec.MAX_CAPSULE_BYTES + 1))
    }

    @Test fun strictStandardUtf8RejectsOverlongAndIsolatedSurrogateBytes() {
        for (bytes in listOf(byteArrayOf(0xC0.toByte(), 0x80.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()), byteArrayOf(0xFF.toByte())))
            refused(golden(record(), rawStrings = mapOf("id" to bytes)).bytes)
    }

    @Test fun dateAndZoneStringsMustBeCanonicalAndCalendarRangeValid() {
        refused(golden(record(), strings = mapOf("base.zone" to "not-a-zone")).bytes)
        refused(golden(record(), strings = mapOf("base.zone" to "+00:00")).bytes)
        refused(golden(record(allDay = true), strings = mapOf("base.startDate" to "2026-1-01")).bytes)
        refused(golden(record(allDay = true), strings = mapOf("base.endDate" to "2026-10-23")).bytes)
        refused(golden(record(allDay = true), strings = mapOf("base.endDate" to "2026-02-30")).bytes)
    }

    @Test fun exactFrozenIntentSurvivesTimedAndAllDayReopen() {
        for (source in listOf(record(), record(true))) {
            val encoded = DeleteAttemptStorageCodec.encode(source)
            val decoded = DeleteAttemptStorageCodec.decode(encoded)
            assertEquals(source, decoded); assertEquals(source.hashCode(), decoded.hashCode())
            assertNotSame(source, decoded); assertNotSame(source.intent, decoded.intent)
            assertEquals("Old\r\nnotes; café 😀", decoded.intent.base.content.description)
            assertArrayEquals(encoded, DeleteAttemptStorageCodec.encode(decoded))
        }
    }
    @Test fun numericTimeAndScopeRangesNeverNormalizeInvalidData() {
        val fixture = golden(record())
        for (name in listOf("base.start.nano", "base.end.nano"))
            for (value in listOf(-1, 1, 1_000_000_000))
                refused(fixture.bytes.copyOf().also { putInt(it, fixture.offsets.getValue(name), value) })
        refused(fixture.bytes.copyOf().also { putLong(it, fixture.offsets.getValue("base.end.second"), 0) })
        refused(fixture.bytes.copyOf().also { putLong(it, fixture.offsets.getValue("generation"), -1) })
        refused(fixture.bytes.copyOf().also { putInt(it, fixture.offsets.getValue("requestFormat.version"), 0) })
    }
    @Test fun stringByteBudgetsAndBlankIdentitiesFailClosed() {
        for ((name, value) in mapOf("id" to "é".repeat(2049), "base.title" to "x".repeat(65537),
            "base.description" to "€".repeat(21846), "payload" to "x".repeat(4_194_305)))
            refused(golden(record(), strings = mapOf(name to value)).bytes)
        refused(golden(record(), strings = mapOf("id" to " ")).bytes)
        refused(golden(record(), strings = mapOf("payload" to " ")).bytes)
    }
    private fun record(allDay: Boolean = false): DeleteAttemptRecord {
        val time = if (allDay) EventTime.AllDay(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-27"))
            else EventTime.Timed(Instant.parse("2026-10-24T12:00:00Z"),
                Instant.parse("2026-10-24T13:00:00Z"), ZoneId.of("Europe/Paris"))
        val base = EventContent("Old title", time, "Old\r\nnotes; café 😀", "Old place", details = EventDetails())
        val intent = LocalDeleteIntent(MutationId("synthetic-owner"), CalendarScope(AccountId("synthetic-account"),
            CalendarId("synthetic-calendar"), 7), AdapterFormat("future-observation", 23),
            RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-revision"), base),
            base)
        return DeleteAttemptRecord(MutationId("synthetic-request"), intent,
            AdapterFormat("future-request", 61), "opaque-request\r\n😀")
    }

    private fun refused(bytes: ByteArray) {
        try { DeleteAttemptStorageCodec.decode(bytes); fail("Invalid capsule accepted") }
        catch (failure: IllegalArgumentException) { assertEquals("Invalid delete attempt capsule", failure.message); assertNull(failure.cause) }
    }
    private fun putInt(bytes: ByteArray, offset: Int, value: Int) { ByteBuffer.wrap(bytes).putInt(offset, value) }
    private fun putLong(bytes: ByteArray, offset: Int, value: Long) { ByteBuffer.wrap(bytes).putLong(offset, value) }
    private data class Golden(val bytes: ByteArray, val offsets: Map<String, Int>)

    /** Independent schema-one writer: literal tags never derive from production enum ordinals or codec output. */
    private fun golden(record: DeleteAttemptRecord, strings: Map<String, String> = emptyMap(),
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
        output.write("CALENDAR_DELETE_ATTEMPT".toByteArray(Charsets.US_ASCII)); integer("schema", 1)
        val intent = record.intent
        string("id", record.id.value); string("account", intent.scope.account.value); string("calendar", intent.scope.calendar.value)
        long("generation", intent.scope.generation); string("owner", intent.id.value); format("observationFormat", intent.format)
        tag("intentState", 1); string("event", intent.base.eventId.value); string("revision", intent.base.revision.value)
        content("base", intent.base.content)
        format("requestFormat", record.format); string("payload", record.protectedPayload)
        tag("state", if (record.state == DeleteAttemptState.PREPARED) 1 else 2)
        val evidence = record.protectedEvidence; tag("evidence", if (evidence == null) 0 else 1)
        if (evidence != null) string("evidenceText", evidence)
        return Golden(bytes.toByteArray(), offsets)
    }
}
