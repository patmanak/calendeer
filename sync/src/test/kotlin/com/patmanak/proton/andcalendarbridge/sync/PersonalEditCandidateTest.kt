// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync
import java.time.*
import org.junit.Assert.*
import org.junit.Test
class PersonalEditCandidateTest {
    private val start = Instant.parse("2026-10-17T09:00:00Z")
    private val time = EventTime.Timed(start, start.plusSeconds(3600), ZoneOffset.UTC)
    private val details = EventDetails(listOf(EventReminder(5), EventReminder(30)))
    private val base = EventContent("Original", time, "Notes", "Place", colorRgb = 0xabcd12, details = details)
    private fun intent(before: EventContent = base, after: EventContent = before.copy(title = "Combined", description = "Changed", location = "New", time = time.copy(end = time.end.plusSeconds(3600)))) = LocalEditIntent(MutationId("personal-edit"), CalendarScope(AccountId("fixture"), CalendarId("calendar"), 1), AdapterFormat("fixture",1), RemoteChange.Upsert(EventId("event"), Revision("revision"), before), after)
    private fun refuse(action: () -> Unit) { try { action(); fail("Expected refusal") } catch (_: IllegalArgumentException) {} }
    @Test fun combinedFieldsExposeIndependentConsentAndPreserveMetadata() {
        val candidate = PersonalEditCandidate(intent())
        assertEquals(setOf(PersonalEditField.TITLE, PersonalEditField.TEXT, PersonalEditField.TIME), candidate.changedFields)
        assertEquals(candidate.changedFields, candidate.requiredConsent)
        assertEquals(details, candidate.intent.content.details); assertEquals(0xabcd12, candidate.intent.content.colorRgb)
        refuse { LocalTimeEditCandidate(candidate.intent) }; refuse { LocalTextEditPreparation(candidate.intent, start, AdapterFormat("legacy",1), "protected") }
    }
    @Test fun bothKindTransitionsAndZoneOnlyAreSeparateCompleteIntent() {
        val day = EventTime.AllDay(LocalDate.of(2026,10,17), LocalDate.of(2026,10,20))
        for ((before, after) in listOf(base to base.copy(time = day), base.copy(time = day) to base,
            base to base.copy(time = time.copy(zone = ZoneId.of("Europe/Paris"))), base to base.copy(time = time.copy(zone = ZoneId.of("Etc/UTC"))))) {
            val candidate = PersonalEditCandidate(intent(before, after))
            assertEquals(setOf(PersonalEditField.TIME), candidate.requiredConsent)
            assertEquals(after, candidate.intent.content)
        }
    }
    @Test fun ambiguousParisAndLordHoweAndFixedOffsetAreHeldBeforeRetention() {
        for ((instant, zone) in listOf("2026-10-25T00:30:00Z" to "Europe/Paris", "2026-04-04T15:15:00Z" to "Australia/Lord_Howe")) {
            val a = Instant.parse(instant)
            refuse { PersonalEditCandidate(intent(after = base.copy(time = EventTime.Timed(a,a.plusSeconds(7200),ZoneId.of(zone))))) }
        }
        refuse { PersonalEditCandidate(intent(after = base.copy(time = time.copy(zone = ZoneOffset.ofHours(2))))) }
        refuse { PersonalEditCandidate(intent(after = base.copy(time = time.copy(end = time.end.plusNanos(1))))) }
    }
    @Test fun listsAreFrozenAndCompleteMetadataChangesBindIdentity() {
        val reminders = mutableListOf(EventReminder(5)); val original = base.copy(details = EventDetails(reminders))
        val frozen = PersonalEditCandidate(intent(original, original.copy(title = "Next"))).intent
        reminders.add(EventReminder(10)); assertEquals(listOf(EventReminder(5)), frozen.content.details!!.reminders)
        fun record(content: EventContent): PersonalUpdateAttemptRecord {
            val edit = intent(content, content.copy(title = "Next"))
            return PersonalUpdateAttemptRecord(MutationId("request"), PersonalEditPreparation(edit,start,AdapterFormat("preparation",2),"protected"),AdapterFormat("request",2),"wire")
        }
        val first = PersonalUpdateAttemptIdentity.of(record(base))
        assertNotEquals(first, PersonalUpdateAttemptIdentity.of(record(base.copy(colorRgb = 1))))
        assertNotEquals(first, PersonalUpdateAttemptIdentity.of(record(base.copy(details = EventDetails(listOf(EventReminder(10)))))))
        assertFalse(first.toString().contains("Original"))
        refuse { intent(after = base.copy(colorRgb = 1)) }
        refuse { intent(after = base.copy(details = EventDetails())) }
        val unknown = base.copy(details = null)
        refuse { PersonalEditCandidate(intent(unknown, unknown.copy(title = "Next"))) }
    }
}
