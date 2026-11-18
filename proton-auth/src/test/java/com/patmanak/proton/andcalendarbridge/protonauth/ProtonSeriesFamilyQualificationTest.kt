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

/** Complete UID reads and admission only; signature verification is a controlled port here. */
class ProtonSeriesFamilyQualificationTest {
    @Test fun strictPreparationContractKeepsIncompleteProofRetryableAndPropagatesReadFailures() = runTest {
        suspend fun failure(expected: LocalEditPreparationFailure, configure: (Fixture) -> Unit) {
            val f = Fixture().also(configure)
            try { f.readback.requireSeries(f.session, f.scope, f.base) { }; fail("Expected closed qualification") }
            catch (actual: LocalEditPreparationException) { assertEquals(expected, actual.reason) }
        }
        failure(LocalEditPreparationFailure.RETRY) { it.rows = emptyList() }
        failure(LocalEditPreparationFailure.RETRY) { it.actual = null }
        failure(LocalEditPreparationFailure.UNSUPPORTED) { it.rows = listOf(it.row, JsonObject(it.row + ("ID" to JsonPrimitive("child")))) }
        failure(LocalEditPreparationFailure.CONFLICT) { it.actual = it.base.copy(revision = Revision("newer")) }
        failure(LocalEditPreparationFailure.UNSUPPORTED) { it.individual = JsonObject(it.row + ("RecurrenceID" to JsonPrimitive(5))) }
        failure(LocalEditPreparationFailure.CONTEXT) { it.individual = JsonObject(it.row + ("RecurrenceID" to buildJsonObject { })) }
        failure(LocalEditPreparationFailure.CONTEXT) { it.individual = JsonObject(it.row + ("Exdates" to JsonPrimitive(5))) }
        val f = Fixture().also { it.after = { throw IOException("Synthetic offline") } }
        try { f.readback.requireSeries(f.session, f.scope, f.base) { }; fail("Read failure must survive") }
        catch (_: IOException) { }
    }

