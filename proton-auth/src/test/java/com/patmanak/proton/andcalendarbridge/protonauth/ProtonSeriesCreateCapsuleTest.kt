// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProtonSeriesCreateCapsuleTest {
    @Test fun protectedExpectationReopensWithoutSessionReadsOrRemoteProof() = runTest {
        val f = fixture()
        f.current = null
        assertEquals(f.verified!!.content, f.adapter.readCapturedExpectation(f.record, f.draft))
        assertEquals(f.verified!!.content, f.adapter.readCapturedExpectation(f.record.copy(state = CreateAttemptState.OUTCOME_UNKNOWN), f.draft))
        assertEquals(0, f.prepares); assertEquals(0, f.sends)
        assertTrue(f.steps.isEmpty())
        assertNull(f.adapter.readCapturedExpectation(f.record, f.draft.copy(series = CreateSeriesRule(4))))
        assertNull(f.adapter.readCapturedExpectation(f.record.copy(scope = f.record.scope.copy(generation = f.scope.generation + 1)), f.draft))
        assertNull(f.adapter.readCapturedExpectation(f.record.copy(protectedPayload = "unreadable"), f.draft))
    }
    private fun fixture() = CreateSubmissionFixture(EventContent("Synthetic series", EventTime.AllDay(
        LocalDate.parse("2030-03-29"), LocalDate.parse("2030-03-31")), details = EventDetails()), CreateSeriesRule(5))
    @Test fun newCapsuleRetainsTheRuleWithExplicitPersonalMetadataEvenWhenEmpty() {
        val f = fixture()
        val opened = ProtonCreateSubmissionCodec.open(f.record, f.protector)
        assertEquals(4, f.record.format.version); assertEquals(f.draft, opened.draft)
        assertEquals(5, opened.policy.version)
        assertEquals(JsonArray(emptyList()), f.fragment["Notifications"]); assertEquals(JsonNull, f.fragment["Color"])
        assertTrue(f.adapter.supportsCapturedSubmission(f.format))
    }
    @Test fun unknownInspectionKeepsTheCanonicalUidAndRuleWithoutRepreparingOrSending() = runTest {
        val f = fixture(); val unknown = f.record.copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
        val result = f.adapter.inspectCaptured(unknown, f.draft) as CreateSubmissionObservation.Verified
        assertEquals(f.verified, result.event)
        assertEquals(f.uid, result.event.content.recurrence!!.uid)
        assertEquals(f.series!!.rule, result.event.content.recurrence!!.rule)
        assertEquals(0, f.prepares); assertEquals(0, f.sends)
        assertTrue(f.adapter.inspectCaptured(unknown, f.draft) is CreateSubmissionObservation.Verified)
    }
    @Test fun preparedSeriesClaimsOnceAndNeverResendsAfterALostResponse() = runTest {
        val f = fixture()
        f.sendFailure = IllegalStateException("Synthetic lost series response")
        assertTrue(f.adapter.submitCaptured(f.record, f.draft, f.journal) is CreateSubmissionObservation.Verified)
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, f.journal.stored!!.state)
        assertEquals(1, f.prepares); assertEquals(1, f.sends)
        assertTrue(f.adapter.submitCaptured(f.record, f.draft, f.journal) is CreateSubmissionObservation.Verified)
        assertEquals(1, f.prepares); assertEquals(1, f.sends)
    }
    @Test fun signedSeriesPartsRoundTripCanonicalIntervalsThroughTheImportParser() {
        val content = fixture().draft.content
        for (interval in listOf(1, 2, 365)) {
            val rule = CreateSeriesRule(5, interval)
            val parts = ProtonSimpleEventSerializer.serializeSeries("synthetic@calendeer.invalid", java.time.Instant.parse("2030-01-01T00:00:00Z"), content, rule)
            val parsed = ProtonICalendar.parse(listOf(parts.clearShared, parts.encryptedShared))
            assertEquals(rule.rule, parsed.recurrence!!.rule); assertEquals(content.time, parsed.time)
        }
    }
    @Test fun foreignUidDowngradesUnboundedRulesAndWrongExpectedSeriesStayUnknown() = runTest {
        val f = fixture()
        val root = f.envelope()
        for ((version, changed) in listOf(4 to JsonObject(root + ("uid" to JsonPrimitive("foreign@calendeer.invalid"))),
            3 to JsonObject(root + ("version" to JsonPrimitive(3))),
            4 to JsonObject(root + ("expected" to JsonObject(root.obj("expected") + ("seriesRule" to JsonPrimitive("FREQ=DAILY"))))))) {
            val record = f.record.copy(format = f.format.copy(version = version), state = CreateAttemptState.OUTCOME_UNKNOWN,
                protectedPayload = f.protector.seal(changed.toString()))
            assertEquals(CreateSubmissionObservation.Unknown, f.adapter.inspectCaptured(record, f.draft))
        }
        assertEquals(CreateSubmissionObservation.Unknown, f.adapter.inspectCaptured(f.record.copy(state = CreateAttemptState.OUTCOME_UNKNOWN),
            f.draft.copy(series = CreateSeriesRule(4))))
        assertEquals(0, f.prepares); assertEquals(0, f.sends)
    }
    @Test fun readbackCannotAdoptADifferentRuleUidOrExcludedSlot() = runTest {
        for (kind in 0..2) {
            val f = fixture(); val before = f.verified!!
            val recurrence = before.content.recurrence!!
            f.verified = before.copy(content = before.content.copy(recurrence = when (kind) {
                0 -> recurrence.copy(rule = "FREQ=DAILY;COUNT=4")
                1 -> recurrence.copy(uid = "foreign@calendeer.invalid")
                else -> recurrence.copy(excludedDates = listOf(RecurrencePoint.AllDay(LocalDate.parse("2030-03-30"))))
            }))
            assertEquals(CreateSubmissionObservation.Unknown, f.adapter.inspectCaptured(f.record.copy(state = CreateAttemptState.OUTCOME_UNKNOWN), f.draft))
            assertEquals(0, f.sends)
        }
    }
}
