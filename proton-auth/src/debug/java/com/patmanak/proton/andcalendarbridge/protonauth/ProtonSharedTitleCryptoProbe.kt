// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
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
import me.proton.core.key.domain.entity.key.PrivateKeyRing
import me.proton.core.key.domain.entity.key.PublicKeyRing
import me.proton.core.key.domain.entity.keyholder.KeyHolderContext
import me.proton.core.network.domain.session.SessionId
import me.proton.core.user.domain.entity.*

/** Native synthetic shared signatures only. No account, network, Provider or UPDATE payload. */
object ProtonSharedTitleCryptoProbe {
    suspend fun runBinary(): String = run(textSignatures = false)
    suspend fun runText(): String = run(textSignatures = true)
    suspend fun runReencryptedBinary(): String = run(textSignatures = false, reencrypted = true)
    suspend fun runReencryptedText(): String = run(textSignatures = true, reencrypted = true)

    private suspend fun run(textSignatures: Boolean, reencrypted: Boolean = false): String = withContext(Dispatchers.IO) {
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
                val addressKey = PrivateKey(addressArmored, true,
                    passphrase = PlainByteArray(addressSecret).encrypt(crypto.keyStoreCrypto))
                val user = UserId("synthetic-title-user")
                val addressId = AddressId("synthetic-title-address")
                val email = "synthetic-title@example.invalid"
                val address = UserAddress(user, addressId, email, canSend = true, canReceive = true, enabled = true,
                    order = 0, keys = listOf(UserAddressKey(addressId, 1, 3, active = true,
                        keyId = KeyId("synthetic-title-key"), privateKey = addressKey)), signedKeyList = null)
                val calendar = "synthetic-title-calendar"
                val uid = "synthetic-title@calendeer.invalid"
                val eventId = EventId("synthetic-title-event")
                val scope = CalendarScope(AccountId(user.id), CalendarId(calendar), 1)
                val session = CatalogSession(user, SessionId("synthetic-title-session"))
                val passphraseText = String(calendarSecret, Charsets.UTF_8)
                val memberPacket = address.useKeys(crypto) { buildJsonObject {
                    put("MemberID", "synthetic-title-member")
                    put("Passphrase", encryptText(passphraseText))
                    put("Signature", signText(passphraseText))
                } }
                val bootstrap = buildJsonObject {
                    putJsonObject("Passphrase") {
                        put("ID", "synthetic-title-passphrase"); put("CalendarID", calendar); put("Flags", 1)
                        put("MemberPassphrases", JsonArray(listOf(memberPacket)))
                    }
                    putJsonArray("Members") { add(buildJsonObject {
                        put("ID", "synthetic-title-member"); put("CalendarID", calendar)
                        put("AddressID", addressId.id); put("Email", email)
                        put("Flags", 1); put("Permissions", 16)
                    }) }
                    putJsonArray("Keys") { add(buildJsonObject {
                        put("ID", "synthetic-title-calendar-key"); put("CalendarID", calendar)
                        put("Flags", 3); put("PassphraseID", "synthetic-title-passphrase")
                        put("PrivateKey", calendarArmored)
                    }) }
                    putJsonObject("CalendarSettings") {
                        put("CalendarID", calendar)
                        put("DefaultPartDayNotifications", JsonArray(emptyList()))
                        put("DefaultFullDayNotifications", JsonArray(emptyList()))
                    }
                }
                val originalTitle = "Original title"
                val clear = ics(uid, "DTSTART:20261003T100000Z\r\nDTEND:20261003T110000Z\r\n" +
                    "X-CLEAR;X-PARAM=opaque:keep this  \r\n folded text\t\r\n")
                val prefix = icsPrefix(uid) + "X-SECRET;X-PARAM=opaque:first\r\n"
                val suffix = "X-SECRET;X-PARAM=opaque:second  \r\n folded text\t\r\n" +
                    "DESCRIPTION:Opaque\\ntext\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\n" +
                    "TRIGGER:-PT15M\r\nSUMMARY:Nested summary\r\nEND:VALARM\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
                val secret = prefix + "SUMMARY:$originalTitle\r\n" + suffix
                fun signature(text: String): String = if (textSignatures) addressKey.signText(crypto, text)
                    else addressKey.signData(crypto, text.toByteArray(Charsets.UTF_8))
                val alteredSignature = signature("Different synthetic plaintext")
                val row = pgp.generateNewSessionKey().use { key -> buildJsonObject {
                    put("ID", eventId.value); put("CalendarID", calendar); put("ModifyTime", 1)
                    put("UID", uid)
                    put("SharedKeyPacket", Base64.getEncoder().encodeToString(
                        pgp.encryptSessionKey(key, pgp.getPublicKey(calendarArmored))))
                    putJsonArray("SharedEvents") {
                        add(buildJsonObject {
                            put("Type", 2); put("Author", email); put("Data", clear)
                            put("Signature", signature(clear)); put("UnknownPartMetadata", "clear-opaque")
                        })
                        add(buildJsonObject {
                            put("Type", 3); put("Author", email)
                            put("Data", Base64.getEncoder().encodeToString(pgp.encryptData(secret.toByteArray(Charsets.UTF_8), key)))
                            put("Signature", signature(secret)); put("UnknownPartMetadata", "encrypted-opaque")
                        })
                    }
                    // These are deliberately opaque, not validated, authenticated or safe to upload.
                    put("CalendarEvents", Json.parseToJsonElement("""[{"Type":99,"Data":"opaque-calendar","Extra":[1,null,"x"]}]"""))
                    put("AttendeesEvents", Json.parseToJsonElement("""[{"Type":99,"Data":"opaque-attendee","Extra":{"Flag":true}}]"""))
                    put("Notifications", Json.parseToJsonElement("""[{"Type":1,"Trigger":"-PT10M","Opaque":7}]"""))
                    putJsonObject("OpaqueMetadata") { put("Value", "untouched"); put("Null", JsonNull) }
                } }
                val decoder = ProtonEventDecryptor(crypto) { check(it == user); listOf(address) }
                suspend fun open(candidate: JsonObject = row, context: JsonObject = bootstrap,
                    candidateScope: CalendarScope = scope, expectedId: EventId = eventId,
                    expectedRevision: Revision = candidate.revision(context), expectedUid: String = uid,
                    expectedTitle: String = originalTitle, engine: ProtonEventDecryptor = decoder): ProtonSharedTitleBasis =
                    engine.openSharedTitle(session, candidateScope, expectedId, expectedRevision, expectedUid,
                        expectedTitle, context, candidate)

                stage = "OPEN"
                val basis = open()
                check(basis.originalRow == row)
                check(basis.originalPlaintext.clearShared == clear && basis.originalPlaintext.encryptedShared == secret)
                check(!basis.toString().contains(uid) && !basis.toString().contains(originalTitle))
                stage = "PATCH"
                val newTitle = "Agenda; forêt, \\ " + "é🦌".repeat(20) + "\nLigne suivante"
                val draft = basis.patchTitle(newTitle)
                check(draft.originalRow == row)
                check(draft.patchedPlaintext.clearShared == clear)
                val patched = draft.patchedPlaintext.encryptedShared
                check(patched.startsWith(prefix) && patched.endsWith(suffix) && patched != secret)
                // Independently decode only the changed SUMMARY; every other span is checked raw.
                check(biweekly.Biweekly.parse(patched).first().events.single().summary.value.replace("\r\n", "\n") == newTitle)
                check(!draft.toString().contains(uid) && !draft.toString().contains(newTitle))
                check(basis.originalPlaintext.encryptedShared == secret)
                check(draft.originalRow.array("SharedEvents") == row.array("SharedEvents"))
                stage = "NO_OP"
                val noOp = basis.patchTitle(originalTitle)
                check(noOp.originalRow == row && noOp.patchedPlaintext.clearShared == clear && noOp.patchedPlaintext.encryptedShared == secret)

                if (reencrypted) {
                    val metadata = buildJsonObject { put("ID", calendar); put("Type", 0) }
                    suspend fun prepare(candidate: JsonObject = row, context: JsonObject = bootstrap,
                        candidateMetadata: JsonObject = metadata, candidateScope: CalendarScope = scope,
                        expectedId: EventId = eventId, expectedRevision: Revision = candidate.revision(context),
                        expectedUid: String = uid, expectedTitle: String = originalTitle,
                        replacement: String = newTitle, engine: ProtonEventDecryptor = decoder): ProtonReencryptedSharedTitle =
                        engine.prepareSharedTitle(session, candidateScope, expectedId, expectedRevision,
                            expectedUid, expectedTitle, replacement, candidateMetadata, context, candidate)

                    stage = "REENCRYPT"
                    val prepared = prepare()
                    val originalPart = row.array("SharedEvents").map { it as JsonObject }.single { it.number("Type") == 3L }
                    val part = prepared.encryptedSharedPart
                    check(prepared.changed && prepared.originalRow == row)
                    check(part.filterKeys { it != "Data" && it != "Signature" } ==
                        originalPart.filterKeys { it != "Data" && it != "Signature" })
                    check(part.string("Data") != originalPart.string("Data"))
                    check(part.string("Signature") != originalPart.string("Signature"))
                    check(prepared.originalRow.array("SharedEvents") == row.array("SharedEvents"))
                    check(prepared.originalRow.string("SharedKeyPacket") == row.string("SharedKeyPacket"))
                    check(!prepared.toString().contains(uid) && !prepared.toString().contains(newTitle))
                    check(prepared.originalRow !== prepared.originalRow && prepared.encryptedSharedPart !== prepared.encryptedSharedPart)

                    stage = "ORIGINAL_PACKET_DECRYPT"
                    val calendarKey = PrivateKey(calendarArmored, true,
                        passphrase = PlainByteArray(calendarSecret).encrypt(crypto.keyStoreCrypto))
                    val reopened = KeyHolderContext(crypto, PrivateKeyRing(crypto, listOf(calendarKey)),
                        PublicKeyRing(emptyList())).use { keys ->
                        val retainedKey = keys.decryptSessionKeyOrNull(Base64.getDecoder().decode(row.string("SharedKeyPacket")))
                            ?: error("Synthetic packet unavailable")
                        val bytes = retainedKey.use { key ->
                            keys.decryptDataOrNull(Base64.getDecoder().decode(part.string("Data")), key)
                                ?: error("Synthetic ciphertext unavailable")
                        }
                        try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
                    }
                    check(reopened == patched && reopened.startsWith(prefix) && reopened.endsWith(suffix))
                    check(biweekly.Biweekly.parse(reopened).first().events.single().summary.value.replace("\r\n", "\n") == newTitle)
                    stage = "NEW_SIGNATURE"
                    val addressPublicKey = addressKey.publicKey(crypto)
                    check(addressPublicKey.verifyText(crypto, reopened, part.string("Signature")))
                    check(!addressPublicKey.verifyData(crypto, reopened.toByteArray(Charsets.UTF_8), originalPart.string("Signature")) &&
                        !addressPublicKey.verifyText(crypto, reopened, originalPart.string("Signature")))
                    stage = "REENCRYPT_NO_OP"
                    val unchanged = prepare(replacement = originalTitle)
                    check(!unchanged.changed && unchanged.originalRow == row && unchanged.encryptedSharedPart == originalPart)
                    stage = "AUTHOR_CASE"
                    val authorCaseRow = changedPart(row, 1, "Author", JsonPrimitive(email.uppercase()))
                    val authorCase = prepare(candidate = authorCaseRow)
                    check(authorCase.encryptedSharedPart.string("Author") == email.uppercase())

                    stage = "REENCRYPT_SOURCE_BINDING"
                    refused { prepare(expectedId = EventId("foreign-event")) }
                    refused { prepare(expectedRevision = Revision("foreign-revision")) }
                    refused { prepare(expectedUid = "foreign@calendeer.invalid") }
                    refused { prepare(expectedTitle = "Stale title") }
                    refused { prepare(candidateScope = scope.copy(account = AccountId("foreign-account"))) }
                    refused { prepare(candidateScope = scope.copy(calendar = CalendarId("foreign-calendar"))) }
                    refused { prepare(candidate = changedPart(row, 1, "Signature", JsonPrimitive(alteredSignature))) }
                    refused { prepare(candidate = changedPart(row, 0, "Signature", JsonPrimitive(alteredSignature))) }
                    refused { prepare(candidate = changedPart(row, 1, "Author", null)) }
                    refused { prepare(candidate = changedPart(row, 1, "Author", JsonPrimitive("foreign@example.invalid"))) }
                    stage = "OWNED_WRITE_CONTEXT"
                    refused { prepare(candidateMetadata = JsonObject(metadata + ("Type" to JsonPrimitive(1)))) }
                    refused { prepare(candidateMetadata = JsonObject(metadata + ("ID" to JsonPrimitive("foreign-calendar")))) }
                    refused { prepare(candidateMetadata = JsonObject(metadata - "Type")) }
                    val member = bootstrap.array("Members").single() as JsonObject
                    for ((field, value) in listOf("Flags" to 0, "Permissions" to 0)) {
                        val blockedBootstrap = JsonObject(bootstrap + ("Members" to JsonArray(listOf(
                            JsonObject(member + (field to JsonPrimitive(value)))))))
                        refused { prepare(context = blockedBootstrap) }
                        refused { prepare(context = blockedBootstrap, replacement = originalTitle) }
                    }
                    refused { prepare(context = JsonObject(bootstrap + ("Members" to JsonArray(listOf(
                        JsonObject(member - "Permissions")))))) }
                    refused { prepare(engine = ProtonEventDecryptor(crypto) {
                        listOf(address.copy(email = "different@example.invalid"))
                    }) }
                    refused { prepare(engine = ProtonEventDecryptor(crypto) { listOf(address.copy(enabled = false)) }) }
                    refused { prepare(engine = ProtonEventDecryptor(crypto) { listOf(address.copy(canSend = false)) }) }
                    stage = "DIFFERENT_OWNED_WRITER"
                    val otherAddress = address.copy(addressId = AddressId("synthetic-other-address"),
                        email = "synthetic-other@example.invalid")
                    val otherMember = JsonObject(member + mapOf("AddressID" to JsonPrimitive(otherAddress.addressId.id),
                        "Email" to JsonPrimitive(otherAddress.email)))
                    refused { prepare(context = JsonObject(bootstrap + ("Members" to JsonArray(listOf(otherMember)))),
                        engine = ProtonEventDecryptor(crypto) { listOf(address, otherAddress) }) }
                    stage = "OWNED_SIGNING_KEYS"
                    val wrongSigningKey = PrivateKey(addressArmored, true,
                        passphrase = PlainByteArray("synthetic-wrong-passphrase".toByteArray()).encrypt(crypto.keyStoreCrypto))
                    refused(RemoteImportFailure.LOCAL_KEYS) { prepare(engine = ProtonEventDecryptor(crypto) {
                        listOf(address.copy(keys = listOf(UserAddressKey(addressId, 1, 3, active = true,
                            keyId = KeyId("synthetic-title-key"), privateKey = wrongSigningKey))))
                    }) }
                    refused(RemoteImportFailure.LOCAL_KEYS) { prepare(engine = ProtonEventDecryptor(crypto) { emptyList() }) }

                    stage = "PRIMARY_SIGNING_UNLOCK"
                    val otherArmored = pgp.generateNewPrivateKey("synthetic-signer", "example.invalid", addressSecret)
                    val unavailablePrimary = PrivateKey(otherArmored, true,
                        passphrase = PlainByteArray("synthetic-wrong-passphrase".toByteArray()).encrypt(crypto.keyStoreCrypto))
                    val availableSecondary = PrivateKey(addressArmored, false,
                        passphrase = PlainByteArray(addressSecret).encrypt(crypto.keyStoreCrypto))
                    val partlyAvailableAddress = address.copy(keys = listOf(
                        UserAddressKey(addressId, 1, 3, active = true, keyId = KeyId("synthetic-unavailable-primary"),
                            privateKey = unavailablePrimary),
                        UserAddressKey(addressId, 2, 1, active = true, keyId = KeyId("synthetic-available-secondary"),
                            privateKey = availableSecondary)))
                    val partlyAvailableDecoder = ProtonEventDecryptor(crypto) { listOf(partlyAvailableAddress) }
                    // The original secondary can open bootstrap/content, but cannot sign as primary.
                    check(open(engine = partlyAvailableDecoder).originalPlaintext.encryptedShared == secret)
                    refused(RemoteImportFailure.LOCAL_KEYS) { prepare(engine = partlyAvailableDecoder) }
                    val signingFreeNoOp = prepare(replacement = originalTitle, engine = partlyAvailableDecoder)
                    check(!signingFreeNoOp.changed && signingFreeNoOp.encryptedSharedPart == originalPart)

                    stage = "REENCRYPT_FROZEN_INPUTS"
                    val mutableMetadata = metadata.toMutableMap()
                    val mutableOpaque = row.obj("OpaqueMetadata").toMutableMap()
                    val mutableParts = row.array("SharedEvents").toMutableList()
                    val mutableRow = row.toMutableMap().apply {
                        put("OpaqueMetadata", JsonObject(mutableOpaque)); put("SharedEvents", JsonArray(mutableParts))
                    }
                    val mutableMembers = bootstrap.array("Members").toMutableList()
                    val mutableBootstrap = bootstrap.toMutableMap().apply { put("Members", JsonArray(mutableMembers)) }
                    var keyReads = 0
                    val mutatingDecoder = ProtonEventDecryptor(crypto) {
                        keyReads++
                        mutableMetadata.clear(); mutableOpaque.clear(); mutableParts.clear()
                        mutableMembers.clear(); mutableBootstrap.clear()
                        listOf(address)
                    }
                    val frozen = prepare(candidate = JsonObject(mutableRow), context = JsonObject(mutableBootstrap),
                        candidateMetadata = JsonObject(mutableMetadata), expectedRevision = row.revision(bootstrap), engine = mutatingDecoder)
                    check(keyReads == 1 && frozen.originalRow == row && frozen.changed)
                    val frozenPart = frozen.encryptedSharedPart
                    mutableRow.clear()
                    check(frozen.originalRow == row && frozen.encryptedSharedPart == frozenPart)

                    stage = "REENCRYPT_CANCELLATION"
                    val cancelled = CancellationException("Synthetic cancellation")
                    var propagated = false
                    try { prepare(engine = ProtonEventDecryptor(crypto) { throw cancelled }) }
                    catch (actual: CancellationException) { propagated = actual === cancelled }
                    check(propagated)
                    return@withContext "RESULT=SUCCESS"
                }

                stage = "FROZEN_INPUTS"
                val mutableMetadata = row.obj("OpaqueMetadata").toMutableMap()
                val mutableParts = row.array("SharedEvents").toMutableList()
                val mutableRow = row.toMutableMap().apply {
                    put("OpaqueMetadata", JsonObject(mutableMetadata)); put("SharedEvents", JsonArray(mutableParts))
                }
                val mutableSettings = bootstrap.obj("CalendarSettings").toMutableMap()
                val mutableBootstrap = bootstrap.toMutableMap().apply { put("CalendarSettings", JsonObject(mutableSettings)) }
                var addressReads = 0
                val mutatingDecoder = ProtonEventDecryptor(crypto) {
                    addressReads++
                    mutableMetadata["Value"] = JsonPrimitive("mutated-during-key-read")
                    mutableParts.clear()
                    mutableSettings["DefaultPartDayNotifications"] = JsonArray(listOf(JsonPrimitive("mutated")))
                    mutableBootstrap.clear()
                    listOf(address)
                }
                val frozen = open(JsonObject(mutableRow), JsonObject(mutableBootstrap),
                    expectedRevision = row.revision(bootstrap), engine = mutatingDecoder)
                check(addressReads == 1 && frozen.originalRow == row)
                mutableRow.clear(); mutableMetadata.clear(); mutableSettings.clear()
                check(frozen.originalRow == row && frozen.patchTitle(newTitle).originalRow == row)
                check(frozen.originalRow !== frozen.originalRow)

                stage = "SCOPE_BINDING"
                refused { open(candidateScope = scope.copy(account = AccountId("foreign-account"))) }
                refused { open(candidateScope = scope.copy(calendar = CalendarId("foreign-calendar"))) }
                refused { open(JsonObject(row + ("CalendarID" to JsonPrimitive("foreign-calendar")))) }
                refused { open(expectedId = EventId("foreign-event")) }
                refused { open(expectedRevision = Revision("foreign-revision")) }
                refused { open(expectedUid = "foreign@calendeer.invalid") }
                stage = "SIGNED_UID_BINDING"
                val foreignUid = "foreign@calendeer.invalid"
                refused { open(JsonObject(row + ("UID" to JsonPrimitive(foreignUid))), expectedUid = foreignUid) }
                stage = "STALE_TITLE"
                refused { open(expectedTitle = "Stale title") }
                stage = "PART_SHAPE"
                val parts = row.array("SharedEvents").map { it as JsonObject }
                refused { open(JsonObject(row + ("SharedEvents" to JsonArray(listOf(parts[0], parts[0], parts[1]))))) }
                refused { open(JsonObject(row + ("SharedEvents" to JsonArray(listOf(parts[0], parts[1], parts[1]))))) }
                refused { open(changedPart(row, 0, "Type", JsonPrimitive(0))) }
                refused { open(changedPart(row, 1, "Type", JsonPrimitive(0))) }
                stage = "ADDRESS_PACKET"
                refused { open(JsonObject(row + ("AddressKeyPacket" to JsonPrimitive("opaque-address-packet")))) }
                for (index in parts.indices) {
                    stage = if (index == 0) "CLEAR_SIGNATURE" else "ENCRYPTED_SIGNATURE"
                    refused { open(changedPart(row, index, "Signature", JsonPrimitive(alteredSignature))) }
                    stage = if (index == 0) "CLEAR_AUTHOR" else "ENCRYPTED_AUTHOR"
                    refused { open(changedPart(row, index, "Author", null)) }
                    refused { open(changedPart(row, index, "Author", JsonPrimitive("foreign@example.invalid"))) }
                }
                stage = "CLEAR_PLAINTEXT"
                refused { open(changedPart(row, 0, "Data", JsonPrimitive(clear.replace("keep this", "different text")))) }
                stage = "FOREIGN_LOCAL_KEYS"
                refused { open(engine = ProtonEventDecryptor(crypto) {
                    listOf(address.copy(userId = UserId("foreign-user")))
                }) }
                stage = "PASSPHRASE_SIGNATURE"
                val tamperedMemberPacket = JsonObject(memberPacket + ("Signature" to JsonPrimitive(alteredSignature)))
                val tamperedPassphrase = JsonObject(bootstrap.obj("Passphrase") +
                    ("MemberPassphrases" to JsonArray(listOf(tamperedMemberPacket))))
                refused(RemoteImportFailure.LOCAL_KEYS) {
                    open(context = JsonObject(bootstrap + ("Passphrase" to tamperedPassphrase)))
                }
                stage = "SETTINGS_CALENDAR"
                val foreignSettings = JsonObject(bootstrap.obj("CalendarSettings") +
                    ("CalendarID" to JsonPrimitive("foreign-calendar")))
                refused { open(context = JsonObject(bootstrap + ("CalendarSettings" to foreignSettings))) }
                stage = "MISSING_KEYS"
                refused(RemoteImportFailure.LOCAL_KEYS) { open(engine = ProtonEventDecryptor(crypto) { emptyList() }) }
                stage = "CANCELLATION"
                val cancelled = CancellationException("Synthetic cancellation")
                var propagated = false
                try { open(engine = ProtonEventDecryptor(crypto) { throw cancelled }) }
                catch (actual: CancellationException) { propagated = actual === cancelled }
                check(propagated)
                stage = "BASIS_RETAINED"
                check(basis.originalRow == row && basis.patchTitle(originalTitle).patchedPlaintext.encryptedShared == secret)
                "RESULT=SUCCESS"
            } finally { addressSecret.fill(0); calendarSecret.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { "RESULT=FAILURE STAGE=$stage" }
    }

    private suspend fun refused(reason: RemoteImportFailure = RemoteImportFailure.UNSUPPORTED, action: suspend () -> Unit) {
        var rejected = false
        try { action() }
        catch (failure: RemoteImportException) { rejected = failure.reason == reason && failure.cause == null }
        check(rejected)
    }

    private fun changedPart(row: JsonObject, index: Int, field: String, value: JsonElement?): JsonObject {
        val parts = row.array("SharedEvents").map { it as JsonObject }.toMutableList()
        val changed = parts[index].toMutableMap()
        if (value == null) changed.remove(field) else changed[field] = value
        parts[index] = JsonObject(changed)
        return JsonObject(row + ("SharedEvents" to JsonArray(parts)))
    }

    private fun icsPrefix(uid: String) = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Synthetic fixture//EN\r\n" +
        "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:20261003T090000Z\r\n"
    private fun ics(uid: String, properties: String) = icsPrefix(uid) + properties + "END:VEVENT\r\nEND:VCALENDAR\r\n"
}
