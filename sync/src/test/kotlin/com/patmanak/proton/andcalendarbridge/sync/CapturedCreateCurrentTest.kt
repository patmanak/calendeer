// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CapturedCreateCurrentTest {
    private val scope = CalendarScope(AccountId("fixture"), CalendarId("calendar"), 0)
    private val content = EventContent("?", EventTime.AllDay(LocalDate.of(2030, 10, 1), LocalDate.of(2030, 10, 2)),
        details = EventDetails())
    private val intent = LocalCreateIntent(LocalCreateReservation(MutationId("capture"), LocalEventId(UUID(0, 1).toString()),
        scope, AdapterFormat("import", 1), Instant.parse("2030-10-01T12:00:00Z")), content)
    private val identity = CapturedCreateDispatchIdentity.of(LocalCreateSubmission(LocalCreateSubmissionReservation(intent,
        MutationId(UUID(0, 2).toString()), AdapterFormat("submission", 2)), "synthetic-payload"))
    private val change = RemoteChange.Upsert(EventId("remote"), Revision("verified"), content)
    private val receipt = CapturedCreateAttachmentReceipt(CapturedCreateBinding(identity, change.eventId, change.revision),
        UUID.randomUUID().toString())
    private val baseline = CapturedCreateBaseline(receipt, change)
    @Test fun unchangedOnlyDescribesCurrentLocalContent() {
        val current = CapturedCreateCurrent(baseline, content, false)
        assertTrue(current.unchanged); assertEquals(baseline, current.baseline)
        assertFalse(current.copy(content = content.copy(title = "Later local title")).unchanged)
        assertFalse(current.copy(deleted = true).unchanged)
    }
    @Test fun deletionPreservesContentEditedAfterSending() {
        val local = content.copy(description = "Local description before delete")
        val current = CapturedCreateCurrent(baseline, local, true)
        assertEquals(local, current.content); assertEquals(content, current.baseline.change.content)
    }
    @Test fun unknownOrNonemptyPersonalDataCannotBecomePartialCurrentContent() {
        for (local in listOf(content.copy(details = null), content.copy(details = EventDetails(reminders = listOf(EventReminder(15))))))
            assertThrows(IllegalArgumentException::class.java) { CapturedCreateCurrent(baseline, local, true) }
    }
    @Test fun recurrenceAndColorChangesRemainUnsupported() {
        for (local in listOf(content.copy(colorRgb = 0x123456), content.copy(recurrence = EventRecurrence("fixture-uid", rule = "FREQ=DAILY;COUNT=3"))))
            assertThrows(IllegalArgumentException::class.java) { CapturedCreateCurrent(baseline, local, false) }
    }
    @Test fun subsecondCurrentDatesCannotBeSilentlyRounded() {
        val start = Instant.parse("2030-10-01T12:00:00Z").plusNanos(1)
        val local = content.copy(time = EventTime.Timed(start, start.plusSeconds(3600), java.time.ZoneId.of("UTC")))
        assertThrows(IllegalArgumentException::class.java) { CapturedCreateCurrent(baseline, local, false) }
    }
    @Test fun malformedTextCannotBeStoredAsReplacementCharacters() {
        for (local in listOf(content.copy(title = "\uD800"), content.copy(description = "\uD800"), content.copy(location = "\uD800")))
            assertThrows(IllegalArgumentException::class.java) { CapturedCreateCurrent(baseline, local, false) }
    }
    @Test fun diagnosticsDoNotRevealLocalContentOrSourceIdentities() {
        val current = CapturedCreateCurrent(baseline, content.copy(title = "Private fixture text"), false)
        assertEquals("CapturedCreateCurrent(REDACTED)", current.toString())
        assertEquals("CapturedCreateCurrentObservation.Supported(REDACTED)", CapturedCreateCurrentObservation.Supported(current).toString())
    }
}
