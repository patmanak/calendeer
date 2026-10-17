// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

enum class DeleteAttemptState { PREPARED, OUTCOME_UNKNOWN }

/** Narrow deletion history, separate from generic adapter dispatch and native acknowledgement. */
class DeleteAttemptRecord(val id: MutationId, intent: LocalDeleteIntent, val format: AdapterFormat,
    val protectedPayload: String, val state: DeleteAttemptState = DeleteAttemptState.PREPARED,
    val protectedEvidence: String? = null) {
    val intent = freezeDeleteIntent(intent)
    val scope: CalendarScope get() = intent.scope
    val eventId: EventId get() = intent.base.eventId
    init {
        deleteBounded(id.value, 4096); deleteBounded(format.id, 4096)
        deleteBounded(protectedPayload, MAX_PAYLOAD_BYTES)
        protectedEvidence?.let { deleteBounded(it, MAX_PAYLOAD_BYTES) }
        require(state != DeleteAttemptState.PREPARED || protectedEvidence == null)
    }
    fun frozen() = copy()
    fun copy(id: MutationId = this.id, intent: LocalDeleteIntent = this.intent,
        format: AdapterFormat = this.format, protectedPayload: String = this.protectedPayload,
        state: DeleteAttemptState = this.state, protectedEvidence: String? = this.protectedEvidence) =
        DeleteAttemptRecord(id, intent, format, protectedPayload, state, protectedEvidence)
    override fun equals(other: Any?) = other is DeleteAttemptRecord && id == other.id && intent == other.intent &&
        format == other.format && protectedPayload == other.protectedPayload && state == other.state && protectedEvidence == other.protectedEvidence
    override fun hashCode(): Int = listOf(id, intent, format, protectedPayload, state, protectedEvidence).hashCode()
    override fun toString() = "DeleteAttemptRecord(redacted)"
    companion object { const val MAX_PAYLOAD_BYTES = 4_194_304 }
}

internal fun deleteBounded(value: String, limit: Int, allowBlank: Boolean = false) {
    require((allowBlank || value.isNotBlank()) && value.length <= limit)
    val bytes = try {
        Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining()
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw IllegalArgumentException("Invalid deletion text")
    }
    require(bytes <= limit)
}

internal fun freezeDeleteContent(content: EventContent): EventContent {
    require(content.details == EventDetails() && content.colorRgb == null && content.recurrence == null)
    listOf(content.title, content.description, content.location).forEach {
        deleteBounded(it, 65_536, true)
        require(it.replace("\r\n", "\n").none { c -> c.isISOControl() && c != '\n' && c != '\t' })
    }
    when (val time = content.time) {
        is EventTime.AllDay -> require(time.start.year in 1970..9999 && time.endExclusive.year in 1970..9999)
        is EventTime.Timed -> for (instant in listOf(time.start, time.end)) {
            require(instant.nano == 0 && instant >= Instant.EPOCH && instant <= Instant.parse("9999-12-31T23:59:59Z"))
            require(instant.atZone(time.zone).year in 1970..9999)
        }
    }
    return content.copy(details = EventDetails())
}

internal fun freezeDeleteIntent(intent: LocalDeleteIntent): LocalDeleteIntent {
    require(intent.state == LocalEditState.PAUSED && intent.content == intent.base.content)
    listOf(intent.id.value, intent.scope.account.value, intent.scope.calendar.value, intent.format.id,
        intent.base.eventId.value, intent.base.revision.value).forEach { deleteBounded(it, 4096) }
    val content = freezeDeleteContent(intent.base.content)
    return intent.copy(base = intent.base.copy(content = content), content = content.copy(details = EventDetails()))
}

/** Scope-owned transactional history. Denied reads return null, never deletion/absence evidence.
 * Freeze arguments before suspending; close a lease permanently on its first denial and check it
 * again before commit. claim commits PREPARED -> UNKNOWN durably before returning. No reset or
 * resend of UNKNOWN, including cancellation after commit. Unresolved event ownership must arbitrate
 * with all UPDATE journals and generations. Storage corruption is an error, never missing work.
 */
interface DeleteAttemptJournal {
    /** Exact PREPARED replay only; never substitute another payload or replace UNKNOWN. */
    suspend fun remember(record: DeleteAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean
    suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): DeleteAttemptRecord?
    suspend fun claim(expected: DeleteAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean
    /** Preserve first evidence immutably; replay requires the same full UNKNOWN identity. */
    suspend fun retainEvidence(expectedUnknown: DeleteAttemptRecord, protectedEvidence: String,
        admit: (CalendarScope) -> Boolean): Boolean
}
