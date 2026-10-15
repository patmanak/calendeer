// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.serialization

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.nio.ByteBuffer
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Host structural checks with JSON-java. Physical Android JSON/SQLite remains separate evidence. */
class TextUpdateProofStorageCodecTest {
    private val scope = CalendarScope(AccountId("proof-account"), CalendarId("proof-calendar"), 3)
    private val allow: (CalendarScope) -> Boolean = { true }
    private val content = EventContent("Original", EventTime.Timed(Instant.parse("2026-10-24T10:00:00Z"),
        Instant.parse("2026-10-24T11:00:00Z"), ZoneId.of("Europe/Paris")), "Old\r\nnotes", "Old room", details = EventDetails())
    private fun work(event: String = "proof-event", id: String = "proof-request", target: CalendarScope = scope,
        payload: String = "opaque-request"): TextUpdateAttemptRecord {
        val intent = LocalEditIntent(MutationId("intent-$id"), target, AdapterFormat("fixture-import", 1),
            RemoteChange.Upsert(EventId(event), Revision("revision-before"), content),
            content.copy(title = "New", description = "New\r\nnotes", location = "New room"))
        return TextUpdateAttemptRecord(MutationId(id), LocalTextEditPreparation(intent, Instant.parse("2026-10-15T09:00:00Z"),
            AdapterFormat("fixture-text-preparation", 1), "opaque-preparation"), AdapterFormat("fixture-request", 1), payload)
    }
    private fun title(event: String = "proof-event", id: String = "next-title", target: CalendarScope = scope): TitleUpdateAttemptRecord {
        val intent = LocalEditIntent(MutationId("intent-$id"), target, AdapterFormat("fixture-import", 1),
            RemoteChange.Upsert(EventId(event), Revision("revision-before"), content), content.copy(title = "New title"))
        return TitleUpdateAttemptRecord(MutationId(id), LocalEditPreparation(intent, Instant.parse("2026-10-15T09:00:00Z"),
            AdapterFormat("fixture-title", 1), "opaque-title-preparation"), AdapterFormat("fixture-request", 1), "opaque-title-request")
    }
    private fun result(record: TextUpdateAttemptRecord, revision: String = "revision-after"): TextUpdateVerifiedResult {
        val unknown = record.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = null)
        val event = unknown.preparation.intent.base.copy(revision = Revision(revision),
            content = unknown.preparation.intent.content.copy(description = "New\nnotes"))
        return TextUpdateVerifiedResult.of(unknown, TextUpdateSubmissionObservation.Verified(unknown, event))
    }
    private fun completion(result: TextUpdateVerifiedResult) = TextUpdateCompletion.of(result,
        TextUpdateAcknowledgementRecord(TextUpdateAcknowledgementReceipt.of(result,
            "97af8411-bf54-4d0c-8ab3-22a9f163348e"), TextUpdateAcknowledgementState.RESTORED))
    @Test fun transferredCompletionWithExistingProjectionReopensExactRawAndObservedContents() {
        val value = result(work())
        val intent = value.record.preparation.intent
        val original = intent.base.copy(revision = Revision("original-create-revision"))
        val capture = LocalCreateIntent(LocalCreateReservation(MutationId("synthetic-create-capture"),
            LocalEventId(UUID(0, 2).toString()), scope, intent.format, value.record.preparation.timestamp), content)
        val submission = LocalCreateSubmission(LocalCreateSubmissionReservation(capture, MutationId(UUID(0, 3).toString()),
            AdapterFormat("fixture-create", 1)), "opaque-create-request")
        val binding = CapturedCreateBinding(CapturedCreateDispatchIdentity.of(submission), original.eventId, original.revision)
        val baseline = CapturedCreateBaseline(CapturedCreateAttachmentReceipt(binding, UUID(0, 4).toString()), original)
        val handoff = CapturedCreateHandoffFrame(CapturedCreateHandoffReceipt(
            CapturedCreateAcknowledgementReceipt(baseline, UUID(0, 5).toString()), UUID(0, 6).toString()), intent.base,
            ProjectionReceipt(scope, intent.format, intent.base, UUID(0, 7).toString(), original))
        val receipt = TextUpdateAcknowledgementReceipt.of(value, UUID(0, 8).toString(), handoff)
        val proof = TextUpdateCompletion.of(value, TextUpdateAcknowledgementRecord(receipt, TextUpdateAcknowledgementState.RESTORED))
        assertEquals(proof, TextUpdateProofStorageCodec.decodeCompletion(TextUpdateProofStorageCodec.encodeCompletion(proof)))
    }
    @Test fun proofFramingRejectsChangedTagsVersionsLengthsTruncationTrailingBytesAndMalformedUtf8() {
        val bytes = TextUpdateProofStorageCodec.encodeResult(result(work()))
        val tag = "CALENDAR_TEXT_UPDATE_RESULT"
        assertArrayEquals(tag.toByteArray(), bytes.copyOfRange(0, tag.length))
        val invalid = listOf(bytes.copyOf().also { it[0] = 0 },
            bytes.copyOf().also { ByteBuffer.wrap(it).putInt(tag.length, 2) },
            bytes.copyOf().also { ByteBuffer.wrap(it).putInt(tag.length + 4, -1) },
            bytes.copyOf().also { ByteBuffer.wrap(it).putInt(tag.length + 4, Int.MAX_VALUE) },
            bytes.copyOf(bytes.size - 1), bytes + byteArrayOf(0),
            bytes.copyOf().also { it[it.lastIndex] = 0x80.toByte() })
        for (payload in invalid) {
            try { TextUpdateProofStorageCodec.decodeResult(payload); fail("Malformed framing was accepted") }
            catch (failure: IllegalArgumentException) { assertEquals("Invalid text proof capsule", failure.message); assertNull(failure.cause) }
        }
        val completion = TextUpdateProofStorageCodec.encodeCompletion(completion(result(work())))
        for (payload in listOf(completion.copyOf(20), completion + byteArrayOf(0))) {
            try { TextUpdateProofStorageCodec.decodeCompletion(payload); fail("Malformed completion was accepted") }
            catch (failure: IllegalArgumentException) { assertEquals("Invalid text proof capsule", failure.message); assertNull(failure.cause) }
        }
    }
    @Test fun receiptEncodingRejectsUnknownKindCoercionsDuplicateKeysAndTrailingWhitespace() {
        val receipt = TextUpdateAcknowledgementReceipt.of(result(work()), UUID(0, 8).toString())
        val payload = TextUpdateAcknowledgementCodec.encode(receipt)
        val invalid = listOf(payload.replace("text-update-acknowledgement", "title-update-acknowledgement"),
            payload.replace("\"generation\":3", "\"generation\":\"3\""),
            payload.replaceFirst("{", "{\"schema\":1,"), payload + " ")
        for (text in invalid) {
            assertNotEquals(payload, text)
            try { TextUpdateAcknowledgementCodec.decode(text); fail("Foreign or noncanonical receipt was accepted") }
            catch (_: Exception) { }
        }
    }
    @Test fun timedAndAllDayProofsKeepExactTimeAndRawVersusObservedTexts() {
        val timed = work()
        val time = EventTime.AllDay(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-27"))
        val intent = timed.preparation.intent
        val dayIntent = intent.copy(base = intent.base.copy(content = intent.base.content.copy(time = time)),
            content = intent.content.copy(time = time))
        val allDay = timed.copy(preparation = LocalTextEditPreparation(dayIntent, timed.preparation.timestamp,
            timed.preparation.format, timed.preparation.protectedPayload))
        for (record in listOf(timed, allDay)) {
            val value = result(record)
            val actual = TextUpdateProofStorageCodec.decodeResult(TextUpdateProofStorageCodec.encodeResult(value))
            assertEquals(value, actual); assertEquals(record.preparation.intent.content.time, actual.event.content.time)
            assertEquals("New\r\nnotes", actual.record.preparation.intent.content.description)
            assertEquals("New\nnotes", actual.event.content.description)
        }
    }
}
