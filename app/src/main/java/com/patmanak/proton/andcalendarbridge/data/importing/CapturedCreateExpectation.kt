// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.data.calendar.CalendarProjection
import com.patmanak.proton.andcalendarbridge.data.calendar.OwnedLocalEditSnapshot
import com.patmanak.proton.andcalendarbridge.sync.*

/** Expectation only, never remote proof. Its caller must requalify the exact stored binding/source.
 * Simple historical content has no adapter-owned UID. Series obtain it only from their sealed request. */
internal suspend fun capturedCreateExpectation(submission: LocalCreateSubmission, port: CalendarCreateSubmissionPort?): EventContent? {
    val reserved = submission.reservation
    if (reserved.intent.series == null) return reserved.intent.content
    val record = CreateAttemptRecord(reserved.attempt, reserved.intent.reservation.scope, reserved.format, submission.protectedPayload)
    val draft = reserved.draft.frozen()
    val opened = port?.readCapturedExpectation(record, draft) ?: return null
    val uid = opened.recurrence?.uid ?: return null
    val canonical = draft.canonicalContent(uid)
    return canonical.takeIf { it == opened }
}

internal suspend fun CalendarProjection.inspectCapturedIntent(intent: LocalCreateIntent): OwnedLocalEditSnapshot? =
    if (intent.series == null) inspectStampedSimpleCreate(intent.reservation.scope, intent.reservation.localId)
    else inspectStampedSeriesCreate(intent.reservation.scope, intent.reservation.localId)

internal fun OwnedLocalEditSnapshot?.matches(intent: LocalCreateIntent): Boolean =
    this != null && content == intent.content && series == intent.series
