// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.data.serialization

import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Project-owned quarantine storage; no adapter payload interpretation or dispatch capability. */
object TimeUpdateAttemptStorageCodec {
    const val MAX_CAPSULE_BYTES = 16 * 1024 * 1024
    private const val TAG = "CALENDAR_TIME_UPDATE_ATTEMPT"
    private const val SCHEMA = 1
    private const val ID_BYTES = 4096
    private const val TEXT_BYTES = 65536
    private const val PAYLOAD_BYTES = 4 * 1024 * 1024

    fun encode(record: TimeUpdateAttemptRecord): ByteArray = sanitized {
        val frozen = record.frozen()
        // First count all fields without allocating encoded strings or a growing aggregate buffer.
        val measured = Writer(null).also { write(it, frozen) }.size
        val bytes = ByteArray(measured)
        val writer = Writer(ByteBuffer.wrap(bytes))
        write(writer, frozen)
        require(writer.size == measured)
        bytes
    }

    fun decode(bytes: ByteArray): TimeUpdateAttemptRecord = sanitized {
        require(bytes.size <= MAX_CAPSULE_BYTES)
        val reader = Reader(ByteBuffer.wrap(bytes))
        TAG.forEach { require(reader.tag() == it.code) }
        require(reader.integer() == SCHEMA)
        val id = MutationId(reader.string(ID_BYTES))
        val scope = CalendarScope(AccountId(reader.string(ID_BYTES)), CalendarId(reader.string(ID_BYTES)), reader.long())
        val owner = MutationId(reader.string(ID_BYTES))
        val observationFormat = reader.format()
        require(reader.tag() == 1) // Retained local observation is explicitly PAUSED.
        val event = EventId(reader.string(ID_BYTES))
        val revision = Revision(reader.string(ID_BYTES))
        val base = RemoteChange.Upsert(event, revision, reader.content())
        val replacement = reader.content()
        val timestamp = reader.instant()
        val preparationFormat = reader.format()
        val preparationPayload = reader.string(PAYLOAD_BYTES)
        val requestFormat = reader.format()
        val requestPayload = reader.string(PAYLOAD_BYTES)
        val state = when (reader.tag()) {
            1 -> TimeUpdateAttemptState.PREPARED
            2 -> TimeUpdateAttemptState.OUTCOME_UNKNOWN
            else -> invalid()
        }
        val evidence = when (reader.tag()) {
            0 -> null
            1 -> reader.string(PAYLOAD_BYTES)
            else -> invalid()
        }
        require(reader.finished())
        val intent = LocalEditIntent(owner, scope, observationFormat, base, replacement, LocalEditState.PAUSED)
        val preparation = LocalTimeEditPreparation(intent, timestamp, preparationFormat, preparationPayload)
        TimeUpdateAttemptRecord(id, preparation, requestFormat, requestPayload, state, evidence)
    }

    /** Stable schema-one order. All tags are literals, independent of Kotlin enum ordering. */
    private fun write(writer: Writer, record: TimeUpdateAttemptRecord) = with(writer) {
        TAG.forEach { tag(it.code) }; integer(SCHEMA)
        val preparation = record.preparation
        val intent = preparation.intent
        string(record.id.value, ID_BYTES)
        string(intent.scope.account.value, ID_BYTES); string(intent.scope.calendar.value, ID_BYTES)
        long(intent.scope.generation); string(intent.id.value, ID_BYTES); format(intent.format)
        require(intent.state == LocalEditState.PAUSED); tag(1)
        string(intent.base.eventId.value, ID_BYTES); string(intent.base.revision.value, ID_BYTES)
        content(intent.base.content); content(intent.content); instant(preparation.timestamp)
        format(preparation.format); string(preparation.protectedPayload, PAYLOAD_BYTES)
        format(record.format); string(record.protectedPayload, PAYLOAD_BYTES)
        tag(when (record.state) {
            TimeUpdateAttemptState.PREPARED -> 1
            TimeUpdateAttemptState.OUTCOME_UNKNOWN -> 2
        })
        val evidence = record.protectedEvidence
        tag(if (evidence == null) 0 else 1)
        if (evidence != null) string(evidence, PAYLOAD_BYTES)
    }

