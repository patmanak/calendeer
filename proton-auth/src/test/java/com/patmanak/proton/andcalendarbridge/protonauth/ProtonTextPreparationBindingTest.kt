// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import com.patmanak.proton.andcalendarbridge.protonauth.ProtonSharedTextProperty.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ProtonTextPreparationBindingTest {
    private fun binding(changes: Map<ProtonSharedTextProperty, ProtonSharedTextChange>) =
        ProtonTextPreparationBinding(CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 2),
            EventId("fixture-event"), Revision("fixture-revision"), "fixture-uid", "fixture-title", changes,
            Instant.parse("2026-10-03T12:00:00Z"))

    @Test fun callerMutationCannotChangeTheFrozenSelectionOrItsValues() {
        val original = linkedMapOf(DESCRIPTION to ProtonSharedTextChange(null, ""),
            LOCATION to ProtonSharedTextChange("old-place", null))
        val actual = binding(original)
        original.clear()
        original[SUMMARY] = ProtonSharedTextChange("other", "replacement")
        assertEquals(setOf(DESCRIPTION, LOCATION), actual.changes.keys)
        assertEquals(ProtonSharedTextChange(null, ""), actual.changes[DESCRIPTION])
        assertEquals(ProtonSharedTextChange("old-place", null), actual.changes[LOCATION])
    }

    @Test fun EachGetterIsAnIndependentCopyAndKeepsAbsenceDistinctFromEmpty() {
        val actual = binding(mapOf(DESCRIPTION to ProtonSharedTextChange(null, ""),
            LOCATION to ProtonSharedTextChange("", null)))
        val first = actual.changes
        assertNotSame(first, actual.changes)
        @Suppress("UNCHECKED_CAST")
        (first as MutableMap<ProtonSharedTextProperty, ProtonSharedTextChange>).clear()
        assertEquals(2, actual.changes.size)
        assertNull(actual.changes.getValue(DESCRIPTION).expected)
        assertEquals("", actual.changes.getValue(DESCRIPTION).replacement)
        assertEquals("", actual.changes.getValue(LOCATION).expected)
        assertNull(actual.changes.getValue(LOCATION).replacement)
    }

    @Test fun ProtectedLocatorAndDiagnosticsDoNotExposeTextOrProtectedPayload() {
        val actual = binding(mapOf(SUMMARY to ProtonSharedTextChange("fixture-title", "new-title")))
        val record = ProtonProtectedTextPreparation(actual.scope, actual.eventId, actual.revision,
            TEXT_PREPARATION_FORMAT, "synthetic-protected-payload")
        assertEquals(AdapterFormat("proton-calendar-offline-text-preparation", 1), record.format)
        assertNotEquals(TITLE_PREPARATION_FORMAT, record.format)
        assertEquals(actual.scope, record.scope)
        assertEquals(actual.eventId, record.eventId)
        assertEquals(actual.revision, record.revision)
        assertEquals("ProtonTextPreparationBinding(redacted)", actual.toString())
        assertEquals("ProtonProtectedTextPreparation(redacted)", record.toString())
        assertEquals("ProtonSharedTextChange(REDACTED)", actual.changes.getValue(SUMMARY).toString())
    }
}
