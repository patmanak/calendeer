// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** First authenticated remote identity for immutable captured UNKNOWN work. No Provider ACK. */
data class CapturedCreateBinding(val identity: CapturedCreateDispatchIdentity,
    val eventId: EventId, val verifiedRevision: Revision) {
    override fun toString() = "CapturedCreateBinding(REDACTED)"
}

/** Compares an already authenticated readback against the sealed work and its distinct detached slot. */
fun CapturedCreateDispatchIdentity.matchesOccurrenceObservation(intent: LocalCreateIntent,
    observed: CreateSubmissionObservation.Verified): Boolean {
    val occurrence = intent.occurrence ?: return false
    return observed.attempt == attempt && matchesOccurrence(intent) &&
        observed.event.eventId != occurrence.master.eventId && observed.event.content == occurrence.content
}

/** Unresolved captured sends hold a whole scope until their remote identities are known.
 * Bound events remain held until original-row reconciliation is separately qualified.
 * A hold never consumes incoming work, alters its content, or grants dispatch permission.
 */
data class CapturedProjectionHold(val scopeBlocked: Boolean = false, val events: Set<EventId> = emptySet(),
    val occurrenceMasters: Set<EventId> = emptySet()) {
    val blocksProjection: Boolean get() = scopeBlocked || events.isNotEmpty() || occurrenceMasters.isNotEmpty()
    fun holds(event: EventId): Boolean = scopeBlocked || event in events || event in occurrenceMasters
    override fun toString() = "CapturedProjectionHold(REDACTED)"
}
