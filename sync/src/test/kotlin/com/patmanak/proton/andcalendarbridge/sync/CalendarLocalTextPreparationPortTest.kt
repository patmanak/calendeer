// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Replacement adapters must explicitly implement the guarded capability; no hidden fallback. */
class CalendarLocalTextPreparationPortTest {
    @Test fun unsupportedGuardedCapabilityDeniesWithoutUsingUnrestrictedPreparation() = runTest {
        var calls = 0
        var admissionCalls = 0
        val port = CalendarLocalTextPreparationPort { _, _ -> calls++; error("No fallback") }
        val content = EventContent("Synthetic", EventTime.AllDay(LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 2)))
        val intent = LocalEditIntent(MutationId("synthetic-edit"),
            CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 0),
            AdapterFormat("synthetic-observation", 1),
            RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-revision"), content),
            content.copy(description = "Changed"))
        assertNull(port.localTextFormat)
        assertNull(port.prepareLocalText(intent, Instant.parse("2030-01-01T12:00:00Z")) { admissionCalls++; true })
        assertEquals(0, calls)
        assertEquals(0, admissionCalls)
    }
}
