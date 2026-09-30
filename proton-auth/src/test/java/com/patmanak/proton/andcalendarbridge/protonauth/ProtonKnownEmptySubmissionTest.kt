// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.IOException
import java.time.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProtonKnownEmptySubmissionTest {
    private fun fixture() = CreateSubmissionFixture(EventContent("Synthetic known-empty submit",
        EventTime.AllDay(LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-03")), details = EventDetails()))

    @Test fun versionTwoRoundtripRetainsKnownEmptyExpectedAndPersonalPolicy() {
        val f = fixture()
        val decoded = ProtonCreateSubmissionCodec.open(f.record, f.protector)
        assertEquals(f.draft, decoded.draft)
        assertEquals(2, f.record.format.version)
        assertEquals("EXPLICIT_EMPTY", f.envelope().string("notificationPolicy"))
        assertTrue(f.envelope().obj("expected").getValue("knownEmptyDetails").jsonPrimitive.boolean)
        assertEquals(JsonArray(emptyList()), decoded.request.array("Events").single().jsonObject.obj("Event").getValue("Notifications"))
        assertFalse(decoded.toString().contains("Synthetic"))
    }

    @Test fun freshContextThenDurableClaimSendAndStrictReadbackHappenOnlyOnce() = runTest {
        val f = fixture()
        assertTrue(f.adapter.submit(f.record, f.journal) is CreateSubmissionObservation.Verified)
        assertEquals(listOf("prepare", "claim", "send", "evidence", "uid:0", "event", "bootstrap", "verify"), f.steps)
        assertEquals(1, f.sends); assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, f.journal.stored!!.state)
        val stored = f.journal.stored!!
        assertTrue(f.adapter.submit(stored, f.journal) is CreateSubmissionObservation.Verified)
        assertEquals(1, f.sends); assertEquals(1, f.prepares); assertEquals(stored, f.journal.stored)
    }

    @Test fun anUnknownVersionTwoAttemptInspectsWithoutFreshPreparationOrAdmission() = runTest {
        val f = fixture()
        f.journal.stored = f.record.copy(state = CreateAttemptState.OUTCOME_UNKNOWN)
        assertTrue(f.adapter.inspect(f.journal.stored!!) is CreateSubmissionObservation.Verified)
        assertEquals(listOf("uid:0", "event", "bootstrap", "verify"), f.steps)
        assertEquals(0, f.sends); assertEquals(0, f.prepares)
    }

    @Test fun inheritedEmailOrDisplayProfilesStayUnknownAfterOneSend() = runTest {
        for (override in listOf(null, JsonNull, JsonPrimitive("[]"),
            JsonArray(listOf(buildJsonObject { put("Type", 0); put("Trigger", "-PT15M") })),
            JsonArray(listOf(buildJsonObject { put("Type", 1); put("Trigger", "-PT15M") })))) {
            val f = fixture()
            f.individualRow = if (override == null) JsonObject(f.row - "Notifications") else JsonObject(f.row + ("Notifications" to override))
            assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(f.record, f.journal))
            assertFalse(f.steps.contains("bootstrap")); assertFalse(f.steps.contains("verify"))
            val unknown = f.journal.stored!!
            assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(unknown, f.journal))
            assertEquals(unknown, f.journal.stored); assertEquals(1, f.sends); assertEquals(1, f.prepares)
        }
    }

    @Test fun inconsistentVersionPolicyExpectedAndRequestAreRefusedBeforePreparationOrClaim() = runTest {
        val f = fixture()
        val root = f.envelope()
        val wrapper = root.obj("request").array("Events").single().jsonObject
        fun withEvent(event: JsonObject) = JsonObject(root + ("request" to JsonObject(root.obj("request") +
            ("Events" to JsonArray(listOf(JsonObject(wrapper + ("Event" to event))))))))
        val event = wrapper.obj("Event")
        val changes = listOf(JsonObject(root + ("version" to JsonPrimitive(1))),
            JsonObject(root - "notificationPolicy"), JsonObject(root + ("notificationPolicy" to JsonPrimitive("EXPLICIT_EMPTY_REQUIRED"))),
            JsonObject(root + ("expected" to JsonObject(root.obj("expected") - "knownEmptyDetails"))),
            JsonObject(root + ("expected" to JsonObject(root.obj("expected") + ("knownEmptyDetails" to JsonPrimitive(false))))),
            JsonObject(root + ("expected" to JsonObject(root.obj("expected") + ("reminders" to JsonArray(emptyList()))))),
            withEvent(JsonObject(event - "Notifications")), withEvent(JsonObject(event + ("Notifications" to JsonNull))),
            withEvent(JsonObject(event + ("Notifications" to JsonArray(listOf(buildJsonObject { put("Type", 0); put("Trigger", "-PT15M") }))))))
        for (envelope in changes) {
            val invalid = f.record.copy(protectedPayload = f.protector.seal(envelope.toString()))
            f.journal.stored = invalid; f.steps.clear()
            assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(invalid, f.journal))
            assertTrue(f.steps.isEmpty()); assertEquals(0, f.sends); assertEquals(0, f.prepares)
            assertEquals(invalid, f.journal.stored)
        }
    }

    @Test fun legacyFormatCannotCarryKnownEmptyPolicyOrRequest() = runTest {
        val f = fixture()
        val invalid = f.record.copy(format = CREATE_SUBMISSION_FORMAT)
        f.journal.stored = invalid
        assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(invalid, f.journal))
        assertTrue(f.steps.isEmpty()); assertEquals(0, f.sends)
    }

    @Test fun freshFormatOrFrozenContentAndTimestampChangesRefuseBeforeClaim() = runTest {
        for (change in listOf<(CreateSubmissionFixture) -> Unit>(
            { it.freshFormat = CREATE_SUBMISSION_FORMAT },
            { it.freshDraft = it.draft.copy(timestamp = it.draft.timestamp.plusSeconds(1)) },
            { it.freshDraft = it.draft.copy(content = it.draft.content.copy(title = "Changed")) },
            { it.freshDraft = it.draft.copy(content = it.draft.content.copy(details = null)) })) {
            val f = fixture(); change(f)
            assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submit(f.record, f.journal))
            assertEquals(listOf("prepare"), f.steps); assertEquals(0, f.sends)
            assertEquals(CreateAttemptState.PREPARED, f.journal.stored!!.state)
        }
    }

    @Test fun failedReadbackCancellationAndLostResponseCannotReopenAnAdmittedVersionTwoAttempt() = runTest {
        val lost = fixture(); lost.sendFailure = IOException("synthetic response loss"); lost.rows = emptyList()
        assertEquals(CreateSubmissionObservation.Unknown, lost.adapter.submit(lost.record, lost.journal))
        lost.rows = listOf(lost.row)
        assertTrue(lost.adapter.inspect(lost.journal.stored!!) is CreateSubmissionObservation.Verified)
        assertEquals(1, lost.sends)
        val cancelled = fixture(); cancelled.sendFailure = CancellationException("synthetic cancellation")
        try { cancelled.adapter.submit(cancelled.record, cancelled.journal); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, cancelled.journal.stored!!.state)
        assertNull(cancelled.journal.stored!!.protectedEvidence)
        assertTrue(cancelled.adapter.inspect(cancelled.journal.stored!!) is CreateSubmissionObservation.Verified)
        assertEquals(1, cancelled.sends)
    }
}
