// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.data.serialization

import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Project-owned quarantine storage; no adapter payload interpretation or dispatch capability. */
object PersonalUpdateAttemptStorageCodec {
    const val MAX_CAPSULE_BYTES = 16 * 1024 * 1024
    private const val TAG = "CALENDAR_PERSONAL_UPDATE_ATTEMPT"
    private const val SCHEMA = 2
    private const val ID_BYTES = 4096
    private const val TEXT_BYTES = 65536
    private const val PAYLOAD_BYTES = 4 * 1024 * 1024

    fun encode(record: PersonalUpdateAttemptRecord): ByteArray = sanitized {
        val frozen = record.frozen()
        // First count all fields without allocating encoded strings or a growing aggregate buffer.
        val measured = Writer(null).also { write(it, frozen) }.size
        val bytes = ByteArray(measured)
        val writer = Writer(ByteBuffer.wrap(bytes))
        write(writer, frozen)
        require(writer.size == measured)
        bytes
    }

    fun decode(bytes: ByteArray): PersonalUpdateAttemptRecord = sanitized {
        require(bytes.size <= MAX_CAPSULE_BYTES)
        val reader = Reader(ByteBuffer.wrap(bytes))
        TAG.forEach { require(reader.tag() == it.code) }
        val schema = reader.integer().also { require(it == SCHEMA || it == 3) }
        val series = schema == 3
        val id = MutationId(reader.string(ID_BYTES))
        val scope = CalendarScope(AccountId(reader.string(ID_BYTES)), CalendarId(reader.string(ID_BYTES)), reader.long())
        val owner = MutationId(reader.string(ID_BYTES))
        val observationFormat = reader.format()
        require(reader.tag() == 1) // Retained local observation is explicitly PAUSED.
        val event = EventId(reader.string(ID_BYTES))
        val revision = Revision(reader.string(ID_BYTES))
        val base = RemoteChange.Upsert(event, revision, reader.content(series))
        val replacement = reader.content(series)
        val timestamp = reader.instant()
        val preparationFormat = reader.format()
        val preparationPayload = reader.string(PAYLOAD_BYTES)
        val requestFormat = reader.format()
        val requestPayload = reader.string(PAYLOAD_BYTES)
        val state = when (reader.tag()) {
            1 -> PersonalUpdateAttemptState.PREPARED
            2 -> PersonalUpdateAttemptState.OUTCOME_UNKNOWN
            else -> invalid()
        }
        val evidence = when (reader.tag()) {
            0 -> null
            1 -> reader.string(PAYLOAD_BYTES)
            else -> invalid()
        }
        require(reader.finished())
        val intent = LocalEditIntent(owner, scope, observationFormat, base, replacement, LocalEditState.PAUSED,
            if (series) SeriesEditProfile.of(base.content, replacement) else null)
        val preparation = if (series) PersonalEditPreparation.forSeries(intent, timestamp, preparationFormat, preparationPayload)
            else PersonalEditPreparation(intent, timestamp, preparationFormat, preparationPayload)
        PersonalUpdateAttemptRecord(id, preparation, requestFormat, requestPayload, state, evidence)
    }

    /** Stable schema-two order. All tags are literals, independent of Kotlin enum ordering. */
    private fun write(writer: Writer, record: PersonalUpdateAttemptRecord) = with(writer) {
        val preparation = record.preparation
        TAG.forEach { tag(it.code) }; integer(if (preparation.isSeries) 3 else SCHEMA)
        val intent = preparation.intent
        string(record.id.value, ID_BYTES)
        string(intent.scope.account.value, ID_BYTES); string(intent.scope.calendar.value, ID_BYTES)
        long(intent.scope.generation); string(intent.id.value, ID_BYTES); format(intent.format)
        require(intent.state == LocalEditState.PAUSED); tag(1)
        string(intent.base.eventId.value, ID_BYTES); string(intent.base.revision.value, ID_BYTES)
        content(intent.base.content, preparation.isSeries); content(intent.content, preparation.isSeries); instant(preparation.timestamp)
        format(preparation.format); string(preparation.protectedPayload, PAYLOAD_BYTES)
        format(record.format); string(record.protectedPayload, PAYLOAD_BYTES)
        tag(when (record.state) {
            PersonalUpdateAttemptState.PREPARED -> 1
            PersonalUpdateAttemptState.OUTCOME_UNKNOWN -> 2
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
        fun content(value: EventContent, series: Boolean) {
            string(value.title, TEXT_BYTES); string(value.description, TEXT_BYTES); string(value.location, TEXT_BYTES)
            when (val time = value.time) {
                is EventTime.Timed -> { tag(1); instant(time.start); instant(time.end); string(time.zone.id, ID_BYTES) }
                is EventTime.AllDay -> { tag(2); string(time.start.toString(), ID_BYTES); string(time.endExclusive.toString(), ID_BYTES) }
            }
            if (series) SeriesEditProfile.freezeMaster(value) else require(value.recurrence == null)
            val details = checkNotNull(value.details)
            require(details.organizer == null && details.attendees.isEmpty())
            val color = value.colorRgb
            tag(if (color == null) 0 else 1)
            if (color != null) { require(color in 0..0xffffff); integer(color) }
            tag(if (series) 1 else 0)
            if (series) { string(value.recurrence!!.uid, ID_BYTES); string(value.recurrence!!.rule!!, ID_BYTES) }
            tag(2) // Known personal display reminders. Schema two retains tag zero above.
            require(details.reminders.size <= 5)
            integer(details.reminders.size)
            details.reminders.forEach { integer(it.minutesBefore) }
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
        fun content(series: Boolean): EventContent {
            val title = string(TEXT_BYTES); val description = string(TEXT_BYTES); val location = string(TEXT_BYTES)
            val time = when (tag()) {
                1 -> EventTime.Timed(instant(), instant(), zone())
                2 -> EventTime.AllDay(date(), date())
                else -> invalid()
            }
            val color = when (tag()) { 0 -> null; 1 -> integer().also { require(it in 0..0xffffff) }; else -> invalid() }
            require(tag() == if (series) 1 else 0)
            val recurrence = if (series) EventRecurrence(string(ID_BYTES), string(ID_BYTES)) else null
            require(tag() == 2)
            val count = integer().also { require(it in 0..5) }
            val details = EventDetails(reminders = List(count) { EventReminder(integer()) })
            return EventContent(title, time, description, location, colorRgb = color, details = details, recurrence = recurrence)
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

    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid personal attempt capsule")
}
