// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.serialization

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class PersonalUpdateStorageCodecTest {
    private fun seriesRecord(): PersonalUpdateAttemptRecord {
        val before = original.copy(recurrence = EventRecurrence("storage-series", "FREQ=DAILY;COUNT=3"))
        val after = intent.content.copy(recurrence = EventRecurrence("storage-series", "FREQ=DAILY;COUNT=5"))
        val local = intent.copy(base = intent.base.copy(content = before), content = after,
            seriesProfile = SeriesEditProfile.of(before, after))
        return PersonalUpdateAttemptRecord(MutationId("series-wire"),
            PersonalEditPreparation.forSeries(local, stamp, AdapterFormat("fixture-series-prep", 3), "series-prepared"),
            AdapterFormat("fixture-series-wire", 3), "series-request", PersonalUpdateAttemptState.OUTCOME_UNKNOWN, "series-evidence")
    }

    @Test fun seriesAttemptHasAnExplicitNewStorageSchemaAndUnknownHistoryStaysImmutable() {
        val record = seriesRecord()
        val encoded = PersonalUpdateAttemptStorageCodec.encode(record)
        assertEquals(record, PersonalUpdateAttemptStorageCodec.decode(encoded))
        assertTrue(PersonalUpdateAttemptStorageCodec.decode(encoded).preparation.isSeries)
        val downgraded = encoded.copyOf()
        java.nio.ByteBuffer.wrap(downgraded).putInt("CALENDAR_PERSONAL_UPDATE_ATTEMPT".length, 2)
        refused { PersonalUpdateAttemptStorageCodec.decode(downgraded) }
        refused { PersonalUpdateAttemptStorageCodec.decode(encoded + byteArrayOf(0)) }
    }

    @Test fun seriesAcknowledgementAndCompletionRoundTripWithoutOpeningLegacySchemas() {
        val record = seriesRecord()
        val event = record.preparation.intent.base.copy(revision = Revision("series-after"), content = record.preparation.intent.content)
        val result = PersonalUpdateVerifiedResult.of(record, PersonalUpdateSubmissionObservation.Verified(record, event))
        val receipt = PersonalUpdateAcknowledgementReceipt.of(result, "11111111-2222-3333-4444-555555555555")
        val encoded = PersonalUpdateAcknowledgementCodec.encode(receipt)
        assertEquals(receipt, PersonalUpdateAcknowledgementCodec.decode(encoded))
        refused { PersonalUpdateAcknowledgementCodec.decode(org.json.JSONObject(encoded).put("schema", 1).toString()) }
        val completion = PersonalUpdateCompletion.of(result,
            PersonalUpdateAcknowledgementRecord(receipt, PersonalUpdateAcknowledgementState.RESTORED))
        assertEquals(completion, PersonalUpdateProofStorageCodec.decodeCompletion(PersonalUpdateProofStorageCodec.encodeCompletion(completion)))
    }

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
