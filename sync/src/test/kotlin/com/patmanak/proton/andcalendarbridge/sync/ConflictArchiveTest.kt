// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ConflictArchiveTest {
    private val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 0)
    private val ref = ConflictArchiveRef(scope, 1, EventId("synthetic-event"), MutationId("synthetic-mutation"), MutationKind.UPDATE)
    private val format = AdapterFormat("fixture", 1)
    private val content = EventContent("Private fixture", EventTime.AllDay(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)))
    private val base = RemoteChange.Upsert(ref.eventId, Revision("opaque"), content)
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Invalid archive model accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun pageLimitsAndExactGenerationAreRequired() {
        for (limit in listOf(0, 51)) {
            rejected { ConflictArchiveQuery(scope, limit) }; rejected { ConflictObservationQuery(ref, limit) }
        }
        rejected { ConflictArchiveQuery(scope, cursor = ConflictArchiveCursor(scope.copy(generation = 1), 1)) }
        rejected { ConflictObservationQuery(ref, cursor = ConflictObservationCursor(ref.copy(mutationId = MutationId("other")), 1)) }
        rejected { ConflictArchiveCursor(scope, 1, 2) }; rejected { ConflictObservationCursor(ref, 1, 2) }
        rejected { ConflictArchiveCursor(scope, 0) }; rejected { ref.copy(kind = MutationKind.CREATE) }
    }
    @Test fun pagesFreezeCallerListsAndBindContinuationToLastRow() {
        val caller = mutableListOf(ref)
        val page = ConflictArchivePage(scope, caller, ConflictArchiveCursor(scope, 1))
        caller.clear(); assertEquals(listOf(ref), page.entries)
        try { (page.entries as MutableList).clear(); fail("Mutable page") } catch (_: UnsupportedOperationException) { }
        rejected { ConflictArchivePage(scope, listOf(ref), ConflictArchiveCursor(scope, 2)) }
        rejected { ConflictArchivePage(scope, listOf(ref, ref), null) }
        rejected { ConflictArchivePage(scope, listOf(ref.copy(scope = scope.copy(generation = 1))), null) }
    }
    @Test fun observationKindsDoNotConfuseAbsenceDeferredAndTombstone() {
        val absent = ConflictObservation(1, ConflictObservationKind.UNOBSERVED, ConflictObservationOrigin.CACHED, null, null, null, null)
        val deferred = ConflictObservation(2, ConflictObservationKind.DEFERRED, ConflictObservationOrigin.RECEIVED, Instant.EPOCH,
            format, null, DeferredEventReason.UNSUPPORTED_CONTENT)
        val deleted = RemoteChange.Deleted(ref.eventId, Revision("gone"))
        val tombstone = ConflictObservation(3, ConflictObservationKind.VERIFIED, ConflictObservationOrigin.RECEIVED, Instant.EPOCH, format, deleted, null)
        assertNull(absent.change); assertNull(deferred.change); assertEquals(deleted, tombstone.change)
        rejected { ConflictObservation(1, ConflictObservationKind.VERIFIED, ConflictObservationOrigin.CACHED, null, format, null, null) }
        rejected { ConflictObservation(1, ConflictObservationKind.DEFERRED, ConflictObservationOrigin.CACHED, null, null, null, DeferredEventReason.UNSUPPORTED_CONTENT) }
        rejected { ConflictObservation(1, ConflictObservationKind.UNOBSERVED, ConflictObservationOrigin.CACHED, null, format, null, null) }
    }
    @Test fun collectionTimestampCannotBeInventedForCachedEvidence() {
        rejected { ConflictObservation(1, ConflictObservationKind.VERIFIED, ConflictObservationOrigin.CACHED, Instant.EPOCH, format, base, null) }
        rejected { ConflictObservation(1, ConflictObservationKind.VERIFIED, ConflictObservationOrigin.RECEIVED, null, format, base, null) }
    }
    @Test fun unreadableCacheMarkerHasNoInventedContentReasonOrReceiptTime() {
        val unreadable = ConflictObservation(1, ConflictObservationKind.UNREADABLE, ConflictObservationOrigin.CACHED, null, format, null, null)
        assertNull(unreadable.change); assertNull(unreadable.deferredReason); assertNull(unreadable.observedAt)
        rejected { ConflictObservation(1, ConflictObservationKind.UNREADABLE, ConflictObservationOrigin.RECEIVED, Instant.EPOCH, format, null, null) }
        rejected { ConflictObservation(1, ConflictObservationKind.UNREADABLE, ConflictObservationOrigin.CACHED, null, format, base, null) }
        rejected { ConflictObservation(1, ConflictObservationKind.UNREADABLE, ConflictObservationOrigin.CACHED, null, format, null, DeferredEventReason.UNSUPPORTED_CONTENT) }
    }
    @Test fun displayContentAndObservationListsAreDefensivelyFrozen() {
        val reminders = mutableListOf(EventReminder(10))
        val withDetails = content.copy(details = EventDetails(reminders))
        val detail = ConflictArchiveDetail(ref, format, base.copy(content = withDetails), withDetails.copy(title = "Local"))
        val observation = ConflictObservation(1, ConflictObservationKind.VERIFIED, ConflictObservationOrigin.CACHED, null, format, detail.base, null)
        reminders.clear()
        assertEquals(listOf(EventReminder(10)), detail.base.content.details?.reminders)
        assertEquals(listOf(EventReminder(10)), (observation.change as RemoteChange.Upsert).content.details?.reminders)
        val list = mutableListOf(observation); val page = ConflictObservationPage(ref, list, null); list.clear()
        assertEquals(1, page.entries.size)
        rejected { ConflictObservationPage(ref.copy(eventId = EventId("other")), listOf(observation), null) }
    }
    @Test fun identitiesAreBoundedAndMalformedUnicodeIsRejected() {
        rejected { ref.copy(eventId = EventId("x".repeat(4097))) }
        rejected { ref.copy(mutationId = MutationId("\uD800")) }
        rejected { ConflictArchiveQuery(scope.copy(account = AccountId("invalid\n"))) }
        rejected { ConflictArchiveDetail(ref, format, base.copy(eventId = EventId("other")), content) }
    }
    @Test fun diagnosticsNeverContainIdentityOrDisplayContent() {
        val values = listOf(ref, ConflictArchiveCursor(scope, 1), ConflictArchiveQuery(scope), ConflictArchivePage(scope, listOf(ref), null),
            ConflictArchiveDetail(ref, format, base, content), ConflictObservationCursor(ref, 1), ConflictObservationQuery(ref),
            ConflictObservation(1, ConflictObservationKind.VERIFIED, ConflictObservationOrigin.CACHED, null, format, base, null),
            ConflictObservationPage(ref, emptyList(), null))
        values.forEach { assertTrue(it.toString().contains("REDACTED")); assertFalse(it.toString().contains("synthetic")); assertFalse(it.toString().contains("Private")) }
    }
}
