// SPDX-License-Identifier: GPL-3.0-only
// Narrow calendar-signed adaptation from Proton WebClients; see PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import kotlinx.serialization.json.*

/** Versioned sub-profile inside the existing personal family; never an invitation or encrypted part. */
internal object ProtonPersonalCalendarPart {
    fun original(row: JsonObject): JsonObject? {
        val parts = row.array("CalendarEvents")
        if (parts.isEmpty()) return null
        val part = parts.single() as JsonObject
        require(part.keys == setOf("Type", "Data", "Signature", "Author") && part.number("Type") == 2L)
        listOf("Data", "Signature", "Author").forEach { require(part.string(it).isNotBlank()) }
        ProtonSharedTextPatcher.calendarPart(part.string("Data"), row.string("UID"))
        return part
    }

    fun validate(row: JsonObject, replacement: JsonObject?, clear: JsonObject) {
        val original = original(row)
        require((original == null) == (replacement == null))
        if (original == null || replacement == null) return
        require(original.keys == replacement.keys && replacement.string("Signature").isNotBlank())
        require(JsonObject(original - setOf("Data", "Signature")) == JsonObject(replacement - setOf("Data", "Signature")))
        val before = row.array("SharedEvents").map { it as JsonObject }.single { it.number("Type") == 2L }
        require(replacement.string("Data") == ProtonSharedTextPatcher.calendarPart(original.string("Data"),
            row.string("UID"), before.string("Data"), clear.string("Data")))
        if (replacement["Data"] == original["Data"]) require(replacement == original)
    }

    fun profile(part: JsonObject) = buildJsonObject { put("version", 1); put("part", part) }
    fun open(profile: JsonObject): JsonObject {
        require(profile.keys == setOf("version", "part") && profile.number("version") == 1L)
        return profile.obj("part")
    }
}
