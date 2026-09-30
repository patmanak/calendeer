// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class LocalCreateSubmissionTest {
    private val intent = LocalCreateIntent(LocalCreateReservation(MutationId("capture"),
        LocalEventId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
        CalendarScope(AccountId("fixture"), CalendarId("calendar"), 2), AdapterFormat("import", 1),
        Instant.parse("2030-01-01T00:00:00Z")), EventContent("Synthetic creation", EventTime.AllDay(
        LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 2)), details = EventDetails()))
    private val reservation = LocalCreateSubmissionReservation(intent,
        MutationId("11111111-2222-3333-4444-555555555555"), AdapterFormat("submission", 2))
    private fun refused(block: () -> Unit) {
        try { block(); fail("Invalid staging accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun draftUsesTheNewCorrelationAndExactCapturedTimestampAndContent() {
        assertEquals(CalendarCreateDraft(intent.reservation.scope, reservation.attempt,
            intent.reservation.timestamp, intent.content), reservation.draft)
        assertNotEquals(intent.reservation.id, reservation.draft.correlation)
    }
    @Test fun canonicalAttemptIsDistinctFromBothCapturedIdentities() {
        refused { reservation.copy(attempt = MutationId("capture")) }
        refused { reservation.copy(attempt = MutationId(intent.reservation.localId.value)) }
        refused { reservation.copy(attempt = MutationId("1-2-3-4-5")) }
        val reusedCapture = intent.copy(reservation = intent.reservation.copy(id = reservation.attempt))
        refused { reservation.copy(intent = reusedCapture) }
    }
    @Test fun opaquePayloadIsNonblankAndDiagnosticsRedactContentAndIdentity() {
        refused { LocalCreateSubmission(reservation, " ") }
        val staged = LocalCreateSubmission(reservation, "synthetic-protected-content")
        val diagnostic = listOf(reservation, staged).joinToString()
        for (privateValue in listOf(intent.content.title, reservation.attempt.value, staged.protectedPayload))
            assertFalse(diagnostic.contains(privateValue))
    }
}
