// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.serialization

import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Local, closed proof framing. Adapter payloads stay opaque; this codec verifies no signatures. */
internal object TimeUpdateProofStorageCodec {
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val TEXT_BYTES = 4 * 1024 * 1024
    private const val RESULT_TAG = "CALENDAR_TIME_UPDATE_RESULT"
    private const val COMPLETION_TAG = "CALENDAR_TIME_UPDATE_COMPLETION"

    fun encodeResult(value: TimeUpdateVerifiedResult): ByteArray = sanitized {
        val result = value.snapshot()
        frame(RESULT_TAG, TimeUpdateAttemptStorageCodec.encode(result.record),
            utf8(TimeUpdateAcknowledgementCodec.encodeChange(result.event)))
    }
    fun decodeResult(bytes: ByteArray): TimeUpdateVerifiedResult = sanitized {
        val parts = unframe(RESULT_TAG, bytes)
        val record = TimeUpdateAttemptStorageCodec.decode(parts.first)
        require(record.protectedEvidence == null)
        val payload = text(parts.second)
        val event = TimeUpdateAcknowledgementCodec.decodeChange(payload) as RemoteChange.Upsert
        require(TimeUpdateAcknowledgementCodec.encodeChange(event) == payload)
        TimeUpdateVerifiedResult.of(record, TimeUpdateSubmissionObservation.Verified(record, event))
    }
    fun encodeCompletion(value: TimeUpdateCompletion): ByteArray = sanitized {
        val completion = value.snapshot()
        frame(COMPLETION_TAG, encodeResult(completion.result), utf8(TimeUpdateAcknowledgementCodec.encode(completion.receipt)))
    }
    fun decodeCompletion(bytes: ByteArray): TimeUpdateCompletion = sanitized {
        val parts = unframe(COMPLETION_TAG, bytes)
        val result = decodeResult(parts.first)
        val receipt = TimeUpdateAcknowledgementCodec.decode(text(parts.second))
        TimeUpdateCompletion.of(result, TimeUpdateAcknowledgementRecord(receipt, TimeUpdateAcknowledgementState.RESTORED))
    }
    private fun frame(tag: String, first: ByteArray, second: ByteArray): ByteArray {
        require(first.isNotEmpty() && second.isNotEmpty() && second.size <= TEXT_BYTES)
        val header = tag.length + 12
        require(first.size <= MAX_BYTES - header && second.size <= MAX_BYTES - header - first.size)
        return ByteBuffer.allocate(header + first.size + second.size).apply {
            tag.forEach { put(it.code.toByte()) }; putInt(1)
            putInt(first.size); put(first); putInt(second.size); put(second)
        }.array()
    }
    private fun unframe(tag: String, bytes: ByteArray): Pair<ByteArray, ByteArray> {
        require(bytes.size <= MAX_BYTES)
        val input = ByteBuffer.wrap(bytes)
        tag.forEach { require(input.get().toInt() == it.code) }
        require(input.int == 1)
        fun field(max: Int): ByteArray {
            val count = input.int
            require(count in 1..max && count <= input.remaining())
            return ByteArray(count).also { input.get(it) }
        }
        val first = field(TimeUpdateAttemptStorageCodec.MAX_CAPSULE_BYTES)
        val second = field(TEXT_BYTES)
        require(!input.hasRemaining())
        return first to second
    }
    private fun utf8(value: String): ByteArray {
        require(value.isNotEmpty() && value.length <= TEXT_BYTES)
        val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        require(buffer.remaining() <= TEXT_BYTES)
        return ByteArray(buffer.remaining()).also { buffer.get(it) }
    }
    private fun text(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private inline fun <T> sanitized(action: () -> T): T = try { action() }
        catch (_: Exception) { throw IllegalArgumentException("Invalid text proof capsule") }
}
