// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Exact durable exclusion identity. Fingerprints are opaque caller-owned commitments, not content. */
data class ConflictResolutionFence(
    val scope: CalendarScope,
    val eventId: EventId,
    val localMutationId: MutationId,
    val resolutionId: MutationId,
    val fingerprint: String,
) {
    init {
        listOf(scope.account.value, scope.calendar.value, eventId.value, localMutationId.value,
            resolutionId.value, fingerprint).forEach(::requireConflictResolutionToken)
        require(fingerprint.matches(Regex("[0-9a-f]{64}")))
    }
    override fun toString() = "ConflictResolutionFence(REDACTED)"
}

/** Caller must establish finalization before supplying its immutable proof commitment.
 * This journal cannot verify Provider or import-store effects and grants no such capability.
 */
data class ConflictResolutionFinalization(val fence: ConflictResolutionFence, val proofFingerprint: String) {
    init { require(proofFingerprint.matches(Regex("[0-9a-f]{64}"))) }
    override fun toString() = "ConflictResolutionFinalization(REDACTED)"
}

private fun requireConflictResolutionToken(value: String) {
    require(value.isNotBlank() && value.length <= 4096)
    require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <= 4096)
}
