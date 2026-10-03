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
import me.proton.core.network.domain.session.SessionId
import me.proton.core.user.domain.entity.*

/** Native synthetic shared signatures only. No account, network, Provider or UPDATE payload. */
object ProtonSharedTitleCryptoProbe {
    suspend fun runBinary(): String = run(textSignatures = false)
    suspend fun runText(): String = run(textSignatures = true)

    private suspend fun run(textSignatures: Boolean): String = withContext(Dispatchers.IO) {
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
