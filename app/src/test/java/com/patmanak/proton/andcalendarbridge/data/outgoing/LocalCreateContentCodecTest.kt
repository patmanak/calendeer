// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.data.outgoing

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LocalCreateContentCodecTest {
    @Test fun seriesSnapshotMustUseANewVersionAndCannotBecomeASimpleSnapshot() {
        val value = LocalCreateIntent(reservation, empty, CreateSeriesRule(3))
        val encoded = LocalCreateContentCodec.encodeContent(value)
        assertEquals(3, JSONObject(encoded).getInt("schema"))
        assertEquals("FREQ=DAILY;COUNT=3", JSONObject(encoded).getString("seriesRule"))
        assertEquals(value, LocalCreateContentCodec.decodeIntent(encoded, reservation))
        assertThrows(IllegalArgumentException::class.java) { LocalCreateContentCodec.decodeContent(encoded, reservation) }
    }
    @Test fun seriesSnapshotRetainsPersonalMetadataAndRejectsDowngradesUnknownFieldsAndUnboundedRules() {
        val value = LocalCreateIntent(reservation, empty.copy(colorRgb = 0, details = EventDetails(listOf(EventReminder(10)))),
            CreateSeriesRule(5, 2))
        val encoded = LocalCreateContentCodec.encodeContent(value)
        assertEquals(value, LocalCreateContentCodec.decodeIntent(encoded, reservation))
        for (invalid in listOf(JSONObject(encoded).put("schema", 2), JSONObject(encoded).put("schema", 4),
            JSONObject(encoded).put("seriesRule", "FREQ=DAILY"), JSONObject(encoded).put("seriesRule", "COUNT=5;FREQ=DAILY;INTERVAL=2"),
            JSONObject(encoded).put("uid", "invented-uid"), JSONObject(encoded).put("seriesRule", JSONObject.NULL),
            JSONObject(encoded).apply { remove("seriesRule") })) {
            assertThrows(IllegalArgumentException::class.java) { LocalCreateContentCodec.decodeIntent(invalid.toString(), reservation) }
        }
    }
    private val reservation = LocalCreateReservation(MutationId("fixture-capture"),
        LocalEventId("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"),
        CalendarScope(AccountId("fixture-account"), CalendarId("fixture-calendar"), 0),
        AdapterFormat("fixture", 1), Instant.parse("2030-10-01T00:00:00Z"))
    private val empty = EventContent("Fixture", EventTime.AllDay(LocalDate.parse("2030-10-01"),
        LocalDate.parse("2030-10-02")), details = EventDetails())
    private val legacy = """{"schema":1,"reservation":{"id":"fixture-capture","localId":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","account":"fixture-account","calendar":"fixture-calendar","generation":0,"adapter":"fixture","version":1,"timestamp":"2030-10-01T00:00:00Z","markerProtocol":1},"knownEmptyDetails":true,"title":"Fixture","description":"","location":"","allDay":true,"start":"2030-10-01","end":"2030-10-02"}"""

    @Test fun historicalKnownEmptyEncodingAndStrictValidationRemainFrozen() {
        assertEquals(empty, LocalCreateContentCodec.decodeContent(legacy, reservation))
        assertEquals(JSONObject(legacy).toString(), LocalCreateContentCodec.encodeContent(LocalCreateIntent(reservation, empty)))
        for (invalid in listOf(JSONObject(legacy).put("knownEmptyDetails", false),
            JSONObject(legacy).put("colorRgb", 0), JSONObject(legacy).put("reminders", org.json.JSONArray()))) {
            assertThrows(IllegalArgumentException::class.java) { LocalCreateContentCodec.decodeContent(invalid.toString(), reservation) }
        }
    }

    @Test fun richEncodingRoundTripsEveryPersonalFieldWithItsOwnSchema() {
        for (content in listOf(empty.copy(colorRgb = 0), empty.copy(details = EventDetails(listOf(EventReminder(10)))),
            empty.copy(colorRgb = 0xffffff, details = EventDetails(listOf(EventReminder(0), EventReminder(-10), EventReminder(525600)))))) {
            val encoded = LocalCreateContentCodec.encodeContent(LocalCreateIntent(reservation, content))
            assertEquals(2, JSONObject(encoded).getInt("schema"))
            assertEquals(content, LocalCreateContentCodec.decodeContent(encoded, reservation))
            assertFalse(JSONObject(encoded).has("knownEmptyDetails"))
        }
    }

    @Test fun richSchemaRejectsUnknownFieldsForeignIdentityAndEmptyProfile() {
        val rich = LocalCreateContentCodec.encodeContent(LocalCreateIntent(reservation, empty.copy(colorRgb = 1)))
        for (invalid in listOf(JSONObject(rich).put("attendees", org.json.JSONArray()),
            JSONObject(rich).put("schema", 1).put("knownEmptyDetails", true),
            JSONObject(rich).put("colorRgb", JSONObject.NULL),
            JSONObject(rich).put("colorRgb", "1"), JSONObject(rich).put("colorRgb", 1.5),
            JSONObject(rich).put("reminders", org.json.JSONArray(listOf(10, 10))),
            JSONObject(rich).put("reminders", org.json.JSONArray(listOf("10"))),
            JSONObject(rich).put("reminders", org.json.JSONArray(listOf(-1))),
            JSONObject(rich).put("reminders", org.json.JSONArray(listOf(0, 10, 20, 30, 40, 50))))) {
            assertThrows(IllegalArgumentException::class.java) { LocalCreateContentCodec.decodeContent(invalid.toString(), reservation) }
        }
        assertThrows(IllegalStateException::class.java) {
            LocalCreateContentCodec.decodeContent(rich, reservation.copy(id = MutationId("foreign")))
        }
    }
}
