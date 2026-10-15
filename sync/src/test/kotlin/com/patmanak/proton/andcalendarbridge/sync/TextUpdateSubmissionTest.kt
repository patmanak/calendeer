// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class TextUpdateSubmissionTest {
    @Test fun comparisonAllowsOnlyCrLfInThreeTextsWithoutChangingEitherValue() {
        val f = TextDeliveryFixture()
        assertTrue(TextUpdateContentPolicy.sameSubmittedContent(f.intent.content, f.event.content))
        assertTrue(TextUpdateContentPolicy.sameSubmittedContent(f.event.content, f.intent.content))
        assertTrue(f.intent.content.description.contains("\r\n")); assertFalse(f.event.content.description.contains("\r"))
        val time = f.event.content.time as EventTime.Timed
        for (other in listOf(f.event.content.copy(title = "new title"), f.event.content.copy(description = "Other"),
            f.event.content.copy(location = f.event.content.location + " "), f.event.content.copy(details = null),
            f.event.content.copy(colorRgb = 1), f.event.content.copy(time = time.copy(start = time.start.plusNanos(1))),
            f.event.content.copy(time = time.copy(zone = ZoneId.of("UTC")))))
            assertFalse(TextUpdateContentPolicy.sameSubmittedContent(f.intent.content, other))
    }

    @Test fun identityAndDeterministicRequestIdBindCompleteRawPreparation() {
        val f = TextDeliveryFixture(); val identity = TextUpdateAttemptIdentity.of(f.unknown)
        assertTrue(identity.matchesPreparation(f.preparation))
        val id = TextUpdateAttemptIdentity.requestId(f.preparation, f.unknown.format)
        assertEquals(id, TextUpdateAttemptIdentity.requestId(f.preparation, f.unknown.format))
        assertTrue(id.value.startsWith("text-update-v1-")); assertEquals(64, id.value.removePrefix("text-update-v1-").length)
        for (changed in f.preparationVariants()) {
            assertFalse(identity.matchesPreparation(changed))
            assertNotEquals(id, TextUpdateAttemptIdentity.requestId(changed, f.unknown.format))
            assertNotEquals(identity.preparationFingerprint, TextUpdateAttemptIdentity.of(f.unknown.copy(preparation = changed)).preparationFingerprint)
        }
        assertNotEquals(id, TextUpdateAttemptIdentity.requestId(f.preparation, f.unknown.format.copy(version = 2)))
        assertNotEquals(id, TextUpdateAttemptIdentity.requestId(f.preparation, AdapterFormat("other-request-format", 1)))
    }

    @Test fun rawCrLfIsComparedCanonicallyButRemainsDifferentHistoricalIdentity() {
        val f = TextDeliveryFixture()
        val normalized = f.preparation(f.intent.copy(content = f.event.content))
        assertTrue(TextUpdateContentPolicy.sameSubmittedContent(normalized.intent.content, f.intent.content))
        assertNotEquals(TextUpdateAttemptIdentity.of(f.unknown), TextUpdateAttemptIdentity.of(f.unknown.copy(preparation = normalized)))
        assertNotEquals(TextUpdateAttemptIdentity.requestId(f.preparation, f.unknown.format),
            TextUpdateAttemptIdentity.requestId(normalized, f.unknown.format))
    }

    @Test fun stateAndEvidenceDoNotChangeIdentityButRequestPayloadAndOwnerDo() {
        val f = TextDeliveryFixture(); val identity = TextUpdateAttemptIdentity.of(f.unknown)
        assertEquals(identity, TextUpdateAttemptIdentity.of(f.unknown.copy(state = TextUpdateAttemptState.PREPARED)))
        assertEquals(identity, TextUpdateAttemptIdentity.of(f.unknown.copy(protectedEvidence = "opaque-evidence")))
        assertNotEquals(identity, TextUpdateAttemptIdentity.of(f.unknown.copy(id = MutationId("other-request"))))
        assertNotEquals(identity, TextUpdateAttemptIdentity.of(f.unknown.copy(protectedPayload = "other-payload")))
        assertNotEquals(identity, TextUpdateAttemptIdentity.of(f.unknown.copy(format = f.unknown.format.copy(version = 2))))
    }

    @Test fun framedFingerprintDoesNotConfuseAdjacentFields() {
        val f = TextDeliveryFixture()
        val a = f.preparation(f.intent.copy(content = f.intent.content.copy(title = "a", description = "bc")))
        val b = f.preparation(f.intent.copy(content = f.intent.content.copy(title = "ab", description = "c")))
        assertNotEquals(TextUpdateAttemptIdentity.requestId(a, f.unknown.format), TextUpdateAttemptIdentity.requestId(b, f.unknown.format))
    }

    @Test fun requestIdentityRefusesMalformedOrOversizedTargetFormat() {
        val f = TextDeliveryFixture()
        for (format in listOf(AdapterFormat("x\uD800", 1), AdapterFormat("x".repeat(4097), 1), AdapterFormat("é".repeat(2049), 1)))
            rejects { TextUpdateAttemptIdentity.requestId(f.preparation, format) }
    }

    @Test fun identityRefusesMalformedFingerprintsAndUnsupportedPreparationProfile() {
        val f = TextDeliveryFixture(); val identity = TextUpdateAttemptIdentity.of(f.unknown)
        for (bad in listOf("", "g".repeat(64), "A".repeat(64), "0".repeat(63))) {
            rejects { identity.copy(preparationFingerprint = bad) }; rejects { identity.copy(payloadFingerprint = bad) }
        }
        for (base in listOf(f.intent.base.content.copy(details = null), f.intent.base.content.copy(colorRgb = 3))) {
            val prep = f.preparation(f.intent.copy(base = f.intent.base.copy(content = base),
                content = base.copy(title = f.intent.content.title, description = f.intent.content.description, location = f.intent.content.location)))
            assertFalse(identity.matchesPreparation(prep))
            rejects { TextUpdateAttemptIdentity.requestId(prep, f.unknown.format) }
        }
    }

    @Test fun verifiedObservationRequiresUnknownExactEventNewRevisionAndAllContent() {
        val f = TextDeliveryFixture()
        val accepted = TextUpdateSubmissionObservation.Verified(f.unknown, f.event)
        assertEquals(f.unknown, accepted.record); assertEquals(f.event, accepted.event)
        rejects { TextUpdateSubmissionObservation.Verified(f.unknown.copy(state = TextUpdateAttemptState.PREPARED), f.event) }
        for (bad in listOf(f.event.copy(eventId = EventId("other-event")), f.event.copy(revision = f.intent.base.revision),
            f.event.copy(content = f.event.content.copy(description = "Other")),
            f.event.copy(content = f.event.content.copy(location = "Other")),
            f.event.copy(content = f.event.content.copy(details = null)), f.event.copy(content = f.event.content.copy(colorRgb = 4)),
            f.event.copy(content = f.event.content.copy(details = EventDetails(listOf(EventReminder(10)))))))
            rejects { TextUpdateSubmissionObservation.Verified(f.unknown, bad) }
    }

    @Test fun verifiedObservationOwnsMutableEmptyDetailsAndKeepsActualRawEvent() {
        val f = TextDeliveryFixture(); val reminders = mutableListOf<EventReminder>()
        val actual = f.event.copy(content = f.intent.content.copy(details = EventDetails(reminders)))
        val observed = TextUpdateSubmissionObservation.Verified(f.unknown, actual)
        reminders += EventReminder(15)
        assertEquals(EventDetails(), observed.event.content.details)
        assertEquals(f.intent.content.description, observed.event.content.description)
        assertFalse(observed.toString().contains(f.intent.content.title)); assertFalse(observed.toString().contains(f.unknown.protectedPayload))
    }

    private fun rejects(block: () -> Unit) { try { block(); fail("Invalid text contract accepted") } catch (_: IllegalArgumentException) { } }
}

