// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Separate offline temporal fragments; no request, current access proof or sending authority. */
internal class ProtonTimestampedSharedPersonal(originalRow: JsonObject, clearSharedPart: JsonObject,
    encryptedSharedPart: JsonObject, val changed: Boolean, defaults: JsonObject? = null, calendarPart: JsonObject? = null) {
    private val originalJson = originalRow.toString()
    private val clearJson = clearSharedPart.toString()
    private val encryptedJson = encryptedSharedPart.toString()
    private val defaultsJson = defaults?.toString()
    private val calendarJson = calendarPart?.toString()
    val originalRow: JsonObject get() = Json.parseToJsonElement(originalJson) as JsonObject
    val clearSharedPart: JsonObject get() = Json.parseToJsonElement(clearJson) as JsonObject
    val encryptedSharedPart: JsonObject get() = Json.parseToJsonElement(encryptedJson) as JsonObject
    val defaults: JsonObject? get() = defaultsJson?.let { Json.parseToJsonElement(it) as JsonObject }
    val calendarPart: JsonObject? get() = calendarJson?.let { Json.parseToJsonElement(it) as JsonObject }
    override fun toString() = "ProtonTimestampedSharedPersonal(REDACTED)"
}
