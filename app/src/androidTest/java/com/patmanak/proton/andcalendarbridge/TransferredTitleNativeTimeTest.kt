// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.provider.CalendarContract.Events
import com.patmanak.proton.andcalendarbridge.sync.EventTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferredTitleNativeTimeTest {
    private val owner = "synthetic-calendar-owner"
    private val time = EventTime.AllDay(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-27"))
    private val flags = mapOf(Events.HAS_ATTENDEE_DATA to "1", Events.IS_ORGANIZER to "1",
        Events.GUESTS_CAN_INVITE_OTHERS to "1", Events.GUESTS_CAN_SEE_GUESTS to "1",
        Events.GUESTS_CAN_MODIFY to "0", Events.HAS_ALARM to "0", Events.SELF_ATTENDEE_STATUS to "0", Events.ACCESS_LEVEL to "0")
    private fun values(endZone: String? = null): Map<String, String?> {
        val bounds = TitleJourneyTimeProfile.bounds(time)
        return flags + mapOf(Events.ORGANIZER to owner, Events.ALL_DAY to "1", Events.DTSTART to bounds.first.toString(),
            Events.DTEND to bounds.second.toString(), Events.EVENT_TIMEZONE to "UTC", Events.EVENT_END_TIMEZONE to endZone)
    }
    private fun matches(values: Map<String, String?>, clean: Boolean = true) =
        TransferredTitleNativeTime.nativeMatches(time, values, owner, clean)

    @Test fun canonicalUtcAndQualifiedNullEndKeepTheExactExclusiveDates() {
        assertTrue(matches(values("UTC")))
        assertTrue(matches(values()))
        assertFalse(TitleJourneyTimeProfile.nativeMatches(time, "1", values()[Events.DTSTART], values()[Events.DTEND], "UTC", null))
    }
    @Test fun canonicalPathDoesNotBorrowPlaceholderRequirements() {
        assertTrue(matches(values("UTC") - flags.keys - Events.ORGANIZER))
    }
    @Test fun absentOrDifferentCleanFrameRejectsBothRepresentations() {
        assertFalse(matches(values(), clean = false))
        assertFalse(matches(values("UTC"), clean = false))
    }
    @Test fun nullEndRequiresTheExactPresentCalendarOwner() {
        assertFalse(matches(values() - Events.ORGANIZER))
        assertFalse(matches(values() + (Events.ORGANIZER to null)))
        assertFalse(matches(values() + (Events.ORGANIZER to "another-owner")))
        assertFalse(TransferredTitleNativeTime.nativeMatches(time, values(), "", true))
    }
    @Test fun everyPlaceholderFlagMustBePresentAndExact() {
        for ((field, expected) in flags) {
            assertFalse(matches(values() - field))
            assertFalse(matches(values() + (field to null)))
            assertFalse(matches(values() + (field to if (expected == "1") "0" else "1")))
        }
    }
    @Test fun missingTimeColumnsCannotMasqueradeAsExplicitNull() {
        for (field in listOf(Events.ALL_DAY, Events.DTSTART, Events.DTEND, Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE)) {
            assertFalse(matches(values() - field))
            assertFalse(matches(values("UTC") - field))
        }
    }
    @Test fun nonUtcAndWrongNonNullEndZonesRemainUnsupported() {
        for (zone in listOf("Europe/Paris", "Z", "", "invalid-zone")) {
            assertFalse(matches(values(zone)))
            assertFalse(matches(values() + (Events.EVENT_TIMEZONE to zone)))
        }
    }
    @Test fun shiftedMidnightsInclusiveEndsAndTimedReinterpretationAreRejected() {
        val bounds = TitleJourneyTimeProfile.bounds(time)
        assertFalse(matches(values() + (Events.DTSTART to (bounds.first + 3_600_000L).toString())))
        assertFalse(matches(values() + (Events.DTEND to (bounds.second - 86_400_000L).toString())))
        assertFalse(matches(values() + (Events.ALL_DAY to "0")))
        assertFalse(matches(values() + (Events.DTEND to bounds.first.toString())))
    }
    @Test fun timedCanonicalBehaviorKeepsAnExplicitNullableEndColumn() {
        val timed = EventTime.Timed(Instant.parse("2026-10-12T14:00:00Z"), Instant.parse("2026-10-12T15:00:00Z"), ZoneId.of("UTC"))
        val bounds = TitleJourneyTimeProfile.bounds(timed)
        val row = values() + mapOf(Events.ALL_DAY to "0", Events.DTSTART to bounds.first.toString(), Events.DTEND to bounds.second.toString())
        assertTrue(TransferredTitleNativeTime.nativeMatches(timed, row, owner, true))
        assertFalse(TransferredTitleNativeTime.nativeMatches(timed, row - Events.EVENT_END_TIMEZONE, owner, true))
    }
}
