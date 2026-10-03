// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class LocalDeleteReviewTest {
    private val format = AdapterFormat("synthetic-import", 1)
    private val base = RemoteChange.Upsert(EventId("synthetic-event"), Revision("base"), EventContent("Private original",
        EventTime.AllDay(LocalDate.of(2030, 10, 1), LocalDate.of(2030, 10, 2)), details = EventDetails()))
    private val owner = LocalDeleteIntent(MutationId("synthetic-delete"),
        CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 0), format, base, base.content)
    private fun review() = LocalDeleteReview(owner, base, base, format, false)

    @Test fun unchangedImportKeepsDeletionIntentWithoutAcknowledgingIt() {
        val value = review()
        assertEquals(LocalEditImportRelation.UNCHANGED, value.importRelation)
        assertTrue(value.owner == owner); assertTrue(value.capturedBaseStillProjected); assertFalse(value.recordedConflict)
    }
    @Test fun importedTombstoneIsDistinctFromTheLocalDeleteAndAnAbsentRecord() {
        val tombstone = RemoteChange.Deleted(base.eventId, Revision("remote-delete"))
        assertEquals(LocalEditImportRelation.DELETED, review().copy(imported = tombstone).importRelation)
        assertTrue(review().copy(imported = tombstone).owner == owner)
        assertEquals(LocalEditImportRelation.UNOBSERVED, review().copy(imported = null).importRelation)
    }
    @Test fun changedContentOrRevisionKeepsBothCapturedVersions() {
        val captured = owner.copy(content = owner.content.copy(description = "Edit before local deletion"))
        for (remote in listOf(base.copy(revision = Revision("changed")), base.copy(content = base.content.copy(title = "Remote edit")))) {
            val value = review().copy(owner = captured, imported = remote)
            assertEquals(LocalEditImportRelation.CHANGED, value.importRelation)
            assertTrue(value.owner.base == base); assertTrue(value.owner.content == captured.content)
        }
    }
    @Test fun formatUncertaintyAndDeferredEvidenceOverrideComparisonAndDeletion() {
        val value = review().copy(imported = RemoteChange.Deleted(base.eventId, Revision("gone")), deferred = true)
        assertEquals(LocalEditImportRelation.FORMAT_UNKNOWN, value.copy(observationFormat = null).importRelation)
        assertEquals(LocalEditImportRelation.FORMAT_MISMATCH, value.copy(observationFormat = format.copy(version = 2)).importRelation)
        assertEquals(LocalEditImportRelation.DEFERRED, value.importRelation)
    }
    @Test fun recordedConflictNeverDisappearsWhenTheImportReturnsToBase() {
        val value = review().copy(owner = owner.copy(state = LocalEditState.CONFLICT))
        assertEquals(LocalEditImportRelation.UNCHANGED, value.importRelation); assertTrue(value.recordedConflict)
        assertTrue(value.owner.content == base.content)
    }
    @Test fun projectedReferenceDoesNotImplyCurrentProviderContents() {
        assertFalse(review().copy(projectedBase = null).capturedBaseStillProjected)
        assertFalse(review().copy(projectedBase = base.copy(revision = Revision("other"))).capturedBaseStillProjected)
    }
    @Test fun foreignIdentitiesAreRefusedAndDiagnosticsHideAllCapturedData() {
        for (remote in listOf(base.copy(eventId = EventId("foreign")), RemoteChange.Deleted(EventId("foreign"), Revision("gone")))) {
            try { review().copy(imported = remote); fail("Foreign import accepted") } catch (_: IllegalArgumentException) { }
        }
        try { review().copy(projectedBase = base.copy(eventId = EventId("foreign"))); fail("Foreign projection accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals("LocalDeleteReview(REDACTED)", review().toString())
    }
}
