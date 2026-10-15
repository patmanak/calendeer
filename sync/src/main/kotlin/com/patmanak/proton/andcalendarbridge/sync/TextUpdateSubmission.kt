// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Comparison only; raw retained values are never rewritten. */
object TextUpdateContentPolicy {
    fun sameSubmittedContent(a: EventContent, b: EventContent): Boolean = canonical(a) == canonical(b)
    private fun canonical(value: EventContent) = value.copy(title = value.title.replace("\r\n", "\n"),
        description = value.description.replace("\r\n", "\n"), location = value.location.replace("\r\n", "\n"))
}

/** Full raw historical binding, independent of claim state and separately retained response evidence. */
data class TextUpdateAttemptIdentity(val scope: CalendarScope, val id: MutationId, val localIntentId: MutationId,
    val eventId: EventId, val baseRevision: Revision, val format: AdapterFormat,
    val preparationFormat: AdapterFormat, val preparationFingerprint: String, val payloadFingerprint: String) {
    init {
        listOf(scope.account.value, scope.calendar.value, id.value, localIntentId.value, eventId.value,
            baseRevision.value, format.id, preparationFormat.id).forEach(::boundedIdentity)
        require(listOf(preparationFingerprint, payloadFingerprint).all { it.matches(Regex("[a-f0-9]{64}")) })
    }
    fun matchesPreparation(preparation: LocalTextEditPreparation): Boolean = try {
        val frozen = freeze(preparation)
        val intent = frozen.intent
        scope == intent.scope && localIntentId == intent.id && eventId == intent.base.eventId &&
            baseRevision == intent.base.revision && preparationFormat == frozen.format &&
            preparationFingerprint == preparationFingerprint(frozen)
    } catch (_: Exception) { false }
    override fun toString() = "TextUpdateAttemptIdentity(redacted)"
    companion object {
        fun of(record: TextUpdateAttemptRecord): TextUpdateAttemptIdentity {
            val frozen = record.frozen()
            return TextUpdateAttemptIdentity(frozen.scope, frozen.id, frozen.preparation.intent.id, frozen.eventId,
                frozen.preparation.intent.base.revision, frozen.format, frozen.preparation.format,
                preparationFingerprint(frozen.preparation), fingerprint("calendeer-text-update-payload-v1") { field(frozen.protectedPayload) })
        }

        /** Stable only for the exact committed local capsule and target request format.
         * Never authorizes regenerating or replacing a first retained wire with this identity.
         */
        fun requestId(preparation: LocalTextEditPreparation, requestFormat: AdapterFormat): MutationId {
            val frozen = freeze(preparation)
            boundedIdentity(requestFormat.id)
            return MutationId("text-update-v1-" + fingerprint("calendeer-text-update-request-id-v1") {
                this.preparation(frozen); field(requestFormat.id); field(requestFormat.version.toString())
            })
        }

        private fun freeze(value: LocalTextEditPreparation): LocalTextEditPreparation {
            val frozen = LocalTextEditPreparation(value.intent, value.timestamp, value.format, value.protectedPayload)
            val base = frozen.intent.base.content
            require(base.details == EventDetails() && base.colorRgb == null && base.recurrence == null)
            require(!TextUpdateContentPolicy.sameSubmittedContent(base, frozen.intent.content))
            return frozen
        }
        private fun preparationFingerprint(value: LocalTextEditPreparation) =
            fingerprint("calendeer-text-update-preparation-v1") { preparation(freeze(value)) }

        private fun boundedIdentity(value: String) {
            require(value.isNotBlank() && value.length <= LocalTextEditPreparation.MAX_CONTEXT_BYTES)
            require(strictBytes(value).remaining() <= LocalTextEditPreparation.MAX_CONTEXT_BYTES)
        }
        private fun strictBytes(value: String): ByteBuffer = try {
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        } catch (_: java.nio.charset.CharacterCodingException) { throw IllegalArgumentException("Invalid text identity") }

        private fun fingerprint(domain: String, contents: Fingerprint.() -> Unit): String = Fingerprint().run {
            field(domain); contents(); finish()
        }
        private class Fingerprint {
            private val digest = MessageDigest.getInstance("SHA-256")
            fun field(value: String) {
                val bytes = strictBytes(value)
                digest.update(ByteBuffer.allocate(4).putInt(bytes.remaining()).array()); digest.update(bytes)
            }
            fun preparation(value: LocalTextEditPreparation) {
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

sealed interface TextUpdateSubmissionObservation {
    data object Unknown : TextUpdateSubmissionObservation
    /** Full UNKNOWN work plus a supplied verified observation, never server CAS or native ACK. */
    class Verified(record: TextUpdateAttemptRecord, observed: RemoteChange.Upsert) : TextUpdateSubmissionObservation {
        val record = record.frozen()
        val event: RemoteChange.Upsert
        init {
            require(this.record.state == TextUpdateAttemptState.OUTCOME_UNKNOWN)
            require(observed.eventId == this.record.eventId && observed.revision != this.record.preparation.intent.base.revision)
            require(observed.content.details == EventDetails() && observed.content.colorRgb == null && observed.content.recurrence == null)
            require(TextUpdateContentPolicy.sameSubmittedContent(this.record.preparation.intent.content, observed.content))
            // Validate the actual raw observation's text bounds as well as its semantic relation.
            val actual = LocalTextEditPreparation.freezeIntent(this.record.preparation.intent.copy(content = observed.content)).content
            event = observed.copy(content = actual)
        }
        override fun toString() = "TextUpdateSubmissionObservation.Verified(redacted)"
    }
}

/** No permissive overload: UNKNOWN is inspection-only and evidence has its own scoped lease. */
interface CalendarTextUpdateSubmissionPort {
    val textUpdateFormat: AdapterFormat?
    suspend fun prepareSubmission(id: MutationId, preparation: LocalTextEditPreparation,
        admit: (CalendarScope) -> Boolean): TextUpdateAttemptRecord?
    suspend fun submit(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation
    suspend fun inspect(record: TextUpdateAttemptRecord, journal: TextUpdateAttemptJournal,
        admit: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation
}
