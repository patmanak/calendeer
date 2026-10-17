// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

object DeleteSubmissionPolicy {
    fun freezeIntent(intent: LocalDeleteIntent): LocalDeleteIntent = freezeDeleteIntent(intent)
}

/** Full raw binding. Claim state and independently retained response evidence are excluded. */
data class DeleteAttemptIdentity(val scope: CalendarScope, val id: MutationId, val localIntentId: MutationId,
    val eventId: EventId, val baseRevision: Revision, val format: AdapterFormat,
    val intentFingerprint: String, val payloadFingerprint: String) {
    init {
        listOf(scope.account.value, scope.calendar.value, id.value, localIntentId.value, eventId.value,
            baseRevision.value, format.id).forEach { deleteBounded(it, 4096) }
        require(listOf(intentFingerprint, payloadFingerprint).all { it.matches(Regex("[a-f0-9]{64}")) })
    }
    override fun toString() = "DeleteAttemptIdentity(redacted)"
    companion object {
        fun of(record: DeleteAttemptRecord): DeleteAttemptIdentity {
            val frozen = record.frozen()
            return DeleteAttemptIdentity(frozen.scope, frozen.id, frozen.intent.id, frozen.eventId,
                frozen.intent.base.revision, frozen.format, intentFingerprint(frozen.intent),
                fingerprint("calendeer-delete-payload-v1") { field(frozen.protectedPayload) })
        }
        /** Local stable request identity, never a server idempotency key or permission to regenerate. */
        fun requestId(intent: LocalDeleteIntent, format: AdapterFormat): MutationId {
            val frozen = freezeDeleteIntent(intent); deleteBounded(format.id, 4096)
            return MutationId("delete-v1-" + fingerprint("calendeer-delete-request-id-v1") {
                intent(frozen); field(format.id); field(format.version.toString())
            })
        }
        fun intentFingerprint(intent: LocalDeleteIntent): String =
            fingerprint("calendeer-delete-intent-v1") { intent(freezeDeleteIntent(intent)) }
        private fun fingerprint(domain: String, contents: Fingerprint.() -> Unit): String = Fingerprint().run {
            field(domain); contents(); finish()
        }
        private class Fingerprint {
            private val digest = MessageDigest.getInstance("SHA-256")
            fun field(value: String) {
                val bytes = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
                digest.update(ByteBuffer.allocate(4).putInt(bytes.remaining()).array()); digest.update(bytes)
            }
            fun intent(value: LocalDeleteIntent) {
                listOf(value.id.value, value.scope.account.value, value.scope.calendar.value, value.scope.generation.toString(),
                    value.format.id, value.format.version.toString(), value.state.name,
                    value.base.eventId.value, value.base.revision.value).forEach(::field)
                content(value.base.content); content(value.content)
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

sealed interface DeleteSubmissionObservation {
    data object Unknown : DeleteSubmissionObservation
    /** Authenticated observed absence, never attribution to our request or native acknowledgement. */
    class Verified(record: DeleteAttemptRecord) : DeleteSubmissionObservation {
        val record = record.frozen()
        init { require(this.record.state == DeleteAttemptState.OUTCOME_UNKNOWN) }
        override fun toString() = "DeleteSubmissionObservation.Verified(redacted)"
    }
    class Conflict(record: DeleteAttemptRecord, event: RemoteChange.Upsert) : DeleteSubmissionObservation {
        val record = record.frozen()
        val event = event.copy(content = freezeDeleteContent(event.content))
        init { require(this.event.eventId == this.record.eventId && this.event != this.record.intent.base) }
        override fun toString() = "DeleteSubmissionObservation.Conflict(redacted)"
    }
}

/** Separate default-off experimental port. UNKNOWN is inspect-only. No generic capability upgrade. */
interface CalendarDeleteSubmissionPort {
    val deleteFormat: AdapterFormat?
    suspend fun prepareSubmission(id: MutationId, intent: LocalDeleteIntent,
        admit: (CalendarScope) -> Boolean): DeleteAttemptRecord?
    suspend fun submit(record: DeleteAttemptRecord, journal: DeleteAttemptJournal,
        admit: (CalendarScope) -> Boolean, qualifySource: suspend (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean): DeleteSubmissionObservation
    suspend fun inspect(record: DeleteAttemptRecord, journal: DeleteAttemptJournal,
        admit: (CalendarScope) -> Boolean): DeleteSubmissionObservation
}
