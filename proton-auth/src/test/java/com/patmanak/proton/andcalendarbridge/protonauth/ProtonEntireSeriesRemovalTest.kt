// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProtonEntireSeriesRemovalTest {
    @Test fun sealedBatchBindsScopeAttemptMasterAndEveryOrderedRevision() = runTest {
        val f = Fixture()
        val sealed = requireNotNull(f.prepare())
        val codec = ProtonEntireSeriesRemovalCodec(f.seed.protector)
        val batch = codec.open(sealed)
        assertEquals(listOf(f.child.eventId to f.child.revision, f.master.eventId to f.master.revision), batch.members)
        assertEquals("""{"MemberID":"${f.seed.context.string("member")}","Events":[{"ID":"child","DeletionReason":0},{"ID":"master","DeletionReason":0}]}""",
            batch.request.toString())
        val key = sealed.key
        for (other in listOf(key.copy(id = MutationId("other")), key.copy(scope = key.scope.copy(generation = 9)),
            key.copy(masterId = EventId("child")), key.copy(revision = Revision("other")), key.copy(uid = "other"))) {
            assertTrue(runCatching { codec.open(sealed.copy(key = other)) }.isFailure)
        }
        val plain = f.seed.protector.open(sealed.protectedPayload)
        val mismatched = plain.replace("\"DeletionReason\":0", "\"DeletionReason\":1")
        assertTrue(runCatching { codec.open(sealed.copy(protectedPayload = f.seed.protector.seal(mismatched))) }.isFailure)
    }

    @Test fun changedRevisionBootstrapOrWriteContextCannotPrepareABatch() = runTest {
        for (mode in listOf("revision", "bootstrap", "permissions", "logout", "growth")) {
            val f = Fixture()
            f.afterVerified = {
                when (mode) {
                    "revision" -> f.rows["child"] = JsonObject(f.rows.getValue("child") + ("ModifyTime" to JsonPrimitive(2)))
                    "bootstrap" -> f.bootstrap = buildJsonObject { put("changed", true) }
                    "permissions" -> f.context = JsonObject(f.context + ("permissions" to JsonPrimitive(0)))
                    "logout" -> f.seed.current = null
                    "growth" -> f.listed += "unexpected"
                }
            }
            assertNull(mode, f.prepare())
        }
    }

    @Test fun completeMultiplePagesFreezeAllDetachedRevisionsBeforeTheMaster() = runTest {
        val f = Fixture(100)
        f.listed.reverse()
        val batch = ProtonEntireSeriesRemovalCodec(f.seed.protector).open(requireNotNull(f.prepare()))
        assertEquals(f.children.values.map { it.eventId to it.revision } + (f.master.eventId to f.master.revision), batch.members)
        assertEquals(listOf(0, 1, 0, 1), f.pages)
    }

    @Test fun mixedResponsesAreMappedByIndexAndAmbiguousIndexesCannotDonateSuccess() = runTest {
        val f = Fixture()
        val batch = ProtonEntireSeriesRemovalCodec(f.seed.protector).open(requireNotNull(f.prepare()))
        val success = """{"Index":0,"Response":{"Code":1000}}"""
        val failure = """{"Index":1,"Response":{"Code":2501,"Error":"missing"}}"""
        fun parse(entries: String) = batch.reportedOutcomes("""{"Code":1001,"Responses":[$entries]}""")
        assertEquals(mapOf(f.child.eventId to ProtonDeleteResponse.ReportedSuccess, f.master.eventId to
            ProtonDeleteResponse.ReportedFailure(2501)), parse("$failure,$success"))
        for (entries in listOf("", success, "$success,$success", "$success,${failure.replace("1,", "2,")}",
            "$success,${failure.replace("\"Index\":1", "\"Index\":1,\"Index\":0")}")) assertNull(parse(entries))
    }

    @Test fun lostResponseRequiresEmptyUidAndEveryExactAbsenceUnderCurrentRights() = runTest {
        for (mode in listOf("lost", "child-present", "unknown", "foreign-id", "foreign-calendar", "growth", "revoked", "logout")) {
            val f = Fixture()
            val sealed = requireNotNull(f.prepare())
            f.listed.clear()
            f.read = { id ->
                if (id == "child") when (mode) {
                    "growth" -> f.listed += "unexpected"
                    "revoked" -> f.context = JsonObject(f.context + ("permissions" to JsonPrimitive(0)))
                    "logout" -> f.seed.current = null
                }
                when {
                    id == "child" && mode == "child-present" -> ProtonDeleteEventRead.Present(f.rows.getValue(id))
                    id == "master" && mode == "unknown" -> ProtonDeleteEventRead.Unknown
                    mode == "foreign-id" -> ProtonDeleteEventRead.Absent(f.seed.scope.calendar.value, "other")
                    mode == "foreign-calendar" -> ProtonDeleteEventRead.Absent("other", id)
                    else -> ProtonDeleteEventRead.Absent(f.seed.scope.calendar.value, id)
                }
            }
            assertEquals(mode, mode == "lost", f.adapter.inspectEntireSeriesAbsence(sealed) { it == f.seed.scope })
            if (mode == "lost") assertEquals(listOf("child", "master"), f.exactReads)
        }
    }

    @Test fun interruptedInspectionPropagatesCancellationAndCanInspectAgainWithoutSending() = runTest {
        val f = Fixture()
        val sealed = requireNotNull(f.prepare())
        f.listed.clear()
        f.read = { throw CancellationException("fixture") }
        try { f.adapter.inspectEntireSeriesAbsence(sealed) { true }; fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        f.read = { ProtonDeleteEventRead.Absent(f.seed.scope.calendar.value, it) }
        assertTrue(f.adapter.inspectEntireSeriesAbsence(sealed) { true })
    }

    private class Fixture(numberOfChildren: Int = 1) {
        val seed = DeleteSubmissionFixture()
        var context = seed.context
        var bootstrap = seed.bootstrap
        val uid = "series@fixture.invalid"
        val childIds = (1..numberOfChildren).map { if (it == 1) "child" else "child-$it" }
        val listed = (listOf("master") + childIds).toMutableList()
        val pages = mutableListOf<Int>()
        val rows = listed.associateWith { id -> buildJsonObject {
            put("ID", id); put("CalendarID", seed.scope.calendar.value); put("UID", uid); put("ModifyTime", 1)
        } }.toMutableMap()
        val start = LocalDate.of(2030, 3, 30)
        val master = RemoteChange.Upsert(EventId("master"), rows.getValue("master").revision(bootstrap), EventContent("Series",
            EventTime.AllDay(start, start.plusDays(1)), recurrence = EventRecurrence(uid, "FREQ=DAILY;COUNT=${numberOfChildren + 1}"), details = EventDetails()))
        val children = childIds.mapIndexed { index, id ->
            id to RemoteChange.Upsert(EventId(id), rows.getValue(id).revision(bootstrap),
                master.content.copy(recurrence = EventRecurrence(uid, original = RecurrencePoint.AllDay(start.plusDays(index + 1L)))))
        }.toMap()
        val child get() = children.getValue("child")
        val exactReads = mutableListOf<String>()
        var read: (String) -> ProtonDeleteEventRead = { error("Unexpected absence inspection") }
        var afterVerified: () -> Unit = {}
        val sessions = CatalogSessionSource { seed.current }
        val imports = object : EventImportRemote {
            override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage = error("No window lookup")
            override suspend fun bootstrap(session: CatalogSession, calendar: String) = this@Fixture.bootstrap
        }
        val verifier = CreateEventVerifier { _, _, _, _, _, _ -> error("No simple event") }
        val family = ProtonCreatedEventReadback(sessions, object : CreateReadbackRemote {
            override suspend fun byUid(session: CatalogSession, calendar: String, uid: String, page: Int): List<JsonObject> {
                pages += page
                return listed.drop(page * 100).take(100).map { id ->
                    buildJsonObject { put("ID", id); put("UID", uid); put("CalendarID", calendar) }
                }
            }
            override suspend fun event(session: CatalogSession, calendar: String, id: String) = rows.getValue(id)
        }, imports, verifier, removalVerifier = SeriesFamilyEventVerifier { _, _, _, row, _ ->
            if (row.string("ID") == "master") master else children.getValue(row.string("ID")).also { afterVerified() }
        })
        val adapter = ProtonDeleteSubmissionAdapter(seed.protector, sessions, { _, _, _ -> context }, object : DeleteSubmissionRemote {
            override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject, allowsTransport: () -> Boolean): String =
                error("Series preparation and recovery must never submit")
            override suspend fun event(session: CatalogSession, calendar: String, id: String): ProtonDeleteEventRead {
                exactReads += id
                return read(id)
            }
        }, imports, verifier, family)
        suspend fun prepare() = adapter.prepareEntireSeriesRemoval(MutationId("fixture-request"), seed.scope, master) { it == seed.scope }
    }
}
