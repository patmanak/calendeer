// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.sync.*

/** All supported fresh edits use personal-v2. Existing legacy capsules keep their frozen route. */
object SystemPersonalEditProfile {
    fun accepts(intent: LocalEditIntent): Boolean = try {
        val candidate = PersonalEditCandidate(intent)
        !PersonalUpdateContentPolicy.sameSubmittedContent(candidate.intent.base.content, candidate.intent.content)
    } catch (_: Exception) { false }

    /** Preserve the historical native-source reader independently of new request routing. */
    fun requiresExtendedSnapshot(intent: LocalEditIntent): Boolean = historicalCandidate(intent) &&
        !SystemTimeEditProfile.accepts(intent) && !SystemTextEditProfile.accepts(intent) && !legacyTitle(intent)
    private fun historicalCandidate(intent: LocalEditIntent): Boolean = try { PersonalEditCandidate(intent); true }
        catch (_: Exception) { false }
    private fun legacyTitle(intent: LocalEditIntent): Boolean {
        val before = intent.base.content
        return before.details == EventDetails() && before.colorRgb == null && before.recurrence == null &&
            intent.content == before.copy(title = intent.content.title)
    }
}

/** Historical source snapshot only. Current ownership must be observed again before any claim. */
class PersonalLocalOwnerSnapshot(intent: LocalEditIntent, preparation: PersonalEditPreparation?) {
    val intent = PersonalEditCandidate(intent).intent
    val preparation = preparation?.let { PersonalEditPreparation(it.intent, it.timestamp, it.format, it.protectedPayload) }
    init { require(this.preparation == null || this.preparation.intent == this.intent) }
    override fun toString() = "PersonalLocalOwnerSnapshot(redacted)"
}

/** Local-only owner/Provider boundary. Implementations serialize with incoming projection and
 * qualify account, generation, selection, native contents and explicit CREATE provenance.
 * Local methods never enter an authenticated facade. Submission acquires the local
 * coordinator lock before the supplied port, whose source callback stays inside that
 * same lock without reacquiring it. This keeps the import-before-auth lock order.
 * Receipt reservation precedes a native CAS. Historical receipts inspect first;
 * a separate opt-in recovery capability may finish an interrupted CAS only after
 * full current-owner/source requalification, preserving its original nonce.
 */
interface SystemPersonalLocalOwner {
    suspend fun observe(scope: CalendarScope, event: EventId, owner: MutationId,
        admit: (CalendarScope) -> Boolean): PersonalLocalOwnerSnapshot?
    suspend fun retain(preparation: PersonalEditPreparation, admit: (CalendarScope) -> Boolean): PersonalEditPreparation?
    suspend fun qualify(preparation: PersonalEditPreparation, admit: (CalendarScope) -> Boolean): Boolean
    suspend fun submit(record: PersonalUpdateAttemptRecord, journal: PersonalUpdateRecoveryJournal,
        port: CalendarPersonalUpdatePort, admit: (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean): PersonalUpdateSubmissionObservation
    suspend fun acknowledge(result: PersonalUpdateVerifiedResult, journal: PersonalUpdateRecoveryJournal,
        admit: (CalendarScope) -> Boolean): Boolean
    suspend fun complete(result: PersonalUpdateVerifiedResult, journal: PersonalUpdateRecoveryJournal,
        admit: (CalendarScope) -> Boolean): Boolean
}
