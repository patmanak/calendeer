// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class OccurrenceCreateIntentTest {
    private val master = RemoteChange.Upsert(EventId("master"), Revision("revision"), EventContent("Master",
        EventTime.AllDay(LocalDate.of(2030, 4, 1), LocalDate.of(2030, 4, 2)), details = EventDetails(),
        recurrence = EventRecurrence("fixture@calendeer.invalid", "FREQ=DAILY;COUNT=5")))
    private val slot = RecurrencePoint.AllDay(LocalDate.of(2030, 4, 2))
    private val flat = master.content.copy(title = "Phone occurrence", recurrence = null)
    private val candidate = OccurrenceEditCandidate(master, slot, flat)
    private val reservation = LocalCreateReservation(MutationId("capture"), LocalEventId("11111111-2222-3333-4444-555555555555"),
        CalendarScope(AccountId("account"), CalendarId("calendar"), 0), AdapterFormat("fixture", 1), Instant.parse("2030-04-01T12:00:00Z"))
    private val intent = LocalCreateIntent(reservation, flat, occurrence = candidate)

    @Test fun equalityIncludesTheCompleteOccurrenceContext() {
        val copy = intent.copy(occurrence = OccurrenceEditCandidate(master.copy(), slot, flat.copy()))
        assertEquals(intent, copy); assertEquals(intent.hashCode(), copy.hashCode())
        assertNotEquals(intent, intent.copy(occurrence = OccurrenceEditCandidate(master.copy(revision = Revision("changed")), slot, flat)))
        assertNotEquals(intent, intent.copy(occurrence = OccurrenceEditCandidate(master, RecurrencePoint.AllDay(LocalDate.of(2030, 4, 3)), flat)))
    }
    @Test fun contradictoryContentAndMixedSeriesProfilesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { intent.copy(content = flat.copy(title = "Different")) }
        assertThrows(IllegalArgumentException::class.java) { intent.copy(series = CreateSeriesRule(5)) }
        assertThrows(IllegalArgumentException::class.java) { LocalCreateIntent(reservation, candidate.content, occurrence = candidate) }
    }
    @Test fun oldPreparationAndSubmissionProfilesRemainClosed() {
        assertThrows(IllegalArgumentException::class.java) { LocalCreatePreparation(intent, AdapterFormat("old", 3), "sealed") }
        assertThrows(IllegalArgumentException::class.java) {
            LocalCreateSubmissionReservation(intent, MutationId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), AdapterFormat("old", 4))
        }
    }
    @Test fun historicalFingerprintDoesNotOmitTheNewContext() {
        val work = CreateAttemptRecord(MutationId("work"), reservation.scope, AdapterFormat("old", 4), "sealed")
        assertThrows(IllegalArgumentException::class.java) { CapturedCreateDispatchIdentity.of(work, intent) }
        val legacy = LocalCreateIntent(reservation, flat)
        assertEquals(1, CapturedCreateDispatchIdentity.of(work, legacy).fingerprintVersion)
        assertEquals(legacy, legacy.copy()); assertEquals(legacy.hashCode(), legacy.copy().hashCode())
    }
}