    private class Writer(private val output: ByteBuffer?) {
        var size = 0
            private set
        private fun reserve(count: Int) {
            require(count >= 0 && count <= MAX_CAPSULE_BYTES - size)
            size += count
        }
        fun tag(value: Int) { reserve(1); output?.put(value.toByte()) }
        fun integer(value: Int) { reserve(4); output?.putInt(value) }
        fun long(value: Long) { reserve(8); output?.putLong(value) }
        fun string(value: String, limit: Int) {
            // Compute strict standard UTF-8 size before allocation, including the aggregate budget.
            val count = utf8Size(value, limit)
            require(4 <= MAX_CAPSULE_BYTES - size && count <= MAX_CAPSULE_BYTES - size - 4)
            integer(count); reserve(count)
            if (output != null) output.put(value.toByteArray(Charsets.UTF_8))
        }
        fun format(value: AdapterFormat) { string(value.id, ID_BYTES); integer(value.version) }
        fun instant(value: Instant) { long(value.epochSecond); integer(value.nano) }
        fun content(value: EventContent) {
            string(value.title, TEXT_BYTES); string(value.description, TEXT_BYTES); string(value.location, TEXT_BYTES)
            when (val time = value.time) {
                is EventTime.Timed -> { tag(1); instant(time.start); instant(time.end); string(time.zone.id, ID_BYTES) }
                is EventTime.AllDay -> { tag(2); string(time.start.toString(), ID_BYTES); string(time.endExclusive.toString(), ID_BYTES) }
            }
            require(value.colorRgb == null && value.recurrence == null && value.details == EventDetails())
            tag(0); tag(0); tag(1) // Absent color, absent recurrence, explicitly known-empty details.
        }
    }

    private class Reader(private val input: ByteBuffer) {
        fun tag(): Int { require(input.remaining() >= 1); return input.get().toInt() and 0xff }
        fun integer(): Int { require(input.remaining() >= 4); return input.int }
        fun long(): Long { require(input.remaining() >= 8); return input.long }
        fun string(limit: Int): String {
            val count = integer()
            require(count >= 0 && count <= limit && count <= input.remaining())
            // A bounded view avoids allocating a byte array from an untrusted declared length.
            val view = input.slice().apply { limit(count) }
            val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(view).toString()
            input.position(input.position() + count)
            return value
        }
        fun format() = AdapterFormat(string(ID_BYTES), integer())
        fun instant(): Instant {
            val seconds = long(); val nanos = integer()
            // Instant.ofEpochSecond would otherwise normalize invalid nanosecond adjustments.
            require(nanos in 0..999_999_999)
            return Instant.ofEpochSecond(seconds, nanos.toLong())
        }
        private fun date(): LocalDate {
            val text = string(ID_BYTES)
            return LocalDate.parse(text).also { require(it.toString() == text) }
        }
        private fun zone(): ZoneId {
            val text = string(ID_BYTES)
            return ZoneId.of(text).also { require(it.id == text) }
        }
        fun content(): EventContent {
            val title = string(TEXT_BYTES); val description = string(TEXT_BYTES); val location = string(TEXT_BYTES)
            val time = when (tag()) {
                1 -> EventTime.Timed(instant(), instant(), zone())
                2 -> EventTime.AllDay(date(), date())
                else -> invalid()
            }
            require(tag() == 0 && tag() == 0 && tag() == 1)
            return EventContent(title, time, description, location, details = EventDetails())
        }
        fun finished() = !input.hasRemaining()
    }

    private fun utf8Size(value: String, limit: Int): Int {
        require(value.length <= limit)
        var index = 0; var bytes = 0
        while (index < value.length) {
            val char = value[index++]
            val count = when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                char.isHighSurrogate() -> {
                    require(index < value.length && value[index].isLowSurrogate()); index++; 4
                }
                char.isLowSurrogate() -> invalid()
                else -> 3
            }
            require(count <= limit - bytes)
            bytes += count
        }
        return bytes
    }

    private inline fun <T> sanitized(block: () -> T): T = try { block() }
        catch (_: Exception) { invalid() }

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid time attempt capsule")
}
