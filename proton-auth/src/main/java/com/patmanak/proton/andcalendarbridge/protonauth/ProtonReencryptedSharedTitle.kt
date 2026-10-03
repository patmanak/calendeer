// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Ephemeral cryptographic experiment, not an UPDATE request or a sealed read receipt.
 * The replacement response-shaped shared part is deliberately separate from originalRow.
 * Original clear text, signatures, key packets and opaque partitions remain in the original.
 * Other part metadata is copied structurally, without proving its continued semantic validity.
 * Timestamps/sequence, event permissions and concurrent writes still require qualification.
 */
internal class ProtonReencryptedSharedTitle internal constructor(original: JsonObject, replacement: JsonObject, val changed: Boolean) {
    private val originalJson = original.toString()
    private val replacementJson = replacement.toString()
    val originalRow: JsonObject get() = Json.parseToJsonElement(originalJson) as JsonObject
    val encryptedSharedPart: JsonObject get() = Json.parseToJsonElement(replacementJson) as JsonObject
    override fun toString() = "ProtonReencryptedSharedTitle(redacted)"
}
