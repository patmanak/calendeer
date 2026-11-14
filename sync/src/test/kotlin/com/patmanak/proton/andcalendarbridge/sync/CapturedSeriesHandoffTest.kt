// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CapturedSeriesHandoffTest {
    private val scope = CalendarScope(AccountId("fixture"), CalendarId("calendar"), 0)
    private val content = EventContent("Synthetic series", EventTime.AllDay(LocalDate.parse("2030-03-29"), LocalDate.parse("2030-03-30")),
        details = EventDetails(listOf(EventReminder(10))), colorRgb = 0x123456)
    private val intent = LocalCreateIntent(LocalCreateReservation(MutationId("capture"), LocalEventId(UUID(0, 1).toString()), scope,
        AdapterFormat("capture", 1), Instant.parse("2030-01-01T00:00:00Z")), content, CreateSeriesRule(5))
    private val record = CreateAttemptRecord(MutationId(UUID(0, 2).toString()), scope, AdapterFormat("submission", 4), "synthetic-payload")
    private val base = RemoteChange.Upsert(EventId("event"), Revision("verified"), content.copy(
        recurrence = EventRecurrence("synthetic@calendeer.invalid", "FREQ=DAILY;COUNT=5")))
    private val baseline = CapturedCreateBaseline(CapturedCreateAttachmentReceipt(CapturedCreateBinding(
        CapturedCreateDispatchIdentity.of(record, intent), base.eventId, base.revision), UUID(0, 3).toString()), base)
    private fun frame(): CapturedCreateHandoffFrame = CapturedCreateHandoffFrame(CapturedCreateHandoffReceipt(
        CapturedCreateAcknowledgementReceipt(baseline, UUID(0, 4).toString()), UUID(0, 5).toString()), base)

    @Test fun seriesTransfersWithoutOpeningHistoricalPersonalEditProfiles() {
        val frame = frame()
        assertTrue(CapturedHandoffCurrent(frame, base.content, false).unchanged)
        assertFalse(supportsPersonalHandoff(base)); assertFalse(supportsCapturedHandoff(base))
        assertFalse(CapturedHandoffCurrent(frame, base.content.copy(title = "Later local title"), false).unchanged)
        assertFalse(CapturedHandoffCurrent(frame, base.content, true).unchanged)
    }
    @Test fun incomingSeriesProjectionRetainsTheVerifiedUidAndNewCanonicalRule() {
        val frame = frame()
        val incoming = base.copy(revision = Revision("later"), content = base.content.copy(
            recurrence = base.content.recurrence!!.copy(rule = "FREQ=DAILY;COUNT=4")))
        val plan = CapturedHandoffProjection(frame, ProjectionReceipt(scope, baseline.format, incoming, UUID(0, 6).toString(), base))
        val advanced = frame.copy(base = incoming, lastProjection = plan.receipt)
        assertEquals(incoming, advanced.base); assertEquals(baseline, advanced.receipt.acknowledgement.baseline)
    }
    @Test fun foreignUidMissingRuleExceptionsAndUnqualifiedRulesCannotTransfer() {
        val frame = frame()
        for (changed in listOf(base.content.copy(recurrence = null), base.content.copy(recurrence = base.content.recurrence!!.copy(uid = "foreign@calendeer.invalid")),
            base.content.copy(recurrence = base.content.recurrence!!.copy(rule = "FREQ=WEEKLY;COUNT=5")),
            base.content.copy(recurrence = base.content.recurrence!!.copy(excludedDates = listOf(RecurrencePoint.AllDay(LocalDate.parse("2030-03-30"))))))) {
            assertThrows(IllegalArgumentException::class.java) { CapturedHandoffCurrent(frame, changed, false) }
            assertThrows(IllegalArgumentException::class.java) { CapturedHandoffProjection(frame, ProjectionReceipt(scope, baseline.format,
                base.copy(content = changed), UUID(0, 6).toString(), base)) }
        }
    }
    @Test fun transferredSnapshotOwnsMutableRecurrenceLists() {
        val dates = mutableListOf<RecurrencePoint>()
        val current = CapturedHandoffCurrent(frame(), base.content.copy(recurrence = base.content.recurrence!!.copy(excludedDates = dates)), false)
        dates += RecurrencePoint.AllDay(LocalDate.parse("2030-03-30"))
        assertEquals(base.content, current.content)
    }
}
