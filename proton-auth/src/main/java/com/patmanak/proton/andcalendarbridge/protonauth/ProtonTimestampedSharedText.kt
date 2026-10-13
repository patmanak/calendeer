// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Verified offline fragments, separately snapshotted from the complete original observation.
 * Not an UPDATE request, current rights proof, persistent journal or upload capability.
 * A no-op does not certify metadata eligibility for a later changed text selection.
 */
internal class ProtonTimestampedSharedText internal constructor(
    originalRow: JsonObject, clearSharedPart: JsonObject, encryptedSharedPart: JsonObject, val changed: Boolean,
) {
    private val originalJson = originalRow.toString()
    private val clearJson = clearSharedPart.toString()
    private val encryptedJson = encryptedSharedPart.toString()
    val originalRow: JsonObject get() = Json.parseToJsonElement(originalJson) as JsonObject
    val clearSharedPart: JsonObject get() = Json.parseToJsonElement(clearJson) as JsonObject
    val encryptedSharedPart: JsonObject get() = Json.parseToJsonElement(encryptedJson) as JsonObject
    override fun toString() = "ProtonTimestampedSharedText(redacted)"
}
