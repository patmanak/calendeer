// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

/** Frozen personal preparation; the historical constructor admits only simple v2 edits. */
class PersonalEditPreparation private constructor(intent: LocalEditIntent, val timestamp: Instant,
    val format: AdapterFormat, val protectedPayload: String, val isSeries: Boolean) {
    constructor(intent: LocalEditIntent, timestamp: Instant, format: AdapterFormat, protectedPayload: String) :
        this(intent, timestamp, format, protectedPayload, false)
    val intent: LocalEditIntent = if (isSeries) SeriesEditCandidate(intent).intent else PersonalEditCandidate(intent).intent
    val changedFields: Set<PersonalEditField> get() = if (isSeries) java.util.Collections.unmodifiableSet(
        SeriesEditCandidate(intent).changedFields.mapTo(linkedSetOf()) { PersonalEditField.valueOf(it.name) })
        else PersonalEditCandidate(intent).changedFields
    val requiredConsent get() = changedFields
    init {
        require(timestamp.nano == 0 && timestamp >= Instant.EPOCH && timestamp <= LAST_TIMESTAMP)
        bounded(format.id, MAX_CONTEXT_BYTES)
        require(protectedPayload.isNotBlank())
        bounded(protectedPayload, MAX_PAYLOAD_BYTES)
    }
    override fun toString() = "PersonalEditPreparation(REDACTED)"
    fun frozen() = PersonalEditPreparation(intent, timestamp, format, protectedPayload, isSeries)

    companion object {
        fun forSeries(intent: LocalEditIntent, timestamp: Instant, format: AdapterFormat,
            protectedPayload: String) = PersonalEditPreparation(intent, timestamp, format, protectedPayload, true)
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
