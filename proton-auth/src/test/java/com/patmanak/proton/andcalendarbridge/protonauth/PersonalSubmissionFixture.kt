// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** Real protected codecs, synthetic signatures and controlled transport: no service/crypto claim. */
internal class PersonalSubmissionFixture(zone: ZoneId = ZoneId.of("UTC")) {
    private val seed = TitleSubmissionFixture()
    val initial = seed.initial
    val bootstrap = seed.bootstrap
    val timestamp = seed.timestamp
    val scope = seed.scope
    private val time = EventTime.Timed(Instant.parse("2026-10-24T12:00:00Z"),
        Instant.parse("2026-10-24T13:00:00Z"), zone)
    private val replacement = time.copy(start = time.start.plusSeconds(7200), end = time.end.plusSeconds(10800))
    val originalRow = JsonObject(seed.row + buildJsonObject {
        put("Color", "#aBcD12")
        put("Notifications", JsonArray(listOf(buildJsonObject { put("Type", 1); put("Trigger", "-PT5M") }, buildJsonObject { put("Type", 0); put("Trigger", "-PT1H") })))
        put("StartTime", time.start.epochSecond); put("EndTime", time.end.epochSecond); put("FullDay", 0)
        val apiZone = if (zone == java.time.ZoneOffset.UTC) "UTC" else zone.id
        put("StartTimezone", apiZone); put("EndTimezone", apiZone)
    })
    val intent = seed.intent.copy(base = seed.intent.base.copy(revision = originalRow.revision(bootstrap),
        content = seed.content.copy(time = time, description = "Old notes", location = "Old place", colorRgb = 0xabcd12, details = EventDetails(listOf(EventReminder(5))))),
        content = seed.content.copy(title = "Combined title", time = replacement, description = "New notes", location = "New place", colorRgb = 0xabcd12, details = EventDetails(listOf(EventReminder(5)))))
    val fragments = ProtonTimestampedSharedPersonal(originalRow, seed.fragments.clearSharedPart, seed.fragments.encryptedSharedPart, true)
    val context = JsonObject(seed.context + buildJsonObject {
        put("permissions", 16); put("flags", 1)
        for (name in listOf("calendarKey", "signingKey", "calendar")) put(name, ProtonSimpleEventUpdate.hash("synthetic-$name"))
        put("bootstrap", ProtonSimpleEventUpdate.hash(bootstrap.toString()))
    })
    val values = linkedMapOf<String, String>()
    val steps = mutableListOf<String>()
    var hook: suspend (String) -> Unit = {}
    var storageHook: (String) -> Unit = {}
    suspend fun step(name: String) { steps += name; hook(name) }
    val protector = object : EventPayloadProtector {
        override fun seal(plain: String): String {
            storageHook("seal")
            return "time-protected-${values.size}".also { values[it] = plain }
        }
        override fun open(protected: String): String { storageHook("open"); return requireNotNull(values[protected]) }
    }
    fun prepare(value: ProtonTimestampedSharedPersonal = fragments): PersonalEditPreparation {
        return ProtonPersonalPreparationCodec(protector).sealLocal(intent, "synthetic-uid", timestamp, value)
    }
    val preparation = prepare()
    var fresh: PersonalEditPreparation? = preparation
    var current: CatalogSession? = initial
    var freshContext = context
    var readRow = JsonObject(originalRow + mapOf("SharedEvents" to JsonArray(listOf(fragments.clearSharedPart,
        fragments.encryptedSharedPart)), "ModifyTime" to JsonPrimitive(5), "StartTime" to JsonPrimitive(replacement.start.epochSecond),
        "EndTime" to JsonPrimitive(replacement.end.epochSecond)))
    var decoded: RemoteChange.Upsert? = intent.base.copy(revision = readRow.revision(bootstrap), content = intent.content.copy(
        title = intent.content.title.replace("\r\n", "\n"), description = intent.content.description.replace("\r\n", "\n")))
    var allowed = true
    var sourceAllowed = true
    var evidenceAllowed = true
    var response = "{\"Code\":1001}"
    var sendFailure: Exception? = null
    var retainedPreparationLease: ((CalendarScope) -> Boolean)? = null
    var retainedCheckpoint: (suspend () -> Unit)? = null
    val journal = Journal(this)
    val adapter = ProtonPersonalUpdateSubmissionAdapter(object : CalendarPersonalPreparationPort {
        override val personalFormat = PERSONAL_PREPARATION_FORMAT
        override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: Instant): PersonalEditPreparation =
            error("No unrestricted time preparation")
        override suspend fun preparePersonal(intent: LocalEditIntent, timestamp: Instant,
            admit: (CalendarScope) -> Boolean): PersonalEditPreparation? {
            assertEquals(this@PersonalSubmissionFixture.intent, intent); assertEquals(this@PersonalSubmissionFixture.timestamp, timestamp)
            retainedPreparationLease = admit
            assertTrue(admit(scope)); step("prepare"); return fresh
        }
    }, protector, { step("session"); current }, { session, actualScope, checkpoint ->
        assertEquals(initial, session); assertEquals(scope, actualScope)
        retainedCheckpoint = checkpoint; checkpoint(); step("context"); checkpoint(); freshContext
    }, object : TitleUpdateSubmissionRemote {
        override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject): String {
            assertEquals(initial, session); assertEquals(scope.calendar.value, calendar)
            assertEquals(PersonalUpdateAttemptState.OUTCOME_UNKNOWN, journal.stored?.state)
            val saved = requireNotNull(journal.stored)
            val opened = ProtonPersonalUpdateRequestCodec(protector).open(ProtonProtectedPersonalUpdateRequest(
                saved.id, saved.preparation, saved.format, saved.protectedPayload))
            assertEquals(opened.request, request)
            step("send"); sendFailure?.let { throw it }; return response
        }
        override suspend fun event(session: CatalogSession, calendar: String, id: String): JsonObject {
            assertEquals(initial, session); assertEquals(scope.calendar.value, calendar)
            assertEquals(intent.base.eventId.value, id); step("get"); return readRow
        }
    }, object : EventImportRemote {
        override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage =
            error("No event search")
        override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject { step("bootstrap"); return this@PersonalSubmissionFixture.bootstrap }
    }, CreateEventVerifier { _, calendar, _, _, uid, policy ->
        assertEquals(scope.calendar.value, calendar); assertEquals("synthetic-uid", uid)
        assertEquals(CreatedEventReadbackPolicy.PERSONAL_EDIT_V2, policy); step("verify"); decoded
    })
    suspend fun record(id: MutationId = MutationId("synthetic-time-request")): PersonalUpdateAttemptRecord {
        val protected = ProtonPersonalUpdateRequestCodec(protector).seal(id, preparation, context)
        return PersonalUpdateAttemptRecord(protected.id, protected.preparation, protected.format, protected.protectedPayload)
    }
    suspend fun seed(): PersonalUpdateAttemptRecord = record().also { journal.stored = it }
    suspend fun submit(record: PersonalUpdateAttemptRecord) = adapter.submit(record, journal,
        { it == scope && allowed }, { assertEquals(scope, it); step("source"); sourceAllowed }, { it == scope && evidenceAllowed })
    suspend fun inspect(record: PersonalUpdateAttemptRecord) = adapter.inspect(record, journal) { it == scope && allowed }
    fun count(name: String) = steps.count { it == name }

    class Journal(private val fixture: PersonalSubmissionFixture) : PersonalUpdateAttemptJournal {
        var stored: PersonalUpdateAttemptRecord? = null
        var claimFailure: Exception? = null
        var evidenceFailure: Exception? = null
        var evidenceLease: ((CalendarScope) -> Boolean)? = null
        var readLease: ((CalendarScope) -> Boolean)? = null
        override suspend fun remember(record: PersonalUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean = error("No adapter remember")
        override suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): PersonalUpdateAttemptRecord? {
            readLease = admit
            if (!admit(scope)) return null
            fixture.step("read")
            return stored?.takeIf { it.scope == scope && it.id == id }
        }
        override suspend fun claim(expected: PersonalUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            if (!admit(expected.scope)) return false
            fixture.step("claim"); claimFailure?.let { throw it }
            if (!admit(expected.scope) || stored != expected || expected.state != PersonalUpdateAttemptState.PREPARED) return false
            stored = expected.copy(state = PersonalUpdateAttemptState.OUTCOME_UNKNOWN)
            fixture.step("claimed"); return true
        }
        override suspend fun retainEvidence(expectedUnknown: PersonalUpdateAttemptRecord, protectedEvidence: String,
            admit: (CalendarScope) -> Boolean): Boolean {
            evidenceLease = admit
            if (!admit(expectedUnknown.scope)) return false
            fixture.step("evidence"); evidenceFailure?.let { throw it }
            val actual = stored ?: return false
            if (!admit(expectedUnknown.scope) || actual.state != PersonalUpdateAttemptState.OUTCOME_UNKNOWN ||
                actual.copy(protectedEvidence = null) != expectedUnknown.copy(protectedEvidence = null) ||
                (actual.protectedEvidence != null && actual.protectedEvidence != protectedEvidence)) return false
            stored = actual.copy(protectedEvidence = protectedEvidence); return true
        }
    }
}
