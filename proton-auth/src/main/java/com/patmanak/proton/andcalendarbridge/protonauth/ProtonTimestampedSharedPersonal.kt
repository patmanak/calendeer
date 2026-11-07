// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Separate offline temporal fragments; no request, current access proof or sending authority. */
internal class ProtonTimestampedSharedPersonal(originalRow: JsonObject, clearSharedPart: JsonObject,
    encryptedSharedPart: JsonObject, val changed: Boolean) {
    private val originalJson = originalRow.toString()
    private val clearJson = clearSharedPart.toString()
    private val encryptedJson = encryptedSharedPart.toString()
    val originalRow: JsonObject get() = Json.parseToJsonElement(originalJson) as JsonObject
    val clearSharedPart: JsonObject get() = Json.parseToJsonElement(clearJson) as JsonObject
    val encryptedSharedPart: JsonObject get() = Json.parseToJsonElement(encryptedJson) as JsonObject
    override fun toString() = "ProtonTimestampedSharedPersonal(REDACTED)"
}
