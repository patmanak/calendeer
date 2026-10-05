// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.CharBuffer
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

enum class TitleUpdateAttemptState { PREPARED, OUTCOME_UNKNOWN }

/** Frozen title-only experiment, separate from CREATE and from native acknowledgement.
 * Preparation carries the original intent; protectedPayload is a distinct versioned wire envelope.
 * Neither historical PAUSED nor PREPARED grants access, mode or dispatch admission.
 */
data class TitleUpdateAttemptRecord(val id: MutationId, val preparation: LocalEditPreparation,
    val format: AdapterFormat, val protectedPayload: String,
    val state: TitleUpdateAttemptState = TitleUpdateAttemptState.PREPARED, val protectedEvidence: String? = null) {
    val scope: CalendarScope get() = preparation.intent.scope
    init {
        val intent = LocalEditPreparation.freezeIntent(preparation.intent)
        require(intent.base.content.details == EventDetails() && intent.base.content.colorRgb == null)
        bounded(id.value, LocalEditPreparation.MAX_CONTEXT_BYTES)
        bounded(format.id, LocalEditPreparation.MAX_CONTEXT_BYTES)
        bounded(protectedPayload, LocalEditPreparation.MAX_PAYLOAD_BYTES)
        protectedEvidence?.let { bounded(it, LocalEditPreparation.MAX_PAYLOAD_BYTES) }
    }
    fun frozen(): TitleUpdateAttemptRecord = copy(preparation = preparation.copy(
        intent = LocalEditPreparation.freezeIntent(preparation.intent)))
    override fun toString() = "TitleUpdateAttemptRecord(state=$state, redacted)"
    companion object {
        private fun bounded(value: String, maximum: Int) {
            require(value.isNotBlank() && value.length <= maximum)
            require(Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <= maximum)
        }
    }
}

