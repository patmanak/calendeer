package com.patmanak.proton.andcalendarbridge.data.importing

import com.patmanak.proton.andcalendarbridge.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
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

    private val intervalJson = """{"start":"2030-07-01","end":"2030-08-01","zone":"UTC"}"""
    private val finishedRunJson = """{"adapter":"fixture-coverage","version":2,"lastIndex":0,"finished":true,"windows":[{"interval":$intervalJson,"prefixKnown":true,"receivedPages":1,"appliedPages":1,"terminalReceived":true,"terminalApplied":true,"receivedAt":10,"appliedAt":11}]}"""
    private val interval = CoverageInterval(LocalDate.of(2030, 7, 1), LocalDate.of(2030, 8, 1), ZoneId.of("UTC"))

    @Test fun historicalCoverageSchemasPreserveProgressAndOptionalObservation() {
        val legacy = """{"schema":1,"epoch":4,"active":null,"previous":$finishedRunJson}"""
        val decoded = ImportPayloadCodec.decodeCoverage(legacy)
        assertEquals(4L, decoded.epoch)
        assertNull(decoded.active); assertNull(decoded.observed)
        val previous = requireNotNull(decoded.previous)
        assertTrue(previous.finished)
        assertEquals(interval, previous.windows.single().interval)
        val current = JSONObject(legacy).put("schema", 2).put("observed", JSONObject.NULL)
        assertEquals(current.toString(), ImportPayloadCodec.encodeCoverage(decoded))

        current.put("observed", JSONObject("""{"adapter":"fixture-observation","version":3,"interval":$intervalJson}"""))
        val observed = ImportPayloadCodec.decodeCoverage(current.toString())
        assertEquals(CoverageObservation(AdapterFormat("fixture-observation", 3), interval), observed.observed)
        assertEquals(current.toString(), ImportPayloadCodec.encodeCoverage(observed))
    }

    @Test fun historicalPageCoverageRetainsEncodingAndPlanValidation() {
        val legacy = """{"schema":1,"plan":[$intervalJson],"index":0,"starts":true,"finishes":false}"""
        val decoded = ImportPayloadCodec.decodePageCoverage(legacy)
        assertEquals(PageCoverage(listOf(interval), 0, true, false), decoded)
        assertEquals(JSONObject(legacy).toString(), ImportPayloadCodec.encodePageCoverage(decoded))
        assertThrows(IllegalArgumentException::class.java) {
            ImportPayloadCodec.decodePageCoverage(JSONObject(legacy).put("schema", 2).toString())
        }
        for (plan in listOf("[]", "[$intervalJson,$intervalJson]", "[${List(129) { intervalJson }.joinToString(",")}]")) {
            assertThrows(IllegalArgumentException::class.java) {
                ImportPayloadCodec.decodePageCoverage("""{"schema":1,"plan":$plan,"index":0,"starts":true,"finishes":false}""")
            }
        }
    }

    @Test fun coverageBudgetCountsUtf8BytesForBothReadingAndWriting() {
        val maximum = 256 * 1024
        val empty = """{"schema":2,"epoch":0,"active":null,"previous":null,"observed":null}"""
        assertEquals(CoverageLedger(), ImportPayloadCodec.decodeCoverage(empty + " ".repeat(maximum - empty.length)))
        val multibyte = empty + "é".repeat(maximum / 2)
        assertTrue(multibyte.length < maximum)
        for (payload in listOf(empty + " ".repeat(maximum - empty.length + 1), multibyte)) {
            val failure = assertThrows(IllegalArgumentException::class.java) { ImportPayloadCodec.decodeCoverage(payload) }
            assertEquals("Coverage payload exceeds limit", failure.message)
        }
        val oversized = CoverageLedger(observed = CoverageObservation(AdapterFormat("é".repeat(maximum / 2), 1), interval))
        val failure = assertThrows(IllegalArgumentException::class.java) { ImportPayloadCodec.encodeCoverage(oversized) }
        assertEquals("Coverage payload exceeds limit", failure.message)
    }

    @Test fun unknownCoverageVersionsAndIncompleteHistoryAreRejected() {
        for (schema in listOf(0, 3)) {
            assertThrows(IllegalArgumentException::class.java) {
                ImportPayloadCodec.decodeCoverage("""{"schema":$schema,"epoch":0,"active":null,"previous":null}""")
            }
        }
        val unfinished = JSONObject(finishedRunJson).put("finished", false)
        assertThrows(IllegalArgumentException::class.java) {
            ImportPayloadCodec.decodeCoverage("""{"schema":2,"epoch":0,"active":null,"previous":$unfinished}""")
        }
        val negativeTimestamp = JSONObject(finishedRunJson)
        negativeTimestamp.getJSONArray("windows").getJSONObject(0).put("receivedAt", -1)
        assertThrows(IllegalArgumentException::class.java) {
            ImportPayloadCodec.decodeCoverage("""{"schema":2,"epoch":0,"active":$negativeTimestamp,"previous":null}""")
        }
    }
}
