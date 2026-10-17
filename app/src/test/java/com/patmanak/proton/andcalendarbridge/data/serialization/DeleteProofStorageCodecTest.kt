// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.serialization

import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class DeleteProofStorageCodecTest {
    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 3)
    private val content = EventContent("Synthetic deletion", EventTime.Timed(
        Instant.parse("2026-10-25T10:00:00Z"), Instant.parse("2026-10-25T11:00:00Z"), ZoneId.of("Europe/Paris")),
        "Synthetic notes\r\nSecond line", "Synthetic place", details = EventDetails())
    private val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-base"), content)
    private val prepared = DeleteAttemptRecord(MutationId("synthetic-request"),
        LocalDeleteIntent(MutationId("synthetic-owner"), scope, AdapterFormat("observation", 1), base, content),
        AdapterFormat("delete-wire", 1), "opaque-request")
    private val unknown = prepared.copy(state = DeleteAttemptState.OUTCOME_UNKNOWN)
    private val verified = DeleteSubmissionObservation.Verified(unknown)
    private val result = DeleteVerifiedResult.of(unknown, verified)
    private val receipt = DeleteAcknowledgementReceipt.of(verified, "00000000-0000-0000-0000-000000000001")
    private val completion = DeleteCompletion.of(result, DeleteAcknowledgementRecord(receipt, DeleteAcknowledgementState.RESTORED))

    @Test fun resultAndCompletionRetainExactUnknownRequestAndReceipt() {
        assertEquals(result, DeleteProofStorageCodec.decodeResult(DeleteProofStorageCodec.encodeResult(result)))
        assertEquals(completion, DeleteProofStorageCodec.decodeCompletion(DeleteProofStorageCodec.encodeCompletion(completion)))
    }
    @Test fun literalAbsenceFrameUsesNoFabricatedEventOrRevision() {
        val bytes = frame("CALENDAR_DELETE_RESULT", DeleteAttemptStorageCodec.encode(unknown), "AUTHENTICATED_ABSENCE_V1".toByteArray())
        assertArrayEquals(bytes, DeleteProofStorageCodec.encodeResult(result))
        assertEquals(result, DeleteProofStorageCodec.decodeResult(bytes))
    }
    @Test fun evidenceIsExcludedFromHistoricalProof() {
        val evidenced = unknown.copy(protectedEvidence = "opaque-evidence")
        assertArrayEquals(DeleteProofStorageCodec.encodeResult(result), DeleteProofStorageCodec.encodeResult(
            DeleteVerifiedResult.of(evidenced, DeleteSubmissionObservation.Verified(evidenced))))
        refused(frame("CALENDAR_DELETE_RESULT", DeleteAttemptStorageCodec.encode(evidenced), "AUTHENTICATED_ABSENCE_V1".toByteArray()))
    }
    @Test fun preparedStateAndForeignAbsenceKindsAreRejected() {
        refused(frame("CALENDAR_DELETE_RESULT", DeleteAttemptStorageCodec.encode(prepared), "AUTHENTICATED_ABSENCE_V1".toByteArray()))
        for (marker in listOf("ABSENT", "AUTHENTICATED_ABSENCE_V2", "AUTHENTICATED_ABSENCE_V1\u0000"))
            refused(frame("CALENDAR_DELETE_RESULT", DeleteAttemptStorageCodec.encode(unknown), marker.toByteArray()))
    }
    @Test fun truncationTrailingBytesVersionAndLengthsFailClosed() {
        val bytes = DeleteProofStorageCodec.encodeResult(result)
        for (size in 0 until bytes.size) refused(bytes.copyOf(size))
        refused(bytes + byteArrayOf(0))
        for (value in listOf(-1, 0, Int.MAX_VALUE)) {
            refused(bytes.copyOf().also { ByteBuffer.wrap(it).putInt("CALENDAR_DELETE_RESULT".length + 4, value) })
        }
        refused(bytes.copyOf().also { ByteBuffer.wrap(it).putInt("CALENDAR_DELETE_RESULT".length, 2) })
        refused(ByteArray(DeleteProofStorageCodec.MAX_BYTES + 1))
    }
    @Test fun malformedUtf8IsNeverReplacementDecoded() {
        refused(frame("CALENDAR_DELETE_RESULT", DeleteAttemptStorageCodec.encode(unknown), byteArrayOf(0xc0.toByte(), 0x80.toByte())))
    }
    @Test fun completionCannotSubstituteRequestReceiptOrFraming() {
        val other = unknown.copy(id = MutationId("foreign-request"))
        val foreign = DeleteAcknowledgementReceipt.of(DeleteSubmissionObservation.Verified(other), receipt.nonce)
        refusedCompletion(frame("CALENDAR_DELETE_COMPLETION", DeleteProofStorageCodec.encodeResult(result), DeleteAcknowledgementCodec.encode(foreign).toByteArray()))
        val bytes = DeleteProofStorageCodec.encodeCompletion(completion)
        for (size in listOf(0, 1, bytes.size - 1)) refusedCompletion(bytes.copyOf(size))
        refusedCompletion(bytes + byteArrayOf(0))
        refusedCompletion(DeleteProofStorageCodec.encodeResult(result))
    }
    private fun frame(tag: String, first: ByteArray, second: ByteArray): ByteArray =
        ByteBuffer.allocate(tag.length + 12 + first.size + second.size).apply {
            put(tag.toByteArray()); putInt(1); putInt(first.size); put(first); putInt(second.size); put(second)
        }.array()
    private fun refused(bytes: ByteArray) = invalid { DeleteProofStorageCodec.decodeResult(bytes) }
    private fun refusedCompletion(bytes: ByteArray) = invalid { DeleteProofStorageCodec.decodeCompletion(bytes) }
    private fun invalid(action: () -> Unit) {
        try { action(); fail("Invalid proof accepted") }
        catch (failure: IllegalArgumentException) { assertEquals("Invalid delete proof capsule", failure.message); assertNull(failure.cause) }
    }
}
