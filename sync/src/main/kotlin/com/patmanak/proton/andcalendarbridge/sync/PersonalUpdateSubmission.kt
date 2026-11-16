// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Only unchanged text may use CRLF equivalence; time, kind, zone and metadata stay exact. */
object PersonalUpdateContentPolicy {
    fun sameSubmittedContent(a: EventContent, b: EventContent): Boolean =
        canonical(a) == canonical(b)
    private fun canonical(value: EventContent) = value.copy(title = value.title.replace("\r\n", "\n"),
        description = value.description.replace("\r\n", "\n"), location = value.location.replace("\r\n", "\n"))

    internal fun freezeObservation(preparation: PersonalEditPreparation, actual: EventContent): EventContent {
        val expected = preparation.intent.content
        if (!preparation.isSeries) return freezeObservation(expected, actual)
        val frozen = SeriesEditProfile.freezeMaster(actual)
        require(frozen.recurrence == expected.recurrence)
        return freezeObservation(expected.copy(recurrence = null), frozen.copy(recurrence = null))
            .copy(recurrence = frozen.recurrence)
    }

    /** Check the actual decoded bytes before freezing. Never run a readback through the
     * raw personal candidate: its unchanged LF baseline may have been serialized as CRLF.
     */
    internal fun freezeObservation(expected: EventContent, actual: EventContent): EventContent {
        require(actual.details == expected.details && actual.colorRgb == expected.colorRgb && actual.recurrence == null)
        for (value in listOf(actual.title, actual.description, actual.location)) {
            require(value.length <= PersonalEditPreparation.MAX_TEXT_BYTES)
            val bytes = try {
                Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining()
            } catch (_: java.nio.charset.CharacterCodingException) {
                throw IllegalArgumentException("Invalid personal edit v2 readback text")
            }
            require(bytes <= PersonalEditPreparation.MAX_TEXT_BYTES)
            require(value.replace("\r\n", "\n").none { it.isISOControl() && it != '\n' && it != '\t' })
        }
        require(sameSubmittedContent(expected, actual))
        return actual.copy(details = actual.details!!.copy(reminders = java.util.Collections.unmodifiableList(actual.details.reminders.toList()), attendees = emptyList()))
    }
}

