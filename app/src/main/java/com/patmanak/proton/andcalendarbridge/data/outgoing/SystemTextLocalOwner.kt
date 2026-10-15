// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.sync.*

/** Disjoint discovery profile: title-only work never borrows text consent. */
object SystemTextEditProfile {
    fun accepts(intent: LocalEditIntent): Boolean = try {
        val frozen = LocalTextEditPreparation.freezeIntent(intent)
        frozen.base.content.details == EventDetails() && frozen.base.content.colorRgb == null &&
            frozen.base.content.recurrence == null &&
            (canonical(frozen.base.content.description) != canonical(frozen.content.description) ||
                canonical(frozen.base.content.location) != canonical(frozen.content.location))
    } catch (_: Exception) { false }
    private fun canonical(value: String) = value.replace("\r\n", "\n")
}

/** Historical source snapshot only. Current ownership must be observed again before any claim. */
class TextLocalOwnerSnapshot(intent: LocalEditIntent, preparation: LocalTextEditPreparation?) {
    val intent = LocalTextEditPreparation.freezeIntent(intent)
    val preparation = preparation?.let { LocalTextEditPreparation(it.intent, it.timestamp, it.format, it.protectedPayload) }
    init { require(this.preparation == null || this.preparation.intent == this.intent) }
    override fun toString() = "TextLocalOwnerSnapshot(redacted)"
}

/** Local-only owner/Provider boundary. Implementations serialize with incoming projection and
 * qualify account, generation, selection, native contents and explicit CREATE provenance.
 * Local methods never enter an authenticated facade. Submission acquires the local
 * coordinator lock before the supplied port, whose source callback stays inside that
 * same lock without reacquiring it. This keeps the import-before-auth lock order.
 * Receipt reservation precedes a unique native CAS; historical receipts inspect only.
 */
interface SystemTextLocalOwner {
    suspend fun observe(scope: CalendarScope, event: EventId, owner: MutationId,
        admit: (CalendarScope) -> Boolean): TextLocalOwnerSnapshot?
    suspend fun retain(preparation: LocalTextEditPreparation, admit: (CalendarScope) -> Boolean): LocalTextEditPreparation?
    suspend fun qualify(preparation: LocalTextEditPreparation, admit: (CalendarScope) -> Boolean): Boolean
    suspend fun submit(record: TextUpdateAttemptRecord, journal: TextUpdateRecoveryJournal,
        port: CalendarTextUpdateSubmissionPort, admit: (CalendarScope) -> Boolean,
        admitEvidence: (CalendarScope) -> Boolean): TextUpdateSubmissionObservation
    suspend fun acknowledge(result: TextUpdateVerifiedResult, journal: TextUpdateRecoveryJournal,
        admit: (CalendarScope) -> Boolean): Boolean
    suspend fun complete(result: TextUpdateVerifiedResult, journal: TextUpdateRecoveryJournal,
        admit: (CalendarScope) -> Boolean): Boolean
}
