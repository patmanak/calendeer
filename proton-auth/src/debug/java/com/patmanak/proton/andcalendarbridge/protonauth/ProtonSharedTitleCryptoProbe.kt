// SPDX-License-Identifier: GPL-3.0-only
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.time.Instant
import java.time.ZoneOffset
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
    suspend fun runTimestampedBinary(): String = run(textSignatures = false, timestamped = true)
    suspend fun runTimestampedText(): String = run(textSignatures = true, timestamped = true)
    suspend fun runProtectedBinary(): String = run(textSignatures = false, timestamped = true, protectPreparation = true)
    suspend fun runProtectedText(): String = run(textSignatures = true, timestamped = true, protectPreparation = true)
    suspend fun runQualifiedBinary(): String = run(textSignatures = false, timestamped = true, protectPreparation = true, qualifyLocalBase = true)
    suspend fun runQualifiedText(): String = run(textSignatures = true, timestamped = true, protectPreparation = true, qualifyLocalBase = true)
    suspend fun runAuthenticatedBinary(): String = run(textSignatures = false, timestamped = true, protectPreparation = true, authenticateLocal = true)
    suspend fun runAuthenticatedText(): String = run(textSignatures = true, timestamped = true, protectPreparation = true, authenticateLocal = true)
    suspend fun runProtectedJournal(textSignatures: Boolean,
        journal: suspend (LocalEditPreparation) -> LocalEditPreparation): String =
        run(textSignatures = textSignatures, timestamped = true, protectPreparation = true, journal = journal)

    private suspend fun run(textSignatures: Boolean, reencrypted: Boolean = false,
        timestamped: Boolean = false, protectPreparation: Boolean = false,
        journal: (suspend (LocalEditPreparation) -> LocalEditPreparation)? = null,
        qualifyLocalBase: Boolean = false, authenticateLocal: Boolean = false): String = withContext(Dispatchers.IO) {
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
                // ImportStore catalog fixtures start at generation zero; legacy crypto fixtures retain one.
                val scope = CalendarScope(AccountId(user.id), CalendarId(calendar), if (journal != null) 0 else 1)
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
                val dates = if (timestamped) "CREATED:20261002T090000Z\r\nLAST-MODIFIED:20261003T090000Z\r\nSEQUENCE:0007\r\n" else ""
                val clear = ics(uid, dates + "DTSTART:20261003T100000Z\r\nDTEND:20261003T110000Z\r\n" +
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
                    put("CalendarEvents", if (timestamped) JsonArray(emptyList()) else Json.parseToJsonElement("""[{"Type":99,"Data":"opaque-calendar","Extra":[1,null,"x"]}]"""))
                    put("AttendeesEvents", if (timestamped) JsonArray(emptyList()) else Json.parseToJsonElement("""[{"Type":99,"Data":"opaque-attendee","Extra":{"Flag":true}}]"""))
                    put("Notifications", if (timestamped) JsonArray(emptyList()) else Json.parseToJsonElement("""[{"Type":1,"Trigger":"-PT10M","Opaque":7}]"""))
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

                if (timestamped) return@withContext runTimestamped(crypto, addressSecret, calendarSecret,
                    addressArmored, calendarArmored, addressKey, address, scope, session, bootstrap, row,
                    originalTitle, newTitle, clear, prefix, suffix, patched, alteredSignature, decoder, protectPreparation, journal, qualifyLocalBase, authenticateLocal)
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

    private suspend fun runTimestamped(crypto: AndroidCryptoContext, addressSecret: ByteArray,
        calendarSecret: ByteArray, addressArmored: String, calendarArmored: String, addressKey: PrivateKey,
        address: UserAddress, scope: CalendarScope, session: CatalogSession, bootstrap: JsonObject, row: JsonObject,
        originalTitle: String, newTitle: String, clear: String, prefix: String, suffix: String, patched: String,
        alteredSignature: String, decoder: ProtonEventDecryptor, protectPreparation: Boolean,
        journal: (suspend (LocalEditPreparation) -> LocalEditPreparation)?, qualifyLocalBase: Boolean, authenticateLocal: Boolean): String {
        var stage = "TIMESTAMPED_START"
        try {
            val pgp = crypto.pgpCrypto
            val calendar = scope.calendar.value
            val uid = row.string("UID")
            val eventId = EventId(row.string("ID"))
            val addressId = address.addressId
            val email = address.email
            suspend fun open(candidate: JsonObject, expectedTitle: String): ProtonSharedTitleBasis =
                decoder.openSharedTitle(session, scope, eventId, candidate.revision(bootstrap), uid,
                    expectedTitle, bootstrap, candidate)
            val metadata = buildJsonObject { put("ID", calendar); put("Type", 0) }
            val advanced = Instant.parse("2026-10-03T09:30:00Z")
            val sameSecond = Instant.parse("2026-10-03T09:00:00Z")
            var savedPreparation: ProtonProtectedTitlePreparation? = null
            var savedBinding: ProtonTitlePreparationBinding? = null
            var savedLocalPreparation: LocalEditPreparation? = null
            var journalUsed = false
            var qualificationUsed = false
            var authenticationUsed = false
            suspend fun prepare(candidate: JsonObject = row, context: JsonObject = bootstrap,
                candidateMetadata: JsonObject = metadata, candidateScope: CalendarScope = scope,
                expectedId: EventId = eventId, expectedRevision: Revision = candidate.revision(context),
                expectedUid: String = uid, expectedTitle: String = originalTitle,
                replacement: String = newTitle, timestamp: Instant = advanced,
                engine: ProtonEventDecryptor = decoder): ProtonTimestampedSharedTitle {
                if (!protectPreparation) return engine.prepareSharedTitleAt(session, candidateScope, expectedId, expectedRevision,
                    expectedUid, expectedTitle, replacement, candidateMetadata, context, candidate, timestamp)
                if (authenticateLocal && !authenticationUsed) {
                    authenticationUsed = true
                    stage = "AUTHENTICATED_LOCAL_PREPARATION"
                    val recovered = checkAuthenticatedLocalPreparation(crypto, decoder, address, session, scope,
                        row, bootstrap, metadata, replacement, timestamp)
                    savedLocalPreparation = recovered
                    return ProtonTitlePreparationCodec(ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto)).openLocal(recovered)
                }
                if (qualifyLocalBase && !qualificationUsed) {
                    qualificationUsed = true
                    stage = "LOCAL_BASE_QUALIFICATION"
                    val recovered = checkLocalBaseQualification(decoder, address, session, scope, row, bootstrap,
                        metadata, replacement, timestamp)
                    savedLocalPreparation = recovered
                    return ProtonTitlePreparationCodec(ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto))
                        .openLocal(recovered)
                }
                if (journal != null && !journalUsed) {
                    journalUsed = true
                    stage = "JOURNAL_DECODE"
                    val base = decoder.decode(session, calendar, bootstrap, listOf(row)).changes.single()
                    stage = "JOURNAL_INTENT"
                    val intent = LocalEditIntent(MutationId("synthetic-title-edit"), scope, EVENT_FORMAT,
                        base, base.content.copy(title = replacement))
                    stage = "JOURNAL_SEAL"
                    val sealed = engine.prepareAndSealLocalTitleEdit(session, intent, expectedUid, timestamp,
                        candidateMetadata, context, candidate, ProtonEventPayloadProtector(crypto.keyStoreCrypto))
                    stage = "JOURNAL_SQL"
                    val recovered = journal(sealed)
                    stage = "JOURNAL_READBACK"
                    check(recovered == sealed)
                    check(recovered.format == LOCAL_TITLE_PREPARATION_FORMAT)
                    check(!recovered.protectedPayload.contains(expectedUid) &&
                        !recovered.protectedPayload.contains(expectedTitle) && !recovered.protectedPayload.contains(replacement))
                    savedLocalPreparation = recovered
                    // Recovery receives only the retained record, with no external UID or title binding.
                    return ProtonTitlePreparationCodec(ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto))
                        .openLocal(recovered)
                }
                val binding = ProtonTitlePreparationBinding(candidateScope, expectedId, expectedRevision,
                    expectedUid, expectedTitle, replacement, timestamp)
                val receipt = engine.prepareAndSealTitleAt(session, binding, candidateMetadata, context,
                    candidate, ProtonEventPayloadProtector(crypto.keyStoreCrypto))
                check(receipt.scope == candidateScope && receipt.eventId == expectedId && receipt.revision == expectedRevision &&
                    receipt.format == TITLE_PREPARATION_FORMAT)
                check(!receipt.protectedPayload.contains(expectedUid) && !receipt.protectedPayload.contains(expectedTitle) &&
                    !receipt.protectedPayload.contains(replacement))
                check(receipt.toString() == "ProtonProtectedTitlePreparation(redacted)")
                check(binding.toString() == "ProtonTitlePreparationBinding(redacted)")
                savedPreparation = receipt
                savedBinding = binding
                // New instances recover the saved protected value; this does not simulate process death.
                return ProtonTitlePreparationCodec(ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto))
                    .open(receipt, binding)
            }
            val originalParts = row.array("SharedEvents").map { it as JsonObject }
            val originalClear = originalParts.single { it.number("Type") == 2L }
            val originalSecret = originalParts.single { it.number("Type") == 3L }
            val expectedClear = clear.replace("DTSTAMP:20261003T090000Z", "DTSTAMP:20261003T093000Z")
                .replace("LAST-MODIFIED:20261003T090000Z", "LAST-MODIFIED:20261003T093000Z")
            val expectedSecret = patched.replace("DTSTAMP:20261003T090000Z", "DTSTAMP:20261003T093000Z")
            stage = "TIMESTAMPED_CLEAR_DATES"
            val prepared = prepare()
            if (protectPreparation) {
                stage = "PROTECTED_RECOVERY_BINDINGS"
                val local = savedLocalPreparation
                if (local != null) checkLocalRecovery(local, prepared)
                else checkProtectedRecovery(checkNotNull(savedPreparation), checkNotNull(savedBinding), prepared)
                stage = "TIMESTAMPED_CLEAR_DATES"
            }
            check(prepared.clearSharedPart.string("Data") == expectedClear)
            check(prepared.changed && prepared.originalRow == row)
            check(prepared.originalRow.array("SharedEvents") == row.array("SharedEvents"))
            check(prepared.originalRow.string("SharedKeyPacket") == row.string("SharedKeyPacket"))
            for ((before, after) in listOf(originalClear to prepared.clearSharedPart, originalSecret to prepared.encryptedSharedPart)) {
                check(before.filterKeys { it != "Data" && it != "Signature" } ==
                    after.filterKeys { it != "Data" && it != "Signature" })
                check(after.string("Data") != before.string("Data") && after.string("Signature") != before.string("Signature"))
            }
            check(expectedClear.contains("CREATED:20261002T090000Z\r\n") && expectedClear.contains("SEQUENCE:0007\r\n"))
            check(!prepared.toString().contains(uid) && !prepared.toString().contains(newTitle))
            check(prepared.originalRow !== prepared.originalRow && prepared.clearSharedPart !== prepared.clearSharedPart &&
                prepared.encryptedSharedPart !== prepared.encryptedSharedPart)
            stage = "TIMESTAMPED_ORIGINAL_PACKET"
            val calendarKey = PrivateKey(calendarArmored, true,
                passphrase = PlainByteArray(calendarSecret).encrypt(crypto.keyStoreCrypto))
            fun decrypt(part: JsonObject): String = KeyHolderContext(crypto, PrivateKeyRing(crypto, listOf(calendarKey)),
                PublicKeyRing(emptyList())).use { keys ->
                val retainedKey = keys.decryptSessionKeyOrNull(Base64.getDecoder().decode(row.string("SharedKeyPacket")))
                    ?: error("Synthetic packet unavailable")
                val bytes = retainedKey.use { key ->
                    keys.decryptDataOrNull(Base64.getDecoder().decode(part.string("Data")), key)
                        ?: error("Synthetic ciphertext unavailable")
                }
                try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) }
            }
            val reopened = decrypt(prepared.encryptedSharedPart)
            check(reopened == expectedSecret)
            check(reopened.startsWith(prefix.replace("DTSTAMP:20261003T090000Z", "DTSTAMP:20261003T093000Z")) && reopened.endsWith(suffix))
            check(biweekly.Biweekly.parse(reopened).first().events.single().summary.value.replace("\r\n", "\n") == newTitle)
            stage = "TIMESTAMPED_SIGNATURES"
            val publicKey = addressKey.publicKey(crypto)
            for ((plain, before, after) in listOf(Triple(expectedClear, originalClear, prepared.clearSharedPart),
                Triple(reopened, originalSecret, prepared.encryptedSharedPart))) {
                check(publicKey.verifyText(crypto, plain, after.string("Signature")))
                check(!publicKey.verifyData(crypto, plain.toByteArray(Charsets.UTF_8), before.string("Signature")) &&
                    !publicKey.verifyText(crypto, plain, before.string("Signature")))
            }
            stage = "TIMESTAMPED_NATIVE_READBACK"
            // Local verification fixture only: no transport/request construction or upload.
            val readbackRow = JsonObject(row + ("SharedEvents" to JsonArray(listOf(prepared.clearSharedPart, prepared.encryptedSharedPart))))
            val readback = open(candidate = readbackRow, expectedTitle = newTitle)
            check(readback.originalPlaintext.clearShared == expectedClear && readback.originalPlaintext.encryptedShared == reopened)
            val decoded = decoder.decode(session, calendar, bootstrap, listOf(readbackRow))
            check(decoded.deferred.isEmpty() && decoded.changes.single().eventId == eventId)
            val imported = decoded.changes.single().content
            check(imported.title == newTitle && imported.time == EventTime.Timed(
                Instant.parse("2026-10-03T10:00:00Z"), Instant.parse("2026-10-03T11:00:00Z"), ZoneOffset.UTC))
            check(imported.details == EventDetails() && imported.recurrence == null)
            val originalDecoded = decoder.decode(session, calendar, bootstrap, listOf(row))
            check(originalDecoded.deferred.isEmpty() && imported == originalDecoded.changes.single().content.copy(title = newTitle))
            stage = "TIMESTAMPED_SAME_SECOND"
            val same = prepare(timestamp = sameSecond)
            check(same.changed && same.clearSharedPart == originalClear && decrypt(same.encryptedSharedPart) == patched)
            check(publicKey.verifyText(crypto, patched, same.encryptedSharedPart.string("Signature")))
            stage = "TIMESTAMPED_NO_OP"
            val unchanged = prepare(replacement = originalTitle)
            check(!unchanged.changed && unchanged.originalRow == row && unchanged.clearSharedPart == originalClear &&
                unchanged.encryptedSharedPart == originalSecret)
            stage = "TIMESTAMPED_AUTHOR_CASE"
            val authorCaseRow = changedPart(changedPart(row, 0, "Author", JsonPrimitive(email.uppercase())),
                1, "Author", JsonPrimitive(email.uppercase()))
            val authorCase = prepare(candidate = authorCaseRow)
            check(authorCase.clearSharedPart.string("Author") == email.uppercase() &&
                authorCase.encryptedSharedPart.string("Author") == email.uppercase())
            stage = "TIMESTAMPED_DISTINCT_CLEAR_AUTHOR"
            val otherAddress = address.copy(addressId = AddressId("synthetic-clear-author"), email = "synthetic-clear@example.invalid")
            val distinctAuthorRow = changedPart(row, 0, "Author", JsonPrimitive(otherAddress.email))
            val distinctDecoder = ProtonEventDecryptor(crypto) { listOf(address, otherAddress) }
            refused { prepare(candidate = distinctAuthorRow, engine = distinctDecoder) }
            val retainedClear = prepare(candidate = distinctAuthorRow, timestamp = sameSecond, engine = distinctDecoder)
            check(retainedClear.changed && retainedClear.clearSharedPart == distinctAuthorRow.array("SharedEvents")[0])
            check(decrypt(retainedClear.encryptedSharedPart) == patched)
            stage = "TIMESTAMPED_FOLDED_SAME_SECOND"
            // Identical instants can still produce changed plaintext when a selected line was folded.
            val foldedClear = clear.replace("DTSTAMP:20261003T090000Z\r\n", "DTSTAMP:20261003T\r\n 090000Z\r\n")
            val foldedSignature = addressKey.signText(crypto, foldedClear)
            val foldedRow = changedPart(changedPart(row, 0, "Data", JsonPrimitive(foldedClear)),
                0, "Signature", JsonPrimitive(foldedSignature))
            val unfolded = prepare(candidate = foldedRow, timestamp = sameSecond)
            check(unfolded.changed && unfolded.clearSharedPart.string("Data") == clear && decrypt(unfolded.encryptedSharedPart) == patched)
            check(unfolded.clearSharedPart.string("Signature") != foldedSignature &&
                publicKey.verifyText(crypto, clear, unfolded.clearSharedPart.string("Signature")))
            check(!publicKey.verifyText(crypto, clear, foldedSignature))
            val foldedDistinctAuthor = changedPart(foldedRow, 0, "Author", JsonPrimitive(otherAddress.email))
            refused { prepare(candidate = foldedDistinctAuthor, timestamp = sameSecond, engine = distinctDecoder) }
            stage = "TIMESTAMPED_PERSONAL_PARTS"
            for (field in listOf("CalendarEvents", "AttendeesEvents")) {
                for (candidate in listOf(JsonObject(row - field), JsonObject(row + (field to JsonNull)),
                    JsonObject(row + (field to JsonArray(listOf(buildJsonObject { put("Type", 99); put("Data", "opaque") })))))) {
                    refused { prepare(candidate = candidate) }
                    refused { prepare(candidate = candidate, replacement = originalTitle) }
                }
            }
            stage = "TIMESTAMPED_TIME_POLICY"
            refused { prepare(timestamp = sameSecond.minusSeconds(1)) }
            refused { prepare(timestamp = advanced.plusNanos(1)) }
            refused { prepare(timestamp = advanced.plusNanos(1), replacement = originalTitle) }
            stage = "TIMESTAMPED_BINDINGS"
            refused { prepare(expectedId = EventId("foreign-event")) }
            refused { prepare(expectedRevision = Revision("foreign-revision")) }
            refused { prepare(expectedUid = "foreign@calendeer.invalid") }
            refused { prepare(expectedTitle = "Stale title") }
            refused { prepare(candidateScope = scope.copy(account = AccountId("foreign-account"))) }
            refused { prepare(candidateScope = scope.copy(calendar = CalendarId("foreign-calendar"))) }
            for (index in originalParts.indices) refused { prepare(candidate = changedPart(row, index, "Signature", JsonPrimitive(alteredSignature))) }
            refused { prepare(candidateMetadata = JsonObject(metadata + ("Type" to JsonPrimitive(1)))) }
            refused { prepare(candidateMetadata = JsonObject(metadata + ("ID" to JsonPrimitive("foreign-calendar")))) }
            val member = bootstrap.array("Members").single() as JsonObject
            for ((field, value) in listOf("Flags" to 0, "Permissions" to 0)) {
                val blocked = JsonObject(bootstrap + ("Members" to JsonArray(listOf(JsonObject(member + (field to JsonPrimitive(value)))))))
                refused { prepare(context = blocked) }
                refused { prepare(context = blocked, replacement = originalTitle) }
            }
            stage = "TIMESTAMPED_KEYS"
            refused(RemoteImportFailure.LOCAL_KEYS) { prepare(engine = ProtonEventDecryptor(crypto) { emptyList() }) }
            val otherArmored = pgp.generateNewPrivateKey("synthetic-signer", "example.invalid", addressSecret)
            val unavailablePrimary = PrivateKey(otherArmored, true,
                passphrase = PlainByteArray("synthetic-wrong-passphrase".toByteArray()).encrypt(crypto.keyStoreCrypto))
            val secondary = PrivateKey(addressArmored, false,
                passphrase = PlainByteArray(addressSecret).encrypt(crypto.keyStoreCrypto))
            val partlyAvailable = address.copy(keys = listOf(
                UserAddressKey(addressId, 1, 3, active = true, keyId = KeyId("synthetic-unavailable-primary"), privateKey = unavailablePrimary),
                UserAddressKey(addressId, 2, 1, active = true, keyId = KeyId("synthetic-available-secondary"), privateKey = secondary)))
            val partlyAvailableDecoder = ProtonEventDecryptor(crypto) { listOf(partlyAvailable) }
            refused(RemoteImportFailure.LOCAL_KEYS) { prepare(engine = partlyAvailableDecoder) }
            val signingFree = prepare(replacement = originalTitle, engine = partlyAvailableDecoder)
            check(!signingFree.changed && signingFree.clearSharedPart == originalClear && signingFree.encryptedSharedPart == originalSecret)
            stage = "TIMESTAMPED_FROZEN_INPUTS"
            val mutableMetadata = metadata.toMutableMap()
            val mutableOpaque = row.obj("OpaqueMetadata").toMutableMap()
            val mutableParts = row.array("SharedEvents").toMutableList()
            val mutableRow = row.toMutableMap().apply { put("OpaqueMetadata", JsonObject(mutableOpaque)); put("SharedEvents", JsonArray(mutableParts)) }
            val mutableMembers = bootstrap.array("Members").toMutableList()
            val mutableBootstrap = bootstrap.toMutableMap().apply { put("Members", JsonArray(mutableMembers)) }
            var keyReads = 0
            val mutatingDecoder = ProtonEventDecryptor(crypto) {
                keyReads++; mutableMetadata.clear(); mutableOpaque.clear(); mutableParts.clear(); mutableMembers.clear(); mutableBootstrap.clear()
                listOf(address)
            }
            val frozen = prepare(candidate = JsonObject(mutableRow), context = JsonObject(mutableBootstrap),
                candidateMetadata = JsonObject(mutableMetadata), expectedRevision = row.revision(bootstrap), engine = mutatingDecoder)
            check(keyReads == 1 && frozen.changed && frozen.originalRow == row && frozen.clearSharedPart.string("Data") == expectedClear)
            val frozenSecret = frozen.encryptedSharedPart
            mutableRow.clear()
            check(frozen.originalRow == row && frozen.encryptedSharedPart == frozenSecret)
            stage = "TIMESTAMPED_CANCELLATION"
            val cancelled = CancellationException("Synthetic cancellation")
            var propagated = false
            try { prepare(engine = ProtonEventDecryptor(crypto) { throw cancelled }) }
            catch (actual: CancellationException) { propagated = actual === cancelled }
            check(propagated)
            return "RESULT=SUCCESS"
            } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { return "RESULT=FAILURE STAGE=$stage" }
    }

    private suspend fun checkAuthenticatedLocalPreparation(crypto: AndroidCryptoContext, decoder: ProtonEventDecryptor,
        address: UserAddress, session: CatalogSession, scope: CalendarScope, row: JsonObject, bootstrap: JsonObject,
        metadata: JsonObject, title: String, timestamp: Instant): LocalEditPreparation {
        val base = decoder.decode(session, scope.calendar.value, bootstrap, listOf(row)).changes.single()
        val intent = LocalEditIntent(MutationId("synthetic-authenticated-title"), scope, EVENT_FORMAT, base, base.content.copy(title = title))
        var seals = 0; var addressReads = 0
        val steps = mutableListOf<String>()
        val delegate = ProtonEventPayloadProtector(crypto.keyStoreCrypto)
        val protector = object : EventPayloadProtector {
            override fun seal(plain: String): String { steps += "seal"; seals++; return delegate.seal(plain) }
            override fun open(protected: String) = delegate.open(protected)
        }
        fun port(context: JsonObject = bootstrap, original: JsonObject = row) = ProtonLocalTitlePreparationAdapter(
            { session }, { check(it == session); steps += "refresh" },
            CreateCalendarMetadataRemote { actual, calendar -> check(actual == session && calendar == scope.calendar.value); steps += "metadata"; metadata },
            object : EventImportRemote {
                override suspend fun page(session: CatalogSession, calendar: String, window: EventWindow): EncryptedEventPage = error("No pagination")
                override suspend fun bootstrap(session: CatalogSession, calendar: String): JsonObject { steps += "bootstrap"; return context }
            }, LocalTitleEventRemote { actual, target, id ->
                check(actual == session && target == scope && id == base.eventId); steps += "event"; original
            }, LocalEventAddresses { check(it == session.userId); steps += "addresses"; addressReads++; listOf(address) },
            localTitleCryptoPreparation(crypto, protector))
        suspend fun denied(reason: LocalEditPreparationFailure, action: suspend () -> Unit) {
            var refused = false
            try { action() } catch (actual: LocalEditPreparationException) { refused = actual.reason == reason && actual.cause == null }
            check(refused && seals == 0)
        }
        val member = bootstrap.array("Members").single() as JsonObject
        val readOnly = JsonObject(bootstrap + ("Members" to JsonArray(listOf(JsonObject(member + ("Permissions" to JsonPrimitive(2)))))))
        denied(LocalEditPreparationFailure.PERMISSION) { port(readOnly).prepareLocalTitle(
            intent.copy(base = base.copy(revision = row.revision(readOnly))), timestamp) }
        val changed = JsonObject(row + ("ModifyTime" to JsonPrimitive(2)))
        denied(LocalEditPreparationFailure.CONFLICT) { port(original = changed).prepareLocalTitle(intent, timestamp) }
        val wrong = base.content.copy(description = "Different synthetic observation")
        denied(LocalEditPreparationFailure.UNSUPPORTED) { port().prepareLocalTitle(
            intent.copy(base = base.copy(content = wrong), content = wrong.copy(title = title)), timestamp) }
        steps.clear(); addressReads = 0
        val result = port().prepareLocalTitle(intent, timestamp)
        check(steps == listOf("refresh", "metadata", "bootstrap", "event", "addresses", "seal"))
        check(seals == 1 && addressReads == 1 && result.intent == intent && result.timestamp == timestamp)
        return result
    }

    private suspend fun checkLocalBaseQualification(decoder: ProtonEventDecryptor, address: UserAddress,
        session: CatalogSession, scope: CalendarScope, row: JsonObject, bootstrap: JsonObject,
        metadata: JsonObject, title: String, timestamp: Instant): LocalEditPreparation {
        val source = decoder.decode(session, scope.calendar.value, bootstrap, listOf(row)).changes.single()
        fun intent(content: EventContent = source.content, format: AdapterFormat = EVENT_FORMAT) =
            LocalEditIntent(MutationId("synthetic-qualified-edit"), scope, format, source.copy(content = content), content.copy(title = title))
        val delegate = ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto)
        var sealed = 0
        val protector = object : EventPayloadProtector {
            override fun seal(plain: String): String { sealed++; return delegate.seal(plain) }
            override fun open(protected: String) = delegate.open(protected)
        }
        val timed = source.content.time as EventTime.Timed
        val alternatives = listOf(
            source.content.copy(description = "Different synthetic description"),
            source.content.copy(location = "Different synthetic location"),
            source.content.copy(colorRgb = 0x6849D3), source.content.copy(details = null),
            source.content.copy(time = timed.copy(start = timed.start.plusSeconds(1))),
            source.content.copy(time = timed.copy(end = timed.end.plusSeconds(1))),
            source.content.copy(time = timed.copy(start = timed.start.plusNanos(1))),
            source.content.copy(time = timed.copy(zone = java.time.ZoneId.of("UTC"))),
            source.content.copy(time = EventTime.AllDay(java.time.LocalDate.of(2026, 10, 3), java.time.LocalDate.of(2026, 10, 4))))
        for (wrong in alternatives) {
            refused { decoder.prepareAndSealLocalTitleEdit(session, intent(wrong), row.string("UID"), timestamp,
                metadata, bootstrap, row, protector) }
            check(sealed == 0)
        }
        refused { decoder.prepareAndSealLocalTitleEdit(session, intent(format = EVENT_FORMAT.copy(version = 2)),
            row.string("UID"), timestamp, metadata, bootstrap, row, protector) }
        check(sealed == 0)

        // Valid shared signatures alone cannot qualify an unsupported complete import.
        val malformed = JsonObject(row + ("Notifications" to JsonPrimitive("unsupported")))
        val malformedBase = source.copy(revision = malformed.revision(bootstrap))
        decoder.openSharedTitle(session, scope, malformedBase.eventId, malformedBase.revision,
            row.string("UID"), source.content.title, bootstrap, malformed)
        val deferred = decoder.decode(session, scope.calendar.value, bootstrap, listOf(malformed))
        check(deferred.changes.isEmpty() && deferred.deferred.size == 1)
        refused { decoder.prepareAndSealLocalTitleEdit(session, intent().copy(base = malformedBase),
            row.string("UID"), timestamp, metadata, bootstrap, malformed, protector) }
        check(sealed == 0)

        // The same locally available addresses/keys must qualify the base and sign, with one DAO read.
        val mutableRow = row.toMutableMap()
        val mutableSettings = bootstrap.obj("CalendarSettings").toMutableMap()
        val mutableMetadata = metadata.toMutableMap()
        val reminders = mutableListOf<EventReminder>()
        val mutableIntent = intent(source.content.copy(details = EventDetails(reminders = reminders)))
        val expected = LocalEditPreparation.freezeIntent(mutableIntent)
        var reads = 0
        val changing = ProtonEventDecryptor(AndroidCryptoContext()) {
            reads++; check(it == session.userId)
            mutableRow["ModifyTime"] = JsonPrimitive(2)
            mutableSettings["CalendarID"] = JsonPrimitive("changed-after-suspension")
            mutableMetadata["Type"] = JsonPrimitive(1)
            reminders.add(EventReminder(10))
            listOf(address)
        }
        val recovered = changing.prepareAndSealLocalTitleEdit(session, mutableIntent, row.string("UID"), timestamp,
            JsonObject(mutableMetadata), JsonObject(bootstrap + ("CalendarSettings" to JsonObject(mutableSettings))),
            JsonObject(mutableRow), protector)
        check(reads == 1 && sealed == 1 && recovered.intent == expected)
        return recovered
    }

    private fun checkLocalRecovery(record: LocalEditPreparation, expected: ProtonTimestampedSharedTitle) {
        val codec = ProtonTitlePreparationCodec(ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto))
        val recovered = codec.openLocal(record)
        check(recovered.originalRow == expected.originalRow && recovered.clearSharedPart == expected.clearSharedPart &&
            recovered.encryptedSharedPart == expected.encryptedSharedPart && recovered.changed == expected.changed)
        fun rejected(reason: ProtonTitlePreparationFailure, action: () -> Unit) {
            var refused = false
            try { action() }
            catch (actual: ProtonTitlePreparationException) { refused = actual.reason == reason && actual.cause == null }
            check(refused)
        }
        for (wrong in listOf(
            record.copy(intent = record.intent.copy(id = MutationId("different-synthetic-edit"))),
            record.copy(intent = record.intent.copy(content = record.intent.content.copy(title = "Different target"))),
            record.copy(timestamp = record.timestamp.plusSeconds(1)),
            record.copy(format = TITLE_PREPARATION_FORMAT),
            record.copy(format = record.format.copy(version = record.format.version + 1)))) {
            rejected(ProtonTitlePreparationFailure.UNSUPPORTED) { codec.openLocal(wrong) }
        }
        val damaged = record.protectedPayload.toCharArray()
        val index = damaged.size / 2
        damaged[index] = if (damaged[index] == 'A') 'B' else 'A'
        rejected(ProtonTitlePreparationFailure.STORAGE) { codec.openLocal(record.copy(protectedPayload = String(damaged))) }
    }

    private fun checkProtectedRecovery(record: ProtonProtectedTitlePreparation, binding: ProtonTitlePreparationBinding,
        expected: ProtonTimestampedSharedTitle) {
        val codec = ProtonTitlePreparationCodec(ProtonEventPayloadProtector(AndroidCryptoContext().keyStoreCrypto))
        val reopened = codec.open(record, binding)
        check(reopened.originalRow == expected.originalRow && reopened.clearSharedPart == expected.clearSharedPart &&
            reopened.encryptedSharedPart == expected.encryptedSharedPart && reopened.changed == expected.changed)
        fun rejected(reason: ProtonTitlePreparationFailure, action: () -> Unit) {
            var refused = false
            try { action() }
            catch (actual: ProtonTitlePreparationException) { refused = actual.reason == reason && actual.cause == null }
            check(refused)
        }
        val wrongTitle = ProtonTitlePreparationBinding(binding.scope, binding.eventId, binding.revision, binding.uid,
            binding.expectedTitle, "Different synthetic target", binding.timestamp)
        rejected(ProtonTitlePreparationFailure.UNSUPPORTED) { codec.open(record, wrongTitle) }
        rejected(ProtonTitlePreparationFailure.UNSUPPORTED) {
            codec.open(ProtonProtectedTitlePreparation(record.scope.copy(generation = record.scope.generation + 1),
                record.eventId, record.revision, record.format, record.protectedPayload), binding)
        }
        rejected(ProtonTitlePreparationFailure.UNSUPPORTED) {
            codec.open(ProtonProtectedTitlePreparation(record.scope, record.eventId, record.revision,
                AdapterFormat(record.format.id, record.format.version + 1), record.protectedPayload), binding)
        }
        val damaged = record.protectedPayload.toCharArray()
        val index = damaged.size / 2
        damaged[index] = if (damaged[index] == 'A') 'B' else 'A'
        rejected(ProtonTitlePreparationFailure.STORAGE) {
            codec.open(ProtonProtectedTitlePreparation(record.scope, record.eventId, record.revision, record.format,
                String(damaged)), binding)
        }
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