/** Full raw historical binding, independent of claim state and separately retained response evidence. */
data class PersonalUpdateAttemptIdentity(val scope: CalendarScope, val id: MutationId, val localIntentId: MutationId,
    val eventId: EventId, val baseRevision: Revision, val format: AdapterFormat,
    val preparationFormat: AdapterFormat, val preparationFingerprint: String, val payloadFingerprint: String) {
    init {
        listOf(scope.account.value, scope.calendar.value, id.value, localIntentId.value, eventId.value,
            baseRevision.value, format.id, preparationFormat.id).forEach(::boundedIdentity)
        require(listOf(preparationFingerprint, payloadFingerprint).all { it.matches(Regex("[a-f0-9]{64}")) })
    }
    fun matchesPreparation(preparation: PersonalEditPreparation): Boolean = try {
        val frozen = freeze(preparation)
        val intent = frozen.intent
        scope == intent.scope && localIntentId == intent.id && eventId == intent.base.eventId &&
            baseRevision == intent.base.revision && preparationFormat == frozen.format &&
            preparationFingerprint == preparationFingerprint(frozen)
    } catch (_: Exception) { false }
    override fun toString() = "PersonalUpdateAttemptIdentity(redacted)"
    companion object {
        fun of(record: PersonalUpdateAttemptRecord): PersonalUpdateAttemptIdentity {
            val frozen = record.frozen()
            return PersonalUpdateAttemptIdentity(frozen.scope, frozen.id, frozen.preparation.intent.id, frozen.eventId,
                frozen.preparation.intent.base.revision, frozen.format, frozen.preparation.format,
                preparationFingerprint(frozen.preparation), fingerprint(tag(frozen.preparation, "payload")) { field(frozen.protectedPayload) })
        }

        /** Stable only for the exact committed local capsule and target request format.
         * Never authorizes regenerating or replacing a first retained wire with this identity.
         */
        fun requestId(preparation: PersonalEditPreparation, requestFormat: AdapterFormat): MutationId {
            val frozen = freeze(preparation)
            boundedIdentity(requestFormat.id)
            return MutationId((if (frozen.isSeries) "personal-series-v3-" else "personal-edit-v2-") + fingerprint(tag(frozen, "request-id")) {
                this.preparation(frozen); field(requestFormat.id); field(requestFormat.version.toString())
            })
        }

        private fun freeze(value: PersonalEditPreparation): PersonalEditPreparation {
            val frozen = value.frozen()
            val base = frozen.intent.base.content
            require(!PersonalUpdateContentPolicy.sameSubmittedContent(base, frozen.intent.content))
            return frozen
        }
        private fun preparationFingerprint(value: PersonalEditPreparation) =
            fingerprint(tag(value, "preparation")) { preparation(freeze(value)) }

        private fun tag(value: PersonalEditPreparation, purpose: String): String = if (value.isSeries)
            "calendeer-personal-series-v3-$purpose-v1" else "calendeer-personal-edit-v2-$purpose-v2"

        private fun boundedIdentity(value: String) {
            require(value.isNotBlank() && value.length <= PersonalEditPreparation.MAX_CONTEXT_BYTES)
            require(strictBytes(value).remaining() <= PersonalEditPreparation.MAX_CONTEXT_BYTES)
        }
        private fun strictBytes(value: String): ByteBuffer = try {
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        } catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("Invalid personal edit v2 identity") }

        private fun fingerprint(domain: String, contents: Fingerprint.() -> Unit): String = Fingerprint().run {
            field(domain); contents(); finish()
        }
        private class Fingerprint {
            private val digest = MessageDigest.getInstance("SHA-256")
            fun field(value: String) {
                val bytes = strictBytes(value)
                digest.update(ByteBuffer.allocate(4).putInt(bytes.remaining()).array()); digest.update(bytes)
            }
            fun preparation(value: PersonalEditPreparation) {
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
                field(if (value.recurrence == null) "PERSONAL_EDIT_COMPLETE_CONTENT_V2" else "PERSONAL_SERIES_COMPLETE_CONTENT_V3")
                field(value.colorRgb?.toString() ?: "NULL")
                val details = checkNotNull(value.details)
                field(details.reminders.size.toString())
                details.reminders.forEach { field(it.minutesBefore.toString()) }
                if (value.recurrence == null) field("NO_ORGANIZER_NO_ATTENDEES_NO_RECURRENCE") else {
                    field("NO_ORGANIZER_NO_ATTENDEES_FINITE_DAILY")
                    field(value.recurrence.uid); field(requireNotNull(value.recurrence.rule))
                    field("NO_RDATES_NO_EXDATES")
                }
            }
            fun finish() = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}

sealed interface PersonalUpdateSubmissionObservation {
    data object Unknown : PersonalUpdateSubmissionObservation
    /** Full UNKNOWN work plus a supplied verified observation, never server CAS or native ACK. */
    class Verified(record: PersonalUpdateAttemptRecord, observed: RemoteChange.Upsert) : PersonalUpdateSubmissionObservation {
        val record = record.frozen()
        val event: RemoteChange.Upsert
        init {
            require(this.record.state == PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
            require(observed.eventId == this.record.eventId && observed.revision != this.record.preparation.intent.base.revision)
            // Preserve the original raw text relation before accepting canonical readback.
            val actual = PersonalUpdateContentPolicy.freezeObservation(this.record.preparation, observed.content)
            event = observed.copy(content = actual)
        }
        override fun toString() = "PersonalUpdateSubmissionObservation.Verified(redacted)"
    }
}

/** No permissive overload: UNKNOWN is inspection-only and evidence has its own scoped lease. */
interface CalendarPersonalUpdatePort {
    val personalUpdateFormat: AdapterFormat?
    val seriesUpdateFormat: AdapterFormat? get() = null
    suspend fun prepareSubmission(id: MutationId, preparation: PersonalEditPreparation,
        admit: (CalendarScope) -> Boolean): PersonalUpdateAttemptRecord?
    suspend fun submit(record: PersonalUpdateAttemptRecord, journal: PersonalUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean): PersonalUpdateSubmissionObservation
    suspend fun inspect(record: PersonalUpdateAttemptRecord, journal: PersonalUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean): PersonalUpdateSubmissionObservation
}
