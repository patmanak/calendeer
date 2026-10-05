// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Historical full-content observation bound to one immutable UNKNOWN attempt.
 * Construction validates the adapter contract, not signatures or server authorship. Only the
 * submission adapter supplies authenticated observations. Response evidence is independently
 * retained and never changes this identity. This value grants neither dispatch nor native ACK.
 */
class TitleUpdateVerifiedResult private constructor(
    val record: TitleUpdateAttemptRecord,
    val event: RemoteChange.Upsert
) {
    val attempt: TitleUpdateAttemptIdentity get() = TitleUpdateAttemptIdentity.of(record)

    override fun equals(other: Any?) = other is TitleUpdateVerifiedResult &&
        record == other.record && event == other.event
    override fun hashCode() = 31 * record.hashCode() + event.hashCode()
    override fun toString() = "TitleUpdateVerifiedResult(redacted)"

    companion object {
        fun of(record: TitleUpdateAttemptRecord, observation: TitleUpdateSubmissionObservation.Verified): TitleUpdateVerifiedResult {
            val frozen = record.frozen().copy(protectedEvidence = null)
            require(frozen.state == TitleUpdateAttemptState.OUTCOME_UNKNOWN) { "Verified results require unknown title work" }
            require(observation.attempt == TitleUpdateAttemptIdentity.of(frozen)) { "Verified result belongs to different title work" }
            require(observation.event.content == frozen.preparation.intent.content) { "Verified result must match complete submitted contents" }
            return TitleUpdateVerifiedResult(frozen, observation.event.copy(content =
                observation.event.content.copy(details = EventDetails())))
        }
    }
}

/** Commit the first verified result only against the complete identical retained UNKNOWN record,
 * ignoring its separately attachable response evidence. Exact repetitions are idempotent; changed
 * work or observations fail without replacing the first result. Successful return follows durable
 * commit. readVerified returns a historical snapshot, never current server state or ACK admission.
 * No result can release, reset or requeue the attempt. Freeze values before storage suspension.
 */
interface TitleUpdateResultJournal {
    suspend fun retainVerified(result: TitleUpdateVerifiedResult)
    suspend fun readVerified(scope: CalendarScope, id: MutationId): TitleUpdateVerifiedResult?
}

/** Internal inspect-only recovery. No preparation, claim, submit or native capability is available.
 * A retained proof is returned without network access. Otherwise an authenticated inspection must
 * precede durable retention; unknown observations leave the journal intact. Exceptions/cancellation
 * propagate, so an uncommitted result is never returned as durable success.
 */
class TitleUpdateResultRecovery(
    private val attempts: TitleUpdateAttemptJournal,
    private val results: TitleUpdateResultJournal,
    private val submission: CalendarTitleUpdateSubmissionPort
) {
    suspend fun recover(scope: CalendarScope, id: MutationId): TitleUpdateVerifiedResult? {
        val record = attempts.read(scope, id)?.frozen() ?: return null
        if (record.state != TitleUpdateAttemptState.OUTCOME_UNKNOWN) return null
        val retained = results.readVerified(scope, id)
        if (retained != null) {
            check(retained.record == record.copy(protectedEvidence = null)) { "Retained title result belongs to different work" }
            return retained
        }
        return when (val observed = submission.inspect(record)) {
            TitleUpdateSubmissionObservation.Unknown -> null
            is TitleUpdateSubmissionObservation.Verified -> TitleUpdateVerifiedResult.of(record, observed).also {
                results.retainVerified(it)
            }
        }
    }
}
