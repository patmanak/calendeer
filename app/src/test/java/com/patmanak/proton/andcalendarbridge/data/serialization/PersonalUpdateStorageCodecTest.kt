// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.serialization

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class PersonalUpdateStorageCodecTest {
    private val scope = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 3)
    private val stamp = Instant.parse("2026-10-17T12:00:00Z")
    private val time = EventTime.Timed(stamp, stamp.plusSeconds(3600), ZoneOffset.UTC)
    private val original = EventContent("Fixture", time, "Notes\r\nNext", "Room", colorRgb = 0x8f6add, details = EventDetails(reminders = listOf(EventReminder(5), EventReminder(60))))
    private val intent = LocalEditIntent(MutationId("fixture-owner"), scope, AdapterFormat("fixture-import", 1),
        RemoteChange.Upsert(EventId("fixture-event"), Revision("fixture-base"), original),
        original.copy(title = "Changed", time = EventTime.AllDay(java.time.LocalDate.of(2026, 10, 18), java.time.LocalDate.of(2026, 10, 20))))
    private fun record() = PersonalUpdateAttemptRecord(MutationId("fixture-request"),
        PersonalEditPreparation(intent, stamp, AdapterFormat("fixture-time-prep", 1), "protected-preparation"),
        AdapterFormat("fixture-time-wire", 1), "protected-request")
    private fun result(): PersonalUpdateVerifiedResult {
        val unknown = record().copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
        val observed = intent.base.copy(revision = Revision("fixture-new"), content = intent.content.copy(description = "Notes\nNext"))
        return PersonalUpdateVerifiedResult.of(unknown, PersonalUpdateSubmissionObservation.Verified(unknown, observed))
    }
    private fun refused(action: () -> Unit) {
        try { action(); fail("Expected invalid personal capsule refusal") } catch (_: IllegalArgumentException) { }
    }

    @Test fun fullRawAttemptEvidenceAndUnknownStateRoundTripWithSeparateTag() {
        for (record in listOf(record(), record().copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN),
            record().copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "protected-response"))) {
            val encoded = PersonalUpdateAttemptStorageCodec.encode(record)
            assertEquals(record, PersonalUpdateAttemptStorageCodec.decode(encoded))
            assertEquals("Notes\r\nNext", PersonalUpdateAttemptStorageCodec.decode(encoded).preparation.intent.content.description)
            refused { TextUpdateAttemptStorageCodec.decode(encoded) }
            refused { TimeUpdateAttemptStorageCodec.decode(encoded) }
            assertEquals(original.details, PersonalUpdateAttemptStorageCodec.decode(encoded).preparation.intent.base.content.details)
            assertEquals(original.colorRgb, PersonalUpdateAttemptStorageCodec.decode(encoded).preparation.intent.content.colorRgb)
            refused { PersonalUpdateAttemptStorageCodec.decode(encoded + byteArrayOf(0)) }
            refused { PersonalUpdateAttemptStorageCodec.decode(encoded.copyOf(encoded.size - 1)) }
        }
    }

    @Test fun textCapsulesCannotBecomeTemporalWorkEvenWithMatchingLocators() {
        val empty = original.copy(colorRgb = null, details = EventDetails())
        val textIntent = intent.copy(base = intent.base.copy(content = empty), content = empty.copy(description = "Changed notes"))
        val text = TextUpdateAttemptRecord(MutationId("fixture-request"),
            LocalTextEditPreparation(textIntent, stamp, AdapterFormat("fixture-text-prep", 1), "protected-preparation"),
            AdapterFormat("fixture-text-wire", 1), "protected-request")
        refused { PersonalUpdateAttemptStorageCodec.decode(TextUpdateAttemptStorageCodec.encode(text)) }
    }

    @Test fun fullResultAndRestoredCompletionKeepActualReadbackSeparateFromRawIntent() {
        val result = result()
        val actual = PersonalUpdateProofStorageCodec.decodeResult(PersonalUpdateProofStorageCodec.encodeResult(result))
        assertEquals(result, actual)
        assertEquals("Notes\nNext", actual.event.content.description)
        assertEquals("Notes\r\nNext", actual.record.preparation.intent.content.description)
        val receipt = PersonalUpdateAcknowledgementReceipt.of(result, "11111111-2222-3333-4444-555555555555")
        assertEquals(receipt, PersonalUpdateAcknowledgementCodec.decode(PersonalUpdateAcknowledgementCodec.encode(receipt)))
        val completion = PersonalUpdateCompletion.of(result, PersonalUpdateAcknowledgementRecord(receipt, PersonalUpdateAcknowledgementState.RESTORED))
        val encoded = PersonalUpdateProofStorageCodec.encodeCompletion(completion)
        assertEquals(completion, PersonalUpdateProofStorageCodec.decodeCompletion(encoded))
        refused { TextUpdateProofStorageCodec.decodeResult(PersonalUpdateProofStorageCodec.encodeResult(result)) }
        refused { TextUpdateProofStorageCodec.decodeCompletion(encoded) }
    }

    @Test fun alteredResultAndReceiptCannotCloseAnotherRequestOrDateTarget() {
        val result = result()
        val receipt = PersonalUpdateAcknowledgementReceipt.of(result, "11111111-2222-3333-4444-555555555555")
        val payload = PersonalUpdateAcknowledgementCodec.encode(receipt)
        refused { PersonalUpdateAcknowledgementCodec.decode(payload.replace("personal-update-acknowledgement", "text-update-acknowledgement")) }
        refused { PersonalUpdateAcknowledgementCodec.decode(payload.replace("fixture-request", "different-request"))
            .let { PersonalUpdateCompletion.of(result, PersonalUpdateAcknowledgementRecord(it, PersonalUpdateAcknowledgementState.RESTORED)) } }
        refused { PersonalUpdateCompletion.of(result, PersonalUpdateAcknowledgementRecord(receipt, PersonalUpdateAcknowledgementState.PENDING)) }
    }
}
