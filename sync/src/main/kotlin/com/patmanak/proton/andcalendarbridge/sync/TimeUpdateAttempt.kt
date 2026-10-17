// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

enum class TimeUpdateAttemptState { PREPARED, OUTCOME_UNKNOWN }

/** Isolated time-edit history only, never dispatch authority.
 * Transactional arbitration with title/text ownership is required before production composition.
 */
class TimeUpdateAttemptRecord(val id: MutationId, preparation: LocalTimeEditPreparation,
    val format: AdapterFormat, val protectedPayload: String,
    val state: TimeUpdateAttemptState = TimeUpdateAttemptState.PREPARED,
    val protectedEvidence: String? = null) {
    val preparation = LocalTimeEditPreparation(preparation.intent, preparation.timestamp,
        preparation.format, preparation.protectedPayload)
    val scope: CalendarScope get() = preparation.intent.scope
    val eventId: EventId get() = preparation.intent.base.eventId

    init {
        val intent = this.preparation.intent
        val original = intent.base.content
        val replacement = intent.content
        require(original.details == EventDetails() && original.colorRgb == null && original.recurrence == null)
        require(replacement.details == EventDetails() && replacement.colorRgb == null && replacement.recurrence == null)
        require(replacement == original.copy(time = replacement.time) && replacement.time != original.time)
        require(state != TimeUpdateAttemptState.PREPARED || protectedEvidence == null)
        bounded(id.value, LocalTimeEditPreparation.MAX_CONTEXT_BYTES)
        bounded(format.id, LocalTimeEditPreparation.MAX_CONTEXT_BYTES)
        bounded(protectedPayload, LocalTimeEditPreparation.MAX_PAYLOAD_BYTES)
        protectedEvidence?.let { bounded(it, LocalTimeEditPreparation.MAX_PAYLOAD_BYTES) }
    }

    fun frozen(): TimeUpdateAttemptRecord = copy()

    fun copy(id: MutationId = this.id, preparation: LocalTimeEditPreparation = this.preparation,
        format: AdapterFormat = this.format, protectedPayload: String = this.protectedPayload,
        state: TimeUpdateAttemptState = this.state, protectedEvidence: String? = this.protectedEvidence) =
        TimeUpdateAttemptRecord(id, preparation, format, protectedPayload, state, protectedEvidence)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TimeUpdateAttemptRecord) return false
        return id == other.id && preparation.intent == other.preparation.intent &&
            preparation.timestamp == other.preparation.timestamp &&
            preparation.format == other.preparation.format &&
            preparation.protectedPayload == other.preparation.protectedPayload &&
            format == other.format && protectedPayload == other.protectedPayload &&
            state == other.state && protectedEvidence == other.protectedEvidence
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + preparation.intent.hashCode()
        result = 31 * result + preparation.timestamp.hashCode()
        result = 31 * result + preparation.format.hashCode()
        result = 31 * result + preparation.protectedPayload.hashCode()
        result = 31 * result + format.hashCode()
        result = 31 * result + protectedPayload.hashCode()
        result = 31 * result + state.hashCode()
        return 31 * result + (protectedEvidence?.hashCode() ?: 0)
    }

    override fun toString() = "TimeUpdateAttemptRecord(redacted)"

    companion object {
        private fun bounded(value: String, maximum: Int) {
            require(value.isNotBlank() && value.length <= maximum)
            require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <= maximum)
        }
    }
}

/** Experimental isolated persistence, with no transport, automatic reset or completion.
 * All calls require a caller-owned scoped lease. A denied read returns null, which cannot
 * establish absence or permission to send. A claim only commits local PREPARED -> UNKNOWN;
 * UNKNOWN is never automatically resent, including after cancellation or a lost reply.
 * Independent title, text and time databases must not act as concurrent dispatch authorities.
 * Evidence is immutable opaque history, not verified success. Its caller may supply a
 * retention lease independent of edit consent, but denied admission cannot mutate storage.
 * Implementations freeze inputs before suspension, check cancellation around callbacks,
 * and keep each lease closed once denied. Mutations recheck admission before commit;
 * cancellation or revocation after commit cannot reset UNKNOWN. A successful claim returns
 * only after durable commit. One unresolved remote event and request identity are retained
 * across generations. Corrupt storage is an error, never evidence of missing work.
 */
interface TimeUpdateAttemptJournal {
    /** Exact PREPARED replay is idempotent; UNKNOWN and substitutions cannot be replaced. */
    suspend fun remember(record: TimeUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean
    suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TimeUpdateAttemptRecord?
    suspend fun claim(expected: TimeUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean
    /** First evidence is immutable. Repetition requires the complete same UNKNOWN work,
     * excluding only evidence, with expected evidence either absent or identical to the retained value.
     */
    suspend fun retainEvidence(expectedUnknown: TimeUpdateAttemptRecord, protectedEvidence: String,
        admit: (CalendarScope) -> Boolean): Boolean
}
