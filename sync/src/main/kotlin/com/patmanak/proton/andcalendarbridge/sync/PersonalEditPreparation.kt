// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

/** Frozen protected offline personal edit v2; no journal ownership, send permit or acknowledgement. */
class PersonalEditPreparation(intent: LocalEditIntent, val timestamp: Instant,
    val format: AdapterFormat, val protectedPayload: String) {
    val intent: LocalEditIntent = PersonalEditCandidate(intent).intent
    val changedFields get() = PersonalEditCandidate(intent).changedFields
    val requiredConsent get() = changedFields
    init {
        require(timestamp.nano == 0 && timestamp >= Instant.EPOCH && timestamp <= LAST_TIMESTAMP)
        bounded(format.id, MAX_CONTEXT_BYTES)
        require(protectedPayload.isNotBlank())
        bounded(protectedPayload, MAX_PAYLOAD_BYTES)
    }
    override fun toString() = "PersonalEditPreparation(REDACTED)"

    companion object {
        const val MAX_CONTEXT_BYTES = 4_096
        const val MAX_TEXT_BYTES = 65_536
        const val MAX_PAYLOAD_BYTES = 4_194_304
        private val LAST_TIMESTAMP = Instant.parse("9999-12-31T23:59:59Z")
        private fun bounded(value: String, limit: Int) {
            require(value.length <= limit)
            require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <= limit)
        }
    }
}
