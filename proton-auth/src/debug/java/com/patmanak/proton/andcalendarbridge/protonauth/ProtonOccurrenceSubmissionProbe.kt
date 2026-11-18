// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.ZoneOffset
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Real crypto, retained capsule and intercepted synthetic HTTP; no socket or service. */
internal object ProtonOccurrenceSubmissionProbe {
    suspend fun verify(record: CreateAttemptRecord, intent: LocalCreateIntent, session: CatalogSession,
        metadata: JsonObject, bootstrap: JsonObject, row: JsonObject, decoder: ProtonEventDecryptor, protector: EventPayloadProtector) {
        val expected = ProtonOccurrenceSubmissionCodec(protector).open(record, intent).request.toString()
        val candidate = requireNotNull(intent.occurrence)
        val event = Json.parseToJsonElement(expected).jsonObject.array("Events").single().jsonObject.obj("Event")
        val signedAuthor = row.array("SharedEvents").first().jsonObject.string("Author")
        fun signedParts(name: String) = JsonArray((event[name] as? JsonArray).orEmpty().map {
            JsonObject(it.jsonObject + ("Author" to JsonPrimitive(signedAuthor)))
        })
        val slot = when (val original = candidate.original) {
            is RecurrencePoint.AllDay -> original.date.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
            is RecurrencePoint.Timed -> original.instant.epochSecond
        }
        val child = JsonObject(row + ProtonPersonalEventUpdate.metadata(candidate.content.time) + mapOf(
            "ID" to JsonPrimitive("detached"), "SharedEventID" to JsonPrimitive("detached-shared"),
            "RRule" to JsonNull, "RecurrenceID" to JsonPrimitive(slot), "ModifyTime" to JsonPrimitive(3),
            "Notifications" to event.getValue("Notifications"), "Color" to event.getValue("Color"),
            "IsPersonalSingleEdit" to event.getValue("IsPersonalSingleEdit"), "SharedKeyPacket" to event.getValue("SharedKeyPacket"),
            "SharedEvents" to signedParts("SharedEventContent"), "CalendarEvents" to signedParts("CalendarEventContent")))
        for (lose in listOf(false, true)) {
            var sends = 0; var reads = 0; var current: CatalogSession? = session
            var stored = record
            val client = OkHttpClient.Builder().withSingleDispatchGuards().addInterceptor { chain ->
                val request = chain.request()
                check(request.url.host == "calendeer.invalid" && request.method == "PUT" && request.body!!.isOneShot())
                Buffer().use { buffer -> request.body!!.writeTo(buffer); check(buffer.readUtf8() == expected) }
                sends++; check(sends == 1 && stored.state == CreateAttemptState.OUTCOME_UNKNOWN)
                if (lose) throw java.io.IOException("Synthetic lost occurrence response")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic")
                    .body("{\"Code\":1000}".toResponseBody("application/json".toMediaType())).build()
            }.build()
            try {
                var observedChild: JsonObject? = null
                val remote = object : CreateSubmissionRemote {
                    override suspend fun event(session: CatalogSession, calendar: String, id: String): JsonObject {
                        reads++; return when (id) {
                            candidate.master.eventId.value -> row
                            "detached" -> checkNotNull(observedChild)
                            else -> error("Unexpected event")
                        }
                    }
                    override suspend fun byUid(session: CatalogSession, calendar: String, uid: String, page: Int): List<JsonObject> {
                        reads++; check(page == 0 && uid == row.string("UID")); return listOfNotNull(row, observedChild)
                    }
                    override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject, allowsTransport: () -> Boolean): String = error("No historical sender")
                    override suspend fun sendOccurrence(session: CatalogSession, calendar: String, request: JsonObject, allowsTransport: () -> Boolean): String {
                        val permit = ProtonSingleDispatchAttempt.occurrence("https://calendeer.invalid/calendar/v1/synthetic/events/sync".toHttpUrl(), request.toString(), allowsTransport)
                        return client.newCall(permit.request()).execute().use { it.body!!.string() }
                    }
                }
                val events = object : EventImportRemote {
                    override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage = error("No windows")
                    override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject { reads++; return bootstrap }
                }
                val adapter = ProtonOccurrenceCreateAdapter(CatalogSessionSource { current }, EventContextRefresher { reads++ },
                    CreateCalendarMetadataRemote { _, _ -> reads++; metadata }, events, remote,
                    OccurrenceCreateCryptography { observed, captured, id, meta, boot, source ->
                        decoder.prepareOccurrenceCreate(observed, captured, id, meta, boot, source, protector)
                    }, seriesFamilyVerifier(decoder), protector, occurrenceChildVerifier(decoder))
                val journal = object : CreateAttemptJournal {
                    override suspend fun remember(record: CreateAttemptRecord) { check(stored == record) }
                    override suspend fun read(scope: CalendarScope, id: MutationId) = stored.takeIf { it.scope == scope && it.id == id }
                    override fun allowsTransport(scope: CalendarScope) = scope == stored.scope
                    override suspend fun claim(expected: CreateAttemptRecord): Boolean {
                        if (stored != expected || stored.state != CreateAttemptState.PREPARED) return false
                        stored = stored.copy(state = CreateAttemptState.OUTCOME_UNKNOWN); return true
                    }
                    override suspend fun retainEvidence(expectedUnknown: CreateAttemptRecord, protectedEvidence: String) {
                        check(stored == expectedUnknown); stored = stored.copy(protectedEvidence = protectedEvidence)
                    }
                }
                val draft = LocalCreateSubmissionReservation.forOccurrence(intent, record.id, record.format).draft
                val first = runCatching { adapter.submit(record, draft, journal) }
                check(if (lose) first.isFailure else first.getOrThrow() == CreateSubmissionObservation.Unknown)
                check(sends == 1 && stored.state == CreateAttemptState.OUTCOME_UNKNOWN && (stored.protectedEvidence != null) == !lose)
                val before = reads; current = null
                repeat(2) { check(adapter.submit(record, draft, journal) == CreateSubmissionObservation.Unknown) }
                check(reads == before && sends == 1)
                current = session
                val port = ProtonCreateSubmissionAdapter(CalendarCreatePreparationPort { error("No preparation during readback") }, protector,
                    CatalogSessionSource { current }, remote, events, CreateEventVerifier { _, _, _, _, _, _ -> error("Wrong verifier") }, adapter)
                check(port.inspectCaptured(stored, draft) == CreateSubmissionObservation.Unknown)
                observedChild = child
                val observed = port.inspectCaptured(stored, draft) as? CreateSubmissionObservation.Verified
                check(observed?.event == RemoteChange.Upsert(EventId("detached"), child.revision(bootstrap), candidate.content))
                observedChild = JsonObject(child + ("SharedKeyPacket" to row.getValue("SharedKeyPacket")))
                check(port.inspectCaptured(stored, draft) == CreateSubmissionObservation.Unknown && sends == 1)
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
}
