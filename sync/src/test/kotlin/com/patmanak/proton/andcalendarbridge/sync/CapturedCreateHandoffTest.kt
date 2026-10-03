// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CapturedCreateHandoffTest {
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