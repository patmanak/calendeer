// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.proton.core.crypto.android.context.AndroidCryptoContext
import me.proton.core.crypto.common.keystore.PlainByteArray
import me.proton.core.crypto.common.keystore.encrypt
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.*
import me.proton.core.key.domain.entity.key.KeyId
import me.proton.core.key.domain.entity.key.PrivateKey
import me.proton.core.network.domain.session.SessionId
import me.proton.core.user.domain.entity.*

/** Device-only native crypto qualification. No network, session, database or provider is accessed. */
object ProtonEventCryptoProbe {
    suspend fun run(): String = withContext(Dispatchers.IO) {
        var stage = "GENERATE"
        try {
            val crypto = AndroidCryptoContext()
            val pgp = crypto.pgpCrypto
            val addressSecret = pgp.generateNewToken()
            val calendarSecret = pgp.generateNewToken()
            try {
                val addressArmored = pgp.generateNewPrivateKey("synthetic", "example.invalid", addressSecret)
                val calendarArmored = pgp.generateNewPrivateKey("calendar", "example.invalid", calendarSecret)
                val addressKey = PrivateKey(addressArmored, true, passphrase = PlainByteArray(addressSecret).encrypt(crypto.keyStoreCrypto))
                val addressId = AddressId("synthetic-address")
                val user = UserId("synthetic-user")
                val email = "synthetic@example.invalid"
                val address = UserAddress(user, addressId, email, canSend = true, canReceive = true, enabled = true,
                    order = 0, keys = listOf(UserAddressKey(addressId, 1, 3, active = true,
                        keyId = KeyId("synthetic-key"), privateKey = addressKey)), signedKeyList = null)
                val passphraseText = String(calendarSecret, Charsets.UTF_8)
                val memberPacket = address.useKeys(crypto) {
                    buildJsonObject {
                        put("MemberID", "synthetic-member")
                        put("Passphrase", encryptText(passphraseText))
                        put("Signature", signText(passphraseText))
                    }
                }
                val bootstrap = buildJsonObject {
                    putJsonObject("Passphrase") {
                        put("ID", "synthetic-passphrase"); put("CalendarID", "synthetic-calendar"); put("Flags", 1)
                        put("MemberPassphrases", JsonArray(listOf(memberPacket)))
                    }
                    putJsonArray("Members") { add(buildJsonObject {
                        put("ID", "synthetic-member"); put("CalendarID", "synthetic-calendar")
                        put("AddressID", addressId.id); put("Email", email)
                    }) }
                    putJsonArray("Keys") { add(buildJsonObject {
                        put("ID", "synthetic-calendar-key"); put("CalendarID", "synthetic-calendar")
                        put("Flags", 3); put("PassphraseID", "synthetic-passphrase"); put("PrivateKey", calendarArmored)
                    }) }
                }
                val shared = ics("DTSTART:20261001T100000Z\nDTEND:20261001T110000Z\nSUMMARY:Synthetic title")
                val local = ics("DESCRIPTION:Synthetic description")
                val attendees = ics("LOCATION:Synthetic location")
                val decoder = ProtonEventDecryptor(crypto) { listOf(address) }
                stage = "BOOTSTRAP"
                check(decoder.decrypt(user, "synthetic-calendar", bootstrap, emptyList()).isEmpty())
                for (useAddressPacket in listOf(false, true)) {
                    stage = if (useAddressPacket) "ADDRESS_PACKET" else "CALENDAR_PACKET"
                    pgp.generateNewSessionKey().use { sharedKey -> pgp.generateNewSessionKey().use { localKey ->
                        fun part(text: String, key: me.proton.core.crypto.common.pgp.SessionKey) = buildJsonObject {
                            put("Type", 3); put("Author", email)
                            put("Data", Base64.getEncoder().encodeToString(pgp.encryptData(text.toByteArray(), key)))
                            put("Signature", addressKey.signData(crypto, text.toByteArray()))
                        }
                        val sharedPart = part(shared, sharedKey)
                        val row = buildJsonObject {
                            put("ID", "synthetic-event"); put("CalendarID", "synthetic-calendar"); put("ModifyTime", 1)
                            put("CalendarKeyPacket", Base64.getEncoder().encodeToString(pgp.encryptSessionKey(localKey, pgp.getPublicKey(calendarArmored))))
                            put(if (useAddressPacket) "AddressKeyPacket" else "SharedKeyPacket",
                                Base64.getEncoder().encodeToString(pgp.encryptSessionKey(sharedKey, pgp.getPublicKey(if (useAddressPacket) addressArmored else calendarArmored))))
                            if (useAddressPacket) put("AddressID", addressId.id)
                            put("SharedEvents", JsonArray(listOf(sharedPart)))
                            put("CalendarEvents", JsonArray(listOf(part(local, localKey))))
                            put("AttendeesEvents", JsonArray(listOf(part(attendees, sharedKey))))
                            put("Notifications", JsonArray(emptyList()))
                        }
                        val actual = decoder.decrypt(user, "synthetic-calendar", bootstrap, listOf(row)).single().content
                        check(actual.title == "Synthetic title" && actual.description == "Synthetic description" && actual.location == "Synthetic location")
                        check((actual.time as EventTime.Timed).start == Instant.parse("2026-10-01T10:00:00Z"))
                        check(actual.details == EventDetails())
                        stage = "SIGNED_DETAILS"
                        val detailPart = part(ics("LOCATION:Synthetic location\nORGANIZER;CN=Host:mailto:host@example.invalid\nATTENDEE;CN=Guest;X-PM-TOKEN=fixture-token;PARTSTAT=NEEDS-ACTION:mailto:guest@example.invalid"), sharedKey)
                        val detailsRow = JsonObject(row + mapOf(
                            "AttendeesEvents" to JsonArray(listOf(detailPart)),
                            "Notifications" to Json.parseToJsonElement("""[{"Type":1,"Trigger":"-PT15M"},{"Type":0,"Trigger":"-PT30M"}]"""),
                            "AttendeesInfo" to Json.parseToJsonElement("""{"Attendees":[{"Token":"fixture-token","Status":3}],"MoreAttendees":0}""")))
                        val expectedDetails = EventDetails(listOf(EventReminder(15)), EventPerson("host@example.invalid", "Host"),
                            listOf(EventAttendee(EventPerson("guest@example.invalid", "Guest"), AttendanceRole.REQUIRED, AttendanceStatus.ACCEPTED)))
                        check(decoder.decrypt(user, "synthetic-calendar", bootstrap, listOf(detailsRow)).single().content.details == expectedDetails)
                        val badDetailPart = JsonObject(detailPart + ("Signature" to JsonPrimitive(addressKey.signData(crypto, "different".toByteArray()))))
                        val badDetails = decoder.decodePage(user, "synthetic-calendar", bootstrap,
                            listOf(JsonObject(detailsRow + ("AttendeesEvents" to JsonArray(listOf(badDetailPart))))))
                        check(badDetails.changes.isEmpty() && badDetails.deferred.single().reason == DeferredEventReason.UNVERIFIED_CONTENT)
                        val corruptPart = JsonObject(sharedPart + ("Signature" to JsonPrimitive(addressKey.signData(crypto, "different".toByteArray()))))
                        val corruptRow = JsonObject(row + ("SharedEvents" to JsonArray(listOf(corruptPart))))
                        stage = "TAMPERING"
                        var refused = false
                        try { decoder.decrypt(user, "synthetic-calendar", bootstrap, listOf(corruptRow)) }
                        catch (failure: RemoteImportException) { refused = failure.reason == RemoteImportFailure.UNSUPPORTED }
                        check(refused)
                        stage = "STAGED_OFFLINE"
                        val invalid = JsonObject(row + mapOf("ID" to JsonPrimitive("synthetic-unsupported"), "Color" to JsonPrimitive("invalid")))
                        val last = JsonObject(row + ("ID" to JsonPrimitive("synthetic-last")))
                        var networkForbidden = false
                        val remote = object : EventImportRemote {
                            override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage {
                                check(!networkForbidden) { "NETWORK_FORBIDDEN" }
                                return EncryptedEventPage(listOf(detailsRow, invalid, last), false)
                            }
                            override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject {
                                check(!networkForbidden) { "NETWORK_FORBIDDEN" }
                                return bootstrap
                            }
                        }
                        val session = CatalogSession(user, SessionId("synthetic-session"))
                        val scope = CalendarScope(AccountId(user.id), CalendarId("synthetic-calendar"), 1)
                        val request = PageRequest(PageCheckpoint(scope, EVENT_FORMAT), 20)
                        val receipt = ProtonEventImportAdapter({ session }, remote, decoder,
                            ProtonEventPayloadProtector(crypto.keyStoreCrypto)).receive(request)
                        check(!receipt.protectedPayload.contains("synthetic-calendar"))
                        networkForbidden = true
                        // Rebuild the crypto context/codec/decoder: only persisted receipt and local keys are retained.
                        val restartedCrypto = AndroidCryptoContext()
                        val restarted = ProtonEventImportAdapter({ session }, remote,
                            ProtonEventDecryptor(restartedCrypto) { listOf(address) }, ProtonEventPayloadProtector(restartedCrypto.keyStoreCrypto))
                        val decoded = restarted.decode(receipt)
                        check(decoded.page.changes.size == 2 && decoded.deferred.size == 1)
                        check((decoded.page.changes.first() as RemoteChange.Upsert).content.details == expectedDetails)
                        check(decoded.deferred.single().reason == DeferredEventReason.UNSUPPORTED_CONTENT)
                        check(restarted.retryDeferred(scope, decoded.deferred.single()) == null)
                        stage = "STAGED_UNVERIFIED"
                        val unverified = decoder.decodePage(user, "synthetic-calendar", bootstrap, listOf(corruptRow))
                        check(unverified.changes.isEmpty() && unverified.deferred.single().reason == DeferredEventReason.UNVERIFIED_CONTENT)
                        stage = "STAGED_INDEPENDENT_BUDGET"
                        val largeUnsupported = buildJsonObject {
                            put("ID", "synthetic-large-unsupported"); put("CalendarID", "synthetic-calendar"); put("ModifyTime", 1)
                            putJsonArray("SharedEvents") { add(buildJsonObject {
                                put("Type", 0); put("Data", "x".repeat(EventTextBudget.MAX_EVENT_BYTES - 50))
                            }) }
                            put("CalendarEvents", JsonArray(emptyList())); put("AttendeesEvents", JsonArray(emptyList()))
                        }
                        val afterLarge = decoder.decodePage(user, "synthetic-calendar", bootstrap, listOf(largeUnsupported, row))
                        check(afterLarge.deferred.size == 1 && afterLarge.changes.map { it.eventId } == listOf(EventId("synthetic-event")))
                        stage = "STAGED_KEYS_MISSING"
                        val missingKeys = ProtonEventImportAdapter({ session }, remote,
                            ProtonEventDecryptor(restartedCrypto) { emptyList() }, ProtonEventPayloadProtector(restartedCrypto.keyStoreCrypto))
                        var blocked = false
                        try { missingKeys.decode(receipt) } catch (failure: RemoteImportException) { blocked = failure.reason == RemoteImportFailure.LOCAL_KEYS }
                        check(blocked)
                    } }
                }
                "RESULT=SUCCESS"
            } finally { addressSecret.fill(0); calendarSecret.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { "RESULT=FAILURE STAGE=$stage" }
    }
    private fun ics(properties: String) = "BEGIN:VCALENDAR\nVERSION:2.0\nPRODID:-//Synthetic fixture//EN\nBEGIN:VEVENT\nUID:synthetic\n$properties\nEND:VEVENT\nEND:VCALENDAR\n".replace("\n", "\r\n")
}