/** Exact ciphertext reference; formats/intent are never reinterpreted across generations. */
data class TitleUpdateAttemptIdentity(val scope: CalendarScope, val id: MutationId, val localIntentId: MutationId,
    val eventId: EventId, val baseRevision: Revision, val format: AdapterFormat,
    val preparationFormat: AdapterFormat, val preparationFingerprint: String, val payloadFingerprint: String) {
    init {
        require(listOf(preparationFingerprint, payloadFingerprint).all { it.matches(Regex("[a-f0-9]{64}")) })
    }
    override fun toString() = "TitleUpdateAttemptIdentity(redacted)"
    /** Complete historical preparation binding; no protected wire decoding or write admission. */
    fun matchesPreparation(preparation: LocalEditPreparation): Boolean {
        val frozen = preparation.copy(intent = LocalEditPreparation.freezeIntent(preparation.intent))
        val intent = frozen.intent
        if (intent.base.content.details != EventDetails() || intent.base.content.colorRgb != null) return false
        return scope == intent.scope && localIntentId == intent.id && eventId == intent.base.eventId &&
            baseRevision == intent.base.revision && preparationFormat == frozen.format &&
            preparationFingerprint == preparationFingerprint(frozen)
    }
    companion object {
        fun of(record: TitleUpdateAttemptRecord): TitleUpdateAttemptIdentity {
            val value = record.frozen()
            return TitleUpdateAttemptIdentity(value.scope, value.id, value.preparation.intent.id,
                value.preparation.intent.base.eventId, value.preparation.intent.base.revision, value.format,
                value.preparation.format, preparationFingerprint(value.preparation), fingerprint(value.protectedPayload))
        }
        private fun preparationFingerprint(preparation: LocalEditPreparation): String {
            val hash = MessageDigest.getInstance("SHA-256")
            fun field(value: String) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                hash.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); hash.update(bytes)
            }
            val intent = preparation.intent
            field("calendeer-title-update-preparation-v1")
            listOf(intent.id.value, intent.scope.account.value, intent.scope.calendar.value, intent.scope.generation.toString(),
                intent.format.id, intent.format.version.toString(), intent.state.name, intent.base.eventId.value,
                intent.base.revision.value, preparation.timestamp.toString(), preparation.format.id,
                preparation.format.version.toString(), preparation.protectedPayload).forEach(::field)
            fun content(value: EventContent) {
                listOf(value.title, value.description, value.location).forEach(::field)
                when (val time = value.time) {
                    is EventTime.AllDay -> listOf("ALL_DAY", time.start.toString(), time.endExclusive.toString()).forEach(::field)
                    is EventTime.Timed -> listOf("TIMED", time.start.toString(), time.end.toString(), time.zone.id).forEach(::field)
                }
                field("KNOWN_EMPTY_DETAILS_NO_COLOR_NO_RECURRENCE")
            }
            content(intent.base.content); content(intent.content)
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        private fun fingerprint(value: String) = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** remember is immutable/idempotent for exact PREPARED work without evidence. Implementations
 * must retain one unresolved attempt per remote account/calendar/event, across generations,
 * so another UUID, base or generation cannot bypass UNKNOWN. A generation is never a retry key.
 * claim atomically compares the complete record and durably commits UNKNOWN before returning true.
 * A failed commit must not admit dispatch. UNKNOWN cannot reset/requeue/delete automatically.
 * Evidence attaches once to the exact UNKNOWN record; changed evidence or work must fail.
 * Payloads are already protected. Freeze before suspension; never decode, log or migrate them.
 */
interface TitleUpdateAttemptJournal {
    suspend fun remember(record: TitleUpdateAttemptRecord)
    suspend fun read(scope: CalendarScope, id: MutationId): TitleUpdateAttemptRecord?
    suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean
    suspend fun retainEvidence(expectedUnknown: TitleUpdateAttemptRecord, protectedEvidence: String)
}

class TitleUpdateAttemptAdmission(private val journal: TitleUpdateAttemptJournal) {
    suspend fun dispatch(expected: TitleUpdateAttemptRecord, send: suspend (TitleUpdateAttemptRecord) -> Unit): Boolean {
        val frozen = expected.frozen()
        if (frozen.state != TitleUpdateAttemptState.PREPARED || frozen.protectedEvidence != null || !journal.claim(frozen)) return false
        send(frozen.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN))
        return true
    }
}

sealed interface TitleUpdateSubmissionObservation {
    data object Unknown : TitleUpdateSubmissionObservation
    /** Current authenticated full-content readback. Not server CAS, authorship of a write,
     * immutable remote history, native acknowledgement or permission to send again.
     */
    class Verified(val attempt: TitleUpdateAttemptIdentity, observed: RemoteChange.Upsert) : TitleUpdateSubmissionObservation {
        val event: RemoteChange.Upsert
        init {
            require(observed.eventId == attempt.eventId && observed.revision != attempt.baseRevision)
            require(observed.content.details == EventDetails() && observed.content.recurrence == null && observed.content.colorRgb == null)
            event = observed.copy(content = observed.content.copy(details = EventDetails()))
        }
        override fun toString() = "TitleUpdateSubmissionObservation.Verified(redacted)"
    }
}

/** Internal optimistic title-only experiment. Fresh full-base/session/rights comparisons precede
 * journal admission; they are successive observations, not an atomic server precondition.
 * UNKNOWN is inspect-only even without a response. Exactly one transport exchange after claim;
 * failures/cancellation never reset it. Full signed partitions and untouched metadata must qualify
 * readback; a matching display title alone is insufficient. No Provider writes or automatic mode.
 */
interface CalendarTitleUpdateSubmissionPort {
    val titleUpdateFormat: AdapterFormat?
    suspend fun prepareSubmission(id: MutationId, preparation: LocalEditPreparation): TitleUpdateAttemptRecord
    suspend fun submit(record: TitleUpdateAttemptRecord, journal: TitleUpdateAttemptJournal): TitleUpdateSubmissionObservation
    suspend fun inspect(record: TitleUpdateAttemptRecord): TitleUpdateSubmissionObservation
}
