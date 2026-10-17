// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class DeleteAcknowledgementTest {
    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 1)
    private val format = AdapterFormat("synthetic-delete", 1)
    private fun record(allDay: Boolean = false): DeleteAttemptRecord {
        val time = if (allDay) EventTime.AllDay(LocalDate.parse("2030-10-01"), LocalDate.parse("2030-10-03"))
            else EventTime.Timed(Instant.parse("2030-10-01T10:00:00Z"), Instant.parse("2030-10-01T11:00:00Z"), ZoneId.of("Europe/Paris"))
        val content = EventContent("Synthetic\r\ntitle", time, description = "Raw\r\nnotes", details = EventDetails())
        val intent = LocalDeleteIntent(MutationId("local-delete"), scope, format,
            RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-base"), content), content)
        return DeleteAttemptRecord(MutationId("synthetic-request"), intent, format, "protected-payload", DeleteAttemptState.OUTCOME_UNKNOWN)
    }
    @Test fun timedAndAllDayReceiptsRetainExactIntentAndBaseWithoutRemoteRevision() {
        for (allDay in listOf(false, true)) {
            val result = DeleteSubmissionObservation.Verified(record(allDay))
            val receipt = DeleteAcknowledgementReceipt.of(result, UUID.randomUUID().toString())
            assertEquals(result.record.intent, receipt.intent); assertEquals(receipt, receipt.snapshot())
            assertEquals(receipt.hashCode(), receipt.snapshot().hashCode()); assertTrue(receipt.matches(result))
            assertNotEquals(receipt, receipt.copy(nonce = UUID.randomUUID().toString()))
        }
    }
    @Test fun mismatchedRawIntentScopeBaseEventAndFingerprintsAreRejected() {
        val receipt = DeleteAcknowledgementReceipt.of(DeleteSubmissionObservation.Verified(record()), UUID.randomUUID().toString())
        val a = receipt.attempt
        for (attempt in listOf(a.copy(scope = scope.copy(generation = 2)), a.copy(localIntentId = MutationId("other")),
            a.copy(eventId = EventId("other")), a.copy(baseRevision = Revision("other")), a.copy(intentFingerprint = "0".repeat(64))))
            rejects { receipt.copy(attempt = attempt) }
        val content = receipt.intent.content.copy(title = "Synthetic\ntitle")
        rejects { receipt.copy(intent = receipt.intent.copy(base = receipt.intent.base.copy(content = content), content = content)) }
        assertFalse(receipt.matches(DeleteSubmissionObservation.Verified(record().copy(protectedPayload = "different-wire"))))
        assertFalse(receipt.matches(DeleteSubmissionObservation.Verified(record().copy(id = MutationId("different-attempt")))))
    }
    @Test fun noncanonicalUnboundedOrBlankNonceIsRejected() {
        val result = DeleteSubmissionObservation.Verified(record())
        for (nonce in listOf("", " ", "x".repeat(4097), "ABCDEFAB-1234-1234-1234-ABCDEFABCDEF", "broken"))
            rejects { DeleteAcknowledgementReceipt.of(result, nonce) }
    }
    @Test fun constructorFreezesMutableEmptyDetailsAndDiagnosticsAreRedacted() {
        val record = record(); val reminders = mutableListOf<EventReminder>()
        val content = record.intent.content.copy(details = EventDetails(reminders))
        val intent = record.intent.copy(base = record.intent.base.copy(content = content), content = content)
        val receipt = DeleteAcknowledgementReceipt(DeleteAttemptIdentity.of(record), intent, UUID.randomUUID().toString())
        reminders += EventReminder(10)
        assertEquals(EventDetails(), receipt.intent.content.details)
        assertEquals(EventDetails(), receipt.intent.base.content.details)
        for (value in listOf(receipt, AppliedDeleteAcknowledgement(receipt),
            DeleteAcknowledgementRecord(receipt, DeleteAcknowledgementState.PENDING),
            DeleteAcknowledgementReservation(DeleteAcknowledgementRecord(receipt, DeleteAcknowledgementState.PENDING), true)))
            for (secret in listOf(scope.account.value, receipt.intent.content.title, receipt.attempt.payloadFingerprint, receipt.nonce))
                assertFalse(value.toString().contains(secret))
    }
    private fun rejects(block: () -> Unit) { try { block(); fail("Invalid acknowledgement accepted") } catch (_: IllegalArgumentException) { } }
}
