// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.security.MessageDigest

/** Exact immutable work reference. The fingerprint identifies ciphertext, not plaintext equivalence. */
data class CreateAttemptIdentity(val scope: CalendarScope, val id: MutationId, val format: AdapterFormat,
    val payloadFingerprint: String) {
    init { require(payloadFingerprint.matches(Regex("[a-f0-9]{64}"))) }
    override fun toString() = "CreateAttemptIdentity(redacted)"
    companion object {
        fun of(record: CreateAttemptRecord) = CreateAttemptIdentity(record.scope, record.id, record.format,
            MessageDigest.getInstance("SHA-256").digest(record.protectedPayload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) })
    }
}

/** Historical authenticated observation, distinct from the latest imported opaque revision. */
data class CreatedEventBinding(val attempt: CreateAttemptIdentity, val eventId: EventId, val verifiedRevision: Revision) {
    override fun toString() = "CreatedEventBinding(redacted)"
}

/** Persist proof only for exact admitted UNKNOWN work, atomically with its validation.
 * One attempt/event association is immutable, including its first verified revision.
 * Same proof is idempotent; substituted work or a different event/revision must fail.
 * A binding never changes admission, requeues a request, or acknowledges a Provider intent.
 */
interface CreateAssociationJournal : CreateAttemptJournal {
    suspend fun bind(expectedUnknown: CreateAttemptRecord, observation: CreateSubmissionObservation.Verified): CreatedEventBinding
    suspend fun binding(identity: CreateAttemptIdentity): CreatedEventBinding?
}
