// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.AdapterFormat
import com.patmanak.proton.andcalendarbridge.sync.CalendarScope
import com.patmanak.proton.andcalendarbridge.sync.EventId
import com.patmanak.proton.andcalendarbridge.sync.Revision
import java.time.Instant

internal val TEXT_PREPARATION_FORMAT = AdapterFormat("proton-calendar-offline-text-preparation", 1)

/** Frozen caller observation only, never proof of current access or dispatch authority. */
internal class ProtonTextPreparationBinding(
    val scope: CalendarScope, val eventId: EventId, val revision: Revision,
    val uid: String, val expectedTitle: String, changes: Map<ProtonSharedTextProperty, ProtonSharedTextChange>,
    val timestamp: Instant,
) {
    private val frozenChanges = LinkedHashMap(changes)
    val changes: Map<ProtonSharedTextProperty, ProtonSharedTextChange> get() = LinkedHashMap(frozenChanges)
    override fun toString() = "ProtonTextPreparationBinding(redacted)"
}

/** Texts, UID and timestamp belong inside the protected payload, not its public locator. */
internal class ProtonProtectedTextPreparation(
    val scope: CalendarScope, val eventId: EventId, val revision: Revision,
    val format: AdapterFormat, val protectedPayload: String,
) {
    override fun toString() = "ProtonProtectedTextPreparation(redacted)"
}
