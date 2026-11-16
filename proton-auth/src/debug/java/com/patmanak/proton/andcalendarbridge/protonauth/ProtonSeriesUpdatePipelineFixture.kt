// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.io.IOException
import java.time.*
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import me.proton.core.crypto.android.context.AndroidCryptoContext
import me.proton.core.crypto.common.keystore.PlainByteArray
import me.proton.core.crypto.common.keystore.encrypt
import me.proton.core.key.domain.*
import me.proton.core.key.domain.entity.key.PrivateKey
import me.proton.core.key.domain.entity.key.PrivateKeyRing
import me.proton.core.key.domain.entity.key.PublicKeyRing
import me.proton.core.key.domain.entity.keyholder.KeyHolderContext
import me.proton.core.user.domain.entity.UserAddress
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer

/** Synthetic service boundary around real preparation, crypto and submission adapters.
 * The app test supplies Provider/SQL/ACK operations. No HTTP or real account is reachable.
 */
class ProtonSeriesUpdatePipelineFixture private constructor(val scope: CalendarScope,
    val base: RemoteChange.Upsert, val replacement: EventContent,
    val preparations: CalendarPersonalPreparationPort, val updates: CalendarPersonalUpdatePort,
    val timestamp: Instant, private val state: State) {
    val format: AdapterFormat get() = EVENT_FORMAT
    val sendCount: Int get() = state.sends.get()
    var loseResponse: Boolean
        get() = state.loseResponse
        set(value) { state.loseResponse = value }
    var holdReadback: Boolean
        get() = state.holdReadback
        set(value) { state.holdReadback = value }
    var detachedChild: Boolean
        get() = state.detachedChild
        set(value) { state.detachedChild = value }
    fun revoke() { state.active = false }
    override fun toString() = "ProtonSeriesUpdatePipelineFixture(REDACTED)"

    private class State(@Volatile var row: JsonObject) {
        @Volatile var active = true
        @Volatile var loseResponse = false
        @Volatile var holdReadback = false
        @Volatile var detachedChild = false
        val sends = AtomicInteger()
        val dispatched = AtomicBoolean()
        override fun toString() = "SeriesPipelineState(REDACTED)"
    }

    internal companion object {
        suspend fun create(crypto: AndroidCryptoContext, calendarSecret: ByteArray, calendarArmored: String,
            signer: PrivateKey, address: UserAddress, scope: CalendarScope, session: CatalogSession,
            bootstrap: JsonObject, originalRow: JsonObject, originalClear: String, originalSecret: String,
            allDay: Boolean): ProtonSeriesUpdatePipelineFixture {
            val uid = originalRow.string("UID")
            val stamp = Instant.parse("2026-10-24T09:30:00Z")
            val zone = ZoneId.of("Europe/Paris")
            val beforeTime: EventTime = if (allDay) EventTime.AllDay(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 25))
                else EventTime.Timed(Instant.parse("2026-10-24T12:00:00Z"), Instant.parse("2026-10-24T13:00:00Z"), zone)
            val afterTime: EventTime = if (allDay) EventTime.AllDay(LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 27))
                else EventTime.Timed(Instant.parse("2026-10-24T13:00:00Z"), Instant.parse("2026-10-24T14:30:00Z"), zone)
            val dates = if (allDay) "DTSTART;VALUE=DATE:20261024\r\nDTEND;VALUE=DATE:20261025"
                else "DTSTART;TZID=Europe/Paris:20261024T140000\r\nDTEND;TZID=Europe/Paris:20261024T150000"
            val clear = originalClear.replace("DTSTART:20261003T100000Z\r\nDTEND:20261003T110000Z", dates)
                .replace("END:VEVENT\r\n", "RRULE:FREQ=DAILY;COUNT=3\r\nEND:VEVENT\r\n")
            val alarm = "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT15M\r\nSUMMARY:Nested summary\r\nEND:VALARM\r\n"
            check(originalSecret.contains(alarm))
            val secret = originalSecret.replace(alarm, "")
            val calendarKey = PrivateKey(calendarArmored, true,
                passphrase = PlainByteArray(calendarSecret).encrypt(crypto.keyStoreCrypto))
            val encrypted = KeyHolderContext(crypto, PrivateKeyRing(crypto, listOf(calendarKey)), PublicKeyRing(emptyList())).use { keys ->
                checkNotNull(keys.decryptSessionKeyOrNull(Base64.getDecoder().decode(originalRow.string("SharedKeyPacket")))).use { key ->
                    val bytes = secret.toByteArray(Charsets.UTF_8)
                    try { Base64.getEncoder().encodeToString(crypto.pgpCrypto.encryptData(bytes, key)) } finally { bytes.fill(0) }
                }
            }
            fun part(type: Int, data: String, plaintext: String) = buildJsonObject {
                put("Type", type); put("Data", data); put("Signature", signer.signText(crypto, plaintext)); put("Author", address.email)
            }
            val personalText = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:$uid\r\n" +
                originalClear.split("\r\n").single { it.startsWith("DTSTAMP:") } +
                "\r\nTRANSP:OPAQUE\r\nSTATUS:CONFIRMED\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
            val alerts = Json.parseToJsonElement("""[{"Type":1,"Trigger":"-PT10M"},{"Type":0,"Trigger":"-PT1H"}]""")
            val frozenBootstrap = JsonObject(bootstrap + ("CalendarSettings" to JsonObject(bootstrap.obj("CalendarSettings") + mapOf(
                "DefaultPartDayNotifications" to alerts, "DefaultFullDayNotifications" to alerts))))
            // Synthetic input only: remove deliberately unsupported seed decorations, then
            // provide the closed personal master shape. Real imported rows are never trimmed.
            val state = State(JsonObject(originalRow - "OpaqueMetadata" + temporal(beforeTime) + buildJsonObject {
                put("CreateTime", 1); put("ModifyTime", 2); put("Permissions", 3); put("IsOrganizer", 1)
                put("IsPersonalSingleEdit", false); put("RRule", "FREQ=DAILY;COUNT=3"); put("RecurrenceID", JsonNull)
                put("Exdates", JsonArray(emptyList())); put("Attendees", JsonArray(emptyList()))
                put("AttendeesEvents", JsonArray(emptyList())); put("Notifications", JsonNull); put("Color", "#aBcD12")
                put("SharedEvents", JsonArray(listOf(part(2, clear, clear), part(3, encrypted, secret))))
                put("CalendarEvents", JsonArray(listOf(part(2, personalText, personalText))))
            }))
            suspend fun active(observed: CatalogSession = session) {
                currentCoroutineContext().ensureActive()
                check(state.active && observed == session && observed.userId.id == scope.account.value)
            }
            val sessions = CatalogSessionSource { currentCoroutineContext().ensureActive(); session.takeIf { state.active } }
            val addresses = LocalEventAddresses { user -> active(); check(user == session.userId); listOf(address) }
            val decoder = ProtonEventDecryptor(crypto, addresses)
            suspend fun decode(observed: CatalogSession, calendar: String, bootstrapRow: JsonObject,
                row: JsonObject, expectedUid: String): RemoteChange.Upsert? {
                active(observed); check(calendar == scope.calendar.value && expectedUid == uid)
                val result = decoder.decodePage(observed.userId, calendar, bootstrapRow, listOf(row), expectedUid)
                active(observed)
                if (result.deferred.isNotEmpty()) return null
                return result.changes.singleOrNull()
            }
            val base = checkNotNull(decode(session, scope.calendar.value, frozenBootstrap, state.row, uid)).let {
                it.copy(content = SeriesEditProfile.freezeMaster(it.content))
            }
            check(base.content.time == beforeTime && base.content.recurrence == EventRecurrence(uid, "FREQ=DAILY;COUNT=3"))
            val replacement = SeriesEditProfile.freezeMaster(base.content.copy(title = "Pipeline series title", description = "Pipeline series notes",
                location = "Pipeline series place", time = afterTime, recurrence = EventRecurrence(uid, "FREQ=DAILY;COUNT=4"),
                details = EventDetails(listOf(EventReminder(20))), colorRgb = null))
            val metadata = buildJsonObject { put("ID", scope.calendar.value); put("Type", 0) }
            val protector = ProtonEventPayloadProtector(crypto.keyStoreCrypto)
            val events = object : EventImportRemote {
                override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage = error("No window reads")
                override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject {
                    active(session); check(calendar == scope.calendar.value); return frozenBootstrap
                }
            }
            val remote = object : SeriesUpdateSubmissionRemote, CreateReadbackRemote {
                override suspend fun event(session: CatalogSession, calendar: String, id: String): JsonObject {
                    active(session); check(calendar == scope.calendar.value && id == base.eventId.value)
                    if (state.holdReadback && state.sends.get() > 0) throw IOException("SYNTHETIC_SERIES_READBACK_OFFLINE")
                    return state.row
                }
                override suspend fun byUid(session: CatalogSession, calendar: String, uid: String, page: Int): List<JsonObject> {
                    active(session); check(calendar == scope.calendar.value && uid == base.content.recurrence!!.uid && page >= 0)
                    if (page != 0) return emptyList()
                    return if (!state.detachedChild) listOf(state.row) else listOf(state.row,
                        JsonObject(state.row + mapOf("ID" to JsonPrimitive("synthetic-detached-child"), "RecurrenceID" to JsonPrimitive(1))))
                }
                override suspend fun send(session: CatalogSession, calendar: String, request: JsonObject): String = error("No personal-v2 dispatch")
                override suspend fun sendSeries(session: CatalogSession, calendar: String, request: JsonObject): String {
                    active(session); check(calendar == scope.calendar.value)
                    val url = "https://calendar-api.proton.me/".toHttpUrl().newBuilder().addPathSegment("calendar").addPathSegment("v1")
                        .addPathSegment(calendar).addPathSegment("events").addPathSegment("sync").build()
                    val permit = ProtonSeriesEditV3DispatchAttempt(url, request.toString())
                    val call = permit.request(); check(permit.enter(call) && permit.exchange(call))
                    val bytes = Buffer(); permit.requestBody.writeTo(bytes); check(bytes.readUtf8() == request.toString())
                    check(state.dispatched.compareAndSet(false, true))
                    val operation = request.array("Events").single() as JsonObject
                    check(operation.string("ID") == base.eventId.value && request.string("MemberID") == frozenBootstrap.array("Members").single().jsonObject.string("ID"))
                    val event = operation.obj("Event")
                    fun authored(name: String) = JsonArray(event.array(name).map { value ->
                        JsonObject((value as JsonObject) + ("Author" to JsonPrimitive(address.email)))
                    })
                    val next = JsonObject(state.row + temporal(afterTime) + buildJsonObject {
                        put("SharedEvents", authored("SharedEventContent")); put("CalendarEvents", authored("CalendarEventContent"))
                        put("Notifications", event.getValue("Notifications")); put("Color", event.getValue("Color"))
                        put("RRule", replacement.recurrence!!.rule); put("ModifyTime", state.row.number("ModifyTime") + 1)
                    })
                    val decoded = decode(session, calendar, frozenBootstrap, next, uid)
                    check(decoded != null && decoded.eventId == base.eventId && decoded.content == replacement)
                    active(session); state.row = next; state.sends.incrementAndGet()
                    if (state.loseResponse) throw IOException("SYNTHETIC_SERIES_RESPONSE_LOST")
                    return "{\"Code\":1001}"
                }
            }
            val seriesVerifier = seriesFamilyVerifier(decoder)
            val family = ProtonCreatedEventReadback(sessions, remote, events,
                CreateEventVerifier { _, _, _, _, _, _ -> null }, seriesVerifier)
            val qualification = PersonalSeriesQualification { observed, target, expected, checkpoint ->
                active(observed); check(target == scope); checkpoint()
                family.requireSeries(observed, target, expected, checkpoint); checkpoint(); active(observed)
            }
            val preparations = ProtonPersonalPreparationAdapter(sessions, { observed -> active(observed) },
                { observed, calendar -> active(observed); check(calendar == scope.calendar.value); metadata }, events,
                { observed, target, event -> active(observed); check(target == scope && event == base.eventId); state.row },
                addresses, personalCryptoPreparation(crypto, protector),
                LocalTitleContextRefresh { observed, checkpoint -> checkpoint(); active(observed); checkpoint() },
                seriesCryptoPreparation(crypto, protector), qualification)
            val context = TitleUpdateContextQualification { observed, target, checkpoint ->
                checkpoint(); active(observed); check(target == scope)
                val selection = ProtonCreateContextPolicy.select(scope, metadata, frozenBootstrap, listOf(address))
                checkpoint(); active(observed)
                ProtonPersonalEventWire.context(selection, metadata, frozenBootstrap)
            }
            val updates = ProtonPersonalUpdateSubmissionAdapter(preparations, protector, sessions, context, remote, events,
                CreateEventVerifier { _, _, _, _, _, _ -> null }, seriesVerifier, qualification)
            return ProtonSeriesUpdatePipelineFixture(scope, base, replacement, preparations, updates, stamp, state)
        }

        private fun temporal(time: EventTime): JsonObject = buildJsonObject {
            when (time) {
                is EventTime.AllDay -> {
                    put("StartTime", time.start.atStartOfDay(ZoneOffset.UTC).toEpochSecond())
                    put("EndTime", time.endExclusive.atStartOfDay(ZoneOffset.UTC).toEpochSecond())
                    put("StartTimezone", "UTC"); put("EndTimezone", "UTC"); put("FullDay", 1)
                }
                is EventTime.Timed -> {
                    put("StartTime", time.start.epochSecond); put("EndTime", time.end.epochSecond)
                    put("StartTimezone", time.zone.id); put("EndTimezone", time.zone.id); put("FullDay", 0)
                }
            }
        }
    }
}
