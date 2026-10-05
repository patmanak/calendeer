// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TitleUpdateSubmissionTest {
    @Test fun commitsUnknownBeforeOneSendAndNeverSendsAReplay() = runTest {
        val record = record(); val journal = Journal(record); var sends = 0
        val admission = TitleUpdateAttemptAdmission(journal)
        assertTrue(admission.dispatch(record) { unknown ->
            assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, journal.value.state)
            assertEquals(journal.value, unknown); sends++
        })
        assertFalse(admission.dispatch(record) { sends++ })
        assertFalse(admission.dispatch(journal.value) { sends++ })
        assertEquals(1, sends)
    }

    @Test fun transportFailureKeepsUnknownAndCannotRetry() = runTest {
        val record = record(); val journal = Journal(record); var sends = 0
        try {
            TitleUpdateAttemptAdmission(journal).dispatch(record) { sends++; throw IllegalStateException("Synthetic transport loss") }
            fail("Transport failure must propagate")
        } catch (_: IllegalStateException) { }
        assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, journal.value.state)
        assertFalse(TitleUpdateAttemptAdmission(journal).dispatch(record) { sends++ })
        assertEquals(1, sends)
    }

    @Test fun cancellationAfterClaimKeepsUnknown() = runTest {
        val record = record(); val journal = Journal(record)
        try {
            TitleUpdateAttemptAdmission(journal).dispatch(record) { throw CancellationException("Synthetic cancellation") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, journal.value.state)
        assertFalse(TitleUpdateAttemptAdmission(journal).dispatch(record) { fail("Never resend") })
    }

    @Test fun failedCommitNeverEntersTransport() = runTest {
        val journal = Journal(record()).apply { failCommit = true }
        try {
            TitleUpdateAttemptAdmission(journal).dispatch(journal.value) { fail("Uncommitted claim must not send") }
            fail("Failed commit must propagate")
        } catch (_: IllegalStateException) { }
        assertEquals(TitleUpdateAttemptState.PREPARED, journal.value.state)
    }

    @Test fun deniedOrChangedExpectedWorkDoesNotSend() = runTest {
        val original = record(); val journal = Journal(original)
        assertFalse(TitleUpdateAttemptAdmission(journal).dispatch(original.copy(protectedPayload = "changed-ciphertext")) { fail("Substituted work") })
        journal.allowed = false
        assertFalse(TitleUpdateAttemptAdmission(journal).dispatch(original) { fail("Closed admission") })
        assertEquals(TitleUpdateAttemptState.PREPARED, journal.value.state)
    }

    @Test fun preparedRecordWithEvidenceIsNeverClaimed() = runTest {
        val journal = Journal(record())
        assertFalse(TitleUpdateAttemptAdmission(journal).dispatch(journal.value.copy(protectedEvidence = "sealed-evidence")) { fail("Evidence is not a permit") })
        assertEquals(0, journal.claims)
    }

    @Test fun identityBindsAttemptPreparationPayloadAndGeneration() {
        val original = record(); val identity = TitleUpdateAttemptIdentity.of(original)
        assertEquals(identity, TitleUpdateAttemptIdentity.of(original.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "evidence")))
        assertNotEquals(identity, TitleUpdateAttemptIdentity.of(original.copy(id = MutationId("other-attempt"))))
        assertNotEquals(identity, TitleUpdateAttemptIdentity.of(original.copy(protectedPayload = "other-sealed-wire")))
        assertNotEquals(identity, TitleUpdateAttemptIdentity.of(original.copy(preparation = original.preparation.copy(protectedPayload = "other-sealed-preparation"))))
        assertNotEquals(identity, TitleUpdateAttemptIdentity.of(original.copy(preparation = original.preparation.copy(timestamp = original.preparation.timestamp.plusSeconds(1)))))
        val intent = original.preparation.intent
        assertNotEquals(identity, TitleUpdateAttemptIdentity.of(original.copy(preparation = original.preparation.copy(
            intent = intent.copy(scope = intent.scope.copy(generation = 1))))))
        assertFalse(original.toString().contains(intent.content.title))
        assertFalse(identity.toString().contains(intent.scope.account.value))
        assertNotEquals(identity, TitleUpdateAttemptIdentity.of(original.copy(preparation = original.preparation.copy(
            intent = intent.copy(format = AdapterFormat("other-import", 1))))))
        assertNotEquals(identity, TitleUpdateAttemptIdentity.of(original.copy(preparation = original.preparation.copy(
            intent = intent.copy(content = intent.content.copy(title = "A different synthetic title"))))))
    }

    @Test fun aVerifiedObservationCannotSubstituteEventOrOriginalRevision() {
        val original = record(); val identity = TitleUpdateAttemptIdentity.of(original)
        for (event in listOf(original.preparation.intent.base,
            original.preparation.intent.base.copy(eventId = EventId("another-event"), revision = Revision("new")))) {
            try { TitleUpdateSubmissionObservation.Verified(identity, event); fail("Mismatched proof") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun unknownRemoteDetailsAndColorCannotBecomeTitleUpdates() {
        val original = record(); val intent = original.preparation.intent
        for (content in listOf(intent.base.content.copy(details = null), intent.base.content.copy(colorRgb = 0x123456))) {
            try {
                original.copy(preparation = original.preparation.copy(intent = intent.copy(
                    base = intent.base.copy(content = content), content = content.copy(title = intent.content.title))))
                fail("Unsupported remote profile must remain held")
            } catch (_: IllegalArgumentException) { }
        }
    }

    private fun record(): TitleUpdateAttemptRecord {
        val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 0)
        val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("base-revision"), EventContent(
            "Original synthetic title", EventTime.Timed(Instant.parse("2030-05-02T10:00:00Z"),
                Instant.parse("2030-05-02T11:00:00Z"), ZoneId.of("UTC")), details = EventDetails()))
        val intent = LocalEditIntent(MutationId("synthetic-intent"), scope, AdapterFormat("synthetic-import", 1),
            base, base.content.copy(title = "Edited synthetic title"))
        return TitleUpdateAttemptRecord(MutationId("synthetic-attempt"), LocalEditPreparation(intent,
            Instant.parse("2030-05-01T10:00:00Z"), AdapterFormat("synthetic-title-preparation", 1), "sealed-preparation"),
            AdapterFormat("synthetic-title-update", 1), "sealed-wire")
    }

    private class Journal(var value: TitleUpdateAttemptRecord) : TitleUpdateAttemptJournal {
        var failCommit = false
        var allowed = true
        var claims = 0
        override suspend fun remember(record: TitleUpdateAttemptRecord) { check(record == value) }
        override suspend fun read(scope: CalendarScope, id: MutationId) = value.takeIf { it.scope == scope && it.id == id }
        override suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean {
            claims++
            if (failCommit) error("Synthetic commit failure")
            if (!allowed || value != expected || value.state != TitleUpdateAttemptState.PREPARED) return false
            value = expected.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
            return true
        }
        override suspend fun retainEvidence(expectedUnknown: TitleUpdateAttemptRecord, protectedEvidence: String) = error("Not used")
    }
}
