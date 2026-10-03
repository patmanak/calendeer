// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** One durable admission for an unchanged original row. Reopening only permits inspection.
 * Its marker establishes a historical batch, never current clean content or import release.
 */
data class CapturedCreateAcknowledgementReceipt(
    val baseline: CapturedCreateBaseline,
    val nonce: String,
    val protocol: Int = 1,
) {
    init {
        require(protocol == 1)
        require(nonce.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
        require(nonce != baseline.receipt.nonce)
        fingerprint() // Refuse malformed identity text before persistence or Provider IO.
    }

    /** Canonical identity digest; all source provenance and both nonces are included. */
    fun fingerprint(): String {
        val attachment = baseline.receipt
        val binding = attachment.binding
        val identity = binding.identity
        val attempt = identity.attempt
        val scope = attempt.scope
        val fields = listOf("captured-ack-v1", scope.account.value, scope.calendar.value,
            scope.generation.toString(), attempt.id.value, attempt.format.id, attempt.format.version.toString(),
            attempt.payloadFingerprint, identity.localId.value, identity.captureId.value,
            identity.captureFormat.id, identity.captureFormat.version.toString(), identity.timestamp.toString(),
            identity.contentFingerprint, identity.fingerprintVersion.toString(), binding.eventId.value,
            binding.verifiedRevision.value, attachment.protocol.toString(), attachment.nonce,
            protocol.toString(), nonce)
        val hash = MessageDigest.getInstance("SHA-256")
        try {
            for (field in fields) {
                val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(field))
                hash.update(ByteBuffer.allocate(4).putInt(bytes.remaining()).array())
                hash.update(bytes)
            }
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("Invalid captured acknowledgement identity")
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    override fun toString() = "CapturedCreateAcknowledgementReceipt(REDACTED)"
}

/** Exact historical Provider batch proof. It is not a clean-row snapshot. */
data class AppliedCapturedCreateAcknowledgement(val receipt: CapturedCreateAcknowledgementReceipt) {
    override fun toString() = "AppliedCapturedCreateAcknowledgement(REDACTED)"
}

enum class CapturedCreateAcknowledgementState { UNKNOWN, PROVEN }

data class CapturedCreateAcknowledgementRecord(
    val receipt: CapturedCreateAcknowledgementReceipt,
    val state: CapturedCreateAcknowledgementState,
) {
    override fun toString() = "CapturedCreateAcknowledgementRecord(REDACTED)"
}

/** Only the invocation that created UNKNOWN may attempt its single Provider batch. */
data class CapturedCreateAcknowledgementReservation(
    val record: CapturedCreateAcknowledgementRecord,
    val fresh: Boolean,
) {
    override fun toString() = "CapturedCreateAcknowledgementReservation(REDACTED)"
}
