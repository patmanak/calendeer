// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class OccurrenceMasterProjectionTest {
    private val date = LocalDate.of(2030, 4, 1)
    private val master = RemoteChange.Upsert(EventId("master"), Revision("first"), EventContent("Series",
        EventTime.AllDay(date, date.plusDays(1)), details = EventDetails(),
        recurrence = EventRecurrence("master@calendeer.invalid", "FREQ=DAILY;COUNT=5")))

    @Test fun acceptsMetadataAndRefusesAllGeometryChangesAndDeletion() {
        val next = master.copy(revision = Revision("next"), content = master.content.copy(title = "New title",
            description = "New description", location = "Updated location", colorRgb = 0x123456))
        assertTrue(compatibleOccurrenceMaster(master, next))
        assertFalse(compatibleOccurrenceMaster(master, next.copy(content = next.content.copy(title = "\uD800"))))
        for (other in listOf(next.copy(eventId = EventId("other")), next.copy(content = next.content.copy(
            time = EventTime.AllDay(date.plusDays(1), date.plusDays(2)))), next.copy(content = next.content.copy(
            recurrence = master.content.recurrence!!.copy(rule = "FREQ=DAILY;COUNT=6"))),
            next.copy(content = next.content.copy(recurrence = master.content.recurrence!!.copy(uid = "another@calendeer.invalid"))),
            RemoteChange.Deleted(master.eventId, Revision("deleted")))) assertFalse(compatibleOccurrenceMaster(master, other))
    }
}
