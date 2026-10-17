// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.charset.CharacterCodingException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class TimeUpdateSubmissionTest {
    @Test fun comparisonAllowsOnlyCrLfInUnchangedTextAndKeepsExactTimeAndZone() {
        val f = TimeDeliveryFixture()
        assertTrue(TimeUpdateContentPolicy.sameSubmittedContent(f.intent.content, f.event.content))
        assertTrue(TimeUpdateContentPolicy.sameSubmittedContent(f.event.content, f.intent.content))
        val time = f.event.content.time as EventTime.Timed
        for (other in listOf(f.event.content.copy(title = "Other"), f.event.content.copy(description = "Other"),
            f.event.content.copy(location = f.event.content.location + " "), f.event.content.copy(details = null),
            f.event.content.copy(colorRgb = 1), f.event.content.copy(time = time.copy(start = time.start.plusNanos(1))),
            f.event.content.copy(time = time.copy(zone = ZoneId.of("UTC")))))
            assertFalse(TimeUpdateContentPolicy.sameSubmittedContent(f.intent.content, other))
        assertTrue(f.intent.content.description.contains("\r\n"))
        assertFalse(f.event.content.description.contains("\r"))
    }

    @Test fun identityAndRequestBindAllRawPreparationFieldsAndSeparateTemporalDomain() {
        val f = TimeDeliveryFixture(); val identity = TimeUpdateAttemptIdentity.of(f.unknown)
        val id = TimeUpdateAttemptIdentity.requestId(f.preparation, f.unknown.format)
        assertTrue(identity.matchesPreparation(f.preparation))
        assertEquals(id, TimeUpdateAttemptIdentity.requestId(f.preparation, f.unknown.format))
        assertTrue(id.value.startsWith("time-update-v1-"))
        assertEquals(64, id.value.removePrefix("time-update-v1-").length)
        for (changed in f.preparationVariants()) {
            assertFalse(identity.matchesPreparation(changed))
            assertNotEquals(id, TimeUpdateAttemptIdentity.requestId(changed, f.unknown.format))
            assertNotEquals(identity.preparationFingerprint,
                TimeUpdateAttemptIdentity.of(f.unknown.copy(preparation = changed)).preparationFingerprint)
        }
        assertNotEquals(id, TimeUpdateAttemptIdentity.requestId(f.preparation, f.unknown.format.copy(version = 2)))
        assertNotEquals(id, TimeUpdateAttemptIdentity.requestId(f.preparation, AdapterFormat("other", 1)))
        val text = TextDeliveryFixture()
        assertNotEquals(identity as Any, TextUpdateAttemptIdentity.of(text.unknown) as Any)
        assertNotEquals(id, TextUpdateAttemptIdentity.requestId(text.preparation, f.unknown.format))
        assertTrue(CalendarTimeUpdateSubmissionPort::class.java.methods.any { it.name == "getTimeUpdateFormat" })
        assertFalse(CalendarTimeUpdateSubmissionPort::class.java.methods.any { it.name == "getTextUpdateFormat" })
    }

    @Test fun temporalDomainsHaveIndependentFixedUtf8FramedSha256Vectors() {
        // Fixed fixture vectors independently calculated from the specified raw field list.
        val f = TimeDeliveryFixture(); val identity = TimeUpdateAttemptIdentity.of(f.unknown)
        assertEquals("time-update-v1-11948bd949bed36a64f93046fc1e8819e1a7383d94c8c08653bf8f1a21c47c1b",
            TimeUpdateAttemptIdentity.requestId(f.preparation, f.unknown.format).value)
        assertEquals("3e5b9790d6dfeedd8be08672ccb8b4a747718303b863fe16ffa072887a65b830", identity.preparationFingerprint)
        assertEquals("b431dd299649fb82f8a523fa05cde32acc6ba4749639a9a1a6d552ee26119955", identity.payloadFingerprint)
    }

    @Test fun rawLineEndingsBindIdentityEvenWhenBothBaselineAndReplacementNormalize() {
        val f = TimeDeliveryFixture()
        val changed = f.intent.copy(base = f.intent.base.copy(content = f.baseContent.copy(description = "Original\nnotes")),
            content = f.intent.content.copy(description = "Original\nnotes"))
        assertTrue(TimeUpdateContentPolicy.sameSubmittedContent(f.intent.content, changed.content))
        assertNotEquals(TimeUpdateAttemptIdentity.of(f.unknown), TimeUpdateAttemptIdentity.of(f.unknown.copy(preparation = f.preparation(changed))))
        assertNotEquals(TimeUpdateAttemptIdentity.requestId(f.preparation, f.unknown.format),
            TimeUpdateAttemptIdentity.requestId(f.preparation(changed), f.unknown.format))
    }

    @Test fun stateAndEvidenceExcludedButWireOwnerAndPayloadRemainBound() {
        val f = TimeDeliveryFixture(); val identity = TimeUpdateAttemptIdentity.of(f.unknown)
        assertEquals(identity, TimeUpdateAttemptIdentity.of(f.unknown.copy(state = TimeUpdateAttemptState.PREPARED)))
        assertEquals(identity, TimeUpdateAttemptIdentity.of(f.unknown.copy(protectedEvidence = "opaque-evidence")))
        for (changed in listOf(f.unknown.copy(id = MutationId("other")), f.unknown.copy(protectedPayload = "other"),
            f.unknown.copy(format = f.unknown.format.copy(version = 2))))
            assertNotEquals(identity, TimeUpdateAttemptIdentity.of(changed))
    }

    @Test fun lengthFramingSeparatesAdjacentRawTexts() {
        val f = TimeDeliveryFixture()
        fun prepared(title: String, notes: String): LocalTimeEditPreparation {
            val base = f.baseContent.copy(title = title, description = notes)
            return f.preparation(f.intent.copy(base = f.intent.base.copy(content = base), content = base.copy(time = f.intent.content.time)))
        }
        assertNotEquals(TimeUpdateAttemptIdentity.requestId(prepared("a", "bc"), f.unknown.format),
            TimeUpdateAttemptIdentity.requestId(prepared("ab", "c"), f.unknown.format))
    }

    @Test fun malformedIdentityAndTargetFormatsAreRefused() {
        val f = TimeDeliveryFixture(); val identity = TimeUpdateAttemptIdentity.of(f.unknown)
        for (bad in listOf("", "g".repeat(64), "A".repeat(64), "0".repeat(63))) {
            refused { identity.copy(preparationFingerprint = bad) }; refused { identity.copy(payloadFingerprint = bad) }
        }
        for (bad in listOf("x\uD800", "x".repeat(4097), "é".repeat(2049)))
            refused { TimeUpdateAttemptIdentity.requestId(f.preparation, AdapterFormat(bad, 1)) }
    }

    @Test fun verifiedReadbackRequiresUnknownEventNewRevisionExactDatesZoneAndText() {
        for (allDay in listOf(false, true)) {
            val f = TimeDeliveryFixture(allDay)
            assertEquals(f.event, f.observed().event)
            refused { f.observed(f.unknown.copy(state = TimeUpdateAttemptState.PREPARED)) }
            val alternateTime = when (val time = f.event.content.time) {
                is EventTime.Timed -> time.copy(end = time.end.plusSeconds(1))
                is EventTime.AllDay -> time.copy(endExclusive = time.endExclusive.plusDays(1))
            }
            for (actual in listOf(f.event.copy(eventId = EventId("other")), f.event.copy(revision = f.intent.base.revision),
                f.event.copy(content = f.event.content.copy(time = alternateTime)),
                f.event.copy(content = f.event.content.copy(time = f.baseContent.time)),
                f.event.copy(content = f.event.content.copy(title = "Other")), f.event.copy(content = f.event.content.copy(description = "Other")),
                f.event.copy(content = f.event.content.copy(location = "Other")), f.event.copy(content = f.event.content.copy(details = null)),
                f.event.copy(content = f.event.content.copy(colorRgb = 4)),
                f.event.copy(content = f.event.content.copy(details = EventDetails(listOf(EventReminder(10)))))))
                refused { f.observed(actual = actual) }
        }
        val f = TimeDeliveryFixture(); val time = f.event.content.time as EventTime.Timed
        for (bad in listOf(time.copy(start = time.start.plusNanos(1)), time.copy(zone = ZoneId.of("UTC")),
            time.copy(start = Instant.parse("1969-12-31T23:59:59Z")), time.copy(end = Instant.parse("+10000-01-01T00:00:00Z"))))
            refused { f.observed(actual = f.event.copy(content = f.event.content.copy(time = bad))) }
    }

    @Test fun lfBaselineAcceptsActualCrLfOnlyAfterUnchangedTextComparison() {
        val f = TimeDeliveryFixture()
        val base = f.baseContent.copy(title = "A\nB", description = "C\nD", location = "E\nF")
        val intent = f.intent.copy(base = f.intent.base.copy(content = base), content = base.copy(time = f.intent.content.time))
        val record = f.unknown.copy(preparation = f.preparation(intent))
        val actual = f.event.copy(content = intent.content.copy(title = "A\r\nB", description = "C\r\nD", location = "E\r\nF"))
        val observed = f.observed(record, actual)
        assertEquals(actual, observed.event)
        assertEquals(actual, TimeUpdateAcknowledgementReceipt.of(f.result(record, actual), f.nonce).projection.change)
        refused { f.observed(record, actual.copy(content = actual.content.copy(description = "C\r\nChanged"))) }
    }

    @Test fun expandedCrLfBytesMustFitActualReadbackTextBudget() {
        val f = TimeDeliveryFixture()
        for (notes in listOf("a\n".repeat(32768), "é\n".repeat(21845))) {
            val base = f.baseContent.copy(description = notes)
            val intent = f.intent.copy(base = f.intent.base.copy(content = base), content = base.copy(time = f.intent.content.time))
            val record = f.unknown.copy(preparation = f.preparation(intent))
            val actual = f.event.copy(content = intent.content.copy(description = notes.replace("\n", "\r\n")))
            assertTrue(TimeUpdateContentPolicy.sameSubmittedContent(intent.content, actual.content))
            refused { f.observed(record, actual) }
            refused { TimeUpdateAcknowledgementReceipt(TimeUpdateAttemptIdentity.of(record), intent,
                ProjectionReceipt(f.scope, intent.format, actual, f.nonce, intent.base)) }
        }
    }

    @Test fun malformedAndControlReadbackTextsAreRefusedWithoutContentInFailure() {
        val f = TimeDeliveryFixture()
        for (bad in listOf("private\uD800", "private\u0000", "private\rtext")) {
            try {
                f.observed(actual = f.event.copy(content = f.event.content.copy(description = bad)))
                fail("Invalid readback text accepted")
            } catch (failure: IllegalArgumentException) {
                assertFalse(failure.message.orEmpty().contains("private")); assertNull(failure.cause)
            }
        }
    }

    @Test fun olderTextPreparationCannotAcceptTemporalIntent() {
        val f = TimeDeliveryFixture()
        refused { LocalTextEditPreparation(f.intent, f.stamp, f.preparation.format, "opaque") }
    }
}

