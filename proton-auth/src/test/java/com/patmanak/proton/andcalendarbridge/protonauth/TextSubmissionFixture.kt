// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*

/** Real protected codecs, synthetic signatures and controlled transport: no service/crypto claim. */
internal class TextSubmissionFixture(timed: Boolean = false) {
    private val seed = TitleSubmissionFixture()
    val initial = seed.initial
    val bootstrap = seed.bootstrap
    val timestamp = seed.timestamp
    val scope = seed.scope
    val originalRow = seed.row
    private val time = if (timed) EventTime.Timed(Instant.parse("2026-10-24T12:00:00Z"),
        Instant.parse("2026-10-24T13:00:00Z"), ZoneId.of("Europe/Paris")) else seed.content.time
    val intent = seed.intent.copy(base = seed.intent.base.copy(revision = originalRow.revision(bootstrap),
        content = seed.content.copy(time = time, description = "Old notes", location = "Old place")),
        content = seed.content.copy(time = time, title = "Changed\r\ntitle", description = "Changed\r\nnotes", location = ""))
    val fragments = ProtonTimestampedSharedText(originalRow, seed.fragments.clearSharedPart, seed.fragments.encryptedSharedPart, true)
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
            return "text-protected-${values.size}".also { values[it] = plain }
        }
        override fun open(protected: String): String { storageHook("open"); return requireNotNull(values[protected]) }
    }
    fun prepare(value: ProtonTimestampedSharedText = fragments): LocalTextEditPreparation {
        val binding = ProtonTextPreparationBinding(scope, intent.base.eventId, intent.base.revision,
            "synthetic-uid", intent.base.content.title, linkedMapOf(
                ProtonSharedTextProperty.SUMMARY to ProtonSharedTextChange(intent.base.content.title, intent.content.title),
                ProtonSharedTextProperty.DESCRIPTION to ProtonSharedTextChange(intent.base.content.description, intent.content.description),
                ProtonSharedTextProperty.LOCATION to ProtonSharedTextChange(intent.base.content.location, null)), timestamp)
        return ProtonTextPreparationCodec(protector).sealLocal(intent, binding, value)
    }
    val preparation = prepare()
    var fresh: LocalTextEditPreparation? = preparation
    var current: CatalogSession? = initial
    var freshContext = context
    var readRow = JsonObject(originalRow + mapOf("SharedEvents" to JsonArray(listOf(fragments.clearSharedPart,
        fragments.encryptedSharedPart)), "ModifyTime" to JsonPrimitive(5)))
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
    val adapter = ProtonTextUpdateSubmissionAdapter(object : CalendarLocalTextPreparationPort {
        override val localTextFormat = LOCAL_TEXT_PREPARATION_FORMAT
        override suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: Instant): LocalTextEditPreparation =
            error("No unrestricted text preparation")
        override suspend fun prepareLocalText(intent: LocalEditIntent, timestamp: Instant,
            admit: (CalendarScope) -> Boolean): LocalTextEditPreparation? {
            assertEquals(this@TextSubmissionFixture.intent, intent); assertEquals(this@TextSubmissionFixture.timestamp, timestamp)
            retainedPreparationLease = admit
            assertTrue(admit(scope)); step("prepare"); return fresh
        }
    }, protector, { step("session"); current }, { session, actualScope, checkpoint ->
        assertEquals(initial, session); assertEquals(scope, actualScope)
        retainedCheckpoint = checkpoint; checkpoint(); step("context"); checkpoint(); freshContext
    }, object : TitleUpdateSubmissionRemote {
        override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject): String {
            assertEquals(initial, session); assertEquals(scope.calendar.value, calendar)
            assertEquals(TextUpdateAttemptState.OUTCOME_UNKNOWN, journal.stored?.state)
            val saved = requireNotNull(journal.stored)
            val opened = ProtonTextUpdateRequestCodec(protector).open(ProtonProtectedTextUpdateRequest(
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
        override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject { step("bootstrap"); return this@TextSubmissionFixture.bootstrap }
    }, CreateEventVerifier { _, calendar, _, _, uid, policy ->
        assertEquals(scope.calendar.value, calendar); assertEquals("synthetic-uid", uid)
        assertEquals(CreatedEventReadbackPolicy.KNOWN_EMPTY_V2, policy); step("verify"); decoded
    })
    suspend fun record(): TextUpdateAttemptRecord {
        val protected = ProtonTextUpdateRequestCodec(protector).seal(MutationId("synthetic-text-request"), preparation, context)
        return TextUpdateAttemptRecord(protected.id, protected.preparation, protected.format, protected.protectedPayload)
    }
    suspend fun seed(): TextUpdateAttemptRecord = record().also { journal.stored = it }
    suspend fun submit(record: TextUpdateAttemptRecord) = adapter.submit(record, journal,
        { it == scope && allowed }, { assertEquals(scope, it); step("source"); sourceAllowed }, { it == scope && evidenceAllowed })
    suspend fun inspect(record: TextUpdateAttemptRecord) = adapter.inspect(record, journal) { it == scope && allowed }
    fun count(name: String) = steps.count { it == name }

    class Journal(private val fixture: TextSubmissionFixture) : TextUpdateAttemptJournal {
        var stored: TextUpdateAttemptRecord? = null
        var claimFailure: Exception? = null
        var evidenceFailure: Exception? = null
        var evidenceLease: ((CalendarScope) -> Boolean)? = null
        var readLease: ((CalendarScope) -> Boolean)? = null
        override suspend fun remember(record: TextUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean = error("No adapter remember")
        override suspend fun read(scope: CalendarScope, id: MutationId, admit: (CalendarScope) -> Boolean): TextUpdateAttemptRecord? {
            readLease = admit
            if (!admit(scope)) return null
            fixture.step("read")
            return stored?.takeIf { it.scope == scope && it.id == id }
        }
        override suspend fun claim(expected: TextUpdateAttemptRecord, admit: (CalendarScope) -> Boolean): Boolean {
            if (!admit(expected.scope)) return false
            fixture.step("claim"); claimFailure?.let { throw it }
            if (!admit(expected.scope) || stored != expected || expected.state != TextUpdateAttemptState.PREPARED) return false
            stored = expected.copy(state = TextUpdateAttemptState.OUTCOME_UNKNOWN)
            fixture.step("claimed"); return true
        }
        override suspend fun retainEvidence(expectedUnknown: TextUpdateAttemptRecord, protectedEvidence: String,
            admit: (CalendarScope) -> Boolean): Boolean {
            evidenceLease = admit
            if (!admit(expectedUnknown.scope)) return false
            fixture.step("evidence"); evidenceFailure?.let { throw it }
            val actual = stored ?: return false
            if (!admit(expectedUnknown.scope) || actual.state != TextUpdateAttemptState.OUTCOME_UNKNOWN ||
                actual.copy(protectedEvidence = null) != expectedUnknown.copy(protectedEvidence = null) ||
                (actual.protectedEvidence != null && actual.protectedEvidence != protectedEvidence)) return false
            stored = actual.copy(protectedEvidence = protectedEvidence); return true
        }
    }
}
