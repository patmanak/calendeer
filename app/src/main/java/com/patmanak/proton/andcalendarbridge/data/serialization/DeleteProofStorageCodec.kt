// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.serialization

import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Local, closed proof framing. Adapter payloads stay opaque; this codec verifies no signatures. */
internal object DeleteProofStorageCodec {
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val TEXT_BYTES = 4 * 1024 * 1024
    private const val RESULT_TAG = "CALENDAR_DELETE_RESULT"
    private const val COMPLETION_TAG = "CALENDAR_DELETE_COMPLETION"

    fun encodeResult(value: DeleteVerifiedResult): ByteArray = sanitized {
        val result = value.snapshot()
        frame(RESULT_TAG, DeleteAttemptStorageCodec.encode(result.record),
            utf8("AUTHENTICATED_ABSENCE_V1"))
    }
    fun decodeResult(bytes: ByteArray): DeleteVerifiedResult = sanitized {
        val parts = unframe(RESULT_TAG, bytes)
        val record = DeleteAttemptStorageCodec.decode(parts.first)
        require(record.protectedEvidence == null)
        val payload = text(parts.second)
        require(payload == "AUTHENTICATED_ABSENCE_V1")
        DeleteVerifiedResult.of(record, DeleteSubmissionObservation.Verified(record))
    }
    fun encodeCompletion(value: DeleteCompletion): ByteArray = sanitized {
        val completion = value.snapshot()
        frame(COMPLETION_TAG, encodeResult(completion.result), utf8(DeleteAcknowledgementCodec.encode(completion.receipt)))
    }
    fun decodeCompletion(bytes: ByteArray): DeleteCompletion = sanitized {
        val parts = unframe(COMPLETION_TAG, bytes)
        val result = decodeResult(parts.first)
        val receipt = DeleteAcknowledgementCodec.decode(text(parts.second))
        DeleteCompletion.of(result, DeleteAcknowledgementRecord(receipt, DeleteAcknowledgementState.RESTORED))
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
        val first = field(if (tag == RESULT_TAG) DeleteAttemptStorageCodec.MAX_CAPSULE_BYTES else MAX_BYTES)
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
        catch (_: Exception) { throw IllegalArgumentException("Invalid delete proof capsule") }
}
