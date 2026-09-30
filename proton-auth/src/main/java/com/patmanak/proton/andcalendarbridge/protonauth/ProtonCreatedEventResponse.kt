// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody

/** Bounded strict-UTF-8 readback, refusing duplicate names before Json could keep the last value.
 * Import response interpretation remains separate. Callers still qualify scope and content.
 */
internal fun createdEventResponse(body: ResponseBody): JsonObject {
    val source = CalendarCatalogBodyReader.read(body)
    if (!UniqueJsonKeys(source).validate()) unsupportedEvents()
    val root = Json.parseToJsonElement(source) as? JsonObject ?: unsupportedEvents()
    if (root.number("Code") != 1000L) unsupportedEvents()
    return root
}
