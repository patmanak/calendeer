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

/** Native Core with synthetic keys and a frozen local fixture; no account/network/Provider IO. */
internal object ProtonPersonalCryptoProbe {
    suspend fun run(crypto: AndroidCryptoContext, calendarSecret: ByteArray, calendarArmored: String,
        signer: PrivateKey, address: UserAddress, scope: CalendarScope, session: CatalogSession,
        bootstrap: JsonObject, originalRow: JsonObject, originalClear: String, originalSecret: String,
        textSignatures: Boolean): String {
        var stage = "PERSONAL_FIXTURE"
        try {
            val timestamp = Instant.parse("2026-10-03T09:30:00Z")
            val uid = originalRow.string("UID")
            val metadata = buildJsonObject { put("ID",scope.calendar.value);put("Type",0) }
            val decoder = ProtonEventDecryptor(crypto) { check(it == session.userId);listOf(address) }
            val calendarKey = PrivateKey(calendarArmored,true,passphrase=PlainByteArray(calendarSecret).encrypt(crypto.keyStoreCrypto))
            val publicKey = signer.publicKey(crypto)
            fun <T> withKey(action: (me.proton.core.crypto.common.pgp.SessionKey) -> T): T =
                KeyHolderContext(crypto,PrivateKeyRing(crypto,listOf(calendarKey)),PublicKeyRing(emptyList())).use { keys ->
                    val key = checkNotNull(keys.decryptSessionKeyOrNull(Base64.getDecoder().decode(originalRow.string("SharedKeyPacket"))))
                    key.use(action)
                }
            fun sign(text: String) = if(textSignatures) signer.signText(crypto,text) else signer.signData(crypto,text.toByteArray(Charsets.UTF_8))
            fun encrypted(text: String): String {
                val bytes=text.toByteArray(Charsets.UTF_8)
                return try { withKey { Base64.getEncoder().encodeToString(crypto.pgpCrypto.encryptData(bytes,it)) } } finally { bytes.fill(0) }
            }
            fun decrypt(part: JsonObject): String = withKey { key ->
                val bytes=crypto.pgpCrypto.decryptData(Base64.getDecoder().decode(part.string("Data")),key)
                try { String(bytes,Charsets.UTF_8) } finally { bytes.fill(0) }
            }
            fun temporal(time: EventTime) = buildJsonObject {
                when(time) {
                    is EventTime.AllDay -> {
                        put("StartTime",time.start.atStartOfDay(ZoneOffset.UTC).toEpochSecond());put("EndTime",time.endExclusive.atStartOfDay(ZoneOffset.UTC).toEpochSecond())
                        put("StartTimezone","UTC");put("EndTimezone","UTC");put("FullDay",1)
                    }
                    is EventTime.Timed -> {
                        put("StartTime",time.start.epochSecond);put("EndTime",time.end.epochSecond)
                        val zone=if(time.zone == ZoneOffset.UTC) "UTC" else time.zone.id
                        put("StartTimezone",zone);put("EndTimezone",zone);put("FullDay",0)
                    }
                }
            }
            val alarm="BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT15M\r\nSUMMARY:Nested summary\r\nEND:VALARM\r\n"
            check(originalSecret.contains(alarm))
            val secret=originalSecret.replace(alarm,"")
            val timed=EventTime.Timed(Instant.parse("2026-10-03T10:00:00Z"),Instant.parse("2026-10-03T11:00:00Z"),ZoneOffset.UTC)
            val day=EventTime.AllDay(LocalDate.of(2026,10,3),LocalDate.of(2026,10,6))
            val notifications=buildJsonArray {
                add(buildJsonObject { put("Type",1);put("Trigger","-PT10M") })
                add(buildJsonObject { put("Type",0);put("Trigger","-PT1H") })
            }
            // Build a distinct closed synthetic wire fixture. Unknown source-fixture JSON is
            // deliberately excluded here; no real imported event is trimmed to make it writable.
            fun source(clear: String,time: EventTime): JsonObject {
                val parts=originalRow.array("SharedEvents").map { JsonObject((it as JsonObject)-"UnknownPartMetadata") }
                return JsonObject(originalRow-"OpaqueMetadata"+temporal(time)+buildJsonObject {
                    put("SharedEvents",JsonArray(listOf(
                        JsonObject(parts[0]+mapOf("Data" to JsonPrimitive(clear),"Signature" to JsonPrimitive(sign(clear)))),
                        JsonObject(parts[1]+mapOf("Data" to JsonPrimitive(encrypted(secret)),"Signature" to JsonPrimitive(sign(secret)))))))
                    put("CreateTime",1);put("ModifyTime",2);put("Permissions",3);put("IsOrganizer",1);put("IsPersonalSingleEdit",false)
                    put("Attendees",JsonArray(emptyList()));put("Exdates",JsonArray(emptyList()))
                    put("Notifications",notifications);put("Color","#aBcD12")
                })
            }
            fun protector()=ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto)
            val selected=ProtonCreateContextPolicy.select(scope,metadata,bootstrap,listOf(address))
            val requestContext=ProtonSimpleEventUpdate.context(selected,metadata,bootstrap)
            val oldDates="DTSTART:20261003T100000Z\r\nDTEND:20261003T110000Z"
            val dayDates="DTSTART;VALUE=DATE:20261003\r\nDTEND;VALUE=DATE:20261006"
            val cases=listOf(timed to timed.copy(end=timed.end.plusSeconds(3600)),timed to day,day to timed,
                timed to timed.copy(zone=ZoneId.of("Europe/Paris")),timed to timed.copy(zone=ZoneId.of("Etc/UTC")),
                timed to timed.copy(start=timed.start.plusSeconds(3600),end=timed.end.plusSeconds(7200),zone=ZoneId.of("Europe/Paris")),
                timed to timed, timed to timed)
            for((index,pair) in cases.withIndex()) {
                stage="PERSONAL_CASE_$index"
                val (before,after)=pair
                val clear=if(before is EventTime.AllDay) originalClear.replace(oldDates,dayDates) else originalClear
                val row=source(clear,before)
                ProtonPersonalEventWire.qualify(row)
                val baseline=decoder.decodePage(session.userId,scope.calendar.value,bootstrap,listOf(row),uid).also { check(it.deferred.isEmpty()) }.changes.single()
                check(baseline.content.time == before && baseline.content.details == EventDetails(listOf(EventReminder(10))) && baseline.content.colorRgb == 0xabcd12)
                val replacement = when(index) {
                    6 -> baseline.content.copy(details = EventDetails(listOf(EventReminder(0), EventReminder(20))))
                    7 -> baseline.content.copy(colorRgb = 0x123456)
                    else -> baseline.content.copy(title="Combined title",description="Combined notes",location="Combined place",time=after)
                }
                val intent=LocalEditIntent(MutationId("synthetic-personal-$index"),scope,EVENT_FORMAT,baseline,replacement)
                val prepared=decoder.prepareAndSealPersonalEdit(session,intent,uid,timestamp,metadata,bootstrap,row,protector())
                check(if(index < 6) prepared.requiredConsent.containsAll(setOf(PersonalEditField.TITLE,PersonalEditField.TEXT,PersonalEditField.TIME))
                    else prepared.requiredConsent == setOf(if(index == 6) PersonalEditField.REMINDERS else PersonalEditField.COLOR))
                val fragments=ProtonPersonalPreparationCodec(protector()).openLocal(prepared)
                check(fragments.originalRow == row && fragments.originalRow.string("SharedKeyPacket") == originalRow.string("SharedKeyPacket"))
                val nextClear=fragments.clearSharedPart.string("Data");val nextSecret=decrypt(fragments.encryptedSharedPart)
                check(nextClear.contains("SEQUENCE:8\r\n") && !nextClear.contains("SEQUENCE:9\r\n"))
                check(nextClear.contains("LAST-MODIFIED:20261003T093000Z\r\n"))
                for(text in listOf(nextClear,nextSecret)) check(text.contains("DTSTAMP:20261003T093000Z\r\n"))
                check(nextClear.contains("X-CLEAR;X-PARAM=opaque:keep this  \r\n folded text\t\r\n"))
                check(nextSecret.contains("X-SECRET;X-PARAM=opaque:first\r\n") && nextSecret.contains("X-SECRET;X-PARAM=opaque:second  \r\n folded text\t\r\n"))
                check(publicKey.verifyText(crypto,nextClear,fragments.clearSharedPart.string("Signature")))
                check(publicKey.verifyText(crypto,nextSecret,fragments.encryptedSharedPart.string("Signature")))
                val codec=ProtonPersonalUpdateRequestCodec(protector())
                val request=codec.seal(MutationId("synthetic-personal-request-$index"),prepared,requestContext)
                val opened=ProtonPersonalUpdateRequestCodec(protector()).open(request)
                val event=(opened.request.array("Events").single() as JsonObject).obj("Event")
                val expectedNotifications = if(index == 6) Json.parseToJsonElement("""[{"Type":0,"Trigger":"-PT1H"},{"Type":1,"Trigger":"PT0S"},{"Type":1,"Trigger":"-PT20M"}]""") else notifications
                val expectedColor = JsonPrimitive(if(index == 7) "#123456" else "#aBcD12")
                check(event["Notifications"] == expectedNotifications && event["Color"] == expectedColor)
                personalEventUpdateOnlyBytes(opened.request.toString()).fill(0)
                val readback=JsonObject(row+temporal(after)+buildJsonObject {
                    put("SharedEvents",JsonArray(listOf(fragments.clearSharedPart,fragments.encryptedSharedPart)));put("ModifyTime",3)
                    put("Notifications",expectedNotifications); put("Color",expectedColor)
                })
                var decoded=0
                val reader=ProtonPersonalUpdateReadbackVerifier(codec,CreateEventVerifier { observed,calendar,observedBootstrap,actual,expectedUid,policy ->
                    decoded++;decoder.decodeCreated(observed,calendar,observedBootstrap,actual,expectedUid,policy)
                })
                val verified=checkNotNull(reader.verify(session,request,requestContext,bootstrap,readback))
                check(verified.content == intent.content && verified.revision == readback.revision(bootstrap))
                check(reader.verify(session,request,requestContext,bootstrap,JsonObject(readback+("Color" to JsonPrimitive("#ABCD12")))) == null)
                check(reader.verify(session,request,requestContext,bootstrap,JsonObject(readback+("Notifications" to JsonArray(emptyList())))) == null)
                check(decoded == 1)
                // Identical request bytes with a forged shared signature cannot become success.
                val forged=JsonObject(fragments.encryptedSharedPart+("Signature" to fragments.clearSharedPart.getValue("Signature")))
                val tampered=JsonObject(readback+("SharedEvents" to JsonArray(listOf(fragments.clearSharedPart,forged))))
                check(decoder.decodeCreated(session,scope.calendar.value,bootstrap,tampered,uid,CreatedEventReadbackPolicy.PERSONAL_EDIT_V2) == null)
            }
            return "RESULT=SUCCESS"
        } catch(cancelled: CancellationException) { throw cancelled }
          catch(_: Exception) { return "RESULT=FAILURE STAGE=$stage" }
    }
}
