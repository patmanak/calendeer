// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class SeriesEditCandidateTest {
    @Test fun profileFactoryKeepsTheUidAndRefusesUnknownOrNonCanonicalRules() {
        val content = base.copy(recurrence = base.recurrence!!.copy(rule = "FREQ=DAILY;COUNT=8;INTERVAL=2"))
        assertEquals(SeriesEditProfile(rule, CreateSeriesRule(8, 2)), SeriesEditProfile.of(base, content))
        refused { SeriesEditProfile.of(base, content.copy(recurrence = content.recurrence!!.copy(uid = "foreign"))) }
        refused { SeriesEditProfile.of(base, content.copy(recurrence = content.recurrence!!.copy(rule = "COUNT=8;FREQ=DAILY;INTERVAL=2"))) }
        refused { SeriesEditProfile.of(base.copy(recurrence = null), content) }
    }
    private val rule = CreateSeriesRule(5)
    private val start = Instant.parse("2030-04-01T10:00:00Z")
    private val time = EventTime.Timed(start, start.plusSeconds(3600), ZoneId.of("Europe/Paris"))
    private val base = EventContent("Series fixture", time, details = EventDetails(listOf(EventReminder(10))),
        colorRgb = 0x123456, recurrence = EventRecurrence("fixture@calendeer.invalid", rule.rule))
    private fun intent(before: EventContent = base, after: EventContent = before.copy(title = "Updated series"),
        profile: SeriesEditProfile? = SeriesEditProfile(rule, rule)) = LocalEditIntent(MutationId("series-edit"),
        CalendarScope(AccountId("fixture"), CalendarId("calendar"), 0), AdapterFormat("fixture", 1),
        RemoteChange.Upsert(EventId("master"), Revision("revision"), before), after, seriesProfile = profile)
    private fun refused(block: () -> Any?) {
        try { block(); fail("Unsupported series must be retained outside the writer") }
        catch (_: IllegalArgumentException) { }
        catch (_: java.nio.charset.CharacterCodingException) { }
    }

    @Test fun titleChangeKeepsCanonicalIdentityAndDoesNotAuthorizeAnySimpleWriter() {
        val candidate = SeriesEditCandidate(intent())
        assertEquals(setOf(SeriesEditField.TITLE), candidate.changedFields)
        assertEquals(base.recurrence, candidate.intent.content.recurrence)
        assertEquals(SeriesEditProfile(rule, rule), candidate.intent.seriesProfile)
        refused { PersonalEditCandidate(candidate.intent) }
        refused { LocalEditPreparation.freezeIntent(candidate.intent) }
        refused { LocalTextEditPreparation.freezeIntent(candidate.intent) }
        refused { LocalTimeEditCandidate(candidate.intent) }
        refused { intent(profile = null) }
    }
    @Test fun ruleOnlyEditAndCombinedPersonalChangesAreDistinct() {
        val next = CreateSeriesRule(8, 2)
        val changed = base.copy(recurrence = base.recurrence!!.copy(rule = next.rule))
        val candidate = SeriesEditCandidate(intent(after = changed, profile = SeriesEditProfile(rule, next)))
        assertEquals(setOf(SeriesEditField.RULE), candidate.changedFields)
        val combined = changed.copy(title = "Changed", description = "Notes", location = "Place",
            time = time.copy(end = time.end.plusSeconds(3600)), details = EventDetails(), colorRgb = null)
        assertEquals(SeriesEditField.entries.toSet(),
            SeriesEditCandidate(intent(after = combined, profile = SeriesEditProfile(rule, next))).changedFields)
        refused { intent(after = base) }
    }
    @Test fun timeKindAndZoneTransitionsKeepTheSameUidAndRule() {
        val day = EventTime.AllDay(LocalDate.of(2030, 4, 1), LocalDate.of(2030, 4, 3))
        for ((before, after) in listOf(base to base.copy(time = day), base.copy(time = day) to base,
            base to base.copy(time = time.copy(zone = ZoneId.of("UTC"))))) {
            assertEquals(setOf(SeriesEditField.TIME), SeriesEditCandidate(intent(before, after)).changedFields)
        }
    }
    @Test fun foreignUidOrProfileAndNonCanonicalRulesAreNeverAdopted() {
        refused { intent(after = base.copy(recurrence = base.recurrence!!.copy(uid = "other@calendeer.invalid"))) }
        refused { intent(profile = SeriesEditProfile(CreateSeriesRule(3), rule)) }
        refused { intent(after = base.copy(recurrence = base.recurrence!!.copy(rule = "FREQ=DAILY;COUNT=3"))) }
        for (raw in listOf("COUNT=5;FREQ=DAILY", "FREQ=WEEKLY;COUNT=5", "FREQ=DAILY", "FREQ=DAILY;UNTIL=20300405")) {
            refused { intent(before = base.copy(recurrence = base.recurrence!!.copy(rule = raw))) }
        }
    }
    @Test fun excludedAddedCancelledOrDetachedSlotsAndParticipantsRemainUnsupported() {
        val point = RecurrencePoint.AllDay(LocalDate.of(2030, 4, 2))
        for (recurrence in listOf(base.recurrence!!.copy(excludedDates = listOf(point)),
            base.recurrence!!.copy(additionalDates = listOf(point)),
            EventRecurrence(base.recurrence!!.uid, original = point),
            EventRecurrence(base.recurrence!!.uid, original = point, cancelled = true))) {
            refused { intent(before = base.copy(recurrence = recurrence)) }
            refused { intent(after = base.copy(recurrence = recurrence)) }
        }
        refused { intent(before = base.copy(details = null)) }
        refused { intent(after = base.copy(details = EventDetails(organizer = EventPerson("fixture@example.invalid", "Fixture")))) }
    }
    @Test fun everyOccurrenceOfBothVersionsMustHaveUnambiguousTimeAndStableElapsedDuration() {
        val gapStart = Instant.parse("2030-03-29T01:30:00Z")
        val gap = base.copy(time = EventTime.Timed(gapStart, gapStart.plusSeconds(3600), ZoneId.of("Europe/Paris")))
        refused { intent(before = gap) }; refused { intent(after = gap) }
        val changingStart = Instant.parse("2030-03-29T00:00:00Z")
        val changing = base.copy(time = EventTime.Timed(changingStart, changingStart.plusSeconds(10800), ZoneId.of("Europe/Paris")))
        refused { intent(before = changing) }; refused { intent(after = changing) }
    }
    @Test fun frozenListsAndConflictStateCannotBeReinterpretedAsFreshAdmission() {
        val reminders = mutableListOf(EventReminder(10))
        val excluded = mutableListOf<RecurrencePoint>()
        val original = base.copy(details = EventDetails(reminders), recurrence = base.recurrence!!.copy(excludedDates = excluded))
        val candidate = SeriesEditCandidate(intent(before = original))
        reminders.add(EventReminder(20)); excluded.add(RecurrencePoint.AllDay(LocalDate.of(2030, 4, 2)))
        assertEquals(listOf(EventReminder(10)), candidate.intent.content.details!!.reminders)
        assertTrue(candidate.intent.base.content.recurrence!!.excludedDates.isEmpty())
        val conflicted = candidate.intent.copy(state = LocalEditState.CONFLICT)
        assertEquals(candidate.intent.seriesProfile, conflicted.seriesProfile)
        refused { SeriesEditCandidate(conflicted) }
        assertFalse(candidate.toString().contains("fixture"))
        assertFalse(candidate.intent.seriesProfile.toString().contains("COUNT"))
    }
    @Test fun contentValidationCannotBeBypassedByTheSeriesProfile() {
        for (next in listOf<() -> EventContent>({ base.copy(title = "bad\u0001") }, { base.copy(title = "\ud800") },
            { base.copy(time = time.copy(end = time.end.plusNanos(1))) },
            { base.copy(time = time.copy(zone = ZoneOffset.ofHours(2))) })) {
            refused { SeriesEditCandidate(intent(after = next())) }
        }
    }
    @Test fun identicalUidStillRequiresValidUtf8AndNoControlCharacters() {
        for (uid in listOf("fixture\u0001", "\ud800")) {
            val original = base.copy(recurrence = base.recurrence!!.copy(uid = uid))
            refused { SeriesEditCandidate(intent(before = original)) }
        }
    }
}
