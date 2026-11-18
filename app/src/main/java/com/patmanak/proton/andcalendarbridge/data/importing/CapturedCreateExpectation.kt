// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.OwnedLocalEditSnapshot
import com.patmanak.proton.andcalendarbridge.sync.*

/** Expectation only, never remote proof. Its caller must requalify the exact stored binding/source.
 * Simple historical content has no adapter-owned UID. Series obtain it only from their sealed request. */
internal suspend fun capturedCreateExpectation(submission: LocalCreateSubmission, port: CalendarCreateSubmissionPort?): EventContent? {
    val reserved = submission.reservation
    if (reserved.isOccurrence) {
        val record = CreateAttemptRecord(reserved.attempt, reserved.intent.reservation.scope, reserved.format, submission.protectedPayload)
        return port?.readCapturedExpectation(record, reserved.draft)?.takeIf { it == reserved.intent.occurrence!!.content }
    }
    if (reserved.intent.series == null) return reserved.intent.content
    val record = CreateAttemptRecord(reserved.attempt, reserved.intent.reservation.scope, reserved.format, submission.protectedPayload)
    val draft = reserved.draft.frozen()
    val opened = port?.readCapturedExpectation(record, draft) ?: return null
    val uid = opened.recurrence?.uid ?: return null
    val canonical = draft.canonicalContent(uid)
    return canonical.takeIf { it == opened }
}

internal suspend fun CalendarProjection.inspectCapturedIntent(intent: LocalCreateIntent): OwnedLocalEditSnapshot? =
    if (intent.occurrence != null) null
    else if (intent.series == null) inspectStampedSimpleCreate(intent.reservation.scope, intent.reservation.localId)
    else inspectStampedSeriesCreate(intent.reservation.scope, intent.reservation.localId)

internal fun OwnedLocalEditSnapshot?.matches(intent: LocalCreateIntent): Boolean =
    intent.occurrence == null && this != null && content == intent.content && series == intent.series

internal suspend fun CalendarProjection.matchesCapturedIntent(intent: LocalCreateIntent): Boolean =
    if (intent.occurrence == null) inspectCapturedIntent(intent).matches(intent)
    else inspectStampedOccurrence(intent)?.candidate == intent.occurrence

internal suspend fun ImportStore.matchesOccurrenceSource(intent: LocalCreateIntent,
    master: RemoteChange.Upsert? = intent.occurrence?.master, pendingMaster: Boolean = false): Boolean {
    if (master == null) return intent.occurrence == null
    val scope = intent.reservation.scope
    return projectedBaseline(scope, master.eventId) == master && localEdit(scope, master.eventId) == null &&
        transaction(scope) {
            val staged = this as? StagedSyncTransaction
            val observed = staged?.remote(master.eventId)
            staged != null && observed != null && (observed == master || pendingMaster && compatibleOccurrenceMaster(master, observed)) &&
                staged.deferred(master.eventId) == null && !hasPendingMutation(master.eventId)
        }
}
