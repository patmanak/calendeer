// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

/** Fresh-write admission only. Historical capsules/readback keep their original RGB values.
 * Palette: ProtonMail/WebClients c9b5f2995744b6f7c8ff1301ca3d5ac4f7a18672,
 * packages/shared/lib/colors.ts ACCENT_COLORS_MAP. Values are compared without hex-case changes.
 */
internal object ProtonEventColourPolicy {
    private val palette = setOf(
        0x8080ff, 0xdb60d6, 0xec3e7c, 0xf78400, 0x936d58,
        0x5252cc, 0xa839a4, 0xba1e55, 0xc44800, 0x54473f,
        0x415df0, 0x179fd9, 0x1da583, 0x3cbb3a, 0xb4a40e,
        0x273eb2, 0x0a77a6, 0x0f735a, 0x258723, 0x807304)

    fun accepts(rgb: Int?): Boolean = rgb == null || rgb in palette
    fun supportedColors(): List<Int> = palette.toList()
    fun acceptsChange(before: Int?, after: Int?): Boolean = before == after || accepts(after)
}