internal fun refused(action: () -> Unit) {
    try { action(); fail("Invalid temporal delivery accepted") }
    catch (_: IllegalArgumentException) { }
    catch (_: CharacterCodingException) { }
}

internal class TimeDeliveryFixture(allDay: Boolean = false) {
    val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 3)
    val baseContent = EventContent("Original\r\ntitle", if (allDay)
        EventTime.AllDay(LocalDate.parse("2030-10-25"), LocalDate.parse("2030-10-28")) else
        EventTime.Timed(Instant.parse("2030-10-25T10:00:00Z"), Instant.parse("2030-10-25T11:00:00Z"), ZoneId.of("Europe/Paris")),
        "Original\r\nnotes", "Original\r\nroom", details = EventDetails())
    val replacementTime = when (val time = baseContent.time) {
        is EventTime.AllDay -> time.copy(start = time.start.plusDays(1), endExclusive = time.endExclusive.plusDays(2))
        is EventTime.Timed -> time.copy(start = time.start.plusSeconds(3600), end = time.end.plusSeconds(7200))
    }
    val intent = LocalEditIntent(MutationId("synthetic-owner"), scope, AdapterFormat("synthetic-observation", 1),
        RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-base"), baseContent), baseContent.copy(time = replacementTime))
    val stamp = Instant.parse("2030-10-24T12:00:00Z")
    fun preparation(value: LocalEditIntent = intent, timestamp: Instant = stamp,
        format: AdapterFormat = AdapterFormat("synthetic-time-preparation", 1), payload: String = "opaque-preparation") =
        LocalTimeEditPreparation(value, timestamp, format, payload)
    val preparation = preparation()
    val unknown = TimeUpdateAttemptRecord(MutationId("synthetic-request"), preparation, AdapterFormat("synthetic-time-request", 1),
        "opaque-request", TimeUpdateAttemptState.OUTCOME_UNKNOWN)
    val event = intent.base.copy(revision = Revision("synthetic-new-revision"), content = intent.content.copy(title = "Original\ntitle",
        description = "Original\nnotes", location = "Original\nroom"))
    val nonce = "00000000-0000-0000-0000-000000000001"
    fun observed(record: TimeUpdateAttemptRecord = unknown, actual: RemoteChange.Upsert = event) = TimeUpdateSubmissionObservation.Verified(record, actual)
    fun result(record: TimeUpdateAttemptRecord = unknown, actual: RemoteChange.Upsert = event) = TimeUpdateVerifiedResult.of(record, observed(record, actual))
    fun receipt() = TimeUpdateAcknowledgementReceipt.of(result(), nonce)
    fun preparationVariants(): List<LocalTimeEditPreparation> {
        fun rebase(base: EventContent, targetTime: EventTime = replacementTime) = preparation(intent.copy(
            base = intent.base.copy(content = base), content = base.copy(time = targetTime)))
        val time = baseContent.time as EventTime.Timed
        val target = replacementTime as EventTime.Timed
        return listOf(preparation(timestamp = stamp.plusSeconds(1)), preparation(payload = "other-capsule"),
            preparation(format = preparation.format.copy(version = 2)), preparation(intent.copy(id = MutationId("other-owner"))),
            preparation(intent.copy(scope = scope.copy(generation = 4))), preparation(intent.copy(scope = scope.copy(account = AccountId("other-account")))),
            preparation(intent.copy(scope = scope.copy(calendar = CalendarId("other-calendar")))), preparation(intent.copy(format = intent.format.copy(version = 2))),
            preparation(intent.copy(base = intent.base.copy(eventId = EventId("other-event")))),
            preparation(intent.copy(base = intent.base.copy(revision = Revision("other-base")))),
            rebase(baseContent.copy(title = "Other")), rebase(baseContent.copy(description = "Other")), rebase(baseContent.copy(location = "Other")),
            rebase(baseContent.copy(time = time.copy(start = time.start.minusSeconds(1)))),
            rebase(baseContent.copy(time = time.copy(end = time.end.plusSeconds(1)))),
            rebase(baseContent, target.copy(start = target.start.plusSeconds(1))), rebase(baseContent, target.copy(end = target.end.plusSeconds(1))),
            rebase(baseContent.copy(time = time.copy(zone = ZoneId.of("UTC"))), target.copy(zone = ZoneId.of("UTC"))),
            rebase(baseContent.copy(time = EventTime.AllDay(LocalDate.parse("2030-10-25"), LocalDate.parse("2030-10-28"))),
                EventTime.AllDay(LocalDate.parse("2030-10-26"), LocalDate.parse("2030-10-30"))))
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