    @Test fun uniqueMasterNeedsAnIndependentSignedReadWithExactBaseAndRevision() = runTest {
        for (allDay in listOf(false, true)) {
            val f = Fixture(allDay)
            assertEquals(f.base, f.qualify())
            assertEquals(listOf("uid:0", "event", "bootstrap", "verify"), f.steps)
        }
    }
    @Test fun missingAmbiguousRepeatedForeignOversizedOrFullUidResultsCannotCertifyAbsence() = runTest {
        val sample = Fixture()
        val child = JsonObject(sample.row + ("ID" to JsonPrimitive("detached")) + ("Status" to JsonPrimitive("CANCELLED")))
        for (rows in listOf(emptyList(), listOf(sample.row, child), listOf(sample.row, sample.row),
            listOf(JsonObject(sample.row + ("UID" to JsonPrimitive("foreign")))),
            listOf(JsonObject(sample.row + ("CalendarID" to JsonPrimitive("foreign")))),
            List(101) { sample.row }, List(100) { JsonObject(sample.row + ("ID" to JsonPrimitive("event-$it"))) })) {
            val f = Fixture(); f.rows = rows
            assertNull(f.qualify()); assertEquals(listOf("uid:0"), f.steps)
        }
    }
    @Test fun lookupDoesNotSubstituteTheIndividualReadOrAcceptDetachedMetadata() = runTest {
        val sample = Fixture()
        val variants = listOf("ID", "CalendarID", "UID").map { JsonObject(sample.row + (it to JsonPrimitive("foreign"))) } +
            listOf(JsonObject(sample.row + ("RecurrenceID" to JsonPrimitive(123))),
                JsonObject(sample.row + ("Exdates" to JsonArray(listOf(JsonPrimitive(123))))))
        for (row in variants) {
            val f = Fixture(); f.individual = row
            assertNull(f.qualify()); assertFalse(f.steps.contains("verify"))
        }
    }
    @Test fun aChangedOrUnverifiableSignedMasterNeverQualifiesTheFrozenBase() = runTest {
        val sample = Fixture()
        for (actual in listOf(null, sample.base.copy(revision = Revision("newer")),
            sample.base.copy(eventId = EventId("foreign")), sample.base.copy(content = sample.base.content.copy(title = "Changed")),
            sample.base.copy(content = sample.base.content.copy(recurrence = sample.base.content.recurrence!!.copy(rule = "FREQ=DAILY;COUNT=8"))))) {
            val f = Fixture(); f.actual = actual
            assertNull(f.qualify()); assertEquals("verify", f.steps.last())
        }
    }
    @Test fun unsupportedBasesNeverReachTheUidEndpoint() = runTest {
        val f = Fixture()
        for (recurrence in listOf(null, EventRecurrence(f.uid, "FREQ=WEEKLY;COUNT=5"),
            EventRecurrence(f.uid, "FREQ=DAILY;COUNT=5", excludedDates = listOf(RecurrencePoint.Timed(Instant.parse("2030-04-02T10:00:00Z")))))) {
            f.base = f.base.copy(content = f.base.content.copy(recurrence = recurrence))
            assertNull(f.qualify()); assertTrue(f.steps.isEmpty())
        }
    }
    @Test fun mutableParticipantAndExceptionListsCannotChangeTheBaseWhileItIsBeingQualified() = runTest {
        for (stage in listOf("uid:0", "verify")) for (participants in listOf(false, true)) {
            val f = Fixture()
            val attendees = mutableListOf<EventAttendee>()
            val exclusions = mutableListOf<RecurrencePoint>()
            f.base = f.base.copy(content = f.base.content.copy(details = EventDetails(attendees = attendees),
                recurrence = f.base.content.recurrence!!.copy(excludedDates = exclusions)))
            f.actual = f.base
            f.after = { if (it == stage) {
                if (participants) attendees += EventAttendee(EventPerson("fixture@example.invalid"), AttendanceRole.REQUIRED, AttendanceStatus.ACCEPTED)
                else exclusions += RecurrencePoint.Timed(Instant.parse("2030-04-02T10:00:00Z"))
            } }
            assertNull(f.qualify())
        }
    }
    @Test fun currentAccessIsCheckedAfterEverySuspendingReadAndFailuresNeverBecomeProof() = runTest {
        for (stage in listOf("uid:0", "event", "bootstrap", "verify")) {
            val f = Fixture(); f.after = { if (it == stage) f.admitted = false }
            assertNull(f.qualify()); assertEquals(stage, f.steps.last())
            val changed = Fixture(); changed.after = { if (it == stage) changed.current = null }
            assertNull(changed.qualify()); assertEquals(stage, changed.steps.last())
            val failed = Fixture(); failed.after = { if (it == stage) throw IOException("Synthetic read failure") }
            assertNull(failed.qualify())
            val cancelled = Fixture(); cancelled.after = { if (it == stage) throw CancellationException("Synthetic cancellation") }
            try { cancelled.qualify(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        }
    }
    @Test fun deletionDiscoveryChecksTheCompleteFamilyAgainAfterVerification() = runTest {
        val f = Fixture()
        val expected = requireNotNull(f.removal())
        assertEquals(listOf(f.base.eventId to f.base.revision), expected.deletions)
        assertEquals(2, f.steps.count { it == "uid:0" })
        for (mode in listOf("growth", "empty", "revoked", "changed", "missing")) {
            val stale = Fixture()
            stale.after = { if (it == "verify") when (mode) {
                "growth" -> stale.rows = stale.rows + JsonObject(stale.row + ("ID" to JsonPrimitive("new-child")))
                "empty" -> stale.rows = emptyList()
                "revoked" -> stale.admitted = false
                "changed" -> stale.actual = stale.base.copy(revision = Revision("changed"))
                "missing" -> stale.actual = null
            } }
            assertNull(mode, stale.removal())
        }
    }

    private class Fixture(allDay: Boolean = false) {
        val scope = CalendarScope(AccountId("family-fixture"), CalendarId("calendar"), 0)
        val session = CatalogSession(UserId(scope.account.value), SessionId("synthetic-session"))
        val uid = "family@calendeer.invalid"
        var base = RemoteChange.Upsert(EventId("master"), Revision("revision"), EventContent("Series fixture",
            if (allDay) EventTime.AllDay(LocalDate.parse("2030-04-01"), LocalDate.parse("2030-04-03"))
            else EventTime.Timed(Instant.parse("2030-04-01T10:00:00Z"), Instant.parse("2030-04-01T11:00:00Z"), ZoneId.of("UTC")),
            recurrence = EventRecurrence(uid, "FREQ=DAILY;COUNT=5"), details = EventDetails()))
        val row = buildJsonObject { put("ID", "master"); put("CalendarID", scope.calendar.value); put("UID", uid) }
        var rows = listOf(row)
        var individual = row
        var actual: RemoteChange.Upsert? = base
        var current: CatalogSession? = session
        var admitted = true
        val steps = mutableListOf<String>()
        var after: (String) -> Unit = {}
        private fun step(value: String) { steps += value; after(value) }
        val readback = ProtonCreatedEventReadback(CatalogSessionSource { current }, object : CreateReadbackRemote {
            override suspend fun byUid(session: CatalogSession, calendar: String, uid: String, page: Int): List<JsonObject> {
                assertEquals(scope.calendar.value, calendar); assertEquals(this@Fixture.uid, uid)
                step("uid:$page"); return rows
            }
            override suspend fun event(session: CatalogSession, calendar: String, id: String): JsonObject {
                assertEquals(scope.calendar.value, calendar); assertEquals(base.eventId.value, id)
                step("event"); return individual
            }
        }, object : EventImportRemote {
            override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage = error("No displayed-window query")
            override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject { step("bootstrap"); return buildJsonObject {} }
        }, CreateEventVerifier { _, _, _, _, _, _ -> error("No historical CREATE policy") },
            SeriesFamilyEventVerifier { _, _, _, _, _ -> step("verify"); actual })
        suspend fun removal() = readback.inspectSeriesRemoval(session, scope, base, SeriesRemovalTarget.Entire) { check(admitted) }
        suspend fun qualify() = readback.qualifySeries(session, scope, base) { check(admitted) }
    }
}
