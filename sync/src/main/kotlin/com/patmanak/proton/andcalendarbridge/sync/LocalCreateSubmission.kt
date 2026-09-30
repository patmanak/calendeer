// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.util.UUID

/** First outgoing identity for one exact captured intention. Not dispatch admission.
 * The separate UUID drives the adapter's UID; captured timestamps/content remain unchanged.
 * A reservation without a retained payload cannot authorize preparation again after reopening.
 */
data class LocalCreateSubmissionReservation(
    val intent: LocalCreateIntent,
    val attempt: MutationId,
    val format: AdapterFormat,
) {
    init {
        require(UUID.fromString(attempt.value).toString() == attempt.value)
        require(attempt != intent.reservation.id && attempt.value != intent.reservation.localId.value)
    }
    val draft: CalendarCreateDraft get() = CalendarCreateDraft(intent.reservation.scope, attempt,
        intent.reservation.timestamp, intent.content)
    override fun toString() = "LocalCreateSubmissionReservation(REDACTED)"
}

/** A separately prepared, immutable opaque submission. Still paused, with no dispatch record. */
data class LocalCreateSubmission(val reservation: LocalCreateSubmissionReservation, val protectedPayload: String) {
    init { require(protectedPayload.isNotBlank()) }
    override fun toString() = "LocalCreateSubmission(REDACTED)"
}
