// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LocalCreateIntentTest {
    private val reservation = LocalCreateReservation(MutationId("fixture-create"),
        LocalEventId("f5268f5b-7465-46af-87dc-50fcc142f133"),
        CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 0),
        AdapterFormat("fixture", 1), Instant.parse("2030-10-01T00:00:00Z"))
    private val content = EventContent("Local fixture", EventTime.AllDay(
        LocalDate.of(2030, 10, 1), LocalDate.of(2030, 10, 2)), details = EventDetails())
    private fun refused(block: () -> Unit) {
        try { block(); fail("Unsupported value accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun identityRequiresCanonicalUuidAndIsSeparateFromTheMutation() {
        refused { LocalEventId("fixture-row-1") }
        refused { LocalEventId(reservation.localId.value.uppercase()) }
        refused { LocalEventId("1-1-1-1-1") }
        assertEquals(reservation.localId, LocalEventId(reservation.localId.value))
        assertNotEquals(reservation.id.value, reservation.localId.value)
    }
    @Test fun reservationFreezesSecondPrecisionAndMarkerProtocol() {
        refused { reservation.copy(timestamp = reservation.timestamp.plusNanos(1)) }
        refused { reservation.copy(markerProtocol = 2) }
        assertEquals(reservation, reservation.copy())
    }
    @Test fun newlyCreatedEmptyDetailsMustBeKnownRatherThanInferredRemoteUnknown() {
        assertEquals(content, LocalCreateIntent(reservation, content).content)
        refused { LocalCreateIntent(reservation, content.copy(details = null)) }
        refused { LocalCreateIntent(reservation, content.copy(colorRgb = 0x112233)) }
    }
    @Test fun timedSnapshotRetainsTimezoneAndRefusesFractionalSeconds() {
        val time = EventTime.Timed(Instant.parse("2030-10-27T00:30:00Z"),
            Instant.parse("2030-10-27T02:30:00Z"), ZoneId.of("Europe/Paris"))
        assertEquals(time, LocalCreateIntent(reservation, content.copy(time = time)).content.time)
        refused { LocalCreateIntent(reservation, content.copy(time = time.copy(start = time.start.plusNanos(1)))) }
        refused { LocalCreateIntent(reservation, content.copy(time = time.copy(end = time.end.plusNanos(1)))) }
    }
    @Test fun recurrenceAndNonemptyDetailsCannotBecomePartialCreates() {
        refused { LocalCreateIntent(reservation, content.copy(recurrence = EventRecurrence("fixture-uid", "FREQ=DAILY"))) }
        refused { LocalCreateIntent(reservation, content.copy(details = EventDetails(reminders = listOf(EventReminder(15))))) }
    }
    @Test fun allDayExclusiveEndAndOriginalTextStayIntact() {
        val text = content.copy(title = "Fête 🦌", description = "Line one\nLine two", location = "Meeting room")
        assertEquals(text, LocalCreateIntent(reservation, text).content)
        assertEquals(LocalDate.of(2030, 10, 2), (text.time as EventTime.AllDay).endExclusive)
    }
    @Test fun diagnosticsDoNotRevealIdentityScopeOrContent() {
        val diagnostics = listOf(reservation.localId, reservation, LocalCreateIntent(reservation, content)).joinToString()
        for (privateValue in listOf(reservation.localId.value, reservation.id.value,
            reservation.scope.account.value, reservation.scope.calendar.value, content.title))
            assertFalse(diagnostics.contains(privateValue))
    }
    @Test fun protectedPreparationBindsTheCapturedIntentAndRejectsEmptyPayload() {
        val intent = LocalCreateIntent(reservation, content)
        val prepared = LocalCreatePreparation(intent, AdapterFormat("fixture-local-preparation", 1), "protected-fixture")
        assertEquals(intent, prepared.intent)
        refused { prepared.copy(protectedPayload = " ") }
        assertFalse(prepared.toString().contains(prepared.protectedPayload))
        assertNotEquals(prepared, prepared.copy(protectedPayload = "different-protected-fixture"))
    }
}
