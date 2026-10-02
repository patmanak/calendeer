// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant

/** Immutable local-to-dispatch link. A private equality fingerprint, not encryption or admission. */
data class CapturedCreateDispatchIdentity(
    val attempt: CreateAttemptIdentity,
    val localId: LocalEventId,
    val captureId: MutationId,
    val captureFormat: AdapterFormat,
    val timestamp: Instant,
    val contentFingerprint: String,
    val fingerprintVersion: Int = 1,
) {
    init {
        require(timestamp.nano == 0 && fingerprintVersion == 1 && contentFingerprint.matches(Regex("[a-f0-9]{64}")))
        require(attempt.id != captureId && attempt.id.value != localId.value)
    }
    override fun toString() = "CapturedCreateDispatchIdentity(REDACTED)"

    /** Equality qualification only; it does not authenticate a remote observation. */
    fun matchesContent(content: EventContent): Boolean = try {
        val original = LocalCreateIntent(LocalCreateReservation(captureId, localId, attempt.scope, captureFormat, timestamp), content)
        contentFingerprint == Companion.contentFingerprint(original)
    } catch (_: IllegalArgumentException) { false }

    companion object {
        fun of(submission: LocalCreateSubmission): CapturedCreateDispatchIdentity {
            val reserved = submission.reservation
            val captured = reserved.intent.reservation
            val record = CreateAttemptRecord(reserved.attempt, captured.scope, reserved.format, submission.protectedPayload)
            return CapturedCreateDispatchIdentity(CreateAttemptIdentity.of(record), captured.localId, captured.id,
                captured.format, captured.timestamp, contentFingerprint(reserved.intent))
        }

        private fun contentFingerprint(intent: LocalCreateIntent): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun field(value: String) {
                val bytes = try {
                    val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
                    ByteArray(encoded.remaining()).also { encoded.get(it) }
                } catch (_: CharacterCodingException) { throw IllegalArgumentException("Invalid captured text") }
                digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
            }
            field("calendeer-captured-create-content-v1")
            val content = intent.content
            field(content.title); field(content.description); field(content.location)
            when (val time = content.time) {
                is EventTime.AllDay -> { field("ALL_DAY"); field(time.start.toString()); field(time.endExclusive.toString()) }
                is EventTime.Timed -> { field("TIMED"); field(time.start.toString()); field(time.end.toString()); field(time.zone.id) }
            }
            // LocalCreateIntent already refuses unknown/nonempty personal or unsupported shared fields.
            field("KNOWN_EMPTY_DETAILS"); field("NO_RECURRENCE"); field("NO_COLOR")
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
