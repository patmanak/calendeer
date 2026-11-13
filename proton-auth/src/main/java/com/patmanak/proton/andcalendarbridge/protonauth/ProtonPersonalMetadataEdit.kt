// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import biweekly.component.VEvent
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Duration
import java.util.Locale
import kotlinx.serialization.json.*

/** Personal API siblings only. Unchanged historical v2 values retain their exact representation. */
internal object ProtonPersonalMetadataEdit {
    fun apply(original: JsonObject, before: EventContent, after: EventContent): JsonObject {
        require(ProtonPersonalMetadata.valid(original["Notifications"], original["Color"]))
        var result = original
        if (before.details?.reminders != after.details?.reminders) {
            val previous = requireNotNull(before.details)
            val next = requireNotNull(after.details)
            require(previous.organizer == null && previous.attendees.isEmpty() && next.organizer == null && next.attendees.isEmpty())
            require(ProtonEventDetails.parse(VEvent(), original, null, before.time is EventTime.AllDay).reminders == previous.reminders)
            val emails = original.array("Notifications").filter { (it as JsonObject).number("Type") == 0L }
            val display = next.reminders.map { reminder -> buildJsonObject {
                val minutes = reminder.minutesBefore.toLong()
                val trigger = (if (minutes > 0) "-" else "") + Duration.ofMinutes(kotlin.math.abs(minutes)).toString()
                put("Type", 1); put("Trigger", trigger)
            } }
            require(emails.size + display.size <= 100)
            result = JsonObject(result + ("Notifications" to JsonArray(emails + display)))
        }
        if (before.colorRgb != after.colorRgb) {
            val raw = original["Color"]
            val source = if (raw == null || raw == JsonNull) null else (raw as JsonPrimitive).content.removePrefix("#").toInt(16)
            require(source == before.colorRgb)
            val color = after.colorRgb?.let { require(it in 0..0xffffff); JsonPrimitive(String.format(Locale.ROOT, "#%06x", it)) } ?: JsonNull
            result = JsonObject(result + ("Color" to color))
        }
        return result
    }
}
