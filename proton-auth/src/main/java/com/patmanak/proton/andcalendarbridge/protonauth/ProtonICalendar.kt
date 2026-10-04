// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Proton Calendar ICalUtilsImpl merge/date handling; see PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import biweekly.Biweekly
import biweekly.ICalendar
import biweekly.ICalVersion
import biweekly.component.VEvent
import biweekly.io.ParseWarning
import biweekly.io.WriteContext
import biweekly.io.scribe.property.RecurrenceRuleScribe
import biweekly.property.*
import com.patmanak.proton.andcalendarbridge.sync.EventContent
import com.patmanak.proton.andcalendarbridge.sync.EventRecurrence
import com.patmanak.proton.andcalendarbridge.sync.EventTime
import com.patmanak.proton.andcalendarbridge.sync.RecurrencePoint
import com.patmanak.proton.andcalendarbridge.sync.RecurringLocalTimeRisk
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonObject

internal object ProtonICalendar {
    fun parse(parts: List<String>, row: JsonObject? = null, bootstrap: JsonObject? = null, expectedUid: String? = null): EventContent {
        if (parts.isEmpty() || parts.any { it.length > 1_048_576 }) unsupportedEvents()
        try {
            val calendars = parts.map { text ->
                validateRawRules(text)
                val warnings = mutableListOf<List<ParseWarning>>()
                val parsed = Biweekly.parse(text).warnings(warnings).all()
                // 37 documents successful resolution of an Olson TZID without VTIMEZONE.
                if (warnings.flatten().any { it.code != 37 }) unsupportedEvents()
                if (parsed.size != 1 || parsed.single().events.size != 1) unsupportedEvents()
                val part = parsed.single().events.single()
                if (part.getProperties(RecurrenceRule::class.java).size > 1 ||
                    part.getProperties(RecurrenceId::class.java).size > 1 || part.getProperties(Uid::class.java).size > 1 ||
                    part.getProperties(Organizer::class.java).size > 1) unsupportedEvents()
                parsed.single()
            }
            val calendar = calendars.first()
            val event = calendar.events.single()
            val uid = event.uid?.value ?: unsupportedEvents()
            if (expectedUid != null && calendars.any { it.events.single().uid?.value != expectedUid }) unsupportedEvents()
            for (other in calendars.drop(1)) {
                val addition = other.events.single()
                if (addition.uid?.value != null && addition.uid.value != uid) unsupportedEvents()
                addition.properties.forEach { entry -> entry.value.forEach { property ->
                    if ((property is RecurrenceRule && event.recurrenceRule != null) ||
                        (property is RecurrenceId && event.recurrenceId != null) ||
                        (property is Organizer && event.organizer != null && property != event.organizer)) unsupportedEvents()
                    if (property is Attendee || property is RawProperty || property is ExceptionDates || property is RecurrenceDates) event.addProperty(property)
                    else event.setProperty(property)
                    calendar.timezoneInfo.setTimezone(property, other.timezoneInfo.getTimezone(property))
                    calendar.timezoneInfo.setFloating(property, other.timezoneInfo.isFloating(property))
                } }
            }
            if (event.getProperties(ExceptionRule::class.java).isNotEmpty()) unsupportedEvents()
            if (event.getProperties(RawProperty::class.java).any {
                it.name.uppercase() in setOf("RRULE", "RDATE", "EXDATE", "EXRULE", "RECURRENCE-ID", "DTSTART", "DTEND")
            }) unsupportedEvents()
            val start = event.dateStart ?: unsupportedEvents()
            val end = event.dateEnd ?: unsupportedEvents()
            if (start.value.hasTime() != end.value.hasTime()) unsupportedEvents()
            val time = if (!start.value.hasTime()) {
                val from = start.value.rawComponents?.let { LocalDate.of(it.year, it.month, it.date) } ?: unsupportedEvents()
                var until = end.value.rawComponents?.let { LocalDate.of(it.year, it.month, it.date) } ?: unsupportedEvents()
                // Proton normalizes legacy equal start/end all-day values to one exclusive day.
                if (until == from) until = from.plusDays(1)
                EventTime.AllDay(from, until)
            } else {
                // Biweekly consumes TZID while parsing and stores its assignment on TimezoneInfo.
                // Validate both final properties: one old UTC part must not hide a floating override.
                if (calendar.timezoneInfo.isFloating(start) || calendar.timezoneInfo.isFloating(end)) unsupportedEvents()
                if (start.parameters.timezoneId != null || end.parameters.timezoneId != null) unsupportedEvents()
                val zone = calendar.timezoneInfo.getTimezone(start)?.timeZone?.id?.let(ZoneId::of) ?: ZoneOffset.UTC
                calendar.timezoneInfo.getTimezone(end)?.timeZone?.id?.let(ZoneId::of)
                EventTime.Timed(start.value.toInstant(), end.value.toInstant(), zone)
            }
            val recurrence = recurrence(calendar, event, uid, time is EventTime.AllDay)
            // Native RRULE expansion can shift fold slots or drift after a gap.
            // Retain the sealed source as unsupported instead of shifting DTSTART/RECURRENCE-ID.
            if (time is EventTime.Timed && RecurringLocalTimeRisk.detected(time.start, time.zone, recurrence?.rule)) unsupportedEvents()
            return EventContent(event.summary?.value ?: "", time, event.description?.value ?: "", event.location?.value ?: "",
                recurrence = recurrence,
                details = row?.let { ProtonEventDetails.parse(event, it, bootstrap, time is EventTime.AllDay) })
        } catch (failure: com.patmanak.proton.andcalendarbridge.sync.RemoteImportException) { throw failure }
          catch (_: Exception) { unsupportedEvents() }
    }

