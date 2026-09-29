// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test

class CreatedEventBindingTest {
    private val record = CreateAttemptRecord(MutationId("synthetic-mutation"),
        CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 0),
        AdapterFormat("synthetic-create", 1), "synthetic-sealed-payload", CreateAttemptState.OUTCOME_UNKNOWN)
    @Test fun exactWorkIdentityIsStableAndDoesNotDependOnAdmissionOrTransportEvidence() {
        val identity = CreateAttemptIdentity.of(record)
        assertEquals(identity, CreateAttemptIdentity.of(record.copy(protectedEvidence = "synthetic-sealed-response")))
        assertEquals(identity, CreateAttemptIdentity.of(record.copy(state = CreateAttemptState.PREPARED)))
        assertNotEquals(identity, CreateAttemptIdentity.of(record.copy(scope = record.scope.copy(generation = 1))))
        assertNotEquals(identity, CreateAttemptIdentity.of(record.copy(id = MutationId("other"))))
        assertNotEquals(identity, CreateAttemptIdentity.of(record.copy(format = record.format.copy(version = 2))))
        assertNotEquals(identity, CreateAttemptIdentity.of(record.copy(protectedPayload = "other-sealed-payload")))
    }
    @Test fun diagnosticsHideScopedIdentitiesPayloadAndEvent() {
        val binding = CreatedEventBinding(CreateAttemptIdentity.of(record), EventId("synthetic-event"), Revision("synthetic-revision"))
        assertFalse(binding.toString().contains("synthetic"))
        assertFalse(binding.attempt.toString().contains("synthetic"))
    }
}
