// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
import me.proton.core.user.domain.entity.*

/** Synthetic keys only. A fabricated server read shape does not establish live CREATE compatibility. */
object ProtonEventPreparationCryptoProbe {
    suspend fun run(): String = withContext(Dispatchers.IO) {
        var stage = "GENERATE"
        try {
            val crypto = AndroidCryptoContext()
            val pgp = crypto.pgpCrypto
            val addressSecret = pgp.generateNewToken()
            var calendarSecret = ByteArray(0)
            try {
                calendarSecret = pgp.generateNewToken()
                val addressArmored = pgp.generateNewPrivateKey("synthetic", "example.invalid", addressSecret)
                val calendarArmored = pgp.generateNewPrivateKey("calendar", "example.invalid", calendarSecret)
                val addressKey = PrivateKey(addressArmored, true, passphrase = PlainByteArray(addressSecret).encrypt(crypto.keyStoreCrypto))
                val calendarKey = PrivateKey(calendarArmored, true, passphrase = PlainByteArray(calendarSecret).encrypt(crypto.keyStoreCrypto))
                val user = UserId("synthetic-user")
                val addressId = AddressId("synthetic-address")
                val email = "synthetic@example.invalid"
                val address = UserAddress(user, addressId, email, canSend = true, canReceive = true, enabled = true,
                    order = 0, keys = listOf(UserAddressKey(addressId, 1, 3, active = true,
                        keyId = KeyId("synthetic-key"), privateKey = addressKey)), signedKeyList = null)
                val passphraseText = String(calendarSecret, Charsets.UTF_8)
                val memberPacket = address.useKeys(crypto) { buildJsonObject {
                    put("MemberID", "synthetic-member"); put("Passphrase", encryptText(passphraseText))
                    put("Signature", signText(passphraseText))
                } }
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
                    putJsonObject("CalendarSettings") {
                        put("CalendarID", "synthetic-calendar")
                        put("DefaultPartDayNotifications", JsonArray(emptyList()))
                        put("DefaultFullDayNotifications", JsonArray(emptyList()))
                    }
                }
                val encryptor = ProtonSimpleEventEncryptor(crypto)
                val decoder = ProtonEventDecryptor(crypto) { listOf(address) }
                val stamp = Instant.parse("2026-09-29T10:00:00Z")
                val times = listOf<EventTime>(EventTime.Timed(Instant.parse("2027-03-28T00:30:00Z"),
                    Instant.parse("2027-03-28T01:30:00Z"), ZoneId.of("Europe/Paris")),
                    EventTime.AllDay(LocalDate.parse("2027-03-28"), LocalDate.parse("2027-03-30")))
                for (time in times) {
                    val content = EventContent("Création; café, \\" + "é".repeat(100), time,
                        "Description\nLigne suivante", "Lieu de test")
                    stage = "PREPARE"
                    val prepared = encryptor.prepare("synthetic-stable@calendeer.invalid", stamp, content,
                        addressKey, calendarKey.publicKey(crypto))
                    check(prepared.format == AdapterFormat("proton-calendar-simple-create", 1))
                    check(!prepared.toString().contains(content.title))
                    val requestParts = prepared.event.array("SharedEventContent").map { it as JsonObject }
                    check(requestParts.map { it.number("Type") } == listOf(2L, 3L))
                    check(requestParts.all { it.string("Author").isEmpty() })
                    check(!requestParts[0].string("Data").contains("SUMMARY:"))
                    check(!requestParts[1].string("Data").contains(content.title))
                    check(prepared.event.keys == setOf("Permissions", "IsOrganizer", "SharedKeyPacket", "SharedEventContent"))
                    // Synthetic service normalization: author resolution, response field names and identity.
                    // No request was sent and this is not an observed server response.
                    val row = buildJsonObject {
                        put("ID", "synthetic-event"); put("CalendarID", "synthetic-calendar"); put("ModifyTime", 1)
                        put("SharedKeyPacket", prepared.event.getValue("SharedKeyPacket"))
                        put("SharedEvents", JsonArray(requestParts.map { JsonObject(it + ("Author" to JsonPrimitive(email))) }))
                        put("CalendarEvents", JsonArray(emptyList())); put("AttendeesEvents", JsonArray(emptyList()))
                        put("Notifications", JsonNull)
                    }
                    stage = "ROUND_TRIP"
                    val actual = decoder.decrypt(user, "synthetic-calendar", bootstrap, listOf(row)).single().content
                    check(actual.copy(description = actual.description.replace("\r\n", "\n"), details = null) == content)
                    check(actual.details == EventDetails())
                    for (index in requestParts.indices) {
                        stage = if (index == 0) "CLEAR_SIGNATURE" else "ENCRYPTED_SIGNATURE"
                        val parts = row.array("SharedEvents").map { it as JsonObject }.toMutableList()
                        parts[index] = JsonObject(parts[index] + ("Signature" to JsonPrimitive(addressKey.signText(crypto, "different"))))
                        val tampered = decoder.decodePage(user, "synthetic-calendar", bootstrap,
                            listOf(JsonObject(row + ("SharedEvents" to JsonArray(parts)))))
                        check(tampered.changes.isEmpty() && tampered.deferred.single().reason == DeferredEventReason.UNVERIFIED_CONTENT)
                    }
                }
                "RESULT=SUCCESS"
            } finally { addressSecret.fill(0); calendarSecret.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { "RESULT=FAILURE STAGE=$stage" }
    }
}
