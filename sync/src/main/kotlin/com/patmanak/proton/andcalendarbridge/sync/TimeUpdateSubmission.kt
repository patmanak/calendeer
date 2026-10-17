// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Only unchanged text may use CRLF equivalence; time, kind, zone and metadata stay exact. */
object TimeUpdateContentPolicy {
    fun sameSubmittedContent(a: EventContent, b: EventContent): Boolean =
        canonical(a) == canonical(b)
    private fun canonical(value: EventContent) = value.copy(title = value.title.replace("\r\n", "\n"),
        description = value.description.replace("\r\n", "\n"), location = value.location.replace("\r\n", "\n"))

    /** Check the actual decoded bytes before freezing. Never run a readback through the
     * raw time candidate: its unchanged LF baseline may have been serialized as CRLF.
     */
    internal fun freezeObservation(expected: EventContent, actual: EventContent): EventContent {
        require(actual.details == EventDetails() && actual.colorRgb == null && actual.recurrence == null)
        for (value in listOf(actual.title, actual.description, actual.location)) {
            require(value.length <= LocalTimeEditPreparation.MAX_TEXT_BYTES)
            val bytes = try {
                Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining()
            } catch (_: java.nio.charset.CharacterCodingException) {
                throw IllegalArgumentException("Invalid time readback text")
            }
            require(bytes <= LocalTimeEditPreparation.MAX_TEXT_BYTES)
            require(value.replace("\r\n", "\n").none { it.isISOControl() && it != '\n' && it != '\t' })
        }
        require(sameSubmittedContent(expected, actual))
        return actual.copy(details = EventDetails())
    }
}

