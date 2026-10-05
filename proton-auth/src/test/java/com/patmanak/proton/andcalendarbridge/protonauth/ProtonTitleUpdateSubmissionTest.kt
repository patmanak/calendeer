// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.proton.core.domain.entity.UserId
import me.proton.core.network.domain.session.SessionId
import org.junit.Assert.*
import org.junit.Test

/** Fabricated signatures qualify control flow and exact bindings, never native crypto or server acceptance. */
class ProtonTitleUpdateSubmissionTest {
    @Test fun observedServerEditClockCanAdvanceExactlyWithModifyTime() {
        val f = TitleSubmissionFixture()
        val original = JsonObject(f.row + ("LastEditTime" to JsonPrimitive(2)))
        val fragments = ProtonTimestampedSharedTitle(original, f.fragments.clearSharedPart, f.fragments.encryptedSharedPart, true)
        val actual = JsonObject(f.readRow + ("LastEditTime" to JsonPrimitive(5)))
        assertTrue(ProtonTitleUpdateProfile.matchesReadback(original, fragments, actual))
        assertTrue(ProtonTitleUpdateProfile.matchesReadback(original, fragments, JsonObject(actual + ("LastEditTime" to JsonPrimitive(2)))))
    }
    @Test fun serverEditClockDoesNotExcuseInvalidClocksPresenceChangesOrOtherMetadataChanges() {
        val f = TitleSubmissionFixture()
        val original = JsonObject(f.row + ("LastEditTime" to JsonPrimitive(2)))
        val fragments = ProtonTimestampedSharedTitle(original, f.fragments.clearSharedPart, f.fragments.encryptedSharedPart, true)
        val actual = JsonObject(f.readRow + ("LastEditTime" to JsonPrimitive(5)))
        val invalid = listOf(
            JsonObject(actual + ("LastEditTime" to JsonPrimitive(1))),
            JsonObject(actual + ("LastEditTime" to JsonPrimitive(4))),
            JsonObject(actual + ("LastEditTime" to JsonPrimitive(-1))),
            JsonObject(actual + ("LastEditTime" to JsonPrimitive("5"))),
            JsonObject(actual + ("LastEditTime" to JsonPrimitive(5.5))),
            JsonObject(actual + ("LastEditTime" to JsonNull)),
            JsonObject(actual - "LastEditTime"),
            JsonObject(actual + ("Permissions" to JsonPrimitive(3))),
            JsonObject(actual + ("SharedKeyPacket" to JsonPrimitive("different"))),
            JsonObject(actual + mapOf("LastEditTime" to JsonPrimitive(2), "ModifyTime" to JsonPrimitive(1)))
        )
        invalid.forEach { assertFalse(ProtonTitleUpdateProfile.matchesReadback(original, fragments, it)) }
        assertFalse(ProtonTitleUpdateProfile.matchesReadback(f.row, f.fragments, actual))
        assertTrue(ProtonTitleUpdateProfile.matchesReadback(f.row, f.fragments, f.readRow))
    }
    @Test fun upstreamReadPermissionsSeeAndInviteStillUseOfficialSeeOnlyWriteInput() {
        val f = TitleSubmissionFixture()
        val row = JsonObject(f.row + ("Permissions" to JsonPrimitive(3)))
        val fragments = ProtonTimestampedSharedTitle(row, f.fragments.clearSharedPart, f.fragments.encryptedSharedPart, true)
        ProtonTitleUpdateProfile.qualify(row)
        val record = f.codec.seal(MutationId("synthetic-see-invite"), f.preparationFor(fragments), f.context)
        val request = f.codec.open(record).request
        assertEquals(1L, (request.array("Events").single() as JsonObject).obj("Event").number("Permissions"))
        val actual = JsonObject(f.readRow + ("Permissions" to JsonPrimitive(3)))
        assertTrue(ProtonTitleUpdateProfile.matchesReadback(row, fragments, actual))
        assertFalse(ProtonTitleUpdateProfile.matchesReadback(row, fragments, f.readRow))
        listOf(0, 2, 4, 7, 8).forEach { unsupported ->
            refused { ProtonTitleUpdateProfile.qualify(JsonObject(row + ("Permissions" to JsonPrimitive(unsupported)))) }
        }
    }
    @Test fun observedGetExtensionsAreNarrowAndRemainExactReadbackContext() {
        val f = TitleSubmissionFixture()
        val extensions = mapOf("LastEditTime" to JsonPrimitive(2), "NotificationOccurrences" to JsonArray(emptyList()),
            "AttendeesInfo" to buildJsonObject { put("Code", 1000); put("Attendees", JsonArray(emptyList())); put("MoreAttendees", 0) })
        val original = JsonObject(f.row + extensions)
        val fragments = ProtonTimestampedSharedTitle(original, f.fragments.clearSharedPart, f.fragments.encryptedSharedPart, true)
        val actual = JsonObject(f.readRow + extensions)
        ProtonTitleUpdateProfile.qualify(original)
        assertTrue(ProtonTitleUpdateProfile.matchesReadback(original, fragments, actual))
        assertFalse(ProtonTitleUpdateProfile.matchesReadback(original, fragments, JsonObject(actual + ("LastEditTime" to JsonPrimitive(3)))))
        assertFalse(ProtonTitleUpdateProfile.matchesReadback(original, fragments, JsonObject(actual - "NotificationOccurrences")))
        listOf("LastEditTime" to JsonPrimitive(-1), "LastEditTime" to JsonPrimitive("2"),
            "NotificationOccurrences" to JsonNull, "NotificationOccurrences" to JsonArray(listOf(JsonPrimitive(1))),
            "AttendeesInfo" to JsonObject(original.obj("AttendeesInfo") + ("Code" to JsonPrimitive(1001))))
            .forEach { (name, value) -> refused { ProtonTitleUpdateProfile.qualify(JsonObject(original + (name to value))) } }
    }
    @Test fun unknownCallerCannotDowngradeToPreparedJournalAndDispatch() = runTest {
        val f = TitleSubmissionFixture()
        assertEquals(TitleUpdateSubmissionObservation.Unknown, f.adapter.submit(f.record.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN), f.journal))
        assertEquals(0, f.prepares); assertEquals(0, f.sends); assertFalse("claim" in f.steps)
    }
    @Test fun preparedCallerWithInventedEvidenceCannotDispatch() = runTest {
        val f = TitleSubmissionFixture()
        assertEquals(TitleUpdateSubmissionObservation.Unknown, f.adapter.submit(f.record.copy(protectedEvidence = "invented"), f.journal))
        assertEquals(0, f.prepares); assertEquals(0, f.sends); assertFalse("claim" in f.steps)
    }
    @Test fun nullAttendeesWithoutAnExplicitEmptyRepresentationIsNotQualified() {
        val f = TitleSubmissionFixture()
        refused { ProtonTitleUpdateProfile.qualify(JsonObject(f.row + ("Attendees" to JsonNull))) }
    }
    @Test fun codecBuildsOnlyOfficialBoundedUpdateShapeAndReopensExactPreparation() {
        val f = TitleSubmissionFixture()
        val opened = f.codec.open(f.record)
        val operation = opened.request.array("Events").single() as JsonObject
        assertEquals(setOf("ID", "Event"), operation.keys)
        assertEquals(f.intent.base.eventId.value, operation.string("ID"))
        assertFalse(operation.obj("Event").containsKey("SharedKeyPacket"))
        assertTrue(operation.obj("Event").array("SharedEventContent").all { (it as JsonObject).keys == setOf("Type", "Data", "Signature") })
        assertEquals(f.fragments.originalRow, opened.fragments.originalRow)
        assertEquals(f.context, opened.context)
        val bytes = titleUpdateOnlyBytes(opened.request.toString()); assertTrue(bytes.isNotEmpty()); bytes.fill(0)
    }
    @Test fun codecRejectsAttemptPreparationFormatAndPayloadRebinding() {
        val f = TitleSubmissionFixture()
        for (record in listOf(f.record.copy(id = MutationId("other")), f.record.copy(format = TITLE_UPDATE_SUBMISSION_FORMAT.copy(version = 2)),
            f.record.copy(protectedPayload = "missing"), f.record.copy(preparation = f.preparation.copy(intent = f.intent.copy(id = MutationId("other")))))) {
            refused { f.codec.open(record) }
        }
    }
    @Test fun unknownJsonSemanticsInvitationAndNonemptyPartitionsAreNotDropped() {
        val f = TitleSubmissionFixture()
        val variations = listOf("Unknown" to JsonPrimitive(1), "Permissions" to JsonPrimitive(7), "IsOrganizer" to JsonPrimitive(0),
            "Notifications" to JsonArray(listOf(JsonPrimitive(1))), "CalendarEvents" to JsonArray(listOf(JsonPrimitive(1))),
            "AttendeesEvents" to JsonArray(listOf(JsonPrimitive(1))), "Color" to JsonPrimitive("#ffffff"),
            "VideoConferencingData" to buildJsonObject {}, "RRule" to JsonPrimitive("FREQ=DAILY"),
            "IsPersonalSingleEdit" to JsonPrimitive(true), "AttendeesInfo" to buildJsonObject { put("MoreAttendees", 1); put("Attendees", JsonArray(emptyList())) })
        variations.forEach { (key, value) -> refused { ProtonTitleUpdateProfile.qualify(JsonObject(f.row + (key to value))) } }
    }
    @Test fun oneDurableClaimPrecedesOnePutAndFirstEvidencePrecedesReadback() = runTest {
        val f = TitleSubmissionFixture()
        assertTrue(f.adapter.submit(f.record, f.journal) is TitleUpdateSubmissionObservation.Verified)
        assertEquals(1, f.sends)
        assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, f.journal.stored.state)
        assertNotNull(f.journal.stored.protectedEvidence)
        assertTrue(f.steps.indexOf("claim") < f.steps.indexOf("send"))
        assertTrue(f.steps.indexOf("evidence") < f.steps.indexOf("get"))
        assertTrue(f.adapter.submit(f.record, f.journal) is TitleUpdateSubmissionObservation.Verified)
        assertEquals(1, f.sends); assertEquals(1, f.prepares)
    }
    @Test fun freshBaseContextOrSessionDivergencePreventsClaim() = runTest {
        for (variant in 0..2) {
            val f = TitleSubmissionFixture()
            when (variant) {
                0 -> f.fresh = f.preparationFor(ProtonTimestampedSharedTitle(JsonObject(f.row + ("ModifyTime" to JsonPrimitive(9))), f.fragments.clearSharedPart, f.fragments.encryptedSharedPart, true))
                1 -> f.freshContext = JsonObject(f.context + ("member" to JsonPrimitive("other")))
                2 -> f.after = { if (it == "prepare") f.current = f.initial.copy(sessionId = SessionId("replacement")) }
            }
            assertEquals(TitleUpdateSubmissionObservation.Unknown, f.adapter.submit(f.record, f.journal))
            assertEquals(TitleUpdateAttemptState.PREPARED, f.journal.stored.state)
            assertEquals(0, f.sends); assertFalse("claim" in f.steps)
        }
    }
    @Test fun failedClaimCommitPreventsTransport() = runTest {
        val f = TitleSubmissionFixture(); f.journal.failClaim = true
        assertEquals(TitleUpdateSubmissionObservation.Unknown, f.adapter.submit(f.record, f.journal))
        assertEquals(TitleUpdateAttemptState.PREPARED, f.journal.stored.state); assertEquals(0, f.sends)
    }
    @Test fun responseLossCancellationAndLostEvidenceRemainInspectOnly() = runTest {
        for (variant in 0..2) {
            val f = TitleSubmissionFixture()
            when (variant) {
                0 -> f.sendFailure = IOException("synthetic lost response")
                1 -> f.sendFailure = CancellationException("synthetic cancellation")
                2 -> f.journal.failEvidence = true
            }
            try { f.adapter.submit(f.record, f.journal) } catch (_: CancellationException) { }
            assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, f.journal.stored.state)
            assertTrue(f.adapter.submit(f.record, f.journal) is TitleUpdateSubmissionObservation.Verified)
            assertEquals(1, f.sends); assertEquals(1, f.prepares)
        }
    }
    @Test fun sameTitleWithDifferentSignatureUntouchedContextOrEventNeverVerifies() = runTest {
        for (variant in 0..3) {
            val f = TitleSubmissionFixture(); f.journal.stored = f.record.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
            when (variant) {
                0 -> f.readRow = JsonObject(f.readRow + ("SharedEvents" to JsonArray(listOf(f.fragments.clearSharedPart,
                    JsonObject(f.fragments.encryptedSharedPart + ("Signature" to JsonPrimitive("different")))))))
                1 -> f.readRow = JsonObject(f.readRow + ("SharedKeyPacket" to JsonPrimitive("different")))
                2 -> f.readRow = JsonObject(f.readRow + ("ID" to JsonPrimitive("other")))
                3 -> f.readRow = JsonObject(f.readRow + ("AddressID" to JsonPrimitive("other")))
            }
            assertEquals(TitleUpdateSubmissionObservation.Unknown, f.adapter.inspect(f.journal.stored))
            assertFalse("verify" in f.steps); assertEquals(0, f.sends)
        }
    }
    @Test fun unknownWithoutEvidenceStillRequiresAuthenticatedCryptoAndExactFullContent() = runTest {
        val f = TitleSubmissionFixture(); val record = f.record.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN)
        assertTrue(f.adapter.inspect(record) is TitleUpdateSubmissionObservation.Verified)
        f.verified = f.intent.base.copy(revision = Revision("new"), content = f.intent.content.copy(description = "different"))
        assertEquals(TitleUpdateSubmissionObservation.Unknown, f.adapter.inspect(record))
        f.current = null
        assertEquals(TitleUpdateSubmissionObservation.Unknown, f.adapter.inspect(record))
        assertEquals(0, f.sends)
    }
    @Test fun preparedInspectionNeverReadsOrAdmits() = runTest {
        val f = TitleSubmissionFixture()
        assertEquals(TitleUpdateSubmissionObservation.Unknown, f.adapter.inspect(f.record))
        assertTrue(f.steps.isEmpty()); assertEquals(0, f.sends)
    }
    private fun refused(action: () -> Unit) { try { action(); fail("Unsupported shape was accepted") } catch (_: Exception) { } }
}

