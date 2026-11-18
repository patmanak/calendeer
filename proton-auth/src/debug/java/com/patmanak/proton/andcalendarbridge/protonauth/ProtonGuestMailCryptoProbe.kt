package com.patmanak.proton.andcalendarbridge.protonauth

import biweekly.Biweekly
import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.util.Base64
import kotlinx.serialization.json.*
import me.proton.core.crypto.android.context.AndroidCryptoContext
import me.proton.core.crypto.common.keystore.PlainByteArray
import me.proton.core.crypto.common.keystore.encrypt
import me.proton.core.crypto.common.pgp.*
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.*
import me.proton.core.key.domain.entity.key.*
import me.proton.core.user.domain.entity.*

/** Entirely synthetic, including both keys. Only stage names leave the probe. */
object ProtonGuestMailCryptoProbe {
    @Suppress("DEPRECATION")
    fun verify(): String {
        var stage = "GENERATE"
        return try {
            val crypto = AndroidCryptoContext()
            val pgp = crypto.pgpCrypto
            fun synthetic(name: String): UserAddress {
                val password = pgp.generateNewToken()
                try {
                    val armored = pgp.generateNewPrivateKey(name, "example.invalid", password)
                    val privateKey = PrivateKey(armored, true,
                        passphrase = PlainByteArray(password).encrypt(crypto.keyStoreCrypto))
                    val id = AddressId(name)
                    return UserAddress(UserId(name), id, "$name@example.invalid", canSend = true,
                        canReceive = true, enabled = true, order = 0, keys = listOf(UserAddressKey(id, 1, 3,
                            active = true, keyId = KeyId(name), privateKey = privateKey)), signedKeyList = null)
                } finally { password.fill(0) }
            }
            val sender = synthetic("organizer")
            val receiver = synthetic("guest")
            val owner = EventPerson(sender.email)
            val guest = EventAttendee(EventPerson(receiver.email), AttendanceRole.REQUIRED, AttendanceStatus.NEEDS_ACTION)
            val empty = EventDetails(organizer = owner)
            val invited = empty.copy(attendees = listOf(guest))
            val senderPublic = sender.keys.single().privateKey.publicKey(crypto)
            val receiverPublic = receiver.keys.single().privateKey.publicKey(crypto)
            val address = PublicAddress(receiver.email, Recipient.Internal.value, null,
                listOf(PublicAddressKey(receiver.email, 3, receiverPublic)), null, null)
            val recipient = requireNotNull(internalGuestMailRecipient(receiver.email, address,
                GuestContactPreferences.DEFAULTS, "text/plain"))
            fun decode(value: String) = Base64.getDecoder().decode(value)
            val body = "Synthetic guest delivery."
            for (method in listOf("REQUEST", "CANCEL")) {
                stage = method + "_PREPARE"
                val add = method == "REQUEST"
                val source = (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Fixture//EN", "BEGIN:VEVENT",
                    "UID:synthetic", "DTSTAMP:20261101T090000Z", "SEQUENCE:1", "DTSTART:20261118T100000Z",
                    "DTEND:20261118T110000Z", "ORGANIZER:mailto:" + sender.email) +
                    (if (add) emptyList() else listOf("ATTENDEE:mailto:" + receiver.email)) +
                    listOf("END:VEVENT", "END:VCALENDAR")).joinToString("\r\n", postfix = "\r\n")
                val attachment = requireNotNull(prepareGuestAttachment(source, "synthetic", owner,
                    if (add) empty else invited, if (add) invited else empty, "fixture-shared",
                    if (add) ByteArray(32) { 43 } else null, Instant.parse("2026-11-17T09:00:00Z"),
                    mapOf(receiver.email to receiver.email)))
                attachment.use {
                    val expected = requireNotNull(it.bytesForEncryption())
                    try {
                        stage = method + "_ENCRYPT"
                        require(prepareGuestMailPackage(crypto, receiver, recipient, it, "Fixture", body) == null)
                        val encoded = requireNotNull(prepareGuestMailPackage(crypto, sender, recipient, it,
                            "Fixture", body)).encodedForSealing()
                        val wire = Json.parseToJsonElement(encoded.toString(Charsets.UTF_8)).jsonObject
                        stage = method + "_SHAPE"
                        require(wire.getValue("AutoSaveContacts").jsonPrimitive.int == 0)
                        val message = wire.getValue("Message").jsonObject
                        require(message.getValue("ToList").jsonArray.single().jsonObject.string("Address") == receiver.email)
                        require(message.getValue("CCList").jsonArray.isEmpty() && message.getValue("BCCList").jsonArray.isEmpty())
                        val mail = wire.getValue("Packages").jsonArray.single().jsonObject
                        require(mail.getValue("Type").jsonPrimitive.int == 1 && "BodyKey" !in mail && "AttachmentKeys" !in mail)
                        val addresses = mail.getValue("Addresses").jsonObject
                        require(addresses.keys == setOf(receiver.email))
                        val packets = addresses.getValue(receiver.email).jsonObject
                        require(packets.getValue("Signature").jsonPrimitive.int == 1)
                        val blob = message.getValue("Attachments").jsonArray.single().jsonObject
                        require(blob.string("Filename") == "invite.ics" && blob.string("MIMEType") == "text/calendar; method=$method")
                        stage = method + "_BODY"
                        receiver.useKeys(crypto) {
                            decryptSessionKey(decode(packets.string("BodyKeyPacket"))).use { key ->
                                val clear = pgp.decryptAndVerifyData(decode(mail.string("Body")), key, listOf(senderPublic.key))
                                require(clear.status == VerificationStatus.Success && clear.data.contentEquals(body.toByteArray()))
                            }
                            stage = method + "_ATTACHMENT"
                            val parts = fixturePackets(decode(blob.string("Contents")))
                            stage = method + "_PACKET_SHAPE"
                            require(parts.map { p -> p.first } in listOf(listOf(1, 18, 2), listOf(1, 20, 2)))
                            val wrapped = packets.getValue("AttachmentKeyPackets").jsonArray.single().jsonPrimitive.content
                            stage = method + "_UNWRAP"
                            decryptSessionKey(decode(wrapped)).use { key ->
                                stage = method + "_DECRYPT"
                                val clear = pgp.decryptData(parts[1].second, key)
                                try {
                                    stage = method + "_PLAINTEXT"
                                    require(clear.contentEquals(expected))
                                    stage = method + "_SIGNATURE"
                                    val signature = pgp.getArmored(parts[2].second, PGPHeader.Signature)
                                    require(pgp.verifyData(clear, signature, senderPublic.key))
                                    stage = method + "_METHOD"
                                    require(Biweekly.parse(clear.toString(Charsets.UTF_8)).first().method.value == method)
                                } finally { clear.fill(0) }
                            }
                        }
                    } finally { expected.fill(0) }
                }
            }
            "VERIFIED"
        } catch (_: Exception) { stage }
    }

    // Split the bounded fixture packets, preserving partial-body length headers in the extracted packet.
    // Core decrypts and verifies the extracted packets. This reader is debug-only.
    private fun fixturePackets(bytes: ByteArray): List<Pair<Int, ByteArray>> {
        var pos = 0
        fun octet(): Int = bytes[pos++].toInt() and 255
        fun length4(): Int = (octet() shl 24) or (octet() shl 16) or (octet() shl 8) or octet()
        return buildList {
            while (pos < bytes.size) {
                val start = pos
                val header = octet()
                require(header and 128 != 0)
                val modern = header and 64 != 0
                val tag = if (modern) header and 63 else (header shr 2) and 15
                val size = if (modern) {
                    var first = octet()
                    while (first in 224..254) {
                        val chunkSize = 1 shl (first and 31)
                        require(chunkSize in 1..(bytes.size - pos))
                        pos += chunkSize
                        first = octet()
                    }
                    when (first) {
                        in 0..191 -> first
                        in 192..223 -> ((first - 192) shl 8) + octet() + 192
                        else -> length4()
                    }
                } else when (header and 3) {
                    0 -> octet()
                    1 -> (octet() shl 8) + octet()
                    2 -> length4()
                    else -> error("indeterminate fixture packet")
                }
                require(size in 0..(bytes.size - pos))
                pos += size
                add(tag to bytes.copyOfRange(start, pos))
            }
        }
    }
}
