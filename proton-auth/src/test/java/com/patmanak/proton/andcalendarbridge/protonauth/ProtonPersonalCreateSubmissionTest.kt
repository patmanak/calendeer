// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProtonPersonalCreateSubmissionTest {
    private fun fixture() = CreateSubmissionFixture(EventContent("Synthetic personal create",
        EventTime.AllDay(LocalDate.parse("2030-10-02"), LocalDate.parse("2030-10-03")),
        details = EventDetails(listOf(EventReminder(10))), colorRgb = 0x123456))

    @Test fun richRoundtripRetainsEveryPersonalFieldInVersionThree() {
        val f = fixture()
        assertEquals(3, f.record.format.version)
        val decoded = ProtonCreateSubmissionCodec.open(f.record, f.protector)
        assertEquals(f.draft, decoded.draft)
        val event = decoded.request.array("Events").single().jsonObject.obj("Event")
        assertEquals(JsonPrimitive("#123456"), event["Color"])
        assertEquals(Json.parseToJsonElement("""[{"Type":1,"Trigger":"-PT10M"}]"""), event["Notifications"])
        assertEquals("EXPLICIT_PERSONAL", f.envelope().string("notificationPolicy"))
    }
    @Test fun capturedPersonalCreateClaimsOnceAndUnknownOnlyInspects() = runTest {
        val f = fixture()
        assertTrue(f.adapter.submitCaptured(f.record, f.draft, f.journal) is CreateSubmissionObservation.Verified)
        val unknown = f.journal.stored!!
        assertTrue(f.adapter.submitCaptured(unknown, f.draft, f.journal) is CreateSubmissionObservation.Verified)
        assertEquals(1, f.prepares); assertEquals(1, f.sends)
        assertEquals(unknown, f.journal.stored)
    }
    @Test fun inconsistentRequestMetadataAndOldFormatCannotReachPreparationOrClaim() = runTest {
        val f = fixture()
        val root = f.envelope()
        val event = f.fragment
        val changes = listOf(JsonObject(event + ("Color" to JsonPrimitive("#654321"))),
            JsonObject(event + ("Notifications" to JsonArray(emptyList()))), JsonObject(event - "Color"),
            JsonObject(event + ("Notifications" to Json.parseToJsonElement("""[{"Type":0,"Trigger":"-PT10M"}]"""))))
        for (changed in changes) {
            val request = ProtonCreateSubmissionCodec.request(f.context.string("member"), changed)
            val bad = f.record.copy(protectedPayload = f.protector.seal(JsonObject(root + ("request" to request)).toString()))
            f.journal.stored = bad
            assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submitCaptured(bad, f.draft, f.journal))
            assertTrue(f.steps.isEmpty()); assertEquals(0, f.sends)
        }
        val old = f.record.copy(format = AdapterFormat("proton-calendar-create-submission", 2))
        f.journal.stored = old
        assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submitCaptured(old, f.draft, f.journal))
        assertTrue(f.steps.isEmpty())
    }
    @Test fun unexpectedEmailChangedColorOrReminderCannotBeAcknowledgedOrResent() = runTest {
        for (kind in 0..2) {
            val f = fixture()
            when (kind) {
                0 -> f.individualRow = JsonObject(f.row + ("Notifications" to Json.parseToJsonElement("""[{"Type":0,"Trigger":"-PT10M"},{"Type":1,"Trigger":"-PT10M"}]""")))
                1 -> f.verified = f.verified!!.copy(content = f.draft.content.copy(colorRgb = 0x654321))
                2 -> f.verified = f.verified!!.copy(content = f.draft.content.copy(details = EventDetails(listOf(EventReminder(15)))))
            }
            assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submitCaptured(f.record, f.draft, f.journal))
            assertEquals(CreateSubmissionObservation.Unknown, f.adapter.submitCaptured(f.journal.stored!!, f.draft, f.journal))
            assertEquals(1, f.prepares); assertEquals(1, f.sends)
        }
    }
    @Test fun cancellationPreservesUnknownAndOnlyInspectionRemains() = runTest {
        val f = fixture(); f.sendFailure = CancellationException("Synthetic personal interruption")
        try { f.adapter.submitCaptured(f.record, f.draft, f.journal); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertEquals(CreateAttemptState.OUTCOME_UNKNOWN, f.journal.stored!!.state)
        assertTrue(f.adapter.inspectCaptured(f.journal.stored!!, f.draft) is CreateSubmissionObservation.Verified)
        assertEquals(1, f.prepares); assertEquals(1, f.sends)
    }
    @Test fun explicitRequestColorFallbackAcceptsMissingOrNullColorInAuthenticatedReadback() = runTest {
        val content = fixture().draft.content.copy(colorRgb = null)
        for (missing in listOf(false, true)) {
            val f = CreateSubmissionFixture(content)
            assertEquals(JsonNull, f.fragment["Color"])
            f.individualRow = if (missing) JsonObject(f.row - "Color") else f.row
            assertTrue(f.adapter.submitCaptured(f.record, f.draft, f.journal) is CreateSubmissionObservation.Verified)
            assertEquals(1, f.sends)
        }
    }
    @Test fun callerExpectedReminderMutationDuringJournalReadKeepsTheCapturedExpectationFrozen() = runTest {
        val f = fixture()
        val reminders = f.draft.content.details!!.reminders.toMutableList()
        val expected = f.draft.copy(content = f.draft.content.copy(details = EventDetails(reminders)))
        val journal = object : CreateAttemptJournal by f.journal {
            override suspend fun read(scope: CalendarScope, id: MutationId): CreateAttemptRecord? {
                reminders.clear(); reminders += EventReminder(30)
                return f.journal.read(scope, id)
            }
        }
        val observed = f.adapter.submitCaptured(f.record, expected, journal) as CreateSubmissionObservation.Verified
        assertEquals(listOf(EventReminder(10)), observed.event.content.details!!.reminders)
        assertEquals(listOf(EventReminder(30)), reminders)
        assertEquals(1, f.sends)
    }
}
