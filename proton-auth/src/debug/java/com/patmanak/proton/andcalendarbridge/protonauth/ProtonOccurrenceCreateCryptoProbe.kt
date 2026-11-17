// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.*
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.proton.core.crypto.android.context.AndroidCryptoContext
import me.proton.core.crypto.common.keystore.PlainByteArray
import me.proton.core.crypto.common.keystore.encrypt
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.*
import me.proton.core.key.domain.entity.key.*
import me.proton.core.key.domain.entity.keyholder.KeyHolderContext
import me.proton.core.network.domain.session.SessionId
import me.proton.core.user.domain.entity.*

/** Real PGP/Keystore on fabricated data only. No account or transport is available. */
object ProtonOccurrenceCreateCryptoProbe {
    suspend fun runSubmission(): String = run(submission = true)
    suspend fun run(timed: Boolean = false, personalOnly: Boolean = false, changeKind: Boolean = false, submission: Boolean = false): String {
        val crypto = AndroidCryptoContext()
        val addressSecret = crypto.pgpCrypto.generateNewToken()
        val calendarSecret = crypto.pgpCrypto.generateNewToken()
        var stage = "KEYS"
        try {
            val signer = PrivateKey(crypto.pgpCrypto.generateNewPrivateKey("fixture", "example.invalid", addressSecret), true,
                passphrase = PlainByteArray(addressSecret).encrypt(crypto.keyStoreCrypto))
            val armored = crypto.pgpCrypto.generateNewPrivateKey("calendar", "example.invalid", calendarSecret)
            val calendarKey = PrivateKey(armored, true, passphrase = PlainByteArray(calendarSecret).encrypt(crypto.keyStoreCrypto))
            val user = UserId("occurrence-fixture"); val addressId = AddressId("address")
            val address = UserAddress(user, addressId, "fixture@example.invalid", canSend = true, canReceive = true, enabled = true,
                order = 0, keys = listOf(UserAddressKey(addressId, 1, 3, active = true, keyId = KeyId("key"), privateKey = signer)), signedKeyList = null)
            val session = CatalogSession(user, SessionId("session"))
            val scope = CalendarScope(AccountId(user.id), CalendarId("calendar"), 0)
            val uid = "occurrence@calendeer.invalid"
            val stamp = Instant.parse("2030-03-30T12:00:00Z")
            val alerts = Json.parseToJsonElement("""[{"Type":0,"Trigger":"-PT1H"},{"Type":1,"Trigger":"-PT10M"}]""")
            val packet = address.useKeys(crypto) { buildJsonObject {
                put("MemberID", "member"); put("Passphrase", encryptText(String(calendarSecret, Charsets.UTF_8)))
                put("Signature", signText(String(calendarSecret, Charsets.UTF_8)))
            } }
            val bootstrap = buildJsonObject {
                putJsonObject("Passphrase") { put("ID", "pass"); put("CalendarID", "calendar"); put("Flags", 1); put("MemberPassphrases", JsonArray(listOf(packet))) }
                putJsonArray("Members") { add(buildJsonObject { put("ID", "member"); put("CalendarID", "calendar"); put("AddressID", "address"); put("Flags", 1); put("Permissions", 16) }) }
                putJsonArray("Keys") { add(buildJsonObject { put("ID", "calendar-key"); put("CalendarID", "calendar"); put("Flags", 3); put("PassphraseID", "pass"); put("PrivateKey", armored) }) }
                putJsonObject("CalendarSettings") { put("CalendarID", "calendar"); put("DefaultPartDayNotifications", alerts); put("DefaultFullDayNotifications", alerts) }
            }
            val metadata = buildJsonObject { put("ID", "calendar"); put("Type", 0) }
            val time: EventTime = if (timed) EventTime.Timed(Instant.parse("2030-03-30T08:00:00Z"),
                Instant.parse("2030-03-30T09:00:00Z"), ZoneId.of("Europe/Paris")) else EventTime.AllDay(LocalDate.parse("2030-03-30"), LocalDate.parse("2030-03-31"))
            val content = EventContent("Original", time, "Notes", "Place", details = EventDetails())
            val serialized = ProtonSimpleEventSerializer.serializeSeries(uid, stamp.minusSeconds(3600), content, CreateSeriesRule(5))
            val opaque = "X-OPAQUE:Retain  bytes\r\n folded\r\n"
            val parts = ProtonEventTextParts(serialized.clearShared.replace("END:VEVENT", opaque + "END:VEVENT"),
                serialized.encryptedShared.replace("END:VEVENT", opaque + "END:VEVENT"))
            val calendarText = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:20300330T110000Z\r\nTRANSP:OPAQUE\r\nSTATUS:CONFIRMED\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
            fun responseParts(value: JsonElement) = JsonArray((value as JsonArray).map { JsonObject((it as JsonObject) + ("Author" to JsonPrimitive(address.email))) })
            val encrypted = ProtonSimpleEventEncryptor(crypto).prepareParts(parts, signer, calendarKey.publicKey(crypto), calendarText).event
            val row = buildJsonObject {
                put("ID", "master"); put("CalendarID", "calendar"); put("UID", uid); put("SharedEventID", "master-shared")
                put("CreateTime", 1); put("ModifyTime", 2); put("Permissions", 3); put("IsOrganizer", 1); put("IsPersonalSingleEdit", false)
                put("RRule", "FREQ=DAILY;COUNT=5"); put("RecurrenceID", JsonNull); put("Exdates", JsonArray(emptyList()))
                put("Attendees", JsonArray(emptyList())); put("AttendeesEvents", JsonArray(emptyList())); put("Notifications", JsonNull); put("Color", JsonNull)
                put("SharedKeyPacket", encrypted["SharedKeyPacket"]!!); put("SharedEvents", responseParts(encrypted["SharedEventContent"]!!))
                put("CalendarEvents", responseParts(encrypted["CalendarEventContent"]!!))
                for ((key, value) in temporal(time)) put(key, value)
            }
            val decoder = ProtonEventDecryptor(crypto) { check(it == user); listOf(address) }
            stage = "BASE"
            val base = decoder.decrypt(user, "calendar", bootstrap, listOf(row)).single()
            val generatedTime: EventTime = if (timed) EventTime.Timed(Instant.parse("2030-03-31T07:00:00Z"), Instant.parse("2030-03-31T08:00:00Z"), ZoneId.of("Europe/Paris"))
                else EventTime.AllDay(LocalDate.parse("2030-03-31"), LocalDate.parse("2030-04-01"))
            val original = if (timed) RecurrencePoint.Timed((generatedTime as EventTime.Timed).start) else RecurrencePoint.AllDay(LocalDate.parse("2030-03-31"))
            val movedTime: EventTime = if (changeKind) EventTime.Timed(Instant.parse("2030-04-02T10:00:00Z"), Instant.parse("2030-04-02T11:00:00Z"), ZoneOffset.UTC)
                else if (timed && !personalOnly) (generatedTime as EventTime.Timed).let { it.copy(start = it.start.plusSeconds(7200), end = it.end.plusSeconds(7200)) } else generatedTime
            val child = base.content.copy(title = if (personalOnly) base.content.title else "Edited", time = movedTime, recurrence = null,
                details = if (personalOnly) EventDetails(listOf(EventReminder(20))) else base.content.details, colorRgb = if (personalOnly) 0x415df0 else null)
            val intent = LocalCreateIntent(LocalCreateReservation(MutationId("capture"), LocalEventId("11111111-2222-3333-4444-555555555555"), scope, EVENT_FORMAT, stamp),
                child, occurrence = OccurrenceEditCandidate(base, original, child))
            val protector = ProtonEventPayloadProtector(crypto.keyStoreCrypto)
            val id = MutationId("22222222-3333-4444-5555-666666666666")
            stage = "PREPARE"
            val record = decoder.prepareOccurrenceCreate(session, intent, id, metadata, bootstrap, row, protector)
            if (submission) {
                ProtonOccurrenceSubmissionProbe.verify(record, intent, session, metadata, bootstrap, row, decoder, protector)
                return "RESULT=SUCCESS"
            }
            val opened = ProtonOccurrenceSubmissionCodec(protector).open(record, intent)
            val event = (opened.request.array("Events").single() as JsonObject).obj("Event")
            check(opened.source == row && event["IsPersonalSingleEdit"] == JsonPrimitive(personalOnly))
            check(event.string("SharedKeyPacket") != row.string("SharedKeyPacket"))
            check(event["Color"] == if (personalOnly) JsonPrimitive("#415df0") else JsonNull)
            check(if (personalOnly) event.array("Notifications").any { (it as JsonObject).number("Type") == 0L } else event["Notifications"] == JsonNull)
            stage = "DECRYPT"
            KeyHolderContext(crypto, PrivateKeyRing(crypto, listOf(calendarKey)), PublicKeyRing(emptyList())).use { keys ->
                checkNotNull(keys.decryptSessionKeyOrNull(Base64.getDecoder().decode(event.string("SharedKeyPacket")))).use { key ->
                    for (element in event.array("SharedEventContent")) {
                        val part = element as JsonObject
                        val plain = if (part.number("Type") == 2L) part.string("Data") else {
                            val bytes = crypto.pgpCrypto.decryptData(Base64.getDecoder().decode(part.string("Data")), key)
                            try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
                        }
                        check(crypto.pgpCrypto.verifyText(plain, part.string("Signature"), signer.publicKey(crypto).key))
                        check(plain.contains(opaque) && plain.contains("UID:$uid\r\n") && !plain.contains("RRULE:"))
                        if (part.number("Type") == 2L) {
                            check(plain.contains(if (timed) "RECURRENCE-ID;TZID=Europe/Paris:20300331T090000" else "RECURRENCE-ID;VALUE=DATE:20300331"))
                            check(plain.contains(if (changeKind || (timed && !personalOnly)) "SEQUENCE:1" else "SEQUENCE:0"))
                        }
                    }
                }
            }
            val calendarPart = event.array("CalendarEventContent").single() as JsonObject
            check(calendarPart.string("Data").contains("STATUS:CONFIRMED") && calendarPart.string("Data").contains("DTSTAMP:20300330T120000Z"))
            check(crypto.pgpCrypto.verifyText(calendarPart.string("Data"), calendarPart.string("Signature"), signer.publicKey(crypto).key))
            stage = "REFUSALS"
            val wrong = JsonObject(row + ("SharedEvents" to JsonArray(row.array("SharedEvents").map {
                val part = it as JsonObject
                if (part.number("Type") == 2L) JsonObject(part + ("Data" to JsonPrimitive(part.string("Data").replace("SEQUENCE:0", "SEQUENCE:99")))) else part
            })))
            // Bind the altered row's actual revision so rejection must reach source authentication/profile checks.
            fun bound(observed: JsonObject) = intent.copy(occurrence = OccurrenceEditCandidate(
                base.copy(revision = observed.revision(bootstrap)), original, child))
            check(runCatching { decoder.prepareOccurrenceCreate(session, bound(wrong), id, metadata, bootstrap, wrong, protector) }.isFailure)
            val wrongCalendar = JsonObject(row + ("CalendarEvents" to JsonArray(row.array("CalendarEvents").map {
                JsonObject((it as JsonObject) + ("Signature" to JsonPrimitive("invalid-signature")))
            })))
            check(runCatching { decoder.prepareOccurrenceCreate(session, bound(wrongCalendar), id, metadata, bootstrap, wrongCalendar, protector) }.isFailure)
            val noSequence = JsonObject(row + ("SharedEvents" to JsonArray(row.array("SharedEvents").map {
                val part = it as JsonObject
                if (part.number("Type") != 2L) part else {
                    val plain = part.string("Data").replace("SEQUENCE:0\r\n", "")
                    JsonObject(part + mapOf("Data" to JsonPrimitive(plain), "Signature" to JsonPrimitive(signer.signText(crypto, plain))))
                }
            })))
            check(runCatching { decoder.prepareOccurrenceCreate(session, bound(noSequence), id, metadata, bootstrap, noSequence, protector) }.isFailure)
            check(runCatching { decoder.prepareOccurrenceCreate(session, intent.copy(reservation = intent.reservation.copy(format = AdapterFormat("foreign", 1))), id, metadata, bootstrap, row, protector) }.isFailure)
            check(runCatching { decoder.prepareOccurrenceCreate(session, intent, id, JsonObject(metadata + ("Type" to JsonPrimitive(1))), bootstrap, row, protector) }.isFailure)
            val cancelled = Job()
            val cancelling = ProtonEventDecryptor(crypto) { cancelled.cancel(); listOf(address) }
            val result = runCatching { withContext(cancelled) { cancelling.prepareOccurrenceCreate(session, intent, id, metadata, bootstrap, row, protector) } }
            check(result.exceptionOrNull() is CancellationException)
            return "RESULT=SUCCESS"
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { return "RESULT=FAIL:$stage" }
        finally { addressSecret.fill(0); calendarSecret.fill(0) }
    }
    private fun temporal(time: EventTime): JsonObject = buildJsonObject {
        when (time) {
            is EventTime.AllDay -> { put("FullDay", 1); put("StartTime", time.start.atStartOfDay(ZoneOffset.UTC).toEpochSecond()); put("EndTime", time.endExclusive.atStartOfDay(ZoneOffset.UTC).toEpochSecond()); put("StartTimezone", "UTC"); put("EndTimezone", "UTC") }
            is EventTime.Timed -> { put("FullDay", 0); put("StartTime", time.start.epochSecond); put("EndTime", time.end.epochSecond); put("StartTimezone", time.zone.id); put("EndTimezone", time.zone.id) }
        }
    }
}
