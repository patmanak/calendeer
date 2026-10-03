// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CapturedCreateAcknowledgementTest {
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
    @Test fun canonicalReceiptRetainsSentProvenanceAndStableFingerprint() {
        assertEquals(baseline, ack.baseline); assertEquals(1, ack.protocol)
        assertTrue(ack.fingerprint().matches(Regex("[a-f0-9]{64}")))
        assertEquals(ack.fingerprint(), ack.copy().fingerprint())
    }
    @Test fun distinctAcknowledgementAndAttachmentNoncesChangeTheMarker() {
        assertNotEquals(ack.fingerprint(), ack.copy(nonce = UUID(0, 10).toString()).fingerprint())
        val other = baseline.copy(receipt = receipt.copy(nonce = UUID(0, 11).toString()))
        assertNotEquals(ack.fingerprint(), ack.copy(baseline = other).fingerprint())
    }
    @Test fun protocolAndCanonicalNonceAreRequired() {
        for (nonce in listOf("", "not-a-uuid", "FFFFFFFF-0000-0000-0000-000000000009", receipt.nonce))
            assertThrows(IllegalArgumentException::class.java) { ack.copy(nonce = nonce) }
        assertThrows(IllegalArgumentException::class.java) { ack.copy(protocol = 2) }
    }
    @Test fun fullSourceIdentityAndRevisionArePartOfTheMarker() {
        fun marker(link: CapturedCreateDispatchIdentity, revision: Revision = change.revision): String {
            val binding = receipt.binding.copy(identity = link, verifiedRevision = revision)
            return ack.copy(baseline = CapturedCreateBaseline(receipt.copy(binding = binding), change.copy(revision = revision))).fingerprint()
        }
        val changed = listOf(identity.copy(captureId = MutationId("other")),
            identity.copy(localId = LocalEventId(UUID(0, 12).toString())),
            identity.copy(timestamp = identity.timestamp.plusSeconds(1)),
            identity.copy(captureFormat = AdapterFormat("other", 2)),
            identity.copy(attempt = identity.attempt.copy(id = MutationId("different"))),
            identity.copy(attempt = identity.attempt.copy(payloadFingerprint = "a".repeat(64))))
        for (link in changed) assertNotEquals(ack.fingerprint(), marker(link))
        assertNotEquals(ack.fingerprint(), marker(identity, Revision("different")))
    }
    @Test fun malformedSourceTextIsRefusedBeforeIo() {
        val malformed = identity.copy(attempt = identity.attempt.copy(scope = scope.copy(account = AccountId("\uD800"))))
        val damaged = baseline.copy(receipt = receipt.copy(binding = receipt.binding.copy(identity = malformed)))
        assertThrows(IllegalArgumentException::class.java) { ack.copy(baseline = damaged) }
    }
    @Test fun fieldBoundariesAndNonAsciiTextAreCanonical() {
        fun marker(account: String, calendar: String): String {
            val changed = identity.copy(attempt = identity.attempt.copy(scope = scope.copy(account = AccountId(account), calendar = CalendarId(calendar))))
            return ack.copy(baseline = baseline.copy(receipt = receipt.copy(binding = receipt.binding.copy(identity = changed)))).fingerprint()
        }
        assertNotEquals(marker("ab", "c"), marker("a", "bc"))
        assertEquals(marker("é", "日"), marker("é", "日"))
    }
    @Test fun currentSnapshotRequiresMatchingAcknowledgementProvenance() {
        val current = CapturedCreateCurrent(baseline, content.copy(title = "Later"), false, ack)
        assertEquals(ack, current.acknowledgement)
        assertNotEquals(current, current.copy(acknowledgement = null))
        val other = baseline.copy(receipt = receipt.copy(nonce = UUID(0, 11).toString()))
        assertThrows(IllegalArgumentException::class.java) { current.copy(acknowledgement = ack.copy(baseline = other)) }
    }
    @Test fun diagnosticsRedactAllSourceAndAdmissionEvidence() {
        assertEquals("CapturedCreateAcknowledgementReceipt(REDACTED)", ack.toString())
        assertEquals("AppliedCapturedCreateAcknowledgement(REDACTED)", AppliedCapturedCreateAcknowledgement(ack).toString())
        val record = CapturedCreateAcknowledgementRecord(ack, CapturedCreateAcknowledgementState.UNKNOWN)
        assertEquals("CapturedCreateAcknowledgementRecord(REDACTED)", record.toString())
        assertEquals("CapturedCreateAcknowledgementReservation(REDACTED)", CapturedCreateAcknowledgementReservation(record, true).toString())
    }
}
