// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ProjectedEventReferenceTest {
    private fun change(start: String = "2030-10-26T00:30:00Z", rule: String = "FREQ=DAILY;COUNT=3") =
        RemoteChange.Upsert(EventId("private-fixture-event"), Revision("private-fixture-revision"), EventContent(
            "Private fixture title", EventTime.Timed(Instant.parse(start), Instant.parse(start).plusSeconds(7200), ZoneId.of("Europe/Paris")),
            "Private description", "Private location", 0x7A42CC, EventRecurrence("private-fixture-series", rule),
            EventDetails(listOf(EventReminder(15)), EventPerson("host@example.test"),
                listOf(EventAttendee(EventPerson("guest@example.test"), AttendanceRole.OPTIONAL, AttendanceStatus.TENTATIVE)))))
    private fun refused(block: () -> Unit) {
        try { block(); fail("Unsupported reference must be refused") } catch (_: IllegalArgumentException) { }
    }
    private fun immutable(block: () -> Unit) {
        try { block(); fail("Reference lists must be immutable") } catch (_: UnsupportedOperationException) { }
    }

    @Test fun directPreservesCanonicalDataAndUnknownDetailsWithoutClaimingProviderAdmission() {
        val source = change("2030-10-27T01:30:00Z").let { it.copy(content = it.content.copy(details = null)) }
        val reference = ProjectedEventReference.direct(source)
        assertEquals(source, reference.canonical); assertEquals(source, reference.nativeChange)
        assertNull(reference.nativeChange.content.details); assertTrue(reference.dates.isEmpty())
        assertEquals(ProjectionRepresentation.DIRECT_V1, reference.representation)
        // Direct representation is not an assertion that an ambiguous rule is safe in Android.
        refused { ProjectedEventReference.finiteDaily(source) }
    }

    @Test fun finiteViewChangesOnlyTheRuleAndAdditionalDatesAcrossBothTransitions() {
        for ((start, dates) in listOf(
            "2030-10-26T00:30:00Z" to listOf("2030-10-26T00:30:00Z", "2030-10-27T00:30:00Z", "2030-10-28T01:30:00Z"),
            "2030-03-30T01:30:00Z" to listOf("2030-03-30T01:30:00Z", "2030-03-31T01:30:00Z", "2030-04-01T00:30:00Z"),
        )) {
            val source = change(start); val reference = ProjectedEventReference.finiteDaily(source)
            val expected = dates.map(Instant::parse)
            assertEquals(source, reference.canonical); assertEquals(expected, reference.dates)
            assertEquals(ProjectionRepresentation.FINITE_DAILY_V1, reference.representation)
            assertEquals(source.copy(content = source.content.copy(recurrence = source.content.recurrence!!.copy(
                rule = null, additionalDates = expected.map { RecurrencePoint.Timed(it) }))), reference.nativeChange)
            assertEquals("FREQ=DAILY;COUNT=3", source.content.recurrence!!.rule)
            assertEquals(source.content.time, reference.nativeChange.content.time)
        }
    }

    @Test fun firstAndAllExclusionsStayOriginalSlotsAndDoNotChangeGeneratedCount() {
        val slots = listOf("2030-03-30T01:30:00Z", "2030-03-31T01:30:00Z", "2030-04-01T00:30:00Z").map(Instant::parse)
        for (excluded in listOf(slots.take(1), slots)) {
            val source = change(slots.first().toString()).let { it.copy(content = it.content.copy(
                recurrence = it.content.recurrence!!.copy(excludedDates = excluded.map { date -> RecurrencePoint.Timed(date) }))) }
            val reference = ProjectedEventReference.finiteDaily(source)
            assertEquals(slots, reference.dates)
            assertEquals(source.content.recurrence!!.excludedDates, reference.nativeChange.content.recurrence!!.excludedDates)
            assertEquals(source, reference.canonical)
        }
    }

    @Test fun snapshotsOwnCanonicalNativeAndDateListsAndKeepStableHashAfterCallerMutation() {
        val additions = mutableListOf<RecurrencePoint>()
        val exclusions = mutableListOf<RecurrencePoint>(RecurrencePoint.Timed(Instant.parse("2030-10-27T00:30:00Z")))
        val reminders = mutableListOf(EventReminder(15))
        val attendees = mutableListOf(EventAttendee(EventPerson("guest@example.test"), AttendanceRole.REQUIRED, AttendanceStatus.NEEDS_ACTION))
        val source = change().let { it.copy(content = it.content.copy(
            recurrence = it.content.recurrence!!.copy(additionalDates = additions, excludedDates = exclusions),
            details = EventDetails(reminders, attendees = attendees))) }
        val direct = ProjectedEventReference.direct(source); val finite = ProjectedEventReference.finiteDaily(source)
        val expected = change().let { it.copy(content = it.content.copy(
            recurrence = it.content.recurrence!!.copy(excludedDates = exclusions.toList()),
            details = EventDetails(reminders.toList(), attendees = attendees.toList()))) }
        val hashes = listOf(direct.hashCode(), finite.hashCode())
        additions.add(RecurrencePoint.Timed(Instant.parse("2030-11-01T00:30:00Z")))
        exclusions.clear(); reminders.clear(); attendees.clear()
        for (reference in listOf(direct, finite)) {
            assertEquals(expected, reference.canonical)
            immutable { (reference.canonical.content.recurrence!!.additionalDates as MutableList).clear() }
            immutable { (reference.canonical.content.recurrence!!.excludedDates as MutableList).clear() }
            immutable { (reference.nativeChange.content.details!!.reminders as MutableList).clear() }
            immutable { (reference.nativeChange.content.details!!.attendees as MutableList).clear() }
            immutable { (reference.dates as MutableList).clear() }
        }
        immutable { (finite.nativeChange.content.recurrence!!.additionalDates as MutableList).clear() }
        assertEquals(hashes, listOf(direct.hashCode(), finite.hashCode()))
    }

    @Test fun aliasesInvalidatedBeforeFactoryAreRefusedInsteadOfDiscarded() {
        fun rejectBoth(source: RemoteChange.Upsert) {
            refused { ProjectedEventReference.direct(source) }; refused { ProjectedEventReference.finiteDaily(source) }
        }
        val reminders = mutableListOf(EventReminder(15))
        val reminderSource = change().let { it.copy(content = it.content.copy(details = EventDetails(reminders))) }
        reminders.add(EventReminder(15)); rejectBoth(reminderSource)
        val attendees = mutableListOf(EventAttendee(EventPerson("guest@example.test"), AttendanceRole.REQUIRED, AttendanceStatus.NEEDS_ACTION))
        val attendeeSource = change().let { it.copy(content = it.content.copy(details = EventDetails(attendees = attendees))) }
        attendees.add(EventAttendee(EventPerson("GUEST@example.test"), AttendanceRole.OPTIONAL, AttendanceStatus.ACCEPTED)); rejectBoth(attendeeSource)
        val exclusions = mutableListOf<RecurrencePoint>()
        val recurrenceSource = change().let { it.copy(content = it.content.copy(recurrence = it.content.recurrence!!.copy(excludedDates = exclusions))) }
        exclusions.add(RecurrencePoint.AllDay(LocalDate.of(2030, 10, 27))); rejectBoth(recurrenceSource)
        exclusions.clear(); exclusions.add(RecurrencePoint.Timed(Instant.parse("2030-10-27T00:30:00.001Z"))); rejectBoth(recurrenceSource)
    }

    @Test fun representationAndExactCanonicalContentParticipateInEquality() {
        val source = change(rule = "FREQ=DAILY;COUNT=1")
        val direct = ProjectedEventReference.direct(source); val finite = ProjectedEventReference.finiteDaily(source)
        assertNotEquals(direct, finite)
        assertEquals(direct, ProjectedEventReference.direct(source)); assertEquals(direct.hashCode(), ProjectedEventReference.direct(source).hashCode())
        assertEquals(finite, ProjectedEventReference.finiteDaily(source)); assertEquals(finite.hashCode(), ProjectedEventReference.finiteDaily(source).hashCode())
        assertNotEquals(finite, ProjectedEventReference.finiteDaily(source.copy(revision = Revision("other-opaque"))))
        assertNotEquals(finite, ProjectedEventReference.finiteDaily(source.copy(content = source.content.copy(title = "Other title"))))
        assertEquals("direct", direct.representation.id); assertEquals("finite-daily-dates", finite.representation.id)
        assertEquals(1, direct.representation.version); assertEquals(1, finite.representation.version)
    }

    @Test fun finiteFactoriesRefuseUnsupportedRulesMastersAndAdditionalDates() {
        for (rule in listOf("FREQ=WEEKLY;COUNT=3", "FREQ=DAILY;COUNT=33", "FREQ=DAILY;COUNT=3;INTERVAL=184", "FREQ=DAILY;UNTIL=20301101T003000Z"))
            refused { ProjectedEventReference.finiteDaily(change(rule = rule)) }
        val source = change()
        for (content in listOf(
            source.content.copy(recurrence = null),
            source.content.copy(time = EventTime.AllDay(LocalDate.of(2030, 10, 26), LocalDate.of(2030, 10, 27))),
            source.content.copy(recurrence = EventRecurrence("fixture-series", original = RecurrencePoint.Timed(Instant.parse("2030-10-26T00:30:00Z")))),
            source.content.copy(recurrence = source.content.recurrence!!.copy(additionalDates = listOf(RecurrencePoint.Timed(Instant.parse("2030-11-01T00:30:00Z"))))),
        )) refused { ProjectedEventReference.finiteDaily(source.copy(content = content)) }
    }

    @Test fun directRetainsExceptionOriginalTypeEvenWhenItsDisplayChanges() {
        val source = change().let { it.copy(content = it.content.copy(recurrence = EventRecurrence("fixture-series",
            original = RecurrencePoint.AllDay(LocalDate.of(2030, 10, 26)), cancelled = true))) }
        val direct = ProjectedEventReference.direct(source)
        assertEquals(source, direct.canonical); assertEquals(source, direct.nativeChange)
        refused { ProjectedEventReference.finiteDaily(source) }
    }

    @Test fun referenceDiagnosticsDoNotExposeEventContentDatesOrIdentities() {
        for (reference in listOf(ProjectedEventReference.direct(change()), ProjectedEventReference.finiteDaily(change())))
            assertEquals("ProjectedEventReference(redacted)", reference.toString())
    }
}
