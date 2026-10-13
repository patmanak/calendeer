// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import java.time.Instant

internal class ProtonTitlePatchException : IllegalStateException("Shared title patch unavailable: UNSUPPORTED")

/** Historical title-only facade. Text preparation grants no transport or writable capability. */
internal object ProtonSharedTitlePatcher {
    fun patch(original: ProtonEventTextParts, expectedTitle: String, newTitle: String,
        expectedUid: String? = null): ProtonEventTextParts = legacy {
        ProtonSharedTextPatcher.patch(original, selected(expectedTitle, newTitle), expectedUid)
    }

    fun patchAt(original: ProtonEventTextParts, expectedTitle: String, newTitle: String,
        expectedUid: String, timestamp: Instant): ProtonEventTextParts = legacy {
        ProtonSharedTextPatcher.patchAt(original, selected(expectedTitle, newTitle), expectedUid, timestamp)
    }

    private fun selected(expected: String, replacement: String) =
        mapOf(ProtonSharedTextProperty.SUMMARY to ProtonSharedTextChange(expected, replacement))

    private inline fun legacy(block: () -> ProtonEventTextParts): ProtonEventTextParts = try {
        block()
    } catch (_: Exception) {
        // Preserve the original public-facing type/message and never attach parser/input causes.
        throw ProtonTitlePatchException()
    }
}
