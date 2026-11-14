// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

import android.content.ContentValues
import android.provider.CalendarContract.Events
import com.patmanak.proton.andcalendarbridge.data.calendar.AndroidCalendarProjection.Companion.ATTACHMENT_SYNC_COLUMNS

/** Decorate only native representation, never a frozen request or receipt. */
internal fun AndroidCalendarProjection.displayColor(values: ContentValues, color: Int?, proof: String?,
    previousMarker: String? = null): ContentValues = values.apply {
    val masked = colorMode() == EventColorMode.CALENDAR
    put(Events.SYNC_DATA3, NativeColorStyle(proof, masked, color).marker())
    if (masked || NativeColorStyle.read(previousMarker)?.masked == true) {
        putNull(Events.EVENT_COLOR_KEY)
        if (masked || color == null) putNull(Events.EVENT_COLOR)
        else put(Events.EVENT_COLOR, color or 0xff000000.toInt())
    }
}

/** Compare embedded proof; callers still assert the complete observed raw metadata. */
internal fun colorMetadataMatches(values: ContentValues, expected: ContentValues): Boolean {
    val style = NativeColorStyle.read(values.getAsString(Events.SYNC_DATA3)) ?: return false
    return expected.keySet().all {
        (if (it == Events.SYNC_DATA3) style.proof else values.getAsString(it)) == expected.getAsString(it)
    } && ATTACHMENT_SYNC_COLUMNS.all {
        it in expected.keySet() || it == Events.SYNC_DATA5 ||
            (if (it == Events.SYNC_DATA3) style.proof == null else values[it] == null)
    }
}
