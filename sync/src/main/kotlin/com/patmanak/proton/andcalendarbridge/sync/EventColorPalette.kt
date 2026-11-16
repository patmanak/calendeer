// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.sync

/** Optional account display capability; never permission to edit or submit an event. */
data class EventPaletteColor(val key: String, val rgb: Int) {
    init { require(key.length in 1..128 && key.all { it.code in 33..126 } && rgb in 0..0xffffff) }
    override fun toString() = "EventPaletteColor(REDACTED)"
}

fun interface EventColorPalette {
    /** Stable keys must never be reassigned to another RGB value. */
    fun colors(): List<EventPaletteColor>
}
