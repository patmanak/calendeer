// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class OccurrenceSubmissionContractTest {
    private val scope = CalendarScope(AccountId("fixture"), CalendarId("calendar"), 0)
    private val master = RemoteChange.Upsert(EventId("master"), Revision("revision"), EventContent("Master",
        EventTime.AllDay(LocalDate.parse("2030-04-01"), LocalDate.parse("2030-04-02")), details = EventDetails(),
        recurrence = EventRecurrence("master@calendeer.invalid", "FREQ=DAILY;COUNT=5")))
    private val child = master.content.copy(title = "Edited", recurrence = null)
    private val intent = LocalCreateIntent(LocalCreateReservation(MutationId("capture"),
        LocalEventId("11111111-2222-3333-4444-555555555555"), scope, AdapterFormat("capture", 1),
        Instant.parse("2030-04-01T12:00:00Z")), child,
        occurrence = OccurrenceEditCandidate(master, RecurrencePoint.AllDay(LocalDate.parse("2030-04-02")), child))
    private val attempt = MutationId("22222222-3333-4444-5555-666666666666")
    private val format = AdapterFormat("submission", 5)
    @Test fun explicitReservationKeepsWholeCaptureAndCanonicalMasterUidInItsDraft() {
        val reserved = LocalCreateSubmissionReservation.forOccurrence(intent, attempt, format)
        assertEquals(intent, reserved.draft.occurrence)
        assertEquals(intent, reserved.draft.frozen().occurrence)
        assertEquals(intent.occurrence!!.content, reserved.draft.canonicalContent("master@calendeer.invalid"))
        assertThrows(IllegalArgumentException::class.java) { reserved.draft.canonicalContent("foreign") }
        val submission = LocalCreateSubmission(reserved, "sealed")
        assertEquals(3, CapturedCreateDispatchIdentity.of(submission).fingerprintVersion)
        assertTrue(CapturedCreateDispatchIdentity.of(submission).matchesOccurrence(intent))
    }
    @Test fun historicalConstructorAndPreparationStillRefuseAnOccurrence() {
        assertThrows(IllegalArgumentException::class.java) { LocalCreateSubmissionReservation(intent, attempt, format) }
        assertThrows(IllegalArgumentException::class.java) { LocalCreatePreparation(intent, format, "sealed") }
        assertThrows(IllegalArgumentException::class.java) {
            LocalCreateSubmissionReservation.forOccurrence(intent.copy(occurrence = null), attempt, format)
        }
    }
    @Test fun draftCannotDetachContentScopeOrTimestampFromItsCapture() {
        val draft = CalendarCreateDraft(scope, attempt, intent.reservation.timestamp, child, occurrence = intent)
        assertThrows(IllegalArgumentException::class.java) { draft.copy(content = child.copy(title = "Newer")) }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(scope = scope.copy(generation = 1)) }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(timestamp = draft.timestamp.plusSeconds(1)) }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(series = CreateSeriesRule(5)) }
        assertThrows(IllegalArgumentException::class.java) { draft.copy(occurrence = intent.copy(occurrence = null)) }
    }
    @Test fun dedicatedStableCapabilityCannotDowngradeIntoAHistoricalFormat() {
        fun port(occurrenceFormat: AdapterFormat?) = object : CalendarCreateSubmissionPort {
            override val knownEmptySubmissionFormat = AdapterFormat("submission", 2)
            override val personalSubmissionFormat = AdapterFormat("submission", 3)
            override val seriesSubmissionFormat = AdapterFormat("submission", 4)
            override val occurrenceSubmissionFormat = occurrenceFormat
            override suspend fun prepareSubmission(draft: CalendarCreateDraft): PreparedCalendarCreate = error("No IO")
            override suspend fun submit(record: CreateAttemptRecord, journal: CreateAttemptJournal) = CreateSubmissionObservation.Unknown
            override suspend fun inspect(record: CreateAttemptRecord) = CreateSubmissionObservation.Unknown
        }
        assertEquals(format, port(format).capturedSubmissionFormat(intent))
        assertTrue(port(format).supportsCapturedSubmission(format))
        assertNull(port(null).capturedSubmissionFormat(intent))
        for (version in 2..4) assertNull(port(AdapterFormat("submission", version)).capturedSubmissionFormat(intent))
        assertEquals(AdapterFormat("submission", 2), port(format).capturedSubmissionFormat(intent.copy(occurrence = null)))
    }
}
