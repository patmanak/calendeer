// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

enum class CreateAttemptState { PREPARED, OUTCOME_UNKNOWN }

/** Protected adapter payload, not a writable Provider intent or a remote acknowledgement. */
data class CreateAttemptRecord(val id: MutationId, val scope: CalendarScope, val format: AdapterFormat,
    val protectedPayload: String, val state: CreateAttemptState = CreateAttemptState.PREPARED,
    val protectedEvidence: String? = null) {
    init {
        require(protectedPayload.isNotBlank())
        require(protectedEvidence == null || protectedEvidence.isNotBlank())
    }
    override fun toString() = "CreateAttemptRecord(state=$state, redacted)"
}

/**
 * A bounded dispatch journal, separate from future Provider capture/conflict storage.
 * remember accepts only PREPARED work without evidence. It is immutable/idempotent only for
 * identical scope/id/format/payload; changed work must fail and existing UNKNOWN/evidence survive.
 * claim atomically compares the complete expected PREPARED snapshot and durably commits UNKNOWN
 * before returning true. Multiple instances/processes must not claim the same record twice.
 * Exceptions/failed commit must not admit dispatch. UNKNOWN has no automatic reset/requeue/delete.
 * Scope generations and adapter formats remain exact; no migration or scope substitution is inferred.
 * retainEvidence only attaches a protected response to an identical UNKNOWN attempt. The first
 * evidence is immutable: the same value is idempotent; a different value must fail. This never
 * acknowledges the attempt or permits dispatch. Payload/evidence are already adapter-protected;
 * implementations must not decode or log them.
 */
interface CreateAttemptJournal {
    suspend fun remember(record: CreateAttemptRecord)
    suspend fun read(scope: CalendarScope, id: MutationId): CreateAttemptRecord?
    suspend fun claim(expected: CreateAttemptRecord): Boolean
    suspend fun retainEvidence(expectedUnknown: CreateAttemptRecord, protectedEvidence: String)
}

/** Caller must freshly qualify rights/context before admission; this grants no writable capability.
 * Success of send is only transport completion. Every admitted attempt stays UNKNOWN until a
 * future explicit authenticated reconciliation, even if send throws, is cancelled, or returns.
 */
class CreateAttemptAdmission(private val journal: CreateAttemptJournal) {
    suspend fun dispatch(expected: CreateAttemptRecord, send: suspend (CreateAttemptRecord) -> Unit): Boolean {
        if (expected.state != CreateAttemptState.PREPARED || expected.protectedEvidence != null ||
            !journal.claim(expected)) return false
        send(expected.copy(state = CreateAttemptState.OUTCOME_UNKNOWN))
        return true
    }
}
