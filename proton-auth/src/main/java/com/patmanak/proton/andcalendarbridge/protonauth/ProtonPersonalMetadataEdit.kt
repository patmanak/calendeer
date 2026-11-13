// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import biweekly.component.VEvent
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Duration
import java.util.Locale
import kotlinx.serialization.json.*

/** Personal API siblings only. Unchanged historical v2 values retain their exact representation. */
internal object ProtonPersonalMetadataEdit {
    fun apply(original: JsonObject, before: EventContent, after: EventContent, defaults: JsonObject? = null): JsonObject {
        require(ProtonPersonalMetadata.valid(original["Notifications"], original["Color"]))
        var result = original
        if (before.details?.reminders != after.details?.reminders) {
            val previous = requireNotNull(before.details)
            val next = requireNotNull(after.details)
            require(previous.organizer == null && previous.attendees.isEmpty() && next.organizer == null && next.attendees.isEmpty())
            val notifications = if (original["Notifications"] == JsonNull) {
                requireNotNull(defaults)
                validateDefaults(original, before, defaults)
                defaults.array("Notifications")
            } else original.array("Notifications")
            val explicit = JsonObject(original + ("Notifications" to notifications))
            require(ProtonEventDetails.parse(VEvent(), explicit, null, before.time is EventTime.AllDay).reminders == previous.reminders)
            val emails = notifications.filter { (it as JsonObject).number("Type") == 0L }
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

    /** Only previously unsupported null profiles need this retained offline reconstruction input.
     * The selected initial-kind defaults remain separate from the untouched original row.
     */
    fun captureDefaults(original: JsonObject, bootstrap: JsonObject, before: EventContent, after: EventContent): JsonObject? {
        if (original["Notifications"] != JsonNull) return null
        if (before.details?.reminders == after.details?.reminders) {
            if ((before.time is EventTime.AllDay) != (after.time is EventTime.AllDay)) {
                // Null must remain inherited. A kind transition cannot silently change its
                // display reminders, nor materialize a list contrary to unchanged local intent.
                val settings = bootstrap.obj("CalendarSettings")
                val destination = settings.array(if (after.time is EventTime.AllDay) "DefaultFullDayNotifications" else "DefaultPartDayNotifications")
                if (settings.string("CalendarID") != original.string("CalendarID") ||
                    original.number("FullDay") != (if (before.time is EventTime.AllDay) 1L else 0L) ||
                    !ProtonPersonalMetadata.valid(destination, JsonNull) ||
                    ProtonEventDetails.parse(VEvent(), original, bootstrap, after.time is EventTime.AllDay).reminders != after.details?.reminders)
                    unsupportedEvents()
            }
            return null
        }
        val settings = bootstrap.obj("CalendarSettings")
        require(settings.string("CalendarID") == original.string("CalendarID"))
        val allDay = before.time is EventTime.AllDay
        val captured = buildJsonObject {
            put("CalendarID", settings.string("CalendarID")); put("FullDay", if (allDay) 1 else 0)
            put("Notifications", settings.array(if (allDay) "DefaultFullDayNotifications" else "DefaultPartDayNotifications"))
        }
        validateDefaults(original, before, captured)
        return Json.parseToJsonElement(captured.toString()) as JsonObject
    }

    fun validateDefaults(original: JsonObject, before: EventContent, defaults: JsonObject) {
        require(original["Notifications"] == JsonNull)
        require(defaults.keys == setOf("CalendarID", "FullDay", "Notifications"))
        require(defaults.string("CalendarID") == original.string("CalendarID"))
        val kind = if (before.time is EventTime.AllDay) 1L else 0L
        require(defaults.number("FullDay") == kind && original.number("FullDay") == kind)
        val notifications = defaults.array("Notifications")
        require(ProtonPersonalMetadata.valid(notifications, JsonNull))
        require(ProtonEventDetails.parse(VEvent(), JsonObject(original + ("Notifications" to notifications)), null, kind == 1L).reminders == requireNotNull(before.details).reminders)
    }
}
