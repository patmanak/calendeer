// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

class CapturedCreateAttachmentReceiptTest {
    private val binding = CapturedCreateBinding(CapturedCreateDispatchIdentity(
        CreateAttemptIdentity(CalendarScope(AccountId("fixture"), CalendarId("calendar"), 0), MutationId("attempt"),
            AdapterFormat("submission", 2), "a".repeat(64)), LocalEventId(UUID.randomUUID().toString()),
        MutationId("capture"), AdapterFormat("import", 1), Instant.parse("2030-10-01T12:00:00Z"), "b".repeat(64)),
        EventId("remote"), Revision("first"))
    @Test fun historicalWitnessRetainsWholePreparedReceipt() {
        val receipt = CapturedCreateAttachmentReceipt(binding, UUID.randomUUID().toString())
        assertEquals(receipt, AppliedCapturedCreateAttachment(receipt).receipt)
        assertNotEquals(receipt, receipt.copy(binding = binding.copy(verifiedRevision = Revision("later"))))
        assertNotEquals(receipt, receipt.copy(nonce = UUID.randomUUID().toString()))
    }
    @Test fun unsupportedProtocolIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { CapturedCreateAttachmentReceipt(binding, UUID.randomUUID().toString(), 2) }
    }
    @Test fun malformedNonceIsRefused() {
        for (nonce in listOf("", "arbitrary", "ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB"))
            assertThrows(IllegalArgumentException::class.java) { CapturedCreateAttachmentReceipt(binding, nonce) }
    }
    @Test fun diagnosticsExposeNoPrivateReceiptFields() {
        val receipt = CapturedCreateAttachmentReceipt(binding, UUID.randomUUID().toString())
        assertEquals("CapturedCreateAttachmentReceipt(REDACTED)", receipt.toString())
        assertEquals("AppliedCapturedCreateAttachment(REDACTED)", AppliedCapturedCreateAttachment(receipt).toString())
    }
}
