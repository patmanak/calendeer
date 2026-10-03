// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class LocalEditReviewTest {
    private val format = AdapterFormat("synthetic-import", 1)
    private val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("base"),
        EventContent("Original private title", EventTime.AllDay(LocalDate.of(2030, 10, 1), LocalDate.of(2030, 10, 2)), details = EventDetails()))
    private val owner = LocalEditIntent(MutationId("synthetic-owner"), CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 0),
        format, base, base.content.copy(title = "Captured private title"))
    private fun review() = LocalEditReview(owner, base, base, format, false, null)

    @Test fun exactImportedBaseIsUnchangedWithoutGrantingUploadAuthority() {
        val value = review()
        assertEquals(LocalEditImportRelation.UNCHANGED, value.importRelation)
        assertTrue(value.capturedBaseStillProjected); assertTrue(value.titleOnly); assertFalse(value.recordedConflict)
    }
    @Test fun recordedConflictSurvivesAnImportReturningToTheBase() {
        val value = review().copy(owner = owner.copy(state = LocalEditState.CONFLICT))
        assertEquals(LocalEditImportRelation.UNCHANGED, value.importRelation); assertTrue(value.recordedConflict)
        assertEquals(owner.base, value.owner.base); assertEquals(owner.content, value.owner.content)
    }
    @Test fun changedRevisionOrContentIsChangedWithoutOrderingOpaqueRevisions() {
        for (event in listOf(base.copy(revision = Revision("other")), base.copy(content = base.content.copy(title = "Remote private title")))) {
            assertEquals(LocalEditImportRelation.CHANGED, review().copy(imported = event).importRelation)
        }
    }
    @Test fun absenceIsUnobservedAndOnlyAnExplicitTombstoneIsDeleted() {
        assertEquals(LocalEditImportRelation.UNOBSERVED, review().copy(imported = null).importRelation)
        assertEquals(LocalEditImportRelation.DELETED, review().copy(imported = RemoteChange.Deleted(base.eventId, Revision("delete"))).importRelation)
    }
    @Test fun unknownOrDifferentFormatsPreventComparisonIncludingDeletion() {
        val deleted = review().copy(imported = RemoteChange.Deleted(base.eventId, Revision("delete")), deferred = true)
        assertEquals(LocalEditImportRelation.FORMAT_UNKNOWN, deleted.copy(observationFormat = null).importRelation)
        assertEquals(LocalEditImportRelation.FORMAT_MISMATCH, deleted.copy(observationFormat = format.copy(version = 2)).importRelation)
    }
    @Test fun deferredEvidencePreventsTreatingAnOldRecordAsVerifiedCurrentContent() {
        assertEquals(LocalEditImportRelation.DEFERRED, review().copy(deferred = true).importRelation)
        assertEquals(LocalEditImportRelation.DEFERRED, review().copy(imported = null, deferred = true).importRelation)
    }
    @Test fun baselineChangesAndNonTitleEditsRemainDistinctObservations() {
        val value = review().copy(projectedBase = null, owner = owner.copy(content = owner.content.copy(description = "Captured description")))
        assertFalse(value.capturedBaseStillProjected); assertFalse(value.titleOnly)
        assertEquals(LocalEditImportRelation.UNCHANGED, value.importRelation)
    }
    @Test fun foreignIdentitiesAreRefusedAndDiagnosticsHideContentAndMetadata() {
        for (event in listOf(base.copy(eventId = EventId("foreign")), RemoteChange.Deleted(EventId("foreign"), Revision("delete")))) {
            try { review().copy(imported = event); fail("Foreign observation accepted") } catch (_: IllegalArgumentException) { }
        }
        try { review().copy(projectedBase = base.copy(eventId = EventId("foreign"))); fail("Foreign baseline accepted") }
        catch (_: IllegalArgumentException) { }
        val info = RetainedLocalTitleInfo(AdapterFormat("synthetic-preparation", 4), Instant.parse("2030-10-01T10:00:00Z"))
        assertEquals("LocalEditReview(REDACTED)", review().copy(retainedTitle = info).toString())
        assertEquals("RetainedLocalTitleInfo(REDACTED)", info.toString())
    }
}
