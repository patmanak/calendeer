// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class OccurrenceDispatchIdentityTest {
    private val scope = CalendarScope(AccountId("account"), CalendarId("calendar"), 0)
    private val reservation = LocalCreateReservation(MutationId("capture"), LocalEventId("11111111-2222-3333-4444-555555555555"),
        scope, AdapterFormat("fixture", 1), Instant.parse("2030-04-01T12:00:00Z"))
    private val master = RemoteChange.Upsert(EventId("master"), Revision("revision"), EventContent("Master",
        EventTime.AllDay(LocalDate.of(2030, 4, 1), LocalDate.of(2030, 4, 2)), details = EventDetails(),
        recurrence = EventRecurrence("master@calendeer.invalid", "FREQ=DAILY;COUNT=5")))
    private val content = master.content.copy(title = "Occurrence", recurrence = null)
    private val candidate = OccurrenceEditCandidate(master, RecurrencePoint.AllDay(LocalDate.of(2030, 4, 2)), content)
    private val intent = LocalCreateIntent(reservation, content, occurrence = candidate)
    private val record = CreateAttemptRecord(MutationId("request"), scope, AdapterFormat("occurrence-fixture", 5), "sealed")

    @Test fun explicitVersionThreeBindsCompleteContextAndRefusesBareContentMatching() {
        val identity = CapturedCreateDispatchIdentity.ofOccurrence(record, intent)
        assertEquals(3, identity.fingerprintVersion)
        // Independent length-prefixed UTF-8 SHA256 fixture; v1/v2 nested digests remain frozen.
        assertEquals("6b080c3d0d37baaa3a4490910edca5c92bb68b184a17fce298729708980df1e4", identity.contentFingerprint)
        assertTrue(identity.matchesOccurrence(intent)); assertFalse(identity.matchesContent(content))
        assertFalse(identity.matchesContent(candidate.content))
        assertEquals(identity, CapturedCreateDispatchIdentity.ofOccurrence(record, intent.copy()))
    }
    @Test fun masterRevisionUidRuleSlotAndContentCannotReuseTheFingerprint() {
        val expected = CapturedCreateDispatchIdentity.ofOccurrence(record, intent)
        val variants = listOf(
            OccurrenceEditCandidate(master.copy(revision = Revision("different")), candidate.original, content),
            OccurrenceEditCandidate(master.copy(eventId = EventId("other")), candidate.original, content),
            OccurrenceEditCandidate(master.copy(content = master.content.copy(recurrence = master.content.recurrence!!.copy(uid = "other@calendeer.invalid"))), candidate.original, content),
            OccurrenceEditCandidate(master.copy(content = master.content.copy(recurrence = master.content.recurrence!!.copy(rule = "FREQ=DAILY;COUNT=6"))), candidate.original, content),
            OccurrenceEditCandidate(master, RecurrencePoint.AllDay(LocalDate.of(2030, 4, 3)), content),
            OccurrenceEditCandidate(master, candidate.original, content.copy(title = "Newer")))
        for (changed in variants) {
            val other = intent.copy(content = changed.content.copy(recurrence = null), occurrence = changed)
            assertFalse(expected.matchesOccurrence(other))
            assertNotEquals(expected.contentFingerprint, CapturedCreateDispatchIdentity.ofOccurrence(record, other).contentFingerprint)
        }
    }
    @Test fun scopeCaptureIdentityAndTimestampRemainBound() {
        val expected = CapturedCreateDispatchIdentity.ofOccurrence(record, intent)
        for (changed in listOf(reservation.copy(id = MutationId("other-capture")),
            reservation.copy(localId = LocalEventId("22222222-2222-3333-4444-555555555555")),
            reservation.copy(timestamp = reservation.timestamp.plusSeconds(1)), reservation.copy(format = AdapterFormat("other", 1)))) {
            assertFalse(expected.matchesOccurrence(intent.copy(reservation = changed)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CapturedCreateDispatchIdentity.ofOccurrence(record.copy(scope = scope.copy(generation = 1)), intent)
        }
    }
    @Test fun oldFactoriesAndBaselineConsumersCannotAdoptAnOccurrence() {
        assertThrows(IllegalArgumentException::class.java) { CapturedCreateDispatchIdentity.of(record, intent) }
        val identity = CapturedCreateDispatchIdentity.ofOccurrence(record, intent)
        val binding = CapturedCreateBinding(identity, EventId("child"), Revision("verified"))
        val receipt = CapturedCreateAttachmentReceipt(binding, "00000000-0000-0000-0000-000000000001")
        assertThrows(IllegalArgumentException::class.java) {
            CapturedCreateBaseline(receipt, RemoteChange.Upsert(binding.eventId, binding.verifiedRevision, candidate.content))
        }
    }
    @Test fun timedOriginalSlotKeepsItsInstantAcrossDstAndBindsBothZonesAndTheMovedChild() {
        val time = EventTime.Timed(Instant.parse("2030-03-30T08:00:00Z"), Instant.parse("2030-03-30T09:00:00Z"),
            ZoneId.of("Europe/Paris"))
        val timedMaster = master.copy(content = master.content.copy(time = time))
        // 09:00 local on the next day is 07:00Z after the daylight-saving transition.
        val original = RecurrencePoint.Timed(Instant.parse("2030-03-31T07:00:00Z"))
        val moved = content.copy(time = EventTime.Timed(Instant.parse("2030-03-31T10:00:00Z"),
            Instant.parse("2030-03-31T11:00:00Z"), ZoneId.of("Europe/Paris")))
        val timed = intent.copy(content = moved, occurrence = OccurrenceEditCandidate(timedMaster, original, moved))
        val identity = CapturedCreateDispatchIdentity.ofOccurrence(record, timed)
        assertTrue(identity.matchesOccurrence(timed.copy()))
        for (variant in listOf(
            OccurrenceEditCandidate(timedMaster, RecurrencePoint.Timed(Instant.parse("2030-04-01T07:00:00Z")), moved),
            OccurrenceEditCandidate(timedMaster.copy(content = timedMaster.content.copy(time = time.copy(zone = ZoneId.of("Europe/Brussels")))), original, moved),
            OccurrenceEditCandidate(timedMaster, original, moved.copy(time = (moved.time as EventTime.Timed).copy(
                start = Instant.parse("2030-03-31T10:30:00Z")))),
            OccurrenceEditCandidate(timedMaster, original, moved.copy(time = (moved.time as EventTime.Timed).copy(zone = ZoneId.of("UTC")))))) {
            val changed = timed.copy(content = variant.content.copy(recurrence = null), occurrence = variant)
            assertFalse(identity.matchesOccurrence(changed))
            assertNotEquals(identity.contentFingerprint, CapturedCreateDispatchIdentity.ofOccurrence(record, changed).contentFingerprint)
        }
    }
}
