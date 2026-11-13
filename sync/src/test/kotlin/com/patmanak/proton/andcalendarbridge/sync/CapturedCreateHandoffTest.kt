// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CapturedCreateHandoffTest {
    @Test fun projectionConstructionAndCopyOwnTheirPreparedReceipt() {
        val reminders = mutableListOf(EventReminder(10))
        val copyReminders = mutableListOf(EventReminder(20))
        val previousReminders = mutableListOf<EventReminder>()
        val incoming = newer.copy(content = content.copy(details = EventDetails(reminders), colorRgb = 0x123456))
        val prepared = projection.copy(change = incoming,
            previousBase = change.copy(content = content.copy(details = EventDetails(previousReminders))))
        val plan = CapturedHandoffProjection(frame = frame, receipt = prepared)
        val copied = plan.copy(receipt = prepared.copy(change = incoming.copy(
            content = incoming.content.copy(details = EventDetails(copyReminders)))))
        val equal = plan.copy()
        val hash = plan.hashCode()
        val copyHash = copied.hashCode()
        reminders.clear(); copyReminders.clear()
        previousReminders += EventReminder(30)
        assertEquals(listOf(EventReminder(10)), plan.receipt.change.content.details!!.reminders)
        assertEquals(listOf(EventReminder(20)), copied.receipt.change.content.details!!.reminders)
        assertEquals(EventDetails(), plan.receipt.previousBase!!.content.details)
        assertEquals(EventDetails(), copied.receipt.previousBase!!.content.details)
        assertEquals(equal, plan)
        assertEquals(hash, plan.hashCode())
        assertEquals(copyHash, copied.hashCode())
        assertNotEquals(plan, copied)
        val (preparedFrame, preparedReceipt) = plan
        assertEquals(frame, preparedFrame)
        assertEquals(plan.receipt, preparedReceipt)
        assertThrows(UnsupportedOperationException::class.java) {
            (plan.receipt.change.content.details!!.reminders as MutableList<EventReminder>).clear()
        }
    }
    @Test fun frameConstructionAndCopyOwnTheirInputsAndKeepValueSemantics() {
        val originReminders = mutableListOf<EventReminder>()
        val origin = baseline.copy(change = change.copy(content = content.copy(details = EventDetails(originReminders))))
        val transfer = handoff.copy(acknowledgement = ack.copy(baseline = origin))
        val previousReminders = mutableListOf(EventReminder(10))
        val baseReminders = mutableListOf(EventReminder(20))
        val copyReminders = mutableListOf(EventReminder(30))
        val previous = newer.copy(content = content.copy(details = EventDetails(previousReminders)))
        val base = newer.copy(content = content.copy(details = EventDetails(baseReminders), colorRgb = 0x123456))
        val last = projection.copy(change = base, previousBase = previous)
        val owned = CapturedCreateHandoffFrame(receipt = transfer, base = base, lastProjection = last)
        val copyBase = base.copy(content = base.content.copy(details = EventDetails(copyReminders)))
        val copied = owned.copy(base = copyBase, lastProjection = last.copy(change = copyBase))
        val equal = owned.copy()
        val originalHash = owned.hashCode()
        val copiedHash = copied.hashCode()
        originReminders += EventReminder(40)
        previousReminders.clear(); baseReminders.clear(); copyReminders.clear()
        assertEquals(listOf(EventReminder(20)), owned.base.content.details!!.reminders)
        assertEquals(listOf(EventReminder(30)), copied.base.content.details!!.reminders)
        assertEquals(listOf(EventReminder(10)), owned.lastProjection!!.previousBase!!.content.details!!.reminders)
        assertEquals(EventDetails(), owned.receipt.acknowledgement.baseline.change.content.details)
        assertEquals(equal, owned)
        assertEquals(originalHash, owned.hashCode())
        assertEquals(copiedHash, copied.hashCode())
        assertNotEquals(owned, copied)
        val (copiedReceipt, copiedBase, copiedLast) = copied
        assertEquals(copied.receipt, copiedReceipt)
        assertEquals(copied.base, copiedBase)
        assertEquals(copied.lastProjection, copiedLast)
        assertThrows(UnsupportedOperationException::class.java) {
            (owned.lastProjection!!.previousBase!!.content.details!!.reminders as MutableList<EventReminder>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (copied.base.content.details!!.reminders as MutableList<EventReminder>).clear()
        }
    }
    @Test fun currentConstructionAndCopyOwnTheObservedContentBeforeItCanChange() {
        val reminders = mutableListOf(EventReminder(10))
        val copyReminders = mutableListOf(EventReminder(10))
        val rich = content.copy(details = EventDetails(reminders), colorRgb = 0x123456)
        val projected = projection.copy(change = newer.copy(content = rich))
        val advanced = CapturedCreateHandoffFrame(handoff, projected.change, projected)
        val current = CapturedHandoffCurrent(frame = advanced, content = rich, deleted = false)
        val copied = current.copy(content = rich.copy(details = EventDetails(copyReminders)))
        val hash = current.hashCode()
        reminders.clear(); copyReminders.clear()
        assertEquals(listOf(EventReminder(10)), current.content.details!!.reminders)
        assertEquals(current, copied)
        assertEquals(hash, current.hashCode())
        assertEquals(hash, copied.hashCode())
        assertTrue(current.unchanged)
        assertTrue(copied.unchanged)
        assertFalse(copied.copy(deleted = true).unchanged)
        val (currentFrame, currentContent, deleted) = copied
        assertEquals(advanced, currentFrame)
        assertEquals(current.content, currentContent)
        assertFalse(deleted)
        assertThrows(UnsupportedOperationException::class.java) {
            (copied.content.details!!.reminders as MutableList<EventReminder>).clear()
        }
    }
    @Test fun personalMetadataAfterTransferPreservesTheStrictOriginalCreationEvidence() {
        val rich = content.copy(details = EventDetails(listOf(EventReminder(10))), colorRgb = 0x123456)
        assertTrue(supportsPersonalHandoff(change.copy(content = rich)))
        assertEquals(rich, CapturedHandoffCurrent(frame, rich, false).content)
        assertThrows(IllegalArgumentException::class.java) { CapturedCreateCurrent(baseline, rich, false) }
        assertFalse(supportsCapturedHandoff(change.copy(content = rich)))
        val projected = projection.copy(change = newer.copy(content = rich))
        CapturedHandoffProjection(frame, projected)
        val advanced = CapturedCreateHandoffFrame(handoff, projected.change, projected)
        assertEquals(change, advanced.receipt.acknowledgement.baseline.change)
        assertEquals(rich, advanced.base.content)
    }
    @Test fun personalAcknowledgementOwnsTheCompleteCurrentHandoffHistory() {
        val previousReminders = mutableListOf(EventReminder(10))
        val currentReminders = mutableListOf(EventReminder(20))
        val previous = newer.copy(content = content.copy(details = EventDetails(previousReminders), colorRgb = 0x123456))
        val current = previous.copy(revision = Revision("current"),
            content = previous.content.copy(details = EventDetails(currentReminders), colorRgb = 0xabcdef))
        val last = ProjectionReceipt(scope, baseline.format, current, UUID(0, 32).toString(), previous)
        val advanced = CapturedCreateHandoffFrame(handoff, current, last)
        val edit = LocalEditIntent(MutationId("local-edit"), scope, baseline.format, current,
            current.content.copy(title = "Edited after transfer"))
        val attempt = PersonalUpdateAttemptIdentity(scope, MutationId("personal-attempt"), edit.id,
            current.eventId, current.revision, AdapterFormat("request", 2), AdapterFormat("preparation", 2),
            "a".repeat(64), "b".repeat(64))
        val projected = ProjectionReceipt(scope, baseline.format,
            current.copy(revision = Revision("acknowledged"), content = edit.content), UUID(0, 33).toString(), current)
        val acknowledgement = PersonalUpdateAcknowledgementReceipt(attempt, edit, projected, advanced)
        previousReminders.clear(); currentReminders.clear()
        val frozen = acknowledgement.handoff!!
        val frozenLast = frozen.lastProjection!!
        assertEquals(listOf(EventReminder(20)), frozen.base.content.details!!.reminders)
        assertEquals(0xabcdef, frozen.base.content.colorRgb)
        assertEquals(listOf(EventReminder(10)), frozenLast.previousBase!!.content.details!!.reminders)
        assertEquals(listOf(EventReminder(20)), frozenLast.change.content.details!!.reminders)
        assertEquals(change, frozen.receipt.acknowledgement.baseline.change)
        assertEquals(acknowledgement, acknowledgement.snapshot())
        assertThrows(UnsupportedOperationException::class.java) {
            (frozen.base.content.details!!.reminders as MutableList<EventReminder>).add(EventReminder(30))
        }
    }
    @Test fun personalHandoffRejectsParticipantsUnknownMetadataAndMalformedText() {
        val person = EventPerson("fixture@example.test")
        val attendee = EventAttendee(person, AttendanceRole.REQUIRED, AttendanceStatus.NEEDS_ACTION)
        val unsupported = listOf(content.copy(details = null), content.copy(title = "\uD800"),
            content.copy(details = EventDetails(organizer = person)),
            content.copy(details = EventDetails(attendees = listOf(attendee))))
        for (value in unsupported) {
            assertFalse(supportsPersonalHandoff(change.copy(content = value)))
            assertThrows(IllegalArgumentException::class.java) { CapturedHandoffCurrent(frame, value, false) }
            assertThrows(IllegalArgumentException::class.java) {
                CapturedHandoffProjection(frame, projection.copy(change = newer.copy(content = value)))
            }
        }
        for (color in listOf(-1, 0x1000000))
            assertThrows(IllegalArgumentException::class.java) { content.copy(colorRgb = color) }
        assertFalse(supportsPersonalHandoff(RemoteChange.Deleted(change.eventId, change.revision)))
    }
    @Test fun transferReceiptOwnsItsOriginalAndRefusesUnboundRichCreationEvidence() {
        val reminders = mutableListOf<EventReminder>()
        val original = baseline.copy(change = change.copy(content = content.copy(details = EventDetails(reminders))))
        reminders += EventReminder(10)
        assertEquals(EventDetails(), original.change.content.details)
        assertEquals(original, CapturedCreateHandoffReceipt(ack.copy(baseline = original), UUID(0, 35).toString()).acknowledgement.baseline)
        assertThrows(IllegalArgumentException::class.java) { baseline.copy(change = change.copy(
            content = content.copy(details = EventDetails(reminders)))) }
    }
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
    private val baseline = CapturedCreateBaseline(receipt, change)
    private val ack = CapturedCreateAcknowledgementReceipt(baseline, UUID(0, 9).toString())
    private val handoff = CapturedCreateHandoffReceipt(ack, UUID(0, 30).toString())
    private val frame = CapturedCreateHandoffFrame(handoff, change)
    private val newer = change.copy(revision = Revision("newer"), content = content.copy(title = "Incoming update"))
    private val projection = ProjectionReceipt(scope, baseline.format, newer, UUID(0, 31).toString(), change)
    @Test fun immutableOriginAndCurrentReferenceAreIndependent() {
        val advanced = CapturedCreateHandoffFrame(handoff, newer, projection)
        assertEquals(change, advanced.receipt.acknowledgement.baseline.change)
        assertEquals(newer, advanced.base)
        assertEquals(projection, advanced.lastProjection)
    }
    @Test fun currentSnapshotComparesTheCurrentReferenceInsteadOfTheOriginal() {
        val advanced = CapturedCreateHandoffFrame(handoff, newer, projection)
        assertTrue(CapturedHandoffCurrent(advanced, newer.content, false).unchanged)
        assertFalse(CapturedHandoffCurrent(advanced, content, false).unchanged)
        assertFalse(CapturedHandoffCurrent(advanced, newer.content, true).unchanged)
    }
    @Test fun originalCannotBeAdvancedWithoutFullHistoricalReceipt() {
        assertThrows(IllegalArgumentException::class.java) { frame.copy(base = newer) }
        assertThrows(IllegalArgumentException::class.java) { frame.copy(lastProjection = projection) }
        assertThrows(IllegalArgumentException::class.java) {
            CapturedCreateHandoffFrame(handoff, newer, projection.copy(scope = scope.copy(generation = 1)))
        }
    }
    @Test fun preparedProjectionRequiresTheWholePreviousReference() {
        val plan = CapturedHandoffProjection(frame, projection)
        assertEquals(frame, plan.frame)
        assertThrows(IllegalArgumentException::class.java) { plan.copy(receipt = projection.copy(previousBase = null)) }
        assertThrows(IllegalArgumentException::class.java) { plan.copy(receipt = projection.copy(previousBase = change.copy(content = content.copy(title = "Other")))) }
    }
    @Test fun everyAdmissionNonceIsDistinctAndCanonical() {
        for (nonce in listOf(ack.nonce, receipt.nonce, "foreign"))
            assertThrows(IllegalArgumentException::class.java) { handoff.copy(nonce = nonce) }
        for (nonce in listOf(ack.nonce, receipt.nonce, handoff.nonce))
            assertThrows(IllegalArgumentException::class.java) { CapturedHandoffProjection(frame, projection.copy(nonce = nonce)) }
        assertThrows(IllegalArgumentException::class.java) { handoff.copy(protocol = 2) }
    }
    @Test fun pendingRoleCannotInventProgressOrWaiting() {
        assertThrows(IllegalArgumentException::class.java) { CapturedCreateHandoffRecord(frame, CapturedCreateHandoffState.PENDING, true) }
        assertThrows(IllegalArgumentException::class.java) { CapturedCreateHandoffRecord(CapturedCreateHandoffFrame(handoff, newer, projection), CapturedCreateHandoffState.PENDING) }
    }
    @Test fun hashIncludesTheWholeOriginalAcknowledgementAndTransferNonce() {
        assertTrue(handoff.fingerprint().matches(Regex("[a-f0-9]{64}")))
        assertNotEquals(handoff.fingerprint(), handoff.copy(nonce = UUID(0, 32).toString()).fingerprint())
        assertNotEquals(handoff.fingerprint(), handoff.copy(acknowledgement = ack.copy(nonce = UUID(0, 33).toString())).fingerprint())
        val changed = baseline.copy(receipt = receipt.copy(nonce = UUID(0, 34).toString()))
        assertNotEquals(handoff.fingerprint(), handoff.copy(acknowledgement = ack.copy(baseline = changed)).fingerprint())
    }
    @Test fun unknownAndRicherRemoteDataCannotBeUsedForTransfer() {
        for (content in listOf(content.copy(details = null), content.copy(colorRgb = 0x123456), content.copy(title = "\uD800")))
            assertFalse(supportsCapturedHandoff(change.copy(content = content)))
        assertFalse(supportsCapturedHandoff(RemoteChange.Deleted(change.eventId, change.revision)))
        assertTrue(supportsCapturedHandoff(change))
    }
    @Test fun allProofsAndSnapshotsRedactData() {
        val plan = CapturedHandoffProjection(frame, projection)
        for (value in listOf(handoff, frame, plan, AppliedCapturedHandoffProjection(plan),
            CapturedHandoffCleanWitness(frame), CapturedHandoffCurrent(frame, content, false),
            CapturedCreateHandoffRecord(frame, CapturedCreateHandoffState.PENDING)))
            assertTrue(value.toString().endsWith("(REDACTED)"))
    }
}