internal class TextDeliveryFixture(allDay: Boolean = false) {
    val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 3)
    val baseContent = EventContent("Old title", if (allDay) EventTime.AllDay(LocalDate.parse("2030-10-25"), LocalDate.parse("2030-10-28"))
        else EventTime.Timed(Instant.parse("2030-10-25T10:00:00Z"), Instant.parse("2030-10-25T11:00:00Z"), ZoneId.of("Europe/Paris")),
        "Old\r\nnotes", "Old room", details = EventDetails())
    val intent = LocalEditIntent(MutationId("synthetic-owner"), scope, AdapterFormat("synthetic-observation", 1),
        RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-base"), baseContent),
        baseContent.copy(title = "New\r\ntitle", description = "New\r\nnotes", location = "New\r\nroom"))
    val stamp = Instant.parse("2030-10-24T12:00:00Z")
    fun preparation(value: LocalEditIntent = intent, timestamp: Instant = stamp, format: AdapterFormat = AdapterFormat("synthetic-preparation", 1),
        payload: String = "opaque-preparation") = LocalTextEditPreparation(value, timestamp, format, payload)
    val preparation = preparation()
    val unknown = TextUpdateAttemptRecord(MutationId("synthetic-request"), preparation, AdapterFormat("synthetic-request-format", 1),
        "opaque-request", TextUpdateAttemptState.OUTCOME_UNKNOWN)
    val event = intent.base.copy(revision = Revision("synthetic-new-revision"), content = intent.content.copy(title = "New\ntitle",
        description = "New\nnotes", location = "New\nroom"))
    val nonce = "00000000-0000-0000-0000-000000000001"
    fun observed(record: TextUpdateAttemptRecord = unknown, actual: RemoteChange.Upsert = event) = TextUpdateSubmissionObservation.Verified(record, actual)
    fun result(record: TextUpdateAttemptRecord = unknown, actual: RemoteChange.Upsert = event) = TextUpdateVerifiedResult.of(record, observed(record, actual))
    fun receipt() = TextUpdateAcknowledgementReceipt.of(result(), nonce)
    fun preparationVariants(): List<LocalTextEditPreparation> {
        fun rebase(base: EventContent) = preparation(intent.copy(base = intent.base.copy(content = base),
            content = base.copy(title = intent.content.title, description = intent.content.description, location = intent.content.location)))
        val time = baseContent.time as EventTime.Timed
        return listOf(preparation(timestamp = stamp.plusSeconds(1)), preparation(payload = "other-capsule"),
            preparation(format = preparation.format.copy(version = 2)), preparation(intent.copy(id = MutationId("other-owner"))),
            preparation(intent.copy(scope = scope.copy(generation = 4))), preparation(intent.copy(scope = scope.copy(account = AccountId("other-account")))),
            preparation(intent.copy(scope = scope.copy(calendar = CalendarId("other-calendar")))),
            preparation(intent.copy(format = intent.format.copy(version = 2))),
            preparation(intent.copy(base = intent.base.copy(eventId = EventId("other-event")))),
            preparation(intent.copy(base = intent.base.copy(revision = Revision("other-base")))),
            preparation(intent.copy(content = intent.content.copy(title = "Other title"))),
            preparation(intent.copy(content = intent.content.copy(description = "Other notes"))),
            preparation(intent.copy(content = intent.content.copy(location = "Other room"))),
            rebase(baseContent.copy(title = "Other original")), rebase(baseContent.copy(description = "Other original notes")),
            rebase(baseContent.copy(location = "Other original room")), rebase(baseContent.copy(time = time.copy(start = time.start.plusNanos(1)))),
            rebase(baseContent.copy(time = time.copy(end = time.end.plusNanos(1)))),
            rebase(baseContent.copy(time = time.copy(zone = ZoneId.of("UTC")))),
            rebase(baseContent.copy(time = EventTime.AllDay(LocalDate.parse("2030-10-25"), LocalDate.parse("2030-10-28")))))
    }
    fun handoff(): CapturedCreateHandoffFrame {
        val capture = LocalCreateIntent(LocalCreateReservation(MutationId("synthetic-capture"), LocalEventId(UUID(0, 2).toString()),
            scope, intent.format, stamp), baseContent)
        val submission = LocalCreateSubmission(LocalCreateSubmissionReservation(capture, MutationId(UUID(0, 3).toString()),
            AdapterFormat("synthetic-create-format", 1)), "opaque-create")
        val binding = CapturedCreateBinding(CapturedCreateDispatchIdentity.of(submission), intent.base.eventId, intent.base.revision)
        val origin = CapturedCreateBaseline(CapturedCreateAttachmentReceipt(binding, UUID(0, 4).toString()), intent.base)
        return CapturedCreateHandoffFrame(CapturedCreateHandoffReceipt(CapturedCreateAcknowledgementReceipt(origin,
            UUID(0, 5).toString()), UUID(0, 6).toString()), intent.base)
    }
}
