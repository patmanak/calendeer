// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CapturedCreateBindingTest {
    @Test fun holdsOnlyTheCapturedEventUnlessTheScopeIsUnresolved() {
        val event = EventId("captured")
        assertFalse(CapturedProjectionHold().blocksProjection)
        assertFalse(CapturedProjectionHold().holds(event))
        assertTrue(CapturedProjectionHold(scopeBlocked = true).holds(EventId("unrelated")))
        val hold = CapturedProjectionHold(events = setOf(event))
        assertTrue(hold.blocksProjection); assertTrue(hold.holds(event))
        assertFalse(hold.holds(EventId("unrelated")))
    }
    @Test fun bindingRetainsExactCaptureIdentityAndFirstRemoteObservation() {
        val identity = identity()
        val binding = CapturedCreateBinding(identity, EventId("observed"), Revision("first"))
        assertEquals(identity, binding.identity)
        assertNotEquals(binding, binding.copy(verifiedRevision = Revision("later")))
        assertNotEquals(binding, binding.copy(eventId = EventId("another")))
    }
    @Test fun diagnosticsContainNoIdsFingerprintsOrRevisions() {
        val identity = identity()
        val binding = CapturedCreateBinding(identity, EventId("private-remote"), Revision("private-revision"))
        assertEquals("CapturedCreateBinding(REDACTED)", binding.toString())
        assertEquals("CapturedProjectionHold(REDACTED)", CapturedProjectionHold(events = setOf(binding.eventId)).toString())
    }
    private fun identity(): CapturedCreateDispatchIdentity {
        val scope = CalendarScope(AccountId("synthetic"), CalendarId("fixture"), 0)
        val intent = LocalCreateIntent(LocalCreateReservation(MutationId("capture"), LocalEventId(UUID.randomUUID().toString()),
            scope, AdapterFormat("import", 1), Instant.parse("2030-10-01T12:00:00Z")),
            EventContent("Fixture", EventTime.AllDay(LocalDate.of(2030, 10, 1), LocalDate.of(2030, 10, 2)), details = EventDetails()))
        return CapturedCreateDispatchIdentity.of(LocalCreateSubmission(LocalCreateSubmissionReservation(intent,
            MutationId(UUID.randomUUID().toString()), AdapterFormat("submission", 2)), "synthetic-sealed-payload"))
    }
}
