// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Offline fragments, deliberately separate from the frozen original and from each other.
 * Not a complete event, UPDATE request, read receipt or proof of edit eligibility.
 * Copied opaque metadata has no renewed semantic guarantee. No-op results do not
 * certify that the timestamp layout would admit a changed title.
 */
internal class ProtonTimestampedSharedTitle internal constructor(
    original: JsonObject, clear: JsonObject, encrypted: JsonObject, val changed: Boolean
) {
    private val originalJson = original.toString()
    private val clearJson = clear.toString()
    private val encryptedJson = encrypted.toString()
    val originalRow: JsonObject get() = Json.parseToJsonElement(originalJson) as JsonObject
    val clearSharedPart: JsonObject get() = Json.parseToJsonElement(clearJson) as JsonObject
    val encryptedSharedPart: JsonObject get() = Json.parseToJsonElement(encryptedJson) as JsonObject
    override fun toString() = "ProtonTimestampedSharedTitle(redacted)"
}
