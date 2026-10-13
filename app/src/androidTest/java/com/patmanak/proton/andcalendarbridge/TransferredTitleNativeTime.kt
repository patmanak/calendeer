// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge

import android.provider.CalendarContract.Events
import com.patmanak.proton.andcalendarbridge.sync.EventTime

/** Fixture-only qualification of a verified transferred row; never repairs native data. */
internal object TransferredTitleNativeTime {
    private val timeColumns = listOf(Events.ALL_DAY, Events.DTSTART, Events.DTEND,
        Events.EVENT_TIMEZONE, Events.EVENT_END_TIMEZONE)
    private val oneFlags = listOf(Events.HAS_ATTENDEE_DATA, Events.IS_ORGANIZER,
        Events.GUESTS_CAN_INVITE_OTHERS, Events.GUESTS_CAN_SEE_GUESTS)
    private val zeroFlags = listOf(Events.GUESTS_CAN_MODIFY, Events.HAS_ALARM,
        Events.SELF_ATTENDEE_STATUS, Events.ACCESS_LEVEL)

    fun qualifiedPlaceholder(values: Map<String, String?>, exactCalendarOwner: String,
        cleanFrameVerified: Boolean): Boolean = cleanFrameVerified && exactCalendarOwner.isNotEmpty() &&
        values.containsKey(Events.ORGANIZER) && values[Events.ORGANIZER] == exactCalendarOwner &&
        oneFlags.all { values.containsKey(it) && values[it] == "1" } &&
        zeroFlags.all { values.containsKey(it) && values[it] == "0" }

    fun nativeMatches(time: EventTime, values: Map<String, String?>, exactCalendarOwner: String,
        cleanFrameVerified: Boolean): Boolean {
        if (!cleanFrameVerified || !timeColumns.all(values::containsKey)) return false
        if (TitleJourneyTimeProfile.nativeMatches(time, values[Events.ALL_DAY], values[Events.DTSTART], values[Events.DTEND],
                values[Events.EVENT_TIMEZONE], values[Events.EVENT_END_TIMEZONE])) return true
        if (time !is EventTime.AllDay || values[Events.EVENT_END_TIMEZONE] != null ||
            !qualifiedPlaceholder(values, exactCalendarOwner, cleanFrameVerified)) return false
        // The existing strict DATE validator still checks UTC start zone, both UTC-midnight
        // bounds, exclusive end and ALL_DAY. Only this verified placeholder permits NULL end zone.
        return TitleJourneyTimeProfile.nativeMatches(time, values[Events.ALL_DAY], values[Events.DTSTART], values[Events.DTEND],
            values[Events.EVENT_TIMEZONE], "UTC")
    }
}
