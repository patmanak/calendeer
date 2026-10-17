// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.sync.*

/** Timed wire discovery only: no all-day preparation reservation or borrowed title/text consent. */
object SystemTimeEditProfile {
    fun accepts(intent: LocalEditIntent): Boolean = try {
        val candidate = LocalTimeEditCandidate(intent)
        candidate.intent.content.time is EventTime.Timed
    } catch (_: Exception) { false }
}

/** Historical source snapshot only. Current ownership must be observed again before any claim. */
class TimeLocalOwnerSnapshot(intent: LocalEditIntent, preparation: LocalTimeEditPreparation?) {
    val intent = LocalTimeEditCandidate(intent).intent
    val preparation = preparation?.let { LocalTimeEditPreparation(it.intent, it.timestamp, it.format, it.protectedPayload) }
    init { require(this.preparation == null || this.preparation.intent == this.intent) }
    override fun toString() = "TimeLocalOwnerSnapshot(redacted)"
}

/** Local-only owner/Provider boundary. Implementations serialize with incoming projection and
 * qualify account, generation, selection, native contents and explicit CREATE provenance.
 * Local methods never enter an authenticated facade. Submission acquires the local
 * coordinator lock before the supplied port, whose source callback stays inside that
 * same lock without reacquiring it. This keeps the import-before-auth lock order.
 * Receipt reservation precedes a unique native CAS; historical receipts inspect only.
 */
interface SystemTimeLocalOwner {
    suspend fun observe(scope: CalendarScope, event: EventId, owner: MutationId,
        admit: (CalendarScope) -> Boolean): TimeLocalOwnerSnapshot?
    suspend fun retain(preparation: LocalTimeEditPreparation, admit: (CalendarScope) -> Boolean): LocalTimeEditPreparation?
    suspend fun qualify(preparation: LocalTimeEditPreparation, admit: (CalendarScope) -> Boolean): Boolean
    suspend fun submit(record: TimeUpdateAttemptRecord, journal: TimeUpdateRecoveryJournal,
        port: CalendarTimeUpdateSubmissionPort, admit: (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean): TimeUpdateSubmissionObservation
    suspend fun acknowledge(result: TimeUpdateVerifiedResult, journal: TimeUpdateRecoveryJournal,
        admit: (CalendarScope) -> Boolean): Boolean
    suspend fun complete(result: TimeUpdateVerifiedResult, journal: TimeUpdateRecoveryJournal,
        admit: (CalendarScope) -> Boolean): Boolean
}
