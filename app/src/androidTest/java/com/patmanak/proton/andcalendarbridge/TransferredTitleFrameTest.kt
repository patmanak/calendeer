// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class TransferredTitleFrameTest {
    private val scope = CalendarScope(AccountId("frame-fixture-account"), CalendarId("frame-fixture-calendar"), 3)
    private val format = AdapterFormat("frame-fixture-import", 1)
    private val base = RemoteChange.Upsert(EventId("frame-fixture-event"), Revision("original"),
        EventContent("Synthetic title", EventTime.AllDay(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-27")),
            "Synthetic description", "Synthetic location", details = EventDetails()))
    private fun uuid(value: Long) = UUID(0, value).toString()
    private fun frame(target: CalendarScope = scope, adapter: AdapterFormat = format,
        original: RemoteChange.Upsert = base): CapturedCreateHandoffFrame {
        val capture = LocalCreateIntent(LocalCreateReservation(MutationId("synthetic-capture"), LocalEventId(uuid(2)),
            target, adapter, Instant.parse("2026-10-12T00:00:00Z")), original.content)
        val submission = LocalCreateSubmission(LocalCreateSubmissionReservation(capture, MutationId(uuid(3)),
            AdapterFormat("synthetic-create-wire", 1)), "synthetic-create-payload")
        val binding = CapturedCreateBinding(CapturedCreateDispatchIdentity.of(submission), original.eventId, original.revision)
        val baseline = CapturedCreateBaseline(CapturedCreateAttachmentReceipt(binding, uuid(4)), original)
        val ack = CapturedCreateAcknowledgementReceipt(baseline, uuid(5))
        return CapturedCreateHandoffFrame(CapturedCreateHandoffReceipt(ack, uuid(6)), original)
    }
    private fun echo(frame: CapturedCreateHandoffFrame, nonce: Long = 7) = frame.copy(lastProjection =
        ProjectionReceipt(frame.scope, frame.format, frame.base, uuid(nonce), frame.base))
    private fun accepts(expected: CapturedCreateHandoffFrame, actual: CapturedCreateHandoffFrame?) =
        TransferredTitleFrame.matchesPreEditorFrame(expected, actual)
    private fun rejectsDistinct(expected: CapturedCreateHandoffFrame, actual: CapturedCreateHandoffFrame) {
        assertNotEquals(expected, actual)
        assertFalse(accepts(expected, actual))
    }

    @Test fun exactFramesAndTwoDistinctSameBaseEchoReceiptsAreAccepted() {
        val original = frame(); val first = echo(original); val second = echo(original, 8)
        assertTrue(accepts(original, original)); assertTrue(accepts(first, first))
        assertNotEquals(original, first); assertNotEquals(first, second)
        assertTrue(accepts(original, first)); assertTrue(accepts(original, second)); assertTrue(accepts(first, second))
    }
    @Test fun timedContentHasTheSameNarrowRelationship() {
        val timed = base.copy(content = base.content.copy(time = EventTime.Timed(Instant.parse("2026-10-12T14:00:00Z"),
            Instant.parse("2026-10-12T15:00:00Z"), ZoneId.of("UTC"))))
        val original = frame(original = timed); val actual = echo(original)
        assertNotEquals(original, actual); assertTrue(accepts(original, actual))
    }
    @Test fun missingHistoricalFrameOrDroppedProjectionCannotPass() {
        val original = frame()
        assertFalse(accepts(original, null)); assertFalse(accepts(echo(original), null))
        rejectsDistinct(echo(original), original)
    }
    @Test fun immutableOriginScopeGenerationAndFormatCannotChange() {
        val original = frame()
        rejectsDistinct(original, echo(original.copy(receipt = original.receipt.copy(nonce = uuid(9)))))
        for (target in listOf(scope.copy(account = AccountId("other-account")), scope.copy(calendar = CalendarId("other-calendar")),
            scope.copy(generation = 4))) rejectsDistinct(original, echo(frame(target = target)))
        rejectsDistinct(original, echo(frame(adapter = AdapterFormat("different-import", 1))))
        rejectsDistinct(original, echo(frame(adapter = format.copy(version = 2))))
    }
    @Test fun EventIdentityAndEverySupportedBaseFieldStayExact() {
        val original = frame()
        rejectsDistinct(original, echo(frame(original = base.copy(eventId = EventId("other-event")))))
        val changed = listOf(base.copy(revision = Revision("other-revision")),
            base.copy(content = base.content.copy(title = "Other title")),
            base.copy(content = base.content.copy(description = "Other description")),
            base.copy(content = base.content.copy(location = "Other location")),
            base.copy(content = base.content.copy(time = EventTime.AllDay(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-26")))))
        for (value in changed) rejectsDistinct(original, original.copy(base = value,
            lastProjection = ProjectionReceipt(scope, format, value, uuid(7), base)))
    }
    @Test fun PreviousBaseMustBeTheCompleteExpectedBase() {
        val original = frame(); val actual = echo(original)
        for (previous in listOf(base.copy(revision = Revision("previous-revision")),
            base.copy(content = base.content.copy(title = "Previous title")),
            base.copy(content = base.content.copy(time = EventTime.AllDay(LocalDate.parse("2026-10-23"), LocalDate.parse("2026-10-27")))))) {
            rejectsDistinct(original, actual.copy(lastProjection = actual.lastProjection!!.copy(previousBase = previous)))
        }
    }
    @Test fun ConstructorsRejectInvalidNonceProtocolOrRepresentedReceipts() {
        val original = frame(); val receipt = echo(original).lastProjection!!
        assertThrows(IllegalArgumentException::class.java) { receipt.copy(nonce = "invalid") }
        assertThrows(IllegalArgumentException::class.java) { receipt.copy(protocol = 2) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(lastProjection = receipt.copy(scope = scope.copy(generation = 4))) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(lastProjection = receipt.copy(format = format.copy(version = 2))) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(lastProjection = receipt.copy(representation = ProjectedEventReference.direct(base))) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(lastProjection = receipt.copy(previousRepresentation = ProjectedEventReference.direct(base))) }
    }
}
