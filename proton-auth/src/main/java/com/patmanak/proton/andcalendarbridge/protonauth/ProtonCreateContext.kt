// SPDX-License-Identifier: GPL-3.0-only
// Adapts Proton calendar member/key selection and passphrase verification; see PROVENANCE.md.
package com.patmanak.proton.andcalendarbridge.protonauth

import com.patmanak.proton.andcalendarbridge.sync.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import me.proton.core.crypto.common.context.CryptoContext
import me.proton.core.crypto.common.keystore.PlainByteArray
import me.proton.core.crypto.common.keystore.encrypt
import me.proton.core.key.domain.*
import me.proton.core.key.domain.entity.key.PrivateKey
import me.proton.core.user.domain.entity.UserAddress

internal fun createUnavailable(reason: CreatePreparationFailure): Nothing = throw CreatePreparationException(reason)

/** Local selection only; no keys or remote IDs in diagnostic representations. */
internal class ProtonCreateSelection(val member: JsonObject, val address: UserAddress,
    val signingKey: PrivateKey, val calendarKey: JsonObject, val passphrase: JsonObject, val packet: JsonObject) {
    override fun toString() = "ProtonCreateSelection(redacted)"
}

internal object ProtonCreateContextPolicy {
    fun select(scope: CalendarScope, metadata: JsonObject, bootstrap: JsonObject, addresses: List<UserAddress>): ProtonCreateSelection = try {
        if (metadata.string("ID") != scope.calendar.value || metadata.number("Type") != 0L)
            createUnavailable(CreatePreparationFailure.CONTEXT)
        if (addresses.any { it.userId.id != scope.account.value } ||
            addresses.map { it.addressId }.distinct().size != addresses.size) createUnavailable(CreatePreparationFailure.CONTEXT)
        val passphrase = bootstrap.obj("Passphrase")
        if (passphrase.string("CalendarID") != scope.calendar.value || passphrase.number("Flags") != 1L ||
            passphrase.string("ID").isBlank()) createUnavailable(CreatePreparationFailure.CONTEXT)
        val members = bootstrap.array("Members").map { it as? JsonObject ?: createUnavailable(CreatePreparationFailure.CONTEXT) }
        if (members.any { it.string("CalendarID") != scope.calendar.value || it.string("ID").isBlank() } ||
            members.map { it.string("ID") }.distinct().size != members.size) createUnavailable(CreatePreparationFailure.CONTEXT)
        val owned = members.mapNotNull { member ->
            val address = addresses.singleOrNull { it.addressId.id == member.optionalString("AddressID") } ?: return@mapNotNull null
            member to address
        }
        if (owned.size != 1) createUnavailable(CreatePreparationFailure.CONTEXT)
        val (member, address) = owned.single()
        val permissions = member.number("Permissions")
        if (member.number("Flags") != 1L || !address.enabled || !address.canSend ||
            permissions !in 0L..127L || permissions and 16L == 0L) createUnavailable(CreatePreparationFailure.PERMISSION)
        val signing = address.keys.map { it.privateKey }.filter { it.isPrimary && it.isActive && it.canVerify }
        if (signing.size != 1) createUnavailable(CreatePreparationFailure.LOCAL_KEYS)
        val keys = bootstrap.array("Keys").map { it as? JsonObject ?: createUnavailable(CreatePreparationFailure.CONTEXT) }
        if (keys.any { it.string("CalendarID") != scope.calendar.value || it.string("ID").isBlank() || it.number("Flags") !in listOf(0L, 1L, 3L) } ||
            keys.map { it.string("ID") }.distinct().size != keys.size) createUnavailable(CreatePreparationFailure.CONTEXT)
        val primary = keys.filter { it.number("Flags") == 3L }.singleOrNull() ?: createUnavailable(CreatePreparationFailure.LOCAL_KEYS)
        if (primary.string("PassphraseID") != passphrase.string("ID")) createUnavailable(CreatePreparationFailure.CONTEXT)
        val packets = passphrase.array("MemberPassphrases").map { it as? JsonObject ?: createUnavailable(CreatePreparationFailure.CONTEXT) }
        if (packets.any { it.string("MemberID").isBlank() } || packets.map { it.string("MemberID") }.distinct().size != packets.size)
            createUnavailable(CreatePreparationFailure.CONTEXT)
        val packet = packets.singleOrNull { it.string("MemberID") == member.string("ID") }
            ?: createUnavailable(CreatePreparationFailure.LOCAL_KEYS)
        ProtonCreateSelection(member, address, signing.single(), primary, passphrase, packet)
    } catch (failure: CreatePreparationException) { throw failure }
      catch (_: Exception) { createUnavailable(CreatePreparationFailure.CONTEXT) }
}

/** Verify the selected member's passphrase, then unlock its selected primary calendar key. */
internal class ProtonCreateContextResolver(private val crypto: CryptoContext) {
    fun prepare(scope: CalendarScope, metadata: JsonObject, bootstrap: JsonObject, addresses: List<UserAddress>, uid: String,
        draft: CalendarCreateDraft): ProtonPreparedCreateEvent {
        if (draft.scope != scope) createUnavailable(CreatePreparationFailure.CONTEXT)
        val selected = ProtonCreateContextPolicy.select(scope, metadata, bootstrap, addresses)
        try {
            GateCProtectedStorageGuard.requireAvailable(crypto.keyStoreCrypto)
            val secret = selected.address.useKeys(crypto) {
                val plain = decryptTextOrNull(selected.packet.string("Passphrase")) ?: createUnavailable(CreatePreparationFailure.LOCAL_KEYS)
                if (!publicKeyRing.keys.any { it.isActive && crypto.pgpCrypto.verifyText(plain, selected.packet.string("Signature"), it.key) })
                    createUnavailable(CreatePreparationFailure.LOCAL_KEYS)
                plain
            }
            val bytes = secret.toByteArray(Charsets.UTF_8)
            val protected = try { PlainByteArray(bytes).encrypt(crypto.keyStoreCrypto) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { createUnavailable(CreatePreparationFailure.STORAGE) }
                finally { bytes.fill(0) }
            val calendarKey = PrivateKey(selected.calendarKey.string("PrivateKey"), isPrimary = true, passphrase = protected)
            if (calendarKey.unlockOrNull(crypto)?.use { true } != true) createUnavailable(CreatePreparationFailure.LOCAL_KEYS)
            return ProtonSimpleEventEncryptor(crypto).prepare(uid, draft.timestamp, draft.content,
                selected.signingKey, calendarKey.publicKey(crypto))
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: CreatePreparationException) { throw failure }
          catch (failure: ProtonEventPreparationException) {
              createUnavailable(if (failure.reason == ProtonEventPreparationFailure.UNSUPPORTED_CONTENT)
                  CreatePreparationFailure.UNSUPPORTED else CreatePreparationFailure.LOCAL_KEYS)
          } catch (_: GateCProtectedStorageUnavailable) { createUnavailable(CreatePreparationFailure.STORAGE) }
          catch (_: Exception) { createUnavailable(CreatePreparationFailure.LOCAL_KEYS) }
    }
}
