// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CapturedSeriesIdentityTest {
    private val scope = CalendarScope(AccountId("fixture"), CalendarId("calendar"), 0)
    private val content = EventContent("Fixture 🦌", EventTime.AllDay(LocalDate.parse("2030-10-02"),
        LocalDate.parse("2030-10-03")), "Line one\nLine two", "Room", details = EventDetails())
    private val record = CreateAttemptRecord(MutationId(UUID(0, 2).toString()), scope,
        AdapterFormat("submission", 4), "synthetic-protected-payload")
    private val intent = LocalCreateIntent(LocalCreateReservation(MutationId("capture"), LocalEventId(UUID(0, 1).toString()),
        scope, AdapterFormat("capture", 1), Instant.parse("2030-01-01T00:00:00Z")), content, CreateSeriesRule(5))
    private val canonical = content.copy(recurrence = EventRecurrence("synthetic@calendeer.invalid", intent.series!!.rule))
    // Independently length-prefixed UTF-8 SHA256 fixture, with the series-v2 domain and canonical rule.
    private fun identity() = CapturedCreateDispatchIdentity(CreateAttemptIdentity.of(record), intent.reservation.localId,
        intent.reservation.id, intent.reservation.format, intent.reservation.timestamp,
        "e191b49e9d4b322a5a7567d4ea79e0204efab32b11d2297d56cd3c21a5a1d0ae", 2)

    @Test fun versionTwoMatchesOnlyTheCapturedRuleAndPersonalContent() {
        val identity = identity()
        assertEquals(identity, CapturedCreateDispatchIdentity.of(record, intent))
        assertTrue(identity.matchesContent(canonical))
        for (changed in listOf(content, canonical.copy(title = "Later"), canonical.copy(colorRgb = 0x123456),
            canonical.copy(details = EventDetails(listOf(EventReminder(10)))),
            canonical.copy(recurrence = canonical.recurrence!!.copy(rule = "FREQ=DAILY;COUNT=4")),
            canonical.copy(recurrence = canonical.recurrence!!.copy(excludedDates = listOf(RecurrencePoint.AllDay(LocalDate.parse("2030-10-03")))))))
            assertFalse(identity.matchesContent(changed))
        assertFalse(identity.copy(fingerprintVersion = 1).matchesContent(canonical))
        assertThrows(IllegalArgumentException::class.java) { identity.copy(fingerprintVersion = 3) }
    }
    @Test fun baselineKeepsTheVerifiedUidAndOwnsMutableRecurrenceLists() {
        val dates = mutableListOf<RecurrencePoint>()
        val change = RemoteChange.Upsert(EventId("event"), Revision("verified"), canonical.copy(
            recurrence = canonical.recurrence!!.copy(excludedDates = dates)))
        val receipt = CapturedCreateAttachmentReceipt(CapturedCreateBinding(identity(), change.eventId, change.revision), UUID(0, 3).toString())
        val baseline = CapturedCreateBaseline(receipt, change)
        dates += RecurrencePoint.AllDay(LocalDate.parse("2030-10-03"))
        assertEquals(canonical, baseline.change.content)
        assertEquals("synthetic@calendeer.invalid", baseline.change.content.recurrence!!.uid)
        assertThrows(IllegalArgumentException::class.java) { CapturedCreateBaseline(receipt, change) }
    }
    @Test fun ruleAndPersonalMetadataChooseVersionTwoWithoutReinterpretingOldBytes() {
        val rich = intent.copy(content = content.copy(details = EventDetails(listOf(EventReminder(10))), colorRgb = 0x123456))
        val richIdentity = CapturedCreateDispatchIdentity.of(record, rich)
        assertTrue(richIdentity.matchesContent(rich.content.copy(recurrence = canonical.recurrence)))
        assertNotEquals(identity().contentFingerprint, richIdentity.contentFingerprint)
        assertNotEquals(identity().contentFingerprint, CapturedCreateDispatchIdentity.of(record, intent.copy(series = CreateSeriesRule(4))).contentFingerprint)
        assertEquals(1, CapturedCreateDispatchIdentity.of(record, intent.copy(series = null)).fingerprintVersion)
        assertThrows(IllegalArgumentException::class.java) { CapturedCreateDispatchIdentity.of(record.copy(scope = scope.copy(generation = 1)), intent) }
    }
}
