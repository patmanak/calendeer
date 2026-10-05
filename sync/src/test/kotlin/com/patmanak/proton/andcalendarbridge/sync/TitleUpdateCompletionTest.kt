// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TitleUpdateCompletionTest {
    private val scope = CalendarScope(AccountId("completion-account"), CalendarId("completion-calendar"), 1)
    private val base = RemoteChange.Upsert(EventId("completion-event"), Revision("original"), EventContent(
        "Original", EventTime.AllDay(LocalDate.parse("2030-06-01"), LocalDate.parse("2030-06-02")), details = EventDetails()))
    private val intent = LocalEditIntent(MutationId("completion-intent"), scope, AdapterFormat("completion-import", 1),
        base, base.content.copy(title = "Updated"))
    private val attempt = TitleUpdateAttemptRecord(MutationId("completion-attempt"), LocalEditPreparation(intent,
        Instant.parse("2030-05-01T10:00:00Z"), AdapterFormat("completion-preparation", 1), "protected-preparation"),
        AdapterFormat("completion-wire", 1), "protected-wire", TitleUpdateAttemptState.OUTCOME_UNKNOWN)
    private val result = TitleUpdateVerifiedResult.of(attempt, TitleUpdateSubmissionObservation.Verified(
        TitleUpdateAttemptIdentity.of(attempt), base.copy(revision = Revision("updated"), content = intent.content)))
    private val receipt = TitleUpdateAcknowledgementReceipt.of(result, "00000000-0000-0000-0000-000000000001")

    @Test fun restoredFullReceiptBindsClosureWithoutResettingUnknownWork() {
        val completion = TitleUpdateCompletion.of(result, TitleUpdateAcknowledgementRecord(receipt,
            TitleUpdateAcknowledgementState.RESTORED))
        assertEquals(result, completion.result)
        assertEquals(receipt, completion.receipt)
        assertEquals(completion, completion.snapshot())
        assertEquals(completion.hashCode(), completion.snapshot().hashCode())
        assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, completion.result.record.state)
        assertEquals(attempt, completion.result.record)
        assertEquals("TitleUpdateCompletion(redacted)", completion.toString())
    }

    @Test fun pendingOrDifferentAttemptCannotCloseWork() {
        assertThrows(IllegalArgumentException::class.java) { TitleUpdateCompletion.of(result,
            TitleUpdateAcknowledgementRecord(receipt, TitleUpdateAcknowledgementState.PENDING)) }
        val changed = attempt.copy(id = MutationId("different-attempt"))
        val other = TitleUpdateVerifiedResult.of(changed, TitleUpdateSubmissionObservation.Verified(
            TitleUpdateAttemptIdentity.of(changed), result.event))
        assertThrows(IllegalArgumentException::class.java) { TitleUpdateCompletion.of(other,
            TitleUpdateAcknowledgementRecord(receipt, TitleUpdateAcknowledgementState.RESTORED)) }
    }

    @Test fun journalDefaultsDenyClosureAndExposeNoProtocol() = runTest {
        val journal = object : TitleUpdateCompletionJournal {
            override suspend fun retainVerified(result: TitleUpdateVerifiedResult) = Unit
            override suspend fun readVerified(scope: CalendarScope, id: MutationId): TitleUpdateVerifiedResult? = null
        }
        val completion = TitleUpdateCompletion.of(result, TitleUpdateAcknowledgementRecord(receipt,
            TitleUpdateAcknowledgementState.RESTORED))
        assertNull(journal.completionProtocol)
        assertNull(journal.readCompletion(scope, attempt.id))
        assertFalse(journal.retainCompletion(completion))
        assertFalse(journal.retainCompletion(completion) { true })
    }
}
