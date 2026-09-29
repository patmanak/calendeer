// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Proton Calendar CacheCalendarPassphraseUseCase/TransformEventUseCase; see PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.crypto.common.keystore.encrypt
import me.proton.core.crypto.common.keystore.PlainByteArray
import me.proton.core.domain.entity.UserId
import me.proton.core.key.domain.*
import me.proton.core.key.domain.entity.key.PrivateKey
import me.proton.core.key.domain.entity.key.PrivateKeyRing
import me.proton.core.key.domain.entity.key.PublicKeyRing
import me.proton.core.key.domain.entity.keyholder.KeyHolderContext
import me.proton.core.user.domain.entity.UserAddress

/** Implemented by the protected local address DAO, never by UserManager's cache/network store. */
internal fun interface LocalEventAddresses {
    suspend fun get(user: UserId): List<UserAddress>
}

/** Keys and decrypted packets live only within one page. No calendar/account setup is invoked. */
internal class ProtonEventDecryptor(private val crypto: CryptoContext, private val localAddresses: LocalEventAddresses) : EventImportDecoder {
    override suspend fun decode(session: CatalogSession, calendar: String, bootstrap: JsonObject, rows: List<JsonObject>) =
        decodePage(session.userId, calendar, bootstrap, rows)

    /** Legacy native probe path must fail if any content was deferred. */
    suspend fun decrypt(user: UserId, calendar: String, bootstrap: JsonObject, rows: List<JsonObject>): List<RemoteChange.Upsert> {
        val result = decodePage(user, calendar, bootstrap, rows)
        if (result.deferred.isNotEmpty()) unsupportedEvents()
        return result.changes
    }
    suspend fun decodeCreated(session: CatalogSession, calendar: String, bootstrap: JsonObject, row: JsonObject,
        uid: String): RemoteChange.Upsert? {
        if (row.string("UID") != uid || row.array("SharedEvents").map { (it as JsonObject).number("Type") }.sorted() != listOf(2L, 3L) ||
            row.array("CalendarEvents").isNotEmpty() || row.array("AttendeesEvents").isNotEmpty()) return null
        return decodePage(session.userId, calendar, bootstrap, listOf(row), uid).changes.singleOrNull()
    }
    suspend fun decodePage(user: UserId, calendar: String, bootstrap: JsonObject, rows: List<JsonObject>, expectedUid: String? = null): EventDecodeResult {
        val addresses = localAddresses.get(user)
        if (addresses.isEmpty() || addresses.none { address -> address.keys.any { it.privateKey.isActive } }) missingLocalKeys()
        return try {
        val passphrase = bootstrap.obj("Passphrase")
        if (passphrase.string("CalendarID") != calendar || passphrase.number("Flags") != 1L) unsupportedEvents()
        val members = bootstrap.array("Members").map { it as? JsonObject ?: unsupportedEvents() }
        if (members.any { it.string("CalendarID") != calendar || it.string("ID").isBlank() } ||
            members.map { it.string("ID") }.distinct().size != members.size) unsupportedEvents()
        val memberPassphrases = passphrase.array("MemberPassphrases").map { it as? JsonObject ?: unsupportedEvents() }
        if (memberPassphrases.any { it.string("MemberID").isBlank() } ||
            memberPassphrases.map { it.string("MemberID") }.distinct().size != memberPassphrases.size) unsupportedEvents()
        val secret = members.firstNotNullOfOrNull { member ->
            val address = addresses.firstOrNull { it.addressId.id == member.optionalString("AddressID") } ?: return@firstNotNullOfOrNull null
            val packet = memberPassphrases.firstOrNull { it.string("MemberID") == member.string("ID") } ?: return@firstNotNullOfOrNull null
            address.useKeys(crypto) {
                val plain = decryptTextOrNull(packet.string("Passphrase")) ?: return@useKeys null
                if (publicKeyRing.keys.any { it.isActive && crypto.pgpCrypto.verifyText(plain, packet.string("Signature"), it.key) }) plain else null
            }
        } ?: missingLocalKeys()
        val secretBytes = secret.toByteArray(Charsets.UTF_8)
        val encryptedSecret = try { PlainByteArray(secretBytes).encrypt(crypto.keyStoreCrypto) } finally { secretBytes.fill(0) }
        val keys = bootstrap.array("Keys").map { it as? JsonObject ?: unsupportedEvents() }.filter {
            if (it.string("CalendarID") != calendar) unsupportedEvents()
            it.number("Flags") and 1L != 0L
        }.map {
            if (it.string("PassphraseID") != passphrase.string("ID")) unsupportedEvents()
            PrivateKey(it.string("PrivateKey"), it.number("Flags") == 3L, passphrase = encryptedSecret)
        }
        if (keys.isEmpty()) missingLocalKeys()
        if (keys.none { it.unlockOrNull(crypto)?.use { true } == true }) missingLocalKeys()
        KeyHolderContext(crypto, PrivateKeyRing(crypto, keys), PublicKeyRing(emptyList())).use { calendarKeys ->
            val pageBudget = EventDecodeBudget()
            val changes = mutableListOf<RemoteChange.Upsert>()
            val deferred = mutableListOf<DeferredEventContent>()
            rows.forEach { row ->
                // Identity is validated outside the per-event recovery boundary.
                if (row.string("CalendarID") != calendar || row.string("ID").isBlank()) unsupportedEvents()
                val eventId = EventId(row.string("ID"))
                val revision = row.revision(bootstrap)
                val eventBudget = EventTextBudget()
                try {
                val texts = mutableListOf<String>()
                for (field in listOf("SharedEvents", "CalendarEvents", "AttendeesEvents")) {
                    for (element in row.array(field)) {
                        val part = element as? JsonObject ?: unsupportedEvents()
                        val type = part.number("Type")
                        if (type !in 0..3) unsupportedEvents()
                        val plain = if (type and 1L == 0L) part.string("Data") else {
                            if (field != "CalendarEvents" && row.optionalString("AddressKeyPacket") != null) {
                                val address = addresses.firstOrNull { it.addressId.id == row.optionalString("AddressID") } ?: unsupportedEvents()
                                address.useKeys(crypto) { decryptPart(row.string("AddressKeyPacket"), part.string("Data")) }
                            } else calendarKeys.decryptPart(row.optionalString(if (field == "CalendarEvents") "CalendarKeyPacket" else "SharedKeyPacket")
                                ?: unsupportedEvents(), part.string("Data"))
                        }
                        eventBudget.consume(plain)
                        if (type and 2L != 0L && !verify(part, plain, addresses)) throw UnverifiedEventContent()
                        texts.add(plain)
                    }
                }
                val content = ProtonICalendar.parse(texts, row, bootstrap, expectedUid).copy(colorRgb = row.eventColorRgb())
                pageBudget.accept(eventBudget)
                changes.add(RemoteChange.Upsert(eventId, revision, content))
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: UnverifiedEventContent) {
                    deferred.add(DeferredEventContent(eventId, revision, DeferredEventReason.UNVERIFIED_CONTENT))
                } catch (failure: RemoteImportException) {
                    if (failure.reason != RemoteImportFailure.UNSUPPORTED) throw failure
                    deferred.add(DeferredEventContent(eventId, revision, DeferredEventReason.UNSUPPORTED_CONTENT))
                }
            }
            EventDecodeResult(changes, deferred)
        }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: RemoteImportException) { throw failure }
          catch (_: Exception) { unsupportedEvents() }
    }
    private fun missingLocalKeys(): Nothing = throw RemoteImportException(RemoteImportFailure.LOCAL_KEYS)
    private fun KeyHolderContext.decryptPart(packet: String, data: String): String {
        return try {
            val key = decryptSessionKeyOrNull(Base64.getDecoder().decode(packet)) ?: unsupportedEvents()
            val bytes = key.use { decryptDataOrNull(Base64.getDecoder().decode(data), it) } ?: unsupportedEvents()
            try {
                if (bytes.size > 1_048_576) unsupportedEvents()
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } finally { bytes.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { unsupportedEvents() }
    }
    private fun verify(part: JsonObject, plain: String, addresses: List<UserAddress>): Boolean {
        // External author pinning is intentionally unsupported until its contact/key trust path is qualified.
        val address = addresses.firstOrNull { it.email.equals(part.string("Author"), ignoreCase = true) } ?: return false
        val signature = part.optionalString("Signature") ?: return false
        return address.useKeys(crypto) { publicKeyRing.keys.any { it.verifyData(crypto, plain.toByteArray(Charsets.UTF_8), signature) } }
    }
}
