// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.importing

import android.database.Cursor
import com.patmanak.proton.andcalendarbridge.sync.*

/** Explicit observation formats, not preparation or submission capabilities. */
internal fun readLocalEditProfile(row: Cursor, column: Int, base: RemoteChange.Upsert,
    replacement: RemoteChange.Upsert): SeriesEditProfile? {
    require(row.getType(column) == Cursor.FIELD_TYPE_INTEGER)
    return when (row.getLong(column)) {
        1L -> null // Frozen historical simple observations.
        2L -> SeriesEditProfile(CreateSeriesRule.parse(requireNotNull(base.content.recurrence?.rule)),
            CreateSeriesRule.parse(requireNotNull(replacement.content.recurrence?.rule)))
        else -> throw IllegalArgumentException("Unsupported local edit profile")
    }
}
