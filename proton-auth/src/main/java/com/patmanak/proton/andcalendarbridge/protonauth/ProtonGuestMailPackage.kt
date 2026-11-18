// SPDX-License-Identifier: GPL-3.0-only
// Internal-recipient branch based on Proton Calendar SendEmailDirect and GenerateEmailPackageUseCase.
package com.patmanak.proton.andcalendarbridge.protonauth

import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.crypto.common.pgp.*
import me.proton.core.key.domain.*
import me.proton.core.user.domain.entity.UserAddress

/** In-memory Mail request for sealing under a durable operation identity. No network capability. */
internal class ProtonGuestMailPackage(private val wire: JsonObject) {
    fun encodedForSealing(): ByteArray = wire.toString().toByteArray(Charsets.UTF_8)
    override fun toString() = "ProtonGuestMailPackage(redacted)"
}

internal fun prepareGuestMailPackage(crypto: CryptoContext, sender: UserAddress, recipient: InternalGuestMailRecipient,
    attachment: ProtonGuestAttachment, subject: String, body: String): ProtonGuestMailPackage? {
    if (sender.email != attachment.organizerEmail || sender.email.equals(recipient.address, true) ||
        !sender.enabled || !sender.canSend || attachment.recipient != recipient.address ||
        subject.length !in 1..512 || subject.any { it.isISOControl() } || body.length !in 1..65536 ||
        !Charsets.UTF_8.newEncoder().canEncode(subject) || !Charsets.UTF_8.newEncoder().canEncode(body)) return null
    val signing = sender.keys.singleOrNull { it.active && it.privateKey.isPrimary && it.privateKey.isActive } ?: return null
    if (!signing.privateKey.canVerify || !signing.privateKey.canEncrypt) return null
    val bytes = attachment.bytesForEncryption() ?: return null
    try {
        val pgp = crypto.pgpCrypto
        val senderPublic = signing.privateKey.publicKey(crypto)
        if (listOf(recipient.key, senderPublic).any { pgp.isKeyExpired(it.key) || pgp.isKeyRevoked(it.key) }) return null
        fun encode(data: ByteArray): String = Base64.getEncoder().encodeToString(data)
        fun address(email: String, name: String) = buildJsonObject { put("Address", email); put("Name", name) }
        return sender.useKeys(crypto) {
            val attachmentPackets = encryptData(bytes).split(pgp)
            val signature = getUnarmored(signData(bytes))
            val senderAttachment = encode(attachmentPackets.keyPacket() + attachmentPackets.dataPacket() + signature)
            val signedBody = encryptAndSignText(body)
            val bodyPackets = signedBody.split(pgp)
            fun wrapRecipientKey(packet: ByteArray) = decryptSessionKey(packet).use {
                encode(recipient.key.encryptSessionKey(crypto, it))
            }
            val recipientAttachmentKey = wrapRecipientKey(attachmentPackets.keyPacket())
            val recipientBodyKey = wrapRecipientKey(bodyPackets.keyPacket())
            val email = recipient.address
            ProtonGuestMailPackage(buildJsonObject {
                put("AutoSaveContacts", 0)
                putJsonArray("AttachmentKeys") { }
                putJsonObject("Message") {
                    put("Subject", subject)
                    put("Sender", address(sender.email, sender.displayName?.takeIf { it.isNotBlank() } ?: sender.email))
                    putJsonArray("ToList") { add(address(email, email)) }
                    putJsonArray("CCList") { }
                    putJsonArray("BCCList") { }
                    put("MIMEType", "text/plain")
                    put("Body", signedBody)
                    putJsonArray("Attachments") { add(buildJsonObject {
                        put("Filename", attachment.fileName)
                        put("MIMEType", attachment.contentType)
                        put("Contents", senderAttachment)
                    }) }
                }
                putJsonArray("Packages") { add(buildJsonObject {
                    put("Type", 1)
                    put("MIMEType", "text/plain")
                    put("Body", encode(bodyPackets.dataPacket()))
                    putJsonObject("Addresses") { putJsonObject(email) {
                        put("Type", 1)
                        put("Signature", 1)
                        put("BodyKeyPacket", recipientBodyKey)
                        putJsonArray("AttachmentKeyPackets") { add(recipientAttachmentKey) }
                    } }
                }) }
            })
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { return null }
    finally { bytes.fill(0) }
}
