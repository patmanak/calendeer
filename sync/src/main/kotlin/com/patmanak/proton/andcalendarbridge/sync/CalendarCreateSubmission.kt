// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

sealed interface CreateSubmissionObservation {
    data object Unknown : CreateSubmissionObservation
    /** Authenticated, verified current read, not server idempotency or atomicity evidence. */
    class Verified(val attempt: CreateAttemptIdentity, val event: RemoteChange.Upsert) : CreateSubmissionObservation {
        override fun toString() = "CreateSubmissionObservation.Verified(redacted)"
    }
}

/** Experimental simple CREATE boundary; no Provider editing/capture capability is advertised.
 * Prepare is read-only. Submit revalidates rights/context then durably claims UNKNOWN before one
 * request. Every uncertain or existing claim is read-only thereafter. inspect never sends/requeues.
 * Raw responses are protected and retained before inspection. A verified read does not consume the
 * journal. The app owns experimental association and admission lifecycle; general Provider intent
 * reconciliation and outgoing resolution remain separate obligations.
 */
interface CalendarCreateSubmissionPort {
    suspend fun prepareSubmission(draft: CalendarCreateDraft): PreparedCalendarCreate
    suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal): CreateSubmissionObservation
    suspend fun inspect(record: CreateAttemptRecord): CreateSubmissionObservation
}