    private fun recurrence(calendar: ICalendar, event: VEvent, uid: String, allDay: Boolean): EventRecurrence? {
        val cancelled = event.status?.value.equals("CANCELLED", ignoreCase = true)
        val original = event.recurrenceId?.let {
            if (it.range != null) unsupportedEvents()
            point(calendar, it, it.value)
        }
        if (cancelled && original == null) unsupportedEvents()
        val dates = event.recurrenceDates.flatMap { property ->
            if (property.periods.isNotEmpty() || property.dates.isEmpty()) unsupportedEvents()
            property.dates.map { point(calendar, property, it) }
        }
        val excluded = event.exceptionDates.flatMap { property ->
            if (property.values.isEmpty()) unsupportedEvents()
            property.values.map { point(calendar, property, it) }
        }
        if (dates.size > 512 || excluded.size > 512) unsupportedEvents()
        // RECURRENCE-ID describes the master's original kind, not a moved child's DTSTART kind.
        if ((dates + excluded).any { (it is RecurrencePoint.AllDay) != allDay }) unsupportedEvents()
        val rule = event.recurrenceRule?.let { canonicalRule(calendar, it, allDay) }
        if (rule == null && original == null && dates.isEmpty() && excluded.isEmpty()) return null
        return EventRecurrence(uid, rule, dates.distinct(), excluded.distinct(), original, cancelled)
    }

    private fun point(calendar: ICalendar, property: ICalProperty, value: biweekly.util.ICalDate): RecurrencePoint {
        if (!value.hasTime()) {
            if (property.parameters.timezoneId != null || calendar.timezoneInfo.getTimezone(property) != null) unsupportedEvents()
            val raw = value.rawComponents ?: unsupportedEvents()
            return RecurrencePoint.AllDay(LocalDate.of(raw.year, raw.month, raw.date))
        }
        if (calendar.timezoneInfo.isFloating(property) || property.parameters.timezoneId != null) unsupportedEvents()
        calendar.timezoneInfo.getTimezone(property)?.timeZone?.id?.let(ZoneId::of)
        return RecurrencePoint.Timed(value.toInstant())
    }

