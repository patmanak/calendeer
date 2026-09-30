// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProtonCapturedSubmissionTest {
    private fun fixture() = CreateSubmissionFixture(EventContent("Synthetic captured dispatch",
        EventTime.AllDay(LocalDate.parse("2030-10-02"), LocalDate.parse("2030-10-03")), details = EventDetails()))

    @Test fun exactCapturedDraftCanClaimOnceAfterFreshContextThenOnlyInspect() = runTest {
        val f = fixture()
        assertTrue(f.adapter.submitCaptured(f.record, f.draft, f.journal) is CreateSubmissionObservation.Verified)
        assertEquals(listOf("prepare", "claim", "send", "evidence", "uid:0", "event", "bootstrap", "verify"), f.steps)
        val unknown = f.journal.stored!!
        assertTrue(f.adapter.submitCaptured(unknown, f.draft, f.journal) is CreateSubmissionObservation.Verified)
        assertEquals(1, f.sends); assertEquals(1, f.prepares)
    }

    @Test fun expectedContentTimestampCorrelationScopeAndUnknownDetailsCannotBeSubstituted() = runTest {
        val f = fixture()
        val wrong = listOf(f.draft.copy(content = f.draft.content.copy(title = "Foreign captured content")),
            f.draft.copy(timestamp = f.draft.timestamp.plusSeconds(1)),
            f.draft.copy(correlation = MutationId("foreign")),
            f.draft.copy(scope = f.draft.scope.copy(generation = f.draft.scope.generation + 1)),
            f.draft.copy(content = f.draft.content.copy(details = null)))
        for (expected in wrong) {
            assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submitCaptured(f.record, expected, f.journal))
            assertTrue(f.steps.isEmpty()); assertEquals(0, f.sends); assertEquals(0, f.prepares)
            assertEquals(CreateAttemptState.PREPARED, f.journal.stored!!.state)
        }
    }

    @Test fun legacySharedOnlyCannotEnterCapturedSubmissionOrInspection() = runTest {
        val f = CreateSubmissionFixture()
        assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submitCaptured(f.record, f.draft, f.journal))
        assertEquals(CreateSubmissionObservation.Unknown,
            f.adapter.inspectCaptured(f.record.copy(state = CreateAttemptState.OUTCOME_UNKNOWN), f.draft))
        assertTrue(f.steps.isEmpty()); assertEquals(0, f.prepares); assertEquals(0, f.sends)
    }

    @Test fun unknownCapturedWorkRefusesWrongExpectationBeforeAnyRead() = runTest {
        val f = fixture(); val unknown = f.record.copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
        f.journal.stored = unknown
        val wrong = f.draft.copy(content = f.draft.content.copy(location = "Foreign location"))
        assertEquals(CreateSubmissionObservation.Unknown, f.adapter.inspectCaptured(unknown, wrong))
        assertTrue(f.steps.isEmpty())
        assertTrue(f.adapter.inspectCaptured(unknown, f.draft) is CreateSubmissionObservation.Verified)
        assertEquals(0, f.prepares); assertEquals(0, f.sends)
    }

    @Test fun freshPreparationDriftAndDeniedLateClaimNeverSend() = runTest {
        val drift = fixture(); drift.freshDraft = drift.draft.copy(timestamp = drift.draft.timestamp.plusSeconds(1))
        assertEquals(CreateSubmissionObservation.Unknown, drift.adapter.submitCaptured(drift.record, drift.draft, drift.journal))
        assertEquals(listOf("prepare"), drift.steps); assertEquals(0, drift.sends)
        val f = fixture()
        val denied = object : CreateAttemptJournal by f.journal {
            override suspend fun claim(expected: CreateAttemptRecord): Boolean { f.steps += "late-local-gate"; return false }
        }
        assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submitCaptured(f.record, f.draft, denied))
        assertEquals(listOf("prepare", "late-local-gate"), f.steps); assertEquals(0, f.sends)
        assertEquals(CreateAttemptState.PREPARED, f.journal.stored!!.state)
    }

    @Test fun cancellationAfterClaimLeavesUnknownAndNeverRepeatsPreparation() = runTest {
        val f = fixture(); f.sendFailure = CancellationException("Synthetic captured interruption")
        try { f.adapter.submitCaptured(f.record, f.draft, f.journal); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, f.journal.stored!!.state)
        assertTrue(f.adapter.inspectCaptured(f.journal.stored!!, f.draft) is CreateSubmissionObservation.Verified)
        assertEquals(1, f.prepares); assertEquals(1, f.sends)
    }
    @Test fun qualifiedCanonicalReadbackReturnsExactCapturedDomainTextAndUtcZone() = runTest {
        val content = EventContent("Synthetic multiline creation", EventTime.Timed(
            java.time.Instant.parse("2030-10-02T10:00:00Z"), java.time.Instant.parse("2030-10-02T11:00:00Z"),
            java.time.ZoneId.of("UTC")), description = "Line one\nLine two", details = EventDetails())
        val f = CreateSubmissionFixture(content)
        f.verified = f.verified!!.copy(content = content.copy(description = "Line one\r\nLine two",
            time = (content.time as EventTime.Timed).copy(zone = java.time.ZoneOffset.UTC)))
        val result = f.adapter.submitCaptured(f.record, f.draft, f.journal) as CreateSubmissionObservation.Verified
        assertEquals(content, result.event.content)
        assertEquals(f.verified!!.eventId, result.event.eventId); assertEquals(f.verified!!.revision, result.event.revision)
    }
}
