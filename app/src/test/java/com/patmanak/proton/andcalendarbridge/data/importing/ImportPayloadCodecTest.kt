package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/** Fixed historical payloads, independent of Android storage and outgoing receipt codecs. */
class ImportPayloadCodecTest {
    @Test fun legacyTimedCacheRetainsMissingOptionalFieldsAndRawText() {
        val legacy = """{"id":"fixture-event","revision":"opaque-v1","deleted":false,"title":"Événement","description":"line\r\nnext","location":"Room","allDay":false,"start":"2030-07-01T10:00:00Z","end":"2030-07-01T11:00:00Z","zone":"Asia/Kathmandu"}"""
        val change = ImportPayloadCodec.decodeChange(legacy) as RemoteChange.Upsert
        assertEquals("line\r\nnext", change.content.description)
        assertEquals(ZoneId.of("Asia/Kathmandu"), (change.content.time as EventTime.Timed).zone)
        assertNull(change.content.details); assertNull(change.content.recurrence); assertNull(change.content.colorRgb)
        assertEquals(JSONObject(legacy).toString(), ImportPayloadCodec.encodeChange(change))
    }

    @Test fun richAllDayCachePreservesRecurrenceAndParticipantRepresentation() {
        // Preserve the historical writer's insertion order as well as values. The host JSON
        // implementation retains insertion order within hash-collision chains.
        val legacy = """{"id":"fixture-series","revision":"opaque-v2","deleted":false,"title":"Days","description":"","location":"","colorRgb":1122867,"recurrence":{"schema":1,"uid":"fixture-uid","rule":"FREQ=DAILY;COUNT=3","additional":[{"type":"date","value":"2030-07-05"}],"excluded":[{"type":"date","value":"2030-07-02"}],"cancelled":false},"details":{"schema":1,"reminders":[15,30],"organizer":{"email":"organizer@example.test","name":"Organizer"},"attendees":[{"person":{"email":"guest@example.test","name":"Guest"},"role":"OPTIONAL","status":"TENTATIVE"}]},"allDay":true,"start":"2030-07-01","end":"2030-07-03"}"""
        val change = ImportPayloadCodec.decodeChange(legacy) as RemoteChange.Upsert
        assertTrue(change.content.time is EventTime.AllDay)
        assertEquals(2, change.content.details!!.reminders.size)
        assertEquals(AttendanceRole.OPTIONAL, change.content.details!!.attendees.single().role)
        assertEquals(JSONObject(legacy).toString(), ImportPayloadCodec.encodeChange(change))
        assertEquals(change, ImportPayloadCodec.decodeChange(ImportPayloadCodec.encodeChange(change)))
    }

    @Test fun tombstonesAndCheckpointKeysKeepHistoricalEncoding() {
        val tombstone = """{"id":"fixture-deleted","revision":"opaque-delete","deleted":true}"""
        assertEquals(JSONObject(tombstone).toString(), ImportPayloadCodec.encodeChange(ImportPayloadCodec.decodeChange(tombstone)))
        val scope = CalendarScope(AccountId("fixture:account"), CalendarId("calendar/é"), 7)
        assertEquals("""["fixture:account","calendar/é",7]""", ImportPayloadCodec.key(scope))
        for (checkpoint in listOf(
            """{"adapter":"fixture-adapter","version":2,"cursor":null,"complete":true}""",
            """{"adapter":"fixture-adapter","version":2,"cursor":"opaque/é:1","complete":false}""",
        )) {
            val decoded = ImportPayloadCodec.decodeCheckpoint(scope, checkpoint)
            assertEquals(scope, decoded.scope)
            assertEquals(JSONObject(checkpoint).toString(), ImportPayloadCodec.encodeCheckpoint(decoded))
        }
    }

    @Test fun unsupportedHistoricalDetailsSchemaFailsClosed() {
        val legacy = """{"id":"fixture","revision":"opaque","deleted":false,"title":"","description":"","location":"","allDay":true,"start":"2030-07-01","end":"2030-07-02","details":{"schema":99,"reminders":[],"attendees":[]}}"""
        assertThrows(IllegalArgumentException::class.java) { ImportPayloadCodec.decodeChange(legacy) }
    }
}