internal class TitleSubmissionFixture {
    val scope = CalendarScope(AccountId("synthetic-account"), CalendarId("synthetic-calendar"), 1)
    val content = EventContent("Original", EventTime.AllDay(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-02")), details = EventDetails())
    val intent = LocalEditIntent(MutationId("synthetic-edit"), scope, EVENT_FORMAT,
        RemoteChange.Upsert(EventId("synthetic-event"), Revision("synthetic-base"), content), content.copy(title = "Changed"))
    val timestamp = Instant.parse("2026-10-05T12:00:00Z")
    private fun part(type: Int, data: String) = buildJsonObject { put("Type", type); put("Data", data); put("Signature", "signature-$data"); put("Author", "synthetic@example.invalid") }
    val row = buildJsonObject {
        put("ID", intent.base.eventId.value); put("CalendarID", scope.calendar.value); put("UID", "synthetic-uid")
        put("SharedKeyPacket", "synthetic-key-packet"); put("Permissions", 1); put("IsOrganizer", 1); put("IsPersonalSingleEdit", false)
        put("CreateTime", 1); put("ModifyTime", 2); put("StartTime", 3); put("EndTime", 4); put("FullDay", 1)
        put("StartTimezone", "UTC"); put("EndTimezone", "UTC")
        listOf("CalendarEvents", "AttendeesEvents", "Notifications", "Attendees", "Exdates").forEach { put(it, JsonArray(emptyList())) }
        put("SharedEvents", JsonArray(listOf(part(2, "old-clear"), part(3, "old-secret"))))
    }
    val fragments = ProtonTimestampedSharedTitle(row, part(2, "new-clear"), part(3, "new-secret"), true)
    val protector = object : EventPayloadProtector {
        private val values = mutableMapOf<String, String>()
        override fun seal(plain: String): String = "sealed-${values.size}".also { values[it] = plain }
        override fun open(protected: String) = requireNotNull(values[protected])
    }
    fun preparationFor(value: ProtonTimestampedSharedTitle) = ProtonTitlePreparationCodec(protector).sealLocal(intent, "synthetic-uid", timestamp, value)
    val preparation = preparationFor(fragments)
    val bootstrap = buildJsonObject { put("synthetic", true) }
    val context = buildJsonObject {
        ProtonTitleUpdateSubmissionCodec.CONTEXT_KEYS.forEach { put(it, "synthetic-$it") }
        put("author", "synthetic@example.invalid"); put("bootstrap", ProtonTitleUpdateSubmissionCodec.hash(bootstrap.toString()))
    }
    val codec = ProtonTitleUpdateSubmissionCodec(protector)
    val record = codec.seal(MutationId("synthetic-attempt"), preparation, context)
    val steps = mutableListOf<String>()
    var after: (String) -> Unit = {}
    fun step(name: String) { steps += name; after(name) }
    val journal = TitleSubmissionJournal(record, ::step)
    val initial = CatalogSession(UserId(scope.account.value), SessionId("synthetic-session"))
    var current: CatalogSession? = initial
    var fresh = preparation
    var freshContext = context
    var sends = 0
    var prepares = 0
    var sendFailure: Exception? = null
    var readRow = JsonObject(row + mapOf("SharedEvents" to JsonArray(listOf(fragments.clearSharedPart, fragments.encryptedSharedPart)), "ModifyTime" to JsonPrimitive(5)))
    var verified: RemoteChange.Upsert? = intent.base.copy(revision = Revision("synthetic-updated"), content = intent.content)
    val adapter = ProtonTitleUpdateSubmissionAdapter(CalendarLocalEditPreparationPort { value, at ->
        assertEquals(intent, value); assertEquals(timestamp, at); prepares++; step("prepare"); fresh
    }, protector, { current }, { _, _ -> step("context"); freshContext }, object : TitleUpdateSubmissionRemote {
        override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject): String {
            assertEquals(TitleUpdateAttemptState.OUTCOME_UNKNOWN, journal.stored.state)
            sends++; step("send"); sendFailure?.let { throw it }; return "{\"Code\":1001}"
        }
        override suspend fun event(session: CatalogSession, calendar: String, id: String): JsonObject {
            assertEquals(intent.base.eventId.value, id); step("get"); return readRow
        }
    }, object : EventImportRemote {
        override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage = error("No paginated search")
        override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject { step("bootstrap"); return this@TitleSubmissionFixture.bootstrap }
    }, CreateEventVerifier { _, _, _, _, uid, policy ->
        assertEquals("synthetic-uid", uid); assertEquals(CreatedEventReadbackPolicy.KNOWN_EMPTY_V2, policy); step("verify"); verified
    })
}

internal class TitleSubmissionJournal(var stored: TitleUpdateAttemptRecord, private val step: (String) -> Unit) : TitleUpdateAttemptJournal {
    var failClaim = false
    var failEvidence = false
    override suspend fun remember(record: TitleUpdateAttemptRecord) { require(record == stored) }
    override suspend fun read(scope: CalendarScope, id: MutationId) = stored.takeIf { it.scope == scope && it.id == id }
    override suspend fun claim(expected: TitleUpdateAttemptRecord): Boolean {
        step("claim"); if (failClaim) throw IOException("synthetic commit failure")
        if (stored != expected || stored.state != TitleUpdateAttemptState.PREPARED) return false
        stored = stored.copy(state = TitleUpdateAttemptState.OUTCOME_UNKNOWN); return true
    }
    override suspend fun retainEvidence(expectedUnknown: TitleUpdateAttemptRecord, protectedEvidence: String) {
        step("evidence"); if (failEvidence) throw IOException("synthetic evidence failure")
        require(stored == expectedUnknown); stored = stored.copy(protectedEvidence = protectedEvidence)
    }
}
