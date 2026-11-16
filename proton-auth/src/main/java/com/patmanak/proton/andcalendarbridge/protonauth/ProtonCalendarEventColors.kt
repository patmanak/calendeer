// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.EventColorPalette
import com.patmanak.proton.andcalendarbridge.sync.EventPaletteColor

/** Offline display metadata, sharing the concrete adapter's fresh-write palette. */
object ProtonCalendarEventColors : EventColorPalette {
    private val entries = java.util.Collections.unmodifiableList(ProtonEventColourPolicy.supportedColors().map {
        EventPaletteColor("calendeer-proton-event-v1-" + it.toString(16).padStart(6, '0'), it)
    })
    override fun colors(): List<EventPaletteColor> = entries
}
