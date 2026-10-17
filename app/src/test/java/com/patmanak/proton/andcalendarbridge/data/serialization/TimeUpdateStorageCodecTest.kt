// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.serialization

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class TimeUpdateStorageCodecTest {
    private val scope = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 3)
    private val stamp = Instant.parse("2026-10-17T12:00:00Z")
    private val time = EventTime.Timed(stamp, stamp.plusSeconds(3600), ZoneOffset.UTC)
    private val original = EventContent("Fixture", time, "Notes\r\nNext", "Room", details = EventDetails())
    private val intent = LocalEditIntent(MutationId("fixture-owner"), scope, AdapterFormat("fixture-import", 1),
        RemoteChange.Upsert(EventId("fixture-event"), Revision("fixture-base"), original),
        original.copy(time = time.copy(end = time.end.plusSeconds(3600))))
    private fun record() = TimeUpdateAttemptRecord(MutationId("fixture-request"),
        LocalTimeEditPreparation(intent, stamp, AdapterFormat("fixture-time-prep", 1), "protected-preparation"),
        AdapterFormat("fixture-time-wire", 1), "protected-request")
    private fun result(): TimeUpdateVerifiedResult {
        val unknown = record().copy(state = TimeUpdateAttemptState.OUTCOME_UNKNOWN)
        val observed = intent.base.copy(revision = Revision("fixture-new"), content = intent.content.copy(description = "Notes\nNext"))
        return TimeUpdateVerifiedResult.of(unknown, TimeUpdateSubmissionObservation.Verified(unknown, observed))
    }
    private fun refused(action: () -> Unit) {
        try { action(); fail("Expected invalid temporal capsule refusal") } catch (_: IllegalArgumentException) { }
    }

    @Test fun fullRawAttemptEvidenceAndUnknownStateRoundTripWithSeparateTag() {
        for (record in listOf(record(), record().copy(state = TimeUpdateAttemptState.OUTCOME_UNKNOWN),
            record().copy(state = TimeUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "protected-response"))) {
            val encoded = TimeUpdateAttemptStorageCodec.encode(record)
            assertEquals(record, TimeUpdateAttemptStorageCodec.decode(encoded))
            assertEquals("Notes\r\nNext", TimeUpdateAttemptStorageCodec.decode(encoded).preparation.intent.content.description)
            refused { TextUpdateAttemptStorageCodec.decode(encoded) }
            refused { TimeUpdateAttemptStorageCodec.decode(encoded + byteArrayOf(0)) }
            refused { TimeUpdateAttemptStorageCodec.decode(encoded.copyOf(encoded.size - 1)) }
        }
    }

    @Test fun textCapsulesCannotBecomeTemporalWorkEvenWithMatchingLocators() {
        val textIntent = intent.copy(content = original.copy(description = "Changed notes"))
        val text = TextUpdateAttemptRecord(MutationId("fixture-request"),
            LocalTextEditPreparation(textIntent, stamp, AdapterFormat("fixture-text-prep", 1), "protected-preparation"),
            AdapterFormat("fixture-text-wire", 1), "protected-request")
        refused { TimeUpdateAttemptStorageCodec.decode(TextUpdateAttemptStorageCodec.encode(text)) }
    }

    @Test fun fullResultAndRestoredCompletionKeepActualReadbackSeparateFromRawIntent() {
        val result = result()
        val actual = TimeUpdateProofStorageCodec.decodeResult(TimeUpdateProofStorageCodec.encodeResult(result))
        assertEquals(result, actual)
        assertEquals("Notes\nNext", actual.event.content.description)
        assertEquals("Notes\r\nNext", actual.record.preparation.intent.content.description)
        val receipt = TimeUpdateAcknowledgementReceipt.of(result, "11111111-2222-3333-4444-555555555555")
        assertEquals(receipt, TimeUpdateAcknowledgementCodec.decode(TimeUpdateAcknowledgementCodec.encode(receipt)))
        val completion = TimeUpdateCompletion.of(result, TimeUpdateAcknowledgementRecord(receipt, TimeUpdateAcknowledgementState.RESTORED))
        val encoded = TimeUpdateProofStorageCodec.encodeCompletion(completion)
        assertEquals(completion, TimeUpdateProofStorageCodec.decodeCompletion(encoded))
        refused { TextUpdateProofStorageCodec.decodeResult(TimeUpdateProofStorageCodec.encodeResult(result)) }
        refused { TextUpdateProofStorageCodec.decodeCompletion(encoded) }
    }

    @Test fun alteredResultAndReceiptCannotCloseAnotherRequestOrDateTarget() {
        val result = result()
        val receipt = TimeUpdateAcknowledgementReceipt.of(result, "11111111-2222-3333-4444-555555555555")
        val payload = TimeUpdateAcknowledgementCodec.encode(receipt)
        refused { TimeUpdateAcknowledgementCodec.decode(payload.replace("time-update-acknowledgement", "text-update-acknowledgement")) }
        refused { TimeUpdateAcknowledgementCodec.decode(payload.replace("fixture-request", "different-request"))
            .let { TimeUpdateCompletion.of(result, TimeUpdateAcknowledgementRecord(it, TimeUpdateAcknowledgementState.RESTORED)) } }
        refused { TimeUpdateCompletion.of(result, TimeUpdateAcknowledgementRecord(receipt, TimeUpdateAcknowledgementState.PENDING)) }
    }
}