/** Full raw historical binding, independent of claim state and separately retained response evidence. */
data class TimeUpdateAttemptIdentity(val scope: CalendarScope, val id: MutationId, val localIntentId: MutationId,
    val eventId: EventId, val baseRevision: Revision, val format: AdapterFormat,
    val preparationFormat: AdapterFormat, val preparationFingerprint: String, val payloadFingerprint: String) {
    init {
        listOf(scope.account.value, scope.calendar.value, id.value, localIntentId.value, eventId.value,
            baseRevision.value, format.id, preparationFormat.id).forEach(::boundedIdentity)
        require(listOf(preparationFingerprint, payloadFingerprint).all { it.matches(Regex("[a-f0-9]{64}")) })
    }
    fun matchesPreparation(preparation: LocalTimeEditPreparation): Boolean = try {
        val frozen = freeze(preparation)
        val intent = frozen.intent
        scope == intent.scope && localIntentId == intent.id && eventId == intent.base.eventId &&
            baseRevision == intent.base.revision && preparationFormat == frozen.format &&
            preparationFingerprint == preparationFingerprint(frozen)
    } catch (_: Exception) { false }
    override fun toString() = "TimeUpdateAttemptIdentity(redacted)"
    companion object {
        fun of(record: TimeUpdateAttemptRecord): TimeUpdateAttemptIdentity {
            val frozen = record.frozen()
            return TimeUpdateAttemptIdentity(frozen.scope, frozen.id, frozen.preparation.intent.id, frozen.eventId,
                frozen.preparation.intent.base.revision, frozen.format, frozen.preparation.format,
                preparationFingerprint(frozen.preparation), fingerprint("calendeer-time-update-payload-v1") { field(frozen.protectedPayload) })
        }

        /** Stable only for the exact committed local capsule and target request format.
         * Never authorizes regenerating or replacing a first retained wire with this identity.
         */
        fun requestId(preparation: LocalTimeEditPreparation, requestFormat: AdapterFormat): MutationId {
            val frozen = freeze(preparation)
            boundedIdentity(requestFormat.id)
            return MutationId("time-update-v1-" + fingerprint("calendeer-time-update-request-id-v1") {
                this.preparation(frozen); field(requestFormat.id); field(requestFormat.version.toString())
            })
        }

        private fun freeze(value: LocalTimeEditPreparation): LocalTimeEditPreparation {
            val frozen = LocalTimeEditPreparation(value.intent, value.timestamp, value.format, value.protectedPayload)
            val base = frozen.intent.base.content
            require(base.details == EventDetails() && base.colorRgb == null && base.recurrence == null)
            require(!TimeUpdateContentPolicy.sameSubmittedContent(base, frozen.intent.content))
            return frozen
        }
        private fun preparationFingerprint(value: LocalTimeEditPreparation) =
            fingerprint("calendeer-time-update-preparation-v1") { preparation(freeze(value)) }

        private fun boundedIdentity(value: String) {
            require(value.isNotBlank() && value.length <= LocalTimeEditPreparation.MAX_CONTEXT_BYTES)
            require(strictBytes(value).remaining() <= LocalTimeEditPreparation.MAX_CONTEXT_BYTES)
        }
        private fun strictBytes(value: String): ByteBuffer = try {
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        } catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("Invalid time identity") }

        private fun fingerprint(domain: String, contents: Fingerprint.() -> Unit): String = Fingerprint().run {
            field(domain); contents(); finish()
        }
        private class Fingerprint {
            private val digest = MessageDigest.getInstance("SHA-256")
            fun field(value: String) {
                val bytes = strictBytes(value)
                digest.update(ByteBuffer.allocate(4).putInt(bytes.remaining()).array()); digest.update(bytes)
            }
            fun preparation(value: LocalTimeEditPreparation) {
                val intent = value.intent
                listOf(intent.id.value, intent.scope.account.value, intent.scope.calendar.value, intent.scope.generation.toString(),
                    intent.format.id, intent.format.version.toString(), "PAUSED", intent.base.eventId.value, intent.base.revision.value,
                    value.timestamp.toString(), value.format.id, value.format.version.toString(), value.protectedPayload).forEach(::field)
                content(intent.base.content); content(intent.content)
            }
            private fun content(value: EventContent) {
                listOf(value.title, value.description, value.location).forEach(::field)
                when (val time = value.time) {
                    is EventTime.Timed -> listOf("TIMED", time.start.toString(), time.end.toString(), time.zone.id).forEach(::field)
                    is EventTime.AllDay -> listOf("ALL_DAY", time.start.toString(), time.endExclusive.toString()).forEach(::field)
                }
                field("KNOWN_EMPTY_DETAILS_NO_COLOR_NO_RECURRENCE")
            }
            fun finish() = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

sealed interface TimeUpdateSubmissionObservation {
    data object Unknown : TimeUpdateSubmissionObservation
    /** Full UNKNOWN work plus a supplied verified observation, never server CAS or native ACK. */
    class Verified(record: TimeUpdateAttemptRecord, observed: RemoteChange.Upsert) : TimeUpdateSubmissionObservation {
        val record = record.frozen()
        val event: RemoteChange.Upsert
        init {
            require(this.record.state == TimeUpdateAttemptState.OUTCOME_UNKNOWN)
            require(observed.eventId == this.record.eventId && observed.revision != this.record.preparation.intent.base.revision)
            // Preserve the original raw text relation before accepting canonical readback.
            val actual = TimeUpdateContentPolicy.freezeObservation(this.record.preparation.intent.content, observed.content)
            event = observed.copy(content = actual)
        }
        override fun toString() = "TimeUpdateSubmissionObservation.Verified(redacted)"
    }
}

/** No permissive overload: UNKNOWN is inspection-only and evidence has its own scoped lease. */
interface CalendarTimeUpdateSubmissionPort {
    val timeUpdateFormat: AdapterFormat?
    suspend fun prepareSubmission(id: MutationId, preparation: LocalTimeEditPreparation,
        admit: (CalendarScope) -> Boolean): TimeUpdateAttemptRecord?
    suspend fun submit(record: TimeUpdateAttemptRecord, journal: TimeUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean): TimeUpdateSubmissionObservation
    suspend fun inspect(record: TimeUpdateAttemptRecord, journal: TimeUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean): TimeUpdateSubmissionObservation
}