    private fun canonicalRule(calendar: ICalendar, property: RecurrenceRule, allDay: Boolean): String {
        val rule = property.value ?: unsupportedEvents()
        val frequency = rule.frequency?.name ?: unsupportedEvents()
        if (frequency !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY") || rule.xRules.isNotEmpty()) unsupportedEvents()
        // Provider expansion has incomplete support for these valid RFC selectors.
        // Retain their sealed records as unsupported rather than silently changing occurrences.
        if (rule.bySetPos.isNotEmpty() || rule.byWeekNo.isNotEmpty() || rule.byYearDay.isNotEmpty()) unsupportedEvents()
        // Without BYMONTH, YEARLY day selectors range over the year, while the
        // native processor's month-based DaySet cannot represent that reliably.
        if (frequency == "YEARLY" && rule.byMonth.isEmpty() &&
            (rule.byDay.isNotEmpty() || rule.byMonthDay.isNotEmpty())) unsupportedEvents()
        if (rule.count?.let { it !in 1..1_000_000 } == true || rule.interval?.let { it !in 1..1_000_000 } == true) unsupportedEvents()
        if (rule.count != null && rule.until != null) unsupportedEvents()
        if (rule.until?.let { it.hasTime() == allDay } == true) unsupportedEvents()
        fun bounded(values: List<Int>, range: IntRange, nonzero: Boolean = false) {
            if (values.size > 366 || values.any { it !in range || nonzero && it == 0 }) unsupportedEvents()
        }
        bounded(rule.bySecond, 0..59); bounded(rule.byMinute, 0..59); bounded(rule.byHour, 0..23)
        bounded(rule.byMonthDay, -31..31, true); bounded(rule.byMonth, 1..12)
        if (rule.byDay.size > 366 || rule.byDay.any { it.day == null || it.num?.let { number -> number == 0 || number !in -53..53 } == true }) unsupportedEvents()
        if (allDay && (rule.byHour.isNotEmpty() || rule.byMinute.isNotEmpty() || rule.bySecond.isNotEmpty())) unsupportedEvents()
        if (frequency == "WEEKLY" && rule.byMonthDay.isNotEmpty()) unsupportedEvents()
        if (rule.byDay.any { it.num != null } && frequency !in setOf("MONTHLY", "YEARLY")) unsupportedEvents()
        // The library scribe emits RFC text. Recurrence.toString() is not a wire serialization.
        return RecurrenceRuleScribe().writeText(property, WriteContext(ICalVersion.V2_0, calendar.timezoneInfo, null))
            .also { if (it.length > 4096) unsupportedEvents() }
    }

    private fun validateRawRules(text: String) {
        val known = setOf("FREQ", "UNTIL", "COUNT", "INTERVAL", "BYSECOND", "BYMINUTE", "BYHOUR", "BYDAY",
            "BYMONTHDAY", "BYYEARDAY", "BYWEEKNO", "BYMONTH", "BYSETPOS", "WKST")
        val unfolded = text.replace(Regex("\\r?\\n[ \\t]"), "")
        unfolded.lineSequence().filter { it.substringBefore(':').substringBefore(';').equals("RRULE", true) }.forEach { line ->
            if (!line.startsWith("RRULE:", true)) unsupportedEvents()
            val body = line.substringAfter(':').trimEnd('\r')
            if (body.length > 4096) unsupportedEvents()
            val fields = body.split(';').map { field ->
                val name = field.substringBefore('=').uppercase()
                val value = field.substringAfter('=', "")
                if (name !in known || value.isBlank() || value.any { it.isWhitespace() }) unsupportedEvents()
                if (name == "UNTIL" && value.contains('T', true) && !value.endsWith('Z', true)) unsupportedEvents()
                name
            }
            if (fields.toSet().size != fields.size || "FREQ" !in fields) unsupportedEvents()
        }
    }
}
