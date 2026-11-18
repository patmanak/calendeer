// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class SerializedOccurrenceQualificationTest {
    @Test fun otherAttemptsSlotsAndGenericWorkCannotEvenOpenTheScopedRuntime() = runBlocking {
        val scope = CalendarScope(AccountId("fixture"), CalendarId("fixture"), 0)
        val master = RemoteChange.Upsert(EventId("master"), Revision("v1"), EventContent("Synthetic series",
            EventTime.AllDay(LocalDate.of(2030, 4, 1), LocalDate.of(2030, 4, 2)), details = EventDetails(),
            recurrence = EventRecurrence("master@calendeer.invalid", "FREQ=DAILY;COUNT=4")))
        val flat = master.content.copy(title = "Synthetic exception", recurrence = null)
        val reservation = LocalCreateReservation(MutationId("capture"), LocalEventId("22222222-2222-4222-8222-222222222222"),
            scope, AdapterFormat("import-fixture", 1), Instant.EPOCH)
        fun candidate(day: Int) = LocalCreateIntent(reservation, flat, occurrence = OccurrenceEditCandidate(master,
            RecurrencePoint.AllDay(LocalDate.of(2030, 4, day)), flat))
        val intent = candidate(2)
        val attempt = MutationId("11111111-1111-4111-8111-111111111111")
        val draft = LocalCreateSubmissionReservation.forOccurrence(intent, attempt, OCCURRENCE_CREATE_SUBMISSION_FORMAT).draft
        val record = CreateAttemptRecord(attempt, scope, OCCURRENCE_CREATE_SUBMISSION_FORMAT, "sealed")
        var opens = 0
        val qualification = SerializedOccurrenceQualification(Dispatchers.Unconfined, Mutex(), { true }, { opens++; null })
        val port = qualification.forAttempt(intent, attempt, "a".repeat(64))
        val other = LocalCreateSubmissionReservation.forOccurrence(candidate(3), attempt, OCCURRENCE_CREATE_SUBMISSION_FORMAT).draft
        assertThrows(IllegalArgumentException::class.java) { runBlocking { port.prepareSubmission(other) } }
        assertSame(CreateSubmissionObservation.Unknown, port.inspectCaptured(record, other))
        assertNull(port.readCapturedExpectation(record.copy(id = MutationId("wrong")), draft))
        assertSame(CreateSubmissionObservation.Unknown, port.inspect(record))
        assertEquals(0, opens)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { port.prepareSubmission(draft) } }
        assertEquals(1, opens)
    }
}
