// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.nio.charset.CharacterCodingException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class LocalTextEditPreparationTest {
    private val scope = CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 1)
    private val format = AdapterFormat("fixture-observation", 1)
    private val preparedFormat = AdapterFormat("fixture-local-text", 1)
    private val time = Instant.parse("2026-10-03T09:30:00Z")
    private val base = RemoteChange.Upsert(EventId("fixture-event"), Revision("fixture-revision"),
        EventContent("Original", EventTime.AllDay(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 6)),
            description = "Old notes", location = "Old place", details = EventDetails()))
    private val intent = LocalEditIntent(MutationId("fixture-edit"), scope, format, base,
        base.content.copy(title = "New title", description = "New notes", location = "New place"))

    private fun value(edit: LocalEditIntent = intent, timestamp: Instant = time,
        recordFormat: AdapterFormat = preparedFormat, payload: String = "synthetic-protected") =
        LocalTextEditPreparation(edit, timestamp, recordFormat, payload)
    private fun refused(action: () -> Unit) {
        try { action(); fail("Expected bounded text preparation refusal") }
        catch (_: IllegalArgumentException) { }
        catch (_: CharacterCodingException) { }
    }
    private fun text(content: EventContent, field: Int, replacement: String) = when (field) {
        0 -> content.copy(title = replacement)
        1 -> content.copy(description = replacement)
        else -> content.copy(location = replacement)
    }
    private fun fieldEdit(field: Int, replacement: String, inBase: Boolean) =
        if (inBase) intent.copy(base = base.copy(content = text(base.content, field, replacement)))
        else intent.copy(content = text(intent.content, field, replacement))

    @Test fun titleDescriptionLocationAndCombinedEditsHaveSeparateFrozenRecords() {
        for (content in listOf(base.content.copy(title = "New"), base.content.copy(description = "New"),
            base.content.copy(location = "New"), intent.content)) {
            val edit = intent.copy(content = content)
            val actual = value(edit)
            assertEquals(edit, actual.intent)
            assertNotSame(edit, actual.intent)
            assertEquals(time, actual.timestamp)
            assertEquals(preparedFormat, actual.format)
            assertEquals("synthetic-protected", actual.protectedPayload)
            assertEquals("LocalTextEditPreparation(REDACTED)", actual.toString())
        }
    }

    @Test fun timedNanosecondEndpointsAndZoneStayExactWhenOnlyTextChanges() {
        val timed = EventTime.Timed(time.plusNanos(1), time.plusSeconds(60).plusNanos(2), ZoneId.of("Europe/Paris"))
        val timedBase = base.copy(content = base.content.copy(time = timed, colorRgb = 0x123456))
        val edit = intent.copy(base = timedBase, content = timedBase.content.copy(description = "Changed"))
        val frozen = value(edit).intent
        assertEquals(timed, frozen.base.content.time)
        assertEquals(timed, frozen.content.time)
        assertEquals(0x123456, frozen.content.colorRgb)
    }

    @Test fun constructorSnapshotsMutableEmptyInputDetailsInsteadOfKeepingAnAlias() {
        val reminders = mutableListOf<EventReminder>()
        val attendees = mutableListOf<EventAttendee>()
        val details = EventDetails(reminders = reminders, attendees = attendees)
        val mutableBase = base.copy(content = base.content.copy(details = details))
        val source = intent.copy(base = mutableBase, content = intent.content.copy(details = details))
        val record = value(source)
        val frozen = LocalTextEditPreparation.freezeIntent(source)
        reminders += EventReminder(5)
        attendees += EventAttendee(EventPerson("fixture@example.invalid"), AttendanceRole.REQUIRED, AttendanceStatus.ACCEPTED)
        for (snapshot in listOf(record.intent, frozen)) {
            assertEquals(EventDetails(), snapshot.base.content.details)
            assertEquals(EventDetails(), snapshot.content.details)
            assertNotSame(details, snapshot.base.content.details)
            assertNotSame(details, snapshot.content.details)
        }
        refused { value(source) }
    }

    @Test fun exposedEmptyDetailsCannotBeMutatedThroughListCasts() {
        val record = value()
        for (details in listOf(record.intent.base.content.details!!, record.intent.content.details!!)) {
            try { (details.reminders as MutableList<EventReminder>).add(EventReminder(10)) }
            catch (_: UnsupportedOperationException) { }
            catch (_: ClassCastException) { }
            try { (details.attendees as MutableList<EventAttendee>).add(
                EventAttendee(EventPerson("fixture@example.invalid"), AttendanceRole.OPTIONAL, AttendanceStatus.NEEDS_ACTION)) }
            catch (_: UnsupportedOperationException) { }
            catch (_: ClassCastException) { }
        }
        assertEquals(EventDetails(), record.intent.base.content.details)
        assertEquals(EventDetails(), record.intent.content.details)
    }

    @Test fun unknownAndKnownEmptyDetailsRemainDifferentHistoricalObservations() {
        val unknown = intent.copy(base = base.copy(content = base.content.copy(details = null)),
            content = intent.content.copy(details = null))
        assertNull(value(unknown).intent.base.content.details)
        assertNull(value(unknown).intent.content.details)
        assertEquals(EventDetails(), value().intent.base.content.details)
        assertNotEquals(value(unknown).intent, value().intent)
        refused { value(unknown.copy(content = unknown.content.copy(details = EventDetails()))) }
    }

    @Test fun normalizedNoOpKeepsTheOriginalRawIntentAndPermittedLineEndings() {
        val original = base.copy(content = base.content.copy(description = "Line\r\nNext\té🦌", location = ""))
        val edit = intent.copy(base = original, content = original.content.copy(description = "Line\nNext\té🦌"))
        val actual = value(edit).intent
        assertEquals(edit, actual)
        assertEquals("Line\r\nNext\té🦌", actual.base.content.description)
        assertEquals("Line\nNext\té🦌", actual.content.description)
        assertEquals("", actual.content.location)
    }

    @Test fun pausedStateAndExactNonTextFieldsAreRequired() {
        refused { value(intent.copy(state = LocalEditState.CONFLICT)) }
        val timed = EventTime.Timed(time, time.plusSeconds(60), ZoneId.of("UTC"))
        val original = base.copy(content = base.content.copy(time = timed))
        for (changed in listOf(
            timed.copy(start = timed.start.plusNanos(1)),
            timed.copy(end = timed.end.plusNanos(1)),
            timed.copy(zone = ZoneId.of("Europe/Paris")),
            base.content.time)) {
            refused { value(intent.copy(base = original, content = original.content.copy(time = changed, title = "Changed"))) }
        }
        refused { value(intent.copy(content = intent.content.copy(time = EventTime.AllDay(
            LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 7))))) }
        refused { value(intent.copy(content = intent.content.copy(colorRgb = 1))) }
    }

    @Test fun recurrenceAndNonemptyDetailsCannotEnterThePreparationProfile() {
        refused {
            val recurring = base.copy(content = base.content.copy(recurrence = EventRecurrence("fixture-uid", "FREQ=DAILY")))
            value(intent.copy(base = recurring, content = recurring.content.copy(title = "Changed")))
        }
        for (details in listOf(EventDetails(reminders = listOf(EventReminder(5))),
            EventDetails(organizer = EventPerson("fixture@example.invalid")),
            EventDetails(attendees = listOf(EventAttendee(EventPerson("fixture@example.invalid"),
                AttendanceRole.REQUIRED, AttendanceStatus.ACCEPTED))))) {
            refused { value(intent.copy(base = base.copy(content = base.content.copy(details = details)),
                content = intent.content.copy(details = details))) }
        }
    }

    @Test fun everyOriginalAndReplacementTextUsesStrictUtf8ByteBounds() {
        val atLimit = "é".repeat(32_768)
        val beyond = "é".repeat(32_769)
        for (inBase in listOf(false, true)) for (field in 0..2) {
            assertEquals(fieldEdit(field, atLimit, inBase), value(fieldEdit(field, atLimit, inBase)).intent)
            refused { value(fieldEdit(field, beyond, inBase)) }
            refused { value(fieldEdit(field, "\uD800", inBase)) }
        }
    }

    @Test fun unsupportedControlsAreRejectedInAllSixTextsWithoutNormalizationOfStoredInput() {
        for (inBase in listOf(false, true)) for (field in 0..2) {
            for (bad in listOf("secret\u0000", "secret\u0001", "secret\u007f", "secret\rremaining"))
                refused { value(fieldEdit(field, bad, inBase)) }
            val accepted = fieldEdit(field, "Line\r\nNext\n\té🦌", inBase)
            assertEquals(accepted, value(accepted).intent)
        }
    }

    @Test fun rawTextBudgetDoesNotShrinkByNormalizingCrlf() {
        // Logical LF normalization would halve this input; the retained raw strings still need their full budget.
        for (inBase in listOf(false, true)) for (field in 0..2) {
            val exact = fieldEdit(field, "\r\n".repeat(32_768), inBase)
            assertEquals(exact, value(exact).intent)
            refused { value(fieldEdit(field, "\r\n".repeat(32_769), inBase)) }
        }
    }

    @Test fun contextBoundsCoverAllIntentAndRecordIdentifiersInStrictUtf8() {
        fun edits(id: String) = listOf(
            intent.copy(id = MutationId(id)),
            intent.copy(scope = scope.copy(account = AccountId(id))),
            intent.copy(scope = scope.copy(calendar = CalendarId(id))),
            intent.copy(base = base.copy(eventId = EventId(id))),
            intent.copy(base = base.copy(revision = Revision(id))),
            intent.copy(format = format.copy(id = id)))
        for (edit in edits("é".repeat(2_048))) assertEquals(edit, value(edit).intent)
        assertEquals("é".repeat(2_048), value(recordFormat = preparedFormat.copy(id = "é".repeat(2_048))).format.id)
        for (bad in listOf("é".repeat(2_049), "\uD800")) {
            for (edit in edits(bad)) refused { value(edit) }
            refused { value(recordFormat = preparedFormat.copy(id = bad)) }
        }
    }

    @Test fun payloadIsNonblankAndStrictUtf8WithAFourMiBBound() {
        for (payload in listOf("", " \t\r\n", "x".repeat(4_194_305), "é".repeat(2_097_153), "\uD800"))
            refused { value(payload = payload) }
        assertEquals(4_194_304, value(payload = "x".repeat(4_194_304)).protectedPayload.length)
        assertEquals(2_097_152, value(payload = "é".repeat(2_097_152)).protectedPayload.length)
    }

    @Test fun explicitTimestampMustBeWholeSecondWithinTheClosedUtcRange() {
        for (bad in listOf(time.plusNanos(1), Instant.parse("1969-12-31T23:59:59Z"),
            Instant.parse("+10000-01-01T00:00:00Z"), Instant.MIN, Instant.MAX))
            refused { value(timestamp = bad) }
        assertEquals(Instant.EPOCH, value(timestamp = Instant.EPOCH).timestamp)
        val latest = Instant.parse("9999-12-31T23:59:59Z")
        assertEquals(latest, value(timestamp = latest).timestamp)
    }

    @Test fun historicalTitlePreparationStillRefusesDescriptionAndLocationChanges() {
        for (content in listOf(base.content.copy(description = "Changed"), base.content.copy(location = "Changed"),
            intent.content)) {
            val edit = intent.copy(content = content)
            value(edit)
            refused { LocalEditPreparation(edit, time, preparedFormat, "synthetic-protected") }
            refused { LocalEditPreparation.freezeIntent(edit) }
        }
    }
}
