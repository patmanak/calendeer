// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CapturedCreateBaselineTest {
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
    @Test fun referenceRetainsFrozenContentAndAttachmentProvenance() {
        val baseline = CapturedCreateBaseline(receipt, change)
        assertEquals(scope, baseline.scope); assertEquals(intent.reservation.format, baseline.format)
        assertEquals(receipt, baseline.receipt); assertEquals(change, baseline.change)
        assertTrue(identity.matchesContent(content))
    }
    @Test fun differentRemoteIdentityOrRevisionIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { CapturedCreateBaseline(receipt, change.copy(eventId = EventId("other"))) }
        assertThrows(IllegalArgumentException::class.java) { CapturedCreateBaseline(receipt, change.copy(revision = Revision("other"))) }
    }
    @Test fun laterLocalContentCannotBecomeTheSentBaseline() {
        for (changed in listOf(content.copy(title = "Later edit"), content.copy(description = "Changed"),
            content.copy(location = "Elsewhere"), content.copy(time = EventTime.AllDay(LocalDate.of(2030, 10, 2), LocalDate.of(2030, 10, 3)))))
            assertThrows(IllegalArgumentException::class.java) { CapturedCreateBaseline(receipt, change.copy(content = changed)) }
    }
    @Test fun unknownPersonalDataOrUnsupportedColorIsRefused() {
        for (changed in listOf(content.copy(details = null), content.copy(details = EventDetails(reminders = listOf(EventReminder(15)))),
            content.copy(colorRgb = 0x123456))) assertFalse(identity.matchesContent(changed))
    }
    @Test fun malformedUnicodeCannotMatchReplacementText() {
        assertFalse(identity.matchesContent(content.copy(title = "\uD800")))
        assertThrows(IllegalArgumentException::class.java) { CapturedCreateBaseline(receipt, change.copy(content = content.copy(title = "\uD800"))) }
    }
    @Test fun damagedFingerprintAndDiagnosticsRemainSafe() {
        assertThrows(IllegalArgumentException::class.java) { CapturedCreateBaseline(receipt.copy(binding = receipt.binding.copy(
            identity = identity.copy(contentFingerprint = "0".repeat(64)))), change) }
        assertEquals("CapturedCreateBaseline(REDACTED)", CapturedCreateBaseline(receipt, change).toString())
    }
}
