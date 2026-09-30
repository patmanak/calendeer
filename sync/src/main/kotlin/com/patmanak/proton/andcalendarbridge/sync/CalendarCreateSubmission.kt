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
    /** Local, stable declaration for separately preparing known-empty captured creates.
     * Null refuses staging; this performs no IO and grants no dispatch or editable capability.
     * Existing local preparation formats must never be converted or reinterpreted.
     */
    val knownEmptySubmissionFormat: AdapterFormat? get() = null
    suspend fun prepareSubmission(draft: CalendarCreateDraft): PreparedCalendarCreate
    suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal): CreateSubmissionObservation
    suspend fun inspect(record: CreateAttemptRecord): CreateSubmissionObservation

    /** Fail-closed captured path. The sealed submission MUST be known-empty and match the entire
     * immutable captured draft before fresh preparation or inspection. Final local qualification
     * belongs to journal.claim, after the adapter's last fresh rights/key/session reads.
     * UNKNOWN is read-only even when the original Provider row has changed or disappeared.
     * A Verified result uses the exact expected domain content after qualifying the adapter's
     * canonical representation; preserve the authenticated observed remote ID and revision.
     */
    suspend fun submitCaptured(record: CreateAttemptRecord, expected: CalendarCreateDraft,
        journal: CreateAttemptJournal): CreateSubmissionObservation = CreateSubmissionObservation.Unknown
    suspend fun inspectCaptured(record: CreateAttemptRecord,
        expected: CalendarCreateDraft): CreateSubmissionObservation = CreateSubmissionObservation.Unknown
}
