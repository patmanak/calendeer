package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class LocalEditPreparationTest {
    private val scope = CalendarScope(AccountId("fixture"), CalendarId("calendar"), 1)
    private val format = AdapterFormat("fixture", 1)
    private val base = RemoteChange.Upsert(EventId("event"), Revision("base"), EventContent("Original",
        EventTime.AllDay(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 4)), details = EventDetails()))
    private val intent = LocalEditIntent(MutationId("edit"), scope, format, base, base.content.copy(title = "Changed"))
    private val time = Instant.parse("2026-10-03T09:30:00Z")
    private fun value(edit: LocalEditIntent = intent, timestamp: Instant = time, payload: String = "protected") =
        LocalEditPreparation(edit, timestamp, format, payload)
    private fun refused(action: () -> Unit) { try { action(); fail("Expected refusal") } catch (_: IllegalArgumentException) { } }

    @Test fun preparationRetainsExactIntentAndRedactsDiagnostics() {
        val actual = value()
        assertEquals(intent, actual.intent); assertEquals(time, actual.timestamp)
        assertEquals(format, actual.format); assertEquals("protected", actual.protectedPayload)
        assertEquals("LocalEditPreparation(REDACTED)", actual.toString())
    }
    @Test fun conflictAndNonTitleChangesCannotBecomePreparations() {
        refused { value(intent.copy(state = LocalEditState.CONFLICT)) }
        refused { value(intent.copy(content = intent.content.copy(description = "Other"))) }
        refused { value(intent.copy(content = intent.content.copy(time = EventTime.AllDay(
            LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 5))))) }
    }
    @Test fun explicitTimeMustBeWholeSecondsWithinTheAdmittedRange() {
        refused { value(timestamp = time.plusNanos(1)) }
        refused { value(timestamp = Instant.parse("1969-12-31T23:59:59Z")) }
        refused { value(timestamp = Instant.parse("+10000-01-01T00:00:00Z")) }
    }
    @Test fun protectedPayloadIsBoundedInStrictUtf8() {
        refused { value(payload = " ") }; refused { value(payload = "x".repeat(4_194_305)) }
        refused { value(payload = "é".repeat(2_097_153)) }
        try { value(payload = "\uD800"); fail("Expected malformed UTF-8 refusal") }
        catch (_: java.nio.charset.CharacterCodingException) { }
        assertEquals(4_194_304, value(payload = "x".repeat(4_194_304)).protectedPayload.length)
    }
    @Test fun freezeSeparatesMutableEmptyDetailsBeforeSuspension() {
        val reminders = mutableListOf<EventReminder>()
        val details = EventDetails(reminders = reminders)
        val mutableBase = base.copy(content = base.content.copy(details = details))
        val mutableIntent = intent.copy(base = mutableBase, content = intent.content.copy(details = details))
        val frozen = LocalEditPreparation.freezeIntent(mutableIntent)
        reminders.add(EventReminder(5))
        assertEquals(EventDetails(), frozen.base.content.details)
        assertEquals(EventDetails(), frozen.content.details)
        refused { value(mutableIntent) }
    }
    @Test fun legacyUnknownDetailsRemainUnknown() {
        val legacy = intent.copy(base = base.copy(content = base.content.copy(details = null)),
            content = intent.content.copy(details = null))
        val frozen = LocalEditPreparation.freezeIntent(legacy)
        assertNull(frozen.base.content.details); assertNull(frozen.content.details)
    }
    @Test fun contextIdentifiersCannotBypassTheJournalSizeBounds() {
        val oversized = "é".repeat(2_049)
        for (changed in listOf(
            intent.copy(id = MutationId(oversized)),
            intent.copy(scope = scope.copy(account = AccountId(oversized))),
            intent.copy(scope = scope.copy(calendar = CalendarId(oversized))),
            intent.copy(base = base.copy(eventId = EventId(oversized))),
            intent.copy(base = base.copy(revision = Revision(oversized))),
            intent.copy(format = format.copy(id = oversized)))) {
            refused { value(changed) }
        }
        refused { value().copy(format = format.copy(id = oversized)) }
        try { value(intent.copy(id = MutationId("\uD800"))); fail("Expected malformed context refusal") }
        catch (_: java.nio.charset.CharacterCodingException) { }
        assertEquals(2_048, value(intent.copy(id = MutationId("é".repeat(2_048)))).intent.id.value.length)
    }
}
