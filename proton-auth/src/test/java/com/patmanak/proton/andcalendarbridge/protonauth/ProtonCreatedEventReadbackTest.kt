// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.IOException
import java.time.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test

/** Controlled read ports qualify policy/order, not native signatures or service responses. */
class ProtonCreatedEventReadbackTest {
    @Test fun exactEmptyRawOverrideAndKnownEmptyContentQualifyTimedAndAllDayReads() = runTest {
        for (time in listOf(EventTime.AllDay(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02")),
            EventTime.Timed(Instant.parse("2026-10-01T10:00:00Z"), Instant.parse("2026-10-01T11:00:00Z"), ZoneId.of("Europe/Paris")))) {
            val f = Fixture(time)
            assertEquals(f.actual, f.inspect())
            assertEquals(listOf("uid:0", "event", "bootstrap", "verify:2"), f.steps)
        }
    }

    @Test fun absentNullWrongTypeAndAnyNonEmptyOverrideAreRefusedBeforeKeysOrVerification() = runTest {
        val overrides = listOf(null, JsonNull, JsonPrimitive("[]"), JsonPrimitive(false), buildJsonObject {},
            JsonArray(listOf(JsonNull)), notification(0), notification(1))
        for (override in overrides) {
            val f = Fixture()
            f.individual = if (override == null) JsonObject(f.row - "Notifications") else JsonObject(f.row + ("Notifications" to override))
            assertNull("Raw override must be explicit and empty", f.inspect())
            assertEquals(listOf("uid:0", "event"), f.steps)
        }
    }

    @Test fun unknownOrNonEmptyExpectedDetailsCannotRequestStrictVerification() = runTest {
        for (details in listOf(null, EventDetails(listOf(EventReminder(15))))) {
            val f = Fixture()
            f.expected = f.expected.copy(details = details)
            assertNull(f.inspect())
            assertTrue(f.steps.isEmpty())
        }
        val f = Fixture()
        for (unsupported in listOf(f.expected.copy(colorRgb = 0x123456),
            f.expected.copy(recurrence = EventRecurrence("synthetic-series@calendeer.invalid", rule = "FREQ=DAILY")))) {
            f.expected = unsupported
            assertNull(f.inspect()); assertTrue(f.steps.isEmpty())
        }
    }

    @Test fun emptyRawOverrideCannotHideUnexpectedDecodedDetails() = runTest {
        for (details in listOf(null, EventDetails(listOf(EventReminder(15))),
            EventDetails(organizer = EventPerson("synthetic@calendeer.invalid", "Synthetic organizer")))) {
            val f = Fixture()
            f.actual = f.actual!!.copy(content = f.expected.copy(details = details))
            assertNull(f.inspect())
            assertTrue(f.steps.contains("verify:2"))
        }
    }

    @Test fun blankUidForeignScopeOrSubsecondExpectationNeverReads() = runTest {
        val f = Fixture()
        val strict = CreatedEventReadbackPolicy.KNOWN_EMPTY_V2
        assertNull(f.readback.inspect(f.session, f.scope, " ", f.expected, strict))
        assertNull(f.readback.inspect(f.session, f.scope.copy(account = AccountId("foreign-user")), f.uid, f.expected, strict))
        val start = Instant.parse("2026-10-01T10:00:00.123Z")
        f.expected = f.expected.copy(time = EventTime.Timed(start, start.plusSeconds(60), ZoneOffset.UTC))
        assertNull(f.inspect()); assertTrue(f.steps.isEmpty())
    }

    @Test fun sharedOnlyLegacyReadStillIgnoresPersonalDetailsAndDoesNotRequireRawNotifications() = runTest {
        val f = Fixture()
        f.expected = f.expected.copy(details = null)
        f.individual = JsonObject(f.row - "Notifications")
        f.actual = f.actual!!.copy(content = f.expected.copy(details = EventDetails(listOf(EventReminder(15)))))
        assertEquals(f.actual, f.inspect(CreatedEventReadbackPolicy.SHARED_ONLY_V1))
        assertEquals(listOf("uid:0", "event", "bootstrap", "verify:1"), f.steps)
    }

    @Test fun titleDescriptionLocationDatesRecurrenceColorAndIdentityMustMatch() = runTest {
        val f0 = Fixture()
        val originals = listOf(f0.expected.copy(title = "Changed"), f0.expected.copy(description = "Changed"),
            f0.expected.copy(location = "Changed"), f0.expected.copy(colorRgb = 0x123456),
            f0.expected.copy(recurrence = EventRecurrence("synthetic-series@calendeer.invalid", rule = "FREQ=DAILY")),
            f0.expected.copy(time = EventTime.AllDay(LocalDate.parse("2026-10-02"), LocalDate.parse("2026-10-03"))))
        for (content in originals) {
            val f = Fixture(); f.actual = f.actual!!.copy(content = content)
            assertNull(f.inspect())
        }
        val f = Fixture(); f.actual = f.actual!!.copy(eventId = EventId("foreign-event"))
        assertNull(f.inspect())
    }

    @Test fun newlineAndUtcAliasesAreEquivalentWithoutDiscardingKnownEmptyDetails() = runTest {
        val f = Fixture(EventTime.Timed(Instant.parse("2026-10-01T10:00:00Z"), Instant.parse("2026-10-01T11:00:00Z"), ZoneId.of("UTC")))
        f.expected = f.expected.copy(title = "First\r\nSecond", description = "One\r\nTwo", location = "Room\r\nA")
        f.actual = f.actual!!.copy(content = f.expected.copy(title = "First\nSecond", description = "One\nTwo", location = "Room\nA",
            time = (f.expected.time as EventTime.Timed).copy(zone = ZoneOffset.UTC)))
        assertEquals(f.actual, f.inspect())
    }

    @Test fun emptyDuplicateForeignAmbiguousAndOversizedUidResultsNeverReadTheEvent() = runTest {
        val sample = Fixture()
        val rows = listOf(emptyList(), listOf(sample.row, sample.row),
            listOf(sample.row, JsonObject(sample.row + ("ID" to JsonPrimitive("second-event")))),
            listOf(JsonObject(sample.row + ("ID" to JsonPrimitive("")))),
            listOf(JsonObject(sample.row + ("CalendarID" to JsonPrimitive("foreign")))),
            listOf(JsonObject(sample.row + ("UID" to JsonPrimitive("foreign")))), List(101) { sample.row })
        for (values in rows) {
            val f = Fixture(); f.rows = values
            assertNull(f.inspect()); assertEquals(listOf("uid:0"), f.steps)
        }
        // A full UID page already proves ambiguity; do not poll more pages or read a row.
        val f = Fixture(); f.rows = List(100) { JsonObject(f.row + ("ID" to JsonPrimitive("event-$it"))) }
        assertNull(f.inspect()); assertEquals(listOf("uid:0"), f.steps)
    }

    @Test fun rawEmptyUidSearchRowCannotSubstituteTheIndividualRead() = runTest {
        for (field in listOf("ID", "CalendarID", "UID")) {
            val f = Fixture(); f.individual = JsonObject(f.row + (field to JsonPrimitive("foreign")))
            assertNull(f.inspect()); assertEquals(listOf("uid:0", "event"), f.steps)
        }
        val f = Fixture()
        assertNull(f.inspect(candidate = "foreign-candidate")); assertEquals(listOf("uid:0"), f.steps)
    }

    @Test fun missingForeignAndChangedSessionsCannotVerify() = runTest {
        for (replacement in listOf(null, CatalogSession(UserId("foreign-user"), SessionId("foreign-session")))) {
            val f = Fixture(); f.current = replacement
            assertNull(f.inspect()); assertTrue(f.steps.isEmpty())
        }
        for (stage in listOf("uid:0", "event", "bootstrap", "verify:2")) {
            val f = Fixture(); f.after = { if (it == stage) f.current = f.session.copy(sessionId = SessionId("replacement")) }
            assertNull(f.inspect()); assertEquals(stage, f.steps.last())
        }
    }

    @Test fun verificationOrReadFailureRemainsUnresolvedAndCancellationPropagates() = runTest {
        val unverifiable = Fixture(); unverifiable.actual = null
        assertNull(unverifiable.inspect())
        for (stage in listOf("uid:0", "event", "bootstrap", "verify:2")) {
            val f = Fixture(); f.after = { if (it == stage) throw IOException("synthetic read failure") }
            assertNull(f.inspect())
            val cancelled = Fixture(); cancelled.after = { if (it == stage) throw CancellationException("synthetic cancellation") }
            try { cancelled.inspect(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        }
    }

    private fun notification(type: Int) = JsonArray(listOf(buildJsonObject { put("Type", type); put("Trigger", "-PT15M") }))

    private class Fixture(time: EventTime = EventTime.AllDay(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02"))) {
        val scope = CalendarScope(AccountId("synthetic-user"), CalendarId("synthetic-calendar"), 1)
        val session = CatalogSession(UserId(scope.account.value), SessionId("synthetic-session"))
        val uid = "synthetic-readback@calendeer.invalid"
        var current: CatalogSession? = session
        var expected = EventContent("Synthetic readback", time, "Synthetic description", "Synthetic location", details = EventDetails())
        val row = buildJsonObject { put("ID", "synthetic-event"); put("CalendarID", scope.calendar.value); put("UID", uid)
            put("Notifications", JsonArray(emptyList())) }
        var rows = listOf(row)
        var individual = row
        var actual: RemoteChange.Upsert? = RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-revision"), expected)
        val steps = mutableListOf<String>()
        var after: (String) -> Unit = {}
        private fun step(value: String) { steps += value; after(value) }
        val readback = ProtonCreatedEventReadback(CatalogSessionSource { current }, object : CreateReadbackRemote {
            override suspend fun byUid(session: CatalogSession, calendar: String, uid: String, page: Int): List<JsonObject> {
                assertEquals(this@Fixture.session, session); assertEquals(scope.calendar.value, calendar); assertEquals(this@Fixture.uid, uid)
                step("uid:$page"); return rows
            }
            override suspend fun event(session: CatalogSession, calendar: String, id: String): JsonObject {
                assertEquals(this@Fixture.session, session); assertEquals(scope.calendar.value, calendar); assertEquals("synthetic-event", id)
                step("event"); return individual
            }
        }, object : EventImportRemote {
            override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage = error("No window fetch")
            override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject { step("bootstrap"); return buildJsonObject {} }
        }, CreateEventVerifier { _, _, _, _, expectedUid, policy -> assertEquals(uid, expectedUid); step("verify:${policy.version}"); actual })

        suspend fun inspect(policy: CreatedEventReadbackPolicy = CreatedEventReadbackPolicy.KNOWN_EMPTY_V2, candidate: String? = null) =
            readback.inspect(session, scope, uid, expected, policy, candidate)
    }
}
