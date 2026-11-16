// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.*
import java.util.Base64
import kotlinx.coroutines.CancellationException
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

/** Native crypto with synthetic keys only. No account, network, Provider or journal access. */
internal object ProtonSeriesEditCryptoProbe {
    suspend fun run(crypto: AndroidCryptoContext, calendarSecret: ByteArray, calendarArmored: String,
        signer: PrivateKey, address: UserAddress, scope: CalendarScope, session: CatalogSession,
        bootstrap: JsonObject, originalRow: JsonObject, originalClear: String, originalSecret: String,
        textSignatures: Boolean): String {
        var stage = "SERIES_FIXTURE"
        try {
            val stamp = Instant.parse("2026-10-03T09:30:00Z")
            val uid = originalRow.string("UID")
            val metadata = buildJsonObject { put("ID", scope.calendar.value); put("Type", 0) }
            val decoder = ProtonEventDecryptor(crypto) { check(it == session.userId); listOf(address) }
            val calendarKey = PrivateKey(calendarArmored, true,
                passphrase = PlainByteArray(calendarSecret).encrypt(crypto.keyStoreCrypto))
            val publicKey = signer.publicKey(crypto)
            fun <T> withKey(action: (me.proton.core.crypto.common.pgp.SessionKey) -> T): T =
                KeyHolderContext(crypto, PrivateKeyRing(crypto, listOf(calendarKey)), PublicKeyRing(emptyList())).use { keys ->
                    checkNotNull(keys.decryptSessionKeyOrNull(Base64.getDecoder().decode(originalRow.string("SharedKeyPacket")))).use(action)
                }
            fun sign(text: String) = if (textSignatures) signer.signText(crypto, text)
                else signer.signData(crypto, text.toByteArray(Charsets.UTF_8))
            fun encrypted(text: String): String {
                val bytes = text.toByteArray(Charsets.UTF_8)
                return try { withKey { Base64.getEncoder().encodeToString(crypto.pgpCrypto.encryptData(bytes, it)) } }
                    finally { bytes.fill(0) }
            }
            fun decrypt(part: JsonObject): String = withKey { key ->
                val bytes = crypto.pgpCrypto.decryptData(Base64.getDecoder().decode(part.string("Data")), key)
                try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
            }
            fun temporal(time: EventTime) = buildJsonObject {
                when (time) {
                    is EventTime.AllDay -> {
                        put("StartTime", time.start.atStartOfDay(ZoneOffset.UTC).toEpochSecond())
                        put("EndTime", time.endExclusive.atStartOfDay(ZoneOffset.UTC).toEpochSecond())
                        put("StartTimezone", "UTC"); put("EndTimezone", "UTC"); put("FullDay", 1)
                    }
                    is EventTime.Timed -> {
                        put("StartTime", time.start.epochSecond); put("EndTime", time.end.epochSecond)
                        val zone = if (time.zone == ZoneOffset.UTC) "UTC" else time.zone.id
                        put("StartTimezone", zone); put("EndTimezone", zone); put("FullDay", 0)
                    }
                }
            }
            val timed = EventTime.Timed(Instant.parse("2026-10-03T10:00:00Z"), Instant.parse("2026-10-03T11:00:00Z"), ZoneOffset.UTC)
            val day = EventTime.AllDay(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 6))
            val cases = listOf(timed to timed.copy(start = timed.start.plusSeconds(7200), end = timed.end.plusSeconds(10800)),
                timed to timed, timed to timed, day to day.copy(start = day.start.plusDays(2), endExclusive = day.endExclusive.plusDays(3)),
                timed to day, day to timed)
            val alarm = "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT15M\r\nSUMMARY:Nested summary\r\nEND:VALARM\r\n"
            check(originalSecret.contains(alarm))
            val secret = originalSecret.replace(alarm, "")
            val notifications = Json.parseToJsonElement("""[{"Type":1,"Trigger":"-PT10M"},{"Type":0,"Trigger":"-PT1H"}]""")
            val keyProtector = ProtonEventPayloadProtector(crypto.keyStoreCrypto)
            for (calendarProfile in listOf(false, true)) for (inherited in listOf(false, true)) for ((index, pair) in cases.withIndex()) {
                stage = "SERIES_CASE_${index}_INHERITED_${inherited}_CALENDAR_$calendarProfile"
                val (beforeTime, afterTime) = pair
                val settings = JsonObject(bootstrap.obj("CalendarSettings") + mapOf(
                    "DefaultPartDayNotifications" to notifications, "DefaultFullDayNotifications" to notifications))
                val observedBootstrap = JsonObject(bootstrap + ("CalendarSettings" to settings))
                val clear = originalClear.replace("END:VEVENT\r\n", "RRULE:FREQ=DAILY;COUNT=4\r\nEND:VEVENT\r\n")
                    .let { if (beforeTime is EventTime.AllDay) it.replace("DTSTART:20261003T100000Z\r\nDTEND:20261003T110000Z",
                        "DTSTART;VALUE=DATE:20261003\r\nDTEND;VALUE=DATE:20261006") else it }
                    .let { if (calendarProfile) it.replace(Regex("(?m)^(CREATED|LAST-MODIFIED):[^\\r\\n]*\\r\\n"), "").removeSuffix("\r\n") else it }
                val secretText = if (calendarProfile) secret.removeSuffix("\r\n") else secret
                val parts = originalRow.array("SharedEvents").map { JsonObject((it as JsonObject) - "UnknownPartMetadata") }
                val personalText = ("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:$uid\r\n" +
                    originalClear.split("\r\n").single { it.startsWith("DTSTAMP:") } +
                    "\r\nTRANSP:OPAQUE\r\nSTATUS:CONFIRMED\r\nEND:VEVENT\r\nEND:VCALENDAR").trimEnd()
                val personalPart = buildJsonObject {
                    put("Type", 2); put("Data", personalText); put("Signature", sign(personalText)); put("Author", address.email)
                }
                // Only synthetic inputs are reshaped into a closed fixture; real rows are never trimmed.
                val row = JsonObject(originalRow - "OpaqueMetadata" + temporal(beforeTime) + buildJsonObject {
                    put("CreateTime", 1); put("ModifyTime", 2); put("Permissions", 3); put("IsOrganizer", 1)
                    put("IsPersonalSingleEdit", false); put("RRule", "FREQ=DAILY;COUNT=4")
                    put("RecurrenceID", JsonNull); put("Exdates", JsonArray(emptyList())); put("Attendees", JsonArray(emptyList()))
                    put("Notifications", if (inherited) JsonNull else notifications); put("Color", "#aBcD12")
                    put("SharedEvents", JsonArray(listOf(
                        JsonObject(parts[0] + mapOf("Data" to JsonPrimitive(clear), "Signature" to JsonPrimitive(sign(clear)))),
                        JsonObject(parts[1] + mapOf("Data" to JsonPrimitive(encrypted(secretText)), "Signature" to JsonPrimitive(sign(secretText)))))))
                    put("CalendarEvents", JsonArray(if (calendarProfile) listOf(personalPart) else emptyList()))
                })
                val caseStage = stage
                stage = "$caseStage BASE_DECODE"
                val baseline = decoder.decodePage(session.userId, scope.calendar.value, observedBootstrap, listOf(row), uid)
                    .also { check(it.deferred.isEmpty()) }.changes.single()
                stage = "$caseStage BASE_FIELDS"
                check(baseline.content.time == beforeTime && baseline.content.details == EventDetails(listOf(EventReminder(10))))
                val replacement = when (index) {
                    1 -> baseline.content.copy(recurrence = EventRecurrence(uid, "FREQ=DAILY;COUNT=8"))
                    2 -> baseline.content.copy(details = EventDetails(listOf(EventReminder(20))), colorRgb = null)
                    else -> baseline.content.copy(title = "Series title", description = "Series notes", location = "Series place", time = afterTime,
                        recurrence = EventRecurrence(uid, "FREQ=DAILY;COUNT=8"))
                }
                val intent = LocalEditIntent(MutationId("synthetic-series-$index"), scope, EVENT_FORMAT, baseline, replacement,
                    seriesProfile = SeriesEditProfile.of(baseline.content, replacement))
                check(runCatching { decoder.prepareAndSealPersonalEdit(session, intent, uid, stamp, metadata, observedBootstrap, row, keyProtector) }.isFailure)
                if (!calendarProfile && !inherited && index == 0) {
                    val badPart = JsonObject(parts[0] + mapOf("Data" to JsonPrimitive(clear), "Signature" to JsonPrimitive(sign("foreign"))))
                    val badRow = JsonObject(row + ("SharedEvents" to JsonArray(listOf(badPart, row.array("SharedEvents")[1]))))
                    val badIntent = intent.copy(base = baseline.copy(revision = badRow.revision(observedBootstrap)))
                    check(runCatching { decoder.prepareAndSealSeriesEdit(session, badIntent, uid, stamp, metadata, observedBootstrap, badRow, keyProtector) }.isFailure)
                    check(runCatching { decoder.prepareAndSealSeriesEdit(session, intent, "foreign", stamp, metadata, observedBootstrap, row, keyProtector) }.isFailure)
                    val wrongBase = baseline.copy(content = baseline.content.copy(title = "Wrong signed base"))
                    val wrongIntent = intent.copy(base = wrongBase)
                    check(runCatching { decoder.prepareAndSealSeriesEdit(session, wrongIntent, uid, stamp, metadata, observedBootstrap, row, keyProtector) }.isFailure)
                }
                stage = "$caseStage PREPARE"
                val prepared = decoder.prepareAndSealSeriesEdit(session, intent, uid, stamp, metadata, observedBootstrap, row, keyProtector)
                check(prepared.isSeries && prepared.format == PERSONAL_SERIES_PREPARATION_FORMAT)
                stage = "$caseStage OPEN"
                val fragments = ProtonPersonalPreparationCodec(keyProtector).openLocal(prepared)
                check(fragments.originalRow == row && (fragments.calendarPart != null) == calendarProfile)
                stage = "$caseStage PATCHED_PARTS"
                val nextClear = fragments.clearSharedPart.string("Data")
                val nextSecret = decrypt(fragments.encryptedSharedPart)
                check(publicKey.verifyText(crypto, nextClear, fragments.clearSharedPart.string("Signature")))
                check(publicKey.verifyText(crypto, nextSecret, fragments.encryptedSharedPart.string("Signature")))
                check(nextClear.contains("X-CLEAR;X-PARAM=opaque:keep this  \r\n folded text\t\r\n"))
                check(nextSecret.contains("X-SECRET;X-PARAM=opaque:second  \r\n folded text\t\r\n"))
                check(nextClear.contains("SEQUENCE:8\r\n") && nextClear.contains("DTSTAMP:20261003T093000Z\r\n"))
                fragments.calendarPart?.let { part -> check(publicKey.verifyText(crypto, part.string("Data"), part.string("Signature"))) }
                stage = "$caseStage REQUEST"
                val selected = ProtonCreateContextPolicy.select(scope, metadata, observedBootstrap, listOf(address))
                val requestContext = ProtonPersonalEventWire.context(selected, metadata, observedBootstrap)
                val codec = ProtonPersonalUpdateRequestCodec(keyProtector)
                val request = codec.seal(MutationId("synthetic-series-request-$index"), prepared, requestContext)
                val opened = codec.open(request)
                val operation = (opened.request.array("Events").single() as JsonObject).obj("Event")
                seriesEventUpdateOnlyBytes(opened.request.toString()).fill(0)
                check(operation["Notifications"] == if (index == 2) Json.parseToJsonElement("""[{"Type":0,"Trigger":"-PT1H"},{"Type":1,"Trigger":"-PT20M"}]""")
                    else if (inherited) JsonNull else notifications)
                check(operation["Color"] == if (index == 2) JsonNull else JsonPrimitive("#aBcD12"))
                val readback = JsonObject(row + temporal(afterTime) + buildJsonObject {
                    put("RRule", replacement.recurrence!!.rule); put("ModifyTime", 3)
                    put("SharedEvents", JsonArray(listOf(fragments.clearSharedPart, fragments.encryptedSharedPart)))
                    put("Notifications", operation.getValue("Notifications")); put("Color", operation.getValue("Color"))
                    fragments.calendarPart?.let { put("CalendarEvents", JsonArray(listOf(it))) }
                })
                stage = "$caseStage READBACK_WIRE"
                check(ProtonSeriesEventUpdate.matchesReadback(row, fragments.clearSharedPart, fragments.encryptedSharedPart,
                    readback, baseline.content, replacement, fragments.defaults, fragments.calendarPart))
                stage = "$caseStage READBACK_CRYPTO"
                val decoded = decoder.decodePage(session.userId, scope.calendar.value, observedBootstrap, listOf(readback), uid)
                check(decoded.deferred.isEmpty() && decoded.changes.single().content == replacement)
                val forged = JsonObject(fragments.encryptedSharedPart + ("Signature" to fragments.clearSharedPart.getValue("Signature")))
                val tampered = JsonObject(readback + ("SharedEvents" to JsonArray(listOf(fragments.clearSharedPart, forged))))
                check(decoder.decodePage(session.userId, scope.calendar.value, observedBootstrap, listOf(tampered), uid).changes.isEmpty())
            }
            return "RESULT=SUCCESS"
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { return "RESULT=FAILURE STAGE=$stage" }
    }
}
