// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import android.content.ContentValues
import android.provider.CalendarContract.Events
import com.patmanak.proton.andcalendarbridge.sync.CreateSeriesRule
import com.patmanak.proton.andcalendarbridge.sync.EventTime

/** Supplies a logical DTEND to the existing time reader, never to the actual Provider row. */
internal fun nativeCreateSeriesSnapshot(values: ContentValues, localOwner: Boolean,
    readTime: (ContentValues, Boolean) -> EventTime?): Pair<EventTime, CreateSeriesRule>? {
    if (values[Events.DTEND] != null) return null
    val source = values.getAsString(Events.RRULE) ?: return null
    val rule = try { CreateSeriesRule.parse(source) } catch (_: IllegalArgumentException) { return null }
    val start = values.getAsLong(Events.DTSTART) ?: return null
    val duration = values.getAsString(Events.DURATION) ?: return null
    val end = seriesCreateEnd(start, duration, values.getAsInteger(Events.ALL_DAY) == 1) ?: return null
    val logical = ContentValues(values).apply { put(Events.DTEND, end) }
    val time = readTime(logical, localOwner) ?: return null
    if (!rule.supports(time)) return null
    return time to rule
}
