// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync
import java.time.*
import org.junit.Assert.*
import org.junit.Test
class PersonalUpdateLifecycleTest {
    private val scope = CalendarScope(AccountId("fixture-account"),CalendarId("fixture-calendar"),4)
    private val stamp = Instant.parse("2026-10-17T12:00:00Z")
    private val time = EventTime.Timed(Instant.parse("2026-10-18T09:00:00Z"),Instant.parse("2026-10-18T10:00:00Z"),ZoneId.of("Europe/Paris"))
    private val content = EventContent("Original",time,"Old notes","Old place",colorRgb=0x123456,details=EventDetails(listOf(EventReminder(5),EventReminder(30))))
    private val intent = LocalEditIntent(MutationId("owner"),scope,AdapterFormat("observation",1),RemoteChange.Upsert(EventId("event"),Revision("old-revision"),content),content.copy(title="New title",description="New\r\nnotes",location="New place",time=EventTime.AllDay(LocalDate.of(2026,10,18),LocalDate.of(2026,10,21))))
    private fun preparation(edit: LocalEditIntent = intent) = PersonalEditPreparation(edit,stamp,AdapterFormat("preparation",2),"protected-preparation")
    private fun record(edit: LocalEditIntent = intent) = PersonalUpdateAttemptRecord(MutationId("request"),preparation(edit),AdapterFormat("request",2),"protected-request",PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
    private val event = intent.base.copy(revision=Revision("new-revision"),content=intent.content.copy(description="New\nnotes"))
    private val nonce = "00000000-0000-0000-0000-000000000001"
    private fun result(work: PersonalUpdateAttemptRecord = record(), actual: RemoteChange.Upsert = event) = PersonalUpdateVerifiedResult.of(work,PersonalUpdateSubmissionObservation.Verified(work,actual))
    private fun refuse(action: () -> Unit) { try { action(); fail("Substituted lifecycle history accepted") } catch (_: IllegalArgumentException) {} }
    @Test fun combinedKindTransitionClosesOnlyWithExactRestoredReceipt() {
        val verified = result(); val receipt = PersonalUpdateAcknowledgementReceipt.of(verified,nonce)
        val completed = PersonalUpdateCompletion.of(verified,PersonalUpdateAcknowledgementRecord(receipt,PersonalUpdateAcknowledgementState.RESTORED))
        assertEquals(completed,completed.snapshot());assertEquals(intent,completed.result.record.preparation.intent)
        assertEquals(event,completed.result.event);assertEquals(content.details,completed.receipt.projection.change.content.details)
        assertEquals(0x123456,completed.receipt.projection.change.content.colorRgb)
        assertEquals(PersonalUpdateAttemptState.OUTCOME_UNKNOWN,completed.result.record.state)
        refuse { PersonalUpdateCompletion.of(verified,PersonalUpdateAcknowledgementRecord(receipt,PersonalUpdateAcknowledgementState.PENDING)) }
        refuse { PersonalUpdateCompletion.of(result(actual=event.copy(revision=Revision("different"))),PersonalUpdateAcknowledgementRecord(receipt,PersonalUpdateAcknowledgementState.RESTORED)) }
    }
    @Test fun verifiedReadbackRequiresAllExpectedTextDatesZoneAndExactMetadata() {
        val variants = listOf(event.copy(eventId=EventId("other")),event.copy(revision=intent.base.revision),
            event.copy(content=event.content.copy(title="Wrong")),event.copy(content=event.content.copy(description="Wrong")),
            event.copy(content=event.content.copy(location="Wrong")),event.copy(content=event.content.copy(time=time)),
            event.copy(content=event.content.copy(colorRgb=0x654321)),event.copy(content=event.content.copy(details=EventDetails())),
            event.copy(content=event.content.copy(details=null)),event.copy(content=event.content.copy(details=EventDetails(listOf(EventReminder(30),EventReminder(5))))))
        for(actual in variants) refuse { result(actual=actual) }
        refuse { result(record().copy(state=PersonalUpdateAttemptState.PREPARED)) }
        assertNotEquals(result(),result(actual=event.copy(content=intent.content)))
    }
    @Test fun identityBindsEveryChangedFieldAndBaseMetadataBeforeClaim() {
        val first = PersonalUpdateAttemptIdentity.of(record())
        val variants = listOf(intent.copy(content=intent.content.copy(title="Other")),intent.copy(content=intent.content.copy(description="Other")),
            intent.copy(content=intent.content.copy(location="Other")),intent.copy(content=intent.content.copy(time=time)),
            intent.copy(scope=scope.copy(generation=5)),intent.copy(base=intent.base.copy(revision=Revision("different"))))
        for(edit in variants) assertNotEquals(first,PersonalUpdateAttemptIdentity.of(record(edit)))
        assertEquals(first,PersonalUpdateAttemptIdentity.of(record().copy(protectedEvidence="first-evidence")))
        assertEquals(first,PersonalUpdateAttemptIdentity.of(record().copy(state=PersonalUpdateAttemptState.PREPARED)))
        assertNotEquals(first,PersonalUpdateAttemptIdentity.of(record().copy(protectedPayload="different-request")))
    }
    @Test fun nativeReceiptAndProofOwnMutableReminderListsAndRedactTheirContents() {
        val reminders = mutableListOf(EventReminder(5),EventReminder(30))
        val actual = event.copy(content=event.content.copy(details=EventDetails(reminders)))
        val verified = result(actual=actual);val receipt=PersonalUpdateAcknowledgementReceipt.of(verified,nonce)
        reminders.clear()
        assertEquals(content.details,verified.event.content.details);assertEquals(content.details,receipt.projection.change.content.details)
        assertTrue(receipt.matches(verified));assertEquals(receipt,receipt.snapshot())
        for(value in listOf(verified,receipt,record(),PersonalUpdateAttemptIdentity.of(record()))) {
            for(secret in listOf("fixture-account","New title","protected-request","Old notes")) assertFalse(value.toString().contains(secret))
        }
    }
}
