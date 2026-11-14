// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.calendar

enum class EventColorMode { EVENT, CALENDAR }

/** Display metadata only; the embedded projection/acknowledgement proof remains opaque. */
internal data class NativeColorStyle(val proof: String?, val masked: Boolean, val colorRgb: Int?) {
    init { require(colorRgb == null || colorRgb in 0..0xffffff) }
    fun marker(): String? = if (!masked) proof else
        proof.orEmpty() + SUFFIX + (colorRgb?.toString(16)?.padStart(6, '0') ?: "none")

    /** A masked null means unchanged color, never a request to erase Proton's color. */
    fun accepts(baseColor: Int?, nativeColor: Int?): Boolean = !masked ||
        (colorRgb == baseColor && nativeColor == null)

    fun logicalColor(nativeColor: Int?): Int? = if (masked) colorRgb else
        nativeColor?.takeUnless { it == 0 }?.and(0xffffff)

    fun forDisplay(mode: EventColorMode, nativeColor: Int?): NativeColorStyle? {
        if (masked && nativeColor != null) return null
        if (!masked && nativeColor != null && nativeColor != 0 && nativeColor.ushr(24) != 0xff) return null
        return copy(masked = mode == EventColorMode.CALENDAR, colorRgb = logicalColor(nativeColor))
    }

    companion object {
        private const val SUFFIX = "|calendeer-color-v1:"
        fun read(raw: String?): NativeColorStyle? {
            val offset = raw?.indexOf("|calendeer-color-") ?: -1
            if (offset == -1) return NativeColorStyle(raw, false, null)
            val tail = raw!!.substring(offset)
            if (!tail.matches(Regex("\\|calendeer-color-v1:(none|[a-f0-9]{6})"))) return null
            val color = tail.removePrefix(SUFFIX).takeUnless { it == "none" }?.toInt(16)
            return NativeColorStyle(raw.substring(0, offset).ifEmpty { null }, true, color)
        }
    }
}
