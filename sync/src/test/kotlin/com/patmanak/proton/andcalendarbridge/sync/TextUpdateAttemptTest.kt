// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.charset.CharacterCodingException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TextUpdateAttemptTest {
    private val scope = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 3)
    private val observation = AdapterFormat("fixture-observation", 2)
    private val preparationFormat = AdapterFormat("fixture-preparation", 3)
    private val wireFormat = AdapterFormat("fixture-request", 4)
    private val timestamp = Instant.parse("2026-10-13T10:00:00Z")
    private val base = RemoteChange.Upsert(EventId("fixture-event"), Revision("fixture-revision"),
        EventContent("Original", EventTime.AllDay(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 27)),
            description = "Original\r\nnotes", location = "Original place", details = EventDetails()))
    private val intent = LocalEditIntent(MutationId("fixture-intent"), scope, observation, base,
        base.content.copy(title = "Replacement", description = "Changed\r\nnotes", location = ""))

    private fun preparation(edit: LocalEditIntent = intent, at: Instant = timestamp,
        format: AdapterFormat = preparationFormat, payload: String = "protected-preparation") =
        LocalTextEditPreparation(edit, at, format, payload)
    private fun record(prepared: LocalTextEditPreparation = preparation()) =
        TextUpdateAttemptRecord(MutationId("fixture-attempt"), prepared, wireFormat, "protected-request")
    private fun refused(action: () -> Unit) {
        try { action(); fail("Expected text attempt refusal") }
        catch (_: IllegalArgumentException) { }
        catch (_: CharacterCodingException) { }
    }

    @Test fun separatelyConstructedPreparationsHaveStructuralEqualityAndHash() {
        val first = record()
        val second = record()
        assertNotSame(first.preparation, second.preparation)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertEquals(1, setOf(first, second).size)
        assertEquals(first, first.frozen())
        assertEquals(first, first.copy())
        assertNotEquals(first, "unrelated")
        assertNotEquals(first, null)
    }

    @Test fun snapshotsPreserveFullIntentTimeFormatsAndRawOptionalValues() {
        val prepared = preparation()
        val actual = record(prepared).frozen()
        assertNotSame(prepared, actual.preparation)
        assertEquals(intent, actual.preparation.intent)
        assertEquals("Original\r\nnotes", actual.preparation.intent.base.content.description)
        assertEquals("Changed\r\nnotes", actual.preparation.intent.content.description)
        assertEquals("", actual.preparation.intent.content.location)
        assertEquals(timestamp, actual.preparation.timestamp)
        assertEquals(preparationFormat, actual.preparation.format)
        assertEquals(wireFormat, actual.format)
        assertEquals(scope, actual.scope)
        assertEquals(base.eventId, actual.eventId)
        assertEquals(TextUpdateAttemptState.PREPARED, actual.state)
        assertNull(actual.protectedEvidence)
    }

    @Test fun eachFieldParticipatesInExactStructuralEquality() {
        val original = record()
        val otherBase = base.copy(revision = Revision("other-revision"))
        val alternatives = listOf(
            original.copy(id = MutationId("other-attempt")),
            original.copy(format = wireFormat.copy(version = 5)),
            original.copy(protectedPayload = "other-protected-request"),
            original.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN),
            original.copy(preparation = preparation(at = timestamp.plusSeconds(1))),
            original.copy(preparation = preparation(format = preparationFormat.copy(version = 4))),
            original.copy(preparation = preparation(payload = "other-protected-preparation")),
            original.copy(preparation = preparation(intent.copy(id = MutationId("other-intent")))),
            original.copy(preparation = preparation(intent.copy(scope = scope.copy(account = AccountId("other-account"))))),
            original.copy(preparation = preparation(intent.copy(scope = scope.copy(calendar = CalendarId("other-calendar"))))),
            original.copy(preparation = preparation(intent.copy(scope = scope.copy(generation = 4)))),
            original.copy(preparation = preparation(intent.copy(format = observation.copy(version = 3)))),
            original.copy(preparation = preparation(intent.copy(base = base.copy(eventId = EventId("other-event"))))),
            original.copy(preparation = preparation(intent.copy(base = otherBase))),
            original.copy(preparation = preparation(intent.copy(base = base.copy(content = base.content.copy(title = "Other original"))))),
            original.copy(preparation = preparation(intent.copy(base = base.copy(content = base.content.copy(description = "Original\nnotes"))))),
            original.copy(preparation = preparation(intent.copy(base = base.copy(content = base.content.copy(location = "Other original"))))),
            original.copy(preparation = preparation(intent.copy(content = intent.content.copy(title = "Other")))),
            original.copy(preparation = preparation(intent.copy(content = intent.content.copy(description = "Other")))),
            original.copy(preparation = preparation(intent.copy(content = intent.content.copy(location = "Other")))))
        alternatives.forEach { assertNotEquals(original, it) }
        val unknown = original.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
        assertNotEquals(unknown, unknown.copy(protectedEvidence = "response"))
        assertNotEquals(unknown.copy(protectedEvidence = "response"), unknown.copy(protectedEvidence = "other"))
    }

    @Test fun everyTextFieldCanBeTheOnlyMeaningfulChange() {
        for (content in listOf(base.content.copy(title = "Changed"), base.content.copy(description = ""),
            base.content.copy(location = ""))) {
            val edit = intent.copy(content = content)
            assertEquals(edit, record(preparation(edit)).frozen().preparation.intent)
        }
    }

    @Test fun rawNoOpAndCrlfOnlyChangesAreNotRetainableRequests() {
        refused { record(preparation(intent.copy(content = base.content))) }
        refused { record(preparation(intent.copy(content = base.content.copy(description = "Original\nnotes")))) }
        val allLines = base.copy(content = base.content.copy(title = "A\r\nB", description = "C\r\nD", location = "E\r\nF"))
        refused { record(preparation(intent.copy(base = allLines,
            content = allLines.content.copy(title = "A\nB", description = "C\nD", location = "E\nF")))) }
        val actual = record(preparation(intent.copy(content = base.content.copy(
            title = "Changed", description = "Original\nnotes"))))
        assertEquals("Original\nnotes", actual.frozen().preparation.intent.content.description)
    }

    @Test fun unknownDetailsColorAndRecurrenceCannotWidenRequestProfile() {
        for (content in listOf(base.content.copy(details = null), base.content.copy(colorRgb = 0x123456),
            base.content.copy(recurrence = EventRecurrence("fixture-uid", "FREQ=DAILY")),
            base.content.copy(details = EventDetails(reminders = listOf(EventReminder(5)))))) {
            refused { record(preparation(intent.copy(base = base.copy(content = content),
                content = content.copy(title = "Changed")))) }
        }
    }

    @Test fun preparedCannotCarryEvidenceAndUnknownRetainsItExactly() {
        val initial = record()
        refused { initial.copy(protectedEvidence = "response") }
        val unknown = initial.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "response")
        assertEquals("response", unknown.frozen().protectedEvidence)
        assertEquals(TextUpdateAttemptState.OUTCOME_UNKNOWN, unknown.state)
        refused { unknown.copy(state = TextUpdateAttemptState.PREPARED) }
        assertEquals(unknown, record().copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "response"))
    }

    @Test fun requestIdentityAndFormatUseStrictUtf8ByteBounds() {
        val atLimit = "é".repeat(2_048)
        assertEquals(atLimit, record().copy(id = MutationId(atLimit)).frozen().id.value)
        assertEquals(atLimit, record().copy(format = AdapterFormat(atLimit, 1)).frozen().format.id)
        for (bad in listOf("é".repeat(2_049), "\uD800", " ")) {
            refused { record().copy(id = MutationId(bad)) }
            refused { record().copy(format = AdapterFormat(bad, 1)) }
        }
    }

    @Test fun requestAndEvidenceAreBoundedStrictUtf8AndNonblank() {
        val exact = "é".repeat(2_097_152)
        assertEquals(exact, record().copy(protectedPayload = exact).frozen().protectedPayload)
        val unknown = record().copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
        assertEquals(exact, unknown.copy(protectedEvidence = exact).frozen().protectedEvidence)
        for (bad in listOf(exact + "é", "\uD800", "", " ")) {
            refused { record().copy(protectedPayload = bad) }
            refused { unknown.copy(protectedEvidence = bad) }
        }
    }

    @Test fun timedPrecisionAndExactZoneArePartOfHistoricalEquality() {
        val timed = EventTime.Timed(timestamp.plusNanos(1), timestamp.plusSeconds(3600).plusNanos(2), ZoneId.of("UTC"))
        fun value(time: EventTime.Timed): TextUpdateAttemptRecord {
            val original = base.copy(content = base.content.copy(time = time))
            return record(preparation(intent.copy(base = original, content = original.content.copy(title = "Changed"))))
        }
        val exact = value(timed)
        assertEquals(timed, exact.frozen().preparation.intent.content.time)
        assertNotEquals(exact, value(timed.copy(zone = ZoneId.of("Z"))))
        assertNotEquals(exact, value(timed.copy(end = timed.end.plusNanos(1))))
    }

    @Test fun detailsHaveNoMutableCollectionAliasThroughInputOrSnapshots() {
        val reminders = mutableListOf<EventReminder>()
        val details = EventDetails(reminders = reminders)
        val original = base.copy(content = base.content.copy(details = details))
        val actual = record(preparation(intent.copy(base = original, content = original.content.copy(title = "Changed"))))
        reminders += EventReminder(5)
        val exposed = actual.preparation.intent.content.details!!
        try { (exposed.reminders as MutableList<EventReminder>).add(EventReminder(10)) }
        catch (_: UnsupportedOperationException) { }
        catch (_: ClassCastException) { }
        assertEquals(EventDetails(), actual.frozen().preparation.intent.base.content.details)
        assertEquals(EventDetails(), actual.preparation.intent.content.details)
    }

    @Test fun diagnosticsNeverExposeIdentityTextsOrProtectedHistory() {
        val actual = record().copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN, protectedEvidence = "response-secret")
        val diagnostic = actual.toString()
        for (secret in listOf(actual.id.value, scope.account.value, base.eventId.value, base.content.title,
            intent.content.description, actual.protectedPayload, actual.preparation.protectedPayload, "response-secret"))
            assertFalse(diagnostic.contains(secret))
    }
}
