// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth
import kotlinx.serialization.json.*
/** Explicit v2 personal siblings. Their raw JSON is preserved exactly, including email delivery. */
internal object ProtonPersonalMetadata {
    fun valid(notifications: JsonElement?, color: JsonElement?): Boolean = try {
        val values = notifications as? JsonArray ?: error("Unsupported")
        require(values.size <= 100)
        values.forEach { item ->
            val value = item as? JsonObject ?: error("Unsupported")
            require(value.keys == setOf("Type", "Trigger"))
            require(value.number("Type") in 0L..1L)
            ProtonEventDetails.durationSeconds(value.string("Trigger"))
        }
        require(color == null || color == JsonNull || (color is JsonPrimitive && color.isString && color.content.matches(Regex("#[a-fA-F0-9]{6}"))))
        true
    } catch (_: Exception) { false }
}
